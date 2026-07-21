/*
 * Copyright (C) 2024 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.data.network

import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import me.him188.ani.app.data.repository.Repository
import me.him188.ani.datasources.bangumi.BangumiClient

object BangumiPersonGraphQLExecutor : AbstractBangumiBatchGraphQLExecutor() {
    private const val FRAGMENTS = """
fragment PF on Person {
  career
  collects
  comment
  id
  images {
    large
    medium
  }
  infobox {
    key
    values {
      k
      v
    } 
  }
  last_post
  lock
  name
  nsfw
  redirect
  summary
  type
}
    """

    private const val QUERY_1 = """
$FRAGMENTS
query MyQuery(${'$'}id: Int!) {
  s0:person(id: ${'$'}id){...PF}
}
"""

    // 服务器会缓存 query 编译, 用 variables 可以让查询更快
    private val QUERY_WHOLE_PAGE by lazy {
        buildString {
            appendLine(FRAGMENTS)

            appendLine("query MyQuery(")
            repeat(Repository.defaultPagingConfig.pageSize) { i ->
                append("\$id").append(i).append(": Int!")
                if (i != Repository.defaultPagingConfig.pageSize - 1) {
                    append(", ")
                }
            }
            appendLine(") {")

            repeat(Repository.defaultPagingConfig.pageSize) { i ->
                append('s')
                append(i)
                append(":person(id: \$id").append(i).append("){...PF}")
                appendLine()
            }

            append("}")
        }
    }

    suspend fun execute(client: BangumiClient, ids: IntArray): BangumiGraphQLResponse {
        val actionName = "BangumiPersonGraphQLExecutor.executeQuerySubjectDetails"
        // 尽量使用 variables
        val resp = when (ids.size) {
            0 -> return BangumiGraphQLResponse(emptyList(), "")
            1 -> {
                client.executeGraphQL(
                    actionName,
                    QUERY_1,
                    variables = buildJsonObject {
                        put("id", ids.first())
                    },
                )
            }

            Repository.defaultPagingConfig.pageSize -> {
                client.executeGraphQL(
                    actionName,
                    QUERY_WHOLE_PAGE,
                    variables = buildJsonObject {
                        repeat(ids.size) { i ->
                            put("id$i", ids[i])
                        }
                    },
                )
            }

            else -> {
                client.executeGraphQL(
                    actionName,
                    buildString(
                        capacity = FRAGMENTS.length + 30 + 55 * ids.size, // big enough to avoid resizing
                    ) {
                        appendLine(FRAGMENTS)
                        appendLine("query MyQuery {")
                        for (id in ids) {
                            append('s')
                            append(id)
                            append(":person(id: ").append(id).append("){...PF}")
                            appendLine()
                        }
                        append("}")
                    },
                )
            }
        }
        return try {
            BangumiGraphQLResponse(
                processResponse(resp),
                errors = resp["errors"].toString(),
            )
        } catch (e: Exception) {
            throw IllegalStateException(
                "Exception while processing Bangumi GraphQL response for action $actionName, ids $ids, see cause",
                e,
            )
        }
    }
}
