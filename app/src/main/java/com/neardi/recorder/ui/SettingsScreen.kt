package com.neardi.recorder.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.neardi.recorder.data.ConfigMerge
import com.neardi.recorder.data.AppearanceMode
import com.neardi.recorder.data.AppearanceViewModel
import com.neardi.recorder.data.RecorderViewModel
import com.neardi.recorder.ui.settings.SettingsDraft
import com.neardi.recorder.ui.settings.SettingsCategoryIcon
import com.neardi.recorder.ui.settings.RecorderIllustration
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject

@Composable
fun SettingsScreen(viewModel: RecorderViewModel, onDownloadLogs: () -> Unit) {
    SettingsEditorScreen(viewModel, onDownloadLogs)
}

/** 全局与单路设置共用草稿、校验和保存流程，各入口仅展示自己的配置。 */
@Composable
internal fun SettingsEditorScreen(viewModel: RecorderViewModel, onDownloadLogs: () -> Unit,
                                  channelId: Int? = null, onBack: (() -> Unit)? = null) {
    val state by viewModel.state.collectAsState()
    val appearance = androidx.lifecycle.viewmodel.compose.viewModel<AppearanceViewModel>()
    val appearanceMode by appearance.mode.collectAsState()
    var endpointText by rememberSaveable(state.endpoint) { mutableStateOf(state.endpoint) }
    var originalJson by rememberSaveable(state.endpoint, channelId) { mutableStateOf("") }
    var draftJson by rememberSaveable(state.endpoint, channelId) { mutableStateOf("") }
    var awaitingSaveResult by rememberSaveable(state.endpoint, channelId) { mutableStateOf(false) }
    val selectedId = channelId ?: 1
    val saveScope = channelId?.let { "channel:$it" } ?: "global"
    var category by rememberSaveable(state.endpoint) { mutableStateOf("home") }
    var feedback by rememberSaveable(state.endpoint, channelId) { mutableStateOf("") }
    var endpointError by rememberSaveable { mutableStateOf("") }
    var pendingEndpoint by remember { mutableStateOf<String?>(null) }
    var confirmReload by remember { mutableStateOf(false) }
    var copyDialog by remember { mutableStateOf(false) }
    var copyTargets by remember { mutableStateOf(emptySet<Int>()) }
    var targets by remember(state.endpoint) { mutableStateOf<List<JSONObject>>(emptyList()) }
    var model by remember(state.endpoint) { mutableStateOf<JSONObject?>(null) }
    var storageError by remember(state.endpoint) { mutableStateOf("") }
    var modelError by remember(state.endpoint) { mutableStateOf("") }
    var auxiliaryRefresh by remember { mutableIntStateOf(0) }
    val dirty = originalJson.isNotEmpty() && draftJson != originalJson
    val editable = !state.saving && !awaitingSaveResult
    val needsConfig = channelId != null || category == "storage"

    BackHandler(enabled = channelId != null || category != "home") {
        if (channelId != null) onBack?.invoke() else category = "home"
    }

    // 草稿存为字符串，使屏幕旋转时保留尚未保存的输入。
    LaunchedEffect(state.config, state.saving, state.saveResults, awaitingSaveResult) {
        val config = state.config
        if (awaitingSaveResult) {
            if (state.saving) return@LaunchedEffect
            // 保存跨越旋转时，旧页面的回调已失效；读取 VM 保留的独立保存结果。
            val result = state.saveResults[saveScope]
            if (result?.succeeded == true && config != null) {
                originalJson = config.toString()
                draftJson = originalJson
                feedback = if (result.restartRequired) "配置已保存，服务提示部分设置需要板端重启后生效。"
                    else "设置已保存并应用。受影响通道可能需要几秒重新连接。"
            } else feedback = result?.error ?: "尚未确认保存成功，草稿已保留，请重试。"
            awaitingSaveResult = false
        } else if (config != null && (!dirty || draftJson.isEmpty())) {
            originalJson = config.toString()
            draftJson = originalJson
        }
    }
    LaunchedEffect(state.endpoint) { viewModel.refreshConfig() }
    LaunchedEffect(state.endpoint, auxiliaryRefresh, state.connected, category) {
        if (!state.connected) return@LaunchedEffect
        coroutineScope {
            if (channelId == null && category == "storage") launch {
                try {
                    targets = viewModel.api.getJson("/api/storage/targets").optJSONArray("targets").objects()
                    storageError = ""
                } catch (error: Exception) {
                    if (error is CancellationException) throw error
                    storageError = error.message ?: "无法读取保存介质"
                }
            }
            if (channelId == null && category == "model") launch {
                try {
                    model = viewModel.api.getJson("/api/diagnostics/model")
                    modelError = ""
                } catch (error: Exception) {
                    if (error is CancellationException) throw error
                    modelError = error.message ?: "无法读取模型信息"
                }
            }
        }
    }

    fun connect(address: String) {
        if (viewModel.setEndpoint(address)) {
            endpointError = ""
            feedback = "正在连接设备，保存的录像机设置不受影响。"
        } else endpointError = "地址无效，请填写 http://192.168.10.172:8080 这样的设备地址。"
    }
    fun editChannel(key: String, value: Any) {
        val draft = JSONObject(draftJson)
        val channel = draft.getJSONArray("channels").objects().first { it.optInt("id") == selectedId }
        val parts = key.split('.')
        if (parts.size == 2) channel.getJSONObject(parts[0]).put(parts[1], value) else channel.put(key, value)
        draftJson = draft.toString()
        feedback = ""
    }
    fun editStorage(key: String, value: Any) {
        val draft = JSONObject(draftJson)
        draft.getJSONObject("storage").put(key, value)
        draftJson = draft.toString()
        feedback = ""
    }
    fun reload() {
        draftJson = ""
        originalJson = ""
        feedback = ""
        viewModel.refreshConfig()
        auxiliaryRefresh++
    }

    SettingsCanvas(if (channelId != null) "channel:$channelId" else category) {
        Box(Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
            if (channelId != null || category != "home") TextButton(onClick = {
                if (channelId != null) onBack?.invoke() else category = "home"
            }, modifier = Modifier.align(Alignment.CenterStart).testTag(if (channelId != null) "channel-settings-back" else "settings-category-back"),
                contentPadding = PaddingValues(end = 8.dp)) {
                SettingsChevron(RecorderBlue, reverse = true)
                Spacer(Modifier.width(5.dp))
                Text("返回")
            }
            Text(if (channelId != null) "通道设置" else when (category) {
                "connection" -> "设备连接"
                "storage" -> "录像存储"
                "model" -> "识别模型"
                "logs" -> "日志与诊断"
                "appearance" -> "外观与显示"
                else -> "设置"
            }, Modifier.align(if (channelId == null && category == "home") Alignment.CenterStart else Alignment.Center),
                fontSize = if (channelId == null && category == "home") 32.sp else 18.sp, fontWeight = FontWeight.SemiBold)
            if (needsConfig && draftJson.isNotEmpty()) Surface(Modifier.align(Alignment.CenterEnd),
                color = if (dirty) MaterialTheme.colorScheme.primaryContainer else Color.Transparent, shape = RoundedCornerShape(8.dp)) {
                Text(if (dirty) "未保存" else "已同步", Modifier.padding(horizontal = 8.dp, vertical = 6.dp), fontSize = 11.sp,
                    color = if (dirty) RecorderBlue else RecorderMuted)
            }
        }
        if (channelId == null && category == "home") {
            SettingsDeviceCard(state.endpoint, state.connected)
            Surface(color = MaterialTheme.colorScheme.surface, shape = RoundedCornerShape(24.dp)) {
                Column {
                    SettingsCategory("connection", "设备连接", "地址与自动重连") { category = it }
                    SettingsCategory("storage", "录像存储", "存储介质与循环录像", showDivider = false) { category = it }
                }
            }
            Surface(color = MaterialTheme.colorScheme.surface, shape = RoundedCornerShape(24.dp)) {
                Column {
                    SettingsCategory("model", "识别模型", "模型版本与硬件状态") { category = it }
                    SettingsCategory("logs", "日志与诊断", "查看与下载日志", showDivider = false) { category = it }
                }
            }
            Surface(color = MaterialTheme.colorScheme.surface, shape = RoundedCornerShape(24.dp)) {
                SettingsCategory("appearance", "外观与显示", appearanceLabel(appearanceMode), showDivider = false) { category = it }
            }
            Text("通道配置在实时画面的通道详情中。", Modifier.padding(horizontal = 8.dp), color = RecorderMuted, style = MaterialTheme.typography.bodySmall)
        }
        if (channelId == null && category == "appearance") AppearanceSettings(appearanceMode, appearance::setMode)
        if (channelId == null && category == "connection") {
            SettingsGroup {
                OutlinedTextField(endpointText, { endpointText = it; endpointError = "" }, Modifier.fillMaxWidth().testTag("endpoint-input"), label = { Text("录像机地址", fontSize = 12.sp) }, singleLine = true,
                    enabled = editable, shape = RoundedCornerShape(12.dp), colors = settingsFieldColors(), textStyle = MaterialTheme.typography.bodyMedium,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri), isError = endpointError.isNotEmpty())
                if (endpointError.isNotEmpty()) Text(endpointError, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        SettingsConnectionState(state.connected)
                        if (!state.connected) Text(state.error ?: "断网后会自动重新连接", color = RecorderMuted, style = MaterialTheme.typography.bodySmall)
                    }
                    Button(onClick = { if (dirty && endpointText.trim() != state.endpoint) pendingEndpoint = endpointText else connect(endpointText) }, modifier = Modifier.heightIn(min = 48.dp), enabled = editable) { Text("连接设备") }
                }
                Text("手机与录像机需处于同一局域网；地址保存在本机。", style = MaterialTheme.typography.bodySmall, color = RecorderMuted)
            }
        }

        if (needsConfig && draftJson.isEmpty()) {
            SettingsGroup {
                Text(if (state.connected) "正在读取设备配置…" else "设备连接后即可加载配置。")
                if (feedback.isNotEmpty()) Text(feedback, color = RecorderMuted, style = MaterialTheme.typography.bodySmall)
                OutlinedButton(onClick = { viewModel.refreshConfig(); auxiliaryRefresh++ }) { Text("重新读取") }
            }
        } else if (needsConfig) {
            val draft = JSONObject(draftJson)
            val channelList = draft.getJSONArray("channels").objects()
            val channel = channelList.firstOrNull { it.optInt("id") == selectedId } ?: channelList.first()
            val recording = channel.getJSONObject("recording")
            val detection = channel.getJSONObject("detection")
            val storage = draft.getJSONObject("storage")
            if (channelId != null) {
                SettingsGroup {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            SettingsTitle(channel.optString("name", "AHD$selectedId"))
                            Text("AHD $selectedId · 独立通道配置", color = RecorderMuted, style = MaterialTheme.typography.bodySmall)
                        }
                        TextButton(onClick = { if (dirty) confirmReload = true else reload() }, enabled = editable) { Text("重新读取") }
                    }
                    OutlinedButton(onClick = { copyTargets = channelList.map { it.optInt("id") }.filter { it != selectedId }.toSet(); copyDialog = true }, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("copy-settings"), enabled = editable) { Text("应用到其他通道") }
                }

                SettingsColumns(first = {
                    SettingsGroup {
                        SettingsTitle("基本与录像")
                        SettingsSwitch("启用通道", channel.optBoolean("enabled"), editable) { editChannel("enabled", it) }
                        SettingsInput("通道名称", channel.optString("name"), editable, numeric = false) { editChannel("name", it) }
                        Text("视频设备：${channel.optString("source")} · 画面映射保持原设置", color = RecorderMuted, style = MaterialTheme.typography.bodySmall)
                        SettingsFieldPair(first = { SettingsInput("宽度（像素）", channel.optString("width"), editable) { editChannel("width", it) } },
                            second = { SettingsInput("高度（像素）", channel.optString("height"), editable) { editChannel("height", it) } })
                        HorizontalDivider(color = RecorderLine)
                        SettingsSwitch("连续录像", recording.optBoolean("enabled"), editable) { editChannel("recording.enabled", it) }
                        if (editable) ChoiceMenu("录像片段", listOf(1, 3, 5, 10).map { "$it" to "$it 分钟" }, recording.optString("segment_minutes")) { editChannel("recording.segment_minutes", it.toInt()) }
                        else Text("录像片段：${recording.optInt("segment_minutes")} 分钟")
                        SettingsFieldPair(first = { SettingsInput("录像目标 fps", channel.optString("fps"), editable) { editChannel("fps", it) } },
                            second = { SettingsInput("预览目标 fps", channel.optString("preview_fps"), editable) { editChannel("preview_fps", it) } })
                        Text("建议录像 25 fps、预览 16 fps。预览帧率不能高于录像目标；实际帧率受输入信号和负载影响。", color = RecorderMuted, style = MaterialTheme.typography.bodySmall)
                    }
                }, second = {
                    SettingsGroup {
                        SettingsTitle("智能侦测")
                        SettingsSwitch("启用侦测", detection.optBoolean("enabled"), editable) { editChannel("detection.enabled", it) }
                        val selectedCategories = detection.optJSONArray("categories")?.let { array -> (0 until array.length()).map { array.optString(it) }.toSet() } ?: emptySet()
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            listOf("person" to "人", "vehicle" to "车", "animal" to "动物").forEach { (category, label) ->
                                FilterChip(selected = category in selectedCategories, onClick = {
                                    val next = if (category in selectedCategories) selectedCategories - category else selectedCategories + category
                                    editChannel("detection.categories", JSONArray(next.sorted()))
                                }, label = { Text(label) }, modifier = Modifier.heightIn(min = 48.dp).weight(1f), border = null,
                                    shape = RoundedCornerShape(10.dp), colors = settingsChipColors(), enabled = editable)
                            }
                        }
                        SettingsInput("人 / 动物停留阈值（秒）", detection.optString("threshold_seconds"), editable) { editChannel("detection.threshold_seconds", it) }
                        SettingsInput("识别置信度（0.1～0.99）", detection.optString("confidence"), editable) { editChannel("detection.confidence", it) }
                        SettingsFieldPair(first = { SettingsInput("侦测间隔（秒）", detection.optString("sample_interval"), editable) { editChannel("detection.sample_interval", it) } },
                            second = { SettingsInput("消失容忍（秒）", detection.optString("lost_tolerance_seconds"), editable) { editChannel("detection.lost_tolerance_seconds", it) } })
                        Text("人、车、动物首次确认出现时保存一次事件。仅人和动物达到阈值后，再保存一次长时间停留事件；车辆不计时。同一目标持续出现不重复保存同类事件。", color = RecorderMuted, style = MaterialTheme.typography.bodySmall)
                    }
                })
            }

            if (channelId == null && category == "storage") {
                SettingsGroup {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                        SettingsTitle("保存介质")
                        TextButton(onClick = { auxiliaryRefresh++ }) { Text("刷新介质") }
                    }
                    if (storageError.isNotEmpty()) Text(storageError, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                    val targetId = storage.optString("target_id", "internal")
                    val selectedTarget = targets.firstOrNull { it.optString("id") == targetId }
                    val options = targets.filter { it.optBoolean("available") && it.optBoolean("writable") || it.optString("id") == targetId }.map {
                        it.optString("id") to (it.optString("label") + if (!it.optBoolean("available") || !it.optBoolean("writable")) "（不可用）" else "")
                    }.toMutableList()
                    if (options.none { it.first == targetId }) options.add(targetId to if (targetId == "internal") "内置存储" else "当前外置介质（待确认）")
                    if (editable) ChoiceMenu("保存介质", options, targetId) { editStorage("target_id", it) }
                    else Text("保存介质：${options.firstOrNull { it.first == targetId }?.second ?: targetId}")
                    if (selectedTarget != null) {
                        Text("可用 ${bytesText(selectedTarget.optDouble("free_bytes", Double.NaN))} / 共 ${bytesText(selectedTarget.optDouble("total_bytes", Double.NaN))}", color = RecorderMuted, style = MaterialTheme.typography.bodySmall)
                        val error = selectedTarget.optString("error")
                        if (error.isNotEmpty()) Text(error, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                    }
                    SettingsInput("录像空间上限（GB）", storage.optString("max_gb"), editable) { editStorage("max_gb", it) }
                    SettingsInput("保留磁盘空间（GB）", storage.optString("min_free_gb"), editable) { editStorage("min_free_gb", it) }
                    Text("达到空间限制后自动循环清理旧录像与事件截图。外置 SD 卡 / USB 存储需由板端系统挂载，刷新后可选择；客户端不会格式化磁盘。", color = RecorderMuted, style = MaterialTheme.typography.bodySmall)
                    TextButton(onClick = { if (dirty) confirmReload = true else reload() }, enabled = editable) { Text("重新读取配置") }
                }
            }

            SettingsGroup {
                Text(if (dirty) "有尚未保存的修改" else "设置已与设备同步", fontWeight = FontWeight.SemiBold, color = if (dirty) RecorderBlue else RecorderMuted)
                if (feedback.isNotEmpty()) Text(feedback, color = RecorderMuted, style = MaterialTheme.typography.bodySmall)
                Button(onClick = {
                    try {
                        val validated = SettingsDraft.normalize(JSONObject(draftJson))
                        val newTarget = validated.getJSONObject("storage").optString("target_id")
                        val oldTarget = JSONObject(originalJson).getJSONObject("storage").optString("target_id")
                        if (newTarget != oldTarget) require(targets.any { it.optString("id") == newTarget && it.optBoolean("available") && it.optBoolean("writable") }) { "所选介质当前不可用，请刷新列表后重试。" }
                        val changes = ConfigMerge.diff(JSONObject(originalJson), validated)
                        if (changes.length() == 0) {
                            // 例如只输入了名称末尾空格，归一化后无需再次写入设备。
                            originalJson = validated.toString()
                            draftJson = originalJson
                            feedback = "配置没有实际变化。"
                        } else {
                            awaitingSaveResult = true
                            feedback = "正在保存…"
                            viewModel.saveScopedConfig(saveScope, changes)
                        }
                    } catch (error: Exception) {
                        awaitingSaveResult = false
                        feedback = error.message ?: "配置无效，请检查输入。"
                    }
                }, Modifier.fillMaxWidth().heightIn(min = 50.dp).testTag("save-config"), enabled = dirty && editable && state.connected) {
                    if (state.saving) { CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp, color = MaterialTheme.colorScheme.onPrimary); Spacer(Modifier.width(8.dp)) }
                    Text(if (state.saving) "正在保存…" else "保存并应用")
                }
                if (!state.connected) Text("离线时保留草稿；连接恢复后可保存。", color = RecorderMuted, style = MaterialTheme.typography.bodySmall)
            }
        }
        if (channelId == null && category == "model") ModelSettingsCard(model, modelError, onRefresh = { auxiliaryRefresh++ })
        if (channelId == null && category == "logs") SettingsGroup {
            Text("诊断包包含日志、运行状态、模型信息和脱敏配置，便于排查问题。", color = RecorderMuted, style = MaterialTheme.typography.bodyMedium)
            Button(onClick = onDownloadLogs, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text("下载诊断日志 ZIP") }
        }
        Spacer(Modifier.height(12.dp))
    }

    if (pendingEndpoint != null) AlertDialog(onDismissRequest = { pendingEndpoint = null }, title = { Text("切换录像机") }, text = { Text("当前设置有未保存的修改。切换设备会放弃这份草稿。") },
        confirmButton = { TextButton(onClick = { val address = pendingEndpoint; pendingEndpoint = null; if (address != null) connect(address) }) { Text("切换设备") } },
        dismissButton = { TextButton(onClick = { pendingEndpoint = null }) { Text("继续编辑") } })
    if (confirmReload) AlertDialog(onDismissRequest = { confirmReload = false }, title = { Text("重新读取配置") }, text = { Text("放弃尚未保存的修改，从录像机重新读取配置。") },
        confirmButton = { TextButton(onClick = { confirmReload = false; reload() }) { Text("重新读取") } },
        dismissButton = { TextButton(onClick = { confirmReload = false }) { Text("取消") } })
    if (copyDialog && draftJson.isNotEmpty()) AlertDialog(onDismissRequest = { copyDialog = false }, title = { Text("应用到其他通道") }, text = {
        Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("复制 AHD $selectedId 的分辨率、帧率、录像与侦测参数。保留目标通道的名称、开关及接线映射；保存后才生效。", style = MaterialTheme.typography.bodySmall)
            JSONObject(draftJson).getJSONArray("channels").objects().filter { it.optInt("id") != selectedId }.forEach { item ->
                val id = item.optInt("id")
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = id in copyTargets, onCheckedChange = { copyTargets = if (it) copyTargets + id else copyTargets - id })
                    Text("$id · ${item.optString("name")}")
                }
            }
        }
    }, confirmButton = { TextButton(onClick = {
        draftJson = SettingsDraft.copyChannelParameters(JSONObject(draftJson), selectedId, copyTargets).toString()
        copyDialog = false
        feedback = "参数已复制到 ${copyTargets.size} 个通道的草稿。点击保存并应用后生效。"
    }, enabled = copyTargets.isNotEmpty()) { Text("复制参数") } }, dismissButton = { TextButton(onClick = { copyDialog = false }) { Text("取消") } })
}

