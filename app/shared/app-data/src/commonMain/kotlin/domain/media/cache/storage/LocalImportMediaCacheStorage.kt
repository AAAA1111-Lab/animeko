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

    suspend fun importFiles(
        subjectId: Int,
        subjectNameCN: String?,
        subjectNames: List<String>,
        items: List<LocalImportFileItem>,
    ): List<MediaCache> = withContext(Dispatchers.IO_) {
        items.map { item ->
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
            cache(media, metadata, episodeMetadata, resume = false)
        }
    }
}
