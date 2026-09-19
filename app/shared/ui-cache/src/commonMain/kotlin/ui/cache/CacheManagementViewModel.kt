/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.ui.cache

import androidx.compose.runtime.Stable
import androidx.paging.PagingData
import androidx.paging.cachedIn
import io.ktor.client.call.body
import io.ktor.client.plugins.userAgent
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.transformLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.supervisorScope
import me.him188.ani.app.data.models.subject.SubjectCollectionInfo
import me.him188.ani.app.data.models.subject.SubjectInfo
import me.him188.ani.app.data.network.AniSubjectSearchService
import me.him188.ani.app.data.repository.player.EpisodePlayHistoryRepository
import me.him188.ani.app.data.repository.subject.CollectionsFilterQuery
import me.him188.ani.app.data.repository.subject.SubjectCollectionRepository
import me.him188.ani.app.data.repository.subject.staticSubjectImageLargeUrl
import me.him188.ani.app.domain.media.cache.DeleteCacheByCacheIdUseCase
import me.him188.ani.app.domain.media.cache.ImportLocalVideosUseCase
import me.him188.ani.app.domain.media.cache.MediaCacheManager
import me.him188.ani.app.domain.media.cache.engine.MediaStats
import me.him188.ani.app.domain.media.cache.engine.sum
import me.him188.ani.app.domain.media.cache.storage.LocalImportFileItem
import me.him188.ani.app.domain.media.cache.storage.MediaCacheStorage
import me.him188.ani.app.ui.cache.components.CacheEpisodeState
import me.him188.ani.app.ui.cache.components.CacheGroupState
import me.him188.ani.app.ui.cache.components.CacheWithEngine
import me.him188.ani.app.ui.cache.components.allCachesWithEngineFlow
import me.him188.ani.app.ui.cache.components.createCacheEpisodeStateFlow
import me.him188.ani.app.ui.foundation.AbstractViewModel
import me.him188.ani.datasources.api.topic.UnifiedCollectionType
import me.him188.ani.datasources.bangumi.apis.DefaultApi
import me.him188.ani.datasources.bangumi.models.BangumiSearchSubjectsRequest
import me.him188.ani.utils.coroutines.flows.flowOfEmptyList
import me.him188.ani.utils.coroutines.sampleWithInitial
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject
import kotlin.time.Duration.Companion.seconds

@Stable
class CacheManagementViewModel : AbstractViewModel(), KoinComponent {
    private val cacheManager: MediaCacheManager by inject()
    private val deleteCacheByCacheIdUseCase: DeleteCacheByCacheIdUseCase by inject()
    private val subjectRepository: SubjectCollectionRepository by inject()
    private val episodePlayHistoryRepository: EpisodePlayHistoryRepository by inject()
    private val importLocalVideosUseCase: ImportLocalVideosUseCase by inject()
    private val subjectSearchService: AniSubjectSearchService by inject()

    private val playbackHistoriesByEpisodeId = episodePlayHistoryRepository.flow
        .map { histories -> histories.associateBy { it.episodeId } }
        .stateInBackground(emptyMap())

    val stateFlow = run {
        val overallStatsFlow = cacheManager.enabledStorages
            .overallStatsFlow()
            .sampleWithInitial(1.seconds)
            .stateInBackground(MediaStats.Unspecified)

        val allCachesFlow = cacheManager.enabledStorages
            .allCachesWithEngineFlow()
            .shareInBackground()

        val groupsFlow = allCachesFlow.transformLatest {
            supervisorScope { emitAll(createCacheGroupStates(it)) } // supervisorScope won't finish itself
        }.shareInBackground()

        combine(overallStatsFlow, groupsFlow, ::CacheManagementState)
            .stateInBackground(CacheManagementState.Placeholder) // has distinctUntilChanged
    }

    private fun createCacheGroupStates(allCaches: List<CacheWithEngine>): Flow<List<CacheGroupState>> {
        val groupStateFlows = allCaches
            .groupBy { it.cache.metadata.subjectId }
            .map { (subjectId, caches) ->
                val groupId = subjectId
                val collectionType = subjectRepository.getSubjectCollectionTypeOffline(subjectId.toInt())
                    .onStart { emit(UnifiedCollectionType.NOT_COLLECTED) }
                val displayInfo = subjectRepository.getSubjectDisplayInfoOffline(subjectId.toInt())
                    .onStart { emit(null) }

                val entriesFlow =
                    combine(
                        caches.map {
                            createCacheEpisodeStateFlow(
                                groupId,
                                it,
                                collectionType,
                                playbackHistoriesByEpisodeId,
                            )
                        },
                    ) { states ->
                        // 防止意外情况出现了相同的 list key, 也就是相同的数据源的同一剧集缓存.
                        // 就算出现了 duplicated key, 这两个 item 对应的 cache 是同一个引用.
                        states.toList().distinctBy { it.listItemKey }
                    }

                combine(entriesFlow, collectionType, displayInfo) { entries, type, info ->
                    CacheGroupState(
                        subjectId = subjectId.toInt(),
                        subjectName = info?.displayName
                            ?: caches.first().cache.metadata.run { subjectNameCN ?: subjectNames.firstOrNull() ?: "" },
                        entries = entries,
                        collectionType = type,
                        imageUrl = info?.imageLarge ?: staticSubjectImageLargeUrl(subjectId.toInt()),
                        totalEpisodeCount = info?.totalEpisodes?.takeIf { it > 0 },
                    )
                }
            }

        if (groupStateFlows.isEmpty()) {
            return flowOfEmptyList()
        }

        return combine(groupStateFlows) { array ->
            array.sortedWith(
                compareByDescending<CacheGroupState> { it.entries.any { entry -> !entry.isFinished } }
                    .thenByDescending { it.entries.maxOfOrNull { entry -> entry.creationTime ?: 0 } },
            )
        }
    }

