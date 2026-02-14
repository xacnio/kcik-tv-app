/**
 * File: EmotePanelManager.kt
 *
 * Description: Manages the Emote Picker panel interface.
 * It handles loading emote data, organizing emotes into tabs (Global, Channel, Emoji),
 * and managing the grid layout and user selection interactions.
 *
 * Author: Xacnio
 *
 */
package dev.xacnio.kciktv.mobile.ui.chat

import android.content.Context
import android.util.Log
import android.view.View
import android.view.inputmethod.InputMethodManager
import androidx.lifecycle.LifecycleCoroutineScope
import dev.xacnio.kciktv.mobile.MobilePlayerActivity
import dev.xacnio.kciktv.R
import dev.xacnio.kciktv.shared.data.repository.ChannelRepository
import dev.xacnio.kciktv.databinding.ActivityMobilePlayerBinding
import kotlinx.coroutines.launch
import dev.xacnio.kciktv.shared.ui.adapter.EmoteAdapter
import dev.xacnio.kciktv.shared.ui.utils.EmoteManager

/**
 * Manages the emote panel UI, loading emotes, and interactions.
 */
class EmotePanelManager(
    private val activity: MobilePlayerActivity,
    private val binding: ActivityMobilePlayerBinding,
    private val repository: ChannelRepository,
    private val lifecycleScope: LifecycleCoroutineScope
) {
    private companion object {
        const val TAG = "EmotePanelManager"
    }

    internal var emoteCategories: List<dev.xacnio.kciktv.shared.data.model.EmoteCategory> = emptyList()
    private var currentEmoteCategoryIndex = 0
    private var emoteNameToId: Map<String, Long> = emptyMap() // For quick emote name lookup
    private var isEmotePanelInitialized = false

    fun toggleEmotePanel(showKeyboardOnClose: Boolean = true) {
        val panel = binding.emotePanelContainer
        val isVisible = panel.visibility == View.VISIBLE
        
        if (isVisible) {
            // Panel closing -> Switch to Emoji icon
            binding.chatEmoteButton.setImageResource(R.drawable.ic_emoji)

            // Close panel
            panel.visibility = View.GONE
            
            if (showKeyboardOnClose) {
                // Open keyboard only if requested
                binding.chatInput.requestFocus()
                val imm = activity.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
                imm.showSoftInput(binding.chatInput, InputMethodManager.SHOW_IMPLICIT)
            }
        } else {
            // Panel opening -> Switch to Keyboard icon
            binding.chatEmoteButton.setImageResource(R.drawable.ic_keyboard)

            // Close keyboard, open panel
            val imm = activity.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
            imm.hideSoftInputFromWindow(binding.chatInput.windowToken, 0)
            
            panel.visibility = View.VISIBLE
            updatePanelHeight()
            activity.chatUiManager.forceScrollToBottomIfEnabled() // Fix chat jumping up
            
            if (!isEmotePanelInitialized) {
                setupEmotePanel()
            } else {
                updateSpanCount()
            }
            
            if (emoteCategories.isNotEmpty()) {
                populateEmotePanel()
            } else {
                val loadingContainer = panel.findViewById<View>(R.id.emoteLoadingContainer)
                if (loadingContainer != null) loadingContainer.visibility = View.VISIBLE
                
                // Retry loading emotes if list is empty
                activity.currentChannel?.slug?.let { slug ->
                     lifecycleScope.launch {
                        loadChannelEmotes(slug)
                    }
                }
            }
        }
    }
    
    private fun setupEmotePanel() {
        val panel = binding.emotePanelContainer
        val recyclerView =
            panel.findViewById<androidx.recyclerview.widget.RecyclerView>(R.id.emoteRecyclerView)
                ?: return

        // 1. Create Adapter & LayoutManager IMMEDIATELY (Before post)
        // This ensures adapter is not null when populateEmotePanel is called right after setup.
        val emoteAdapter = dev.xacnio.kciktv.shared.ui.adapter.EmoteAdapter { emote ->
            activity.appendEmoteToInput(binding.chatInput, emote)
            activity.addRecentEmote(emote)
        }
        recyclerView.adapter = emoteAdapter
        
        // Pass info
        emoteAdapter.setSubscriptionStatus(activity.chatStateManager.isSubscribedToCurrentChannel)
        val currentChannelIdLong = activity.currentChannel?.id?.toLongOrNull()
        emoteAdapter.setCurrentChannelId(currentChannelIdLong)

        // Initial Layout Manager (Default span 7)
        val initialSpanCount = 7
        val gridLayoutManager = androidx.recyclerview.widget.GridLayoutManager(activity, initialSpanCount)
        gridLayoutManager.spanSizeLookup = object : androidx.recyclerview.widget.GridLayoutManager.SpanSizeLookup() {
            override fun getSpanSize(position: Int): Int {
                return if (emoteAdapter.getItemViewType(position) == 1) gridLayoutManager.spanCount else 1
            }
        }
        recyclerView.layoutManager = gridLayoutManager

        // 2. Post block for Dynamic Width Calculations (Span Count & Padding)
        recyclerView.post {
            if (!recyclerView.isAttachedToWindow) return@post
            
            val width = recyclerView.width
            val displayMetrics = activity.resources.displayMetrics
            val effectiveWidth = if (width > 0) width else displayMetrics.widthPixels
            
            // Correct item width is 40dp + 2dp margin each side = 44dp
            val itemWidthPx = (44 * displayMetrics.density).toInt() 
            val spanCount = (effectiveWidth / itemWidthPx).coerceAtLeast(4)

            // Update Span Count
            if (gridLayoutManager.spanCount != spanCount) {
                gridLayoutManager.spanCount = spanCount
                // Re-trigger layout if span changed
                emoteAdapter.notifyDataSetChanged()
            }
            
            // Center items by adding padding
            val totalItemWidth = spanCount * itemWidthPx
            val remainingSpace = effectiveWidth - totalItemWidth
            val sidePadding = (remainingSpace / 2).coerceAtLeast(0)
            recyclerView.setPadding(sidePadding, 0, sidePadding, 0)
            recyclerView.clipToPadding = false
            
            // Populate initial data if empty (fallback)
             if (emoteCategories.isNotEmpty() && currentEmoteCategoryIndex < emoteCategories.size && emoteAdapter.itemCount == 0) {
                 emoteAdapter.setEmotes(emoteCategories[currentEmoteCategoryIndex].emotes)
            }
        }
        
        // Setup Delete (Backspace) Button
        panel.findViewById<View>(R.id.btnDeleteEmote)?.setOnClickListener {
            val event = android.view.KeyEvent(android.view.KeyEvent.ACTION_DOWN, android.view.KeyEvent.KEYCODE_DEL)
            binding.chatInput.dispatchKeyEvent(event)
        }
        
        isEmotePanelInitialized = true
    }

    private fun updatePanelHeight() {
        // Adjust height for landscape mode
        val config = activity.resources.configuration
        val isLandscape = config.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE
        
        val emotePickerLayout = binding.emotePanelContainer.findViewById<View>(R.id.emotePickerLayout) ?: return
        val params = emotePickerLayout.layoutParams as? android.view.ViewGroup.MarginLayoutParams ?: return
        
        if (isLandscape) {
             // Use 70% of screen height for landscape to maximize visibility
             val screenHeight = activity.resources.displayMetrics.heightPixels
             val height = (screenHeight * 0.70).toInt()
             params.height = height
             
             // Add bottom margin to avoid accidental seek bar touches
             params.bottomMargin = (24 * activity.resources.displayMetrics.density).toInt()
        } else {
             // Standard height for portrait
             params.height = (280 * activity.resources.displayMetrics.density).toInt()
             params.bottomMargin = 0
        }
        emotePickerLayout.layoutParams = params
    }

    private fun updateSpanCount() {
         val recyclerView = binding.emotePanelContainer.findViewById<androidx.recyclerview.widget.RecyclerView>(R.id.emoteRecyclerView) ?: return
         // Recalculate span count on layout updates (e.g. rotation)
         recyclerView.post {
             val width = recyclerView.width
             if (width <= 0) return@post
             
             val displayMetrics = activity.resources.displayMetrics
             // Correct item width is 40dp + 2dp margin each side = 44dp
             val itemWidthPx = (44 * displayMetrics.density).toInt()
             
             val spanCount = (width / itemWidthPx).coerceAtLeast(4)
             
             // Center items by adding padding
             val totalItemWidth = spanCount * itemWidthPx
             val remainingSpace = width - totalItemWidth
             val sidePadding = (remainingSpace / 2).coerceAtLeast(0)
             recyclerView.setPadding(sidePadding, 0, sidePadding, 0)
             recyclerView.clipToPadding = false
             
             val layoutManager = recyclerView.layoutManager as? androidx.recyclerview.widget.GridLayoutManager
             if (layoutManager != null && layoutManager.spanCount != spanCount) {
                  layoutManager.spanCount = spanCount
                  layoutManager.spanSizeLookup.invalidateSpanIndexCache()
                  // Update SpanSizeLookup to use new spanCount for headers
                  layoutManager.spanSizeLookup = object : androidx.recyclerview.widget.GridLayoutManager.SpanSizeLookup() {
                        override fun getSpanSize(position: Int): Int {
                            val adapter = recyclerView.adapter as? dev.xacnio.kciktv.shared.ui.adapter.EmoteAdapter
                            return if (adapter?.getItemViewType(position) == 1) spanCount else 1
                        }
                  }
                  recyclerView.adapter?.notifyDataSetChanged()
             }
         }
    }
    
    suspend fun loadChannelEmotes(slug: String) {
        val result = repository.getEmotes(slug, activity.prefs.authToken)
        result.onSuccess { categories ->
            this.emoteCategories = categories
            Log.d(TAG, "Stored ${categories.size} categories. Building name map...")
            val nameMap = mutableMapOf<String, Long>()
            categories.forEach { category ->
                category.emotes.forEach { emote ->
                    nameMap[emote.name] = emote.id
                }
            }
            emoteNameToId = nameMap
            Log.d(TAG, "Emote map built with ${nameMap.size} entries")
            
            // Pass emote map to ChatUiManager for manual typing support
            try {
                activity.chatUiManager.setEmoteMap(nameMap.mapValues { it.value.toString() })
            } catch (e: Exception) {
                Log.e(TAG, "Error setting emote map to ChatUiManager", e)
            }
            
            // Update UI if panel is initialized
            if (isEmotePanelInitialized) {
                activity.runOnUiThread {
                    populateEmotePanel()
                }
            }
            // Update Quick Emote Bar
            activity.runOnUiThread {
                activity.updateQuickEmoteBar()
            }
        }.onFailure {
            Log.e(TAG, "Failed to load emotes", it)
        }
    }

    private fun populateEmotePanel() {
        Log.d(TAG, "populateEmotePanel called with ${emoteCategories.size} categories")
        val panel = binding.emotePanelContainer
        val loadingContainer = panel.findViewById<View>(R.id.emoteLoadingContainer)
        val recyclerView = panel.findViewById<androidx.recyclerview.widget.RecyclerView>(R.id.emoteRecyclerView)
        val tabContainer = panel.findViewById<android.widget.LinearLayout>(R.id.emoteTabContainer)
        
        if (loadingContainer != null) loadingContainer.visibility = View.GONE
        if (recyclerView != null) recyclerView.visibility = View.VISIBLE
        if (tabContainer == null) {
            Log.e(TAG, "tabContainer is null in populateEmotePanel")
            return
        }
        
        tabContainer.removeAllViews()
        
        // Ensure adapter is ready
        var adapter = recyclerView?.adapter as? dev.xacnio.kciktv.shared.ui.adapter.EmoteAdapter
        if (adapter == null) {
            if (!isEmotePanelInitialized) setupEmotePanel()
            adapter = recyclerView?.adapter as? dev.xacnio.kciktv.shared.ui.adapter.EmoteAdapter
            if (adapter == null) return
        }
        
        // Initial data set
        if (emoteCategories.isNotEmpty() && currentEmoteCategoryIndex < emoteCategories.size) {
            adapter.setEmotes(emoteCategories[currentEmoteCategoryIndex].emotes)
        }
        
        // Build Tabs
        val size = (40 * activity.resources.displayMetrics.density).toInt()
        val padding = (8 * activity.resources.displayMetrics.density).toInt()

        emoteCategories.forEachIndexed { index, category ->
             val tabView = android.widget.ImageView(activity)
             tabView.layoutParams = android.widget.LinearLayout.LayoutParams(size, size)
             tabView.setPadding(padding, padding, padding, padding)
             tabView.scaleType = android.widget.ImageView.ScaleType.FIT_CENTER
             
             // Load category icon
             when {
                 category.isGlobal -> {
                     tabView.setImageResource(R.drawable.ic_globe)
                     tabView.setColorFilter(android.graphics.Color.WHITE)
                 }
                 category.isEmoji -> {
                     tabView.setImageResource(R.drawable.ic_emoji)
                     tabView.setColorFilter(android.graphics.Color.WHITE)
                 }
                 else -> {
                     val profilePic = category.profilePic
                     val firstEmoteId = category.emotes.firstOrNull()?.id
                     
                     if (profilePic != null) {
                         // Categories with profile pics (streamers) get circle crop
                         com.bumptech.glide.Glide.with(activity).load(profilePic).circleCrop().into(tabView)
                         tabView.clearColorFilter()
                     } else if (firstEmoteId != null) {
                         // Categories using first emote as icon get fully synchronized emote (no crop)
                         dev.xacnio.kciktv.shared.ui.utils.EmoteManager.loadSynchronizedEmote(
                             activity,
                             firstEmoteId.toString(),
                             size,
                             tabView
                         ) { drawable ->
                             tabView.setImageDrawable(drawable)
                             tabView.clearColorFilter()
                         }
                     }
                 }
             }
             
             // Selected state
             if (index == currentEmoteCategoryIndex) {
                 tabView.alpha = 1.0f
                 tabView.setBackgroundResource(R.drawable.bg_item_ripple)
             } else {
                 tabView.alpha = 0.5f
                 tabView.background = null
             }
             
             tabView.setOnClickListener {
                 currentEmoteCategoryIndex = index
                 
                 // Update adapter content
                 adapter.setEmotes(emoteCategories[index].emotes)
                 
                 // Update tab UI
                 for (i in 0 until tabContainer.childCount) {
                     val child = tabContainer.getChildAt(i)
                     if (i == index) {
                         child.alpha = 1.0f
                         child.setBackgroundResource(R.drawable.bg_item_ripple)
                     } else {
                         child.alpha = 0.5f
                         child.background = null
                     }
                 }
             }
             
             tabContainer.addView(tabView)
        }
    }

    fun updateSubscriptionStatus() {
        val panel = binding.emotePanelContainer
        val recyclerView = panel.findViewById<androidx.recyclerview.widget.RecyclerView>(R.id.emoteRecyclerView)
        val emoteAdapter = recyclerView?.adapter as? dev.xacnio.kciktv.shared.ui.adapter.EmoteAdapter
        emoteAdapter?.setSubscriptionStatus(activity.chatStateManager.isSubscribedToCurrentChannel)
    }

    fun updateCurrentChannelId(channelId: Long?) {
        val recyclerView = binding.emotePanelContainer.findViewById<androidx.recyclerview.widget.RecyclerView>(R.id.emoteRecyclerView)
        (recyclerView?.adapter as? dev.xacnio.kciktv.shared.ui.adapter.EmoteAdapter)?.setCurrentChannelId(channelId)
    }
}
