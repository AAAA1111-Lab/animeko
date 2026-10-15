/*
 * Copyright (C) 2024-2025 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.data.repository.subject

import androidx.paging.Pager
import androidx.paging.PagingData
import androidx.paging.PagingSource
import androidx.paging.PagingState
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import me.him188.ani.app.data.models.preference.NsfwMode
import me.him188.ani.app.data.network.AniSubjectSearchService
import me.him188.ani.app.data.network.BangumiSearchService
import me.him188.ani.app.data.network.SubjectSearchField
import me.him188.ani.app.data.network.SubjectSearchFilters
import me.him188.ani.app.data.repository.Repository
import me.him188.ani.app.data.repository.runWrappingExceptionAsLoadResult
import me.him188.ani.app.data.repository.user.SettingsRepository
import me.him188.ani.datasources.api.topic.UnifiedCollectionType
import me.him188.ani.utils.logging.error

/**
 * 搜索补全 (推荐)
 */
class SubjectSearchCompletionRepository(
    private val aniSubjectSearchService: AniSubjectSearchService,
    private val subjectCollectionRepository: SubjectCollectionRepository,
    private val bangumiSearchService: BangumiSearchService,
    settingsRepository: SettingsRepository,
) : Repository() {
    private val ignoreDoneAndDroppedFlow =
        settingsRepository.uiSettings.flow.map { it.searchSettings.ignoreDoneAndDroppedSubjects }
    private val nsfwSettings = settingsRepository.uiSettings.flow.map { it.searchSettings.nsfwMode }

    fun completionsFlow(query: String): Flow<PagingData<String>> = Pager(
        config = defaultPagingConfig,
        pagingSourceFactory = {
            object : PagingSource<Int, String>() {
                override fun getRefreshKey(state: PagingState<Int, String>): Int? = null

                override suspend fun load(
                    params: LoadParams<Int>
                ): LoadResult<Int, String> = runWrappingExceptionAsLoadResult<Int, String> {
                    // 只加载第一页
                    val subjects = aniSubjectSearchService.searchSubjects(
                        keyword = query,
                        limit = params.loadSize,
                        filters = SubjectSearchFilters(
                            nsfw = when (nsfwSettings.first()) {
                                NsfwMode.DISPLAY -> null
                                NsfwMode.BLUR -> false
                                NsfwMode.HIDE -> false
                            },
                            excludeCollectionTypes = if (ignoreDoneAndDroppedFlow.first()) {
                                listOf(UnifiedCollectionType.DONE, UnifiedCollectionType.DROPPED)
                            } else {
                                null
                            },
                        ),
                        fields = listOf(SubjectSearchField.NAME),
                    )

                    val filteredSubjects = if (ignoreDoneAndDroppedFlow.first()) {
                        val excludedIds = subjectCollectionRepository.getSubjectIdsByCollectionType(
                            types = listOf(UnifiedCollectionType.DONE, UnifiedCollectionType.DROPPED),
                        ).first()

                        subjects.filter { it.subjectInfo.subjectId !in excludedIds }
                    } else {
                        subjects
                    }

                    val bgmCompletions = if (filteredSubjects.isEmpty() && query.isNotBlank()) {
                        runCatching {
                            val excludedIds = if (ignoreDoneAndDroppedFlow.first()) {
                                subjectCollectionRepository.getSubjectIdsByCollectionType(
                                    listOf(UnifiedCollectionType.DONE, UnifiedCollectionType.DROPPED),
                                ).first()
                            } else {
                                emptySet()
                            }
                            bangumiSearchService.searchSubjects(
                                query,
                                limit = params.loadSize,
                                filters = SubjectSearchFilters(
                                    nsfw = if (nsfwSettings.first() == NsfwMode.DISPLAY) null else false,
                                ),
                            )
                                .filter { it.subjectId !in excludedIds }
                                .map { it.nameCn.ifBlank { it.name } }
                        }.getOrElse {
                            if (it is CancellationException) throw it
                            emptyList()
                        }
                    } else {
                        emptyList()
                    }

                    LoadResult.Page(
                        data = (filteredSubjects.map { it.subjectInfo.nameCn.ifEmpty { it.subjectInfo.name } } + bgmCompletions)
                            .filter { it.isNotBlank() }
                            .distinct(),
                        prevKey = null,
                        nextKey = null,
                    )
                }.also {
                    if (it is LoadResult.Error) {
                        logger.error(it.throwable) { "Failed to get search completions, query: $query" }
                    }
                }
            }
        },
    ).flow.flowOn(defaultDispatcher)
}
