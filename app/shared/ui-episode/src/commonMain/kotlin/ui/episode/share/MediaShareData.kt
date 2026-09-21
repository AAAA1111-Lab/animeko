/*
 * Copyright (C) 2024-2025 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.ui.episode.share

import androidx.compose.runtime.Immutable
import me.him188.ani.app.domain.media.player.data.TorrentMediaData
import me.him188.ani.datasources.api.Media
import me.him188.ani.datasources.api.topic.ResourceLocation
import org.openani.mediamp.source.MediaData
import org.openani.mediamp.source.SeekableInputMediaData
import org.openani.mediamp.source.UriMediaData


@Immutable
data class MediaShareData(
    val websiteUrl: String?,
    val download: ResourceLocation?
) {
    companion object {
        fun from(
            media: Media?,
            mediaData: MediaData?,
        ): MediaShareData {
            val realDownload = when (mediaData) {
                is UriMediaData -> {
                    // 本地导入的媒体在 Android 上是 SAF `content://` URI, 也允许 `file://`.
                    // 这类地址不是流式链接, 必须走 [ResourceLocation.LocalFile] ——
                    // [ResourceLocation.HttpStreamingFile] 会在构造时拒绝它们并抛异常.
                    // (播放期间每条 pageState 都会走到这里, 所以用错类型会让整个页面挂掉.)
                    if (mediaData.uri.isLocalContentUri()) {
                        ResourceLocation.LocalFile(mediaData.uri)
                    } else {
                        ResourceLocation.HttpStreamingFile(mediaData.uri)
                    }
                }

                is TorrentMediaData,
                is SeekableInputMediaData,
                null -> {
                    media?.download
                }
            }

            return MediaShareData(
                websiteUrl = media?.originalUrl,
                download = realDownload,
            )
        }

        private fun String.isLocalContentUri(): Boolean {
            val scheme = substringBefore(':', missingDelimiterValue = "").lowercase()
            return scheme == "content" || scheme == "file"
        }
    }
}
