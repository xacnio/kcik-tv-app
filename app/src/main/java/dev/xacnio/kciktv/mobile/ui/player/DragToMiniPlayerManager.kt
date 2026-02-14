/**
 * File: DragToMiniPlayerManager.kt
 *
 * Description: Manages the interactive drag-to-minimize gesture for the video player.
 * It handles touch events to track the drag, applies real-time transformations (scale, translation, fade)
 * to UI elements, and animates the transition between full-screen and mini-player modes.
 *
 * Author: Xacnio
 *
 */
package dev.xacnio.kciktv.mobile.ui.player

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.graphics.Color
import android.view.View
import android.view.animation.AccelerateDecelerateInterpolator
import android.view.animation.DecelerateInterpolator
import dev.xacnio.kciktv.mobile.MobilePlayerActivity

/**
 * Manages the interactive drag-to-minimize gesture.
 * When the user drags down on the video player, the video follows the finger,
 * shrinks toward the mini-player size, while other UI elements fade out smoothly.
 */
class DragToMiniPlayerManager(private val activity: MobilePlayerActivity) {

    private val binding = activity.binding

    // Drag state
    var isDragToMiniActive = false
        private set
    private var dragStartRawX = 0f
    private var dragStartRawY = 0f
    private var isDragging = false
    private var directionLocked = false // Once we determine direction, lock it
    private var isHorizontalGesture = false // If true, this is a horizontal gesture - ignore
    private val DRAG_THRESHOLD = 20f // px threshold before drag begins

    // Layout measurements cached at drag start
    private var originalVideoWidth = 0
    private var originalVideoHeight = 0
    private var originalVideoTop = 0
    private var screenHeight = 0
    private var screenWidth = 0

    // Mini player target dimensions (must match MiniPlayerManager values)
    private val MINI_WIDTH_DP = 200f
    private val MINI_HEIGHT_DP = 112f
    private val MINI_MARGIN_END_DP = 16f
    private val MINI_MARGIN_BOTTOM_DP = 112f

    // Drag progress threshold to complete transition (0.0 - 1.0)
    private val COMPLETE_THRESHOLD = 0.35f
    // Velocity threshold - if fast enough, complete even if below position threshold
    private val VELOCITY_THRESHOLD = 800f

    // Track velocity
    private var lastMoveY = 0f
    private var lastMoveTime = 0L
    private var currentVelocityY = 0f

    /**
     * Called from PlayerGestureManager on ACTION_DOWN.
     * Stores the initial touch position for potential drag.
     */
    fun onTouchDown(rawX: Float, rawY: Float): Boolean {
        // Don't start drag if already in mini player mode, fullscreen, landscape, or theatre mode
        if (activity.miniPlayerManager.isMiniPlayerMode) return false
        if (activity.isFullscreen) return false
        if (activity.resources.configuration.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE) return false
        if (activity.fullscreenToggleManager.isTheatreMode) return false
        // Don't start if player isn't visible
        if (binding.playerScreenContainer.visibility != View.VISIBLE) return false

        dragStartRawX = rawX
        dragStartRawY = rawY
        lastMoveY = rawY
        lastMoveTime = System.currentTimeMillis()
        currentVelocityY = 0f
        isDragging = false
        directionLocked = false
        isHorizontalGesture = false
        return true
    }

    /**
     * Called on ACTION_MOVE. Returns true if this manager is handling the drag.
     */
    fun onTouchMove(rawX: Float, rawY: Float): Boolean {
        if (activity.miniPlayerManager.isMiniPlayerMode) return false
        if (activity.isFullscreen) return false
        if (activity.resources.configuration.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE) return false
        if (activity.fullscreenToggleManager.isTheatreMode) return false

        val deltaX = rawX - dragStartRawX
        val deltaY = rawY - dragStartRawY

        // Direction lock: determine if this is a horizontal or vertical gesture
        if (!directionLocked && (kotlin.math.abs(deltaX) > DRAG_THRESHOLD || kotlin.math.abs(deltaY) > DRAG_THRESHOLD)) {
            directionLocked = true
            isHorizontalGesture = kotlin.math.abs(deltaX) > kotlin.math.abs(deltaY)
        }

        // If horizontal gesture or upward, don't handle
        if (isHorizontalGesture) return false
        if (deltaY < 0) {
            if (isDragging) {
                // User dragged back up past start - snap to 0 progress
                updateDragProgress(0f)
            }
            return isDragging // Keep intercepting if we already started
        }

        // Calculate velocity
        val now = System.currentTimeMillis()
        val dt = now - lastMoveTime
        if (dt > 0) {
            currentVelocityY = (rawY - lastMoveY) / dt * 1000f // px/sec
        }
        lastMoveY = rawY
        lastMoveTime = now

        if (!isDragging && directionLocked && !isHorizontalGesture && deltaY > DRAG_THRESHOLD) {
            // Start dragging
            isDragging = true
            isDragToMiniActive = true
            beginDrag()
        }

        if (isDragging) {
            updateDragProgress(deltaY)
            return true
        }

        return false
    }

