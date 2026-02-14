/**
 * File: OverlayControlManager.kt
 *
 * Description: Manages the visibility and behavior of the main Player Overlay.
 * It controls the showing and hiding of UI panels (Info, Actions, Top Bar), handles screen taps
 * for toggling controls, and coordinates with Theatre Mode and Tablet layouts.
 *
 * Author: Xacnio
 *
 */
package dev.xacnio.kciktv.mobile.ui.player

import android.view.View
import com.amazonaws.ivs.player.Player
import dev.xacnio.kciktv.mobile.MobilePlayerActivity
import dev.xacnio.kciktv.R

/**
 * Manages player overlay visibility and interactions.
 */
class OverlayControlManager(private val activity: MobilePlayerActivity) {

    private val binding get() = activity.binding
    private val miniPlayerManager get() = activity.miniPlayerManager
    private val vodManager get() = activity.vodManager
    private val overlayManager get() = activity.overlayManager
    private val ivsPlayer get() = activity.ivsPlayer
    private val mainHandler get() = activity.mainHandler
    private val hideOverlayRunnable get() = activity.getHideOverlayRunnable()
    private val startMarqueeRunnable get() = activity.startMarqueeRunnable

    /**
     * Toggles overlay visibility.
     */
    fun toggleOverlay() {
        val isVisible = binding.videoOverlay.visibility == View.VISIBLE
        if (isVisible) {
            hideOverlay()
        } else {
            showOverlay()
        }
    }

    /**
     * Shows the player overlay with all controls.
     */
    fun showOverlay() {
        // Don't show overlay in mini player or PiP mode
        if (miniPlayerManager.isMiniPlayerMode || activity.isInPictureInPictureMode) return

        binding.videoOverlay.visibility = View.VISIBLE
        binding.videoTopBar.visibility = View.VISIBLE

        // Handle error/offline state visibility
        if (activity.isErrorStateActive) {
            binding.errorOverlay.visibility = View.VISIBLE
            binding.retryButton.visibility = View.VISIBLE
            binding.playPauseOverlay.visibility = View.GONE
        } else {
            binding.errorOverlay.visibility = View.GONE
            binding.retryButton.visibility = View.GONE
            binding.playPauseOverlay.visibility = View.VISIBLE
        }

        val isTheatreMode = try { activity.fullscreenToggleManager.isTheatreMode } catch (e: Exception) { false }
        val isTablet = activity.resources.getBoolean(R.bool.is_tablet)
        val isSideChatVisible = try { activity.fullscreenToggleManager.isSideChatVisible } catch (e: Exception) { false }

        val isPortrait = activity.resources.configuration.orientation == android.content.res.Configuration.ORIENTATION_PORTRAIT

        // Determine which panels to show
        val showInfoPanel = !isTheatreMode
        val shouldShowActionBar = if (isTheatreMode || (isTablet && isSideChatVisible)) {
             true 
        } else if (activity.isFullscreen) {
             false
        } else {
             vodManager.currentPlaybackMode == VodManager.PlaybackMode.LIVE
        }

        // Animate both panels together as one smooth surface
        activity.channelUiManager.animatePanelsIn(showInfoPanel, shouldShowActionBar)
        
        if (isTheatreMode && shouldShowActionBar) {
            binding.actionBar.bringToFront()
        }
        
        if (!activity.isFullscreen || isTheatreMode) overlayManager.updatePinnedGiftsUI()
        activity.updateChatOverlayMargin()

        // Update play/pause icon
        ivsPlayer?.let { player ->
            if (player.state == Player.State.PLAYING) {
                binding.playPauseButton.setImageResource(R.drawable.ic_pause)
            } else {
                binding.playPauseButton.setImageResource(R.drawable.ic_play_arrow)
            }
        }

        // Delay marquee start to allow reading the beginning
        binding.infoStreamTitle.isSelected = false
        binding.infoStreamTitle.removeCallbacks(startMarqueeRunnable)
        binding.infoStreamTitle.postDelayed(startMarqueeRunnable, 1500)

        mainHandler.removeCallbacks(hideOverlayRunnable)
        mainHandler.postDelayed(hideOverlayRunnable, 3000)
        
        // Notify side chat manager
        try {
            activity.fullscreenToggleManager.updateSideChatOverlayState(true)
        } catch(e: Exception) {}
    }

    /**
     * Hides the player overlay.
     */
    fun hideOverlay() {
        binding.videoOverlay.visibility = View.GONE
        binding.videoTopBar.visibility = View.GONE
        binding.playPauseOverlay.visibility = View.GONE
        binding.errorOverlay.visibility = View.GONE
        binding.retryButton.visibility = View.GONE
        val isTablet = activity.resources.getBoolean(R.bool.is_tablet)
        val isPortrait = activity.resources.configuration.orientation == android.content.res.Configuration.ORIENTATION_PORTRAIT
        val isTheatreMode = try { activity.fullscreenToggleManager.isTheatreMode } catch (e: Exception) { false }

        val isSideChatVisible = try { activity.fullscreenToggleManager.isSideChatVisible } catch (e: Exception) { false }

        // Info panel should be visible on tablets in portrait OR landscape split-view
        val shouldKeepInfoVisible = isTablet && (isPortrait || isSideChatVisible) && !isTheatreMode
        val shouldKeepActionVisible = isTablet && isSideChatVisible

        // Stop marquee
        binding.infoStreamTitle.isSelected = false
        binding.infoStreamTitle.removeCallbacks(startMarqueeRunnable)

        // Animate both panels out together as one smooth retraction
        activity.channelUiManager.animatePanelsOut(
            hideInfoPanel = !shouldKeepInfoVisible,
            hideActionBar = !shouldKeepActionVisible
        )

        activity.updateChatOverlayMargin()
        mainHandler.removeCallbacks(hideOverlayRunnable)
        
        // Notify side chat manager
        try {
            activity.fullscreenToggleManager.updateSideChatOverlayState(false)
        } catch(e: Exception) {}
    }

    /**
     * Handles screen tap to toggle overlay visibility.
     */
    fun handleScreenTap() {
        if (binding.playPauseOverlay.visibility == View.VISIBLE || binding.videoOverlay.visibility == View.VISIBLE) {
            hideOverlay()
            activity.stopProgressUpdater()
        } else {
            showOverlay()
            activity.startProgressUpdater()
        }
    }
}
