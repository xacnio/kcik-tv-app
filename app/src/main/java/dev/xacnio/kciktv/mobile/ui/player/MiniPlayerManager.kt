/**
 * File: MiniPlayerManager.kt
 *
 * Description: Manages the in-app Mini Player mode (distinct from system PiP).
 * It handles the transitions between full-screen and mini-player states, manages the window layout params
 * to shrink the video to the corner, and restores the UI when expanding back to full screen.
 *
 * Author: Xacnio
 *
 */
package dev.xacnio.kciktv.mobile.ui.player

import android.annotation.SuppressLint
import android.graphics.Color
import android.view.MotionEvent
import android.view.View
import androidx.constraintlayout.widget.ConstraintLayout
import dev.xacnio.kciktv.mobile.MobilePlayerActivity
import dev.xacnio.kciktv.R

class MiniPlayerManager(private val activity: MobilePlayerActivity) {

    private val binding = activity.binding
    private val channelProfileManager = activity.channelProfileManager

    // Keeping track of state
    var isMiniPlayerMode = false
    var miniPlayerTranslationX = 0f
    var miniPlayerTranslationY = 0f

    /**
     * Refreshes mini player interactions. Called to ensure touch listeners work after PiP.
     */
    @SuppressLint("ClickableViewAccessibility")
    fun refreshInteractions() {
        if (!isMiniPlayerMode) return
        
        // Clear existing listeners first
        binding.miniPlayerMaximizeArea.setOnTouchListener(null)
        binding.miniPlayerMaximizeArea.setOnClickListener(null)
        binding.miniPlayerCloseButton.setOnClickListener(null)
        binding.miniPlayerControls.setOnTouchListener(null)
        
        // Add touch listener to miniPlayerControls to intercept touches and prevent parent from stealing
        binding.miniPlayerControls.setOnTouchListener { v, _ ->
            v.parent?.requestDisallowInterceptTouchEvent(true)
            false
        }
        
        // Re-setup drag listener
        setupMiniPlayerDraggable()
        
        // Re-setup click listeners
        binding.miniPlayerCloseButton.setOnClickListener {
            closeMiniPlayer()
        }
        
        binding.miniPlayerMaximizeArea.setOnClickListener {
            if (channelProfileManager.isChannelProfileVisible) {
                // Save the profile slug before closing — back from maximized player
                // should return to this profile, not the stale one from stream start
                val profileSlug = channelProfileManager.currentProfileSlug
                channelProfileManager.closeChannelProfile()
                activity.exitMiniPlayerMode()
                if (!profileSlug.isNullOrEmpty()) {
                    activity.returnToProfileSlug = profileSlug
                    activity.screenBeforePlayer = MobilePlayerActivity.AppScreen.CHANNEL_PROFILE
                }
            } else {
                activity.exitMiniPlayerMode()
            }
        }
        
        // Ensure interactivity
        binding.miniPlayerControls.isClickable = true
        binding.miniPlayerControls.isFocusable = true
        binding.miniPlayerMaximizeArea.isClickable = true
        binding.miniPlayerMaximizeArea.isFocusable = true
        binding.miniPlayerCloseButton.isClickable = true
        binding.miniPlayerCloseButton.isEnabled = true
        binding.miniPlayerCloseButton.isFocusable = true
        
        // Ensure the parent doesn't intercept touch events
        binding.playerScreenContainer.isClickable = false
        binding.playerScreenContainer.isFocusable = false
        
        // Force Z-order
        binding.playerScreenContainer.bringToFront()
        binding.videoContainer.bringToFront()
        binding.miniPlayerControls.bringToFront()
        binding.miniPlayerCloseButton.bringToFront()
    }

    /**
     * Closes the mini player and stops playback.
     */
    fun closeMiniPlayer() {
        activity.stopPlayer()
        
        // CRITICAL: Reset side chat state to prevent "Stuck Split Screen" bug
        if (activity.fullscreenToggleManager != null) {
            activity.fullscreenToggleManager.cleanupSideChat(forceReset = true)
        }
        
        // Clean up Chat UI state
        activity.chatUiManager.isChatUiPaused = false
        activity.chatUiManager.reset()
        
        binding.playerScreenContainer.visibility = View.GONE
        binding.miniPlayerControls.visibility = View.GONE
        binding.videoContainer.translationX = 0f
        binding.videoContainer.translationY = 0f
        miniPlayerTranslationX = 0f
        miniPlayerTranslationY = 0f
        isMiniPlayerMode = false
        
        // Reset player state
        activity.currentChannel = null
        
        // Hide notification
        activity.hideNotification()
        
        // Restore container layout - ensure ALL split-screen constraints are cleared
        val params = ConstraintLayout.LayoutParams(0, 0)
        params.dimensionRatio = "16:9"
        params.topToTop = ConstraintLayout.LayoutParams.PARENT_ID
        params.endToEnd = ConstraintLayout.LayoutParams.PARENT_ID
        params.startToStart = ConstraintLayout.LayoutParams.PARENT_ID
        params.endToStart = ConstraintLayout.LayoutParams.UNSET // CRITICAL: Clear split-screen anchor
        binding.videoContainer.layoutParams = params
        
        // Reset elevation and background
        binding.playerScreenContainer.elevation = 0f
        binding.videoContainer.elevation = 0f
        binding.miniPlayerControls.elevation = 0f
        binding.playerScreenContainer.setBackgroundColor(android.graphics.Color.parseColor("#0f0f0f"))
    }

