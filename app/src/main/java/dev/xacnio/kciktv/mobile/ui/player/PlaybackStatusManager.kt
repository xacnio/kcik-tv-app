/**
 * File: PlaybackStatusManager.kt
 *
 * Description: Manages the display of stream uptime and duration.
 * It calculates the stream's duration for Live and DVR modes, handles "Live Edge" logic,
 * and updates the stream time badge independently of the main UI loop.
 *
 * Author: Xacnio
 *
 */
package dev.xacnio.kciktv.mobile.ui.player

import android.os.Handler
import android.os.Looper
import android.view.View
import dev.xacnio.kciktv.mobile.MobilePlayerActivity

class PlaybackStatusManager(private val activity: MobilePlayerActivity) {

    private val uptimeHandler = Handler(Looper.getMainLooper())
    var streamCreatedAtMillis: Long? = null
    
    // Track if we have stopped the handler explicitly due to metadata providing time
    private var isUsingMetadataTime = false

    private val uptimeRunnable = object : Runnable {
        override fun run() {
            updateUptimeDisplay()
            uptimeHandler.postDelayed(this, 1000L)
        }
    }

    fun startUptimeUpdater() {
        if (!isUsingMetadataTime) {
            uptimeHandler.removeCallbacks(uptimeRunnable)
            uptimeHandler.postDelayed(uptimeRunnable, 1000L)
        }
    }

    fun stopUptimeUpdater() {
        uptimeHandler.removeCallbacks(uptimeRunnable)
    }
    
    fun onMetadataTimeReceived(streamTime: Double) {
        // Update UI Badge directly, overriding internal timer
        activity.binding.streamTimeBadge.text = activity.formatStreamTime(streamTime.toLong())
        activity.binding.streamTimeBadge.visibility = View.VISIBLE
        activity.binding.uptimeDivider.visibility = View.VISIBLE
        
        // Stop the internal uptime handler since metadata provides accurate time
        isUsingMetadataTime = true
        uptimeHandler.removeCallbacks(uptimeRunnable)
    }

    fun updateUptimeDisplay() {
        if (activity.isReturningToLive || activity.isVodPlaying) return
        // Skip UI updates if in mini player mode (UI is not visible anyway)
        if (activity.miniPlayerManager.isMiniPlayerMode) return

        // DVR / VOD Mode Logic
        val player = activity.ivsPlayer
        val duration = player?.duration ?: 0L
        val position = player?.position ?: 0L
        
        // Check if we are actually using DVR URL
        val isDvrActive = activity.dvrPlaybackUrl != null && activity.currentStreamUrl == activity.dvrPlaybackUrl
        
        // Calculate Latency based on Real Time to detect lag correctly even if player manifest is stale
        val start = streamCreatedAtMillis
        val latencyMs = if (isDvrActive && start != null) {
            val now = System.currentTimeMillis()
            val totalDur = now - start
            // Latency = Real Total Duration - Player Position
            (totalDur - position)
        } else {
            if (duration > 0) duration - position else 0
        }

        // Define "Live Edge" tolerance (10 seconds)
        val isBehindLive = isDvrActive && latencyMs > 10000
        
        if (isBehindLive) {
            // DVR Mode
            val streamStart = streamCreatedAtMillis
            if (streamStart != null) {
                // Calculate based on stream start time for accurate "Total"
                val now = System.currentTimeMillis()
                val totalSeconds = (now - streamStart) / 1000
                
                // Use direct player position for Current time to avoid jitter from duration updates
                val currentSeconds = position / 1000
                
                val currentStr = activity.formatStreamTime(currentSeconds)
                val totalStr = activity.formatStreamTime(totalSeconds)
                activity.binding.streamTimeBadge.text = "$currentStr / $totalStr"
            } else {
                // Fallback to player duration
                val currentPosSeconds = position / 1000
                val totalDurationSeconds = duration / 1000
                val currentStr = activity.formatStreamTime(currentPosSeconds)
                val totalStr = activity.formatStreamTime(totalDurationSeconds)
                activity.binding.streamTimeBadge.text = "$currentStr / $totalStr"
            }
            
            activity.binding.streamTimeBadge.visibility = View.VISIBLE
            // Hide divider in DVR mode
            activity.binding.uptimeDivider.visibility = View.GONE
            
            // Show dedicated Return to Live Button
            activity.binding.returnToLiveButton.visibility = View.VISIBLE
            activity.binding.returnToLiveButton.setOnClickListener {
                activity.liveDvrManager.returnToLive()
            }
            
            // Hide Viewer Count layout completely in DVR mode
            activity.binding.viewerLayout.visibility = View.GONE
            activity.binding.liveDot.visibility = View.GONE 
            
        } else {
            // LIVE Mode
            
            // If caught up to live edge while in DVR mode -> Switch to Real Live
            // Check if player buffer is running low (<5s) to proactively switch source
            val bufferLeft = if (duration > 0) duration - position else 0
            
            if (isDvrActive && !activity.isReturningToLive && bufferLeft < 5000 && duration > 30000) {
                 activity.liveDvrManager.returnToLive()
                 return
            }

            // Hide Return to Live Button
            activity.binding.returnToLiveButton.visibility = View.GONE
            activity.binding.returnToLiveButton.setOnClickListener(null)

            val streamStart = streamCreatedAtMillis
            if (streamStart != null) {
                val now = System.currentTimeMillis()
                val diffSeconds = (now - streamStart) / 1000
                if (diffSeconds >= -60) {
                     val displaySeconds = if (diffSeconds < 0) 0L else diffSeconds
                     activity.binding.streamTimeBadge.text = activity.formatStreamTime(displaySeconds)
                     activity.binding.streamTimeBadge.visibility = View.VISIBLE
                     activity.binding.uptimeDivider.visibility = View.VISIBLE
                } else {
                     activity.binding.streamTimeBadge.visibility = View.GONE
                     activity.binding.uptimeDivider.visibility = View.GONE
                }
            } else {
                 activity.binding.streamTimeBadge.visibility = View.GONE
                 activity.binding.uptimeDivider.visibility = View.GONE
            }
            
            // Restore UI
            activity.binding.liveDot.visibility = View.VISIBLE
            activity.binding.viewerLayout.visibility = View.VISIBLE
            // Viewer count is handled by its own 60s timer in MobilePlayerActivity
            // No need to force update here            
            // Note: Original code had "Dot animation restoration if needed" handling logic around here probably
        }
    }
}
