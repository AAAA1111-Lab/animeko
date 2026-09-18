/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.ui.cache.subject

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ArrowDropDown
import androidx.compose.material.icons.rounded.AutoFixHigh
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.SwapHoriz
import androidx.compose.material.icons.rounded.VideoFile
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.vinceglb.filekit.PlatformFile
import me.him188.ani.app.data.models.episode.EpisodeInfo
import me.him188.ani.app.data.models.episode.displayName
import me.him188.ani.app.domain.media.parser.EpisodeFilenameParser
import me.him188.ani.app.ui.lang.Lang
import me.him188.ani.app.ui.lang.cache_import_cancel
import me.him188.ani.app.ui.lang.cache_import_confirm
import me.him188.ani.app.ui.lang.cache_import_dialog_title
import me.him188.ani.app.ui.lang.cache_import_empty_hint
import me.him188.ani.app.ui.lang.cache_import_ignore
import me.him188.ani.app.ui.lang.cache_import_offset
import me.him188.ani.app.ui.lang.cache_import_offset_apply
import me.him188.ani.app.ui.lang.cache_import_offset_hint
import me.him188.ani.app.ui.lang.cache_import_offset_title
import me.him188.ani.app.ui.lang.cache_import_parsed_as
import me.him188.ani.app.ui.lang.cache_import_rematch
import me.him188.ani.app.ui.lang.cache_import_safe_hint
import me.him188.ani.app.ui.lang.cache_import_unrecognized
import me.him188.ani.datasources.api.topic.FileSize.Companion.bytes
import org.jetbrains.compose.resources.stringResource

class LocalImportCandidate(
    val file: PlatformFile,
    val filePath: String,
    val filename: String,
    val fileSize: Long,
    val detectedSeason: Int?,
    val detectedEpisode: Double?,
    initialTargetEpisode: EpisodeInfo?,
) {
    var targetEpisode by mutableStateOf(initialTargetEpisode)
}

