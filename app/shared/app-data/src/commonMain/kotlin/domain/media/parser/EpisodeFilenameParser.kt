/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.domain.media.parser

import me.him188.ani.datasources.api.EpisodeSort

data class ParsedEpisodeInfo(
    val rawFilename: String,
    val season: Int? = null,
    val episodeSort: EpisodeSort? = null,
    val episodeNumber: Float? = null,
    val isConfident: Boolean = false,
) {
    val episode: Double? get() = episodeNumber?.toDouble()
}

object EpisodeFilenameParser {
    private val NOISE_REGEX = Regex(
        """(?i)\b(2160[pP]|1080[pP]|720[pP]|480[pP]|4[kK]|1920x1080|1280x720|3840x2160|x264|x265|hevc|avc|h264|h265|10bit|8bit|hi10p|ma10p|flac|aac|dts|ac3|truehd|opus|mp3|web-?rip|bd-?rip|web-?dl|blu-?ray|baha|chs|cht|big5|gb|jp|eng|jap|sub|dub|v2|final)\b""",
    )

    private val VIDEO_EXT_REGEX = Regex("""\.(mp4|mkv|avi|flv|ts|mov|webm|wmv|m4v)$""", RegexOption.IGNORE_CASE)

    private val SXX_EXX_REGEX = Regex("""(?i)[sS](\d{1,2})[eE](\d{1,3}(?:\.\d+)?)""")
    private val SEASON_REGEX = Regex("""(?i)(?:season|\bS|part)\s*(\d{1,2})""")
    private val CHINESE_SEASON_REGEX = Regex("""第\s*([0-9一二三四五六七八九十]+)\s*[季期]""")

    private val CHINESE_EP_REGEX = Regex("""第\s*(\d{1,3}(?:\.\d+)?)\s*[集话話]""")
    private val EP_PREFIX_REGEX = Regex("""(?:^|[\s_.\-\[(])(?:EP|E|Ep|ep)\.?\s*(\d{1,3}(?:\.\d+)?)""")
    private val BRACKETED_EP_REGEX = Regex("""[\[【](\d{1,3}(?:\.\d+)?)(?:v\d+)?[\]】]""")
    private val DELIMITED_EP_REGEX = Regex("""(?:[-_]\s*|\s+-\s+)(\d{1,3}(?:\.\d+)?)(?:v\d+)?(?=[\s_.\[\(]|$)""")

    fun parse(filename: String): ParsedEpisodeInfo {
        val withoutExt = filename.replace(VIDEO_EXT_REGEX, "")

        var season: Int? = null
        var epFloat: Float? = null

        // 1. SxxExx
        val sxxExxMatch = SXX_EXX_REGEX.find(withoutExt)
        if (sxxExxMatch != null) {
            season = sxxExxMatch.groupValues[1].toIntOrNull()
            epFloat = sxxExxMatch.groupValues[2].toFloatOrNull()
            if (epFloat != null) {
                val epSort = if (epFloat % 1f == 0f) EpisodeSort(epFloat.toInt()) else EpisodeSort(epFloat.toString())
                return ParsedEpisodeInfo(
                    rawFilename = filename,
                    season = season,
                    episodeSort = epSort,
                    episodeNumber = epFloat,
                    isConfident = true,
                )
            }
        }

        // 2. Season parsing
        val seasonMatch = SEASON_REGEX.find(withoutExt)
        if (seasonMatch != null) {
            season = seasonMatch.groupValues[1].toIntOrNull()
        } else {
            val cnSeasonMatch = CHINESE_SEASON_REGEX.find(withoutExt)
            if (cnSeasonMatch != null) {
                val str = cnSeasonMatch.groupValues[1]
                season = parseChineseNumber(str)
            }
        }

        // 3. Noise removal for episode parsing
        val cleaned = withoutExt.replace(NOISE_REGEX, "")

        // 4. Episode parsing
        // 4a. Chinese episode: 第xx集
        val cnEpMatch = CHINESE_EP_REGEX.find(cleaned)
        if (cnEpMatch != null) {
            epFloat = cnEpMatch.groupValues[1].toFloatOrNull()
        }

        // 4b. EPxx / Exx
        if (epFloat == null) {
            val epPrefixMatch = EP_PREFIX_REGEX.find(cleaned)
            if (epPrefixMatch != null) {
                epFloat = epPrefixMatch.groupValues[1].toFloatOrNull()
            }
        }

        // 4c. Bracketed: [01], 【02】
        if (epFloat == null) {
            val bracketMatches = BRACKETED_EP_REGEX.findAll(cleaned).toList()
            for (m in bracketMatches) {
                val num = m.groupValues[1].toFloatOrNull()
                if (num != null && num in 0f..2000f) {
                    epFloat = num
                    break
                }
            }
        }

        // 4d. Delimited: - 01
        if (epFloat == null) {
            val delimMatch = DELIMITED_EP_REGEX.find(cleaned)
            if (delimMatch != null) {
                epFloat = delimMatch.groupValues[1].toFloatOrNull()
            }
        }

        val epSort = epFloat?.let { num ->
            if (num % 1f == 0f) EpisodeSort(num.toInt()) else EpisodeSort(num.toString())
        }

        return ParsedEpisodeInfo(
            rawFilename = filename,
            season = season,
            episodeSort = epSort,
            episodeNumber = epFloat,
            isConfident = epFloat != null,
        )
    }

    private fun parseChineseNumber(str: String): Int? {
        str.toIntOrNull()?.let { return it }
        val cnMap = mapOf(
            '一' to 1, '二' to 2, '三' to 3, '四' to 4, '五' to 5,
            '六' to 6, '七' to 7, '八' to 8, '九' to 9, '十' to 10,
        )
        if (str.length == 1) {
            return cnMap[str[0]]
        }
        if (str.length == 2 && str.startsWith("十")) {
            return 10 + (cnMap[str[1]] ?: 0)
        }
        return null
    }
}
