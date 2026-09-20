/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.datasources.jellyfin

import kotlin.test.Test
import kotlin.test.assertEquals

class JellyfinStreamUriTest {
    @Test
    fun `audio transcode uri keeps the original video stream`() {
        assertEquals(
            "http://jellyfin.local/Videos/episode-1/stream.mkv" +
                "?AudioCodec=aac&ApiKey=token&api_key=token",
            buildJellyfinTranscodeAudioUri(
                baseUrl = "http://jellyfin.local",
                itemId = "episode-1",
                container = "mkv",
                accessToken = "token",
            ),
        )
    }

    @Test
    fun `audio transcode uri uses the first container and tolerates a leading dot`() {
        assertEquals(
            "http://jellyfin.local/Videos/episode-1/stream.mp4" +
                "?AudioCodec=aac&ApiKey=token&api_key=token",
            buildJellyfinTranscodeAudioUri(
                baseUrl = "http://jellyfin.local",
                itemId = "episode-1",
                container = ".mp4,webm",
                accessToken = "token",
            ),
        )
    }

    @Test
    fun `audio transcode uri omits the extension when the container is unknown`() {
        assertEquals(
            "http://jellyfin.local/Videos/episode-1/stream" +
                "?AudioCodec=aac&ApiKey=token&api_key=token",
            buildJellyfinTranscodeAudioUri(
                baseUrl = "http://jellyfin.local",
                itemId = "episode-1",
                container = null,
                accessToken = "token",
            ),
        )
    }
}
