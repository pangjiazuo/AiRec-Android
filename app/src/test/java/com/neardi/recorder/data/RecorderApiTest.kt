package com.neardi.recorder.data

import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.Call
import okhttp3.EventListener
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

class RecorderApiTest {
    private lateinit var server: MockWebServer
    private lateinit var client: OkHttpClient
    private lateinit var api: RecorderApi
    private val canceled = AtomicBoolean(false)

    @Before fun setUp() {
        server = MockWebServer()
        server.start()
        client = RecorderApi.defaultClient().newBuilder().eventListener(object : EventListener() {
            override fun canceled(call: Call) { canceled.set(true) }
        }).build()
        api = RecorderApi(server.url("/").toString(), client)
    }

    @After fun tearDown() {
        client.dispatcher.cancelAll()
        client.connectionPool.evictAll()
        client.dispatcher.executorService.shutdown()
        server.shutdown()
    }

    @Test fun baseAddressNormalizesIpv4HostAndIpv6() {
        assertEquals("http://192.168.10.172:8080", RecorderApi.normalizeBaseUrl(" 192.168.10.172:8080/ "))
        assertEquals("https://recorder.local", RecorderApi.normalizeBaseUrl("https://recorder.local/"))
        assertEquals("http://[::1]:8080", RecorderApi.normalizeBaseUrl("[::1]:8080"))
    }

    @Test fun baseAddressRejectsCredentialsPathsAndOtherSchemes() {
        listOf("", "http://user:secret@board", "http://board/page", "http://board/?x=1", "http://board/#live",
            "file:///tmp/recording", "javascript:alert(1)", "http://board\\evil", "http://boa rd").forEach {
            assertThrows(IllegalArgumentException::class.java) { RecorderApi.normalizeBaseUrl(it) }
        }
    }

    @Test fun mediaCannotEscapeConfiguredOrigin() {
        assertEquals(server.url("/media/events/test.jpg").toString(), api.mediaUrl("/media/events/test.jpg"))
        listOf("https://example.com/media/test.jpg", "//example.com/test", "http://localhost:1/media/test",
            "file:///tmp/test.jpg", "\\\\example.com\\test", "javascript:alert(1)").forEach {
            assertThrows(IllegalArgumentException::class.java) { api.mediaUrl(it) }
        }
        assertThrows(IllegalArgumentException::class.java) { api.mediaUrl(server.url("/media/test").newBuilder().username("bad").build().toString()) }
    }

    @Test fun getParsesJsonAndKeepsQuery() = runBlocking {
        server.enqueue(MockResponse().setBody("{\"items\":[{\"event_type\":\"dwell\"}]}"))
        val result = api.getJson("/api/events?event_type=dwell&channel_id=2")
        assertEquals("dwell", result.getJSONArray("items").getJSONObject(0).getString("event_type"))
        assertEquals("/api/events?event_type=dwell&channel_id=2", server.takeRequest().path)
    }

    @Test fun timelineUsesCompleteDayRangeAndKeeps404ForUpgradeNotice() = runBlocking {
        val start = java.time.Instant.parse("2026-11-01T04:00:00Z")
        val end = java.time.Instant.parse("2026-11-02T05:00:00Z")
        server.enqueue(MockResponse().setResponseCode(404).setBody("{}"))
        val failure = runCatching { api.getTimeline(3, start, end) }.exceptionOrNull()
        assertEquals(404, (failure as ApiException).statusCode)
        val request = server.takeRequest().requestUrl!!
        assertEquals("/api/timeline", request.encodedPath)
        assertEquals("3", request.queryParameter("channel_id"))
        assertEquals(start.toString(), request.queryParameter("start"))
        assertEquals(end.toString(), request.queryParameter("end"))
        assertEquals(1, server.requestCount)
        assertTrue(runCatching { api.getTimeline(3, start, start.plusSeconds(26 * 3600 + 1)) }.isFailure)
    }

    @Test fun missingMediaAndBusyChannelPreserveStatusAndServerReason() = runBlocking {
        for ((status, message) in listOf(404 to "文件已被循环清理", 503 to "此通道暂无有效画面")) {
            server.enqueue(MockResponse().setResponseCode(status).setBody(JSONObject().put("error", message).toString()))
            val error = runCatching { api.getJson("/api/snapshot/2.jpg") }.exceptionOrNull()
            assertTrue(error is ApiException)
            assertEquals(status, (error as ApiException).statusCode)
            assertEquals(message, error.message)
        }
    }

