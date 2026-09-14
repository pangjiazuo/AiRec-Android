package com.neardi.recorder.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** 标题和底部操作固定，只有页面内容滚动。所有设置子页使用同一套尺寸。 */
@Composable internal fun PhonePage(title: String, onBack: (() -> Unit)? = null,
    backTag: String = "page-back", footer: (@Composable () -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit) {
    Column(Modifier.fillMaxSize().background(RecorderBackground)) {
        PhoneHeader(title, onBack, backTag)
        Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState())
            .padding(start = 16.dp, end = 16.dp, top = 10.dp, bottom = 20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp), content = content)
        if (footer != null) Box(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 12.dp)) { footer() }
    }
}

@Composable internal fun PhoneHeader(title: String, onBack: (() -> Unit)? = null, backTag: String = "page-back") {
    Box(Modifier.fillMaxWidth().height(62.dp)) {
        Text(title, Modifier.align(Alignment.Center), fontSize = 20.sp, style = MaterialTheme.typography.titleLarge)
        if (onBack != null) IconButton(onClick = onBack,
            modifier = Modifier.align(Alignment.CenterStart).padding(start = 6.dp).testTag(backTag)) {
            RecorderGlyph("back", Modifier.size(24.dp), RecorderInk)
        }
    }
}

@Composable internal fun NestedNavigation(nested: Boolean) {
    val visibility = LocalRecorderNavigationVisibility.current
    DisposableEffect(nested) { visibility(!nested); onDispose { visibility(true) } }
}

/** 设备页显示真实状态；断开连接后不继续显示旧的运行值。 */
@Composable internal fun DeviceInformation(status: org.json.JSONObject?, connected: Boolean, endpoint: String) {
    val system = if (connected) status?.optJSONObject("system") else null
    val memory = system?.optJSONObject("memory")
    val storage = if (connected) status?.optJSONObject("storage") else null
    val channels = if (connected) status?.optJSONArray("channels").objects() else emptyList()
    GroupedRows {
        ReadOnlyRow("设备", system?.optString("hostname")?.ifBlank { "—" } ?: "—")
        ReadOnlyRow("系统", system?.optString("os")?.ifBlank { "—" } ?: "—")
        Column(Modifier.padding(vertical = 13.dp)) { Text("连接地址", fontSize = 16.sp); Hint(endpoint) }
    }
    GroupedRows {
        ReadOnlyRow("CPU 使用率", system.metric("cpu_percent", "%"))
        ReadOnlyRow("芯片温度", system.metric("temperature_c", "°C"))
        ReadOnlyRow("内存使用", bytesText(memory?.optDouble("used_bytes", Double.NaN) ?: Double.NaN) + " / " + bytesText(memory?.optDouble("total_bytes", Double.NaN) ?: Double.NaN))
        ReadOnlyRow("存储剩余", bytesText(storage?.optDouble("free_bytes", Double.NaN) ?: Double.NaN))
        ReadOnlyRow("运行时长", (if (connected) status else null).metric("uptime_seconds", " 秒"))
    }
    GroupedRows {
        ReadOnlyRow("在线通道", if (connected) "${channels.count { it.optString("state") == "online" }} / 5" else "—")
        ReadOnlyRow("录像通道", channels.filter { it.optBoolean("recording") }.joinToString("、") { it.optString("name", "AHD${it.optInt("id")}") }.ifBlank { "—" })
    }
}
