package com.neardi.recorder.data

import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Callback
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

class ApiException(val statusCode: Int?, message: String, cause: Throwable? = null) :
    IOException(message, cause)

/** 板端 HTTP API。请求取消时同时关闭底层连接，不在主线程读取网络。 */
class RecorderApi(baseUrl: String, client: OkHttpClient = defaultClient()) {
    val baseUrl: String = normalizeBaseUrl(baseUrl)
    private val endpoint: HttpUrl = this.baseUrl.toHttpUrlOrNull()!!
    private val http = client.newBuilder().followRedirects(false).followSslRedirects(false).build()

    suspend fun getJson(path: String): JSONObject {
        val url = checkedUrl(path)
        require(url.encodedPath.startsWith("/api/")) { "JSON 请求必须使用 /api/ 接口" }
        val request = Request.Builder().url(url).header("Accept", "application/json")
            .header("Cache-Control", "no-cache").get().build()
        // 仅读取请求可安全重试；未收到完整 JSON 时绝不展示半份索引。
        repeat(3) { attempt ->
            try { return requestJson(request) } catch (error: IOException) {
                val truncated = error is java.io.EOFException ||
                    (error is java.net.ProtocolException && error.message?.contains("unexpected end of stream") == true)
                if (!truncated || attempt == 2) throw error
                kotlinx.coroutines.delay(150L * (attempt + 1))
            }
        }
        error("unreachable")
    }

    /** 全天索引使用带时区的绝对时间，不把最近 200 条误当作一天。 */
    suspend fun getTimeline(channelId: Int, start: java.time.Instant, end: java.time.Instant): JSONObject {
        require(channelId in 1..5 && end > start && java.time.Duration.between(start, end) <= java.time.Duration.ofHours(26))
        val url = endpoint.newBuilder().addPathSegments("api/timeline")
            .addQueryParameter("channel_id", channelId.toString())
            .addQueryParameter("start", start.toString()).addQueryParameter("end", end.toString()).build()
        return getJson(url.toString())
    }

    suspend fun putConfig(config: JSONObject): JSONObject {
        val bytes = config.toString().toByteArray(Charsets.UTF_8)
        require(bytes.size in 1..65_536) { "配置不能超过 64 KiB" }
        return requestJson(Request.Builder().url(checkedUrl("/api/config"))
            .put(bytes.toRequestBody("application/json; charset=utf-8".toMediaType())).build())
    }

    /** 每次保存前重读整份配置，合并本次实际编辑项，保留其他通道和未知字段。 */
    suspend fun saveConfigChanges(changes: JSONObject): JSONObject =
        putConfig(ConfigMerge.merge(getJson("/api/config"), changes))

    fun mediaUrl(relative: String): String = checkedUrl(relative).toString()

    private fun checkedUrl(value: String): HttpUrl {
        require(value.isNotBlank() && value.none { it.isISOControl() || it == '\\' }) { "媒体地址无效" }
        val resolved = endpoint.resolve(value) ?: throw IllegalArgumentException("媒体地址无效")
        require(resolved.scheme == endpoint.scheme && resolved.host == endpoint.host && resolved.port == endpoint.port) {
            "拒绝访问录像机以外的媒体地址"
        }
        require(resolved.username.isEmpty() && resolved.password.isEmpty() && resolved.fragment == null) { "媒体地址无效" }
        return resolved
    }

    private suspend fun requestJson(request: Request): JSONObject = suspendCancellableCoroutine { continuation ->
        val call = http.newCall(request)
        continuation.invokeOnCancellation { call.cancel() }
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                if (continuation.isActive) continuation.resumeWithException(ApiException(null, "无法连接录像机：${e.message ?: "网络错误"}", e))
            }

            override fun onResponse(call: Call, response: Response) {
                try {
                    response.use {
                        val body = it.body ?: throw ApiException(it.code, "录像机返回空响应")
                        if (body.contentLength() > MAX_JSON_BYTES) throw ApiException(it.code, "接口响应过大")
                        val output = ByteArrayOutputStream()
                        body.byteStream().use { input ->
                            val buffer = ByteArray(8_192)
                            while (true) {
                                val count = input.read(buffer)
                                if (count < 0) break
                                if (output.size() + count > MAX_JSON_BYTES) throw ApiException(it.code, "接口响应过大")
                                output.write(buffer, 0, count)
                            }
                        }
                        val text = output.toString(Charsets.UTF_8.name())
                        val json = try { JSONObject(text) } catch (error: Exception) {
                            if (!it.isSuccessful) throw ApiException(it.code, httpMessage(it.code))
                            throw ApiException(it.code, "接口返回的不是有效 JSON", error)
                        }
                        if (!it.isSuccessful) {
                            throw ApiException(it.code, json.optString("error").takeIf(String::isNotBlank) ?: httpMessage(it.code))
                        }
                        if (continuation.isActive) continuation.resume(json)
                    }
                } catch (error: Exception) {
                    if (continuation.isActive) continuation.resumeWithException(error)
                }
            }
        })
    }

    companion object {
        const val DEFAULT_ENDPOINT = "http://192.168.10.209:8080"
        private const val MAX_JSON_BYTES = 2 * 1_024 * 1_024

        fun normalizeBaseUrl(input: String): String {
            val text = input.trim()
            require(text.isNotEmpty() && text.none { it.isWhitespace() || it.isISOControl() || it == '\\' }) { "请输入有效的录像机地址" }
            val candidate = if ("://" in text) text else "http://$text"
            val url = candidate.toHttpUrlOrNull() ?: throw IllegalArgumentException("地址格式错误，例如 192.168.10.209:8080")
            require(url.username.isEmpty() && url.password.isEmpty()) { "地址不能包含用户名或密码" }
            require(url.encodedPath == "/" && url.query == null && url.fragment == null) { "只填写主机地址和端口，无需页面路径" }
            return url.toString().removeSuffix("/")
        }

        fun defaultClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(5, TimeUnit.SECONDS).readTimeout(10, TimeUnit.SECONDS)
            .writeTimeout(10, TimeUnit.SECONDS).callTimeout(15, TimeUnit.SECONDS)
            .followRedirects(false).followSslRedirects(false).build()

        private fun httpMessage(code: Int): String = when (code) {
            404 -> "文件或接口不存在，媒体可能已被循环清理"
            503 -> "通道暂无信号或服务繁忙，请稍后重试"
            else -> "录像机请求失败（HTTP $code）"
        }
    }
}

fun JSONObject.obj(key: String): JSONObject = optJSONObject(key) ?: JSONObject()

fun JSONObject.objects(key: String): List<JSONObject> {
    val array = optJSONArray(key) ?: return emptyList()
    return (0 until array.length()).mapNotNull { array.optJSONObject(it) }
}
