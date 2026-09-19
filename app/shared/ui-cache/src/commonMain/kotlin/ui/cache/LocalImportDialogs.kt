/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.ui.cache

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AutoFixHigh
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.VideoLibrary
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import me.him188.ani.app.data.models.subject.SubjectInfo
import me.him188.ani.app.ui.foundation.AsyncImage
import me.him188.ani.app.ui.lang.Lang
import me.him188.ani.app.ui.lang.cache_import_auto_no_result
import me.him188.ani.app.ui.lang.cache_import_cancel
import me.him188.ani.app.ui.lang.cache_import_mode_auto
import me.him188.ani.app.ui.lang.cache_import_mode_auto_desc
import me.him188.ani.app.ui.lang.cache_import_mode_collection
import me.him188.ani.app.ui.lang.cache_import_mode_collection_desc
import me.him188.ani.app.ui.lang.cache_import_mode_title
import me.him188.ani.app.ui.lang.cache_import_search_hint
import me.him188.ani.app.ui.lang.cache_import_select_subject
import org.jetbrains.compose.resources.stringResource

/**
 * 本地导入的模式选择: 自动剧集匹配 (按文件名搜索条目) / 从追番列表手动选择.
 */
@Composable
internal fun ImportModeChooserDialog(
    fileCount: Int,
    onDismiss: () -> Unit,
    onAutoMatch: () -> Unit,
    onFromCollection: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(Lang.cache_import_mode_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                ImportModeOption(
                    icon = Icons.Rounded.AutoFixHigh,
                    title = stringResource(Lang.cache_import_mode_auto),
                    description = stringResource(Lang.cache_import_mode_auto_desc),
                    onClick = onAutoMatch,
                )
                ImportModeOption(
                    icon = Icons.Rounded.VideoLibrary,
                    title = stringResource(Lang.cache_import_mode_collection),
                    description = stringResource(Lang.cache_import_mode_collection_desc),
                    onClick = onFromCollection,
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(Lang.cache_import_cancel)) }
        },
    )
}

@Composable
private fun ImportModeOption(
    icon: ImageVector,
    title: String,
    description: String,
    onClick: () -> Unit,
) {
    Surface(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surfaceContainerHighest,
    ) {
        Row(
            Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Icon(icon, contentDescription = null)
            Column {
                Text(title, style = MaterialTheme.typography.titleMedium)
                Text(
                    description,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/**
 * 本地导入 (自动剧集匹配): 从文件名猜测的标题搜索条目, 也可手动修改关键词重新搜索.
 * 选中候选条目后由调用方加载剧集并继续剧集匹配; 搜索无结果时可退回从追番列表手动选择.
 */
@Composable
internal fun ImportSubjectSearchDialog(
    initialKeywords: String?,
    onSearch: suspend (String) -> List<SubjectInfo>,
    onDismiss: () -> Unit,
    onSelect: (SubjectInfo) -> Unit,
    onManualFallback: () -> Unit,
) {
    var query by remember { mutableStateOf(initialKeywords ?: "") }
    var searchResults by remember { mutableStateOf<List<SubjectInfo>?>(null) } // null = 搜索中
    val scope = rememberCoroutineScope()

    fun startSearch(keywords: String) {
        if (keywords.isBlank()) return
        query = keywords
        searchResults = null
        scope.launch {
            searchResults = runCatching { onSearch(keywords.trim()) }.getOrDefault(emptyList())
        }
    }

    LaunchedEffect(initialKeywords) {
        initialKeywords?.takeIf { it.isNotBlank() }?.let { startSearch(it) }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(Lang.cache_import_select_subject)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    singleLine = true,
                    label = { Text(stringResource(Lang.cache_import_search_hint)) },
                    trailingIcon = {
                        IconButton(onClick = { startSearch(query) }) {
                            Icon(Icons.Rounded.Search, null)
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                )

                Box(
                    Modifier
                        .fillMaxWidth()
                        .height(360.dp),
                ) {
                    val results = searchResults
                    when {
                        results == null -> Box(
                            Modifier
                                .fillMaxWidth()
                                .padding(vertical = 32.dp),
                            contentAlignment = Alignment.Center,
                        ) {
                            CircularProgressIndicator()
                        }

                        results.isEmpty() -> Column(
                            Modifier
                                .fillMaxWidth()
                                .padding(vertical = 16.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                        ) {
                            Text(
                                stringResource(Lang.cache_import_auto_no_result),
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            TextButton(onClick = onManualFallback) {
                                Text(stringResource(Lang.cache_import_mode_collection))
                            }
                        }

                        else -> Column(
                            Modifier
                                .verticalScroll(rememberScrollState()),
                            verticalArrangement = Arrangement.spacedBy(2.dp),
                        ) {
                            results.forEach { subject ->
                                ImportSubjectCardRow(
                                    displayName = subject.displayName,
                                    imageUrl = subject.imageLarge,
                                    onClick = { onSelect(subject) },
                                )
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(Lang.cache_import_cancel)) }
        },
    )
}
