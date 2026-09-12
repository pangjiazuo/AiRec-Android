package com.neardi.recorder.ui

import android.graphics.Bitmap
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.neardi.recorder.data.RecorderApi
import com.neardi.recorder.media.MediaNetwork
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import org.json.JSONObject
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

@Composable
fun HistoryScreen(kind: String, api: RecorderApi, active: Boolean, onVideo: (String, String) -> Unit,
    onImage: (String, String) -> Unit, fixedChannelId: Int? = null, showHeading: Boolean = true,
    onSeekVideo: (String, String, Long, Long?) -> Unit = { url, title, _, _ -> onVideo(url, title) },
    onDownload: (String, String) -> Unit = { _, _ -> }) {
    require(fixedChannelId == null || fixedChannelId in 1..5) { "通道编号应为 1～5" }
    var channel by rememberSaveable(api.baseUrl, kind, fixedChannelId) { mutableStateOf("") }
    var type by rememberSaveable(api.baseUrl, kind, fixedChannelId) { mutableStateOf("") }
    var date by rememberSaveable(api.baseUrl, kind, fixedChannelId) { mutableStateOf("") }
    val effectiveChannel = fixedChannelId?.toString() ?: channel
    var refresh by remember { mutableIntStateOf(0) }
    var records by remember(api.baseUrl, kind, fixedChannelId) { mutableStateOf<List<JSONObject>>(emptyList()) }
    var error by remember(api.baseUrl, kind, fixedChannelId) { mutableStateOf<String?>(null) }
    var loading by remember(api.baseUrl, kind, fixedChannelId) { mutableStateOf(true) }
    val zone = ZoneId.systemDefault()
    val bottomInset = LocalRecorderContentBottomInset.current
    LaunchedEffect(api.baseUrl, kind, effectiveChannel, type, refresh, active) {
        if (!active) return@LaunchedEffect
        records = emptyList()
        loading = true
        while (isActive) {
            try {
                val params = buildList {
                    if (effectiveChannel.isNotBlank()) add("channel_id=$effectiveChannel")
                    if (kind == "events" && type.isNotBlank()) add("event_type=$type")
                }
                val response = api.getJson("/api/$kind" + if (params.isEmpty()) "" else "?" + params.joinToString("&"))
                records = response.optJSONArray("items").objects()
                error = null
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) { error = failure.message ?: "连接失败，正在重试" }
            loading = false
            delay(5000)
        }
    }
    val intervals = remember(records, api.baseUrl) {
        if (kind != "recordings") emptyList() else records.mapNotNull { item ->
            val url = runCatching { api.mediaUrl(item.optString("url")) }.getOrNull() ?: return@mapNotNull null
            RecordingTimeline.interval(item.optString("id"), item.optInt("channel_id"), item.optString("created_at"),
                item.optDouble("duration_seconds", Double.NaN), item.optBoolean("available"), url, recordingTitle(item))
        }
    }
    val dates = remember(records, intervals, zone) {
        (records.mapNotNull { RecordingTimeline.parseStart(it.optString("created_at"))?.let { ms -> Instant.ofEpochMilli(ms).atZone(zone).toLocalDate() } } +
            intervals.flatMap { RecordingTimeline.dates(it, zone) }).distinct().sortedDescending()
    }
    val chosenDate = runCatching { LocalDate.parse(date) }.getOrNull() ?: dates.firstOrNull()
    val shown = if (kind != "recordings" || chosenDate == null) records else records.filter { item ->
        val interval = intervals.firstOrNull { it.id == item.optString("id") }
        if (interval != null) RecordingTimeline.overlapsDay(interval, chosenDate, zone)
        else RecordingTimeline.parseStart(item.optString("created_at"))?.let { Instant.ofEpochMilli(it).atZone(zone).toLocalDate() == chosenDate } ?: true
    }
    BoxWithConstraints(Modifier.fillMaxSize().background(RecorderBackground)) {
        val columns = if (kind == "events") { if (maxWidth >= 1100.dp) 3 else if (maxWidth >= 650.dp) 2 else 1 }
            else if (maxWidth >= 850.dp) 2 else 1
        LazyVerticalGrid(GridCells.Fixed(columns), contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 24.dp + bottomInset),
            horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            if (showHeading) item(span = { GridItemSpan(maxLineSpan) }) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.weight(1f)) { PageHeading(if (kind == "events") "智能事件" else "录像回放", "") }
                    if (fixedChannelId == null) ChoiceMenu("通道", listOf("" to "全部") + (1..5).map { "$it" to "AHD$it" }, channel) {
                        channel = it; date = ""
                    }
                }
            }
            if (kind == "events") item(span = { GridItemSpan(maxLineSpan) }) {
                EventFilters(type, onChange = { type = it })
            }
            if (kind == "recordings" && chosenDate != null) item(span = { GridItemSpan(maxLineSpan) }) {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    RecordingDateSelector(chosenDate, dates, onChange = { date = it.toString() })
                }
            }
            error?.let { message -> item(span = { GridItemSpan(maxLineSpan) }) {
                Column {
                    MessageCard("$message · 每5秒自动重试")
                    TextButton(onClick = { refresh++ }) { Text("立即重试") }
                }
            } }
            if (shown.isEmpty()) item(span = { GridItemSpan(maxLineSpan) }) {
                SectionCard { Text(if (loading) "正在加载…" else if (kind == "events") "暂无符合筛选条件的事件" else "暂无已完成录像，请等待片段保存", color = RecorderMuted) }
            }
            items(shown, key = { it.optString("id") }) { item ->
                if (kind == "events") EventCard(api, item, active, onVideo, onImage)
                else RecordingCard(api, item, onSeekVideo, onDownload)
            }
            if (records.isNotEmpty()) item(span = { GridItemSpan(maxLineSpan) }) {
                Text("最近 ${records.size.coerceAtMost(200)} 条${if (records.size >= 200) " · 最多载入 200 条" else ""}",
                    color = RecorderMuted, style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(top = 4.dp))
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable private fun EventFilters(selected: String, onChange: (String) -> Unit) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(7.dp), verticalArrangement = Arrangement.spacedBy(0.dp)) {
        (listOf("" to "全部") + EventNames.toList()).forEach { (value, name) ->
            FilterChip(selected = selected == value, onClick = { onChange(value) }, label = {
                Text(name, style = MaterialTheme.typography.labelMedium)
            }, modifier = Modifier.testTag("event-filter-${value.ifBlank { "all" }}"), shape = RoundedCornerShape(8.dp),
                colors = FilterChipDefaults.filterChipColors(containerColor = MaterialTheme.colorScheme.surface,
                    selectedContainerColor = MaterialTheme.colorScheme.primaryContainer, selectedLabelColor = RecorderBlue, labelColor = RecorderInk),
                border = FilterChipDefaults.filterChipBorder(enabled = true, selected = selected == value,
                    borderColor = RecorderLine, selectedBorderColor = RecorderBlue.copy(alpha = .65f)))
        }
    }
}

