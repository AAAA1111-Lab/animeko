/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.ui.settings.tabs.media

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ContentPaste
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalClipboard
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.flow.Flow
import me.him188.ani.app.data.models.preference.DanmakuCacheStrategy
import me.him188.ani.app.data.models.preference.MediaCacheSettings
import me.him188.ani.app.platform.PermissionManager
import me.him188.ani.app.ui.foundation.getClipEntryText
import me.him188.ani.app.ui.foundation.rememberAsyncHandler
import me.him188.ani.app.ui.foundation.setClipEntryText
import me.him188.ani.app.ui.foundation.widgets.LocalToaster
import me.him188.ani.app.ui.lang.Lang
import me.him188.ani.app.ui.lang.settings_danmaku_cancel
import me.him188.ani.app.ui.lang.settings_danmaku_confirm
import me.him188.ani.app.ui.lang.settings_mediasource_rss_copied_to_clipboard
import me.him188.ani.app.ui.lang.settings_storage_backup_op_backup_description
import me.him188.ani.app.ui.lang.settings_storage_backup_op_backup_error
import me.him188.ani.app.ui.lang.settings_storage_backup_op_backup_title
import me.him188.ani.app.ui.lang.settings_storage_backup_op_restore
import me.him188.ani.app.ui.lang.settings_storage_backup_op_restore_description
import me.him188.ani.app.ui.lang.settings_storage_backup_op_restore_error
import me.him188.ani.app.ui.lang.settings_storage_backup_op_restore_succees
import me.him188.ani.app.ui.lang.settings_storage_backup_op_restore_warning
import me.him188.ani.app.ui.lang.settings_storage_backup_title
import me.him188.ani.app.ui.lang.settings_storage_danmaku_cache_clear_confirm_text
import me.him188.ani.app.ui.lang.settings_storage_danmaku_cache_clear_confirm_title
import me.him188.ani.app.ui.lang.settings_storage_danmaku_cache_clear_description
import me.him188.ani.app.ui.lang.settings_storage_danmaku_cache_clear_done
import me.him188.ani.app.ui.lang.settings_storage_danmaku_cache_clear_title
import me.him188.ani.app.ui.lang.settings_storage_danmaku_cache_strategy_description_cache_on_collection_doing_media_play
import me.him188.ani.app.ui.lang.settings_storage_danmaku_cache_strategy_description_cache_on_media_cache
import me.him188.ani.app.ui.lang.settings_storage_danmaku_cache_strategy_description_do_not_cache
import me.him188.ani.app.ui.lang.settings_storage_danmaku_cache_strategy_title
import me.him188.ani.app.ui.lang.subject_episode_danmaku_cache_cached
import me.him188.ani.app.ui.settings.framework.SettingsState
import me.him188.ani.app.ui.settings.framework.components.DropdownItem
import me.him188.ani.app.ui.settings.framework.components.SettingsScope
import me.him188.ani.app.ui.settings.framework.components.TextItem
import org.jetbrains.compose.resources.getString
import org.jetbrains.compose.resources.stringResource

@Stable
class CacheDirectoryGroupState(
    val mediaCacheSettingsState: SettingsState<MediaCacheSettings>,
    val permissionManager: PermissionManager,
    val onGetBackupData: suspend () -> String,
    val onRestoreSettings: suspend (String) -> Boolean,
    /**
     * 本地已缓存的弹幕总条数. `null` 表示还在加载.
     */
    val cachedDanmakuCountFlow: Flow<Int>,
    /**
     * 清空全部弹幕缓存, 返回被清除的条数.
     */
    val onClearDanmakuCache: suspend () -> Int,
)

