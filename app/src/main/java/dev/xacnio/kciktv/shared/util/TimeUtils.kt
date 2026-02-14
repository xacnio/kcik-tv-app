/**
 * File: TimeUtils.kt
 *
 * Description: Utility helper class providing static methods for Time.
 *
 * Author: Xacnio
 *
 */
package dev.xacnio.kciktv.shared.util

import android.content.Context
import dev.xacnio.kciktv.R

object TimeUtils {
    /**
     * Formats duration in minutes into human readable string (e.g. "2d 5h 30m")
     */
    fun formatDuration(context: Context, totalMinutes: Int): String {
        if (totalMinutes <= 0) return ""

        val days = totalMinutes / 1440
        val remainingAfterDays = totalMinutes % 1440
        val hours = remainingAfterDays / 60
        val minutes = remainingAfterDays % 60

        val parts = mutableListOf<String>()
        if (days > 0) parts.add(context.getString(R.string.duration_days, days))
        if (hours > 0) parts.add(context.getString(R.string.duration_hours, hours))
        if (minutes > 0) parts.add(context.getString(R.string.duration_minutes, minutes))

        return parts.joinToString(" ")
    }

    /**
     * Formats slow mode duration in seconds into human readable string (e.g. "1m 30s" or "30s")
     */
    fun formatSlowModeDuration(context: Context, totalSeconds: Int): String {
        if (totalSeconds <= 0) return ""

        val minutes = totalSeconds / 60
        val seconds = totalSeconds % 60

        val parts = mutableListOf<String>()
        if (minutes > 0) parts.add(context.getString(R.string.duration_minutes, minutes))
        if (seconds > 0) parts.add(context.getString(R.string.duration_seconds, seconds))
        
        // Fallback for exactly 0 seconds if the logic above results in empty
        if (parts.isEmpty() && totalSeconds == 0) {
            parts.add(context.getString(R.string.duration_seconds, 0))
        }

        return parts.joinToString(" ")
    }
    /**
     * Formats remaining time in milliseconds into localized human readable string (e.g. "1d 2h 30m 15s")
     */
    fun formatRemainingTime(context: Context, remainingMs: Long): String {
        if (remainingMs <= 0) return ""

        val days = (remainingMs / 86400000).toInt()
        val hours = ((remainingMs % 86400000) / 3600000).toInt()
        val minutes = ((remainingMs % 3600000) / 60000).toInt()
        val seconds = ((remainingMs % 60000) / 1000).toInt()

        val parts = mutableListOf<String>()
        if (days > 0) parts.add(context.getString(R.string.duration_days, days))
        if (hours > 0) parts.add(context.getString(R.string.duration_hours, hours))
        if (minutes > 0) parts.add(context.getString(R.string.duration_minutes, minutes))
        if (seconds > 0 || parts.isEmpty()) parts.add(context.getString(R.string.duration_seconds, seconds))

        return parts.joinToString(" ")
    }
    
    /**
     * Parses ISO date string to milliseconds
     */

    fun parseIso8601ToMillis(isoDateString: String?): Long? {
        if (isoDateString.isNullOrEmpty()) return null
        val cleaned = if (isoDateString.contains(".")) {
             isoDateString.substringBefore(".") + "Z"
        } else {
             isoDateString
        }
        
        // Try ISO format
        try {
            val format = java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", java.util.Locale.US)
            format.timeZone = java.util.TimeZone.getTimeZone("UTC")
            return format.parse(cleaned)?.time
        } catch (e: Exception) {
            // Try SQL format (yyyy-MM-dd HH:mm:ss)
            try {
                val format = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.US)
                format.timeZone = java.util.TimeZone.getTimeZone("UTC")
                return format.parse(isoDateString)?.time
            } catch (e2: Exception) {
                return null
            }
        }
    }

    /**
     * Converts a timestamp to relative time like "2 hours ago"
     */
    fun getRelativeTimeSpan(context: Context, timeMillis: Long): String {
        val now = System.currentTimeMillis()
        val diff = now - timeMillis
        
        val seconds = diff / 1000
        val minutes = seconds / 60
        val hours = minutes / 60
        val days = hours / 24
        val months = days / 30
        val years = days / 365
        
        return when {
            years > 0 -> context.getString(R.string.time_ago_years, years)
            months > 0 -> context.getString(R.string.time_ago_months, months)
            days > 0 -> context.getString(R.string.time_ago_days, days)
            hours > 0 -> context.getString(R.string.time_ago_hours, hours)
            minutes > 0 -> context.getString(R.string.time_ago_minutes, minutes)
            else -> context.getString(R.string.time_ago_just_now)
        }
    }

    /**
     * Converts an ISO date string to relative time like "2 hours ago"
     */
    fun getRelativeTimeSpan(context: Context, isoDateString: String?): String {
        val time = parseIso8601ToMillis(isoDateString) ?: return ""
        return getRelativeTimeSpan(context, time)
    }
}
