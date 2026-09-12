package com.neardi.recorder

import com.neardi.recorder.ui.RecordingTimeline
import org.junit.Assert.*
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId

class RecordingTimelineTest {
    @Test fun wholeDayIndexUses23And25HoursAcrossDst() {
        val ny = ZoneId.of("America/New_York")
        val spring = RecordingTimeline.day(LocalDate.of(2026, 3, 8), ny)
        val autumn = RecordingTimeline.day(LocalDate.of(2026, 11, 1), ny)
        assertEquals(23 * 3_600_000L, spring.endMs - spring.startMs)
        assertEquals(25 * 3_600_000L, autumn.endMs - autumn.startMs)
    }

    @Test fun continuationNeverSkipsGapsOrCrossesDayBoundary() {
        val day = RecordingTimeline.day(LocalDate.of(2026, 9, 8), zone)
        val a = clip("a", "2026-09-08T10:00:00+08:00")
        val adjacent = clip("b", "2026-09-08T10:01:00+08:00")
        val gap = clip("gap", "2026-09-08T10:01:01+08:00")
        val overlap = clip("overlap", "2026-09-08T10:00:30+08:00")
        assertEquals(0L, RecordingTimeline.next(listOf(a, adjacent), a, day)?.positionMs)
        assertEquals(30_000L, RecordingTimeline.next(listOf(a, overlap), a, day)?.positionMs)
        assertNull(RecordingTimeline.next(listOf(a, gap), a, day))
        val midnight = clip("midnight", "2026-09-08T23:59:30+08:00")
        assertNull(RecordingTimeline.next(listOf(midnight, clip("tomorrow", "2026-09-09T00:00:00+08:00")), midnight, day))
    }
    private val zone = ZoneId.of("Asia/Shanghai")
    private fun clip(id: String, start: String, seconds: Double = 60.0, channel: Int = 1) =
        RecordingTimeline.interval(id, channel, start, seconds, true, "http://192.168.10.172:8080/media/$id.mp4", id)!!

    @Test fun missingOrInvalidMetadataNeverCreatesPlayableTime() {
        listOf(Double.NaN, Double.POSITIVE_INFINITY, 0.0, -1.0, Double.MAX_VALUE).forEach { duration ->
            assertNull(RecordingTimeline.interval("a", 1, "2026-09-08T00:00:00Z", duration, true, "/a.mp4", "a"))
        }
        assertNull(RecordingTimeline.interval("a", 1, "invalid", 60.0, true, "/a.mp4", "a"))
        assertNull(RecordingTimeline.interval("a", 1, "2026-09-08T00:00:00Z", 60.0, false, "/a.mp4", "a"))
        assertNull(RecordingTimeline.interval("a", 6, "2026-09-08T00:00:00Z", 60.0, true, "/a.mp4", "a"))
    }

    @Test fun gapAndOtherChannelsDoNotJumpToANearbyClip() {
        val a = clip("a", "2026-09-08T10:00:00+08:00")
        val b = clip("b", "2026-09-08T10:02:00+08:00")
        val other = clip("other", "2026-09-08T10:01:00+08:00", channel = 2)
        val rows = listOf(a, b, other)
        assertNull(RecordingTimeline.select(rows, 1, a.endMs + 15_000))
        assertEquals("other", RecordingTimeline.select(rows, 2, a.endMs + 15_000)?.interval?.id)
        assertNull(RecordingTimeline.select(rows, 3, a.startMs))
        assertEquals(15_000L, RecordingTimeline.select(rows, 1, b.startMs + 15_000)?.positionMs)
    }

    @Test fun endBoundaryBelongsToNextSegmentAndOverlapsPreferLatestStart() {
        val a = clip("a", "2026-09-08T10:00:00+08:00")
        val b = clip("b", "2026-09-08T10:01:00+08:00")
        assertNull(RecordingTimeline.select(listOf(a), 1, a.endMs))
        val selection = RecordingTimeline.select(listOf(a, b), 1, a.endMs)!!
        assertEquals("b", selection.interval.id)
        assertEquals(0L, selection.positionMs)
        val overlapping = clip("overlap", "2026-09-08T10:00:30+08:00")
        assertEquals("overlap", RecordingTimeline.select(listOf(a, overlapping), 1, a.startMs + 45_000)?.interval?.id)
    }

    @Test fun midnightClipAppearsOnBothDatesAndSeeksFromOriginalStart() {
        val a = clip("a", "2026-09-07T23:59:30+08:00")
        val yesterday = LocalDate.of(2026, 9, 7)
        val today = yesterday.plusDays(1)
        assertEquals(setOf(yesterday, today), RecordingTimeline.dates(a, zone))
        assertTrue(RecordingTimeline.overlapsDay(a, yesterday, zone))
        assertTrue(RecordingTimeline.overlapsDay(a, today, zone))
        val midnight = today.atStartOfDay(zone).toInstant().toEpochMilli()
        assertEquals(30_000L, RecordingTimeline.select(listOf(a), 1, midnight)?.positionMs)
        assertFalse(RecordingTimeline.overlapsDay(a, today.plusDays(1), zone))
    }

    @Test fun exactMidnightEndDoesNotInventNextDayRecording() {
        val a = clip("a", "2026-09-07T23:59:00+08:00")
        assertEquals(setOf(LocalDate.of(2026, 9, 7)), RecordingTimeline.dates(a, zone))
        assertFalse(RecordingTimeline.overlapsDay(a, LocalDate.of(2026, 9, 8), zone))
    }

    @Test fun windowPreservesThirtyMinutesAtDayEdgesAndUsesRealDstDayLength() {
        val a = clip("a", "2026-09-08T00:00:00+08:00")
        val window = RecordingTimeline.window(listOf(a), LocalDate.of(2026, 9, 8), zone)
        assertEquals(a.startMs, window.startMs)
        assertEquals(30 * 60_000L, window.endMs - window.startMs)
        val summerTransition = RecordingTimeline.window(emptyList(), LocalDate.of(2026, 3, 8), ZoneId.of("America/New_York"))
        assertEquals(23 * 3_600_000L, summerTransition.endMs - summerTransition.startMs)
        assertEquals(window.startMs, window.timeAt(-.5f))
        assertEquals(window.endMs, window.timeAt(1.5f))
    }
}
