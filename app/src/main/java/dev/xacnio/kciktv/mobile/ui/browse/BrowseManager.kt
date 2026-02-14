/**
 * File: BrowseManager.kt
 *
 * Description: Orchestrates the main browsing experience, managing distinct tabs for Live Channels,
 * Categories, and Clips. It handles data fetching for top categories and channels, manages pagination
 * for infinite scrolling, and coordinates the display of content across different browse sections.
 *
 * Author: Xacnio
 *
 */
package dev.xacnio.kciktv.mobile.ui.browse

import android.graphics.Color
import android.util.Log
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import dev.xacnio.kciktv.mobile.MobilePlayerActivity
import dev.xacnio.kciktv.R
import dev.xacnio.kciktv.shared.data.model.ChannelItem
import dev.xacnio.kciktv.shared.data.model.TopCategory
import dev.xacnio.kciktv.shared.util.CategoryUtils
import dev.xacnio.kciktv.shared.util.ShimmerDrawable
import dev.xacnio.kciktv.shared.ui.widget.ShimmerLayout
import dev.xacnio.kciktv.shared.util.dpToPx
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Job
import com.google.android.material.bottomsheet.BottomSheetDialog
import dev.xacnio.kciktv.shared.data.model.ChannelClip
import dev.xacnio.kciktv.shared.ui.adapter.ChannelClipAdapter
import dev.xacnio.kciktv.shared.util.ThumbnailCacheHelper

class BrowseManager(private val activity: MobilePlayerActivity) {
    private val binding = activity.binding
    private val prefs = activity.prefs
    private val repository = activity.repository

    private var currentBrowseCategoryPage = 1
    private var isBrowseCategoriesLoading = false
    private var hasMoreBrowseCategories = true
    private var browseCategoriesList = mutableListOf<TopCategory?>()
    private var browseCategoriesJob: Job? = null
    private var browseCategoriesAdapter: RecyclerView.Adapter<*>? = null
    private var followedCategorySlugs = mutableSetOf<String>()
    private var isFollowedCategoriesFetched = false

    private var nextBrowseLiveCursor: String? = null
    private var isBrowseLiveLoading = false
    private var hasMoreBrowseLive = true
    private var browseLiveChannelsList = mutableListOf<ChannelItem>()
    private var browseLiveAdapter: RecyclerView.Adapter<*>? = null
    
    // Category Details State
    private var currentCategoryChannels = listOf<ChannelItem>()
    private var isCategoryGridMode = false
    private var isCategoryFilterButtonAdded = false
    private var openedCategory: TopCategory? = null
    private var currentCategorySort = "featured"
    private var nextCategoryCursor: String? = null
    private var hasMoreCategoryStreams = true
    private var isCategoryStreamsLoading = false
    private val categoryCache = mutableMapOf<String, TopCategory>()

    var isBrowseGridMode = activity.resources.getBoolean(R.bool.is_tablet) || (activity.prefs.mobileLayoutMode == "grid")
    var isCategoryDetailsVisible = false
    private var currentBrowseTab = 1 // 1: Categories default
    private var currentBrowseLiveSort = "featured"
    private var categoryDetailsOpenedFromHome = false // Track where we came from

    // Category Tabs State
    private var categoryClipsAdapter: dev.xacnio.kciktv.shared.ui.adapter.ChannelClipAdapter? = null
    private var categoryClipsList = mutableListOf<dev.xacnio.kciktv.shared.data.model.ChannelClip>()
    private var isClipsLoaded = false
    private var currentCategoryTab = 0 // 0: Live, 1: Clips
    private var currentCategoryClipsSort = "view" // date, view
    private var currentCategoryClipsTime = "day"  // day, week, month, all
    private var nextCategoryClipsCursor: String? = null
    private var hasMoreCategoryClips = true
    private var isCategoryClipsLoading = false

    private val TAG = "BrowseManager"
    private var lastConfiguration: android.content.res.Configuration? = null

    private fun addCategoryFilterButton() {
        if (isCategoryFilterButtonAdded) return
        
        val root = binding.categoryDetailsContainer.root as? android.view.ViewGroup ?: return
        val context = activity
        
        val filterBtn = ImageView(context).apply {
            tag = "cat_details_filter_btn"
            setImageResource(R.drawable.ic_sort) // Using ic_sort as filter list is missing
            setColorFilter(Color.WHITE)
            setBackgroundResource(R.drawable.bg_item_ripple)
            
            val p = 8.dpToPx(activity.resources)
            setPadding(p, p, p, p)
            
            setOnClickListener {
                showCategoryFilterDialog()
            }
        }
        
        val params = android.widget.FrameLayout.LayoutParams(40.dpToPx(activity.resources), 40.dpToPx(activity.resources))
        params.gravity = android.view.Gravity.TOP or android.view.Gravity.END
        // Adjust margins to align with Back button (usually 16dp margin, + statusBar)
        // Assuming back button is TOP|START with similar margins
        params.topMargin = 12.dpToPx(activity.resources) 
        params.rightMargin = 16.dpToPx(activity.resources)
        
        filterBtn.layoutParams = params
        
        // Check if view with tag exists
        if (root.findViewWithTag<View>("cat_details_filter_btn") == null) {
            root.addView(filterBtn)
            isCategoryFilterButtonAdded = true
        }
    }

    private fun showCategoryFilterDialog() {

        val layoutRes = if (currentCategoryTab == 1) R.layout.bottom_sheet_clips_filter else R.layout.bottom_sheet_browse_filter
        val dialogView = activity.layoutInflater.inflate(layoutRes, null)
        val dialog = com.google.android.material.bottomsheet.BottomSheetDialog(activity)
        dialog.setContentView(dialogView)
        dialog.behavior.state = com.google.android.material.bottomsheet.BottomSheetBehavior.STATE_EXPANDED
        dialog.behavior.skipCollapsed = true
        
        // --- Shared View Mode Logic ---
        val viewModeGroup = dialogView.findViewById<android.widget.RadioGroup>(R.id.viewModeGroup)
        val viewModeLabel = dialogView.findViewById<TextView>(R.id.viewModeLabel)
        val radioList = dialogView.findViewById<android.widget.RadioButton>(R.id.radioList)
        val radioGrid = dialogView.findViewById<android.widget.RadioButton>(R.id.radioGrid)
        
        val isTablet = activity.resources.getBoolean(R.bool.is_tablet)
        if (isTablet) {
            // Hide View Mode options on tablet (always grid)
            viewModeGroup?.visibility = View.GONE
            viewModeLabel?.visibility = View.GONE
        } else {
            if (isCategoryGridMode) {
                radioGrid?.isChecked = true
            } else {
                radioList?.isChecked = true
            }
            
            viewModeGroup?.setOnCheckedChangeListener { _, checkedId ->
                isCategoryGridMode = checkedId == R.id.radioGrid
                prefs.mobileLayoutMode = if (isCategoryGridMode) "grid" else "list"
                
                // Refresh Live Streams listing
                setupBrowseChannelAdapter(binding.categoryDetailsContainer.categoryStreamsRecycler, currentCategoryChannels, isCategoryGridMode)
                
                // Refresh Clips listing and shimmer if loaded
                setupClipsLayoutManager(isCategoryGridMode)
                setupClipsShimmer(isCategoryGridMode)
                if (categoryClipsAdapter != null) {
                    // Clear recycled view pool to force new layout inflation
                    binding.categoryDetailsContainer.categoryClipsRecycler.recycledViewPool.clear()
                    categoryClipsAdapter?.updateData(categoryClipsList, isCategoryGridMode)
                }
                
                dialog.dismiss()
            }
        }

        if (currentCategoryTab == 1) {
            // --- Clips Specific Logic ---
            
            // Sorting Chips
            val chipDate = dialogView.findViewById<com.google.android.material.chip.Chip>(R.id.chipSortDate)
            val chipViews = dialogView.findViewById<com.google.android.material.chip.Chip>(R.id.chipSortViews)
            
            when (currentCategoryClipsSort) {
                "date" -> chipDate?.isChecked = true
                "view" -> chipViews?.isChecked = true
            }
            
            chipDate?.setOnClickListener {
                if (currentCategoryClipsSort != "date") {
                    currentCategoryClipsSort = "date"
                    dialog.dismiss()
                    openedCategory?.let { loadCategoryClips(it.slug) }
                }
            }
            
            chipViews?.setOnClickListener {
                if (currentCategoryClipsSort != "view") {
                    currentCategoryClipsSort = "view"
                    dialog.dismiss()
                    openedCategory?.let { loadCategoryClips(it.slug) }
                }
            }
            
            // Time Chips
            val chipDay = dialogView.findViewById<com.google.android.material.chip.Chip>(R.id.chipTimeDay)
            val chipWeek = dialogView.findViewById<com.google.android.material.chip.Chip>(R.id.chipTimeWeek)
            val chipMonth = dialogView.findViewById<com.google.android.material.chip.Chip>(R.id.chipTimeMonth)
            val chipAll = dialogView.findViewById<com.google.android.material.chip.Chip>(R.id.chipTimeAll)
            
            when (currentCategoryClipsTime) {
                "day" -> chipDay?.isChecked = true
                "week" -> chipWeek?.isChecked = true
                "month" -> chipMonth?.isChecked = true
                "all" -> chipAll?.isChecked = true
            }
            
            chipDay?.setOnClickListener {
                if (currentCategoryClipsTime != "day") {
                    currentCategoryClipsTime = "day"
                    dialog.dismiss()
                    openedCategory?.let { loadCategoryClips(it.slug) }
                }
            }
            
            chipWeek?.setOnClickListener {
                if (currentCategoryClipsTime != "week") {
                    currentCategoryClipsTime = "week"
                    dialog.dismiss()
                    openedCategory?.let { loadCategoryClips(it.slug) }
                }
            }
            
            chipMonth?.setOnClickListener {
                if (currentCategoryClipsTime != "month") {
                    currentCategoryClipsTime = "month"
                    dialog.dismiss()
                    openedCategory?.let { loadCategoryClips(it.slug) }
                }
            }
            
            chipAll?.setOnClickListener {
                if (currentCategoryClipsTime != "all") {
                    currentCategoryClipsTime = "all"
                    dialog.dismiss()
                    openedCategory?.let { loadCategoryClips(it.slug) }
                }
            }
            
        } else {
            // --- Live Streams Specific Logic ---
            
            // Sort
            val sortGroup = dialogView.findViewById<android.widget.RadioGroup>(R.id.sortGroup)
            val sortRecommended = dialogView.findViewById<android.widget.RadioButton>(R.id.sortRecommended)
            val sortViewersDesc = dialogView.findViewById<android.widget.RadioButton>(R.id.sortViewersDesc)
            val sortViewersAsc = dialogView.findViewById<android.widget.RadioButton>(R.id.sortViewersAsc)
            
            when (currentCategorySort) {
                "featured" -> sortRecommended?.isChecked = true
                "viewer_count_desc" -> sortViewersDesc?.isChecked = true
                "viewer_count_asc" -> sortViewersAsc?.isChecked = true
                else -> sortRecommended?.isChecked = true
            }
            
            sortGroup?.setOnCheckedChangeListener { _, checkedId ->
                val newSort = when (checkedId) {
                    R.id.sortRecommended -> "featured"
                    R.id.sortViewersDesc -> "viewer_count_desc"
                    R.id.sortViewersAsc -> "viewer_count_asc"
                    else -> "featured"
                }
                if (newSort != currentCategorySort) {
                    currentCategorySort = newSort
                    loadCategoryStreams() 
                    dialog.dismiss()
                }
            }

            // Languages
            val chipGroup = dialogView.findViewById<com.google.android.material.chip.ChipGroup>(R.id.languageChipGroup)
            if (chipGroup != null) {
                val languageCodes = activity.resources.getStringArray(R.array.stream_language_codes)
                val languageNames = activity.resources.getStringArray(R.array.stream_language_names)
                
                val selectedLanguages = prefs.streamLanguages.toMutableSet()
                
                chipGroup.visibility = View.VISIBLE
                chipGroup.removeAllViews() // Clear to avoid duplicates
                
                languageCodes.zip(languageNames).forEach { (code, name) ->
                    val chip = com.google.android.material.chip.Chip(activity).apply {
                        text = name
                        isCheckable = true
                        isChecked = selectedLanguages.contains(code)
                        setChipBackgroundColorResource(R.color.chip_background_color)
                        setTextColor(android.graphics.Color.WHITE)
                        chipStrokeWidth = 0f
                        
                        setOnCheckedChangeListener { _, isChecked ->
                            if (isChecked) {
                                selectedLanguages.add(code)
                            } else {
                                selectedLanguages.remove(code)
                            }
                            prefs.streamLanguages = selectedLanguages
                            loadCategoryStreams()
                        }
                    }
                    chipGroup.addView(chip)
                }
            }
        }
        
        dialog.show()
    }
    
