package com.example.phonecontrol.controller

import android.content.Context
import android.util.AttributeSet
import android.view.View
import android.widget.FrameLayout

/** 画面在容器里的排布方式。 */
enum class VideoFitMode {
    /** 等比缩放，完整显示，可能留黑边。 */
    FIT,

    /** 等比放大覆盖容器，超出部分裁掉，无黑边。 */
    FILL,

    /** 直接拉伸填满，比例会变形。 */
    STRETCH
}

/**
 * Sizes itself to the remote video aspect ratio inside the available space.
 *
 * - FIT：容器按视频比例收缩，与解码输出同比例，触摸坐标 1:1 映射。
 * - FILL / STRETCH：容器铺满可用空间，子 View 居中排布并裁剪溢出部分。
 */
class AspectRatioFrameLayout @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : FrameLayout(context, attrs, defStyleAttr) {

    /** 被控端视频的宽高比（width / height）。 */
    var aspectRatio: Float = 9f / 16f
        set(value) {
            if (!value.isFinite() || value <= 0f) return
            if (field != value) {
                field = value
                requestLayout()
            }
        }

    /** 非 0 时强制使用该比例（4:3、16:9 之类），忽略真实视频比例。 */
    var forcedAspectRatio: Float = 0f
        set(value) {
            val sanitized = if (value.isFinite() && value > 0f) value else 0f
            if (field != sanitized) {
                field = sanitized
                requestLayout()
            }
        }

    var fitMode: VideoFitMode = VideoFitMode.FIT
        set(value) {
            if (field != value) {
                field = value
                requestLayout()
            }
        }

    private val effectiveRatio: Float
        get() = if (forcedAspectRatio > 0f) forcedAspectRatio else aspectRatio

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val availableWidth = MeasureSpec.getSize(widthMeasureSpec)
        val availableHeight = MeasureSpec.getSize(heightMeasureSpec)
        if (availableWidth <= 0 || availableHeight <= 0) {
            super.onMeasure(widthMeasureSpec, heightMeasureSpec)
            return
        }

        val ratio = effectiveRatio
        var containerWidth: Int
        var containerHeight: Int
        var childWidth: Int
        var childHeight: Int

        when (fitMode) {
            VideoFitMode.FIT -> {
                containerWidth = availableWidth
                containerHeight = (containerWidth / ratio).toInt()
                if (containerHeight > availableHeight) {
                    containerHeight = availableHeight
                    containerWidth = (containerHeight * ratio).toInt()
                }
                childWidth = containerWidth
                childHeight = containerHeight
            }

            VideoFitMode.FILL -> {
                containerWidth = availableWidth
                containerHeight = availableHeight
                var coverWidth = containerWidth
                var coverHeight = (coverWidth / ratio).toInt()
                if (coverHeight < containerHeight) {
                    coverHeight = containerHeight
                    coverWidth = (coverHeight * ratio).toInt()
                }
                childWidth = coverWidth
                childHeight = coverHeight
            }

            VideoFitMode.STRETCH -> {
                containerWidth = availableWidth
                containerHeight = availableHeight
                childWidth = containerWidth
                childHeight = containerHeight
            }
        }

        containerWidth = containerWidth.coerceAtLeast(1)
        containerHeight = containerHeight.coerceAtLeast(1)
        childWidth = childWidth.coerceAtLeast(1)
        childHeight = childHeight.coerceAtLeast(1)
        setMeasuredDimension(containerWidth, containerHeight)

        val childWidthSpec = MeasureSpec.makeMeasureSpec(childWidth, MeasureSpec.EXACTLY)
        val childHeightSpec = MeasureSpec.makeMeasureSpec(childHeight, MeasureSpec.EXACTLY)
        for (index in 0 until childCount) {
            val child = getChildAt(index)
            if (child.visibility != View.GONE) {
                child.measure(childWidthSpec, childHeightSpec)
            }
        }
    }

    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        val parentWidth = right - left
        val parentHeight = bottom - top
        for (index in 0 until childCount) {
            val child = getChildAt(index)
            if (child.visibility == View.GONE) continue
            val childWidth = child.measuredWidth
            val childHeight = child.measuredHeight
            val childLeft = (parentWidth - childWidth) / 2
            val childTop = (parentHeight - childHeight) / 2
            child.layout(childLeft, childTop, childLeft + childWidth, childTop + childHeight)
        }
    }
}
