/*
 * Copyright (C) 2024-2025 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.ui.subject.episode.details


import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOf
import me.him188.ani.app.domain.episode.DanmakuFetchResultWithConfig
import me.him188.ani.danmaku.api.DanmakuServiceId
import me.him188.ani.danmaku.api.provider.DanmakuMatchMethod
import me.him188.ani.danmaku.api.provider.DanmakuProviderId
import me.him188.ani.danmaku.ui.DanmakuPresentation
import me.him188.ani.utils.platform.Uuid

/**
 * 弹幕列表项数据类，用于在UI中显示单条弹幕信息。
 */
data class DanmakuListItem(
    val id: String,
    val randomId: Uuid,
    val content: String,
    val timeMillis: Long,
    val serviceId: DanmakuServiceId,
    val isSelf: Boolean,
)

/**
 * 剧集弹幕的本地缓存情况, 用于在弹幕列表区域提供"缓存本集弹幕"入口.
 */
data class DanmakuCacheStatus(
    val isCached: Boolean,
    val cachedCount: Int,
    /**
     * 当前已经取到的远端弹幕条数, 用于在还没缓存时提示"可缓存多少条".
     */
    val availableCount: Int,
) {
    companion object {
        val None = DanmakuCacheStatus(isCached = false, cachedCount = 0, availableCount = 0)
    }
}

/**
 * 弹幕列表状态数据类
 */
data class DanmakuListState(
    val danmakuItems: List<DanmakuListItem>,
    val sourceItems: List<DanmakuSourceItem>,
    val isLoading: Boolean,
    val isEmpty: Boolean,
    val cacheStatus: DanmakuCacheStatus = DanmakuCacheStatus.None,
) {
    companion object {
        val Loading = DanmakuListState(
            danmakuItems = emptyList(),
            sourceItems = emptyList(),
            isLoading = true,
            isEmpty = false,
        )
    }
}

/**
 * 弹幕源选择项数据类
 */
data class DanmakuSourceItem(
    val serviceId: DanmakuServiceId,
    val displayName: String,
    val enabled: Boolean,
    val isFuzzyMatch: Boolean,
    val shiftMillis: Long,
    val count: Int,
)

/**
 * 弹幕列表状态生产者，负责处理弹幕数据的计算和转换逻辑
 */
class DanmakuListStateProducer(
    danmakuFlow: Flow<List<DanmakuPresentation>>,
    fetchResultsFlow: Flow<List<DanmakuFetchResultWithConfig>>,
    /**
     * 当前集在本地缓存中的弹幕条数.
     */
    cachedCountFlow: Flow<Int> = flowOf(0),
) {
    val stateFlow: Flow<DanmakuListState> = combine(
        danmakuFlow,
        fetchResultsFlow,
        cachedCountFlow,
    ) { danmakuList, fetchResults, cachedCount ->
        val sourceItems = fetchResults.map { result ->
            DanmakuSourceItem(
                serviceId = result.serviceId,
                displayName = result.matchInfo.serviceId.value,
                enabled = result.config.enabled,
                isFuzzyMatch = !result.matchInfo.method.isExactMatch(),
                shiftMillis = result.config.shiftMillis,
                count = danmakuList.count { presentation -> presentation.danmaku.serviceId == result.serviceId },
            )
        }

        val danmakuItems = danmakuList.map { presentation ->
            DanmakuListItem(
                id = presentation.danmaku.id,
                randomId = Uuid.random(),
                content = presentation.danmaku.text,
                timeMillis = presentation.danmaku.playTimeMillis,
                serviceId = presentation.danmaku.serviceId,
                isSelf = presentation.isSelf,
            )
        }.sortedBy { it.timeMillis }

        DanmakuListState(
            danmakuItems = danmakuItems,
            sourceItems = sourceItems,
            isLoading = fetchResults.isEmpty(),
            isEmpty = danmakuList.isEmpty() && fetchResults.isNotEmpty(),
            cacheStatus = DanmakuCacheStatus(
                isCached = cachedCount > 0,
                cachedCount = cachedCount,
                // 不含 Local: 它读的就是本地缓存, 计入会把"已缓存条数"算成两倍.
                availableCount = fetchResults
                    .filter { it.providerId != DanmakuProviderId.Local }
                    .sumOf { it.matchInfo.count },
            ),
        )
    }.distinctUntilChanged()

    private fun DanmakuMatchMethod.isExactMatch(): Boolean {
        return when (this) {
            is DanmakuMatchMethod.Exact, is DanmakuMatchMethod.ExactId -> true
            else -> false
        }
    }
}
