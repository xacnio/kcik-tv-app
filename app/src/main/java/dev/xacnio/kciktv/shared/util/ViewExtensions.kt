/**
 * File: ViewExtensions.kt
 *
 * Description: Implementation of View Extensions functionality.
 *
 * Author: Xacnio
 *
 */
package dev.xacnio.kciktv.shared.util

import android.content.res.Resources

/**
 * Extension functions for View classes.
 */

/**
 * Convert dp to px
 */
fun Int.dpToPx(resources: Resources): Int {
    return (this * resources.displayMetrics.density).toInt()
}

/**
 * Convert dp (Float) to px
 */
fun Float.dpToPx(resources: Resources): Float {
    return this * resources.displayMetrics.density
}
