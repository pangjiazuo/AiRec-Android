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
import androidx.compose.ui.graphics.graphicsLayer
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
@OptIn(ExperimentalMaterial3Api::class)
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
    var selectedLog by remember { mutableStateOf<JSONObject?>(null) }
    var category by rememberSaveable(state.endpoint, channelId) { mutableStateOf("home") }
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

    var confirmLeave by remember { mutableStateOf(false) }
    fun leave() {
        if (channelId != null && category != "home") category = channelParent(category)
        else if (channelId == null) category = when (category) { "licenses" -> "about"; "model-details" -> "model"; "log-detail" -> "logs"; else -> "home" }
        else if (dirty && !state.saving) confirmLeave = true
        else onBack?.invoke()
    }
    BackHandler(enabled = channelId != null || category != "home") { leave() }


    // 草稿存为字符串，使屏幕旋转时保留尚未保存的输入。
    LaunchedEffect(state.config, state.saving, state.saveResults, awaitingSaveResult) {
        val config = state.config
        if (awaitingSaveResult) {
            if (state.saving) return@LaunchedEffect
            // 保存跨越旋转时，旧页面的回调已失效；读取 VM 保留的独立保存结果。
            val result = state.saveResults[saveScope] ?: return@LaunchedEffect
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
        } else endpointError = "地址无效，请填写 http://192.168.10.209:8080 这样的设备地址。"
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

    val fieldSheet = channelId != null && category in listOf("resolution", "preview", "record-fps")
    val visibleCategory = if (fieldSheet) channelParent(category) else category
    NestedNavigation(channelId != null || category != "home")
    val footer: (@Composable () -> Unit)? = if (needsConfig && draftJson.isNotEmpty() && (category != "home" || dirty || feedback.isNotEmpty())) ({
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (feedback.isNotEmpty()) Text(feedback, color = RecorderMuted, style = MaterialTheme.typography.bodySmall)
                Button(shape = RoundedCornerShape(10.dp), onClick = {
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
                }, modifier = Modifier.fillMaxWidth().heightIn(min = 46.dp).testTag("save-config"), enabled = editable && state.connected) {
                    if (state.saving) { CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp, color = MaterialTheme.colorScheme.onPrimary); Spacer(Modifier.width(8.dp)) }
                    Text(if (state.saving) "正在保存…" else "保存并应用")
                }
                if (!state.connected) Text("离线时保留草稿；连接恢复后可保存。", color = RecorderMuted, style = MaterialTheme.typography.bodySmall)
            }
    }) else if (channelId == null && category == "connection") ({
        Button(shape = RoundedCornerShape(10.dp), onClick = { if (dirty && endpointText.trim() != state.endpoint) pendingEndpoint = endpointText else connect(endpointText) },
            modifier = Modifier.fillMaxWidth().heightIn(min = 46.dp), enabled = editable) { Text("连接设备") }
    }) else if (channelId == null && category in listOf("logs", "log-detail")) ({
        Button(shape = RoundedCornerShape(10.dp), onClick = onDownloadLogs, enabled = state.connected, modifier = Modifier.fillMaxWidth().heightIn(min = 46.dp)) { Text("下载诊断包") }
    }) else null
    PhonePage(if (channelId != null) channelPageTitle(visibleCategory) else when (category) {
                "connection" -> "设备连接"
                "storage" -> "录像存储"
                "model" -> "识别模型"
                "model-details" -> "模型说明"
                "log-detail" -> "日志详情"
                "logs" -> "日志与诊断"
                "appearance" -> "外观与显示"
                "device" -> "设备信息"
                "about" -> "关于 AiRec"
                "licenses" -> "第三方许可"
                else -> "设置"
            }, onBack = if (channelId != null || category != "home") ({ leave() }) else null,
        backTag = if (channelId != null) "channel-settings-back" else "settings-category-back", footer = footer) {
        if (channelId == null && category == "home") {
            Surface(onClick = { category = "device" }, shape = RoundedCornerShape(12.dp)) { SettingsDeviceCard(state.endpoint, state.connected) }
            Surface(color = MaterialTheme.colorScheme.surface, shape = RoundedCornerShape(12.dp)) {
                Column {
                    SettingsCategory("connection", "设备连接", "地址与自动重连") { category = it }
                    SettingsCategory("storage", "录像存储", "存储介质与循环录像", showDivider = false) { category = it }
                }
            }
            Surface(color = MaterialTheme.colorScheme.surface, shape = RoundedCornerShape(12.dp)) {
                Column {
                    SettingsCategory("model", "识别模型", "YOLO、ByteTrack 与硬件状态") { category = it }
                    SettingsCategory("logs", "日志与诊断", "查看并下载诊断日志", showDivider = false) { category = it }
                }
            }
            Surface(color = MaterialTheme.colorScheme.surface, shape = RoundedCornerShape(12.dp)) {
                Column {
                    SettingsCategory("appearance", "外观与显示", "", value = appearanceLabel(appearanceMode)) { category = it }
                    SettingsCategory("about", "关于 AiRec", "", showDivider = false) { category = it }
                }
            }
        }
        if (channelId == null && category == "appearance") AppearanceSettings(appearanceMode, appearance::setMode)
        if (channelId == null && category == "connection") {
            SettingsGroup {
                SettingsInput("录像机地址", endpointText, editable, numeric = false, tag = "endpoint-input") { endpointText = it; endpointError = "" }
            }
            GroupedRows {
                ReadOnlyRow("连接状态", if (state.connected) "已连接" else "正在重连")
                ReadOnlyRow("断网自动重连", "开启")
            }
            if (endpointError.isNotEmpty()) Hint(endpointError)
            Hint("手机与录像机连接同一局域网。修改地址只保存在当前手机。")
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
                ChannelOptions(channel, visibleCategory, editable, { category = it }, ::editChannel) {
                    copyTargets = channelList.map { it.optInt("id") }.filter { it != selectedId }.toSet()
                    copyDialog = true
                }
                if (fieldSheet) ModalBottomSheet(onDismissRequest = { category = channelParent(category) }, containerColor = RecorderBackground) {
                    Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text(channelPageTitle(category), Modifier.align(Alignment.CenterHorizontally), style = MaterialTheme.typography.titleLarge)
                        ChannelOptions(channel, category, editable, { category = it }, ::editChannel) {}
                        Button(onClick = { category = channelParent(category) }, modifier = Modifier.fillMaxWidth().heightIn(min = 46.dp), shape = RoundedCornerShape(10.dp)) { Text("确定") }
                        Spacer(Modifier.height(12.dp))
                    }
                }
            }

            if (channelId == null && category == "storage") {
                val targetId = storage.optString("target_id", "internal")
                val selectedTarget = targets.firstOrNull { it.optString("id") == targetId }
                val options = targets.filter { it.optBoolean("available") && it.optBoolean("writable") || it.optString("id") == targetId }.map {
                    it.optString("id") to (it.optString("label") + if (!it.optBoolean("available") || !it.optBoolean("writable")) "（不可用）" else "")
                }.toMutableList()
                if (options.none { it.first == targetId }) options.add(targetId to if (targetId == "internal") "内置存储" else "当前外置介质（待确认）")
                GroupedRows { ChoiceMenu("保存介质", options, targetId, asRow = true) { if (editable) editStorage("target_id", it) } }
                if (storageError.isNotEmpty()) Hint(storageError)
                val missingTarget = selectedTarget != null && (!selectedTarget.optBoolean("available") || !selectedTarget.optBoolean("writable"))
                if (missingTarget) {
                    MessageCard("所选保存介质当前不可用")
                    Hint("请重新接入介质，或选择其他可用介质。")
                }
                if (!missingTarget) SettingsGroup {
                    val capacity = selectedTarget ?: state.status?.optJSONObject("storage")
                    Text(bytesText(capacity?.optDouble("free_bytes", Double.NaN) ?: Double.NaN), Modifier.padding(top = 18.dp), fontSize = 24.sp)
                    Hint("可用 / 共 " + bytesText(capacity?.optDouble("total_bytes", Double.NaN) ?: Double.NaN))
                    val total = capacity?.optDouble("total_bytes", 0.0) ?: 0.0
                    val free = capacity?.optDouble("free_bytes", 0.0) ?: 0.0
                    LinearProgressIndicator(progress = { if (total > 0) (1-free/total).toFloat().coerceIn(0f,1f) else 0f },
                        modifier = Modifier.fillMaxWidth().padding(vertical = 18.dp), color = RecorderBlue)
                }
                SettingsGroup { SettingsInput("录像空间上限（GB）", storage.optString("max_gb"), editable) { editStorage("max_gb", it) } }
                SettingsGroup { SettingsInput("保留磁盘空间（GB）", storage.optString("min_free_gb"), editable) { editStorage("min_free_gb", it) } }
                Hint("达到空间限制后，自动清理本应用最旧的录像和事件截图。")
                GroupedRows { OptionRow("刷新可用介质", route = "refresh-storage", onRoute = { auxiliaryRefresh++ }) }
                Hint("外置 SD 卡需由录像机系统识别。此处不会格式化磁盘。")
            }



        }
        if (channelId == null && category == "model") ModelSettingsCard(model, modelError, onRefresh = { auxiliaryRefresh++ }, onDetails = { category = "model-details" })
        if (channelId == null && category == "logs") LogsSettings(viewModel.api, state.connected, { selectedLog = it; category = "log-detail" })
        if (channelId == null && category == "log-detail") {
            GroupedRows { ReadOnlyRow("文件名", selectedLog?.optString("name") ?: "—"); ReadOnlyRow("大小", bytesText(selectedLog?.optDouble("size_bytes", Double.NaN) ?: Double.NaN)) }
            Hint("下载诊断包后可查看完整日志。")
        }
        if (channelId == null && category == "model-details") {
            val mapping = model?.optJSONObject("category_mapping")
            GroupedRows { listOf("person", "vehicle", "animal").forEach { key ->
                Column(Modifier.padding(vertical = 13.dp)) { Text(EventNames[key] ?: key); Hint(mapping?.optJSONArray(key)?.let { a -> (0 until a.length()).joinToString("、") { a.optString(it) } } ?: "设备未提供类别说明") }
            } }
            Hint("动物并不覆盖所有物种；无法识别的类别不会被强行归类。")
            GroupedRows { model?.optJSONArray("files").objects().forEach { ReadOnlyRow("模型文件", it.optString("name")); ReadOnlyRow("校验状态", if (it.optBoolean("verified")) "校验通过" else "未通过") } }
        }
        if (channelId == null && category == "device") DeviceInformation(state.status, state.connected, state.endpoint)
        if (channelId == null && category == "about") {
            Column(Modifier.fillMaxWidth().padding(vertical = 35.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(10.dp)) {
                RecorderGlyph("camera", Modifier.size(45.dp), RecorderBlue)
                Text("AiRec", fontSize = 23.sp)
                Hint("安卓手机客户端")
            }
            GroupedRows { ReadOnlyRow("适配系统", "Android 12 及以上"); ReadOnlyRow("应用版本", com.neardi.recorder.BuildConfig.VERSION_NAME) }
            GroupedRows { OptionRow("第三方组件与许可", route = "licenses", onRoute = { category = "licenses" }) }
            Hint("通过局域网查看录像机画面、录像和事件。")
        }
        if (channelId == null && category == "licenses") LicenseText()
        Spacer(Modifier.height(12.dp))
    }

    if (confirmLeave) AlertDialog(onDismissRequest = { confirmLeave = false },
        title = { Text("修改尚未保存") }, text = { Text("可以保留草稿并返回，也可以继续编辑。草稿不会自动提交给录像机。") },
        confirmButton = { TextButton(onClick = { confirmLeave = false }) { Text("继续编辑") } },
        dismissButton = { TextButton(onClick = {
            confirmLeave = false
            if (channelId != null) onBack?.invoke() else category = "home"
        }) { Text("保留草稿并返回") } })
    if (pendingEndpoint != null) AlertDialog(onDismissRequest = { pendingEndpoint = null }, title = { Text("切换录像机") }, text = { Text("当前设置有未保存的修改。切换设备会放弃这份草稿。") },
        confirmButton = { TextButton(onClick = { val address = pendingEndpoint; pendingEndpoint = null; if (address != null) connect(address) }) { Text("切换设备") } },
        dismissButton = { TextButton(onClick = { pendingEndpoint = null }) { Text("继续编辑") } })
    if (confirmReload) AlertDialog(onDismissRequest = { confirmReload = false }, title = { Text("重新读取配置") }, text = { Text("放弃尚未保存的修改，从录像机重新读取配置。") },
        confirmButton = { TextButton(onClick = { confirmReload = false; reload() }) { Text("重新读取") } },
        dismissButton = { TextButton(onClick = { confirmReload = false }) { Text("取消") } })
    if (copyDialog && draftJson.isNotEmpty()) ModalBottomSheet(onDismissRequest = { copyDialog = false }, containerColor = RecorderBackground) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp)) {
            Text("应用到其他通道", Modifier.align(Alignment.CenterHorizontally).padding(bottom = 18.dp), style = MaterialTheme.typography.titleLarge)
            JSONObject(draftJson).getJSONArray("channels").objects().filter { it.optInt("id") != selectedId }.forEach { item ->
                val id = item.optInt("id")
                Row(Modifier.fillMaxWidth().heightIn(min = 51.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(item.optString("name"), Modifier.weight(1f))
                    Checkbox(checked = id in copyTargets, onCheckedChange = { copyTargets = if (it) copyTargets + id else copyTargets - id })
                }
                HorizontalDivider(color = RecorderLine)
            }
            Hint("保留目标通道名称、开关及接线映射；保存后才生效。")
            Button(onClick = {
                draftJson = SettingsDraft.copyChannelParameters(JSONObject(draftJson), selectedId, copyTargets).toString()
                copyDialog = false
                feedback = "参数已复制到 ${copyTargets.size} 个通道的草稿。点击保存并应用后生效。"
            }, enabled = copyTargets.isNotEmpty(), shape = RoundedCornerShape(10.dp), modifier = Modifier.fillMaxWidth().padding(top = 18.dp)) { Text("复制参数") }
            TextButton(onClick = { copyDialog = false }, modifier = Modifier.fillMaxWidth()) { Text("取消") }
        }
    }

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
        Column(Modifier.selectableGroup()) {
            listOf(AppearanceMode.SYSTEM, AppearanceMode.LIGHT, AppearanceMode.DARK).forEachIndexed { index, item ->
                Row(Modifier.fillMaxWidth().heightIn(min = 62.dp).testTag("appearance-${item.id}")
                    .selectable(selected = mode == item, role = Role.RadioButton, onClick = { onSelect(item) })
                    .padding(horizontal = 4.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(appearanceLabel(item), Modifier.weight(1f), color = RecorderInk, style = MaterialTheme.typography.bodyLarge)
                    RadioButton(selected = mode == item, onClick = null)
                }
                if (index < AppearanceMode.entries.lastIndex) HorizontalDivider(thickness = 0.5.dp, color = RecorderLine)
            }
        }
    }
    Hint("选择后立即生效，保存在此设备。跟随系统会随 Android 的外观设置自动切换。")
    Row(Modifier.fillMaxWidth().padding(18.dp), horizontalArrangement = Arrangement.spacedBy(20.dp)) {
        listOf(false, true).forEach { dark ->
            Surface(Modifier.weight(1f).height(160.dp), shape = RoundedCornerShape(9.dp), color = if (dark) Color(0xFF24272E) else Color.White, border = BorderStroke(1.dp, RecorderLine)) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                    Text(if (dark) "深色" else "浅色", color = if (dark) Color.White else Color(0xFF22252A), fontSize = 13.sp)
                    repeat(2) { Box(Modifier.fillMaxWidth().height(28.dp).background(Color(0x269BABC0), RoundedCornerShape(5.dp))) }
                }
            }
        }
    }
}

