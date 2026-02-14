/**
 * File: ChannelUiManager.kt
 *
 * Description: Manages the visual state and updates of the channel interface.
 * It handles the display of dynamic channel information such as viewer counts, uptime, and stream tags,
 * and coordinates the visibility and animation of overlay panels like the info panel and action bar.
 *
 * Author: Xacnio
 *
 */
package dev.xacnio.kciktv.mobile.ui.channel

import android.animation.ValueAnimator
import android.graphics.Bitmap
import android.graphics.drawable.Drawable
import android.os.Build
import android.util.Log
import android.view.View
import android.view.animation.AnimationUtils
import android.view.animation.DecelerateInterpolator
import androidx.core.graphics.toColorInt
import androidx.lifecycle.lifecycleScope
import com.bumptech.glide.Glide
import com.bumptech.glide.request.target.CustomTarget
import com.bumptech.glide.request.transition.Transition
import dev.xacnio.kciktv.mobile.MobilePlayerActivity
import dev.xacnio.kciktv.R
import dev.xacnio.kciktv.shared.data.model.ChannelItem
import kotlinx.coroutines.launch
import dev.xacnio.kciktv.mobile.ui.player.VodManager
import dev.xacnio.kciktv.shared.util.FormatUtils

/**
 * Manages channel UI updates and display.
 */
class ChannelUiManager(private val activity: MobilePlayerActivity) {

    private val binding get() = activity.binding
    private val prefs get() = activity.prefs
    private val repository get() = activity.repository
    private val vodManager get() = activity.vodManager
    private val chatConnectionManager get() = activity.chatConnectionManager
    private val chatAdapter get() = activity.chatAdapter
    private var paddingAnimator: ValueAnimator? = null

    companion object {
        private const val TAG = "ChannelUiManager"
    }

    /**
     * Updates UI elements to display channel information.
     */
    fun updateChannelUI(channel: ChannelItem) {
        // Update new info panel basic info
        binding.infoChannelName.text = channel.username
        binding.infoVerifiedBadge.visibility = if (channel.verified) View.VISIBLE else View.GONE
        binding.infoStreamTitle.text = channel.title ?: "Offline"

        if (!channel.categoryName.isNullOrEmpty()) {
            binding.infoCategoryName.text = channel.categoryName
            binding.infoCategoryName.visibility = View.VISIBLE
        } else {
            binding.infoCategoryName.visibility = View.GONE
        }
        binding.infoMatureBadge.visibility = if (channel.isMature) View.VISIBLE else View.GONE

        val tags = channel.tags?.take(5)?.joinToString(" • ") ?: ""
        binding.infoTagsText.text = tags
        binding.infoTagsText.isSelected = true // For Marquee effect

        binding.viewerCount.text = dev.xacnio.kciktv.shared.util.FormatUtils.formatViewerCount(
            channel.viewerCount.toLong(),
            activity.showFullViewerCount
        )

        binding.viewerCount.setOnClickListener {
            activity.showFullViewerCount = !activity.showFullViewerCount
            activity.viewerCountManager.fetchCurrentViewerCount()
        }

        // Setup uptime tracking
        if (channel.startTimeMillis != null) {
            activity.playbackStatusManager.streamCreatedAtMillis = channel.startTimeMillis
            activity.playbackStatusManager.startUptimeUpdater()
        } else {
            activity.playbackStatusManager.streamCreatedAtMillis = null
            activity.playbackStatusManager.stopUptimeUpdater()
            binding.streamTimeBadge.text = "00:00"
        }
        // Keep dividers always visible
        binding.streamTimeBadge.visibility = View.VISIBLE
        binding.uptimeDivider.visibility = View.VISIBLE

        // Start viewer count polling
        activity.currentLivestreamId = channel.livestreamId
        activity.viewerCountHandler.removeCallbacks(activity.viewerCountRunnable)
        if (activity.currentLivestreamId != null) {
            // Start immediately, then repeat every interval
            activity.viewerCountHandler.post(activity.viewerCountRunnable)
        } else {
            Log.w(TAG, "Not starting viewer count polling: livestreamId is null")
        }

        // Start pulse animation for live dot if not already running
        val pulseAnimation = AnimationUtils.loadAnimation(activity, R.anim.pulse)
        binding.liveDot.startAnimation(pulseAnimation)

        // Enable Marquee effect if title is too long
        binding.infoStreamTitle.isSelected = true

        // Initial badges
        binding.videoQualityBadge.text = activity.getString(R.string.quality_loading)

        // Load regular avatar and cache for notification
        Glide.with(activity)
            .asBitmap()
            .load(channel.getEffectiveProfilePicUrl())
            .circleCrop()
            .into(object : CustomTarget<Bitmap>() {
                override fun onResourceReady(resource: Bitmap, transition: Transition<in Bitmap>?) {
                    activity.currentProfileBitmap = resource
                    binding.infoProfileImage.setImageBitmap(resource)
                    if (activity.isBackgroundAudioEnabled) activity.showNotification()
                }
                override fun onLoadCleared(placeholder: Drawable?) {
                    activity.currentProfileBitmap = null
                }
            })
    }

