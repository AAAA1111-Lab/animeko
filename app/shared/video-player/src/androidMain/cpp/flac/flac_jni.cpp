/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * Use of this source code is governed by the Apache License version 2 license, which can be found at the following link.
 *
 * https://github.com/open-ani/mediamp/blob/main/LICENSE
 */

/**
 * JNI bridge for the bundled FLAC software decoder (libmediamp_flac).
 *
 * Why this exists: Media3 has no FLAC software decoder and some devices only expose a hardware
 * FLAC decoder that fails on 24-bit streams (observed: OMX.qti.audio.decoder.flac on API 26,
 * "Decoder failed" out of native_queueInputBuffer). MediaCodec has no extension point that could
 * register a bundled decoder, so the only way to decode those streams is to own the decoding.
 *
 * This file is only the JNI edge; the decoding itself lives in flac_decode.cpp so the on-device
 * probe can drive the same code. DR_FLAC_IMPLEMENTATION is compiled by dr_flac_impl.cpp in this
 * same directory, so this file only sees the declarations.
 */

#include <jni.h>

#include <android/log.h>
#include <cstdint>
#include <vector>

#include "flac_decode.h"

namespace {

constexpr const char *kTag = "mediamp-flac";

/**
 * Copies [size] bytes out of a direct ByteBuffer.
 *
 * Both buffers the Kotlin side passes are direct by construction: the codec configuration and the
 * frame come from buffers allocated with `allocateDirect`, and the PCM target comes from
 * `SimpleDecoderOutputBuffer.init`, which always returns a direct buffer. Anything else is a bug
 * on the caller's side, so it yields an empty vector and the decode fails loudly.
 */
std::vector<uint8_t> copyDirectBuffer(JNIEnv *env, jobject buffer, jint size) {
    std::vector<uint8_t> result;
    if (buffer == nullptr || size <= 0) {
        return result;
    }
    auto *base = static_cast<uint8_t *>(env->GetDirectBufferAddress(buffer));
    if (base == nullptr) {
        return result;
    }
    result.assign(base, base + size);
    return result;
}

}  // namespace

extern "C" {

/**
 * Decodes one FLAC frame.
 *
 * @param streamInfoBuffer the decoder configuration Media3 reports for the track: the bare
 *   34-byte STREAMINFO body, which is what every extractor able to produce an `audio/flac`
 *   track hands over (Matroska's `A_FLAC` CodecPrivate, and FlacExtractor after it consumes
 *   the signature and block header itself).
 * @param frameBuffer one complete FLAC frame.
 * @param outputBuffer direct buffer to receive interleaved 32-bit PCM.
 * @return the number of PCM frames written, or a negative [mediamp_flac::DecodeStatus] when the
 *   frame could not be decoded.
 */
JNIEXPORT jint JNICALL
Java_me_him188_ani_app_video_player_media_audio_FlacDecoderNative_nativeDecodeFrame(
    JNIEnv *env,
    jclass /* clazz */,
    jobject streamInfoBuffer,
    jint streamInfoSize,
    jobject frameBuffer,
    jint frameSize,
    jobject outputBuffer,
    jint outputCapacity
) {
    if (outputBuffer == nullptr || outputCapacity <= 0) {
        __android_log_print(
            ANDROID_LOG_ERROR, kTag, "decode rejected: output capacity %d", outputCapacity);
        return static_cast<jint>(mediamp_flac::DecodeStatus::OutputTooSmall);
    }
    auto *output = static_cast<uint8_t *>(env->GetDirectBufferAddress(outputBuffer));
    if (output == nullptr) {
        __android_log_print(ANDROID_LOG_ERROR, kTag, "decode rejected: output is not direct");
        return static_cast<jint>(mediamp_flac::DecodeStatus::OutputTooSmall);
    }

    const std::vector<uint8_t> streamInfo =
        copyDirectBuffer(env, streamInfoBuffer, streamInfoSize);
    const std::vector<uint8_t> frame = copyDirectBuffer(env, frameBuffer, frameSize);

    int32_t framesDecoded = 0;
    const mediamp_flac::DecodeStatus status = mediamp_flac::decodeFrame(
        streamInfo.data(),
        streamInfo.size(),
        frame.data(),
        frame.size(),
        output,
        static_cast<size_t>(outputCapacity),
        &framesDecoded
    );
    if (status != mediamp_flac::DecodeStatus::Ok) {
        const mediamp_flac::StreamInfo info =
            mediamp_flac::parseStreamInfo(streamInfo.data(), streamInfo.size());
        __android_log_print(
            ANDROID_LOG_ERROR,
            kTag,
            "decode failed: %s (csd=%zu bytes, frame=%zu bytes, rate=%u, channels=%u, bits=%u, "
            "block=%u, first frame bytes=%02X %02X %02X %02X)",
            mediamp_flac::describe(status),
            streamInfo.size(),
            frame.size(),
            info.sampleRate,
            info.channels,
            info.bitsPerSample,
            info.maxBlockSize,
            frame.size() > 0 ? frame[0] : 0,
            frame.size() > 1 ? frame[1] : 0,
            frame.size() > 2 ? frame[2] : 0,
            frame.size() > 3 ? frame[3] : 0
        );
        return static_cast<jint>(status);
    }
    return framesDecoded;
}

}  // extern "C"
