/*
 * Copyright (C) 2024-2025 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.domain.danmaku

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.retry
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import me.him188.ani.app.data.models.preference.DanmakuCacheStrategy
import me.him188.ani.app.data.network.danmaku.AniDanmakuProvider
import me.him188.ani.app.data.network.danmaku.AniDanmakuSender
import me.him188.ani.app.data.persistent.database.dao.CachedDanmakuEpisode
import me.him188.ani.app.data.persistent.database.dao.DanmakuCacheOrigin
import me.him188.ani.app.data.persistent.database.dao.DanmakuDao
import me.him188.ani.app.data.persistent.database.dao.DanmakuEntity
import me.him188.ani.app.data.persistent.database.dao.LocalDanmakuProvider
import me.him188.ani.app.data.repository.RepositoryException
import me.him188.ani.app.data.repository.user.SettingsRepository
import me.him188.ani.app.domain.episode.GetSubjectEpisodeInfoBundleFlowUseCase
import me.him188.ani.app.domain.foundation.HttpClientProvider
import me.him188.ani.app.domain.foundation.get
import me.him188.ani.app.domain.media.cache.GetMediaCacheUseCase
import me.him188.ani.app.domain.media.cache.MediaCache
import me.him188.ani.app.platform.currentAniBuildConfig
import me.him188.ani.app.ui.foundation.BackgroundScope
import me.him188.ani.app.ui.foundation.HasBackgroundScope
import me.him188.ani.client.apis.DanmakuAniApi
import me.him188.ani.danmaku.api.DanmakuContent
import me.him188.ani.danmaku.api.DanmakuInfo
import me.him188.ani.danmaku.api.provider.DanmakuFetchRequest
import me.him188.ani.danmaku.api.provider.DanmakuFetchResult
import me.him188.ani.danmaku.api.provider.DanmakuMatchInfo
import me.him188.ani.danmaku.api.provider.DanmakuMatchMethod
import me.him188.ani.danmaku.api.provider.DanmakuProvider
import me.him188.ani.danmaku.api.provider.DanmakuProviderId
import me.him188.ani.danmaku.api.provider.MatchingDanmakuProvider
import me.him188.ani.danmaku.api.provider.SimpleDanmakuProvider
import me.him188.ani.danmaku.dandanplay.DandanplayDanmakuProvider
import me.him188.ani.datasources.api.topic.UnifiedCollectionType
import me.him188.ani.utils.ktor.ApiInvoker
import me.him188.ani.utils.logging.debug
import me.him188.ani.utils.logging.error
import me.him188.ani.utils.logging.info
import me.him188.ani.utils.logging.logger
import me.him188.ani.utils.platform.currentTimeMillis
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.Duration.Companion.seconds

/**
 * 管理多个弹幕源 [DanmakuProvider]
 */
