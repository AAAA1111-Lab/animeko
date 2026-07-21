/*
 * Copyright (C) 2024-2025 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.datasources.ikaros

import io.ktor.util.toLowerCasePreservingASCIIRules

internal class AssNameParser {

    private val scCnLowerCaseList = listOf("sc", "chs", "gb")
    private val tcCnLowerCaseList = listOf("tc", "cht", "big5")

    /**
     * parse ass name to language.
     *
     * such as `[DBD-Raws][XXX！][01][1080P][BDRip][HEVC-10bit][FLAC].sc.ass` to `sc`.
     */
    fun parseAssName2Language(name: String): String {
        if (name.isBlank() || !name.endsWith("ass")) return name
        // remove `.ass` postfix 
        val removeSuffix = name.removeSuffix(".ass")
        val language = removeSuffix.substringAfterLast('.', "")
        if (language.isEmpty()) return removeSuffix
        if (scCnLowerCaseList.contains(language.toLowerCasePreservingASCIIRules())) return "简中"
        if (tcCnLowerCaseList.contains(language.toLowerCasePreservingASCIIRules())) return "繁中"
        return language;
    }

    companion object {
        val default by lazy(LazyThreadSafetyMode.PUBLICATION) {
            AssNameParser()
        }

        val mineTypeTextXssa = "text/x-ssa"
        val mineTypeApplicationXssa = "application/x-ssa"

        /**
         * default is `text/x-ssa`.
         *
         * @see AssNameParser.mineTypeTextXssa
         * @see AssNameParser.mineTypeApplicationXssa
         */
        val httpMineType = mineTypeTextXssa;
    }

}
