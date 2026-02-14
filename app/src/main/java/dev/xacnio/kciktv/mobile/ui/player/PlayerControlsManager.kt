/**
 * File: PlayerControlsManager.kt
 *
 * Description: Manages the player's primary control actions.
 * It handles toggling Play/Pause and Mute states, and performs the comprehensive "Stop" logic
 * which resets the UI, cancels background services, and cleans up resources when playback ends.
 *
 * Author: Xacnio
 *
 */
package dev.xacnio.kciktv.mobile.ui.player

import android.util.Log
import android.view.View
import androidx.core.app.NotificationManagerCompat
import androidx.core.view.isVisible
import com.amazonaws.ivs.player.Player
import dev.xacnio.kciktv.mobile.MobilePlayerActivity
import dev.xacnio.kciktv.R

/**
 * Manages player control actions (play/pause, mute, stop).
 */
class PlayerControlsManager(private val activity: MobilePlayerActivity) {

    private val binding get() = activity.binding
    private val ivsPlayer get() = activity.ivsPlayer
    private val mainHandler get() = activity.mainHandler
    private val hideOverlayRunnable get() = activity.getHideOverlayRunnable()

    companion object {
        private const val TAG = "PlayerControlsManager"
    }

    /**
     * Toggles between play and pause states.
     */
    fun togglePlayPause() {
        // Prevent playing if we are in error/offline state
        if (binding.errorOverlay.isVisible) return

        ivsPlayer?.let { player ->
            if (player.state == Player.State.PLAYING) {
                player.pause()
                binding.playPauseButton.setImageResource(R.drawable.ic_play_arrow)
            } else {
                player.play()
                binding.playPauseButton.setImageResource(R.drawable.ic_pause)
            }
            // Reset auto-hide timer when interacting
            mainHandler.removeCallbacks(hideOverlayRunnable)
            mainHandler.postDelayed(hideOverlayRunnable, 3000)
        }
    }

    /**
     * Toggles between muted and unmuted states.
     */
    fun toggleMute() {
        ivsPlayer?.let { player ->
            activity.isMuted = !activity.isMuted
            player.volume = if (activity.isMuted) 0f else 1f
            binding.muteButton.setImageResource(
                if (activity.isMuted) R.drawable.ic_volume_off else R.drawable.ic_volume
            )
        }
    }

    /**
     * Stops the player and resets UI.
     */
    fun stopPlayer() {
        try {
            // Cancel any ongoing stream loading operations first
            activity.playerManager.cancelLoadingOperations()
            
            // Fully stop (not just pause) the IVS player
            ivsPlayer?.pause()
            // Clear the source so it cannot auto-resume
            try {
                ivsPlayer?.load(android.net.Uri.parse(""))
            } catch (ignore: Exception) {}
            
            activity.currentStreamUrl = null

            // Stop viewer count polling
            activity.viewerCountHandler.removeCallbacks(activity.viewerCountRunnable)
            activity.currentLivestreamId = null
            
            // Stop all UI updaters
            activity.playbackStatusManager.stopUptimeUpdater()
            activity.playbackControlManager.stopProgressUpdater()
            
            // Stop chat flushing
            activity.chatUiManager.stopFlushing()

            // Reset UI
            binding.playerView.visibility = View.GONE
            binding.actionBar.visibility = View.GONE
            binding.chatContainer.visibility = View.GONE
            binding.offlineBanner.visibility = View.VISIBLE
            binding.playPauseButton.setImageResource(R.drawable.ic_play_arrow)
            binding.loadingIndicator.visibility = View.GONE
            binding.errorOverlay.visibility = View.GONE

            // Stop background audio service/notification
            activity.isBackgroundAudioEnabled = false
            NotificationManagerCompat.from(activity).cancel(activity.NOTIFICATION_ID)

            // Close socket
            activity.stopChatWebSocket()

            // Stop VOD chat replay if running
            activity.vodManager.stopVodChatReplay()
            
            // Reset DVR state
            activity.dvrPlaybackUrl = null
            activity.dvrVideoUuid = null
        } catch (e: Exception) {
            Log.e(TAG, "Error stopping player", e)
        }
    }
}