@Composable private fun SettingsCanvas(pageKey: String, content: @Composable ColumnScope.() -> Unit) {
    key(pageKey) {
        Box(Modifier.fillMaxSize().background(RecorderBackground)) {
            Column(Modifier.widthIn(max = 560.dp).fillMaxSize().align(Alignment.TopCenter)
                .verticalScroll(rememberScrollState()).padding(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 24.dp + LocalRecorderContentBottomInset.current),
                verticalArrangement = Arrangement.spacedBy(14.dp), content = content)
        }
    }
}

@Composable internal fun SettingsGroup(content: @Composable ColumnScope.() -> Unit) {
    Surface(Modifier.fillMaxWidth(), color = MaterialTheme.colorScheme.surface, shape = RoundedCornerShape(12.dp)) {
        Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(0.dp), content = content)
    }
}

@Composable private fun SettingsTitle(text: String) {
    Text(text, fontWeight = FontWeight.SemiBold, fontSize = 15.sp, color = MaterialTheme.colorScheme.onSurface)
}

@Composable private fun SettingsCategory(id: String, title: String, summary: String, showDivider: Boolean = true, value: String = "", onSelect: (String) -> Unit) {
    Column {
        Surface(onClick = { onSelect(id) }, modifier = Modifier.fillMaxWidth().testTag("settings-category-$id"), color = MaterialTheme.colorScheme.surface) {
            Row(Modifier.fillMaxWidth().heightIn(min = 62.dp).padding(horizontal = 16.dp, vertical = 13.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                RecorderGlyph(when (id) { "storage", "logs" -> "file"; "model" -> "event"; "about" -> "camera"; "appearance" -> "moon"; else -> "server" }, Modifier.size(22.dp), RecorderInk)
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Medium)
                    if (summary.isNotEmpty()) Text(summary, color = RecorderMuted, style = MaterialTheme.typography.bodySmall)
                }
                if (value.isNotEmpty()) Text(value, color = RecorderMuted, style = MaterialTheme.typography.bodySmall)
                SettingsChevron(RecorderMuted.copy(alpha = 0.6f))
            }
        }
        if (showDivider) HorizontalDivider(Modifier.padding(horizontal = 16.dp), thickness = 0.5.dp, color = RecorderLine)
    }
}

