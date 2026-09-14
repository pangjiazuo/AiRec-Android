package com.neardi.recorder.ui

import android.graphics.Paint
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.MutatePriority
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.stopScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.setProgress
import androidx.compose.ui.unit.dp
import com.neardi.recorder.data.RecorderApi
import com.neardi.recorder.data.objects
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import org.json.JSONObject
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

data class TimelineEvent(val startMs: Long, val endMs: Long, val type: String)
data class DayIndex(val recordings: List<RecordingInterval>, val events: List<TimelineEvent>)

/** 索引有缺失字段时明确报错，不能画成“全天没有录像”。 */
fun decodeDayIndex(json: JSONObject, api: RecorderApi, channel: Int, day: TimelineWindow): DayIndex {
    require(json.optJSONArray("recordings") != null && json.optJSONArray("event_segments") != null &&
        RecordingTimeline.parseStart(json.optString("start")) == day.startMs &&
        RecordingTimeline.parseStart(json.optString("end")) == day.endMs) { "全天索引响应不完整，请升级板端服务" }
    val rows = json.getJSONArray("recordings")
    val recordings = (0 until rows.length()).map { rows.getJSONObject(it) }.mapNotNull { item ->
        if (!item.optBoolean("available")) return@mapNotNull null
        require(item.optInt("channel_id") == channel) { "录像通道与索引不一致" }
        val interval = RecordingTimeline.interval(item.getString("id"), channel, item.getString("created_at"),
            item.getDouble("duration_seconds"), true, api.mediaUrl(item.getString("url")), item.optString("name"))
        require(interval != null) { "录像时间信息无效" }
        interval.takeIf { it.startMs < day.endMs && it.endMs > day.startMs }
    }.sortedBy { it.startMs }
    val eventRows = json.getJSONArray("event_segments")
    val events = (0 until eventRows.length()).map { eventRows.getJSONObject(it) }.map { item ->
        val start = RecordingTimeline.parseStart(item.getString("start"))
        val end = RecordingTimeline.parseStart(item.getString("end"))
        val type = item.getString("event_type")
        require(start != null && end != null && end > start && type in EventNames) { "事件时间信息无效" }
        TimelineEvent(start.coerceAtLeast(day.startMs), end.coerceAtMost(day.endMs), type)
    }.filter { it.endMs > it.startMs }
    return DayIndex(recordings, events)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DayDateSelector(date: LocalDate, onDate: (LocalDate) -> Unit) {
    var showPicker by rememberSaveable { mutableStateOf(false) }
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween) {
        IconButton(onClick = { onDate(date.minusDays(1)) }, modifier = Modifier.testTag("timeline-previous-day")) { RecorderGlyph("back", Modifier.size(18.dp)) }
        TextButton(onClick = { showPicker = true }, modifier = Modifier.testTag("timeline-date")) { Text(date.toString()) }
        IconButton(onClick = { onDate(date.plusDays(1)) }, enabled = date < LocalDate.now(),
            modifier = Modifier.testTag("timeline-next-day")) { RecorderGlyph("chevron", Modifier.size(18.dp)) }
    }
    if (showPicker) {
        // Material 日期选择器以 UTC 午夜表示日历日期；请求时仍使用设备本地时区。
        val picker = remember(date) { DatePickerState(locale = java.util.Locale.SIMPLIFIED_CHINESE, initialSelectedDateMillis = date.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()) }
        val selected = picker.selectedDateMillis?.let { Instant.ofEpochMilli(it).atZone(ZoneOffset.UTC).toLocalDate() }
        ModalBottomSheet(onDismissRequest = { showPicker = false }, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true), containerColor = RecorderBackground) {
            DatePicker(state = picker, title = null, headline = null, showModeToggle = false)
            Button(onClick = { selected?.let(onDate); showPicker = false }, enabled = selected != null && selected <= LocalDate.now(),
                modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 12.dp), shape = RoundedCornerShape(10.dp)) { Text("确定") }
            Spacer(Modifier.height(12.dp))
        }
    }
}

