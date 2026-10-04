/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.domain.media.resolver

import kotlinx.coroutines.CoroutineScope
import kotlinx.io.files.Path
import me.him188.ani.app.domain.media.player.data.MediaDataProvider
import me.him188.ani.app.domain.media.player.data.SystemFileMediaDataProvider
import me.him188.ani.datasources.api.Media
import me.him188.ani.datasources.api.topic.ResourceLocation
import org.openani.mediamp.source.MediaExtraFiles
import org.openani.mediamp.source.UriMediaData

/**
 * Android 的本地文件 resolver.
 *
 * 通过 SAF 导入的文件路径是 `content://` URI, 不能当作文件系统路径打开;
 * 交给 ExoPlayer 的 ContentDataSource 播放 (见 [UriMediaData]), 其余仍是普通文件路径.
 */
class AndroidLocalFileMediaResolver : MediaResolver {
    override fun supports(media: Media): Boolean {
        return media.download is ResourceLocation.LocalFile
    }

    override suspend fun resolve(media: Media, episode: EpisodeMetadata): MediaDataProvider<*> {
        val download = media.download as? ResourceLocation.LocalFile
            ?: throw UnsupportedMediaException(media)
        return if (download.filePath.startsWith(CONTENT_SCHEME_PREFIX)) {
            AndroidLocalFileUriMediaDataProvider(
                uri = download.filePath,
                originalTitle = media.originalTitle,
                extraFiles = media.extraFiles.toMediampMediaExtraFiles(),
            )
        } else {
            SystemFileMediaDataProvider(
                Path(download.filePath),
                media.extraFiles.toMediampMediaExtraFiles(),
                fileType = download.fileType,
            )
        }
    }

    private companion object {
        const val CONTENT_SCHEME_PREFIX = "content:"
    }
}

class AndroidLocalFileUriMediaDataProvider(
    val uri: String,
    val originalTitle: String,
    private val headers: Map<String, String> = emptyMap(),
    override val extraFiles: MediaExtraFiles = MediaExtraFiles.EMPTY,
) : MediaDataProvider<UriMediaData> {
    override suspend fun open(scopeForCleanup: CoroutineScope): UriMediaData =
        UriMediaData(uri, headers, extraFiles)

    override fun toString(): String = "AndroidLocalFileUriMediaDataProvider(uri='$uri')"
}