private val SettingsControlBackground: Color
    @Composable get() = MaterialTheme.colorScheme.surfaceVariant

private fun appearanceLabel(mode: AppearanceMode): String = when (mode) {
    AppearanceMode.LIGHT -> "浅色"
    AppearanceMode.DARK -> "深色"
    AppearanceMode.SYSTEM -> "跟随系统"
}

@Composable private fun AppearanceSettings(mode: AppearanceMode, onSelect: (AppearanceMode) -> Unit) {
    SettingsGroup {
        SettingsTitle("界面主题")
        Column(Modifier.selectableGroup()) {
            AppearanceMode.entries.forEachIndexed { index, item ->
                Row(Modifier.fillMaxWidth().heightIn(min = 56.dp).testTag("appearance-${item.id}")
                    .selectable(selected = mode == item, role = Role.RadioButton, onClick = { onSelect(item) })
                    .padding(horizontal = 4.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(appearanceLabel(item), Modifier.weight(1f), color = RecorderInk, style = MaterialTheme.typography.bodyLarge)
                    RadioButton(selected = mode == item, onClick = null)
                }
                if (index < AppearanceMode.entries.lastIndex) HorizontalDivider(thickness = 0.5.dp, color = RecorderLine)
            }
        }
        Text("选择后立即生效，保存在此设备。跟随系统会随 Android 的外观设置自动切换。",
            color = RecorderMuted, style = MaterialTheme.typography.bodySmall)
    }
}

@Composable private fun SettingsCanvas(pageKey: String, content: @Composable ColumnScope.() -> Unit) {
    key(pageKey) {
        Box(Modifier.fillMaxSize().background(RecorderBackground)) {
            Column(Modifier.widthIn(max = if (pageKey == "home") 720.dp else 1120.dp).fillMaxSize().align(Alignment.TopCenter)
                .verticalScroll(rememberScrollState()).padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 24.dp + LocalRecorderContentBottomInset.current),
                verticalArrangement = Arrangement.spacedBy(14.dp), content = content)
        }
    }
}