    private fun loadCategoryStreams(isLoadMore: Boolean = false) {
        val category = openedCategory ?: return
        if (prefs.isCategoryBlocked(category.name)) return
        if (isCategoryStreamsLoading) return
        if (isLoadMore && !hasMoreCategoryStreams) return
        
        isCategoryStreamsLoading = true
        
        if (!isLoadMore) {
             nextCategoryCursor = null
             hasMoreCategoryStreams = true
             binding.categoryDetailsContainer.categoryStreamsRecycler.visibility = View.GONE
             binding.categoryDetailsContainer.categoryStreamsLoadingMore.visibility = View.GONE
             setupCategoryStreamsShimmer(isCategoryGridMode)
             binding.categoryDetailsContainer.categoryDetailsShimmer.root.visibility = View.VISIBLE
             
             // Clear list if reloading
             currentCategoryChannels = emptyList()
             val catName = CategoryUtils.getLocalizedCategoryName(activity, category.name, category.slug)
             setupBrowseChannelAdapter(binding.categoryDetailsContainer.categoryStreamsRecycler, emptyList(), isCategoryGridMode, currentCategorySort, openedCategory?.id, catName, cursorProvider = { nextCategoryCursor })
        } else {
             // Show loading spinner for pagination
             binding.categoryDetailsContainer.categoryStreamsLoadingMore.visibility = View.VISIBLE
        }

        activity.lifecycleScope.launch(Dispatchers.IO) {
            try {
                val langs = prefs.streamLanguages.toList()
                // Use sorted by and 'after' cursor
                val result = repository.getFilteredLiveStreams(
                     categoryId = category.id, 
                     languages = if (langs.isNotEmpty()) langs else null,
                     sort = currentCategorySort,
                     after = if (isLoadMore) nextCategoryCursor else null
                )
                
                val response = result.getOrNull()
                val channels = response?.channels ?: emptyList()
                val nextCursor = response?.nextCursor // Assuming cursor field name
                
                withContext(Dispatchers.Main) {
                    binding.categoryDetailsContainer.categoryDetailsShimmer.root.visibility = View.GONE
                    binding.categoryDetailsContainer.categoryDetailsContent.visibility = View.VISIBLE
                    binding.categoryDetailsContainer.categoryStreamsRecycler.visibility = View.VISIBLE
                    
                    if (!isLoadMore) {
                        currentCategoryChannels = channels
                        if (channels.isEmpty()) {
                            binding.categoryDetailsContainer.categoryEmptyState.visibility = View.VISIBLE
                            binding.categoryDetailsContainer.emptyStateText.text = activity.getString(R.string.category_no_streams)
                            binding.categoryDetailsContainer.emptyStateIcon.setImageResource(R.drawable.ic_no_channels)
                        } else {
                            binding.categoryDetailsContainer.categoryEmptyState.visibility = View.GONE
                        }
                        val catName = CategoryUtils.getLocalizedCategoryName(activity, category.name, category.slug)
                        setupBrowseChannelAdapter(binding.categoryDetailsContainer.categoryStreamsRecycler, currentCategoryChannels, isCategoryGridMode, currentCategorySort, openedCategory?.id, catName, cursorProvider = { nextCategoryCursor })
                        setupStreamsScrollListener()
                    } else if (channels.isNotEmpty()) {
                        currentCategoryChannels = currentCategoryChannels + channels
                        val catName = CategoryUtils.getLocalizedCategoryName(activity, category.name, category.slug)
                        
                        // Save scroll state
                        val recyclerView = binding.categoryDetailsContainer.categoryStreamsRecycler
                        val state = recyclerView.layoutManager?.onSaveInstanceState()
                        
                        setupBrowseChannelAdapter(binding.categoryDetailsContainer.categoryStreamsRecycler, currentCategoryChannels, isCategoryGridMode, currentCategorySort, openedCategory?.id, catName, cursorProvider = { nextCategoryCursor })
                        
                        // Restore scroll state
                        if (state != null) {
                            recyclerView.layoutManager?.onRestoreInstanceState(state)
                        }
                        
                        setupStreamsScrollListener()
                    }

                    // Update cursor logic
                    if (nextCursor != null && channels.isNotEmpty()) {
                         nextCategoryCursor = nextCursor
                         hasMoreCategoryStreams = true
                    } else {
                         hasMoreCategoryStreams = false
                    }
                    
                    isCategoryStreamsLoading = false
                    binding.categoryDetailsContainer.categoryStreamsLoadingMore.visibility = View.GONE
                    binding.categoryDetailsContainer.categoryDetailsSwipeRefresh.isRefreshing = false
                }
            } catch (e: Exception) {
                 withContext(Dispatchers.Main) {
                     isCategoryStreamsLoading = false
                     binding.categoryDetailsContainer.categoryStreamsLoadingMore.visibility = View.GONE
                     binding.categoryDetailsContainer.categoryDetailsSwipeRefresh.isRefreshing = false
                     binding.categoryDetailsContainer.categoryDetailsShimmer.root.visibility = View.GONE
                     binding.categoryDetailsContainer.categoryDetailsContent.visibility = View.VISIBLE
                 }

            }
        }
    }

    fun setupClickListeners() {
        val browseBinding = binding.browseScreenContainer
        
        browseBinding.tabLive.setOnClickListener { switchBrowseTab(0) }
        browseBinding.tabCategories.setOnClickListener { switchBrowseTab(1) }
        browseBinding.tabClips.setOnClickListener { switchBrowseTab(2) }
        
        browseBinding.browseFilterButton.setOnClickListener {
             if (currentBrowseTab == 2) {
                 // Clips tab - show clips filter
                 activity.browseClipsManager.showClipsFilterDialog()
             } else {
                 // Live tab - show stream filter
                 showStreamFilterDialog()
             }
        }

        browseBinding.browseBlockedManagerBtn.setOnClickListener {
            showBlockedCategoriesDialog()
        }
        
        browseBinding.browseSwipeRefresh.setOnRefreshListener {
            when (currentBrowseTab) {
                0 -> loadBrowseLiveChannels()
                1 -> loadBrowseData()
                2 -> activity.browseClipsManager.loadBrowseClips()
                else -> browseBinding.browseSwipeRefresh.isRefreshing = false
            }
        }
        
        binding.categoryDetailsContainer.backButton.setOnClickListener {
            closeCategoryDetails()
        }

        binding.categoryDetailsContainer.categoryDetailsSwipeRefresh.setOnRefreshListener {
            val slug = openedCategory?.slug ?: return@setOnRefreshListener
            if (currentCategoryTab == 0) {
                loadCategoryStreams()
            } else {
                loadCategoryClips(slug)
            }
        }
    }