/** 中间指针固定，上下滑动全天时间轴。等滚动停止后再发一次 seek。 */
@Composable
fun VerticalDayTimeline(index: DayIndex, day: TimelineWindow, zone: ZoneId, enabled: Boolean,
    initialTime: Long, onScrubbing: (Boolean) -> Unit, onSelect: (Long) -> Unit) {
    val scroll = rememberScrollState()
    val density = LocalDensity.current
    var precise by rememberSaveable { mutableStateOf(false) }
    var anchor by rememberSaveable { mutableLongStateOf(initialTime) }
    var lastCommitted by rememberSaveable { mutableLongStateOf(initialTime) }
    var scrollCommand by remember { mutableIntStateOf(0) }
    var programmatic by remember { mutableStateOf(true) }
    val hourHeight = if (precise) 960.dp else 320.dp
    val pxPerMs = with(density) { hourHeight.toPx() } / 3_600_000.0
    val totalHeight = hourHeight * ((day.endMs - day.startMs) / 3_600_000f)
    // 概览的一像素可跨数秒；停滑后保留精确选时，不让绘制像素反向量化时间。
    val selected = if (scroll.isScrollInProgress && !programmatic)
        (day.startMs + scroll.value / pxPerMs).toLong().coerceIn(day.startMs, day.endMs - 1)
    else anchor.coerceIn(day.startMs, day.endMs - 1)
    val currentSelect by rememberUpdatedState(onSelect)
    val currentScrubbing by rememberUpdatedState(onScrubbing)
    var initialized by remember { mutableStateOf(false) }
    fun submit(time: Long) {
        anchor = time.coerceIn(day.startMs, day.endMs - 1)
        lastCommitted = anchor
        currentScrubbing(false)
        currentSelect(anchor)
    }
    // 续接下一片段只同步指针，不能再次产生用户 seek。
    LaunchedEffect(initialTime) {
        if (initialTime != lastCommitted) {
            anchor = initialTime; lastCommitted = initialTime; programmatic = true; scrollCommand++
        }
    }
    LaunchedEffect(day, pxPerMs, scrollCommand) {
        programmatic = true
        currentScrubbing(false)
        try {
            val target = ((anchor.coerceIn(day.startMs, day.endMs - 1) - day.startMs) * pxPerMs).toInt()
            // 缩放须先接管仍按住的手势；默认优先级会被 UserInput 取消。
            scroll.scroll(MutatePriority.PreventUserInput) { scrollBy((target - scroll.value).toFloat()) }
            withFrameNanos { }
            initialized = true
        } finally { programmatic = false; currentScrubbing(false) }
    }
    LaunchedEffect(enabled) {
        if (!enabled) { scroll.stopScroll(MutatePriority.PreventUserInput); currentScrubbing(false) }
    }
    DisposableEffect(Unit) { onDispose { currentScrubbing(false) } }
    LaunchedEffect(scroll, day, enabled, initialized, pxPerMs, scrollCommand) {
        if (!enabled || !initialized) return@LaunchedEffect
        var touched = false
        try {
            snapshotFlow { Triple(scroll.isScrollInProgress, scroll.value, programmatic) }.collectLatest { (moving, value, automatic) ->
                if (automatic) { touched = false; currentScrubbing(false); return@collectLatest }
                if (moving) {
                    touched = true
                    // 手势中途旋转也恢复最后可见时刻，而非初始时刻。
                    anchor = (day.startMs + value / pxPerMs).toLong().coerceIn(day.startMs, day.endMs - 1)
                    currentScrubbing(true)
                }
                if (!moving && touched) {
                    delay(300)
                    touched = false
                    submit((day.startMs + value / pxPerMs).toLong())
                }
            }
        } finally { currentScrubbing(false) }
    }
    val ink = RecorderMuted
    val line = RecorderLine
    val recording = RecorderBlue.copy(alpha = .24f)
    val colors = EventNames.keys.associateWith { eventColor(it) }
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), horizontalArrangement = Arrangement.SpaceEvenly) {
            EventNames.forEach { (type, name) -> Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(6.dp).background(colors.getValue(type), CircleShape)); Spacer(Modifier.width(4.dp))
                Text(name, color = RecorderMuted, style = MaterialTheme.typography.labelSmall)
            } }
        }
        Surface(Modifier.fillMaxWidth().weight(1f), shape = RoundedCornerShape(0.dp), color = RecorderBackground) {
            BoxWithConstraints(Modifier.fillMaxSize().testTag("channel-day-timeline").semantics {
                stateDescription = if (enabled) "已加载" else "加载中"
                progressBarRangeInfo = ProgressBarRangeInfo(day.fractionAt(selected), 0f..1f)
                setProgress { fraction ->
                    if (!enabled || !fraction.isFinite()) false else {
                        programmatic = true; scrollCommand++
                        submit(day.timeAt(fraction))
                        true
                    }
                }
            }) {
                val half = maxHeight / 2
                Column(Modifier.fillMaxSize().verticalScroll(scroll, enabled = enabled)) {
                    Spacer(Modifier.height(half))
                    Canvas(Modifier.fillMaxWidth().height(totalHeight)) {
                        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = ink.toArgb(); textSize = 11.dp.toPx() }
                        val left = size.width * .36f
                        val right = size.width - 20.dp.toPx()
                        val scale = size.height / (day.endMs - day.startMs)
                        index.recordings.forEach { r ->
                            val top = (r.startMs.coerceAtLeast(day.startMs) - day.startMs) * scale
                            val bottom = (r.endMs.coerceAtMost(day.endMs) - day.startMs) * scale
                            drawRect(recording, Offset(left, top), Size(16.dp.toPx(), (bottom-top).coerceAtLeast(1f)))
                        }
                        EventNames.keys.forEachIndexed { lane, type -> index.events.filter { it.type == type }.forEach { event ->
                            val top = (event.startMs - day.startMs) * scale
                            val bottom = (event.endMs - day.startMs) * scale
                            drawRect(colors.getValue(type), Offset(left + 23.dp.toPx() + lane * 14.dp.toPx(), top),
                                Size(8.dp.toPx(), (bottom-top).coerceAtLeast(2.dp.toPx())))
                        } }
                        var time = day.startMs
                        while (time <= day.endMs) {
                            val y = (time - day.startMs) * scale
                            val hour = (time - day.startMs) % (if (precise) 300_000 else 600_000) == 0L
                            drawLine(line, Offset(44.dp.toPx(), y), Offset(size.width, y), 1.dp.toPx())
                            if (hour) drawContext.canvas.nativeCanvas.drawText(
                                if (time == day.endMs) "24:00" else Instant.ofEpochMilli(time).atZone(zone).format(DateTimeFormatter.ofPattern("HH:mm")),
                                6.dp.toPx(), y + 4.dp.toPx(), paint)
                            time += if (precise) 60_000 else 600_000
                        }
                    }
                    Spacer(Modifier.height(half))
                }
                HorizontalDivider(Modifier.align(Alignment.Center).padding(start = 54.dp), color = RecorderBlue, thickness = 1.dp)
                Text(Instant.ofEpochMilli(selected).atZone(zone).format(DateTimeFormatter.ofPattern("HH:mm:ss")),
                    Modifier.align(Alignment.CenterStart).background(RecorderBackground).testTag("timeline-selected-time"), color = RecorderBlue, style = MaterialTheme.typography.labelSmall)
                Row(Modifier.align(Alignment.BottomEnd).background(RecorderBackground)) {
                    TextButton(onClick = { anchor = selected; programmatic = true; precise = false }, enabled = precise, modifier = Modifier.testTag("timeline-overview")) { Text("概览", style = MaterialTheme.typography.labelSmall) }
                    TextButton(onClick = { anchor = selected; programmatic = true; precise = true }, enabled = !precise, modifier = Modifier.testTag("timeline-precise")) { Text("精细", style = MaterialTheme.typography.labelSmall) }
                }
            }
        }
        Text("上下滑动查看全天录像", Modifier.padding(vertical = 6.dp),
            color = RecorderMuted, style = MaterialTheme.typography.labelSmall)
    }
}