class DanmakuRepository(
    parentCoroutineContext: CoroutineContext = EmptyCoroutineContext,
    danmakuApi: ApiInvoker<DanmakuAniApi>,
    private val danmakuDao: DanmakuDao,
    httpClientProvider: HttpClientProvider,
    private val getMediaCacheUseCase: GetMediaCacheUseCase,
    private val getSubjectEpisodeInfoBundleFlowUseCase: GetSubjectEpisodeInfoBundleFlowUseCase,
    private val settingsRepository: SettingsRepository,
) : HasBackgroundScope by BackgroundScope(parentCoroutineContext) {

    private val sender by lazy { AniDanmakuSender(danmakuApi) }
    private val localProvider = LocalDanmakuProvider(danmakuDao)
    private val remoteProviders by lazy {
        listOf(
            AniDanmakuProvider(danmakuApi),
            DandanplayDanmakuProvider(
                dandanplayAppId = currentAniBuildConfig.dandanplayAppId,
                dandanplayAppSecret = currentAniBuildConfig.dandanplayAppSecret,
                httpClientProvider.get(),
            ),
        )
    }

    val selfId: Flow<String?> get() = sender.selfId

    /**
     * 本地已缓存的弹幕总条数, 用于设置页展示与清理确认.
     */
    val cachedDanmakuCountFlow: Flow<Int> = danmakuDao.countAllFlow().distinctUntilChanged()

    /**
     * 本地已缓存的某一集弹幕条数, 用于播放页展示该集是否已缓存.
     */
    fun cachedDanmakuCountFlow(subjectId: Int, episodeId: Int): Flow<Int> =
        danmakuDao.countBySubjectAndEpisode(subjectId, episodeId).distinctUntilChanged()

    /**
     * 条目下每一集已缓存的弹幕条数. 没有缓存的剧集不会出现在结果里.
     */
    fun cachedDanmakuCountsFlow(subjectId: Int): Flow<Map<Int, Int>> =
        danmakuDao.episodeDanmakuCountsFlow(subjectId)
            .map { rows -> rows.associate { it.episodeId to it.count } }
            .distinctUntilChanged()

    /**
     * 清空全部弹幕缓存.
     *
     * @return 被清除的弹幕条数.
     */
    suspend fun clearCachedDanmaku(): Int {
        val count = danmakuDao.countAll()
        danmakuDao.deleteAll()
        logger.info { "clearCachedDanmaku, removed $count danmaku" }
        return count
    }

    /**
     * 有弹幕缓存的剧集, 连带条目与剧集名称, 供设置-存储里的弹幕缓存管理使用.
     */
    fun cachedDanmakuEpisodesFlow(): Flow<List<CachedDanmakuEpisode>> =
        danmakuDao.cachedDanmakuEpisodesFlow()

    /**
     * 删除某一集的弹幕缓存, 不论手动还是自动. 这是用户在存储设置里的显式操作.
     */
    suspend fun removeCachedDanmaku(subjectId: Int, episodeId: Int) {
        danmakuDao.deleteBySubjectAndEpisode(subjectId, episodeId)
    }

    /**
     * 删除一个条目的全部弹幕缓存, 不论手动还是自动.
     */
    suspend fun removeCachedDanmakuOfSubject(subjectId: Int) {
        danmakuDao.deleteBySubject(subjectId)
    }

    fun getInteractiveDanmakuFetcherOrNull(providerId: DanmakuProviderId): DanmakuFetcher? {
        return remoteProviders
            .firstOrNull { it is MatchingDanmakuProvider && it.providerId == providerId }
            ?.let { DanmakuFetcher(it) }
    }

    fun fetchFromAllRemotes(request: DanmakuFetchRequest): Flow<List<DanmakuFetchResult>> {
        return flow {
            val fetchers = remoteProviders.map { DanmakuFetcher(it) }
            val results = fetchers.map { fetcher ->
                fetcher.fetch(request)
            }
            emit(results.flatten())
        }
    }

    fun fetchFromLocal(request: DanmakuFetchRequest): Flow<List<DanmakuFetchResult>> {
        return flow {
            val result = localProvider.fetchAutomatic(request)
            emit(result)
        }
    }

    fun cacheDanmakuIfNeeded(request: DanmakuFetchRequest) = backgroundScope.launch {
        if (shouldCache(request.subjectId, request.episodeId)) {
            val remotes = fetchFromAllRemotes(request)
            saveToLocal(request.subjectId, request.episodeId, remotes.first(), DanmakuCacheOrigin.AUTO)
        }
    }

    fun cacheDanmakuIfNeeded(subjectId: Int, episodeId: Int, list: List<DanmakuFetchResult>) = backgroundScope.launch {
        if (shouldCache(subjectId, episodeId)) {
            saveToLocal(subjectId, episodeId, list, DanmakuCacheOrigin.AUTO)
        }
    }

    /**
     * 显式把已取到的弹幕写入本地缓存, 不检查 [shouldCache].
     *
     * 与 [cacheDanmakuIfNeeded] 的区别在于: 前者是用户主动要求的动作, 策略只决定"自动缓存"的行为,
     * 不应该让用户点了没有反应. 写入的内容标记为 [DanmakuCacheOrigin.MANUAL], 之后不会被自动清理删除.
     *
     * 与 [cacheDanmakuIfNeeded] 相同的是仍然忽略 [DanmakuProviderId.Local] 的数据源, 它的内容本来就来自
     * 本地缓存, 再写回去只会把[presentationServiceId] 覆盖成 `Local`.
     */
    suspend fun saveToLocalCache(subjectId: Int, episodeId: Int, list: List<DanmakuFetchResult>) {
        saveToLocal(subjectId, episodeId, list, DanmakuCacheOrigin.MANUAL)
    }

    /**
     * 从所有远端弹幕源拉取并写入本地缓存, 不检查 [shouldCache].
     *
     * 用于用户在播放页显式点击"缓存弹幕". 调用方应先用 [fetchFromLocal] 之外的已有结果, 只有结果为空时
     * 才需要走这里, 避免重复请求.
     *
     * @return 实际写入的弹幕条数.
     */
    suspend fun fetchAndCacheNow(request: DanmakuFetchRequest): Int {
        val results = fetchFromAllRemotes(request).first()
        saveToLocal(request.subjectId, request.episodeId, results, DanmakuCacheOrigin.MANUAL)
        return results.sumOf { it.list.size }
    }

    /**
     * 该剧集的弹幕不再需要缓存时, 删除**自动缓存**的部分.
     *
     * 手动缓存([DanmakuCacheOrigin.MANUAL])不在此处删除: 它是用户的显式选择, 只由用户在设置-存储里
     * 或"清空弹幕缓存"主动清理.
     */
    fun deleteDanmakuIfDontNeeded(subjectId: Int, episodeId: Int) = backgroundScope.launch {
        if (!shouldCache(subjectId, episodeId)) {
            logger.info { "deleteAutoDanmaku, subjectId: $subjectId, episodeId: $episodeId" }
            danmakuDao.deleteAutoBySubjectAndEpisode(subjectId, episodeId)
        }
    }

    private suspend fun saveToLocal(
        subjectId: Int,
        episodeId: Int,
        list: List<DanmakuFetchResult>,
        origin: DanmakuCacheOrigin,
    ) {
        // 自动写入不能把已有的手动缓存降级成自动缓存, 否则自动清理就会把用户主动缓存的内容删掉.
        val manualIds = if (origin == DanmakuCacheOrigin.AUTO) {
            danmakuDao.manualDanmakuIds(subjectId, episodeId).toHashSet()
        } else {
            emptySet()
        }
        val entriesWithoutLocalSource = list
            .filter { it.providerId != DanmakuProviderId.Local }
            .flatMap { it.toEntityList(subjectId, episodeId, origin, manualIds, currentTimeMillis()) }
        logger.info { "saveToLocal, subjectId: $subjectId, episodeId: $episodeId, origin: $origin, danmaku size: ${entriesWithoutLocalSource.size}" }

        if (entriesWithoutLocalSource.isNotEmpty()) {
            danmakuDao.upsertAll(entriesWithoutLocalSource)
        }
        if (origin == DanmakuCacheOrigin.AUTO) {
            evictExcessAutoCache()
        }
    }

    /**
     * 自动缓存的剧集数超过设置上限时, 按最近一次缓存时间从早到晚淘汰, 直到回到上限之内.
     *
     * 只淘汰 [DanmakuCacheOrigin.AUTO] 的行; 手动缓存的剧集不参与, 也不计入上限 —— 它们由用户显式
     * 选择, 只在设置-存储里主动清理.
     */
    private suspend fun evictExcessAutoCache() {
        val maxEpisodes = settingsRepository.mediaCacheSettings.flow.first().maxAutoCachedDanmakuEpisodes
        if (maxEpisodes <= 0) return

        val cached = danmakuDao.autoCachedEpisodesByAge()
        val excess = cached.size - maxEpisodes
        if (excess <= 0) return

        val victims = cached.take(excess)
        victims.forEach {
            danmakuDao.deleteAutoBySubjectAndEpisode(it.subjectId, it.episodeId)
        }
        logger.info { "evictExcessAutoCache, 上限 $maxEpisodes, 淘汰 ${victims.size} 集" }
    }

    /**
     * 是否需要缓存这个剧集的弹幕到本地.
     * 
     * * 如果 strategy 不是 [DanmakuCacheStrategy.DON_NOT_CACHE], 只要有对应的 [MediaCache] 就一定缓存.
     * * 如果 strategy 是 [DanmakuCacheStrategy.CACHE_ON_MEDIA_CACHE], 只考虑是否有 [MediaCache].
     * * 如果 strategy 是 [DanmakuCacheStrategy.CACHE_ON_COLLECTION_DOING_MEDIA_PLAY], 不仅要考虑 [MediaCache], 还要考虑 [UnifiedCollectionType].
     */
    private suspend fun shouldCache(subjectId: Int, episodeId: Int): Boolean {
        val strategy = settingsRepository.mediaCacheSettings.flow.first().danmakuCacheStrategy
        val hasMediaCache = getMediaCacheUseCase(subjectId, episodeId).isNotEmpty()
        val isCollectionDoing = getSubjectEpisodeInfoBundleFlowUseCase(
            flowOf(GetSubjectEpisodeInfoBundleFlowUseCase.SubjectIdAndEpisodeId(subjectId, episodeId)),
        ).first().subjectCollectionInfo.collectionType == UnifiedCollectionType.DOING

        logger.debug {
            "shouldCache, subjectId: ${subjectId}, episodeId: ${episodeId}, strategy: $strategy, " +
                    "hasMediaCache: $hasMediaCache, isCollectionDoing: $isCollectionDoing"
        }

        return when (strategy) {
            DanmakuCacheStrategy.DON_NOT_CACHE -> false
            DanmakuCacheStrategy.CACHE_ON_MEDIA_CACHE -> hasMediaCache
            DanmakuCacheStrategy.CACHE_ON_COLLECTION_DOING_MEDIA_PLAY -> hasMediaCache || isCollectionDoing
        }
    }

    suspend fun post(
        episodeId: Int,
        danmaku: DanmakuContent
    ): DanmakuInfo {
        return try {
            sender.send(episodeId, danmaku)
        } catch (e: Throwable) {
            throw RepositoryException.wrapOrThrowCancellation(e)
        }
    }

    private fun DanmakuFetchResult.toEntityList(
        subjectId: Int,
        episodeId: Int,
        origin: DanmakuCacheOrigin,
        manualIds: Set<String>,
        cachedAtMillis: Long,
    ): List<DanmakuEntity> {
        return list.map {
            DanmakuEntity(
                id = it.id,
                subjectId = subjectId,
                episodeId = episodeId,
                serviceId = it.serviceId,
                presentationServiceId = matchInfo.serviceId,
                senderId = it.senderId,
                origin = if (it.id in manualIds) DanmakuCacheOrigin.MANUAL else origin,
                cachedAtMillis = cachedAtMillis,
                content = it.content,
            )
        }
    }


    companion object {
        private val logger = logger<DanmakuRepository>()
    }
}