    private fun showStreamFilterDialog() {
        val dialogView = activity.layoutInflater.inflate(R.layout.bottom_sheet_browse_filter, null)
        val dialog = com.google.android.material.bottomsheet.BottomSheetDialog(activity)
        dialog.setContentView(dialogView)
        dialog.behavior.state = com.google.android.material.bottomsheet.BottomSheetBehavior.STATE_EXPANDED
        dialog.behavior.skipCollapsed = true
        
        // View Mode
        val viewModeGroup = dialogView.findViewById<android.widget.RadioGroup>(R.id.viewModeGroup)
        val viewModeLabel = dialogView.findViewById<TextView>(R.id.viewModeLabel)
        val radioList = dialogView.findViewById<android.widget.RadioButton>(R.id.radioList)
        val radioGrid = dialogView.findViewById<android.widget.RadioButton>(R.id.radioGrid)
        
        val isTablet = activity.resources.getBoolean(R.bool.is_tablet)
        if (isTablet) {
            viewModeGroup?.visibility = View.GONE
            viewModeLabel?.visibility = View.GONE
        } else {
             if (isBrowseGridMode) {
                radioGrid.isChecked = true
            } else {
                radioList.isChecked = true
            }
            
            viewModeGroup.setOnCheckedChangeListener { _, checkedId ->
                isBrowseGridMode = checkedId == R.id.radioGrid
                // Refresh the live channels list with new view mode
                if (browseLiveChannelsList.isNotEmpty()) {
                    val recycler = binding.browseScreenContainer.browseLiveChannelsRecycler
                    setupBrowseChannelAdapter(recycler, browseLiveChannelsList, isBrowseGridMode, currentBrowseLiveSort, null, null, cursorProvider = { nextBrowseLiveCursor })
                }
            }
        }
        
        // Sort
        val sortGroup = dialogView.findViewById<android.widget.RadioGroup>(R.id.sortGroup)
        val sortRecommended = dialogView.findViewById<android.widget.RadioButton>(R.id.sortRecommended)
        val sortViewersDesc = dialogView.findViewById<android.widget.RadioButton>(R.id.sortViewersDesc)
        val sortViewersAsc = dialogView.findViewById<android.widget.RadioButton>(R.id.sortViewersAsc)
        
        when (currentBrowseLiveSort) {
            "featured" -> sortRecommended.isChecked = true
            "viewer_count_desc" -> sortViewersDesc.isChecked = true
            "viewer_count_asc" -> sortViewersAsc.isChecked = true
            else -> sortRecommended.isChecked = true
        }
        
        sortGroup.setOnCheckedChangeListener { _, checkedId ->
            val newSort = when (checkedId) {
                R.id.sortRecommended -> "featured"
                R.id.sortViewersDesc -> "viewer_count_desc"
                R.id.sortViewersAsc -> "viewer_count_asc"
                else -> "featured"
            }
            if (newSort != currentBrowseLiveSort) {
                currentBrowseLiveSort = newSort
                loadBrowseLiveChannels() // Refresh with new sort
            }
        }
        
        // Language - Load from strings.xml arrays
        val chipGroup = dialogView.findViewById<com.google.android.material.chip.ChipGroup>(R.id.languageChipGroup)
        val languageCodes = activity.resources.getStringArray(R.array.stream_language_codes)
        val languageNames = activity.resources.getStringArray(R.array.stream_language_names)
        
        val selectedLanguages = prefs.streamLanguages.toMutableSet()
        
        languageCodes.zip(languageNames).forEach { (code, name) ->
            val chip = com.google.android.material.chip.Chip(activity).apply {
                text = name
                isCheckable = true
                isChecked = selectedLanguages.contains(code)
                setChipBackgroundColorResource(R.color.chip_background_color)
                setTextColor(android.graphics.Color.WHITE)
                chipStrokeWidth = 0f
                
                setOnCheckedChangeListener { _, isChecked ->
                    if (isChecked) {
                        selectedLanguages.add(code)
                    } else {
                        selectedLanguages.remove(code)
                    }
                    prefs.streamLanguages = selectedLanguages
                    loadBrowseLiveChannels() // Refresh with new language filter
                }
            }
            chipGroup.addView(chip)
        }
        
        dialog.show()
    }

    fun showBrowseScreen(initialTab: Int = 0) {
        if (!activity.miniPlayerManager.isMiniPlayerMode) {
            activity.ivsPlayer?.pause()
        }
        
        binding.mobileHeader.visibility = View.VISIBLE
        binding.browseScreenContainer.root.visibility = View.VISIBLE
        binding.homeScreenContainer.root.visibility = View.GONE
        binding.followingScreenContainer.root.visibility = View.GONE
        binding.searchContainer.visibility = View.GONE
        binding.channelProfileContainer.root.visibility = View.GONE
        if (!activity.miniPlayerManager.isMiniPlayerMode) {
            binding.playerScreenContainer.visibility = View.GONE
        }
        activity.updateNavigationBarColor(false) // Transparent for browse screen
        activity.isHomeScreenVisible = false
        activity.setCurrentScreen(MobilePlayerActivity.AppScreen.BROWSE)
        
        if (activity.binding.mainBottomNavigation.selectedItemId != R.id.nav_browse) {
            activity.isNavigationProgrammatic = true
            activity.binding.mainBottomNavigation.selectedItemId = R.id.nav_browse
            activity.isNavigationProgrammatic = false
        }
        
        switchBrowseTab(initialTab)
    }

    fun switchBrowseTab(index: Int) {
        if (currentBrowseTab == index && index != 0) return
        currentBrowseTab = index
        
        val themeColor = prefs.themeColor
        val grey = Color.parseColor("#AAAAAA")
        val browseBinding = binding.browseScreenContainer
        
        browseBinding.tabLive.setTextColor(grey)
        browseBinding.tabLiveIndicator.visibility = View.INVISIBLE
        browseBinding.tabCategories.setTextColor(grey)
        browseBinding.tabCategoriesIndicator.visibility = View.INVISIBLE
        browseBinding.tabClips.setTextColor(grey)
        browseBinding.tabClipsIndicator.visibility = View.INVISIBLE
        
        browseBinding.tabLiveIndicator.setBackgroundColor(themeColor)
        browseBinding.tabCategoriesIndicator.setBackgroundColor(themeColor)
        browseBinding.tabClipsIndicator.setBackgroundColor(themeColor)
        
        browseBinding.browseCategoriesRecycler.visibility = View.GONE
        browseBinding.browseLiveChannelsRecycler.visibility = View.GONE
        browseBinding.browseClipsRecycler.visibility = View.GONE
        browseBinding.browseClipsEmptyState.visibility = View.GONE
        
        when (index) {
            0 -> { // Live
                browseBinding.tabLive.setTextColor(themeColor)
                browseBinding.tabLiveIndicator.visibility = View.VISIBLE
                browseBinding.browseLiveChannelsRecycler.visibility = View.VISIBLE
                browseBinding.browseLiveChannelsRecycler.requestApplyInsets()
                if (browseLiveChannelsList.isEmpty()) {
                    loadBrowseLiveChannels()
                }
            }
            1 -> { // Categories
                browseBinding.tabCategories.setTextColor(themeColor)
                browseBinding.tabCategoriesIndicator.visibility = View.VISIBLE
                browseBinding.browseCategoriesRecycler.visibility = View.VISIBLE
                browseBinding.browseCategoriesRecycler.requestApplyInsets()
                if (browseBinding.browseCategoriesRecycler.adapter == null || browseBinding.browseCategoriesRecycler.adapter?.itemCount == 0) {
                     loadBrowseData()
                }
            }
            2 -> { // Clips
                browseBinding.tabClips.setTextColor(themeColor)
                browseBinding.tabClipsIndicator.visibility = View.VISIBLE
                browseBinding.browseClipsRecycler.visibility = View.VISIBLE
                browseBinding.browseClipsRecycler.requestApplyInsets()
                if (!activity.browseClipsManager.hasData()) {
                    activity.browseClipsManager.loadBrowseClips()
                }
            }
        }
        
        // Show filter button for Live and Clips tabs
        browseBinding.browseFilterButton.visibility = if (index == 0 || index == 2) View.VISIBLE else View.GONE
        browseBinding.browseBlockedManagerBtn.visibility = if (index == 1) View.VISIBLE else View.GONE
    }

