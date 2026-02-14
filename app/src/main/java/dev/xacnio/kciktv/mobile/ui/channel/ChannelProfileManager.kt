/**
 * File: ChannelProfileManager.kt
 *
 * Description: Manages the comprehensive view of a user's channel profile. This includes fetching
 * and displaying the channel banner, avatar, subscriber count, and about section, as well as handling
 * tabbed navigation for videos, clips, and past broadcasts.
 *
 * Author: Xacnio
 *
 */
package dev.xacnio.kciktv.mobile.ui.channel

import android.content.Intent
import android.graphics.Color
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.lifecycle.LifecycleCoroutineScope
import com.bumptech.glide.Glide
import dev.xacnio.kciktv.mobile.MobilePlayerActivity
import dev.xacnio.kciktv.R
import dev.xacnio.kciktv.shared.data.model.*
import dev.xacnio.kciktv.shared.data.prefs.AppPreferences
import dev.xacnio.kciktv.shared.data.repository.ChannelRepository
import dev.xacnio.kciktv.databinding.ActivityMobilePlayerBinding
import dev.xacnio.kciktv.shared.util.dpToPx
import com.google.android.material.tabs.TabLayout
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import dev.xacnio.kciktv.shared.data.model.ChannelClip
import dev.xacnio.kciktv.shared.data.model.ChannelDetailResponse
import dev.xacnio.kciktv.shared.data.model.ChannelItem
import dev.xacnio.kciktv.shared.data.model.ChannelUserMeResponse
import dev.xacnio.kciktv.shared.ui.adapter.ChannelAboutLinkAdapter
import dev.xacnio.kciktv.shared.ui.adapter.ChannelClipAdapter
import dev.xacnio.kciktv.shared.ui.adapter.ChannelVideoAdapter
import dev.xacnio.kciktv.shared.ui.widget.ShimmerLayout
import dev.xacnio.kciktv.mobile.util.MarkdownUtils
import dev.xacnio.kciktv.shared.util.ShimmerDrawable
import dev.xacnio.kciktv.shared.util.TimeUtils
import dev.xacnio.kciktv.databinding.LayoutChannelProfileBinding

