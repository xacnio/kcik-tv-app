/**
 * File: HomeScreenManager.kt
 *
 * Description: Manages business logic, state, and UI interactions for Home Screen.
 *
 * Author: Xacnio
 *
 */
package dev.xacnio.kciktv.mobile.ui.home

import android.graphics.Color
import android.util.Log
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.LinearSnapHelper
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.bumptech.glide.load.engine.DiskCacheStrategy
import dev.xacnio.kciktv.shared.util.Constants
import dev.xacnio.kciktv.mobile.MobilePlayerActivity
import dev.xacnio.kciktv.R
import dev.xacnio.kciktv.shared.data.model.ChannelItem
import dev.xacnio.kciktv.shared.data.model.TopCategory
import dev.xacnio.kciktv.shared.data.model.BrowseClip
import dev.xacnio.kciktv.shared.data.model.ClipPlayDetails
import dev.xacnio.kciktv.shared.data.model.ClipChannel
import dev.xacnio.kciktv.shared.data.model.ClipCategory
import dev.xacnio.kciktv.shared.data.model.ClipCreator
import dev.xacnio.kciktv.shared.util.ShimmerDrawable
import dev.xacnio.kciktv.shared.util.CategoryUtils
import dev.xacnio.kciktv.shared.util.ThumbnailCacheHelper
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import androidx.lifecycle.lifecycleScope
import dev.xacnio.kciktv.shared.data.util.PreloadCache

/**
 * Manages Home Screen data loading, adapters, and dynamic sections.
 */
class HomeScreenManager(private val activity: MobilePlayerActivity) {
    
    private val binding get() = activity.binding
    private val prefs get() = activity.prefs
    private val repository get() = activity.repository
    private val TAG = "HomeScreenManager"

