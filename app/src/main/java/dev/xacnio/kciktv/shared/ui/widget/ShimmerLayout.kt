/**
 * File: ShimmerLayout.kt
 *
 * Description: Implementation of Shimmer Layout functionality.
 *
 * Author: Xacnio
 *
 */
package dev.xacnio.kciktv.shared.ui.widget

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.*
import android.util.AttributeSet
import android.view.animation.LinearInterpolator
import android.widget.FrameLayout
import dev.xacnio.kciktv.R

class ShimmerLayout @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : FrameLayout(context, attrs, defStyleAttr) {

    private val paint = Paint()
    private var offset = -1f
    private var animator: ValueAnimator? = null
    private var isShimmering = false
    private var autoStart = true

    init {
        setWillNotDraw(false)
        
        if (attrs != null) {
            val typedArray = context.obtainStyledAttributes(attrs, R.styleable.ShimmerLayout)
            try {
                autoStart = typedArray.getBoolean(R.styleable.ShimmerLayout_shimmer_auto_start, true)
            } finally {
                typedArray.recycle()
            }
        }
        
        if (autoStart) {
            startShimmer()
        }
    }

    fun startShimmer() {
        if (isShimmering) return
        animator = ValueAnimator.ofFloat(-1f, 2f).apply {
            duration = 1500
            repeatCount = ValueAnimator.INFINITE
            interpolator = LinearInterpolator()
            addUpdateListener {
                offset = it.animatedValue as Float
                invalidate()
            }
            start()
        }
        isShimmering = true
    }

    fun stopShimmer() {
        animator?.cancel()
        animator = null
        isShimmering = false
        invalidate()
    }
    
    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        if (autoStart && !isShimmering) startShimmer()
    }
    
    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        stopShimmer()
    }

    override fun dispatchDraw(canvas: Canvas) {
        if (isShimmering) {
            // Save a layer to composite children and shimmer
            // allocating a bitmap buffer the size of the view (expensive?)
            // For a full screen view this might be heavy but it's the standard way to do masking.
            val saveCount = canvas.saveLayer(0f, 0f, width.toFloat(), height.toFloat(), null)
            
            super.dispatchDraw(canvas)

            val width = width.toFloat()
            val height = height.toFloat()
            
            // Lighter gradient band
            val shimmerWidth = width * 1.0f 
            val x = offset * width
            
            val colors = intArrayOf(
                0x00FFFFFF, // Transparent
                0x4DFFFFFF, // 30% White
                0x99FFFFFF.toInt(), // 60% White (Cast to Int because it exceeds positive Int range)
                0x4DFFFFFF, // 30% White
                0x00FFFFFF  // Transparent
            )
            val positions = floatArrayOf(0f, 0.4f, 0.5f, 0.6f, 1f)

            val shader = LinearGradient(
                x, 0f, 
                x + shimmerWidth, 0f, // Tilted? usually horizontal is fine. Let's do slightly tilted? 0,0 to w,0 is horizontal.
                colors, positions, 
                Shader.TileMode.CLAMP
            )
            
            // Add a slight tilt for style
            val matrix = Matrix()
            matrix.setTranslate(x, 0f)
            matrix.postSkew(-0.1f, 0f) // Skew x by -20 degrees?
            // LinearGradient doesn't use matrix directly in ctor, we use setLocalMatrix on shader
            shader.setLocalMatrix(matrix)

            paint.shader = shader
            paint.xfermode = PorterDuffXfermode(PorterDuff.Mode.SRC_ATOP)
            
            // Draw the shimmer rect over the entire view
            // The SRC_ATOP mode ensures it only paints where the underlying layer (children) has pixels.
            canvas.drawRect(0f, 0f, width, height, paint)
            
            paint.xfermode = null
            paint.shader = null
            
            canvas.restoreToCount(saveCount)
        } else {
            super.dispatchDraw(canvas)
        }
    }
}
