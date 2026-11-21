package com.example.phonecontrol.agent

import android.util.Log
import org.java_websocket.WebSocket
import org.java_websocket.handshake.ClientHandshake
import org.java_websocket.server.WebSocketServer
import org.json.JSONObject
import java.net.InetSocketAddress
import java.nio.ByteBuffer
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

/**
 * WebSocket server hosted by the agent.
 *
 * Text channel: JSON control messages.
 * Binary channel: WireProtocol packets (codec config + H.264 frames).
 */
class AgentWebSocketServer(
    private val port: Int,
    private val deviceName: String,
    private val screenWidth: Int,
    private val screenHeight: Int,
    private val listener: Listener
) : WebSocketServer(InetSocketAddress(port)) {

    interface Listener {
        fun onServerStarted()
        fun onServerError(message: String)
        fun onClientsChanged(count: Int)
        fun onTouch(phase: String, x: Float, y: Float)
        fun onAction(action: String)
        fun onRequestKeyFrame()

        /**
         * 控制端下发的采集参数：分辨率长边上限 / 帧率 / 码率。
         * 传 0 表示由被控端自动决定。
         */
        fun onProfile(longEdge: Int, fps: Int, bitrateKbps: Int)
    }

    private val clients = ConcurrentHashMap.newKeySet<WebSocket>()
    private val errorReported = AtomicBoolean(false)

    @Volatile
    private var latestCodecConfig: ByteArray? = null

    @Volatile
    private var started = false

    init {
        setConnectionLostTimeout(20)
        // 关掉 Nagle：帧和触摸事件都要尽快发出，牺牲一点包合并换低延迟
        setTcpNoDelay(true)
    }

    fun startServer() {
        try {
            start()
        } catch (t: Throwable) {
            reportServerError(t.message ?: t.javaClass.simpleName)
        }
    }

    fun stopServer() {
        started = false
        try {
            stop(800)
        } catch (e: Exception) {
            Log.w(TAG, "stopServer failed", e)
        }
        clients.clear()
    }

    fun updateCodecConfig(packet: ByteArray) {
        latestCodecConfig = packet
        broadcastBinary(packet)
    }

    fun broadcastFrame(packet: ByteArray) {
        if (clients.isEmpty()) return
        broadcastBinary(packet)
    }

    fun sendConfigTo(client: WebSocket) {
        latestCodecConfig?.let { sendBinary(client, it) }
    }

    fun connectionCount(): Int = clients.size

    override fun onStart() {
        started = true
        Log.i(TAG, "WebSocket server listening on $port")
        listener.onServerStarted()
    }

    override fun onOpen(conn: WebSocket, handshake: ClientHandshake) {
        if (clients.size >= MAX_CLIENTS) {
            conn.close(1013, "too many controllers")
            return
        }
        clients.add(conn)
        sendText(conn, helloJson())
        latestCodecConfig?.let { sendBinary(conn, it) }
        listener.onClientsChanged(clients.size)
        broadcastStatus()
        listener.onRequestKeyFrame()
    }

    override fun onClose(conn: WebSocket, code: Int, reason: String, remote: Boolean) {
        clients.remove(conn)
        listener.onClientsChanged(clients.size)
        broadcastStatus()
    }

    override fun onMessage(conn: WebSocket, message: String) {
        handleText(conn, message)
    }

    override fun onMessage(conn: WebSocket, message: ByteBuffer) {
        // Controllers only send JSON in the text channel.
    }

    override fun onError(conn: WebSocket?, ex: Exception) {
        if (conn == null && errorReported.compareAndSet(false, true)) {
            Log.e(TAG, "server error", ex)
            listener.onServerError(ex.message ?: ex.javaClass.simpleName)
        } else {
            Log.w(TAG, "client error", ex)
        }
    }

    private fun handleText(conn: WebSocket, message: String) {
        val json = try {
            JSONObject(message)
        } catch (_: Exception) {
            sendError(conn, "invalid json")
            return
        }

        when (json.optString("type")) {
            "hello" -> Unit
            "ping" -> {
                val pong = JSONObject()
                    .put("type", "pong")
                    .put("t", json.optLong("t"))
                sendText(conn, pong.toString())
            }

            "touch" -> {
                val phase = json.optString("phase")
                val x = json.optDouble("x", Double.NaN)
                val y = json.optDouble("y", Double.NaN)
                if (phase !in VALID_PHASES || !x.isFinite() || !y.isFinite() ||
                    x < 0.0 || x > 1.0 || y < 0.0 || y > 1.0
                ) {
                    sendError(conn, "invalid touch")
                    return
                }
                listener.onTouch(phase, x.toFloat(), y.toFloat())
            }

            "action" -> {
                val action = json.optString("action")
                if (action !in VALID_ACTIONS) {
                    sendError(conn, "invalid action")
                    return
                }
                listener.onAction(action)
            }

            "requestSync" -> {
                sendConfigTo(conn)
                listener.onRequestKeyFrame()
            }

            "requestKeyframe" -> listener.onRequestKeyFrame()

            "profile" -> {
                val longEdge = json.optInt("longEdge", 0)
                val fps = json.optInt("fps", 0)
                val bitrateKbps = json.optInt("bitrateKbps", 0)
                if (longEdge < 0 || fps < 0 || bitrateKbps < 0) {
                    sendError(conn, "invalid profile")
                    return
                }
                listener.onProfile(longEdge, fps, bitrateKbps)
            }

            else -> sendError(conn, "unsupported message")
        }
    }

    private fun helloJson(): String {
        return JSONObject()
            .put("type", "hello")
            .put("role", "agent")
            .put("protocol", 1)
            .put("name", deviceName)
            .put("width", screenWidth)
            .put("height", screenHeight)
            .put("port", port)
            .toString()
    }

    private fun broadcastStatus() {
        val status = JSONObject()
            .put("type", "status")
            .put("sharing", true)
            .put("clients", clients.size)
            .toString()
        clients.forEach { sendText(it, status) }
    }

    private fun broadcastBinary(packet: ByteArray) {
        clients.forEach { client ->
            if (client.isOpen) {
                try {
                    client.send(ByteBuffer.wrap(packet))
                } catch (t: Throwable) {
                    Log.w(TAG, "binary send failed", t)
                }
            }
        }
    }

    private fun sendBinary(client: WebSocket, packet: ByteArray) {
        if (!client.isOpen) return
        try {
            client.send(ByteBuffer.wrap(packet))
        } catch (t: Throwable) {
            Log.w(TAG, "binary send failed", t)
        }
    }

    private fun sendText(client: WebSocket, text: String) {
        if (!client.isOpen) return
        try {
            client.send(text)
        } catch (t: Throwable) {
            Log.w(TAG, "text send failed", t)
        }
    }

    private fun sendError(client: WebSocket, message: String) {
        val error = JSONObject()
            .put("type", "error")
            .put("message", message)
            .toString()
        sendText(client, error)
    }

    private fun reportServerError(message: String) {
        if (errorReported.compareAndSet(false, true)) {
            listener.onServerError(message)
        }
    }

    companion object {
        private const val TAG = "AgentWebSocketServer"
        private const val MAX_CLIENTS = 4
        private val VALID_PHASES = setOf("down", "move", "up", "cancel")
        private val VALID_ACTIONS = setOf("back", "home", "recents")
    }
}
