/*
 * Copyright (C) 2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.platform

import android.view.View
import android.view.WindowInsets
import androidx.core.graphics.Insets
import androidx.core.view.WindowInsetsCompat
import androidx.test.platform.app.InstrumentationRegistry
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue

class PlatformWindowInsetsTest {
    @Test
    fun nativeInsetsHandlingIsPreservedThroughFullscreenAndRotation() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.runOnMainSync {
            val window = PlatformWindow(DeviceOrientation.PORTRAIT, false)
            val receivedInsets = mutableListOf<WindowInsets>()
            var nativeResult: WindowInsets? = null
            val view = object : View(instrumentation.targetContext) {
                @Suppress("DEPRECATION")
                override fun onApplyWindowInsets(insets: WindowInsets): WindowInsets {
                    receivedInsets.add(insets)
                    return insets.consumeSystemWindowInsets().also { nativeResult = it }
                }
            }

            val portrait = insets(statusBarHeight = 24, navigationBar = Insets.of(0, 0, 0, 48))
            assertSame(window.applyWindowInsets(view, portrait), nativeResult)
            assertSame(portrait, receivedInsets.last())
            assertFalse(window.isUndecoratedFullscreen)

            val fullscreen = insets(statusBarHeight = 0, navigationBar = Insets.NONE)
            assertSame(window.applyWindowInsets(view, fullscreen), nativeResult)
            assertSame(fullscreen, receivedInsets.last())
            assertTrue(window.isUndecoratedFullscreen)

            val landscape = insets(statusBarHeight = 24, navigationBar = Insets.of(48, 0, 0, 0))
            assertSame(window.applyWindowInsets(view, landscape), nativeResult)
            assertSame(landscape, receivedInsets.last())
            assertFalse(window.isUndecoratedFullscreen)

            assertSame(window.applyWindowInsets(view, portrait), nativeResult)
            assertSame(portrait, receivedInsets.last())
            assertFalse(window.isUndecoratedFullscreen)
        }
    }

    private fun insets(statusBarHeight: Int, navigationBar: Insets): WindowInsets =
        WindowInsetsCompat.Builder()
            .setInsets(WindowInsetsCompat.Type.statusBars(), Insets.of(0, statusBarHeight, 0, 0))
            .setInsets(WindowInsetsCompat.Type.navigationBars(), navigationBar)
            .setVisible(WindowInsetsCompat.Type.statusBars(), statusBarHeight > 0)
            .setVisible(WindowInsetsCompat.Type.navigationBars(), navigationBar != Insets.NONE)
            .build()
            .toWindowInsets()!!
}
