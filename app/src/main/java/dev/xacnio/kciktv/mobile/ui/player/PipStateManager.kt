/**
 * File: PipStateManager.kt
 *
 * Description: Manages the robust state transitions for Picture-in-Picture mode.
 * It saves the app's UI state (e.g., active profile, visible bottom sheets) before entering PiP
 * and reliably restores that state when the user expands the player back to full screen.
 *
 * Author: Xacnio
 *
 */
package dev.xacnio.kciktv.mobile.ui.player

import android.os.Build
import android.util.Log
import android.view.View
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.lifecycle.Lifecycle
import com.amazonaws.ivs.player.Player
import dev.xacnio.kciktv.mobile.MobilePlayerActivity

/**
 * Manages Picture-in-Picture mode state and UI transitions.
 */
class PipStateManager(private val activity: MobilePlayerActivity) {

    private companion object {
        const val TAG = "PipStateManager"
    }

    // State preservation flags
    private var wasProfileVisibleBeforePip = false
    private var wasMiniPlayerModeBeforePip = false
    private var wasSettingsVisibleBeforePip = false
    private var wasPredictionVisibleBeforePip = false
    private var wasFullscreenBeforePip = false
    private var wasTheatreModeBeforePip = false

    /**
     * Flag to temporarily suppress auto-fullscreen in onConfigurationChanged.
     * Set to true when exiting PiP to prevent automatic fullscreen entry.
     */
    var suppressAutoFullscreen = false
        private set

    /**
     * Allows external components to set the suppress flag.
     * Called from onPictureInPictureModeChanged before any UI restoration.
     */
    fun setSuppressAutoFullscreen(value: Boolean) {
        suppressAutoFullscreen = value
    }
    private var screenBeforePip = MobilePlayerActivity.AppScreen.HOME

    var exitedPipMode = false

    var isExplicitAudioSwitch = false

    /**
     * Tracks whether we are currently in PiP mode to prevent duplicate calls.
     */
    private var isCurrentlyInPipMode = false

    /**
     * Allows external components (like PipManager) to signal mini player state 
     * before it gets reset by navigation transitions.
     */
    fun setWasMiniPlayerModeBeforePip(wasMini: Boolean) {
        wasMiniPlayerModeBeforePip = wasMini
        Log.d(TAG, "setWasMiniPlayerModeBeforePip: $wasMini")
    }

    fun onPictureInPictureModeChanged(isInPictureInPictureMode: Boolean) {
        if (isInPictureInPictureMode) {
            // Only enter if not already in PiP (prevents duplicate state saving)
            if (!isCurrentlyInPipMode) {
                enterPipMode()
            }
        } else {
            // Only exit if currently in PiP
            if (isCurrentlyInPipMode) {
                exitPipMode()
            }
        }
    }

    private fun enterPipMode() {
        isCurrentlyInPipMode = true
        val binding = activity.binding

        // Save current state before entering PiP
        wasProfileVisibleBeforePip = activity.channelProfileManager.isChannelProfileVisible
        // Only update if not already set by PipManager (manual hint)
        if (!wasMiniPlayerModeBeforePip) {
            wasMiniPlayerModeBeforePip = activity.miniPlayerManager.isMiniPlayerMode
        }
        wasSettingsVisibleBeforePip = activity.isSettingsVisible
        wasPredictionVisibleBeforePip = binding.predictionContainer.visibility == View.VISIBLE
        wasFullscreenBeforePip = activity.isFullscreen
        wasTheatreModeBeforePip = activity.fullscreenToggleManager.isTheatreMode
        screenBeforePip = activity.currentScreen

        Log.d(TAG, "enterPipMode: wasMini=$wasMiniPlayerModeBeforePip, wasFullscreen=$wasFullscreenBeforePip, wasTheatre=$wasTheatreModeBeforePip, screen=$screenBeforePip")

        // Dismiss all open bottom sheets
        activity.dismissAllBottomSheets()

        // Hide settings bottom sheet if visible
        if (activity.isSettingsVisible) {
            activity.settingsPanelManager.hideSettingsPanel()
        }

        // Hide UI elements
        binding.mobileHeader.visibility = View.GONE
        binding.bottomNavContainer.visibility = View.GONE
        binding.bottomNavGradient.visibility = View.GONE
        binding.chatContainer.visibility = View.GONE
        
        // Hide chat rules if visible
        activity.chatRulesManager.dismiss()
        binding.actionBar.visibility = View.GONE
        binding.channelProfileContainer.root.visibility = View.GONE
        binding.homeScreenContainer.root.visibility = View.GONE
        binding.browseScreenContainer.root.visibility = View.GONE
        binding.followingScreenContainer.root.visibility = View.GONE
        binding.searchContainer.visibility = View.GONE
        binding.bottomSheetCoordinator.visibility = View.GONE
        binding.playPauseOverlay.visibility = View.GONE
        binding.videoOverlay.visibility = View.GONE
        binding.videoTopBar.visibility = View.GONE
        binding.infoPanel.visibility = View.GONE
        binding.pinnedGiftsBlur.visibility = View.GONE
        binding.predictionContainer.visibility = View.GONE
        binding.pollContainer.visibility = View.GONE
        binding.errorOverlay.visibility = View.GONE
        binding.loadingIndicator.visibility = View.GONE

        // Hide MiniPlayer controls if they were visible
        binding.miniPlayerControls.visibility = View.GONE

        // Ensure video container fills the PiP window instead of being fixed to 16:9
        val videoParams = binding.videoContainer.layoutParams as? ConstraintLayout.LayoutParams
        videoParams?.let {
            it.height = 0
            it.width = 0
            it.bottomToBottom = ConstraintLayout.LayoutParams.PARENT_ID
            it.topToTop = ConstraintLayout.LayoutParams.PARENT_ID
            it.startToStart = ConstraintLayout.LayoutParams.PARENT_ID
            it.endToEnd = ConstraintLayout.LayoutParams.PARENT_ID
            it.dimensionRatio = null
            // Clear all margins that mini player mode might have set
            it.setMargins(0, 0, 0, 0)
            binding.videoContainer.layoutParams = it
        }

        binding.videoContainer.translationX = 0f
        binding.videoContainer.translationY = 0f

        if (activity.ivsPlayer?.state != Player.State.PLAYING) {
            activity.ivsPlayer?.play()
        }

        // Enforce 360p limit for PiP/Background
        activity.setForcedQualityLimit("360p")

        // Pause UI updates for chat in PiP
        activity.chatUiManager.isChatUiPaused = true
    }

