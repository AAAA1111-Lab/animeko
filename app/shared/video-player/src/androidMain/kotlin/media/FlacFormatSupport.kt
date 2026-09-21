/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)

package me.him188.ani.app.videoplayer.media

import androidx.media3.common.DataReader
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.ParsableByteArray
import androidx.media3.extractor.Extractor
import androidx.media3.extractor.ExtractorInput
import androidx.media3.extractor.ExtractorOutput
import androidx.media3.extractor.ExtractorsFactory
import androidx.media3.extractor.PositionHolder
import androidx.media3.extractor.SeekMap
import androidx.media3.extractor.TrackOutput
import me.him188.ani.utils.logging.logger
import me.him188.ani.utils.logging.warn

private const val FLAC_MARKER = "fLaC"
private const val FLAC_STREAM_INFO_SIZE = 34

/** `fLaC` + a STREAMINFO metadata block header: type 0, "last block" set, 34 bytes long. */
private const val FLAC_HEADER_SIZE = 8
private val flacHeader = byteArrayOf(
    0x66, 0x4C, 0x61, 0x43, // "fLaC"
    0x80.toByte(), 0x00, 0x00, 0x22, // METADATA_BLOCK_HEADER: last block, type 0 (STREAMINFO), 34 bytes
)

/**
 * Normalises a FLAC track's codec-specific data to the canonical `fLaC` stream form.
 *
 * Matroska keeps a FLAC track's `CodecPrivate` as a bare STREAMINFO block: the `fLaC` stream marker
 * and its metadata block header are not part of it. Media3 hands those bytes to the platform
 * `audio/flac` decoder as `csd-0` unchanged. Newer Android versions accept the bare block, but the
 * decoder shipped on Android 8.0/8.1 rejects it and the renderer fails with
 * `ERROR_CODE_DECODING_FAILED`, even though the codec is reported as supported.
 *
 * Only bare blocks are rewritten; data that already carries the marker, and data that is not
 * STREAMINFO-sized, are returned untouched so the common path keeps working exactly as before.
 */
internal fun Format.withNormalizedFlacCodecSpecificData(): Format {
    if (sampleMimeType != MimeTypes.AUDIO_FLAC) return this
    val codecSpecificData = initializationData.firstOrNull() ?: return this
    if (codecSpecificData.startsWithFlacMarker()) return this
    if (codecSpecificData.size != FLAC_STREAM_INFO_SIZE) return this
    if (ENABLE_AUDIO_FORMAT_PROBE) {
        flacNormalizationLogger.warn {
            "flac csd was a bare ${codecSpecificData.size}B STREAMINFO, prefixed the fLaC header"
        }
    }
    val normalized = ByteArray(FLAC_HEADER_SIZE + FLAC_STREAM_INFO_SIZE)
    flacHeader.copyInto(normalized)
    codecSpecificData.copyInto(normalized, FLAC_HEADER_SIZE)
    return buildUpon()
        .setInitializationData(listOf(normalized))
        .build()
}

private val flacNormalizationLogger = logger("FlacFormatSupport")

private fun ByteArray.startsWithFlacMarker(): Boolean =
    size >= FLAC_MARKER.length && FLAC_MARKER.indices.all { this[it] == FLAC_MARKER[it].code.toByte() }

/**
 * Wraps every extractor so each track format is passed through [withNormalizedFlacCodecSpecificData]
 * before ExoPlayer builds its renderers.
 */
internal fun ExtractorsFactory.withNormalizedFlacFormats(): ExtractorsFactory {
    val delegate = this
    return ExtractorsFactory { delegate.createExtractors().map { it.withNormalizedFlacFormat() }.toTypedArray() }
}

private fun Extractor.withNormalizedFlacFormat(): Extractor {
    val delegate = this
    return object : Extractor {
        override fun sniff(extractorInput: ExtractorInput): Boolean = delegate.sniff(extractorInput)

        override fun init(output: ExtractorOutput) {
            delegate.init(
                object : ExtractorOutput {
                    override fun track(id: Int, type: Int): TrackOutput =
                        output.track(id, type).withNormalizedFlacFormat()

                    override fun endTracks() = output.endTracks()

                    override fun seekMap(seekMap: SeekMap) = output.seekMap(seekMap)
                },
            )
        }

        override fun read(input: ExtractorInput, seekPosition: PositionHolder): Int =
            delegate.read(input, seekPosition)

        override fun seek(position: Long, timeUs: Long) = delegate.seek(position, timeUs)

        override fun release() = delegate.release()
    }
}

private fun TrackOutput.withNormalizedFlacFormat(): TrackOutput {
    val delegate = this
    return object : TrackOutput {
        override fun format(format: Format) = delegate.format(format.withNormalizedFlacCodecSpecificData())

        override fun sampleData(
            input: DataReader,
            length: Int,
            allowEndOfInput: Boolean,
            sampleDataPart: Int,
        ): Int = delegate.sampleData(input, length, allowEndOfInput, sampleDataPart)

        override fun sampleData(data: ParsableByteArray, length: Int, sampleDataPart: Int) =
            delegate.sampleData(data, length, sampleDataPart)

        override fun sampleMetadata(
            timeUs: Long,
            flags: Int,
            size: Int,
            offset: Int,
            encryptionData: TrackOutput.CryptoData?,
        ) = delegate.sampleMetadata(timeUs, flags, size, offset, encryptionData)
    }
}
