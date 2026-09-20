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

/**
 * A lightweight Contrast Adaptive Sharpening (AMD FidelityFX CAS) effect.
 *
 * Single-pass sharpening suitable for mobile GPUs (e.g. Snapdragon 845 / Adreno 630).
 */
internal object ContrastAdaptiveSharpenEffect : GlEffect {
    override fun toGlShaderProgram(context: Context, useHdr: Boolean): GlShaderProgram =
        ContrastAdaptiveSharpenShaderProgram(context)
}

private class ContrastAdaptiveSharpenShaderProgram(
    context: Context,
) : BaseGlShaderProgram(
    /* useHighPrecisionColorComponents = */ false,
    /* texturePoolCapacity = */ 1,
) {
    private val shaderSources = CasShaderSources(context)

    private val program = try {
        GlProgram(shaderSources.vertexShader, shaderSources.fragmentShader).also {
            it.setBufferAttribute(
                "aFramePosition",
                GlUtil.getNormalizedCoordinateBounds(),
                GlUtil.HOMOGENEOUS_COORDINATE_VECTOR_SIZE,
            )
        }
    } catch (e: GlUtil.GlException) {
        throw VideoFrameProcessingException("Could not compile CAS sharpening effect", e)
    }

    private var inputWidth = 0
    private var inputHeight = 0

    override fun configure(inputWidth: Int, inputHeight: Int): Size {
        this.inputWidth = inputWidth
        this.inputHeight = inputHeight
        return Size(inputWidth, inputHeight)
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
            throw VideoFrameProcessingException("Could not release CAS sharpening effect", e)
        }
        super.release()
    }
}

private class CasShaderSources(context: Context) {
    val vertexShader = VideoEnhancementShaderProvider.getShaderSource(
        context,
        "$exoEffectShaderDirectory/ewa_lanczossharp.vert",
    )
    val fragmentShader = VideoEnhancementShaderProvider.getShaderSource(
        context,
        "$exoEffectShaderDirectory/cas.frag",
    )
}