@Composable private fun SettingsDeviceCard(endpoint: String, connected: Boolean) {
    Surface(Modifier.fillMaxWidth(), color = MaterialTheme.colorScheme.surface, shape = RoundedCornerShape(12.dp)) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 20.dp),
            horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Surface(shape = RoundedCornerShape(24.dp), color = MaterialTheme.colorScheme.surfaceVariant) {
                RecorderGlyph("server", Modifier.padding(8.dp).size(26.dp), RecorderInk)
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text("家中录像机", fontSize = 17.sp, lineHeight = 20.sp)
                Text("设备信息与运行状态", style = MaterialTheme.typography.bodySmall, color = RecorderMuted)
            }
            RecorderGlyph("chevron", Modifier.size(17.dp), RecorderMuted)
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

@Composable internal fun SettingsInput(label: String, value: String, enabled: Boolean, numeric: Boolean = true,
    tag: String = "setting-$label", onChange: (String) -> Unit) {
    Column(Modifier.fillMaxWidth().padding(vertical = 16.dp)) {
        Text(label, fontSize = 12.sp, color = RecorderMuted)
        androidx.compose.foundation.text.BasicTextField(value, onChange, Modifier.fillMaxWidth().padding(top = 10.dp, bottom = 6.dp).testTag(tag),
            enabled = enabled, singleLine = true, textStyle = MaterialTheme.typography.bodyLarge.copy(color = RecorderInk, fontSize = 16.sp),
            cursorBrush = androidx.compose.ui.graphics.SolidColor(RecorderBlue),
            keyboardOptions = KeyboardOptions(keyboardType = if (numeric) KeyboardType.Decimal else KeyboardType.Text))
        HorizontalDivider(color = RecorderLine)
    }
}

