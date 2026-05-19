/**
 * File: ThermalMonitor.kt
 *
 * Description: Process-wide thermal pressure level. Hosts UI components read
 * `ThermalMonitor.level` to decide whether to skip optional animations (floating
 * emotes, combo bump effects) and to lengthen the chat flush interval. This
 * never touches stream quality — only auxiliary work that contributes to heat.
 *
 * Mapping (Android Q+ `PowerManager.OnThermalStatusChangedListener`):
 *   NONE, LIGHT       -> 0  (normal: no throttling)
 *   MODERATE          -> 1  (suspend floating emotes & combo bumps)
 *   SEVERE and above  -> 2  (also lengthen chat flush)
 *
 * Pre-Q devices stay at level 0 forever (no API).
 *
 * Author: Xacnio
 */
package dev.xacnio.kciktv.shared.util

import android.content.Context
import android.os.Build
import android.os.PowerManager
import android.util.Log

object ThermalMonitor {
    private const val TAG = "ThermalMonitor"

    @Volatile
    var level: Int = 0
        private set

    private var listener: PowerManager.OnThermalStatusChangedListener? = null

    /**
     * Idempotent: subsequent calls with the same Context replace the listener
     * (avoids leaking multiple registrations if the activity is recreated).
     */
    fun start(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return
        val pm = context.applicationContext.getSystemService(Context.POWER_SERVICE)
            as? PowerManager ?: return

        // Tear down any previous registration first.
        stop(context)

        val l = PowerManager.OnThermalStatusChangedListener { status ->
            val newLevel = when (status) {
                PowerManager.THERMAL_STATUS_MODERATE -> 1
                PowerManager.THERMAL_STATUS_SEVERE,
                PowerManager.THERMAL_STATUS_CRITICAL,
                PowerManager.THERMAL_STATUS_EMERGENCY,
                PowerManager.THERMAL_STATUS_SHUTDOWN -> 2
                else -> 0 // NONE or LIGHT — leave optional animations on
            }
            if (newLevel != level) {
                Log.d(TAG, "thermal status=$status level=$level -> $newLevel")
                level = newLevel
            }
        }
        try {
            pm.addThermalStatusListener(context.applicationContext.mainExecutor, l)
            listener = l
            // Seed the current state — listener only fires on change.
            level = when (pm.currentThermalStatus) {
                PowerManager.THERMAL_STATUS_MODERATE -> 1
                PowerManager.THERMAL_STATUS_SEVERE,
                PowerManager.THERMAL_STATUS_CRITICAL,
                PowerManager.THERMAL_STATUS_EMERGENCY,
                PowerManager.THERMAL_STATUS_SHUTDOWN -> 2
                else -> 0
            }
        } catch (e: Exception) {
            Log.w(TAG, "addThermalStatusListener failed: ${e.message}")
        }
    }

    fun stop(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return
        val pm = context.applicationContext.getSystemService(Context.POWER_SERVICE)
            as? PowerManager ?: return
        listener?.let {
            try {
                pm.removeThermalStatusListener(it)
            } catch (_: Exception) { }
        }
        listener = null
        level = 0
    }
}
