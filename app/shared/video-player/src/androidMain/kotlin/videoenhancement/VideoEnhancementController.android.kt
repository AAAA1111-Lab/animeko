/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)

package me.him188.ani.app.videoplayer.videoenhancement

import androidx.media3.exoplayer.ExoPlayer
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import me.him188.ani.app.data.models.preference.PlayerKernelConfig
import org.openani.mediamp.MediampPlayer
import kotlin.coroutines.CoroutineContext
import kotlin.math.roundToInt

actual fun createVideoEnhancementController(
    player: MediampPlayer,
    playerKernelConfig: Flow<PlayerKernelConfig>,
    parentCoroutineContext: CoroutineContext,
): VideoEnhancementController? {
    val exoPlayer = player.impl as? ExoPlayer ?: return null
    return ExoPlayerVideoEnhancementController(
        player,
        exoPlayer,
        playerKernelConfig.map { it.exoPlayerInitEffectGraphInAdvance },
        parentCoroutineContext,
    )
}

private class ExoPlayerVideoEnhancementController(
    player: MediampPlayer,
    private val exoPlayer: ExoPlayer,
    preinitVideoEffects: Flow<Boolean>,
    parentCoroutineContext: CoroutineContext,
) : BaseVideoEnhancementController(player, parentCoroutineContext) {
    private var appliedMode = VideoEnhancementMode.OFF
    private var scalerApplied = false
    private var appliedWidth = 0
    private var appliedHeight = 0

    init {
        // Media3 requires the effect graph to exist before the first prepare in order to
        // support switching effects while playback is active.
        scope.launch {
            if (preinitVideoEffects.first()) {
                exoPlayer.setVideoEffects(emptyList())
            }
        }
        startObserving()
    }

    override suspend fun apply(
        mode: VideoEnhancementMode,
        videoSize: VideoDimensions?,
        viewportSize: VideoDimensions?,
    ) {
        if (mode == VideoEnhancementMode.OFF) {
            restore()
            return
        }

        val scalerTarget = lanczosSharpScalerTarget(videoSize, viewportSize)
        val scalerAppliedNow = scalerTarget != null
        if (
            appliedMode == mode && scalerApplied == scalerAppliedNow &&
            (scalerTarget == null || appliedWidth == scalerTarget.width && appliedHeight == scalerTarget.height)
        ) return

        exoPlayer.setVideoEffects(
            buildList {
                when (mode) {
                    VideoEnhancementMode.OFF -> Unit
                    VideoEnhancementMode.FAST -> add(ContrastAdaptiveSharpenEffect)
                    VideoEnhancementMode.PERFORMANCE -> add(Anime4kRestoreEffect)
                    VideoEnhancementMode.QUALITY -> {
                        add(Anime4kRestoreQualityEffect)
                        add(Anime4kUpscaleQualityEffect)
                    }
                }
                if (scalerTarget != null) {
                    add(DesktopStyleLanczosSharpEffect(scalerTarget.width, scalerTarget.height))
                }
            },
        )
        appliedMode = mode
        scalerApplied = scalerAppliedNow
        appliedWidth = scalerTarget?.width ?: 0
        appliedHeight = scalerTarget?.height ?: 0
    }

    override fun restore() {
        if (appliedMode == VideoEnhancementMode.OFF) return
        exoPlayer.setVideoEffects(emptyList())
        appliedMode = VideoEnhancementMode.OFF
        scalerApplied = false
        appliedWidth = 0
        appliedHeight = 0
    }
}

/**
 * `ewa_lanczossharp` 缩放的目标尺寸, `null` 表示不叠加这一层.
 *
 * 该 shader 每个输出像素要采样约 8x8 邻域 (含 sigmoid 与 anti-ringing), 是整条链里最贵的一步.
 * 只有在"确实需要放大"且放大后的输出不超过 [MAX_ENHANCED_OUTPUT_PIXELS] 时才使用它:
 * 4K 屏上看 1080p (输出约 830 万像素) 会直接跳过, 交给平台的硬件缩放完成剩余放大,
 * 否则中端 GPU (例如骁龙 845) 会持续掉帧.
 */
private fun lanczosSharpScalerTarget(
    videoSize: VideoDimensions?,
    viewportSize: VideoDimensions?,
): VideoDimensions? {
    if (videoSize == null || viewportSize == null) return null
    val scale = minOf(
        viewportSize.width.toDouble() / videoSize.width,
        viewportSize.height.toDouble() / videoSize.height,
    )
    if (scale <= 1.0) return null // 不需要放大, 不叠加额外的全屏 pass
    val outputWidth = (videoSize.width * scale).roundToInt().coerceAtLeast(1)
    val outputHeight = (videoSize.height * scale).roundToInt().coerceAtLeast(1)
    if (outputWidth.toLong() * outputHeight > MAX_ENHANCED_OUTPUT_PIXELS) return null
    return VideoDimensions(outputWidth, outputHeight)
}

/** 1080p. 超过这个输出规模时, 增强链改由平台硬件缩放收尾. */
private const val MAX_ENHANCED_OUTPUT_PIXELS = 1920L * 1080L

internal const val exoEffectShaderDirectory = "exo-effects"