    /**
     * Enters MiniPlayer mode by shrinking the video container and adjusting UI visibility.
     */
    @SuppressLint("ClickableViewAccessibility")
    fun enterMiniPlayerMode() {
        if (isMiniPlayerMode || activity.currentChannel == null) {
            return
        }

        // CRITICAL: Reset side chat state when entering mini player
        if (activity.fullscreenToggleManager != null) {
            activity.fullscreenToggleManager.cleanupSideChat(forceReset = true)
        }
        
        // Hide chat rules if visible
        activity.chatRulesManager.dismiss()

        // Enforce 360p limit for Mini Player
        activity.setForcedQualityLimit("360p")

        // 1. Configure UI for MiniPlayer (No Reparenting)
        // Show MiniPlayer Controls overlay
        binding.miniPlayerControls.visibility = View.VISIBLE
        binding.miniPlayerControls.bringToFront()
        
        // Add touch interceptor to prevent parent from stealing touch events
        binding.miniPlayerControls.setOnTouchListener { v, _ ->
            v.parent?.requestDisallowInterceptTouchEvent(true)
            false // Let children handle the event
        }

        // Update Layout Params to shrink videoContainer to bottom-right
        val videoContainer = binding.videoContainer
        val density = activity.resources.displayMetrics.density
        val width = (200 * density).toInt()
        val height = (112 * density).toInt()
        val marginEnd = (16 * density).toInt()
        // Adjust bottom margin: If profile is visible, use smaller margin (no bottom nav).
        val marginBottom = if (channelProfileManager.isChannelProfileVisible) {
            (16 * density).toInt()
        } else {
            (112 * density).toInt() // Space for bottom nav on Home
        }

        val params = ConstraintLayout.LayoutParams(width, height)
        params.bottomToBottom = ConstraintLayout.LayoutParams.PARENT_ID
        params.endToEnd = ConstraintLayout.LayoutParams.PARENT_ID
        params.setMargins(0, 0, marginEnd, marginBottom)
        videoContainer.layoutParams = params
        videoContainer.requestLayout()

        // Make background transparent so Home screen is visible behind
        binding.playerScreenContainer.visibility = View.VISIBLE
        binding.playerScreenContainer.setBackgroundColor(Color.TRANSPARENT)
        
        // Use high elevation to ensure it's above all other UI elements (including bottom nav and header)
        binding.playerScreenContainer.elevation = 150f
        binding.videoContainer.elevation = 151f
        binding.miniPlayerControls.elevation = 152f

        // Ensure player is on top of everything
        binding.playerScreenContainer.bringToFront()
        
        // Critical: In mini mode, the container itself must NOT be clickable to let touches 
        // pass through to the Home screen, BUT its children MUST be clickable.
        binding.playerScreenContainer.isClickable = false
        binding.playerScreenContainer.isFocusable = false
        binding.videoContainer.isClickable = true
        binding.miniPlayerControls.isClickable = true
        binding.miniPlayerMaximizeArea.isClickable = true
        binding.miniPlayerCloseButton.isClickable = true
        binding.miniPlayerCloseButton.isEnabled = true

        // 2. Hide Player Overlays & ensure old container is gone
        binding.miniPlayerContainer.visibility = View.GONE

        // Hide external UI elements
        binding.root.post {
            binding.actionBar.visibility = View.GONE
            binding.chatContainer.visibility = View.GONE
            binding.pinnedGiftsBlur.visibility = View.GONE
        }
            
        // LOGIC MOVED OUTSIDE POST TO AVOID RACE CONDITION WITH resumeFeed()
        // Only show home header if we are NOT going to channel profile AND NOT returning to a Feed
        val isStreamFeedActive = try { activity.streamFeedManager.isFeedActive } catch (e: Exception) { false }
        val isClipFeedActive = try { activity.clipFeedManager.isFeedActive } catch (e: Exception) { false }
        
        val isReturningToFeed = activity.screenBeforePlayer == MobilePlayerActivity.AppScreen.STREAM_FEED || 
                               activity.screenBeforePlayer == MobilePlayerActivity.AppScreen.CLIP_FEED ||
                               isStreamFeedActive || isClipFeedActive
        
            val isGoingToCategoryDetails = activity.screenBeforePlayer == MobilePlayerActivity.AppScreen.CATEGORY_DETAILS
                    || binding.categoryDetailsContainer.root.visibility == View.VISIBLE

            if (!channelProfileManager.isChannelProfileVisible && !isReturningToFeed && !isGoingToCategoryDetails) {
                binding.mobileHeader.visibility = View.VISIBLE
            } else {
                binding.mobileHeader.visibility = View.GONE
            }

        binding.videoOverlay.visibility = View.GONE
        binding.videoTopBar.visibility = View.GONE
        binding.playPauseOverlay.visibility = View.GONE
        binding.loadingOverlay.visibility = View.GONE
        binding.infoPanel.visibility = View.GONE

        // 3. Screen visibility is now handled by closePlayerToProfile() 
        // to ensure only one screen is visible at a time
        // Just hide home if profile is visible
        if (channelProfileManager.isChannelProfileVisible) {
            binding.homeScreenContainer.root.visibility = View.GONE
        }

        // NOTE: We intentionally do NOT restore translationX/Y here because
        // translation moves the view visually but doesn't update touch bounds.
        // The mini player always starts at the default bottom-right position.
        videoContainer.translationX = 0f
        videoContainer.translationY = 0f
        
        isMiniPlayerMode = true
        
        // Log analytics event
        activity.analytics.logMiniPlayerUsed()

        // Dismiss bottom sheets
        activity.dismissAllBottomSheets()
        
        // Final Z-order enforcement in a post to ensure it happens after layout settles
        binding.root.postDelayed({
            if (isMiniPlayerMode) {
                binding.playerScreenContainer.bringToFront()
                binding.miniPlayerControls.bringToFront()
                binding.miniPlayerCloseButton.bringToFront()
            }
        }, 300)

        // Stop unnecessary UI updaters while in mini player mode
        activity.playbackStatusManager.stopUptimeUpdater()
        activity.playbackControlManager.stopProgressUpdater()
        activity.chatUiManager.stopFlushing()
    }

