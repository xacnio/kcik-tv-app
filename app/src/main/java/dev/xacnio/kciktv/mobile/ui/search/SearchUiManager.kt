/**
 * File: SearchUiManager.kt
 *
 * Description: Manages business logic, state, and UI interactions for Search Ui.
 *
 * Author: Xacnio
 *
 */
package dev.xacnio.kciktv.mobile.ui.search

import android.util.Log
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.Toast
import androidx.recyclerview.widget.LinearLayoutManager
import dev.xacnio.kciktv.mobile.MobilePlayerActivity
import dev.xacnio.kciktv.R
import dev.xacnio.kciktv.shared.data.model.SearchResultItem
import dev.xacnio.kciktv.shared.data.prefs.AppPreferences
import dev.xacnio.kciktv.shared.ui.adapter.SearchResultAdapter
import com.google.android.material.bottomsheet.BottomSheetBehavior
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import androidx.lifecycle.lifecycleScope
import dev.xacnio.kciktv.shared.ui.adapter.SearchHistoryAdapter

class SearchUiManager(private val activity: MobilePlayerActivity) {

    private val binding = activity.binding
    private val prefs = activity.prefs
    private val repository = activity.repository
    private val lifecycleScope = activity.lifecycleScope
    
    private var searchJob: Job? = null
    private val TAG = "SearchUiManager"

