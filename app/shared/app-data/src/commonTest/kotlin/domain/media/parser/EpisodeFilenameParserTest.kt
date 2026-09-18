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
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class EpisodeFilenameParserTest {

    @Test
    fun testStandardSxxExx() {
        val res = EpisodeFilenameParser.parse("Dungeon Meshi S01E05 1080p WEB-DL AAC2.0 H.264.mkv")
        assertEquals(1, res.season)
        assertEquals(EpisodeSort(5), res.episodeSort)
        assertEquals(5f, res.episodeNumber)
        assertEquals(5.0, res.episode)
        assertTrue(res.isConfident)
    }

    @Test
    fun testSeason2SxxExx() {
        val res = EpisodeFilenameParser.parse("Mushoku Tensei S02E10.mkv")
        assertEquals(2, res.season)
        assertEquals(EpisodeSort(10), res.episodeSort)
        assertTrue(res.isConfident)
    }

    @Test
    fun testChineseSeasonAndEpisode() {
        val res = EpisodeFilenameParser.parse("无职转生 第二季 第07集.mp4")
        assertEquals(2, res.season)
        assertEquals(EpisodeSort(7), res.episodeSort)
        assertTrue(res.isConfident)
    }

    @Test
    fun testChineseEpisodeOnly() {
        val res = EpisodeFilenameParser.parse("葬送的芙莉莲 第01话 1080p.mkv")
        assertEquals(EpisodeSort(1), res.episodeSort)
        assertTrue(res.isConfident)
    }

    @Test
    fun testFansubDashSeparated() {
        val res = EpisodeFilenameParser.parse("[LoliHouse] Sousou no Frieren - 01 [WebRip 1080p HEVC-10bit AAC].mkv")
        assertEquals(EpisodeSort(1), res.episodeSort)
        assertTrue(res.isConfident)
    }

    @Test
    fun testFansubBracketed() {
        val res = EpisodeFilenameParser.parse("[BeanSub][Sousou no Frieren][02][1080P][x264_AAC][CHS].mp4")
        assertEquals(EpisodeSort(2), res.episodeSort)
        assertTrue(res.isConfident)
    }

    @Test
    fun testFansubFullWidthBrackets() {
        val res = EpisodeFilenameParser.parse("【千夏字幕组】【怪兽8号_Kaiju No.8】【第03话】【BIG5_MP4】【1080P】.mp4")
        assertEquals(EpisodeSort(3), res.episodeSort)
        assertTrue(res.isConfident)
    }

    @Test
    fun testFansubNCRaws() {
        val res = EpisodeFilenameParser.parse("[NC-Raws] 摇曳露营 第三季 - 04 (B-Global 1920x1080 HEVC AAC MKV).mkv")
        assertEquals(3, res.season)
        assertEquals(EpisodeSort(4), res.episodeSort)
        assertTrue(res.isConfident)
    }

    @Test
    fun testEnglishSeason2Dash() {
        val res = EpisodeFilenameParser.parse("Oshi no Ko Season 2 - 06 (1080p).mkv")
        assertEquals(2, res.season)
        assertEquals(EpisodeSort(6), res.episodeSort)
        assertTrue(res.isConfident)
    }

    @Test
    fun testEpPrefix() {
        val res = EpisodeFilenameParser.parse("Sousou_no_Frieren_EP08_1080p.mp4")
        assertEquals(EpisodeSort(8), res.episodeSort)
        assertTrue(res.isConfident)
    }

    @Test
    fun testVersionSuffix() {
        val res = EpisodeFilenameParser.parse("Girls Band Cry - 12v2 (BD 1920x1080 HEVC-yuv420p10 FLAC).mkv")
        assertEquals(EpisodeSort(12), res.episodeSort)
        assertTrue(res.isConfident)
    }

    @Test
    fun testDecimalEpisode() {
        val res = EpisodeFilenameParser.parse("[VCB-Studio] Bocchi the Rock! - 08.5 [Ma10p_1080p].mkv")
        assertEquals(EpisodeSort("8.5"), res.episodeSort)
        assertEquals(8.5f, res.episodeNumber)
        assertTrue(res.isConfident)
    }
}
