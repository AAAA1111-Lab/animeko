/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.ui.download

import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.time.Duration.Companion.seconds
import androidx.paging.PagingData
import androidx.paging.cachedIn
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart
import me.him188.ani.app.data.models.subject.SubjectCollectionInfo
import me.him188.ani.app.data.models.subject.SubjectInfo
import me.him188.ani.app.data.network.AniSubjectSearchService
import me.him188.ani.app.data.network.BangumiSearchService
import me.him188.ani.app.data.repository.player.EpisodePlayHistoryRepository
import me.him188.ani.app.data.repository.subject.CollectionsFilterQuery
import me.him188.ani.app.data.repository.subject.OfflineSubjectDisplayInfo
import me.him188.ani.app.data.repository.subject.SubjectCollectionRepository
import me.him188.ani.app.data.repository.subject.staticSubjectImageLargeUrl
import me.him188.ani.app.domain.media.cache.ImportLocalVideosUseCase
import me.him188.ani.app.domain.media.cache.storage.LocalImportFileItem
import me.him188.ani.app.domain.media.download.DownloadOperation
import me.him188.ani.app.domain.media.download.DownloadOperations
import me.him188.ani.app.domain.media.download.MediaDownloadManager
import me.him188.ani.app.ui.cache.ImportSubjectCandidate
import me.him188.ani.app.ui.download.components.DownloadItem
import me.him188.ani.app.ui.download.components.SubjectDownloadGroup
import me.him188.ani.app.ui.download.components.toDownloadItem
import me.him188.ani.app.ui.download.subject.SubjectDownloadsPresenter
import me.him188.ani.app.ui.download.subject.SubjectDownloadsPresenterFactory
import me.him188.ani.app.ui.foundation.AbstractViewModel
import me.him188.ani.datasources.api.topic.UnifiedCollectionType
import me.him188.ani.utils.coroutines.sampleWithInitial
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject

/**
 * 全局下载管理页面: 所有存储中的下载按条目分组展示.
 *
 * @param coroutineContext [backgroundScope] 的额外 context, 测试时传入测试调度器.
 */
