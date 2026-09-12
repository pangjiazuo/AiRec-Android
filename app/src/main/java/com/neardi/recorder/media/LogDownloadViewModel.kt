package com.neardi.recorder.media

import android.app.Application
import android.net.Uri
import android.provider.DocumentsContract
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.IOException

data class DownloadState(val busy: Boolean = false, val message: String? = null)

/** 日志和录像共用下载任务，旋转屏幕不会重新发起下载。 */
class LogDownloadViewModel(application: Application) : AndroidViewModel(application) {
    private val mutableState = MutableStateFlow(DownloadState())
    val state = mutableState.asStateFlow()
    fun clearMessage() { if (!mutableState.value.busy) mutableState.value = DownloadState() }
    fun download(url: String, destination: Uri, label: String = "日志", maximumBytes: Long = 16L * 1024 * 1024) {
        if (mutableState.value.busy) return
        mutableState.value = DownloadState(true, "正在下载${label}…")
        viewModelScope.launch {
            val resolver = getApplication<Application>().contentResolver
            try {
                val size = withContext(Dispatchers.IO) {
                    (resolver.openOutputStream(destination, "w") ?: throw IOException("无法写入所选文件")).use {
                        MediaNetwork.download(url, it, maximum = maximumBytes)
                    }
                }
                mutableState.value = DownloadState(false, "${label}已保存（${size / 1024} KB）")
            } catch (failure: Exception) {
                // 清理本次未完成文件，避免把不完整的 ZIP 或 MP4 当成成功下载。
                withContext(Dispatchers.IO + NonCancellable) { runCatching { DocumentsContract.deleteDocument(resolver, destination) } }
                mutableState.value = DownloadState(false, "${label}下载失败：${failure.message ?: "连接中断"}，请重试")
                if (failure is CancellationException) throw failure
            }
        }
    }
}