    /**
     * Called on ACTION_UP / ACTION_CANCEL. Decides whether to complete or cancel transition.
     */
    fun onTouchUp(rawY: Float): Boolean {
        if (!isDragging) {
            isDragToMiniActive = false
            return false
        }

        val deltaY = rawY - dragStartRawY
        val maxDrag = screenHeight * 0.6f
        val progress = (deltaY / maxDrag).coerceIn(0f, 1f)

        isDragging = false

        // Complete if progress > threshold OR velocity is high enough (fast fling down)
        if (progress > COMPLETE_THRESHOLD || currentVelocityY > VELOCITY_THRESHOLD) {
            animateToMiniPlayer(progress)
        } else {
            animateBackToFull(progress)
        }

        return true
    }

    /**
     * Initialize drag state - cache original dimensions.
     */
    private fun beginDrag() {
        val videoContainer = binding.videoContainer
        originalVideoWidth = videoContainer.width
        originalVideoHeight = videoContainer.height

        if (originalVideoWidth == 0 || originalVideoHeight == 0) {
            // Safety: can't calculate transforms without dimensions
            isDragging = false
            isDragToMiniActive = false
            return
        }

        val location = IntArray(2)
        videoContainer.getLocationOnScreen(location)
        originalVideoTop = location[1]

        screenHeight = binding.root.height
        screenWidth = binding.root.width

        // Hide overlays immediately as drag begins
        binding.videoOverlay.visibility = View.GONE
        binding.videoTopBar.visibility = View.GONE
        binding.playPauseOverlay.visibility = View.GONE

        // Stop overlay auto-hide
        activity.hideOverlay()
        activity.stopProgressUpdater()

        // Bring player container to front to prevent background views (like Feed) from overlapping 
        // when they become visible during drag animation
        binding.playerScreenContainer.bringToFront()
        binding.videoContainer.bringToFront()
    }

    /**
     * Updates the visual state based on drag distance.
     */
    private fun updateDragProgress(deltaY: Float) {
        val maxDrag = screenHeight * 0.6f
        val progress = (deltaY / maxDrag).coerceIn(0f, 1f)

        applyProgress(progress)
    }

