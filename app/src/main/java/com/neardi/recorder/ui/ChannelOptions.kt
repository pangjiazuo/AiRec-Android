package com.neardi.recorder.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import org.json.JSONObject

internal fun channelPageTitle(route: String) = when (route) {
    "basic" -> "通道信息"
    "image" -> "图像设置"
    "resolution" -> "分辨率"
    "preview" -> "预览帧率"
    "detection" -> "智能侦测"
    "categories" -> "识别类别"
    "dwell" -> "停留检测"
    "confidence" -> "识别置信度"
    "advanced" -> "高级参数"
    "recording" -> "录像设置"
    "record-fps" -> "录像帧率"
    else -> "通道设置"
}

internal fun channelParent(route: String) = when (route) {
    "resolution", "preview" -> "image"
    "categories", "dwell", "confidence", "advanced" -> "detection"
    "record-fps" -> "recording"
    else -> "home"
}

/** 子页共用父页面草稿，切换类别不会提前提交到录像机。 */
@Composable internal fun ChannelOptions(channel: JSONObject, route: String, enabled: Boolean,
    onRoute: (String) -> Unit, onEdit: (String, Any) -> Unit, onCopy: () -> Unit) {
    val rec = channel.getJSONObject("recording")
    val detection = channel.getJSONObject("detection")
    when (route) {
        "home" -> {
            GroupedRows {
                OptionRow(channel.optString("name"), "通道名称、状态与视频来源", "", "basic", onRoute)
            }
            SettingsGroup { SettingsSwitch("启用通道", channel.optBoolean("enabled"), enabled) { onEdit("enabled", it) } }
            GroupedRows {
                OptionRow("图像设置", "分辨率与预览帧率", "", "image", onRoute)
                OptionRow("智能侦测", "识别类别、停留时间与置信度", "", "detection", onRoute)
                OptionRow("录像设置", "连续录像、片段时长与目标帧率", "", "recording", onRoute)
            }
            GroupedRows { OptionRow("应用到其他通道", "复制参数，保留通道名称和接线", "", "copy", { onCopy() }, enabled) }
        }
        "basic" -> {
          SettingsGroup { SettingsInput("通道名称", channel.optString("name"), enabled, numeric = false) { onEdit("name", it) } }
          GroupedRows {
            ReadOnlyRow("通道编号", channel.optInt("id").toString())
            ReadOnlyRow("视频来源", channel.optString("source"))
            ReadOnlyRow("画面映射", "保持当前映射")
          }
          Hint("更改名称不会影响接线和已有录像。")
        }
        "image" -> {
          GroupedRows {
            OptionRow("分辨率", "", "${channel.optInt("width")} × ${channel.optInt("height")}", "resolution", onRoute)
            OptionRow("预览目标帧率", "", "${channel.optInt("preview_fps")} fps", "preview", onRoute)
          }
          Hint("实际帧率取决于视频输入、网络和设备负载。")
          GroupedRows { ReadOnlyRow("视频来源", channel.optString("source")); ReadOnlyRow("画面映射", "保持当前设置") }
        }
        "resolution" -> SettingsGroup {
            SettingsInput("宽度（像素）", channel.optString("width"), enabled) { onEdit("width", it) }
            SettingsInput("高度（像素）", channel.optString("height"), enabled) { onEdit("height", it) }
            Hint("以录像机实际支持的输入和编码能力为准。")
        }
        "preview", "record-fps" -> SettingsGroup {
            val key = if (route == "preview") "preview_fps" else "fps"
            SettingsInput("目标帧率（fps）", channel.optString(key), enabled) { onEdit(key, it) }
            Hint("预览帧率不能高于录像目标；实际帧率受输入信号、网络和设备负载影响。")
        }
        "recording" -> {
            SettingsGroup { SettingsSwitch("连续录像", rec.optBoolean("enabled"), enabled) { onEdit("recording.enabled", it) } }
            SettingsGroup {
                ChoiceMenu("片段时长", listOf(1, 3, 5, 10).map { "$it" to "$it 分钟" }, rec.optString("segment_minutes"), asRow = true) {
                    if (enabled) onEdit("recording.segment_minutes", it.toInt())
                }
                OptionRow("录像目标帧率", "", "${channel.optInt("fps")} fps", "record-fps", onRoute)
                ReadOnlyRow("编码格式", "H.264")
            }
            Hint("保存介质和循环清理规则在全局设置的“录像存储”中管理。")
        }
        "detection" -> {
            SettingsGroup { SettingsSwitch("启用智能侦测", detection.optBoolean("enabled"), enabled) { onEdit("detection.enabled", it) } }
            Hint("关闭后不再生成新的识别事件，连续录像不受影响。")
            GroupedRows {
                val selected = detection.optJSONArray("categories")
                val names = (0 until (selected?.length() ?: 0)).mapNotNull { EventNames[selected?.optString(it)] }.joinToString("、")
                OptionRow("识别类别", "", names.ifBlank { "未选择" }, "categories", onRoute)
                OptionRow("停留检测", "", detection.optString("threshold_seconds") + " 秒", "dwell", onRoute)
                OptionRow("识别置信度", "", detection.optString("confidence"), "confidence", onRoute)
                OptionRow("高级参数", "侦测间隔、消失容忍", "", "advanced", onRoute)
            }
            Hint("人、车、动物首次确认出现时保存事件。仅人和动物达到阈值后，再保存长时间停留事件。")
        }
        "categories" -> SettingsGroup {
            val array = detection.optJSONArray("categories")
            val selected = (0 until (array?.length() ?: 0)).map { array!!.optString(it) }.toSet()
            listOf("person", "vehicle", "animal").forEach { category ->
                SettingsSwitch(EventNames.getValue(category), category in selected, enabled) {
                    onEdit("detection.categories", org.json.JSONArray((if (it) selected + category else selected - category).sorted()))
                }
            }
        }
        "dwell" -> {
            GroupedRows { ReadOnlyRow("检测对象", "人、动物") }
            SettingsGroup { SettingsInput("人 / 动物停留阈值（秒）", detection.optString("threshold_seconds"), enabled) { onEdit("detection.threshold_seconds", it) } }
            Hint("同一目标达到阈值后另存一次长时间停留事件。车辆不计停留时间。")
        }
        "confidence" -> {
            SettingsGroup { SettingsInput("识别置信度（0.1～0.99）", detection.optString("confidence"), enabled) { onEdit("detection.confidence", it) } }
            Hint("值越高，确认目标越严格；值越低，也更容易误报。")
        }
        "advanced" -> {
            SettingsGroup { SettingsInput("侦测间隔（秒）", detection.optString("sample_interval"), enabled) { onEdit("detection.sample_interval", it) } }
            SettingsGroup { SettingsInput("消失容忍（秒）", detection.optString("lost_tolerance_seconds"), enabled) { onEdit("detection.lost_tolerance_seconds", it) } }
            Hint("侦测间隔越短，计算负载越高。短暂遮挡未超过消失容忍时间时，继续跟踪原目标。")
        }
    }
}

