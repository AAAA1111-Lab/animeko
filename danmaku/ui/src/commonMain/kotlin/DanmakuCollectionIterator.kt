/*
 * Copyright (C) 2024-2025 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.danmaku.ui

class DanmakuCollectionIterator<T : SizeSpecifiedDanmaku, D>(
    private val tracks: List<DanmakuTrack<T, D>>
) : Iterator<D>, Iterable<D> {
    private var currentTrackIndex = 0
    private var currentTrackIterator: Iterator<D>? = null

    init {
        advanceToNextNonEmptyTrack()
    }

    private fun advanceToNextNonEmptyTrack() {
        currentTrackIterator = null

        while (currentTrackIndex < tracks.size) {
            val iterator = tracks[currentTrackIndex].iterator()
            if (iterator.hasNext()) {
                currentTrackIterator = iterator
                return
            }
            currentTrackIndex++
        }
    }

    override fun hasNext(): Boolean {
        // If current iterator is done, try to find next track with elements
        if (currentTrackIterator?.hasNext() != true && currentTrackIndex < tracks.size) {
            currentTrackIndex++
            advanceToNextNonEmptyTrack()
        }

        return currentTrackIterator?.hasNext() == true
    }

    override fun next(): D {
        if (!hasNext()) {
            throw NoSuchElementException("No more elements in danmaku collection")
        }

        val next = currentTrackIterator!!.next()

        // If current iterator is exhausted, prepare the next track's iterator
        if (!currentTrackIterator!!.hasNext()) {
            currentTrackIndex++
            advanceToNextNonEmptyTrack()
        }

        return next
    }

    override fun iterator(): Iterator<D> {
        return this
    }
}