@Composable private fun RecordingDateSelector(selected: LocalDate, dates: List<LocalDate>, onChange: (LocalDate) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    val index = dates.indexOf(selected)
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
        IconButton(onClick = { dates.getOrNull(index + 1)?.let(onChange) }, enabled = index >= 0 && index < dates.lastIndex,
            modifier = Modifier.testTag("recording-date-previous").semantics { contentDescription = "前一个有录像的日期" }) {
            RecorderGlyph("back", modifier = Modifier.size(19.dp))
        }
        Box {
            OutlinedButton(onClick = { expanded = true }, modifier = Modifier.testTag("recording-date"),
                shape = RoundedCornerShape(12.dp), border = BorderStroke(.75.dp, RecorderLine),
                colors = ButtonDefaults.outlinedButtonColors(containerColor = MaterialTheme.colorScheme.surface, contentColor = RecorderInk)) {
                Text(selected.format(DateTimeFormatter.ofPattern("M月d日  E", Locale.CHINESE)))
                Spacer(Modifier.width(9.dp))
                RecorderGlyph("chevron", modifier = Modifier.size(14.dp).rotate(90f))
            }
            DropdownMenu(expanded, onDismissRequest = { expanded = false }) {
                dates.forEach { day -> DropdownMenuItem(text = { Text(day.format(DateTimeFormatter.ofPattern("yyyy年M月d日  E", Locale.CHINESE))) },
                    onClick = { onChange(day); expanded = false }) }
            }
        }
        IconButton(onClick = { dates.getOrNull(index - 1)?.let(onChange) }, enabled = index > 0,
            modifier = Modifier.testTag("recording-date-next").semantics { contentDescription = "后一个有录像的日期" }) {
            RecorderGlyph("chevron", modifier = Modifier.size(19.dp))
        }
    }
}

private fun recordingTitle(item: JSONObject) = "AHD${item.optInt("channel_id")} · ${dateText(item.optString("created_at"))}"

@Composable private fun RecordingCard(api: RecorderApi, item: JSONObject, onSeekVideo: (String, String, Long, Long?) -> Unit, onDownload: (String, String) -> Unit) {
    val url = runCatching { api.mediaUrl(item.optString("url")) }.getOrNull()
    val available = item.optBoolean("available", false) && url != null
    val title = recordingTitle(item)
    Surface(shape = RoundedCornerShape(12.dp), color = MaterialTheme.colorScheme.surface) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                Surface(color = MaterialTheme.colorScheme.primaryContainer, shape = RoundedCornerShape(7.dp)) {
                    Text("AHD${item.optInt("channel_id")}", Modifier.padding(horizontal = 7.dp, vertical = 5.dp), color = RecorderBlue, style = MaterialTheme.typography.labelSmall)
                }
                Text(dateText(item.optString("created_at")), Modifier.weight(1f), color = RecorderInk, style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text("MP4", color = RecorderMuted, style = MaterialTheme.typography.labelSmall)
            }
            Text(item.optString("name"), color = RecorderMuted, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(item.metric("duration_seconds", " 秒"), color = RecorderMuted, style = MaterialTheme.typography.labelMedium)
                Text(bytesText(item.optDouble("size_bytes", Double.NaN)), color = RecorderMuted, style = MaterialTheme.typography.labelMedium)
            }
            if (!available) Text(item.optString("error").ifBlank { "录像介质未连接或文件不可用" }, color = RecorderMuted, style = MaterialTheme.typography.bodySmall)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = { url?.let { onSeekVideo(it, title, 0, RecordingTimeline.parseStart(item.optString("created_at"))) } },
                    enabled = available, contentPadding = PaddingValues(horizontal = 0.dp)) {
                    PlayGlyph(if (available) RecorderBlue else RecorderMuted.copy(alpha = .5f)); Spacer(Modifier.width(7.dp)); Text("播放录像")
                }
                TextButton(onClick = { url?.let { onDownload(it, item.optString("name").ifBlank { "AHD${item.optInt("channel_id")}.mp4" }) } },
                    enabled = available, modifier = Modifier.testTag("recording-download-${item.optString("id")}")) {
                    RecorderGlyph("download", modifier = Modifier.size(19.dp)); Spacer(Modifier.width(5.dp)); Text("下载")
                }
            }
        }
    }
}

