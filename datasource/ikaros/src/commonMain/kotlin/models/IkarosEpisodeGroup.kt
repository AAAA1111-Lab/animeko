/*
 * Copyright (C) 2024-2025 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.datasources.ikaros.models

import kotlinx.serialization.Serializable

@Serializable
enum class IkarosEpisodeGroup {
    MAIN,

    /**
     * PV.
     */
    PROMOTION_VIDEO,

    /**
     * OP.
     */
    OPENING_SONG,

    /**
     * ED.
     */
    ENDING_SONG,

    /**
     * SP.
     */
    SPECIAL_PROMOTION,

    /**
     * ST.
     */
    SMALL_THEATER,

    /**
     * Live.
     */
    LIVE,

    /**
     * CM: Commercial Message.
     */
    COMMERCIAL_MESSAGE,

    /**
     * OST: Original Sound Track.
     */
    ORIGINAL_SOUND_TRACK,

    /**
     * OVA: Original Video Animation.
     */
    ORIGINAL_VIDEO_ANIMATION,

    /**
     * OAD: Original Animation Disc.
     */
    ORIGINAL_ANIMATION_DISC,
    MUSIC_DIST1,
    MUSIC_DIST2,
    MUSIC_DIST3,
    MUSIC_DIST4,
    MUSIC_DIST5,
    OTHER
}
