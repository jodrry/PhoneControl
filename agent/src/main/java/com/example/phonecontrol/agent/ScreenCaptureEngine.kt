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
 * The virtual display is captured at a scaled resolution (long edge limited by the
 * controller's quality profile) to keep encoding and Wi-Fi bandwidth reasonable;
 * touch coordinates are always normalized, so the controller does not need to know
 * this capture size.
 *
 * 编码参数按低延迟优先配置：CBR、禁用 B 帧、latency=0。设备编码器不接受时逐级
 * 回退，保证至少能用最基础的参数编出来。
 */
class ScreenCaptureEngine(
    private val projection: MediaProjection,
    @Volatile private var captureWidth: Int,
    @Volatile private var captureHeight: Int,
    private val densityDpi: Int,
    @Volatile private var frameRate: Int,
    @Volatile private var bitrateKbps: Int,
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
            val encoder = newEncoder()
            val surface = encoder.createInputSurface()
            inputSurface = surface
            encoder.start()
            codec = encoder
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
            Log.i(TAG, "capture started ${captureWidth}x$captureHeight @ ${frameRate}fps ${bitrateLabel()}")
        } catch (t: Throwable) {
            running = false
            Log.e(TAG, "capture start failed", t)
            callbacks.onError(t)
        }
    }

    /**
     * 运行期切换采集参数：分辨率、帧率、码率。
     *
     * 不重新申请 MediaProjection（Android 14 只允许一次 createVirtualDisplay），
     * 而是复用已有的 VirtualDisplay，换掉编码器 Surface 后 resize。
     *
     * @return true 表示新参数已生效；false 表示回退到原参数。
     */
    fun reconfigure(newWidth: Int, newHeight: Int, newFps: Int, newBitrateKbps: Int): Boolean {
        synchronized(stopLock) {
            if (!running) return false
            val width = alignEven(newWidth)
            val height = alignEven(newHeight)
            val fps = newFps.coerceIn(10, 60)
            val kbps = newBitrateKbps.coerceIn(0, 40_000)
            if (width == captureWidth && height == captureHeight &&
                fps == frameRate && kbps == bitrateKbps
            ) {
                return true
            }
            val display = virtualDisplay ?: return false
            val oldWidth = captureWidth
            val oldHeight = captureHeight
            val oldFps = frameRate
            val oldKbps = bitrateKbps

            stopEncoderOnly()

            captureWidth = width
            captureHeight = height
            frameRate = fps
            bitrateKbps = kbps
            if (attachEncoder(display)) {
                Log.i(TAG, "reconfigured ${width}x$height @ ${fps}fps ${bitrateLabel()}")
                return true
            }

            // 回退到原来的参数
            Log.w(TAG, "reconfigure rejected, restoring ${oldWidth}x$oldHeight")
            stopEncoderOnly()
            captureWidth = oldWidth
            captureHeight = oldHeight
            frameRate = oldFps
            bitrateKbps = oldKbps
            if (!attachEncoder(display)) {
                running = false
                callbacks.onError(IllegalStateException("encoder could not be restored"))
            }
            return false
        }
    }

    /** 建编码器 + 输入 Surface，挂到已有的 VirtualDisplay 上，然后开始出帧。 */
    private fun attachEncoder(display: VirtualDisplay): Boolean {
        return try {
            val encoder = newEncoder()
            val surface = encoder.createInputSurface()
            encoder.start()
            runCatching { display.setSurface(null) }
                .onFailure { Log.w(TAG, "detach surface failed", it) }
            runCatching { display.resize(captureWidth, captureHeight, densityDpi) }
                .onFailure { Log.w(TAG, "virtual display resize failed", it) }
            display.setSurface(surface)
            codec = encoder
            inputSurface = surface
            lastConfigPacket = null
            worker = Thread({ encodeLoop(encoder) }, "PhoneControl-H264").apply {
                priority = Thread.NORM_PRIORITY + 1
                start()
            }
            requestKeyFrame()
            true
        } catch (t: Throwable) {
            Log.e(TAG, "attach encoder failed", t)
            false
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

    fun stop(releaseProjection: Boolean = true) {
        synchronized(stopLock) {
            if (stopped) return
            stopped = true
            running = false

            stopEncoderOnly()

            try {
                virtualDisplay?.release()
            } catch (t: Throwable) {
                Log.w(TAG, "virtual display release failed", t)
            }
            virtualDisplay = null

            if (releaseProjection) {
                try {
                    projection.stop()
                } catch (t: Throwable) {
                    Log.w(TAG, "projection stop failed", t)
                }
            }
        }
    }

    private fun stopEncoderOnly() {
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
    }

    /**
     * 按低延迟优先配置编码器。高优先级的键（CBR / 禁用 B 帧 / latency）逐级尝试，
     * 编码器不认就退回更保守的参数。
     */
    private fun newEncoder(): MediaCodec {
        val extras = arrayOf(
            mapOf(
                "bitrate-mode" to MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_CBR,
                "max-b-frames" to 0,
                "latency" to 0,
                "repeat-previous-frame-after" to (1_000_000 / frameRate.coerceAtLeast(1))
            ),
            mapOf("bitrate-mode" to MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_CBR),
            emptyMap<String, Int>()
        )
        var lastError: Throwable? = null
        for (extra in extras) {
            val encoder = try {
                MediaCodec.createEncoderByType(MIME_TYPE)
            } catch (t: Throwable) {
                throw if (lastError == null) t else lastError
            }
            try {
                encoder.configure(
                    buildFormat(extra),
                    null,
                    null,
                    MediaCodec.CONFIGURE_FLAG_ENCODE
                )
                return encoder
            } catch (t: Throwable) {
                lastError = t
                Log.w(TAG, "encoder rejected ${extra.keys}, retrying simpler config", t)
                try {
                    encoder.release()
                } catch (_: Throwable) {
                    // ignored
                }
            }
        }
        throw lastError ?: IllegalStateException("no usable H.264 encoder")
    }

    private fun buildFormat(extra: Map<String, Int>): MediaFormat {
        val format = MediaFormat.createVideoFormat(MIME_TYPE, captureWidth, captureHeight)
        format.setInteger(
            MediaFormat.KEY_COLOR_FORMAT,
            MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface
        )
        format.setInteger(MediaFormat.KEY_BIT_RATE, resolvedBitrate())
        format.setInteger(MediaFormat.KEY_FRAME_RATE, frameRate)
        format.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, iFrameIntervalSeconds())
        extra.forEach { (key, value) -> format.setInteger(key, value) }
        return format
    }

    private fun resolvedBitrate(): Int {
        if (bitrateKbps > 0) {
            return (bitrateKbps.toLong() * 1000L).coerceIn(300_000L, 40_000_000L).toInt()
        }
        return (captureWidth.toLong() * captureHeight.toLong() * 4L)
            .coerceIn(1_500_000L, 8_000_000L)
            .toInt()
    }

    /** 关键帧间隔按帧率换算，1 秒一个，丢包后能快速恢复画面。 */
    private fun iFrameIntervalSeconds(): Int = I_FRAME_INTERVAL_SECONDS

    private fun bitrateLabel(): String =
        if (bitrateKbps > 0) "${bitrateKbps}kbps" else "${resolvedBitrate() / 1000}kbps"

    private fun alignEven(value: Int): Int {
        val even = (value / 2) * 2
        return if (even < 2) 2 else even
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

    private companion object {
        const val TAG = "ScreenCaptureEngine"
        const val MIME_TYPE = MediaFormat.MIMETYPE_VIDEO_AVC
        const val I_FRAME_INTERVAL_SECONDS = 1
        const val DEQUEUE_TIMEOUT_US = 10_000L
        const val NAL_SPS = 7
        const val NAL_PPS = 8
    }
}
