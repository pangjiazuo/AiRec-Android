package com.neardi.recorder.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.neardi.recorder.media.DownloadState

/** 进度只显示实际收到的字节；服务未提供总长度时使用不定进度。 */
@Composable internal fun DownloadScreen(state: DownloadState, onClose: () -> Unit, onRetry: () -> Unit) {
    PhonePage(if (state.label == "日志") "下载诊断包" else "保存${state.label}", onClose, footer = {
        Button(onClick = if (state.phase == "failed") onRetry else onClose, modifier = Modifier.fillMaxWidth().heightIn(min = 46.dp), shape = RoundedCornerShape(10.dp)) {
            Text(if (state.busy) "取消下载" else if (state.phase == "failed") "重新下载" else "完成")
        }
    }) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 25.dp, vertical = 90.dp), horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(20.dp)) {
            RecorderGlyph("file", Modifier.size(44.dp), RecorderBlue)
            Text(if (state.busy) "正在下载" else if (state.phase == "failed") "文件未能保存" else "文件已保存", style = MaterialTheme.typography.titleLarge)
            Hint(state.label)
            if (state.busy) {
                if (state.total > 0) LinearProgressIndicator(progress = { (state.received.toFloat()/state.total).coerceIn(0f,1f) }, modifier = Modifier.fillMaxWidth())
                else LinearProgressIndicator(Modifier.fillMaxWidth())
                Hint(bytesText(state.received.toDouble()) + if(state.total>0) " / " + bytesText(state.total.toDouble()) else " · 正在接收")
            } else Hint(state.message ?: "")
        }
        if (state.label == "日志") Hint("诊断包不包含录像与事件截图。")
    }
}

/** 选择系统保存位置前先展示文件用途，返回不会触发下载。 */
@Composable internal fun MediaSaveScreen(label: String, name: String, onClose: () -> Unit, onSave: () -> Unit) {
    PhonePage("保存$label", onClose, footer = {
        Button(onClick = onSave, modifier = Modifier.fillMaxWidth().heightIn(min = 46.dp), shape = RoundedCornerShape(10.dp)) { Text("选择保存位置") }
    }) {
        GroupedRows { ReadOnlyRow("文件", name); ReadOnlyRow("格式", if (label == "截图") "JPEG" else "MP4") }
        Hint("文件将保存到手机中。下一步通过系统文件选择器指定位置。")
        Hint("下载期间请保持与录像机连接，录像机仍会继续录像。")
    }
}
