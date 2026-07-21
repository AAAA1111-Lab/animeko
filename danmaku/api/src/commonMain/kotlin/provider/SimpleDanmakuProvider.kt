/*
 * Copyright (C) 2024-2025 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.danmaku.api.provider

import me.him188.ani.danmaku.api.DanmakuInfo
import me.him188.ani.danmaku.api.DanmakuServiceId
import kotlin.jvm.JvmStatic

/**
 * 通过请求, 自动查询出精确的结果的 [DanmakuProvider].
 *
 * [SimpleDanmakuProvider] 通常需要使用 [DanmakuFetchRequest.subjectId] 和 [DanmakuFetchRequest.episodeId] 来精确匹配剧集弹幕.
 */
interface SimpleDanmakuProvider : DanmakuProvider


class DanmakuFetchResult(
    val providerId: DanmakuProviderId,
    val matchInfo: DanmakuMatchInfo,
    val list: List<DanmakuInfo>,
) {
    companion object {
        @JvmStatic
        fun noMatch(
            providerId: DanmakuProviderId,
            serviceId: DanmakuServiceId
        ) = DanmakuFetchResult(
            providerId = providerId,
            matchInfo = DanmakuMatchInfo(
                serviceId = serviceId,
                count = 0,
                method = DanmakuMatchMethod.NoMatch,
            ),
            list = emptyList(),
        )
    }
}

class DanmakuMatchInfo(
    val serviceId: DanmakuServiceId,
    val count: Int,
    val method: DanmakuMatchMethod,
) {
    companion object
}


sealed class DanmakuMatchMethod {
    data class Exact(
        val subjectTitle: String,
        val episodeTitle: String,
    ) : DanmakuMatchMethod()

    data class ExactSubjectFuzzyEpisode(
        val subjectTitle: String,
        val episodeTitle: String,
    ) : DanmakuMatchMethod()

    data class Fuzzy(
        val subjectTitle: String,
        val episodeTitle: String,
    ) : DanmakuMatchMethod()

    data class ExactId(
        val subjectId: Int,
        val episodeId: Int,
    ) : DanmakuMatchMethod()

    data object NoMatch : DanmakuMatchMethod()
}
