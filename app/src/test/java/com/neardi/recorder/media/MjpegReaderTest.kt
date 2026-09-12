package com.neardi.recorder.media

import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.EOFException
import java.io.IOException

class MjpegReaderTest {
    private val jpeg = byteArrayOf(0xff.toByte(), 0xd8.toByte(), 1, 2, 0xff.toByte(), 0xd9.toByte())
    private fun part(size: String = "6", tail: ByteArray = jpeg): ByteArray =
        "--frame\r\nContent-Type: image/jpeg\r\nContent-Length: $size\r\n\r\n".toByteArray() + tail + "\r\n".toByteArray()

    @Test fun sequentialFramesRemainSeparatedDespiteSmallReads() {
        val source = object : ByteArrayInputStream(part() + part()) {
            override fun read(buffer: ByteArray, offset: Int, count: Int): Int = super.read(buffer, offset, minOf(1, count))
        }
        val reader = MjpegReader(source)
        assertArrayEquals(jpeg, reader.nextFrame())
        assertArrayEquals(jpeg, reader.nextFrame())
    }
    @Test fun invalidLengthsAreRejectedBeforeAllocation() {
        listOf("-1", "0", "4194305", "NaN", "99999999999999").forEach { size ->
            assertThrows(IOException::class.java) { MjpegReader(ByteArrayInputStream(part(size))).nextFrame() }
        }
    }
    @Test fun partialJpegFailsInsteadOfReusingStaleFrame() {
        assertThrows(EOFException::class.java) { MjpegReader(ByteArrayInputStream(part("100"))).nextFrame() }
        assertThrows(IOException::class.java) { MjpegReader(ByteArrayInputStream(part(tail = ByteArray(6)))).nextFrame() }
    }
    @Test fun hugeHeaderIsBounded() {
        assertThrows(IOException::class.java) { MjpegReader(ByteArrayInputStream(("--" + "a".repeat(6000)).toByteArray())).nextFrame() }
    }
}