@Composable private fun SettingsGroup(content: @Composable ColumnScope.() -> Unit) {
    Surface(Modifier.fillMaxWidth(), color = MaterialTheme.colorScheme.surface, shape = RoundedCornerShape(24.dp)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp), content = content)
    }
}

@Composable private fun SettingsTitle(text: String) {
    Text(text, fontWeight = FontWeight.SemiBold, fontSize = 15.sp, color = MaterialTheme.colorScheme.onSurface)
}

@Composable private fun SettingsCategory(id: String, title: String, summary: String, showDivider: Boolean = true, onSelect: (String) -> Unit) {
    Column {
        Surface(onClick = { onSelect(id) }, modifier = Modifier.fillMaxWidth().testTag("settings-category-$id"), color = MaterialTheme.colorScheme.surface) {
            Row(Modifier.fillMaxWidth().heightIn(min = 74.dp).padding(horizontal = 16.dp, vertical = 14.dp),
                horizontalArrangement = Arrangement.spacedBy(14.dp), verticalAlignment = Alignment.CenterVertically) {
                SettingsCategoryIcon(id, Modifier.size(27.dp))
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                    Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Medium)
                    Text(summary, color = RecorderMuted, style = MaterialTheme.typography.bodySmall)
                }
                SettingsChevron(RecorderMuted.copy(alpha = 0.6f))
            }
        }
        if (showDivider) HorizontalDivider(Modifier.padding(start = 64.dp), thickness = 0.5.dp, color = RecorderLine)
    }
}

