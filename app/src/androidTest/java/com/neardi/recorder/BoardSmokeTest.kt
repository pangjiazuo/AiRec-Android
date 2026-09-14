package com.neardi.recorder

import android.content.Context
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.graphics.Bitmap
import android.view.View
import android.view.ViewGroup
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.media3.common.Player
import androidx.media3.ui.PlayerView
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import com.neardi.recorder.data.RecorderApi
import com.neardi.recorder.media.MediaNetwork
import com.neardi.recorder.ui.RecordingTimeline
import com.neardi.recorder.ui.decodeDayIndex
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.ZipInputStream
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/** 显式连接真实板卡，只读预览/回放/日志，绝不提交配置。 */
@OptIn(ExperimentalTestApi::class)
class BoardSmokeTest {
    @get:Rule val compose = createEmptyComposeRule()
    private lateinit var scenario: ActivityScenario<MainActivity>
    private var originalAppearance: String? = null
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val board get() = InstrumentationRegistry.getArguments().getString("boardUrl") ?: "http://192.168.10.209:8080"
    @Before fun start() {
        File(context.getExternalFilesDir(null), "verification").listFiles()?.filter { it.isFile && it.extension == "png" }
            ?.forEach { check(it.delete()) }
        File(context.getExternalFilesDir(null), "verification").apply { mkdirs() }
        File(context.getExternalFilesDir(null), "verification/playback-steps.txt").writeText("")
        val appearance = context.getSharedPreferences("recorder_appearance", Context.MODE_PRIVATE)
        originalAppearance = appearance.getString("mode", null)
        InstrumentationRegistry.getArguments().getString("theme")?.let { appearance.edit().putString("mode", it).commit() }
        context.getSharedPreferences("recorder_connection", Context.MODE_PRIVATE).edit().putString("endpoint", board).commit()
        scenario = ActivityScenario.launch(MainActivity::class.java)
    }
    @After fun stop() {
        scenario.close()
        val appearance = context.getSharedPreferences("recorder_appearance", Context.MODE_PRIVATE).edit()
        if (originalAppearance == null) appearance.remove("mode") else appearance.putString("mode", originalAppearance)
        appearance.commit()
    }

    @Test fun diagnoseTimelineTransport() {
        val day = RecordingTimeline.day(LocalDate.now(), ZoneId.systemDefault())
        val url = okhttp3.HttpUrl.Companion.run { board.toHttpUrl() }.newBuilder().addPathSegments("api/timeline")
            .addQueryParameter("channel_id", "1").addQueryParameter("start", Instant.ofEpochMilli(day.startMs).toString())
            .addQueryParameter("end", Instant.ofEpochMilli(day.endMs).toString()).build().toString()
        val rows = mutableListOf<String>(url)
        repeat(3) {
            var received = 0L
            var expected = -1L
            try {
                RecorderApi.defaultClient().newCall(okhttp3.Request.Builder().url(url).build()).execute().use { response ->
                    expected = response.body!!.contentLength()
                    response.body!!.byteStream().use { input ->
                        val buf = ByteArray(8192)
                        while (true) { val n=input.read(buf); if(n<0)break; received += n }
                    }
                }
                rows.add("OK $received/$expected")
            } catch (e: Exception) { rows.add("FAIL $received/$expected ${e.message}") }
        }
        File(context.getExternalFilesDir(null), "verification/transport.txt").writeText(rows.joinToString("\n"))
        org.junit.Assert.assertTrue(rows.toString(), rows.drop(1).all { it.startsWith("OK") })
    }

    private fun capture(name: String) {
        val directory = File(context.getExternalFilesDir(null), "verification").apply { mkdirs() }
        InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot().let { image ->
            File(directory, name).outputStream().use { image.compress(Bitmap.CompressFormat.PNG, 100, it) }
        }
    }
    private fun playerView(view: View): PlayerView? {
        if (view is PlayerView) return view
        if (view is ViewGroup) for (index in 0 until view.childCount) playerView(view.getChildAt(index))?.let { return it }
        return null
    }