    fun loadHomeScreenData() {
        val isSwipeRefreshing = binding.homeScreenContainer.homeSwipeRefresh.isRefreshing
        
        // Unified check for preloaded data
        val hasPreloadedData = PreloadCache.categories != null && 
                               PreloadCache.featuredStreams != null && 
                               (!prefs.isLoggedIn || PreloadCache.followingStreams != null)
        
        Log.d("Preload", "HomeScreen - Check: hasPreloaded=$hasPreloadedData (Cats=${PreloadCache.categories != null}, Featured=${PreloadCache.featuredStreams != null}, Following=${PreloadCache.followingStreams != null})")

        // Priority 1: If we have preloaded data, hide ALL loading indicators IMMEDIATELY
        if (hasPreloadedData) {
            binding.startupLoadingOverlay.visibility = View.GONE
            binding.homeScreenContainer.homeSwipeRefresh.visibility = View.VISIBLE
            binding.homeScreenContainer.homeShimmerLayout.root.visibility = View.GONE
            binding.homeScreenContainer.homeCategoriesSection.visibility = View.VISIBLE
        } 
        // Priority 2: If we are NOT pull-to-refreshing and have NO data, show shimmer/loading
        else if (!isSwipeRefreshing) {
            binding.homeScreenContainer.homeShimmerLayout.root.visibility = View.VISIBLE
            binding.homeScreenContainer.homeSwipeRefresh.visibility = View.GONE
            binding.startupLoadingOverlay.visibility = View.VISIBLE
            
            // Hide following shimmer if not logged in
            val shimmerFollowingSection = binding.homeScreenContainer.homeShimmerLayout.root.findViewById<View>(R.id.shimmerFollowingSection)
            if (shimmerFollowingSection != null) {
                shimmerFollowingSection.visibility = if (prefs.isLoggedIn) View.VISIBLE else View.GONE
            }
        }
        
        activity.lifecycleScope.launch {
            try {
                // Load categories (Check cache first)
                val catTask = async(Dispatchers.IO) { 
                    PreloadCache.categories?.let { Result.success(it) } ?: repository.getTopCategories() 
                }
                
                // Load featured/live channels (Check cache first)
                val featuredTask = async(Dispatchers.IO) { 
                    PreloadCache.featuredStreams?.let { Result.success(it) } ?: run {
                        val langs = prefs.streamLanguages.toList()
                        repository.getFilteredLiveStreams(languages = if (langs.isNotEmpty()) langs else null, after = null, sort = "featured")
                    }
                }
                
                // Load following if logged in (Check cache first)
                val followingTask: Deferred<List<ChannelItem>>? = if (prefs.isLoggedIn) {
                    async(Dispatchers.IO) {
                        PreloadCache.followingStreams ?: try {
                            val response = repository.getFollowingLiveStreams(prefs.authToken ?: "")
                            response.getOrNull()?.channels ?: emptyList()
                        } catch (e: Exception) {
                            emptyList<ChannelItem>()
                        }
                    }
                } else null

                // Process categories
                val catResult = catTask.await()
                val categories = catResult.getOrNull() ?: emptyList()
                withContext(Dispatchers.Main) {
                    if (categories.isNotEmpty()) {
                        binding.homeScreenContainer.homeCategoriesSection.visibility = View.VISIBLE
                        setupHomeCategoryAdapter(binding.homeScreenContainer.homeCategoriesRecycler, categories)
                        
                        // Add dynamic sections - defer to avoid UI freeze
                        binding.homeScreenContainer.homeDynamicSectionsContainer.removeAllViews()
                        
                        // Stagger the section creation to prevent UI jank
                        val sectionsToAdd = categories.filter { !prefs.isCategoryBlocked(it.name) }.take(5)
                        sectionsToAdd.forEachIndexed { index, category ->
                            binding.root.postDelayed({
                                if (!activity.isFinishing && !activity.isDestroyed) {
                                    addCategorySection(category)
                                }
                            }, (index * 50).toLong()) // 50ms delay between each section
                        }
                    } else {
                        binding.homeScreenContainer.homeCategoriesSection.visibility = View.GONE
                    }
                }

                // Process featured
                val featuredResult = featuredTask.await()
                val channels = featuredResult.getOrNull()?.channels ?: emptyList()
                val blocked = prefs.blockedCategories
                val filteredFeatured = channels.filter { !blocked.contains(it.categoryName) }.take(12)
                
                withContext(Dispatchers.Main) {
                    if (filteredFeatured.isNotEmpty()) {
                        setupHomeChannelAdapter(binding.homeScreenContainer.homeLiveChannelsRecycler, filteredFeatured)
                    }
                }

                // Process following - only show LIVE channels on home screen
                followingTask?.await()?.let { followingChannels ->
                    val liveFollowingChannels = followingChannels.filter { it.isLive && !blocked.contains(it.categoryName) }
                    withContext(Dispatchers.Main) {
                        if (liveFollowingChannels.isNotEmpty()) {
                            binding.homeScreenContainer.homeFollowingSection.visibility = View.VISIBLE
                            setupHomeChannelAdapter(binding.homeScreenContainer.homeFollowingRecycler, liveFollowingChannels)
                        } else {
                            binding.homeScreenContainer.homeFollowingSection.visibility = View.GONE
                        }
                    }
                }

                // Load popular clips
                val clipsResponse = try {
                    repository.getBrowseClips(sort = "view", time = "day", cursor = null)
                } catch (e: Exception) {
                    null
                }
                
                val clips = clipsResponse?.clips ?: emptyList()
                withContext(Dispatchers.Main) {
                    if (clips.isNotEmpty()) {
                        binding.homeScreenContainer.homePopularClipsSection.visibility = View.VISIBLE
                        setupHomeClipAdapter(binding.homeScreenContainer.homePopularClipsRecycler, clips.take(12))
                        
                        // Header click navigates to Browse > Clips
                        binding.homeScreenContainer.homePopularClipsHeader.setOnClickListener {
                            activity.browseManager.showBrowseScreen(2) // Clips tab
                        }
                    } else {
                        binding.homeScreenContainer.homePopularClipsSection.visibility = View.GONE
                    }
                }

            } catch (e: Exception) {
                Log.e(TAG, "Failed to load home screen data", e)
            } finally {
                withContext(Dispatchers.Main) {
                    binding.homeScreenContainer.homeSwipeRefresh.isRefreshing = false
                    binding.homeScreenContainer.homeShimmerLayout.root.visibility = View.GONE
                    binding.homeScreenContainer.homeSwipeRefresh.visibility = View.VISIBLE
                    binding.startupLoadingOverlay.visibility = View.GONE
                    activity.homeDataLoaded = true
                    
                    // Clear preload cache after first successful load
                    PreloadCache.clear()
                }
            }
        }
    }

