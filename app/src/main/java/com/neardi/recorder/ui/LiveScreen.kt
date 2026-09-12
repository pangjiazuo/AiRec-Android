package com.neardi.recorder.ui

import android.graphics.Bitmap
import android.graphics.Paint
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.neardi.recorder.data.RecorderApi
import com.neardi.recorder.media.MediaNetwork
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import org.json.JSONObject
import kotlin.math.min

@Composable fun LiveScreen(api: RecorderApi, status: JSONObject?, connected: Boolean, active: Boolean, onChannelSelected: (Int) -> Unit) {
    val channels = status?.optJSONArray("channels").objects().associateBy { it.optInt("id") }
    var showDevice by rememberSaveable { mutableStateOf(false) }
    val onlineCount = if (connected) channels.values.count { it.optString("state") == "online" } else 0
    val recordingCount = if (connected) channels.values.count { it.optString("state") == "online" && it.optBoolean("recording") } else 0
    BoxWithConstraints(Modifier.fillMaxSize()) {
        // 方案 B：手机双列、宽屏三列，五路画面具有相同权重。
        val columns = if (maxWidth >= 840.dp) 3 else if (androidx.compose.ui.platform.LocalDensity.current.fontScale >= 1.5f) 1 else 2
        LazyVerticalGrid(columns = GridCells.Fixed(columns), modifier = Modifier.fillMaxSize().testTag("live-grid"),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 24.dp + LocalRecorderContentBottomInset.current),
            horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            item(span = { GridItemSpan(maxLineSpan) }) { LiveHeading(status, connected, onlineCount) { showDevice = true } }
            items((1..5).toList(), key = { it }) { id ->
                CameraCard(api, channels[id], id, connected, active, Modifier, onChannelSelected)
            }
            item {
                Surface(shape = RoundedCornerShape(24.dp), color = MaterialTheme.colorScheme.surface) {
                    Column {
                        Column(Modifier.fillMaxWidth().aspectRatio(16f / 9f).padding(12.dp),
                            verticalArrangement = Arrangement.SpaceEvenly) {
                            listOf("通道总数" to 5, "在线通道" to onlineCount, "正在录像" to recordingCount).forEach { (label, value) ->
                                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically) {
                                    Text(label, color = RecorderMuted, style = MaterialTheme.typography.labelSmall)
                                    Text(value.toString(), color = RecorderInk, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                                }
                            }
                        }
                        Row(Modifier.fillMaxWidth().heightIn(min = 52.dp).padding(horizontal = 14.dp, vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            Box(Modifier.size(6.dp).background(if (connected) RecorderGreen else RecorderMuted, CircleShape))
                            Text(if (connected) "设备已连接" else "正在重连", color = RecorderMuted, style = MaterialTheme.typography.labelSmall)
                        }
                    }
                }
            }
        }
    }
    if (showDevice) AlertDialog(onDismissRequest = { showDevice = false }, title = { Text("设备信息") },
        text = { DevicePanel(status, connected) },
        confirmButton = { TextButton(onClick = { showDevice = false }) { Text("完成") } })
}

