/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.ui.subject.episode.details.components

import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import me.him188.ani.app.ui.foundation.widgets.SelectableDropdownMenuItem
import me.him188.ani.app.ui.lang.Lang
import me.him188.ani.app.ui.lang.video_player_fast
import me.him188.ani.app.ui.lang.video_player_off
import me.him188.ani.app.ui.lang.video_player_performance
import me.him188.ani.app.ui.lang.video_player_quality
import me.him188.ani.app.ui.lang.video_player_video_enhancement
import me.him188.ani.app.videoplayer.videoenhancement.VideoEnhancementController
import me.him188.ani.app.videoplayer.videoenhancement.VideoEnhancementMode
import org.jetbrains.compose.resources.stringResource

@Composable
fun VideoEnhancementDropdown(
    videoEnhancement: VideoEnhancementController,
    showDropdown: Boolean,
    onDismissRequest: () -> Unit,
) {
    val mode by videoEnhancement.mode.collectAsState()
    val title = stringResource(Lang.video_player_video_enhancement)

    DropdownMenu(
        expanded = showDropdown,
        onDismissRequest = onDismissRequest,
    ) {
        // The heading is a [DropdownMenuItem] with no action rather than a bare [Text]: a menu item
        // brings its own content padding and minimum height, so the heading lines up with the
        // labels below and its row is exactly as tall as theirs. A [Text] row would be sized by its
        // own line box and come out short.
        DropdownMenuItem(
            text = { Text(title, style = MaterialTheme.typography.titleSmall) },
            onClick = {},
            enabled = false,
            // `enabled = false` is only used to make the item non-interactive; keep the heading
            // fully opaque instead of inheriting the disabled 38% alpha.
            colors = MenuDefaults.itemColors(
                disabledTextColor = MaterialTheme.colorScheme.onSurface,
            ),
            interactionSource = null,
        )
        listOf(
            VideoEnhancementMode.QUALITY,
            VideoEnhancementMode.PERFORMANCE,
            VideoEnhancementMode.FAST,
            VideoEnhancementMode.OFF,
        ).forEach { item ->
            SelectableDropdownMenuItem(
                selected = item == mode,
                text = {
                    Text(
                        when (item) {
                            VideoEnhancementMode.OFF -> stringResource(Lang.video_player_off)
                            VideoEnhancementMode.FAST -> stringResource(Lang.video_player_fast)
                            VideoEnhancementMode.PERFORMANCE -> stringResource(Lang.video_player_performance)
                            VideoEnhancementMode.QUALITY -> stringResource(Lang.video_player_quality)
                        },
                    )
                },
                onClick = {
                    videoEnhancement.setMode(item)
                    onDismissRequest()
                },
            )
        }
    }
}