    fun setupSearchListeners() {
        // Search Screen Bottom Nav handled by MobilePlayerActivity

        // Live search as user types (with debounce)
        binding.searchInput.addTextChangedListener(object : android.text.TextWatcher {
            private var searchRunnable: Runnable? = null
            
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            
            override fun afterTextChanged(s: android.text.Editable?) {
                searchRunnable?.let { activity.mainHandler.removeCallbacks(it) }
                
                val query = s?.toString()?.trim() ?: ""
                if (query.length >= 2) {
                    searchRunnable = Runnable { performEmbeddedSearch(query) }
                    activity.mainHandler.postDelayed(searchRunnable!!, 300) // 300ms debounce
                } else if (query.isEmpty()) {
                    updateSearchHistoryUI()
                    binding.searchResultsRecyclerView.visibility = View.GONE
                    binding.searchEmptyState.visibility = View.GONE
                    binding.searchLoading.visibility = View.GONE
                }
            }
        })

        binding.clearSearchHistoryButton.setOnClickListener {
            prefs.clearSearchHistory()
            updateSearchHistoryUI()
        }

        updateSearchHistoryUI() // Initial load

        binding.searchInput.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEARCH) {
                val query = binding.searchInput.text.toString().trim()
                if (query.isNotEmpty()) {
                    performEmbeddedSearch(query)
                }
                true
            } else false
        }
    }

    fun showSearchScreen() {
        binding.mobileHeader.visibility = View.VISIBLE
        binding.homeScreenContainer.root.visibility = View.GONE
        binding.browseScreenContainer.root.visibility = View.GONE
        binding.followingScreenContainer.root.visibility = View.GONE
        binding.categoryDetailsContainer.root.visibility = View.GONE
        
        // Only hide player if not in mini player mode
        if (!activity.miniPlayerManager.isMiniPlayerMode) {
            binding.playerScreenContainer.visibility = View.GONE
        }
        activity.updateNavigationBarColor(false) // Transparent for search screen
        
        binding.searchContainer.visibility = View.VISIBLE
        activity.setCurrentScreen(MobilePlayerActivity.AppScreen.SEARCH)
        
        // Focus on input
        binding.searchInput.requestFocus()
        val imm = activity.getSystemService(android.content.Context.INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager
        imm.showSoftInput(binding.searchInput, android.view.inputmethod.InputMethodManager.SHOW_IMPLICIT)
        
        // Sync Nav in search screen
        try {
            if (activity.binding.mainBottomNavigation.selectedItemId != R.id.nav_search) {
                activity.isNavigationProgrammatic = true
                activity.binding.mainBottomNavigation.selectedItemId = R.id.nav_search
                activity.isNavigationProgrammatic = false
            }
        } catch (e: Exception) {}
    }

    private fun hideKeyboard() {
        val imm = activity.getSystemService(android.content.Context.INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager
        imm.hideSoftInputFromWindow(binding.searchInput.windowToken, 0)
    }
    
    fun updateSearchHistoryUI() {
        val history = prefs.getSearchHistoryItems().take(10)
        
        if (history.isEmpty()) {
            binding.searchHistoryContainer.visibility = View.GONE
        } else {
            binding.searchHistoryContainer.visibility = View.VISIBLE
            binding.searchHistoryRecyclerView.layoutManager = LinearLayoutManager(activity)
            binding.searchHistoryRecyclerView.adapter = dev.xacnio.kciktv.shared.ui.adapter.SearchHistoryAdapter(history) { item ->
                when (item.type) {
                    "query" -> {
                        val query = item.query ?: ""
                        binding.searchInput.setText(query)
                        binding.searchInput.setSelection(query.length)
                        hideKeyboard()
                        performEmbeddedSearch(query)
                    }
                    "channel" -> {
                        item.channelItem?.let { ch ->
                            // Use modern channel profile system
                            activity.channelProfileManager.openChannelProfile(ch.slug) 
                        }
                        binding.searchContainer.visibility = View.GONE
                        hideKeyboard()
                    }
                    "category" -> {
                        item.categoryItem?.let { cat ->
                            activity.browseManager.openCategoryBySlug(cat.slug, fromSearch = true)
                        }
                        binding.searchContainer.visibility = View.GONE
                        hideKeyboard()
                    }
                }
            }
        }
        
        // Initial visibility states for other containers
        if (binding.searchLoading.visibility != View.VISIBLE) {
            binding.searchInitialState.visibility = if (history.isEmpty()) View.VISIBLE else View.GONE
        }
    }

    fun performEmbeddedSearch(query: String) {
        if (query.isBlank()) {
            updateSearchHistoryUI()
            binding.searchResultsRecyclerView.visibility = View.GONE
            binding.searchEmptyState.visibility = View.GONE
            binding.searchLoading.visibility = View.GONE
            return
        }
        
        binding.searchInitialState.visibility = View.GONE
        binding.searchHistoryContainer.visibility = View.GONE
        binding.searchLoading.visibility = View.VISIBLE
        binding.searchResultsRecyclerView.visibility = View.GONE
        binding.searchEmptyState.visibility = View.GONE

        // Cancel previous search
        searchJob?.cancel()
        
        searchJob = lifecycleScope.launch {
            // Log analytics event (anonymous - no search terms)
            activity.analytics.logSearchPerformed()
            
            val result = repository.searchChannels(query)
            
            result.onSuccess { searchResults ->
                activity.runOnUiThread {
                    binding.searchLoading.visibility = View.GONE
                    if (searchResults.isEmpty()) {
                        binding.searchEmptyState.visibility = View.VISIBLE
                    } else {
                        binding.searchResultsRecyclerView.visibility = View.VISIBLE
                        binding.searchResultsRecyclerView.layoutManager = LinearLayoutManager(activity)
                        binding.searchResultsRecyclerView.adapter = dev.xacnio.kciktv.shared.ui.adapter.SearchResultAdapter(searchResults, activity.prefs.themeColor) { item ->
                            hideKeyboard()
                            when (item) {
                                is SearchResultItem.ChannelResult -> {
                                    binding.searchInput.setText("")
                                    prefs.addSearchHistoryEntry(AppPreferences.HistoryEntry(type = "channel", channelItem = item))
                                    // Use modern channel profile system
                                    activity.channelProfileManager.openChannelProfile(item.slug)
                                    binding.searchContainer.visibility = View.GONE
                                    // Note: we can't access bottomSheetBehavior directly unless exposed.
                                    // But usually search is full screen. If it was inside bottom sheet, we would need to know.
                                    // MobilePlayerActivity:3396: bottomSheetBehavior.state = BottomSheetBehavior.STATE_HIDDEN
                                    // Let's assume searchContainer closing is enough or expose bottomSheetBehavior.
                                }
                                is SearchResultItem.CategoryResult -> {
                                    binding.searchInput.setText("")
                                    prefs.addSearchHistoryEntry(AppPreferences.HistoryEntry(type = "category", categoryItem = item))
                                    activity.browseManager.openCategoryBySlug(item.slug, fromSearch = true)
                                    binding.searchContainer.visibility = View.GONE
                                }
                                is SearchResultItem.TagResult -> {
                                    Toast.makeText(activity, activity.getString(R.string.tag_format, item.label), Toast.LENGTH_SHORT).show()
                                }
                            }
                        }
                    }
                }
            }.onFailure { error ->
                activity.runOnUiThread {
                    binding.searchLoading.visibility = View.GONE
                    binding.searchEmptyState.visibility = View.VISIBLE
                    Log.e(TAG, "Search failed: ${error.message}")
                }
            }
        }
    }
    fun handleBack(): Boolean {
        if (activity.miniPlayerManager.isMiniPlayerMode) return false
        
        if (binding.searchContainer.visibility == View.VISIBLE) {
            binding.searchContainer.visibility = View.GONE
            hideKeyboard()
            activity.showHomeScreen()
            return true
        }
        return false
    }
}
