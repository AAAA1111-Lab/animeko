/*
 * Copyright (C) 2024-2025 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.domain.episode

import kotlinx.collections.immutable.persistentMapOf
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.shareIn
import kotlinx.coroutines.flow.update
import me.him188.ani.app.data.models.episode.displayName
import me.him188.ani.app.data.repository.danmaku.SearchDanmakuRequest
import me.him188.ani.app.domain.danmaku.DanmakuFetcher
import me.him188.ani.app.domain.danmaku.DanmakuLoaderImpl
import me.him188.ani.app.domain.danmaku.DanmakuLoadingState
import me.him188.ani.app.domain.danmaku.DanmakuRepository
import me.him188.ani.app.domain.media.player.data.filenameOrNull
import me.him188.ani.app.domain.settings.GetDanmakuRegexFilterListFlowUseCase
import me.him188.ani.danmaku.api.DanmakuCollection
import me.him188.ani.danmaku.api.DanmakuEvent
import me.him188.ani.danmaku.api.DanmakuInfo
import me.him188.ani.danmaku.api.DanmakuServiceId
import me.him188.ani.danmaku.api.DanmakuSession
import me.him188.ani.danmaku.api.TimeBasedDanmakuSession
import me.him188.ani.danmaku.api.provider.DanmakuFetchRequest
import me.him188.ani.danmaku.api.provider.DanmakuFetchResult
import me.him188.ani.danmaku.api.provider.DanmakuMatchInfo
import me.him188.ani.danmaku.api.provider.DanmakuMatchMethod
import me.him188.ani.danmaku.api.provider.DanmakuProviderId
import me.him188.ani.datasources.api.Media
import me.him188.ani.utils.logging.info
import me.him188.ani.utils.logging.logger
import me.him188.ani.utils.logging.warn
import me.him188.ani.utils.platform.annotations.TestOnly
import org.openani.mediamp.MediampPlayer
import org.openani.mediamp.features.PlaybackSpeed
import org.openani.mediamp.metadata.duration
import org.openani.mediamp.source.SeekableInputMediaData
import org.openani.mediamp.source.UriMediaData
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * Connects episode data, the player, and the danmaku loader.
 *
 * It reads [bundleFlow] to launch danmaku loading, and provides a [danmakuEventFlow] that is connected to the player.
 */
