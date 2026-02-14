/**
 * File: DateParseUtils.kt
 *
 * Description: Utility helper class providing static methods for Date Parse.
 *
 * Author: Xacnio
 *
 */
package dev.xacnio.kciktv.shared.util

import android.util.Log
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone

/**
 * Utility object for parsing and formatting date strings.
 */
object DateParseUtils {
    private const val TAG = "DateParseUtils"
    
    /**
     * Parses an ISO 8601 date string to milliseconds since epoch.
     * Handles various formats including microseconds, timezone offsets, and edge cases.
     * 
     * @param isoString The ISO date string to parse (e.g., "2024-01-20T15:30:45.123456Z")
     * @return Milliseconds since epoch, or 0 if parsing fails
     */
    fun parseIsoDate(isoString: String?): Long {
        if (isoString.isNullOrEmpty() || isoString.startsWith("0001")) return 0
        return try {
            // Handle +00:00 or other offsets by normalizing to Z (UTC)
            var cleaned = isoString.replace(Regex("[+-][0-9]{2}:?[0-9]{2}$"), "Z")
            
            // Handle microseconds/milliseconds if present
            if (cleaned.contains(".")) {
                val base = cleaned.substringBefore(".")
                cleaned = base + "Z"
            } else if (!cleaned.endsWith("Z")) {
                cleaned += "Z"
            }

            val sdf = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US)
            sdf.timeZone = TimeZone.getTimeZone("UTC")
            sdf.parse(cleaned)?.time ?: 0
        } catch (e: Exception) {
            Log.e(TAG, "Failed to parse date: $isoString", e)
            0
        }
    }
    
    /**
     * Parses an ISO date string with milliseconds precision.
     * Useful for more precise time comparisons.
     * 
     * @param isoString The ISO date string to parse
     * @return Milliseconds since epoch, or 0 if parsing fails
     */
    fun parseIsoDateWithMs(isoString: String?): Long {
        if (isoString.isNullOrEmpty() || isoString.startsWith("0001")) return 0
        return try {
            var cleaned = isoString.replace(Regex("[+-][0-9]{2}:?[0-9]{2}$"), "Z")
            
            val format = if (cleaned.contains(".")) {
                // Extract milliseconds part
                val msPart = cleaned.substringAfter(".").substringBefore("Z")
                val msValue = msPart.take(3).padEnd(3, '0') // Take first 3 digits, pad if needed
                val base = cleaned.substringBefore(".")
                cleaned = "$base.${msValue}Z"
                SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US)
            } else {
                if (!cleaned.endsWith("Z")) cleaned += "Z"
                SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US)
            }
            
            format.timeZone = TimeZone.getTimeZone("UTC")
            format.parse(cleaned)?.time ?: 0
        } catch (e: Exception) {
            Log.e(TAG, "Failed to parse date with ms: $isoString", e)
            0
        }
    }
    
    /**
     * Converts a date string to ISO format for API requests.
     * 
     * @param dateMillis Milliseconds since epoch
     * @return ISO 8601 formatted string
     */
    fun toIsoString(dateMillis: Long): String {
        val sdf = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US)
        sdf.timeZone = TimeZone.getTimeZone("UTC")
        return sdf.format(dateMillis)
    }
    
    /**
     * Checks if the given date string is within the last N seconds.
     * 
     * @param isoString The ISO date string to check
     * @param seconds Number of seconds to check within
     * @return true if the date is within the specified duration
     */
    fun isWithinSeconds(isoString: String?, seconds: Int): Boolean {
        val dateMillis = parseIsoDate(isoString)
        if (dateMillis <= 0) return false
        
        val diff = System.currentTimeMillis() - dateMillis
        return diff <= seconds * 1000L
    }
}
