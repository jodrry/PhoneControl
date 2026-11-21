package com.example.phonecontrol.agent

import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.projection.MediaProjection
import android.os.Bundle
import android.util.Log
import android.view.Surface
import java.nio.ByteBuffer

/**
 * MediaProjection -> encoder input Surface -> MediaCodec H.264 -> WireProtocol.
 *
 * The virtual display is captured at a scaled resolution (long edge <= 1280) to
 * keep encoding and Wi-Fi bandwidth reasonable; touch coordinates are always
 * normalized, so the controller does not need to know this capture size.
 */
class ScreenCaptureEngine(
    private val projection: MediaProjection,
    private val captureWidth: Int,
    private val captureHeight: Int,
    private val densityDpi: Int,
    private val callbacks: Callbacks
) {
    interface Callbacks {
        fun onCodecConfig(packet: ByteArray)
        fun onFrame(packet: ByteArray)
        fun onError(error: Throwable)
    }

    private val stopLock = Any()
    private var stopped = false

    @Volatile
    private var running = false

    private var codec: MediaCodec? = null
    private var inputSurface: Surface? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var worker: Thread? = null
    private var lastConfigPacket: ByteArray? = null

    fun start() {
        if (running) return
        stopped = false
        running = true
        try {
            val format = MediaFormat.createVideoFormat(MIME_TYPE, captureWidth, captureHeight).apply {
                setInteger(
                    MediaFormat.KEY_COLOR_FORMAT,
                    MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface
                )
                setInteger(MediaFormat.KEY_BIT_RATE, bitrateFor(captureWidth, captureHeight))
                setInteger(MediaFormat.KEY_FRAME_RATE, FRAME_RATE)
                setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, I_FRAME_INTERVAL_SECONDS)
            }
            val encoder = MediaCodec.createEncoderByType(MIME_TYPE)
            codec = encoder
            encoder.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            val surface = encoder.createInputSurface()
            inputSurface = surface
            encoder.start()
            virtualDisplay = projection.createVirtualDisplay(
                "PhoneControlCapture",
                captureWidth,
                captureHeight,
                densityDpi,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                surface,
                null,
                null
            )

            worker = Thread({ encodeLoop(encoder) }, "PhoneControl-H264").apply {
                priority = Thread.NORM_PRIORITY + 1
                start()
            }
            Log.i(TAG, "capture started ${captureWidth}x$captureHeight @ ${densityDpi}dpi")
        } catch (t: Throwable) {
            running = false
            Log.e(TAG, "capture start failed", t)
            callbacks.onError(t)
        }
    }

    fun requestKeyFrame() {
        val encoder = codec ?: return
        if (!running) return
        try {
            val params = Bundle().apply {
                putInt(MediaCodec.PARAMETER_KEY_REQUEST_SYNC_FRAME, 0)
            }
            encoder.setParameters(params)
        } catch (t: Throwable) {
            Log.w(TAG, "requestKeyFrame failed", t)
        }
    }

    fun stop() {
        synchronized(stopLock) {
            if (stopped) return
            stopped = true
            running = false

            val thread = worker
            worker = null
            if (thread != null && thread !== Thread.currentThread()) {
                thread.interrupt()
                try {
                    thread.join(1_200)
                } catch (_: InterruptedException) {
                    Thread.currentThread().interrupt()
                }
            }

            try {
                virtualDisplay?.release()
            } catch (t: Throwable) {
                Log.w(TAG, "virtual display release failed", t)
            }
            virtualDisplay = null

            try {
                inputSurface?.release()
            } catch (t: Throwable) {
                Log.w(TAG, "input surface release failed", t)
            }
            inputSurface = null

            try {
                codec?.stop()
            } catch (t: Throwable) {
                Log.w(TAG, "encoder stop failed", t)
            }
            try {
                codec?.release()
            } catch (t: Throwable) {
                Log.w(TAG, "encoder release failed", t)
            }
            codec = null

            try {
                projection.stop()
            } catch (t: Throwable) {
                Log.w(TAG, "projection stop failed", t)
            }
        }
    }

    private fun encodeLoop(encoder: MediaCodec) {
        val bufferInfo = MediaCodec.BufferInfo()
        try {
            while (running) {
                val outputIndex = encoder.dequeueOutputBuffer(bufferInfo, DEQUEUE_TIMEOUT_US)
                when {
                    outputIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        sendConfigFromFormat(encoder.outputFormat)
                    }

                    outputIndex >= 0 -> {
                        val outputBuffer = encoder.getOutputBuffer(outputIndex)
                        if (outputBuffer == null) {
                            encoder.releaseOutputBuffer(outputIndex, false)
                            continue
                        }
                        if ((bufferInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0) {
                            copyBytes(outputBuffer, bufferInfo)?.let(::sendConfigFromCsd)
                        } else if (bufferInfo.size > 0) {
                            if (lastConfigPacket == null) {
                                try {
                                    sendConfigFromFormat(encoder.outputFormat)
                                } catch (_: Throwable) {
                                    // The controller also handles SPS/PPS inside key frames.
                                }
                            }
                            val bytes = copyBytes(outputBuffer, bufferInfo)
                            if (bytes != null) {
                                val keyFrame =
                                    (bufferInfo.flags and MediaCodec.BUFFER_FLAG_KEY_FRAME) != 0
                                val packet = WireProtocol.encodePacket(
                                    type = WireProtocol.TYPE_VIDEO_FRAME,
                                    flags = if (keyFrame) WireProtocol.FLAG_KEY_FRAME else 0,
                                    width = captureWidth,
                                    height = captureHeight,
                                    presentationTimeUs = bufferInfo.presentationTimeUs,
                                    payload = bytes
                                )
                                callbacks.onFrame(packet)
                            }
                        }
                        encoder.releaseOutputBuffer(outputIndex, false)
                    }

                    outputIndex == MediaCodec.INFO_TRY_AGAIN_LATER -> Unit
                }
            }
        } catch (t: Throwable) {
            if (running) {
                Log.e(TAG, "encode loop failed", t)
                callbacks.onError(t)
            }
        }
    }

    private fun sendConfigFromFormat(format: MediaFormat) {
        val nals = ArrayList<ByteArray>()
        nals.addAll(extractNals(format.getByteBuffer("csd-0")))
        nals.addAll(extractNals(format.getByteBuffer("csd-1")))
        val spsPps = nals.filter {
            val type = WireProtocol.nalType(it)
            type == NAL_SPS || type == NAL_PPS
        }.ifEmpty { nals }
        if (spsPps.isEmpty()) return
        sendConfigPacket(spsPps)
    }

    private fun sendConfigFromCsd(csd: ByteArray) {
        val parsed = parseNals(csd)
        val nals = parsed.filter {
            val type = WireProtocol.nalType(it)
            type == NAL_SPS || type == NAL_PPS
        }.ifEmpty { parsed }
        if (nals.isNotEmpty()) sendConfigPacket(nals)
    }

    private fun sendConfigPacket(nals: List<ByteArray>) {
        val payload = WireProtocol.encodeCodecConfig(nals)
        if (payload.isEmpty()) return
        val packet = WireProtocol.encodePacket(
            type = WireProtocol.TYPE_CODEC_CONFIG,
            flags = 0,
            width = captureWidth,
            height = captureHeight,
            presentationTimeUs = 0L,
            payload = payload
        )
        val previous = lastConfigPacket
        if (previous != null && previous.contentEquals(packet)) return
        lastConfigPacket = packet
        Log.i(TAG, "codec config: ${nals.size} NAL(s)")
        callbacks.onCodecConfig(packet)
    }

    private fun extractNals(buffer: ByteBuffer?): List<ByteArray> {
        if (buffer == null) return emptyList()
        val duplicate = buffer.duplicate()
        if (duplicate.remaining() <= 0) return emptyList()
        val bytes = ByteArray(duplicate.remaining())
        duplicate.get(bytes)
        return parseNals(bytes)
    }

    private fun parseNals(data: ByteArray): List<ByteArray> {
        if (data.isEmpty()) return emptyList()

        // AVCDecoderConfigurationRecord (avcC).
        if (data.size >= 7 && (data[0].toInt() and 0xff) == 1) {
            parseAvcC(data)?.let { if (it.isNotEmpty()) return it }
        }

        // Annex-B start codes.
        val annexB = splitAnnexB(data)
        if (annexB.isNotEmpty()) return annexB

        // 4-byte length-prefixed NAL units.
        val lengthPrefixed = splitLengthPrefixed(data)
        if (lengthPrefixed.isNotEmpty()) return lengthPrefixed

        // Some encoders return exactly one raw NAL.
        if (WireProtocol.nalType(data) in 1..12) return listOf(data)
        return emptyList()
    }

    private fun parseAvcC(data: ByteArray): List<ByteArray>? {
        if (data.size < 7) return null
        val result = ArrayList<ByteArray>()
        var position = 5
        val spsCount = data[position++].toInt() and 0x1f
        repeat(spsCount) {
            if (position + 2 > data.size) return result
            val length = ((data[position].toInt() and 0xff) shl 8) or
                (data[position + 1].toInt() and 0xff)
            position += 2
            if (length <= 0 || position + length > data.size) return result
            result.add(data.copyOfRange(position, position + length))
            position += length
        }
        if (position >= data.size) return result
        val ppsCount = data[position++].toInt() and 0xff
        repeat(ppsCount) {
            if (position + 2 > data.size) return result
            val length = ((data[position].toInt() and 0xff) shl 8) or
                (data[position + 1].toInt() and 0xff)
            position += 2
            if (length <= 0 || position + length > data.size) return result
            result.add(data.copyOfRange(position, position + length))
            position += length
        }
        return result
    }

    private fun splitAnnexB(data: ByteArray): List<ByteArray> {
        // startCodeStart -> NAL start. Keeping both avoids guessing whether the
        // next delimiter was three or four bytes long.
        val starts = ArrayList<Pair<Int, Int>>(8)
        var index = 0
        while (index + 3 < data.size) {
            if (data[index] == 0.toByte() &&
                data[index + 1] == 0.toByte() &&
                data[index + 2] == 0.toByte() &&
                data[index + 3] == 1.toByte()
            ) {
                starts.add(index to index + 4)
                index += 4
            } else if (data[index] == 0.toByte() &&
                data[index + 1] == 0.toByte() &&
                data[index + 2] == 1.toByte()
            ) {
                starts.add(index to index + 3)
                index += 3
            } else {
                index++
            }
        }
        if (starts.isEmpty()) return emptyList()
        val result = ArrayList<ByteArray>(starts.size)
        starts.forEachIndexed { i, (_, start) ->
            val end = if (i + 1 < starts.size) starts[i + 1].first else data.size
            if (end > start) result.add(data.copyOfRange(start, end))
        }
        return result
    }

    private fun splitLengthPrefixed(data: ByteArray): List<ByteArray> {
        val result = ArrayList<ByteArray>()
        var position = 0
        while (position + 4 <= data.size) {
            val length = ((data[position].toInt() and 0xff) shl 24) or
                ((data[position + 1].toInt() and 0xff) shl 16) or
                ((data[position + 2].toInt() and 0xff) shl 8) or
                (data[position + 3].toInt() and 0xff)
            position += 4
            if (length <= 0 || position + length > data.size) return emptyList()
            result.add(data.copyOfRange(position, position + length))
            position += length
        }
        return if (position == data.size) result else emptyList()
    }

    private fun copyBytes(buffer: ByteBuffer, info: MediaCodec.BufferInfo): ByteArray? {
        if (info.size <= 0) return null
        val duplicate = buffer.duplicate()
        duplicate.position(info.offset)
        duplicate.limit(info.offset + info.size)
        if (duplicate.remaining() <= 0) return null
        return ByteArray(duplicate.remaining()).also { duplicate.get(it) }
    }

    private fun bitrateFor(width: Int, height: Int): Int {
        return (width.toLong() * height.toLong() * 4L)
            .coerceIn(1_500_000L, 8_000_000L)
            .toInt()
    }

    private companion object {
        const val TAG = "ScreenCaptureEngine"
        const val MIME_TYPE = MediaFormat.MIMETYPE_VIDEO_AVC
        const val FRAME_RATE = 30
        const val I_FRAME_INTERVAL_SECONDS = 2
        const val DEQUEUE_TIMEOUT_US = 10_000L
        const val NAL_SPS = 7
        const val NAL_PPS = 8
    }
}
