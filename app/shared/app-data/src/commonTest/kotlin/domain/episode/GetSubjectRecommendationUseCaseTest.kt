/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.domain.episode

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.test.runTest
import me.him188.ani.app.data.models.BangumiSyncState
import me.him188.ani.app.data.models.SubjectCollectionCounts
import me.him188.ani.app.data.models.subject.SubjectInfo
import me.him188.ani.app.data.network.SubjectService
import me.him188.ani.client.models.AniSubjectRecommendation
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class GetSubjectRecommendationUseCaseTest {

    private class FakeSubjectService(
        private val recommendations: List<AniSubjectRecommendation>,
    ) : SubjectService {
        override suspend fun getSubjectRecommendations(subjectId: Int, limit: Int): List<AniSubjectRecommendation> {
            return recommendations
        }

        override suspend fun getSubject(subjectId: Int): SubjectInfo? = null
        override suspend fun setSubjectCollection(subjectId: Int, type: Int, tags: List<String>, comment: String?, rate: Int?, private: Boolean) {}
        override suspend fun deleteSubjectCollection(subjectId: Int) {}
        override fun subjectCollectionCountsFlow(): Flow<SubjectCollectionCounts> = emptyFlow()
        override suspend fun performBangumiFullSync() {}
        override suspend fun getBangumiFullSyncState(): BangumiSyncState? = null
    }

    @Test
    fun `filters out recommendations with external URI or non-positive subjectId`() = runTest {
        val fakeRecommendations = listOf(
            AniSubjectRecommendation(
                subjectName = "Legit Anime 1",
                subjectNameCn = "正版番剧1",
                imageUrl = "https://example.com/cover1.jpg",
                desc1 = "2024年4月",
                desc2 = "8.5分",
                subjectId = 1001L,
                uri = null,
            ),
            AniSubjectRecommendation(
                subjectName = "Sponsored Ad Card",
                subjectNameCn = "广告推广",
                imageUrl = "https://example.com/ad.jpg",
                desc1 = "赞助商提供",
                desc2 = "点击了解更多",
                subjectId = 9999L,
                uri = "https://ad.example.com/campaign",
            ),
            AniSubjectRecommendation(
                subjectName = "Legit Anime 2",
                subjectNameCn = "正版番剧2",
                imageUrl = "https://example.com/cover2.jpg",
                desc1 = "2024年7月",
                desc2 = "8.1分",
                subjectId = 1002L,
                uri = "",
            ),
            AniSubjectRecommendation(
                subjectName = "Ad Without SubjectId",
                subjectNameCn = "无效推荐",
                imageUrl = "https://example.com/ad2.jpg",
                desc1 = "推广内容",
                desc2 = "点击跳转",
                subjectId = null,
                uri = "https://ad2.example.com",
            ),
            AniSubjectRecommendation(
                subjectName = "Negative ID",
                subjectNameCn = "非法ID条目",
                imageUrl = "https://example.com/invalid.jpg",
                desc1 = "无效",
                desc2 = "无效",
                subjectId = -1L,
                uri = null,
            ),
        )

        val useCase = GetSubjectRecommendationUseCaseImpl(FakeSubjectService(fakeRecommendations))
        val result = useCase(123)

        assertEquals(2, result.size)
        assertEquals(1001L, result[0].subjectId)
        assertEquals("Legit Anime 1", result[0].name)
        assertNull(result[0].uri)

        assertEquals(1002L, result[1].subjectId)
        assertEquals("Legit Anime 2", result[1].name)
        assertNull(result[1].uri)
        assertTrue(result.all { it.uri == null })
    }
}
