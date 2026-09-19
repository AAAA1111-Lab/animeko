/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.domain.media.cache

import me.him188.ani.app.data.models.subject.SubjectInfo
import me.him188.ani.app.domain.danmaku.DanmakuRepository
import me.him188.ani.app.domain.media.cache.storage.LocalImportFileItem
import me.him188.ani.app.domain.media.cache.storage.LocalImportMediaCacheStorage
import me.him188.ani.app.domain.usecase.UseCase
import me.him188.ani.danmaku.api.provider.DanmakuFetchRequest
import me.him188.ani.datasources.api.topic.inBytes
import kotlin.time.Duration

/**
 * 将本地视频文件导入为指定条目的已完成缓存 (软引用, 不复制文件),
 * 并为导入的各集预取弹幕.
 *
 * 条目缓存页与全局缓存管理页共用.
 */
interface ImportLocalVideosUseCase : UseCase {
    /**
     * @return 实际新导入的缓存数量, 重复导入的文件会被跳过.
     */
    suspend fun import(subjectInfo: SubjectInfo, items: List<LocalImportFileItem>): Int
}

class ImportLocalVideosUseCaseImpl(
    private val storage: LocalImportMediaCacheStorage,
    private val danmakuRepository: DanmakuRepository,
) : ImportLocalVideosUseCase {
    override suspend fun import(subjectInfo: SubjectInfo, items: List<LocalImportFileItem>): Int {
        val caches = storage.importFiles(
            subjectId = subjectInfo.subjectId,
            subjectNameCN = subjectInfo.nameCn.ifBlank { subjectInfo.name },
            subjectNames = listOfNotNull(subjectInfo.nameCn, subjectInfo.name).distinct(),
            items = items,
        )
        caches.forEach { cache ->
            try {
                val episodeId = cache.metadata.episodeId.toIntOrNull() ?: return@forEach
                danmakuRepository.cacheDanmakuIfNeeded(
                    DanmakuFetchRequest(
                        subjectId = subjectInfo.subjectId,
                        subjectPrimaryName = subjectInfo.displayName,
                        subjectNames = subjectInfo.allNames,
                        subjectPublishDate = subjectInfo.airDate,
                        episodeId = episodeId,
                        episodeSort = cache.metadata.episodeSort,
                        episodeEp = cache.metadata.episodeEp,
                        episodeName = cache.metadata.episodeName,
                        filename = cache.origin.originalTitle,
                        fileSize = cache.fileStats.first().totalSize.inBytes,
                        fileHash = null,
                        videoDuration = Duration.ZERO,
                    ),
                )
            } catch (_: Throwable) {
            }
        }
        return caches.size
    }
}
