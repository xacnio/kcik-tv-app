/**
 * File: LiveDvrManager.kt
 *
 * Description: Manages Live DVR (Digital Video Recorder) functionality for live streams.
 * It enables pausing, rewinding, and seeking within a live broadcast by switching between
 * real-time and DVR playback URLs, and handles the "Return to Live" logic.
 *
 * Author: Xacnio
 *
 */
package dev.xacnio.kciktv.mobile.ui.player

import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.View
import android.widget.Toast
import androidx.lifecycle.lifecycleScope
import dev.xacnio.kciktv.mobile.MobilePlayerActivity
import dev.xacnio.kciktv.R
import dev.xacnio.kciktv.shared.data.repository.ChannelRepository
import kotlinx.coroutines.launch
import dev.xacnio.kciktv.mobile.ui.player.VodManager

class LiveDvrManager(
    private val activity: MobilePlayerActivity,
    private val repository: ChannelRepository
) {
    private val mainHandler = Handler(Looper.getMainLooper())
    private var rewindAccumulator = 0
    
    // Internal runnable for rewind processing
    private val rewindRunnable = Runnable {
        if (activity.ivsPlayer != null) {
            val seekAmount = rewindAccumulator * 1000L
            val currentPos = activity.ivsPlayer?.position ?: 0L
            val targetPos = (currentPos - seekAmount).coerceAtLeast(0)
            
            activity.ivsPlayer?.seekTo(targetPos)
        }
        rewindAccumulator = 0
    }

    private var pendingSeekPosition: Long? = null
    private var pendingRewindSeconds: Int = 0

    fun handleRewindRequest(seekPercentage: Int? = null, seekPositionMs: Long? = null) {
        val vodManager = activity.vodManager
        val dvrPlaybackUrl = activity.dvrPlaybackUrl
        val currentStreamUrl = activity.currentStreamUrl
        
        // Only trigger DVR switch logic for LIVE mode
        val currentMode = vodManager.currentPlaybackMode
        val isLiveMode = currentMode == VodManager.PlaybackMode.LIVE
        
        if (seekPositionMs != null) {
             pendingSeekPosition = seekPositionMs
        } else if (seekPercentage != null) {
             // Backward compatibility or percentage based fallback
             val start = activity.playbackStatusManager.streamCreatedAtMillis
             if (start != null) {
                 val now = System.currentTimeMillis()
                 val total = now - start
                 pendingSeekPosition = (total * seekPercentage) / 100
             }
        }
        
        Log.d("LiveDvrManager", "Rewind Request: isLiveMode=$isLiveMode, dvrPlaybackUrl=${dvrPlaybackUrl != null}, currentStreamUrl=$currentStreamUrl")

        // If we have a DVR URL but are not using it yet, switch first.
        if (isLiveMode && dvrPlaybackUrl != null && currentStreamUrl != dvrPlaybackUrl) {
            // If triggered by button (null percentage and null pos), queue a 20s rewind (safer buffer)
            if (seekPercentage == null && seekPositionMs == null) {
                pendingRewindSeconds = 20
            }
            // ... (switch logic same as before)
            Toast.makeText(activity, activity.getString(R.string.dvr_mode_activating), Toast.LENGTH_SHORT).show()
            
            // Switch to DVR URL
            activity.currentStreamUrl = dvrPlaybackUrl
            
            // Update SeekToLiveEdge flag safely via activity property (handles null check internally)
            activity.shouldSeekToLiveEdge = true
            activity.currentDvrTitle = activity.currentChannel?.title ?: activity.binding.infoStreamTitle.text.toString()

            try {
                // Disable Low Latency for DVR to allow larger buffer
                activity.ivsPlayer?.setRebufferToLive(false)
                activity.ivsPlayer?.load(android.net.Uri.parse(dvrPlaybackUrl))
                activity.ivsPlayer?.play()

                // Show controls (but not seekbar in theatre mode)
                if (!activity.fullscreenToggleManager.isTheatreMode) {
                    activity.binding.playerSeekBar.visibility = View.VISIBLE
                }
                activity.binding.playerSeekBar.isEnabled = true
                activity.binding.rewindButton.visibility = View.VISIBLE
                activity.binding.rewindButton.isEnabled = true
                activity.binding.forwardButton.visibility = View.VISIBLE
                activity.binding.forwardButton.isEnabled = true
                
                // activity.startProgressUpdater() // Moved to Listener.onStatePlaying to avoid 0:00 glitch
                activity.revealOverlay()
                
            } catch (e: Exception) {
                Log.e("LiveDvrManager", "Failed to load DVR url", e)
                Toast.makeText(activity, activity.getString(R.string.error_occurred), Toast.LENGTH_SHORT).show()
                activity.shouldSeekToLiveEdge = false
            }
        } else if (isLiveMode && dvrPlaybackUrl == null) {
            // DVR not available for this stream
            Toast.makeText(activity, activity.getString(R.string.dvr_not_available), Toast.LENGTH_SHORT).show()
        } else {
            // Already in DVR mode OR normal live mode (try seeking anyway)
            // Accumulate seek time
            rewindAccumulator += 10
            
            // Cancel pending seek
            mainHandler.removeCallbacks(rewindRunnable)
            
            // Show feedback
            activity.showSeekAnimation(-10)
            
            // Reset overlay timer
            activity.revealOverlay()
            
            // Schedule actual seek
            mainHandler.postDelayed(rewindRunnable, 600)
        }
    }

    fun returnToLive() {
        val channel = activity.currentChannel ?: return
        
        Toast.makeText(activity, activity.getString(R.string.returning_to_live), Toast.LENGTH_SHORT).show()
        
        // Set flag to bypass VOD checks during transition
        activity.isReturningToLive = true
        activity.shouldSeekToLiveEdge = true
        
        // activity.binding.playerSeekBar.visibility = View.GONE (Don't hide if we want it visible)
        // Adjust button visibility if needed, but play/pause overlay handles most.
        // We keep buttons visible if they should apply to live (like rewind)
        activity.binding.rewindButton.visibility = View.VISIBLE
        
        // Fetch fresh channel data and reload stream
        activity.lifecycleScope.launch {
            try {
                // Get fresh channel data with updated playback URL
                val result = repository.getChannelDetails(channel.slug)
                
                result.onSuccess { freshChannelDetail ->
                    activity.runOnUiThread {
                        val freshPlaybackUrl = freshChannelDetail.playbackUrl
                        
                        if (!freshPlaybackUrl.isNullOrEmpty()) {
                            // Update current stream URL FIRST (before loading)
                            activity.currentStreamUrl = freshPlaybackUrl
                            
                            // Also update the channel's playback URL
                            activity.currentChannel = channel.copy(playbackUrl = freshPlaybackUrl)
                            
                            // Load the fresh live stream URL
                            try {
                                // Re-enable Low Latency for Live
                                activity.ivsPlayer?.setRebufferToLive(true)
                                activity.ivsPlayer?.load(android.net.Uri.parse(freshPlaybackUrl))
                                activity.ivsPlayer?.play()
                                
                                // Reset flag after successful load
                                mainHandler.postDelayed({
                                    activity.isReturningToLive = false
                                    // Force update UI to hide VOD controls
                                    activity.updateSeekBarProgress()
                                }, 2000)
                                
                            } catch (e: Exception) {
                                Log.e("LiveDvrManager", "Failed to load fresh URL", e)
                                activity.isReturningToLive = false
                                Toast.makeText(activity, activity.getString(R.string.error_format, e.message), Toast.LENGTH_SHORT).show()
                            }
                        } else {
                            Log.e("LiveDvrManager", "Fresh playback URL is null or empty")
                            activity.isReturningToLive = false
                            Toast.makeText(activity, activity.getString(R.string.live_url_not_found), Toast.LENGTH_SHORT).show()
                        }
                    }
                }.onFailure { error ->
                    Log.e("LiveDvrManager", "Failed to fetch fresh channel data", error)
                    activity.runOnUiThread {
                        activity.isReturningToLive = false
                        // Fallback: try with existing URL
                        if (!channel.playbackUrl.isNullOrEmpty()) {
                            activity.currentStreamUrl = channel.playbackUrl
                            try {
                                activity.ivsPlayer?.load(android.net.Uri.parse(channel.playbackUrl))
                                activity.ivsPlayer?.play()
                            } catch (e: Exception) {
                                Log.e("LiveDvrManager", "Failed to load fallback URL", e)
                                Toast.makeText(activity, activity.getString(R.string.error_format, e.message), Toast.LENGTH_SHORT).show()
                            }
                        } else {
                            Toast.makeText(activity, activity.getString(R.string.live_url_not_found), Toast.LENGTH_SHORT).show()
                        }
                }
            }
        } catch (e: Exception) {
                Log.e("LiveDvrManager", "Exception while returning to live", e)
                activity.runOnUiThread {
                    Toast.makeText(activity, activity.getString(R.string.error_format, e.message), Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    fun onPlayerPlaying() {
        // Handle Rewind Button Logic (DVR Switch)
        if (pendingRewindSeconds > 0) {
            activity.shouldSeekToLiveEdge = false
            val duration = activity.ivsPlayer?.duration ?: 0
            if (duration > 0) {
                 val seekPos = (duration - (pendingRewindSeconds * 1000)).coerceAtLeast(0)
                 activity.ivsPlayer?.seekTo(seekPos)
                 pendingRewindSeconds = 0
            }
            pendingSeekPosition = null
            return
        }

        pendingSeekPosition?.let { pos ->
            // Prevent conflict with "seek to live edge" logic
            activity.shouldSeekToLiveEdge = false 
            
            val duration = activity.ivsPlayer?.duration ?: 0
            if (duration > 0) {
                // Should clamp to duration
                activity.ivsPlayer?.seekTo(pos.coerceAtMost(duration))
            }
            pendingSeekPosition = null
        }
    }
}
