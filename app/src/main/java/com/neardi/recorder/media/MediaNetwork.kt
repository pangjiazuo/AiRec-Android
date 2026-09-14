package com.neardi.recorder.media

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.io.OutputStream
import java.util.concurrent.TimeUnit

object MediaNetwork {
    private val client = OkHttpClient.Builder().connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(8, TimeUnit.SECONDS).followRedirects(false).followSslRedirects(false).build()

    private suspend fun <T> request(url: String, block: suspend (okhttp3.Response) -> T): T = coroutineScope {
        val call = client.newCall(Request.Builder().url(url).build())
        // 取消页面任务时主动断开socket，让后台阻塞读取立即退出。
        val cancelGuard = launch(start = CoroutineStart.UNDISPATCHED) {
            try { awaitCancellation() } finally { call.cancel() }
        }
        try {
            withContext(Dispatchers.IO) {
                call.execute().use { response ->
                    if (!response.isSuccessful) throw IOException(when (response.code) {
                        404 -> "文件不存在或已被循环清理"
                        503 -> "通道暂无画面或连接繁忙"
                        else -> "请求失败（${response.code}）"
                    })
                    block(response)
                }
            }
        } finally { cancelGuard.cancel() }
    }

    private fun decode(bytes: ByteArray): Bitmap {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        if (bounds.outWidth !in 1..4096 || bounds.outHeight !in 1..4096 ||
            bounds.outWidth.toLong() * bounds.outHeight > 8_000_000) throw IOException("图片尺寸无效")
        val options = BitmapFactory.Options().apply { inPreferredConfig = Bitmap.Config.RGB_565 }
        return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options) ?: throw IOException("图片解码失败")
    }

    suspend fun stream(url: String, onFrame: suspend (Bitmap) -> Unit) = request(url) { response ->
        if (response.header("Content-Type")?.startsWith("multipart/", true) != true) throw IOException("不是MJPEG预览")
        val input = response.body?.byteStream()?.buffered(64 * 1024) ?: throw IOException("预览为空")
        val reader = MjpegReader(input)
        while (true) {
            currentCoroutineContext().ensureActive()
            onFrame(decode(reader.nextFrame()))
        }
    }

    suspend fun image(url: String): Bitmap = request(url) { response ->
        val source = response.body?.source() ?: throw IOException("图片为空")
        source.request(4L * 1024 * 1024 + 1)
        if (source.buffer.size > 4L * 1024 * 1024) throw IOException("图片过大")
        decode(source.readByteArray())
    }

    suspend fun download(url: String, output: OutputStream, maximum: Long = 16L * 1024 * 1024, onProgress: (Long, Long) -> Unit = { _, _ -> }): Long = request(url) { response ->
        val body = response.body ?: throw IOException("下载内容为空")
        if (body.contentLength() > maximum) throw IOException("下载文件超过大小限制")
        var total = 0L
        val input = body.byteStream()
        val buffer = ByteArray(64 * 1024)
        while (true) {
            currentCoroutineContext().ensureActive()
            val count = input.read(buffer)
            if (count < 0) break
            total += count
            if (total > maximum) throw IOException("下载文件超过大小限制")
            output.write(buffer, 0, count)
            onProgress(total, body.contentLength())
        }
        output.flush()
        total
    }
}
