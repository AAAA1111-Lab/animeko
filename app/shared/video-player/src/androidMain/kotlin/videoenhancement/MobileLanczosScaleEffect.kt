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

import android.content.Context
import android.opengl.GLES20
import androidx.media3.common.VideoFrameProcessingException
import androidx.media3.common.util.GlProgram
import androidx.media3.common.util.GlUtil
import androidx.media3.common.util.Size
import androidx.media3.effect.BaseGlShaderProgram
import androidx.media3.effect.GlEffect
import androidx.media3.effect.GlShaderProgram
import me.him188.ani.utils.video.enhancement.shader.provider.VideoEnhancementShaderProvider
import kotlin.math.roundToInt

/**
 * Mobile-friendly upscaler used by the Android enhancement chain.
 *
 * The desktop chain relies on mpv's `ewa_lanczossharp`. Emulating it in ExoPlayer (the removed
 * `DesktopStyleLanczosSharpEffect`) evaluates an 8x8 neighbourhood plus a sigmoid transfer function
 * and an anti-ringing clamp for every output pixel, which is a large cost once the viewport is 4k.
 * This effect keeps the scaling job (which the compositor would otherwise do with a plain bilinear
 * stretch) but uses a separable Lanczos-2 kernel (`lanczos2.frag`): 9 texture fetches and no
 * transcendentals per pixel.
 */
internal class MobileLanczosScaleEffect(
    private val viewportWidth: Int,
    private val viewportHeight: Int,
) : GlEffect {
    override fun toGlShaderProgram(context: Context, useHdr: Boolean): GlShaderProgram =
        MobileLanczosScaleShaderProgram(context, viewportWidth, viewportHeight)
}

private class MobileLanczosScaleShaderProgram(
    context: Context,
    private val viewportWidth: Int,
    private val viewportHeight: Int,
) : BaseGlShaderProgram(
    /* useHighPrecisionColorComponents = */ false,
    /* texturePoolCapacity = */ 1,
) {
    private val program = try {
        GlProgram(
            VideoEnhancementShaderProvider.getShaderSource(
                context,
                "$exoEffectShaderDirectory/ewa_lanczossharp.vert",
            ),
            VideoEnhancementShaderProvider.getShaderSource(
                context,
                "$exoEffectShaderDirectory/lanczos2.frag",
            ),
        ).also {
            it.setBufferAttribute(
                "aFramePosition",
                GlUtil.getNormalizedCoordinateBounds(),
                GlUtil.HOMOGENEOUS_COORDINATE_VECTOR_SIZE,
            )
        }
    } catch (e: GlUtil.GlException) {
        throw VideoFrameProcessingException("Could not compile mobile Lanczos scale effect", e)
    }

    private var inputWidth = 0
    private var inputHeight = 0

    override fun configure(inputWidth: Int, inputHeight: Int): Size {
        this.inputWidth = inputWidth
        this.inputHeight = inputHeight
        val scale = minOf(
            viewportWidth.toDouble() / inputWidth,
            viewportHeight.toDouble() / inputHeight,
        )
        return Size(
            (inputWidth * scale).roundToInt().coerceAtLeast(1),
            (inputHeight * scale).roundToInt().coerceAtLeast(1),
        )
    }

    override fun drawFrame(inputTexId: Int, presentationTimeUs: Long) {
        try {
            program.use()
            program.setSamplerTexIdUniform("uTexSampler", inputTexId, /* texUnitIndex = */ 0)
            program.setFloatsUniform(
                "uInputSize",
                floatArrayOf(inputWidth.toFloat(), inputHeight.toFloat()),
            )
            program.bindAttributesAndUniforms()
            GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, /* first = */ 0, /* count = */ 4)
            GlUtil.checkGlError()
        } catch (e: GlUtil.GlException) {
            throw VideoFrameProcessingException(e, presentationTimeUs)
        }
    }

    override fun release() {
        try {
            program.delete()
        } catch (e: GlUtil.GlException) {
            throw VideoFrameProcessingException("Could not release mobile Lanczos scale effect", e)
        }
        super.release()
    }
}