    /**
     * Updates UI based on playback mode (LIVE, VOD, CLIP).
     */
    fun updatePlaybackUI() {
        val mode = vodManager.currentPlaybackMode
        val isLive = mode == dev.xacnio.kciktv.mobile.ui.player.VodManager.PlaybackMode.LIVE
        val isVod = mode == dev.xacnio.kciktv.mobile.ui.player.VodManager.PlaybackMode.VOD

        // For VOD/Clip: Show chat container but hide input, disconnect websocket
        if (!isLive) {
            // Disconnect live chat websocket
            chatConnectionManager.disconnect()

            // Clear existing chat messages
            chatAdapter.clearMessages()

            // Show chat container but hide input (read-only chat replay)
            binding.chatContainer.visibility = View.VISIBLE
            binding.chatInputRoot.visibility = View.GONE

            // Hide live-only overlays
            binding.chatOverlayContainer.visibility = View.GONE
            binding.pinnedGiftsBlur.visibility = View.GONE
            binding.blerpButton.visibility = View.GONE
            binding.pollContainer.visibility = View.GONE
            binding.predictionContainer.visibility = View.GONE

            // Hide "Canlıya Dön" button - reset viewer layout
            binding.liveDot.visibility = View.GONE
            binding.viewerLayout.visibility = View.GONE
            binding.viewerLayout.setOnClickListener(null)
            binding.viewerLayout.isClickable = false

            // Hide clip button for Clip, Show for VOD
            binding.clipButtonContainer.visibility = if (isVod) View.VISIBLE else View.GONE

            // Quality controls: Show badge for both, but only settings for VOD
            binding.videoQualityBadge.visibility = View.VISIBLE
            binding.videoQualityBadge.isEnabled = !mode.equals(dev.xacnio.kciktv.mobile.ui.player.VodManager.PlaybackMode.CLIP)
            binding.videoQualityBadge.alpha = if (isVod) 1.0f else 0.8f // Subtle hint

            binding.videoSettingsButton.visibility = View.VISIBLE // Settings always visible for VOD/Clip now for speed control

            // Stop live viewer count polling
            activity.viewerCountHandler.removeCallbacks(activity.viewerCountRunnable)
            activity.playbackStatusManager.stopUptimeUpdater()
            
            // Hide uptimeDivider in VOD/Clip mode - used for live stream uptime
            binding.uptimeDivider.visibility = View.GONE
        } else {
            binding.chatContainer.visibility = View.VISIBLE
            binding.chatInputRoot.visibility = View.VISIBLE
            binding.liveDot.visibility = View.VISIBLE
            binding.viewerLayout.visibility = View.VISIBLE
            binding.uptimeDivider.visibility = View.VISIBLE
            binding.streamTimeBadge.visibility = View.VISIBLE

            // Restore clip button and quality controls
            binding.clipButtonContainer.visibility = View.VISIBLE
            binding.videoQualityBadge.visibility = View.VISIBLE
            binding.videoSettingsButton.visibility = View.VISIBLE

            // Stop VOD chat replay
            vodManager.stopVodChatReplay()
        }
    }

