/*
 * Copyright (C) 2024-2025 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package models

import kotlinx.serialization.Serializable
import me.him188.ani.datasources.ikaros.models.IkarosAttachmentType

@Serializable
class IkarosAttachment(
    val id: Long = 0,
    val parentId: Long = 0,
    val type: IkarosAttachmentType = IkarosAttachmentType.File,

    /**
     * HTTP path.
     */
    val url: String? = null,

    /**
     * Attachment logic path.
     */
    val path: String? = null,

    /**
     * File path in file system.
     */
    val fsPath: String? = null,

    /**
     * filename with postfix.
     */
    val name: String? = null,
    val size: Long = 0,
    val updateTime: String? = null,
)
