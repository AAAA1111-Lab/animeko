/*
 * Copyright (C) 2024 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.domain.mediasource

object StringMatcher {
    /**
     * Calculates the match rate between two strings (0..100).
     * Uses Levenshtein distance to measure the difference between them,
     * then converts it to a percentage of similarity.
     */
    fun calculateMatchRate(a: String, b: String): Int {
        if (a.isEmpty() && b.isEmpty()) {
            return 100
        }

        val distance = levenshteinDistance(a, b)
        // We scale similarity based on the longest string length
        val maxLen = maxOf(a.length, b.length)
        // Similarity is 1 - (distance / maxLen), then multiplied by 100
        val similarity = 1 - (distance.toDouble() / maxLen)
        return (similarity * 100).toInt().coerceIn(0, 100)
    }

    /**
     * Computes the Levenshtein distance between two strings.
     *
     * Levenshtein distance is the minimum number of single-character
     * edits (insertions, deletions, or substitutions) required to
     * change one word into the other.
     */
    private fun levenshteinDistance(s1: String, s2: String): Int {
        val len1 = s1.length
        val len2 = s2.length

        // Create a 2D array DP where dp[i][j] represents
        // the distance between s1[0..i-1] and s2[0..j-1]
        val dp = Array(len1 + 1) { IntArray(len2 + 1) }

        // Base cases
        for (i in 0..len1) {
            dp[i][0] = i
        }
        for (j in 0..len2) {
            dp[0][j] = j
        }

        // Fill dp array
        for (i in 1..len1) {
            for (j in 1..len2) {
                val cost = if (s1[i - 1] == s2[j - 1]) 0 else 1

                dp[i][j] = minOf(
                    dp[i - 1][j] + 1,      // deletion
                    dp[i][j - 1] + 1,      // insertion
                    dp[i - 1][j - 1] + cost, // substitution
                )
            }
        }

        return dp[len1][len2]
    }
}