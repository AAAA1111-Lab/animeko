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
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import me.him188.ani.app.domain.search.SearchSort
import me.him188.ani.utils.ktor.asScopedHttpClient
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

class BangumiSearchServiceTest {
    @Test
    fun supplementarySearchUsesTheSameFiltersAsAniSearch() {
        val body = buildBangumiSearchBody(
            "frieren",
            SubjectSearchFilters(
                tags = listOf("奇幻"),
                airDates = listOf(">=2023-01-01", "<2024-01-01"),
                ratings = listOf(">=8"),
                ranks = listOf(">=1"),
                nsfw = false,
            ),
            SearchSort.RANK,
        )
        assertEquals(JsonPrimitive("score"), body["sort"])
        val filter = body.getValue("filter").jsonObject
        assertEquals(JsonPrimitive(false), filter["nsfw"])
        assertEquals(Json.parseToJsonElement("[\">=1\"]"), filter["rank"])
        assertEquals(Json.parseToJsonElement("[\">=2023-01-01\",\"<2024-01-01\"]"), filter["air_date"])
        assertEquals(JsonPrimitive("奇幻"), filter.getValue("tag").jsonArray.single())
    }

    @Test
    fun supplementarySearchRespectsNsfwAndPreservesTheFlag() = runTest {
        val service = BangumiSearchService(HttpClient(MockEngine {
            respond(
                """{"data":[{"id":1,"name":"safe","type":2,"nsfw":false},{"id":2,"name":"restricted","type":2,"nsfw":true}]}""",
                headers = headersOf(HttpHeaders.ContentType, "application/json"),
            )
        }).asScopedHttpClient())
        assertEquals(listOf(1), service.searchSubjects("test", filters = SubjectSearchFilters(nsfw = false)).map { it.subjectId })
        val restricted = service.searchSubjects("test", filters = SubjectSearchFilters(nsfw = true)).single()
        assertEquals(true, restricted.toBatchSubjectDetails().subjectInfo.nsfw)
    }

    @Test
    fun `searchSubjects correctly parses real Bangumi API response with nested rating and images`() = runTest {
        val mockJson = """
            {
              "total": 1,
              "limit": 10,
              "offset": 0,
              "data": [
                {
                  "id": 400652,
                  "name": "葬送のフリーレン",
                  "name_cn": "葬送的芙莉莲",
                  "type": 2,
                  "image": "https://lain.bgm.tv/pic/cover/l/f1/88/400652_13eW4.jpg",
                  "images": {
                    "small": "https://lain.bgm.tv/r/200/pic/cover/l/f1/88/400652_13eW4.jpg",
                    "large": "https://lain.bgm.tv/pic/cover/l/f1/88/400652_13eW4.jpg",
                    "common": "https://lain.bgm.tv/r/400/pic/cover/l/f1/88/400652_13eW4.jpg"
                  },
                  "summary": "千年以上の時を生きるエルフの魔法使い・フリーレン...",
                  "date": "2023-09-29",
                  "eps": 28,
                  "total_episodes": 28,
                  "rating": {
                    "rank": 3,
                    "score": 8.9,
                    "total": 15420
                  }
                }
              ]
            }
        """.trimIndent()

        val client = HttpClient(
            MockEngine { request ->
                assertEquals("https://api.bgm.tv/v0/search/subjects?limit=10", request.url.toString())
                assertEquals("Animeko/1.0 (https://github.com/open-ani/ani)", request.headers[HttpHeaders.UserAgent])
                respond(
                    content = mockJson,
                    status = HttpStatusCode.OK,
                    headers = headersOf(HttpHeaders.ContentType, "application/json"),
                )
            },
        ).asScopedHttpClient()

        val service = BangumiSearchService(client = client)
        val results = service.searchSubjects("frieren", limit = 10)

        assertEquals(1, results.size)
        val item = results.first()
        assertEquals(400652, item.subjectId)
        assertEquals("葬送のフリーレン", item.name)
        assertEquals("葬送的芙莉莲", item.nameCn)
        assertEquals("https://lain.bgm.tv/pic/cover/l/f1/88/400652_13eW4.jpg", item.imageUrl)
        assertEquals(28, item.eps)
        assertEquals(8.9, item.score)
        assertEquals(3, item.rank)

        val batchDetails = item.toBatchSubjectDetails()
        assertEquals(400652, batchDetails.subjectInfo.subjectId)
        assertEquals("葬送的芙莉莲", batchDetails.subjectInfo.nameCn)
        assertEquals("葬送のフリーレン", batchDetails.subjectInfo.name)
        assertEquals(28, batchDetails.mainEpisodeCount)
        assertEquals("8.9", batchDetails.subjectInfo.ratingInfo.score)
        assertEquals(3, batchDetails.subjectInfo.ratingInfo.rank)
    }

    @Test
    fun `searchSubjects handles items without rating or with empty fields`() = runTest {
        val mockJson = """
            {
              "total": 1,
              "limit": 10,
              "offset": 0,
              "data": [
                {
                  "id": 999999,
                  "name": "Unknown Anime",
                  "name_cn": "",
                  "type": 2
                }
              ]
            }
        """.trimIndent()

        val client = HttpClient(
            MockEngine {
                respond(
                    content = mockJson,
                    status = HttpStatusCode.OK,
                    headers = headersOf(HttpHeaders.ContentType, "application/json"),
                )
            },
        ).asScopedHttpClient()

        val service = BangumiSearchService(client = client)
        val results = service.searchSubjects("unknown", limit = 10)

        assertEquals(1, results.size)
        val item = results.first()
        assertEquals(999999, item.subjectId)
        assertEquals("Unknown Anime", item.name)
        assertEquals("", item.nameCn)

        val batchDetails = item.toBatchSubjectDetails()
        assertEquals("Unknown Anime", batchDetails.subjectInfo.nameCn) // falls back to name if blank
    }

    @Test
    fun `searchSubjects filters out non-anime items`() = runTest {
        val mockJson = """
            {
              "total": 3,
              "limit": 10,
              "offset": 0,
              "data": [
                {
                  "id": 101,
                  "name": "Anime Item",
                  "name_cn": "动画条目",
                  "type": 2
                },
                {
                  "id": 102,
                  "name": "Book Item",
                  "name_cn": "书籍条目",
                  "type": 1
                },
                {
                  "id": 103,
                  "name": "Game Item",
                  "name_cn": "游戏条目",
                  "type": 4
                }
              ]
            }
        """.trimIndent()

        val client = HttpClient(
            MockEngine { request ->
                respond(
                    content = mockJson,
                    status = HttpStatusCode.OK,
                    headers = headersOf(HttpHeaders.ContentType, "application/json"),
                )
            },
        ).asScopedHttpClient()

        val service = BangumiSearchService(client = client)
        val results = service.searchSubjects("test", limit = 10)

        assertEquals(1, results.size)
        assertEquals(101, results.first().subjectId)
        assertEquals("Anime Item", results.first().name)
    }
}
