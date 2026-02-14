/**
 * File: BrowseClipsManager.kt
 *
 * Description: Responsible for fetching and displaying a curated list of clips in the browse section.
 * It manages the RecyclerView adapter for clips, handles pagination and data loading states,
 * and updates the UI with the latest clip content.
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
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import dev.xacnio.kciktv.mobile.MobilePlayerActivity
import dev.xacnio.kciktv.R
import dev.xacnio.kciktv.shared.data.model.BrowseClip
import dev.xacnio.kciktv.shared.util.ShimmerDrawable
import dev.xacnio.kciktv.shared.util.dpToPx
import dev.xacnio.kciktv.shared.util.ThumbnailCacheHelper
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * Manager class for handling Browse Clips functionality
 * Handles fetching, displaying, and filtering of clips in the Browse screen
 */
class BrowseClipsManager(private val activity: MobilePlayerActivity) {
    
    private val binding = activity.binding
    private val repository = activity.repository
    private val prefs = activity.prefs
    
    // State variables
    private var browseClipsList = mutableListOf<BrowseClip?>()
    private var browseClipsAdapter: RecyclerView.Adapter<*>? = null
    private var browseClipsJob: Job? = null
    
    private var isBrowseClipsLoading = false
    private var hasMoreBrowseClips = true
    private var nextBrowseClipsCursor: String? = null
    
    // Current filter state
    private var currentClipsSort = "view" // date, view
    private var currentClipsTime = "day"  // day, week, month, all
    private var isClipsGridMode: Boolean
        get() = activity.resources.getBoolean(R.bool.is_tablet) || (prefs.mobileLayoutMode == "grid")
        set(value) { prefs.mobileLayoutMode = if (value) "grid" else "list" }
    
    private val TAG = "BrowseClipsManager"
    private var lastConfiguration: android.content.res.Configuration? = null
    
