/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.data.persistent.database.dao

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import me.him188.ani.app.data.persistent.database.AniDatabase
import me.him188.ani.app.data.persistent.database.createTestAniDatabase
import me.him188.ani.danmaku.api.DanmakuContent
import me.him188.ani.danmaku.api.DanmakuLocation
import me.him188.ani.danmaku.api.DanmakuServiceId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * 覆盖"清空弹幕缓存"依赖的两个 DAO 能力: 全库计数与全库删除.
 *
 * `countAll`/`deleteAll` 不限定 subject/episode, 所以必须在真实 schema 上验证, 而不是 memory fake —
 * 一旦以后有人给 deleteAll 加上 subject 条件, fake 测不出来.
 */
class DanmakuDaoTest {
    @Test
    fun `countAll counts danmaku across all subjects`() = runTest {
        withDatabase { db ->
            val dao = db.danmakuDao()
            dao.upsertAll(
                listOf(
                    danmaku("a1", subjectId = 1, episodeId = 11),
                    danmaku("a2", subjectId = 1, episodeId = 11),
                    danmaku("b1", subjectId = 2, episodeId = 21),
                ),
            )

            assertEquals(3, dao.countAll())
            assertEquals(3, dao.countAllFlow().first())
        }
    }

    @Test
    fun `countAll is zero on an empty database`() = runTest {
        withDatabase { db ->
            assertEquals(0, db.danmakuDao().countAll())
        }
    }

    @Test
    fun `deleteAll removes every subject but keeps the table usable`() = runTest {
        withDatabase { db ->
            val dao = db.danmakuDao()
            dao.upsertAll(
                listOf(
                    danmaku("a1", subjectId = 1, episodeId = 11),
                    danmaku("b1", subjectId = 2, episodeId = 21),
                ),
            )
            assertEquals(2, dao.countAll())

            dao.deleteAll()

            assertEquals(0, dao.countAll())
            assertTrue(dao.getDanmaku(1, 11).isEmpty())
            assertTrue(dao.getDanmaku(2, 21).isEmpty())

            dao.upsertAll(listOf(danmaku("c1", subjectId = 3, episodeId = 31)))
            assertEquals(1, dao.countAll())
        }
    }

    @Test
    fun `upsertAll deduplicates by danmaku id`() = runTest {
        withDatabase { db ->
            val dao = db.danmakuDao()
            dao.upsertAll(listOf(danmaku("dup", subjectId = 1, episodeId = 11)))
            dao.upsertAll(
                listOf(
                    danmaku("dup", subjectId = 1, episodeId = 11),
                    danmaku("new", subjectId = 1, episodeId = 11),
                ),
            )

            assertEquals(2, dao.countAll())
        }
    }

    private suspend fun withDatabase(block: suspend (AniDatabase) -> Unit) {
        val db = createTestAniDatabase()
        try {
            block(db)
        } finally {
            db.close()
        }
    }

    private fun danmaku(
        id: String,
        subjectId: Int,
        episodeId: Int,
    ): DanmakuEntity {
        return DanmakuEntity(
            id = id,
            subjectId = subjectId,
            episodeId = episodeId,
            serviceId = DanmakuServiceId.Dandanplay,
            presentationServiceId = DanmakuServiceId.Dandanplay,
            senderId = "sender-$id",
            content = DanmakuContent(
                playTimeMillis = 1000,
                color = 0xFFFFFF,
                text = "danmaku-$id",
                location = DanmakuLocation.NORMAL,
            ),
        )
    }
}
