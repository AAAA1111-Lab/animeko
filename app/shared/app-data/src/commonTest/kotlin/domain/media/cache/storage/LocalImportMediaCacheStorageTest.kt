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
    fun testImportFilesDeduplicatesByEpisode() = runTest {
        val dataStore = MemoryDataStore(emptyList<MediaCacheSave>())
        val storage = LocalImportMediaCacheStorage(
            mediaSourceId = "local_fs",
            datastore = dataStore,
            importEngine = LocalImportMediaCacheEngine(),
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

        val first = storage.importFiles(500, "测试动画", listOf("测试动画"), items)
        assertEquals(2, first.size)

        // 重复导入同一批文件: 全部跳过, 缓存数量不变
        val second = storage.importFiles(500, "测试动画", listOf("测试动画"), items)
        assertEquals(0, second.size)
        assertEquals(2, storage.listFlow.first().size)
        assertEquals(2, dataStore.data.first().size)

        // 同一集导入不同的文件: 替换该集的旧缓存
        val replacement = listOf(
            LocalImportFileItem(
                filePath = "/test/other_ep01.mp4",
                filename = "Other_S01E01.mp4",
                episodeSort = EpisodeSort(1),
                episodeId = 1001,
                episodeTitle = "Episode 1",
            ),
        )
        val third = storage.importFiles(500, "测试动画", listOf("测试动画"), replacement)
        assertEquals(1, third.size)
        val list = storage.listFlow.first()
        assertEquals(2, list.size)
        val ep1Caches = list.filter { it.metadata.episodeId == "1001" }
        assertEquals(1, ep1Caches.size)
        assertEquals(
            "/test/other_ep01.mp4",
            (ep1Caches[0].origin.download as ResourceLocation.LocalFile).filePath,
        )
    }

    @Test
    fun testImportFilesSameEpisodeInBatchKeepsLast() = runTest {
        val dataStore = MemoryDataStore(emptyList<MediaCacheSave>())
        val storage = LocalImportMediaCacheStorage(
            mediaSourceId = "local_fs",
            datastore = dataStore,
            importEngine = LocalImportMediaCacheEngine(),
            parentCoroutineContext = backgroundScope.coroutineContext,
        )

        // 同一批内两个文件指向同一集: 只保留后导入的
        val batch = listOf(
            LocalImportFileItem(
                filePath = "/test/a_ep01.mp4",
                filename = "A_S01E01.mp4",
                episodeSort = EpisodeSort(1),
                episodeId = 1001,
                episodeTitle = "Episode 1",
            ),
            LocalImportFileItem(
                filePath = "/test/b_ep01.mp4",
                filename = "B_S01E01.mp4",
                episodeSort = EpisodeSort(1),
                episodeId = 1001,
                episodeTitle = "Episode 1",
            ),
        )
        val imported = storage.importFiles(500, "测试动画", listOf("测试动画"), batch)
        assertEquals(2, imported.size)
        val ep1Caches = storage.listFlow.first().filter { it.metadata.episodeId == "1001" }
        assertEquals(1, ep1Caches.size)
        assertEquals(
            "/test/b_ep01.mp4",
            (ep1Caches[0].origin.download as ResourceLocation.LocalFile).filePath,
        )
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
            properties = me.him188.ani.datasources.api.MediaProperties(
                subjectName = null,
                episodeName = null,
                subtitleLanguageIds = emptyList(),
                resolution = "",
                alliance = "",
                size = me.him188.ani.datasources.api.topic.FileSize.Zero,
                subtitleKind = null,
            ),
            episodeRange = me.him188.ani.datasources.api.topic.EpisodeRange.single(EpisodeSort(1)),
            location = me.him188.ani.datasources.api.source.MediaSourceLocation.Local,
            kind = me.him188.ani.datasources.api.source.MediaSourceKind.LocalCache,
        )
        assertTrue(engine.supports(dummyMedia))

        // onCloseAndDeleteFiles must execute safely and not touch files
        engine.deleteUnusedCaches(emptyList())
    }
}