class DanmakuFetcher(
    private val provider: DanmakuProvider
) {
    suspend fun fetch(
        request: DanmakuFetchRequest
    ): List<DanmakuFetchResult> {
        return flow {
            emit(
                withTimeout(60.seconds) {
                    when (provider) {
                        is MatchingDanmakuProvider -> {
                            provider.fetchAutomatic(request)
                        }

                        is SimpleDanmakuProvider -> {
                            provider.fetchAutomatic(request = request)
                        }
                    }
                },
            )
        }.retry(1) {
            if (it is CancellationException && !currentCoroutineContext().isActive) {
                // collector was cancelled
                return@retry false
            }
            logger.error(it) { "Failed to fetch danmaku from service '${provider.mainServiceId}'" }
            true
        }.catch {
            emit(
                listOf(
                    DanmakuFetchResult(
                        provider.providerId,
                        DanmakuMatchInfo(
                            provider.mainServiceId,
                            0,
                            DanmakuMatchMethod.NoMatch,
                        ),
                        list = emptyList(),
                    ),
                ),
            )// 忽略错误, 否则一个源炸了会导致所有弹幕都不发射了
            // 必须要 emit 一个, 否则下面 .first 会出错
        }.first()
    }

    val providerId get() = provider.providerId
    val supportsInteractiveMatching get() = provider is MatchingDanmakuProvider

    fun startInteractiveMatch(): MatchingDanmakuProvider {
        check(provider is MatchingDanmakuProvider) {
            "Provider $provider does not support interactive matching"
        }
        return provider
    }

    private companion object {
        private val logger = logger<DanmakuFetcher>()
    }
}