    private fun setupHomeChannelAdapter(recyclerView: RecyclerView?, channels: List<ChannelItem>) {
        if (recyclerView == null) return
        
        if (recyclerView.layoutManager == null) {
            recyclerView.layoutManager = LinearLayoutManager(activity, LinearLayoutManager.HORIZONTAL, false).apply {
                isItemPrefetchEnabled = false // Disable prefetch for lazy loading
            }
        }
        
        // Cache views for better scroll performance
        recyclerView.setItemViewCacheSize(10)
        
        // Add SnapHelper for slider behavior
        // Add SnapHelper for slider behavior (Phone only)
        val isTablet = activity.resources.getBoolean(R.bool.is_tablet)
        if (!isTablet && recyclerView.onFlingListener == null) {
            LinearSnapHelper().attachToRecyclerView(recyclerView)
        }
        
        // Simple inline adapter for now
        recyclerView.adapter = object : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
            
            inner class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
                val thumbnail: ImageView = view.findViewById(R.id.channelThumbnail)
                val profilePic: ImageView = view.findViewById(R.id.channelProfilePic)
                val username: TextView = view.findViewById(R.id.channelUsername)
                val category: TextView = view.findViewById(R.id.channelCategory)
                val streamerName: TextView = view.findViewById(R.id.channelStreamerName)
                val viewerCount: TextView = view.findViewById(R.id.viewerCount)
                val tagsContainer: LinearLayout = view.findViewById(R.id.tagsContainer)
            }
            