class ChannelProfileManager(
    private val activity: MobilePlayerActivity,
    private val binding: ActivityMobilePlayerBinding,
    private val prefs: AppPreferences,
    private val repository: ChannelRepository,
    private val lifecycleScope: LifecycleCoroutineScope
) {
    var isChannelProfileVisible = false
    private var loadedProfileSlug: String? = null
    /** Public accessor for the currently loaded channel profile slug */
    val currentProfileSlug: String? get() = loadedProfileSlug
    private var lastFilterSlug: String? = null
    private var loadedChannel: ChannelDetailResponse? = null  // Store loaded channel for clips/vods

    private var previousContainerId: Int = R.id.homeScreenContainer
    
    // Clips Pagination State
    private var clipsNextCursor: String? = null
    private var clipsIsLoading = false
    private var clipsCurrentList = mutableListOf<dev.xacnio.kciktv.shared.data.model.ChannelClip>()
    private var clipsAdapter: dev.xacnio.kciktv.shared.ui.adapter.ChannelClipAdapter? = null
    private var currentClipsSort: String = "view"
    private var currentClipsTime: String = "all"
    private var clipsProfileSlug: String? = null
    
    // Flag to track if profile was opened from Stream Feed
    private var openedFromStreamFeed = false
    
    // Flag to track if profile was opened from Clip Feed
    private var openedFromClipFeed = false

    /**
     * Opens channel profile from Stream Feed.
     * When closed, returns to Stream Feed instead of other screens.
     */
    fun openChannelProfileFromFeed(channelSlug: String) {
        openedFromStreamFeed = true
        openedFromClipFeed = false
        openChannelProfile(channelSlug)
    }
    
    /**
     * Opens channel profile from Clip Feed.
     * When closed, returns to Clip Feed instead of other screens.
     */
    fun openChannelProfileFromClipFeed(channelSlug: String) {
        openedFromClipFeed = true
        openedFromStreamFeed = false
        openChannelProfile(channelSlug)
    }

    fun openChannelProfile(channelSlug: String, isRestore: Boolean = false) {
        android.util.Log.d("ChannelProfileManager", "openChannelProfile($channelSlug) called, loadedProfileSlug=$loadedProfileSlug, isChannelProfileVisible=$isChannelProfileVisible, isRestore=$isRestore")
        
        // Capture previous screen state if fresh open AND not restoring
        if (!isChannelProfileVisible && !isRestore) {
            previousContainerId = when {
                binding.searchContainer.visibility == View.VISIBLE -> R.id.searchContainer
                activity.browseManager.isCategoryDetailsVisible -> R.id.categoryDetailsContainer
                binding.browseScreenContainer.root.visibility == View.VISIBLE -> R.id.browseScreenContainer
                binding.followingScreenContainer.root.visibility == View.VISIBLE -> R.id.followingScreenContainer
                else -> R.id.homeScreenContainer
            }
        }
        
        // If profile is already visible for the SAME channel, just ensure container is shown
        if (isChannelProfileVisible && loadedProfileSlug == channelSlug) {
            android.util.Log.d("ChannelProfileManager", "  same channel, just ensuring container is visible")
            // Still need to ensure container is visible and in front
            binding.channelProfileContainer.root.visibility = View.VISIBLE
            binding.channelProfileContainer.root.bringToFront()
            if (binding.playerScreenContainer.visibility == View.VISIBLE) {
                binding.playerScreenContainer.bringToFront()
            }
            // Hide other screens
            binding.mobileHeader.visibility = View.GONE
            binding.homeScreenContainer.root.visibility = View.GONE
            activity.isHomeScreenVisible = false
            return
        }
        
        // If profile is visible but for a DIFFERENT channel, just reload data
        if (isChannelProfileVisible && loadedProfileSlug != channelSlug) {
            android.util.Log.d("ChannelProfileManager", "  different channel, reloading")
            loadChannelProfile(channelSlug, forceRefresh = true)
            return
        }
        
        android.util.Log.d("ChannelProfileManager", "  fresh open")
        
        isChannelProfileVisible = true
        activity.updateNavigationBarColor(false) // Transparent for channel profile
        activity.setCurrentScreen(MobilePlayerActivity.AppScreen.CHANNEL_PROFILE)
        
        // Switch to MiniPlayer if watching a stream
        if (activity.currentChannel != null) {
            if (!activity.miniPlayerManager.isMiniPlayerMode) {
                activity.enterMiniPlayerMode()
            }
            binding.playerScreenContainer.visibility = View.VISIBLE
        } else {
            binding.playerScreenContainer.visibility = View.GONE
        }
        
        // Hide other containers (override enterMiniPlayerMode defaults)
        binding.mobileHeader.visibility = View.GONE
        binding.homeScreenContainer.root.visibility = View.GONE
        binding.browseScreenContainer.root.visibility = View.GONE
        binding.followingScreenContainer.root.visibility = View.GONE
        binding.searchContainer.visibility = View.GONE
        binding.categoryDetailsContainer.root.visibility = View.GONE
        activity.isHomeScreenVisible = false
        
        // Show channel profile container (via root)
        binding.channelProfileContainer.root.visibility = View.VISIBLE
        binding.channelProfileContainer.root.bringToFront()

        // Important: If player is visible (MiniMode), bring it to front ABOVE the profile
        if (binding.playerScreenContainer.visibility == View.VISIBLE) {
            binding.playerScreenContainer.bringToFront()
        }
        
        // Setup back button (using binding)
        binding.channelProfileContainer.btnBack.setOnClickListener {
            closeChannelProfile()
        }
        
        // Setup Pull-to-Refresh
        val swipeRefreshLayout = binding.channelProfileContainer.root
        swipeRefreshLayout.setOnRefreshListener {
            loadChannelProfile(channelSlug, forceRefresh = true)
        }
        
        loadChannelProfile(channelSlug)
    }
    
    fun closeChannelProfile() {
        isChannelProfileVisible = false
        // Hide channel profile
        binding.channelProfileContainer.root.visibility = View.GONE
        
        // Check if opened from Stream Feed - return to feed if so
        if (openedFromStreamFeed) {
            openedFromStreamFeed = false
            activity.streamFeedManager.resumeFeed()
            activity.setCurrentScreen(MobilePlayerActivity.AppScreen.STREAM_FEED)
            return
        }
        
        // Check if opened from Clip Feed - return to clip feed if so
        if (openedFromClipFeed) {
            openedFromClipFeed = false
            activity.clipFeedManager.resumeFeed()
            activity.setCurrentScreen(MobilePlayerActivity.AppScreen.CLIP_FEED)
            return
        }
        
        // Restore previous screen based on saved ID
        when (previousContainerId) {
            R.id.followingScreenContainer -> {
                binding.followingScreenContainer.root.visibility = View.VISIBLE
                binding.followingScreenContainer.root.bringToFront()
                binding.mobileHeader.visibility = View.VISIBLE
                activity.setCurrentScreen(MobilePlayerActivity.AppScreen.FOLLOWING)
            }
            R.id.browseScreenContainer -> {
                binding.browseScreenContainer.root.visibility = View.VISIBLE
                binding.browseScreenContainer.root.bringToFront()
                binding.mobileHeader.visibility = View.VISIBLE
                activity.setCurrentScreen(MobilePlayerActivity.AppScreen.BROWSE)
            }
            R.id.categoryDetailsContainer -> {
                binding.categoryDetailsContainer.root.visibility = View.VISIBLE
                binding.categoryDetailsContainer.root.bringToFront()
                binding.mobileHeader.visibility = View.GONE
                activity.setCurrentScreen(MobilePlayerActivity.AppScreen.CATEGORY_DETAILS)
            }
            R.id.searchContainer -> {
                binding.searchContainer.visibility = View.VISIBLE
                binding.searchContainer.bringToFront()
                binding.mobileHeader.visibility = View.VISIBLE
                activity.setCurrentScreen(MobilePlayerActivity.AppScreen.SEARCH)
            }
            else -> {
                // Default to Home
                activity.showHomeScreen()
            }
        }
    }
    
    fun loadChannelProfile(channelSlug: String, forceRefresh: Boolean = false) {
        val swipeRefreshLayout = binding.channelProfileContainer.root
        
        // Cache Check: If already loaded and not forcing refresh, skip
        if (channelSlug == loadedProfileSlug && !forceRefresh) {
            swipeRefreshLayout.isRefreshing = false
            return
        }
        
        val layout = binding.channelProfileContainer
        val isSwipeRefresh = swipeRefreshLayout.isRefreshing
        
        // Reset scroll position to top when loading a new profile
        if (channelSlug != loadedProfileSlug) {
            layout.scrollView.scrollTo(0, 0)
            // Also reset to Home tab
            layout.channelTabs.getTabAt(0)?.select()
        }
        
        // Show loading (Shimmer) ONLY if not pull-to-refresh (Initial Load)
        if (!isSwipeRefresh) {
            layout.loadingOverlay.visibility = View.VISIBLE
            layout.loadingOverlay.startShimmer()
        }
        
        lifecycleScope.launch {
            val token = withContext(Dispatchers.IO) {
                prefs.authToken
            }

            try {
                kotlinx.coroutines.coroutineScope {
                    val channelDeferred = async { repository.getChannelDetails(channelSlug) }
                    val livestreamDeferred = async { repository.getLiveStreamDetails(channelSlug) }
                    val userMeDeferred = if (!token.isNullOrEmpty()) {
                        async { repository.getChannelUserMe(channelSlug, token) }
                    } else {
                        null
                    }

                    val channelResult = channelDeferred.await()
                    val livestreamResult = livestreamDeferred.await()
                    val userMeResult = userMeDeferred?.await()

                    withContext(Dispatchers.Main) {
                        // Stop Loading / Refreshing
                        if (isSwipeRefresh) {
                            swipeRefreshLayout.isRefreshing = false
                        } else {
                            layout.loadingOverlay.stopShimmer()
                            layout.loadingOverlay.visibility = View.GONE
                        }
                        
                        channelResult.onSuccess { channel ->
                            loadedProfileSlug = channelSlug // Mark as loaded
                            val userMe = userMeResult?.getOrNull()
                            val livestreamInfo = livestreamResult.getOrNull()
                            updateChannelProfileUI(channel, userMe, livestreamInfo)
                        }.onFailure { error ->
                            loadedProfileSlug = null // Failed, allow retry
                            Toast.makeText(activity, activity.getString(R.string.channel_load_failed, error.message), Toast.LENGTH_SHORT).show()
                        }
                    }
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    if (isSwipeRefresh) {
                        swipeRefreshLayout.isRefreshing = false
                    } else {
                        layout.loadingOverlay.stopShimmer()
                        layout.loadingOverlay.visibility = View.GONE
                    }
                    loadedProfileSlug = null
                    Toast.makeText(activity, activity.getString(R.string.operation_failed, e.message), Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private fun updateChannelProfileUI(
        channel: ChannelDetailResponse, 
        userMe: ChannelUserMeResponse? = null,
        livestreamResponse: LivestreamResponse? = null
    ) {
        loadedChannel = channel  // Store for clips/vods playback
        val layout = binding.channelProfileContainer
        
        // Banner
        Glide.with(activity).load(channel.getEffectiveBannerUrl()).into(layout.channelBanner)
        
        // Avatar
        Glide.with(activity).load(channel.getEffectiveProfilePicUrl()).into(layout.channelAvatar)
        
        // Name
        layout.channelName.text = channel.user?.username ?: channel.slug
        
        // Verified badge (ChannelDetailResponse has verified: Boolean?)
        layout.verifiedBadge.visibility = if (channel.verified == true) View.VISIBLE else View.GONE
        
        // Subtitle (followers + last stream)
        val followerCount = channel.followersCount ?: 0
        val followerText = when {
            followerCount >= 1_000_000 -> String.format(activity.getString(R.string.followers_millions), followerCount / 1_000_000.0)
            followerCount >= 1_000 -> String.format(activity.getString(R.string.followers_thousands), followerCount / 1_000.0)
            else -> activity.getString(R.string.followers_count, followerCount)
        }
        layout.channelSubtitle.text = followerText

        // Social Links
        val socialList = layout.socialLinksList
        socialList.removeAllViews()
        var hasSocialLinks = false

        fun addSocialLink(iconRes: Int, rawHandle: String?, baseUrl: String) {
            if (rawHandle.isNullOrBlank()) return
            hasSocialLinks = true
            
            val itemLayout = LinearLayout(activity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = android.view.Gravity.CENTER_VERTICAL
                setPadding(0, 4.dpToPx(activity.resources), 16.dpToPx(activity.resources), 4.dpToPx(activity.resources))
                background = androidx.core.content.ContextCompat.getDrawable(context, R.drawable.bg_item_ripple)
                setOnClickListener {
                    try {
                        var url = rawHandle
                        if (!url.startsWith("http")) {
                            var cleanHandle = rawHandle.trim()
                            if (baseUrl.contains("tiktok.com") && !cleanHandle.startsWith("@")) {
                                cleanHandle = "@$cleanHandle"
                            }
                            url = baseUrl + cleanHandle
                        }
                        val intent = Intent(Intent.ACTION_VIEW, android.net.Uri.parse(url))
                        activity.startActivity(intent)
                    } catch (e: Exception) {
                        Toast.makeText(activity, activity.getString(R.string.link_open_failed), Toast.LENGTH_SHORT).show()
                    }
                }
            }

            val icon = ImageView(activity).apply {
                layoutParams = LinearLayout.LayoutParams(20.dpToPx(activity.resources), 20.dpToPx(activity.resources))
                setImageResource(iconRes)
                scaleType = ImageView.ScaleType.FIT_CENTER
                imageTintList = android.content.res.ColorStateList.valueOf(Color.WHITE)
            }

            val text = TextView(activity).apply {
                layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                    marginStart = 8.dpToPx(activity.resources)
                }
                this.text = rawHandle
                textSize = 13f
                setTextColor(Color.parseColor("#EEEEEE"))
                setTypeface(null, android.graphics.Typeface.BOLD)
            }

            itemLayout.addView(icon)
            itemLayout.addView(text)
            socialList.addView(itemLayout)
        }
        
        addSocialLink(R.drawable.ic_social_instagram, channel.user?.instagram, "https://instagram.com/")
        addSocialLink(R.drawable.ic_social_twitter, channel.user?.twitter, "https://twitter.com/")
        addSocialLink(R.drawable.ic_social_youtube, channel.user?.youtube, "https://youtube.com/")
        addSocialLink(R.drawable.ic_social_discord, channel.user?.discord, "https://discord.gg/")
        addSocialLink(R.drawable.ic_social_tiktok, channel.user?.tiktok, "https://tiktok.com/")
        addSocialLink(R.drawable.ic_social_facebook, channel.user?.facebook, "https://facebook.com/")

        layout.socialLinksList.visibility = if (hasSocialLinks) View.VISIBLE else View.GONE

        // Live Stream Card Logic
        val lsResponse = livestreamResponse
        val lsDetail = channel.livestream
        
        // Use response if available and live, or fallback to detail
        // Note: lsResponse might be live but lsDetail inside channel might be stale or null
        val isLive = (lsResponse?.isLive == true) || (lsDetail?.isLive == true)
        
        if (isLive) {
            layout.liveStreamCard.visibility = View.VISIBLE
            layout.offlineStreamCard.visibility = View.GONE
            
            // Data Extraction (Priority: Response -> Detail)
            val title = lsResponse?.sessionTitle ?: lsDetail?.sessionTitle ?: ""
            val viewers = lsResponse?.viewers ?: lsDetail?.viewerCount ?: 0
            val thumbObj = lsResponse?.thumbnail ?: lsDetail?.thumbnail
            val created = lsResponse?.createdAt ?: lsDetail?.startTime ?: lsDetail?.createdAt
            val tags = lsDetail?.tags ?: lsResponse?.tags
            val mature = lsResponse?.isMature ?: lsDetail?.isMature // lsDetail has isMature now (updated model)
            val lang = lsDetail?.langIso
            val cats = lsResponse?.categories ?: lsDetail?.categories
            val playbackUrl =  channel.playbackUrl ?: lsResponse?.playbackUrl

            // Thumbnail Handling
            var thumbUrl = thumbObj?.src ?: thumbObj?.url
            var isBlurredFallback = false

            if (thumbUrl.isNullOrEmpty()) {
                 // Fallback 1: Profile picture (Blurred/Censored effect requested)
                 thumbUrl = channel.user?.profilePic
                 isBlurredFallback = true
            }
            
            if (thumbUrl.isNullOrEmpty()) {
                 // Fallback 2: Banner (Regular)
                 thumbUrl = channel.bannerImage?.url ?: channel.bannerImage?.src ?: channel.offlineBannerImage?.src
                 isBlurredFallback = false
            }
            
            if (!thumbUrl.isNullOrEmpty()) {
                // Prepare fallback request (Blurred Profile Pic) for error case
                val errorFallback = if (!isBlurredFallback && !channel.user?.profilePic.isNullOrEmpty()) {
                    Glide.with(activity).load(channel.user?.profilePic).override(60).centerCrop()
                } else {
                    null
                }

                var request = Glide.with(activity).load(thumbUrl)
                    .centerCrop()
                    .placeholder(dev.xacnio.kciktv.shared.util.ShimmerDrawable(isCircle = false))
                
                // Set error fallback if available, otherwise transparent
                if (errorFallback != null) {
                    request = request.error(errorFallback)
                } else {
                    request = request.error(android.R.color.transparent)
                }

                if (isBlurredFallback) {
                    // Simulate blur/censored effect by loading low resolution and letting ImageView scale it up
                    request = request.override(60) 
                }

                request.into(layout.cardLiveThumbnail)
            }
            
            // Uptime Parsing
            var startTimeMs = 0L
            if (!created.isNullOrEmpty()) {
                try {
                    val format1 = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.US)
                    format1.timeZone = java.util.TimeZone.getTimeZone("UTC")
                    startTimeMs = format1.parse(created)?.time ?: 0L
                } catch (e: Exception) {}
                
                if (startTimeMs == 0L) {
                    try {
                         val format2 = java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", java.util.Locale.US)
                         format2.timeZone = java.util.TimeZone.getTimeZone("UTC")
                         startTimeMs = format2.parse(created)?.time ?: 0L
                    } catch (e: Exception) {}
                }
            }
            
            if (startTimeMs > 0) {
                val duration = System.currentTimeMillis() - startTimeMs
                val diff = if (duration < 0) 0 else duration
                val hrs = diff / 3600000
                val mins = (diff % 3600000) / 60000
                layout.cardLiveUptime.text = if (hrs > 0) activity.getString(R.string.uptime_hours_minutes, hrs.toInt(), mins.toInt()) else activity.getString(R.string.uptime_minutes, mins.toInt())
                layout.cardLiveUptime.visibility = View.VISIBLE
            } else {
                layout.cardLiveUptime.visibility = View.GONE
            }

            // Info
            layout.cardLiveTitle.text = title
            layout.cardLiveCategory.text = cats?.firstOrNull()?.name ?: activity.getString(R.string.category_stream)
            
            // Language
            if (!lang.isNullOrEmpty()) {
                layout.cardLiveLanguage.text = lang.uppercase()
                layout.cardLiveLanguage.visibility = View.VISIBLE
            } else {
               layout.cardLiveLanguage.visibility = View.GONE
            }

            // Tags
            val tagsContainer = layout.cardInfoBadges // ChipGroup
            
            // Clean dynamic tags (keep language & mature views at index 0 & 1)
            while (tagsContainer.childCount > 2) {
                tagsContainer.removeViewAt(2)
            }

            if (!tags.isNullOrEmpty()) {
                tags.take(3).forEach { tag ->
                     val tagView = TextView(activity).apply {
                        text = tag
                        setTextColor(Color.parseColor("#CCCCCC"))
                        textSize = 10f
                        setPadding(12.dpToPx(activity.resources), 4.dpToPx(activity.resources), 12.dpToPx(activity.resources), 4.dpToPx(activity.resources))
                        setBackgroundResource(R.drawable.bg_tag)
                        includeFontPadding = false
                        gravity = android.view.Gravity.CENTER
                        layoutParams = android.view.ViewGroup.LayoutParams(
                            android.view.ViewGroup.LayoutParams.WRAP_CONTENT,
                            android.view.ViewGroup.LayoutParams.WRAP_CONTENT
                        )
                    }
                    tagsContainer.addView(tagView)
                }
            }
 
            // Mature
            layout.cardLiveMature.visibility = if (mature == true) View.VISIBLE else View.GONE
            
            // Click to watch
            layout.liveStreamCard.setOnClickListener {
                 val currentSlug = activity.currentChannel?.slug
                 val targetSlug = channel.slug
                 
                 if (currentSlug != null && currentSlug == targetSlug && binding.playerScreenContainer.visibility == View.VISIBLE) {
                     // Already playing this channel - Expand / Show Full Screen
                     if (activity.miniPlayerManager.isMiniPlayerMode) {
                         activity.miniPlayerManager.exitMiniPlayerMode()
                     }
                     binding.playerScreenContainer.visibility = View.VISIBLE
                     activity.updateNavigationBarColor(true)
                     
                     // IMPORTANT: Update previousScreenId to ensure we come back here
                     activity.previousScreenId = R.id.channelProfileContainer
                     activity.returnToProfileSlug = targetSlug
                     
                     // Hide profile temporarily (Activity handles restoration via back)
                     isChannelProfileVisible = false
                     binding.channelProfileContainer.root.visibility = View.GONE
                 } else {
                     // Play new channel
                     val item = ChannelItem(
                         id = (channel.id ?: 0).toString(),
                         slug = channel.slug ?: "",
                         username = channel.user?.username ?: channel.slug ?: "",
                         title = title,
                         categoryName = cats?.firstOrNull()?.name,
                         categorySlug = cats?.firstOrNull()?.slug,
                         viewerCount = viewers,
                         isLive = true,
                         profilePicUrl = channel.user?.profilePic,
                         thumbnailUrl = thumbUrl,
                         playbackUrl = playbackUrl,
                         language = lang
                     )
                     
                     activity.returnToProfileSlug = channel.slug
                     activity.playChannel(item)
                 }
            }
        } else {
            layout.liveStreamCard.visibility = View.GONE
            layout.offlineStreamCard.visibility = View.VISIBLE
            
            // Offline Thumbnail
            val offlineBannerUrl = ChannelItem.getBestImageUrl(channel.offlineBannerImage?.src, channel.offlineBannerImage?.srcset)
                ?: channel.offlineBannerImage?.url 
                ?: ChannelItem.getBestImageUrl(channel.bannerImage?.src, channel.bannerImage?.srcset)
                ?: "https://kick.com/img/default-channel-banners/offline-banner.webp"
            
            if (!offlineBannerUrl.isNullOrEmpty()) {
                Glide.with(activity)
                    .load(offlineBannerUrl)
                    .centerCrop()
                    .placeholder(R.color.placeholder_grey)
                    .into(layout.offlineThumbnail)
            }

            // Go to Chat Button
            layout.btnGoToChat.setOnClickListener {
                activity.runOnUiThread {
                    val item = ChannelItem.fromChannelDetailResponse(channel).copy(isLive = true)
                    val currentSlug = activity.currentChannel?.slug
                    val targetSlug = channel.slug
                    
                    if (currentSlug != null && currentSlug == targetSlug && binding.playerScreenContainer.visibility == View.VISIBLE) {
                        // Already playing this channel - Expand from mini player
                        if (activity.miniPlayerManager.isMiniPlayerMode) {
                            activity.miniPlayerManager.exitMiniPlayerMode()
                        }
                        binding.playerScreenContainer.visibility = View.VISIBLE
                        activity.updateNavigationBarColor(true)
                        
                        // IMPORTANT: Update previousScreenId to ensure we come back here
                        activity.previousScreenId = R.id.channelProfileContainer
                        activity.returnToProfileSlug = targetSlug
                        
                        // Hide profile temporarily (Activity handles restoration via back)
                        isChannelProfileVisible = false
                        binding.channelProfileContainer.root.visibility = View.GONE
                    } else {
                        // Play new channel - also ensure full screen
                        activity.returnToProfileSlug = channel.slug
                        activity.previousScreenId = R.id.channelProfileContainer
                        
                        // Hide profile BEFORE playing channel
                        isChannelProfileVisible = false
                        binding.channelProfileContainer.root.visibility = View.GONE
                        
                        activity.playChannel(item)
                    }
                }
            }
        }
        
        // Follow Button State
        val isSelf = channel.slug == prefs.userSlug
        if (isSelf) {
            binding.channelProfileContainer.root.findViewById<View>(R.id.btnFollow)?.visibility = View.GONE
        } else {
            binding.channelProfileContainer.root.findViewById<View>(R.id.btnFollow)?.visibility = View.VISIBLE
            val isFollowing = userMe?.isFollowing ?: (channel.following == true)
            activity.currentIsFollowing = isFollowing // Update global state
            updateChannelProfileFollowButton(isFollowing)
        }

        layout.btnFollow.setOnClickListener {
             val item = ChannelItem.fromChannelDetailResponse(channel)
             if (activity.currentIsFollowing) {
                 activity.showUnfollowDialog(item)
             } else {
                 activity.followChannel(item)
             }
        }

        // Setup tabs
        setupChannelProfileTabs(channel)
        
        // Load videos and clips for home tab
        channel.slug?.let { 
            loadChannelVideos(it)
            loadChannelClips(it)
        }
    }

    fun updateChannelProfileFollowButton(isFollowing: Boolean) {
        // Access via root view to ensure we find the newly added elements
        val container = binding.channelProfileContainer.root
        val btnFollowIcon = container.findViewById<ImageView>(R.id.btnFollowIcon)
        val btnFollowLoader = container.findViewById<ProgressBar>(R.id.btnFollowLoader)
        val btnFollowContainer = container.findViewById<View>(R.id.btnFollow) // Common View for any container

        if (btnFollowIcon != null) {
            btnFollowIcon.visibility = View.VISIBLE
            if (isFollowing) {
                btnFollowIcon.setImageResource(R.drawable.ic_check)
            } else {
                btnFollowIcon.setImageResource(R.drawable.ic_person_add)
            }
        }
        
        btnFollowLoader?.setVisibility(View.GONE)
        
        if (btnFollowContainer != null) {
            btnFollowContainer.setEnabled(true)
            if (isFollowing) {
                 btnFollowContainer.backgroundTintList = android.content.res.ColorStateList.valueOf(Color.parseColor("#1A1A1A"))
            } else {
                 btnFollowContainer.backgroundTintList = android.content.res.ColorStateList.valueOf(prefs.themeColor)
            }
        }
    }

    fun showChannelProfileFollowLoading() {
        val container = binding.channelProfileContainer.root
        val btnFollowIcon = container.findViewById<ImageView>(R.id.btnFollowIcon)
        val btnFollowLoader = container.findViewById<ProgressBar>(R.id.btnFollowLoader)
        val btnFollowContainer = container.findViewById<View>(R.id.btnFollow)

        btnFollowIcon?.visibility = View.GONE
        btnFollowLoader?.setVisibility(View.VISIBLE)
        btnFollowContainer?.setEnabled(false)
    }
    
    private fun setupChannelProfileTabs(channel: ChannelDetailResponse) {
        val layout = binding.channelProfileContainer
        val tabLayout = layout.channelTabs
        
        tabLayout.removeAllTabs()
        tabLayout.addTab(tabLayout.newTab().setText(activity.getString(R.string.tab_home)))
        tabLayout.addTab(tabLayout.newTab().setText(activity.getString(R.string.tab_about)))
        tabLayout.addTab(tabLayout.newTab().setText(activity.getString(R.string.tab_videos)))
        tabLayout.addTab(tabLayout.newTab().setText(activity.getString(R.string.tab_clips)))
        
        // About tab items
        val bioText = channel.user?.bio ?: activity.getString(R.string.no_channel_info)
        dev.xacnio.kciktv.mobile.util.MarkdownUtils.parseAndSetMarkdown(layout.aboutText, bioText)
        loadChannelAbout(channel.slug ?: "")
        
        tabLayout.addOnTabSelectedListener(object : TabLayout.OnTabSelectedListener {
            override fun onTabSelected(tab: TabLayout.Tab?) {
                layout.tabHomeContent.visibility = View.GONE
                layout.tabAboutContent.visibility = View.GONE
                layout.tabVideosContent.visibility = View.GONE
                layout.tabClipsContent.visibility = View.GONE
                
                when (tab?.position) {
                    0 -> layout.tabHomeContent.visibility = View.VISIBLE
                    1 -> layout.tabAboutContent.visibility = View.VISIBLE
                    2 -> layout.tabVideosContent.visibility = View.VISIBLE
                    3 -> layout.tabClipsContent.visibility = View.VISIBLE
                }
            }
            
            override fun onTabUnselected(tab: TabLayout.Tab?) {}
            override fun onTabReselected(tab: TabLayout.Tab?) {}
        })
    }
    
    private fun loadChannelVideos(channelSlug: String) {
        val layout = binding.channelProfileContainer
        val homeRecyclerView = layout.videosRecyclerView 
        val allVideosRecyclerView = layout.allVideosRecyclerView
        val shimmer = layout.allVideosShimmer
        val emptyText = layout.videosEmptyText
        val videosHeader = layout.videosSection.getChildAt(0) // Header layout
        
        // Setup Videos Tab RecyclerView (Grid)
        val isTablet = activity.resources.getBoolean(R.bool.is_tablet)
        val tabletSpanCount = activity.resources.getInteger(R.integer.grid_span_count)
        val spanCount = if (isTablet) tabletSpanCount else 2
        
        // Update layout manager or create if null
        setupResponsiveGrid(allVideosRecyclerView)
        val spacing = 8.dpToPx(activity.resources)
        allVideosRecyclerView.setPadding(spacing, spacing, spacing, spacing)
        allVideosRecyclerView.clipToPadding = false
        
        // For Home Tab Videos Section - Use Responsive Grid
        setupResponsiveGrid(homeRecyclerView)

        // Initialize Profile Refresh Layout (Centralized)
        layout.refreshProfile.setColorSchemeColors(Color.parseColor("#53FC18"))
        layout.refreshProfile.setOnRefreshListener {
            val activeTab = layout.channelTabs.selectedTabPosition
            when (activeTab) {
                0 -> { 
                    loadChannelVideos(channelSlug)
                    loadChannelClips(channelSlug)
                }
                2 -> loadChannelVideos(channelSlug)
                3 -> refreshAllClipsTab(channelSlug)
                else -> layout.refreshProfile.isRefreshing = false
            }
        }

        videosHeader.setOnClickListener {
            layout.channelTabs.getTabAt(2)?.select()
        }

        // Only show shimmer if not already pulling-to-refresh
        if (!layout.refreshProfile.isRefreshing) {
            allVideosRecyclerView.visibility = View.GONE
            shimmer.visibility = View.VISIBLE
            shimmer.startShimmer()
            
            // Offline time shimmer
            try {
                // Try to access via binding dynamically or findviewbyid if binding not regen
                val offlineShimmer = layout.root.findViewById<dev.xacnio.kciktv.shared.ui.widget.ShimmerLayout>(R.id.offlineLastStreamShimmer)
                offlineShimmer?.visibility = View.VISIBLE
                offlineShimmer?.startShimmer()
                layout.offlineLastStreamTime.visibility = View.GONE
            } catch (e: Exception) {}
        }

        lifecycleScope.launch {
            repository.getChannelVideos(channelSlug).onSuccess { videos ->
                withContext(Dispatchers.Main) {
                    layout.refreshProfile.isRefreshing = false
                    shimmer.stopShimmer()
                    shimmer.visibility = View.GONE
                    
                    // Stop offline time shimmer
                    try {
                        val offlineShimmer = layout.root.findViewById<dev.xacnio.kciktv.shared.ui.widget.ShimmerLayout>(R.id.offlineLastStreamShimmer)
                        offlineShimmer?.stopShimmer()
                        offlineShimmer?.visibility = View.GONE
                        layout.offlineLastStreamTime.visibility = View.VISIBLE
                    } catch (e: Exception) {}

                    if (videos.isEmpty()) {
                        homeRecyclerView.visibility = View.GONE
                        emptyText.visibility = View.VISIBLE
                        allVideosRecyclerView.visibility = View.GONE
                        layout.offlineLastStreamTime.text = activity.getString(R.string.currently_offline)
                    } else {
                        homeRecyclerView.visibility = View.VISIBLE
                        emptyText.visibility = View.GONE
                        
                        // Update Last Stream Time from latest video
                        val latestVideo = videos.first()
                        val videoCreatedAt = latestVideo.video?.createdAt ?: latestVideo.createdAt
                        val startTime = dev.xacnio.kciktv.shared.util.TimeUtils.parseIso8601ToMillis(videoCreatedAt)
                        
                        android.util.Log.d("ChannelProfile", "Last Stream Calc: Video ID=${latestVideo.id}, RawCreatedAt=${latestVideo.createdAt}, VideoCreatedAt=${latestVideo.video?.createdAt}, StartTime=$startTime, Duration=${latestVideo.duration}")
                        
                        if (startTime != null && layout.offlineStreamCard.visibility == View.VISIBLE) {
                             val duration = (latestVideo.duration ?: 0).toLong()
                             val endTime = startTime + duration
                             
                             android.util.Log.d("ChannelProfile", "Last Stream Calc: EndTime=$endTime, Now=${System.currentTimeMillis()}")
                             
                             val relativeTime = dev.xacnio.kciktv.shared.util.TimeUtils.getRelativeTimeSpan(activity, endTime)
                             android.util.Log.d("ChannelProfile", "Last Stream Calc: Result='$relativeTime'")
                             
                             layout.offlineLastStreamTime.text = activity.getString(R.string.last_stream_time, relativeTime)
                        } else {
                             // Fallback if date parsing fails but video exists
                             layout.offlineLastStreamTime.text = activity.getString(R.string.currently_offline)
                        }
                        
                        val homeList = videos.take(6)
                        val homeAdapter = dev.xacnio.kciktv.shared.ui.adapter.ChannelVideoAdapter(homeList, prefs) { video ->
                             val channel = loadedChannel ?: ChannelItem(
                                 "0", channelSlug, channelSlug, video.sessionTitle ?: activity.getString(R.string.video_default_title), 0, null, null, null, null, null, "tr"
                             )
                             activity.playVideo(video.toChannelVideo(), channel)
                        }
                        homeRecyclerView.adapter = homeAdapter
                        
                        // 2. Videos Tab
                        allVideosRecyclerView.visibility = View.VISIBLE
                        val allAdapter = dev.xacnio.kciktv.shared.ui.adapter.ChannelVideoAdapter(videos, prefs) { video ->
                             val channel = loadedChannel ?: activity.currentChannel
                             if (channel != null) {
                                 activity.playVideo(video.toChannelVideo(), channel)
                             }
                        }
                        allVideosRecyclerView.adapter = allAdapter
                    }
                }
            }.onFailure {
                 withContext(Dispatchers.Main) {
                    layout.refreshProfile.isRefreshing = false
                    shimmer.stopShimmer()
                    shimmer.visibility = View.GONE
                    
                    try {
                        val offlineShimmer = layout.root.findViewById<dev.xacnio.kciktv.shared.ui.widget.ShimmerLayout>(R.id.offlineLastStreamShimmer)
                        offlineShimmer?.stopShimmer()
                        offlineShimmer?.visibility = View.GONE
                        layout.offlineLastStreamTime.visibility = View.VISIBLE
                        layout.offlineLastStreamTime.text = activity.getString(R.string.currently_offline)
                    } catch (e: Exception) {}

                    homeRecyclerView.visibility = View.GONE
                    emptyText.visibility = View.VISIBLE
                    allVideosRecyclerView.visibility = View.GONE
                }
            }
        }
    }
    
    private fun loadChannelClips(channelSlug: String) {
        val layout = binding.channelProfileContainer
        val homeRecyclerView = layout.clipsRecyclerView // Horizontal in Home Tab
        val allClipsRecyclerView = layout.allClipsRecyclerView // Grid in Clips Tab
        val emptyText = layout.clipsEmptyText
        val clipsHeader = layout.clipsSection.getChildAt(0) // Header layout
        
        // Setup Grid for Clips Tab
        val isTablet = activity.resources.getBoolean(R.bool.is_tablet)
        val tabletSpanCount = activity.resources.getInteger(R.integer.grid_span_count)
        val spanCount = if (isTablet) tabletSpanCount else 2
        
        setupResponsiveGrid(allClipsRecyclerView)
        val spacing = 8.dpToPx(activity.resources)
        allClipsRecyclerView.setPadding(spacing, spacing, spacing, spacing)
        allClipsRecyclerView.clipToPadding = false
        
        // For Home Tab Clips Section - Use Responsive Grid
        setupResponsiveGrid(homeRecyclerView)

        // Header click -> Switch to Clips Tab (index 3)
        clipsHeader.setOnClickListener {
            layout.channelTabs.getTabAt(3)?.select()
        }

        // Init Filters once
        setupClipFilters(channelSlug)

        lifecycleScope.launch {
            // 1. Fetch Popular for Home Tab (views, all time)
            repository.getChannelClips(channelSlug, sort = "view", time = "all").onSuccess { data ->
                withContext(Dispatchers.Main) {
                    val clips = data.clips
                    if (clips.isEmpty()) {
                        homeRecyclerView.visibility = View.GONE
                        emptyText.visibility = View.VISIBLE
                    } else {
                        homeRecyclerView.visibility = View.VISIBLE
                        emptyText.visibility = View.GONE
                        
                        val homeList = clips.take(12)
                        val adapter = dev.xacnio.kciktv.shared.ui.adapter.ChannelClipAdapter(
                            homeList, 
                            true,
                            onItemClick = { clip -> openClipFeed(homeList, clip, channelSlug) }
                        )
                        homeRecyclerView.adapter = adapter
                    }
                }
            }

            // 2. Fetch Initial for Clips Tab
            refreshAllClipsTab(channelSlug)
        }
    }

    private fun setupClipFilters(channelSlug: String) {
        val layout = binding.channelProfileContainer
        
        // Only reset filters if channel changed
        if (lastFilterSlug != channelSlug) {
            layout.chipGroupSort.check(R.id.chipSortPop)
            layout.chipGroupTime.check(R.id.chipTimeAll)
            lastFilterSlug = channelSlug
        }

        // Always update listeners to capture correct channelSlug
        val listener = { _: com.google.android.material.chip.ChipGroup, checkedIds: List<Int> ->
            if (checkedIds.isNotEmpty()) {
                refreshAllClipsTab(channelSlug)
            }
        }

        layout.chipGroupSort.setOnCheckedStateChangeListener(listener)
        layout.chipGroupTime.setOnCheckedStateChangeListener(listener)
    }

    private fun refreshAllClipsTab(channelSlug: String) {
        val layout = binding.channelProfileContainer
        val allClipsRecyclerView = layout.allClipsRecyclerView
        val shimmer = layout.allClipsShimmer

        val sort = when (layout.chipGroupSort.checkedChipId) {
            R.id.chipSortPop -> "view"
            R.id.chipSortNew -> "date"
            else -> "view"
        }
        
        val time = when (layout.chipGroupTime.checkedChipId) {
            R.id.chipTimeDay -> "day"
            R.id.chipTimeWeek -> "week"
            R.id.chipTimeMonth -> "month"
            R.id.chipTimeAll -> "all"
            else -> "all"
        }

        // Store current filter params for pagination
        currentClipsSort = sort
        currentClipsTime = time
        clipsProfileSlug = channelSlug

        // Reset pagination state
        clipsNextCursor = null
        clipsCurrentList.clear()
        clipsIsLoading = false
        layout.clipsLoadingMore.visibility = View.GONE

        // Show Shimmer if not pulling-to-refresh
        if (!layout.refreshProfile.isRefreshing) {
            allClipsRecyclerView.visibility = View.GONE
            shimmer.visibility = View.VISIBLE
            shimmer.startShimmer()
        }

        lifecycleScope.launch {
            repository.getChannelClips(channelSlug, cursor = null, sort = sort, time = time).onSuccess { data ->
                withContext(Dispatchers.Main) {
                    layout.refreshProfile.isRefreshing = false
                    shimmer.stopShimmer()
                    shimmer.visibility = View.GONE
                    allClipsRecyclerView.visibility = View.VISIBLE

                    clipsCurrentList.addAll(data.clips)
                    clipsNextCursor = data.nextCursor

                    val adapter = dev.xacnio.kciktv.shared.ui.adapter.ChannelClipAdapter(
                        clipsCurrentList, 
                        true,
                        onItemClick = { clip -> openClipFeed(clipsCurrentList, clip, channelSlug) }
                    )
                    clipsAdapter = adapter
                    allClipsRecyclerView.adapter = adapter
                    
                    // Setup NestedScrollView listener for pagination
                    setupNestedScrollListener(layout)
                }
            }.onFailure {
                withContext(Dispatchers.Main) {
                    layout.refreshProfile.isRefreshing = false
                    shimmer.stopShimmer()
                    shimmer.visibility = View.GONE
                    allClipsRecyclerView.visibility = View.GONE
                }
            }
        }
    }
    
    private fun setupNestedScrollListener(layout: dev.xacnio.kciktv.databinding.LayoutChannelProfileBinding) {
        val nestedScrollView = layout.scrollView
        
        nestedScrollView.setOnScrollChangeListener { v: androidx.core.widget.NestedScrollView, _, scrollY, _, _ ->
            val childHeight = v.getChildAt(0)?.height ?: 0
            val scrollViewHeight = v.height
            val bottomThreshold = 500 // Increased threshold for better responsiveness
            
            // Check if near the bottom
            if (scrollY + scrollViewHeight >= childHeight - bottomThreshold) {
                // Check which tab is active
                val activeTab = layout.channelTabs.selectedTabPosition
                
                // Tab 3 = Clips tab
                if (activeTab == 3 && !clipsNextCursor.isNullOrEmpty() && !clipsIsLoading) {
                    android.util.Log.d("ChannelProfile", "Scroll triggered loadMoreClips()")
                    loadMoreClips()
                }
            }
        }
    }
    
    private fun loadMoreClips() {
        val slug = clipsProfileSlug ?: return
        if (clipsIsLoading || clipsNextCursor.isNullOrEmpty()) return
        
        clipsIsLoading = true
        val layout = binding.channelProfileContainer
        
        // Show loading indicator
        layout.clipsLoadingMore.visibility = View.VISIBLE
        
        lifecycleScope.launch {
            repository.getChannelClips(slug, cursor = clipsNextCursor, sort = currentClipsSort, time = currentClipsTime).onSuccess { data ->
                withContext(Dispatchers.Main) {
                    layout.clipsLoadingMore.visibility = View.GONE
                    
                    // Filter out duplicates based on clip ID
                    val existingIds = clipsCurrentList.map { it.id }.toSet()
                    val newClips = data.clips.filter { it.id !in existingIds }
                    
                    if (newClips.isNotEmpty()) {
                        val startPosition = clipsCurrentList.size
                        clipsCurrentList.addAll(newClips)
                        clipsAdapter?.notifyItemRangeInserted(startPosition, newClips.size)
                    }
                    
                    clipsNextCursor = data.nextCursor
                    clipsIsLoading = false
                }
            }.onFailure {
                withContext(Dispatchers.Main) {
                    layout.clipsLoadingMore.visibility = View.GONE
                    clipsIsLoading = false
                }
            }
        }
    }
    
    private fun loadChannelAbout(channelSlug: String) {
        val layout = binding.channelProfileContainer
        val linksRecyclerView = layout.aboutLinksRecyclerView
        
        lifecycleScope.launch {
            repository.getChannelLinks(channelSlug).onSuccess { links ->
                withContext(Dispatchers.Main) {
                    if (links.isNotEmpty()) {
                        linksRecyclerView.visibility = View.VISIBLE
                        setupResponsiveGrid(linksRecyclerView)
                        val adapter = dev.xacnio.kciktv.shared.ui.adapter.ChannelAboutLinkAdapter(links)
                        linksRecyclerView.adapter = adapter
                    } else {
                        linksRecyclerView.visibility = View.GONE
                    }
                }
            }.onFailure {
                withContext(Dispatchers.Main) {
                    linksRecyclerView.visibility = View.GONE
                }
            }
        }
    }
    private fun openClipFeed(clips: List<dev.xacnio.kciktv.shared.data.model.ChannelClip>, startClip: dev.xacnio.kciktv.shared.data.model.ChannelClip, channelSlug: String) {
        val feedClips = clips.map { clip ->
            dev.xacnio.kciktv.shared.data.model.ClipPlayDetails(
                id = clip.id,
                livestreamId = null,
                categoryId = null,
                channelId = loadedChannel?.id,
                userId = null,
                title = clip.title,
                clipUrl = clip.url,
                thumbnailUrl = clip.thumbnailUrl,
                videoUrl = clip.url,
                views = clip.views,
                viewCount = clip.views,
                duration = clip.duration,
                startedAt = clip.createdAt,
                createdAt = clip.createdAt,
                vodStartsAt = null,
                isMature = false,
                vod = null,
                category = null,
                creator = clip.creator,
                channel = loadedChannel?.let { ch ->
                    dev.xacnio.kciktv.shared.data.model.ClipChannel(
                        id = ch.id ?: 0L,
                        username = ch.user?.username ?: "",
                        slug = ch.slug ?: channelSlug,
                        profilePicture = ch.user?.profilePic
                    )
                } ?: dev.xacnio.kciktv.shared.data.model.ClipChannel(0, channelSlug, channelSlug, null)
            )
        }
        
        val startIndex = feedClips.indexOfFirst { it.id == startClip.id }
        if (startIndex >= 0) {
            activity.clipFeedManager.openFeed(
                feedClips, 
                startIndex, 
                channelSlug = channelSlug, 
                initialCursor = clipsNextCursor,
                sort = currentClipsSort,
                time = currentClipsTime
            )
        }
    }

    private fun setupResponsiveGrid(recyclerView: androidx.recyclerview.widget.RecyclerView, config: android.content.res.Configuration? = null) {
        val widthDp = config?.screenWidthDp ?: activity.resources.configuration.screenWidthDp
        val spanCount = when {
            widthDp >= 1200 -> 4
            widthDp >= 900 -> 3
            widthDp >= 600 -> 2
            else -> 1
        }
        
        val lm = recyclerView.layoutManager
        if (lm !is androidx.recyclerview.widget.GridLayoutManager || lm.spanCount != spanCount) {
            recyclerView.layoutManager = androidx.recyclerview.widget.GridLayoutManager(activity, spanCount)
        }
        recyclerView.isNestedScrollingEnabled = false
    }

    fun updateLayoutsForOrientation(config: android.content.res.Configuration? = null) {
        if (!isChannelProfileVisible) return

        val layout = binding.channelProfileContainer
        setupResponsiveGrid(layout.videosRecyclerView, config)
        setupResponsiveGrid(layout.clipsRecyclerView, config)
        setupResponsiveGrid(layout.aboutLinksRecyclerView, config)
        setupResponsiveGrid(layout.allVideosRecyclerView, config) // Videos Tab
        setupResponsiveGrid(layout.allClipsRecyclerView, config)   // Clips Tab
    }

    fun handleBack(): Boolean {
        android.util.Log.d("ChannelProfileManager", "handleBack() called")
        android.util.Log.d("ChannelProfileManager", "  playerVisible=${binding.playerScreenContainer.visibility == View.VISIBLE}")
        android.util.Log.d("ChannelProfileManager", "  isMiniPlayerMode=${activity.miniPlayerManager.isMiniPlayerMode}")
        android.util.Log.d("ChannelProfileManager", "  isChannelProfileVisible=$isChannelProfileVisible")
        
        // If player is visible (full or mini), let activity handle back first
        if (binding.playerScreenContainer.visibility == View.VISIBLE) {
            android.util.Log.d("ChannelProfileManager", "  -> return false (player visible)")
            return false
        }
        
        // If mini player mode is active, let activity handle it
        if (activity.miniPlayerManager.isMiniPlayerMode) {
            android.util.Log.d("ChannelProfileManager", "  -> return false (miniPlayerMode)")
            return false
        }
        
        // If profile is visible and no player, close the profile
        if (isChannelProfileVisible) {
            android.util.Log.d("ChannelProfileManager", "  -> closeChannelProfile(), return true")
            closeChannelProfile()
            return true
        }
        
        android.util.Log.d("ChannelProfileManager", "  -> return false (default)")
        return false
    }
}