///**
// * 手动选择弹幕条目的会话.
// *
// * This is not thread-safe.
// */
//class InteractiveDanmakuMatchSession(
//    private val provider: MatchingDanmakuProvider,
//    private val defaultDispatcher: CoroutineContext = Dispatchers.Default,
//) {
//    private val _subjectState = MutableStateFlow<SubjectState>(SubjectState.Waiting)
//    val subjectState = _subjectState.asStateFlow()
//    private val _episodeState = MutableStateFlow<EpisodeState>(EpisodeState.Waiting)
//    val episodeState = _episodeState.asStateFlow()
//
//    suspend fun setSubjectName(name: String) {
//        withContext(defaultDispatcher) {
//            _subjectState.value = SubjectState.Fetching(name)
//            try {
//                val subjects = provider.fetchSubjectList(name)
//                _subjectState.value = SubjectState.Success(subjects)
//            } catch (e: CancellationException) {
//                _subjectState.value = SubjectState.Waiting
//                throw e
//            } catch (e: Throwable) {
//                _subjectState.value = SubjectState.Failed(e)
//            }
//        }
//    }
//
//    fun dismissSubject() {
//        _subjectState.value = SubjectState.Waiting
//    }
//
//    suspend fun setEpisodeName(subject: DanmakuSubject) {
//        withContext(defaultDispatcher) {
//            _episodeState.value = EpisodeState.Fetching
//            try {
//                val episodes = provider.fetchEpisodeList(subject)
//                _episodeState.value = EpisodeState.Success(episodes)
//            } catch (e: CancellationException) {
//                _episodeState.value = EpisodeState.Waiting
//                throw e
//            } catch (e: Throwable) {
//                _episodeState.value = EpisodeState.Failed(e)
//            }
//        }
//    }
//
//    fun dismissEpisode() {
//        _episodeState.value = EpisodeState.Waiting
//    }
//
//    sealed class SubjectState {
//        data object Waiting : SubjectState()
//
//        data class Fetching(
//            val query: String,
//        ) : SubjectState()
//
//        data class Success(
//            val subjects: List<DanmakuSubject>,
//        ) : SubjectState()
//
//        data class Failed(
//            val error: Throwable,
//        ) : SubjectState()
//    }
//
//    sealed class EpisodeState {
//        data object Waiting : EpisodeState()
//
//        data object Fetching : EpisodeState()
//
//        data class Success(
//            val episodes: List<DanmakuEpisode>,
//        ) : EpisodeState()
//
//        data class Failed(
//            val error: Throwable,
//        ) : EpisodeState()
//    }
//}
//
//@Immutable
//data class InteractiveDanmakuMatchSessionPresentation(
//    val showInputQuery: Boolean,
//    val showSelectSubjects: Boolean,
//    val subjects: List<DanmakuSubject>,
//    val showSelectEpisodes: Boolean,
//    val episodes: List<DanmakuEpisode>,
//    val showSearching: Boolean,
//
//    val error: Throwable?,
//)
//
//class InteractiveDanmakuMatchSessionPresenter(
//    private val session: InteractiveDanmakuMatchSession,
//) {
//    suspend fun setQuery(name: String) {
//        session.setSubjectName(name)
//    }
//
//    fun dismissError() {
//        session.dismissSubject()
//        session.dismissEpisode()
//    }
//
//    val presentationFlow = combine(
//        session.subjectState,
//        session.episodeState,
//    ) { subjectState, episodeState ->
//        val showInputQuery = subjectState is InteractiveDanmakuMatchSession.SubjectState.Waiting
//        val showSelectSubjects = subjectState is InteractiveDanmakuMatchSession.SubjectState.Success
//        val subjects = (subjectState as? InteractiveDanmakuMatchSession.SubjectState.Success)?.subjects ?: emptyList()
//        val showSelectEpisodes = episodeState is InteractiveDanmakuMatchSession.EpisodeState.Success
//        val episodes = (episodeState as? InteractiveDanmakuMatchSession.EpisodeState.Success)?.episodes ?: emptyList()
//        val showSearching = subjectState is InteractiveDanmakuMatchSession.SubjectState.Fetching ||
//                episodeState is InteractiveDanmakuMatchSession.EpisodeState.Fetching
//
//        val error = when {
//            subjectState is InteractiveDanmakuMatchSession.SubjectState.Failed -> subjectState.error
//            episodeState is InteractiveDanmakuMatchSession.EpisodeState.Failed -> episodeState.error
//            else -> null
//        }
//
//        InteractiveDanmakuMatchSessionPresentation(
//            showInputQuery,
//            showSelectSubjects,
//            subjects,
//            showSelectEpisodes,
//            episodes,
//            showSearching,
//            error,
//        )
//    }.shareIn(CoroutineScope(Dispatchers.Default), started = SharingStarted.WhileSubscribed(), replay = 1)
//}
