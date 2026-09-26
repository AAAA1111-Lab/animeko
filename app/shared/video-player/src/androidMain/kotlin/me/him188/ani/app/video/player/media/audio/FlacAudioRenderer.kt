/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * Use of this source code is governed by the Apache License version 2 license, which can be found at the following link.
 *
 * https://github.com/open-ani/mediamp/blob/main/LICENSE
 */

package me.him188.ani.app.video.player.media.audio

import android.content.Context
import android.os.Handler
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import androidx.media3.decoder.CryptoConfig
import androidx.media3.exoplayer.Renderer
import androidx.media3.exoplayer.RendererCapabilities
import androidx.media3.exoplayer.audio.AudioCapabilities
import androidx.media3.exoplayer.audio.AudioRendererEventListener
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.DecoderAudioRenderer
import me.him188.ani.app.video.player.media.audio.FlacDecoder.Companion.createOrNull
import me.him188.ani.app.video.player.media.audio.FlacDecoder.Companion.isAvailable

/**
 * Audio renderer that decodes FLAC with the bundled software decoder.
 *
 * Media3's own FLAC support is MediaCodec-based, which is useless on a device whose only
 * `audio/flac` decoder is a hardware one that fails on 24-bit streams. [DecoderAudioRenderer]
 * provides everything else an audio renderer needs (sink plumbing, playback parameters, position
 * reporting, decoder counters), so this only answers "can I handle this format" and "give me a
 * decoder".
 *
 * [supportsFormatInternal] deliberately reports `FORMAT_HANDLED` while every other renderer in
 * the chain reports less, because Media3 picks the renderer with the highest capability for a
 * track. Taking FLAC away from [androidx.media3.exoplayer.audio.MediaCodecAudioRenderer] is the
 * whole point: leaving it there would keep the broken platform decoder in play.
 */
@OptIn(UnstableApi::class)
internal class FlacAudioRenderer(
    private val audioCapabilities: AudioCapabilities,
    eventHandler: Handler?,
    eventListener: AudioRendererEventListener?,
    audioSink: AudioSink,
) : DecoderAudioRenderer<FlacDecoder>(
    eventHandler,
    eventListener,
    audioSink,
) {
    override fun getName(): String = "ani-flac"

    override fun supportsFormatInternal(format: Format): Int {
        if (!isAvailable || format.sampleMimeType != MimeTypes.AUDIO_FLAC) {
            return RendererCapabilities.create(C.FORMAT_UNSUPPORTED_SUBTYPE)
        }
        // Media3 picks the renderer with the highest capability for a track, so claiming FLAC
        // here is what keeps MediaCodecAudioRenderer (and its broken platform decoder) away
        // from it. Returning null later is not an option: DecoderAudioRenderer does not accept
        // a null decoder, so createDecoder throws instead.
        return RendererCapabilities.create(C.FORMAT_HANDLED)
    }

    override fun createDecoder(format: Format, cryptoConfig: CryptoConfig?): FlacDecoder =
        createOrNull(format, cryptoConfig)
            ?: throw FlacDecoderException(
                "The bundled FLAC decoder cannot handle this track " +
                    "(mime=${format.sampleMimeType}, csd=${format.initializationData.size})",
            )

    override fun getOutputFormat(decoder: FlacDecoder): Format = decoder.requireOutputFormat()

    companion object {
        /**
         * Appends a FLAC software renderer to [renderers].
         *
         * The renderer is installed only when the bundled decoder actually loaded; otherwise
         * FLAC keeps going through the platform path rather than failing every track.
         */
        fun addTo(
            renderers: ArrayList<Renderer>,
            context: Context,
            eventHandler: Handler?,
            eventListener: AudioRendererEventListener?,
            audioSink: AudioSink,
        ) {
            if (!isAvailable) return
            renderers.add(
                FlacAudioRenderer(
                    audioCapabilities = runCatching { AudioCapabilities.getCapabilities(context) }
                        .getOrDefault(AudioCapabilities.DEFAULT_AUDIO_CAPABILITIES),
                    eventHandler = eventHandler,
                    eventListener = eventListener,
                    audioSink = audioSink,
                ),
            )
        }
    }
}
