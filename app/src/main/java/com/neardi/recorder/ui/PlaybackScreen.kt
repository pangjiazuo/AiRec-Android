package com.neardi.recorder.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.ui.PlayerView
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import okhttp3.OkHttpClient
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.concurrent.TimeUnit

@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
@Composable
fun PlaybackScreen(url: String, title: String, active: Boolean, initialPositionMs: Long = 0,
    recordedAtEpochMs: Long? = null, fullscreen: Boolean = false, onFullscreenChange: (Boolean) -> Unit = {},
    onDownload: (() -> Unit)? = null, onClose: () -> Unit) {
    val context = LocalContext.current
    var position by rememberSaveable(url, initialPositionMs) { mutableLongStateOf(initialPositionMs.coerceAtLeast(0)) }
    var shouldPlay by rememberSaveable(url) { mutableStateOf(true) }
    var error by remember(url) { mutableStateOf<String?>(null) }
    var failureCount by remember(url) { mutableIntStateOf(0) }
    var duration by remember(url) { mutableLongStateOf(0) }
    var ready by remember(url) { mutableStateOf(false) }
    var ended by remember(url) { mutableStateOf(false) }
    var format by remember(url) { mutableStateOf("MP4") }
    val currentActive by rememberUpdatedState(active)
    val player = remember(url) { ExoPlayer.Builder(context)
        .setMediaSourceFactory(DefaultMediaSourceFactory(OkHttpDataSource.Factory(OkHttpClient.Builder()
            .connectTimeout(5, TimeUnit.SECONDS).readTimeout(10, TimeUnit.SECONDS)
            .followRedirects(false).followSslRedirects(false).build())))
        .build().apply {
            setMediaItem(MediaItem.fromUri(url)); seekTo(position); prepare(); playWhenReady = active && shouldPlay
        }
    }
    DisposableEffect(player) {
        val listener = object : Player.Listener {
            override fun onPlayerError(failure: PlaybackException) {
                position = player.currentPosition.coerceAtLeast(0)
                error = "录像暂时无法播放，正在重连（${failure.errorCodeName}）"
                failureCount++
            }
            override fun onPlaybackStateChanged(state: Int) {
                ended = state == Player.STATE_ENDED
                ready = state == Player.STATE_READY || state == Player.STATE_ENDED
                if (ready) {
                    error = null; failureCount = 0
                    duration = player.duration.coerceAtLeast(0)
                    val video = player.videoFormat
                    format = buildList {
                        if (video != null && video.width > 0 && video.height > 0) add("${video.width} × ${video.height}")
                        video?.sampleMimeType?.let { mime -> add(when (mime) {
                            "video/avc" -> "H.264"; "video/hevc" -> "H.265"; "video/av01" -> "AV1"; else -> mime.substringAfter('/')
                        }) }
                    }.joinToString(" · ").ifBlank { "MP4" }
                }
            }
            override fun onPlayWhenReadyChanged(value: Boolean, reason: Int) {
                // 系统切到后台造成的暂停不能覆盖用户之前的播放意图。
                if (currentActive) shouldPlay = value
            }
        }
        player.addListener(listener)
        onDispose { player.removeListener(listener); player.release() }
    }
    LaunchedEffect(player, initialPositionMs) { player.seekTo(position) }
    LaunchedEffect(player, active) {
        player.playWhenReady = active && shouldPlay
        while (active && isActive) {
            position = player.currentPosition.coerceAtLeast(0)
            duration = player.duration.coerceAtLeast(0)
            delay(250)
        }
    }
    LaunchedEffect(failureCount, active) {
        if (failureCount > 0 && active) {
            delay((failureCount * 2000L).coerceAtMost(10000))
            player.seekTo(position); player.prepare(); player.playWhenReady = shouldPlay
        }
    }
    fun seek(target: Long) {
        val actualDuration = player.duration.takeIf { it >= 0 }
        val clamped = if (actualDuration != null) target.coerceIn(0L, actualDuration) else target.coerceAtLeast(0)
        if (actualDuration != null) duration = actualDuration
        position = clamped; player.seekTo(clamped)
    }
    val foreground = if (fullscreen) Color.White else RecorderInk
    BoxWithConstraints(Modifier.fillMaxSize().background(if (fullscreen) Color.Black else RecorderBackground).safeDrawingPadding().testTag("playback-screen")) {
        val sideBySide = !fullscreen && ((maxWidth > maxHeight && maxHeight < 650.dp) || maxWidth >= 840.dp)
        Column(Modifier.fillMaxSize()) {
            Box(Modifier.fillMaxWidth().height(56.dp).padding(horizontal = 8.dp), contentAlignment = Alignment.Center) {
                IconButton(onClick = onClose, modifier = Modifier.align(Alignment.CenterStart).testTag("playback-close")
                    .semantics { contentDescription = "关闭" }) { RecorderGlyph("back", color = foreground) }
                Text("录像播放", Modifier.padding(horizontal = 80.dp), color = foreground, style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold, maxLines = 1)
                if (fullscreen) TextButton(onClick = { onFullscreenChange(false) }, modifier = Modifier.align(Alignment.CenterEnd)) { Text("退出全屏", color = Color.White) }
            }
            // 预览始终在相同组合位置；横竖屏/全屏只调整测量，不重建播放器。
            Layout(modifier = Modifier.fillMaxWidth().weight(1f), content = {
                Box(Modifier.padding(horizontal = if (fullscreen) 0.dp else 16.dp).testTag("playback-video")
                    .clip(RoundedCornerShape(if (fullscreen) 0.dp else 12.dp)).background(Color.Black)) {
                    AndroidView(factory = { PlayerView(it).apply { this.player = player; keepScreenOn = true; useController = fullscreen } },
                        modifier = Modifier.fillMaxSize(), update = { it.player = player; it.useController = fullscreen })
                    if (!fullscreen) FilledTonalIconButton(onClick = { onFullscreenChange(true) },
                        modifier = Modifier.align(Alignment.BottomEnd).padding(8.dp).size(44.dp).testTag("playback-fullscreen")
                            .semantics { contentDescription = "全屏" }, shape = RoundedCornerShape(10.dp),
                        colors = IconButtonDefaults.filledTonalIconButtonColors(containerColor = Color(0xC018181B), contentColor = Color.White)) {
                        RecorderGlyph("expand", modifier = Modifier.size(21.dp))
                    }
                }
                Column(Modifier.verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 18.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        IconButton(onClick = { if (ended) seek(0); shouldPlay = !shouldPlay; player.playWhenReady = active && shouldPlay },
                            modifier = Modifier.size(40.dp).testTag("playback-toggle").semantics { contentDescription = if (shouldPlay && !ended) "暂停录像" else "播放录像" }) { PlaybackToggleGlyph(shouldPlay && !ended) }
                        Box(Modifier.weight(1f)) { PlaybackTimeline(position, duration, ready, onSeek = ::seek) }
                        IconButton(onClick = { onFullscreenChange(true) }, modifier = Modifier.size(40.dp).testTag("playback-expand")) { RecorderGlyph("expand", Modifier.size(20.dp)) }
                    }
                    error?.let { MessageCard(it) }
                    if (!ready && error == null) Hint("正在载入录像…")
                    GroupedRows {
                        ReadOnlyRow("通道", title.substringBefore(" · "))
                        ReadOnlyRow("开始时间", recordedAtEpochMs?.let { Instant.ofEpochMilli(it).atZone(ZoneId.systemDefault()).format(DateTimeFormatter.ofPattern("MM月dd日 HH:mm:ss")) } ?: "—")
                        ReadOnlyRow("时长", if (duration > 0) playbackTime(duration) else "—")
                        ReadOnlyRow("编码格式", format)
                    }
                    onDownload?.let { download -> GroupedRows { OptionRow("保存录像到手机", route = "video-download", onRoute = { download() }) } }

                }
            }) { children, constraints ->
                val width = constraints.maxWidth
                val height = constraints.maxHeight
                val videoSlotWidth = if (sideBySide) (width * .56f).toInt() else width
                val padding = if (fullscreen) 0 else 32.dp.roundToPx()
                val maxVideoHeight = if (sideBySide) height else (height * .43f).toInt()
                // 同时约束宽高，预览始终保持 16:9，避免宽平板出现多余黑边。
                val pictureWidth = (videoSlotWidth - padding).coerceAtLeast(1).coerceAtMost((maxVideoHeight * 16 / 9).coerceAtLeast(1))
                val videoWidth = if (fullscreen) width else (pictureWidth + padding).coerceAtMost(videoSlotWidth)
                val videoHeight = if (fullscreen) height else pictureWidth * 9 / 16
                val video = children[0].measure(Constraints.fixed(videoWidth, videoHeight.coerceAtLeast(0)))
                val detailsWidth = if (sideBySide) width - videoSlotWidth else width
                val detailsHeight = if (fullscreen) 0 else if (sideBySide) height else (height - videoHeight).coerceAtLeast(0)
                val details = children[1].measure(Constraints.fixed(detailsWidth, detailsHeight))
                layout(width, height) {
                    // 分栏时与右侧控件顶部对齐，竖屏平板也不会出现大块错位留白。
                    val videoTop = if (sideBySide) 18.dp.roundToPx().coerceAtMost((height - video.height).coerceAtLeast(0)) else 0
                    video.placeRelative((videoSlotWidth - videoWidth) / 2, videoTop)
                    if (!fullscreen) details.placeRelative(if (sideBySide) videoSlotWidth else 0, if (sideBySide) 0 else videoHeight)
                }
            }
        }
    }
}