    /** 即使当前摄像头关闭，也能只读验收板上已经保存的录像。 */
    @Test fun realWholeDayTimelinePlaysInlineAndRestoresAfterRotation() {
        val api = RecorderApi(board)
        val day = RecordingTimeline.day(LocalDate.now(), ZoneId.systemDefault())
        val index = runBlocking { decodeDayIndex(api.getTimeline(1, Instant.ofEpochMilli(day.startMs),
            Instant.ofEpochMilli(day.endMs)), api, 1, day) }
        val clip = index.recordings.firstOrNull { it.endMs.coerceAtMost(day.endMs) - it.startMs.coerceAtLeast(day.startMs) > 45_000 }
            ?: error("板端当天没有足够长度的可用录像，无法完成实际播放验收")
        val selectedAt = clip.startMs.coerceAtLeast(day.startMs) + 8_000
        val fraction = ((selectedAt - day.startMs).toDouble() / (day.endMs - day.startMs)).toFloat()
        val initialPosition = selectedAt - clip.startMs
        val minimumPosition = (day.startMs - clip.startMs).coerceAtLeast(0)
        compose.waitUntilAtLeastOneExists(hasTestTag("camera-1"), 20_000)
        capture("timeline-live.png")
        compose.onNodeWithTag("camera-1").performClick()
        try {
            compose.waitUntil(20_000) {
                compose.onNodeWithTag("channel-day-timeline").fetchSemanticsNode().config[SemanticsProperties.StateDescription] == "已加载"
            }
        } catch (failure: Exception) {
            File(context.getExternalFilesDir(null), "verification/timeline-failure.txt").writeText(compose.onRoot(useUnmergedTree = true).printToString())
            throw failure
        }
        compose.onNodeWithTag("nav-live").assertDoesNotExist()
        compose.onNodeWithText("下载录像").assertDoesNotExist()
        compose.onNodeWithTag("channel-day-timeline").performSemanticsAction(SemanticsActions.SetProgress) { it(fraction) }
        compose.waitUntil(30_000) { currentPlayback().third == Player.STATE_READY && currentPlayback().first >= initialPosition }
        assertTrue("时间轴必须定位到真实片段中的对应时间", currentPlayback().first < initialPosition + 8_000)
        capture("timeline-inline-playback.png")
        val timelineBeforeRotation = compose.onNodeWithTag("timeline-selected-time").fetchSemanticsNode().config[SemanticsProperties.Text].joinToString { it.text }
        // 模拟片段已经播放一段，再选择相同时间，必须真正重新 seek。
        scenario.onActivity { playerView(it.window.decorView)?.player?.seekTo(initialPosition + 20_000 - minimumPosition) }
        compose.waitUntil(10_000) { currentPlayback().first >= initialPosition + 19_000 }
        compose.onNodeWithTag("channel-day-timeline").performSemanticsAction(SemanticsActions.SetProgress) { it(fraction) }
        compose.waitUntil(15_000) { currentPlayback().third == Player.STATE_READY && currentPlayback().first < initialPosition + 5_000 }
        scenario.onActivity { playerView(it.window.decorView)?.player?.pause() }
        val pausedPosition = currentPlayback().first
        val beforeRotation = context.resources.configuration.orientation
        scenario.onActivity { it.requestedOrientation = if (beforeRotation == Configuration.ORIENTATION_LANDSCAPE)
            ActivityInfo.SCREEN_ORIENTATION_PORTRAIT else ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE }
        compose.waitUntil(10_000) { context.resources.configuration.orientation != beforeRotation }
        compose.waitUntil(20_000) { currentPlayback().third == Player.STATE_READY }
        assertFalse("旋转应保留用户暂停状态", currentPlayback().second)
        assertTrue("旋转应恢复正在播放的片段位置", kotlin.math.abs(currentPlayback().first - pausedPosition) < 2_000)
        compose.onNodeWithTag("timeline-selected-time").assertTextContains(timelineBeforeRotation)
        capture("timeline-inline-rotated.png")
        compose.onNodeWithTag("channel-archive-fullscreen").performClick()
        compose.onNodeWithTag("channel-archive-fullscreen-screen").assertIsDisplayed()
        compose.onNodeWithText("下载录像").assertDoesNotExist()
        compose.onNodeWithText("退出全屏").performClick()
        compose.onNodeWithTag("channel-return-live").performClick()
        compose.onNodeWithTag("channel-preview-1").assertExists()
        compose.onNodeWithTag("channel-archive-player").assertDoesNotExist()
        compose.onNodeWithTag("channel-detail-back").performClick()
        compose.onNodeWithTag("nav-recordings").performClick()
        compose.waitUntilAtLeastOneExists(hasClickAction() and hasText(" 秒", substring = true) and isEnabled(), 20_000)
        compose.onNodeWithText("录像时间轴").assertDoesNotExist()
        capture("timeline-global-recordings.png")
        compose.onNodeWithTag("nav-settings").performClick()
        capture("timeline-settings.png")
        File(context.getExternalFilesDir(null), "verification/timeline-board.txt").writeText(
            "recordings=${index.recordings.size} eventSegments=${index.events.size} selectedAt=$selectedAt initialPosition=$initialPosition pausedPosition=$pausedPosition\n")
    }

