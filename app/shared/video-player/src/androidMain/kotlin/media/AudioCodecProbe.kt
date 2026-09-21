/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.videoplayer.media

import android.media.MediaCodecList
import android.os.Build
import me.him188.ani.utils.logging.logger
import me.him188.ani.utils.logging.warn

/**
 * 临时诊断: 列出本机所有能解码 `audio/flac` 的解码器, 判断是否存在可用的**软件**解码器.
 *
 * 背景: 这台设备上 `OMX.qti.audio.decoder.flac` 对 24-bit FLAC 会在喂入第一帧时崩溃
 * (`ERROR_CODE_DECODING_FAILED`), 本地导入与 Jellyfin 都受影响; VLC / mpv 因为自带软解所以正常.
 * 如果这里能列出软件解码器, 就可以用 Media3 的 `MediaCodecSelector.PREFER_SOFTWARE` 绕过它.
 *
 * 只读取列表并写日志, 不改变任何播放行为. 定位完成后删除本文件即可.
 */
internal object AudioCodecProbe {
    private val logger = logger("AudioCodecProbe")
    private var alreadyLogged = false

    fun logFlacDecodersOnce() {
        if (alreadyLogged) return
        alreadyLogged = true
        runCatching { logFlacDecoders() }
            .onFailure { logger.warn(it) { "failed to enumerate audio/flac decoders" } }
    }

    private fun logFlacDecoders() {
        val decoders = MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos
            .filter { info ->
                !info.isEncoder && info.supportedTypes.any { it.equals("audio/flac", ignoreCase = true) }
            }
        logger.warn {
            buildString {
                append("audio/flac decoders: ").append(decoders.size)
                decoders.forEach { info ->
                    append(" | ").append(info.name)
                    // API 29+ 才有 isSoftwareOnly; 26 上改用命名约定判断 (google/c2.android = 软解)
                    append(" softwareOnly=")
                    append(
                        if (Build.VERSION.SDK_INT >= 29) {
                            info.isSoftwareOnly
                        } else {
                            val n = info.name.lowercase()
                            n.startsWith("omx.google.") || n.startsWith("c2.android.") || ".sw." in n
                        },
                    )
                }
            }
        }
    }
}