@Composable private fun SettingsDeviceCard(endpoint: String, connected: Boolean) {
    Surface(Modifier.fillMaxWidth(), color = MaterialTheme.colorScheme.surface, shape = RoundedCornerShape(24.dp)) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 20.dp),
            horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.CenterVertically) {
            RecorderIllustration(Modifier.width(78.dp).height(60.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("智能录像机", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                Text(endpoint.removePrefix("http://").removePrefix("https://").trimEnd('/'),
                    style = MaterialTheme.typography.bodySmall, color = RecorderMuted, maxLines = 2, overflow = TextOverflow.Ellipsis)
                SettingsConnectionState(connected)
            }
        }
    }
}

@Composable private fun SettingsConnectionState(connected: Boolean) {
    val statusColor = if (connected) RecorderGreen else RecorderMuted
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(7.dp)) {
        Canvas(Modifier.size(7.dp)) { drawCircle(statusColor) }
        Text(if (connected) "已连接" else "正在等待设备", color = RecorderMuted, style = MaterialTheme.typography.bodySmall)
    }
}

@Composable private fun SettingsChevron(color: Color, reverse: Boolean = false) {
    Canvas(Modifier.width(9.dp).height(16.dp)) {
        val edge = if (reverse) size.width * 0.8f else size.width * 0.2f
        val point = if (reverse) size.width * 0.2f else size.width * 0.8f
        drawLine(color, Offset(edge, size.height * 0.2f), Offset(point, size.height * 0.5f), 1.5.dp.toPx(), StrokeCap.Round)
        drawLine(color, Offset(point, size.height * 0.5f), Offset(edge, size.height * 0.8f), 1.5.dp.toPx(), StrokeCap.Round)
    }
}

