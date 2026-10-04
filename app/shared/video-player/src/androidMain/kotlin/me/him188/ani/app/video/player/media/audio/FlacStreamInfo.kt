/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * Use of this source code is governed by the Apache License version 2 license, which can be found at the following link.
 *
 * https://github.com/open-ani/mediamp/blob/main/LICENSE
 */

package me.him188.ani.app.video.player.media.audio

/**
 * Packs the STREAMINFO body the FLAC decoder is configured with.
 *
 * Kept apart from [FlacDecoderNative] on purpose: that object loads the native library when it is
 * first touched, so anything reachable through it cannot be exercised on a host JVM.
 *
 * STREAMINFO layout (RFC 9639 搂8.2), 34 bytes total:
 *   [0..1]   minimum block size
 *   [2..3]   maximum block size
 *   [4..6]   minimum frame size (0 = unknown)
 *   [7..9]   maximum frame size (0 = unknown)
 *   [10..12] sample rate (20 bits) | channels - 1 (3 bits) | bits per sample - 1 (5 bits)
 *   [13..17] total samples (36 bits), 0 = unknown
 *   [18..33] MD5 of the unencoded audio (0 = unknown)
 */
internal object FlacStreamInfo {
    const val SIZE = 34

    /**
     * Extracts the STREAMINFO body the decoder needs out of whatever the extractor reported.
     *
     * The shapes that actually reach a decoder differ more than the format description suggests,
     * and getting this wrong fails playback: on a real 24-bit Matroska release the codec private
     * was **42** bytes (metadata block header + STREAMINFO) and another stream reported 113 bytes
     * (STREAMINFO plus a seek table), while Media3's FlacExtractor hands over the bare 34 bytes.
     * So the metadata block chain is parsed rather than a fixed size assumed:
     *
     *   [0..3]   optional "fLaC" signature
     *   then, repeatedly: 1 byte block header (last-block flag + 7-bit type), 3-byte length, body
     *
     * Returns the 34-byte STREAMINFO body, or null when [data] does not contain one.
     */
    fun extractStreamInfo(data: ByteArray): ByteArray? {
        var offset = 0
        if (data.size >= 4 &&
            data[0] == 'f'.code.toByte() &&
            data[1] == 'L'.code.toByte() &&
            data[2] == 'a'.code.toByte() &&
            data[3] == 'C'.code.toByte()
        ) {
            offset = 4
        }

        // A bare body: exactly the 34 bytes with no block header in front of them.
        if (data.size == SIZE) {
            return data.copyOf()
        }

        while (offset + 4 <= data.size) {
            val header = data[offset].toInt() and 0xFF
            val isLast = (header and 0x80) != 0
            val type = header and 0x7F
            val length = ((data[offset + 1].toInt() and 0xFF) shl 16) or
                ((data[offset + 2].toInt() and 0xFF) shl 8) or
                (data[offset + 3].toInt() and 0xFF)
            val bodyStart = offset + 4
            if (bodyStart + length > data.size) {
                return null
            }
            if (type == BLOCK_TYPE_STREAMINFO) {
                if (length < SIZE) {
                    return null
                }
                return data.copyOfRange(bodyStart, bodyStart + SIZE)
            }
            if (isLast) {
                return null
            }
            offset = bodyStart + length
        }
        return null
    }

    private const val BLOCK_TYPE_STREAMINFO = 0

    fun pack(
        sampleRate: Int,
        channelCount: Int,
        bitsPerSample: Int,
        maxBlockSize: Int,
    ): ByteArray {
        val streamInfo = ByteArray(SIZE)
        // Minimum and maximum block size: the extractor reports one value for both.
        streamInfo[0] = ((maxBlockSize ushr 8) and 0xFF).toByte()
        streamInfo[1] = (maxBlockSize and 0xFF).toByte()
        streamInfo[2] = ((maxBlockSize ushr 8) and 0xFF).toByte()
        streamInfo[3] = (maxBlockSize and 0xFF).toByte()
        // Frame sizes stay 0 (unknown): the extractor does not report them and dr_flac does not
        // require them.

        // 20 bits of sample rate, 3 bits of (channels - 1), 5 bits of (bits per sample - 1).
        // Together with the 36 bits of total samples below, these 64 bits map onto the eight
        // bytes [10..17], most significant byte first.
        val packed = ((sampleRate.toLong() and 0xFFFFF) shl 44) or
            (((channelCount - 1).toLong() and 0x7) shl 41) or
            (((bitsPerSample - 1).toLong() and 0x1F) shl 36)
        for (index in 0 until 8) {
            // Byte `index` of [10..17] holds bits (63 - index * 8) down to (56 - index * 8).
            streamInfo[10 + index] = ((packed ushr (56 - index * 8)) and 0xFF).toByte()
        }
        // Total samples and MD5 stay 0 (unknown).
        return streamInfo
    }
}
