package com.example.phonecontrol.agent

import android.accessibilityservice.GestureDescription
import android.content.Context
import android.graphics.Path
import android.graphics.Point
import android.os.Handler
import android.os.Looper
import android.view.WindowManager

/**
 * Converts normalized (0..1) coordinates into a gesture on the real display and
 * dispatches it through AccessibilityService.dispatchGesture.
 */
object GestureInjector {
    private val mainHandler = Handler(Looper.getMainLooper())

    fun isAvailable(): Boolean = RemoteAccessibilityService.peek() != null

    fun tap(normalizedX: Float, normalizedY: Float) {
        val screen = screenSize() ?: return
        val path = Path().apply {
            moveTo(normalizedX.toPx(screen.x), normalizedY.toPx(screen.y))
        }
        dispatch(path, TAP_DURATION_MS)
    }

    fun longPress(normalizedX: Float, normalizedY: Float) {
        val screen = screenSize() ?: return
        val path = Path().apply {
            moveTo(normalizedX.toPx(screen.x), normalizedY.toPx(screen.y))
        }
        dispatch(path, LONG_PRESS_DURATION_MS)
    }

    /**
     * [points] must contain at least two normalized points. The path is replayed
     * with the duration measured by the controller.
     */
    fun swipe(points: List<Pair<Float, Float>>, durationMs: Long) {
        if (points.size < 2) {
            points.firstOrNull()?.let { tap(it.first, it.second) }
            return
        }
        val screen = screenSize() ?: return
        val path = Path()
        points.forEachIndexed { index, (x, y) ->
            val px = x.toPx(screen.x)
            val py = y.toPx(screen.y)
            if (index == 0) path.moveTo(px, py) else path.lineTo(px, py)
        }
        dispatch(path, durationMs.coerceIn(1L, MAX_GESTURE_DURATION_MS))
    }

    /** [globalAction] is one of AccessibilityService.GLOBAL_ACTION_*. */
    fun globalAction(globalAction: Int) {
        mainHandler.post {
            RemoteAccessibilityService.peek()?.performGlobalAction(globalAction)
        }
    }

    private fun dispatch(path: Path, durationMs: Long) {
        mainHandler.post {
            val service = RemoteAccessibilityService.peek() ?: return@post
            if (path.isEmpty) return@post
            val gesture = GestureDescription.Builder()
                .addStroke(
                    GestureDescription.StrokeDescription(
                        path,
                        0L,
                        durationMs.coerceIn(1L, MAX_GESTURE_DURATION_MS)
                    )
                )
                .build()
            try {
                service.dispatchGesture(gesture, null, null)
            } catch (_: IllegalArgumentException) {
                // The display may have changed between measuring and dispatching.
            }
        }
    }

    private fun screenSize(): Point? {
        val service = RemoteAccessibilityService.peek() ?: return null
        val windowManager = service.getSystemService(Context.WINDOW_SERVICE) as? WindowManager
            ?: return null
        val point = Point()
        try {
            @Suppress("DEPRECATION")
            windowManager.defaultDisplay.getRealSize(point)
        } catch (_: Exception) {
            return null
        }
        return point.takeIf { it.x > 0 && it.y > 0 }
    }

    private fun Float.toPx(size: Int): Float =
        (coerceIn(0f, 1f) * size).coerceIn(0f, (size - 1).toFloat())

    private const val TAP_DURATION_MS = 60L
    private const val LONG_PRESS_DURATION_MS = 650L
    private const val MAX_GESTURE_DURATION_MS = 5_000L
}
