/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.ui.foundation.layout

import androidx.compose.material3.adaptive.WindowAdaptiveInfo
import androidx.compose.material3.adaptive.currentWindowAdaptiveInfo
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalConfiguration
import androidx.window.core.layout.WindowSizeClass

/**
 * Uses configuration dimensions for the size class and Material's folding posture calculation.
 * Configuration dimensions follow rotation and multi-window changes independently of cached
 * window metrics.
 */
@Composable
@Suppress("DEPRECATION") // Keep the compact, medium and expanded breakpoints used by the shared UI.
actual fun currentWindowAdaptiveInfo1(): WindowAdaptiveInfo {
    val configuration = LocalConfiguration.current
    val windowAdaptiveInfo = currentWindowAdaptiveInfo()
    return WindowAdaptiveInfo(
        WindowSizeClass.compute(configuration.screenWidthDp.toFloat(), configuration.screenHeightDp.toFloat()),
        windowAdaptiveInfo.windowPosture,
    )
}