    private fun exitPipMode() {
        isCurrentlyInPipMode = false
        
        // Resume UI updates and flush buffer
        activity.chatUiManager.isChatUiPaused = false
        activity.chatUiManager.flushPendingMessages()

        if (isExplicitAudioSwitch) {
            exitedPipMode = false
            isExplicitAudioSwitch = false
            // Do NOT restore UI here - we remain in "background/audio" mode state
        } else {
            exitedPipMode = true

            // Handle race condition on some devices
            if (activity.lifecycle.currentState == Lifecycle.State.CREATED) {
                Log.d(TAG, "PiP exited in CREATED state - stopping delayed playback")
                activity.ivsPlayer?.pause()
                activity.isBackgroundAudioEnabled = false
                activity.hideNotification()
            }

            // Set flag to suppress auto-fullscreen in onConfigurationChanged
            // This prevents the configuration change from overriding the restored state
            suppressAutoFullscreen = true

            restoreUiFromPip()

            // Restore quality to user preference
            activity.setForcedQualityLimit(null)

            // Clear the suppress flag after a short delay to allow configuration change to complete
            android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                suppressAutoFullscreen = false
            }, 500)
        }
    }

    private fun restoreUiFromPip() {
        val binding = activity.binding
        Log.d(TAG, "restoreUiFromPip: wasMini=$wasMiniPlayerModeBeforePip, screen=$screenBeforePip")

        // Restore bottom navigation visibility based on the screen context
        val navVisibility = when (screenBeforePip) {
            MobilePlayerActivity.AppScreen.HOME,
            MobilePlayerActivity.AppScreen.BROWSE,
            MobilePlayerActivity.AppScreen.FOLLOWING,
            MobilePlayerActivity.AppScreen.SEARCH -> View.VISIBLE
            else -> View.GONE
        }
        binding.bottomNavContainer.visibility = navVisibility
        binding.bottomNavGradient.visibility = navVisibility

        // Restore prediction panel if it was visible
        if (wasPredictionVisibleBeforePip) {
            binding.predictionContainer.visibility = View.VISIBLE
        }

        // 1. First, restore the background screen based on original context
        when (screenBeforePip) {
            MobilePlayerActivity.AppScreen.HOME -> {
                binding.homeScreenContainer.root.visibility = View.VISIBLE
                binding.mobileHeader.visibility = View.VISIBLE
            }
            MobilePlayerActivity.AppScreen.BROWSE -> {
                binding.browseScreenContainer.root.visibility = View.VISIBLE
                binding.mobileHeader.visibility = View.VISIBLE
            }
            MobilePlayerActivity.AppScreen.FOLLOWING -> {
                binding.followingScreenContainer.root.visibility = View.VISIBLE
                binding.mobileHeader.visibility = View.VISIBLE
            }
            MobilePlayerActivity.AppScreen.CHANNEL_PROFILE -> {
                binding.channelProfileContainer.root.visibility = View.VISIBLE
                binding.mobileHeader.visibility = View.VISIBLE
            }
            MobilePlayerActivity.AppScreen.SEARCH -> {
                binding.searchContainer.visibility = View.VISIBLE
                binding.mobileHeader.visibility = View.GONE
            }
            MobilePlayerActivity.AppScreen.CATEGORY_DETAILS -> {
                binding.categoryDetailsContainer.root.visibility = View.VISIBLE
                binding.mobileHeader.visibility = View.GONE
            }
            MobilePlayerActivity.AppScreen.STREAM_FEED -> {
                activity.streamFeedManager.restoreVisibility()
                binding.mobileHeader.visibility = View.GONE
            }
            MobilePlayerActivity.AppScreen.CLIP_FEED -> {
                activity.clipFeedManager.restoreVisibility()
                binding.mobileHeader.visibility = View.GONE
            }

        }

        // 2. Then restore the Player state (Mini or Full)
        if (wasMiniPlayerModeBeforePip) {
            // Reset translation values before entering mini player mode
            // This ensures touch area matches visual position after PiP restoration
            activity.miniPlayerTranslationX = 0f
            activity.miniPlayerTranslationY = 0f
            binding.videoContainer.translationX = 0f
            binding.videoContainer.translationY = 0f
            
            // CRITICAL: Reset isMiniPlayerMode to false so enterMiniPlayerMode() 
            // fully runs and sets up all touch listeners
            activity.miniPlayerManager.isMiniPlayerMode = false
            
            // Restore as Mini Player on top of the restored background screen
            activity.enterMiniPlayerMode()
            
            // Refresh interactions after a delay to ensure layout is settled
            binding.root.postDelayed({
                activity.miniPlayerManager.refreshInteractions()
            }, 100)
        } else if (activity.currentChannel != null) {
            // Restore as Full Player
            binding.mobileHeader.visibility = View.GONE
            binding.playerScreenContainer.visibility = View.VISIBLE
            binding.chatContainer.visibility = View.VISIBLE
            binding.bottomSheetCoordinator.visibility = View.VISIBLE
            
            // Hide bottom navigation when in full player mode
            binding.bottomNavContainer.visibility = View.GONE
            binding.bottomNavGradient.visibility = View.GONE

            // If was Theatre Mode before PiP, restore it
            if (wasTheatreModeBeforePip) {
                val toggleManager = activity.fullscreenToggleManager
                binding.root.post {
                    if (toggleManager.isTheatreMode) {
                        toggleManager.exitTheatreMode()
                    }
                    toggleManager.enterTheatreMode()
                }
            }
            // If was fullscreen before PiP (and NOT in theatre mode), restore fullscreen
            else if (wasFullscreenBeforePip) {
                activity.fullscreenToggleManager.enterFullscreen()
                // Don't restore 16:9 layout - enterFullscreen() sets its own layout
            } else {
                // Restore video container to default 16:9 layout (portrait mode)
                val videoParams = binding.videoContainer.layoutParams as? ConstraintLayout.LayoutParams
                videoParams?.let {
                    it.height = 0
                    it.width = 0
                    it.dimensionRatio = "16:9"
                    it.topToTop = ConstraintLayout.LayoutParams.PARENT_ID
                    it.startToStart = ConstraintLayout.LayoutParams.PARENT_ID
                    it.endToEnd = ConstraintLayout.LayoutParams.PARENT_ID
                    it.bottomToBottom = ConstraintLayout.LayoutParams.UNSET
                    binding.videoContainer.layoutParams = it
                }
            }

            // Resume chat if it was paused for low battery mode
            if (activity.prefs.lowBatteryModeEnabled && activity.isChatPausedForLowBattery) {
                activity.resumeChatForLowBatteryMode()
            }

            // Restore chat error message if it was showing before PIP
            activity.currentChatErrorMessage?.let { errorMsg ->
                binding.chatErrorText.text = errorMsg
                binding.chatErrorContainer.visibility = View.VISIBLE
            }

            // Restore chatroom hint
            activity.updateChatroomHint(activity.currentChatroom)
        }

        // 3. Restore settings panel if it was visible
        if (wasSettingsVisibleBeforePip) {
            activity.settingsPanelManager.showSettingsPanel()
            binding.mobileHeader.visibility = View.GONE
        }

        // Reset PiP state flags
        wasProfileVisibleBeforePip = false
        wasMiniPlayerModeBeforePip = false
        wasSettingsVisibleBeforePip = false
        wasFullscreenBeforePip = false
        wasTheatreModeBeforePip = false

        // Update chat padding to account for restored panels
        activity.channelUiManager.updateChatPaddingForPanels(false)
    }
}
