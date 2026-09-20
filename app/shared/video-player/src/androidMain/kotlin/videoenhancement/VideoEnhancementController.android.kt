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

        val scalerTarget = enhancedOutputTarget(videoSize, viewportSize)
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
                    add(MobileLanczosScaleEffect(scalerTarget.width, scalerTarget.height))
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
 * 缩放层的目标尺寸, `null` 表示不叠加这一层.
 *
 * 只有"确实需要放大"且放大后的输出不超过 [MAX_ENHANCED_OUTPUT_PIXELS] 时才启用:
 * 超大输出会让整条效果链在极高分辦率下重跑, 分辨率越高收益越小、代价越大.
 * 输出规模在预算内时由 [MobileLanczosScaleEffect] 以可分离 Lanczos-2 完成放大.
 */
private fun enhancedOutputTarget(
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

/** 4K. 超过这个输出规模时, 增强链改由平台硬件缩放收尾. */
private const val MAX_ENHANCED_OUTPUT_PIXELS = 3840L * 2160L

internal const val exoEffectShaderDirectory = "exo-effects"