@OptIn(ExperimentalLayoutApi::class)
@Composable private fun LiveHeading(status: JSONObject?, connected: Boolean, onlineCount: Int, onDevice: () -> Unit) {
    val system = if (connected) status?.optJSONObject("system") else null
    val memory = system?.optJSONObject("memory")
    val storage = if (connected) status?.optJSONObject("storage") else null
    val total = storage?.optDouble("total_bytes", Double.NaN) ?: Double.NaN
    val free = storage?.optDouble("free_bytes", Double.NaN) ?: Double.NaN
    val used = if (total > 0 && free.isFinite()) "%.0f%%".format(((total - free) / total * 100).coerceIn(0.0,100.0)) else "—"
    Column(Modifier.padding(bottom = 6.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Text("录像机", style = MaterialTheme.typography.headlineMedium, color = RecorderInk)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Text("$onlineCount / 5 路在线", color = RecorderMuted, style = MaterialTheme.typography.bodyMedium)
            TextButton(onClick = onDevice, contentPadding = PaddingValues(horizontal = 8.dp)) {
                Text("设备信息")
                Spacer(Modifier.width(6.dp))
                RecorderGlyph("info", Modifier.size(18.dp), RecorderBlue)
            }
        }
        Surface(onClick = onDevice, modifier = Modifier.fillMaxWidth().testTag("device-info-entry"),
            shape = RoundedCornerShape(24.dp), color = MaterialTheme.colorScheme.surface) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(22.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    listOf("温度" to system.metric("temperature_c", "°C"), "CPU" to system.metric("cpu_percent", "%"),
                        "存储已用" to used).forEach { (label, value) ->
                        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text(label, color = RecorderMuted, style = MaterialTheme.typography.bodySmall)
                            Text(value, color = RecorderInk, style = MaterialTheme.typography.titleMedium)
                        }
                    }
                }
                Text("内存 ${bytesText(memory?.optDouble("used_bytes", Double.NaN) ?: Double.NaN)} / ${bytesText(memory?.optDouble("total_bytes", Double.NaN) ?: Double.NaN)}",
                    color = RecorderMuted, style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

@Composable private fun CameraCard(api: RecorderApi, channel: JSONObject?, id: Int, connected: Boolean, active: Boolean,
                                  modifier: Modifier, onSelect: (Int) -> Unit) {
    val online = connected && channel?.optString("state") == "online"
    Surface(modifier, shape = RoundedCornerShape(24.dp), color = MaterialTheme.colorScheme.surface) {
        Column {
            CameraFrame(api, channel, id, connected, active, Modifier.fillMaxWidth().aspectRatio(16f / 9f)
                .clickable { onSelect(id) }.testTag("camera-$id"))
            Row(Modifier.fillMaxWidth().clickable { onSelect(id) }.heightIn(min = 52.dp)
                .padding(horizontal = 14.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Box(Modifier.size(6.dp).background(if (online) RecorderGreen else RecorderMuted, CircleShape))
                Text(channel?.optString("name", "AHD$id")?.takeIf(String::isNotBlank) ?: "AHD$id", Modifier.weight(1f),
                    color = RecorderInk, style = MaterialTheme.typography.titleSmall,
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (online && channel?.optBoolean("recording") == true) Text("REC", color = Color(0xFFFF6659),
                    style = MaterialTheme.typography.labelSmall)
            }
        }
    }
}

@Composable fun DevicePanel(status: JSONObject?, connected: Boolean) {
    val system = if (connected) status?.optJSONObject("system") else null
    val memory = system?.optJSONObject("memory")
    val storage = if (connected) status?.optJSONObject("storage") else null
    Surface(Modifier.fillMaxWidth(), color = MaterialTheme.colorScheme.surfaceVariant, shape = RoundedCornerShape(12.dp)) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            val detector = status?.optJSONObject("detector")
            Text(if (connected) "${system?.optString("hostname", "RK3399PRO")} · ${detector?.optString("model", "YOLO") ?: "YOLO"}"
                else "设备状态", color = RecorderMuted, style = MaterialTheme.typography.labelSmall,
                maxLines = 1, overflow = TextOverflow.Ellipsis)
            val metrics = listOf("温度" to system.metric("temperature_c", " °C"), "CPU" to system.metric("cpu_percent", " %"),
                "内存" to memory.metric("used_percent", " %"), "可用存储" to bytesText(storage?.optDouble("free_bytes", Double.NaN) ?: Double.NaN))
            metrics.chunked(2).forEach { pair ->
              Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                pair.forEach { (name, value) -> Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    Text(name, style = MaterialTheme.typography.labelSmall, color = RecorderMuted)
                    Text(value, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Medium,
                        maxLines = 1, overflow = TextOverflow.Ellipsis)
                } }
              }
            }
            Text(if (connected) "${storage?.optString("label", "存储") ?: "存储"} · ${if (detector?.optBoolean("ready") == true) "识别运行中" else "识别准备中"}"
                else "等待设备连接", color = RecorderMuted, style = MaterialTheme.typography.labelSmall)
        }
    }
}

