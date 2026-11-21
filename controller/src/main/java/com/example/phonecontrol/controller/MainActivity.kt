package com.example.phonecontrol.controller

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.MotionEvent
import android.view.SurfaceHolder
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.example.phonecontrol.controller.databinding.ActivityControllerBinding
import org.json.JSONObject
import java.util.LinkedHashMap

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityControllerBinding
    private lateinit var nsdDiscovery: NsdDiscovery
    private lateinit var deviceAdapter: ArrayAdapter<String>

    @Volatile
    private var client: ControllerWebSocketClient? = null

    @Volatile
    private var decoder: VideoDecoder? = null

    private var connected = false
    private var connecting = false
    private var scanning = false
    private var agentName = ""

    private var pingPending = false
    private var pingSentAt = 0L

    @Volatile
    private var lastSyncRequestAt = 0L

    private var lastTouchMoveAt = 0L

    private val mainHandler = Handler(Looper.getMainLooper())
    private val foundAgents = LinkedHashMap<String, DiscoveredAgent>()
    private val deviceChoices = ArrayList<DiscoveredAgent>()

    private val pingRunnable = object : Runnable {
        override fun run() {
            if (!connected) return
            val target = client
            if (target != null && target.isOpen) {
                if (!pingPending) {
                    val sentAt = SystemClock.elapsedRealtime()
                    if (target.sendPing(sentAt)) {
                        pingPending = true
                        pingSentAt = sentAt
                    }
                } else if (SystemClock.elapsedRealtime() - pingSentAt > 3_000L) {
                    pingPending = false
                    binding.latencyText.text = getString(R.string.latency_unknown)
                }
            }
            mainHandler.postDelayed(this, 1_000L)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityControllerBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.videoContainer.aspectRatio = 9f / 16f
        binding.latencyText.text = getString(R.string.latency_unknown)
        setupSpinner()
        setupSurface()
        setupInput()
        setupButtons()

        val prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
        binding.ipInput.setText(prefs.getString(KEY_LAST_HOST, ""))
        binding.portInput.setText(prefs.getInt(KEY_LAST_PORT, DEFAULT_PORT).toString())

        nsdDiscovery = NsdDiscovery(this, object : NsdDiscovery.Listener {
            override fun onFound(agent: DiscoveredAgent) {
                runOnUiThread {
                    foundAgents[agent.id] = agent
                    refreshDeviceChoices()
                    if (binding.ipInput.text.isNullOrBlank()) {
                        binding.ipInput.setText(agent.host)
                        binding.portInput.setText(agent.port.toString())
                    }
                }
            }

            override fun onLost(id: String) {
                runOnUiThread {
                    foundAgents.remove(id)
                    refreshDeviceChoices()
                }
            }

            override fun onScanChanged(scanningState: Boolean) {
                runOnUiThread {
                    scanning = scanningState
                    binding.scanButton.text = if (scanningState) {
                        getString(R.string.scanning)
                    } else {
                        getString(R.string.scan)
                    }
                }
            }
        })

        refreshDeviceChoices()
        setStatus(R.string.status_idle, R.color.warning)
        updateConnectionUi()
        nsdDiscovery.start()
    }

    override fun onDestroy() {
        nsdDiscovery.stop()
        stopPing()
        val activeClient = client
        client = null
        activeClient?.disconnect()
        val activeDecoder = decoder
        decoder = null
        activeDecoder?.release()
        super.onDestroy()
    }

    private fun setupInput() {
        binding.surfaceView.setOnTouchListener { view, event ->
            if (!connected) return@setOnTouchListener false
            val target = client ?: return@setOnTouchListener false
            val width = view.width.toFloat()
            val height = view.height.toFloat()
            if (width <= 0f || height <= 0f) return@setOnTouchListener true

            val x = (event.x / width).coerceIn(0f, 1f)
            val y = (event.y / height).coerceIn(0f, 1f)
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    lastTouchMoveAt = 0L
                    target.sendTouch("down", x, y)
                    true
                }

                MotionEvent.ACTION_MOVE -> {
                    val now = SystemClock.uptimeMillis()
                    if (now - lastTouchMoveAt >= TOUCH_MOVE_INTERVAL_MS) {
                        lastTouchMoveAt = now
                        target.sendTouch("move", x, y)
                    }
                    true
                }

                MotionEvent.ACTION_UP -> {
                    target.sendTouch("up", x, y)
                    true
                }

                MotionEvent.ACTION_CANCEL -> {
                    target.sendTouch("cancel", x, y)
                    true
                }

                else -> true
            }
        }
    }

    private fun setupSpinner() {
        deviceAdapter = ArrayAdapter(
            this,
            R.layout.item_spinner,
            arrayListOf<String>()
        ).apply {
            setDropDownViewResource(R.layout.item_spinner_dropdown)
        }
        binding.deviceSpinner.adapter = deviceAdapter
        binding.deviceSpinner.onItemSelectedListener =
            object : AdapterView.OnItemSelectedListener {
                override fun onItemSelected(
                    parent: AdapterView<*>?,
                    view: View?,
                    position: Int,
                    id: Long
                ) {
                    if (position <= 0) return
                    val agent = deviceChoices.getOrNull(position - 1) ?: return
                    binding.ipInput.setText(agent.host)
                    binding.portInput.setText(agent.port.toString())
                }

                override fun onNothingSelected(parent: AdapterView<*>?) = Unit
            }
    }

    private fun setupSurface() {
        binding.surfaceView.holder.addCallback(object : SurfaceHolder.Callback {
            override fun surfaceCreated(holder: SurfaceHolder) {
                createDecoder(holder)
                requestSyncFor(client, force = true)
            }

            override fun surfaceChanged(
                holder: SurfaceHolder,
                format: Int,
                width: Int,
                height: Int
            ) = Unit

            override fun surfaceDestroyed(holder: SurfaceHolder) {
                val activeDecoder = decoder
                decoder = null
                activeDecoder?.release()
            }
        })
    }

    private fun createDecoder(holder: SurfaceHolder) {
        val previous = decoder
        decoder = null
        previous?.release()

        var decoderRef: VideoDecoder? = null
        val newDecoder = VideoDecoder(holder.surface) { message ->
            mainHandler.post {
                if (decoder === decoderRef) {
                    toast(getString(R.string.toast_decoder_error, message))
                    requestSyncFor(client, force = true)
                }
            }
        }
        decoderRef = newDecoder
        decoder = newDecoder
        newDecoder.start()
    }

    private fun setupButtons() {
        binding.scanButton.setOnClickListener {
            if (scanning) nsdDiscovery.stop() else nsdDiscovery.start()
        }
        binding.connectButton.setOnClickListener { connect() }
        binding.disconnectButton.setOnClickListener { disconnect(showToast = true) }
        binding.backButton.setOnClickListener { sendAction("back") }
        binding.homeButton.setOnClickListener { sendAction("home") }
    }

    private fun connect() {
        if (connected || connecting) return
        val address = parseAddress() ?: run {
            toast(getString(R.string.toast_invalid_address))
            return
        }
        val (host, port) = address
        nsdDiscovery.stop()
        stopPing()
        lastSyncRequestAt = 0L
        connecting = true
        agentName = ""
        setStatus(R.string.status_connecting, R.color.warning)
        updateConnectionUi()

        getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
            .edit()
            .putString(KEY_LAST_HOST, host)
            .putInt(KEY_LAST_PORT, port)
            .apply()

        try {
            var newClient: ControllerWebSocketClient? = null
            val candidate = ControllerWebSocketClient(
                host = host,
                port = port,
                listener = object : ControllerWebSocketClient.Listener {
                    override fun onOpen() {
                        val target = newClient ?: return
                        mainHandler.post {
                            if (client !== target) return@post
                            connected = true
                            connecting = false
                            setStatus(R.string.status_connected, R.color.success)
                            updateConnectionUi()
                            toast(getString(R.string.toast_connected))
                            startPing()
                            requestSyncFor(target, force = true)
                        }
                    }

                    override fun onText(text: String) {
                        val target = newClient ?: return
                        handleText(target, text)
                    }

                    override fun onBinary(packet: ByteArray) {
                        val target = newClient ?: return
                        if (client !== target) return
                        val accepted = decoder?.submit(packet) ?: false
                        if (!accepted) requestSyncFor(target, force = false)
                    }

                    override fun onClosed(reason: String) {
                        val target = newClient ?: return
                        mainHandler.post {
                            if (client !== target) return@post
                            handleConnectionLost(reason, null)
                        }
                    }

                    override fun onFailure(message: String) {
                        val target = newClient ?: return
                        mainHandler.post {
                            if (client !== target) return@post
                            handleConnectionLost(null, message)
                        }
                    }
                }
            )
            newClient = candidate
            client = candidate
            candidate.connectToAgent()
            toast(getString(R.string.toast_connecting, host, port))
        } catch (t: Throwable) {
            client = null
            connected = false
            connecting = false
            setStatus(R.string.status_error, R.color.danger)
            updateConnectionUi()
            toast(getString(R.string.toast_connection_failed) + ": ${t.message}")
        }
    }

    private fun disconnect(showToast: Boolean) {
        val hadConnection = client != null || connected || connecting
        val activeClient = client
        client = null
        activeClient?.disconnect()
        stopPing()
        decoder?.reset()
        connected = false
        connecting = false
        agentName = ""
        lastSyncRequestAt = 0L
        binding.latencyText.text = getString(R.string.latency_unknown)
        binding.resolutionText.text = "--"
        setStatus(R.string.status_idle, R.color.warning)
        updateConnectionUi()
        if (showToast && hadConnection) toast(getString(R.string.toast_disconnected))
    }

    private fun handleConnectionLost(reason: String?, error: String?) {
        val activeClient = client
        client = null
        activeClient?.disconnect()
        stopPing()
        decoder?.reset()
        connected = false
        connecting = false
        agentName = ""
        lastSyncRequestAt = 0L
        binding.latencyText.text = getString(R.string.latency_unknown)
        binding.resolutionText.text = "--"

        if (error != null) {
            setStatus(R.string.status_error, R.color.danger)
            toast(getString(R.string.toast_connection_failed))
        } else {
            setStatus(R.string.status_disconnected, R.color.warning)
            toast(getString(R.string.toast_disconnected))
        }
        updateConnectionUi()
    }

    private fun handleText(target: ControllerWebSocketClient, text: String) {
        val json = try {
            JSONObject(text)
        } catch (_: Exception) {
            return
        }
        when (json.optString("type")) {
            "hello" -> {
                val width = json.optInt("width", 0)
                val height = json.optInt("height", 0)
                val name = json.optString("name")
                mainHandler.post {
                    if (client !== target) return@post
                    agentName = name
                    if (name.isNotBlank() && connected) {
                        binding.statusText.text =
                            "${getString(R.string.status_connected)} · $name"
                    }
                    if (width > 0 && height > 0) {
                        binding.videoContainer.aspectRatio =
                            width.toFloat() / height.toFloat()
                        binding.resolutionText.text =
                            getString(R.string.resolution_value, width, height)
                    }
                }
            }

            "pong" -> {
                val sentAt = json.optLong("t", 0L)
                if (sentAt <= 0L) return
                val latency = (SystemClock.elapsedRealtime() - sentAt).coerceAtLeast(0L)
                mainHandler.post {
                    if (client !== target) return@post
                    pingPending = false
                    binding.latencyText.text = getString(R.string.latency_value, latency)
                }
            }

            "error" -> {
                val message = json.optString("message").ifBlank { "unknown agent error" }
                mainHandler.post {
                    if (client === target) {
                        toast(getString(R.string.toast_agent_error, message))
                    }
                }
            }

            "status" -> Unit
        }
    }

    private fun sendAction(action: String) {
        val target = client
        if (!connected || target == null) {
            toast(getString(R.string.toast_not_connected))
            return
        }
        target.sendAction(action)
    }

    private fun requestSyncFor(target: ControllerWebSocketClient?, force: Boolean) {
        if (target == null || !target.isOpen) return
        val now = SystemClock.elapsedRealtime()
        if (force || now - lastSyncRequestAt >= SYNC_REQUEST_INTERVAL_MS) {
            lastSyncRequestAt = now
            target.requestSync()
        }
    }

    private fun startPing() {
        stopPing()
        pingPending = false
        mainHandler.postDelayed(pingRunnable, 250L)
    }

    private fun stopPing() {
        mainHandler.removeCallbacks(pingRunnable)
        pingPending = false
    }

    private fun updateConnectionUi() {
        val busy = connected || connecting
        binding.connectButton.isEnabled = !busy
        binding.disconnectButton.isEnabled = busy
        binding.backButton.isEnabled = connected
        binding.homeButton.isEnabled = connected
        binding.ipInput.isEnabled = !busy
        binding.portInput.isEnabled = !busy
        binding.deviceSpinner.isEnabled = !busy
        binding.scanButton.isEnabled = !busy
        binding.hintText.setText(
            if (connected) R.string.hint_connected else R.string.hint_disconnected
        )
    }

    private fun setStatus(textRes: Int, colorRes: Int) {
        binding.statusText.setText(textRes)
        binding.statusText.setTextColor(ContextCompat.getColor(this, colorRes))
    }

    private fun refreshDeviceChoices() {
        deviceChoices.clear()
        deviceAdapter.clear()
        if (foundAgents.isEmpty()) {
            deviceAdapter.add(getString(R.string.no_device))
        } else {
            deviceAdapter.add(getString(R.string.select_device))
            foundAgents.values
                .sortedBy { it.name.lowercase() }
                .forEach { agent ->
                    deviceAdapter.add("${agent.name}  (${agent.host}:${agent.port})")
                    deviceChoices.add(agent)
                }
        }
        deviceAdapter.notifyDataSetChanged()
    }

    private fun parseAddress(): Pair<String, Int>? {
        var raw = binding.ipInput.text?.toString()?.trim().orEmpty()
        if (raw.isEmpty()) return null
        raw = raw
            .removePrefix("ws://")
            .removePrefix("wss://")
            .removePrefix("http://")
            .removePrefix("https://")
            .substringBefore('/')
            .trim()

        val portText = binding.portInput.text?.toString()?.trim().orEmpty()
        var port = if (portText.isEmpty()) {
            DEFAULT_PORT
        } else {
            portText.toIntOrNull() ?: return null
        }

        if (raw.startsWith("[") && raw.contains("]")) {
            val closing = raw.indexOf(']')
            val host = raw.substring(1, closing)
            if (portText.isEmpty() && raw.length > closing + 1 && raw[closing + 1] == ':') {
                port = raw.substring(closing + 2).toIntOrNull() ?: return null
            }
            if (host.isBlank()) return null
            if (port !in 1..65535) return null
            return host to port
        }

        if (raw.count { it == ':' } == 1) {
            val host = raw.substringBefore(':')
            val embeddedPort = raw.substringAfter(':')
            raw = host
            if (portText.isEmpty()) {
                port = embeddedPort.toIntOrNull() ?: return null
            }
        }

        if (raw.isBlank() || raw.any { it.isWhitespace() } || raw.contains(':')) return null
        if (port !in 1..65535) return null
        return raw to port
    }

    private fun toast(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }

    private companion object {
        const val PREFS_NAME = "controller_prefs"
        const val KEY_LAST_HOST = "last_host"
        const val KEY_LAST_PORT = "last_port"
        const val DEFAULT_PORT = 8765
        const val TOUCH_MOVE_INTERVAL_MS = 16L
        const val SYNC_REQUEST_INTERVAL_MS = 1_000L
    }
}