    /**
     * Loads browse clips from the API
     */
    fun loadBrowseClips(isLoadMore: Boolean = false) {
        if (isBrowseClipsLoading) {
            if (isLoadMore) return
            browseClipsJob?.cancel()
            isBrowseClipsLoading = false
        }
        
        if (isLoadMore && !hasMoreBrowseClips) return
        
        isBrowseClipsLoading = true
        
        if (!isLoadMore) {
            val isRefreshing = binding.browseScreenContainer.browseSwipeRefresh.isRefreshing
            if (!isRefreshing) {
                updateClipsShimmer(true)
            }
            nextBrowseClipsCursor = null
            hasMoreBrowseClips = true
        } else {
            // Add loading indicator
            browseClipsList.add(null)
            browseClipsAdapter?.notifyItemInserted(browseClipsList.size - 1)
        }
        
        browseClipsJob = activity.lifecycleScope.launch(Dispatchers.IO) {
            try {
                val response = repository.getBrowseClips(
                    sort = currentClipsSort,
                    time = currentClipsTime,
                    cursor = if (isLoadMore) nextBrowseClipsCursor else null
                )
                
                val clips = response?.clips ?: emptyList()
                val nextCursor = response?.nextCursor
                
                withContext(Dispatchers.Main) {
                    if (isLoadMore) {
                        // Remove loading indicator
                        if (browseClipsList.isNotEmpty() && browseClipsList.last() == null) {
                            browseClipsList.removeAt(browseClipsList.size - 1)
                            browseClipsAdapter?.notifyItemRemoved(browseClipsList.size)
                        }
                    }
                    
                    if (!isLoadMore) {
                        setupBrowseClipsAdapter(clips)
                        
                        if (clips.isNotEmpty() && !nextCursor.isNullOrEmpty()) {
                            nextBrowseClipsCursor = nextCursor
                            hasMoreBrowseClips = true
                        } else {
                            hasMoreBrowseClips = false
                        }
                    } else if (clips.isNotEmpty()) {
                        val addedCount = appendBrowseClips(clips)
                        
                        if (addedCount > 0) {
                            if (!nextCursor.isNullOrEmpty()) {
                                nextBrowseClipsCursor = nextCursor
                                hasMoreBrowseClips = true
                            } else {
                                hasMoreBrowseClips = false
                            }
                        } else if (!nextCursor.isNullOrEmpty() && nextCursor != nextBrowseClipsCursor) {
                            // All were duplicates, try next page automatically
                            nextBrowseClipsCursor = nextCursor
                            isBrowseClipsLoading = false
                            loadBrowseClips(isLoadMore = true)
                            return@withContext
                        } else {
                            hasMoreBrowseClips = false
                        }
                    } else {
                        hasMoreBrowseClips = false
                    }
                    
                    isBrowseClipsLoading = false
                    updateClipsShimmer(false)
                    binding.browseScreenContainer.browseSwipeRefresh.isRefreshing = false
                    
                    // Show/hide empty state
                    if (browseClipsList.isEmpty()) {
                        binding.browseScreenContainer.browseClipsEmptyState.visibility = View.VISIBLE
                        binding.browseScreenContainer.browseClipsRecycler.visibility = View.GONE
                    } else {
                        binding.browseScreenContainer.browseClipsEmptyState.visibility = View.GONE
                        binding.browseScreenContainer.browseClipsRecycler.visibility = View.VISIBLE
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to load browse clips", e)
                withContext(Dispatchers.Main) {
                    if (isLoadMore) {
                        if (browseClipsList.isNotEmpty() && browseClipsList.last() == null) {
                            browseClipsList.removeAt(browseClipsList.size - 1)
                            browseClipsAdapter?.notifyItemRemoved(browseClipsList.size)
                        }
                    }
                    isBrowseClipsLoading = false
                    updateClipsShimmer(false)
                    binding.browseScreenContainer.browseSwipeRefresh.isRefreshing = false
                    Toast.makeText(
                        activity, 
                        activity.getString(R.string.clips_load_failed, e.message ?: "Unknown error"), 
                        Toast.LENGTH_SHORT
                    ).show()
                }
            }
        }
    }
    
    private fun appendBrowseClips(newClips: List<BrowseClip>): Int {
        val existingIds = browseClipsList.filterNotNull().map { it.id }.toSet()
        val uniqueNewClips = newClips.filter { it.id !in existingIds }
        
        if (uniqueNewClips.isEmpty()) return 0
        
        val start = browseClipsList.size
        browseClipsList.addAll(uniqueNewClips)
        browseClipsAdapter?.notifyItemRangeInserted(start, uniqueNewClips.size)
        return uniqueNewClips.size
    }
    
    private fun setupBrowseClipsAdapter(clips: List<BrowseClip>, config: android.content.res.Configuration? = null) {
        browseClipsList.clear()
        browseClipsList.addAll(clips)
        
        val recycler = binding.browseScreenContainer.browseClipsRecycler
        
        // Responsive Layout based on available width
        val currentConfig = config ?: lastConfiguration ?: activity.resources.configuration
        val screenWidthDp = currentConfig.screenWidthDp
        
        val spanCount = when {
            screenWidthDp >= 840 -> 4 // Landscape Tablet / Desktop
            screenWidthDp >= 580 -> 3 // Portrait Tablet / Large Phone Landscape
            else -> if (isClipsGridMode) 2 else 1 // Phone Portrait: Respect user preference for Grid (2 cols)
        }
        
        val effectiveIsGrid = spanCount > 1
        
        // Set layout manager based on mode
        if (effectiveIsGrid) {
            recycler.layoutManager = GridLayoutManager(activity, spanCount)
            recycler.setPadding(
                8.dpToPx(activity.resources), 
                8.dpToPx(activity.resources), 
                8.dpToPx(activity.resources), 
                80.dpToPx(activity.resources)
            )
        } else {
            recycler.layoutManager = androidx.recyclerview.widget.LinearLayoutManager(activity)
            recycler.setPadding(
                0, 
                8.dpToPx(activity.resources), 
                0, 
                80.dpToPx(activity.resources)
            )
        }
        recycler.clipToPadding = false
        
        browseClipsAdapter = object : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
            private val VIEW_TYPE_ITEM = 0
            private val VIEW_TYPE_LOADING = 1
            
            inner class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
                val thumbnail: ImageView = view.findViewById(R.id.clipThumbnail)
                val durationBadge: TextView = view.findViewById(R.id.durationBadge)
                val matureBadge: TextView = view.findViewById(R.id.matureBadge)
                val channelAvatar: ImageView = view.findViewById(R.id.channelAvatar)
                val title: TextView = view.findViewById(R.id.clipTitle)
                val channelName: TextView = view.findViewById(R.id.channelName)
                val stats: TextView = view.findViewById(R.id.clipStats)
            }
            
            inner class LoadingViewHolder(view: View) : RecyclerView.ViewHolder(view)
            
            override fun getItemViewType(position: Int): Int {
                return if (browseClipsList[position] == null) VIEW_TYPE_LOADING else VIEW_TYPE_ITEM
            }
            
            override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
                if (viewType == VIEW_TYPE_LOADING) {
                    val view = activity.layoutInflater.inflate(R.layout.item_loading_spinner, parent, false)
                    return LoadingViewHolder(view)
                }
                // Use grid layout if effectiveIsGrid is true
                val layoutRes = if (effectiveIsGrid) R.layout.item_browse_clip else R.layout.item_browse_clip_list
                val view = activity.layoutInflater.inflate(layoutRes, parent, false)
                return ViewHolder(view)
            }
            
            override fun getItemCount(): Int = browseClipsList.size
            
            override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
                if (holder !is ViewHolder) return
                val clip = browseClipsList[position] ?: return
                
                // Title
                holder.title.text = clip.title ?: activity.getString(R.string.video_default_title)
                
                // Channel name
                holder.channelName.text = clip.channel?.username ?: clip.creator?.username ?: ""
                
                // Duration badge
                val durationSeconds = clip.duration ?: 0
                holder.durationBadge.text = formatDuration(durationSeconds)
                
                // Mature badge
                holder.matureBadge.visibility = if (clip.isMature == true) View.VISIBLE else View.GONE
                
                // Stats (views + date)
                val views = clip.views ?: clip.viewCount ?: 0
                val viewsFormatted = activity.formatViewerCount(views.toLong())
                val dateStr = formatRelativeDate(clip.createdAt)
                holder.stats.text = activity.getString(R.string.clip_stats_format, viewsFormatted, dateStr)
                
                // Thumbnail - only reload if URL changed
                val thumbUrl = clip.thumbnailUrl
                if (holder.thumbnail.tag != thumbUrl) {
                    holder.thumbnail.tag = thumbUrl
                    Glide.with(holder.itemView.context)
                        .load(thumbUrl)
                        .signature(ThumbnailCacheHelper.getCacheSignature())
                        .placeholder(ShimmerDrawable(isCircle = false))
                        .error(R.color.placeholder_grey)
                        .into(holder.thumbnail)
                }
                
                // Channel avatar - only reload if URL changed
                val avatarUrl = clip.channel?.profilePicture
                if (holder.channelAvatar.tag != avatarUrl) {
                    holder.channelAvatar.tag = avatarUrl
                    Glide.with(holder.itemView.context)
                        .load(avatarUrl)
                        .circleCrop()
                        .placeholder(R.drawable.default_avatar)
                        .error(R.drawable.default_avatar)
                        .into(holder.channelAvatar)
                }
                
                // Click handler - opens in clip feed
                holder.itemView.setOnClickListener {
                    openClip(clip)
                }
                
                // Long press handler - opens directly in main player
                holder.itemView.setOnLongClickListener {
                    playClipDirectly(clip)
                    true
                }
            }
        }
        
        recycler.adapter = browseClipsAdapter
        
        // Increase view cache to prevent re-binding when scrolling back
        recycler.setItemViewCacheSize(20)
        
        recycler.clearOnScrollListeners()
        recycler.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) {
                val layoutManager = recyclerView.layoutManager ?: return
                val totalItemCount = layoutManager.itemCount
                
                val lastVisibleItem = when (layoutManager) {
                    is GridLayoutManager -> layoutManager.findLastVisibleItemPosition()
                    is androidx.recyclerview.widget.LinearLayoutManager -> layoutManager.findLastVisibleItemPosition()
                    else -> return
                }
                
                if (!isBrowseClipsLoading && hasMoreBrowseClips && totalItemCount <= (lastVisibleItem + 4)) {
                    loadBrowseClips(isLoadMore = true)
                }
            }
        })

    }
    
    private fun openClip(clip: BrowseClip) {
        val clipUrl = clip.clipUrl ?: clip.videoUrl
        if (clipUrl.isNullOrEmpty()) {
            Toast.makeText(activity, activity.getString(R.string.clip_url_not_found), Toast.LENGTH_SHORT).show()
            return
        }
        
        // Filter out nulls and convert valid clips to ClipPlayDetails
        val feedClips = browseClipsList.filterNotNull().map { browseClip ->
            convertToFeedClip(browseClip)
        }
        
        // Find current index after filtering
        val currentIndex = feedClips.indexOfFirst { it.id == clip.id }
        
        if (currentIndex >= 0) {
            activity.clipFeedManager.openFeed(
                feedClips, 
                currentIndex, 
                initialCursor = nextBrowseClipsCursor,
                sort = currentClipsSort,
                time = currentClipsTime
            )
        }
    }
    
    private fun playClipDirectly(clip: BrowseClip) {
        // Hide browse screen and play clip directly in main player
        binding.browseScreenContainer.root.visibility = View.GONE
        activity.vodManager.playClipById(clip.id)
    }
    
    private fun convertToFeedClip(browseClip: BrowseClip): dev.xacnio.kciktv.shared.data.model.ClipPlayDetails {
        return dev.xacnio.kciktv.shared.data.model.ClipPlayDetails(
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
            vod = null, // browse clips don't have vod details
            category = browseClip.category?.let { 
                dev.xacnio.kciktv.shared.data.model.ClipCategory(
                    id = it.id.toLong(),
                    name = it.name,
                    slug = it.slug
                )
            },
            creator = browseClip.creator?.let {
                dev.xacnio.kciktv.shared.data.model.ClipCreator(
                    id = it.id,
                    username = it.username,
                    slug = it.slug,
                    profilePicture = null // BrowseClipUser doesn't have pfp
                )
            },
            channel = browseClip.channel?.let {
                dev.xacnio.kciktv.shared.data.model.ClipChannel(
                    id = it.id,
                    username = it.username,
                    slug = it.slug,
                    profilePicture = it.profilePicture
                )
            }
        )
    }
    
    private fun updateClipsShimmer(visible: Boolean) {
        val shimmerInclude = binding.browseScreenContainer.browseShimmerInclude
        if (visible) {
            shimmerInclude.root.visibility = View.VISIBLE
            shimmerInclude.shimmerCategoriesContainer.visibility = View.GONE
            shimmerInclude.shimmerLiveListContainer.visibility = View.GONE
            shimmerInclude.shimmerLiveGridContainer.visibility = View.GONE
            
            val isTablet = activity.resources.getBoolean(R.bool.is_tablet)
            val effectiveIsGrid = if (isTablet) true else isClipsGridMode
            
            // Show appropriate clips shimmer
            if (effectiveIsGrid) {
                shimmerInclude.shimmerClipsContainer.visibility = View.VISIBLE
                shimmerInclude.shimmerClipsListContainer.visibility = View.GONE
            } else {
                shimmerInclude.shimmerClipsContainer.visibility = View.GONE
                shimmerInclude.shimmerClipsListContainer.visibility = View.VISIBLE
            }
            
            binding.browseScreenContainer.browseClipsRecycler.visibility = View.GONE
        } else {
            shimmerInclude.root.visibility = View.GONE
            shimmerInclude.shimmerClipsContainer.visibility = View.GONE
            shimmerInclude.shimmerClipsListContainer.visibility = View.GONE
        }
    }
    
    /**
     * Shows the clips filter dialog
     */
    fun showClipsFilterDialog() {
        val dialogView = activity.layoutInflater.inflate(R.layout.bottom_sheet_clips_filter, null)
        val dialog = com.google.android.material.bottomsheet.BottomSheetDialog(activity)
        dialog.setContentView(dialogView)
        dialog.behavior.state = com.google.android.material.bottomsheet.BottomSheetBehavior.STATE_EXPANDED
        dialog.behavior.skipCollapsed = true
        
        // View Mode options
        val viewModeGroup = dialogView.findViewById<android.widget.RadioGroup>(R.id.viewModeGroup)
        val viewModeLabel = dialogView.findViewById<TextView>(R.id.viewModeLabel)
        val radioList = dialogView.findViewById<android.widget.RadioButton>(R.id.radioList)
        val radioGrid = dialogView.findViewById<android.widget.RadioButton>(R.id.radioGrid)
        
        val isTablet = activity.resources.getBoolean(R.bool.is_tablet)
        if (isTablet) {
            viewModeGroup?.visibility = View.GONE
            viewModeLabel?.visibility = View.GONE
        } else {
            if (isClipsGridMode) {
                radioGrid.isChecked = true
            } else {
                radioList.isChecked = true
            }
            
            viewModeGroup.setOnCheckedChangeListener { _, checkedId ->
                val newGridMode = checkedId == R.id.radioGrid
                if (newGridMode != isClipsGridMode) {
                    isClipsGridMode = newGridMode
                    dialog.dismiss()
                    loadBrowseClips()
                }
            }
        }
        
        // Sort options
        val chipDate = dialogView.findViewById<com.google.android.material.chip.Chip>(R.id.chipSortDate)
        val chipViews = dialogView.findViewById<com.google.android.material.chip.Chip>(R.id.chipSortViews)
        
        when (currentClipsSort) {
            "date" -> chipDate.isChecked = true
            "view" -> chipViews.isChecked = true
        }
        
        chipDate.setOnClickListener {
            if (currentClipsSort != "date") {
                currentClipsSort = "date"
                dialog.dismiss()
                loadBrowseClips()
            }
        }
        
        chipViews.setOnClickListener {
            if (currentClipsSort != "view") {
                currentClipsSort = "view"
                dialog.dismiss()
                loadBrowseClips()
            }
        }
        
        // Time options
        val chipDay = dialogView.findViewById<com.google.android.material.chip.Chip>(R.id.chipTimeDay)
        val chipWeek = dialogView.findViewById<com.google.android.material.chip.Chip>(R.id.chipTimeWeek)
        val chipMonth = dialogView.findViewById<com.google.android.material.chip.Chip>(R.id.chipTimeMonth)
        val chipAll = dialogView.findViewById<com.google.android.material.chip.Chip>(R.id.chipTimeAll)
        
        when (currentClipsTime) {
            "day" -> chipDay.isChecked = true
            "week" -> chipWeek.isChecked = true
            "month" -> chipMonth.isChecked = true
            "all" -> chipAll.isChecked = true
        }
        
        chipDay.setOnClickListener {
            if (currentClipsTime != "day") {
                currentClipsTime = "day"
                dialog.dismiss()
                loadBrowseClips()
            }
        }
        
        chipWeek.setOnClickListener {
            if (currentClipsTime != "week") {
                currentClipsTime = "week"
                dialog.dismiss()
                loadBrowseClips()
            }
        }
        
        chipMonth.setOnClickListener {
            if (currentClipsTime != "month") {
                currentClipsTime = "month"
                dialog.dismiss()
                loadBrowseClips()
            }
        }
        
        chipAll.setOnClickListener {
            if (currentClipsTime != "all") {
                currentClipsTime = "all"
                dialog.dismiss()
                loadBrowseClips()
            }
        }
        
        dialog.show()
    }
    
    /**
     * Checks if clips data is already loaded
     */
    fun hasData(): Boolean = browseClipsList.isNotEmpty()
    
    /**
     * Formats duration in seconds to MM:SS or HH:MM:SS format
     */
    private fun formatDuration(seconds: Int): String {
        val hours = seconds / 3600
        val minutes = (seconds % 3600) / 60
        val secs = seconds % 60
        
        return if (hours > 0) {
            String.format(Locale.US, "%d:%02d:%02d", hours, minutes, secs)
        } else {
            String.format(Locale.US, "%d:%02d", minutes, secs)
        }
    }
    
    /**
     * Formats date string to relative time (e.g., "2 days ago")
     */
    private fun formatRelativeDate(dateString: String?): String {
        if (dateString.isNullOrEmpty()) return ""
        
        return try {
            val formats = listOf(
                "yyyy-MM-dd'T'HH:mm:ss.SSSSSS'Z'",
                "yyyy-MM-dd'T'HH:mm:ss.SSS'Z'",
                "yyyy-MM-dd'T'HH:mm:ss'Z'",
                "yyyy-MM-dd HH:mm:ss"
            )
            
            var date: Date? = null
            for (format in formats) {
                try {
                    val sdf = SimpleDateFormat(format, Locale.US)
                    sdf.timeZone = TimeZone.getTimeZone("UTC")
                    date = sdf.parse(dateString)
                    if (date != null) break
                } catch (e: Exception) {
                    continue
                }
            }
            
            if (date == null) return dateString
            
            val now = System.currentTimeMillis()
            val diff = now - date.time
            
            val seconds = diff / 1000
            val minutes = seconds / 60
            val hours = minutes / 60
            val days = hours / 24
            val months = days / 30
            val years = days / 365
            
            when {
                years > 0 -> activity.getString(R.string.time_year_ago, years.toInt())
                months > 0 -> activity.getString(R.string.time_month_ago, months.toInt())
                days > 0 -> activity.getString(R.string.time_day_ago, days.toInt())
                hours > 0 -> activity.getString(R.string.time_hour_ago, hours.toInt())
                minutes > 0 -> activity.getString(R.string.time_minute_ago, minutes.toInt())
                else -> activity.getString(R.string.time_just_now)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to parse date: $dateString", e)
            dateString
        }
    }
    
    fun updateLayout(config: android.content.res.Configuration? = null) {
        if (config != null) {
            lastConfiguration = config
        }
        if (browseClipsList.isNotEmpty()) {
            val validClips = browseClipsList.filterNotNull()
            if (validClips.isNotEmpty()) {
                setupBrowseClipsAdapter(validClips, config)
            }
        }
    }
    
    /**
     * Cleanup resources
     */
    fun destroy() {
        browseClipsJob?.cancel()
        browseClipsList.clear()
    }
}
