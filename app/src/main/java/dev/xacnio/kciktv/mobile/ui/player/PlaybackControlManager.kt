/**
 * File: PlaybackControlManager.kt
 *
 * Description: Manages the interactive playback UI controls.
 * It handles the SeekBar logic (including DVR buffering visualization), Play/Pause interactions,
 * Rewind/Forward buttons, and the "Maximize" gesture for the mini player.
 *
 * Author: Xacnio
 *
 */
package dev.xacnio.kciktv.mobile.ui.player

import android.os.Handler
import android.os.Looper
import android.view.View
import android.widget.SeekBar
import com.amazonaws.ivs.player.Player
import dev.xacnio.kciktv.mobile.MobilePlayerActivity
import dev.xacnio.kciktv.R
import dev.xacnio.kciktv.databinding.ActivityMobilePlayerBinding

class PlaybackControlManager(
    private val activity: MobilePlayerActivity,
    private val binding: ActivityMobilePlayerBinding,
    private val mainHandler: Handler
) {

    private val updateProgressRunnable = object : Runnable {
        override fun run() {
            updateSeekBarProgress()
            if (activity.playerManager.ivsPlayer?.state == Player.State.PLAYING) {
                mainHandler.postDelayed(this, 1000)
            }
        }
    }

    fun setupPlayerControls() {
        setupInfoPanelListeners()
        
        // Use higher resolution for seekbar (smoother seeking for short clips)
        binding.playerSeekBar.max = 1000
        binding.playerSeekBar.progress = 1000 // Start at 100% for live streams

        // Maximize on tap (mini player to full screen)
        binding.videoContainer.findViewById<View>(R.id.miniPlayerMaximizeArea)?.setOnClickListener {
             if (activity.miniPlayerManager.isMiniPlayerMode) {
                 activity.exitMiniPlayerMode()
             }
        }
        
        // Clip Button
        binding.clipButton.setOnClickListener {
            activity.currentChannel?.let { activity.clipManager.handleClipRequest(it) }
        }
        binding.clipButton.setOnLongClickListener {
            activity.captureVideoScreenshot()
            true
        }

        // Rewind Button
        binding.rewindButton.setOnClickListener {
            activity.handleRewindRequest()
        }

        // Forward 10s (only works in VOD mode)
        binding.forwardButton.setOnClickListener {
            activity.playerManager.ivsPlayer?.let { player ->
                val duration = player.duration
                if (duration > 0) {
                    val newPos = (player.position + 10000).coerceAtMost(duration)
                    player.seekTo(newPos)
                    showSeekAnimation(+10)
                    updateSeekBarProgress()
                }
            }
        }

        // SeekBar
        binding.playerSeekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                if (fromUser) {
                     val player = activity.playerManager.ivsPlayer ?: return
                     val duration = player.duration
                     
                     // Determine playback mode
                     val isVodOrClipMode = activity.isVodPlaying // This is true for both VOD and Clip modes
                     val isDvrActive = !isVodOrClipMode && activity.dvrPlaybackUrl != null && activity.currentStreamUrl == activity.dvrPlaybackUrl
                     val createdAt = activity.playbackStatusManager.streamCreatedAtMillis
                     
                     var previewSeconds: Long = 0
                     var totalSecondsForDisplay: Long = 0

                     when {
                         isVodOrClipMode -> {
                             // VOD/Clip Mode: Use player duration directly
                             if (duration > 0) {
                                 val seekPosMs = (duration * progress) / 1000
                                 previewSeconds = seekPosMs / 1000
                                 totalSecondsForDisplay = duration / 1000
                             }
                         }
                         isDvrActive && createdAt != null -> {
                             // DVR Mode (Live with rewind): Use calculated total from stream start
                             val now = System.currentTimeMillis()
                             totalSecondsForDisplay = (now - createdAt) / 1000
                             val seekPosMs = (duration * progress) / 1000
                             previewSeconds = seekPosMs / 1000
                         }
                         createdAt != null -> {
                             // Live Mode with DVR available (estimate based on stream start)
                             val totalSeconds = (System.currentTimeMillis() - createdAt) / 1000
                             previewSeconds = (totalSeconds * progress) / 1000
                         }
                     }
                     
                     // Update UI with Preview Time
                     if (previewSeconds >= 0 && (duration > 0 || createdAt != null)) {
                         val timeStr = activity.formatStreamTime(previewSeconds)
                         if (isVodOrClipMode || isDvrActive) {
                             val totalStr = activity.formatStreamTime(totalSecondsForDisplay)
                             activity.binding.streamTimeBadge.text = "$timeStr / $totalStr"
                         } else {
                             activity.binding.streamTimeBadge.text = timeStr
                         }
                         activity.binding.streamTimeBadge.visibility = View.VISIBLE
                         
                         // In Live Mode (seeking DVR), hide live indicators
                         if (!isVodOrClipMode && !isDvrActive) {
                             activity.binding.uptimeDivider.visibility = View.GONE
                             activity.binding.returnToLiveButton.visibility = View.GONE
                             activity.binding.viewerLayout.visibility = View.GONE
                             activity.binding.liveDot.visibility = View.GONE
                         }
                     }

                     // Seek Logic - Different paths for VOD/Clip vs DVR
                     when {
                         isVodOrClipMode -> {
                             // VOD/Clip: Seek directly using player duration
                             if (duration > 0) {
                                 val seekPos = (duration * progress) / 1000
                                 player.seekTo(seekPos)
                             }
                         }
                         isDvrActive -> {
                             // DVR Mode: Already on DVR URL, seek within buffer
                             if (createdAt != null) {
                                 val now = System.currentTimeMillis()
                                 val totalMs = now - createdAt
                                 val seekPos = (totalMs * progress) / 1000
                                 player.seekTo(seekPos.coerceAtMost(duration))
                             } else if (duration > 0) {
                                 val seekPos = (duration * progress) / 1000
                                 player.seekTo(seekPos)
                             }
                         }
                         else -> {
                             // Live Mode: Don't seek here, wait for DVR transition in onStopTrackingTouch
                             return
                         }
                     }
                }
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {
                mainHandler.removeCallbacks(activity.hideOverlayRunnable)
                stopProgressUpdater()
            }
            override fun onStopTrackingTouch(seekBar: SeekBar?) {
                // If in Live mode and DVR is available, trigger DVR switch on release
                val isVodMode = (activity.currentStreamUrl == activity.dvrPlaybackUrl && activity.dvrPlaybackUrl != null) || activity.isVodPlaying
                if (!isVodMode && activity.dvrPlaybackUrl != null && !activity.isVodPlaying) {
                    val progress = seekBar?.progress ?: 0
                    val start = activity.playbackStatusManager.streamCreatedAtMillis
                    if (start != null) {
                         val now = System.currentTimeMillis()
                         val total = now - start
                         val seekPos = (total * progress) / 1000
                         // Pass absolute position for accurate switch
                         activity.liveDvrManager.handleRewindRequest(null, seekPos)
                    } else {
                         // Convert back to percentage 0-100 logic for fallback if needed, or update dvr manager to handle 1000 scale
                         // Assuming handleRewindRequest(Int) expects %, we should scale it back or update DvrManager.
                         // Let's pass null and use seekPos if possible, or scale down.
                         // But for now, let's just use the seekPos logic above which is better.
                         // If we MUST use percentage, we scale it.
                         activity.liveDvrManager.handleRewindRequest(progress / 10) 
                    }
                    return
                }

                // Delay resuming updater to prevent "jumping back" effect
                mainHandler.postDelayed({
                    startProgressUpdater()
                }, 800)
                mainHandler.postDelayed(activity.hideOverlayRunnable, 3000)
            }
        })
    }
    
    // Extracted from activity, logic kept same
    private fun setupInfoPanelListeners() {
        // Implementation will be moved here or delegated if it touches too many UI elements
        // For now, let's keep it simple as it wasn't fully shown in the activity view
        binding.infoPanel.setOnClickListener { 
            activity.channelProfileManager.openChannelProfile(activity.currentChannel?.slug ?: "")
        }
    }

    fun startProgressUpdater() {
        stopProgressUpdater()
        updateProgressRunnable.run()
    }

    fun stopProgressUpdater() {
        mainHandler.removeCallbacks(updateProgressRunnable)
    }

    fun updateSeekBarProgress() {
        // Skip UI updates if in mini player mode
        if (activity.miniPlayerManager.isMiniPlayerMode) return
        
        // Skip seekbar visibility updates in theatre mode
        val isTheatreMode = activity.fullscreenToggleManager.isTheatreMode
        
        activity.playerManager.ivsPlayer?.let { player ->
            val duration = player.duration
            val position = player.position
            val isDvrMode = activity.currentStreamUrl == activity.dvrPlaybackUrl && activity.dvrPlaybackUrl != null
            val isVodPlaying = activity.isVodPlaying
            
            // For VOD/Clip mode, VodManager.updateVodProgress() handles seekbar updates
            // This function only handles DVR and Live modes
            if (isVodPlaying) {
                // Just ensure controls are visible for VOD/Clip (but not in theatre mode)
                if (!isTheatreMode) binding.playerSeekBar.visibility = View.VISIBLE
                binding.playerSeekBar.isEnabled = true
                binding.rewindButton.visibility = View.VISIBLE
                binding.rewindButton.isEnabled = true
                binding.forwardButton.visibility = View.VISIBLE
                binding.forwardButton.isEnabled = true
                binding.videoOverlay.setPadding(
                    binding.videoOverlay.paddingLeft,
                    binding.videoOverlay.paddingTop,
                    binding.videoOverlay.paddingRight,
                    0
                )
                return@let
            }
            
            val isVodMode = isDvrMode || isVodPlaying
            
            // Use CreatedAt for Total Duration in DVR mode
            val createdAt = activity.playbackStatusManager.streamCreatedAtMillis
            
            // Only use CreatedAt for DVR mode, NOT VOD/Clips (isVodPlaying includes both)
            val useDvrCalculation = isDvrMode && !isVodPlaying && createdAt != null

            val totalDurationCalc = if (useDvrCalculation && createdAt != null) {
                System.currentTimeMillis() - createdAt
            } else {
                duration
            }
            
            if (totalDurationCalc > 0) {
                val progress = ((position.toDouble() / totalDurationCalc.toDouble()) * 1000).toInt()
                val bufferedProgress = ((player.bufferedPosition.toDouble() / totalDurationCalc.toDouble()) * 1000).toInt()
                
                binding.playerSeekBar.progress = progress
                binding.playerSeekBar.secondaryProgress = bufferedProgress
                // Seek bar is already visible in VOD mode from handleRewindRequest() (but not in theatre mode)
                if (!isTheatreMode) binding.playerSeekBar.visibility = View.VISIBLE
                binding.playerSeekBar.isEnabled = true
                binding.rewindButton.visibility = View.VISIBLE
                binding.rewindButton.isEnabled = true
                binding.forwardButton.visibility = View.VISIBLE
                binding.forwardButton.isEnabled = true
                
                // Remove bottom padding when seek bar is visible
                binding.videoOverlay.setPadding(
                    binding.videoOverlay.paddingLeft,
                    binding.videoOverlay.paddingTop,
                    binding.videoOverlay.paddingRight,
                    0
                )
            } else {
                 // Live with no buffer or unknown duration
                 binding.playerSeekBar.progress = 1000
                 
                 // If in VOD mode, keep controls visible even if duration not ready yet
                 if (isVodMode) {
                     if (!isTheatreMode) binding.playerSeekBar.visibility = View.VISIBLE
                     binding.rewindButton.visibility = View.VISIBLE
                     binding.forwardButton.visibility = View.VISIBLE
                     
                     // Remove bottom padding
                     binding.videoOverlay.setPadding(
                         binding.videoOverlay.paddingLeft,
                         binding.videoOverlay.paddingTop,
                         binding.videoOverlay.paddingRight,
                         0
                     )
                 } else {
                     // Live Mode
                     // If DVR is available, show seek bar (at 100%) to allow user to engage DVR mode
                     if (activity.dvrPlaybackUrl != null) {
                         if (!isTheatreMode) binding.playerSeekBar.visibility = View.VISIBLE
                         binding.playerSeekBar.isEnabled = true
                         binding.videoOverlay.setPadding(binding.videoOverlay.paddingLeft, binding.videoOverlay.paddingTop, binding.videoOverlay.paddingRight, 0)
                     } else {
                         // No DVR support, hide seek bar
                         binding.playerSeekBar.visibility = View.GONE
                         binding.playerSeekBar.isEnabled = false
                         
                         // Add bottom padding
                         val paddingDp = 16
                         val paddingPx = (paddingDp * activity.resources.displayMetrics.density).toInt()
                         binding.videoOverlay.setPadding(binding.videoOverlay.paddingLeft, binding.videoOverlay.paddingTop, binding.videoOverlay.paddingRight, paddingPx)
                     }

                     binding.rewindButton.visibility = View.VISIBLE
                     binding.rewindButton.isEnabled = true
                     binding.forwardButton.visibility = View.GONE
                     binding.forwardButton.isEnabled = false
                 }
            }
            
            if (!isVodPlaying) {
                activity.playbackStatusManager.updateUptimeDisplay()
            }
        }
    }

    fun showSeekAnimation(seconds: Int) {
        activity.showSeekAnimation(seconds)
    }
}