    /**
     * Updates contextual channel info (username with verified badge and followers).
     */
    fun updateContextualChannelInfo(username: String, verified: Boolean, followers: Int, isFollowing: Boolean) {
        val sb = android.text.SpannableStringBuilder(username)

        if (verified) {
            sb.append("  ")
            val start = sb.length - 1
            val d = androidx.core.content.ContextCompat.getDrawable(activity, R.drawable.ic_verified)
            if (d != null) {
                d.mutate()
                d.setTint(prefs.themeColor)
                val size = (binding.infoChannelName.textSize).toInt()
                d.setBounds(0, 0, size, size)
                val alignment = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    android.text.style.ImageSpan.ALIGN_CENTER
                } else {
                    android.text.style.ImageSpan.ALIGN_BASELINE
                }
                val span = android.text.style.ImageSpan(d, alignment)
                sb.setSpan(span, start, start + 1, android.text.Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
            }
        }

        val formattedFollowers = activity.formatViewerCount(followers.toLong())
        val statsText = " • $formattedFollowers"
        val statsStart = sb.length
        sb.append(statsText)

        // Gray color, smaller size, and light font for stats
        sb.setSpan(
            android.text.style.ForegroundColorSpan("#AAAAAA".toColorInt()),
            statsStart,
            sb.length,
            android.text.Spannable.SPAN_EXCLUSIVE_EXCLUSIVE
        )
        sb.setSpan(
            android.text.style.RelativeSizeSpan(0.85f),
            statsStart,
            sb.length,
            android.text.Spannable.SPAN_EXCLUSIVE_EXCLUSIVE
        )
        sb.setSpan(
            android.text.style.TypefaceSpan("sans-serif-light"),
            statsStart,
            sb.length,
            android.text.Spannable.SPAN_EXCLUSIVE_EXCLUSIVE
        )

        binding.infoChannelName.text = sb

        activity.currentIsFollowing = isFollowing
        activity.updateFollowButtonState()
    }