    /**
     * Applies visual state for a given progress value (0.0 = full, 1.0 = mini).
     */
    private fun applyProgress(progress: Float) {
        if (originalVideoWidth == 0 || originalVideoHeight == 0) return

        val density = activity.resources.displayMetrics.density
        val videoContainer = binding.videoContainer

        // --- Video Container Transform ---
        val miniWidthPx = MINI_WIDTH_DP * density
        val miniHeightPx = MINI_HEIGHT_DP * density

        // Scale down from full to mini size
        val scaleX = 1f - progress * (1f - miniWidthPx / originalVideoWidth)
        val scaleY = 1f - progress * (1f - miniHeightPx / originalVideoHeight)
        val scale = kotlin.math.min(scaleX, scaleY)

        videoContainer.scaleX = scale
        videoContainer.scaleY = scale

        // Pivot at top-right corner so it scales towards bottom-right
        videoContainer.pivotX = originalVideoWidth.toFloat()
        videoContainer.pivotY = 0f

        // Translation Y: move down towards the mini player position
        val marginBottom = MINI_MARGIN_BOTTOM_DP * density
        val targetY = screenHeight - marginBottom - (miniHeightPx) - originalVideoTop
        videoContainer.translationY = targetY * progress

        // Translation X stays at 0 since pivot is at right edge and margins handle positioning
        val marginEnd = MINI_MARGIN_END_DP * density
        // The video needs to shift right to account for the margin
        videoContainer.translationX = -marginEnd * progress

        // --- Fade out other UI elements ---
        // Chat, action bar etc. fade out faster than drag progress
        val fadeAlpha = (1f - progress * 2.5f).coerceIn(0f, 1f)

        binding.chatContainer.alpha = fadeAlpha
        if (binding.actionBar.visibility == View.VISIBLE) {
            binding.actionBar.alpha = fadeAlpha
        }
        if (binding.infoPanel.visibility == View.VISIBLE) {
            binding.infoPanel.alpha = fadeAlpha
        }
        binding.loadingIndicator.alpha = fadeAlpha

        // Also fade the pinned gifts
        if (binding.pinnedGiftsBlur.visibility == View.VISIBLE) {
            binding.pinnedGiftsBlur.alpha = fadeAlpha
        }

        // --- Fade in background screens ---
        if (progress > 0.1f) {
            val bgAlpha = ((progress - 0.1f) / 0.3f).coerceIn(0f, 1f)

            // Show header and bottom nav with fade (only if NOT going to channel profile OR feed)
            val isGoingToProfile = activity.channelProfileManager.isChannelProfileVisible
                    || activity.screenBeforePlayer == MobilePlayerActivity.AppScreen.CHANNEL_PROFILE
            
            val isStreamFeedActive = try { activity.streamFeedManager.isFeedActive } catch (e: Exception) { false }
            val isClipFeedActive = try { activity.clipFeedManager.isFeedActive } catch (e: Exception) { false }
            
            val isGoingToFeed = activity.screenBeforePlayer == MobilePlayerActivity.AppScreen.STREAM_FEED
                    || activity.screenBeforePlayer == MobilePlayerActivity.AppScreen.CLIP_FEED
                    || isStreamFeedActive || isClipFeedActive
            
            val isGoingToCategoryDetails = activity.screenBeforePlayer == MobilePlayerActivity.AppScreen.CATEGORY_DETAILS
                    || binding.categoryDetailsContainer.root.visibility == View.VISIBLE

            if (!isGoingToProfile && !isGoingToFeed && !isGoingToCategoryDetails) {
                if (binding.mobileHeader.visibility != View.VISIBLE) {
                    binding.mobileHeader.visibility = View.VISIBLE
                    binding.mobileHeader.alpha = 0f
                }
                binding.mobileHeader.alpha = bgAlpha
            } else {
                // Explicitly ensure header is hidden if going to profile, feed, or category details
                 if (binding.mobileHeader.visibility == View.VISIBLE) {
                     binding.mobileHeader.visibility = View.GONE
                 }
            }

            if (binding.bottomNavContainer.visibility != View.VISIBLE && !isGoingToProfile && !isGoingToFeed) {
                binding.bottomNavContainer.visibility = View.VISIBLE
                binding.bottomNavContainer.alpha = 0f
            }
            if (!isGoingToProfile && !isGoingToFeed) binding.bottomNavContainer.alpha = bgAlpha

            if (binding.bottomNavGradient.visibility != View.VISIBLE && !isGoingToProfile && !isGoingToFeed) {
                binding.bottomNavGradient.visibility = View.VISIBLE
                binding.bottomNavGradient.alpha = 0f
            }
            if (!isGoingToProfile && !isGoingToFeed) binding.bottomNavGradient.alpha = bgAlpha

            // Show the correct background screen based on screenBeforePlayer
            // PRIORITIZE Feeds based on active state to avoid "Home appears then Feed appears" glitch
            val bgView: View? = if (isStreamFeedActive) {
                activity.streamFeedManager.feedRootView
            } else if (isClipFeedActive) {
                activity.clipFeedManager.feedRootView
            } else {
                when (activity.screenBeforePlayer) {
                    MobilePlayerActivity.AppScreen.STREAM_FEED -> activity.streamFeedManager.feedRootView
                    MobilePlayerActivity.AppScreen.CLIP_FEED -> activity.clipFeedManager.feedRootView
                    MobilePlayerActivity.AppScreen.SEARCH -> binding.searchContainer
                    MobilePlayerActivity.AppScreen.BROWSE -> binding.browseScreenContainer.root
                    MobilePlayerActivity.AppScreen.FOLLOWING -> binding.followingScreenContainer.root
                    MobilePlayerActivity.AppScreen.CHANNEL_PROFILE -> binding.channelProfileContainer.root
                    MobilePlayerActivity.AppScreen.CATEGORY_DETAILS -> binding.categoryDetailsContainer.root
                    else -> binding.homeScreenContainer.root
                }
            }

            if (bgView != null && bgView.visibility != View.VISIBLE) {
                bgView.visibility = View.VISIBLE
                bgView.alpha = 0f
            }
            if (bgView != null) {
                bgView.alpha = bgAlpha
            }
        }

        // Background color transition: from opaque dark to transparent
        val bgAlphaInt = ((1f - progress) * 255).toInt().coerceIn(0, 255)
        binding.playerScreenContainer.setBackgroundColor(Color.argb(bgAlphaInt, 15, 15, 15))
    }