@Composable
fun LocalMediaImportDialog(
    files: List<PlatformFile>,
    episodes: List<EpisodeInfo>,
    subjectTitle: String?,
    onDismissRequest: () -> Unit,
    onConfirm: (List<LocalImportCandidate>) -> Unit,
    modifier: Modifier = Modifier,
) {
    fun matchEpisode(parsedEp: Double?): EpisodeInfo? {
        if (parsedEp == null) return null
        return episodes.firstOrNull { ep ->
            val sortNum = ep.sort.number?.toDouble()
            val epNum = ep.ep?.number?.toDouble()
            sortNum == parsedEp || epNum == parsedEp
        }
    }

    val candidates = remember(files, episodes) {
        files.map { file ->
            val parsed = EpisodeFilenameParser.parse(file.name)
            val path = file.toString().ifBlank { file.name }
            val size = try { file.size() } catch (e: Exception) { 0L }
            val matched = matchEpisode(parsed.episode)
            LocalImportCandidate(
                file = file,
                filePath = path,
                filename = file.name,
                fileSize = size,
                detectedSeason = parsed.season,
                detectedEpisode = parsed.episode,
                initialTargetEpisode = matched,
            )
        }
    }

    var showOffsetDialog by remember { mutableStateOf(false) }

    val importableCount = candidates.count { it.targetEpisode != null }

    AlertDialog(
        onDismissRequest = onDismissRequest,
        title = {
            Column {
                Text(
                    text = stringResource(Lang.cache_import_dialog_title),
                    style = MaterialTheme.typography.titleLarge,
                )
                if (!subjectTitle.isNullOrBlank()) {
                    Spacer(Modifier.height(2.dp))
                    Text(
                        text = subjectTitle,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        },
        icon = { Icon(Icons.Rounded.VideoFile, null) },
        confirmButton = {
            Button(
                onClick = {
                    onConfirm(candidates.filter { it.targetEpisode != null })
                },
                enabled = importableCount > 0,
            ) {
                Text(stringResource(Lang.cache_import_confirm, importableCount))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismissRequest) {
                Text(stringResource(Lang.cache_import_cancel))
            }
        },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                // Safe Hint banner
                Surface(
                    color = MaterialTheme.colorScheme.surfaceVariant,
                    shape = RoundedCornerShape(8.dp),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Row(
                        modifier = Modifier.padding(8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(
                            Icons.Rounded.Info,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(18.dp),
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(
                            text = stringResource(Lang.cache_import_safe_hint),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }

                // Batch Tool bar
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    OutlinedButton(
                        onClick = { showOffsetDialog = true },
                        modifier = Modifier.weight(1f),
                    ) {
                        Icon(Icons.Rounded.SwapHoriz, null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(4.dp))
                        Text(stringResource(Lang.cache_import_offset), style = MaterialTheme.typography.labelMedium)
                    }

                    OutlinedButton(
                        onClick = {
                            candidates.forEach { candidate ->
                                candidate.targetEpisode = matchEpisode(candidate.detectedEpisode)
                            }
                        },
                        modifier = Modifier.weight(1f),
                    ) {
                        Icon(Icons.Rounded.AutoFixHigh, null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(4.dp))
                        Text(stringResource(Lang.cache_import_rematch), style = MaterialTheme.typography.labelMedium)
                    }
                }

                if (candidates.isEmpty()) {
                    Box(
                        modifier = Modifier.fillMaxWidth().padding(vertical = 24.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            text = stringResource(Lang.cache_import_empty_hint),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.outline,
                        )
                    }
                } else {
                    LazyColumn(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = 380.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        items(candidates, key = { it.filePath }) { candidate ->
                            CandidateItemCard(
                                candidate = candidate,
                                allEpisodes = episodes,
                            )
                        }
                    }
                }
            }
        },
        modifier = modifier,
    )

    if (showOffsetDialog) {
        BatchOffsetDialog(
            onDismissRequest = { showOffsetDialog = false },
            onApplyOffset = { offset ->
                candidates.forEach { candidate ->
                    val currentNum = candidate.targetEpisode?.sort?.number?.toInt()
                        ?: candidate.detectedEpisode?.toInt()
                    if (currentNum != null) {
                        val targetNum = currentNum + offset
                        val newEp = episodes.firstOrNull {
                            it.sort.number?.toInt() == targetNum || it.ep?.number?.toInt() == targetNum
                        }
                        if (newEp != null) {
                            candidate.targetEpisode = newEp
                        }
                    }
                }
                showOffsetDialog = false
            },
        )
    }
}

@Composable
private fun CandidateItemCard(
    candidate: LocalImportCandidate,
    allEpisodes: List<EpisodeInfo>,
    modifier: Modifier = Modifier,
) {
    var dropdownExpanded by remember { mutableStateOf(false) }

    OutlinedCard(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(10.dp),
        border = BorderStroke(
            1.dp,
            if (candidate.targetEpisode != null) MaterialTheme.colorScheme.outlineVariant
            else MaterialTheme.colorScheme.outline.copy(alpha = 0.4f),
        ),
    ) {
        Column(
            modifier = Modifier.padding(10.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            // Filename & File size
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = candidate.filename,
                    style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Medium),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                if (candidate.fileSize > 0) {
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = candidate.fileSize.bytes.toString(),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            // Parsed detection tags & Episode selector
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                // Detection badge
                val detectedText = if (candidate.detectedEpisode != null) {
                    val epFormatted = if (candidate.detectedEpisode % 1.0 == 0.0) {
                        candidate.detectedEpisode.toInt().toString()
                    } else {
                        candidate.detectedEpisode.toString()
                    }
                    val seasonText = if (candidate.detectedSeason != null) "S${candidate.detectedSeason} " else ""
                    stringResource(Lang.cache_import_parsed_as, "$seasonText$epFormatted")
                } else {
                    stringResource(Lang.cache_import_unrecognized)
                }

                Surface(
                    color = if (candidate.detectedEpisode != null) {
                        MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.6f)
                    } else {
                        MaterialTheme.colorScheme.surfaceVariant
                    },
                    shape = RoundedCornerShape(4.dp),
                ) {
                    Text(
                        text = detectedText,
                        style = MaterialTheme.typography.labelSmall,
                        color = if (candidate.detectedEpisode != null) {
                            MaterialTheme.colorScheme.onPrimaryContainer
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                    )
                }

                // Dropdown trigger button
                Box {
                    Surface(
                        modifier = Modifier.clickable { dropdownExpanded = true },
                        color = if (candidate.targetEpisode != null) {
                            MaterialTheme.colorScheme.secondaryContainer
                        } else {
                            MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                        },
                        shape = RoundedCornerShape(6.dp),
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                        ) {
                            val epText = candidate.targetEpisode?.let {
                                "${it.sort} ${it.displayName.take(12)}"
                            } ?: stringResource(Lang.cache_import_ignore)
                            Text(
                                text = epText,
                                style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold),
                                color = if (candidate.targetEpisode != null) {
                                    MaterialTheme.colorScheme.onSecondaryContainer
                                } else {
                                    MaterialTheme.colorScheme.outline
                                },
                                maxLines = 1,
                            )
                            Spacer(Modifier.width(2.dp))
                            Icon(
                                Icons.Rounded.ArrowDropDown,
                                contentDescription = null,
                                modifier = Modifier.size(16.dp),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }

                    DropdownMenu(
                        expanded = dropdownExpanded,
                        onDismissRequest = { dropdownExpanded = false },
                        modifier = Modifier.heightIn(max = 280.dp),
                    ) {
                        // "Do not import" option
                        DropdownMenuItem(
                            text = {
                                Text(
                                    stringResource(Lang.cache_import_ignore),
                                    color = MaterialTheme.colorScheme.error,
                                )
                            },
                            leadingIcon = {
                                Icon(Icons.Rounded.Close, null, tint = MaterialTheme.colorScheme.error)
                            },
                            onClick = {
                                candidate.targetEpisode = null
                                dropdownExpanded = false
                            },
                        )

                        // Episode options
                        allEpisodes.forEach { ep ->
                            val isSelected = candidate.targetEpisode?.episodeId == ep.episodeId
                            DropdownMenuItem(
                                text = {
                                    Text(
                                        text = "${ep.sort}  ${ep.displayName}",
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                        fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                                    )
                                },
                                trailingIcon = if (isSelected) {
                                    { Icon(Icons.Rounded.Check, null, tint = MaterialTheme.colorScheme.primary) }
                                } else null,
                                onClick = {
                                    candidate.targetEpisode = ep
                                    dropdownExpanded = false
                                },
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun BatchOffsetDialog(
    onDismissRequest: () -> Unit,
    onApplyOffset: (Int) -> Unit,
) {
    var offsetText by remember { mutableStateOf("") }
    val offset = offsetText.trim().toIntOrNull()

    AlertDialog(
        onDismissRequest = onDismissRequest,
        title = { Text(stringResource(Lang.cache_import_offset_title)) },
        icon = { Icon(Icons.Rounded.SwapHoriz, null) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                // Quick offset buttons
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    listOf(-12, -1, 1, 12).forEach { quickVal ->
                        OutlinedButton(
                            onClick = {
                                onApplyOffset(quickVal)
                            },
                            modifier = Modifier.weight(1f),
                            shape = RoundedCornerShape(8.dp),
                        ) {
                            Text(if (quickVal > 0) "+$quickVal" else "$quickVal")
                        }
                    }
                }

                OutlinedTextField(
                    value = offsetText,
                    onValueChange = { offsetText = it },
                    label = { Text(stringResource(Lang.cache_import_offset_hint)) },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    if (offset != null) {
                        onApplyOffset(offset)
                    }
                },
                enabled = offset != null && offset != 0,
            ) {
                Text(stringResource(Lang.cache_import_offset_apply))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismissRequest) {
                Text(stringResource(Lang.cache_import_cancel))
            }
        },
    )
}