    /**
     * Fetches the follow status for a channel.
     */
    fun fetchChannelFollowStatus(channelSlug: String) {
        val token = prefs.authToken ?: return

        activity.lifecycleScope.launch {
            try {
                // Background fetch to check actual follow status
                repository.getChannelUserMe(channelSlug, token).onSuccess { data ->
                    val isFollowing = data.isFollowing ?: false
                    activity.currentIsFollowing = isFollowing
                    activity.runOnUiThread {
                        activity.updateFollowButtonState()
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to fetch follow status: ${e.message}")
            }
        }
    }

    private var panelAnimator: ValueAnimator? = null

    /**
     * Animates infoPanel and/or actionBar in together as one smooth sliding surface.
     * Both panels clip-reveal from top to bottom behind the video edge.
     * Padding is animated in sync within the same animator.
     * Handles interrupted animations by continuing from current clip state.
     */
    fun animatePanelsIn(showInfoPanel: Boolean, showActionBar: Boolean) {
        panelAnimator?.cancel()
        paddingAnimator?.cancel()

        val infoPanel = binding.infoPanel
        val actionBar = binding.actionBar

        // Record previous state BEFORE changing visibility
        val infoPanelWasHidden = infoPanel.visibility == View.GONE
        val actionBarWasHidden = actionBar.visibility == View.GONE

        // Set up panels as visible, clip to zero height IMMEDIATELY for fresh panels to prevent flash
        if (showInfoPanel) {
            infoPanel.alpha = 1f
            infoPanel.translationY = 0f
            if (infoPanelWasHidden) {
                // Clip to zero height before making visible — prevents one-frame flash
                infoPanel.clipBounds = android.graphics.Rect(0, 0, Int.MAX_VALUE, 0)
            }
            infoPanel.visibility = View.VISIBLE
        }
        if (showActionBar) {
            actionBar.alpha = 1f
            actionBar.translationY = 0f
            if (actionBarWasHidden) {
                actionBar.clipBounds = android.graphics.Rect(0, 0, Int.MAX_VALUE, 0)
            }
            actionBar.visibility = View.VISIBLE
        }

        if (!showInfoPanel && !showActionBar) return

        // Post to wait for layout measurement
        binding.root.post {
            val infoPanelH = if (showInfoPanel) infoPanel.measuredHeight else 0
            val infoPanelW = if (showInfoPanel) infoPanel.measuredWidth else 0
            val actionBarH = if (showActionBar) actionBar.measuredHeight else 0
            val actionBarW = if (showActionBar) actionBar.measuredWidth else 0
            val totalHeight = infoPanelH + actionBarH

            if (totalHeight == 0) {
                if (showInfoPanel) infoPanel.clipBounds = null
                if (showActionBar) actionBar.clipBounds = null
                return@post
            }

            // Read current clip state — use wasHidden flag to avoid misdetecting fresh panels as "fully revealed"
            val currentInfoRevealed = when {
                !showInfoPanel || infoPanelH == 0 -> 0
                infoPanelWasHidden -> 0 // just became visible, start from zero
                infoPanel.clipBounds != null -> infoPanel.clipBounds!!.bottom // mid-animation
                else -> infoPanelH // already fully visible (no clip)
            }
            val currentActionRevealed = when {
                !showActionBar || actionBarH == 0 -> 0
                actionBarWasHidden -> 0
                actionBar.clipBounds != null -> actionBar.clipBounds!!.bottom
                else -> actionBarH
            }
            val currentTotalRevealed = currentInfoRevealed + currentActionRevealed

            // Already fully revealed
            if (currentTotalRevealed >= totalHeight) {
                if (showInfoPanel) infoPanel.clipBounds = null
                if (showActionBar) actionBar.clipBounds = null
                return@post
            }

            val startPadding = binding.chatContainer.paddingTop
            val targetPadding = infoPanelH + actionBarH

            panelAnimator = ValueAnimator.ofInt(currentTotalRevealed, totalHeight).apply {
                addUpdateListener { anim ->
                    val revealed = anim.animatedValue as Int

                    // InfoPanel reveals first
                    if (showInfoPanel && infoPanelH > 0) {
                        val infoRevealed = kotlin.math.min(revealed, infoPanelH)
                        infoPanel.clipBounds = android.graphics.Rect(0, 0, infoPanelW, infoRevealed)
                    }

                    // ActionBar reveals after infoPanel is fully shown
                    if (showActionBar && actionBarH > 0) {
                        val actionRevealed = kotlin.math.max(0, revealed - infoPanelH)
                        actionBar.clipBounds = android.graphics.Rect(0, 0, actionBarW, actionRevealed)
                    }

                    // Sync padding with reveal progress (ONLY if NOT in theatre mode AND NOT in side chat mode)
                    val isSideChat = try { activity.fullscreenToggleManager.isSideChatVisible } catch (e: Exception) { false }
                    val isTablet = activity.resources.getBoolean(R.bool.is_tablet)
                    val sideChatOverride = (isSideChat && !isTablet) // Force 0 padding for side chat on phones
                    
                    if (!activity.fullscreenToggleManager.isTheatreMode) {
                        val fraction = anim.animatedFraction
                        val currentPad = if (sideChatOverride) 0 else {
                            startPadding + ((targetPadding - startPadding) * fraction).toInt()
                        }
                        
                        binding.chatContainer.setPadding(
                            binding.chatContainer.paddingLeft,
                            currentPad,
                            binding.chatContainer.paddingRight,
                            binding.chatContainer.paddingBottom
                        )

                        // Keep chat scrolled to bottom during padding change
                        if (activity.chatUiManager.isChatAutoScrollEnabled) {
                            val count = activity.chatUiManager.chatAdapter.itemCount
                            if (count > 0) binding.chatRecyclerView.scrollToPosition(count - 1)
                        }
                    }
                }
                addListener(object : android.animation.AnimatorListenerAdapter() {
                    override fun onAnimationEnd(animation: android.animation.Animator) {
                        if (showInfoPanel) infoPanel.clipBounds = null
                        if (showActionBar) actionBar.clipBounds = null
                        activity.overlayManager.updateChatOverlayMargin()
                    }
                })
                duration = 350
                interpolator = DecelerateInterpolator()
                start()
            }
        }
    }

    /**
     * Animates infoPanel and/or actionBar out together as one smooth retraction.
     * Both panels clip-hide from bottom to top, sliding back behind the video edge.
     * Padding is animated in sync within the same animator.
     * Handles interrupted animations by continuing from current clip state.
     */
    fun animatePanelsOut(hideInfoPanel: Boolean, hideActionBar: Boolean) {
        panelAnimator?.cancel()
        paddingAnimator?.cancel()

        val infoPanel = binding.infoPanel
        val actionBar = binding.actionBar

        val infoPanelH = if (hideInfoPanel && infoPanel.visibility != View.GONE) infoPanel.height else 0
        val infoPanelW = if (hideInfoPanel && infoPanel.visibility != View.GONE) infoPanel.width else 0
        val actionBarH = if (hideActionBar && actionBar.visibility != View.GONE) actionBar.height else 0
        val actionBarW = if (hideActionBar && actionBar.visibility != View.GONE) actionBar.width else 0
        val totalHeight = infoPanelH + actionBarH

        if (totalHeight == 0) {
            // Already hidden, just ensure state
            if (hideInfoPanel) infoPanel.visibility = View.GONE
            if (hideActionBar) actionBar.visibility = View.GONE
            return
        }

        // Read current clip state to continue from where we are (no jump)
        val currentInfoRevealed = when {
            !hideInfoPanel || infoPanelH == 0 -> 0
            infoPanel.clipBounds != null -> infoPanel.clipBounds!!.bottom
            else -> infoPanelH // fully visible (no clip)
        }
        val currentActionRevealed = when {
            !hideActionBar || actionBarH == 0 -> 0
            actionBar.clipBounds != null -> actionBar.clipBounds!!.bottom
            else -> actionBarH
        }
        val currentTotalRevealed = currentInfoRevealed + currentActionRevealed

        // Already fully hidden
        if (currentTotalRevealed <= 0) {
            if (hideInfoPanel) infoPanel.visibility = View.GONE
            if (hideActionBar) actionBar.visibility = View.GONE
            return
        }

        val startPadding = binding.chatContainer.paddingTop

        panelAnimator = ValueAnimator.ofInt(currentTotalRevealed, 0).apply {
            addUpdateListener { anim ->
                val revealed = anim.animatedValue as Int

                // ActionBar hides first (reverse of show order)
                if (hideActionBar && actionBarH > 0) {
                    val actionRevealed = kotlin.math.max(0, revealed - infoPanelH)
                    actionBar.clipBounds = android.graphics.Rect(0, 0, actionBarW, actionRevealed)
                }

                // InfoPanel hides after actionBar is gone
                if (hideInfoPanel && infoPanelH > 0) {
                    val infoRevealed = kotlin.math.min(revealed, infoPanelH)
                    infoPanel.clipBounds = android.graphics.Rect(0, 0, infoPanelW, infoRevealed)
                }

                // Sync padding with hide progress (ONLY if NOT in theatre mode)
                val isSideChat = try { activity.fullscreenToggleManager.isSideChatVisible } catch (e: Exception) { false }
                val isTablet = activity.resources.getBoolean(R.bool.is_tablet)
                val sideChatOverride = (isSideChat && !isTablet)
                
                if (!activity.fullscreenToggleManager.isTheatreMode) {
                    val fraction = anim.animatedFraction
                    val currentPad = if (sideChatOverride) 0 else {
                        startPadding + ((0 - startPadding) * fraction).toInt()
                    }
                    
                    binding.chatContainer.setPadding(
                        binding.chatContainer.paddingLeft,
                        currentPad,
                        binding.chatContainer.paddingRight,
                        binding.chatContainer.paddingBottom
                    )

                    // Keep chat scrolled to bottom during padding change
                    if (activity.chatUiManager.isChatAutoScrollEnabled) {
                        val count = activity.chatUiManager.chatAdapter.itemCount
                        if (count > 0) binding.chatRecyclerView.scrollToPosition(count - 1)
                    }
                }
            }
            addListener(object : android.animation.AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: android.animation.Animator) {
                    if (hideInfoPanel) {
                        infoPanel.visibility = View.GONE
                        infoPanel.clipBounds = null
                    }
                    if (hideActionBar) {
                        actionBar.visibility = View.GONE
                        actionBar.clipBounds = null
                    }
                    activity.overlayManager.updateChatOverlayMargin()
                }
            })
            duration = 250
            interpolator = DecelerateInterpolator()
            start()
        }
    }

