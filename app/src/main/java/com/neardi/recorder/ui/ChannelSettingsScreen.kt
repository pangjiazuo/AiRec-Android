package com.neardi.recorder.ui

import androidx.compose.runtime.Composable
import com.neardi.recorder.data.RecorderViewModel

/** 从通道详情进入，固定编辑这一路；复制参数仍可选择其他通道。 */
@Composable
fun ChannelSettingsScreen(viewModel: RecorderViewModel, channelId: Int, onBack: () -> Unit) {
    SettingsEditorScreen(viewModel, onDownloadLogs = {}, channelId = channelId, onBack = onBack)
}
