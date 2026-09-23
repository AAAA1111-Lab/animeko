/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.domain.danmaku

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import me.him188.ani.app.data.models.episode.displayName
import me.him188.ani.app.data.models.subject.SubjectInfo
import me.him188.ani.app.data.repository.episode.EpisodeCollectionRepository
import me.him188.ani.app.data.repository.subject.SubjectCollectionRepository
import me.him188.ani.danmaku.api.provider.DanmakuFetchRequest
import me.him188.ani.utils.logging.info
import me.him188.ani.utils.logging.logger
import me.him188.ani.utils.logging.warn
import kotlin.time.Duration

/**
 * 批量缓存一个条目全部剧集弹幕的进度.
 */
data class DanmakuBatchCacheState(
    val isRunning: Boolean = false,
    /** 本次已处理的剧集数 (含跳过和失败的). */
    val done: Int = 0,
    /** 本次真正需要拉取的剧集总数 (已有弹幕的不计入). */
    val total: Int = 0,
    /** 本次实际写入弹幕的剧集数. */
    val succeeded: Int = 0,
    /**
     * 每完成一次批量缓存自增一次. UI 靠它判断"刚刚跑完了一次", 从而弹出结果提示 ——
     * 用 [isRunning] 的边沿判断在页面重建后会丢事件.
     */
    val completionCount: Int = 0,
) {
    /**
     * 最近一次批量缓存的结果, 用于提示. `null` 表示还没有完成过.
     */
    val lastResult: DanmakuBatchCacheResult?
        get() = if (completionCount == 0) null else DanmakuBatchCacheResult(succeeded, total)
}

/**
 * 一次批量缓存的结果.
 */
data class DanmakuBatchCacheResult(
    /** 实际缓存到的剧集数. */
    val succeeded: Int,
    /** 本次需要处理的剧集数. */
    val total: Int,
)

/**
 * 应用级的"缓存全部弹幕"执行者.
 *
 * 状态必须活在页面之外: 用户在条目缓存页点一下就会退出去看别的, 而 `SubjectCacheViewModel` 的
 * `backgroundScope` 在离开页面时会被取消. 放在这里才能做到"退出页面仍在后台跑", 回到页面也能
 * 直接看到之前的进度和结果.
 *
 * 同一个条目同时只跑一个任务, 重复点击不会再起一份.
 */
class DanmakuBatchCacheManager(
    private val applicationScope: CoroutineScope,
    private val danmakuRepository: DanmakuRepository,
    private val subjectCollectionRepository: SubjectCollectionRepository,
    private val episodeCollectionRepository: EpisodeCollectionRepository,
) {
    private val perSubject = mutableMapOf<Int, MutableStateFlow<DanmakuBatchCacheState>>()
    private val jobs = mutableMapOf<Int, Job>()
    private val lock = Mutex()

    /** 该条目批量缓存的状态. 每个条目一个, 页面重建后仍然保留. */
    fun stateFlow(subjectId: Int): StateFlow<DanmakuBatchCacheState> =
        stateFor(subjectId).asStateFlow()

    private fun stateFor(subjectId: Int): MutableStateFlow<DanmakuBatchCacheState> =
        perSubject.getOrPut(subjectId) { MutableStateFlow(DanmakuBatchCacheState()) }

    fun isRunning(subjectId: Int): Boolean = stateFor(subjectId).value.isRunning

    /**
     * 缓存该条目下**尚未缓存**的剧集弹幕.
     *
     * 已经有弹幕的剧集会被跳过, 所以重复点击不会重复拉取; 全部缓存完后再点不会起任何任务.
     */
    fun cacheAll(subjectId: Int) {
        val state = stateFor(subjectId)
        if (state.value.isRunning) return

        applicationScope.launch {
            lock.withLock {
                if (state.value.isRunning) return@withLock
                if (jobs[subjectId]?.isActive == true) return@withLock
                jobs[subjectId] = launch { runCache(subjectId, state) }
            }
        }
    }

    private suspend fun runCache(subjectId: Int, state: MutableStateFlow<DanmakuBatchCacheState>) {
        val subjectInfo = try {
            subjectCollectionRepository.subjectCollectionFlow(subjectId).first().subjectInfo
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            logger.warn("cacheAllDanmaku: cannot load subject $subjectId", e)
            return
        }

        val episodes = try {
            episodeCollectionRepository.subjectEpisodeCollectionInfosFlow(subjectId).first()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            logger.warn("cacheAllDanmaku: cannot load episodes of $subjectId", e)
            return
        }
        if (episodes.isEmpty()) return

        // Only fetch what is missing: an episode that already has danmaku is not re-requested.
        val cached = danmakuRepository.cachedDanmakuCountsFlow(subjectId).first()
        val missing = episodes.filter { (cached[it.episodeId] ?: 0) <= 0 }
        if (missing.isEmpty()) {
            state.update { it.copy(isRunning = false, done = 0, total = 0, succeeded = 0, completionCount = it.completionCount + 1) }
            return
        }

        state.update {
            it.copy(isRunning = true, done = 0, total = missing.size, succeeded = 0)
        }
        var succeeded = 0
        try {
            for (episode in missing) {
                val episodeInfo = episode.episodeInfo
                // 单个源不可达不应中断其余剧集.
                val count = runCatching {
                    danmakuRepository.fetchAndCacheNow(
                        DanmakuFetchRequest(
                            subjectId = subjectInfo.subjectId,
                            subjectPrimaryName = subjectInfo.displayName,
                            subjectNames = subjectInfo.allNames,
                            subjectPublishDate = subjectInfo.airDate,
                            episodeId = episodeInfo.episodeId,
                            episodeSort = episodeInfo.sort,
                            episodeEp = episodeInfo.ep,
                            episodeName = episodeInfo.displayName,
                            filename = null,
                            fileHash = null,
                            fileSize = null,
                            videoDuration = Duration.ZERO,
                        ),
                    )
                }.onFailure {
                    logger.warn("cacheAllDanmaku: episode ${episodeInfo.episodeId} failed", it)
                }.getOrDefault(0)
                if (count > 0) succeeded++
                state.update { it.copy(done = it.done + 1) }
            }
        } finally {
            state.update {
                it.copy(
                    isRunning = false,
                    succeeded = succeeded,
                    completionCount = it.completionCount + 1,
                )
            }
            lock.withLock { jobs.remove(subjectId) }
            logger.info { "cacheAllDanmaku: subject=$subjectId cached $succeeded of ${missing.size}" }
        }
    }

    private companion object {
        private val logger = logger<DanmakuBatchCacheManager>()
    }
}