    /**
     * Updates the chat container's top padding to account for the visible infoPanel + actionBar.
     * This pushes the chat content down so it's not hidden behind the overlay panels.
     */
    fun updateChatPaddingForPanels(animate: Boolean = false, forceHideActionBar: Boolean = false, forceHideInfoPanel: Boolean = false) {
        binding.chatContainer.post {
            val density = activity.resources.displayMetrics.density

            val infoPanelHeight = if (!forceHideInfoPanel && binding.infoPanel.visibility == View.VISIBLE) {
                binding.infoPanel.height.takeIf { it > 0 } ?: (68 * density).toInt()
            } else 0

            val actionBarHeight = if (!forceHideActionBar && binding.actionBar.visibility == View.VISIBLE && binding.actionBar.alpha > 0f) {
                (44 * density).toInt()
            } else 0

            // Do NOT include pinned gifts height in padding, as pinned items are inside the container.
            // Adding it would push the pinned items down, creating a gap.
            // In Theatre Mode, panels float over chat/video, so padding should be 0.
            // In Side Chat mode (Phones), panels are reparented above chat, so padding should be 0.
            val isSideChat = try { activity.fullscreenToggleManager.isSideChatVisible } catch (e: Exception) { false }
            val isTablet = activity.resources.getBoolean(R.bool.is_tablet)
            
            val targetPadding = if (activity.fullscreenToggleManager.isTheatreMode || (isSideChat && !isTablet)) 0 else {
                infoPanelHeight + actionBarHeight
            }
            val currentPadding = binding.chatContainer.paddingTop

            if (targetPadding == currentPadding) return@post

            if (animate) {
                paddingAnimator?.cancel()
                val animator = ValueAnimator.ofInt(currentPadding, targetPadding)
                paddingAnimator = animator
                
                animator.duration = 300
                animator.interpolator = DecelerateInterpolator()
                animator.addUpdateListener { anim ->
                    val value = anim.animatedValue as Int
                    binding.chatContainer.setPadding(
                        binding.chatContainer.paddingLeft,
                        value,
                        binding.chatContainer.paddingRight,
                        binding.chatContainer.paddingBottom
                    )
                    // Keep chat scrolled to bottom during padding change
                    if (activity.chatUiManager.isChatAutoScrollEnabled) {
                        val count = activity.chatUiManager.chatAdapter.itemCount
                        if (count > 0) binding.chatRecyclerView.scrollToPosition(count - 1)
                    }
                }
                animator.start()
            } else {
                paddingAnimator?.cancel()
                binding.chatContainer.setPadding(
                    binding.chatContainer.paddingLeft,
                    targetPadding,
                    binding.chatContainer.paddingRight,
                    binding.chatContainer.paddingBottom
                )
            }

            // Also update overlay margin
            activity.overlayManager.updateChatOverlayMargin()
        }
    }
}
