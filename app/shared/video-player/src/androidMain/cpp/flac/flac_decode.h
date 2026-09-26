/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * Use of this source code is governed by the Apache License version 2 license, which can be found at the following link.
 *
 * https://github.com/open-ani/mediamp/blob/main/LICENSE
 */

/**
 * The FLAC software decode itself, kept free of JNI so it can also be driven by the on-device
 * probe (`flac_probe.cxx`) that is used to diagnose playback failures without going through the
 * whole app.
 *
 * Contract with the decoder: Media3 hands over one container sample at a time, and for every
 * extractor that can produce an `audio/flac` track that sample is one complete FLAC frame
 * (Matroska stores one frame per block; FlacExtractor splits the stream at frame boundaries).
 * dr_flac has no frame-level API, so a frame is decoded by wrapping it in a minimal,
 * self-contained FLAC stream:
 *
 *     "fLaC" | metadata block header (STREAMINFO, 34 bytes) | 34-byte STREAMINFO | frame
 *
 * and opening that with drflac_open_memory. That keeps this a pure push model, which is what
 * Media3's Decoder interface expects.
 */

#ifndef MEDIAMP_FLAC_DECODE_H
#define MEDIAMP_FLAC_DECODE_H

#include <cstddef>
#include <cstdint>

namespace mediamp_flac {

/** Why a frame could not be decoded. Mirrored by the probe's output so failures are attributable. */
enum class DecodeStatus : int {
    Ok = 0,
    /** The codec configuration is not the bare 34-byte STREAMINFO this decoder expects. */
    BadStreamInfoSize = -1,
    /** STREAMINFO describes something dr_flac cannot be driven with. */
    BadStreamInfoContent = -2,
    /** The container sample was empty. */
    EmptyFrame = -3,
    /** dr_flac refused the synthesised stream. */
    OpenFailed = -4,
    /** dr_flac opened the stream but produced no PCM. */
    NoSamples = -5,
    /** The caller's output buffer cannot hold even one PCM frame. */
    OutputTooSmall = -6,
};

/** Human readable name, for logs and probe output. */
const char *describe(DecodeStatus status);

/** STREAMINFO field layout, exposed so the probe can report what it parsed. */
struct StreamInfo {
    uint32_t minBlockSize;
    uint32_t maxBlockSize;
    uint32_t sampleRate;
    uint32_t channels;
    uint32_t bitsPerSample;
    uint64_t totalSamples;
};

/** The 34-byte STREAMINFO body of `streamInfo`, or a zeroed struct when the size is wrong. */
StreamInfo parseStreamInfo(const uint8_t *streamInfo, size_t streamInfoSize);

/**
 * Decodes one FLAC frame into interleaved 32-bit PCM.
 *
 * @param streamInfo the bare 34-byte STREAMINFO body, as reported by the extractor.
 * @param frame one complete FLAC frame.
 * @param output must hold at least `outputCapacity` bytes.
 * @param framesDecoded receives the number of PCM frames written on success.
 * @return [DecodeStatus.Ok] on success, another value describing the failure otherwise.
 */
DecodeStatus decodeFrame(
    const uint8_t *streamInfo,
    size_t streamInfoSize,
    const uint8_t *frame,
    size_t frameSize,
    uint8_t *output,
    size_t outputCapacity,
    int32_t *framesDecoded
);

}  // namespace mediamp_flac

#endif  // MEDIAMP_FLAC_DECODE_H