    @Test fun realPreviewFullscreenPlaybackAndLogs() {
        compose.waitUntilAtLeastOneExists(hasContentDescription("AHD1实时画面"), 40_000)
        capture("phone-live.png")
        compose.onNodeWithTag("camera-1").performClick()
        compose.onNodeWithTag("channel-detail").assertIsDisplayed()
        compose.waitUntilAtLeastOneExists(hasContentDescription("AHD1实时画面"), 15_000)
        compose.waitUntilAtLeastOneExists(hasTestTag("channel-day-timeline"), 20_000)
        compose.waitForIdle()
        capture("phone-channel-detail.png")
        compose.onNodeWithContentDescription("退出全屏").assertDoesNotExist()
        compose.onNodeWithTag("channel-fullscreen").performClick()
        compose.onNodeWithContentDescription("退出全屏").assertExists()
        compose.waitUntilAtLeastOneExists(hasContentDescription("AHD1实时画面"), 15_000)
        capture("phone-fullscreen.png")
        compose.onNodeWithText("退出全屏").performClick()
        compose.onNodeWithTag("channel-detail").assertIsDisplayed()
        compose.onNodeWithTag("channel-tab-recordings").performClick()
        compose.onNodeWithTag("channel-day-timeline").assertIsDisplayed()
        compose.onNodeWithTag("channel-detail-back").performClick()
        compose.onNodeWithTag("nav-recordings").performClick()
        compose.waitUntilAtLeastOneExists(hasClickAction() and hasText(" 秒", substring = true) and isEnabled(), 20_000)
        capture("phone-recordings.png")
        compose.onAllNodes(hasClickAction() and hasText(" 秒", substring = true) and isEnabled()).onFirst().performClick()
        compose.waitUntil(30_000) {
            var ready = false
            scenario.onActivity { activity ->
                val player = playerView(activity.window.decorView)?.player
                ready = player?.playbackState == Player.STATE_READY && player.currentPosition > 500
            }
            ready
        }
        assertPlaybackAspect()
        capture("phone-playback.png")
        // 新回放控件必须驱动真实播放器；只读定位，不改变板端文件。
        compose.onNodeWithTag("playback-toggle").performScrollTo().performClick()
        compose.waitUntil(10_000) { !currentPlayback().second }
        val pausedPosition = currentPlayback().first
        recordPlayback("paused", pausedPosition)
        compose.onNodeWithTag("playback-seek-forward").performScrollTo().assertIsDisplayed().assertIsEnabled().performClick()
        awaitPlayback("forward", pausedPosition) { it.first >= pausedPosition + 14_000 && it.third == Player.STATE_READY }
        compose.onNodeWithTag("playback-seek-back").assertIsEnabled().performClick()
        awaitPlayback("back", pausedPosition) { it.first <= pausedPosition + 1_000 && it.third == Player.STATE_READY }
        compose.onNodeWithTag("playback-fullscreen").performClick()
        compose.onNodeWithContentDescription("退出全屏").assertIsDisplayed()
        assertFalse("进入全屏不能恢复用户暂停的录像", currentPlayback().second)
        capture("phone-playback-fullscreen.png")
        compose.onNodeWithText("退出全屏").performClick()
        val beforeRotation = context.resources.configuration.orientation
        scenario.onActivity { it.requestedOrientation = if (beforeRotation == Configuration.ORIENTATION_LANDSCAPE)
            ActivityInfo.SCREEN_ORIENTATION_PORTRAIT else ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE }
        compose.waitUntil(10_000) { context.resources.configuration.orientation != beforeRotation }
        compose.waitUntil(20_000) { currentPlayback().third == Player.STATE_READY }
        assertFalse("旋转不能恢复用户暂停的录像", currentPlayback().second)
        assertTrue("旋转保留播放位置", kotlin.math.abs(currentPlayback().first - pausedPosition) < 2_000)
        assertPlaybackAspect()
        capture("phone-playback-rotated.png")
        scenario.onActivity { it.requestedOrientation = if (beforeRotation == Configuration.ORIENTATION_LANDSCAPE)
            ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE else ActivityInfo.SCREEN_ORIENTATION_PORTRAIT }
        compose.waitUntil(10_000) { context.resources.configuration.orientation == beforeRotation }
        compose.onNodeWithTag("playback-close").performClick()
        compose.onNodeWithTag("nav-live").performClick()
        compose.onNodeWithTag("camera-1").performClick()
        compose.onNodeWithTag("channel-detail").assertIsDisplayed()
        // 图片解码成功后再抓图；直接验证共享下载器可解包真实日志ZIP。
        val archive = ByteArrayOutputStream()
        runBlocking { MediaNetwork.download(RecorderApi(board).mediaUrl("/api/logs/download"), archive) }
        var entries = 0
        ZipInputStream(archive.toByteArray().inputStream()).use { zip ->
            while (zip.nextEntry != null) { zip.copyTo(ByteArrayOutputStream()); entries++ }
        }
        assertTrue("真实日志ZIP应包含状态与日志文件", entries >= 3)
        // 进入当前通道设置并只读截图；不向开发板提交配置。
        compose.onNodeWithTag("channel-settings-entry").performClick()
        compose.waitUntilAtLeastOneExists(hasTestTag("option-basic"), 15_000)
        compose.onNodeWithTag("option-basic").performClick()
        capture("phone-channel-settings.png")
        compose.onNodeWithTag("channel-settings-back").performScrollTo().performClick()
        compose.onNodeWithTag("option-detection").performClick()
        compose.onNodeWithTag("option-dwell").performClick()
        compose.onNodeWithTag("setting-人 / 动物停留阈值（秒）").performScrollTo()
        capture("phone-settings-channel.png")
        repeat(3) { compose.onNodeWithTag("channel-settings-back").performScrollTo().performClick() }
        compose.onNodeWithTag("channel-detail-back").performClick()
        compose.onNodeWithTag("nav-settings").performClick()
        compose.onNodeWithTag("settings-category-storage").assertExists()
        compose.onNodeWithTag("setting-通道名称").assertDoesNotExist()
        capture("phone-settings.png")
        compose.onNodeWithTag("settings-category-storage").performClick()
        compose.waitUntilAtLeastOneExists(hasTestTag("setting-录像空间上限（GB）"), 15_000)
        capture("phone-storage-settings.png")
    }

