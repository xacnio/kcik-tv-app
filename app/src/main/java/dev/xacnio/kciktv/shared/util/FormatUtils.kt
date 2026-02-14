/**
 * File: FormatUtils.kt
 *
 * Description: Utility helper class providing static methods for Format.
 *
 * Author: Xacnio
 *
 */
package dev.xacnio.kciktv.shared.util

import android.content.Context
import dev.xacnio.kciktv.R
import java.text.DecimalFormat
import java.util.Locale

/**
 * Utility object for formatting various values for display.
 */
object FormatUtils {
    
    /**
     * Formats viewer count into human-readable format.
     * Examples: 1234 -> "1.2K", 1234567 -> "1.2M"
     * 
     * @param count The viewer count
     * @return Formatted string
     */
    fun formatViewerCount(count: Long, forceFull: Boolean = false): String {
        // LTR mark (\u200E) prevents bidirectional text issues when mixed with RTL languages like Arabic
        val ltrMark = "\u200E"
        
        if (forceFull) return ltrMark + DecimalFormat("#,###").format(count)

        return when {
            count >= 1_000_000 -> {
                val millions = count / 1_000_000.0
                val formatted = String.format(Locale.US, "%.1f", millions)
                ltrMark + if (formatted.endsWith(".0")) "${formatted.substringBefore(".")}M" else "${formatted}M"
            }
            count >= 1_000 -> {
                val thousands = count / 1_000.0
                val formatted = String.format(Locale.US, "%.1f", thousands)
                ltrMark + if (formatted.endsWith(".0")) "${formatted.substringBefore(".")}K" else "${formatted}K"
            }
            else -> ltrMark + count.toString()
        }
    }
    
    /**
     * Overload for Int viewer count
     */
    fun formatViewerCount(count: Int, forceFull: Boolean = false): String = formatViewerCount(count.toLong(), forceFull)
    
    /**
     * Formats stream uptime in HH:MM:SS format.
     * 
     * @param seconds Total seconds
     * @return Formatted string like "02:15:30"
     */
    fun formatStreamTime(seconds: Long): String {
        val hours = seconds / 3600
        val minutes = (seconds % 3600) / 60
        val secs = seconds % 60
        return String.format(Locale.US, "%02d:%02d:%02d", hours, minutes, secs)
    }
    
    /**
     * Formats duration in MM:SS format for clips/VODs.
     * 
     * @param seconds Total seconds
     * @return Formatted string like "05:30"
     */
    fun formatDurationShort(seconds: Int): String {
        val minutes = seconds / 60
        val secs = seconds % 60
        return String.format(Locale.US, "%02d:%02d", minutes, secs)
    }
    
    /**
     * Formats duration in HH:MM:SS format for longer content.
     * 
     * @param seconds Total seconds
     * @return Formatted string like "01:23:45"
     */
    fun formatDurationLong(seconds: Int): String {
        val hours = seconds / 3600
        val minutes = (seconds % 3600) / 60
        val secs = seconds % 60
        return String.format(Locale.US, "%02d:%02d:%02d", hours, minutes, secs)
    }

    /**
     * Smart formatting based on duration length (MM:SS or HH:MM:SS)
     * Handles inputs in milliseconds.
     */
    fun formatDuration(millis: Long): String {
        val seconds = (millis / 1000).toInt()
        return if (seconds >= 3600) {
            formatDurationLong(seconds)
        } else {
            formatDurationShort(seconds)
        }
    }
    
    /**
     * Formats a double value with specified decimal places.
     * 
     * @param value The value to format
     * @param decimals Number of decimal places
     * @return Formatted string
     */
    fun formatDecimal(value: Double, decimals: Int = 2): String {
        val pattern = if (decimals > 0) "#." + "#".repeat(decimals) else "#"
        return DecimalFormat(pattern).format(value)
    }
    
    /**
     * Formats file size in human-readable format.
     * 
     * @param bytes Size in bytes
     * @return Formatted string like "1.5 MB"
     */
    fun formatFileSize(bytes: Long): String {
        return when {
            bytes >= 1_073_741_824 -> String.format(Locale.US, "%.1f GB", bytes / 1_073_741_824.0)
            bytes >= 1_048_576 -> String.format(Locale.US, "%.1f MB", bytes / 1_048_576.0)
            bytes >= 1024 -> String.format(Locale.US, "%.1f KB", bytes / 1024.0)
            else -> "$bytes B"
        }
    }
    
    /**
     * Formats percentage value.
     * 
     * @param value The percentage value (0-100)
     * @return Formatted string like "85%"
     */
    fun formatPercentage(value: Int): String {
        return "$value%"
    }
    
    /**
     * Formats percentage value from ratio (0.0-1.0).
     * 
     * @param ratio The ratio value
     * @return Formatted string like "85%"
     */
    fun formatPercentageFromRatio(ratio: Double): String {
        return "${(ratio * 100).toInt()}%"
    }
    
    /**
     * Converts dp to pixels.
     * 
     * @param dp The dp value
     * @param context Android context
     * @return Pixel value
     */
    fun dpToPx(dp: Int, context: Context): Int {
        return (dp * context.resources.displayMetrics.density).toInt()
    }
    
    /**
     * Converts pixels to dp.
     * 
     * @param px The pixel value
     * @param context Android context
     * @return Dp value
     */
    fun pxToDp(px: Int, context: Context): Int {
        return (px / context.resources.displayMetrics.density).toInt()
    }
}
