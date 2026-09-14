package com.neardi.recorder

import android.graphics.Bitmap
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import com.neardi.recorder.media.DownloadState
import com.neardi.recorder.ui.DownloadScreen
import com.neardi.recorder.ui.RecorderTheme
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import java.io.File

/** 使用实际下载组件检查状态和操作，不把设计图当作运行截图。 */
class DownloadVisualTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test fun capturePlayerLayoutWithoutPrivateVideo() {
        val dark = InstrumentationRegistry.getArguments().getString("theme") == "dark"
        compose.setContent {
            RecorderTheme(dark) {
                Box(Modifier.fillMaxSize().safeDrawingPadding()) {
                    com.neardi.recorder.ui.PlaybackScreen("http://127.0.0.1:1/visual.mp4", "AHD1 · 录像回放", active = false, onDownload = {}) {}
                }
            }
        }
        compose.waitForIdle()
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val directory = File(context.getExternalFilesDir(null), "test-results").apply { mkdirs() }
        val bitmap = InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()
        File(directory, "approved-player.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
        compose.onNodeWithText("保存录像到手机").assertExists()
    }

    @Test fun captureDownloadStatesAndActions() {
        val dark = InstrumentationRegistry.getArguments().getString("theme") == "dark"
        val state = mutableStateOf(DownloadState(true, "正在下载", "running", received = 400_000, total = 1_000_000))
        var closed = false
        var retried = false
        compose.setContent {
            RecorderTheme(dark) {
                Box(Modifier.fillMaxSize().safeDrawingPadding()) {
                    DownloadScreen(state.value, { closed = true }, { retried = true })
                }
            }
        }
        fun capture(name: String) {
            compose.waitForIdle()
            val context = InstrumentationRegistry.getInstrumentation().targetContext
            val directory = File(context.getExternalFilesDir(null), "test-results").apply { mkdirs() }
            val bitmap = InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()
            File(directory, "approved-$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            bitmap.recycle()
        }
        capture("download")
        compose.onNodeWithText("取消下载").performClick()
        assertTrue(closed)
        compose.runOnIdle { state.value = state.value.copy(label = "录像") }
        capture("media-download")
        compose.runOnIdle { state.value = state.value.copy(busy = false, label = "日志", phase = "done", message = "日志已保存") }
        capture("download-done")
        compose.runOnIdle { state.value = state.value.copy(phase = "failed", message = "连接中断，请重试") }
        capture("download-failed")
        compose.onNodeWithText("重新下载").performClick()
        assertTrue(retried)
    }
}