@Composable private fun PlaybackTimeline(position: Long, duration: Long, enabled: Boolean, onSeek: (Long) -> Unit) {
    var dragging by remember { mutableStateOf(false) }
    var dragValue by remember { mutableFloatStateOf(0f) }
    val lineColor = RecorderLine
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(0.dp)) {
        Box(Modifier.fillMaxWidth().height(40.dp)) {
            Slider(value = if (dragging) dragValue else if (duration > 0) (position.toDouble() / duration).toFloat().coerceIn(0f, 1f) else 0f,
                onValueChange = { dragging = true; dragValue = it },
                onValueChangeFinished = { onSeek((duration * dragValue).toLong()); dragging = false }, enabled = enabled && duration > 0,
                modifier = Modifier.fillMaxWidth().testTag("playback-timeline"), colors = SliderDefaults.colors(
                    thumbColor = RecorderBlue, activeTrackColor = RecorderBlue.copy(alpha = .65f), inactiveTrackColor = MaterialTheme.colorScheme.surfaceVariant))
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(playbackTime(position), color = RecorderMuted, style = MaterialTheme.typography.labelSmall)
            Text(if (duration > 0) playbackTime(duration) else "—", color = RecorderMuted, style = MaterialTheme.typography.labelSmall)
        }
    }
}

