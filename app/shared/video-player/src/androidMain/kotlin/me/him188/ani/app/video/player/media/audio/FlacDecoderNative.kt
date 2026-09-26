/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * Use of this source code is governed by the Apache License version 2 license, which can be found at the following link.
 *
 * https://github.com/open-ani/mediamp/blob/main/LICENSE
 */

package me.him188.ani.app.video.player.media.audio

import java.nio.ByteBuffer

/**
 * JNI surface of the bundled FLAC software decoder (`libani_flac.so`).
 *
 * The native side is a pure push model: [nativeDecodeFrame] takes exactly one FLAC frame and the
 * codec configuration, wraps them in a minimal self-contained FLAC stream, decodes it with the
 * bundled dr_flac, and writes interleaved 32-bit PCM to [outputBuffer]. See
 * `src/cpp/flac/flac_jni.cpp` for the C++ contract and [FlacFrameAccumulator] for why a container
 * sample has to be reassembled into a frame first.
 *
 * Touching this object loads the native library, so nothing a host test needs may live here; use
 * [FlacStreamInfo] for the pure parts.
 *
 * Every method is static and takes no locks: a decoder instance owns its buffers and is only
 * driven from the playback thread.
 */
internal object FlacDecoderNative {
    init {
        System.loadLibrary("ani_flac")
    }

    /**
     * Decodes one FLAC frame.
     *
     * All three buffers must be direct, and the frame and configuration are read from offset 0.
     *
     * @param streamInfoBuffer the decoder configuration Media3 reports for the track: the bare
     *   34-byte STREAMINFO body, which is what every extractor able to produce an `audio/flac`
     *   track hands over (Matroska's `A_FLAC` CodecPrivate, and FlacExtractor after it consumes
     *   the signature and block header itself).
     * @param frameBuffer one complete FLAC frame.
     * @param outputBuffer direct buffer to receive interleaved 32-bit PCM.
     * @return the number of PCM frames written, or a negative value when the frame is undecodable.
     */
    @JvmStatic
    external fun nativeDecodeFrame(
        streamInfoBuffer: ByteBuffer,
        streamInfoSize: Int,
        frameBuffer: ByteBuffer,
        frameSize: Int,
        outputBuffer: ByteBuffer,
        outputCapacity: Int,
    ): Int
}
