/*
 * Copyright (C) 2024-2025 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.domain.danmaku

import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import me.him188.ani.app.data.models.danmaku.DanmakuFilterConfig
import me.him188.ani.app.data.models.preference.AnalyticsSettings
import me.him188.ani.app.data.models.preference.AnitorrentConfig
import me.him188.ani.app.data.models.preference.DanmakuCacheStrategy
import me.him188.ani.app.data.models.preference.DanmakuSettings
import me.him188.ani.app.data.models.preference.DebugSettings
import me.him188.ani.app.data.models.preference.MediaCacheSettings
import me.him188.ani.app.data.models.preference.MediaPreference
import me.him188.ani.app.data.models.preference.MediaSelectorSettings
import me.him188.ani.app.data.models.preference.OneshotActionConfig
import me.him188.ani.app.data.models.preference.PikPakConfig
import me.him188.ani.app.data.models.preference.PlayerKernelConfig
import me.him188.ani.app.data.models.preference.ProfileSettings
import me.him188.ani.app.data.models.preference.ProxySettings
import me.him188.ani.app.data.models.preference.ThemeSettings
import me.him188.ani.app.data.models.preference.TorrentPeerConfig
import me.him188.ani.app.data.models.preference.UISettings
import me.him188.ani.app.data.models.preference.UpdateSettings
import me.him188.ani.app.data.models.preference.VideoResolverSettings
import me.him188.ani.app.data.models.preference.VideoScaffoldConfig
import me.him188.ani.app.data.models.preference.WatchTogetherSettings
import me.him188.ani.app.data.models.subject.SubjectSeriesInfo
import me.him188.ani.app.data.models.subject.TestSubjectCollections
import me.him188.ani.app.data.persistent.database.dao.AutoCachedEpisode
import me.him188.ani.app.data.persistent.database.dao.CachedDanmakuEpisode
import me.him188.ani.app.data.persistent.database.dao.DanmakuCacheOrigin
import me.him188.ani.app.data.persistent.database.dao.DanmakuDao
import me.him188.ani.app.data.persistent.database.dao.DanmakuEntity
import me.him188.ani.app.data.persistent.database.dao.EpisodeDanmakuCount
import me.him188.ani.app.data.repository.user.Settings
import me.him188.ani.app.data.repository.user.SettingsRepository
import me.him188.ani.app.domain.episode.GetSubjectEpisodeInfoBundleFlowUseCase
import me.him188.ani.app.domain.episode.SubjectEpisodeInfoBundle
import me.him188.ani.app.domain.foundation.DefaultHttpClientProvider
import me.him188.ani.app.domain.foundation.HttpClientProvider
import me.him188.ani.app.domain.media.cache.GetMediaCacheUseCase
import me.him188.ani.app.domain.media.cache.MediaCache
import me.him188.ani.app.domain.media.cache.MediaCacheState
import me.him188.ani.app.domain.settings.NoProxyProvider
import me.him188.ani.client.apis.DanmakuAniApi
import me.him188.ani.danmaku.api.DanmakuContent
import me.him188.ani.danmaku.api.DanmakuInfo
import me.him188.ani.danmaku.api.DanmakuLocation
import me.him188.ani.danmaku.api.DanmakuServiceId
import me.him188.ani.danmaku.api.provider.DanmakuFetchResult
import me.him188.ani.danmaku.api.provider.DanmakuMatchInfo
import me.him188.ani.danmaku.api.provider.DanmakuMatchMethod
import me.him188.ani.danmaku.api.provider.DanmakuProviderId
import me.him188.ani.danmaku.ui.DanmakuConfig
import me.him188.ani.datasources.api.CachedMedia
import me.him188.ani.datasources.api.topic.UnifiedCollectionType
import me.him188.ani.utils.ktor.ApiInvoker
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class DanmakuCacheTest {
    @Test
    fun `DON_NOT_CACHE never saves`() = runTest {
        val dao = TestDanmakuDao()
        val repository = createRepository(
            strategy = DanmakuCacheStrategy.DON_NOT_CACHE,
            hasMediaCache = true,
            collectionType = UnifiedCollectionType.DOING,
            danmakuDao = dao,
        )

        repository.cacheDanmakuIfNeeded(SUBJECT_ID, EPISODE_ID, createFetchResults()).join()

        assertEquals(0, dao.upsertCalls.size)
    }

    @Test
    fun `CACHE_ON_MEDIA_CACHE requires cache`() = runTest {
        val dao = TestDanmakuDao()
        val repository = createRepository(
            strategy = DanmakuCacheStrategy.CACHE_ON_MEDIA_CACHE,
            hasMediaCache = false,
            collectionType = UnifiedCollectionType.DOING,
            danmakuDao = dao,
        )

        repository.cacheDanmakuIfNeeded(SUBJECT_ID, EPISODE_ID, createFetchResults()).join()

        assertEquals(0, dao.upsertCalls.size)
    }

    @Test
    fun `CACHE_ON_MEDIA_CACHE saves when cache exists`() = runTest {
        val dao = TestDanmakuDao()
        val repository = createRepository(
            strategy = DanmakuCacheStrategy.CACHE_ON_MEDIA_CACHE,
            hasMediaCache = true,
            collectionType = UnifiedCollectionType.WISH,
            danmakuDao = dao,
        )

        repository.cacheDanmakuIfNeeded(SUBJECT_ID, EPISODE_ID, createFetchResults()).join()

        assertEquals(1, dao.upsertCalls.single().size)
    }

    @Test
    fun `CACHE_ON_COLLECTION_DOING_MEDIA_PLAY saves when doing`() = runTest {
        val dao = TestDanmakuDao()
        val repository = createRepository(
            strategy = DanmakuCacheStrategy.CACHE_ON_COLLECTION_DOING_MEDIA_PLAY,
            hasMediaCache = false,
            collectionType = UnifiedCollectionType.DOING,
            danmakuDao = dao,
        )

        repository.cacheDanmakuIfNeeded(SUBJECT_ID, EPISODE_ID, createFetchResults()).join()

        assertEquals(1, dao.upsertCalls.single().size)
    }

    @Test
    fun `CACHE_ON_COLLECTION_DOING_MEDIA_PLAY saves when cache exists`() = runTest {
        val dao = TestDanmakuDao()
        val repository = createRepository(
            strategy = DanmakuCacheStrategy.CACHE_ON_COLLECTION_DOING_MEDIA_PLAY,
            hasMediaCache = true,
            collectionType = UnifiedCollectionType.WISH,
            danmakuDao = dao,
        )

        repository.cacheDanmakuIfNeeded(SUBJECT_ID, EPISODE_ID, createFetchResults()).join()

        assertEquals(1, dao.upsertCalls.single().size)
    }

    @Test
    fun `CACHE_ON_COLLECTION_DOING_MEDIA_PLAY skips when not doing and no cache`() = runTest {
        val dao = TestDanmakuDao()
        val repository = createRepository(
            strategy = DanmakuCacheStrategy.CACHE_ON_COLLECTION_DOING_MEDIA_PLAY,
            hasMediaCache = false,
            collectionType = UnifiedCollectionType.WISH,
            danmakuDao = dao,
        )

        repository.cacheDanmakuIfNeeded(SUBJECT_ID, EPISODE_ID, createFetchResults()).join()

        assertEquals(0, dao.upsertCalls.size)
    }

    @Test
    fun `自动清理只删除自动缓存, 手动缓存保留`() = runTest {
        val dao = TestDanmakuDao()
        val repository = createRepository(
            strategy = DanmakuCacheStrategy.DON_NOT_CACHE,
            hasMediaCache = false,
            collectionType = UnifiedCollectionType.WISH,
            danmakuDao = dao,
        )

        // 用户主动缓存
        repository.saveToLocalCache(SUBJECT_ID, EPISODE_ID, createFetchResults())
        val manualIds = dao.storedDanmaku().map { it.id }.toSet()
        assertTrue(manualIds.isNotEmpty(), "前置条件: 手动缓存应当写入了弹幕")
        assertTrue(dao.storedDanmaku().all { it.origin == DanmakuCacheOrigin.MANUAL })

        // 策略变成不缓存, 触发自动清理
        repository.deleteDanmakuIfDontNeeded(SUBJECT_ID, EPISODE_ID).join()

        assertEquals(manualIds, dao.storedDanmaku().map { it.id }.toSet())
    }

    @Test
    fun `自动写入不会把手动缓存降级成自动缓存`() = runTest {
        val dao = TestDanmakuDao()
        val repository = createRepository(
            strategy = DanmakuCacheStrategy.CACHE_ON_MEDIA_CACHE,
            hasMediaCache = true,
            collectionType = UnifiedCollectionType.DOING,
            danmakuDao = dao,
        )

        repository.saveToLocalCache(SUBJECT_ID, EPISODE_ID, createFetchResults())
        val manualIds = dao.storedDanmaku().map { it.id }.toSet()

        // 同一批弹幕随后又被自动缓存写入
        repository.cacheDanmakuIfNeeded(SUBJECT_ID, EPISODE_ID, createFetchResults()).join()

        val rows = dao.storedDanmaku().filter { it.id in manualIds }
        assertEquals(manualIds.size, rows.size)
        assertTrue(
            rows.all { it.origin == DanmakuCacheOrigin.MANUAL },
            "手动缓存被降级成自动缓存, 之后会被自动清理删掉",
        )
    }

    private fun TestScope.createRepository(
        strategy: DanmakuCacheStrategy,
        hasMediaCache: Boolean,
        collectionType: UnifiedCollectionType,
        danmakuDao: DanmakuDao,
    ): DanmakuRepository {
        val settingsRepository = TestSettingsRepository(strategy)
        val mediaCacheUseCase = TestGetMediaCacheUseCase(hasMediaCache)
        val subjectInfoBundleUseCase = TestGetSubjectEpisodeInfoBundleFlowUseCase(collectionType)

        return DanmakuRepository(
            parentCoroutineContext = backgroundScope.coroutineContext,
            danmakuApi = UnusedApiInvoker,
            danmakuDao = danmakuDao,
            httpClientProvider = backgroundScope.run { TestHttpClientProvider() },
            getMediaCacheUseCase = mediaCacheUseCase,
            getSubjectEpisodeInfoBundleFlowUseCase = subjectInfoBundleUseCase,
            settingsRepository = settingsRepository,
        )
    }

    @Suppress("TestFunctionName")
    private fun TestScope.TestHttpClientProvider(): HttpClientProvider {
        return DefaultHttpClientProvider(NoProxyProvider, this).apply {
            backgroundScope.coroutineContext.job.invokeOnCompletion {
                launch(NonCancellable) {
                    forceReleaseAll()
                }
            }
        }
    }

    private fun createFetchResults(
        subjectId: Int = SUBJECT_ID,
        episodeId: Int = EPISODE_ID,
    ): List<DanmakuFetchResult> = listOf(
        DanmakuFetchResult(
            providerId = DanmakuProviderId.Animeko,
            matchInfo = DanmakuMatchInfo(
                serviceId = DanmakuServiceId.Animeko,
                count = 1,
                method = DanmakuMatchMethod.ExactId(subjectId, episodeId),
            ),
            list = listOf(
                DanmakuInfo(
                    id = "id-$subjectId-$episodeId",
                    serviceId = DanmakuServiceId.Animeko,
                    senderId = "sender",
                    content = DanmakuContent(
                        playTimeMillis = 0,
                        color = 0,
                        text = "Hello",
                        location = DanmakuLocation.NORMAL,
                    ),
                ),
            ),
        ),
    )

    private class TestGetMediaCacheUseCase(
        private val hasCache: Boolean,
    ) : GetMediaCacheUseCase {
        override suspend fun invoke(subjectId: Int, episodeId: Int): List<MediaCache> {
            return if (hasCache) listOf(TestMediaCache) else emptyList()
        }
    }

    private class TestGetSubjectEpisodeInfoBundleFlowUseCase(
        private val collectionType: UnifiedCollectionType,
    ) : GetSubjectEpisodeInfoBundleFlowUseCase {
        override fun invoke(idsFlow: Flow<GetSubjectEpisodeInfoBundleFlowUseCase.SubjectIdAndEpisodeId>): Flow<SubjectEpisodeInfoBundle> {
            return idsFlow.map { (subjectId, episodeId) ->
                createBundle(collectionType, subjectId, episodeId)
            }
        }
    }

    private class TestSettingsRepository(
        initialStrategy: DanmakuCacheStrategy,
    ) : SettingsRepository {
        private val mediaCacheState = MutableStateFlow(MediaCacheSettings(danmakuCacheStrategy = initialStrategy))

        override val mediaCacheSettings: Settings<MediaCacheSettings> = object : Settings<MediaCacheSettings> {
            override val flow: Flow<MediaCacheSettings> = mediaCacheState
            override suspend fun set(value: MediaCacheSettings) {
                mediaCacheState.value = value
            }
        }

        override val danmakuEnabled: Settings<Boolean> by lazy { error("no implemented") }
        override val danmakuConfig: Settings<DanmakuConfig> by lazy { error("no implemented") }
        override val danmakuFilterConfig: Settings<DanmakuFilterConfig> by lazy { error("no implemented") }
        override val mediaSelectorSettings: Settings<MediaSelectorSettings> by lazy { error("no implemented") }
        override val defaultMediaPreference: Settings<MediaPreference> by lazy { error("no implemented") }
        override val profileSettings: Settings<ProfileSettings> by lazy { error("no implemented") }
        override val proxySettings: Settings<ProxySettings> by lazy { error("no implemented") }
        override val danmakuSettings: Settings<DanmakuSettings> by lazy { error("no implemented") }
        override val uiSettings: Settings<UISettings> by lazy { error("no implemented") }
        override val themeSettings: Settings<ThemeSettings> by lazy { error("no implemented") }
        override val updateSettings: Settings<UpdateSettings> by lazy { error("no implemented") }
        override val videoScaffoldConfig: Settings<VideoScaffoldConfig> by lazy { error("no implemented") }
        override val playerKernelConfig: Settings<PlayerKernelConfig> by lazy { error("no implemented") }
        override val videoResolverSettings: Settings<VideoResolverSettings> by lazy { error("no implemented") }
        override val anitorrentConfig: Settings<AnitorrentConfig> by lazy { error("no implemented") }
        override val pikpakConfig: Settings<PikPakConfig> by lazy { error("no implemented") }
        override val torrentPeerConfig: Settings<TorrentPeerConfig> by lazy { error("no implemented") }
        override val oneshotActionConfig: Settings<OneshotActionConfig> by lazy { error("no implemented") }
        override val analyticsSettings: Settings<AnalyticsSettings> by lazy { error("no implemented") }
        override val debugSettings: Settings<DebugSettings> by lazy { error("no implemented") }
        override val watchTogetherSettings: Settings<WatchTogetherSettings> by lazy { error("no implemented") }
    }

    private class TestDanmakuDao : DanmakuDao {
        /** 原始写入调用记录, 用于断言"写了几次". */
        val upsertCalls = mutableListOf<List<DanmakuEntity>>()

        /** 记录自动清理调用的 (subjectId, episodeId). */
        val deleteCalls = mutableListOf<Pair<Int, Int>>()

        /**
         * 表内容的真实模型: 按主键 `id` 唯一, 与 `@Upsert` 的替换语义一致.
         *
         * 直接用 [upsertCalls] 展开当表内容会重复计算同一个 id 的多次写入, 从而掩盖真实的覆盖行为.
         */
        private val rows = LinkedHashMap<String, DanmakuEntity>()

        override fun countBySubjectAndEpisode(subjectId: Int, episodeId: Int): Flow<Int> = MutableStateFlow(0)

        override suspend fun getDanmaku(subjectId: Int, episodeId: Int): List<DanmakuEntity> {
            error("getDanmaku not expected in tests")
        }

        override suspend fun upsertAll(danmaku: List<DanmakuEntity>) {
            upsertCalls += danmaku
            danmaku.forEach { rows[it.id] = it }
        }

        override suspend fun deleteBySubject(subjectId: Int) {
            rows.values.removeAll { it.subjectId == subjectId }
        }

        override suspend fun deleteBySubjectAndEpisode(subjectId: Int, episodeId: Int) {
            rows.values.removeAll { it.subjectId == subjectId && it.episodeId == episodeId }
        }

        override suspend fun deleteAutoBySubjectAndEpisode(subjectId: Int, episodeId: Int) {
            deleteCalls += subjectId to episodeId
            // 真实删除语义: 只移除 AUTO 行, 手动缓存保留.
            rows.values.removeAll {
                it.subjectId == subjectId && it.episodeId == episodeId &&
                        it.origin == DanmakuCacheOrigin.AUTO
            }
        }

        /** 当前表内容, 按主键去重后. */
        fun storedDanmaku(): List<DanmakuEntity> = rows.values.toList()

        override suspend fun manualDanmakuIds(subjectId: Int, episodeId: Int): List<String> =
            rows.values
                .filter {
                    it.subjectId == subjectId && it.episodeId == episodeId &&
                            it.origin == DanmakuCacheOrigin.MANUAL
                }
                .map { it.id }

        override suspend fun countManualBySubjectAndEpisode(subjectId: Int, episodeId: Int): Int =
            manualDanmakuIds(subjectId, episodeId).size

        override suspend fun autoCachedEpisodesByAge(): List<AutoCachedEpisode> =
            rows.values
                .filter { it.origin == DanmakuCacheOrigin.AUTO }
                .map { AutoCachedEpisode(it.subjectId, it.episodeId) }
                .distinct()

        override suspend fun countAll(): Int = rows.size
        override fun countAllFlow(): Flow<Int> = MutableStateFlow(rows.size)
        override fun episodeDanmakuCountsFlow(subjectId: Int): Flow<List<EpisodeDanmakuCount>> =
            MutableStateFlow(
                rows.values
                    .filter { it.subjectId == subjectId }
                    .groupBy { it.episodeId }
                    .map { (episodeId, episodeRows) -> EpisodeDanmakuCount(episodeId, episodeRows.size) },
            )

        override fun cachedDanmakuEpisodesFlow(): Flow<List<CachedDanmakuEpisode>> = MutableStateFlow(emptyList())

        override suspend fun deleteAll() {
            rows.clear()
        }
    }

    private object TestMediaCache : MediaCache {
        override val cacheId: String = "dummy-cache"
        override val origin: me.him188.ani.datasources.api.Media
            get() = error("origin not expected")
        override val metadata: me.him188.ani.datasources.api.MediaCacheMetadata
            get() = error("metadata not expected")
        override val state: Flow<MediaCacheState> = MutableStateFlow(MediaCacheState.IN_PROGRESS)
        override val fileStats: MutableStateFlow<MediaCache.FileStats> =
            MutableStateFlow(MediaCache.FileStats.Unspecified)
        override val sessionStats: MutableStateFlow<MediaCache.SessionStats> =
            MutableStateFlow(MediaCache.SessionStats.Unspecified)
        override val isDeleted: MutableStateFlow<Boolean> = MutableStateFlow(false)
        override suspend fun getCachedMedia(): CachedMedia = error("getCachedMedia not expected")
        override suspend fun pause() = Unit
        override suspend fun close() = Unit
        override suspend fun resume() = Unit
        override suspend fun closeAndDeleteFiles() = Unit
    }

    private object UnusedApiInvoker : ApiInvoker<DanmakuAniApi> {
        override suspend fun <R> invoke(action: suspend DanmakuAniApi.() -> R): R {
            error("ApiInvoker not expected in tests")
        }
    }

    private companion object {
        private const val SUBJECT_ID = 101
        private const val EPISODE_ID = 202

        private fun createBundle(
            collectionType: UnifiedCollectionType,
            subjectId: Int,
            episodeId: Int,
        ): SubjectEpisodeInfoBundle {
            val subjectCollection = TestSubjectCollections.first().let { base ->
                base.copy(
                    collectionType = collectionType,
                    subjectInfo = base.subjectInfo.copy(subjectId = subjectId),
                    episodes = base.episodes.map { episode ->
                        episode.copy(episodeInfo = episode.episodeInfo.copy(episodeId = episodeId))
                    },
                )
            }
            return SubjectEpisodeInfoBundle(
                subjectId = subjectId,
                episodeId = episodeId,
                subjectCollectionInfo = subjectCollection,
                episodeCollectionInfo = subjectCollection.episodes.first(),
                seriesInfo = SubjectSeriesInfo.Fallback,
                subjectCompleted = false,
            )
        }
    }
}
