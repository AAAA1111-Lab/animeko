/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.domain.media.cache.engine

import android.content.Intent
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import me.him188.ani.app.platform.Context
import me.him188.ani.utils.coroutines.IO_

actual fun createLocalImportFileAccess(context: Context): LocalImportFileAccess {
    return AndroidLocalImportFileAccess(context)
}

class AndroidLocalImportFileAccess(private val context: Context) : LocalImportFileAccess {
    private val systemFileAccess = SystemLocalImportFileAccess()

    override suspend fun exists(filePath: String): Boolean = withContext(Dispatchers.IO_) {
        if (!filePath.startsWith(CONTENT_SCHEME_PREFIX)) {
            return@withContext systemFileAccess.exists(filePath)
        }
        try {
            context.contentResolver.query(Uri.parse(filePath), null, null, null, null)
                ?.use { it.count > 0 } ?: false
        } catch (_: Exception) {
            false
        }
    }

    override suspend fun persistReadPermissions(filePaths: List<String>) = withContext(Dispatchers.IO_) {
        for (filePath in filePaths) {
            if (!filePath.startsWith(CONTENT_SCHEME_PREFIX)) continue
            try {
                context.contentResolver.takePersistableUriPermission(
                    Uri.parse(filePath),
                    Intent.FLAG_GRANT_READ_URI_PERMISSION,
                )
            } catch (_: SecurityException) {
                // 部分内容提供方不支持持久化授权, 只能维持本次会话内的临时授权.
            }
        }
    }

    private companion object {
        const val CONTENT_SCHEME_PREFIX = "content:"
    }
}
