/**
 * File: ShimmerDrawable.kt
 *
 * Description: Implementation of Shimmer Drawable functionality.
 *
 * Author: Xacnio
 *
 */
package dev.xacnio.kciktv.shared.util

import android.animation.ValueAnimator
import android.graphics.*
import android.graphics.drawable.Drawable
import android.view.animation.LinearInterpolator

class ShimmerDrawable(private val isCircle: Boolean = true) : Drawable() {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private var offset = 0f
    private val rectF = RectF()
    private val animator: ValueAnimator = ValueAnimator.ofFloat(-1f, 1f).apply {
        duration = 1500
        repeatCount = ValueAnimator.INFINITE
        interpolator = LinearInterpolator()
        addUpdateListener {
            offset = it.animatedValue as Float
            invalidateSelf()
        }
    }

    init {
        animator.start()
    }

    override fun draw(canvas: Canvas) {
        val width = bounds.width().toFloat()
        val height = bounds.height().toFloat()
        if (width <= 0 || height <= 0) return

        val shimmerWidth = width * 1.5f
        val startX = offset * shimmerWidth
        
        val colors = intArrayOf(
            Color.parseColor("#2a2a2a"),
            Color.parseColor("#3a3a3a"),
            Color.parseColor("#4a4a4a"),
            Color.parseColor("#3a3a3a"),
            Color.parseColor("#2a2a2a")
        )
        val positions = floatArrayOf(0f, 0.35f, 0.5f, 0.65f, 1f)
        
        paint.shader = LinearGradient(
            startX, 0f, startX + width, height,
            colors, positions, Shader.TileMode.CLAMP
        )
        
        if (isCircle) {
            val radius = minOf(width, height) / 2f
            canvas.drawCircle(width / 2f, height / 2f, radius, paint)
        } else {
            rectF.set(0f, 0f, width, height)
            val cornerRadius = 8f * canvas.density // 8dp rounded corners
            canvas.drawRoundRect(rectF, cornerRadius, cornerRadius, paint)
        }
    }

    override fun setAlpha(alpha: Int) {
        paint.alpha = alpha
    }

    override fun setColorFilter(colorFilter: ColorFilter?) {
        paint.colorFilter = colorFilter
    }

    override fun getOpacity(): Int = PixelFormat.TRANSLUCENT

    fun stop() {
        animator.cancel()
    }
}