    private fun currentPlayback(): Triple<Long, Boolean, Int> {
        var result = Triple(-1L, false, Player.STATE_IDLE)
        scenario.onActivity { activity -> playerView(activity.window.decorView)?.player?.let {
            // 通道播放器使用官方片段裁剪；对外仍按原始文件偏移检查定位。
            val clippingStart = it.currentMediaItem?.clippingConfiguration?.startPositionMs ?: 0
            result = Triple(it.currentPosition + clippingStart, it.playWhenReady, it.playbackState)
        } }
        return result
    }

    private fun assertPlaybackAspect() {
        val video = compose.onNodeWithTag("playback-video").fetchSemanticsNode().boundsInRoot
        assertEquals("手机和平板非全屏预览应保持16:9", 16f / 9f, video.width / video.height, .025f)
        if (context.resources.configuration.screenWidthDp >= 840) {
            val clock = compose.onNodeWithTag("playback-time").fetchSemanticsNode().boundsInRoot
            val gapDp = (clock.top - video.top) / context.resources.displayMetrics.density
            assertTrue("平板分栏视频应与日期/时间控件共同从顶部开始，不能在整页居中：$gapDp dp", gapDp in 0f..128f)
            File(context.getExternalFilesDir(null), "verification/playback-steps.txt").appendText(
                "layout: video=$video time=$clock topGapDp=$gapDp\n")
        }
    }

    private fun recordPlayback(step: String, baseline: Long) {
        val value = currentPlayback()
        var duration = -1L
        scenario.onActivity { activity -> duration = playerView(activity.window.decorView)?.player?.duration ?: -1L }
        val forward = compose.onNodeWithTag("playback-seek-forward").fetchSemanticsNode().config.toString()
        File(context.getExternalFilesDir(null), "verification/playback-steps.txt").appendText(
            "$step: baseline=$baseline position=${value.first} playWhenReady=${value.second} state=${value.third} duration=$duration forward=$forward\n")
    }

    private fun awaitPlayback(step: String, baseline: Long, predicate: (Triple<Long, Boolean, Int>) -> Boolean) {
        try {
            compose.waitUntil(15_000) { predicate(currentPlayback()) }
            recordPlayback(step, baseline)
        } catch (failure: Throwable) {
            recordPlayback("failed-$step", baseline)
            capture("failure-$step.png")
            throw AssertionError("$step: baseline=$baseline, actual=${currentPlayback()}", failure)
        }
    }
}
