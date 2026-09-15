package com.neardi.recorder

import android.view.View
import android.view.ViewGroup
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.media3.ui.PlayerView
import androidx.media3.common.Player
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.neardi.recorder.ui.ChannelArchivePlayer
import com.neardi.recorder.ui.DayIndex
import com.neardi.recorder.ui.RecorderTheme
import com.neardi.recorder.ui.RecordingTimeline
import com.neardi.recorder.ui.VerticalDayTimeline
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okio.Buffer
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.time.LocalDate
import java.time.ZoneId

/** 独立组件测试：不连接开发板、不写配置，也不依赖录像文件。 */
@RunWith(AndroidJUnit4::class)
class TimelineInteractionTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val zone = ZoneId.of("Asia/Shanghai")
    private val day = RecordingTimeline.day(LocalDate.of(2026, 9, 7), zone)
    private val empty = DayIndex(emptyList(), emptyList())

    @Test fun denseEventsExpandAndCanSelectEverySnapshot() {
        val base = day.startMs + 12*3_600_000L
        val selected = mutableLongStateOf(base)
        val moments = listOf(
            com.neardi.recorder.ui.TimelineMoment("first", base, "person", null),
            com.neardi.recorder.ui.TimelineMoment("second", base+1000, "animal", null))
        compose.setContent {
            RecorderTheme { Box(Modifier.fillMaxSize()) {
                VerticalDayTimeline(DayIndex(emptyList(), emptyList(), moments, true), day, zone, true,
                    selected.longValue, {}, { selected.longValue = it }, active = false)
            } }
        }
        compose.onNodeWithTag("timeline-event-first").performClick()
        compose.onNodeWithText("附近 2 条事件").assertIsDisplayed()
        compose.onNodeWithText("12:00:01").performClick()
        compose.onNodeWithTag("timeline-selected-time").assertTextEquals("12:00:01")
        compose.runOnIdle { assertEquals(base+1000, selected.longValue) }
    }
    @Test fun preciseAccessibleTimeIsNotRoundedToOverviewPixelsAfterZoomOrRestore() {
        val target = day.startMs + 8_000
        val fraction = day.fractionAt(target)
        val selected = mutableLongStateOf(day.startMs)
        val restoration = StateRestorationTester(compose)
        restoration.setContent {
            RecorderTheme { Box(Modifier.fillMaxSize()) {
                VerticalDayTimeline(empty, day, zone, true, selected.longValue, {}, { selected.longValue = it })
            } }
        }
        choose(fraction)
        fun assertExactTime() {
            compose.onNodeWithTag("timeline-selected-time").assertTextEquals("00:00:08")
            val actual = compose.onNodeWithTag("channel-day-timeline").fetchSemanticsNode().config[SemanticsProperties.ProgressBarRangeInfo].current
            assertEquals(fraction, actual, 0f)
            compose.runOnIdle { assertEquals(target, selected.longValue) }
        }
        assertExactTime()
        compose.onNodeWithTag("timeline-precise").performClick()
        assertExactTime()
        compose.onNodeWithTag("timeline-overview").performClick()
        assertExactTime()
        restoration.emulateSavedInstanceStateRestore()
        assertExactTime()
    }

    @Test fun repeatedAccessibleSelectionCommitsOnceEachAndRestorationDoesNotSeekAgain() {
        val selected = mutableLongStateOf(day.timeAt(.1f))
        val commits = mutableListOf<Long>()
        val restoration = StateRestorationTester(compose)
        restoration.setContent {
            RecorderTheme { Box(Modifier.fillMaxSize()) {
                VerticalDayTimeline(empty, day, zone, true, selected.longValue, {}, {
                    selected.longValue = it; commits.add(it)
                })
            } }
        }
        choose(.4f)
        waitForFraction(.4f)
        choose(.4f)
        waitForFraction(.4f)
        compose.runOnIdle { assertEquals(listOf(day.timeAt(.4f), day.timeAt(.4f)), commits) }
        compose.onNodeWithTag("timeline-precise").performClick()
        waitForFraction(.4f)
        restoration.emulateSavedInstanceStateRestore()
        waitForFraction(.4f)
        // 程序恢复与缩放只同步视觉位置，不额外触发录像请求。
        compose.runOnIdle { assertEquals(2, commits.size) }
        compose.runOnIdle { selected.longValue = day.timeAt(.7f) }
        waitForFraction(.7f)
        compose.runOnIdle { assertEquals(2, commits.size) }
    }

    @Test fun disablingOrZoomingDuringDragAlwaysClearsScrubbingWithoutCommitting() {
        val enabled = mutableStateOf(true)
        var scrubbing = false
        var commits = 0
        compose.setContent {
            RecorderTheme { Box(Modifier.fillMaxSize()) {
                VerticalDayTimeline(empty, day, zone, enabled.value, day.timeAt(.5f), { scrubbing = it }, { commits++ })
            } }
        }
        waitForFraction(.5f)
        beginDrag()
        compose.runOnIdle { assertTrue(scrubbing); enabled.value = false }
        compose.waitForIdle()
        compose.runOnIdle { assertFalse(scrubbing) }
        compose.onNodeWithTag("channel-day-timeline").performTouchInput { up() }
        compose.runOnIdle { enabled.value = true }
        compose.waitForIdle()
        beginDrag()
        compose.runOnIdle { assertTrue(scrubbing) }
        // 手指仍按在时间轴上，直接调用按钮语义，避免重复注入同一指针的 DOWN。
        compose.onNodeWithTag("timeline-precise").performSemanticsAction(SemanticsActions.OnClick) { assertTrue(it()) }
        compose.waitForIdle()
        compose.runOnIdle { assertFalse(scrubbing) }
        compose.onNodeWithTag("channel-day-timeline").performTouchInput { up() }
        compose.waitForIdle()
        compose.runOnIdle { assertEquals(0, commits); assertFalse(scrubbing) }
    }

    @androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
    @Test fun repeatedSeekRequestResetsPositionWithoutReplacingSameUrlPlayer() {
        val request = mutableLongStateOf(1)
        compose.setContent {
            RecorderTheme {
                // 非活动播放器不 prepare，不发 HTTP；pending seek 仍可检验请求语义。
                ChannelArchivePlayer("http://127.0.0.1:1/isolated.mp4", 10_000, null,
                    active = false, scrubbing = false, seekRequestId = request.longValue,
                    modifier = Modifier.fillMaxSize(), onEnded = {})
            }
        }
        compose.waitForIdle()
        val first = compose.runOnIdle { requireNotNull(findPlayer(compose.activity.window.decorView)?.player) }
        compose.runOnIdle { first.seekTo(37_000); assertEquals(37_000L, first.currentPosition) }
        compose.runOnIdle { request.longValue++ }
        compose.waitForIdle()
        compose.runOnIdle {
            assertSame(first, findPlayer(compose.activity.window.decorView)?.player)
            assertEquals(10_000L, first.currentPosition)
        }
    }

    @androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
    @Test fun nativePlayerClipsBothDayBoundariesAndConvertsOriginalSeekOffset() {
        // 可重复生成的静音媒体用于验证真实 Media3 时间轴，不依赖板上的录像。
        val sampleBytes = 90 * 8_000 * 2
        val wav = ByteBuffer.allocate(44 + sampleBytes).order(ByteOrder.LITTLE_ENDIAN)
            .put("RIFF".toByteArray()).putInt(36 + sampleBytes).put("WAVEfmt ".toByteArray())
            .putInt(16).putShort(1).putShort(1).putInt(8_000).putInt(16_000)
            .putShort(2).putShort(16).put("data".toByteArray()).putInt(sampleBytes).array()
        val server = MockWebServer().apply {
            dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest) = MockResponse()
                    .setHeader("Content-Type", "audio/wav").setBody(Buffer().write(wav))
            }
            start()
        }
        val mediaUrl = server.url("/midnight.wav").toString()
        var finished = 0
        var phase = "准备媒体"
        try {
            compose.setContent {
                RecorderTheme {
                    ChannelArchivePlayer(mediaUrl, 37_000, 60_000,
                        active = true, scrubbing = false, seekRequestId = 1,
                        modifier = Modifier.fillMaxSize(), minimumPosition = 30_000, onEnded = { finished++ })
                }
            }
            // Media3 使用真实线程与时钟；读取其状态无需等待整个 Compose 树空闲。
            compose.waitUntil(10_000) { compose.runOnUiThread {
                val current = findPlayer(compose.activity.window.decorView)?.player
                assertNull("原生媒体准备失败", current?.playerError)
                current?.playbackState == Player.STATE_READY
            } }
            val player = compose.runOnUiThread { requireNotNull(findPlayer(compose.activity.window.decorView)?.player) }
            phase = "验证裁剪范围与原始偏移"
            compose.runOnUiThread {
                player.pause()
                assertEquals(30_000L, player.duration)
                assertTrue("原文件37秒在裁剪后应接近7秒", player.currentPosition in 7_000..9_000)
                assertEquals(30_000L, player.currentMediaItem!!.clippingConfiguration.startPositionMs)
                assertEquals(60_000L, player.currentMediaItem!!.clippingConfiguration.endPositionMs)
                player.seekTo(0)
            }
            phase = "定位当天起点"
            compose.waitUntil(5_000) { compose.runOnUiThread { player.currentPosition == 0L && player.playbackState == Player.STATE_READY } }
            phase = "播放至当天终点并完成一次"
            compose.runOnUiThread {
                // 暂停态拖到末点可能仍为 READY；播放最后一小段验证真实自然结束。
                player.seekTo((player.duration - 250).coerceAtLeast(0))
                player.play()
            }
            try {
                compose.waitUntil(5_000) { compose.runOnUiThread { player.playbackState == Player.STATE_ENDED && finished == 1 } }
            } catch (failure: Throwable) {
                val diagnostic = compose.runOnUiThread {
                    "state=${player.playbackState}, position=${player.currentPosition}, duration=${player.duration}, finished=$finished, playWhenReady=${player.playWhenReady}, error=${player.playerError}"
                }
                throw AssertionError("等待自然结束失败：$diagnostic", failure)
            }
            compose.runOnUiThread {
                // ENDED 后原生音频时钟可稍超时长；应用保存的位置必须封顶当天边界。
                assertEquals(60_000L, com.neardi.recorder.ui.ArchivePlaybackRange(30_000, 60_000)
                    .toOriginalPosition(player.currentPosition))
                assertFalse(player.isPlaying)
            }
        } catch (failure: Throwable) {
            throw AssertionError("原生跨日回放测试失败：$phase", failure)
        } finally {
            // 生命周期销毁直接释放播放器；清理阶段不再用空树 idle 遮盖正文失败。
            try { compose.activityRule.scenario.close() } finally { server.shutdown() }
        }
    }

    private fun choose(fraction: Float) {
        compose.onNodeWithTag("channel-day-timeline").performSemanticsAction(SemanticsActions.SetProgress) { assertTrue(it(fraction)) }
    }

    private fun waitForFraction(expected: Float) {
        compose.waitUntil(5_000) {
            val actual = compose.onNodeWithTag("channel-day-timeline").fetchSemanticsNode().config[SemanticsProperties.ProgressBarRangeInfo].current
            kotlin.math.abs(actual - expected) < .0005f
        }
    }

    private fun beginDrag() {
        compose.onNodeWithTag("channel-day-timeline").performTouchInput {
            down(center); moveBy(Offset(0f, -30f)); moveBy(Offset(0f, -80f))
        }
        compose.waitForIdle()
    }

    private fun findPlayer(view: View): PlayerView? {
        if (view is PlayerView) return view
        if (view is ViewGroup) for (index in 0 until view.childCount) findPlayer(view.getChildAt(index))?.let { return it }
        return null
    }
}
