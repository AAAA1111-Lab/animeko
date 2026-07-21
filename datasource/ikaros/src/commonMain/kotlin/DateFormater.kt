/*
 * Copyright (C) 2024-2025 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.datasources.ikaros

import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toInstant

internal object DateFormater {
    fun utcDateStr2timeStamp(dateStr: String): Long {
        if (dateStr.isEmpty()) {
            return 0
        }
        return LocalDateTime.parse(dateStr).toInstant(TimeZone.UTC).toEpochMilliseconds()
    }
}
