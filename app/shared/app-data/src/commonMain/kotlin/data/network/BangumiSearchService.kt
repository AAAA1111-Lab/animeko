/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.data.network

import io.ktor.client.HttpClient
import io.ktor.client.request.header
import io.ktor.client.request.parameter
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.contentType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import me.him188.ani.app.data.models.subject.RatingCounts
import me.him188.ani.app.data.models.subject.RatingInfo
import me.him188.ani.app.data.models.subject.SubjectCollectionStats
import me.him188.ani.app.data.models.subject.SubjectInfo
import me.him188.ani.app.domain.search.SubjectType
import me.him188.ani.datasources.api.PackedDate
import me.him188.ani.utils.coroutines.IO_
import me.him188.ani.utils.ktor.ScopedHttpClient
import kotlin.coroutines.CoroutineContext

/**
 * bgm.tv (Bangumi 官方 API) 的条目搜索.
 *
 * 罗马音标题在 Bangumi 官方搜索中的匹配通常好于 Ani 服务器的分词搜索,
 * 作为 [AniSubjectSearchService] 的补充 (例如探索搜索与本地导入的自动匹配).
 *
 * 匿名即可调用; Bangumi 强制要求 User-Agent, 缺失时请求会被拒绝.
 */
class BangumiSearchService(
    private val client: ScopedHttpClient? = null,
    private val ioDispatcher: CoroutineContext = Dispatchers.IO_,
) {
    private val fallbackClient by lazy { HttpClient() }

    suspend fun searchSubjects(keywords: String, limit: Int = 20): List<BangumiSubjectSearchResult> =
        withContext(ioDispatcher) {
            val responseText = if (client != null) {
                client.use {
                    post("https://api.bgm.tv/v0/search/subjects") {
                        header(HttpHeaders.UserAgent, BGM_SEARCH_USER_AGENT)
                        contentType(ContentType.Application.Json)
                        parameter("limit", limit)
                        setBody(
                            buildJsonObject {
                                put("keyword", keywords)
                                put(
                                    "filter",
                                    buildJsonObject {
                                        put(
                                            "type",
                                            buildJsonArray {
                                                add(JsonPrimitive(2))
                                            },
                                        )
                                    },
                                )
                            }.toString(),
                        )
                    }.bodyAsText()
                }
            } else {
                fallbackClient.post("https://api.bgm.tv/v0/search/subjects") {
                    header(HttpHeaders.UserAgent, BGM_SEARCH_USER_AGENT)
                    contentType(ContentType.Application.Json)
                    parameter("limit", limit)
                    setBody(
                        buildJsonObject {
                            put("keyword", keywords)
                            put(
                                "filter",
                                buildJsonObject {
                                    put(
                                        "type",
                                        buildJsonArray {
                                            add(JsonPrimitive(2))
                                        },
                                    )
                                },
                            )
                        }.toString(),
                    )
                }.bodyAsText()
            }

            val response = json.decodeFromString<BangumiSearchResponse>(responseText)
            response.data
                .filter { it.type == null || it.type == 2 }
                .map {
                    BangumiSubjectSearchResult(
                        subjectId = it.id,
                        name = it.name,
                        nameCn = it.nameCn,
                        imageUrl = it.image ?: it.images?.large ?: it.images?.common,
                        summary = it.summary,
                        date = it.date,
                        eps = it.eps ?: it.totalEpisodes,
                        score = it.rating?.score,
                        rank = it.rating?.rank,
                    )
                }
        }

    private companion object {
        const val BGM_SEARCH_USER_AGENT = "Animeko/1.0 (https://github.com/open-ani/ani)"

        val json = Json {
            ignoreUnknownKeys = true
            isLenient = true
            coerceInputValues = true
        }
    }
}

/**
 * Bangumi 搜索结果模型.
 */
data class BangumiSubjectSearchResult(
    val subjectId: Int,
    val name: String,
    val nameCn: String,
    val imageUrl: String?,
    val summary: String? = null,
    val date: String? = null,
    val eps: Int? = null,
    val score: Double? = null,
    val rank: Int? = null,
)

fun BangumiSubjectSearchResult.toBatchSubjectDetails(): BatchSubjectDetails {
    val airDate = date?.takeIf { it.isNotBlank() }?.let { PackedDate.parseFromDate(it) } ?: PackedDate.Invalid
    val scoreStr = score?.let {
        if (it % 1.0 == 0.0) it.toInt().toString() else it.toString()
    } ?: ""
    return BatchSubjectDetails(
        subjectInfo = SubjectInfo(
            subjectId = subjectId,
            subjectType = SubjectType.ANIME,
            name = name,
            nameCn = nameCn.ifBlank { name },
            summary = summary.orEmpty(),
            nsfw = false,
            imageLarge = imageUrl.orEmpty(),
            totalEpisodes = eps ?: 0,
            airDate = airDate,
            tags = emptyList(),
            aliases = emptyList(),
            ratingInfo = RatingInfo(
                rank = rank ?: 0,
                total = 0,
                count = RatingCounts.Zero,
                score = scoreStr,
            ),
            collectionStats = SubjectCollectionStats.Zero,
            completeDate = PackedDate.Invalid,
        ),
        mainEpisodeCount = eps ?: 0,
        lightSubjectRelations = LightSubjectRelations(
            lightRelatedPersonInfoList = emptyList(),
            lightRelatedCharacterInfoList = emptyList(),
        ),
    )
}

@Serializable
private data class BangumiSearchResponse(
    val data: List<BangumiSearchSubjectItem> = emptyList(),
)

@Serializable
private data class BangumiSearchSubjectItem(
    val id: Int,
    val name: String,
    @SerialName("name_cn") val nameCn: String = "",
    val type: Int? = null,
    val image: String? = null,
    val images: BangumiSearchImages? = null,
    val summary: String? = null,
    val date: String? = null,
    val eps: Int? = null,
    @SerialName("total_episodes") val totalEpisodes: Int? = null,
    val rating: BangumiSearchRating? = null,
)

@Serializable
private data class BangumiSearchImages(
    val large: String? = null,
    val common: String? = null,
    val medium: String? = null,
    val small: String? = null,
    val grid: String? = null,
)

@Serializable
private data class BangumiSearchRating(
    val rank: Int? = null,
    val score: Double? = null,
    val total: Int? = null,
)

