/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.domain.media.cache.engine

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.io.files.Path
import me.him188.ani.app.domain.media.cache.LocalFileMediaCache
import me.him188.ani.app.domain.media.cache.MediaCache
import me.him188.ani.app.domain.media.cache.MediaCacheManager
import me.him188.ani.app.domain.media.resolver.EpisodeMetadata
import me.him188.ani.datasources.api.CachedMedia
import me.him188.ani.datasources.api.Media
import me.him188.ani.datasources.api.MediaCacheMetadata
import me.him188.ani.datasources.api.topic.ResourceLocation
import me.him188.ani.utils.io.SystemPath
import me.him188.ani.utils.io.inSystem
import kotlin.coroutines.CoroutineContext

class LocalImportMediaCacheEngine(
    override val engineKey: MediaCacheEngineKey = ENGINE_KEY,
    /**
     * 检查导入文件是否仍然可访问. Android 上导入的是 SAF `content://` URI, 需要平台实现通过 ContentResolver 检查.
     */
    val fileAccess: LocalImportFileAccess = SystemLocalImportFileAccess(),
) : MediaCacheEngine {
    companion object {
        val ENGINE_KEY = MediaCacheEngineKey("local-file-import")
    }

    override val stats: Flow<MediaStats> = MutableStateFlow(MediaStats.Zero)

    override fun supports(media: Media): Boolean {
        return media.download is ResourceLocation.LocalFile
    }

    override suspend fun restore(
        origin: Media,
        metadata: MediaCacheMetadata,
        parentContext: CoroutineContext,
    ): MediaCache? {
        val download = origin.download as? ResourceLocation.LocalFile ?: return null
        if (!fileAccess.exists(download.filePath)) {
            return null
        }
        val path = Path(download.filePath).inSystem
        return LocalImportMediaCache(
            origin = origin,
            metadata = metadata,
            file = path,
        )
    }

    override suspend fun createCache(
        origin: Media,
        metadata: MediaCacheMetadata,
        episodeMetadata: EpisodeMetadata,
        parentContext: CoroutineContext,
    ): MediaCache {
        val download = origin.download as? ResourceLocation.LocalFile
            ?: throw IllegalArgumentException("Expected LocalFile but was ${origin.download}")
        val path = Path(download.filePath).inSystem
        return LocalImportMediaCache(
            origin = origin,
            metadata = metadata,
            file = path,
        )
    }

    override suspend fun deleteUnusedCaches(all: List<MediaCache>) {
        // No-op for imported files
    }
}

/**
 * 导入的本地视频对应的 [LocalFileMediaCache].
 *
 * 覆写 [getCachedMedia]: Android 上导入的是 SAF `content://` URI, 基类默认会用
 * `file.absolutePath` 把它改写成无效的文件路径, 导致播放时找不到文件.
 * 这里原样透传 [origin.download], 播放时由 Android 平台的 LocalFile resolver 解析.
 */
private class LocalImportMediaCache(
    origin: Media,
    metadata: MediaCacheMetadata,
    file: SystemPath,
) : LocalFileMediaCache(
    origin = origin,
    metadata = metadata,
    file = file,
    backedMediaSourceId = MediaCacheManager.LOCAL_FS_MEDIA_SOURCE_ID,
    onCloseAndDeleteFiles = {
        // Safety: Do NOT delete user's original imported media files on disk!
    },
) {
    override suspend fun getCachedMedia(): CachedMedia {
        return CachedMedia(origin, MediaCacheManager.LOCAL_FS_MEDIA_SOURCE_ID, origin.download)
    }
}
