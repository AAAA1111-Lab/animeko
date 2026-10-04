/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * Use of this source code is governed by the Apache License version 2 license, which can be found at the following link.
 *
 * https://github.com/open-ani/mediamp/blob/main/LICENSE
 */

package me.him188.ani.app.video.player.media.audio

/**
 * Reassembles FLAC frames from the container samples Media3 hands to the decoder.
 *
 * A container sample is **not** a FLAC frame. Measured on a real Matroska release (FLAC 24-bit,
 * 48 kHz, 4096-sample blocks): 10 seconds carried 161 blocks but only about 118 frames, i.e. the
 * blocks are neither frame-aligned nor one-frame-per-block 鈥?they can hold a fragment of a frame
 * or parts of several. The bundled decoder can only decode whole frames, so the raw byte stream
 * has to be reassembled first.
 *
 * This class keeps the trailing partial frame between calls and yields every complete frame it
 * can. Frames are located from the header itself rather than by scanning for the sync code, since
 * the two sync bytes also occur inside a frame's compressed subframes.
 *
 * Not thread safe: the decoder only drives it from the playback thread.
 */
internal class FlacFrameAccumulator(initialCapacity: Int = 64 * 1024) {
    private var buffer = ByteArray(initialCapacity)
    private var size = 0
    private var endOfInput = false

    /** Bytes currently held; the frame the next append completes is not yet available. */
    val bufferedBytes: Int get() = size

    /** Discards everything buffered; a seek invalidates any partially received frame. */
    fun reset() {
        size = 0
        endOfInput = false
    }

    /**
     * Declares that no further bytes will arrive.
     *
     * A frame's length is known from where the next frame starts, so the last frame of a stream
     * cannot be recognised as complete while more data could still follow. This tells the
     * accumulator that what remains is the final frame.
     */
    fun endOfInput() {
        endOfInput = true
    }

    /** Appends one container sample. */
    fun append(data: ByteArray, offset: Int, length: Int) {
        if (length <= 0) {
            return
        }
        endOfInput = false
        ensureCapacity(size + length)
        data.copyInto(buffer, destinationOffset = size, startIndex = offset, endIndex = offset + length)
        size += length
    }

    /**
     * Removes and returns the next complete frame, or null when what is buffered does not yet hold
     * one. The returned array is a copy, so it stays valid while the caller decodes it.
     *
     * A non-null **empty** array means bytes were discarded without producing a frame. That happens
     * when the buffer does not start on a frame header (the stream was rejoined mid-frame by a seek)
     * and re-aligning it means throwing the partial frame away. The caller must not treat an empty
     * array as audio; it exists so the caller can distinguish "discarded" from "not enough data yet".
     */
    fun nextFrame(): ByteArray? {
        if (size < MIN_HEADER_SIZE) {
            return null
        }
        if (parseHeaderSize(buffer, 0, size) == null) {
            // The buffer does not start with a frame header, which means the stream was joined
            // mid-frame. That only happens after a seek, so realign on the next header instead of
            // failing the track. Without one, keep collecting bytes.
            val nextStart = findNextHeaderStart(buffer, size)
            if (nextStart < 0) {
                return null
            }
            discardPrefix(nextStart)
            // Report the discard so the caller does not silently lose a sample's worth of time.
            return EMPTY_FRAME
        }

        val headerSize = parseHeaderSize(buffer, 0, size) ?: return null
        val frameLength = findFrameLength(buffer, size, headerSize, endOfInput) ?: return null
        if (frameLength <= headerSize + FOOTER_SIZE) {
            // No candidate verified against this frame's CRC-16. The bytes are not decodable as a
            // frame; drop the header and re-align rather than handing garbage to the decoder, which
            // would fail the whole track. Only possible mid-stream, where a real boundary is pending.
            discardPrefix(headerSize)
            return EMPTY_FRAME
        }
        val frame = buffer.copyOfRange(0, frameLength)
        discardPrefix(frameLength)
        return frame
    }

    private fun ensureCapacity(required: Int) {
        if (buffer.size >= required) {
            return
        }
        var capacity = buffer.size
        while (capacity < required) {
            capacity *= 2
        }
        buffer = buffer.copyOf(capacity)
    }

    private fun discardPrefix(count: Int) {
        if (count >= size) {
            size = 0
            return
        }
        buffer.copyInto(buffer, destinationOffset = 0, startIndex = count, endIndex = size)
        size -= count
    }

