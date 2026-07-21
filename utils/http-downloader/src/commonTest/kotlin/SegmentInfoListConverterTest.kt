/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.utils.httpdownloader

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import me.him188.ani.utils.serialization.DatabaseProtoBuf
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class SegmentInfoListConverterTest {
    @Serializable
    private data class LegacySegmentInfo(
        val index: Int,
        val url: String,
        val isDownloaded: Boolean,
        val byteSize: Long = -1,
        @SerialName("tempFilePath")
        val relativeTempFilePath: String,
        val rangeStart: Long? = null,
        val rangeEnd: Long? = null,
    )

    @Serializable
    private data class LegacyNode(val value: List<LegacySegmentInfo>)

    @Serializable
    private data class CurrentNode(val value: List<SegmentInfo>)

    @Test
    fun should_upgrade_legacy_segment_info_payload_and_serialize_back_to_current_schema() {
        val legacySegments = listOf(
            LegacySegmentInfo(
                index = 0,
                url = "https://example.com/0.ts",
                isDownloaded = true,
                byteSize = 1024,
                relativeTempFilePath = "segments/0.tmp",
                rangeStart = 0,
                rangeEnd = 1023,
            ),
            LegacySegmentInfo(
                index = 1,
                url = "https://example.com/1.ts",
                isDownloaded = false,
                byteSize = -1,
                relativeTempFilePath = "segments/1.tmp",
                rangeStart = null,
                rangeEnd = null,
            ),
        )
        val legacyBytes = DatabaseProtoBuf.encodeToByteArray(LegacyNode.serializer(), LegacyNode(legacySegments))

        assertFailsWith<SerializationException> {
            DatabaseProtoBuf.decodeFromByteArray(CurrentNode.serializer(), legacyBytes)
        }

        val upgradedSegments = SegmentInfoListConverter.fromByteArray(legacyBytes)
        val expected = listOf(
            SegmentInfo(
                index = 0,
                url = "https://example.com/0.ts",
                isDownloaded = true,
                byteSize = 1024,
                durationSeconds = null,
                title = null,
                isDiscontinuity = false,
                encryption = null,
                relativeTempFilePath = "segments/0.tmp",
                rangeStart = 0,
                rangeEnd = 1023,
            ),
            SegmentInfo(
                index = 1,
                url = "https://example.com/1.ts",
                isDownloaded = false,
                byteSize = -1,
                durationSeconds = null,
                title = null,
                isDiscontinuity = false,
                encryption = null,
                relativeTempFilePath = "segments/1.tmp",
                rangeStart = null,
                rangeEnd = null,
            ),
        )
        assertEquals(expected, upgradedSegments)

        val newBytes = SegmentInfoListConverter.fromList(upgradedSegments)
        val currentNode = DatabaseProtoBuf.decodeFromByteArray(CurrentNode.serializer(), newBytes)

        assertEquals(expected, currentNode.value)
    }
}
