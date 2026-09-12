package com.neardi.recorder

import com.neardi.recorder.data.RecorderApi
import com.neardi.recorder.ui.RecordingTimeline
import com.neardi.recorder.ui.decodeDayIndex
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

class DayIndexTest {
    private val api = RecorderApi("http://127.0.0.1:8080")
    private val day = RecordingTimeline.day(LocalDate.of(2026, 9, 8), ZoneId.of("Asia/Shanghai"))
    private fun response(rows: JSONArray = JSONArray(), events: JSONArray = JSONArray()) = JSONObject()
        .put("start", Instant.ofEpochMilli(day.startMs)).put("end", Instant.ofEpochMilli(day.endMs))
        .put("recordings", rows).put("event_segments", events)
    private fun clip(id: String, start: Long, available: Boolean = true) = JSONObject().put("id", id)
        .put("channel_id", 1).put("created_at", Instant.ofEpochMilli(start).toString()).put("duration_seconds", 60)
        .put("url", "/media/$id.mp4").put("available", available)

    @Test fun indexRetainsAllCompletedSegmentsAndOriginalMidnightStart() {
        val rows = JSONArray()
        repeat(1440) { rows.put(clip("$it", day.startMs + it * 60_000L)) }
        rows.put(clip("midnight", day.startMs - 30_000))
        rows.put(clip("removed", day.startMs, false))
        val result = decodeDayIndex(response(rows), api, 1, day)
        assertEquals(1441, result.recordings.size)
        assertEquals(day.startMs - 30_000, result.recordings.first().startMs)
        assertFalse(result.recordings.any { it.id == "removed" })
    }

    @Test fun eventColorsKeepTypesAndClipAtDayEdges() {
        val events = JSONArray()
        listOf("dwell", "person", "vehicle", "animal").forEach { type -> events.put(JSONObject()
            .put("start", Instant.ofEpochMilli(day.startMs - 5000).toString())
            .put("end", Instant.ofEpochMilli(day.startMs + 1000).toString()).put("event_type", type)) }
        val result = decodeDayIndex(response(events = events), api, 1, day)
        assertEquals(4, result.events.size)
        assertTrue(result.events.all { it.startMs == day.startMs && it.endMs == day.startMs + 1000 })
    }

    @Test fun incompleteOrWrongRangeIsNotPresentedAsEmptyDay() {
        assertThrows(IllegalArgumentException::class.java) { decodeDayIndex(JSONObject(), api, 1, day) }
        assertThrows(IllegalArgumentException::class.java) {
            decodeDayIndex(response().put("end", Instant.ofEpochMilli(day.endMs + 1000).toString()), api, 1, day)
        }
    }
}
