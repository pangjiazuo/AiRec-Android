package com.neardi.recorder

import android.content.Context
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Color
import android.os.ParcelFileDescriptor
import androidx.core.view.WindowInsetsControllerCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.ViewCompat
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.geometry.Offset
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.LargeTest
import androidx.test.platform.app.InstrumentationRegistry
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okio.Buffer
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.rules.TestName
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** 所有测试读写均指向设备内 MockWebServer，不向真实录像机提交配置。 */
@RunWith(AndroidJUnit4::class)
@LargeTest
class RecorderUiTest {
    @get:Rule val compose = createEmptyComposeRule()
    @get:Rule val testName = TestName()
    private lateinit var scenario: ActivityScenario<MainActivity>
    private lateinit var server: MockWebServer
    private lateinit var fixture: BoardFixture
    private lateinit var context: Context
    private var originalEndpoint: String? = null
    private var originalAppearance: String? = null
    private val extraServers = mutableListOf<MockWebServer>()

    @Before fun launchIsolatedRecorder() {
        context = ApplicationProvider.getApplicationContext()
        val preferences = context.getSharedPreferences("recorder_connection", Context.MODE_PRIVATE)
        originalEndpoint = preferences.getString("endpoint", null)
        val appearance = context.getSharedPreferences("recorder_appearance", Context.MODE_PRIVATE)
        originalAppearance = appearance.getString("mode", null)
        InstrumentationRegistry.getArguments().getString("theme")?.let {
            check(appearance.edit().putString("mode", it).commit())
        }
        fixture = BoardFixture()
        server = MockWebServer().apply { dispatcher = fixture; start() }
        check(preferences.edit().putString("endpoint", server.url("/").toString()).commit())
        // 手动启动，确保 ViewModel 首次读取到的已经是本地测试服务。
        scenario = ActivityScenario.launch(MainActivity::class.java)
        connected()
    }

    @After fun restoreConnection() {
        if (::context.isInitialized) runCatching { capture("${testName.methodName}.png") }
        if (::scenario.isInitialized) scenario.close()
        if (::context.isInitialized) {
            val editor = context.getSharedPreferences("recorder_connection", Context.MODE_PRIVATE).edit()
            if (originalEndpoint == null) editor.remove("endpoint") else editor.putString("endpoint", originalEndpoint)
            editor.commit()
            val appearance = context.getSharedPreferences("recorder_appearance", Context.MODE_PRIVATE).edit()
            if (originalAppearance == null) appearance.remove("mode") else appearance.putString("mode", originalAppearance)
            appearance.commit()
        }
        if (::server.isInitialized) server.shutdown()
        extraServers.forEach { it.shutdown() }
    }

