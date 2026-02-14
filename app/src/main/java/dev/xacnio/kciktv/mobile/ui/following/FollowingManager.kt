/**
 * File: FollowingManager.kt
 *
 * Description: Manages business logic, state, and UI interactions for Following.
 *
 * Author: Xacnio
 *
 */
package dev.xacnio.kciktv.mobile.ui.following

import android.content.res.ColorStateList
import android.graphics.Color
import android.util.Log
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.bumptech.glide.load.engine.DiskCacheStrategy
import dev.xacnio.kciktv.shared.util.Constants
import dev.xacnio.kciktv.mobile.MobilePlayerActivity
import dev.xacnio.kciktv.R
import dev.xacnio.kciktv.shared.data.model.ChannelItem
import dev.xacnio.kciktv.shared.util.CategoryUtils
import dev.xacnio.kciktv.shared.util.ShimmerDrawable
import dev.xacnio.kciktv.shared.util.dpToPx
import dev.xacnio.kciktv.shared.util.ThumbnailCacheHelper
import jp.wasabeef.glide.transformations.BlurTransformation
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import dev.xacnio.kciktv.mobile.ui.browse.BrowseManager
import dev.xacnio.kciktv.shared.data.model.ChannelVideo
import dev.xacnio.kciktv.shared.data.model.TopCategory
import dev.xacnio.kciktv.shared.data.prefs.AppPreferences
import dev.xacnio.kciktv.shared.ui.adapter.FollowingCategoriesAdapter
import dev.xacnio.kciktv.shared.ui.adapter.OfflineChannelAdapter

class FollowingManager(private val activity: MobilePlayerActivity) {
    private val binding = activity.binding
    private val prefs = activity.prefs
    private val repository = activity.repository

    private var followingNextCursor: String? = null
    private var isFollowingLoading = false
    private var followingLiveChannelsList = mutableListOf<ChannelItem>()
    private var followingOfflineChannelsList = mutableListOf<ChannelItem>()
    private var followingLiveAdapter: RecyclerView.Adapter<RecyclerView.ViewHolder>? = null
    private var followingOfflineAdapter: dev.xacnio.kciktv.shared.ui.adapter.OfflineChannelAdapter? = null
    private var followingCategoriesAdapter: dev.xacnio.kciktv.shared.ui.adapter.FollowingCategoriesAdapter? = null

    private val TAG = "FollowingManager"

    fun showFollowingScreen() {
        // Only pause player if NOT in mini-player mode
        if (!activity.miniPlayerManager.isMiniPlayerMode) {
            activity.ivsPlayer?.pause()
        }
        
        // Show following screen, hide others
        binding.mobileHeader.visibility = View.VISIBLE
        binding.followingScreenContainer.root.visibility = View.VISIBLE
        binding.homeScreenContainer.root.visibility = View.GONE
        binding.browseScreenContainer.root.visibility = View.GONE
        binding.searchContainer.visibility = View.GONE
        binding.channelProfileContainer.root.visibility = View.GONE
        if (!activity.miniPlayerManager.isMiniPlayerMode) {
            binding.playerScreenContainer.visibility = View.GONE
        }
        activity.updateNavigationBarColor(false) // Transparent for following screen
        activity.isHomeScreenVisible = false
        activity.setCurrentScreen(MobilePlayerActivity.AppScreen.FOLLOWING)
        
        // Setup listeners (only once)
        setupFollowingScreen()
        
        // Check Login Status for UI Setup
        val container = binding.followingScreenContainer
        container.followingSwipeRefresh.visibility = View.VISIBLE
        container.followingSwipeRefresh.isEnabled = true
        
        if (!prefs.isLoggedIn) {
            // Show Login Required UI
            container.followingEmptyState.visibility = View.VISIBLE
            container.followingEmptyStateText.text = activity.getString(R.string.login_required_following)
            container.followingLoginButton.visibility = View.VISIBLE
            
            container.followingLoginButton.setOnClickListener {
                val intent = android.content.Intent(activity, dev.xacnio.kciktv.mobile.LoginActivity::class.java)
                activity.startActivity(intent)
            }
            
            // Hide Content (but NOT Continue Watching container specifically, let loadContinueWatching handle it)
            container.followingLiveSection.visibility = View.GONE
            container.followingOfflineSection.visibility = View.GONE
            container.followingCategoriesSection.visibility = View.GONE
            container.followingShimmerLayout.root.visibility = View.GONE
            
        } else {
            // Logged In - Reset UI state
            container.followingEmptyStateText.text = activity.getString(R.string.following_empty_state)
            container.followingLoginButton.visibility = View.GONE
        }
        
        // Only load data if not already loaded (pull-to-refresh still works)
        if (!activity.followingDataLoaded) {
            loadFollowingData()
        }
        
        // Always load continue watching (it handles visibility internally)
        if (!activity.miniPlayerManager.isMiniPlayerMode) {
            loadContinueWatching()
        }
    }

