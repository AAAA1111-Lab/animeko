/*
 * Copyright (C) 2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.ui.adaptive.navigation

import android.content.res.Configuration
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.width
import androidx.compose.material3.adaptive.navigationsuite.NavigationSuiteType
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.platform.WindowInfo
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import me.him188.ani.app.data.models.preference.ThemeSettings
import me.him188.ani.app.ui.foundation.layout.currentWindowAdaptiveInfo1
import me.him188.ani.app.ui.foundation.theme.LocalThemeSettings
import me.him188.ani.app.ui.framework.AniComposeUiTest
import me.him188.ani.app.ui.framework.runAniComposeUiTest
import kotlin.test.Test
import kotlin.test.assertEquals

class AniNavigationRotationTest {
    @Test
    fun navigationReturnsToBottomWhen4KWindowMetricsKeepLandscapeSize() = verifyNavigationRotation(800)

    @Test
    fun navigationReturnsToBottomWhen4KWindowMetricsKeepLandscapeSizeAt840Dpi() = verifyNavigationRotation(840)

    private fun verifyNavigationRotation(testDensityDpi: Int) = runAniComposeUiTest {
        var landscape by mutableStateOf(false)
        var metricsSize by mutableStateOf(IntSize(2160, 3840))
        val testDensity = Density(testDensityDpi / 160f)
        val portraitWidthDp = (2160 / testDensity.density).toInt()
        val portraitHeightDp = (3840 / testDensity.density).toInt()

        setContent {
            val hostWindow = LocalWindowInfo.current
            val window = remember(hostWindow) {
                object : WindowInfo by hostWindow {
                    override val containerSize: IntSize get() = metricsSize
                }
            }
            val configuration = Configuration(LocalConfiguration.current).apply {
                orientation = if (landscape) Configuration.ORIENTATION_LANDSCAPE else Configuration.ORIENTATION_PORTRAIT
                screenWidthDp = if (landscape) portraitHeightDp else portraitWidthDp
                screenHeightDp = if (landscape) portraitWidthDp else portraitHeightDp
                densityDpi = testDensityDpi
            }
            CompositionLocalProvider(
                LocalConfiguration provides configuration,
                LocalDensity provides testDensity,
                LocalWindowInfo provides window,
                LocalThemeSettings provides ThemeSettings.Default,
            ) {
                AniNavigationSuiteLayout(
                    modifier = Modifier.requiredSize(configuration.screenWidthDp.dp, configuration.screenHeightDp.dp)
                        .testTag("scaffold"),
                    navigationSuite = {
                        val layoutType = AniNavigationSuiteDefaults.calculateLayoutType(
                            currentWindowAdaptiveInfo1(),
                        )
                        val navigationSize = if (layoutType == NavigationSuiteType.NavigationBar) {
                            Modifier.fillMaxWidth().height(80.dp)
                        } else {
                            Modifier.width(80.dp).fillMaxHeight()
                        }
                        Box(
                            navigationSize.testTag("navigation").clickable {
                                landscape = !landscape
                                if (landscape) {
                                    metricsSize = IntSize(3840, 2160)
                                }
                            },
                        )
                    },
                ) {
                    Box(Modifier.fillMaxSize().testTag("content"))
                }
            }
        }

        assertNavigationBounds(atBottom = true)
        repeat(2) {
            onNodeWithTag("navigation").performSemanticsAction(SemanticsActions.OnClick) { it() }
            assertNavigationBounds(atBottom = false)
            onNodeWithTag("navigation").performSemanticsAction(SemanticsActions.OnClick) { it() }
            assertNavigationBounds(atBottom = true)
        }
    }

    private fun AniComposeUiTest.assertNavigationBounds(atBottom: Boolean) {
        val scaffold = onNodeWithTag("scaffold", useUnmergedTree = true).fetchSemanticsNode()
        val navigation = onNodeWithTag("navigation", useUnmergedTree = true).fetchSemanticsNode()
        val content = onNodeWithTag("content", useUnmergedTree = true).fetchSemanticsNode()
        val navigationPosition = navigation.positionInRoot - scaffold.positionInRoot
        val contentPosition = content.positionInRoot - scaffold.positionInRoot

        assertEquals(0f, navigationPosition.x)
        if (atBottom) {
            assertEquals(scaffold.size.height - navigation.size.height.toFloat(), navigationPosition.y)
            assertEquals(scaffold.size.width, navigation.size.width)
            assertEquals(0f, contentPosition.x)
            assertEquals(scaffold.size.width, content.size.width)
            assertEquals(scaffold.size.height - navigation.size.height, content.size.height)
        } else {
            assertEquals(0f, navigationPosition.y)
            assertEquals(scaffold.size.height, navigation.size.height)
            assertEquals(navigation.size.width.toFloat(), contentPosition.x)
            assertEquals(scaffold.size.width - navigation.size.width, content.size.width)
            assertEquals(scaffold.size.height, content.size.height)
        }
        assertEquals(0f, contentPosition.y)
    }
}
