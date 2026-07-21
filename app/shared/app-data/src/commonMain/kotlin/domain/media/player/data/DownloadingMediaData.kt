/*
 * Copyright (C) 2024 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.domain.media.player.data

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import org.openani.mediamp.source.MediaData

/**
 * A media data that is currently being downloaded. So there is [networkStats] to be displayed.
 */
sealed interface DownloadingMediaData {
    /**
     * Subscribe to network stats updates of this video data, if known.
     */
    val networkStats: Flow<NetStats>

    val isCacheFinished: Flow<Boolean> get() = flowOf(false)
}

sealed interface FileMediaData {
    val filename: String?
}

val MediaData.filenameOrNull: String? get() = (this as? FileMediaData)?.filename

class NetStats(
    /**
     * The download speed in bytes per second.
     *
     * May return `-1` if it is not known.
     */
    val downloadSpeed: Long,

    /**
     * The upload speed in bytes per second.
     *
     * May return `-1` if it is not known.
     */
    val uploadRate: Long,
)