    private companion object {
        const val MIN_HEADER_SIZE = 6
        const val MAX_HEADER_SIZE = 16

        /** FLAC stores a 16-bit CRC of the frame in its last two bytes (RFC 9639 搂9.3). */
        const val FOOTER_SIZE = 2

        /**
         * Sentinel returned by [nextFrame] to mean "bytes were discarded, no frame produced".
         * Compared by identity, never by content.
         */
        val EMPTY_FRAME = ByteArray(0)

        const val SYNC_FIRST = 0xFF
        const val SYNC_SECOND_MASK = 0xFE
        const val SYNC_SECOND_VALUE = 0xF8

        /**
         * CRC-8 with the polynomial x^8 + x^2 + x + 1, which FLAC uses for frame headers
         * (RFC 9639 搂9.1.7).
         *
         * Checking it is what makes boundary detection reliable: the sync code alone occurs inside
         * a frame's compressed subframes, so candidates are only accepted when the header's own
         * CRC agrees.
         */
        val CRC8_TABLE = IntArray(256).also { table ->
            for (index in 0 until 256) {
                var crc = index
                repeat(8) {
                    crc = if ((crc and 0x80) != 0) ((crc shl 1) xor 0x07) and 0xFF else (crc shl 1) and 0xFF
                }
                table[index] = crc
            }
        }

        fun crc8(bytes: ByteArray, from: Int, until: Int): Int {
            var crc = 0
            for (index in from until until) {
                crc = CRC8_TABLE[(crc xor (bytes[index].toInt() and 0xFF)) and 0xFF]
            }
            return crc
        }

        /**
         * CRC-16 with the polynomial x^16 + x^15 + x^2 + 1, which FLAC uses for the frame footer
         * (RFC 9639 搂9.3). Initial value 0, no reflection, no final XOR.
         *
         * This is the authoritative frame boundary check. The header's CRC-8 is only 8 bits, so a
         * sync code inside a frame's compressed subframes passes it roughly once every 256 tries;
         * accepting one truncates the frame and `dr_flac` then produces no PCM. That is what crashed
         * playback after a seek on device (auto-skip and scrubbing), where the stream is rejoined
         * mid-frame.
         */
        val CRC16_TABLE = IntArray(256).also { table ->
            for (index in 0 until 256) {
                var crc = index shl 8
                repeat(8) {
                    crc = if ((crc and 0x8000) != 0) ((crc shl 1) xor 0x8005) and 0xFFFF else (crc shl 1) and 0xFFFF
                }
                table[index] = crc
            }
        }

        fun crc16(bytes: ByteArray, from: Int, until: Int): Int {
            var crc = 0
            for (index in from until until) {
                crc = ((crc shl 8) xor CRC16_TABLE[((crc shr 8) xor (bytes[index].toInt() and 0xFF)) and 0xFF]) and 0xFFFF
            }
            return crc
        }

        fun isHeaderStart(bytes: ByteArray, offset: Int): Boolean {
            val first = bytes[offset].toInt() and 0xFF
            val second = bytes[offset + 1].toInt() and 0xFF
            return first == SYNC_FIRST && (second and SYNC_SECOND_MASK) == SYNC_SECOND_VALUE
        }

        /**
         * Length of the frame header at [offset], or null when there is not enough data or the
         * bytes do not form a valid header (RFC 9639 搂9.1). The trailing CRC-8 is verified, so a
         * false sync inside compressed data is rejected.
         */
        fun parseHeaderSize(bytes: ByteArray, offset: Int, available: Int): Int? {
            val size = headerSizeIgnoringCrc(bytes, offset, available) ?: return null
            if (offset + size > available) {
                return null
            }
            val expected = bytes[offset + size - 1].toInt() and 0xFF
            if (crc8(bytes, offset, offset + size - 1) != expected) {
                return null
            }
            return size
        }

        private fun headerSizeIgnoringCrc(bytes: ByteArray, offset: Int, available: Int): Int? {
            if (available - offset < MIN_HEADER_SIZE) return null
            if (!isHeaderStart(bytes, offset)) return null

            val blockSizeCode = (bytes[offset + 2].toInt() and 0xFF) shr 4
            val sampleRateCode = bytes[offset + 2].toInt() and 0x0F
            if (blockSizeCode == 0x0) return null // reserved

            var cursor = offset + 4
            // UTF-8 style coded frame number (fixed block size) or sample number (variable).
            val first = bytes[cursor].toInt() and 0xFF
            cursor += 1
            if ((first and 0x80) != 0) {
                for (bit in 6 downTo 0) {
                    if ((first and (1 shl bit)) != 0) {
                        cursor += 1
                    } else {
                        break
                    }
                }
            }
            if (cursor - offset > MAX_HEADER_SIZE) return null

            if (blockSizeCode == 0x6) cursor += 1
            if (blockSizeCode == 0x7) cursor += 2
            if (sampleRateCode == 0xC) cursor += 1
            if (sampleRateCode == 0xD || sampleRateCode == 0xE) cursor += 2
            cursor += 1 // CRC-8 of the header

            if (cursor - offset > MAX_HEADER_SIZE) return null
            return cursor - offset
        }

        /**
         * Number of PCM frames the header at [offset] declares, or 0 when the block size is
         * stored after the coded number and cannot be read yet.
         */
        fun blockSizeOf(bytes: ByteArray, offset: Int, available: Int): Int {
            val code = (bytes[offset + 2].toInt() and 0xFF) shr 4
            return when (code) {
                0x1 -> 192
                0x2 -> 576
                0x3 -> 1152
                0x4 -> 2304
                0x5 -> 4608
                0x6 -> {
                    val headerSize = parseHeaderSize(bytes, offset, available) ?: return 0
                    // The 8-bit value sits immediately before the trailing CRC-8.
                    val valueOffset = offset + headerSize - 2
                    if (valueOffset < offset || valueOffset >= available) return 0
                    (bytes[valueOffset].toInt() and 0xFF) + 1
                }

                0x7 -> {
                    val headerSize = parseHeaderSize(bytes, offset, available) ?: return 0
                    val valueOffset = offset + headerSize - 3
                    if (valueOffset < offset || valueOffset + 2 > available) return 0
                    (((bytes[valueOffset].toInt() and 0xFF) shl 8) or
                        (bytes[valueOffset + 1].toInt() and 0xFF)) + 1
                }

                0x8 -> 256
                0x9 -> 512
                0xA -> 1024
                0xB -> 2048
                0xC -> 4096
                0xD -> 8192
                0xE -> 16384
                0xF -> 32768
                else -> 0
            }
        }

        /**
         * Length of the frame that starts at [offset], found by locating the next header whose
         * preceding frame CRC-16 agrees.
         *
         * No lower bound beyond the header plus the footer is applied: the block size is a sample
         * count, not a byte count, and a block whose samples are all equal compresses to a handful
         * of bytes.
         *
         * The CRC over the candidate span is recomputed rather than maintained incrementally. That
         * costs a pass over the frame per candidate, but [isHeaderStart] rejects almost every
         * position first, so in practice this is one pass per frame 鈥?and recomputing is the version
         * whose correctness is actually pinned by the tests.
         */
        fun findFrameLength(
            bytes: ByteArray,
            available: Int,
            headerSize: Int,
            endOfInput: Boolean,
        ): Int? {
            var candidate = headerSize + FOOTER_SIZE
            while (candidate + MIN_HEADER_SIZE <= available) {
                if (isHeaderStart(bytes, candidate) &&
                    parseHeaderSize(bytes, candidate, available) != null
                ) {
                    val stored = ((bytes[candidate - 2].toInt() and 0xFF) shl 8) or
                            (bytes[candidate - 1].toInt() and 0xFF)
                    if (crc16(bytes, 0, candidate - FOOTER_SIZE) == stored) {
                        return candidate
                    }
                }
                // A rejected candidate is a false sync inside the frame: the two bytes before a real
                // boundary are the frame's stored CRC-16, which a correctly built frame always
                // matches. So the real boundary can only lie further on and scanning forward never
                // skips it.
                candidate += 1
            }
            // No next frame header yet. Only when no more bytes can arrive is what is buffered the
            // final frame of the stream, and therefore complete.
            return if (endOfInput) available else null
        }

        /** Offset of the next plausible header start in [0, size), or -1 when there is none. */
        fun findNextHeaderStart(bytes: ByteArray, size: Int): Int {
            for (offset in 0 until size - 1) {
                if (isHeaderStart(bytes, offset) && parseHeaderSize(bytes, offset, size) != null) {
                    return offset
                }
            }
            return -1
        }
    }
}
