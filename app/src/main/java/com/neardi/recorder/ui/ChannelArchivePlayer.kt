package com.neardi.recorder.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.ui.PlayerView
import com.neardi.recorder.data.RecorderApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive

/** 单个原生播放器在详情与全屏间复用；不提供下载入口。 */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
@Composable
fun ChannelArchivePlayer(url: String, initialPosition: Long, endPosition: Long?, active: Boolean,
    scrubbing: Boolean, seekRequestId: Long, modifier: Modifier = Modifier, minimumPosition: Long = 0, playing: Boolean = true, playbackSpeed: Float = 1f,
    onEnded: () -> Unit) {
    val context = LocalContext.current
    val range = remember(minimumPosition, endPosition) { ArchivePlaybackRange(minimumPosition, endPosition) }
    // 新选时即使时间相同也重置；旋转保持同一个请求编号，恢复已播放的位置。
    var position by rememberSaveable(url, range, seekRequestId) { mutableLongStateOf(initialPosition) }
    var playIntent by rememberSaveable(url) { mutableStateOf(true) }
    var fullscreen by rememberSaveable { mutableStateOf(false) }
    var error by remember(url, range) { mutableStateOf<String?>(null) }
    var retry by remember(url, range) { mutableIntStateOf(0) }
    var completed by remember(url, range, seekRequestId) { mutableStateOf(false) }
    val currentActive by rememberUpdatedState(active && !scrubbing)
    val finished by rememberUpdatedState(onEnded)
    val player = remember(url, range) { ExoPlayer.Builder(context)
        .setMediaSourceFactory(DefaultMediaSourceFactory(OkHttpDataSource.Factory(RecorderApi.defaultClient()
            .newBuilder().callTimeout(0, java.util.concurrent.TimeUnit.MILLISECONDS).build())))
        .build().apply {
            // 原生进度条也只显示当天范围，跨午夜文件不能拖回前一天。
            val clipping = MediaItem.ClippingConfiguration.Builder().setStartPositionMs(range.minimumPosition)
            range.endPosition?.let { clipping.setEndPositionMs(it) }
            setMediaItem(MediaItem.Builder().setUri(url).setClippingConfiguration(clipping.build()).build())
            seekTo(range.toPlayerPosition(position))
        } }
    DisposableEffect(player, seekRequestId) {
        val listener = object : Player.Listener {
            override fun onPlaybackStateChanged(state: Int) {
                if (state == Player.STATE_READY) { error = null; retry = 0 }
                if (state == Player.STATE_ENDED && !completed && currentActive) { completed = true; finished() }
            }
            override fun onPlayerError(failure: PlaybackException) {
                position = range.toOriginalPosition(player.currentPosition)
                error = "录像暂时无法播放，文件可能已清理或网络已断开（${failure.errorCodeName}）"; retry++
            }
            override fun onPlayWhenReadyChanged(value: Boolean, reason: Int) {
                if (currentActive && reason == Player.PLAY_WHEN_READY_CHANGE_REASON_USER_REQUEST) playIntent = value
            }
        }
        player.addListener(listener)
        onDispose { player.removeListener(listener) }
    }
    DisposableEffect(player) { onDispose { player.release() } }
    LaunchedEffect(player, seekRequestId) { completed = false; player.seekTo(range.toPlayerPosition(position)) }
    LaunchedEffect(player, playing, playbackSpeed) { player.setPlaybackSpeed(playbackSpeed); player.playWhenReady = active && !scrubbing && playIntent && playing }
    LaunchedEffect(player, active, scrubbing, seekRequestId, endPosition) {
        if (!active) { position = range.toOriginalPosition(player.currentPosition); player.stop(); return@LaunchedEffect }
        if (player.playbackState == Player.STATE_IDLE) { player.seekTo(range.toPlayerPosition(position)); player.prepare() }
        player.playWhenReady = active && !scrubbing && playIntent && playing
        while (active && isActive) {
            position = range.toOriginalPosition(player.currentPosition)
            if (!scrubbing && !completed && (player.playbackState == Player.STATE_ENDED || (endPosition != null && position >= endPosition))) {
                completed = true; player.pause(); finished()
            }
            delay(200)
        }
    }
    LaunchedEffect(player, retry, active, seekRequestId) {
        if (retry > 0 && active) {
            delay((retry * 2000L).coerceAtMost(10000)); player.seekTo(range.toPlayerPosition(position)); player.prepare()
            player.playWhenReady = currentActive && playIntent
        }
    }
    Box(modifier.background(Color.Black).testTag("channel-archive-player")) {
        AndroidView(factory = { PlayerView(it).apply { useController = true } }, modifier = Modifier.fillMaxSize(),
            update = { it.player = if (fullscreen) null else player; it.keepScreenOn = active && playIntent })
        TextButton(onClick = { fullscreen = true }, modifier = Modifier.align(Alignment.TopEnd).testTag("channel-archive-fullscreen")) {
            Text("全屏", color = Color.White)
        }
        if (error != null) Text(error!!, Modifier.align(Alignment.Center).background(Color(0xBB000000)).padding(12.dp),
            color = Color.White, style = MaterialTheme.typography.bodySmall)
    }
    if (fullscreen) Dialog(onDismissRequest = { fullscreen = false },
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        Box(Modifier.fillMaxSize().background(Color.Black).testTag("channel-archive-fullscreen-screen")) {
            AndroidView(factory = { PlayerView(it).apply { useController = true } }, modifier = Modifier.fillMaxSize(),
                update = { it.player = player; it.keepScreenOn = active && playIntent }, onRelease = { it.player = null })
            TextButton(onClick = { fullscreen = false }, modifier = Modifier.align(Alignment.TopStart).statusBarsPadding()) {
                Text("退出全屏", color = Color.White)
            }
            if (error != null) Text(error!!, Modifier.align(Alignment.Center).padding(20.dp), color = Color.White)
        }
    }
}
