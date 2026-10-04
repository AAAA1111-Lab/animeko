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
 * A single-pass radial EWA approximation of mpv's `ewa_lanczossharp` presentation chain.
 *
 * It uses mpv's Jinc radius and sharp blur, sigmoid upscaling, and 0.7 anti-ringing while
 * avoiding a second full-size intermediate texture on mobile GPUs.
 *
 * 视口尺寸在构图时传入; 输入(视频)尺寸由 Media3 在首帧前的 [configure][BaseGlShaderProgram.configure]
 * 给出, 因此图层不需要控制器等待视频元数据. 是否真的启用 lanczos 也在 configure 里判定:
 * 只有在"确实需要放大"且放大后的输出不超过 [MAX_ENHANCED_OUTPUT_PIXELS] 时才使用它
 * (4K 屏上看 1080p, 输出约 830 万像素, 会直接跳过, 交给平台的硬件缩放完成剩余放大,
 * 否则中端 GPU 例如骁龙 845 会持续掉帧); 其余情况走廉价的纹理拷贝直通,
 * 避免按全分辨率跑每像素约 8x8 的邻域采样.
 */
internal class DesktopStyleLanczosSharpEffect(
    private val viewportWidth: Int,
    private val viewportHeight: Int,
) : GlEffect {
    override fun toGlShaderProgram(context: Context, useHdr: Boolean): GlShaderProgram =
        DesktopStyleLanczosSharpShaderProgram(context, viewportWidth, viewportHeight)
}

private class DesktopStyleLanczosSharpShaderProgram(
    context: Context,
    private val viewportWidth: Int,
    private val viewportHeight: Int,
) : BaseGlShaderProgram(
    // 8-bit components: this pass writes the final picture, which the platform composites into an
    // 8-bit surface anyway. Keeping RGBA16F here additionally costs a full extra read+write of the
    // output at viewport size (about 265 MB/frame at 4K) for no visible benefit.
    /* useHighPrecisionColorComponents = */ false,
    /* texturePoolCapacity = */ 2,
) {
    private val shaderSources = LanczosSharpShaderSources(context)

    /** 不满足 lanczos 条件时的直通拷贝, 避免为永远用不到的 8x8 采样链路付出编译与运行成本. */
    private val copyProgram = try {
        GlProgram(shaderSources.vertexShader, COPY_FRAGMENT_SHADER).also {
            it.setBufferAttribute(
                "aFramePosition",
                GlUtil.getNormalizedCoordinateBounds(),
                GlUtil.HOMOGENEOUS_COORDINATE_VECTOR_SIZE,
            )
        }
    } catch (e: GlUtil.GlException) {
        throw VideoFrameProcessingException("Could not compile lanczos bypass copy effect", e)
    }

    private val lanczosProgram: Lazy<GlProgram> = lazy {
        try {
            GlProgram(shaderSources.vertexShader, shaderSources.fragmentShader).also {
                it.setBufferAttribute(
                    "aFramePosition",
                    GlUtil.getNormalizedCoordinateBounds(),
                    GlUtil.HOMOGENEOUS_COORDINATE_VECTOR_SIZE,
                )
            }
        } catch (e: GlUtil.GlException) {
            throw VideoFrameProcessingException("Could not compile desktop-style Lanczos sharp effect", e)
        }
    }

    private var inputWidth = 0
    private var inputHeight = 0
    private var bypass = true

    override fun configure(inputWidth: Int, inputHeight: Int): Size {
        this.inputWidth = inputWidth
        this.inputHeight = inputHeight
        val scale = minOf(
            viewportWidth.toDouble() / inputWidth,
            viewportHeight.toDouble() / inputHeight,
        )
        val outputWidth = (inputWidth * scale).roundToInt().coerceAtLeast(1)
        val outputHeight = (inputHeight * scale).roundToInt().coerceAtLeast(1)
        bypass = scale <= 1.0 || outputWidth.toLong() * outputHeight > MAX_ENHANCED_OUTPUT_PIXELS
        return if (bypass) Size(inputWidth, inputHeight) else Size(outputWidth, outputHeight)
    }

    override fun drawFrame(inputTexId: Int, presentationTimeUs: Long) {
        val program = if (bypass) copyProgram else lanczosProgram.value
        try {
            program.use()
            program.setSamplerTexIdUniform("uTexSampler", inputTexId, /* texUnitIndex = */ 0)
            if (!bypass) {
                program.setFloatsUniform(
                    "uInputSize",
                    floatArrayOf(inputWidth.toFloat(), inputHeight.toFloat()),
                )
            }
            program.bindAttributesAndUniforms()
            GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, /* first = */ 0, /* count = */ 4)
            GlUtil.checkGlError()
        } catch (e: GlUtil.GlException) {
            throw VideoFrameProcessingException(e, presentationTimeUs)
        }
    }

    override fun release() {
        try {
            copyProgram.delete()
            if (lanczosProgram.isInitialized()) lanczosProgram.value.delete()
        } catch (e: GlUtil.GlException) {
            throw VideoFrameProcessingException(e)
        }
        super.release()
    }

    private companion object {
        /** 1080p. 超过这个输出规模时, 增强链改由平台硬件缩放收尾. */
        private const val MAX_ENHANCED_OUTPUT_PIXELS = 1920L * 1080L

        private val COPY_FRAGMENT_SHADER = """
            #version 100
            precision mediump float;
            varying vec2 vTexSamplingCoord;
            uniform sampler2D uTexSampler;
            void main() {
                gl_FragColor = texture2D(uTexSampler, vTexSamplingCoord);
            }
        """.trimIndent()
    }
}


private class LanczosSharpShaderSources(context: Context) {
    val vertexShader = VideoEnhancementShaderProvider.getShaderSource(
        context,
        "$exoEffectShaderDirectory/ewa_lanczossharp.vert",
    )
    val fragmentShader = VideoEnhancementShaderProvider.getShaderSource(
        context,
        "$exoEffectShaderDirectory/ewa_lanczossharp.frag",
    )
}
