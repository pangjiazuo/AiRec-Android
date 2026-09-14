package com.neardi.recorder.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import com.neardi.recorder.data.RecorderApi
import com.neardi.recorder.data.ApiException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import org.json.JSONObject

/** 通道详情只组合预览和历史列表，采集、录像和检测仍由板端独立运行。 */
@Composable
fun ChannelDetailScreen(
    api: RecorderApi,
    channelId: Int,
    channel: JSONObject?,
    connected: Boolean,
    active: Boolean,
    onBack: () -> Unit,
    onSettings: () -> Unit,
    onFullscreen: () -> Unit,
    onVideo: (String, String) -> Unit,
    onImage: (String, String) -> Unit,
    onSeekVideo: (String, String, Long, Long?) -> Unit = { url, title, _, _ -> onVideo(url, title) },
    onDownload: (String, String) -> Unit = { _, _ -> },
) {
    require(channelId in 1..5) { "通道编号应为 1～5" }
    var playing by rememberSaveable { mutableStateOf(true) }
    var speed by rememberSaveable { mutableFloatStateOf(1f) }
    var kind by rememberSaveable(api.baseUrl, channelId) { mutableStateOf("recordings") }
    var dateText by rememberSaveable(api.baseUrl, channelId) { mutableStateOf(LocalDate.now().toString()) }
    val date = LocalDate.parse(dateText)
    val zone = ZoneId.systemDefault()
    val day = RecordingTimeline.day(date, zone)
    var archive by rememberSaveable(api.baseUrl, channelId, dateText) { mutableStateOf(false) }
    var chosenTime by rememberSaveable(api.baseUrl, channelId, dateText) { mutableLongStateOf(System.currentTimeMillis().coerceIn(day.startMs, day.endMs - 1)) }
    var directUrl by rememberSaveable(api.baseUrl, channelId, dateText) { mutableStateOf<String?>(null) }
    var scrubbing by remember { mutableStateOf(false) }
    var seekRequestId by rememberSaveable(api.baseUrl, channelId, dateText) { mutableLongStateOf(0) }
    var archiveStopped by rememberSaveable(api.baseUrl, channelId, dateText) { mutableStateOf(false) }
    var index by remember(api.baseUrl, channelId, dateText) { mutableStateOf(DayIndex(emptyList(), emptyList())) }
    var indexError by remember(api.baseUrl, channelId, dateText) { mutableStateOf<String?>(null) }
    var loaded by remember(api.baseUrl, channelId, dateText) { mutableStateOf(false) }
    var playbackMessage by remember(api.baseUrl, channelId, dateText) { mutableStateOf<String?>(null) }
    LaunchedEffect(api.baseUrl, channelId, dateText, active) {
        scrubbing = false
        if (!active) return@LaunchedEffect
        while (isActive) {
            try {
                index = decodeDayIndex(api.getTimeline(channelId, Instant.ofEpochMilli(day.startMs), Instant.ofEpochMilli(day.endMs)), api, channelId, day)
                loaded = true; indexError = null
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) {
                indexError = if (failure is ApiException && failure.statusCode == 404)
                    "板端尚不支持全天时间轴，请升级板端服务" else failure.message ?: "全天索引加载失败"
                if (failure is ApiException && failure.statusCode == 404) break
            }
            delay(30_000)
        }
    }
    val selection = if (archive && directUrl == null) RecordingTimeline.select(index.recordings, channelId, chosenTime) else null
    fun choose(time: Long) {
        seekRequestId++
        scrubbing = false; archiveStopped = false; directUrl = null; archive = true; chosenTime = time
        playbackMessage = if (RecordingTimeline.select(index.recordings, channelId, time) == null) "所选时间没有可用录像" else null
    }
    val embeddedVideo: (String, String) -> Unit = { url, _ ->
        seekRequestId++
        scrubbing = false; archiveStopped = false; archive = true; directUrl = url; playbackMessage = null
    }
    val historyState = rememberSaveableStateHolder()
    val channelName = channel?.optString("name", "AHD$channelId") ?: "AHD$channelId"
    BoxWithConstraints(Modifier.fillMaxSize().background(RecorderBackground).testTag("channel-detail")) {
        // 手机横屏使用并排布局，避免固定预览把历史列表挤成一条细缝。
        val compactLandscape = (maxWidth > maxHeight && maxHeight < 600.dp)
        val previewHeight = ((maxWidth) * 9f / 16f)
            .coerceAtMost(maxHeight * .36f).coerceAtMost(320.dp).coerceAtLeast(96.dp)
        val landscapePreviewHeight = ((maxWidth * .45f - 32.dp) * 9f / 16f)
            .coerceAtMost((maxHeight - 116.dp).coerceAtLeast(96.dp))
        Column(Modifier.fillMaxSize()) {
            Row(Modifier.fillMaxWidth().heightIn(min = 62.dp).padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onBack, modifier = Modifier.testTag("channel-detail-back").semantics { contentDescription = "返回" }) {
                    RecorderGlyph("back", color = RecorderInk)
                }
                Column(Modifier.weight(1f).padding(horizontal = 8.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(channelName, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold,
                        maxLines = 1, overflow = TextOverflow.Ellipsis)
                    if (!connected) Text("设备离线 · 正在重连", color = RecorderMuted, style = MaterialTheme.typography.labelSmall)
                }
                IconButton(onClick = onSettings, modifier = Modifier.testTag("channel-settings-entry").semantics { contentDescription = "通道设置" }) {
                    RecorderGlyph("settings", color = RecorderInk)
                }
            }
            val historyContent: @Composable () -> Unit = {
                ChannelHistoryTabs(kind, onChange = { kind = it; scrubbing = false }) {
                    if (kind == "recordings") Column(Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
                        DayDateSelector(date) { dateText = it.toString() }
                        indexError?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
                        if (!loaded && indexError == null) Text("正在加载全天索引…", color = RecorderMuted)
                        Box(Modifier.fillMaxWidth().weight(1f)) {
                            key(api.baseUrl, channelId, dateText) {
                                VerticalDayTimeline(index, day, zone, loaded && indexError == null, chosenTime,
                                    onScrubbing = { scrubbing = it }, onSelect = ::choose)
                            }
                        }
                    } else historyState.SaveableStateProvider("${api.baseUrl}:$channelId:$kind") {
                        HistoryScreen(kind, api, active, embeddedVideo, onImage, fixedChannelId = channelId, showHeading = false)
                    }
                }
            }
            // 只改变两个区域的测量位置，保持历史页在同一个组合位置；旋转不会丢失筛选。
            Layout(modifier = Modifier.fillMaxWidth().weight(1f), content = {
                Column(Modifier.padding(top = if (compactLandscape) 8.dp else 0.dp)) {
                    val height = if (compactLandscape) landscapePreviewHeight else previewHeight
                    if (!archive) ChannelPreview(api, channelId, channel, connected, active && playing, height, onFullscreen)
                    else {
                        Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                            Surface(Modifier.widthIn(max = height * 16f / 9f).fillMaxWidth().aspectRatio(16f / 9f),
                                shape = RoundedCornerShape(0.dp), color = Color.Black) {
                                val url = if (archiveStopped) null else directUrl ?: selection?.interval?.url
                                if (url != null) ChannelArchivePlayer(url, selection?.positionMs ?: 0,
                                    selection?.interval?.let { day.endMs - it.startMs }, active, scrubbing, seekRequestId,
                                    Modifier.fillMaxSize(), playing = playing, playbackSpeed = speed, minimumPosition = selection?.interval?.let { (day.startMs - it.startMs).coerceAtLeast(0) } ?: 0) {
                                    val next = selection?.interval?.let { RecordingTimeline.next(index.recordings, it, day) }
                                    if (next != null) choose(next.interval.startMs + next.positionMs)
                                    else {
                                        chosenTime = (selection?.interval?.endMs ?: day.endMs).coerceAtMost(day.endMs - 1)
                                        archiveStopped = true; directUrl = null; playbackMessage = "已播放完毕，后续时间没有连续录像"
                                    }
                                } else Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                    Text(playbackMessage ?: "所选时间没有可用录像", Modifier.padding(16.dp), color = Color.White)
                                }
                            }
                        }
                        Row(Modifier.fillMaxWidth().height(40.dp).padding(horizontal = 18.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text(playbackMessage ?: "录像回放", color = RecorderMuted, style = MaterialTheme.typography.labelSmall)
                        }

                    }
                }
                Box { historyContent() }
            }) { children, constraints ->
                val width = constraints.maxWidth
                val height = constraints.maxHeight
                val previewWidth = if (compactLandscape) (width * .45f).toInt() else width
                val preview = children[0].measure(Constraints(minWidth = previewWidth, maxWidth = previewWidth, maxHeight = height))
                val historyWidth = if (compactLandscape) width - previewWidth else width
                val historyHeight = if (compactLandscape) height else (height - preview.height).coerceAtLeast(0)
                val history = children[1].measure(Constraints.fixed(historyWidth, historyHeight))
                layout(width, height) {
                    preview.placeRelative(0, 0)
                    history.placeRelative(if (compactLandscape) previewWidth else 0,
                        if (compactLandscape) 0 else preview.height)
                }
            }
            if (kind == "recordings") {
                HorizontalDivider(color = RecorderLine)
                Row(Modifier.fillMaxWidth().height(if (compactLandscape) 44.dp else 66.dp).padding(horizontal = 24.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                    ChoiceMenu("速度", listOf("0.5" to "0.5×", "1.0" to "1×", "2.0" to "2×"), speed.toString()) { speed = it.toFloat() }
                    FilledIconButton(onClick = { playing = !playing }, modifier = Modifier.size(44.dp).testTag("channel-play-toggle"), shape = CircleShape,
                        colors = IconButtonDefaults.filledIconButtonColors(containerColor = RecorderInk, contentColor = RecorderBackground)) { Text(if (playing) "Ⅱ" else "▷") }
                    TextButton(onClick = { archive = false; directUrl = null; scrubbing = false; playbackMessage = null; playing = true }, modifier = Modifier.testTag("channel-return-live")) { Text("返回实时", color = RecorderInk) }
                }
            }
        }
    }
}

