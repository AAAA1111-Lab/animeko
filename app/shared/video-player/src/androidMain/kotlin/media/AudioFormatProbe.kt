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

import androidx.media3.common.Format
import androidx.media3.common.PlaybackException
import androidx.media3.exoplayer.DecoderReuseEvaluation
import androidx.media3.exoplayer.analytics.AnalyticsListener
import me.him188.ani.utils.logging.logger
import me.him188.ani.utils.logging.warn
import java.util.Locale

/**
 * 临时诊断开关: 打开后会把音频渲染器实际收到的 [Format], 解码器初始化结果以及播放失败原因写进 app.log.
 *
 * 目的是对比"本地导入 (content://) 能播"与"Jellyfin (http) 不能播"这两条路径实际交给解码器的东西,
 * 定位 API 26 上 FLAC 初始化失败 (`ERROR_CODE_DECODING_FAILED`) 的原因.
 * 定位完成后删除本文件即可, 或把 [ENABLE_AUDIO_FORMAT_PROBE] 改回 false.
 */
internal const val ENABLE_AUDIO_FORMAT_PROBE = true

/**
 * 只记录 [Format] 真正变化的那一次, 所以不会按帧刷屏.
 */
internal class AudioFormatProbe : AnalyticsListener {
    private val logger = logger<AudioFormatProbe>()
    private var lastFormat: Format? = null

    override fun onAudioInputFormatChanged(
        eventTime: AnalyticsListener.EventTime,
        format: Format,
        decoderReuseEvaluation: DecoderReuseEvaluation?,
    ) {
        if (format == lastFormat) return
        lastFormat = format
        logger.warn { "audio input format: ${format.describeForProbe()}" }
    }

    override fun onAudioDecoderInitialized(
        eventTime: AnalyticsListener.EventTime,
        decoderName: String,
        initializedTimestampMs: Long,
        initializationDurationMs: Long,
    ) {
        logger.warn { "audio decoder initialized: name=$decoderName, took=${initializationDurationMs}ms" }
    }

    override fun onAudioDecoderReleased(eventTime: AnalyticsListener.EventTime, decoderName: String) {
        logger.warn { "audio decoder released: name=$decoderName" }
    }

    override fun onAudioCodecError(eventTime: AnalyticsListener.EventTime, error: Exception) {
        logger.warn(error) { "audio codec error" }
    }

    override fun onAudioSinkError(eventTime: AnalyticsListener.EventTime, error: Exception) {
        logger.warn(error) { "audio sink error" }
    }

    override fun onPlayerError(eventTime: AnalyticsListener.EventTime, error: PlaybackException) {
        logger.warn(error) { "player error: code=${error.errorCodeName}" }
    }
}

private fun Format.describeForProbe(): String = buildString {
    append("mime=").append(sampleMimeType)
    append(", sampleRate=").append(sampleRate)
    append(", channels=").append(channelCount)
    append(", pcmEncoding=").append(pcmEncoding)
    append(", bitrate=").append(bitrate)
    append(", csdCount=").append(initializationData.size)
    initializationData.forEachIndexed { index, bytes ->
        append(", csd[").append(index).append("].len=").append(bytes.size)
        append(", csd[").append(index).append("].head=").append(bytes.take(8).toHex())
    }
}

private fun ByteArray.toHex(): String =
    take(8).joinToString(" ") { byte -> String.format(Locale.ROOT, "%02X", byte.toInt() and 0xFF) }
