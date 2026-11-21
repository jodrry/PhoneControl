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
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.example.phonecontrol.controller.databinding.ActivityControllerBinding
import org.json.JSONObject
import java.util.LinkedHashMap

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityControllerBinding
    private lateinit var nsdDiscovery: NsdDiscovery
    private lateinit var deviceAdapter: ArrayAdapter<String>
    private lateinit var qualityAdapter: ArrayAdapter<String>
    private lateinit var fpsAdapter: ArrayAdapter<String>
    private lateinit var fitAdapter: ArrayAdapter<String>

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
    private var missedPongs = 0

    @Volatile
    private var lastSyncRequestAt = 0L

    private var lastTouchMoveAt = 0L

    // 当前下发给被控端的采集参数
    private var currentQuality = CaptureQuality.AUTO
    private var currentFps = CaptureFps.STANDARD
    private var currentFit = VideoFit.FIT

    // 自动重连
    private var reconnectAttempts = 0
    private var userDisconnected = false
    private var lastHost = ""
    private var lastPort = 0

    private var fullscreen = false

    private val mainHandler = Handler(Looper.getMainLooper())
    private val foundAgents = LinkedHashMap<String, DiscoveredAgent>()
    private val deviceChoices = ArrayList<DiscoveredAgent>()

    private val qualityOptions = arrayOf(
        CaptureQuality.AUTO,
        CaptureQuality.LOW,
        CaptureQuality.MEDIUM,
        CaptureQuality.HIGH,
        CaptureQuality.ULTRA
    )
    private val fpsOptions = arrayOf(CaptureFps.ECO, CaptureFps.STANDARD, CaptureFps.SMOOTH)
    private val fitOptions = arrayOf(
        VideoFit.FIT,
        VideoFit.FILL,
        VideoFit.STRETCH,
        VideoFit.RATIO_4_3,
        VideoFit.RATIO_16_9
    )

    private val pingRunnable = object : Runnable {
        override fun run() {
            if (!connected) return
            val target = client
            if (target != null && target.isOpen) {
                val now = SystemClock.elapsedRealtime()
                if (!pingPending) {
                    val sentAt = now
                    if (target.sendPing(sentAt)) {
                        pingPending = true
                        pingSentAt = sentAt
                    }
                } else if (now - pingSentAt > PONG_TIMEOUT_MS) {
                    pingPending = false
                    missedPongs++
                    binding.latencyText.text = getString(R.string.latency_unknown)
                    if (missedPongs >= MAX_MISSED_PONGS) {
                        missedPongs = 0
                        // 心跳连续丢失：链路已经假死，主动断开并重连
                        handleConnectionLost("heartbeat timeout", null, allowReconnect = true)
                        return
                    }
                }
            }
            mainHandler.postDelayed(this, PING_INTERVAL_MS)
        }
    }

    private val reconnectRunnable = object : Runnable {
        override fun run() {
            if (userDisconnected) return
            if (client != null || connected || connecting) return
            if (lastHost.isEmpty() || lastPort <= 0) return
            startConnection(lastHost, lastPort, isReconnect = true)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityControllerBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.videoContainer.aspectRatio = 9f / 16f
        binding.latencyText.text = getString(R.string.latency_unknown)
        binding.videoPlaceholder.visibility = View.VISIBLE

        setupSpinner()
        setupQualityControls()
        setupSurface()
        setupInput()
        setupButtons()

        val prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
        binding.ipInput.setText(prefs.getString(KEY_LAST_HOST, ""))
        binding.portInput.setText(prefs.getInt(KEY_LAST_PORT, DEFAULT_PORT).toString())

        restoreProfilePrefs(prefs)

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
        mainHandler.removeCallbacks(reconnectRunnable)
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
            if (width <= 0f || height <= 0f) return@setOnTouchListener false

            val x = (event.x / width).coerceIn(0f, 1f)
            val y = (event.y / height).coerceIn(0f, 1f)

            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    target.sendTouch("down", x, y)
                    lastTouchMoveAt = 0L
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
                    view.performClick()
                    true
                }

                MotionEvent.ACTION_CANCEL -> {
                    target.sendTouch("cancel", x, y)
                    true
                }

                else -> false
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

    private fun setupQualityControls() {
        qualityAdapter = ArrayAdapter(
            this,
            R.layout.item_spinner,
            qualityOptions.map { qualityLabel(it) }
        ).apply { setDropDownViewResource(R.layout.item_spinner_dropdown) }
        binding.qualitySpinner.adapter = qualityAdapter
        binding.qualitySpinner.onItemSelectedListener =
            object : AdapterView.OnItemSelectedListener {
                override fun onItemSelected(
                    parent: AdapterView<*>?,
                    view: View?,
                    position: Int,
                    id: Long
                ) {
                    val picked = qualityOptions.getOrNull(position) ?: return
                    if (picked == currentQuality) return
                    currentQuality = picked
                    persistProfile()
                    pushProfile()
                }

                override fun onNothingSelected(parent: AdapterView<*>?) = Unit
            }

        fpsAdapter = ArrayAdapter(
            this,
            R.layout.item_spinner,
            fpsOptions.map { fpsLabel(it) }
        ).apply { setDropDownViewResource(R.layout.item_spinner_dropdown) }
        binding.fpsSpinner.adapter = fpsAdapter
        binding.fpsSpinner.onItemSelectedListener =
            object : AdapterView.OnItemSelectedListener {
                override fun onItemSelected(
                    parent: AdapterView<*>?,
                    view: View?,
                    position: Int,
                    id: Long
                ) {
                    val picked = fpsOptions.getOrNull(position) ?: return
                    if (picked == currentFps) return
                    currentFps = picked
                    persistProfile()
                    pushProfile()
                }

                override fun onNothingSelected(parent: AdapterView<*>?) = Unit
            }

        fitAdapter = ArrayAdapter(
            this,
            R.layout.item_spinner,
            fitOptions.map { fitLabel(it) }
        ).apply { setDropDownViewResource(R.layout.item_spinner_dropdown) }
        binding.fitSpinner.adapter = fitAdapter
        binding.fitSpinner.onItemSelectedListener =
            object : AdapterView.OnItemSelectedListener {
                override fun onItemSelected(
                    parent: AdapterView<*>?,
                    view: View?,
                    position: Int,
                    id: Long
                ) {
                    val picked = fitOptions.getOrNull(position) ?: return
                    currentFit = picked
                    persistProfile()
                    applyFitMode()
                }

                override fun onNothingSelected(parent: AdapterView<*>?) = Unit
            }
    }

    private fun qualityLabel(quality: CaptureQuality): String = when (quality) {
        CaptureQuality.AUTO -> getString(R.string.quality_auto)
        CaptureQuality.LOW -> getString(R.string.quality_low)
        CaptureQuality.MEDIUM -> getString(R.string.quality_medium)
        CaptureQuality.HIGH -> getString(R.string.quality_high)
        CaptureQuality.ULTRA -> getString(R.string.quality_ultra)
    }

    private fun fpsLabel(fps: CaptureFps): String = when (fps) {
        CaptureFps.ECO -> getString(R.string.fps_eco)
        CaptureFps.STANDARD -> getString(R.string.fps_standard)
        CaptureFps.SMOOTH -> getString(R.string.fps_smooth)
    }

    private fun fitLabel(fit: VideoFit): String = when (fit) {
        VideoFit.FIT -> getString(R.string.fit_fit)
        VideoFit.FILL -> getString(R.string.fit_fill)
        VideoFit.STRETCH -> getString(R.string.fit_stretch)
        VideoFit.RATIO_4_3 -> getString(R.string.fit_4_3)
        VideoFit.RATIO_16_9 -> getString(R.string.fit_16_9)
    }

    private fun applyFitMode() {
        binding.videoContainer.fitMode = currentFit.mode
        binding.videoContainer.forcedAspectRatio = currentFit.forcedRatio
        binding.videoContainer.requestLayout()
    }

    private fun pushProfile() {
        val target = client
        if (!connected || target == null || !target.isOpen) return
        target.sendProfile(VideoProfile(currentQuality, currentFps))
    }

    private fun persistProfile() {
        getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
            .edit()
            .putInt(KEY_QUALITY, currentQuality.ordinal)
            .putInt(KEY_FPS, currentFps.ordinal)
            .putInt(KEY_FIT, currentFit.ordinal)
            .apply()
    }

    private fun restoreProfilePrefs(prefs: android.content.SharedPreferences) {
        currentQuality = qualityOptions.getOrNull(prefs.getInt(KEY_QUALITY, 0))
            ?: CaptureQuality.AUTO
        currentFps = fpsOptions.getOrNull(prefs.getInt(KEY_FPS, 1)) ?: CaptureFps.STANDARD
        currentFit = fitOptions.getOrNull(prefs.getInt(KEY_FIT, 0)) ?: VideoFit.FIT

        binding.qualitySpinner.setSelection(qualityOptions.indexOf(currentQuality), false)
        binding.fpsSpinner.setSelection(fpsOptions.indexOf(currentFps), false)
        binding.fitSpinner.setSelection(fitOptions.indexOf(currentFit), false)
        applyFitMode()
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
        binding.recentsButton.setOnClickListener { sendAction("recents") }
        binding.fullscreenButton.setOnClickListener { setFullscreen(!fullscreen) }
    }

    private fun connect() {
        if (connected || connecting) return
        val address = parseAddress() ?: run {
            toast(getString(R.string.toast_invalid_address))
            return
        }
        val (host, port) = address
        userDisconnected = false
        reconnectAttempts = 0
        mainHandler.removeCallbacks(reconnectRunnable)
        startConnection(host, port, isReconnect = false)
    }

    private fun startConnection(host: String, port: Int, isReconnect: Boolean) {
        if (connected || connecting) return
        nsdDiscovery.stop()
        stopPing()
        lastSyncRequestAt = 0L
        lastHost = host
        lastPort = port
        connecting = true
        agentName = ""
        if (isReconnect) {
            setStatus(R.string.status_reconnecting, R.color.warning)
        } else {
            setStatus(R.string.status_connecting, R.color.warning)
        }
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
                            reconnectAttempts = 0
                            missedPongs = 0
                            binding.videoPlaceholder.visibility = View.GONE
                            setStatus(R.string.status_connected, R.color.success)
                            updateConnectionUi()
                            toast(getString(R.string.toast_connected))
                            startPing()
                            requestSyncFor(target, force = true)
                            // 连上就把当前的画质 / 帧率下发过去
                            target.sendProfile(VideoProfile(currentQuality, currentFps))
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
                            handleConnectionLost(reason, null, allowReconnect = true)
                        }
                    }

                    override fun onFailure(message: String) {
                        val target = newClient ?: return
                        mainHandler.post {
                            if (client !== target) return@post
                            handleConnectionLost(null, message, allowReconnect = true)
                        }
                    }
                }
            )
            newClient = candidate
            client = candidate
            candidate.connectToAgent()
            if (!isReconnect) {
                toast(getString(R.string.toast_connecting, host, port))
            }
        } catch (t: Throwable) {
            client = null
            connected = false
            connecting = false
            setStatus(R.string.status_error, R.color.danger)
            updateConnectionUi()
            toast(getString(R.string.toast_connection_failed) + ": ${t.message}")
            scheduleReconnect()
        }
    }

    private fun scheduleReconnect() {
        if (userDisconnected) return
        if (reconnectAttempts >= MAX_RECONNECT_ATTEMPTS) {
            reconnectAttempts = 0
            return
        }
        reconnectAttempts++
        val delay = (RECONNECT_BASE_DELAY_MS * reconnectAttempts)
            .coerceAtMost(RECONNECT_MAX_DELAY_MS)
        toast(getString(R.string.toast_reconnecting, reconnectAttempts))
        mainHandler.removeCallbacks(reconnectRunnable)
        mainHandler.postDelayed(reconnectRunnable, delay)
    }

    private fun disconnect(showToast: Boolean) {
        userDisconnected = true
        reconnectAttempts = 0
        mainHandler.removeCallbacks(reconnectRunnable)
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
        missedPongs = 0
        binding.latencyText.text = getString(R.string.latency_unknown)
        binding.resolutionText.text = "--"
        binding.videoPlaceholder.visibility = View.VISIBLE
        setStatus(R.string.status_idle, R.color.warning)
        updateConnectionUi()
        if (showToast && hadConnection) toast(getString(R.string.toast_disconnected))
    }

    private fun handleConnectionLost(reason: String?, error: String?, allowReconnect: Boolean) {
        val activeClient = client
        client = null
        activeClient?.disconnect()
        stopPing()
        decoder?.reset()
        connected = false
        connecting = false
        agentName = ""
        lastSyncRequestAt = 0L
        missedPongs = 0
        binding.latencyText.text = getString(R.string.latency_unknown)
        binding.resolutionText.text = "--"
        binding.videoPlaceholder.visibility = View.VISIBLE

        if (userDisconnected) {
            setStatus(R.string.status_idle, R.color.warning)
            updateConnectionUi()
            return
        }

        if (error != null) {
            setStatus(R.string.status_error, R.color.danger)
        } else {
            setStatus(R.string.status_disconnected, R.color.warning)
        }
        updateConnectionUi()

        if (allowReconnect && !userDisconnected) {
            scheduleReconnect()
        } else if (error != null) {
            toast(getString(R.string.toast_connection_failed))
        }
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
                        applyFitMode()
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
                    missedPongs = 0
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

    private fun setFullscreen(enabled: Boolean) {
        if (fullscreen == enabled) return
        fullscreen = enabled
        val params = binding.videoContainer.layoutParams as?
            ConstraintLayout.LayoutParams ?: return
        val density = resources.displayMetrics.density
        val margin = (10 * density).toInt()

        if (enabled) {
            binding.topBar.visibility = View.GONE
            binding.bottomPanel.visibility = View.GONE
            params.topToTop = ConstraintLayout.LayoutParams.PARENT_ID
            params.bottomToBottom = ConstraintLayout.LayoutParams.PARENT_ID
            params.topToBottom = ConstraintLayout.LayoutParams.UNSET
            params.setMargins(0, 0, 0, 0)
            binding.fullscreenButton.setText(R.string.fullscreen_exit)
            binding.root.keepScreenOn = true
            WindowCompat.setDecorFitsSystemWindows(window, false)
            val controller = WindowInsetsControllerCompat(window, binding.root)
            controller.hide(WindowInsetsCompat.Type.systemBars())
            controller.systemBarsBehavior =
                WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        } else {
            binding.topBar.visibility = View.VISIBLE
            binding.bottomPanel.visibility = View.VISIBLE
            params.topToTop = ConstraintLayout.LayoutParams.UNSET
            params.topToBottom = R.id.topBar
            params.bottomToBottom = R.id.bottomPanel
            params.setMargins(margin, margin, margin, margin)
            binding.fullscreenButton.setText(R.string.fullscreen_enter)
            binding.root.keepScreenOn = false
            WindowCompat.setDecorFitsSystemWindows(window, true)
            WindowInsetsControllerCompat(window, binding.root)
                .show(WindowInsetsCompat.Type.systemBars())
        }
        binding.videoContainer.layoutParams = params
        binding.videoContainer.requestLayout()
    }

    override fun onBackPressed() {
        if (fullscreen) {
            setFullscreen(false)
            return
        }
        @Suppress("DEPRECATION")
        super.onBackPressed()
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
        missedPongs = 0
        mainHandler.postDelayed(pingRunnable, PING_START_DELAY_MS)
    }

    private fun stopPing() {
        mainHandler.removeCallbacks(pingRunnable)
        pingPending = false
        missedPongs = 0
    }

    private fun updateConnectionUi() {
        val busy = connected || connecting
        binding.connectButton.isEnabled = !busy
        binding.disconnectButton.isEnabled = busy
        binding.backButton.isEnabled = connected
        binding.homeButton.isEnabled = connected
        binding.recentsButton.isEnabled = connected
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
        const val KEY_QUALITY = "quality"
        const val KEY_FPS = "fps"
        const val KEY_FIT = "fit"
        const val DEFAULT_PORT = 8765
        const val TOUCH_MOVE_INTERVAL_MS = 16L
        const val SYNC_REQUEST_INTERVAL_MS = 1_000L
        const val PING_INTERVAL_MS = 1_000L
        const val PING_START_DELAY_MS = 250L
        const val PONG_TIMEOUT_MS = 2_500L
        const val MAX_MISSED_PONGS = 3
        const val MAX_RECONNECT_ATTEMPTS = 6
        const val RECONNECT_BASE_DELAY_MS = 1_200L
        const val RECONNECT_MAX_DELAY_MS = 6_000L
    }
}
