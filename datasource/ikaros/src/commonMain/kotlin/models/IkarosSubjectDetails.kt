/*
 * Copyright (C) 2024-2025 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.datasources.ikaros.models

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class IkarosSubjectDetails(
    val id: Long,
    val name: String?,
    @SerialName("name_cn") val nameCn: String?,
    val infobox: String?,
    val summary: String?,
    val nsfw: Boolean?,
    @SerialName("airTime") val airTime: String?,
    val cover: String?,
    @SerialName("collection_status") val collectionType: IkarosCollectionType?,
    val canRead: Boolean = true,
    @SerialName("total_episodes") val totalEpisodes: Long,
    val episodes: List<IkarosEpisodeDetails>,
)