@Composable internal fun SettingsSwitch(label: String, checked: Boolean, enabled: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().heightIn(min = 62.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.weight(1f), fontSize = 16.sp)
        // 保留原生开关语义和点击区域，仅按设计缩小视觉尺寸。
        Switch(checked, onChange, enabled = enabled, modifier = Modifier.graphicsLayerCompat(), colors = SwitchDefaults.colors(
            checkedTrackColor = RecorderBlue, checkedThumbColor = androidx.compose.ui.graphics.Color.White,
            uncheckedTrackColor = MaterialTheme.colorScheme.surfaceVariant, uncheckedThumbColor = androidx.compose.ui.graphics.Color.White,
            uncheckedBorderColor = Color.Transparent))
    }
}
private fun Modifier.graphicsLayerCompat() = this.then(Modifier.graphicsLayer(scaleX = .86f, scaleY = .86f))

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

@Composable private fun ModelSettingsCard(model: JSONObject?, error: String, onRefresh: () -> Unit, onDetails: () -> Unit) {
    if (error.isNotEmpty()) {
        MessageCard(error)
        GroupedRows { ReadOnlyRow("模型信息", "暂不可用"); ReadOnlyRow("视频录像", "由主机独立运行") }
        Hint("识别故障不应中断录像。请重新读取状态，或在日志与诊断中导出日志。")
        GroupedRows { OptionRow("重新读取状态", route = "model-retry", onRoute = { onRefresh() }) }
        return
    }
    GroupedRows {
        ReadOnlyRow("模型", model?.optString("name") ?: "—")
        ReadOnlyRow("跟踪器", model?.optString("tracker") ?: "—")
        ReadOnlyRow("推理后端", model?.optString("backend") ?: "—")
        ReadOnlyRow("RKNN 运行库", model?.optString("sdk_version")?.substringBefore(" (") ?: "—")
    }
    GroupedRows {
        ReadOnlyRow("NPU", if (model?.optJSONObject("npu")?.optBoolean("used") == true) "正在推理" else "未运行")
        ReadOnlyRow("VPU", if (model?.optJSONObject("vpu")?.optBoolean("used") == true) "正在编码" else "未运行")
        val files = model?.optJSONArray("files").objects()
        ReadOnlyRow("模型文件", if (files.isNotEmpty() && files.all { it.optBoolean("verified") }) "校验通过" else "未确认")
    }
    GroupedRows { OptionRow("类别与模型说明", route = "model-details", onRoute = { onDetails() }) }
    Hint("状态根据设备返回结果显示，未运行时不会标为已启用。")
    TextButton(onClick = onRefresh) { Text("重新读取状态") }
}

@Composable private fun SettingsValueRow(label: String, value: String) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.Top) {
        Text(label, Modifier.width(80.dp), color = RecorderMuted, style = MaterialTheme.typography.bodySmall)
        Text(value, Modifier.weight(1f), color = MaterialTheme.colorScheme.onSurface, style = MaterialTheme.typography.bodySmall)
    }
}
