/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.domain.media.cache.storage

import androidx.datastore.core.DataStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import me.him188.ani.app.domain.media.cache.MediaCache
import me.him188.ani.app.domain.media.cache.engine.LocalImportMediaCacheEngine
import me.him188.ani.app.domain.media.resolver.EpisodeMetadata
import me.him188.ani.datasources.api.DefaultMedia
import me.him188.ani.datasources.api.EpisodeSort
import me.him188.ani.datasources.api.MediaCacheMetadata
import me.him188.ani.datasources.api.MediaProperties
import me.him188.ani.datasources.api.source.MediaSourceKind
import me.him188.ani.datasources.api.source.MediaSourceLocation
import me.him188.ani.datasources.api.topic.EpisodeRange
import me.him188.ani.datasources.api.topic.FileSize
import me.him188.ani.datasources.api.topic.ResourceLocation
import me.him188.ani.utils.coroutines.IO_
import me.him188.ani.utils.platform.currentTimeMillis
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.EmptyCoroutineContext

data class LocalImportFileItem(
    val filePath: String,
    val filename: String,
    val episodeSort: EpisodeSort,
    val episodeId: Int,
    val episodeTitle: String,
)

private val EmptyMediaProperties = MediaProperties(
    subjectName = null,
    episodeName = null,
    subtitleLanguageIds = emptyList(),
    resolution = "",
    alliance = "",
    size = FileSize.Zero,
    subtitleKind = null,
)

class LocalImportMediaCacheStorage(
    override val mediaSourceId: String,
    private val datastore: DataStore<List<MediaCacheSave>>,
    private val importEngine: LocalImportMediaCacheEngine,
    displayName: String = "LocalImport",
    parentCoroutineContext: CoroutineContext = EmptyCoroutineContext,
) : AbstractDataStoreMediaCacheStorage(
    mediaSourceId = mediaSourceId,
    datastore = datastore,
    engine = importEngine,
    displayName = displayName,
    parentCoroutineContext = parentCoroutineContext,
) {
    override suspend fun restorePersistedCaches() {
        refreshCache()
    }

    /**
     * 导入本地视频文件为已完成缓存. 每一集只保留一个导入缓存:
     * - 重复导入同一个文件: 跳过, 并清理旧版本残留的同一文件的重复缓存;
     * - 同一集导入了不同的文件: 替换该集的全部旧缓存.
     *
     * 导入是软引用, 删除应用内缓存不会删除磁盘上的原视频.
     *
     * @return 实际新导入的 [MediaCache] 列表, 跳过的重复文件不在其中.
     */
    suspend fun importFiles(
        subjectId: Int,
        subjectNameCN: String?,
        subjectNames: List<String>,
        items: List<LocalImportFileItem>,
    ): List<MediaCache> = withContext(Dispatchers.IO_) {
        // Android 上导入的是 SAF content:// URI, 临时授权在应用重启后失效, 需要尽早持久化.
        importEngine.fileAccess.persistReadPermissions(items.map { it.filePath })

        val existingByEpisode = HashMap(
            listFlow.first()
                .filter { it.metadata.subjectId == subjectId.toString() }
                .groupBy { it.metadata.episodeId },
        )

        val imported = ArrayList<MediaCache>(items.size)
        for (item in items) {
            val episodeKey = item.episodeId.toString()
            val existingForEpisode = existingByEpisode[episodeKey].orEmpty()
            val filePathOf = { cache: MediaCache ->
                (cache.origin.download as? ResourceLocation.LocalFile)?.filePath
            }
            val identical = existingForEpisode.firstOrNull { filePathOf(it) == item.filePath }
            if (identical != null) {
                existingForEpisode
                    .filter { it !== identical && filePathOf(it) == item.filePath }
                    .forEach { delete(it) }
                existingByEpisode[episodeKey] = listOf(identical)
                continue
            }
            existingForEpisode.forEach { delete(it) }
            val newCache = importOne(subjectId, subjectNameCN, subjectNames, item)
            existingByEpisode[episodeKey] = listOf(newCache)
            imported.add(newCache)
        }
        imported
    }

    private suspend fun importOne(
        subjectId: Int,
        subjectNameCN: String?,
        subjectNames: List<String>,
        item: LocalImportFileItem,
    ): MediaCache {
        val mediaId = "local-import-$subjectId-${item.episodeId}-${currentTimeMillis()}-${item.filename.hashCode()}"
        val media = DefaultMedia(
            mediaId = mediaId,
            mediaSourceId = mediaSourceId,
            originalUrl = item.filePath,
            download = ResourceLocation.LocalFile(item.filePath),
            originalTitle = item.filename,
            publishedTime = currentTimeMillis(),
            properties = EmptyMediaProperties.copy(
                subjectName = subjectNameCN,
                episodeName = item.episodeTitle,
            ),
            episodeRange = EpisodeRange.single(item.episodeSort),
            location = MediaSourceLocation.Local,
            kind = MediaSourceKind.LocalCache,
        )
        val metadata = MediaCacheMetadata(
            subjectId = subjectId.toString(),
            episodeId = item.episodeId.toString(),
            subjectNameCN = subjectNameCN,
            subjectNames = subjectNames,
            episodeSort = item.episodeSort,
            episodeEp = item.episodeSort,
            episodeName = item.episodeTitle,
            creationTime = currentTimeMillis(),
            autoCached = false,
        )
        val episodeMetadata = EpisodeMetadata(
            title = item.episodeTitle,
            ep = item.episodeSort,
            sort = item.episodeSort,
        )
        return cache(media, metadata, episodeMetadata, resume = false)
    }
}
