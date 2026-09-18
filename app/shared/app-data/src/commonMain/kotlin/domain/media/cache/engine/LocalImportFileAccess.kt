/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.domain.media.cache.engine

import kotlinx.io.files.Path
import me.him188.ani.app.platform.Context
import me.him188.ani.utils.io.exists
import me.him188.ani.utils.io.inSystem

/**
 * 本地导入缓存 ([LocalImportMediaCacheEngine]) 对导入文件的访问抽象.
 *
 * 其他平台导入的文件路径就是普通文件系统路径; Android 上系统文件选择器 (SAF) 返回的是 `content://` URI,
 * 必须通过 ContentResolver 访问, 且需要持久化授权才能在应用重启后继续读取.
 *
 * Use [createLocalImportFileAccess] to create instance.
 */
interface LocalImportFileAccess {
    /**
     * 导入的文件当前是否仍然可访问.
     */
    suspend fun exists(filePath: String): Boolean

    /**
     * 对选中的文件持久化读取授权, 使应用重启后仍可访问.
     * 仅 Android 需要, 其他平台为空操作.
     */
    suspend fun persistReadPermissions(filePaths: List<String>) {
    }
}

/**
 * 普通文件系统路径的实现, 用于桌面与 iOS.
 */
class SystemLocalImportFileAccess : LocalImportFileAccess {
    override suspend fun exists(filePath: String): Boolean {
        return Path(filePath).inSystem.exists()
    }
}

expect fun createLocalImportFileAccess(context: Context): LocalImportFileAccess