    /**
     * Animate from current progress to full mini player state.
     */
    private fun animateToMiniPlayer(currentProgress: Float) {
        val animator = ValueAnimator.ofFloat(currentProgress, 1f)
        animator.duration = ((1f - currentProgress) * 250).toLong().coerceIn(100, 250)
        animator.interpolator = DecelerateInterpolator()

        animator.addUpdateListener { animation ->
            val progress = animation.animatedValue as Float
            applyProgress(progress)
        }

        animator.addListener(object : AnimatorListenerAdapter() {
            override fun onAnimationEnd(animation: Animator) {
                // Prevent glitch: Hide these immediately before resetting alphas
                // This ensures they don't flash visible for a frame when alpha is reset to 1f
                binding.chatContainer.visibility = View.GONE
                binding.actionBar.visibility = View.GONE
                binding.infoPanel.visibility = View.GONE
                binding.loadingIndicator.visibility = View.GONE
                binding.pinnedGiftsBlur.visibility = View.GONE

                // Reset all visual transforms
                resetVideoTransforms()
                resetAlphas()

                // Ensure we have a slug to return to (same logic as back handler)
                if (activity.returnToProfileSlug == null && activity.currentChannel != null) {
                    activity.returnToProfileSlug = activity.currentChannel?.slug
                }

                // Use the full closePlayerToProfile flow which handles:
                // 1. Entering mini player mode
                // 2. Restoring the previous screen (home, browse, following, etc.)
                activity.closePlayerToProfile()

                isDragToMiniActive = false
            }
        })

        animator.start()
    }

    /**
     * Animate from current progress back to full player state.
     */
    private fun animateBackToFull(currentProgress: Float) {
        val animator = ValueAnimator.ofFloat(currentProgress, 0f)
        animator.duration = (currentProgress * 300).toLong().coerceIn(100, 300)
        animator.interpolator = AccelerateDecelerateInterpolator()

        animator.addUpdateListener { animation ->
            val progress = animation.animatedValue as Float
            applyProgress(progress)
        }

        animator.addListener(object : AnimatorListenerAdapter() {
            override fun onAnimationEnd(animation: Animator) {
                resetVideoTransforms()
                restoreUIState()
                isDragToMiniActive = false
            }
        })

        animator.start()
    }

    /**
     * Cancel drag and restore everything immediately.
     */
    private fun cancelDrag() {
        isDragging = false
        isDragToMiniActive = false
        resetVideoTransforms()
        restoreUIState()
    }

    /**
     * Reset video container visual transforms to default.
     */
    private fun resetVideoTransforms() {
        val videoContainer = binding.videoContainer
        videoContainer.scaleX = 1f
        videoContainer.scaleY = 1f
        videoContainer.translationX = 0f
        videoContainer.translationY = 0f
        // Reset pivot to center
        if (originalVideoWidth > 0 && originalVideoHeight > 0) {
            videoContainer.pivotX = (originalVideoWidth / 2f)
            videoContainer.pivotY = (originalVideoHeight / 2f)
        }
    }

    /**
     * Reset alpha values on all affected views.
     */
    private fun resetAlphas() {
        binding.chatContainer.alpha = 1f
        binding.actionBar.alpha = 1f
        binding.infoPanel.alpha = 1f
        binding.loadingIndicator.alpha = 1f
        binding.mobileHeader.alpha = 1f
        binding.bottomNavContainer.alpha = 1f
        binding.bottomNavGradient.alpha = 1f
        binding.pinnedGiftsBlur.alpha = 1f
        // Reset alpha on all possible background screens
        binding.homeScreenContainer.root.alpha = 1f
        binding.searchContainer.alpha = 1f
        binding.browseScreenContainer.root.alpha = 1f
        binding.followingScreenContainer.root.alpha = 1f
        binding.channelProfileContainer.root.alpha = 1f
        binding.categoryDetailsContainer.root.alpha = 1f
        activity.streamFeedManager.feedRootView?.alpha = 1f
        activity.clipFeedManager.feedRootView?.alpha = 1f
    }

    /**
     * Restore UI elements that were faded during drag (cancel/snap-back).
     */
    private fun restoreUIState() {
        resetAlphas()

        // Restore background
        binding.playerScreenContainer.setBackgroundColor(Color.parseColor("#0f0f0f"))

        // Hide home UI elements that shouldn't be visible during player mode
        binding.mobileHeader.visibility = View.GONE
        binding.bottomNavContainer.visibility = View.GONE
        binding.bottomNavGradient.visibility = View.GONE

        // Hide any background screen that was made visible during drag
        if (!activity.isHomeScreenVisible) {
            binding.homeScreenContainer.root.visibility = View.GONE
        }
        // These screens should never be visible behind the full player
        binding.searchContainer.visibility = View.GONE
        binding.browseScreenContainer.root.visibility = View.GONE
        binding.followingScreenContainer.root.visibility = View.GONE
        binding.categoryDetailsContainer.root.visibility = View.GONE
        // Channel profile: only hide if it was shown just for the drag preview
        if (!activity.channelProfileManager.isChannelProfileVisible) {
            binding.channelProfileContainer.root.visibility = View.GONE
        }
        
        activity.streamFeedManager.feedRootView?.visibility = View.GONE
        activity.clipFeedManager.feedRootView?.visibility = View.GONE
    }
}