    @Test fun fiveChannelsKeepMissingInputsIndependentAndSupportFullscreen() {
        for (id in 1..5) {
            scrollToCamera(id)
            compose.onNodeWithTag("camera-$id").assertIsDisplayed()
            if (id != 1) compose.onNodeWithTag("camera-$id").assert(hasText("暂无信号"))
        }
        scrollToCamera(1)
        compose.onNodeWithTag("camera-1").performClick()
        compose.onNodeWithTag("channel-detail").assertIsDisplayed()
        compose.onNodeWithText("退出全屏").assertDoesNotExist()
        compose.onNodeWithTag("channel-preview-1").performTouchInput { click() }
        compose.onNodeWithText("退出全屏").assertDoesNotExist()
        compose.onNodeWithTag("channel-fullscreen").performClick()
        compose.onNodeWithText("退出全屏").assertIsDisplayed()
        compose.waitUntil(10_000) {
            compose.onAllNodesWithContentDescription("AHD1实时画面", useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithText("退出全屏").performClick()
        compose.onNodeWithTag("channel-detail").assertIsDisplayed()
        compose.onNodeWithTag("channel-detail-back").performClick()
        compose.onNodeWithTag("nav-live").assertIsDisplayed()
        assertTrue(fixture.requests.any { it.contains("/stream/1.mjpg") })
        assertFalse(fixture.requests.any { it.contains(Regex("/stream/[2-5]\\.mjpg")) })
        assertTrue(fixture.savedConfigs.isEmpty())
    }

    @Test fun recordingAndEventNavigationSendCombinedFiltersAndRetainThemAfterRotation() {
        navigate("回放")
        compose.waitUntil(10_000) { fixture.requests.any { it.startsWith("GET /api/recordings") } }
        compose.onNodeWithText("录像回放").assertIsDisplayed()
        compose.waitUntil(10_000) { compose.onAllNodesWithText("播放录像").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("播放录像").assertIsNotEnabled()
        navigate("事件")
        compose.onNodeWithTag("event-filter-vehicle").performClick()
        compose.onNodeWithText("通道：全部 ▾").performClick()
        compose.onNodeWithText("AHD2", substring = false).performClick()
        compose.waitUntil(10_000) { fixture.requests.any { it.contains("channel_id=2") && it.contains("event_type=vehicle") } }
        compose.waitUntil(10_000) { compose.onAllNodesWithText("车出现").fetchSemanticsNodes().isNotEmpty() }
        rotate()
        compose.onNodeWithTag("event-filter-vehicle").assertIsDisplayed().assertIsSelected()
        compose.onNodeWithText("通道：AHD2 ▾").assertIsDisplayed()
        compose.onNodeWithText("智能事件").assertIsDisplayed()
        assertTrue(fixture.savedConfigs.isEmpty())
    }

    @Test fun editedDwellSurvivesRotationAndCopiesToOtherChannelsBeforeSaving() {
        openChannelSettings(1)
        compose.onNodeWithTag("setting-人 / 动物停留阈值（秒）").performScrollTo().performTextReplacement("12")
        compose.onNodeWithTag("setting-通道名称").performScrollTo().performTextReplacement("入口测试")
        rotate()
        compose.onNodeWithTag("setting-通道名称").performScrollTo().assertTextContains("入口测试")
        compose.onNodeWithTag("setting-人 / 动物停留阈值（秒）").performScrollTo().assertTextContains("12")
        compose.onNodeWithTag("copy-settings").performScrollTo().performClick()
        compose.onNodeWithText("复制参数").performClick()
        fixture.putResponseDelayMillis = 2_000
        compose.onNodeWithTag("save-config").performScrollTo().assertIsEnabled().performClick()
        compose.waitUntil(10_000) { fixture.savedConfigs.isNotEmpty() }
        compose.onNodeWithTag("save-config").assertTextContains("正在保存…")
        // 响应仍在路上时重建页面，成功结果应由 ViewModel 传给新页面。
        rotate()
        compose.waitUntil(10_000) { compose.onAllNodesWithText("设置已与设备同步").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("save-config").performScrollTo().assertIsNotEnabled()
        val saved = fixture.savedConfigs.last()
        val channels = saved.getJSONArray("channels")
        assertEquals(5, channels.length())
        for (index in 0 until channels.length()) {
            val channel = channels.getJSONObject(index)
            assertEquals(12.0, channel.getJSONObject("detection").getDouble("threshold_seconds"), 0.0)
            assertEquals(if (index == 0) "/dev/video5" else "/dev/video0", channel.getString("source"))
            assertEquals(if (index == 0) "入口测试" else "AHD${index + 1}", channel.getString("name"))
            assertEquals(BoardFixture.config().getJSONArray("channels").getJSONObject(index).getJSONArray("crop").toString(), channel.getJSONArray("crop").toString())
        }
        assertEquals("preserve-me", saved.getJSONObject("server").getString("test_extension"))
        // 一次用户保存只有一次 PUT，轮询、旋转和批量复制均不会暗中提交。
        assertEquals(1, fixture.savedConfigs.size)
    }

    @Test fun unavailableServiceRecoversWithoutLeavingCurrentPage() {
        navigate("事件")
        compose.onNodeWithTag("event-filter-animal").performClick()
        fixture.unavailable = true
        compose.waitUntil(15_000) {
            compose.onAllNodesWithTag("connection-status").fetchSemanticsNodes().isNotEmpty()
        }
        fixture.unavailable = false
        connected()
        compose.onNodeWithTag("event-filter-animal").assertIsDisplayed().assertIsSelected()
        compose.waitUntil(10_000) { compose.onAllNodesWithText("动物出现").fetchSemanticsNodes().isNotEmpty() }
        assertTrue(fixture.savedConfigs.isEmpty())
    }

    @Test fun recordingDatesAndTimelineGapsRetainSelectionAfterRotation() {
        // 固定两天的可用片段；点无录像区不得误开播放，也不能提交配置。
        fixture.recordingRows = JSONArray().put(JSONObject()
            .put("id", "day-newer").put("channel_id", 1).put("created_at", "2026-09-08T10:00:00+00:00")
            .put("name", "较新日期片段").put("duration_seconds", 60).put("size_bytes", 1_024_000)
            .put("available", true).put("url", "/media/newer.mp4"))
            .put(JSONObject().put("id", "day-older").put("channel_id", 1).put("created_at", "2026-09-07T10:00:00+00:00")
                .put("name", "较早日期片段").put("duration_seconds", 60).put("size_bytes", 1_024_000)
                .put("available", true).put("url", "/media/older.mp4"))
        navigate("回放")
        compose.waitUntil(10_000) { compose.onAllNodesWithText("较新日期片段").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("较早日期片段").assertDoesNotExist()
        compose.onNodeWithTag("recording-timeline-1").assertDoesNotExist()
        compose.onNodeWithTag("playback-toggle").assertDoesNotExist()
        assertFalse(fixture.requests.any { it.contains("/media/newer.mp4") })
        capture("recording-timeline-newer.png")
        compose.onNodeWithTag("recording-date-previous").performClick()
        compose.waitUntil(10_000) { compose.onAllNodesWithText("较早日期片段").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("较新日期片段").assertDoesNotExist()
        rotate()
        compose.onNodeWithText("较早日期片段").assertExists()
        compose.onNodeWithText("较新日期片段").assertDoesNotExist()
        capture("recording-timeline-rotated.png")
        compose.onNodeWithTag("recording-date-next").performScrollTo().performClick()
        compose.waitUntil(10_000) { compose.onAllNodesWithText("较新日期片段").fetchSemanticsNodes().isNotEmpty() }
        assertTrue(fixture.savedConfigs.isEmpty())
    }

    @Test fun changingRecorderNeverRestoresThePreviousDevicesUnsavedDraft() {
        val nextFixture = BoardFixture(firstChannelName = "第二台录像机")
        val nextServer = MockWebServer().apply { dispatcher = nextFixture; start() }
        extraServers.add(nextServer)
        val nextAddress = nextServer.url("/").toString().removeSuffix("/")
        openChannelSettings(1)
        compose.onNodeWithTag("setting-通道名称").performScrollTo().performTextReplacement("旧设备的未保存草稿")
        compose.onNodeWithTag("channel-settings-back").performScrollTo().performClick()
        compose.onNodeWithTag("channel-detail-back").performClick()
        navigate("设置")
        compose.onNodeWithTag("settings-category-connection").performClick()
        compose.onNodeWithTag("endpoint-input").performScrollTo().performTextReplacement(nextAddress)
        compose.onNodeWithText("连接设备").performScrollTo().performClick()
        // 地址页可能没有本页草稿；若出现确认框则确认，否则等待新端点。
        compose.waitUntil(15_000) {
            compose.onAllNodesWithText("切换设备").fetchSemanticsNodes().isNotEmpty() ||
                nextFixture.requests.any { it.startsWith("GET /api/config") }
        }
        if (compose.onAllNodesWithText("切换设备").fetchSemanticsNodes().isNotEmpty())
            compose.onNodeWithText("切换设备").performClick()
        compose.waitUntil(15_000) { nextFixture.requests.any { it.startsWith("GET /api/config") } }
        navigate("实时")
        openChannelSettings(1)
        compose.waitUntil(15_000) {
            compose.onAllNodes(hasTestTag("setting-通道名称") and hasText("第二台录像机")).fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithTag("setting-通道名称").performScrollTo().assertTextContains("第二台录像机")
        compose.onNodeWithText("旧设备的未保存草稿").assertDoesNotExist()
        assertTrue(nextFixture.requests.any { it.startsWith("GET /api/config") })
        assertTrue(fixture.savedConfigs.isEmpty())
        assertTrue(nextFixture.savedConfigs.isEmpty())
    }

    @Test fun channelDetailScopesHistoryAndRetainsItsContextAcrossFullscreenAndRotation() {
        openChannel(2)
        compose.onNodeWithTag("channel-preview-2").assertExists()
        compose.onNodeWithTag("channel-tab-recordings").performClick()
        compose.waitUntil(10_000) { fixture.requests.any { it.startsWith("GET /api/timeline?") && it.contains("channel_id=2") } }
        compose.onNodeWithTag("channel-day-timeline").assertIsDisplayed()
        compose.onNodeWithText("通道2的测试录像").assertDoesNotExist()
        capture("channel-detail-portrait.png")
        compose.onNodeWithText("通道1的测试录像").assertDoesNotExist()
        compose.onNodeWithText("通道：全部 ▾").assertDoesNotExist()
        compose.onNodeWithTag("channel-tab-events").performClick()
        compose.onNodeWithTag("event-filter-vehicle").performClick()
        compose.waitUntil(10_000) { fixture.requests.any { it.startsWith("GET /api/events?") && it.contains("channel_id=2") && it.contains("event_type=vehicle") } }
        compose.waitUntil(10_000) { compose.onAllNodesWithText("车出现").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("channel-fullscreen").performClick()
        compose.onNodeWithText("退出全屏").assertIsDisplayed().performClick()
        compose.onNodeWithTag("channel-detail").assertIsDisplayed()
        compose.onNodeWithTag("event-filter-vehicle").assertIsDisplayed().assertIsSelected()
        rotate()
        compose.onNodeWithTag("channel-preview-2").assertExists()
        compose.onNodeWithTag("event-filter-vehicle").assertIsDisplayed().assertIsSelected()
        val historyHeight = compose.onNodeWithTag("channel-history").fetchSemanticsNode().boundsInRoot.height
        assertTrue("横屏下历史列表应有至少120dp的可用高度", historyHeight >= 120f * context.resources.displayMetrics.density)
        compose.waitUntil(10_000) { compose.onAllNodesWithText("车出现").fetchSemanticsNodes().isNotEmpty() }
        capture("channel-detail-landscape.png")
        assertFalse(fixture.requests.any { it.startsWith("GET /api/recordings") && !it.contains("channel_id=2") })
        assertFalse(fixture.requests.any { it.startsWith("GET /api/events") && !it.contains("channel_id=2") })
        assertTrue(fixture.savedConfigs.isEmpty())
    }

    @Test fun channelWholeDayScrollStopsAtGapAndPreservesDateOnRotation() {
        openChannel(1)
        compose.waitUntil(10_000) { fixture.requests.any { it.startsWith("GET /api/timeline?") } }
        compose.onNodeWithTag("timeline-previous-day").performClick()
        compose.waitUntil(10_000) { compose.onNodeWithTag("channel-day-timeline").fetchSemanticsNode().config[SemanticsProperties.StateDescription] == "已加载" }
        val date = compose.onNodeWithTag("timeline-date").fetchSemanticsNode().config[SemanticsProperties.Text].joinToString { it.text }
        // 前一天默认定位日末，向下滑才能移动到更早的时刻。
        compose.onNodeWithTag("channel-day-timeline").performTouchInput { swipeDown() }
        compose.waitUntil(10_000) { compose.onAllNodesWithText("所选时间没有可用录像").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("channel-archive-player").assertDoesNotExist()
        compose.onNodeWithTag("channel-return-live").assertIsDisplayed()
        rotate()
        compose.onNodeWithTag("timeline-date").assertTextContains(date)
        compose.onNodeWithTag("channel-archive-player").assertDoesNotExist()
        compose.onNodeWithTag("channel-return-live").performClick()
        compose.onNodeWithTag("channel-preview-1").assertExists()
        assertFalse(fixture.requests.any { it.contains(".mp4") })
        assertTrue(fixture.savedConfigs.isEmpty())
    }

    @Test fun channelSelectedTimeSurvivesZoomAndActivityRotationWithoutOpeningGapVideo() {
        openChannel(1)
        compose.waitUntil(10_000) { compose.onNodeWithTag("channel-day-timeline").fetchSemanticsNode().config[SemanticsProperties.StateDescription] == "已加载" }
        compose.onNodeWithTag("channel-day-timeline").performSemanticsAction(SemanticsActions.SetProgress) { assertTrue(it(.375f)) }
        compose.waitUntil(10_000) { compose.onAllNodesWithText("所选时间没有可用录像").fetchSemanticsNodes().isNotEmpty() }
        fun selectedFraction() = compose.onNodeWithTag("channel-day-timeline").fetchSemanticsNode().config[SemanticsProperties.ProgressBarRangeInfo].current
        compose.waitUntil(10_000) { kotlin.math.abs(selectedFraction() - .375f) < .0005f }
        compose.onNodeWithTag("timeline-selected-time").assertTextContains("选中时刻", substring = true)
        compose.onNodeWithTag("timeline-precise").performClick()
        compose.waitUntil(10_000) { kotlin.math.abs(selectedFraction() - .375f) < .0005f }
        rotate()
        compose.waitUntil(10_000) { kotlin.math.abs(selectedFraction() - .375f) < .0005f }
        compose.onNodeWithTag("timeline-overview").performClick()
        compose.waitUntil(10_000) { kotlin.math.abs(selectedFraction() - .375f) < .0005f }
        compose.onNodeWithTag("channel-archive-player").assertDoesNotExist()
        assertFalse(fixture.requests.any { it.contains(".mp4") })
        assertTrue(fixture.savedConfigs.isEmpty())
    }

    @Test fun channelSettingsSaveOnlyTheChannelOpenedFromItsDetails() {
        openChannelSettings(2)
        capture("channel-settings.png")
        compose.onNodeWithTag("setting-通道名称").performScrollTo().assertTextContains("AHD2").performTextReplacement("后门二路")
        compose.onNodeWithTag("setting-人 / 动物停留阈值（秒）").performScrollTo().performTextReplacement("17")
        compose.onNodeWithTag("save-config").performScrollTo().performClick()
        compose.waitUntil(10_000) { fixture.savedConfigs.isNotEmpty() }
        compose.waitUntil(10_000) { compose.onAllNodesWithText("设置已与设备同步").fetchSemanticsNodes().isNotEmpty() }
        val saved = fixture.savedConfigs.single()
        val before = BoardFixture.config()
        for (index in 0 until 5) {
            val channel = saved.getJSONArray("channels").getJSONObject(index)
            val original = before.getJSONArray("channels").getJSONObject(index)
            if (channel.getInt("id") == 2) {
                assertEquals("后门二路", channel.getString("name"))
                assertEquals(17.0, channel.getJSONObject("detection").getDouble("threshold_seconds"), 0.0)
                assertEquals(original.getString("source"), channel.getString("source"))
                assertEquals(original.getJSONArray("crop").toString(), channel.getJSONArray("crop").toString())
            } else assertTrue("未编辑的通道必须保留", original.jsonMatches(channel))
        }
        assertTrue(before.getJSONObject("storage").jsonMatches(saved.getJSONObject("storage")))
        assertTrue(before.getJSONObject("server").jsonMatches(saved.getJSONObject("server")))
        compose.onNodeWithTag("channel-settings-back").performScrollTo().performClick()
        compose.onNodeWithTag("channel-preview-2").assertExists()
    }

    @Test fun globalSettingsCategoriesRetainStorageDraftWithoutExposingChannelFields() {
        navigate("设置")
        for (category in listOf("connection", "storage", "model", "logs", "appearance"))
            compose.onNodeWithTag("settings-category-$category").assertExists()
        capture("settings-categories.png")
        compose.onNodeWithTag("setting-通道名称").assertDoesNotExist()
        compose.onNodeWithTag("settings-category-connection").performClick()
        compose.onNodeWithTag("endpoint-input").assertExists()
        compose.onNodeWithTag("settings-category-back").performScrollTo().performClick()
        compose.onNodeWithTag("settings-category-model").performClick()
        compose.waitUntil(10_000) { fixture.requests.any { it.startsWith("GET /api/diagnostics/model") } }
        compose.onNodeWithTag("settings-category-back").performScrollTo().performClick()
        compose.onNodeWithTag("settings-category-logs").performClick()
        compose.onNodeWithTag("settings-category-back").performScrollTo().performClick()
        compose.onNodeWithTag("settings-category-storage").performClick()
        compose.waitUntil(10_000) { compose.onAllNodesWithTag("setting-录像空间上限（GB）").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("setting-录像空间上限（GB）").performScrollTo().performTextReplacement("32")
        compose.onNodeWithTag("settings-category-back").performScrollTo().performClick()
        compose.onNodeWithTag("settings-category-connection").performClick()
        compose.onNodeWithTag("settings-category-back").performScrollTo().performClick()
        compose.onNodeWithTag("settings-category-storage").performClick()
        compose.onNodeWithTag("setting-录像空间上限（GB）").performScrollTo().assertTextContains("32")
        rotate()
        compose.onNodeWithTag("setting-录像空间上限（GB）").performScrollTo().assertTextContains("32")
        assertTrue(fixture.savedConfigs.isEmpty())
        compose.onNodeWithTag("save-config").performScrollTo().performClick()
        compose.waitUntil(10_000) { fixture.savedConfigs.isNotEmpty() }
        val saved = fixture.savedConfigs.single()
        assertEquals(32, saved.getJSONObject("storage").getInt("max_gb"))
        assertTrue(BoardFixture.config().getJSONArray("channels").jsonMatches(saved.getJSONArray("channels")))
    }

    @Test fun appearancePersistsAcrossRestartAndFollowsSystemWithReadableSystemBars() {
        navigate("设置")
        compose.onNodeWithTag("settings-category-appearance").performScrollTo().performClick()
        compose.onNodeWithTag("appearance-dark").performClick()
        effectiveTheme("dark")
        compose.onNodeWithTag("appearance-dark").assertIsSelected()
        assertSystemBars(light = false)
        assertEquals("dark", context.getSharedPreferences("recorder_appearance", Context.MODE_PRIVATE).getString("mode", null))
        capture("appearance-dark.png")
        // 完整关闭并重新启动 Activity，验证来自持久化配置而非暂存的页面状态。
        scenario.close()
        scenario = ActivityScenario.launch(MainActivity::class.java)
        connected()
        effectiveTheme("dark")
        assertSystemBars(light = false)
        capture("appearance-dark-restarted.png")
        navigate("设置")
        compose.onNodeWithTag("settings-category-appearance").performScrollTo().performClick()
        compose.onNodeWithTag("appearance-dark").assertIsSelected()
        compose.onNodeWithTag("appearance-light").performClick()
        effectiveTheme("light")
        compose.onNodeWithTag("appearance-light").assertIsSelected()
        assertSystemBars(light = true)
        capture("appearance-light.png")
        compose.onNodeWithTag("appearance-system").performClick()
        compose.onNodeWithTag("appearance-system").assertIsSelected()
        try {
            systemNight("yes")
            effectiveTheme("dark")
            assertSystemBars(light = false)
            systemNight("no")
            effectiveTheme("light")
            assertSystemBars(light = true)
            assertEquals("system", context.getSharedPreferences("recorder_appearance", Context.MODE_PRIVATE).getString("mode", null))
        } finally { systemNight("no") }
        assertTrue(fixture.savedConfigs.isEmpty())
    }

    @Test fun aDifferentChannelsSuccessfulSaveNeverClearsAnEarlierFailedDraft() {
        val failureGate = CountDownLatch(1)
        fixture.nextPutFailureGate = failureGate
        try {
            openChannelSettings(1)
            compose.onNodeWithTag("setting-通道名称").performScrollTo().performTextReplacement("一号未保存草稿")
            hideKeyboard()
            compose.onNodeWithTag("save-config").performScrollTo().assertIsDisplayed().assertIsEnabled().performClick()
            compose.waitUntil(10_000) { fixture.requests.any { it.startsWith("PUT /api/config") } }
            // 保证失败响应在离开一号设置后到达，模拟慢网络下切换通道。
            compose.onNodeWithTag("channel-settings-back").performScrollTo().performClick()
            compose.onNodeWithTag("channel-detail-back").performClick()
            openChannelSettings(2)
            failureGate.countDown()
            compose.waitUntil(10_000) {
                compose.onAllNodes(hasTestTag("setting-通道名称") and isEnabled()).fetchSemanticsNodes().isNotEmpty()
            }
            compose.onNodeWithTag("setting-通道名称").performScrollTo().performTextReplacement("二号已保存")
            hideKeyboard()
            compose.onNodeWithTag("save-config").performScrollTo().assertIsDisplayed().assertIsEnabled().performClick()
            compose.waitUntil(10_000) { compose.onAllNodesWithText("设置已与设备同步").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithTag("channel-settings-back").performScrollTo().performClick()
            compose.onNodeWithTag("channel-detail-back").performClick()
            openChannelSettings(1)
            compose.onNodeWithTag("setting-通道名称").performScrollTo().assertTextContains("一号未保存草稿")
            compose.onNodeWithTag("save-config").performScrollTo().assertIsEnabled()
            compose.onNodeWithText("测试一号通道保存失败").assertExists()
            val saved = fixture.savedConfigs.single()
            assertEquals("AHD1", saved.getJSONArray("channels").getJSONObject(0).getString("name"))
            assertEquals("二号已保存", saved.getJSONArray("channels").getJSONObject(1).getString("name"))
            assertEquals(2, fixture.requests.count { it.startsWith("PUT /api/config") })
        } finally {
            failureGate.countDown()
        }
    }

    private fun connected() = compose.waitUntil(15_000) {
        fixture.requests.any { it.startsWith("GET /api/status") } &&
            compose.onAllNodesWithTag("connection-status").fetchSemanticsNodes().isEmpty()
    }

    private fun effectiveTheme(value: String) = compose.waitUntil(10_000) {
        compose.onAllNodes(hasTestTag("recorder-theme") and
            SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, value)).fetchSemanticsNodes().isNotEmpty()
    }

    private fun assertSystemBars(light: Boolean) {
        scenario.onActivity { activity ->
            val controller = WindowInsetsControllerCompat(activity.window, activity.window.decorView)
            assertEquals("状态栏文字应与主题匹配", light, controller.isAppearanceLightStatusBars)
            assertEquals("导航栏文字应与主题匹配", light, controller.isAppearanceLightNavigationBars)
        }
    }

    private fun systemNight(mode: String) {
        ParcelFileDescriptor.AutoCloseInputStream(InstrumentationRegistry.getInstrumentation().uiAutomation
            .executeShellCommand("cmd uimode night $mode")).use { it.readBytes() }
        compose.waitForIdle()
    }

    private fun hideKeyboard() {
        scenario.onActivity { activity ->
            WindowInsetsControllerCompat(activity.window, activity.window.decorView).hide(WindowInsetsCompat.Type.ime())
        }
        compose.waitUntil(10_000) {
            var visible = false
            scenario.onActivity { visible = ViewCompat.getRootWindowInsets(it.window.decorView)?.isVisible(WindowInsetsCompat.Type.ime()) == true }
            !visible
        }
        compose.waitForIdle()
    }

    private fun capture(name: String) {
        compose.waitForIdle()
        val directory = File(context.getExternalFilesDir(null), "test-results").apply { mkdirs() }
        InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()?.let { bitmap ->
            File(directory, name).outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            bitmap.recycle()
        }
    }

    private fun navigate(label: String) {
        val page = mapOf("实时" to "live", "回放" to "recordings", "事件" to "events", "设置" to "settings").getValue(label)
        compose.onNodeWithTag("nav-$page").performClick()
    }

    private fun waitForConfig() = compose.waitUntil(10_000) {
        compose.onAllNodesWithTag("setting-通道名称").fetchSemanticsNodes().isNotEmpty()
    }

    private fun openChannel(id: Int) {
        scrollToCamera(id)
        compose.onNodeWithTag("camera-$id").performClick()
        compose.onNodeWithTag("channel-detail").assertIsDisplayed()
    }

    private fun scrollToCamera(id: Int) {
        compose.onNodeWithTag("live-grid").performScrollToIndex(id)
    }

    private fun openChannelSettings(id: Int) {
        openChannel(id)
        compose.onNodeWithTag("channel-settings-entry").performClick()
        waitForConfig()
    }

    private fun rotate() {
        val previous = context.resources.configuration.orientation
        scenario.onActivity { it.requestedOrientation = if (previous == Configuration.ORIENTATION_LANDSCAPE)
            ActivityInfo.SCREEN_ORIENTATION_PORTRAIT else ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE }
        compose.waitUntil(10_000) { context.resources.configuration.orientation != previous }
        compose.waitForIdle()
    }
}

private class BoardFixture(firstChannelName: String = "AHD1") : Dispatcher() {
    val requests = ConcurrentLinkedQueue<String>()
    val savedConfigs = ConcurrentLinkedQueue<JSONObject>()
    @Volatile var unavailable = false
    @Volatile var putResponseDelayMillis = 0L
    @Volatile var nextPutFailureGate: CountDownLatch? = null
    @Volatile var recordingRows: JSONArray? = null
    private val lock = Any()
    private var settings = config().apply { getJSONArray("channels").getJSONObject(0).put("name", firstChannelName) }
    private val jpeg: ByteArray = ByteArrayOutputStream().use { output ->
        val bitmap = Bitmap.createBitmap(320, 180, Bitmap.Config.RGB_565)
        bitmap.eraseColor(Color.rgb(58, 125, 183))
        bitmap.compress(Bitmap.CompressFormat.JPEG, 75, output)
        bitmap.recycle()
        output.toByteArray()
    }

    override fun dispatch(request: RecordedRequest): MockResponse {
        requests.add("${request.method} ${request.path}")
        if (unavailable) return json(JSONObject().put("error", "测试服务暂时不可用"), 503)
        val url = request.requestUrl ?: return json(JSONObject(), 400)
        if (url.encodedPath == "/api/config" && request.method == "PUT") {
            nextPutFailureGate?.let { gate ->
                nextPutFailureGate = null
                gate.await(30, TimeUnit.SECONDS)
                return json(JSONObject().put("error", "测试一号通道保存失败"), 503)
            }
        }
        return when (url.encodedPath) {
            "/api/status" -> json(status())
            "/api/config" -> synchronized(lock) {
                if (request.method == "PUT") {
                    settings = JSONObject(request.body.readUtf8())
                    savedConfigs.add(JSONObject(settings.toString()))
                    json(JSONObject().put("ok", true).put("config", settings).put("restart_required", false))
                        .setBodyDelay(putResponseDelayMillis, TimeUnit.MILLISECONDS)
                } else json(settings)
            }
            "/api/timeline" -> json(JSONObject().put("start", url.queryParameter("start")).put("end", url.queryParameter("end"))
                .put("recordings", JSONArray()).put("event_segments", JSONArray()))
            "/api/recordings" -> json(JSONObject().put("items", recordingRows ?: JSONArray().put(JSONObject()
                .put("id", "recording-${url.queryParameter("channel_id") ?: "1"}").put("channel_id", url.queryParameter("channel_id")?.toIntOrNull() ?: 1).put("created_at", "2026-09-07T10:00:00+00:00")
                .put("name", "通道${url.queryParameter("channel_id") ?: "1"}的测试录像").put("duration_seconds", 60).put("size_bytes", 1_024_000)
                .put("available", false).put("error", "文件已被循环清理").put("url", "/media/test.mp4"))))
            "/api/events" -> {
                val wantedType = url.queryParameter("event_type")
                val wantedChannel = url.queryParameter("channel_id")?.toIntOrNull()
                val rows = JSONArray()
                listOf("person", "vehicle", "animal", "dwell").forEachIndexed { index, type ->
                    val channel = if (type == "vehicle") 2 else 1
                    if ((wantedType == null || type == wantedType) && (wantedChannel == null || channel == wantedChannel)) rows.put(
                        JSONObject().put("id", "event-$type").put("channel_id", channel).put("event_type", type)
                            .put("category", if (type == "dwell") "person" else type).put("label", type)
                            .put("dwell_seconds", if (type == "dwell") 12 else 0).put("snapshot_url", "/media/test-$index.jpg")
                            .put("created_at", "2026-09-07T10:00:00+00:00"))
                }
                json(JSONObject().put("items", rows))
            }
            "/api/storage/targets" -> json(JSONObject().put("selected_id", "internal").put("targets", JSONArray().put(
                JSONObject().put("id", "internal").put("label", "内置存储").put("available", true).put("writable", true)
                    .put("free_bytes", 40_000_000_000L).put("total_bytes", 54_000_000_000L).put("error", ""))))
            "/api/diagnostics/model" -> json(JSONObject().put("name", "YOLOv5s ReLU").put("version", "YOLOv5")
                .put("tracker", "ByteTrack").put("backend_label", "RKNN NPU").put("npu", JSONObject().put("used", true))
                .put("vpu", JSONObject().put("used", true)).put("sdk_version", "1.7.5"))
            "/stream/1.mjpg" -> {
                val body = Buffer()
                val header = "--frame\r\nContent-Type: image/jpeg\r\nContent-Length: ${jpeg.size}\r\n\r\n"
                repeat(240) { body.writeUtf8(header).write(jpeg).writeUtf8("\r\n") }
                MockResponse().setHeader("Content-Type", "multipart/x-mixed-replace; boundary=frame")
                    .setBody(body).throttleBody((header.length + jpeg.size + 2).toLong(), 80, TimeUnit.MILLISECONDS)
            }
            else -> if (url.encodedPath.startsWith("/media/") && url.encodedPath.endsWith(".jpg")) {
                MockResponse().setHeader("Content-Type", "image/jpeg").setBody(Buffer().write(jpeg))
            } else json(JSONObject().put("error", "不存在"), 404)
        }
    }

    private fun json(value: JSONObject, code: Int = 200): MockResponse = MockResponse().setResponseCode(code)
        .setHeader("Content-Type", "application/json; charset=utf-8").setBody(value.toString())

    private fun status(): JSONObject {
        val channels = JSONArray()
        for (id in 1..5) channels.put(JSONObject().put("id", id).put("name", "AHD$id").put("enabled", true)
            .put("state", if (id == 1) "online" else "no_signal").put("recording", id == 1)
            .put("fps", if (id == 1) 16 else 0).put("preview_fps", if (id == 1) 16 else 0)
            .put("recording_fps", if (id == 1) 25 else 0).put("detections", JSONArray()))
        return JSONObject().put("channels", channels).put("uptime_seconds", 120)
            .put("system", JSONObject().put("hostname", "RK3399-UI-TEST").put("temperature_c", 55.0).put("cpu_percent", 30)
                .put("memory", JSONObject().put("used_percent", 25).put("total_bytes", 4_000_000_000L)))
            .put("storage", JSONObject().put("available", true).put("label", "内置存储").put("free_bytes", 40_000_000_000L))
            .put("detector", JSONObject().put("ready", true).put("model", "YOLOv5s ReLU"))
    }

    companion object {
        fun config(): JSONObject {
            val channels = JSONArray()
            val crops = listOf("[0,0,1,1]", "[0,0,0.5,0.5]", "[0.5,0,0.5,0.5]", "[0,0.5,0.5,0.5]", "[0.5,0.5,0.5,0.5]")
            for (id in 1..5) channels.put(JSONObject().put("id", id).put("name", "AHD$id").put("enabled", true)
                .put("source", if (id == 1) "/dev/video5" else "/dev/video0").put("crop", JSONArray(crops[id - 1]))
                .put("width", 1280).put("height", 720).put("fps", 25).put("preview_fps", 16)
                .put("recording", JSONObject().put("enabled", true).put("segment_minutes", 1))
                .put("detection", JSONObject().put("enabled", true).put("categories", JSONArray("[\"person\",\"vehicle\",\"animal\"]"))
                    .put("threshold_seconds", 3).put("confidence", 0.5).put("sample_interval", 1.0).put("lost_tolerance_seconds", 2.0)))
            return JSONObject().put("channels", channels).put("server", JSONObject().put("host", "0.0.0.0").put("port", 8080).put("test_extension", "preserve-me"))
                .put("storage", JSONObject().put("target_id", "internal").put("max_gb", 24).put("min_free_gb", 3))
        }
    }
}

/** Android 的 org.json 没有 similar；按结构比较，忽略对象键顺序。 */
private fun Any?.jsonMatches(other: Any?): Boolean = when {
    this is JSONObject && other is JSONObject -> {
        val keys = keys().asSequence().toSet()
        keys == other.keys().asSequence().toSet() && keys.all { get(it).jsonMatches(other.get(it)) }
    }
    this is JSONArray && other is JSONArray -> length() == other.length() &&
        (0 until length()).all { get(it).jsonMatches(other.get(it)) }
    this is Number && other is Number -> toDouble() == other.toDouble()
    else -> this == other
}
