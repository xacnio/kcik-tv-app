/**
 * File: HoldBubbleView.kt
 *
 * Description: A circular hover that grows from a touch point. Driven externally via [radius];
 * it runs no animation of its own. Used as a hold-to-confirm indicator — once the bubble covers
 * the view, the hold has completed.
 *
 * Author: Xacnio
 *
 */
package dev.xacnio.kciktv.shared.ui.widget

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.util.AttributeSet
import android.view.View
import kotlin.math.hypot

class HoldBubbleView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = 0x3DFFFFFF
    }
    private val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = 0xCCFFFFFF.toInt()
        strokeWidth = context.resources.displayMetrics.density * 2f
    }

    private var centerX = 0f
    private var centerY = 0f

    var radius: Float = 0f
        set(value) {
            field = value
            invalidate()
        }

    fun setCenter(x: Float, y: Float) {
        centerX = x
        centerY = y
    }

    /** Radius at which the bubble covers the whole view: the distance to its farthest corner. */
    fun radiusToCover(): Float {
        val w = width.toFloat()
        val h = height.toFloat()
        return maxOf(
            hypot(centerX, centerY),
            hypot(w - centerX, centerY),
            hypot(centerX, h - centerY),
            hypot(w - centerX, h - centerY)
        )
    }

    override fun onDraw(canvas: Canvas) {
        if (radius <= 0f) return
        canvas.drawCircle(centerX, centerY, radius, fillPaint)
        canvas.drawCircle(centerX, centerY, radius, ringPaint)
    }
}