@Composable internal fun GroupedRows(content: @Composable ColumnScope.() -> Unit) {
    Surface(Modifier.fillMaxWidth(), color = MaterialTheme.colorScheme.surface, shape = RoundedCornerShape(12.dp)) {
        Column(Modifier.padding(horizontal = 16.dp), content = content)
    }
}

@Composable internal fun OptionRow(title: String, subtitle: String = "", value: String = "", route: String,
    onRoute: (String) -> Unit, enabled: Boolean = true) {
    Surface(onClick = { onRoute(route) }, enabled = enabled, color = MaterialTheme.colorScheme.surface,
        modifier = Modifier.fillMaxWidth().testTag("option-$route")) {
        Row(Modifier.heightIn(min = if (route == "basic") 82.dp else 62.dp).padding(vertical = 13.dp), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            if (route == "basic") Surface(shape = RoundedCornerShape(24.dp), color = MaterialTheme.colorScheme.surfaceVariant) {
                RecorderGlyph("camera", Modifier.padding(8.dp).size(26.dp), RecorderInk)
            }
            if (route in listOf("image", "detection", "recording", "copy"))
                RecorderGlyph(when(route) { "detection" -> "event"; "recording" -> "playback"; "copy" -> "server"; else -> "camera" }, Modifier.size(if (route == "basic") 42.dp else 21.dp), RecorderInk)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(title, style = MaterialTheme.typography.titleMedium)
                if (subtitle.isNotEmpty()) Hint(subtitle)
            }
            if (value.isNotEmpty()) Text(value, color = RecorderMuted, style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.widthIn(max = 145.dp))
            RecorderGlyph("chevron", Modifier.size(15.dp), RecorderMuted)
        }
    }
    HorizontalDivider(color = RecorderLine, thickness = .5.dp)
}

@Composable internal fun ReadOnlyRow(label: String, value: String) {
    Row(Modifier.fillMaxWidth().heightIn(min = 62.dp), verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(label, Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
        Text(value, color = RecorderMuted, style = MaterialTheme.typography.bodySmall, modifier = Modifier.widthIn(max = 205.dp))
    }
}

@Composable internal fun Hint(text: String) { Text(text, color = RecorderMuted, style = MaterialTheme.typography.bodySmall) }
