package com.neardi.recorder.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.neardi.recorder.data.RecorderApi
import kotlinx.coroutines.CancellationException
import org.json.JSONObject

@Composable internal fun LogsSettings(api: RecorderApi, connected: Boolean, onSelect: (JSONObject) -> Unit) {
    var logs by remember(api.baseUrl) { mutableStateOf<List<JSONObject>>(emptyList()) }
    var error by remember(api.baseUrl) { mutableStateOf("") }
    var refresh by remember { mutableIntStateOf(0) }
    LaunchedEffect(api.baseUrl, connected, refresh) {
        if (connected) try {
            val result = api.getJson("/api/logs")
            logs = (result.optJSONArray("items") ?: result.optJSONArray("logs")).objects()
            error = ""
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            error = e.message ?: "无法读取日志"
        }
    }
    GroupedRows {
        logs.forEachIndexed { index, log ->
            OptionRow(log.optString("name"), value = bytesText(log.optDouble("size_bytes", Double.NaN)),
                route = "$index", onRoute = { onSelect(log) })
        }
    }
    if (logs.isEmpty()) Hint(if (connected) "暂无日志文件" else "连接设备后读取日志")
    if (error.isNotEmpty()) MessageCard(error)
    TextButton(onClick = { refresh++ }) { Text("刷新日志") }
    Hint("诊断包包含运行日志、设备状态和模型信息，不包含录像与事件截图。")

}

@Composable internal fun LicenseText() {
    var expanded by remember { mutableStateOf(false) }
    val context = LocalContext.current
    val text = remember { context.assets.open("licenses/apache-2.0.txt").bufferedReader().use { it.readText() } }
    GroupedRows {
        listOf("AndroidX / Jetpack Compose", "Media3", "OkHttp").forEach { name ->
            OptionRow(name, "Apache License 2.0", route = name, onRoute = { expanded = !expanded })
        }
    }
    Hint("点按组件名称可展开或收起完整许可文本。")
    if (expanded) SettingsGroup { Text(text, style = MaterialTheme.typography.bodySmall) }
}