    fun loadBrowseLiveChannels(isLoadMore: Boolean = false) {
        if (isBrowseLiveLoading) return
        if (isLoadMore && !hasMoreBrowseLive) return
        
        isBrowseLiveLoading = true
        if (!isLoadMore) {
            val isRefreshing = binding.browseScreenContainer.browseSwipeRefresh.isRefreshing
            if (!isRefreshing) {
                updateBrowseShimmer(true, if (isBrowseGridMode) "live_grid" else "live_list")
            }
            nextBrowseLiveCursor = null
            hasMoreBrowseLive = true
        }
        
        activity.lifecycleScope.launch(Dispatchers.IO) {
            try {
                val langs = prefs.streamLanguages.toList()
                val result = repository.getFilteredLiveStreams(
                    sort = currentBrowseLiveSort, 
                    languages = if (langs.isNotEmpty()) langs else null,
                    after = nextBrowseLiveCursor
                )
                val response = result.getOrNull()
                val channels = response?.channels ?: emptyList()
                
                withContext(Dispatchers.Main) {
                     if (!isLoadMore) {
                         browseLiveChannelsList.clear()
                         val blocked = prefs.blockedCategories
                         val filteredChannels = channels.filter { !blocked.contains(it.categoryName) }
                         browseLiveChannelsList.addAll(filteredChannels)
                         val recycler = binding.browseScreenContainer.browseLiveChannelsRecycler
                         setupBrowseChannelAdapter(recycler, browseLiveChannelsList, isBrowseGridMode, currentBrowseLiveSort, null, null, cursorProvider = { nextBrowseLiveCursor })
                     } else if (channels.isNotEmpty()) {
                         val blocked = prefs.blockedCategories
                         val filteredChannels = channels.filter { !blocked.contains(it.categoryName) }
                         
                         val existingIds = browseLiveChannelsList.map { it.id }.toSet()
                         val uniqueChannels = filteredChannels.filter { it.id !in existingIds }
                         
                         if (uniqueChannels.isNotEmpty()) {
                             val startPos = browseLiveChannelsList.size
                             browseLiveChannelsList.addAll(uniqueChannels)
                             browseLiveAdapter?.notifyItemRangeInserted(startPos, uniqueChannels.size)
                         }
                     }
                     
                     nextBrowseLiveCursor = response?.nextCursor
                     hasMoreBrowseLive = !nextBrowseLiveCursor.isNullOrEmpty()
                     isBrowseLiveLoading = false
                     updateBrowseShimmer(false)
                     binding.browseScreenContainer.browseSwipeRefresh.isRefreshing = false
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    isBrowseLiveLoading = false
                    updateBrowseShimmer(false)
                    binding.browseScreenContainer.browseSwipeRefresh.isRefreshing = false
                    Toast.makeText(activity, activity.getString(R.string.streams_load_failed, e.message), Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private fun updateBrowseShimmer(visible: Boolean, mode: String = "") {
        val shimmerInclude = binding.browseScreenContainer.browseShimmerInclude
        if (visible) {
            shimmerInclude.root.visibility = View.VISIBLE
            shimmerInclude.shimmerCategoriesContainer.visibility = if (mode == "categories") View.VISIBLE else View.GONE
            shimmerInclude.shimmerLiveListContainer.visibility = if (mode == "live_list") View.VISIBLE else View.GONE
            shimmerInclude.shimmerLiveGridContainer.visibility = if (mode == "live_grid") View.VISIBLE else View.GONE
            
            if (mode == "categories") binding.browseScreenContainer.browseCategoriesRecycler.visibility = View.GONE
            if (mode == "live_list" || mode == "live_grid") binding.browseScreenContainer.browseLiveChannelsRecycler.visibility = View.GONE
        } else {
            shimmerInclude.root.visibility = View.GONE
            // Restore recycler visibility based on current tab
            if (currentBrowseTab == 0) {
                binding.browseScreenContainer.browseLiveChannelsRecycler.visibility = View.VISIBLE
            } else if (currentBrowseTab == 1) {
                binding.browseScreenContainer.browseCategoriesRecycler.visibility = View.VISIBLE
            }
        }
    }

    fun loadBrowseData(isLoadMore: Boolean = false) {
        if (isBrowseCategoriesLoading) {
            if (isLoadMore) return
            // If refreshing but already loading, cancel previous job
            browseCategoriesJob?.cancel()
            isBrowseCategoriesLoading = false
        }
        
        if (isLoadMore && !hasMoreBrowseCategories) return
        
        isBrowseCategoriesLoading = true
        if (!isLoadMore) {
            val isRefreshing = binding.browseScreenContainer.browseSwipeRefresh.isRefreshing
            if (!isRefreshing) {
                updateBrowseShimmer(true, "categories")
            }
            currentBrowseCategoryPage = 1
            hasMoreBrowseCategories = true
            isFollowedCategoriesFetched = false
        } else {
            // Add loading item
            browseCategoriesList.add(null)
            browseCategoriesAdapter?.notifyItemInserted(browseCategoriesList.size - 1)
        }
        
        browseCategoriesJob = activity.lifecycleScope.launch(Dispatchers.IO) {
            try {
                val result = repository.getSubcategories(limit = 40, page = currentBrowseCategoryPage)
                
                // Fetch followed categories if not already fetched
                val token = prefs.authToken
                if (token != null && !isFollowedCategoriesFetched) {
                    val followedRes = repository.getFollowedCategories(token)
                    followedRes.onSuccess { list ->
                        followedCategorySlugs.clear()
                        followedCategorySlugs.addAll(list.map { it.slug })
                        isFollowedCategoriesFetched = true
                    }
                }

                val response = result.getOrNull()
                val categories = response?.data ?: emptyList()
                
                withContext(Dispatchers.Main) {
                    if (isLoadMore) {
                        // Remove loading item
                        if (browseCategoriesList.isNotEmpty() && browseCategoriesList.last() == null) {
                            browseCategoriesList.removeAt(browseCategoriesList.size - 1)
                            browseCategoriesAdapter?.notifyItemRemoved(browseCategoriesList.size)
                        }
                    }
                    
                    if (!isLoadMore) {
                        setupBrowseCategoryAdapter(categories)
                    } else if (categories.isNotEmpty()) {
                        val existingIds = browseCategoriesList.mapNotNull { it?.id }.toSet()
                        val uniqueCategories = categories.filter { it.id !in existingIds }
                    
                        if (uniqueCategories.isNotEmpty()) {
                            appendBrowseCategories(uniqueCategories)
                        }
                    }
                    
                    if (categories.isNotEmpty()) {
                        currentBrowseCategoryPage++
                        hasMoreBrowseCategories = response?.nextPageUrl != null
                    } else {
                        if (!isLoadMore) hasMoreBrowseCategories = false
                        // If we loaded more but got 0 items, hasMore is false (keep existing logic or rely on next page url)
                        if (isLoadMore && categories.isEmpty()) hasMoreBrowseCategories = false
                    }
                    isBrowseCategoriesLoading = false
                    updateBrowseShimmer(false)
                    binding.browseScreenContainer.browseSwipeRefresh.isRefreshing = false
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) { 
                    if (isLoadMore) {
                         // Remove loading item
                        if (browseCategoriesList.isNotEmpty() && browseCategoriesList.last() == null) {
                            browseCategoriesList.removeAt(browseCategoriesList.size - 1)
                            browseCategoriesAdapter?.notifyItemRemoved(browseCategoriesList.size)
                        }
                    }
                    isBrowseCategoriesLoading = false 
                    updateBrowseShimmer(false)
                    binding.browseScreenContainer.browseSwipeRefresh.isRefreshing = false
                    if (!isLoadMore) {
                         Toast.makeText(activity, activity.getString(R.string.categories_load_failed), Toast.LENGTH_SHORT).show()
                    }
                }
            }
        }
    }

    private fun appendBrowseCategories(newCategories: List<TopCategory>) {
        val start = browseCategoriesList.size
        browseCategoriesList.addAll(newCategories)
        browseCategoriesAdapter?.notifyItemRangeInserted(start, newCategories.size)
    }

    private fun setupBrowseCategoryAdapter(categories: List<TopCategory>) {
        browseCategoriesList.clear()
        browseCategoriesList.addAll(categories)
        
        val recycler = binding.browseScreenContainer.browseCategoriesRecycler
        // Use LinearLayoutManager - one category per row (matching shimmer design)
        recycler.layoutManager = LinearLayoutManager(activity)
        // Set padding to match shimmer (8dp all around + bottom for nav)
        recycler.setPadding(8.dpToPx(activity.resources), 8.dpToPx(activity.resources), 8.dpToPx(activity.resources), 80.dpToPx(activity.resources))
        recycler.clipToPadding = false
        
        browseCategoriesAdapter = object : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
            private val VIEW_TYPE_ITEM = 0
            private val VIEW_TYPE_LOADING = 1
            
            inner class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
                val cover: ImageView = view.findViewById(R.id.categoryImage)
                val name: TextView = view.findViewById(R.id.categoryName)
                val viewers: TextView = view.findViewById(R.id.categoryViewers)
                val tagsContainer: LinearLayout = view.findViewById(R.id.tagsContainer)
                val btnFollow: android.widget.ImageButton = view.findViewById(R.id.btnFollow)
            }
            
            inner class LoadingViewHolder(view: View) : RecyclerView.ViewHolder(view)
            
            override fun getItemViewType(position: Int): Int {
                return if (browseCategoriesList[position] == null) VIEW_TYPE_LOADING else VIEW_TYPE_ITEM
            }
            
            override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
                if (viewType == VIEW_TYPE_LOADING) {
                    val view = activity.layoutInflater.inflate(R.layout.item_loading_spinner, parent, false)
                    return LoadingViewHolder(view)
                }
                val view = activity.layoutInflater.inflate(R.layout.item_browse_category, parent, false)
                return ViewHolder(view)
            }
            
            override fun getItemCount(): Int = browseCategoriesList.size
            
            override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
                if (holder !is ViewHolder) return
                val category = browseCategoriesList[position] ?: return
                
                holder.name.text = CategoryUtils.getLocalizedCategoryName(
                    activity, category.name, category.slug
                )
                holder.viewers.text = activity.getString(R.string.viewer_count_label, activity.formatViewerCount(category.viewers.toLong()))
                holder.viewers.visibility = View.VISIBLE

                holder.tagsContainer.removeAllViews()
                val tags = category.tags
                if (!tags.isNullOrEmpty()) {
                    holder.tagsContainer.visibility = View.VISIBLE
                    tags.take(3).forEach { tag ->
                        val tv = TextView(activity)
                        tv.text = tag
                        tv.textSize = 10f
                        tv.setTextColor(android.graphics.Color.parseColor("#CCCCCC"))
                        tv.setBackgroundResource(R.drawable.bg_category_tag)
                        
                        val ph = 6.dpToPx(activity.resources)
                        val pv = 2.dpToPx(activity.resources)
                        tv.setPadding(ph, pv, ph, pv)
                        
                        val params = LinearLayout.LayoutParams(
                            ViewGroup.LayoutParams.WRAP_CONTENT,
                            ViewGroup.LayoutParams.WRAP_CONTENT
                        )
                        params.marginEnd = 8.dpToPx(activity.resources)
                        tv.layoutParams = params
                        
                        holder.tagsContainer.addView(tv)
                    }
                } else {
                    holder.tagsContainer.visibility = View.GONE
                }
                
                val coverUrl = category.banner?.src
                Glide.with(holder.itemView.context)
                    .load(coverUrl)
                    .placeholder(R.color.placeholder_grey)
                    .error(R.color.placeholder_grey)
                    .into(holder.cover)
                
                // Follow Logic
                val token = prefs.authToken
                if (token != null) {
                    holder.btnFollow.visibility = View.VISIBLE
                    val followed = followedCategorySlugs.contains(category.slug)
                    updateHeartIcon(holder.btnFollow, followed)
                    holder.btnFollow.setOnClickListener {
                        handleCategoryFollowToggle(category.slug, holder.btnFollow)
                    }
                } else {
                    holder.btnFollow.visibility = View.GONE
                }

                holder.itemView.setOnClickListener {
                    openCategoryDetails(category)
                }
            }
            
            private fun updateHeartIcon(btn: android.widget.ImageButton, isFollowed: Boolean) {
                if (isFollowed) {
                    btn.setImageResource(R.drawable.ic_heart)
                    btn.setColorFilter(Color.parseColor("#53FC18"))
                } else {
                    btn.setImageResource(R.drawable.ic_heart_outline)
                    btn.setColorFilter(Color.parseColor("#666666"))
                }
            }

            private fun handleCategoryFollowToggle(slug: String, btn: android.widget.ImageButton) {
                val token = prefs.authToken ?: return
                btn.isEnabled = false
                activity.lifecycleScope.launch(Dispatchers.IO) {
                    val result = repository.toggleCategoryFollow(slug, token)
                    withContext(Dispatchers.Main) {
                        btn.isEnabled = true
                        result.onSuccess { isFollowed ->
                            updateHeartIcon(btn, isFollowed)
                            if (isFollowed) {
                                followedCategorySlugs.add(slug)
                                Toast.makeText(activity, activity.getString(R.string.followed), Toast.LENGTH_SHORT).show()
                            } else {
                                followedCategorySlugs.remove(slug)
                                Toast.makeText(activity, activity.getString(R.string.unfollowed), Toast.LENGTH_SHORT).show()
                            }
                        }.onFailure {
                             Toast.makeText(activity, activity.getString(R.string.operation_failed_code, 0), Toast.LENGTH_SHORT).show()
                        }
                    }
                }
            }
        }
        
        recycler.adapter = browseCategoriesAdapter
        
        recycler.clearOnScrollListeners()
        recycler.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) {
                val layoutManager = recyclerView.layoutManager as? LinearLayoutManager ?: return
                val totalItemCount = layoutManager.itemCount
                val lastVisibleItem = layoutManager.findLastVisibleItemPosition()
                
                if (!isBrowseCategoriesLoading && hasMoreBrowseCategories && totalItemCount <= (lastVisibleItem + 5)) {
                    loadBrowseData(isLoadMore = true)
                }
            }
        })
    }

    fun setupBrowseChannelAdapter(
        recyclerView: RecyclerView?, 
        channels: List<ChannelItem>, 
        isGrid: Boolean = false,
        sortContext: String = "featured",
        categoryIdContext: Long? = null,
        categoryNameContext: String? = null,
        cursorProvider: () -> String? = { null },
        config: android.content.res.Configuration? = null
    ) {
        if (recyclerView == null) return
        
        // Responsive Layout based on available width
        // Prefer explicit config if provided, layout update if stored, otherwise use current resources
        val currentConfig = config ?: lastConfiguration ?: activity.resources.configuration
        val screenWidthDp = currentConfig.screenWidthDp
        
        val spanCount = when {
            screenWidthDp >= 840 -> 4 // Large Tablet / Desktop
            screenWidthDp >= 580 -> 3 // Landscape Tablet / Large Phone Landscape
            else -> if (isGrid) 2 else 1 // Phone Portrait: Respect user preference for Grid (2 cols)
        }
        
        // Determine layout manager
        val effectiveIsGrid = spanCount > 1
        
        // Only re-create layout manager if type changes to prevent scroll reset
        val currentLM = recyclerView.layoutManager
        // Note: GridLayoutManager extends LinearLayoutManager, so we need explicit check
        val needsNewLM = when {
            effectiveIsGrid && currentLM !is GridLayoutManager -> true
            !effectiveIsGrid && (currentLM is GridLayoutManager || currentLM !is LinearLayoutManager) -> true
            currentLM is GridLayoutManager && currentLM.spanCount != spanCount -> true // Span count changed
            currentLM == null -> true
            else -> false
        }
        
        if (needsNewLM) {
            // Clear recycled view pool to prevent crash
            recyclerView.recycledViewPool.clear()
            recyclerView.layoutManager = if (effectiveIsGrid) {
                GridLayoutManager(activity, spanCount)
            } else {
                LinearLayoutManager(activity)
            }
        }
        
        // Set padding to match shimmer
        if (effectiveIsGrid) {
            recyclerView.setPadding(10.dpToPx(activity.resources), 0, 10.dpToPx(activity.resources), 80.dpToPx(activity.resources))
        } else {
            recyclerView.setPadding(16.dpToPx(activity.resources), 0, 16.dpToPx(activity.resources), 80.dpToPx(activity.resources))
        }
        recyclerView.clipToPadding = false
        
        browseLiveAdapter = object : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
            inner class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
                val thumbnail: ImageView = view.findViewById(R.id.channelThumbnail)
                val profilePic: ImageView = view.findViewById(R.id.channelProfilePic)
                val username: TextView = view.findViewById(R.id.channelUsername)
                val category: TextView = view.findViewById(R.id.channelCategory)
                val streamerName: TextView = view.findViewById(R.id.channelStreamerName)
                val viewerCount: TextView = view.findViewById(R.id.viewerCount)
                val tagsContainer: LinearLayout? = view.findViewById(R.id.tagsContainer)
            }
            
            override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
                // Use card layout for both modes if forced grid
                val layoutRes = if (effectiveIsGrid) R.layout.item_following_channel_card else R.layout.item_following_channel_card
                val view = activity.layoutInflater.inflate(layoutRes, parent, false)
                
                // Add margins for grid view to match shimmer (6dp all sides)
                if (effectiveIsGrid) {
                    val params = view.layoutParams as? RecyclerView.LayoutParams
                        ?: RecyclerView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                    params.marginStart = 6.dpToPx(activity.resources)
                    params.marginEnd = 6.dpToPx(activity.resources)
                    params.topMargin = 6.dpToPx(activity.resources)
                    params.bottomMargin = 6.dpToPx(activity.resources)
                    view.layoutParams = params
                }
                // List mode already has marginVertical="6dp" in the item layout
                
                return ViewHolder(view)
            }
            
            override fun getItemCount(): Int = channels.size
            
            override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
                if (holder !is ViewHolder) return
                val channel = channels[position]
                
                val thumbnailUrl = channel.thumbnailUrl ?: "https://images.kick.com/video_thumbnails/${channel.slug}/uuid/fullsize"
                val profileUrl = channel.getEffectiveProfilePicUrl()

                val defaultThumb = dev.xacnio.kciktv.shared.util.Constants.Urls.DEFAULT_LIVESTREAM_THUMBNAIL

                // Only reload if URL changed
                if (holder.thumbnail.tag != thumbnailUrl) {
                    holder.thumbnail.tag = thumbnailUrl
                    Glide.with(holder.itemView.context)
                        .load(thumbnailUrl)
                        .signature(ThumbnailCacheHelper.getCacheSignature())
                        .placeholder(ShimmerDrawable(isCircle = false))
                        .error(Glide.with(holder.itemView.context).load(defaultThumb))
                        .into(holder.thumbnail)
                }

                // Only reload profile pic if URL changed
                if (holder.profilePic.tag != profileUrl) {
                    holder.profilePic.tag = profileUrl
                    Glide.with(holder.itemView.context)
                        .load(profileUrl)
                        .circleCrop()
                        .placeholder(R.drawable.default_avatar)
                        .error(R.drawable.default_avatar)
                        .into(holder.profilePic)
                }
                
                holder.username.text = channel.username
                holder.category.text = CategoryUtils.getLocalizedCategoryName(
                    activity, channel.categoryName, channel.categorySlug
                )
                holder.streamerName.text = channel.title
                holder.viewerCount.text = activity.getString(R.string.watching_count_label, activity.formatViewerCount(channel.viewerCount.toLong()))
                
                holder.tagsContainer?.let { container ->
                    container.removeAllViews()
                    channel.language?.let { lang ->
                        val tagView = activity.layoutInflater.inflate(R.layout.item_tag, container, false) as TextView
                        tagView.text = activity.getLanguageName(lang).uppercase()
                        container.addView(tagView)
                    }
                }
                
                holder.itemView.setOnClickListener {
                    // activity.openChannel(channel)
                    activity.streamFeedManager.openFeedFromBrowse(channels, position, sortContext, categoryIdContext, cursorProvider(), categoryNameContext)
                }
                
                holder.itemView.setOnLongClickListener {
                    // Open directly in main player
                    // Set flag to return to category details after player closes
                    if (isCategoryDetailsVisible) {
                        activity.returnToCategoryDetails = true
                    }
                    binding.categoryDetailsContainer.root.visibility = View.GONE
                    binding.browseScreenContainer.root.visibility = View.GONE
                    activity.openChannel(channel)
                    true
                }
                
                // Profile pic click opens channel profile
                holder.profilePic.setOnClickListener {
                    activity.channelProfileManager.openChannelProfile(channel.slug)
                }
            }
        }
        
        recyclerView.adapter = browseLiveAdapter
        
        // Increase view cache to prevent re-binding when scrolling back
        recyclerView.setItemViewCacheSize(20)
        
        recyclerView.clearOnScrollListeners()
        recyclerView.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) {
                val layoutManager = recyclerView.layoutManager
                val totalItemCount = layoutManager?.itemCount ?: 0
                val lastVisibleItem = if (layoutManager is GridLayoutManager) {
                    layoutManager.findLastVisibleItemPosition()
                } else if (layoutManager is LinearLayoutManager) {
                    layoutManager.findLastVisibleItemPosition()
                } else -1
                
                if (!isBrowseLiveLoading && hasMoreBrowseLive && totalItemCount <= (lastVisibleItem + 5) && currentBrowseTab == 0) {
                    loadBrowseLiveChannels(isLoadMore = true)
                }
            }
        })

    }

    private var previousContainerId: Int = R.id.browseScreenContainer

    fun openCategoryDetails(category: TopCategory, fromHome: Boolean = false, fromSearch: Boolean = false) {
        // Capture previous screen state if fresh open
        if (!isCategoryDetailsVisible) {
            val isHomeVisible = binding.homeScreenContainer.root.visibility == View.VISIBLE
            val isFollowingVisible = binding.followingScreenContainer.root.visibility == View.VISIBLE
            val isSearchVisible = binding.searchContainer.visibility == View.VISIBLE
            
            previousContainerId = when {
                fromSearch -> R.id.searchContainer
                isSearchVisible -> R.id.searchContainer
                isHomeVisible || fromHome -> R.id.homeScreenContainer
                isFollowingVisible -> R.id.followingScreenContainer
                else -> R.id.browseScreenContainer
            }
        }
        
        android.util.Log.d("BrowseManager", "openCategoryDetails: category=${category.name}, fromHome=$fromHome, prevContainer=$previousContainerId")
        
        // Use cached category entirely if available to keep UI stable (prevents viewers/followers jump)
        val workingCategory = categoryCache[category.slug] ?: category

        val isTablet = activity.resources.getBoolean(R.bool.is_tablet)
        isCategoryGridMode = if (isTablet) true else (prefs.mobileLayoutMode == "grid")

        isCategoryDetailsVisible = true
        categoryDetailsOpenedFromHome = fromHome // Keep for now
        activity.setCurrentScreen(MobilePlayerActivity.AppScreen.CATEGORY_DETAILS)
        
        binding.categoryDetailsContainer.root.visibility = View.VISIBLE
        binding.browseScreenContainer.root.visibility = View.GONE
        binding.homeScreenContainer.root.visibility = View.GONE
        binding.followingScreenContainer.root.visibility = View.GONE
        binding.mobileHeader.visibility = View.GONE // Hide header in category details
        
        setupCategoryStreamsShimmer(isCategoryGridMode)
        binding.categoryDetailsContainer.categoryDetailsShimmer.root.visibility = View.VISIBLE
        binding.categoryDetailsContainer.categoryDetailsContent.visibility = View.GONE
        
        binding.categoryDetailsContainer.categoryTitle.text = CategoryUtils.getLocalizedCategoryName(
            activity, workingCategory.name, workingCategory.slug
        )
        
        val viewers = activity.formatViewerCount(workingCategory.viewers.toLong())
        val initialStats = if (workingCategory.followersCount != null) {
            val followers = activity.formatViewerCount(workingCategory.followersCount.toLong())
            activity.getString(R.string.viewer_follower_format, viewers, followers)
        } else {
            activity.getString(R.string.viewer_count_label, viewers)
        }
        binding.categoryDetailsContainer.categoryStats.text = initialStats
        
        openedCategory = workingCategory
        currentCategorySort = "featured" // Reset sort on fresh open

        // loadCategoryStreams() is handled by the block check below
        
        val bannerUrl = category.banner?.src
        if (!bannerUrl.isNullOrEmpty()) {
             Glide.with(activity).load(bannerUrl).into(binding.categoryDetailsContainer.categoryBanner)
        }
        
        // Initial Follow State from Cache
        val isFollowedKnown = isFollowedCategoriesFetched
        if (isFollowedKnown) {
            val followed = followedCategorySlugs.contains(workingCategory.slug)
            updateFollowButtonState(followed)
            binding.categoryDetailsContainer.categoryFollowButton.isEnabled = true
            binding.categoryDetailsContainer.categoryFollowLoading.visibility = View.GONE
        } else {
            // Reset Follow Button to loading state
            binding.categoryDetailsContainer.categoryFollowButton.apply {
                isEnabled = false
                text = ""
                setBackgroundColor(Color.parseColor("#333333"))
            }
            binding.categoryDetailsContainer.categoryFollowLoading.visibility = View.VISIBLE
        }
        
        // Setup Block Button
        val blockBtn = binding.categoryDetailsContainer.categoryBlockButton
        val isBlocked = prefs.isCategoryBlocked(category.name)
        updateBlockButtonState(isBlocked)
        
        if (isBlocked) {
             applyCategoryBlockedUi(true)
        } else {
             applyCategoryBlockedUi(false)
             loadCategoryStreams()
        }
        
        blockBtn.setOnClickListener {
            val currentlyBlocked = prefs.isCategoryBlocked(category.name)
            if (currentlyBlocked) {
                prefs.removeBlockedCategory(category.name)
                updateBlockButtonState(false)
                Toast.makeText(activity, activity.getString(R.string.category_unhidden_toast), Toast.LENGTH_SHORT).show()
                applyCategoryBlockedUi(false)
                loadCategoryStreams()
            } else {
                prefs.addBlockedCategory(category.name)
                updateBlockButtonState(true)
                Toast.makeText(activity, activity.getString(R.string.category_hidden_toast), Toast.LENGTH_SHORT).show()
                applyCategoryBlockedUi(true)
            }
        }
        
        // Add Filter Button
        addCategoryFilterButton()
        
        // Check Follow Status
        checkCategoryFollowStatus(category.slug)

        // Setup Tabs
        setupCategoryTabs(category.slug)

        // FINAL UI check for blocked state to ensure it overrides tabs/shimmer defaults
        if (prefs.isCategoryBlocked(category.name)) {
            applyCategoryBlockedUi(true)
        }
        
        activity.lifecycleScope.launch(Dispatchers.IO) {
            try {
                // Determine details only (streams loaded via loadCategoryStreams)
                // val langs = prefs.streamLanguages.toList()
                // val streamsDef = async { repository.getFilteredLiveStreams(categoryId = category.id, languages = if (langs.isNotEmpty()) langs else null) }
                val detailsDef = async { repository.getSubcategoryDetails(category.slug) }
                
                // val streamsResult = streamsDef.await()
                val detailsResult = detailsDef.await()
                
                // val channels = streamsResult.getOrNull()?.channels ?: emptyList()
                // currentCategoryChannels = channels // Store for filtering
                val details = detailsResult.getOrNull()
                
                withContext(Dispatchers.Main) {
                     // Details logic
                     if (details != null) {
                        // Cache for next time
                        categoryCache[category.slug] = details
                        
                         val viewersStr = activity.formatViewerCount(details.viewers.toLong())
                        val text = if (details.followersCount != null) {
                            val followers = activity.formatViewerCount(details.followersCount.toLong())
                            activity.getString(R.string.viewer_follower_format, viewersStr, followers)
                        } else {
                            activity.getString(R.string.viewer_count_label, viewersStr)
                        }
                        binding.categoryDetailsContainer.categoryStats.text = text
                     }
                }
            } catch (e: Exception) {
                // Ignore details error, main content handled by loadCategoryStreams
            }
        }
    }

    fun openCategoryBySlug(slug: String, fromSearch: Boolean = false) {
        activity.lifecycleScope.launch(Dispatchers.IO) {
            try {
                val result = repository.getSubcategoryDetails(slug)
                result.onSuccess { category ->
                    withContext(Dispatchers.Main) {
                        openCategoryDetails(category, fromSearch = fromSearch)
                    }
                }.onFailure {
                    withContext(Dispatchers.Main) {
                         Toast.makeText(activity, activity.getString(R.string.category_load_failed), Toast.LENGTH_SHORT).show()
                    }
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    Toast.makeText(activity, activity.getString(R.string.operation_failed, e.message), Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    fun closeCategoryDetails() {
        android.util.Log.d("BrowseManager", "closeCategoryDetails: prevContainer=$previousContainerId")
        
        // Reset flag since we are closing it
        activity.returnToCategoryDetails = false
        
        isCategoryDetailsVisible = false
        binding.categoryDetailsContainer.root.visibility = View.GONE
        binding.mobileHeader.visibility = View.VISIBLE
        
        if (previousContainerId == R.id.searchContainer) {
            binding.browseScreenContainer.root.visibility = View.GONE
            binding.searchContainer.visibility = View.VISIBLE
            binding.searchContainer.bringToFront()
            activity.setCurrentScreen(MobilePlayerActivity.AppScreen.SEARCH)
        } else if (previousContainerId == R.id.homeScreenContainer) {
            binding.browseScreenContainer.root.visibility = View.GONE
            binding.homeScreenContainer.root.visibility = View.VISIBLE
            activity.isHomeScreenVisible = true
            activity.setCurrentScreen(MobilePlayerActivity.AppScreen.HOME)
        } else if (previousContainerId == R.id.followingScreenContainer) {
            binding.browseScreenContainer.root.visibility = View.GONE
            binding.followingScreenContainer.root.visibility = View.VISIBLE
            activity.setCurrentScreen(MobilePlayerActivity.AppScreen.FOLLOWING)
        } else {
            binding.browseScreenContainer.root.visibility = View.VISIBLE
            activity.setCurrentScreen(MobilePlayerActivity.AppScreen.BROWSE)
            if (currentBrowseTab == 0) {
                binding.browseScreenContainer.browseLiveChannelsRecycler.visibility = View.VISIBLE
            } else {
                binding.browseScreenContainer.browseCategoriesRecycler.visibility = View.VISIBLE
            }
        }
        categoryDetailsOpenedFromHome = false
    }

    fun handleBack(): Boolean {
        if (activity.miniPlayerManager.isMiniPlayerMode) return false
        
        if (isCategoryDetailsVisible) {
            closeCategoryDetails()
            return true
        }
        
        if (binding.playerScreenContainer.visibility == View.VISIBLE && !activity.miniPlayerManager.isMiniPlayerMode) {
             return false
        }

        return false
    }

    fun updateLayout(config: android.content.res.Configuration? = null) {
        if (config != null) {
            lastConfiguration = config
        }
        if (binding.browseScreenContainer.root.visibility == View.VISIBLE) {
            
            // Update Live Channels Grid
            if (currentBrowseTab == 0 && browseLiveChannelsList.isNotEmpty()) {
                val recycler = binding.browseScreenContainer.browseLiveChannelsRecycler
                setupBrowseChannelAdapter(recycler, browseLiveChannelsList, isBrowseGridMode, currentBrowseLiveSort, null, null, { nextBrowseLiveCursor }, config)
            }
            
            // Update Clips Grid (delegated to clips manager)
            if (currentBrowseTab == 2) {
                activity.browseClipsManager.updateLayout(config)
            }
        }
        
        // Also update Category Details if visible
        if (isCategoryDetailsVisible && binding.categoryDetailsContainer.root.visibility == View.VISIBLE) {
            if (currentCategoryTab == 0 && currentCategoryChannels.isNotEmpty()) {
                val recycler = binding.categoryDetailsContainer.categoryStreamsRecycler
                val catName = openedCategory?.let { CategoryUtils.getLocalizedCategoryName(activity, it.name, it.slug) }
                setupBrowseChannelAdapter(recycler, currentCategoryChannels, isCategoryGridMode, currentCategorySort, openedCategory?.id, catName, { nextCategoryCursor }, config)
            } else if (currentCategoryTab == 1) {
                 setupCategoryClipsLayout(config)
            }
        }
    }
    
    private fun setupCategoryClipsLayout(config: android.content.res.Configuration? = null) {
         val recycler = binding.categoryDetailsContainer.categoryClipsRecycler
         if (recycler.adapter == null && categoryClipsList.isEmpty()) return
         
         // Responsive Layout based on available width
         val currentConfig = config ?: lastConfiguration ?: activity.resources.configuration
         val screenWidthDp = currentConfig.screenWidthDp
        
        val spanCount = when {
            screenWidthDp >= 840 -> 4 // Large Tablet / Desktop
            screenWidthDp >= 580 -> 3 // Landscape Tablet / Large Phone Landscape
            else -> if (isCategoryGridMode) 2 else 1 // Phone Portrait: Respect user preference for Grid (2 cols)
        }
        
        val effectiveIsGrid = spanCount > 1
        
        // Only re-create layout manager if type changes
        val currentLM = recycler.layoutManager
        // Note: GridLayoutManager extends LinearLayoutManager, so we need explicit check
        val needsNewLM = when {
            effectiveIsGrid && currentLM !is GridLayoutManager -> true
            !effectiveIsGrid && (currentLM is GridLayoutManager || currentLM !is LinearLayoutManager) -> true
            currentLM is GridLayoutManager && currentLM.spanCount != spanCount -> true
            currentLM == null -> true
            else -> false
        }
        
        if (needsNewLM) {
            // Clear recycled view pool to prevent crash
            recycler.recycledViewPool.clear()
            recycler.layoutManager = if (effectiveIsGrid) {
                GridLayoutManager(activity, spanCount)
            } else {
                LinearLayoutManager(activity)
            }
        }
        
        // Update padding
        if (effectiveIsGrid) {
            recycler.setPadding(8.dpToPx(activity.resources), 8.dpToPx(activity.resources), 8.dpToPx(activity.resources), 80.dpToPx(activity.resources))
        } else {
            recycler.setPadding(0, 8.dpToPx(activity.resources), 0, 80.dpToPx(activity.resources))
        }
        recycler.clipToPadding = false
        
        // Update adapter if needed (to change view types?)
        // The adapter might need to be notified or re-set to pick up the grid/list view type change if it handles it.
        // ChannelClipAdapter usually handles view type in onBind or getItemViewType.
        // If it supports switching, we might need to tell it.
        categoryClipsAdapter?.updateData(categoryClipsList, effectiveIsGrid)
    }

    private fun checkCategoryFollowStatus(slug: String) {
        val followBtn = binding.categoryDetailsContainer.categoryFollowButton
        val loading = binding.categoryDetailsContainer.categoryFollowLoading
        
        // Show loading only if we don't know the status yet
        if (!isFollowedCategoriesFetched) {
            followBtn.isEnabled = false
            followBtn.text = ""
            loading.visibility = View.VISIBLE
        }
        
        val token = prefs.authToken
        if (token.isNullOrEmpty()) {
             loading.visibility = View.GONE
             updateFollowButtonState(false)
             followBtn.isEnabled = true
             followBtn.setOnClickListener {
                 activity.authManager.handleLoginClick()
             }
             return
        }
        
        activity.lifecycleScope.launch(Dispatchers.IO) {
            val token = prefs.authToken ?: return@launch
            val result = repository.getFollowedCategories(token)
            val followedList = result.getOrNull()
            
            if (followedList != null) {
                // Update Global Cache
                followedCategorySlugs.clear()
                followedCategorySlugs.addAll(followedList.map { it.slug })
                isFollowedCategoriesFetched = true
            }

            val isFollowed = followedList?.any { it.slug == slug } == true
            
            withContext(Dispatchers.Main) {
                if (openedCategory?.slug == slug) {
                    binding.categoryDetailsContainer.categoryFollowLoading.visibility = View.GONE
                    updateFollowButtonState(isFollowed)
                    followBtn.isEnabled = true
                    
                    followBtn.setOnClickListener {
                        toggleCategoryFollow(slug, isFollowed)
                    }
                }
            }
        }
    }

    private fun updateFollowButtonState(isFollowed: Boolean) {
        val followBtn = binding.categoryDetailsContainer.categoryFollowButton
        if (isFollowed) {
             followBtn.text = activity.getString(R.string.unfollow)
             followBtn.setBackgroundColor(Color.parseColor("#333333"))
             followBtn.setTextColor(Color.WHITE)
        } else {
             followBtn.text = activity.getString(R.string.follow)
             followBtn.setBackgroundColor(Color.parseColor("#53FC18"))
             followBtn.setTextColor(Color.BLACK)
        }
    }

    private fun toggleCategoryFollow(slug: String, currentStatus: Boolean) {
        val followBtn = binding.categoryDetailsContainer.categoryFollowButton
        val loading = binding.categoryDetailsContainer.categoryFollowLoading
        
        followBtn.isEnabled = false
        followBtn.text = "" // Hide text during action
        loading.visibility = View.VISIBLE
        
        activity.lifecycleScope.launch(Dispatchers.IO) {
            val token = prefs.authToken ?: return@launch
            val result = repository.toggleCategoryFollow(slug, token)
            
            withContext(Dispatchers.Main) {
                loading.visibility = View.GONE
                followBtn.isEnabled = true
                result.onSuccess { newStatus ->
                    updateFollowButtonState(newStatus)
                    followBtn.setOnClickListener {
                        toggleCategoryFollow(slug, newStatus)
                    }
                    activity.followingManager.loadFollowedCategories()
                }.onFailure {
                    updateFollowButtonState(currentStatus) // Revert state UI
                    Toast.makeText(activity, activity.getString(R.string.error_generic), Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private fun applyCategoryBlockedUi(isBlocked: Boolean) {
        val container = binding.categoryDetailsContainer
        if (isBlocked) {
            // Keep content container visible (for header)
            container.categoryDetailsContent.visibility = View.VISIBLE
            
            // Hide Tabs and Content
            container.categoryTabsContainer.visibility = View.GONE
            container.categoryStreamsRecycler.visibility = View.GONE
            container.categoryClipsRecycler.visibility = View.GONE
            container.categoryClipsShimmer.root.visibility = View.GONE
            container.categoryDetailsShimmer.root.visibility = View.GONE
            
            // Show Blocked Message
            container.categoryEmptyState.visibility = View.VISIBLE
            container.emptyStateText.text = activity.getString(R.string.category_hidden_msg)
            container.emptyStateIcon.setImageResource(R.drawable.ic_block)
        } else {
            container.categoryTabsContainer.visibility = View.VISIBLE
            container.categoryEmptyState.visibility = View.GONE
            container.emptyStateText.text = ""
            
            // Visibility of recyclers handled by tab switch logic
            if (currentCategoryTab == 0) {
                container.categoryStreamsRecycler.visibility = View.VISIBLE
                container.categoryClipsRecycler.visibility = View.GONE
            } else {
                container.categoryStreamsRecycler.visibility = View.GONE
                container.categoryClipsRecycler.visibility = View.VISIBLE
            }
        }
    }

    private fun updateBlockButtonState(isBlocked: Boolean) {
        val btn = binding.categoryDetailsContainer.categoryBlockButton
        if (isBlocked) {
            btn.text = activity.getString(R.string.category_hidden)
            btn.setIconResource(R.drawable.ic_visibility)
            btn.setIconTint(android.content.res.ColorStateList.valueOf(Color.WHITE))
            btn.setBackgroundColor(Color.parseColor("#FF5252")) 
        } else {
            btn.text = activity.getString(R.string.category_hide)
            btn.setIconResource(R.drawable.ic_block)
            btn.setIconTint(android.content.res.ColorStateList.valueOf(Color.WHITE))
            btn.setBackgroundColor(Color.parseColor("#333333"))
        }
    }

    private fun showBlockedCategoriesDialog() {
        val blockedList = prefs.blockedCategories.toList().sorted().toMutableList()
        if (blockedList.isEmpty()) {
            Toast.makeText(activity, activity.getString(R.string.no_blocked_categories_toast), Toast.LENGTH_SHORT).show()
            return
        }

        val bottomSheet = BottomSheetDialog(activity, R.style.BottomSheetDialogTheme)
        val view = activity.layoutInflater.inflate(R.layout.bottom_sheet_blocked_categories, null)
        bottomSheet.setContentView(view)

        val recycler = view.findViewById<RecyclerView>(R.id.blockedCategoriesRecycler)
        val emptyText = view.findViewById<View>(R.id.emptyStateText)

        recycler.layoutManager = LinearLayoutManager(activity)
        recycler.adapter = object : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
            inner class ViewHolder(v: View) : RecyclerView.ViewHolder(v) {
                val name: TextView = v.findViewById(R.id.categoryName)
                val icon: ImageView = v.findViewById(R.id.categoryIcon)
                val unblockBtn: View = v.findViewById(R.id.unblockBtn)
            }

            override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
                return ViewHolder(activity.layoutInflater.inflate(R.layout.item_blocked_category, parent, false))
            }

            override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
                if (holder !is ViewHolder) return
                val categoryName = blockedList[position]
                holder.name.text = categoryName
                
                // Derive a potential slug for icon lookup
                val derivedSlug = categoryName.lowercase().replace(" ", "-").replace("&", "").replace(",", "").replace("--", "-")
                holder.icon.setImageResource(CategoryUtils.getCategoryIcon(derivedSlug))

                holder.unblockBtn.setOnClickListener {
                    prefs.removeBlockedCategory(categoryName)
                    Toast.makeText(activity, activity.getString(R.string.category_unblocked_toast, categoryName), Toast.LENGTH_SHORT).show()
                    
                    blockedList.removeAt(position)
                    notifyItemRemoved(position)
                    notifyItemRangeChanged(position, blockedList.size)

                    if (blockedList.isEmpty()) {
                        emptyText.visibility = View.VISIBLE
                        recycler.visibility = View.GONE
                    }

                    // Refresh Browse data in background
                    when (currentBrowseTab) {
                        0 -> loadBrowseLiveChannels()
                        1 -> loadBrowseData()
                        2 -> activity.browseClipsManager.loadBrowseClips()
                    }
                }
            }
            override fun getItemCount() = blockedList.size
        }

        bottomSheet.show()
    }

    private fun setupCategoryTabs(slug: String) {
        val container = binding.categoryDetailsContainer
        currentCategoryTab = 0
        container.tabLive.setTextColor(Color.parseColor("#53FC18"))
        container.tabLive.setBackgroundResource(R.drawable.bg_item_ripple)
        container.tabClips.setTextColor(Color.parseColor("#888888"))
        container.tabClips.background = null

        container.categoryStreamsRecycler.visibility = View.VISIBLE
        container.categoryClipsRecycler.visibility = View.GONE
        container.categoryEmptyState.visibility = View.GONE

        // Reset clips state
        isClipsLoaded = false
        categoryClipsList.clear()
        if (categoryClipsAdapter != null) {
            categoryClipsAdapter?.updateData(emptyList())
        }

        container.tabLive.setOnClickListener {
            switchCategoryTab(0, slug)
        }
        
        container.tabClips.setOnClickListener {
            switchCategoryTab(1, slug)
        }
    }

    private fun switchCategoryTab(index: Int, slug: String) {
        if (currentCategoryTab == index) return
        currentCategoryTab = index
        val container = binding.categoryDetailsContainer
        
        if (index == 0) {
            // Live
            container.tabLive.setTextColor(Color.parseColor("#53FC18"))
            container.tabLive.setBackgroundResource(R.drawable.bg_item_ripple)
            container.tabClips.setTextColor(Color.parseColor("#888888"))
            container.tabClips.background = null
            
            container.categoryStreamsRecycler.visibility = View.VISIBLE
            container.categoryClipsRecycler.visibility = View.GONE
            
            if (browseLiveChannelsList.isEmpty() && !isCategoryStreamsLoading) {
                 // Actually relying on loadCategoryStreams to existing list
                 // If streams list is empty? We don't track it here directly but via existing logic
                 // But we can check empty state visibility logic from existing code
                 // Existing code doesn't explicitly toggle empty state in openCategoryDetails except what loadCategoryStreams does
                 // Let's assume loading streams handles empty state for itself.
                 container.categoryEmptyState.visibility = View.GONE // Reset default
            }
        } else {
            // Clips
            container.tabClips.setTextColor(Color.parseColor("#53FC18"))
            container.tabClips.setBackgroundResource(R.drawable.bg_item_ripple)
            container.tabLive.setTextColor(Color.parseColor("#888888"))
            container.tabLive.background = null
            
            container.categoryStreamsRecycler.visibility = View.GONE
            
            // Check layout preference (Grid vs List)
            val isGrid = prefs.mobileLayoutMode == "grid"
            setupClipsLayoutManager(isGrid)
            setupClipsShimmer(isGrid)
            
            if (!isClipsLoaded) {
                container.categoryClipsRecycler.visibility = View.GONE
                container.categoryClipsShimmer.root.visibility = View.VISIBLE
                loadCategoryClips(slug)
            } else {
                container.categoryClipsShimmer.root.visibility = View.GONE
                if (categoryClipsList.isEmpty()) {
                    container.categoryClipsRecycler.visibility = View.GONE
                    container.categoryEmptyState.visibility = View.VISIBLE
                    container.emptyStateText.text = activity.getString(R.string.no_clips_found)
                } else {
                    container.categoryClipsRecycler.visibility = View.VISIBLE
                    container.categoryEmptyState.visibility = View.GONE
                }
            }
        }
    }

    private fun loadCategoryClips(slug: String, isLoadMore: Boolean = false) {
        val container = binding.categoryDetailsContainer
        val categoryName = openedCategory?.name ?: ""
        if (prefs.isCategoryBlocked(categoryName)) return
        if (isCategoryClipsLoading) return
        if (isLoadMore && !hasMoreCategoryClips) return
        
        isCategoryClipsLoading = true
        
        if (!isLoadMore) {
            // Reset pagination state for fresh load
            nextCategoryClipsCursor = null
            hasMoreCategoryClips = true
            categoryClipsList.clear()
            
            val isGrid = prefs.mobileLayoutMode == "grid"
            setupClipsShimmer(isGrid)
            container.categoryClipsShimmer.root.visibility = View.VISIBLE
            container.categoryClipsRecycler.visibility = View.GONE
            container.categoryEmptyState.visibility = View.GONE
        }
        
        activity.lifecycleScope.launch(Dispatchers.IO) {
            try {
                val cursor = if (isLoadMore) nextCategoryClipsCursor else null
                val result = repository.getCategoryClips(slug, currentCategoryClipsSort, currentCategoryClipsTime, cursor)
                
                withContext(Dispatchers.Main) {
                    isCategoryClipsLoading = false
                    val currentIsGrid = prefs.mobileLayoutMode == "grid"
                    
                    if (!isLoadMore) {
                        setupClipsShimmer(currentIsGrid)
                    }
                    
                    if (currentCategoryTab != 1) return@withContext
                    
                    container.categoryClipsShimmer.root.visibility = View.GONE

                    result.onSuccess { response ->
                        isClipsLoaded = true
                        val browseClips = response?.clips ?: emptyList()
                        nextCategoryClipsCursor = response?.nextCursor
                        hasMoreCategoryClips = !nextCategoryClipsCursor.isNullOrEmpty()
                        
                        val newClips = browseClips.map { clip ->
                            dev.xacnio.kciktv.shared.data.model.ChannelClip(
                                id = clip.id,
                                title = clip.title,
                                thumbnailUrl = clip.thumbnailUrl,
                                duration = clip.duration,
                                views = clip.viewCount ?: clip.views,
                                createdAt = clip.createdAt,
                                url = clip.clipUrl ?: clip.videoUrl,
                                creator = if (clip.creator != null) {
                                    dev.xacnio.kciktv.shared.data.model.ClipCreator(
                                        id = clip.creator.id,
                                        username = clip.creator.username,
                                        slug = clip.creator.slug,
                                        profilePicture = null
                                    )
                                } else null,
                                channel = if (clip.channel != null) {
                                    dev.xacnio.kciktv.shared.data.model.ClipChannel(
                                        id = clip.channel.id,
                                        username = clip.channel.username,
                                        slug = clip.channel.slug,
                                        profilePicture = clip.channel.profilePicture
                                    )
                                } else null
                            )
                        }
                        
                        if (isLoadMore) {
                            val startPos = categoryClipsList.size
                            categoryClipsList.addAll(newClips)
                            categoryClipsAdapter?.notifyItemRangeInserted(startPos, newClips.size)
                        } else {
                            categoryClipsList.addAll(newClips)
                            setupCategoryClipsAdapter()
                        }
                        
                        if (categoryClipsList.isEmpty()) {
                            container.categoryClipsRecycler.visibility = View.GONE
                            container.categoryEmptyState.visibility = View.VISIBLE
                            container.emptyStateText.text = activity.getString(R.string.no_clips_found)
                        } else {
                            container.categoryClipsRecycler.visibility = View.VISIBLE
                            container.categoryEmptyState.visibility = View.GONE
                        }
                    }.onFailure {
                        Toast.makeText(activity, activity.getString(R.string.clips_load_failed), Toast.LENGTH_SHORT).show()
                    }
                    container.categoryClipsLoadingMore.visibility = View.GONE
                    container.categoryDetailsSwipeRefresh.isRefreshing = false
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    isCategoryClipsLoading = false
                    container.categoryClipsLoadingMore.visibility = View.GONE
                    container.categoryDetailsSwipeRefresh.isRefreshing = false
                    container.categoryClipsShimmer.root.visibility = View.GONE
                    Toast.makeText(activity, activity.getString(R.string.clips_load_failed), Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private fun setupCategoryClipsAdapter() {
        val isTablet = activity.resources.getBoolean(R.bool.is_tablet)
        val isGrid = if (isTablet) true else (prefs.mobileLayoutMode == "grid")
        val recycler = binding.categoryDetailsContainer.categoryClipsRecycler
        
        if (categoryClipsAdapter == null) {
            categoryClipsAdapter = dev.xacnio.kciktv.shared.ui.adapter.ChannelClipAdapter(
                categoryClipsList, 
                isGrid,
                onItemClick = { clickedClip -> openCategoryClipFeed(clickedClip) },
                onItemLongClick = { clickedClip -> openClipInPlayer(clickedClip) }
            )
            recycler.adapter = categoryClipsAdapter
            
            // Increase view cache to prevent re-binding when scrolling back
            recycler.setItemViewCacheSize(20)
            
            // Add scroll listener for infinite scroll
            recycler.addOnScrollListener(object : RecyclerView.OnScrollListener() {
                override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) {
                    super.onScrolled(recyclerView, dx, dy)
                    if (dy <= 0) return // Only on scroll down
                    
                    val layoutManager = recyclerView.layoutManager
                    val totalItemCount = layoutManager?.itemCount ?: 0
                    val lastVisibleItem = when (layoutManager) {
                        is GridLayoutManager -> layoutManager.findLastVisibleItemPosition()
                        is LinearLayoutManager -> layoutManager.findLastVisibleItemPosition()
                        else -> 0
                    }
                    
                    // Load more only when at the very last item
                    if (lastVisibleItem >= totalItemCount - 1 && hasMoreCategoryClips && !isCategoryClipsLoading) {
                        openedCategory?.let { cat ->
                            binding.categoryDetailsContainer.categoryClipsLoadingMore.visibility = View.VISIBLE
                            loadCategoryClips(cat.slug, isLoadMore = true)
                        }
                    }
                }
            })

        } else {
            categoryClipsAdapter?.updateData(categoryClipsList, isGrid)
        }
    }

    private fun openCategoryClipFeed(clickedClip: dev.xacnio.kciktv.shared.data.model.ChannelClip) {
        val index = categoryClipsList.indexOf(clickedClip)
        if (index == -1) return
        
        val playDetailsList = categoryClipsList.map { clip ->
            dev.xacnio.kciktv.shared.data.model.ClipPlayDetails(
                id = clip.id,
                title = clip.title,
                clipUrl = clip.url,
                videoUrl = clip.url,
                thumbnailUrl = clip.thumbnailUrl,
                views = clip.views,
                viewCount = clip.views,
                duration = clip.duration,
                createdAt = clip.createdAt,
                startedAt = null,
                isMature = false,
                vodStartsAt = null,
                vod = null,
                category = null, // Could fill this if we have category info
                creator = clip.creator,
                channel = clip.channel,
                livestreamId = null,
                categoryId = null,
                channelId = null, 
                userId = null
            )
        }
        
        // Pass "view" and "day" as default sort for now
        activity.clipFeedManager.openFeed(playDetailsList, index, sort = "view", time = "day", initialCursor = null) 
    }

    private fun openClipInPlayer(clip: dev.xacnio.kciktv.shared.data.model.ChannelClip) {
        val channel = clip.channel ?: return
        
        // Set flag to return to category details after player closes
        if (isCategoryDetailsVisible) {
            activity.returnToCategoryDetails = true
        }
        
        // Hide browse/category views but keep state for returning
        binding.categoryDetailsContainer.root.visibility = View.GONE
        binding.browseScreenContainer.root.visibility = View.GONE
        
        activity.playClip(clip, channel)
    }

    private fun setupClipsLayoutManager(isGrid: Boolean) {
        val recycler = binding.categoryDetailsContainer.categoryClipsRecycler
        
        val isTablet = activity.resources.getBoolean(R.bool.is_tablet)
        val effectiveIsGrid = if (isTablet) true else isGrid
        val tabletSpanCount = activity.resources.getInteger(R.integer.grid_span_count)
        val spanCount = if (isTablet) tabletSpanCount else 2
        
        // Clear recycled view pool to prevent crash
        recycler.recycledViewPool.clear()
        
        if (effectiveIsGrid) {
            recycler.layoutManager = GridLayoutManager(activity, spanCount)
            recycler.setPadding(
                8.dpToPx(activity.resources), 
                8.dpToPx(activity.resources), 
                8.dpToPx(activity.resources), 
                8.dpToPx(activity.resources)
            )
        } else {
            recycler.layoutManager = androidx.recyclerview.widget.LinearLayoutManager(activity)
            recycler.setPadding(
                0, 
                8.dpToPx(activity.resources), 
                0, 
                8.dpToPx(activity.resources)
            )
        }
        recycler.clipToPadding = false
        
        // Update adapter if it exists to refresh view types
        if (categoryClipsAdapter != null) {
            setupCategoryClipsAdapter()
        }
    }

    private fun setupClipsShimmer(isGrid: Boolean) {
        val container = binding.categoryDetailsContainer.categoryClipsShimmer.root as? ViewGroup ?: return
        val gridShimmer = container.findViewById<View>(R.id.gridShimmerContainer)
        val listShimmer = container.findViewById<View>(R.id.listShimmerContainer)
        
        val isTablet = activity.resources.getBoolean(R.bool.is_tablet)
        val effectiveIsGrid = if (isTablet) true else isGrid
        
        if (effectiveIsGrid) {
            gridShimmer?.visibility = View.VISIBLE
            listShimmer?.visibility = View.GONE
        } else {
            gridShimmer?.visibility = View.GONE
            listShimmer?.visibility = View.VISIBLE
        }
    }

    private fun setupCategoryStreamsShimmer(isGrid: Boolean) {
        val container = binding.categoryDetailsContainer.categoryDetailsShimmer.root as? ViewGroup ?: return
        val gridShimmer = container.findViewById<View>(R.id.shimmerGridContainer)
        val listShimmer = container.findViewById<View>(R.id.shimmerListContainer)
        
        val isTablet = activity.resources.getBoolean(R.bool.is_tablet)
        val effectiveIsGrid = if (isTablet) true else isGrid
        
        if (effectiveIsGrid) {
            gridShimmer?.visibility = View.VISIBLE
            listShimmer?.visibility = View.GONE
        } else {
            gridShimmer?.visibility = View.GONE
            listShimmer?.visibility = View.VISIBLE
        }
    }

    private fun setupStreamsScrollListener() {
        val recyclerView = binding.categoryDetailsContainer.categoryStreamsRecycler
        
        // Remove any existing scroll listeners to avoid duplicates
        recyclerView.clearOnScrollListeners()
        
        // Increase view cache to prevent re-binding when scrolling back
        recyclerView.setItemViewCacheSize(20)
        
        recyclerView.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) {
                super.onScrolled(recyclerView, dx, dy)
                
                // Only trigger load more when scrolling down
                if (dy <= 0) return
                
                val layoutManager = recyclerView.layoutManager
                val totalItemCount = layoutManager?.itemCount ?: 0
                val lastVisibleItem = when (layoutManager) {
                    is GridLayoutManager -> layoutManager.findLastVisibleItemPosition()
                    is LinearLayoutManager -> layoutManager.findLastVisibleItemPosition()
                    else -> 0
                }
                
                // Load more when 3 items remaining (smoother experience)
                val threshold = 3
                if (lastVisibleItem >= totalItemCount - threshold && 
                    totalItemCount > 0 && 
                    hasMoreCategoryStreams && 
                    !isCategoryStreamsLoading) {
                    loadCategoryStreams(isLoadMore = true)
                }
            }
        })
    }

}