class DownloadManagementViewModel(
    downloadManager: MediaDownloadManager,
    private val subjects: SubjectCollectionRepository,
    histories: EpisodePlayHistoryRepository,
    operations: DownloadOperations,
    private val presenters: SubjectDownloadsPresenterFactory,
    coroutineContext: CoroutineContext = EmptyCoroutineContext,
) : AbstractViewModel(coroutineContext), KoinComponent {
    private val operationRunner = DownloadOperationRunner(operations, backgroundScope)

    // 本地导入相关依赖: 懒注入, 仅在调用导入功能时解析 (测试构造无 Koin 环境也不受影响).
    private val importLocalVideosUseCase: ImportLocalVideosUseCase by inject()
    private val subjectSearchService: AniSubjectSearchService by inject()
    private val bangumiSearchService: BangumiSearchService by inject()

    private val currentSubjectPresenter = MutableStateFlow<SubjectDownloadsPresenter?>(null)

    /**
     * 详情栏展示的条目, `null` 表示未展示.
     */
    val subjectPresenter: StateFlow<SubjectDownloadsPresenter?> = currentSubjectPresenter.asStateFlow()

    /**
     * 上一条目的实例被关闭, 其选源会话随之取消; 相同条目不做任何事.
     * @param subjectName 已知的条目名, 在条目信息加载完成前作为标题
     */
    fun selectSubject(subjectId: Int?, subjectName: String? = null) {
        val previous = currentSubjectPresenter.value
        if (previous?.subjectId == subjectId) return
        currentSubjectPresenter.value = subjectId?.let { presenters.create(it, backgroundScope, subjectName) }
        previous?.close()
    }
    private val downloads = downloadManager.snapshots().shareInBackground()

    /**
     * 数据库返回前以 `null` 占位, 列表不必等待条目信息.
     */
    private val subjectMetadata = downloads
        .map { list -> list.mapTo(hashSetOf()) { it.metadata.subjectId.toIntOrNull() ?: 0 } }
        .distinctUntilChanged()
        .flatMapLatest { ids ->
            if (ids.isEmpty()) {
                flowOf(emptyMap())
            } else {
                combine(
                    ids.map { id ->
                        combine(
                            subjects.getSubjectCollectionTypeOffline(id).onStart { emit(null) },
                            subjects.getSubjectDisplayInfoOffline(id).onStart { emit(null) },
                        ) { type, info -> id to SubjectMetadata(type, info) }
                    },
                ) { it.toMap() }
            }
        }
    private val overallStats = downloadManager.overallStats.sampleWithInitial(1.seconds)

    val uiState = combine(downloads, subjectMetadata, histories.flow, overallStats) { downloads, metadata, histories, stats ->
        val historyByEpisode = histories.associateBy { it.episodeId }
        val groups = downloads.groupBy { it.metadata.subjectId.toIntOrNull() ?: 0 }.map { (subjectId, snapshots) ->
            val subject = metadata[subjectId]
            val entries = snapshots.map { snapshot ->
                val history = snapshot.metadata.episodeId.toIntOrNull()?.let { historyByEpisode[it] }
                snapshot.toDownloadItem(subject?.type, history)
            }
            SubjectDownloadGroup(
                subjectId = subjectId,
                subjectName = subject?.info?.displayName ?: entries.first().subjectName,
                entries = entries,
                collectionType = subject?.type,
                imageUrl = subject?.info?.imageLarge ?: staticSubjectImageLargeUrl(subjectId),
                totalEpisodeCount = subject?.info?.totalEpisodes?.takeIf { it > 0 },
            )
        }.sortedWith(
            // 有未完成下载的条目在前, 其余按最新一条下载的创建时间降序.
            compareByDescending<SubjectDownloadGroup> { it.hasUnfinished }
                .thenByDescending { it.entries.maxOfOrNull { entry -> entry.creationTime ?: 0 } },
        )
        DownloadManagementUiState(stats, groups, isLoading = false)
    }.stateInBackground(DownloadManagementUiState.Placeholder)

    fun pauseDownload(item: DownloadItem) = operationRunner.run(setOf(item.id), DownloadOperation.Pause)
    fun resumeDownload(item: DownloadItem) = operationRunner.run(setOf(item.id), DownloadOperation.Resume)
    fun deleteDownload(item: DownloadItem) = operationRunner.run(setOf(item.id), DownloadOperation.Delete)

    /**
     * 批量操作累计的失败数, [dismissOperationFailures] 后归零.
     */
    val operationFailures: StateFlow<Int> get() = operationRunner.failedCount

    fun dismissOperationFailures() = operationRunner.dismissFailures()

    /**
     * 本地导入: 供选择条目的收藏列表, 支持按收藏类型过滤.
     */
    fun importSubjectsPager(query: CollectionsFilterQuery): Flow<PagingData<SubjectCollectionInfo>> =
        subjects.subjectCollectionsPager(query)
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

    private suspend fun searchBangumi(keywords: String): List<ImportSubjectCandidate> =
        bangumiSearchService.searchSubjects(keywords)
            .map { ImportSubjectCandidate(it.subjectId, it.nameCn.ifBlank { it.name }, it.imageUrl) }

    /**
     * 本地导入: 加载条目的完整信息 (含剧集列表), 用于剧集匹配与导入.
     * 对未收藏的条目同样可用 (会自动从服务器获取并缓存).
     */
    suspend fun loadSubjectForImport(subjectId: Int): SubjectCollectionInfo {
        return subjects.subjectCollectionFlow(subjectId).first()
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

    private data class SubjectMetadata(val type: UnifiedCollectionType?, val info: OfflineSubjectDisplayInfo?)
}
