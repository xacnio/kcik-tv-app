/**
 * File: MaxHeightRecyclerView.kt
 *
 * Description: Implementation of Max Height Recycler View functionality.
 *
 * Author: Xacnio
 *
 */
package dev.xacnio.kciktv.shared.ui.view

import android.content.Context
import android.util.AttributeSet
import androidx.recyclerview.widget.RecyclerView
import dev.xacnio.kciktv.R

class MaxHeightRecyclerView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : RecyclerView(context, attrs, defStyleAttr) {

    private var maxHeight: Int = -1

    init {
        if (attrs != null) {
            val a = context.obtainStyledAttributes(attrs, intArrayOf(android.R.attr.maxHeight))
            maxHeight = a.getDimensionPixelSize(0, -1)
            a.recycle()
        }
    }

    override fun onMeasure(widthSpec: Int, heightSpec: Int) {
        var newHeightSpec = heightSpec
        if (maxHeight > 0) {
            newHeightSpec = MeasureSpec.makeMeasureSpec(maxHeight, MeasureSpec.AT_MOST)
        }
        super.onMeasure(widthSpec, newHeightSpec)
    }

    fun setMaxHeight(height: Int) {
        maxHeight = height
        requestLayout()
    }
}
