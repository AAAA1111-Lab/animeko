/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * Use of this source code is governed by the Apache License version 2 license, which can be found at the following link.
 *
 * https://github.com/open-ani/mediamp/blob/main/LICENSE
 */

#include "flac_decode.h"

#include <cstring>
#include <vector>

#include "thirdparty/dr_flac.h"

namespace mediamp_flac {
namespace {

constexpr size_t kStreamInfoSize = 34;
constexpr size_t kMetadataHeaderSize = 4;
constexpr size_t kFlacSignatureSize = 4;
constexpr size_t kWrappedHeaderSize = kFlacSignatureSize + kMetadataHeaderSize + kStreamInfoSize;

// Must match FlacDecoderNative.packStreamInfo in Kotlin.
constexpr int kSampleRateShift = 44;
constexpr int kChannelsShift = 41;
constexpr int kBitsPerSampleShift = 36;

/**
 * Builds the bytes that precede one FLAC frame.
 *
 * Only the bare 34-byte STREAMINFO body is accepted. MatroskaExtractor forwards the Matroska
 * `A_FLAC` CodecPrivate unchanged and FlacExtractor consumes the signature and metadata block
 * header itself, so that is the only shape that reaches a decoder. Rejecting everything else
 * turns an unexpected configuration into a clear failure instead of feeding dr_flac garbage.
 */
bool buildPrologue(const uint8_t *streamInfo, size_t streamInfoSize, std::vector<uint8_t> &out) {
    if (streamInfo == nullptr || streamInfoSize != kStreamInfoSize) {
        return false;
    }

    out.resize(kWrappedHeaderSize);
    std::memcpy(out.data(), "fLaC", kFlacSignatureSize);
    // Metadata block header: last-block flag set, type 0 (STREAMINFO), 24-bit big-endian length.
    out[kFlacSignatureSize] = 0x80;
    out[kFlacSignatureSize + 1] = 0x00;
    out[kFlacSignatureSize + 2] = 0x00;
    out[kFlacSignatureSize + 3] = static_cast<uint8_t>(kStreamInfoSize);
    std::memcpy(out.data() + kFlacSignatureSize + kMetadataHeaderSize, streamInfo, kStreamInfoSize);
    return true;
}

uint64_t readBigEndian(const uint8_t *bytes, size_t count) {
    uint64_t value = 0;
    for (size_t index = 0; index < count; ++index) {
        value = (value << 8) | bytes[index];
    }
    return value;
}

}  // namespace

const char *describe(DecodeStatus status) {
    switch (status) {
        case DecodeStatus::Ok: return "ok";
        case DecodeStatus::BadStreamInfoSize: return "stream info is not 34 bytes";
        case DecodeStatus::BadStreamInfoContent: return "stream info describes an unsupported stream";
        case DecodeStatus::EmptyFrame: return "container sample is empty";
        case DecodeStatus::OpenFailed: return "dr_flac rejected the synthesised stream";
        case DecodeStatus::NoSamples: return "dr_flac produced no PCM";
        case DecodeStatus::OutputTooSmall: return "output buffer is too small";
    }
    return "unknown";
}

StreamInfo parseStreamInfo(const uint8_t *streamInfo, size_t streamInfoSize) {
    StreamInfo info{};
    if (streamInfo == nullptr || streamInfoSize != kStreamInfoSize) {
        return info;
    }
    info.minBlockSize = static_cast<uint32_t>(readBigEndian(streamInfo, 2));
    info.maxBlockSize = static_cast<uint32_t>(readBigEndian(streamInfo + 2, 2));
    const uint64_t packed = readBigEndian(streamInfo + 10, 8);
    info.sampleRate = static_cast<uint32_t>((packed >> kSampleRateShift) & 0xFFFFF);
    info.channels = static_cast<uint32_t>(((packed >> kChannelsShift) & 0x7) + 1);
    info.bitsPerSample = static_cast<uint32_t>(((packed >> kBitsPerSampleShift) & 0x1F) + 1);
    info.totalSamples = packed & 0xFFFFFFFFFULL;
    return info;
}

DecodeStatus decodeFrame(
    const uint8_t *streamInfo,
    size_t streamInfoSize,
    const uint8_t *frame,
    size_t frameSize,
    uint8_t *output,
    size_t outputCapacity,
    int32_t *framesDecoded
) {
    if (framesDecoded != nullptr) {
        *framesDecoded = 0;
    }
    if (output == nullptr || outputCapacity == 0 || outputCapacity > static_cast<size_t>(INT32_MAX)) {
        return DecodeStatus::OutputTooSmall;
    }

    std::vector<uint8_t> wrapped;
    if (!buildPrologue(streamInfo, streamInfoSize, wrapped)) {
        return DecodeStatus::BadStreamInfoSize;
    }

    // The prologue is always signature + metadata block header + the 34-byte body, so the fields
    // live at a fixed offset; reading them from the wrapped copy keeps one source of truth.
    const StreamInfo info =
        parseStreamInfo(wrapped.data() + kFlacSignatureSize + kMetadataHeaderSize, kStreamInfoSize);
    if (info.channels == 0 || info.channels > 8 || info.maxBlockSize == 0 ||
        info.sampleRate == 0 || info.bitsPerSample == 0) {
        return DecodeStatus::BadStreamInfoContent;
    }
    if (frame == nullptr || frameSize == 0) {
        return DecodeStatus::EmptyFrame;
    }

    wrapped.insert(wrapped.end(), frame, frame + frameSize);

    drflac *decoder = drflac_open_memory(wrapped.data(), wrapped.size(), nullptr);
    if (decoder == nullptr) {
        return DecodeStatus::OpenFailed;
    }

    const auto bytesPerFrame =
        static_cast<size_t>(info.channels) * static_cast<size_t>(sizeof(int32_t));
    const size_t availableFrames = outputCapacity / bytesPerFrame;
    const size_t framesToRead =
        info.maxBlockSize < availableFrames ? info.maxBlockSize : availableFrames;
    if (framesToRead == 0) {
        drflac_close(decoder);
        return DecodeStatus::OutputTooSmall;
    }

    const drflac_uint64 decoded = drflac_read_pcm_frames_s32(
        decoder,
        framesToRead,
        reinterpret_cast<drflac_int32 *>(output)
    );
    drflac_close(decoder);
    if (decoded == 0) {
        return DecodeStatus::NoSamples;
    }
    if (framesDecoded != nullptr) {
        *framesDecoded = static_cast<int32_t>(decoded);
    }
    return DecodeStatus::Ok;
}

}  // namespace mediamp_flac
