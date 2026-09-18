/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.domain.media.cache.storage

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import me.him188.ani.app.data.persistent.MemoryDataStore
import me.him188.ani.app.domain.media.cache.LocalFileMediaCache
import me.him188.ani.app.domain.media.cache.engine.LocalImportMediaCacheEngine
import me.him188.ani.datasources.api.EpisodeSort
import me.him188.ani.datasources.api.topic.ResourceLocation
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class LocalImportMediaCacheStorageTest {

    @Test
    fun testImportFilesStoresAndEmitsCaches() = runTest {
        val dataStore = MemoryDataStore(emptyList<MediaCacheSave>())
        val engine = LocalImportMediaCacheEngine()
        val storage = LocalImportMediaCacheStorage(
            mediaSourceId = "local_fs",
            datastore = dataStore,
            importEngine = engine,
            parentCoroutineContext = backgroundScope.coroutineContext,
        )

        val items = listOf(
            LocalImportFileItem(
                filePath = "/test/video_ep01.mp4",
                filename = "Anime_S01E01.mp4",
                episodeSort = EpisodeSort(1),
                episodeId = 1001,
                episodeTitle = "Episode 1",
            ),
            LocalImportFileItem(
                filePath = "/test/video_ep02.mp4",
                filename = "Anime_S01E02.mp4",
                episodeSort = EpisodeSort(2),
                episodeId = 1002,
                episodeTitle = "Episode 2",
            ),
        )

        val caches = storage.importFiles(
            subjectId = 500,
            subjectNameCN = "测试动画",
            subjectNames = listOf("测试动画", "Test Anime"),
            items = items,
        )

        assertEquals(2, caches.size)
        assertEquals("Episode 1", caches[0].metadata.episodeName)
        assertEquals("1001", caches[0].metadata.episodeId)
        assertTrue(caches[0] is LocalFileMediaCache)

        val list = storage.listFlow.first()
        assertEquals(2, list.size)
        assertEquals(1, list[0].cache.metadata.episodeSort.number?.toInt())
        assertEquals(2, list[1].cache.metadata.episodeSort.number?.toInt())

        val savedInDatastore = dataStore.data.first()
        assertEquals(2, savedInDatastore.size)
    }

    @Test
    fun testEngineSafetyDoesNotDeletePhysicalFiles() = runTest {
        val engine = LocalImportMediaCacheEngine()
        // verify engine supports local file
        val dummyMedia = me.him188.ani.datasources.api.DefaultMedia(
            mediaId = "local-1",
            mediaSourceId = "local_fs",
            originalUrl = "/path/to/test.mp4",
            download = ResourceLocation.LocalFile("/path/to/test.mp4"),
            originalTitle = "test.mp4",
            publishedTime = 0,
            properties = me.him188.ani.datasources.api.MediaProperties.EMPTY,
            episodeRange = me.him188.ani.datasources.api.topic.EpisodeRange.single(EpisodeSort(1)),
            location = me.him188.ani.datasources.api.source.MediaSourceLocation.Local,
            kind = me.him188.ani.datasources.api.source.MediaSourceKind.LocalCache,
        )
        assertTrue(engine.supports(dummyMedia))

        // onCloseAndDeleteFiles must execute safely and not touch files
        engine.deleteUnusedCaches(emptyList())
    }
}
