/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.videoplayer.ui

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class VideoDisplaySizeTest {

    @Test
    fun square_pixels_pass_the_stored_size_through() {
        assertEquals(
            VideoDisplaySize(1920, 1080),
            displaySizeOrNull(1920, 1080, pixelWidthHeightRatio = 1f, rotationDegrees = 0),
        )
    }

    @Test
    fun anamorphic_ratio_widens_the_stored_width() {
        // 720x576 PAL stored at 16:9 display aspect: PAR 1.4587 -> 1050x576.
        assertEquals(
            VideoDisplaySize(1050, 576),
            displaySizeOrNull(720, 576, pixelWidthHeightRatio = 1.4587f, rotationDegrees = 0),
        )
    }

    @Test
    fun quarter_turns_swap_the_axes() {
        for (rotation in listOf(90, 270)) {
            assertEquals(
                VideoDisplaySize(1080, 1920),
                displaySizeOrNull(1920, 1080, pixelWidthHeightRatio = 1f, rotationDegrees = rotation),
                "rotation=$rotation",
            )
        }
    }

    @Test
    fun half_turns_keep_the_axes() {
        assertEquals(
            VideoDisplaySize(1920, 1080),
            displaySizeOrNull(1920, 1080, pixelWidthHeightRatio = 1f, rotationDegrees = 180),
        )
    }

    @Test
    fun a_quarter_turn_scales_the_display_height_by_the_ratio() {
        // The ratio applies to the stored width, which becomes the display height after the turn.
        assertEquals(
            VideoDisplaySize(576, 1050),
            displaySizeOrNull(720, 576, pixelWidthHeightRatio = 1.4587f, rotationDegrees = 90),
        )
    }

    @Test
    fun an_unknown_stored_size_has_no_display_size() {
        assertNull(displaySizeOrNull(0, 1080, pixelWidthHeightRatio = 1f, rotationDegrees = 0))
        assertNull(displaySizeOrNull(1920, 0, pixelWidthHeightRatio = 1f, rotationDegrees = 0))
        assertNull(displaySizeOrNull(-1, -1, pixelWidthHeightRatio = 1f, rotationDegrees = 0))
    }
}
