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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import kotlin.math.roundToInt
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
data class TimelineMoment(val id: String, val timeMs: Long, val type: String, val snapshotUrl: String?)
data class DayIndex(val recordings: List<RecordingInterval>, val events: List<TimelineEvent>,
    val moments: List<TimelineMoment> = emptyList(), val hasSnapshots: Boolean = false)

/** 同一时间附近的事件合并，保持真实时间比例，避免截图互相覆盖。 */
fun groupTimelineMoments(moments: List<TimelineMoment>, separationMs: Long): List<List<TimelineMoment>> {
    val groups = mutableListOf<MutableList<TimelineMoment>>()
    moments.sortedWith(compareBy<TimelineMoment> { it.timeMs }.thenBy { it.id }).forEach { event ->
        val last = groups.lastOrNull()
        if (last != null && event.timeMs - last.first().timeMs < separationMs) last.add(event)
        else groups.add(mutableListOf(event))
    }
    return groups
}

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
    val moments = json.optJSONArray("event_items").objects().mapNotNull { item ->
        val time = RecordingTimeline.parseStart(item.optString("created_at")) ?: return@mapNotNull null
        val type = item.optString("event_type")
        if (type !in EventNames || item.optInt("channel_id") != channel || time !in day.startMs until day.endMs) return@mapNotNull null
        val url = item.optString("snapshot_url").takeIf { it.isNotBlank() }?.let { runCatching { api.mediaUrl(it) }.getOrNull() }
        TimelineMoment(item.getString("id"), time, type, url)
    }.distinctBy { it.id }.sortedBy { it.timeMs }
    return DayIndex(recordings, events, moments, json.has("event_items"))
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DayDateSelector(date: LocalDate, onDate: (LocalDate) -> Unit) {
    var showPicker by rememberSaveable { mutableStateOf(false) }
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween) {
        IconButton(onClick = { onDate(date.minusDays(1)) }, modifier = Modifier.testTag("timeline-previous-day")) { RecorderGlyph("back", Modifier.size(18.dp)) }
        TextButton(onClick = { showPicker = true }, modifier = Modifier.testTag("timeline-date")) {
            RecorderGlyph("calendar", Modifier.size(18.dp), RecorderInk)
            Spacer(Modifier.width(8.dp))
            Text(date.format(DateTimeFormatter.ofPattern("yyyy年M月d日")), color = RecorderInk)
        }
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
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun VerticalDayTimeline(index: DayIndex, day: TimelineWindow, zone: ZoneId, enabled: Boolean,
    initialTime: Long, onScrubbing: (Boolean) -> Unit, onSelect: (Long) -> Unit,
    eventFilter: String = "", active: Boolean = true, archive: Boolean = false) {
    val scroll = rememberScrollState()
    val density = LocalDensity.current
    var precise by rememberSaveable { mutableStateOf(false) }
    var anchor by rememberSaveable { mutableLongStateOf(initialTime) }
    var lastCommitted by rememberSaveable { mutableLongStateOf(initialTime) }
    var scrollCommand by remember { mutableIntStateOf(0) }
    var programmatic by remember { mutableStateOf(true) }
    val hourHeight = if (precise) 1440.dp else 800.dp
    val pxPerMs = with(density) { hourHeight.toPx() } / 3_600_000.0
    val totalHeight = hourHeight * ((day.endMs - day.startMs) / 3_600_000f)
    // 概览的一像素可跨数秒；停滑后保留精确选时，不让绘制像素反向量化时间。
    val selected = if (scroll.isScrollInProgress && !programmatic)
        (day.startMs + scroll.value / pxPerMs).toLong().coerceIn(day.startMs, day.endMs - 1)
    else anchor.coerceIn(day.startMs, day.endMs - 1)
    val currentSelect by rememberUpdatedState(onSelect)
    val currentScrubbing by rememberUpdatedState(onScrubbing)
    var initialized by remember { mutableStateOf(false) }
    var expandedGroup by remember { mutableStateOf<List<TimelineMoment>?>(null) }
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
    val accent = RecorderBlue
    val ink = RecorderMuted
    val line = RecorderLine
    val recording = RecorderBlue.copy(alpha = .35f)
    val colors = EventNames.keys.associateWith { eventColor(it) }
    val moments = remember(index, eventFilter) {
        // 旧版服务仍能画出完整色带，明确显示没有截图，不能拿最近 200 条冒充全天。
        (if (index.hasSnapshots) index.moments else index.events.mapIndexed { n, event ->
            TimelineMoment("segment-$n", event.startMs, event.type, null)
        }).filter { eventFilter.isEmpty() || it.type == eventFilter }
    }
    val cardHeight = 74.dp
    val groups = remember(moments, pxPerMs) {
        groupTimelineMoments(moments, (with(density) { (cardHeight + 4.dp).toPx() } / pxPerMs).toLong())
    }
    Column(Modifier.fillMaxSize()) {
        if (!index.hasSnapshots && index.events.isNotEmpty()) Text("主机升级后可查看事件截图", color = RecorderMuted,
            style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(bottom = 4.dp))
        BoxWithConstraints(Modifier.fillMaxWidth().weight(1f).clip(RoundedCornerShape(0.dp))
            .testTag("channel-day-timeline").semantics {
                stateDescription = if (enabled) "已加载" else "加载中"
                progressBarRangeInfo = ProgressBarRangeInfo(day.fractionAt(selected), 0f..1f)
                setProgress { fraction ->
                    if (!enabled || !fraction.isFinite()) false else {
                        programmatic = true; scrollCommand++
                        submit(day.timeAt(fraction)); true
                    }
                }
            }) {
            val half = maxHeight / 2
            val viewportHeight = maxHeight
            val halfPx = with(density) { half.toPx() }
            val cardPx = with(density) { cardHeight.toPx() }
            val visibleStart = day.startMs + (scroll.value - halfPx - cardPx) / pxPerMs
            val visibleEnd = day.startMs + (scroll.value + halfPx + cardPx) / pxPerMs
            // 滚动区只有占位和少量可见卡片；不会为一天的全部截图创建控件或下载图片。
            Box(Modifier.fillMaxSize().verticalScroll(scroll, enabled = enabled)) {
                Spacer(Modifier.fillMaxWidth().height(totalHeight + viewportHeight))
                groups.filter { it.first().timeMs >= visibleStart && it.first().timeMs <= visibleEnd }.forEach { group ->
                    val selectedMoment = group.firstOrNull { archive && kotlin.math.abs(it.timeMs - selected) < 1500 }
                    val moment = selectedMoment ?: group.first()
                    val isSelected = selectedMoment != null
                    key(group.first().id) {
                        Surface(onClick = {
                            if (group.size > 1) expandedGroup = group else {
                                programmatic = true; scrollCommand++; submit(moment.timeMs)
                            }
                        }, enabled = enabled, shape = RoundedCornerShape(8.dp),
                            color = if (isSelected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface,
                            modifier = Modifier.padding(start = 76.dp).fillMaxWidth().height(cardHeight)
                                .offset { IntOffset(0, (halfPx + (group.first().timeMs - day.startMs) * pxPerMs - cardPx / 2).roundToInt()) }
                                .testTag("timeline-event-${moment.id}")) {
                            Row(Modifier.fillMaxSize().padding(7.dp), verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                RemoteImage(moment.snapshotUrl, active && !scroll.isScrollInProgress,
                                    Modifier.width(100.dp).height(56.dp).clip(RoundedCornerShape(5.dp)), ContentScale.Crop)
                                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                                        Text(Instant.ofEpochMilli(moment.timeMs).atZone(zone).format(DateTimeFormatter.ofPattern("HH:mm:ss")),
                                            style = MaterialTheme.typography.labelMedium, color = RecorderInk)
                                        Box(Modifier.size(6.dp).background(colors.getValue(moment.type), CircleShape))
                                    }
                                    Text(when(moment.type) { "dwell" -> "长时间停留"; "person" -> "人员活动"; "vehicle" -> "车辆活动"; else -> "动物活动" },
                                        color = RecorderInk, fontWeight = FontWeight.Medium, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    if (isSelected || group.size > 1) Text(
                                        if (isSelected) "播放中" + if (group.size > 1) " · ${group.size}条事件" else ""
                                        else "附近 ${group.size} 条 · 点击展开",
                                        color = RecorderBlue, style = MaterialTheme.typography.labelSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                }
                            }
                        }
                    }
                }
            }
            // 所有时间刻度仅绘制当前视口；色条只占左侧，不再横穿截图和文字。
            Canvas(Modifier.fillMaxSize()) {
                val left = 56.dp.toPx()
                fun y(time: Long) = (halfPx + (time - day.startMs) * pxPerMs - scroll.value).toFloat()
                val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = ink.toArgb(); textSize = 10.dp.toPx() }
                index.recordings.forEach { r ->
                    val top = y(r.startMs.coerceAtLeast(day.startMs)); val bottom = y(r.endMs.coerceAtMost(day.endMs))
                    if (bottom >= 0 && top <= size.height) drawRect(recording, Offset(left, top.coerceAtLeast(0f)),
                        Size(3.dp.toPx(), (bottom.coerceAtMost(size.height) - top.coerceAtLeast(0f)).coerceAtLeast(1f)))
                }
                index.events.filter { eventFilter.isEmpty() || eventFilter == it.type }.forEach { event ->
                    val top = y(event.startMs); val bottom = y(event.endMs)
                    if (bottom >= 0 && top <= size.height) drawRect(colors.getValue(event.type), Offset(left + 6.dp.toPx(), top.coerceAtLeast(0f)),
                        Size(3.dp.toPx(), (bottom.coerceAtMost(size.height) - top.coerceAtLeast(0f)).coerceAtLeast(3.dp.toPx())))
                }
                val step = 300_000L
                var time = (day.startMs + ((visibleStart - day.startMs).toLong().coerceAtLeast(0) / step) * step)
                while (time <= day.endMs && time <= visibleEnd) {
                    val top = y(time)
                    drawLine(line, Offset(left - 8.dp.toPx(), top), Offset(left, top), 1.dp.toPx())
                    drawContext.canvas.nativeCanvas.drawText(if(time == day.endMs) "24:00" else Instant.ofEpochMilli(time).atZone(zone).format(DateTimeFormatter.ofPattern("HH:mm")),
                        0f, top + 3.dp.toPx(), paint)
                    time += step
                }
                groups.forEach { group ->
                    val top = y(group.first().timeMs)
                    if (top in 0f..size.height) {
                        drawLine(line, Offset(left + 3.dp.toPx(), top), Offset(76.dp.toPx(), top), 1.dp.toPx())
                        drawCircle(colors.getValue(group.first().type), 3.dp.toPx(), Offset(left + 1.5.dp.toPx(), top))
                    }
                }
                drawPath(Path().apply {
                    moveTo(left - 11.dp.toPx(), halfPx - 5.dp.toPx()); lineTo(left - 3.dp.toPx(), halfPx)
                    lineTo(left - 11.dp.toPx(), halfPx + 5.dp.toPx()); close()
                }, accent)
            }
            if (loadedEmpty(index, enabled)) Text("当天暂无录像和事件", Modifier.align(Alignment.Center).padding(start = 70.dp),
                color = RecorderMuted, style = MaterialTheme.typography.bodySmall)
        }
        Row(Modifier.fillMaxWidth().height(44.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(Instant.ofEpochMilli(selected).atZone(zone).format(DateTimeFormatter.ofPattern("HH:mm:ss")),
                    Modifier.testTag("timeline-selected-time"), color = RecorderBlue, style = MaterialTheme.typography.labelMedium)
                Text("上下滑动查看全天录像", color = RecorderMuted, style = MaterialTheme.typography.labelSmall)
            }
            Row(Modifier.background(MaterialTheme.colorScheme.surfaceContainerHigh, RoundedCornerShape(10.dp)).padding(2.dp)) {
                val choiceColors = ButtonDefaults.textButtonColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                    contentColor = RecorderInk, disabledContainerColor = RecorderBlue, disabledContentColor = MaterialTheme.colorScheme.onPrimary)
                TextButton(onClick = { anchor = selected; programmatic = true; precise = false }, enabled = precise,
                    shape = RoundedCornerShape(8.dp), colors = choiceColors, modifier = Modifier.height(36.dp).testTag("timeline-overview")) { Text("概览", style = MaterialTheme.typography.labelSmall) }
                TextButton(onClick = { anchor = selected; programmatic = true; precise = true }, enabled = !precise,
                    shape = RoundedCornerShape(8.dp), colors = choiceColors, modifier = Modifier.height(36.dp).testTag("timeline-precise")) { Text("精细", style = MaterialTheme.typography.labelSmall) }
            }
        }
    }
    expandedGroup?.let { group ->
        ModalBottomSheet(onDismissRequest = { expandedGroup = null }, containerColor = RecorderBackground) {
            Text("附近 ${group.size} 条事件", Modifier.padding(horizontal = 20.dp, vertical = 12.dp), style = MaterialTheme.typography.titleMedium)
            LazyColumn(Modifier.fillMaxWidth().heightIn(max = 420.dp), contentPadding = PaddingValues(12.dp)) {
                items(group, key = { it.id }) { moment ->
                    Surface(onClick = { expandedGroup = null; programmatic = true; scrollCommand++; submit(moment.timeMs) }, color = RecorderBackground) {
                        Row(Modifier.fillMaxWidth().padding(8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            RemoteImage(moment.snapshotUrl, active, Modifier.width(100.dp).height(56.dp).clip(RoundedCornerShape(5.dp)), ContentScale.Crop)
                            Column {
                                Text(Instant.ofEpochMilli(moment.timeMs).atZone(zone).format(DateTimeFormatter.ofPattern("HH:mm:ss")), color = RecorderInk)
                                Text(EventNames.getValue(moment.type), color = eventColor(moment.type), style = MaterialTheme.typography.bodySmall)
                            }
                        }
                    }
                }
            }
        }
    }
}

private fun loadedEmpty(index: DayIndex, enabled: Boolean) = enabled && index.recordings.isEmpty() && index.events.isEmpty() && index.moments.isEmpty()
