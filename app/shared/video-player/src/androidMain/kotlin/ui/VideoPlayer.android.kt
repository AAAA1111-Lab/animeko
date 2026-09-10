/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.videoplayer.ui

import android.graphics.Color
import android.graphics.Typeface
import android.view.SurfaceView
import android.view.View
import androidx.annotation.OptIn
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.Player
import androidx.media3.common.Tracks
import androidx.media3.common.VideoSize
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.CaptionStyleCompat
import androidx.media3.ui.PlayerView
import androidx.media3.ui.PlayerView.ControllerVisibilityListener
import io.github.peerless2012.ass.media.widget.AssSubtitleView
import me.him188.ani.app.videoplayer.media.LibassExoPlayerMediampPlayer
import org.openani.mediamp.MediampPlayer
import org.openani.mediamp.exoplayer.ExoPlayerMediampPlayer
import org.openani.mediamp.features.AspectRatioMode
import org.openani.mediamp.features.VideoAspectRatio

@OptIn(UnstableApi::class)
@Composable
actual fun VideoPlayer(
    player: MediampPlayer,
    modifier: Modifier
) {
    val isPreviewing by rememberUpdatedState(me.him188.ani.app.ui.foundation.LocalIsPreviewing.current)

    if (isPreviewing) {
        Box(modifier)
    } else {
        val libassPlayer = player as? LibassExoPlayerMediampPlayer
        val exoPlayer = libassPlayer?.exoMediampPlayer ?: player as ExoPlayerMediampPlayer
        PatchedExoPlayerMediampPlayerSurface(exoPlayer, modifier) {
            (videoSurfaceView as? SurfaceView)?.let { registerAndroidVideoSurface(player, it) }
            controllerAutoShow = false
            useController = false
            controllerHideOnTouch = false
            subtitleView?.apply {
                libassPlayer?.let { addView(AssSubtitleView(context, it.assHandler)) }
                this.setStyle(
                    CaptionStyleCompat(
                        Color.WHITE,
                        0x000000FF,
                        0x00000000,
                        CaptionStyleCompat.EDGE_TYPE_OUTLINE,
                        Color.BLACK,
                        Typeface.DEFAULT,
                    ),
                )
            }
            setControllerVisibilityListener(
                ControllerVisibilityListener { visibility ->
                    if (visibility == View.VISIBLE) {
                        hideController()
                    }
                },
            )
        }
    }
}

/**
 * Patched version of [org.openani.mediamp.exoplayer.compose.ExoPlayerMediampPlayerSurface].
 * Under video effects, media3 1.9.0 stops reporting [VideoSize] due to b/292111083.
 * This surface applies [applyVideoAspectRatioFallback] on track changes so that
 * [AspectRatioFrameLayout] retains the correct aspect ratio, preventing libass subtitles
 * from being horizontally stretched (#3364, mediamp#69).
 */
@OptIn(UnstableApi::class)
@Composable
private fun PatchedExoPlayerMediampPlayerSurface(
    mediampPlayer: ExoPlayerMediampPlayer,
    modifier: Modifier = Modifier,
    configuration: PlayerView.() -> Unit = {},
) {
    val aspectRatioMode by mediampPlayer.features[VideoAspectRatio.Key]?.mode?.collectAsState()
        ?: return // Return early if VideoAspectRatio feature is not available

    var playerView by remember { mutableStateOf<PlayerView?>(null) }

    AndroidView(
        factory = { context ->
            PlayerView(context).apply {
                useController = false
                this.player = mediampPlayer.impl
                configuration()
            }.also { playerView = it }
        },
        modifier,
        update = { view ->
            view.resizeMode = when (aspectRatioMode) {
                AspectRatioMode.FIT -> AspectRatioFrameLayout.RESIZE_MODE_FIT
                AspectRatioMode.CROP -> AspectRatioFrameLayout.RESIZE_MODE_ZOOM
                AspectRatioMode.STRETCH -> AspectRatioFrameLayout.RESIZE_MODE_FILL
            }
        },
    )

    val player = mediampPlayer.impl
    val view = playerView
    if (view != null) {
        DisposableEffect(view, player) {
            val listener = object : Player.Listener {
                override fun onTracksChanged(tracks: Tracks) = applyVideoAspectRatioFallback(view, player)
                override fun onVideoSizeChanged(videoSize: VideoSize) = applyVideoAspectRatioFallback(view, player)
            }
            player.addListener(listener)
            // Tracks may already be known by the time the view exists.
            applyVideoAspectRatioFallback(view, player)
            onDispose { player.removeListener(listener) }
        }
    }
}
