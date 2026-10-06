/*
 * Copyright (C) 2024-2025 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.data.persistent.database.dao

import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Embedded
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.Query
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow
import me.him188.ani.danmaku.api.DanmakuContent
import me.him188.ani.danmaku.api.DanmakuInfo
import me.him188.ani.danmaku.api.DanmakuServiceId
import me.him188.ani.danmaku.api.provider.DanmakuFetchRequest
import me.him188.ani.danmaku.api.provider.DanmakuFetchResult
import me.him188.ani.danmaku.api.provider.DanmakuMatchInfo
import me.him188.ani.danmaku.api.provider.DanmakuMatchMethod
import me.him188.ani.danmaku.api.provider.DanmakuProviderId
import me.him188.ani.danmaku.api.provider.SimpleDanmakuProvider

/**
 * 弹幕缓存是怎么写入的.
 *
 * 区分二者的唯一目的是: 自动清理只能删除 [AUTO], 不能动用户主动缓存的 [MANUAL].
 */
enum class DanmakuCacheOrigin {
    /** 用户在播放页或条目缓存页主动缓存. */
    MANUAL,

    /** 按 [me.him188.ani.app.data.models.preference.DanmakuCacheStrategy] 自动缓存. */
    AUTO,
}

@Entity(
    tableName = "danmaku",
    primaryKeys = ["id"],
    foreignKeys = [
        ForeignKey(
            entity = SubjectCollectionEntity::class,
            parentColumns = ["subjectId"],
            childColumns = ["subjectId"],
            onDelete = ForeignKey.CASCADE,
        ),
        ForeignKey(
            entity = EpisodeCollectionEntity::class,
            parentColumns = ["subjectId", "episodeId"],
            childColumns = ["subjectId", "episodeId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [
        Index(value = ["id"]),
        Index(value = ["subjectId"]),
        Index(value = ["subjectId", "episodeId"]),
        /** 按最近缓存时间淘汰自动缓存时使用. */
        Index(value = ["origin", "cachedAtMillis"]),
    ],
)
class DanmakuEntity(
    val id: String,
    val subjectId: Int,
    val episodeId: Int,
    val serviceId: DanmakuServiceId, // 弹幕源的 service id
    val presentationServiceId: DanmakuServiceId, // 外显 service id, dandanplay 的 origin
    val senderId: String,
    /**
     * 这条弹幕属于手动缓存还是自动缓存.
     *
     * 存量数据的默认值是 [DanmakuCacheOrigin.MANUAL]: 迁移前无法区分来源, 按保守处理, 不自动删除,
     * 避免升级后立刻清掉用户此前手动缓存的内容. 它们可以在设置-存储里单独清理.
     */
    @ColumnInfo(defaultValue = "MANUAL")
    val origin: DanmakuCacheOrigin,
    /** 本行最后一次写入缓存的时间. 自动缓存的淘汰按它排序. */
    @ColumnInfo(defaultValue = "0")
    val cachedAtMillis: Long,
    @Embedded(prefix = "content_") val content: DanmakuContent
)

/** One row of [DanmakuDao.episodeDanmakuCountsFlow]. */
class EpisodeDanmakuCount(
    val episodeId: Int,
    val count: Int,
)

/**
 * 有弹幕缓存的一个剧集, 供设置-存储里的弹幕缓存管理使用.
 *
 * 名称列取自 `subject_collection` / `episode_collection`, 可空: 条目或剧集可能已被清理, 而弹幕行还在.
 */
class CachedDanmakuEpisode(
    val subjectId: Int,
    val episodeId: Int,
    val count: Int,
    /** 该集里手动缓存的行数. 大于 0 表示这一集有用户主动缓存的内容. */
    val manualCount: Int,
    /** 该集最近一次写入缓存的时间. */
    val lastCachedAtMillis: Long,
    val subjectNameCn: String?,
    val subjectName: String?,
    val episodeSort: Double?,
    val episodeNameCn: String?,
    val episodeName: String?,
) {
    val hasManual: Boolean get() = manualCount > 0
}

/** One episode's worth of automatic cache, used to pick eviction victims. */
class AutoCachedEpisode(
    val subjectId: Int,
    val episodeId: Int,
)

@Dao
interface DanmakuDao {
    @Query("SELECT COUNT(*) FROM danmaku WHERE subjectId = :subjectId AND episodeId = :episodeId")
    fun countBySubjectAndEpisode(subjectId: Int, episodeId: Int): Flow<Int>

    @Query("SELECT * FROM danmaku WHERE subjectId = :subjectId AND episodeId = :episodeId")
    suspend fun getDanmaku(subjectId: Int, episodeId: Int): List<DanmakuEntity>

    @Upsert
    suspend fun upsertAll(danmaku: List<DanmakuEntity>)

    @Query("DELETE FROM danmaku WHERE subjectId = :subjectId")
    suspend fun deleteBySubject(subjectId: Int)

    @Query("DELETE FROM danmaku WHERE subjectId = :subjectId AND episodeId = :episodeId")
    suspend fun deleteBySubjectAndEpisode(subjectId: Int, episodeId: Int)

    /**
     * 只删除某一集的自动缓存, 保留手动缓存.
     *
     * 自动清理走这里: 用户主动缓存过的内容不因为它同时被自动缓存过而被删掉.
     */
    @Query(
        "DELETE FROM danmaku WHERE subjectId = :subjectId AND episodeId = :episodeId " +
                "AND origin = 'AUTO'",
    )
    suspend fun deleteAutoBySubjectAndEpisode(subjectId: Int, episodeId: Int)

    /** 某一集里手动缓存的弹幕 id. 自动写入时用它避免把手动缓存降级成自动缓存. */
    @Query(
        "SELECT id FROM danmaku WHERE subjectId = :subjectId AND episodeId = :episodeId " +
                "AND origin = 'MANUAL'",
    )
    suspend fun manualDanmakuIds(subjectId: Int, episodeId: Int): List<String>

    /** 手动缓存的弹幕条数. 大于 0 表示这一集不该被自动清理. */
    @Query(
        "SELECT COUNT(*) FROM danmaku WHERE subjectId = :subjectId AND episodeId = :episodeId " +
                "AND origin = 'MANUAL'",
    )
    suspend fun countManualBySubjectAndEpisode(subjectId: Int, episodeId: Int): Int

    /**
     * 有自动缓存的剧集, 按最近一次缓存时间从早到晚排序, 用于淘汰超出上限的部分.
     *
     * 只统计 [DanmakuCacheOrigin.AUTO] 的行, 手动缓存不参与淘汰.
     */
    @Query(
        "SELECT subjectId, episodeId FROM danmaku WHERE origin = 'AUTO' " +
                "GROUP BY subjectId, episodeId ORDER BY MAX(cachedAtMillis) ASC",
    )
    suspend fun autoCachedEpisodesByAge(): List<AutoCachedEpisode>

    @Query("SELECT COUNT(*) FROM danmaku")
    suspend fun countAll(): Int

    /**
     * Cached danmaku count per episode of one subject. Episodes with no cached danmaku are absent
     * rather than present with a zero count.
     *
     * Returns a single row per episode number, so callers can render "already cached" per episode
     * and work out which episodes a bulk cache still has to fetch.
     */
    @Query("SELECT episodeId AS episodeId, COUNT(*) AS count FROM danmaku WHERE subjectId = :subjectId GROUP BY episodeId")
    fun episodeDanmakuCountsFlow(subjectId: Int): Flow<List<EpisodeDanmakuCount>>

    /**
     * 全部有弹幕缓存的剧集, 连带展示用的名称, 供设置-存储里的弹幕缓存管理使用.
     *
     * [episode_collection] 的主键就是 `episodeId`, 因此按它单列连接即可. 名称用左连接: 条目被清理后
     * 弹幕行可能仍在, 此时名称为空, 由调用方回退到 id.
     */
    @Query(
        "SELECT d.subjectId AS subjectId, d.episodeId AS episodeId, COUNT(*) AS count, " +
                "SUM(CASE WHEN d.origin = 'MANUAL' THEN 1 ELSE 0 END) AS manualCount, " +
                "MAX(d.cachedAtMillis) AS lastCachedAtMillis, " +
                "s.nameCn AS subjectNameCn, s.name AS subjectName, " +
                "e.sortNumber AS episodeSort, e.nameCn AS episodeNameCn, e.name AS episodeName " +
                "FROM danmaku d " +
                "LEFT JOIN subject_collection s ON s.subjectId = d.subjectId " +
                "LEFT JOIN episode_collection e ON e.episodeId = d.episodeId " +
                "GROUP BY d.subjectId, d.episodeId " +
                "ORDER BY MAX(d.cachedAtMillis) DESC",
    )
    fun cachedDanmakuEpisodesFlow(): Flow<List<CachedDanmakuEpisode>>

    @Query("SELECT COUNT(*) FROM danmaku")
    fun countAllFlow(): Flow<Int>

    @Query("DELETE FROM danmaku")
    suspend fun deleteAll()
}


class LocalDanmakuProvider(
    private val danmakuDao: DanmakuDao,
) : SimpleDanmakuProvider {
    override val providerId: DanmakuProviderId = DanmakuProviderId.Local
    override val mainServiceId: DanmakuServiceId = DanmakuServiceId.Animeko

    override suspend fun fetchAutomatic(request: DanmakuFetchRequest): List<DanmakuFetchResult> {
        val list = danmakuDao.getDanmaku(request.subjectId, request.episodeId)
            .groupBy { it.presentationServiceId }

        return list.map { (presentationServiceId, danmakus) ->
            DanmakuFetchResult(
                providerId = providerId,
                matchInfo = DanmakuMatchInfo(
                    serviceId = presentationServiceId,
                    count = danmakus.size,
                    method = DanmakuMatchMethod.ExactId(request.subjectId, request.episodeId),
                ),
                list = danmakus.map {
                    DanmakuInfo(
                        id = it.id,
                        serviceId = it.serviceId,
                        senderId = it.senderId,
                        content = it.content,
                    )
                },
            )
        }
    }
}