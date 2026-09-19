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
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.paging.LoadState
import androidx.paging.PagingData
import androidx.paging.compose.collectAsLazyPagingItems
import kotlinx.coroutines.flow.Flow
import me.him188.ani.app.data.models.subject.SubjectCollectionInfo
import me.him188.ani.app.ui.lang.Lang
import me.him188.ani.app.ui.lang.cache_import_cancel
import me.him188.ani.app.ui.lang.cache_import_select_subject
import me.him188.ani.app.ui.lang.cache_import_subject_picker_empty
import org.jetbrains.compose.resources.stringResource

/**
 * 本地导入时选择要导入到的条目 (从追番/收藏列表中选择).
 * 选中后由调用方加载该剧集的剧集列表, 并继续剧集匹配流程.
 */
@Composable
internal fun ImportSubjectPickerDialog(
    pager: Flow<PagingData<SubjectCollectionInfo>>,
    onDismiss: () -> Unit,
    onSelect: (SubjectCollectionInfo) -> Unit,
) {
    val pagingItems = pager.collectAsLazyPagingItems()
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(Lang.cache_import_select_subject)) },
        text = {
            LazyColumn(
                Modifier
                    .fillMaxWidth()
                    .heightIn(max = 420.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                if (pagingItems.loadState.refresh is LoadState.Loading) {
                    item(key = "loading") {
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
                    item(key = "empty") {
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
                    Column(
                        Modifier
                            .fillMaxWidth()
                            .clickable { onSelect(subject) }
                            .padding(horizontal = 12.dp, vertical = 10.dp),
                    ) {
                        Text(
                            subject.subjectInfo.displayName,
                            style = MaterialTheme.typography.bodyLarge,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
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
