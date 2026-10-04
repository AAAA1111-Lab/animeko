/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)

package me.him188.ani.app.videoplayer.ui

import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import kotlin.math.roundToInt

/**
 * Display size of the current video, in pixels, with the pixel aspect ratio and rotation applied.
 * Returns null while no video track is selected or its size is unknown.
 *
 * Once ExoPlayer.setVideoEffects has been called, even with an empty list, media3 1.9.0 routes
 * video through the VideoSink path and stops reporting VideoSize to Player.Listener:
 * MediaCodecVideoRenderer calls maybeNotifyVideoSizeChanged only while videoSink is null, and the
 * sink listener it installs in configureVideoSink has an empty onVideoSizeChanged (b/292111083).
 * The selected video track's Format is then the only source of the size.
 */
@OptIn(UnstableApi::class)
internal fun ExoPlayer.videoDisplaySizeOrNull(): VideoDisplaySize? {
    val videoSize = this.videoSize
    if (videoSize.width > 0 && videoSize.height > 0) {
        // VideoSize is already rotated.
        return VideoDisplaySize(
            (videoSize.width * videoSize.pixelWidthHeightRatio).roundToInt(),
            videoSize.height,
        )
    }

    val group = currentTracks.groups.firstOrNull { it.type == C.TRACK_TYPE_VIDEO && it.isSelected }
        ?: return null
    val selectedIndex = (0 until group.length).firstOrNull { group.isTrackSelected(it) } ?: 0
    val format = group.getTrackFormat(selectedIndex)
    return displaySizeOrNull(
        format.width,
        format.height,
        format.pixelWidthHeightRatio,
        format.rotationDegrees,
    )
}

@OptIn(UnstableApi::class)
internal fun applyVideoAspectRatioFallback(view: PlayerView, player: ExoPlayer) {
    if (player.videoSize.width > 0 && player.videoSize.height > 0) {
        return // PlayerView applies the aspect ratio itself in this case.
    }
    val size = player.videoDisplaySizeOrNull() ?: return
    val contentFrame = view.findViewById<AspectRatioFrameLayout>(androidx.media3.ui.R.id.exo_content_frame)
        ?: return
    contentFrame.setAspectRatio(size.width.toFloat() / size.height)
}