@Composable private fun settingsFieldColors() = OutlinedTextFieldDefaults.colors(
    focusedContainerColor = SettingsControlBackground,
    unfocusedContainerColor = SettingsControlBackground,
    disabledContainerColor = SettingsControlBackground,
    focusedBorderColor = RecorderBlue,
    unfocusedBorderColor = Color.Transparent,
    disabledBorderColor = Color.Transparent,
    focusedLabelColor = RecorderBlue,
    unfocusedLabelColor = RecorderMuted,
    cursorColor = RecorderBlue,
)

@Composable private fun settingsChipColors() = FilterChipDefaults.filterChipColors(
    containerColor = SettingsControlBackground,
    labelColor = MaterialTheme.colorScheme.onSurface,
    selectedContainerColor = MaterialTheme.colorScheme.primaryContainer,
    selectedLabelColor = RecorderBlue,
)

@Composable private fun SettingsInput(label: String, value: String, enabled: Boolean, numeric: Boolean = true, onChange: (String) -> Unit) {
    OutlinedTextField(value, onChange, Modifier.fillMaxWidth().heightIn(min = 56.dp).testTag("setting-$label"), enabled = enabled, singleLine = true,
        label = { Text(label, fontSize = 12.sp) }, shape = RoundedCornerShape(12.dp), colors = settingsFieldColors(), textStyle = MaterialTheme.typography.bodyMedium,
        keyboardOptions = KeyboardOptions(keyboardType = if (numeric) KeyboardType.Decimal else KeyboardType.Text))
}