    @Test fun redirectsAreNotFollowed() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(302).setHeader("Location", "http://example.com/private"))
        val error = runCatching { api.getJson("/api/status") }.exceptionOrNull()
        assertEquals(302, (error as ApiException).statusCode)
        assertEquals(1, server.requestCount)
    }

    @Test fun invalidAndOversizedJsonFailClearly() = runBlocking {
        server.enqueue(MockResponse().setBody("<html>not an API</html>"))
        assertTrue(runCatching { api.getJson("/api/status") }.exceptionOrNull() is ApiException)
        server.enqueue(MockResponse().setBody(" ".repeat(2 * 1_024 * 1_024 + 1)))
        assertEquals("接口响应过大", runCatching { api.getJson("/api/status") }.exceptionOrNull()?.message)
    }

    @Test fun cancelRequestClosesTheUnderlyingCall() = runBlocking {
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
        val request = async { api.getJson("/api/status") }
        // 用挂起等待让子协程真正发出请求，而不是阻塞它的事件循环。
        withTimeout(3_000) {
            while (server.requestCount == 0) kotlinx.coroutines.delay(10)
        }
        request.cancelAndJoin()
        assertTrue(request.isCancelled)
        assertTrue(canceled.get())
    }

    @Test fun connectedButSilentServerStillTimesOut() = runBlocking {
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
        val boundedApi = RecorderApi(server.url("/").toString(),
            client.newBuilder().callTimeout(150, TimeUnit.MILLISECONDS).build())
        val error = withTimeout(3_000) { runCatching { boundedApi.getJson("/api/status") }.exceptionOrNull() }
        assertTrue(error is ApiException)
        assertNull((error as ApiException).statusCode)
        assertTrue(canceled.get())
    }

    @Test fun saveFetchesLatestAndPreservesUneditedHardwareAndOtherChannels() = runBlocking {
        val latest = configFixture()
        latest.getJSONArray("channels").getJSONObject(0).put("source", "/dev/video9").put("custom", "keep")
        latest.getJSONArray("channels").getJSONObject(1).put("name", "另一客户端刚修改")
        server.enqueue(MockResponse().setBody(latest.toString()))
        server.enqueue(MockResponse().setBody(JSONObject().put("ok", true).put("config", latest).toString()))
        val patch = JSONObject("""{"channels":[{"id":1,"detection":{"threshold_seconds":12}}]}""")
        assertTrue(api.saveConfigChanges(patch).getBoolean("ok"))
        assertEquals("GET", server.takeRequest().method)
        val put = server.takeRequest()
        assertEquals("PUT", put.method)
        val saved = JSONObject(put.body.readUtf8())
        val channel = saved.getJSONArray("channels").getJSONObject(0)
        assertEquals("/dev/video9", channel.getString("source"))
        assertEquals("keep", channel.getString("custom"))
        assertEquals(12, channel.getJSONObject("detection").getInt("threshold_seconds"))
        assertEquals("另一客户端刚修改", saved.getJSONArray("channels").getJSONObject(1).getString("name"))
        assertEquals(5, saved.getJSONArray("channels").length())
        assertEquals("keep", saved.getJSONObject("server").getString("extension"))
    }
}

internal fun configFixture(): JSONObject = JSONObject("""{
  "server":{"host":"0.0.0.0","port":8080,"extension":"keep"},
  "storage":{"target_id":"internal","max_gb":24,"min_free_gb":3},
  "channels":[
    {"id":1,"name":"AHD1","source":"/dev/video5","crop":[0,0,1,1],"detection":{"enabled":true,"threshold_seconds":3,"confidence":0.5}},
    {"id":2,"name":"AHD2","source":"/dev/video0","crop":[0,0,0.5,0.5],"detection":{"threshold_seconds":3}},
    {"id":3,"name":"AHD3","source":"/dev/video0","detection":{"threshold_seconds":3}},
    {"id":4,"name":"AHD4","source":"/dev/video0","detection":{"threshold_seconds":3}},
    {"id":5,"name":"AHD5","source":"/dev/video0","detection":{"threshold_seconds":3}}
  ]
}""")
