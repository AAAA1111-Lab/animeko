/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.data.network

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import me.him188.ani.datasources.bangumi.apis.DefaultApi
import me.him188.ani.datasources.bangumi.models.BangumiSearchSubjectsRequest
import me.him188.ani.utils.coroutines.IO_
import me.him188.ani.utils.ktor.userAgent
import kotlin.coroutines.CoroutineContext

/**
 * bgm.tv (Bangumi 官方 API) 的条目搜索.
 *
 * 罗马音标题在 Bangumi 官方搜索中的匹配通常好于 Ani 服务器的分词搜索,
 * 作为 [AniSubjectSearchService] 的补充 (例如本地导入的自动匹配).
 *
 * 匿名即可调用; Bangumi 强制要求 User-Agent, 缺失时请求会被拒绝.
 */
class BangumiSearchService(
    private val ioDispatcher: CoroutineContext = Dispatchers.IO_,
) {
    private val api: DefaultApi by lazy {
        DefaultApi(
            baseUrl = "https://api.bgm.tv",
            httpClientConfig = { config -> config.userAgent(BGM_SEARCH_USER_AGENT) },
        )
    }

    suspend fun searchSubjects(keywords: String, limit: Int = 20): List<BangumiSubjectSearchResult> =
        withContext(ioDispatcher) {
            api.searchSubjects(
                limit = limit,
                bangumiSearchSubjectsRequest = BangumiSearchSubjectsRequest(keyword = keywords),
            ).body().data.orEmpty().map {
                BangumiSubjectSearchResult(
                    subjectId = it.id,
                    name = it.name,
                    nameCn = it.nameCn,
                    imageUrl = it.image,
                )
            }
        }

    private companion object {
        const val BGM_SEARCH_USER_AGENT = "Animeko/1.0 (https://github.com/open-ani/ani)"
    }
}

/**
 * Bangumi 搜索结果的轻量模型 (仅保留本地需要的字段).
 */
data class BangumiSubjectSearchResult(
    val subjectId: Int,
    val name: String,
    val nameCn: String,
    val imageUrl: String?,
)