@Composable private fun SettingsSwitch(label: String, checked: Boolean, enabled: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().heightIn(min = 48.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
        Switch(checked, onChange, enabled = enabled, colors = SwitchDefaults.colors(
            checkedTrackColor = RecorderGreen, checkedThumbColor = MaterialTheme.colorScheme.onPrimary,
            uncheckedTrackColor = MaterialTheme.colorScheme.surfaceVariant, uncheckedThumbColor = MaterialTheme.colorScheme.onSurfaceVariant,
            uncheckedBorderColor = Color.Transparent))
    }
}

@Composable private fun SettingsFieldPair(first: @Composable () -> Unit, second: @Composable () -> Unit) {
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        // 小屏不挤压标签，空间足够时才并排输入。
        if (maxWidth >= 360.dp) Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Box(Modifier.weight(1f)) { first() }
            Box(Modifier.weight(1f)) { second() }
        } else Column(verticalArrangement = Arrangement.spacedBy(12.dp)) { first(); second() }
    }
}

@Composable private fun SettingsColumns(first: @Composable () -> Unit, second: @Composable () -> Unit) {
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        if (maxWidth >= 720.dp) Row(horizontalArrangement = Arrangement.spacedBy(20.dp)) {
            Box(Modifier.weight(1f)) { first() }
            Box(Modifier.weight(1f)) { second() }
        } else Column(verticalArrangement = Arrangement.spacedBy(18.dp)) {
            first()
            second()
        }
    }
}

