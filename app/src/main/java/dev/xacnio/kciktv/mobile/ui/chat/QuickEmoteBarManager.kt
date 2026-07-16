/**
 * File: QuickEmoteBarManager.kt
 *
 * Description: Manages the Quick Emote Bar located above the chat input.
 * It tracks recently used emotes and displays a horizontally scrolling list of
 * available emotes (including subscriber-only checks) for quick access.
 *
 * Author: Xacnio
 *
 */
package dev.xacnio.kciktv.mobile.ui.chat

import android.os.Handler
import android.view.View
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import dev.xacnio.kciktv.mobile.MobilePlayerActivity
import dev.xacnio.kciktv.shared.data.model.Emote
import dev.xacnio.kciktv.shared.data.model.EmoteCategory
import dev.xacnio.kciktv.shared.data.prefs.AppPreferences
import dev.xacnio.kciktv.databinding.ActivityMobilePlayerBinding
import dev.xacnio.kciktv.shared.ui.adapter.QuickEmoteAdapter
import dev.xacnio.kciktv.shared.util.VibrationUtils

/**
 * Manages the quick emote bar functionality.
 * Handles emote display, recent emotes tracking, and subscription status.
 */
class QuickEmoteBarManager(
    private val activity: MobilePlayerActivity,
    private val binding: ActivityMobilePlayerBinding,
    private val prefs: AppPreferences,
    private val mainHandler: Handler
) {
    companion object {
        /** How many recent emote ids are kept in prefs. Caps the emote panel's recents tab. */
        const val MAX_STORED_RECENTS = 60

        /** How many recents lead the quick bar before it fills up with the rest of the emotes. */
        private const val QUICK_BAR_RECENTS = 15
    }

    private lateinit var quickEmoteAdapter: QuickEmoteAdapter
    private var isInitialized = false
    private var revealRunnable: Runnable? = null
    
    // Callbacks for communication with activity
    var onEmoteSend: ((String) -> Unit)? = null
    var onEmoteAppend: ((Emote) -> Unit)? = null
    
    /**
     * Get current channel ID from activity
     */
    private val currentChannelId: Long?
        get() = activity.currentChannel?.id?.toLongOrNull()
    
    /**
     * Get subscription status from activity  
     */
    private val isSubscribedToCurrentChannel: Boolean
        get() = activity.chatStateManager.isSubscribedToCurrentChannel
    
    /**
     * Get emote categories from activity
     */
    private val emoteCategories: List<EmoteCategory>
        get() = activity.emotePanelManager.emoteCategories
    
    /**
     * Check if emote panel is visible
     */
    private val isEmotePanelVisible: Boolean
        get() = binding.emotePanelContainer.visibility == View.VISIBLE
    
    /**
     * Check if keyboard is visible
     */
    private val isKeyboardVisible: Boolean
        get() = ViewCompat.getRootWindowInsets(binding.root)
            ?.isVisible(WindowInsetsCompat.Type.ime()) == true
    
    /**
     * Setup the quick emote bar with adapter and click handling
     */
    fun setupQuickEmoteBar() {
        quickEmoteAdapter = QuickEmoteAdapter { emote ->
            // Use a more robust check: focused AND keyboard actually visible
            if ((binding.chatInput.isFocused && isKeyboardVisible) || isEmotePanelVisible) {
                onEmoteAppend?.invoke(emote)
            } else {
                onEmoteSend?.invoke("[emote:${emote.id}:${emote.name}]")
                // Light haptic feedback for emote send
                VibrationUtils.lightTick(activity)
            }
            addRecentEmote(emote)
        }
        
        // Correctly set initial subscription and channel info
        quickEmoteAdapter.setSubscriptionStatus(isSubscribedToCurrentChannel)
        quickEmoteAdapter.setCurrentChannelId(currentChannelId)
        
        binding.quickEmoteRecyclerView.adapter = quickEmoteAdapter
        isInitialized = true

        // Trigger an immediate visibility pass: if emotes are already loaded this shows
        // the bar; if not (first open) it shows the shimmer instead of a black gap.
        updateQuickEmoteBar()
    }

    /**
     * Update the quick emote bar with available emotes
     */
    fun updateQuickEmoteBar(showShimmer: Boolean = true) {
        // Hide for logged out users - they can't send emotes anyway
        if (!prefs.isLoggedIn) {
            binding.quickEmoteBarContainer.visibility = View.GONE
            return
        }

        // Make sure container is visible for logged in users
        binding.quickEmoteBarContainer.visibility = View.VISIBLE

        if (!isInitialized) {
            // Adapter not set up yet — hide everything
            binding.quickEmoteRecyclerView.visibility = View.GONE
            binding.quickEmoteShimmer.root.visibility = View.GONE
            return
        }

        if (emoteCategories.isEmpty()) {
            // Still loading emotes — show shimmer placeholder
            binding.quickEmoteRecyclerView.visibility = View.GONE
            binding.quickEmoteShimmer.root.visibility = View.VISIBLE
            return
        }

        val channelId = currentChannelId

        // 1. Get all usable emotes
        val allUsableEmotes = emoteCategories.flatMap { category ->
            category.emotes.filter { emote ->
                if (!emote.subscribersOnly) return@filter true

                // If it's a sub emote:
                // - Only allow current channel's sub emotes if user is subscribed
                // - Allow other channels' sub emotes (since we got them via the API, user is a sub there)
                val emoteChannelId = emote.channelId
                if (emoteChannelId != null && channelId != null && emoteChannelId == channelId) {
                    isSubscribedToCurrentChannel
                } else {
                    true
                }
            }
        }

        if (allUsableEmotes.isEmpty()) {
            binding.quickEmoteRecyclerView.visibility = View.GONE
            binding.quickEmoteShimmer.root.visibility = View.GONE
            return
        }

        // 2. Get recent emotes (filtered for usability)
        val channelRecent = if (channelId != null) prefs.getChannelRecentEmoteIds(channelId) else null
        val globalRecent = prefs.recentEmoteIds
        val effectiveRecent = channelRecent ?: globalRecent

        val recentIds = effectiveRecent?.split(",")?.mapNotNull { it.toLongOrNull() } ?: emptyList()
        val recentEmotes = recentIds.mapNotNull { id ->
            allUsableEmotes.find { it.id == id }
        }.take(QUICK_BAR_RECENTS)

        // 3. Combine: Recent first, then others (limit total for performance/UI)
        val finalEmotes = (recentEmotes + allUsableEmotes).distinctBy { it.id }.take(40)

        // Strategy: keep shimmer visible while images load, reveal RecyclerView only when
        // the threshold number of images is loaded, or when the 1000ms failsafe timeout fires.
        if (showShimmer) {
            binding.quickEmoteRecyclerView.visibility = View.INVISIBLE
            binding.quickEmoteShimmer.root.visibility = View.VISIBLE

            val startTime = android.os.SystemClock.uptimeMillis()

            // Cancel any pending reveal to avoid multiple posts/leaks
            revealRunnable?.let { mainHandler.removeCallbacks(it) }

            val runnable = Runnable {
                binding.quickEmoteShimmer.root.visibility = View.GONE
                binding.quickEmoteRecyclerView.visibility = View.VISIBLE
                revealRunnable = null
            }
            revealRunnable = runnable

            // Failsafe: if some emotes fail or load too slowly, reveal the RecyclerView after 1000ms anyway
            mainHandler.postDelayed(runnable, 1000L)

            quickEmoteAdapter.onFirstImageReady = {
                val elapsed = android.os.SystemClock.uptimeMillis() - startTime
                // If loaded instantly (elapsed < 15ms), it's a memory cache hit - reveal instantly.
                // Otherwise, enforce a minimum shimmer duration of 300ms to allow parallel loads to finish.
                val delay = if (elapsed < 15L) 0L else Math.max(0L, 300L - elapsed)
                
                revealRunnable?.let { mainHandler.removeCallbacks(it) }
                
                val revealAction = Runnable {
                    binding.quickEmoteShimmer.root.visibility = View.GONE
                    binding.quickEmoteRecyclerView.visibility = View.VISIBLE
                    revealRunnable = null
                }
                revealRunnable = revealAction
                if (delay > 0) {
                    mainHandler.postDelayed(revealAction, delay)
                } else {
                    revealAction.run()
                }
            }
        } else {
            quickEmoteAdapter.onFirstImageReady = null
        }
        quickEmoteAdapter.setEmotes(finalEmotes)
        
        if (!showShimmer) {
            binding.quickEmoteRecyclerView.scrollToPosition(0)
        }
    }

    /**
     * Add an emote to the recent emotes list
     */
    fun addRecentEmote(emote: Emote) {
        val channelId = currentChannelId
        
        val channelRecent = if (channelId != null) prefs.getChannelRecentEmoteIds(channelId) else null
        val globalRecent = prefs.recentEmoteIds
        
        // Use channel specific list if exists, otherwise global list as base
        val effectiveRecent = channelRecent ?: globalRecent
        val currentIds = effectiveRecent?.split(",")?.toMutableList() ?: mutableListOf()
        
        val idStr = emote.id.toString()
        
        currentIds.remove(idStr)
        currentIds.add(0, idStr) // Most recent first
        
        val newRecent = currentIds.take(MAX_STORED_RECENTS).joinToString(",")
        
        // Always update global to keep it fresh
        prefs.recentEmoteIds = newRecent
        
        // Update Channel specific
        if (channelId != null) {
            prefs.setChannelRecentEmoteIds(channelId, newRecent)
        }
        
        // Refresh bar to move this emote to the front
        // Delay update to allow click animation to finish
        mainHandler.postDelayed({
            updateQuickEmoteBar(showShimmer = false)
        }, 250)

        // Both the bar and the emote panel funnel their clicks here, so refresh the panel's
        // recents tab from this one place.
        activity.emotePanelManager.refreshRecents()
    }
    
    /**
     * Update subscription status for the quick emote adapter
     */
    fun updateSubscriptionStatus(isSubscribed: Boolean) {
        if (isInitialized) {
            quickEmoteAdapter.setSubscriptionStatus(isSubscribed)
        }
    }
    
    /**
     * Update the current channel ID in the adapter
     */
    fun updateCurrentChannelId(channelId: Long?) {
        if (isInitialized) {
            quickEmoteAdapter.setCurrentChannelId(channelId)
        }
    }
    
    /**
     * Check if the quick emote bar is initialized
     */
    fun isBarInitialized(): Boolean = isInitialized
}