@Composable
private fun ChannelHistoryTabs(kind: String, onChange: (String) -> Unit, content: @Composable () -> Unit) {
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().height(48.dp).padding(horizontal = 20.dp), verticalAlignment = Alignment.CenterVertically) {
            listOf("recordings" to "回放", "events" to "事件").forEach { (value, label) ->
                Column(Modifier.width(60.dp).fillMaxHeight().selectable(kind == value, onClick = { onChange(value) }).testTag("channel-tab-$value"), verticalArrangement = Arrangement.SpaceBetween) {
                    Text(label, Modifier.padding(top = 14.dp), style = MaterialTheme.typography.titleMedium, color = if(kind == value) RecorderBlue else RecorderMuted)
                    Box(Modifier.width(30.dp).height(2.dp).background(if(kind == value) RecorderBlue else Color.Transparent))
                }
            }
        }
        Box(Modifier.fillMaxWidth().weight(1f).testTag("channel-history")) { content() }
    }
}

@Composable
private fun ChannelPreview(api: RecorderApi, channelId: Int, channel: JSONObject?, connected: Boolean,
                           active: Boolean, height: Dp, onFullscreen: () -> Unit) {
    val online = connected && channel?.optString("state") == "online"
    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
    Surface(Modifier.widthIn(max = height * 16f / 9f).fillMaxWidth().aspectRatio(16f / 9f),
        shape = RoundedCornerShape(0.dp), color = Color.Black) {
        Box {
            CameraFrame(api, channel, channelId, connected, active,
                Modifier.fillMaxSize().testTag("channel-preview-$channelId"))
            // 只有此按钮进入全屏，轻触画面不会误切换导航。
            if (online && channel?.optBoolean("recording") == true) {
                Surface(Modifier.align(Alignment.TopEnd).padding(10.dp), color = Color(0xA618181B), shape = RoundedCornerShape(7.dp)) {
                    Row(Modifier.padding(horizontal = 8.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                        Box(Modifier.size(5.dp).background(Color(0xFFFF453A), CircleShape))
                        Text("录像中", color = Color.White, style = MaterialTheme.typography.labelSmall)
                    }
                }
            }

        }
    }
    }
    Row(Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
        Text(when {
            !connected -> "等待设备连接"
            channel?.optBoolean("enabled", true) == false -> "通道已关闭"
            !online -> "暂无信号"
            else -> "预览 ${channel.metric("preview_fps", " fps")}"
        }, color = RecorderMuted, style = MaterialTheme.typography.labelSmall)
        IconButton(onClick = onFullscreen, modifier = Modifier.size(40.dp).testTag("channel-fullscreen").semantics { contentDescription = "全屏" }) {
            RecorderGlyph("expand", Modifier.size(20.dp), RecorderInk)
        }
    }
}
