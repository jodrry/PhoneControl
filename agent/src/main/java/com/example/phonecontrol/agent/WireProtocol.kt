package com.example.phonecontrol.agent

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Binary WebSocket payload used by both apps.
 *
 * header (18 bytes):
 *   int32 magic = "PCV1"
 *   uint8 type (1 = codec config, 2 = H.264 access unit)
 *   uint8 flags (bit 0 = key frame)
 *   uint16 width
 *   uint16 height
 *   int64 presentationTimeUs
 * payload:
 *   codec config: uint8 nalCount, then [int32 length + raw NAL]* (no start codes)
 *   video frame: H.264 Annex-B access unit
 */
object WireProtocol {
    const val MAGIC = 0x50435631
    const val TYPE_CODEC_CONFIG = 1
    const val TYPE_VIDEO_FRAME = 2
    const val FLAG_KEY_FRAME = 1
    const val HEADER_SIZE = 18

    data class Header(
        val type: Int,
        val flags: Int,
        val width: Int,
        val height: Int,
        val presentationTimeUs: Long
    )

    fun encodePacket(
        type: Int,
        flags: Int,
        width: Int,
        height: Int,
        presentationTimeUs: Long,
        payload: ByteArray
    ): ByteArray {
        val buffer = ByteBuffer.allocate(HEADER_SIZE + payload.size).order(ByteOrder.BIG_ENDIAN)
        buffer.putInt(MAGIC)
        buffer.put(type.toByte())
        buffer.put(flags.toByte())
        buffer.putShort(width.coerceIn(0, 0xffff).toShort())
        buffer.putShort(height.coerceIn(0, 0xffff).toShort())
        buffer.putLong(presentationTimeUs)
        buffer.put(payload)
        return buffer.array()
    }

    fun decodeHeader(packet: ByteArray): Header? {
        if (packet.size < HEADER_SIZE) return null
        val buffer = ByteBuffer.wrap(packet).order(ByteOrder.BIG_ENDIAN)
        if (buffer.int != MAGIC) return null
        val type = buffer.get().toInt() and 0xff
        val flags = buffer.get().toInt() and 0xff
        val width = buffer.short.toInt() and 0xffff
        val height = buffer.short.toInt() and 0xffff
        val pts = buffer.long
        return Header(type, flags, width, height, pts)
    }

    fun encodeCodecConfig(nals: List<ByteArray>): ByteArray {
        val out = ByteArrayOutputStream()
        val usable = nals.filter { it.isNotEmpty() }.take(255)
        out.write(usable.size)
        usable.forEach { nal ->
            writeInt(out, nal.size)
            out.write(nal)
        }
        return out.toByteArray()
    }

    fun decodeCodecConfig(payload: ByteArray): List<ByteArray> {
        if (payload.isEmpty()) return emptyList()
        val buffer = ByteBuffer.wrap(payload).order(ByteOrder.BIG_ENDIAN)
        val count = buffer.get().toInt() and 0xff
        val result = ArrayList<ByteArray>(count)
        repeat(count) {
            if (buffer.remaining() < 4) return result
            val length = buffer.int
            if (length <= 0 || length > buffer.remaining()) return result
            val nal = ByteArray(length)
            buffer.get(nal)
            result.add(nal)
        }
        return result
    }

    fun nalType(nal: ByteArray): Int = if (nal.isEmpty()) -1 else nal[0].toInt() and 0x1f

    private fun writeInt(out: ByteArrayOutputStream, value: Int) {
        out.write((value ushr 24) and 0xff)
        out.write((value ushr 16) and 0xff)
        out.write((value ushr 8) and 0xff)
        out.write(value and 0xff)
    }
}
