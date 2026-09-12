package com.neardi.recorder.media

import java.io.EOFException
import java.io.InputStream
import java.io.IOException

/** 按长度读取一帧；断帧、超大帧直接重连，避免无界缓存。 */
class MjpegReader(private val input: InputStream, private val maxFrameBytes: Int = 4 * 1024 * 1024) {
    private fun line(): String {
        val bytes = ArrayList<Byte>()
        while (bytes.size < 4096) {
            val next = input.read()
            if (next < 0) throw EOFException("预览连接结束")
            if (next == 10) return bytes.toByteArray().toString(Charsets.US_ASCII).trimEnd('\r')
            bytes.add(next.toByte())
        }
        throw IOException("预览头部过长")
    }

    fun nextFrame(): ByteArray {
        var boundary = line()
        var skipped = 0
        while (boundary.isBlank() && skipped++ < 8) boundary = line()
        if (!boundary.startsWith("--") || boundary.endsWith("--")) throw IOException("预览边界无效")
        var length: Int? = null
        var contentType = ""
        var headers = 0
        while (true) {
            val header = line()
            if (header.isEmpty()) break
            if (++headers > 32) throw IOException("预览头部过多")
            val parts = header.split(':', limit = 2)
            if (parts.size == 2) when (parts[0].trim().lowercase()) {
                "content-length" -> length = parts[1].trim().toIntOrNull()
                "content-type" -> contentType = parts[1].trim().lowercase()
            }
        }
        val size = length ?: throw IOException("预览缺少帧长度")
        if (size !in 4..maxFrameBytes || !contentType.startsWith("image/jpeg")) throw IOException("预览帧格式无效")
        val frame = ByteArray(size)
        var offset = 0
        while (offset < size) {
            val count = input.read(frame, offset, size - offset)
            if (count <= 0) throw EOFException("预览帧未传输完整")
            offset += count
        }
        if (frame[0] != 0xff.toByte() || frame[1] != 0xd8.toByte() ||
            frame[size - 2] != 0xff.toByte() || frame[size - 1] != 0xd9.toByte()) throw IOException("JPEG帧损坏")
        return frame
    }
}
