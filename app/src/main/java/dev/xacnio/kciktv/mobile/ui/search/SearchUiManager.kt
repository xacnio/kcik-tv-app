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
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
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
        // Tapping the transparent scrim area dismisses the overlay
        binding.searchContainer.setOnClickListener { closeSearch() }

        // Panel rides above the keyboard: update paddingBottom as IME animates in/out
        ViewCompat.setOnApplyWindowInsetsListener(binding.searchContainer) { view, insets ->
            val imeHeight = insets.getInsets(WindowInsetsCompat.Type.ime()).bottom
            view.setPadding(0, 0, 0, imeHeight)
            insets
        }

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
        if (binding.searchContainer.visibility == View.VISIBLE) return
        binding.searchContainer.visibility = View.VISIBLE
        updateSearchHistoryUI()

        // Focus and show keyboard
        binding.searchInput.requestFocus()
        val imm = activity.getSystemService(android.content.Context.INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager
        imm.showSoftInput(binding.searchInput, android.view.inputmethod.InputMethodManager.SHOW_IMPLICIT)
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
                        closeSearch()
                        item.channelItem?.let { ch ->
                            activity.channelProfileManager.openChannelProfile(ch.slug)
                        }
                    }
                    "category" -> {
                        closeSearch()
                        item.categoryItem?.let { cat ->
                            activity.browseManager.openCategoryBySlug(cat.slug)
                        }
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
                                    prefs.addSearchHistoryEntry(AppPreferences.HistoryEntry(type = "channel", channelItem = item))
                                    closeSearch()
                                    activity.channelProfileManager.openChannelProfile(item.slug)
                                }
                                is SearchResultItem.CategoryResult -> {
                                    prefs.addSearchHistoryEntry(AppPreferences.HistoryEntry(type = "category", categoryItem = item))
                                    closeSearch()
                                    activity.browseManager.openCategoryBySlug(item.slug)
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
    fun closeSearch() {
        binding.searchContainer.visibility = View.GONE
        binding.searchInput.setText("")
        hideKeyboard()
    }

    fun handleBack(): Boolean {
        if (binding.searchContainer.visibility == View.VISIBLE) {
            closeSearch()
            return true
        }
        return false
    }
}