@Composable private fun EventCard(api: RecorderApi, item: JSONObject, active: Boolean, onVideo: (String, String) -> Unit, onImage: (String, String) -> Unit) {
    val type = item.optString("event_type", "dwell").takeIf { it in EventNames } ?: "dwell"
    val title = if (type == "dwell") "长时间停留" else "${EventNames[type]}出现"
    val caption = "AHD${item.optInt("channel_id")} · ${dateText(item.optString("created_at"))}"
    val imageUrl = runCatching { api.mediaUrl(item.optString("snapshot_url")) }.getOrNull()
    val videoUrl = if (item.optBoolean("recording_available", false)) runCatching { api.mediaUrl(item.optString("recording_url")) }.getOrNull() else null
    Surface(shape = RoundedCornerShape(10.dp), color = MaterialTheme.colorScheme.surface) {
        Column {
            RemoteImage(imageUrl, active, Modifier.fillMaxWidth().aspectRatio(16f / 9f).clickable(enabled = imageUrl != null) {
                imageUrl?.let { onImage(it, "$title · $caption") }
            }, contentScale = ContentScale.Crop)
            Column(Modifier.padding(horizontal = 11.dp, vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(7.dp).background(eventColor(type), CircleShape))
                    Text(title, Modifier.weight(1f), color = RecorderInk, fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.titleSmall,
                        maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(shortEventTime(item.optString("created_at")), color = RecorderMuted, style = MaterialTheme.typography.labelSmall)
                }
                Text(caption, color = RecorderMuted, style = MaterialTheme.typography.labelSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (type == "dwell") Text("${EventNames[item.optString("category")] ?: "目标"} · 停留 ${item.metric("dwell_seconds", " 秒")}",
                    color = RecorderMuted, style = MaterialTheme.typography.labelSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (videoUrl != null) TextButton(onClick = { onVideo(videoUrl, "$title · $caption") },
                    modifier = Modifier.heightIn(min = 44.dp), contentPadding = PaddingValues(horizontal = 0.dp, vertical = 0.dp)) {
                    PlayGlyph(RecorderBlue); Spacer(Modifier.width(5.dp)); Text("查看关联录像", style = MaterialTheme.typography.labelMedium)
                }
                else Text(if (item.has("recording_url")) "录像已清理或介质离线" else "等待录像片段保存", color = RecorderMuted,
                    style = MaterialTheme.typography.labelSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

@Composable fun RemoteImage(url: String?, active: Boolean, modifier: Modifier = Modifier, contentScale: ContentScale = ContentScale.Fit) {
    var bitmap by remember(url) { mutableStateOf<Bitmap?>(null) }
    var message by remember(url) { mutableStateOf("截图加载中…") }
    LaunchedEffect(url, active) {
        if (url == null || !active || bitmap != null) return@LaunchedEffect
        while (isActive) {
            try { bitmap = MediaNetwork.image(url); break }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) { message = failure.message ?: "截图不可用"; delay(10000) }
        }
    }
    Box(modifier.background(MaterialTheme.colorScheme.surfaceVariant), contentAlignment = Alignment.Center) {
        bitmap?.let { Image(it.asImageBitmap(), "事件截图", Modifier.fillMaxSize(), contentScale = contentScale) }
            ?: Text(if (url == null) "暂无截图" else message, Modifier.padding(12.dp), color = RecorderMuted, style = MaterialTheme.typography.bodySmall)
    }
}

private fun shortEventTime(createdAt: String): String = RecordingTimeline.parseStart(createdAt)?.let {
    Instant.ofEpochMilli(it).atZone(ZoneId.systemDefault()).format(DateTimeFormatter.ofPattern("HH:mm"))
} ?: "—"

@Composable private fun PlayGlyph(color: Color) {
    Canvas(Modifier.size(12.dp)) {
        drawPath(Path().apply { moveTo(size.width * .25f, size.height * .14f); lineTo(size.width * .86f, size.height * .5f)
            lineTo(size.width * .25f, size.height * .86f); close() }, color)
    }
}
