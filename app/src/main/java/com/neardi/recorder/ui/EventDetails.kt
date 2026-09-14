package com.neardi.recorder.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.neardi.recorder.data.RecorderApi
import com.neardi.recorder.media.LogDownloadViewModel
import org.json.JSONObject

@Composable internal fun EventDetails(api: RecorderApi, item: JSONObject, active: Boolean,
    onBack: () -> Unit, onVideo: (String, String) -> Unit) {
    val type = item.optString("event_type", "dwell")
    val title = EventNames[type] ?: "事件"
    val image = runCatching { api.mediaUrl(item.optString("snapshot_url")) }.getOrNull()
    val video = if (item.optBoolean("recording_available")) runCatching { api.mediaUrl(item.optString("recording_url")) }.getOrNull() else null
    val downloads: LogDownloadViewModel = viewModel()
    val progress by downloads.state.collectAsState()
    var confirmSave by rememberSaveable { mutableStateOf(false) }
    var pending by rememberSaveable { mutableStateOf<String?>(null) }
    val save = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("image/jpeg")) { uri ->
        val url = pending; pending = null
        if (uri != null && url != null) downloads.download(url, uri, "截图")
    }
    if (confirmSave) {
        androidx.activity.compose.BackHandler { confirmSave = false }
        MediaSaveScreen("截图", "AHD${item.optInt("channel_id")}-${item.optString("id").take(40)}.jpg", { confirmSave = false }) {
            confirmSave = false; pending = image
            save.launch("AHD${item.optInt("channel_id")}-${item.optString("id").take(40)}.jpg")
        }
        return
    }
    Column(Modifier.fillMaxSize()) {
        Box(Modifier.fillMaxWidth().height(62.dp)) {
            IconButton(onClick = onBack, modifier = Modifier.align(Alignment.CenterStart).testTag("event-detail-back")) { RecorderGlyph("back") }
            Text("事件详情", Modifier.align(Alignment.Center), style = MaterialTheme.typography.titleLarge)
        }
        Column(Modifier.fillMaxWidth().weight(1f).verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)) {
            RemoteImage(image, active, Modifier.fillMaxWidth().aspectRatio(16f / 9f))
            SettingsGroup {
                ReadOnlyRow("事件类型", title)
                ReadOnlyRow("通道", "AHD${item.optInt("channel_id")}")
                ReadOnlyRow("发生时间", dateText(item.optString("created_at")))
                ReadOnlyRow("目标类型", EventNames[item.optString("category")] ?: "—")
                if (type == "dwell") ReadOnlyRow("停留时长", item.metric("dwell_seconds", " 秒"))
            }
            GroupedRows {
                OptionRow("查看关联录像", route = "event-video", onRoute = { video?.let { onVideo(it, title) } }, enabled = video != null)
                OptionRow("保存截图", route = "event-save", onRoute = {
                    confirmSave = true
                }, enabled = image != null && !progress.busy)
            }
            if (video == null) Hint("关联录像尚未保存、已被清理或介质不可用。")
            progress.message?.let { MessageCard(it) }
        }
    }
}