    private fun setupFollowingScreen() {
        val container = binding.followingScreenContainer
        
        // Swipe refresh
        container.followingSwipeRefresh.setOnRefreshListener {
            followingNextCursor = null
            loadFollowingData()
        }
        
        // Load more button
        container.followingLoadMoreButton.setOnClickListener {
            loadFollowingData(isLoadMore = true)
        }
        
        // Bottom Nav Sync handled by MobilePlayerActivity
        
        if (activity.binding.mainBottomNavigation.selectedItemId != R.id.nav_following) {
            activity.isNavigationProgrammatic = true
            activity.binding.mainBottomNavigation.selectedItemId = R.id.nav_following
            activity.isNavigationProgrammatic = false
        }
    }

    fun loadFollowingData(isLoadMore: Boolean = false) {
        if (isFollowingLoading) return
        
        isFollowingLoading = true
        val container = binding.followingScreenContainer
        
        if (isLoadMore) {
            container.followingLoadMoreButton.isEnabled = false
            container.followingLoadMoreButton.text = ""
            container.followingLoadMoreSpinner.visibility = View.VISIBLE
        } else {
            container.followingShimmerLayout.root.visibility = View.VISIBLE
            // Reset shimmer sections visibility
            container.followingShimmerLayout.shimmerCategoriesSection.visibility = View.VISIBLE
            // Keep swipe refresh VISIBLE so Header and Continue Watching are shown
            container.followingSwipeRefresh.visibility = View.VISIBLE
            followingNextCursor = null
            
            // Clean up existing lists visuals if needed, but keep data until reload?
            // Better to hide lists so shimmer is the only "content" below headers
            container.followingLiveSection.visibility = View.GONE
            container.followingOfflineSection.visibility = View.GONE
            container.followingCategoriesSection.visibility = View.GONE
            container.followingEmptyState.visibility = View.GONE
            
            // Load followed categories (only on fresh load/refresh and if logged in)
            if (prefs.isLoggedIn) {
                loadFollowedCategories()
            }
            loadContinueWatching()
        }
        
        if (!prefs.isLoggedIn) {
            container.followingSwipeRefresh.isRefreshing = false
            container.followingShimmerLayout.root.visibility = View.GONE
            container.followingEmptyState.visibility = View.VISIBLE
            container.followingEmptyStateText.text = activity.getString(R.string.login_required_following)
            container.followingLoginButton.visibility = View.VISIBLE
            isFollowingLoading = false
            return
        }
        
        activity.lifecycleScope.launch(Dispatchers.IO) {
            try {
                val token = prefs.authToken ?: ""
                val result = repository.getFollowingLiveStreams(token, followingNextCursor)
                val data = result.getOrNull()
                
                if (data != null) {
                    val allChannels = data.channels
                    val liveChannels = allChannels.filter { it.isLive }
                    val offlineChannels = allChannels.filter { !it.isLive }
                    followingNextCursor = data.nextCursor
                    
                    withContext(Dispatchers.Main) {
                        container.followingSwipeRefresh.isRefreshing = false
                        container.followingShimmerLayout.root.visibility = View.GONE
                        container.followingSwipeRefresh.visibility = View.VISIBLE
                        
                        if (!isLoadMore) {
                            followingLiveChannelsList.clear()
                            followingOfflineChannelsList.clear()
                        }
                        
                        if (allChannels.isEmpty() && followingLiveChannelsList.isEmpty() && followingOfflineChannelsList.isEmpty()) {
                            container.followingEmptyState.visibility = View.VISIBLE
                            container.followingLiveSection.visibility = View.GONE
                            container.followingOfflineSection.visibility = View.GONE
                            container.followingLoadMoreButton.visibility = View.GONE
                        } else {
                            container.followingEmptyState.visibility = View.GONE
                            
                            // Update Lists
                            followingLiveChannelsList.addAll(liveChannels)
                            followingOfflineChannelsList.addAll(offlineChannels)
                            
                            // Live channels section
                            if (followingLiveChannelsList.isNotEmpty()) {
                                container.followingLiveSection.visibility = View.VISIBLE
                                setupFollowingLiveAdapter(container.followingLiveRecycler)
                                followingLiveAdapter?.notifyDataSetChanged()
                            } else {
                                container.followingLiveSection.visibility = View.GONE
                            }
                            
                            // Offline channels section
                            // Offline channels section
                            if (followingOfflineChannelsList.isNotEmpty()) {
                                container.followingOfflineSection.visibility = View.VISIBLE
                                setupFollowingOfflineAdapter(container.followingOfflineRecycler)
                                
                                // Force DiffUtil to detect changes
                                val newOfflineList = followingOfflineChannelsList.toList()
                                followingOfflineAdapter?.submitList(newOfflineList) {
                                     followingOfflineAdapter?.notifyDataSetChanged()
                                }
                            } else {
                                container.followingOfflineSection.visibility = View.GONE
                            }
                            
                            // Load more button
                        val hasMore = followingNextCursor != null
                        container.followingLoadMoreButton.visibility = if (hasMore) View.VISIBLE else View.GONE
                        container.followingLoadMoreButton.isEnabled = true
                        container.followingLoadMoreButton.setText(R.string.load_more)
                        if (isLoadMore) container.followingLoadMoreSpinner.visibility = View.GONE
                        }
                        activity.followingDataLoaded = true
                    }
                } else {
                    withContext(Dispatchers.Main) {
                        container.followingSwipeRefresh.isRefreshing = false
                        container.followingShimmerLayout.root.visibility = View.GONE
                        container.followingSwipeRefresh.visibility = View.VISIBLE
                        Toast.makeText(activity, activity.getString(R.string.data_load_failed), Toast.LENGTH_SHORT).show()
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to load following data", e)
                withContext(Dispatchers.Main) {
                    container.followingSwipeRefresh.isRefreshing = false
                    container.followingShimmerLayout.root.visibility = View.GONE
                    container.followingSwipeRefresh.visibility = View.VISIBLE
                    if (isLoadMore) {
                         container.followingLoadMoreSpinner.visibility = View.GONE
                         container.followingLoadMoreButton.visibility = View.VISIBLE
                         container.followingLoadMoreButton.isEnabled = true
                         container.followingLoadMoreButton.setText(R.string.load_more)
                    }
                }
            } finally {
                isFollowingLoading = false
            }
        }
    }

    private fun setupFollowingLiveAdapter(recyclerView: RecyclerView?, config: android.content.res.Configuration? = null) {
        if (recyclerView == null) return
        
        // Responsive Layout based on available width
        val displayMetrics = activity.resources.displayMetrics
        val screenWidthDp = displayMetrics.widthPixels / displayMetrics.density
        
        val spanCount = when {
            screenWidthDp >= 840 -> 3 // Landscape Tablet / Desktop
            screenWidthDp >= 580 -> 2 // Portrait Tablet / Large Phone Landscape
            else -> 1 // Phone Portrait / Small Window
        }

        if (recyclerView.layoutManager == null || (recyclerView.layoutManager is androidx.recyclerview.widget.GridLayoutManager && (recyclerView.layoutManager as androidx.recyclerview.widget.GridLayoutManager).spanCount != spanCount) || (spanCount > 1 && recyclerView.layoutManager !is androidx.recyclerview.widget.GridLayoutManager)) {
            if (spanCount > 1) {
                recyclerView.layoutManager = androidx.recyclerview.widget.GridLayoutManager(activity, spanCount).apply {
                    isItemPrefetchEnabled = true
                    initialPrefetchItemCount = spanCount * 2
                }
            } else {
                recyclerView.layoutManager = LinearLayoutManager(activity).apply {
                    isItemPrefetchEnabled = true
                    initialPrefetchItemCount = 4
                }
            }
        }
        
        recyclerView.setItemViewCacheSize(10)
        recyclerView.setHasFixedSize(true)
        
        if (followingLiveAdapter == null) {
            followingLiveAdapter = object : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
            
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
                    val view = activity.layoutInflater.inflate(R.layout.item_following_channel_card, parent, false)
                    // adjust margins for grid vs list
                    val horizontalMargin = if (spanCount > 1) 8.dpToPx(activity.resources) else 0
                    
                    view.layoutParams = RecyclerView.LayoutParams(
                        RecyclerView.LayoutParams.MATCH_PARENT,
                        RecyclerView.LayoutParams.WRAP_CONTENT
                    ).apply {
                        bottomMargin = 16.dpToPx(activity.resources)
                        leftMargin = horizontalMargin
                        rightMargin = horizontalMargin
                    }
                    return ViewHolder(view)
                }
                
                override fun getItemCount(): Int = followingLiveChannelsList.size
                
                override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
                    if (holder !is ViewHolder) return
                    val channel = followingLiveChannelsList[position]
                    
                    var thumbnailUrl = channel.thumbnailUrl
                    
                    val profileUrl = channel.getEffectiveProfilePicUrl()

                    val defaultThumb = Constants.Urls.DEFAULT_LIVESTREAM_THUMBNAIL
                    val thumbToLoad = thumbnailUrl ?: defaultThumb
                    
                    // Only reload if URL changed
                    if (holder.thumbnail.tag != thumbToLoad) {
                        holder.thumbnail.tag = thumbToLoad
                        Glide.with(activity)
                            .load(thumbToLoad)
                            .signature(ThumbnailCacheHelper.getCacheSignature())
                            .placeholder(ShimmerDrawable(isCircle = false))
                            .error(
                                Glide.with(activity)
                                    .load(defaultThumb)
                                    .transform(BlurTransformation(25, 3))
                            )
                            .into(holder.thumbnail)
                    }
                    
                    // Only reload if URL changed
                    if (holder.profilePic.tag != profileUrl) {
                        holder.profilePic.tag = profileUrl
                        Glide.with(activity)
                            .load(profileUrl)
                            .circleCrop()
                            .placeholder(R.drawable.default_avatar)
                            .into(holder.profilePic)
                    }
                    
                    holder.username.text = channel.username
                    val localizedCategory = CategoryUtils.getLocalizedCategoryName(
                        holder.category.context,
                        channel.categoryName,
                        channel.categorySlug
                    )
                    holder.category.text = localizedCategory
                    holder.streamerName.text = channel.title
                    
                    val viewers = channel.viewerCount
                    holder.viewerCount.text = activity.getString(R.string.watching_count_label, activity.formatViewerCount(viewers.toLong()))
                    
                    holder.tagsContainer?.let { container ->
                        container.removeAllViews()
                        
                        channel.language?.let { lang ->
                            val displayText = activity.getLanguageName(lang)
                            val tagView = activity.layoutInflater.inflate(R.layout.item_tag, container, false) as TextView
                            tagView.text = displayText.uppercase()
                            container.addView(tagView)
                        }
                        
                        if (channel.isMature) {
                            val tagView = activity.layoutInflater.inflate(R.layout.item_tag, container, false) as TextView
                            tagView.text = activity.getString(R.string.rating_18_plus)
                            tagView.setTextColor(Color.parseColor("#FF5252"))
                            container.addView(tagView)
                        }
                        
                        channel.tags?.take(3)?.forEach { tag ->
                            val tagView = activity.layoutInflater.inflate(R.layout.item_tag, container, false) as TextView
                            tagView.text = tag
                            container.addView(tagView)
                        }
                    }
                    
                    holder.itemView.setOnClickListener {
                        activity.openChannel(channel)
                    }
                    
                    // Profile pic click opens channel profile
                    holder.profilePic.setOnClickListener {
                        activity.channelProfileManager.openChannelProfile(channel.slug)
                    }
                }
            }
        }
        
