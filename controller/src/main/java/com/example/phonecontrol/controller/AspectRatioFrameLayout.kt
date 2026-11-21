package com.example.phonecontrol.controller

import android.content.Context
import android.util.AttributeSet
import android.view.View
import android.widget.FrameLayout

/**
 * Sizes itself to the remote video aspect ratio inside the available space.
 * Keeping the SurfaceView exactly the same aspect ratio as the decoder output
 * means a touch position / view size maps 1:1 to the normalized video position.
 */
class AspectRatioFrameLayout @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : FrameLayout(context, attrs, defStyleAttr) {

    var aspectRatio: Float = 9f / 16f
        set(value) {
            if (!value.isFinite() || value <= 0f) return
            if (field != value) {
                field = value
                requestLayout()
            }
        }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val availableWidth = MeasureSpec.getSize(widthMeasureSpec)
        val availableHeight = MeasureSpec.getSize(heightMeasureSpec)
        if (availableWidth <= 0 || availableHeight <= 0) {
            super.onMeasure(widthMeasureSpec, heightMeasureSpec)
            return
        }

        var measuredWidth = availableWidth
        var measuredHeight = (measuredWidth / aspectRatio).toInt()
        if (measuredHeight > availableHeight) {
            measuredHeight = availableHeight
            measuredWidth = (measuredHeight * aspectRatio).toInt()
        }
        measuredWidth = measuredWidth.coerceAtLeast(1)
        measuredHeight = measuredHeight.coerceAtLeast(1)
        setMeasuredDimension(measuredWidth, measuredHeight)

        val childWidthSpec = MeasureSpec.makeMeasureSpec(measuredWidth, MeasureSpec.EXACTLY)
        val childHeightSpec = MeasureSpec.makeMeasureSpec(measuredHeight, MeasureSpec.EXACTLY)
        for (index in 0 until childCount) {
            val child = getChildAt(index)
            if (child.visibility != View.GONE) {
                child.measure(childWidthSpec, childHeightSpec)
            }
        }
    }
}
