package com.example.phonecontrol.agent

import android.os.SystemClock
import kotlin.math.hypot

/**
 * Buffers the controller's down/move/up stream and turns it into one atomic
 * AccessibilityService gesture when the finger is released. dispatchGesture can
 * only inject complete gestures, so a drag cannot be extended after it starts.
 */
class TouchGestureSession {
    private val lock = Any()
    private val points = ArrayList<Pair<Float, Float>>(256)
    private var active = false
    private var startX = 0f
    private var startY = 0f
    private var lastX = 0f
    private var lastY = 0f
    private var startTime = 0L

    fun handle(phase: String, x: Float, y: Float) {
        val finished: FinishedGesture? = synchronized(lock) {
            when (phase) {
                "down" -> {
                    active = true
                    points.clear()
                    startX = x
                    startY = y
                    lastX = x
                    lastY = y
                    startTime = SystemClock.uptimeMillis()
                    points.add(x to y)
                    null
                }

                "move" -> {
                    if (active) {
                        if (hypot(x - lastX, y - lastY) >= MIN_POINT_DISTANCE) {
                            lastX = x
                            lastY = y
                            if (points.size < MAX_POINTS) {
                                points.add(x to y)
                            } else {
                                points[points.lastIndex] = x to y
                            }
                        }
                    }
                    null
                }

                "up" -> {
                    if (!active) {
                        null
                    } else {
                        active = false
                        if (hypot(x - lastX, y - lastY) >= MIN_POINT_DISTANCE) {
                            if (points.size < MAX_POINTS) points.add(x to y)
                            else points[points.lastIndex] = x to y
                        }
                        buildFinishedGesture(x, y)
                    }
                }

                "cancel" -> {
                    active = false
                    points.clear()
                    null
                }

                else -> null
            }
        }
        finished?.let(::dispatch)
    }

    fun cancel() {
        synchronized(lock) {
            active = false
            points.clear()
        }
    }

    private fun buildFinishedGesture(upX: Float, upY: Float): FinishedGesture {
        val elapsed = (SystemClock.uptimeMillis() - startTime).coerceAtLeast(1L)
        val maxDistance = points.maxOfOrNull { (x, y) -> hypot(x - startX, y - startY) } ?: 0f
        return when {
            elapsed >= LONG_PRESS_MS && maxDistance <= LONG_PRESS_SLOP ->
                FinishedGesture.LongPress(startX, startY)

            maxDistance <= TAP_SLOP ->
                FinishedGesture.Tap(upX, upY)

            else -> {
                if (points.size < 2) points.add(upX to upY)
                FinishedGesture.Swipe(points.toList(), elapsed)
            }
        }
    }

    private fun dispatch(gesture: FinishedGesture) {
        when (gesture) {
            is FinishedGesture.Tap -> GestureInjector.tap(gesture.x, gesture.y)
            is FinishedGesture.LongPress -> GestureInjector.longPress(gesture.x, gesture.y)
            is FinishedGesture.Swipe -> GestureInjector.swipe(gesture.points, gesture.durationMs)
        }
    }

    private sealed interface FinishedGesture {
        data class Tap(val x: Float, val y: Float) : FinishedGesture
        data class LongPress(val x: Float, val y: Float) : FinishedGesture
        data class Swipe(
            val points: List<Pair<Float, Float>>,
            val durationMs: Long
        ) : FinishedGesture
    }

    private companion object {
        const val MAX_POINTS = 240
        const val MIN_POINT_DISTANCE = 0.0015f
        const val TAP_SLOP = 0.018f
        const val LONG_PRESS_SLOP = 0.025f
        const val LONG_PRESS_MS = 550L
    }
}
