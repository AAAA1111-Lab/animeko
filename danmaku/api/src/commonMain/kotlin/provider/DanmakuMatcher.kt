/*
 * Copyright (C) 2024-2025 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.danmaku.api.provider

import kotlinx.serialization.Serializable
import me.him188.ani.datasources.api.EpisodeSort

fun interface DanmakuMatcher {
    fun match(list: List<DanmakuEpisodeWithSubject>): DanmakuEpisodeWithSubject?
}

@Serializable
data class DanmakuEpisodeWithSubject(
    val id: String,
    val subjectName: String,
    val episodeName: String,
    /**
     * 可能是系列内的, 也可能是单季的
     */
    val epOrSort: EpisodeSort? = null,
)

object DanmakuMatchers {
    fun first() = DanmakuMatcher { it.firstOrNull() }

    fun mostRelevant(targetSubjectName: String, targetEpisodeName: String): DanmakuMatcher = DanmakuMatcher { list ->
        list.minByOrNull {
            levenshteinDistance(it.subjectName, targetSubjectName) + levenshteinDistance(
                it.episodeName,
                targetEpisodeName,
            )
        }
    }

    // Thanks to ChatGPT :)
    // Helper function to calculate the Levenshtein distance between two strings
    private fun levenshteinDistance(lhs: CharSequence, rhs: CharSequence): Int {
        val lhsLength = lhs.length
        val rhsLength = rhs.length

        var cost = Array(lhsLength + 1) { it }
        var newCost = Array(lhsLength + 1) { 0 }

        for (i in 1..rhsLength) {
            newCost[0] = i

            for (j in 1..lhsLength) {
                val match = if (lhs[j - 1] == rhs[i - 1]) 0 else 1

                val costReplace = cost[j - 1] + match
                val costInsert = cost[j] + 1
                val costDelete = newCost[j - 1] + 1

                newCost[j] = minOf(costReplace, costInsert, costDelete)
            }

            val swap = cost
            cost = newCost
            newCost = swap
        }

        return cost[lhsLength]
    }
}

