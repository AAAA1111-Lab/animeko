/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.ui.cache

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.paging.LoadState
import androidx.paging.PagingData
import androidx.paging.compose.collectAsLazyPagingItems
import kotlinx.coroutines.flow.Flow
import me.him188.ani.app.data.models.subject.SubjectCollectionInfo
import me.him188.ani.app.data.repository.subject.CollectionsFilterQuery
import me.him188.ani.app.ui.lang.Lang
import me.him188.ani.app.ui.lang.cache_filter_collection_doing
import me.him188.ani.app.ui.lang.cache_filter_collection_done
import me.him188.ani.app.ui.lang.cache_filter_collection_dropped
import me.him188.ani.app.ui.lang.cache_filter_collection_on_hold
import me.him188.ani.app.ui.lang.cache_filter_collection_wish
import me.him188.ani.app.ui.lang.cache_import_cancel
import me.him188.ani.app.ui.lang.cache_import_select_subject
import me.him188.ani.app.ui.lang.cache_import_subject_picker_all
import me.him188.ani.app.ui.lang.cache_import_subject_picker_empty
import me.him188.ani.app.ui.subject.SubjectCoverCard
import me.him188.ani.datasources.api.topic.UnifiedCollectionType
import org.jetbrains.compose.resources.stringResource

/**
 * 本地导入: 从追番 (收藏) 列表中选择要导入到的条目.
 * 支持按收藏类型过滤, 每项显示条目封面缩略图.
 * 选中后由调用方加载该剧集的剧集列表, 并继续剧集匹配流程.
 */
@Composable
internal fun ImportSubjectPickerDialog(
    pagerFactory: (CollectionsFilterQuery) -> Flow<PagingData<SubjectCollectionInfo>>,
    onDismiss: () -> Unit,
    onSelect: (SubjectCollectionInfo) -> Unit,
) {
    var selectedType by remember { mutableStateOf<UnifiedCollectionType?>(null) }
    val pagingItems = remember(selectedType) {
        pagerFactory(CollectionsFilterQuery(selectedType))
    }.collectAsLazyPagingItems()

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(Lang.cache_import_select_subject)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    FilterChip(
                        selected = selectedType == null,
                        onClick = { selectedType = null },
                        label = { Text(stringResource(Lang.cache_import_subject_picker_all)) },
                    )
                    for (type in UnifiedCollectionType.entries.filter { it != UnifiedCollectionType.NOT_COLLECTED }) {
                        FilterChip(
                            selected = selectedType == type,
                            onClick = { selectedType = type },
                            label = { Text(renderImportCollectionType(type)) },
                        )
                    }
                }

                LazyVerticalGrid(
                    GridCells.Adaptive(120.dp),
                    Modifier
                        .fillMaxWidth()
                        .height(380.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    if (pagingItems.loadState.refresh is LoadState.Loading) {
                        item(span = { GridItemSpan(maxLineSpan) }) {
                            Box(
                                Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 32.dp),
                                contentAlignment = Alignment.Center,
                            ) {
                                CircularProgressIndicator()
                            }
                        }
                    } else if (pagingItems.itemCount == 0) {
                        item(span = { GridItemSpan(maxLineSpan) }) {
                            Text(
                                stringResource(Lang.cache_import_subject_picker_empty),
                                Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 32.dp),
                                textAlign = TextAlign.Center,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                    items(
                        count = pagingItems.itemCount,
                        key = { pagingItems.peek(it)?.subjectId ?: it },
                    ) { index ->
                        val subject = pagingItems[index] ?: return@items
                        SubjectCoverCard(
                            name = subject.subjectInfo.displayName,
                            image = subject.subjectInfo.imageLarge,
                            isPlaceholder = false,
                            onClick = { onSelect(subject) },
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(Lang.cache_import_cancel)) }
        },
    )
}

@Composable
private fun renderImportCollectionType(type: UnifiedCollectionType): String = when (type) {
    UnifiedCollectionType.WISH -> stringResource(Lang.cache_filter_collection_wish)
    UnifiedCollectionType.DOING -> stringResource(Lang.cache_filter_collection_doing)
    UnifiedCollectionType.DONE -> stringResource(Lang.cache_filter_collection_done)
    UnifiedCollectionType.ON_HOLD -> stringResource(Lang.cache_filter_collection_on_hold)
    UnifiedCollectionType.DROPPED -> stringResource(Lang.cache_filter_collection_dropped)
    UnifiedCollectionType.NOT_COLLECTED -> stringResource(Lang.cache_filter_collection_doing)
}
