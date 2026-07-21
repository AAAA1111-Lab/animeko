/*
 * Copyright (C) 2024-2025 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.domain.mediasource

import io.ktor.http.URLBuilder
import io.ktor.http.appendPathSegments

object MediaSourceEngineHelpers {
    fun encodeUrlSegment(value: String) =
        URLBuilder().appendPathSegments(value).encodedPathSegments.first()

    fun getSearchKeyword(subjectName: String, removeSpecial: Boolean, useOnlyFirstWord: Boolean): String {
        val finalName = if (removeSpecial) {
            MediaListFilters.removeSpecials(
                subjectName,
                removeWhitespace = false,
                replaceNumbers = false,
                removeMarkers = true, // Note: 删除 "剧场版" 等标记, 否则 "剧场版 命运石之门" 会变为 "剧场版"
            ) // keep whitespace for getFirstWord
        } else {
            subjectName
        }

        return if (useOnlyFirstWord) {
            getFirstWord(finalName)
        } else {
            finalName
        }
    }

    private fun getFirstWord(string: String): String {
        if (!(string.contains(' '))) return string
        return string.substringBefore(' ').ifBlank { string }
    }
}