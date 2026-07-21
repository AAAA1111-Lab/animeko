/*
 * Copyright (C) 2024-2025 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.domain.mediasource.instance

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import me.him188.ani.app.domain.media.fetch.MediaSourceInfoWithId
import me.him188.ani.app.domain.media.fetch.MediaSourceManager
import me.him188.ani.app.domain.usecase.UseCase

interface GetMediaSourceInstancesUseCase : UseCase {
    operator fun invoke(): Flow<List<MediaSourceInstance>>

    fun getAsMediaSourceInfoWithId(): Flow<List<MediaSourceInfoWithId>> = invoke().map { instances ->
        instances.map {
            MediaSourceInfoWithId(
                instanceId = it.instanceId,
                mediaSourceId = it.mediaSourceId,
                info = it.source.info,
            )
        }
    }
}

class GetMediaSourceInstancesUseCaseImpl(
    private val mediaSourceManager: MediaSourceManager,
) : GetMediaSourceInstancesUseCase {
    override fun invoke(): Flow<List<MediaSourceInstance>> = mediaSourceManager.allInstances
}
