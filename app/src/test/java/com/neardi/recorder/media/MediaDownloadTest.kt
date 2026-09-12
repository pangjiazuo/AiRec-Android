package com.neardi.recorder.media

import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import okio.Buffer
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.io.IOException
import java.io.OutputStream
import java.util.zip.CRC32

/** 下载到文件时逐块写出；录像可以较大，日志仍维持原来的空间限制。 */
class MediaDownloadTest {
    private lateinit var server: MockWebServer

    @Before fun setUp() {
        server = MockWebServer()
        server.start()
    }

    @After fun tearDown() { server.shutdown() }

    @Test fun recordingOverSixteenMiBDownloadsWithExplicitLimit() = runBlocking {
        val (body, checksum) = fixture()
        server.enqueue(MockResponse().setBody(body))
        val output = CountingOutput()

        val size = MediaNetwork.download(server.url("/recording.mp4").toString(), output,
            maximum = 2L * 1024 * 1024 * 1024)

        assertEquals(17L * 1024 * 1024, size)
        assertEquals(size, output.count)
        assertEquals(checksum, output.checksum.value)
        // 输出端不积累整个录像，写入块必须远小于文件本身。
        assertTrue(output.largestWrite < 1024 * 1024)
    }

    @Test fun logDefaultLimitRejectsOversizedContentBeforeWriting() = runBlocking {
        server.enqueue(MockResponse().setBody(fixture().first))
        val output = CountingOutput()

        val result = runCatching { MediaNetwork.download(server.url("/logs.zip").toString(), output) }

        assertTrue(result.exceptionOrNull() is IOException)
        assertEquals("下载文件超过大小限制", result.exceptionOrNull()?.message)
        assertEquals(0L, output.count)
    }

    @Test fun unknownContentLengthStillEnforcesLogLimit() = runBlocking {
        server.enqueue(MockResponse().setChunkedBody(fixture().first, 64 * 1024))
        val output = CountingOutput()

        val result = runCatching { MediaNetwork.download(server.url("/logs.zip").toString(), output) }

        assertTrue(result.exceptionOrNull() is IOException)
        assertEquals("下载文件超过大小限制", result.exceptionOrNull()?.message)
        assertTrue(output.count in 1L..16L * 1024 * 1024)
    }

    @Test fun interruptedRecordingCannotReturnSuccessfulByteCount() = runBlocking {
        server.enqueue(MockResponse().setBody(fixture().first)
            .setSocketPolicy(SocketPolicy.DISCONNECT_DURING_RESPONSE_BODY))
        val output = CountingOutput()

        val result = runCatching {
            MediaNetwork.download(server.url("/recording.mp4").toString(), output,
                maximum = 2L * 1024 * 1024 * 1024)
        }

        assertTrue(result.exceptionOrNull() is IOException)
        assertNull(result.getOrNull())
        assertTrue(output.count in 1L until 17L * 1024 * 1024)
    }

    private fun fixture(): Pair<Buffer, Long> {
        val block = ByteArray(64 * 1024) { (it % 251).toByte() }
        val body = Buffer()
        val checksum = CRC32()
        repeat(17 * 16) { body.write(block); checksum.update(block) }
        return body to checksum.value
    }

    private class CountingOutput : OutputStream() {
        var count = 0L
        var largestWrite = 0
        val checksum = CRC32()
        override fun write(value: Int) {
            count++
            largestWrite = maxOf(largestWrite, 1)
            checksum.update(value)
        }
        override fun write(buffer: ByteArray, offset: Int, length: Int) {
            count += length
            largestWrite = maxOf(largestWrite, length)
            checksum.update(buffer, offset, length)
        }
    }
}
