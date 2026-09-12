package com.neardi.recorder

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.neardi.recorder.data.RecorderViewModel
import com.neardi.recorder.data.AppearanceMode
import com.neardi.recorder.data.AppearanceViewModel
import com.neardi.recorder.media.LogDownloadViewModel
import com.neardi.recorder.ui.*
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // 系统栏先透明；实际文字颜色由用户选择的主题与全屏状态共同决定。
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.light(android.graphics.Color.TRANSPARENT, android.graphics.Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.light(android.graphics.Color.TRANSPARENT, android.graphics.Color.rgb(23, 25, 28)),
        )
        setContent {
            val appearance: AppearanceViewModel = viewModel()
            val mode by appearance.mode.collectAsStateWithLifecycle()
            val darkTheme = mode == AppearanceMode.DARK || (mode == AppearanceMode.SYSTEM && isSystemInDarkTheme())
            RecorderTheme(darkTheme) {
                Box(Modifier.fillMaxSize().testTag("recorder-theme")
                    .semantics { stateDescription = if (darkTheme) "dark" else "light" }) {
                    RecorderApp(darkTheme = darkTheme)
                }
            }
        }
    }

    @Composable private fun RecorderApp(darkTheme: Boolean, recorder: RecorderViewModel = viewModel(), downloads: LogDownloadViewModel = viewModel()) {
        val state by recorder.state.collectAsStateWithLifecycle()
        val download by downloads.state.collectAsStateWithLifecycle()
        val owner = LocalLifecycleOwner.current
        var active by remember { mutableStateOf(owner.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) }
        DisposableEffect(owner, recorder) {
            val observer = LifecycleEventObserver { _, event ->
                if (event == Lifecycle.Event.ON_START) { active = true; recorder.start() }
                if (event == Lifecycle.Event.ON_STOP) { active = false; recorder.stop() }
            }
            owner.lifecycle.addObserver(observer)
            if (active) recorder.start()
            onDispose { owner.lifecycle.removeObserver(observer); recorder.stop() }
        }
        var page by rememberSaveable { mutableIntStateOf(0) }
        var detailChannel by rememberSaveable(state.endpoint) { mutableIntStateOf(0) }
        var channelSettings by rememberSaveable(state.endpoint) { mutableStateOf(false) }
        var fullscreen by rememberSaveable(state.endpoint) { mutableIntStateOf(0) }
        var mediaUrl by rememberSaveable(state.endpoint) { mutableStateOf<String?>(null) }
        var mediaTitle by rememberSaveable(state.endpoint) { mutableStateOf("") }
        var mediaKind by rememberSaveable(state.endpoint) { mutableStateOf("video") }
        var mediaPosition by rememberSaveable(state.endpoint) { mutableLongStateOf(0) }
        var mediaRecordedAt by rememberSaveable(state.endpoint) { mutableStateOf<Long?>(null) }
        var mediaFullscreen by rememberSaveable(state.endpoint) { mutableStateOf(false) }
        var pendingDownload by rememberSaveable { mutableStateOf<String?>(null) }
        var pendingVideoDownload by rememberSaveable { mutableStateOf<String?>(null) }
        // 全屏/回放临时移走页面时，仍保留筛选与未提交的设置草稿。
        val stateHolder = rememberSaveableStateHolder()
        val launcher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) { uri ->
            val url = pendingDownload
            pendingDownload = null
            if (uri != null && url != null) downloads.download(url, uri)
        }
        val downloadLogs: () -> Unit = {
            if (!download.busy) {
                pendingDownload = recorder.api.mediaUrl("/api/logs/download")
                launcher.launch("smart-recorder-${LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss"))}.zip")
            }
        }
        val videoLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("video/mp4")) { uri ->
            val url = pendingVideoDownload
            pendingVideoDownload = null
            if (uri != null && url != null) downloads.download(url, uri, "录像", 2L * 1024 * 1024 * 1024)
        }
        val downloadVideo: (String, String) -> Unit = { url, title ->
            if (!download.busy && pendingVideoDownload == null) {
                pendingVideoDownload = url
                val safeTitle = title.replace(Regex("[\\\\/:*?\"<>|\\p{Cntrl}]"), "-").take(90)
                val filename = safeTitle.ifBlank { "smart-recorder" }
                videoLauncher.launch(if (filename.endsWith(".mp4", ignoreCase = true)) filename else "$filename.mp4")
            }
        }
        val immersive = fullscreen != 0 || (mediaUrl != null && (mediaKind == "image" || mediaFullscreen))
        DisposableEffect(immersive, darkTheme) {
            val controller = WindowCompat.getInsetsController(window, window.decorView)
            controller.isAppearanceLightStatusBars = !immersive && !darkTheme
            controller.isAppearanceLightNavigationBars = !immersive && !darkTheme
            if (immersive) {
                controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
                controller.hide(WindowInsetsCompat.Type.systemBars())
            } else controller.show(WindowInsetsCompat.Type.systemBars())
            onDispose { controller.show(WindowInsetsCompat.Type.systemBars()) }
        }
        BackHandler(mediaUrl != null || immersive || channelSettings || detailChannel != 0 || page != 0) {
            when {
                mediaUrl != null && mediaFullscreen -> mediaFullscreen = false
                mediaUrl != null -> mediaUrl = null
                fullscreen != 0 -> fullscreen = 0
                channelSettings -> channelSettings = false
                detailChannel != 0 -> detailChannel = 0
                else -> page = 0
            }
        }
        val openVideo: (String, String) -> Unit = { url, title ->
            mediaPosition = 0; mediaRecordedAt = null; mediaFullscreen = false
            mediaKind = "video"; mediaTitle = title; mediaUrl = url
        }
        val openVideoAt: (String, String, Long, Long?) -> Unit = { url, title, position, recordedAt ->
            mediaPosition = position.coerceAtLeast(0); mediaRecordedAt = recordedAt; mediaFullscreen = false
            mediaKind = "video"; mediaTitle = title; mediaUrl = url
        }
        val openImage: (String, String) -> Unit = { url, title -> mediaKind = "image"; mediaTitle = title; mediaUrl = url }
        if (mediaUrl != null) {
            if (mediaKind == "video") RecorderNavigationFrame(page, onNavigate = {
                mediaUrl = null; mediaFullscreen = false; detailChannel = 0; channelSettings = false; page = it
            }, showNavigation = false) {
            Box(Modifier.fillMaxSize()) {
                PlaybackScreen(mediaUrl!!, mediaTitle, active, initialPositionMs = mediaPosition,
                    recordedAtEpochMs = mediaRecordedAt, fullscreen = mediaFullscreen,
                    onFullscreenChange = { mediaFullscreen = it },
                    onDownload = { downloadVideo(mediaUrl!!, mediaTitle) }) { mediaUrl = null; mediaFullscreen = false }
                if (download.message != null) DownloadNotice(download.message!!, download.busy, downloads::clearMessage,
                    Modifier.align(Alignment.BottomCenter).safeDrawingPadding().padding(16.dp))
            }
            }
            else Column(Modifier.fillMaxSize().background(Color.Black).safeDrawingPadding()) {
                Row(Modifier.fillMaxWidth().padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(mediaTitle, Modifier.weight(1f), color = Color.White, style = MaterialTheme.typography.titleSmall)
                    TextButton(onClick = { mediaUrl = null }) { Text("关闭", color = Color.White) }
                }
                RemoteImage(mediaUrl, active, Modifier.fillMaxWidth().weight(1f))
            }
            return
        }
        if (fullscreen != 0) {
            val channel = state.status?.optJSONArray("channels").objects().firstOrNull { it.optInt("id") == fullscreen }
            Box(Modifier.fillMaxSize().background(Color.Black)) {
                CameraFrame(recorder.api, channel, fullscreen, state.connected, active, Modifier.fillMaxSize())
                Row(Modifier.fillMaxWidth().safeDrawingPadding().padding(12.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                    Surface(color = Color(0xB311151D), shape = MaterialTheme.shapes.medium) {
                        Text(channel?.optString("name") ?: "AHD$fullscreen", Modifier.padding(12.dp), color = Color.White)
                    }
                    FilledTonalButton(onClick = { fullscreen = 0 }) { Text("退出全屏") }
                }
            }
            return
        }
        if (detailChannel in 1..5) {
            val channel = state.status?.optJSONArray("channels").objects().firstOrNull { it.optInt("id") == detailChannel }
                ?: state.config?.optJSONArray("channels").objects().firstOrNull { it.optInt("id") == detailChannel }
            RecorderNavigationFrame(page, onNavigate = { detailChannel = 0; channelSettings = false; page = it },
                modifier = Modifier.imePadding(), showNavigation = false) {
            Box(Modifier.fillMaxSize()) {
                val detailRoute = if (channelSettings) "channel-settings" else "channel-detail"
                stateHolder.SaveableStateProvider("${state.endpoint}:$detailRoute:$detailChannel") {
                    if (channelSettings) {
                        ChannelSettingsScreen(recorder, detailChannel, onBack = { channelSettings = false })
                    } else {
                        ChannelDetailScreen(recorder.api, detailChannel, channel, state.connected, active,
                            onBack = { detailChannel = 0 }, onSettings = { channelSettings = true },
                            onFullscreen = { fullscreen = detailChannel }, onVideo = openVideo, onImage = openImage,
                            onSeekVideo = openVideoAt, onDownload = downloadVideo)
                    }
                }
                if (download.message != null) DownloadNotice(download.message!!, download.busy, downloads::clearMessage,
                    Modifier.align(Alignment.BottomCenter).padding(16.dp))
            }
            }
            return
        }
        RecorderNavigationFrame(page, onNavigate = { page = it }, modifier = Modifier.imePadding()) {
            Column(Modifier.fillMaxSize()) {
                if (!state.connected) MessageCard(state.error?.let { "$it · 自动重连中，可在设置中修改地址" } ?: "正在连接开发板…",
                    Modifier.padding(horizontal = 16.dp, vertical = 8.dp).testTag("connection-status")
                        .semantics { stateDescription = "连接中" })
                if (download.message != null) Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(download.message!!, Modifier.weight(1f), color = RecorderMuted, style = MaterialTheme.typography.bodySmall)
                    if (!download.busy) TextButton(onClick = downloads::clearMessage) { Text("知道了") }
                }
                Box(Modifier.weight(1f)) {
                    key(state.endpoint) { stateHolder.SaveableStateProvider("${state.endpoint}:$page") {
                        when (page) {
                            0 -> LiveScreen(recorder.api, state.status, state.connected, active) {
                                detailChannel = it
                                channelSettings = false
                            }
                            1 -> HistoryScreen("recordings", recorder.api, active, openVideo, openImage,
                                onSeekVideo = openVideoAt, onDownload = downloadVideo)
                            2 -> HistoryScreen("events", recorder.api, active, openVideo, openImage,
                                onSeekVideo = openVideoAt, onDownload = downloadVideo)
                            3 -> SettingsScreen(recorder, downloadLogs)
                        }
                    } }
                }
            }
        }
    }
}