@Composable
fun SettingsScope.BackupSettings(state: CacheDirectoryGroupState) {
    var showRestoreDialog by remember { mutableStateOf(false) }

    val scope = rememberAsyncHandler()
    val clipboard = LocalClipboard.current
    val toaster = LocalToaster.current

    Group({ Text(stringResource(Lang.settings_storage_backup_title)) }) {
        val backupErrorText = stringResource(Lang.settings_storage_backup_op_backup_error)

        TextItem(
            onClick = {
                scope.launch {
                    val data = state.onGetBackupData()
                    clipboard.setClipEntryText(data)
                    toaster.toast(getString(Lang.settings_mediasource_rss_copied_to_clipboard))
                }
            },
            title = { Text(stringResource(Lang.settings_storage_backup_op_backup_title)) },
            description = { Text(stringResource(Lang.settings_storage_backup_op_backup_description)) },
        )
        TextItem(
            onClick = { showRestoreDialog = true },
            title = { Text(stringResource(Lang.settings_storage_backup_op_restore)) },
            description = { Text(stringResource(Lang.settings_storage_backup_op_restore_description)) },
        )
    }

    if (showRestoreDialog) {
        val restoreSuccess = stringResource(Lang.settings_storage_backup_op_restore_succees)
        val restoreFailed = stringResource(Lang.settings_storage_backup_op_restore_error)

        AlertDialog(
            { showRestoreDialog = false },
            icon = { Icon(Icons.Rounded.ContentPaste, null, tint = MaterialTheme.colorScheme.error) },
            title = { Text(stringResource(Lang.settings_storage_backup_op_restore)) },
            text = { Text(stringResource(Lang.settings_storage_backup_op_restore_warning)) },
            confirmButton = {
                TextButton(
                    {
                        scope.launch {
                            val clipboardText = clipboard.getClipEntryText()
                                ?.takeIf { it.isNotBlank() && it.isNotEmpty() }
                            val result = clipboardText?.let { state.onRestoreSettings(it) } == true

                            toaster.toast(if (result) restoreSuccess else restoreFailed)
                            showRestoreDialog = false
                        }
                    },
                ) {
                    Text(stringResource(Lang.settings_danmaku_confirm), color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton({ showRestoreDialog = false }) {
                    Text(stringResource(Lang.settings_danmaku_cancel))
                }
            },
        )
    }
}

@Composable
fun SettingsScope.DanmakuCacheSettings(state: CacheDirectoryGroupState) {
    val mediaCacheSettings by state.mediaCacheSettingsState
    val tasker = rememberAsyncHandler()
    val toaster = LocalToaster.current
    val cachedDanmakuCount by state.cachedDanmakuCountFlow.collectAsStateWithLifecycle<Int?>(initialValue = null)
    var showClearDanmakuCacheDialog by remember { mutableStateOf(false) }

    DropdownItem(
        title = { Text(stringResource(Lang.settings_storage_danmaku_cache_strategy_title)) },
        selected = { mediaCacheSettings.danmakuCacheStrategy },
        values = { DanmakuCacheStrategy.entries },
        description = {
            Text(
                when (mediaCacheSettings.danmakuCacheStrategy) {
                    DanmakuCacheStrategy.DON_NOT_CACHE ->
                        stringResource(Lang.settings_storage_danmaku_cache_strategy_description_do_not_cache)

                    DanmakuCacheStrategy.CACHE_ON_COLLECTION_DOING_MEDIA_PLAY ->
                        stringResource(Lang.settings_storage_danmaku_cache_strategy_description_cache_on_collection_doing_media_play)

                    DanmakuCacheStrategy.CACHE_ON_MEDIA_CACHE ->
                        stringResource(Lang.settings_storage_danmaku_cache_strategy_description_cache_on_media_cache)
                },
            )
        },
        itemText = { strategy ->
            Text(
                when (strategy) {
                    DanmakuCacheStrategy.DON_NOT_CACHE -> "NONE"
                    DanmakuCacheStrategy.CACHE_ON_MEDIA_CACHE -> "MEDIA"
                    DanmakuCacheStrategy.CACHE_ON_COLLECTION_DOING_MEDIA_PLAY -> "COLLECT"
                },
            )
        },
        onSelect = { newStrategy ->
            tasker.launch {
                state.mediaCacheSettingsState.updateSuspended(
                    mediaCacheSettings.copy(danmakuCacheStrategy = newStrategy),
                )
            }
        },
    )

    val clearTitleText = stringResource(Lang.settings_storage_danmaku_cache_clear_title)
    val clearDescriptionText = stringResource(Lang.settings_storage_danmaku_cache_clear_description)
    TextItem(
        title = { Text(clearTitleText) },
        description = {
            val count = cachedDanmakuCount
            if (count != null && count > 0) {
                Text(stringResource(Lang.subject_episode_danmaku_cache_cached, count))
            } else {
                Text(clearDescriptionText)
            }
        },
        onClick = { showClearDanmakuCacheDialog = true },
        onClickEnabled = (cachedDanmakuCount ?: 0) > 0,
    )

    if (showClearDanmakuCacheDialog) {
        AlertDialog(
            onDismissRequest = { showClearDanmakuCacheDialog = false },
            icon = { Icon(Icons.Rounded.Delete, null, tint = MaterialTheme.colorScheme.error) },
            title = { Text(stringResource(Lang.settings_storage_danmaku_cache_clear_confirm_title)) },
            text = { Text(stringResource(Lang.settings_storage_danmaku_cache_clear_confirm_text)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        showClearDanmakuCacheDialog = false
                        tasker.launch {
                            val removed = state.onClearDanmakuCache()
                            if (removed > 0) {
                                toaster.toast(getString(Lang.settings_storage_danmaku_cache_clear_done, removed))
                            }
                        }
                    },
                ) {
                    Text(stringResource(Lang.settings_danmaku_confirm), color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton({ showClearDanmakuCacheDialog = false }) {
                    Text(stringResource(Lang.settings_danmaku_cancel))
                }
            },
        )
    }
}

@Composable
expect fun SettingsScope.CacheDirectoryGroup(
    state: CacheDirectoryGroupState,
)
