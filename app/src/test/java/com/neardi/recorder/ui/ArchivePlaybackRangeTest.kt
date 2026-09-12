package com.neardi.recorder.ui

import org.junit.Assert.*
import org.junit.Test

class ArchivePlaybackRangeTest {
    @Test fun midnightClipUsesRelativePlayerTimeAndKeepsOriginalFileOffsets() {
        val range = ArchivePlaybackRange(30_000, 90_000)
        assertEquals(0L, range.toPlayerPosition(10_000))
        assertEquals(0L, range.toPlayerPosition(30_000))
        assertEquals(7_000L, range.toPlayerPosition(37_000))
        assertEquals(30_000L, range.toOriginalPosition(0))
        assertEquals(37_000L, range.toOriginalPosition(7_000))
        assertEquals(59_999L, range.toPlayerPosition(120_000))
        assertEquals(90_000L, range.toOriginalPosition(60_000))
        assertEquals(90_000L, range.toOriginalPosition(Long.MAX_VALUE))
    }

    @Test fun unboundedEventVideoRetainsOriginalTimelineAndRejectsEmptyRanges() {
        val range = ArchivePlaybackRange()
        assertEquals(37_000L, range.toPlayerPosition(37_000))
        assertEquals(37_000L, range.toOriginalPosition(37_000))
        assertEquals(0L, range.toOriginalPosition(-1))
        assertThrows(IllegalArgumentException::class.java) { ArchivePlaybackRange(30_000, 30_000) }
    }
}