class EpisodeDanmakuLoader(
    player: MediampPlayer,
    private val selectedMedia: Flow<Media?>,
    private val bundleFlow: Flow<SubjectEpisodeInfoBundle>,
    private val danmakuRepository: DanmakuRepository,
    getDanmakuRegexFilterListFlowUseCase: GetDanmakuRegexFilterListFlowUseCase,
    backgroundScope: CoroutineScope,
    sharingStarted: SharingStarted = SharingStarted.WhileSubscribed(),
) {
    private val flowScope = backgroundScope

//    val playerExtension = object : PlayerExtension("EpisodeDanmakuLoader") {
//        override fun onStart(backgroundTaskScope: ExtensionBackgroundTaskScope) {
//            backgroundTaskScope.launch("DanmakuLoader") {
//                danmakuLoader.collectionFlow.first()
//            }
//        }
//    }

    private val danmakuLoader = DanmakuLoaderImpl(
        combine(
            bundleFlow,
            player.mediaData,
            selectedMedia,
            player.mediaProperties.filter { it != null }.map { it?.duration ?: 0.milliseconds },
        ) { info, mediaData, selectedMedia, duration ->
            if (mediaData == null) {
                null
            } else {
                SearchDanmakuRequest(
                    info.subjectInfo,
                    info.episodeInfo,
                    info.episodeId,
                    filename = mediaData.filenameOrNull ?: selectedMedia?.originalTitle,
                    fileLength = when (mediaData) {
                        is SeekableInputMediaData -> mediaData.fileLength()
                        is UriMediaData -> null
                    },
                    videoDuration = duration,
                )
            }
        }.distinctUntilChanged()
            .debounce {
                if (it == null) {
                    0.milliseconds // 立即清空
                } else {
                    1.seconds
                }
            }
            .onEach {
                currentRequest.value = it
                logger.info { "New SearchDanmakuRequest: $it" }
            },
        backgroundScope,
        danmakuRepository,
        sharingStarted,
    )

    private val config = MutableStateFlow(persistentMapOf<DanmakuServiceId, DanmakuOriginConfig>())
    val configFlow = config.asStateFlow()

    /**
     * 最近一次弹幕请求. 用户在播放页点击"缓存弹幕"时用它定位当前剧集, 并作为兜底重新拉取的请求.
     */
    private val currentRequest = MutableStateFlow<SearchDanmakuRequest?>(null)


    private val danmakuSessionFlow: Flow<DanmakuSession> = config.mapLatest { configMap ->
        createDanmakuCollection(danmakuLoader.fetchResultFlow, configMap).at(
            progress = player.currentPositionMillis.map { it.milliseconds },
            playbackSpeed = { player.features[PlaybackSpeed]?.value ?: 1f },
            danmakuRegexFilterList = getDanmakuRegexFilterListFlowUseCase(),
        )
    }.shareIn(flowScope, started = sharingStarted, replay = 1)

    val danmakuLoadingStateFlow: StateFlow<DanmakuLoadingState> = danmakuLoader.danmakuLoadingStateFlow

    val danmakuEventFlow: Flow<DanmakuEvent> = danmakuSessionFlow.flatMapLatest { it.events }

    suspend fun requestRepopulate() {
        danmakuSessionFlow.first().requestRepopulate()
    }

    /**
     * 最近一次弹幕请求的结果, 用于显式缓存时复用已经取到的数据.
     */
    private val latestFetchResults = MutableStateFlow<List<DanmakuFetchResult>?>(null)

    // this flow must emit a value quickly when started, otherwise it will block ui
    val fetchResults: Flow<List<DanmakuFetchResultWithConfig>> = danmakuLoader.fetchResultFlow
        .onEach { latestFetchResults.value = it }
        .combine(configFlow) { results, configs ->
            results.orEmpty().map {
                DanmakuFetchResultWithConfig(
                    it.providerId,
                    it.matchInfo.serviceId,
                    it.matchInfo,
                    configs[it.matchInfo.serviceId] ?: DanmakuOriginConfig.Default,
                )
            }
        }.shareIn(flowScope, started = sharingStarted, replay = 1)

    /**
     * 当前集弹幕在本地缓存中的条数.
     */
    val cachedDanmakuCountFlow: Flow<Int> = bundleFlow
        .map { danmakuRepository.cachedDanmakuCountFlow(it.subjectId, it.episodeId) }
        .flatMapLatest { it }
        .distinctUntilChanged()

    /**
     * 用户在播放页显式点击"缓存弹幕".
     *
     * 优先复用本次播放已经取到的弹幕; 只有在还没有任何结果时才重新向所有远端弹幕源请求, 避免用户点一下
     * 就重复打一遍接口.
     *
     * 策略 ([me.him188.ani.app.data.models.preference.DanmakuCacheStrategy]) 只约束自动缓存, 不影响这里:
     * 用户主动要求的动作不应该被静默忽略.
     */
    suspend fun cacheCurrentEpisode() {
        val request = currentRequest.value
        if (request == null) {
            logger.warn { "cacheCurrentEpisode: no danmaku request yet, ignored" }
            return
        }
        val subjectId = request.subjectInfo.subjectId
        val episodeId = request.episodeId

        val existing = latestFetchResults.value
            .orEmpty()
            .filter { it.providerId != DanmakuProviderId.Local }
        if (existing.any { it.list.isNotEmpty() }) {
            danmakuRepository.saveToLocalCache(subjectId, episodeId, existing)
            logger.info {
                "cacheCurrentEpisode: saved ${existing.sumOf { it.list.size }} danmaku from existing results"
            }
        } else {
            val count = danmakuRepository.fetchAndCacheNow(request.toFetchRequest())
            logger.info { "cacheCurrentEpisode: fetched and saved $count danmaku" }
        }
    }

    fun getInteractiveDanmakuFetcherOrNull(providerId: DanmakuProviderId?): DanmakuFetcher? {
        return danmakuRepository.getInteractiveDanmakuFetcherOrNull(providerId ?: return null)
    }

    fun setEnabled(serviceId: DanmakuServiceId, enabled: Boolean) {
        config.update { conf ->
            conf.put(
                serviceId,
                conf.getConfigOrDefault(serviceId).copy(enabled = enabled),
            )
        }
    }

    fun setShiftMillis(serviceId: DanmakuServiceId, shiftMillis: Long) {
        config.update { conf ->
            conf.put(
                serviceId,
                conf.getConfigOrDefault(serviceId).copy(shiftMillis = shiftMillis),
            )
        }
    }

    private fun Map<DanmakuServiceId, DanmakuOriginConfig>.getConfigOrDefault(providerId: DanmakuServiceId) =
        this[providerId] ?: DanmakuOriginConfig.Default

    private fun createDanmakuCollection(
        danmakuListFlow: Flow<List<DanmakuFetchResult>?>,
        config: Map<DanmakuServiceId, DanmakuOriginConfig>
    ): DanmakuCollection {
        return TimeBasedDanmakuSession.create(
            danmakuListFlow.map {
                it?.flatMap { result ->
                    val config = config[result.matchInfo.serviceId] ?: DanmakuOriginConfig.Default

                    if (!config.enabled) {
                        return@flatMap emptyList()
                    }

                    result.list
                        .mapNotNull { danmaku ->
                            val newText = sanitizeDanmakuText(danmaku.text) ?: return@mapNotNull null
                            danmaku.copy(
                                content = danmaku.content.copy(
                                    playTimeMillis = danmaku.playTimeMillis + config.shiftMillis,
                                    text = newText,
                                ),
                            )
                        }
                } ?: emptyList()
            },
        )
    }

    private fun sanitizeDanmakuText(text: String): String? {
        if (text.isEmpty()) {
            return null
        }
        // 全部是空白或者控制字符不行
        // https://github.com/open-ani/animeko/issues/1643
        val result = text
            .trim {
                it.isWhitespace() || it.isISOControl()
            }
            .filterNot { it.isISOControl() }
        if (result.isEmpty()) {
            return null
        }
        return result
    }

    fun overrideResults(provider: DanmakuProviderId, result: List<DanmakuFetchResult>) {
        danmakuLoader.overrideResults(provider, result)
    }

    /**
     * 获取所有弹幕数据的流，用于弹幕列表显示
     */
    val allDanmakuFlow: Flow<List<DanmakuInfo>> = combine(
        fetchResults,
        danmakuLoader.fetchResultFlow.onStart { emit(null) },
    ) { fetchResultsWithConfig, rawResults ->
        rawResults?.flatMap { result ->
            val configResult = fetchResultsWithConfig.find { it.serviceId == result.matchInfo.serviceId }
            if (configResult?.config?.enabled == true) {
                result.list.mapNotNull { danmaku ->
                    val newText = sanitizeDanmakuText(danmaku.text) ?: return@mapNotNull null
                    danmaku.copy(
                        serviceId = result.matchInfo.serviceId,
                        content = danmaku.content.copy(
                            playTimeMillis = danmaku.playTimeMillis + configResult.config.shiftMillis,
                            text = newText,
                        ),
                    )
                }
            } else emptyList()
        } ?: emptyList()
    }.shareIn(flowScope, started = SharingStarted.WhileSubscribed(5000), replay = 1)

    private fun SearchDanmakuRequest.toFetchRequest(): DanmakuFetchRequest {
        return DanmakuFetchRequest(
            subjectId = subjectInfo.subjectId,
            subjectPrimaryName = subjectInfo.displayName,
            subjectNames = subjectInfo.allNames,
            subjectPublishDate = subjectInfo.airDate,
            episodeId = episodeId,
            episodeSort = episodeInfo.sort,
            episodeEp = episodeInfo.ep,
            episodeName = episodeInfo.displayName,
            filename = filename,
            fileHash = fileHash,
            fileSize = fileLength,
            videoDuration = videoDuration,
        )
    }

    private companion object {
        private val logger = logger<EpisodeDanmakuLoader>()
    }
}

/**
 * 配置一个弹幕数据源
 */
data class DanmakuOriginConfig(
    val enabled: Boolean,
    val shiftMillis: Long,
) {
    companion object {
        val Default = DanmakuOriginConfig(enabled = true, shiftMillis = 0)
    }
}

/**
 * 一个弹幕数据源的结果, 包含了匹配信息和弹幕列表, 还包含本次会话的配置
 */
data class DanmakuFetchResultWithConfig(
    val providerId: DanmakuProviderId,
    val serviceId: DanmakuServiceId,
    val matchInfo: DanmakuMatchInfo,
    val config: DanmakuOriginConfig,
)

@TestOnly
fun createTestDanmakuFetchResultWithConfig(
    serviceId: String,
    matchInfo: DanmakuMatchInfo = DanmakuMatchInfo(
        DanmakuServiceId(serviceId),
        100,
        DanmakuMatchMethod.Exact(
            subjectTitle = "条目标题",
            episodeTitle = "剧集标题",
        ),
    ),
    config: DanmakuOriginConfig = DanmakuOriginConfig.Default,
): DanmakuFetchResultWithConfig =
    DanmakuFetchResultWithConfig(DanmakuProviderId(serviceId), DanmakuServiceId(serviceId), matchInfo, config)
