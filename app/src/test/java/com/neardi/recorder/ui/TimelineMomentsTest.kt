package com.neardi.recorder.ui

import com.neardi.recorder.data.RecorderApi
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId

class TimelineMomentsTest {
    @Test fun denseEventsKeepEveryItemAndDoNotChainAcrossTheWholeDay() {
        val moments = (0..1000).map { TimelineMoment("$it", it * 1000L, "person", null) }
        val groups = groupTimelineMoments(moments.reversed(), 10_000)
        assertEquals(101, groups.size)
        assertEquals(moments, groups.flatten())
        assertEquals(10_000L, groups[1].first().timeMs)
    }

    @Test fun snapshotsKeepDayAndChannelIsolationAndRejectForeignUrls() {
        val day = RecordingTimeline.day(LocalDate.of(2026, 9, 15), ZoneId.of("UTC"))
        fun item(id: String, channel: Int, date: String, url: String) = JSONObject()
            .put("id", id).put("channel_id", channel).put("created_at", date)
            .put("event_type", "person").put("snapshot_url", url)
        val response = JSONObject().put("start", "2026-09-15T00:00:00Z").put("end", "2026-09-16T00:00:00Z")
            .put("recordings", JSONArray()).put("event_segments", JSONArray())
            .put("event_items", JSONArray()
                .put(item("good", 1, "2026-09-15T08:00:00Z", "/media/good"))
                .put(item("wrong-channel", 2, "2026-09-15T08:00:00Z", "/media/other"))
                .put(item("wrong-day", 1, "2026-09-14T08:00:00Z", "/media/old"))
                .put(item("foreign", 1, "2026-09-15T09:00:00Z", "https://example.org/image")))
        val index = decodeDayIndex(response, RecorderApi("http://127.0.0.1:8080"), 1, day)
        assertTrue(index.hasSnapshots)
        assertEquals(listOf("good", "foreign"), index.moments.map { it.id })
        assertNull(index.moments.last().snapshotUrl)
        response.remove("event_items")
        assertFalse(decodeDayIndex(response, RecorderApi("http://127.0.0.1:8080"), 1, day).hasSnapshots)
    }
}