        if (recyclerView.adapter == null) {
             recyclerView.adapter = followingLiveAdapter
        }
    }

    private fun setupFollowingOfflineAdapter(recyclerView: RecyclerView?, config: android.content.res.Configuration? = null) {
        if (recyclerView == null) return
        
        // Responsive Layout based on available width
        val displayMetrics = activity.resources.displayMetrics
        val screenWidthDp = displayMetrics.widthPixels / displayMetrics.density
        
        val spanCount = when {
            screenWidthDp >= 840 -> 3 // Landscape Tablet / Desktop
            screenWidthDp >= 580 -> 2 // Portrait Tablet / Large Phone Landscape
            else -> 1 // Phone Portrait / Small Window
        }

        if (recyclerView.layoutManager == null || (recyclerView.layoutManager is androidx.recyclerview.widget.GridLayoutManager && (recyclerView.layoutManager as androidx.recyclerview.widget.GridLayoutManager).spanCount != spanCount) || (spanCount > 1 && recyclerView.layoutManager !is androidx.recyclerview.widget.GridLayoutManager)) {
            if (spanCount > 1) {
                recyclerView.layoutManager = androidx.recyclerview.widget.GridLayoutManager(activity, spanCount)
            } else {
                recyclerView.layoutManager = LinearLayoutManager(activity).apply {
                    isItemPrefetchEnabled = false
                }
            }
        }
        
        recyclerView.setItemViewCacheSize(2)
        recyclerView.setHasFixedSize(true)
        
        if (followingOfflineAdapter == null) {
            followingOfflineAdapter = dev.xacnio.kciktv.shared.ui.adapter.OfflineChannelAdapter(
                onChannelClick = { channel ->
                    activity.openChannel(channel)
                },
                onProfileClick = { channel ->
                    activity.channelProfileManager.openChannelProfile(channel.slug)
                },
                onUnfollowClick = { channel ->
                    showUnfollowDialog(channel)
                },
                themeColor = prefs.themeColor
            )
        }
        
        if (recyclerView.adapter == null) {
             recyclerView.adapter = followingOfflineAdapter
        }
    }

    fun loadFollowedCategories() {
        val container = binding.followingScreenContainer
        
        activity.lifecycleScope.launch(Dispatchers.IO) {
            try {
                val token = prefs.authToken ?: ""
                val result = repository.getFollowedCategories(token)
                val categories = result.getOrNull()
                
                withContext(Dispatchers.Main) {
                    // Hide categories shimmer when categories load (regardless of result)
                    container.followingShimmerLayout.shimmerCategoriesSection.visibility = View.GONE
                    
                    if (categories != null && categories.isNotEmpty()) {
                        container.followingCategoriesSection.visibility = View.VISIBLE
                        setupFollowingCategoriesAdapter(container.followingCategoriesRecycler, categories)
                    } else {
                        container.followingCategoriesSection.visibility = View.GONE
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to load followed categories", e)
                withContext(Dispatchers.Main) {
                    // Hide categories shimmer even on error
                    container.followingShimmerLayout.shimmerCategoriesSection.visibility = View.GONE
                    container.followingCategoriesSection.visibility = View.GONE
                }
            }
        }
    }

    private fun setupFollowingCategoriesAdapter(recyclerView: RecyclerView?, categories: List<dev.xacnio.kciktv.shared.data.model.TopCategory>) {
        if (recyclerView == null) return
        
        // Add SnapHelper just like Home screen
        if (recyclerView.onFlingListener == null) {
            androidx.recyclerview.widget.LinearSnapHelper().attachToRecyclerView(recyclerView)
        }
        
        if (followingCategoriesAdapter == null) {
            followingCategoriesAdapter = dev.xacnio.kciktv.shared.ui.adapter.FollowingCategoriesAdapter(categories) { category ->
                // Open Category Details
                // Assuming BrowseManager handles opening category details. 
                // We need to access it via activity.browseManager (if available) or similar.
                // MobilePlayerActivity should have browseManager public.
                try {
                    activity.browseManager.openCategoryDetails(category)
                } catch (e: Exception) {
                     Log.e(TAG, "Error opening category details: ${e.message}")
                }
            }
            recyclerView.adapter = followingCategoriesAdapter
        } else {
            followingCategoriesAdapter?.updateData(categories)
        }
    }

    private var continueWatchingList = mutableListOf<dev.xacnio.kciktv.shared.data.prefs.AppPreferences.VodWatchState>()
    private var continueWatchingAdapter: RecyclerView.Adapter<RecyclerView.ViewHolder>? = null

    fun loadContinueWatching() {
        val history = prefs.getVodWatchHistory()
        continueWatchingList.clear()
        continueWatchingList.addAll(history)
        
        val container = binding.followingScreenContainer
        
        container.followingHistoryClearButton.setOnClickListener {
            prefs.clearVodWatchHistory()
            continueWatchingList.clear()
            continueWatchingAdapter?.notifyDataSetChanged()
            container.followingContinueWatchingSection.visibility = View.GONE
        }
        
        if (continueWatchingList.isNotEmpty()) {
            container.followingContinueWatchingSection.visibility = View.VISIBLE
            setupContinueWatchingAdapter(container.followingContinueWatchingRecycler)
            continueWatchingAdapter?.notifyDataSetChanged()
        } else {
            container.followingContinueWatchingSection.visibility = View.GONE
        }
    }

    private fun setupContinueWatchingAdapter(recyclerView: RecyclerView?) {
        if (recyclerView == null) return
        
        if (recyclerView.layoutManager == null) {
            recyclerView.layoutManager = LinearLayoutManager(activity, LinearLayoutManager.HORIZONTAL, false)
        }
        
        if (continueWatchingAdapter == null) {
            continueWatchingAdapter = object : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
                
                inner class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
                    val thumbnail: ImageView = view.findViewById(R.id.videoThumbnail)
                    val durationBadge: TextView = view.findViewById(R.id.videoDuration)
                    val progressBar: android.widget.ProgressBar = view.findViewById(R.id.watchProgressBar)
                    val profilePic: ImageView = view.findViewById(R.id.channelProfilePic)
                    val title: TextView = view.findViewById(R.id.videoTitle)
                    val channelName: TextView = view.findViewById(R.id.channelName)
                    val removeButton: ImageView = view.findViewById(R.id.removeButton)
                }

                override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
                    val view = activity.layoutInflater.inflate(R.layout.item_continue_watching, parent, false)
                    return ViewHolder(view)
                }

                override fun getItemCount(): Int = continueWatchingList.size

                override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
                    if (holder !is ViewHolder) return
                    val state = continueWatchingList[position]
                    
                    val defaultThumb = Constants.Urls.DEFAULT_LIVESTREAM_THUMBNAIL
                    Glide.with(activity)
                        .load(state.thumbnailUrl ?: defaultThumb)
                        .placeholder(ShimmerDrawable(isCircle = false))
                        .error(Glide.with(activity).load(defaultThumb))
                        .into(holder.thumbnail)
                        
                    Glide.with(activity)
                        .load(state.profilePic)
                        .circleCrop()
                        .placeholder(R.drawable.ic_user)
                        .into(holder.profilePic)
                        
                    holder.title.text = state.videoTitle
                    holder.channelName.text = state.channelName
                    
                    if (state.duration > 0) {
                        val progress = ((state.watchedDuration.toFloat() / state.duration) * 1000).toInt()
                        holder.progressBar.progress = progress
                        holder.progressBar.max = 1000
                        
                        // Remaining time or total duration?
                        // Format: 10:00 (Total) or Remaining? Usually total duration on badge.
                        holder.durationBadge.text = formatDuration(state.duration)
                        holder.durationBadge.visibility = View.VISIBLE
                    } else {
                        holder.progressBar.progress = 0
                        holder.durationBadge.visibility = View.GONE
                    }
                    
                    holder.itemView.setOnClickListener {
                        // Play Video
                        // We need to create a ChannelVideo object and ChannelItem
                        // State has most info.
                        
                        val channel = ChannelItem(
                            id = state.channelId ?: "",
                            slug = state.channelSlug ?: "",
                            username = state.channelName ?: "",
                            title = null,
                            viewerCount = 0,
                            thumbnailUrl = null,
                            profilePicUrl = state.profilePic,
                            playbackUrl = null,
                            categoryName = state.categoryName,
                            categorySlug = state.categorySlug,
                            language = null
                        )
                        
                        val video = dev.xacnio.kciktv.shared.data.model.ChannelVideo(
                            topId = state.videoId.toLongOrNull(),
                            topLiveStreamId = null,
                            uuid = state.videoId,
                            createdAt = null,
                            updatedAt = null,
                            topSessionTitle = state.videoTitle,
                            topDuration = state.duration,
                            topViewerCount = 0,
                            views = 0,
                            topLanguage = null,
                            topIsMature = null,
                            topCategories = if (state.categoryName != null && state.categorySlug != null) {
                                listOf(
                                    dev.xacnio.kciktv.shared.data.model.TopCategory(
                                        id = 0L,
                                        categoryId = 0L,
                                        name = state.categoryName,
                                        slug = state.categorySlug,
                                        tags = null,
                                        description = null,
                                        viewers = 0,
                                        followersCount = 0,
                                        banner = null,
                                        parentCategory = null
                                    )
                                )
                            } else null,
                            topThumbnail = if (state.thumbnailUrl != null) dev.xacnio.kciktv.shared.data.model.VideoThumbnail(state.thumbnailUrl, null) else null,
                            topThumb = state.thumbnailUrl,
                            topIsLive = false,
                            source = state.sourceUrl,
                            livestream = null
                        )
                        
                        // playVideo expects ChannelVideo and ChannelData
                        activity.playVideo(video, channel, state.watchedDuration)
                    }

                    holder.profilePic.setOnClickListener {
                        state.channelSlug?.let { slug ->
                            if (slug.isNotEmpty()) {
                                activity.channelProfileManager.openChannelProfile(slug)
                            }
                        }
                    }

                    holder.removeButton.setOnClickListener {
                        val videoId = state.videoId
                        if (videoId.isNotEmpty()) {
                            prefs.removeVodProgress(videoId)

                            val currentPos = holder.adapterPosition
                            if (currentPos != RecyclerView.NO_POSITION && currentPos < continueWatchingList.size) {
                                continueWatchingList.removeAt(currentPos)
                                notifyItemRemoved(currentPos)

                                if (continueWatchingList.isEmpty()) {
                                    activity.binding.followingScreenContainer.followingContinueWatchingSection.visibility = View.GONE
                                }
                            }
                        }
                    }
                }
            }
            recyclerView.adapter = continueWatchingAdapter
        }
    }

    private fun formatDuration(ms: Long): String {
        val totalSeconds = ms / 1000
        val hours = totalSeconds / 3600
        val minutes = (totalSeconds % 3600) / 60
        val seconds = totalSeconds % 60
        return if (hours > 0) {
            String.format("%d:%02d:%02d", hours, minutes, seconds)
        } else {
            String.format("%d:%02d", minutes, seconds)
        }
    }

    fun updateVideoProgress(videoId: String, watchedDuration: Long, totalDuration: Long) {
        val index = continueWatchingList.indexOfFirst { it.videoId == videoId }
        if (index != -1) {
            val item = continueWatchingList[index]
            // Update item in list
            // VodWatchState is a data class, so we copy it
            continueWatchingList[index] = item.copy(watchedDuration = watchedDuration, duration = totalDuration)
            
            // Notify only that item changed
            // Using payload to avoid flickering if possible, or just standard notify
            continueWatchingAdapter?.notifyItemChanged(index)
        } else {
            // New video not in list yet? Reload fully to be safe and insert it correctly
            loadContinueWatching()
        }
    }

    fun updateLayout(config: android.content.res.Configuration? = null) {
        if (binding.followingScreenContainer.root.visibility == View.VISIBLE) {
            setupFollowingLiveAdapter(binding.followingScreenContainer.followingLiveRecycler, config)
            setupFollowingOfflineAdapter(binding.followingScreenContainer.followingOfflineRecycler, config)
        }
    }

    private fun showUnfollowDialog(channel: ChannelItem) {
        val title = activity.getString(R.string.unfollow_confirm_title, channel.username)
        
        com.google.android.material.dialog.MaterialAlertDialogBuilder(activity)
            .setTitle(title)
            .setMessage(activity.getString(R.string.unfollow_confirm_title, channel.username))
            .setPositiveButton(activity.getString(R.string.unfollow)) { _, _ ->
                performUnfollow(channel)
            }
            .setNegativeButton(activity.getString(R.string.cancel), null)
            .show()
    }

    private fun performUnfollow(channel: ChannelItem) {
        activity.lifecycleScope.launch(Dispatchers.IO) {
            val token = prefs.authToken ?: return@launch
            val result = repository.unfollowChannel(channel.slug, token)
            
            withContext(Dispatchers.Main) {
                if (result.isSuccess) {
                    val index = followingOfflineChannelsList.indexOfFirst { it.slug == channel.slug }
                    if (index != -1) {
                        followingOfflineChannelsList.removeAt(index)
                        followingOfflineAdapter?.submitList(followingOfflineChannelsList.toList())
                        
                        Toast.makeText(activity, "${activity.getString(R.string.unfollowed)} ${channel.username}", Toast.LENGTH_SHORT).show()
                        
                        if (followingOfflineChannelsList.isEmpty()) {
                            binding.followingScreenContainer.followingOfflineSection.visibility = View.GONE
                        }
                        
                         if (followingLiveChannelsList.isEmpty() && followingOfflineChannelsList.isEmpty()) {
                             binding.followingScreenContainer.followingEmptyState.visibility = View.VISIBLE
                         }
                    }
                } else {
                    Toast.makeText(activity, activity.getString(R.string.operation_failed, result.exceptionOrNull()?.message), Toast.LENGTH_SHORT).show()
                }
            }
        }
    }
}
