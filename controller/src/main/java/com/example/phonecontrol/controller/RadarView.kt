package com.example.phonecontrol.controller

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.SweepGradient
import android.util.AttributeSet
import android.view.View
import android.view.animation.LinearInterpolator
import androidx.core.content.ContextCompat

/**
 * 极简雷达扫描动画，用于「等待画面接入」的空状态。
 * 三个同心圆环 + 一道渐变扇形持续旋转，纯 Canvas 绘制，不占 GPU。
 */
class RadarView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = resources.displayMetrics.density
        color = ContextCompat.getColor(context, R.color.card_stroke)
    }

    private val sweepPaint = Paint(Paint.ANTI_ALIAS_FLAG)

    private val centerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ContextCompat.getColor(context, R.color.brand_secondary)
    }

    private val brandColor = ContextCompat.getColor(context, R.color.brand_primary)

    private var angle = 0f
    private var radius = 0f
    private var animator: ValueAnimator? = null

    override fun onSizeChanged(width: Int, height: Int, oldWidth: Int, oldHeight: Int) {
        super.onSizeChanged(width, height, oldWidth, oldHeight)
        radius = minOf(width, height) / 2f - ringPaint.strokeWidth
        if (radius <= 0f) return
        val cx = width / 2f
        val cy = height / 2f
        sweepPaint.shader = SweepGradient(
            cx,
            cy,
            intArrayOf(
                Color.TRANSPARENT,
                Color.TRANSPARENT,
                withAlpha(brandColor, 0x33),
                withAlpha(brandColor, 0xAA)
            ),
            floatArrayOf(0f, 0.58f, 0.9f, 1f)
        )
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (radius <= 0f) return
        val cx = width / 2f
        val cy = height / 2f

        for (index in 1..3) {
            canvas.drawCircle(cx, cy, radius * index / 3f, ringPaint)
        }

        canvas.save()
        canvas.rotate(angle, cx, cy)
        canvas.drawCircle(cx, cy, radius, sweepPaint)
        canvas.restore()

        canvas.drawCircle(cx, cy, radius * 0.045f, centerPaint)
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        start()
    }

    override fun onDetachedFromWindow() {
        stop()
        super.onDetachedFromWindow()
    }

    fun start() {
        if (animator != null) return
        animator = ValueAnimator.ofFloat(0f, 360f).apply {
            duration = 2400L
            repeatCount = ValueAnimator.INFINITE
            interpolator = LinearInterpolator()
            addUpdateListener {
                angle = it.animatedValue as Float
                invalidate()
            }
            start()
        }
    }

    fun stop() {
        animator?.cancel()
        animator = null
    }

    private fun withAlpha(color: Int, alpha: Int): Int =
        (color and 0x00FFFFFF) or (alpha shl 24)
}
