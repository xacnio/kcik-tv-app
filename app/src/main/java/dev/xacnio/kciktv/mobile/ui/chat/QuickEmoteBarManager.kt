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
    private lateinit var quickEmoteAdapter: QuickEmoteAdapter
    private var isInitialized = false
    
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
    }

    /**
     * Update the quick emote bar with available emotes
     */
    fun updateQuickEmoteBar() {
        // Hide for logged out users - they can't send emotes anyway
        if (!prefs.isLoggedIn) {
            binding.quickEmoteBarContainer.visibility = View.GONE
            return
        }
        
        // Make sure container is visible for logged in users
        binding.quickEmoteBarContainer.visibility = View.VISIBLE
        
        if (!isInitialized || emoteCategories.isEmpty()) {
            binding.quickEmoteRecyclerView.visibility = View.GONE
            binding.quickEmoteShimmer.root.visibility = View.GONE
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
        }.take(15)

        // 3. Combine: Recent first, then others (limit total for performance/UI)
        val finalEmotes = (recentEmotes + allUsableEmotes).distinctBy { it.id }.take(40)

        quickEmoteAdapter.setEmotes(finalEmotes)
        binding.quickEmoteShimmer.root.visibility = View.GONE
        binding.quickEmoteRecyclerView.visibility = View.VISIBLE
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
        
        val newRecent = currentIds.take(20).joinToString(",")
        
        // Always update global to keep it fresh
        prefs.recentEmoteIds = newRecent
        
        // Update Channel specific
        if (channelId != null) {
            prefs.setChannelRecentEmoteIds(channelId, newRecent)
        }
        
        // Refresh bar to move this emote to the front
        // Delay update to allow click animation to finish
        mainHandler.postDelayed({
            updateQuickEmoteBar()
        }, 250)
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
