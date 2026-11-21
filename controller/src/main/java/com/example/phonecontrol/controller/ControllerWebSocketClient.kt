package com.example.phonecontrol.controller

import android.util.Log
import org.java_websocket.client.WebSocketClient
import org.java_websocket.handshake.ServerHandshake
import org.json.JSONObject
import java.net.URI
import java.nio.ByteBuffer

class ControllerWebSocketClient(
    host: String,
    port: Int,
    private val listener: Listener
) : WebSocketClient(websocketUri(host, port)) {

    interface Listener {
        fun onOpen()
        fun onText(text: String)
        fun onBinary(packet: ByteArray)
        fun onClosed(reason: String)
        fun onFailure(message: String)
    }

    init {
        setTcpNoDelay(true)
        setConnectionLostTimeout(15)
    }

    fun connectToAgent() {
        try {
            connect()
        } catch (t: Throwable) {
            listener.onFailure(t.message ?: t.javaClass.simpleName)
        }
    }

    fun disconnect() {
        try {
            if (isOpen) close(1000, "controller disconnect")
            else close()
        } catch (t: Throwable) {
            Log.w(TAG, "disconnect failed", t)
        }
    }

    fun sendTouch(phase: String, x: Float, y: Float): Boolean {
        val json = JSONObject()
            .put("type", "touch")
            .put("phase", phase)
            .put("id", 0)
            .put("x", x.toDouble())
            .put("y", y.toDouble())
        return sendJson(json)
    }

    fun sendAction(action: String): Boolean {
        val json = JSONObject()
            .put("type", "action")
            .put("action", action)
        return sendJson(json)
    }

    fun requestSync(): Boolean {
        return sendJson(JSONObject().put("type", "requestSync"))
    }

    fun sendPing(sentAtElapsedRealtime: Long): Boolean {
        val json = JSONObject()
            .put("type", "ping")
            .put("t", sentAtElapsedRealtime)
        return sendJson(json)
    }

    fun sendJson(json: JSONObject): Boolean {
        if (!isOpen) return false
        return try {
            send(json.toString())
            true
        } catch (t: Throwable) {
            Log.w(TAG, "send failed", t)
            false
        }
    }

    override fun onOpen(handshake: ServerHandshake?) {
        listener.onOpen()
    }

    override fun onMessage(message: String?) {
        if (message != null) listener.onText(message)
    }

    override fun onMessage(bytes: ByteBuffer?) {
        if (bytes == null) return
        val copy = bytes.duplicate()
        val data = ByteArray(copy.remaining())
        copy.get(data)
        listener.onBinary(data)
    }

    override fun onClose(code: Int, reason: String?, remote: Boolean) {
        listener.onClosed(reason?.takeIf { it.isNotBlank() } ?: code.toString())
    }

    override fun onError(ex: Exception?) {
        listener.onFailure(ex?.message ?: ex?.javaClass?.simpleName ?: "websocket error")
    }

    private companion object {
        const val TAG = "ControllerWebSocketClient"
    }
}

private fun websocketUri(host: String, port: Int): URI {
    val formattedHost = if (host.contains(':') && !host.startsWith("[")) "[$host]" else host
    return URI("ws://$formattedHost:$port/")
}
