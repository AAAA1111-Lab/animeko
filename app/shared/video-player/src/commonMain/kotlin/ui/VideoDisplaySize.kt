/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.videoplayer.ui

import kotlin.math.roundToInt

data class VideoDisplaySize(val width: Int, val height: Int)

/**
 * Display size of a video frame stored as [storedWidth] x [storedHeight], or null if either is
 * unknown.
 *
 * [pixelWidthHeightRatio] describes the stored frame, so it scales the stored width; after a
 * quarter-turn that axis is the display height. Format normalizes an unknown ratio to 1
 * (Format.NO_VALUE never reaches here), so there is nothing to guard against.
 */
fun displaySizeOrNull(
    storedWidth: Int,
    storedHeight: Int,
    pixelWidthHeightRatio: Float,
    rotationDegrees: Int,
): VideoDisplaySize? {
    if (storedWidth <= 0 || storedHeight <= 0) return null
    val width = (storedWidth * pixelWidthHeightRatio).roundToInt()
    return if (rotationDegrees == 90 || rotationDegrees == 270) {
        VideoDisplaySize(storedHeight, width)
    } else {
        VideoDisplaySize(width, storedHeight)
    }
}