    /**
     * Exits MiniPlayer mode by restoring video container to full width and restoring UI visibility.
     */
    fun exitMiniPlayerMode() {
        if (!isMiniPlayerMode) return
        
        // Remove enforced limit (return to user preference)
        activity.setForcedQualityLimit(null)

        // 1. Restore Layout Params (Match Parent)
        val videoContainer = binding.videoContainer

        val params = ConstraintLayout.LayoutParams(0, 0)
        params.dimensionRatio = "16:9"
        params.topToTop = ConstraintLayout.LayoutParams.PARENT_ID
        params.endToEnd = ConstraintLayout.LayoutParams.PARENT_ID
        params.startToStart = ConstraintLayout.LayoutParams.PARENT_ID
        params.endToStart = ConstraintLayout.LayoutParams.UNSET // CRITICAL: Clear split-screen anchor
        videoContainer.layoutParams = params

        // Fix: Reset translation that might have been changed by dragging
        videoContainer.translationX = 0f
        videoContainer.translationY = 0f
        // Position variables preserved for PiP restoration

        // Restore background
        binding.playerScreenContainer.elevation = 0f
        binding.videoContainer.elevation = 0f
        binding.miniPlayerControls.elevation = 0f
        binding.playerScreenContainer.setBackgroundColor(Color.parseColor("#0f0f0f"))
        binding.playerScreenContainer.isClickable = true
        binding.playerScreenContainer.isFocusable = true

        // 2. UI Toggles
        binding.miniPlayerControls.visibility = View.GONE

        binding.miniPlayerContainer.visibility = View.GONE
        binding.playerScreenContainer.visibility = View.VISIBLE

        // Restore external UI elements
        binding.actionBar.visibility = View.GONE
        binding.chatContainer.visibility = View.VISIBLE
        binding.mobileHeader.visibility = View.GONE

        // Hide Home Screen (or others)
        if (!channelProfileManager.isChannelProfileVisible) {
             binding.homeScreenContainer.root.visibility = View.GONE
             binding.browseScreenContainer.root.visibility = View.GONE
             binding.followingScreenContainer.root.visibility = View.GONE
             binding.searchContainer.visibility = View.GONE
             activity.isHomeScreenVisible = false
             
             // Hide Feed Views if they were visible
             if (try { activity.streamFeedManager.isFeedVisible } catch(e: Exception) { false }) {
                 activity.streamFeedManager.feedRootView?.visibility = View.GONE
             }
             if (try { activity.clipFeedManager.isFeedVisible } catch(e: Exception) { false }) {
                 activity.clipFeedManager.feedRootView?.visibility = View.GONE
             }
        } else {
             // If Channel Profile is visible, we DO NOT hide it.
             // We ensure it stays visible behind the player (or when player closes)
             binding.channelProfileContainer.root.visibility = View.VISIBLE
        }

        isMiniPlayerMode = false
        
        activity.overlayManager.updatePinnedGiftsUI()

        binding.videoContainer.translationX = 0f
        
        binding.bottomNavContainer.visibility = View.GONE
        binding.bottomNavGradient.visibility = View.GONE
        
        // Restart UI updaters when exiting mini player mode
        activity.playbackStatusManager.startUptimeUpdater()
        activity.playbackControlManager.startProgressUpdater()
        activity.chatUiManager.startFlushing()

        // Update chat padding to account for potentially restored panels
        activity.channelUiManager.updateChatPaddingForPanels(false)
    }