@Composable fun CameraFrame(api: RecorderApi, channel: JSONObject?, id: Int, connected: Boolean, active: Boolean, modifier: Modifier = Modifier) {
    var bitmap by remember(api.baseUrl, id) { mutableStateOf<Bitmap?>(null) }
    var message by remember(api.baseUrl, id) { mutableStateOf("连接画面…") }
    val online = connected && channel?.optString("state") == "online"
    LaunchedEffect(api.baseUrl, id, online, active) {
        bitmap = null
        if (!online || !active) return@LaunchedEffect
        var retry = 1L
        while (isActive) {
            try {
                message = "连接画面…"
                MediaNetwork.stream(api.mediaUrl("/stream/$id.mjpg")) { frame ->
                    withContext(Dispatchers.Main) { bitmap = frame; message = "" }
                    retry = 1L
                }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) { bitmap = null; message = "${error.message ?: "连接断开"}，自动重连中" }
            delay(retry * 1000)
            retry = (retry * 2).coerceAtMost(10)
        }
    }
    Box(modifier.background(if (online) Color(0xFF121214) else MaterialTheme.colorScheme.surfaceVariant), contentAlignment = Alignment.Center) {
        val frame = bitmap.takeIf { online && active }
        if (frame != null) {
            Image(frame.asImageBitmap(), "AHD${id}实时画面", Modifier.fillMaxSize(), contentScale = ContentScale.Fit)
            DetectionOverlay(channel, frame.width, frame.height)
        } else {
            Column(Modifier.padding(8.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(7.dp)) {
                RecorderGlyph("camera-off", Modifier.size(36.dp), Color(0xFFA5A5AC))
                Text(when { !connected -> "等待设备连接"; channel?.optBoolean("enabled", true) == false -> "通道已关闭"
                    !online -> "暂无信号"; !active -> "预览已暂停"; else -> message }, color = if (online) Color(0xFFADADB5) else RecorderMuted,
                    style = MaterialTheme.typography.labelSmall)
            }
        }
    }
}


@Composable private fun DetectionOverlay(channel: JSONObject?, imageWidth: Int, imageHeight: Int) {
    val detections = channel?.optJSONArray("detections").objects()
    Canvas(Modifier.fillMaxSize()) {
        val scale = min(size.width / imageWidth, size.height / imageHeight)
        val width = imageWidth * scale
        val height = imageHeight * scale
        val left = (size.width - width) / 2
        val top = (size.height - height) / 2
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { textSize = 11.dp.toPx() }
        for (item in detections) {
            val bbox = item.optJSONArray("bbox") ?: continue
            if (bbox.length() != 4) continue
            val values = (0..3).map { bbox.optDouble(it, Double.NaN).toFloat() }
            if (values.any { !it.isFinite() }) continue
            val x1 = left + values[0].coerceIn(0f, 1f) * width
            val y1 = top + values[1].coerceIn(0f, 1f) * height
            val x2 = left + values[2].coerceIn(0f, 1f) * width
            val y2 = top + values[3].coerceIn(0f, 1f) * height
            if (x2 <= x1 || y2 <= y1) continue
            val category = item.optString("category")
            val type = if (item.optBoolean("dwell_reached")) "dwell" else category
            val color = eventColor(type)
            drawRect(color, androidx.compose.ui.geometry.Offset(x1, y1), androidx.compose.ui.geometry.Size(x2 - x1, y2 - y1), style = Stroke(2.dp.toPx()))
            var label = EventNames[category] ?: "目标"
            if (category in listOf("person", "animal")) label += " ${item.metric("dwell_seconds", "秒")}"
            if (type == "dwell") label += " · 停留"
            paint.color = color.toArgb()
            drawContext.canvas.nativeCanvas.drawText(label, x1 + 3.dp.toPx(), (y1 + 14.dp.toPx()).coerceAtMost(size.height - 2.dp.toPx()), paint)
        }
    }
}
