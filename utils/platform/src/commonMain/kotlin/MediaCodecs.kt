/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.utils.platform

/**
 * Audio codec names used by Jellyfin/Emby, mapped to the decoder MIME type used by the platform.
 *
 * [mimeType] 为 `null` 表示该编码在本项目支持的所有平台上都无法保证解码, 因此调用方应该
 * 请求服务端转码, 而不是尝试直接播放.
 */
internal val audioCodecMimeTypes: Map<String, String?> = mapOf(
    "flac" to "audio/flac",
    "truehd" to "audio/true-hd",
    "mlp" to "audio/mlp",
    "dts" to "audio/vnd.dts",
    "dca" to "audio/vnd.dts",
    "alac" to "audio/alac",
)

/**
 * 判断当前平台是否有能够解码 [codec] 的音频解码器.
 *
 * 用于决定是否需要请求服务端把音频转码为通用格式. 典型场景是 Android 8.0 (API 26) 及以下的
 * 系统没有内置 FLAC 解码器, 直接播放 Jellyfin 的 FLAC 音轨会失败.
 *
 * 未知的 [codec] 返回 `true`, 即默认交给播放器尝试.
 */
expect fun isAudioCodecSupported(codec: String): Boolean