@Composable private fun ModelSettingsCard(model: JSONObject?, error: String, onRefresh: () -> Unit) {
    var showDetails by rememberSaveable { mutableStateOf(false) }
    SettingsGroup {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            SettingsTitle("当前模型")
            TextButton(onClick = onRefresh) { Text("刷新") }
        }
        if (error.isNotEmpty()) Text(error, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
        if (model == null) Text("设备连接后读取当前模型信息", color = RecorderMuted)
        else {
            Text(model.optString("name", "未知模型"), fontWeight = FontWeight.SemiBold)
            SettingsValueRow("版本", model.optString("version", "—"))
            SettingsValueRow("跟踪器", model.optString("tracker", "—"))
            SettingsValueRow("推理后端", model.optString("backend_label", "—"))
            val npu = model.optJSONObject("npu")
            val vpu = model.optJSONObject("vpu")
            SettingsValueRow("NPU", if (npu?.optBoolean("used") == true) "正在推理" else "当前未检测到推理")
            SettingsValueRow("VPU", if (vpu?.optBoolean("used") == true) "正在编码录像" else "当前未检测到硬件录像")
            val detectorError = model.optString("error").takeIf { it.isNotBlank() && it != "null" }
            if (detectorError != null) Text(detectorError, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            val sdk = model.optString("sdk_version").takeIf { it.isNotBlank() && it != "null" }
            if (sdk != null) SettingsValueRow("RKNN SDK", sdk)
            TextButton(onClick = { showDetails = !showDetails }) { Text(if (showDetails) "收起模型说明" else "查看模型说明") }
            if (showDetails) {
                Text("硬件使用情况按本次读取时的实际运行状态显示。", color = RecorderMuted, style = MaterialTheme.typography.bodySmall)
                model.optJSONArray("limitations")?.let { values ->
                    for (index in 0 until values.length()) Text("• ${values.optString(index)}", color = RecorderMuted, style = MaterialTheme.typography.bodySmall)
                }
            }
        }
    }
}

@Composable private fun SettingsValueRow(label: String, value: String) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.Top) {
        Text(label, Modifier.width(80.dp), color = RecorderMuted, style = MaterialTheme.typography.bodySmall)
        Text(value, Modifier.weight(1f), color = MaterialTheme.colorScheme.onSurface, style = MaterialTheme.typography.bodySmall)
    }
}
