package com.example.phonecontrol.agent

import android.accessibilityservice.AccessibilityService
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.util.DisplayMetrics
import android.util.Log
import android.view.WindowManager
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Foreground service that owns screen capture, the H.264 encoder and the
 * WebSocket server. It is started only after the user grants MediaProjection and
 * calls startForeground(mediaProjection) before getMediaProjection(), as required
 * by Android 14.
 */
class AgentService : Service() {

    private val mainHandler = Handler(Looper.getMainLooper())
    private val touchSession = TouchGestureSession()

    private var server: AgentWebSocketServer? = null
    private var capture: ScreenCaptureEngine? = null
    private var advertiser: NsdAdvertiser? = null
    private var projection: MediaProjection? = null
    private var wakeLock: PowerManager.WakeLock? = null

    @Volatile
    private var stopping = false

    private var foregroundStarted = false
    private var startingCapture = false

    // 控制端下发的采集参数（0 表示自动）
    @Volatile
    private var profileLongEdge = DEFAULT_LONG_EDGE

    @Volatile
    private var profileFps = DEFAULT_FPS

    @Volatile
    private var profileBitrateKbps = 0

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                val resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, 0)
                val resultData = intent.parcelableIntent(EXTRA_RESULT_DATA)
                if (resultData == null) {
                    AgentState.stopped("missing screen capture result")
                    stopEverything("missing screen capture result")
                } else {
                    startCapture(resultCode, resultData)
                }
            }

            ACTION_STOP -> stopEverything()
        }
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        if (!stopping) {
            stopping = true
            releaseResources()
            AgentState.stopped()
        }
        super.onDestroy()
    }

    private fun startCapture(resultCode: Int, resultData: Intent) {
        if (stopping || startingCapture || capture != null) return
        startingCapture = true

        try {
            AgentState.update {
                it.copy(isPreparing = true, error = null, localIp = NetworkUtils.localIpv4() ?: "--")
            }
            ensureForeground()

            val metrics = readDisplayMetrics()
            val screenWidth = metrics.widthPixels
            val screenHeight = metrics.heightPixels
            if (screenWidth <= 0 || screenHeight <= 0) {
                throw IllegalStateException("invalid display metrics")
            }

            val (captureWidth, captureHeight) = computeCaptureSize(screenWidth, screenHeight)

            AgentState.update {
                it.copy(
                    localIp = NetworkUtils.localIpv4() ?: "--",
                    port = PORT,
                    screenWidth = screenWidth,
                    screenHeight = screenHeight,
                    captureWidth = captureWidth,
                    captureHeight = captureHeight
                )
            }

            acquireWakeLock()

            val projectionManager = getSystemService(MediaProjectionManager::class.java)
            val newProjection = try {
                projectionManager.getMediaProjection(resultCode, resultData)
            } catch (t: Throwable) {
                Log.e(TAG, "getMediaProjection failed", t)
                null
            }
            if (newProjection == null) {
                throw IllegalStateException("MediaProjection permission was not granted")
            }
            projection = newProjection
            newProjection.registerCallback(projectionCallback, mainHandler)

            val engine = ScreenCaptureEngine(
                projection = newProjection,
                captureWidth = captureWidth,
                captureHeight = captureHeight,
                densityDpi = metrics.densityDpi,
                frameRate = profileFps,
                bitrateKbps = profileBitrateKbps,
                callbacks = object : ScreenCaptureEngine.Callbacks {
                    override fun onCodecConfig(packet: ByteArray) {
                        server?.updateCodecConfig(packet)
                    }

                    override fun onFrame(packet: ByteArray) {
                        server?.broadcastFrame(packet)
                    }

                    override fun onError(error: Throwable) {
                        Log.e(TAG, "capture error", error)
                        fail(getString(R.string.error_capture, error.message ?: error.javaClass.simpleName))
                    }
                }
            )
            capture = engine

            val newServer = AgentWebSocketServer(
                port = PORT,
                deviceName = "${Build.MANUFACTURER} ${Build.MODEL}".trim(),
                screenWidth = screenWidth,
                screenHeight = screenHeight,
                listener = serverListener
            )
            server = newServer
            newServer.startServer()
            engine.start()

            AgentState.update { it.copy(isSharing = true, isPreparing = false) }
            updateNotification()
            Log.i(TAG, "sharing started; screen=${screenWidth}x$screenHeight capture=${captureWidth}x$captureHeight")
        } catch (t: Throwable) {
            Log.e(TAG, "startCapture failed", t)
            fail(t.message ?: t.javaClass.simpleName)
        } finally {
            startingCapture = false
        }
    }

    private val projectionCallback = object : MediaProjection.Callback() {
        override fun onStop() {
            mainHandler.post {
                if (!stopping) {
                    fail(getString(R.string.error_projection_stopped))
                }
            }
        }
    }

    private val serverListener = object : AgentWebSocketServer.Listener {
        override fun onServerStarted() {
            Log.i(TAG, "server started on port $PORT")
            AgentState.update { it.copy(isServerRunning = true) }
            updateNotification()
            mainHandler.post {
                if (stopping) return@post
                advertiser = NsdAdvertiser(this@AgentService) { mdnsState ->
                    AgentState.update { it.copy(mdnsState = mdnsState) }
                }.also { it.start(PORT) }
            }
        }

        override fun onServerError(message: String) {
            fail(getString(R.string.error_port_busy, PORT))
        }

        override fun onClientsChanged(count: Int) {
            AgentState.update { it.copy(connections = count) }
            updateNotification()
        }

        override fun onTouch(phase: String, x: Float, y: Float) {
            touchSession.handle(phase, x, y)
        }

        override fun onAction(action: String) {
            val globalAction = when (action) {
                "back" -> AccessibilityService.GLOBAL_ACTION_BACK
                "home" -> AccessibilityService.GLOBAL_ACTION_HOME
                "recents" -> AccessibilityService.GLOBAL_ACTION_RECENTS
                else -> return
            }
            GestureInjector.globalAction(globalAction)
        }

        override fun onRequestKeyFrame() {
            capture?.requestKeyFrame()
        }

        override fun onProfile(longEdge: Int, fps: Int, bitrateKbps: Int) {
            mainHandler.post { applyProfile(longEdge, fps, bitrateKbps) }
        }
    }

    /**
     * 控制端改画质 / 帧率：复用已有 MediaProjection 和 VirtualDisplay，
     * 只重建编码器，所以不会重新弹授权。
     */
    private fun applyProfile(longEdge: Int, fps: Int, bitrateKbps: Int) {
        if (stopping) return
        val engine = capture ?: return
        val newLongEdge = if (longEdge > 0) longEdge else DEFAULT_LONG_EDGE
        val newFps = if (fps in MIN_FPS..MAX_FPS) fps else DEFAULT_FPS
        val newBitrate = bitrateKbps.coerceIn(0, MAX_BITRATE_KBPS)

        val unchanged = newLongEdge == profileLongEdge &&
            newFps == profileFps &&
            newBitrate == profileBitrateKbps
        profileLongEdge = newLongEdge
        profileFps = newFps
        profileBitrateKbps = newBitrate
        if (unchanged) return

        val metrics = readDisplayMetrics()
        if (metrics.widthPixels <= 0 || metrics.heightPixels <= 0) return
        val (width, height) = computeCaptureSize(metrics.widthPixels, metrics.heightPixels)
        val applied = engine.reconfigure(width, height, newFps, newBitrate)
        if (applied) {
            AgentState.update { it.copy(captureWidth = width, captureHeight = height) }
            Log.i(TAG, "profile applied: ${width}x$height @ ${newFps}fps")
        } else {
            Log.w(TAG, "profile rejected by encoder, kept previous capture size")
        }
    }

    /** 按控制端要求的长边上限等比缩放，并保证宽高都是偶数（编码器要求）。 */
    private fun computeCaptureSize(screenWidth: Int, screenHeight: Int): Pair<Int, Int> {
        val longEdge = profileLongEdge.coerceIn(MIN_LONG_EDGE, MAX_LONG_EDGE)
        val maxSide = max(screenWidth, screenHeight)
        if (maxSide <= 0) {
            return evenDimension(screenWidth) to evenDimension(screenHeight)
        }
        val scale = minOf(1f, longEdge.toFloat() / maxSide.toFloat())
        val width = evenDimension((screenWidth * scale).roundToInt())
        val height = evenDimension((screenHeight * scale).roundToInt())
        return width to height
    }

    private fun fail(message: String) {
        mainHandler.post {
            if (stopping) return@post
            Log.e(TAG, "failing: $message")
            stopEverything(message)
        }
    }

    private fun stopEverything(error: String? = null) {
        if (stopping) return
        stopping = true
        touchSession.cancel()
        releaseResources()
        AgentState.stopped(error)
        stopForeground(Service.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun releaseResources() {
        advertiser?.stop()
        advertiser = null

        server?.stopServer()
        server = null

        val activeCapture = capture
        capture = null
        if (activeCapture != null) {
            // ScreenCaptureEngine owns the projection and stops it after releasing
            // the encoder and virtual display.
            activeCapture.stop()
            projection = null
        } else {
            try {
                projection?.stop()
            } catch (t: Throwable) {
                Log.w(TAG, "projection stop failed", t)
            }
            projection = null
        }

        try {
            wakeLock?.takeIf { it.isHeld }?.release()
        } catch (t: Throwable) {
            Log.w(TAG, "wake lock release failed", t)
        }
        wakeLock = null
    }

    private fun ensureForeground() {
        val notification = buildNotification()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
        foregroundStarted = true
    }

    private fun updateNotification() {
        if (!foregroundStarted) return
        try {
            val manager = getSystemService(NotificationManager::class.java)
            manager.notify(NOTIFICATION_ID, buildNotification())
        } catch (t: Throwable) {
            Log.w(TAG, "notification update failed", t)
        }
    }

    private fun buildNotification(): Notification {
        val state = AgentState.state.value
        val text = if (state.isSharing) {
            getString(
                R.string.notification_text,
                state.localIp,
                state.port,
                state.connections
            )
        } else {
            getString(R.string.notification_preparing)
        }

        val contentIntent = PendingIntent.getActivity(
            this,
            REQUEST_CONTENT,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val stopIntent = PendingIntent.getService(
            this,
            REQUEST_STOP,
            Intent(this, AgentService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(getString(R.string.notification_title))
            .setContentText(text)
            .setContentIntent(contentIntent)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setShowWhen(false)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .addAction(R.drawable.ic_notification, getString(R.string.notification_stop), stopIntent)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            builder.setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
        }
        return builder.build()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = getSystemService(NotificationManager::class.java)
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.channel_name),
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = getString(R.string.channel_description)
            setShowBadge(false)
            setSound(null, null)
            enableVibration(false)
        }
        manager.createNotificationChannel(channel)
    }

    @Suppress("DEPRECATION")
    private fun readDisplayMetrics(): DisplayMetrics {
        val windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val metrics = DisplayMetrics()
        windowManager.defaultDisplay.getRealMetrics(metrics)
        return metrics
    }

    private fun acquireWakeLock() {
        try {
            val powerManager = getSystemService(PowerManager::class.java)
            wakeLock = powerManager.newWakeLock(
                PowerManager.PARTIAL_WAKE_LOCK,
                "PhoneControl::ScreenSharing"
            ).apply {
                setReferenceCounted(false)
                acquire()
            }
        } catch (t: Throwable) {
            Log.w(TAG, "wake lock failed", t)
        }
    }

    private fun evenDimension(value: Int): Int {
        return ((value.coerceAtLeast(16)) / 2 * 2)
    }

    @Suppress("DEPRECATION")
    private fun Intent.parcelableIntent(key: String): Intent? {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            getParcelableExtra(key, Intent::class.java)
        } else {
            getParcelableExtra(key)
        }
    }

    companion object {
        private const val TAG = "AgentService"
        private const val CHANNEL_ID = "phone_control_sharing"
        private const val NOTIFICATION_ID = 1001
        private const val REQUEST_CONTENT = 100
        private const val REQUEST_STOP = 101
        private const val DEFAULT_LONG_EDGE = 1280
        private const val MIN_LONG_EDGE = 240
        private const val MAX_LONG_EDGE = 2560
        private const val DEFAULT_FPS = 30
        private const val MIN_FPS = 10
        private const val MAX_FPS = 60
        private const val MAX_BITRATE_KBPS = 40_000

        const val PORT = 8765

        private const val ACTION_START = "com.example.phonecontrol.agent.action.START"
        private const val ACTION_STOP = "com.example.phonecontrol.agent.action.STOP"
        private const val EXTRA_RESULT_CODE = "screen_capture_result_code"
        private const val EXTRA_RESULT_DATA = "screen_capture_result_data"

        fun start(context: Context, resultCode: Int, resultData: Intent) {
            val intent = Intent(context, AgentService::class.java).apply {
                action = ACTION_START
                putExtra(EXTRA_RESULT_CODE, resultCode)
                putExtra(EXTRA_RESULT_DATA, resultData)
            }
            ContextCompat.startForegroundService(context, intent)
        }

        fun stop(context: Context) {
            val intent = Intent(context, AgentService::class.java).setAction(ACTION_STOP)
            try {
                context.startService(intent)
            } catch (_: Exception) {
                context.stopService(Intent(context, AgentService::class.java))
            }
        }
    }
}