/** 导航保持悬浮外观，内容视口避开导航，焦点滚动和保存按钮都不会被遮挡。 */
@Composable private fun RecorderNavigationFrame(page: Int, onNavigate: (Int) -> Unit,
    modifier: Modifier = Modifier, showNavigation: Boolean = true, content: @Composable () -> Unit) {
    val titles = listOf("实时", "回放", "事件", "设置")
    val tags = listOf("nav-live", "nav-recordings", "nav-events", "nav-settings")
    Box(modifier.fillMaxSize().background(RecorderBackground).safeDrawingPadding()) {
        Box(Modifier.fillMaxSize().padding(bottom = if (showNavigation) 96.dp else 0.dp)) {
            CompositionLocalProvider(LocalRecorderContentBottomInset provides 0.dp) {
                content()
            }
        }
        if (showNavigation) Surface(
            modifier = Modifier.align(Alignment.BottomCenter).padding(horizontal = 16.dp, vertical = 12.dp)
                .widthIn(max = 560.dp).fillMaxWidth().testTag("floating-navigation"),
            shape = RoundedCornerShape(50),
            color = MaterialTheme.colorScheme.surface.copy(alpha = .96f),
            contentColor = RecorderInk,
            shadowElevation = 6.dp,
            border = BorderStroke(.75.dp, MaterialTheme.colorScheme.surface.copy(alpha = .72f))
        ) {
            NavigationBar(containerColor = Color.Transparent, windowInsets = WindowInsets(0),
                tonalElevation = 0.dp, modifier = Modifier.heightIn(min = 80.dp)) {
                titles.forEachIndexed { index, label ->
                    NavigationBarItem(selected = page == index, onClick = { onNavigate(index) },
                        modifier = Modifier.testTag(tags[index]),
                        colors = NavigationBarItemDefaults.colors(indicatorColor = Color.Transparent,
                            selectedIconColor = RecorderBlue, selectedTextColor = RecorderBlue,
                            unselectedIconColor = RecorderInk, unselectedTextColor = RecorderInk),
                        icon = { NavigationGlyph(index, page == index) }, label = {
                            Text(label, style = MaterialTheme.typography.labelMedium,
                                fontWeight = if (page == index) FontWeight.SemiBold else FontWeight.Medium)
                        })
                }
            }
        }
    }
}
/** 导航和页面操作使用同一套细线图标。 */
@Composable private fun NavigationGlyph(index: Int, selected: Boolean) {
    RecorderGlyph(listOf("camera", "playback", "event", "settings")[index],
        Modifier.size(26.dp), if (selected) RecorderBlue else RecorderInk)
}

@Composable private fun DownloadNotice(message: String, busy: Boolean, onDismiss: () -> Unit, modifier: Modifier = Modifier) {
    Surface(modifier.widthIn(max = 600.dp).fillMaxWidth(), color = MaterialTheme.colorScheme.surface,
        shape = MaterialTheme.shapes.large, shadowElevation = 5.dp) {
        Row(Modifier.padding(horizontal = 14.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            if (busy) {
                CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                Spacer(Modifier.width(10.dp))
            }
            Text(message, Modifier.weight(1f), style = MaterialTheme.typography.bodySmall, color = RecorderInk)
            if (!busy) TextButton(onClick = onDismiss) { Text("完成") }
        }
    }
}