    /**
     * Sets up the touch listener for dragging the MiniPlayer.
     */
    @SuppressLint("ClickableViewAccessibility")
    fun setupMiniPlayerDraggable() {
        val videoContainer = binding.videoContainer
        var initialX = 0f
        var initialY = 0f
        var initialTouchX = 0f
        var initialTouchY = 0f
        var isDragging = false
        val dragThreshold = 10f // Threshold for drag vs click

        // Attach drag listener to the maximize area specifically as it covers the most space
        val dragHandle = binding.miniPlayerMaximizeArea

        dragHandle.setOnTouchListener { v, event ->
            if (!isMiniPlayerMode) return@setOnTouchListener false

            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    initialX = videoContainer.translationX
                    initialY = videoContainer.translationY
                    initialTouchX = event.rawX
                    initialTouchY = event.rawY
                    isDragging = false
                    false // Return false to allow click detection on UP if no move occurs
                }
                MotionEvent.ACTION_MOVE -> {
                    val deltaX = event.rawX - initialTouchX
                    val deltaY = event.rawY - initialTouchY

                    if (!isDragging && (kotlin.math.abs(deltaX) > dragThreshold || kotlin.math.abs(deltaY) > dragThreshold)) {
                        isDragging = true
                        videoContainer.parent.requestDisallowInterceptTouchEvent(true)
                    }

                    if (isDragging) {
                        val newTranslationX = initialX + deltaX
                        val newTranslationY = initialY + deltaY

                        val parent = videoContainer.parent as View
                        val density = activity.resources.displayMetrics.density
                        val marginHorizontal = (16 * density).toInt()

                        // X logic (Right-to-Left)
                        val maxScrollLeft = -(parent.width - videoContainer.width - 2 * marginHorizontal).toFloat()
                        val boundedTranslationX = Math.max(maxScrollLeft, Math.min(0f, newTranslationX))

                        // Y logic (Bottom-to-Top)
                        // Get current limits
                        val params = videoContainer.layoutParams as? ConstraintLayout.LayoutParams
                        val bottomMargin = params?.bottomMargin ?: (16 * density).toInt()
                        val topMarginSafety = (50 * density).toInt() // Avoid touching status bar/header area

                        val maxScrollUp = -(parent.height - videoContainer.height - bottomMargin - topMarginSafety).toFloat()
                        val boundedTranslationY = Math.max(maxScrollUp, Math.min(0f, newTranslationY))

                        videoContainer.translationX = boundedTranslationX
                        videoContainer.translationY = boundedTranslationY

                        // Update state immediately during drag
                        miniPlayerTranslationX = boundedTranslationX
                        miniPlayerTranslationY = boundedTranslationY

                        true
                    } else {
                        false
                    }
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    if (isDragging) {
                        // Snap specifically to left or right edge (X only)
                        val parent = videoContainer.parent as View
                        val density = activity.resources.displayMetrics.density
                        val marginHorizontal = (16 * density).toInt()
                        val maxScrollLeft = -(parent.width - videoContainer.width - 2 * marginHorizontal).toFloat()

                        val targetTranslationX = if (videoContainer.translationX < maxScrollLeft / 2) {
                            maxScrollLeft
                        } else {
                            0f
                        }

                        // Keep Y where it is dropped
                        val targetTranslationY = videoContainer.translationY

                        videoContainer.animate()
                            .translationX(targetTranslationX)
                            .translationY(targetTranslationY)
                            .setDuration(200)
                            .withEndAction {
                                miniPlayerTranslationX = targetTranslationX
                                miniPlayerTranslationY = targetTranslationY
                            }
                            .start()
                        true
                    } else {
                        // If it wasn't a drag, handle it as a click on maximize area
                        if (event.action == MotionEvent.ACTION_UP) {
                            v.performClick()
                        }
                        false
                    }
                }
                else -> false
            }
        }
    }
}