private fun playbackTime(milliseconds: Long): String {
    val seconds = milliseconds.coerceAtLeast(0) / 1_000
    return if (seconds >= 3_600) String.format(Locale.ROOT, "%d:%02d:%02d", seconds / 3_600, seconds / 60 % 60, seconds % 60)
        else String.format(Locale.ROOT, "%02d:%02d", seconds / 60, seconds % 60)
}

@Composable private fun SkipGlyph(direction: String) {
    Box(Modifier.size(32.dp), contentAlignment = Alignment.Center) {
        RecorderGlyph(direction, modifier = Modifier.fillMaxSize())
        Text("15", modifier = Modifier.padding(top = 2.dp), fontSize = 12.sp, lineHeight = 14.sp, fontWeight = FontWeight.Medium)
    }
}

@Composable private fun PlaybackToggleGlyph(playing: Boolean) {
    val inkColor = RecorderInk
    Canvas(Modifier.size(24.dp)) {
        if (playing) {
            drawLine(inkColor, Offset(size.width * .32f, size.height * .16f), Offset(size.width * .32f, size.height * .84f), size.width * .18f)
            drawLine(inkColor, Offset(size.width * .68f, size.height * .16f), Offset(size.width * .68f, size.height * .84f), size.width * .18f)
        } else drawPath(Path().apply { moveTo(size.width * .24f, size.height * .12f); lineTo(size.width * .88f, size.height * .5f)
            lineTo(size.width * .24f, size.height * .88f); close() }, inkColor)
    }
}
