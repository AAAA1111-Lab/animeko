/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.video.player.media.audio

import android.content.Context
import android.os.Handler
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.Renderer
import androidx.media3.exoplayer.audio.AudioRendererEventListener
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.DefaultAudioSink
import androidx.media3.exoplayer.mediacodec.MediaCodecSelector

/**
 * Ani 自带的渲染器工厂, 经 [androidx.media3.exoplayer.ExoPlayer.Builder.setRenderersFactory]
 * 注入 mediamp 的 ExoPlayer. 相比 Media3 默认工厂, 它做两件事:
 *
 *  - 注册 [FlacAudioRenderer] (内置 dr_flac 软解). 注册在平台渲染器**之前**:
 *    MediaCodec 的能力协商无法区分 16-bit 与 24-bit FLAC (FLAC profile level 不携带位深),
 *    平台解码器对同级支持按注册顺序取胜, 若让位给平台路径, 24-bit 流会重新落入解码失败的平台解码器.
 *    软解渲染器不可用 (native 库未打包) 时 [FlacAudioRenderer.addTo] 是空操作,
 *    FLAC 自然走平台解码器, 即"无软解时回退硬解".
 *  - [highQualityTimeStretch] 为 true 时, 在 audio sink 上安装 [WsolaAudioProcessorChain]
 *    (native 不可用时内部自动回退 Sonic), 保持 mediamp HighQualityWsola 的变速音质.
 *
 * 替换 mediamp 内部工厂是必要的: 它是 internal 类, 且不注册 FLAC 软解渲染器.
 */
@OptIn(UnstableApi::class)
internal class AniRenderersFactory(
    context: Context,
    private val highQualityTimeStretch: Boolean,
) : DefaultRenderersFactory(context) {
    init {
        setEnableDecoderFallback(true)
    }

    override fun buildAudioRenderers(
        context: Context,
        extensionRendererMode: Int,
        mediaCodecSelector: MediaCodecSelector,
        enableDecoderFallback: Boolean,
        audioSink: AudioSink,
        eventHandler: Handler,
        eventListener: AudioRendererEventListener,
        out: ArrayList<Renderer>,
    ) {
        FlacAudioRenderer.addTo(
            renderers = out,
            context = context,
            eventHandler = eventHandler,
            eventListener = eventListener,
            audioSink = audioSink,
        )
        super.buildAudioRenderers(
            context,
            extensionRendererMode,
            mediaCodecSelector,
            enableDecoderFallback,
            audioSink,
            eventHandler,
            eventListener,
            out,
        )
    }

    override fun buildAudioSink(
        context: Context,
        enableFloatOutput: Boolean,
        enableAudioTrackPlaybackParams: Boolean,
    ): AudioSink {
        if (!highQualityTimeStretch) {
            return super.buildAudioSink(context, enableFloatOutput, enableAudioTrackPlaybackParams)
        }
        val chain = WsolaAudioProcessorChain()
        return DefaultAudioSink.Builder(context)
            .setEnableFloatOutput(enableFloatOutput)
            .setEnableAudioOutputPlaybackParameters(enableAudioTrackPlaybackParams)
            .setAudioProcessorChain(chain)
            .build()
    }
}