    fun pauseCache(cache: CacheEpisodeState) {
        backgroundScope.launch {
            cacheManager.findFirstCache { it.cacheId == cache.cacheId }?.pause()
        }
    }

    fun resumeCache(cache: CacheEpisodeState) {
        backgroundScope.launch {
            cacheManager.findFirstCache { it.cacheId == cache.cacheId }?.resume()
        }
    }

    fun deleteCache(cache: CacheEpisodeState) {
        backgroundScope.launch {
            deleteCacheByCacheIdUseCase(cache.subjectId, cache.episodeId, cache.cacheId)
        }
    }

    /**
     * 本地导入: 供选择条目的收藏列表, 支持按收藏类型过滤.
     */
    fun importSubjectsPager(query: CollectionsFilterQuery): Flow<PagingData<SubjectCollectionInfo>> =
        subjectRepository.subjectCollectionsPager(query)
            .cachedIn(backgroundScope)

    /**
     * 本地导入 (自动匹配): 同时搜索 Ani 服务器与 Bangumi (bgm.tv), 合并去重后返回候选列表.
     *
     * Bangumi 官方搜索对罗马音的匹配通常好于 Ani 服务器, 两者互为补充.
     */
    suspend fun searchSubjectsForImport(keywords: String): List<ImportSubjectCandidate> = coroutineScope {
        val ani = async { runCatching { searchAni(keywords) }.getOrDefault(emptyList()) }
        val bangumi = async { runCatching { searchBangumi(keywords) }.getOrDefault(emptyList()) }
        (ani.await() + bangumi.await()).distinctBy { it.subjectId }
    }

    private suspend fun searchAni(keywords: String): List<ImportSubjectCandidate> =
        subjectSearchService.searchSubjects(keywords, limit = 20)
            .map {
                ImportSubjectCandidate(
                    subjectId = it.subjectInfo.subjectId,
                    displayName = it.subjectInfo.displayName,
                    imageUrl = it.subjectInfo.imageLarge,
                )
            }

    private val bangumiSearchApi: DefaultApi by lazy {
        // bgm.tv 强制要求 User-Agent, 缺失时请求会被拒绝.
        DefaultApi(
            baseUrl = "https://api.bgm.tv",
            httpClientConfig = { config ->
                config.userAgent("Animeko-LocalImport/1.0")
            },
        )
    }

    private suspend fun searchBangumi(keywords: String): List<ImportSubjectCandidate> =
        bangumiSearchApi.searchSubjects(
            limit = 20,
            bangumiSearchSubjectsRequest = BangumiSearchSubjectsRequest(keyword = keywords),
        ).body().data.orEmpty().map {
            ImportSubjectCandidate(
                subjectId = it.id,
                displayName = it.nameCn.ifBlank { it.name },
                imageUrl = it.image,
            )
        }

    /**
     * 本地导入: 加载条目的完整信息 (含剧集列表), 用于剧集匹配与导入.
     * 对未收藏的条目同样可用 (会自动从服务器获取并缓存).
     */
    suspend fun loadSubjectForImport(subjectId: Int): SubjectCollectionInfo {
        return subjectRepository.subjectCollectionFlow(subjectId).first()
    }

    /**
     * 本地导入: 将文件导入为指定条目的已完成缓存.
     *
     * @return 实际新导入的数量, 重复导入的文件会被跳过.
     */
    suspend fun importLocalFiles(subjectInfo: SubjectInfo, items: List<LocalImportFileItem>): Int {
        if (items.isEmpty()) return 0
        return importLocalVideosUseCase.import(subjectInfo, items)
    }
}

/**
 * 本地导入的条目候选 (来自搜索结果或收藏列表).
 */
data class ImportSubjectCandidate(
    val subjectId: Int,
    val displayName: String,
    val imageUrl: String?,
)

internal fun Flow<List<MediaCacheStorage>>.overallStatsFlow(): Flow<MediaStats> {
    return flatMapLatest { storages ->
        if (storages.isEmpty()) {
            flowOf(MediaStats.Zero)
        } else {
            storages.map { it.stats }.sum()
        }
    }
}
