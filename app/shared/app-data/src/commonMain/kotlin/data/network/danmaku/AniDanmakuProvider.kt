/*
 * Copyright (C) 2024-2025 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.data.network.danmaku

import me.him188.ani.client.apis.DanmakuAniApi
import me.him188.ani.client.models.AniDanmakuLocation
import me.him188.ani.danmaku.api.DanmakuContent
import me.him188.ani.danmaku.api.DanmakuInfo
import me.him188.ani.danmaku.api.DanmakuLocation
import me.him188.ani.danmaku.api.DanmakuServiceId
import me.him188.ani.danmaku.api.provider.DanmakuFetchRequest
import me.him188.ani.danmaku.api.provider.DanmakuFetchResult
import me.him188.ani.danmaku.api.provider.DanmakuMatchInfo
import me.him188.ani.danmaku.api.provider.DanmakuMatchMethod
import me.him188.ani.danmaku.api.provider.DanmakuProviderId
import me.him188.ani.danmaku.api.provider.SimpleDanmakuProvider
import me.him188.ani.utils.ktor.ApiInvoker
import me.him188.ani.utils.logging.info
import me.him188.ani.utils.logging.thisLogger

class AniDanmakuProvider(
    private val danmakuApi: ApiInvoker<DanmakuAniApi>,
) : SimpleDanmakuProvider {
    private val logger = thisLogger()
    override val providerId: DanmakuProviderId get() = DanmakuProviderId.Animeko

    companion object {
        val ID = DanmakuServiceId.Animeko
    }

    override val mainServiceId: DanmakuServiceId get() = ID

    override suspend fun fetchAutomatic(request: DanmakuFetchRequest): List<DanmakuFetchResult> {
        val list = danmakuApi {
            getDanmaku(request.episodeId.toString()).body().danmakuList
        }
        logger.info { "$ID Fetched danmaku list: ${list.size}" }
        return listOf(
            DanmakuFetchResult(
                providerId,
                matchInfo = DanmakuMatchInfo(
                    serviceId = DanmakuServiceId.Animeko,
                    count = list.size,
                    method = DanmakuMatchMethod.ExactId(request.subjectId, request.episodeId),
                ),
                list = list.map {
                    DanmakuInfo(
                        id = it.id,
                        serviceId = DanmakuServiceId.Animeko, senderId = it.senderId,
                        content = DanmakuContent(
                            it.danmakuInfo.playTime,
                            it.danmakuInfo.color,
                            it.danmakuInfo.text,
                            it.danmakuInfo.location.toLocation(),
                        ),
                    )
                },
            ),
        )
    }
}

object AniBangumiSeverBaseUrls {
    const val GLOBAL = "https://danmaku-global.myani.org"
    const val CN = "https://danmaku-cn.myani.org"

    val list = listOf(CN, GLOBAL)
}

private fun AniDanmakuLocation.toLocation(): DanmakuLocation {
    return when (this) {
        AniDanmakuLocation.TOP -> DanmakuLocation.TOP
        AniDanmakuLocation.BOTTOM -> DanmakuLocation.BOTTOM
        AniDanmakuLocation.NORMAL -> DanmakuLocation.NORMAL
    }
}