            override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
                val view = activity.layoutInflater.inflate(R.layout.item_home_channel_card, parent, false)
                return ViewHolder(view)
            }
            
            override fun getItemCount(): Int = channels.size
            
            override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
                if (holder !is ViewHolder) return
                val channel = channels[position]
                
                // Thumbnail
                // Try to use provided thumbnail, or construct one if missing
                var thumbnailUrl = channel.thumbnailUrl
                if (thumbnailUrl.isNullOrEmpty()) {
                     thumbnailUrl = "https://images.kick.com/video_thumbnails/${channel.slug}/uuid/fullsize"
                }
                
                val profileUrl = channel.getEffectiveProfilePicUrl()

                val defaultThumb = Constants.Urls.DEFAULT_LIVESTREAM_THUMBNAIL
                
                // Only reload if URL changed
                if (holder.thumbnail.tag != thumbnailUrl) {
                    holder.thumbnail.tag = thumbnailUrl
                    Glide.with(activity)
                        .load(thumbnailUrl)
                        .signature(ThumbnailCacheHelper.getCacheSignature())
                        .placeholder(ShimmerDrawable(isCircle = false))
                        .thumbnail(Glide.with(activity).load(thumbnailUrl).override(50))
                        .error(
                            Glide.with(activity)
                                .load(defaultThumb)
                                .transform(jp.wasabeef.glide.transformations.BlurTransformation(25, 3))
                        )
                        .into(holder.thumbnail)
                }
                
                // Profile pic - use effective URL (profileUrl already defined above)
                if (holder.profilePic.tag != profileUrl) {
                    holder.profilePic.tag = profileUrl
                    Glide.with(activity)
                        .load(profileUrl)
                        .circleCrop()
                        .placeholder(R.drawable.default_avatar)
                        .thumbnail(Glide.with(activity).load(profileUrl).override(100))
                        .into(holder.profilePic)
                }
                
                holder.username.text = channel.username
                holder.category.text = CategoryUtils.getLocalizedCategoryName(activity, channel.categoryName, channel.categorySlug)
                holder.streamerName.text = channel.title ?: channel.username
                
                // Viewer count
                val viewers = channel.viewerCount
                holder.viewerCount.text = activity.getString(R.string.watching_count_label, activity.formatViewerCount(viewers.toLong()))
                
                // Tags
                holder.tagsContainer.removeAllViews()
                
                // Language Tag
                if (!channel.language.isNullOrEmpty()) {
                    val langView = TextView(activity).apply {
                        text = activity.getLanguageName(channel.language)
                        setTextColor(Color.WHITE)
                        textSize = 10f
                        setPadding(16, 6, 16, 6)
                        setBackgroundResource(R.drawable.bg_tag)
                        layoutParams = LinearLayout.LayoutParams(
                            LinearLayout.LayoutParams.WRAP_CONTENT,
                            LinearLayout.LayoutParams.WRAP_CONTENT
                        ).apply { marginEnd = 8 }
                    }
                    holder.tagsContainer.addView(langView)
                }
                
                channel.tags?.take(2)?.forEach { tag ->
                    val tagView = TextView(activity).apply {
                        text = tag
                        setTextColor(Color.WHITE)
                        textSize = 10f
                        setPadding(16, 6, 16, 6)
                        setBackgroundResource(R.drawable.bg_tag)
                        layoutParams = LinearLayout.LayoutParams(
                            LinearLayout.LayoutParams.WRAP_CONTENT,
                            LinearLayout.LayoutParams.WRAP_CONTENT
                        ).apply { marginEnd = 8 }
                    }
                    holder.tagsContainer.addView(tagView)
                }

                if (holder.tagsContainer.childCount > 0) {
                    holder.tagsContainer.visibility = View.VISIBLE
                } else {
                    holder.tagsContainer.visibility = View.GONE
                }
                
                // Click to watch
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

    private fun setupHomeCategoryAdapter(recyclerView: RecyclerView?, categories: List<TopCategory>) {
        if (recyclerView == null) return
        
        if (recyclerView.layoutManager == null) {
            recyclerView.layoutManager = LinearLayoutManager(activity, LinearLayoutManager.HORIZONTAL, false).apply {
                isItemPrefetchEnabled = false // Disable prefetch for lazy loading
            }
        }
        
        // Cache views for better scroll performance
        recyclerView.setItemViewCacheSize(10)
        
        // Add SnapHelper for slider behavior
        if (recyclerView.onFlingListener == null) {
            LinearSnapHelper().attachToRecyclerView(recyclerView)
        }
        
        recyclerView.adapter = object : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
            
            inner class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
                val image: ImageView = view.findViewById(R.id.categoryImage)
                val blurredBg: ImageView = view.findViewById(R.id.categoryBlurredBg)
                val name: TextView = view.findViewById(R.id.categoryName)
                val viewers: TextView = view.findViewById(R.id.categoryViewers)
            }
            
            override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
                val view = activity.layoutInflater.inflate(R.layout.item_home_category, parent, false)
                return ViewHolder(view)
            }
            
            override fun getItemCount(): Int = categories.size
            
            override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
                if (holder !is ViewHolder) return
                val category = categories[position]
                
                holder.name.text = CategoryUtils.getLocalizedCategoryName(activity, category.name, category.slug)
                holder.viewers.text = activity.getString(R.string.viewer_count_label, activity.formatViewerCount(category.viewers.toLong()))
                
                val url = category.banner?.src
                if (!url.isNullOrEmpty()) {
                    // Load main image - only reload if URL changed
                    if (holder.image.tag != url) {
                        holder.image.tag = url
                        Glide.with(activity)
                            .load(url)
                            .placeholder(ShimmerDrawable(isCircle = false))
                            .thumbnail(Glide.with(activity).load(url).override(50))
                            .into(holder.image)
                    }
                    
                    // Load blurred background - only reload if URL changed
                    val blurTag = "${url}_blur"
                    if (holder.blurredBg.tag != blurTag) {
                        holder.blurredBg.tag = blurTag
                        Glide.with(activity)
                            .load(url)
                            .transform(jp.wasabeef.glide.transformations.BlurTransformation(25, 3))
                            .into(holder.blurredBg)
                    }
                }
                
                // Click to open category details
                holder.itemView.setOnClickListener {
                    activity.browseManager.openCategoryDetails(category)
                }
            }
        }
    }

    private fun addCategorySection(category: TopCategory) {
        val container = binding.homeScreenContainer.homeDynamicSectionsContainer
        
        // Root container for this section (no background)
        val sectionRoot = LinearLayout(activity).apply {
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                topMargin = (24 * activity.resources.displayMetrics.density).toInt()
            }
            orientation = LinearLayout.VERTICAL
            visibility = View.GONE // Initially GONE, will show when data loads
        }
        
        // Add to container IMMEDIATELY to preserve order
        container.addView(sectionRoot)

        // Create Header
        val headerLayout = LinearLayout(activity).apply {
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                val px16 = (16 * activity.resources.displayMetrics.density).toInt()
                val px8 = (8 * activity.resources.displayMetrics.density).toInt()
                setPadding(px16, px8, px16, px8)
            }
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
            
            isClickable = true
            isFocusable = true
            val outValue = android.util.TypedValue()
            activity.theme.resolveAttribute(android.R.attr.selectableItemBackground, outValue, true)
            setBackgroundResource(outValue.resourceId)
            
            setOnClickListener {
                activity.browseManager.openCategoryDetails(category)
            }
        }
        
        val titleView = TextView(activity).apply {
            text = CategoryUtils.getLocalizedCategoryName(activity, category.name, category.slug)
            setTextColor(Color.WHITE)
            textSize = 18f
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        
        val icon = ImageView(activity).apply {
            setImageResource(R.drawable.ic_chevron_right)
            setColorFilter(Color.parseColor("#FFFFFF"))
            val size = (24 * activity.resources.displayMetrics.density).toInt()
            layoutParams = LinearLayout.LayoutParams(size, size)
        }
        
        headerLayout.addView(titleView)
        headerLayout.addView(icon)
        
        // Create RecyclerView
        val recycler = RecyclerView(activity).apply {
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
            clipToPadding = false
            val px16 = (16 * activity.resources.displayMetrics.density).toInt()
            setPadding(px16, 0, px16, 0)
            layoutManager = LinearLayoutManager(activity, LinearLayoutManager.HORIZONTAL, false)
            if (onFlingListener == null) {
                val isTablet = activity.resources.getBoolean(R.bool.is_tablet)
                if (!isTablet) {
                    LinearSnapHelper().attachToRecyclerView(this)
                }
            }
        }

        sectionRoot.addView(headerLayout)
        sectionRoot.addView(recycler)
        
        // Fetch channels
        activity.lifecycleScope.launch(Dispatchers.IO) {
            try {
                val langs = prefs.streamLanguages.toList().takeIf { it.isNotEmpty() }
                val result = repository.getFilteredLiveStreams(
                    categoryId = category.id,
                    languages = langs
                )
                val channels = result.getOrNull()?.channels ?: emptyList()
                
                withContext(Dispatchers.Main) {
                    if (channels.isNotEmpty()) {
                        sectionRoot.visibility = View.VISIBLE
                        setupHomeChannelAdapter(recycler, channels)
                    } else {
                         // Remove empty sections to prevent gaps
                         container.removeView(sectionRoot)
                    }
                }
            } catch (_: Exception) {
                // Silently ignore errors
            }
        }
    }

    private fun setupHomeClipAdapter(recyclerView: RecyclerView?, clips: List<BrowseClip>) {
        if (recyclerView == null) return
        
        if (recyclerView.layoutManager == null) {
            recyclerView.layoutManager = LinearLayoutManager(activity, LinearLayoutManager.HORIZONTAL, false).apply {
                isItemPrefetchEnabled = false
            }
        }
        
        recyclerView.setItemViewCacheSize(10)
        
        if (recyclerView.onFlingListener == null) {
            LinearSnapHelper().attachToRecyclerView(recyclerView)
        }
        
        recyclerView.adapter = object : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
            
            inner class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
                val thumbnail: ImageView = view.findViewById(R.id.clipThumbnail)
                val durationBadge: TextView = view.findViewById(R.id.durationBadge)
                val viewCount: TextView = view.findViewById(R.id.viewCount)
                val matureBadge: TextView = view.findViewById(R.id.matureBadge)
                val channelAvatar: ImageView = view.findViewById(R.id.channelAvatar)
                val title: TextView = view.findViewById(R.id.clipTitle)
                val channelName: TextView = view.findViewById(R.id.channelName)
            }
            
            override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
                val view = activity.layoutInflater.inflate(R.layout.item_home_clip_card, parent, false)
                return ViewHolder(view)
            }
            
            override fun getItemCount(): Int = clips.size
            
            override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
                if (holder !is ViewHolder) return
                val clip = clips[position]
                
                // Title
                holder.title.text = clip.title ?: activity.getString(R.string.video_default_title)
                
                // Channel name
                holder.channelName.text = clip.channel?.username ?: clip.creator?.username ?: ""
                
                // Duration badge
                val durationSeconds = clip.duration ?: 0
                holder.durationBadge.text = formatClipDuration(durationSeconds)
                
                // View count
                val views = clip.views ?: clip.viewCount ?: 0
                holder.viewCount.text = activity.formatViewerCount(views.toLong())
                
                // Mature badge
                holder.matureBadge.visibility = if (clip.isMature == true) View.VISIBLE else View.GONE
                
                // Thumbnail
                val defaultThumb = Constants.Urls.DEFAULT_LIVESTREAM_THUMBNAIL
                Glide.with(activity)
                    .load(clip.thumbnailUrl ?: defaultThumb)
                    .placeholder(ShimmerDrawable(isCircle = false))
                    .thumbnail(Glide.with(activity).load(clip.thumbnailUrl).override(50))
                    .error(Glide.with(activity).load(defaultThumb).placeholder(ShimmerDrawable(isCircle = false)))
                    .into(holder.thumbnail)
                
                // Channel avatar
                val avatarUrl = clip.channel?.profilePicture
                Glide.with(activity)
                    .load(avatarUrl)
                    .circleCrop()
                    .placeholder(R.drawable.default_avatar)
                    .into(holder.channelAvatar)
                
                // Click to open clip
                holder.itemView.setOnClickListener {
                    openClip(clips, position)
                }
                
                // Avatar click opens channel profile
                holder.channelAvatar.setOnClickListener {
                    val slug = clip.channel?.slug
                    if (!slug.isNullOrEmpty()) {
                        activity.channelProfileManager.openChannelProfile(slug)
                    }
                }
            }
        }
    }
    
    private fun openClip(clips: List<BrowseClip>, position: Int) {
        val clip = clips[position]
        val clipUrl = clip.clipUrl ?: clip.videoUrl
        if (clipUrl.isNullOrEmpty()) {
            return
        }
        
        // Convert to ClipPlayDetails for feed
        val feedClips = clips.map { browseClip ->
            ClipPlayDetails(
                id = browseClip.id,
                livestreamId = browseClip.livestreamId,
                categoryId = browseClip.categoryId,
                channelId = browseClip.channelId,
                userId = browseClip.userId,
                title = browseClip.title,
                clipUrl = browseClip.clipUrl,
                thumbnailUrl = browseClip.thumbnailUrl,
                videoUrl = browseClip.videoUrl,
                views = browseClip.views,
                viewCount = browseClip.viewCount,
                duration = browseClip.duration,
                startedAt = browseClip.startedAt,
                createdAt = browseClip.createdAt,
                vodStartsAt = browseClip.vodStartsAt?.toLong(),
                isMature = browseClip.isMature,
                vod = null,
                category = browseClip.category?.let { 
                    ClipCategory(id = it.id.toLong(), name = it.name, slug = it.slug)
                },
                creator = browseClip.creator?.let {
                    ClipCreator(id = it.id, username = it.username, slug = it.slug, profilePicture = null)
                },
                channel = browseClip.channel?.let {
                    ClipChannel(id = it.id, username = it.username, slug = it.slug, profilePicture = it.profilePicture)
                }
            )
        }
        
        activity.clipFeedManager.openFeed(feedClips, position, initialCursor = null, sort = "view", time = "day")
    }
    
    private fun formatClipDuration(seconds: Int): String {
        val hours = seconds / 3600
        val minutes = (seconds % 3600) / 60
        val secs = seconds % 60
        
        return if (hours > 0) {
            String.format(java.util.Locale.US, "%d:%02d:%02d", hours, minutes, secs)
        } else {
            String.format(java.util.Locale.US, "%d:%02d", minutes, secs)
        }
    }
}
