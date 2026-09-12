package com.neardi.recorder.ui

import java.time.Instant
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.ZoneId

/** 只使用片段的真实起点、时长和可用状态；区间右端不包含在本片段内。 */
data class RecordingInterval(
    val id: String,
    val channelId: Int,
    val startMs: Long,
    val endMs: Long,
    val url: String,
    val title: String,
)

data class RecordingSelection(val interval: RecordingInterval, val positionMs: Long)

data class TimelineWindow(val startMs: Long, val endMs: Long) {
    fun timeAt(fraction: Float): Long = startMs + ((endMs - startMs) * fraction.coerceIn(0f, 1f)).toLong()
    fun fractionAt(timeMs: Long): Float = ((timeMs - startMs).toDouble() / (endMs - startMs)).toFloat().coerceIn(0f, 1f)
}

object RecordingTimeline {
    fun day(date: LocalDate, zone: ZoneId): TimelineWindow = TimelineWindow(
        date.atStartOfDay(zone).toInstant().toEpochMilli(), date.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli())

    /** 只接时间相邻或重叠的下一段，不跨过无录像区间。 */
    fun next(intervals: List<RecordingInterval>, current: RecordingInterval, day: TimelineWindow): RecordingSelection? {
        if (current.endMs >= day.endMs) return null
        val next = intervals.filter { it.channelId == current.channelId && it.id != current.id &&
            it.startMs <= current.endMs && it.endMs > current.endMs }.minByOrNull { it.startMs } ?: return null
        return RecordingSelection(next, (current.endMs - next.startMs).coerceAtLeast(0))
    }
    fun parseStart(value: String): Long? = runCatching { OffsetDateTime.parse(value).toInstant().toEpochMilli() }.getOrNull()

    fun interval(id: String, channelId: Int, createdAt: String, durationSeconds: Double,
                 available: Boolean, url: String, title: String): RecordingInterval? {
        if (!available || channelId !in 1..5 || !durationSeconds.isFinite() || durationSeconds <= 0 || url.isBlank()) return null
        val start = parseStart(createdAt) ?: return null
        val duration = durationSeconds * 1_000.0
        if (!duration.isFinite() || duration < 1 || duration >= Long.MAX_VALUE.toDouble()) return null
        val end = runCatching { Math.addExact(start, duration.toLong()) }.getOrNull() ?: return null
        return RecordingInterval(id, channelId, start, end, url, title)
    }

    fun select(intervals: List<RecordingInterval>, channelId: Int, timeMs: Long): RecordingSelection? =
        intervals.asSequence().filter { it.channelId == channelId && timeMs >= it.startMs && timeMs < it.endMs }
            .maxByOrNull { it.startMs }?.let { RecordingSelection(it, timeMs - it.startMs) }

    fun overlapsDay(interval: RecordingInterval, date: LocalDate, zone: ZoneId): Boolean {
        val start = date.atStartOfDay(zone).toInstant().toEpochMilli()
        val end = date.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
        return interval.startMs < end && interval.endMs > start
    }

    fun dates(interval: RecordingInterval, zone: ZoneId): Set<LocalDate> = setOf(
        Instant.ofEpochMilli(interval.startMs).atZone(zone).toLocalDate(),
        Instant.ofEpochMilli(interval.endMs - 1).atZone(zone).toLocalDate(),
    )

    /** 自动聚焦本日已加载片段，至少展示 30 分钟；空白处保持空白。 */
    fun window(intervals: List<RecordingInterval>, date: LocalDate, zone: ZoneId): TimelineWindow {
        val dayStart = date.atStartOfDay(zone).toInstant().toEpochMilli()
        val dayEnd = date.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
        val visible = intervals.filter { overlapsDay(it, date, zone) }
        if (visible.isEmpty()) return TimelineWindow(dayStart, dayEnd)
        val first = visible.minOf { it.startMs }.coerceAtLeast(dayStart)
        val last = visible.maxOf { it.endMs }.coerceAtMost(dayEnd)
        val span = maxOf(30 * 60_000L, last - first + 120_000L).coerceAtMost(dayEnd - dayStart)
        val start = (first - (span - (last - first)) / 2).coerceIn(dayStart, dayEnd - span)
        return TimelineWindow(start, start + span)
    }
}
