package com.example.phonecontrol.controller

import android.media.MediaCodec
import android.media.MediaFormat
import android.util.Log
import android.view.Surface
import java.nio.ByteBuffer
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.TimeUnit

/**
 * MediaCodec H.264 decoder rendered directly into the SurfaceView's Surface.
 *
 * Codec configuration is delivered in-band by the agent as a WireProtocol config
 * packet, so the controller does not need to guess SPS/PPS from key frames. Only
 * the decoder thread touches MediaCodec.
 */
class VideoDecoder(
    private val surface: Surface,
    private val onError: (String) -> Unit
) {
    private sealed interface Item {
        data class Config(
            val nals: List<ByteArray>,
            val width: Int,
            val height: Int
        ) : Item

        data class Frame(
            val data: ByteArray,
            val presentationTimeUs: Long,
            val keyFrame: Boolean
        ) : Item

        data object Reset : Item
        data object Quit : Item
    }

    private val queue = ArrayBlockingQueue<Item>(MAX_QUEUED_ITEMS)
    private val bufferInfo = MediaCodec.BufferInfo()

    @Volatile
    private var running = true

    private var codec: MediaCodec? = null
    private var configured = false
    private var lastErrorReported = false
    private val worker = Thread({ decodeLoop() }, "PhoneControl-Decoder")

    fun start() {
        worker.start()
    }

    /**
     * Returns false when the packet could not be queued (queue full / released).
     * The activity may then ask the agent for a fresh sync frame.
     */
    fun submit(packet: ByteArray): Boolean {
        if (!running) return false
        val header = WireProtocol.decodeHeader(packet) ?: return false
        val payload = packet.copyOfRange(WireProtocol.HEADER_SIZE, packet.size)
        return when (header.type) {
            WireProtocol.TYPE_CODEC_CONFIG -> {
                val nals = WireProtocol.decodeCodecConfig(payload)
                if (nals.isEmpty()) return false
                // A config supersedes any frames that preceded it.
                queue.clear()
                queue.offer(Item.Config(nals, header.width, header.height))
            }

            WireProtocol.TYPE_VIDEO_FRAME -> {
                if (payload.isEmpty()) return false
                queue.offer(
                    Item.Frame(
                        data = payload,
                        presentationTimeUs = header.presentationTimeUs,
                        keyFrame = (header.flags and WireProtocol.FLAG_KEY_FRAME) != 0
                    )
                )
            }

            else -> false
        }
    }

    fun reset() {
        if (!running) return
        queue.clear()
        queue.offer(Item.Reset)
    }

    fun release() {
        if (!running) return
        running = false
        queue.clear()
        queue.offer(Item.Quit)
        if (Thread.currentThread() !== worker) {
            try {
                worker.join(1_500)
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
            }
        }
    }

    private fun decodeLoop() {
        try {
            while (running) {
                val item = queue.poll(20, TimeUnit.MILLISECONDS)
                if (item == null) {
                    drainOutput()
                    continue
                }
                when (item) {
                    is Item.Config -> {
                        try {
                            configureCodec(item)
                        } catch (t: Throwable) {
                            Log.e(TAG, "decoder configure failed", t)
                            configured = false
                            reportError(t)
                        }
                    }

                    is Item.Frame -> {
                        if (configured) feedFrame(item)
                    }

                    Item.Reset -> {
                        releaseCodec()
                    }

                    Item.Quit -> break
                }
                drainOutput()
            }
        } catch (t: Throwable) {
            if (running) {
                Log.e(TAG, "decode loop failed", t)
                reportError(t)
            }
        } finally {
            releaseCodec()
        }
    }

    private fun configureCodec(config: Item.Config) {
        releaseCodec()
        if (!surface.isValid) {
            throw IllegalStateException("SurfaceView surface is not valid")
        }
        val sps = config.nals.firstOrNull { WireProtocol.nalType(it) == NAL_SPS }
            ?: throw IllegalArgumentException("codec config has no SPS")
        val pps = config.nals.firstOrNull { WireProtocol.nalType(it) == NAL_PPS }

        val format = MediaFormat.createVideoFormat(MIME_TYPE, config.width, config.height).apply {
            setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, MAX_INPUT_SIZE)
            setByteBuffer("csd-0", ByteBuffer.wrap(withStartCode(sps)))
            if (pps != null) {
                setByteBuffer("csd-1", ByteBuffer.wrap(withStartCode(pps)))
            }
        }

        val decoder = MediaCodec.createDecoderByType(MIME_TYPE)
        decoder.configure(format, surface, null, 0)
        decoder.start()
        codec = decoder
        configured = true
        lastErrorReported = false
        Log.i(TAG, "decoder configured ${config.width}x${config.height}, pps=${pps != null}")
    }

    private fun feedFrame(frame: Item.Frame) {
        val decoder = codec ?: return
        val data = if (hasStartCode(frame.data)) {
            frame.data
        } else {
            ByteArray(START_CODE.size + frame.data.size).also {
                START_CODE.copyInto(it)
                frame.data.copyInto(it, START_CODE.size)
            }
        }

        var queued = false
        var attempts = 0
        while (running && !queued && attempts < 100) {
            attempts++
            val inputIndex = decoder.dequeueInputBuffer(10_000L)
            if (inputIndex >= 0) {
                val inputBuffer = decoder.getInputBuffer(inputIndex)
                if (inputBuffer == null) {
                    decoder.queueInputBuffer(inputIndex, 0, 0, frame.presentationTimeUs, 0)
                    break
                }
                inputBuffer.clear()
                if (inputBuffer.capacity() < data.size) {
                    decoder.queueInputBuffer(inputIndex, 0, 0, frame.presentationTimeUs, 0)
                    reportError(IllegalStateException("decoder input buffer too small"))
                    return
                }
                inputBuffer.put(data)
                decoder.queueInputBuffer(
                    inputIndex,
                    0,
                    data.size,
                    frame.presentationTimeUs,
                    if (frame.keyFrame) MediaCodec.BUFFER_FLAG_KEY_FRAME else 0
                )
                queued = true
            } else {
                drainOutput()
            }
        }
    }

    private fun drainOutput() {
        val decoder = codec ?: return
        while (true) {
            val outputIndex = try {
                decoder.dequeueOutputBuffer(bufferInfo, 0L)
            } catch (t: Throwable) {
                if (running) reportError(t)
                return
            }
            when {
                outputIndex >= 0 -> {
                    decoder.releaseOutputBuffer(outputIndex, true)
                }

                outputIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                    Log.i(TAG, "decoder output format changed")
                }

                else -> return
            }
        }
    }

    private fun releaseCodec() {
        configured = false
        try {
            codec?.stop()
        } catch (t: Throwable) {
            Log.w(TAG, "decoder stop failed", t)
        }
        try {
            codec?.release()
        } catch (t: Throwable) {
            Log.w(TAG, "decoder release failed", t)
        }
        codec = null
    }

    private fun reportError(error: Throwable) {
        if (lastErrorReported) return
        lastErrorReported = true
        val message = error.message ?: error.javaClass.simpleName
        onError(message)
    }

    private fun withStartCode(nal: ByteArray): ByteArray {
        val prefixLength = when {
            nal.size >= 4 &&
                nal[0] == 0.toByte() && nal[1] == 0.toByte() &&
                nal[2] == 0.toByte() && nal[3] == 1.toByte() -> 4

            nal.size >= 3 &&
                nal[0] == 0.toByte() && nal[1] == 0.toByte() &&
                nal[2] == 1.toByte() -> 3

            else -> 0
        }
        val raw = if (prefixLength == 0) nal else nal.copyOfRange(prefixLength, nal.size)
        return ByteArray(START_CODE.size + raw.size).also {
            START_CODE.copyInto(it)
            raw.copyInto(it, START_CODE.size)
        }
    }

    private fun hasStartCode(data: ByteArray): Boolean {
        val fourByte = data.size >= 4 &&
            data[0] == 0.toByte() && data[1] == 0.toByte() &&
            data[2] == 0.toByte() && data[3] == 1.toByte()
        val threeByte = data.size >= 3 &&
            data[0] == 0.toByte() && data[1] == 0.toByte() && data[2] == 1.toByte()
        return fourByte || threeByte
    }

    private companion object {
        const val TAG = "VideoDecoder"
        const val MIME_TYPE = MediaFormat.MIMETYPE_VIDEO_AVC
        const val NAL_SPS = 7
        const val NAL_PPS = 8
        const val MAX_INPUT_SIZE = 2 * 1024 * 1024
        const val MAX_QUEUED_ITEMS = 90
        val START_CODE = byteArrayOf(0, 0, 0, 1)
    }
}
