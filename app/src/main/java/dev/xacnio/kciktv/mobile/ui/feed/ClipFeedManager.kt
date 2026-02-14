/**
 * File: ClipFeedManager.kt
 *
 * Description: Manages the TikTok-style vertical clip feed experience.
 * It handles the ViewPager2 setup, a 3-player system for seamless playback and preloading,
 * pagination of clips, and user interactions like mute, share, and channel navigation.
 *
 * Author: Xacnio
 *
 */
package dev.xacnio.kciktv.mobile.ui.feed

import android.content.Intent
import android.graphics.Bitmap
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.lifecycle.lifecycleScope
import androidx.viewpager2.widget.ViewPager2
import com.amazonaws.ivs.player.Player
import dev.xacnio.kciktv.mobile.MobilePlayerActivity
import dev.xacnio.kciktv.R
import dev.xacnio.kciktv.shared.data.model.ClipPlayDetails
import dev.xacnio.kciktv.databinding.LayoutClipFeedBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.abs
import dev.xacnio.kciktv.shared.ui.adapter.FeedCategory
import dev.xacnio.kciktv.shared.ui.adapter.FeedCategoryAdapter
import dev.xacnio.kciktv.shared.data.api.RetrofitClient
import dev.xacnio.kciktv.shared.data.model.ChannelClip
import dev.xacnio.kciktv.shared.data.model.ChannelItem

/**
 * Manager class for TikTok-style vertical clip feed.
 * Allows swiping up/down to navigate between clips.
 * 
 * Uses a 3-player system:
 * - Current player: plays current clip
 * - Previous player: keeps previous clip ready for back navigation
 * - Next player: preloads next clip for instant forward navigation
 */
class ClipFeedManager(private val activity: MobilePlayerActivity) {
    
    private val binding = activity.binding
    private val repository = activity.repository
    
    private var feedBinding: LayoutClipFeedBinding? = null
    private var feedAdapter: ClipFeedAdapter? = null
    private var clipsList = mutableListOf<ClipPlayDetails>()
    
    // 3-player system
    private var player1: Player? = null
    private var player2: Player? = null
    private var player3: Player? = null
    
    // Track which position each player is assigned to (-1 = idle/unassigned)
    private var player1Position = -1
    private var player2Position = -1
    private var player3Position = -1
    
    // Current position and settings
    private var currentPosition = -1
    private var isMuted = false
    private var isAutoScrollEnabled = false
    
    // Pagination state
    private var nextCursor: String? = null
    private var isLoading = false
    private var hasMore = true
    
    private var viewPagerCallback: ViewPager2.OnPageChangeCallback? = null
    
    private var currentChannelSlug: String? = null
    private var currentCategorySlug: String = "all"
    
    // Filter state
    private var currentClipsSort = "view" // date, view
    private var currentClipsTime = "day"  // day, week, month, all
    
    // Cache for last frames
    private val lastFrameCache = ConcurrentHashMap<Int, Bitmap>()
    
    // Preload jobs for cancellation
    private var preloadNextJob: Job? = null
    private var preloadPrevJob: Job? = null
    
    // Feed is "active" (loaded but may be hidden temporarily)
    var isFeedActive = false
        private set
    
    // Feed is truly visible on screen (used for back press handling)
    val isFeedVisible: Boolean
        get() = isFeedActive && feedBinding?.root?.visibility == View.VISIBLE

    val feedRootView: View? get() = feedBinding?.root
    
    // Track if playback was paused by lifecycle (app going to background)
    private var wasPausedByLifecycle = false
    
    /**
     * Called when the activity goes to background. Pauses video playback.
     */
    fun onPause() {
        if (isFeedVisible && currentPosition >= 0) {
            val playerPair = getPlayerForPosition(currentPosition)
            val player = playerPair?.second
            if (player?.state == Player.State.PLAYING) {
                player.pause()
                wasPausedByLifecycle = true
                android.util.Log.d("ClipFeedManager", "Paused playback due to lifecycle")
            }
        }
    }
    
    /**
     * Called when the activity comes to foreground. Resumes video playback if it was paused by lifecycle.
     */
    fun onResume() {
        if (isFeedVisible && wasPausedByLifecycle && currentPosition >= 0) {
            val playerPair = getPlayerForPosition(currentPosition)
            playerPair?.second?.play()
            wasPausedByLifecycle = false
            android.util.Log.d("ClipFeedManager", "Resumed playback after lifecycle")
        }
    }

    /**
     * Opens the clip feed with the provided list of clips.
     */
    fun openFeed(
        clips: List<ClipPlayDetails>, 
        startIndex: Int = 0, 
        channelSlug: String? = null, 
        initialCursor: String? = null,
        sort: String? = null,
        time: String? = null
    ) {
        currentChannelSlug = channelSlug
        if (sort != null) currentClipsSort = sort
        if (time != null) currentClipsTime = time
        
        this.nextCursor = initialCursor
        this.hasMore = !initialCursor.isNullOrEmpty()
        this.isLoading = false
        if (clips.isEmpty()) {
            Toast.makeText(activity, activity.getString(R.string.no_clips_found), Toast.LENGTH_SHORT).show()
            return
        }
        
        if (feedBinding == null) {
            feedBinding = LayoutClipFeedBinding.inflate(
                LayoutInflater.from(activity),
                binding.root as ViewGroup,
                false
            )
            (binding.root as ViewGroup).addView(feedBinding!!.root)
            setupGlobalListeners()
        }
        
        clipsList.clear()
        clipsList.addAll(clips)
        clearCaches()
        setupAdapter()
        setupViewPager(startIndex)
        setupCategories()
        showFeed()
        activity.setCurrentScreen(MobilePlayerActivity.AppScreen.CLIP_FEED)
        hideOtherViews()
        initializePlayers()
        
        if (clipsList.isNotEmpty()) {
            playClipAtPosition(startIndex)
        }
    }
    
    private fun clearCaches() {
        lastFrameCache.values.forEach { bitmap ->
            if (!bitmap.isRecycled) bitmap.recycle()
        }
        lastFrameCache.clear()
    }
    
    /**
     * Creates a player listener for a specific player index
     */
    private fun createPlayerListener(playerIndex: Int) = object : Player.Listener() {
        override fun onStateChanged(state: Player.State) {
            val position = getPlayerPosition(playerIndex)
            if (position < 0) return
            
            // Loop functionality for clips or Auto Scroll
            if (state == Player.State.ENDED) {
                if (isAutoScrollEnabled && currentPosition < clipsList.size - 1) {
                    activity.runOnUiThread {
                        feedBinding?.clipFeedViewPager?.currentItem = currentPosition + 1
                    }
                } else {
                    getPlayer(playerIndex)?.seekTo(0)
                    getPlayer(playerIndex)?.play()
                }
                return
            }
            
            // Only handle UI updates for the current position
            if (position == currentPosition) {
                val viewHolder = getViewHolder(position)
                when (state) {
                    Player.State.PLAYING -> {
                        activity.runOnUiThread {
                            viewHolder?.hideLoading()
                            binding.root.postDelayed({
                                viewHolder?.showPlayer()
                            }, 50)
                            
                            // Keep screen on while playing in feed
                            activity.window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                            
                            // Update duration badge if available
                            val duration = getPlayer(playerIndex)?.duration ?: 0
                            if (duration > 0) {
                                viewHolder?.updateTimeDisplay(getPlayer(playerIndex)?.position ?: 0, duration)
                            }
                        }
                    }
                    Player.State.BUFFERING -> {
                        activity.runOnUiThread {
                            viewHolder?.showLoading()
                        }
                    }
                    Player.State.IDLE -> {
                        activity.runOnUiThread {
                            activity.window.clearFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                        }
                    }
                    else -> {}
                }
            } else {
                // Background player (Preloading)
                if (state == Player.State.PLAYING) {
                    // Pause immediately after starting to buffer the first frame
                    // This ensures instant playback when swiped to, without auto-playing in background
                    getPlayer(playerIndex)?.pause()
                    getPlayer(playerIndex)?.seekTo(0)
                }
            }
        }

        override fun onError(exception: com.amazonaws.ivs.player.PlayerException) {
            val position = getPlayerPosition(playerIndex)
            if (position == currentPosition) {
                val viewHolder = getViewHolder(position)
                activity.runOnUiThread {
                    viewHolder?.hideLoading()
                    viewHolder?.showThumbnail()
                }
            }
        }

        override fun onDurationChanged(duration: Long) {
            // Update seekbar range
            val position = getPlayerPosition(playerIndex)
            if (position == currentPosition) {
                val viewHolder = getViewHolder(position)
                activity.runOnUiThread {
                     viewHolder?.updateTimeDisplay(getPlayer(playerIndex)?.position ?: 0, duration)
                }
            }
        }
        
        override fun onCue(cue: com.amazonaws.ivs.player.Cue) {}
        override fun onMetadata(type: String, data: java.nio.ByteBuffer) {}
        override fun onQualityChanged(quality: com.amazonaws.ivs.player.Quality) {}
        override fun onRebuffering() {
            val position = getPlayerPosition(playerIndex)
            if (position == currentPosition) {
                val viewHolder = getViewHolder(position)
                activity.runOnUiThread { viewHolder?.showLoading() }
            }
        }
        override fun onSeekCompleted(duration: Long) {}
        override fun onVideoSizeChanged(width: Int, height: Int) {}
    }
    
    private fun initializePlayers() {
        if (player1 == null) {
            player1 = Player.Factory.create(activity).apply {
                setMuted(true)
                addListener(createPlayerListener(1))
                setLooping(false) // Clips should loop
            }
        }
        if (player2 == null) {
            player2 = Player.Factory.create(activity).apply {
                setMuted(true)
                addListener(createPlayerListener(2))
                setLooping(true)
            }
        }
        if (player3 == null) {
            player3 = Player.Factory.create(activity).apply {
                setMuted(true)
                addListener(createPlayerListener(3))
                setLooping(true)
            }
        }
    }
    
    private fun getPlayer(index: Int): Player? = when(index) {
        1 -> player1
        2 -> player2
        3 -> player3
        else -> null
    }
    
    private fun getPlayerPosition(index: Int): Int = when(index) {
        1 -> player1Position
        2 -> player2Position
        3 -> player3Position
        else -> -1
    }
    
    private fun setPlayerPosition(index: Int, position: Int) {
        when(index) {
            1 -> player1Position = position
            2 -> player2Position = position
            3 -> player3Position = position
        }
    }
    
    private fun getPlayerForPosition(position: Int): Pair<Int, Player>? {
        if (player1Position == position && player1 != null) return 1 to player1!!
        if (player2Position == position && player2 != null) return 2 to player2!!
        if (player3Position == position && player3 != null) return 3 to player3!!
        return null
    }
    
    private fun getIdlePlayer(): Pair<Int, Player>? {
        if (player1Position == -1 && player1 != null) return 1 to player1!!
        if (player2Position == -1 && player2 != null) return 2 to player2!!
        if (player3Position == -1 && player3 != null) return 3 to player3!!
        return null
    }
    
    private fun getPlayerToRelease(currentPos: Int, excludePositions: Set<Int>): Pair<Int, Player>? {
        val candidates = mutableListOf<Pair<Int, Pair<Int, Player>>>()
        
        if (player1 != null && player1Position !in excludePositions) {
            candidates.add(player1Position to (1 to player1!!))
        }
        if (player2 != null && player2Position !in excludePositions) {
            candidates.add(player2Position to (2 to player2!!))
        }
        if (player3 != null && player3Position !in excludePositions) {
            candidates.add(player3Position to (3 to player3!!))
        }
        
        return candidates.maxByOrNull { abs(it.first - currentPos) }?.second
    }
    
    private fun getViewHolder(position: Int): ClipFeedAdapter.ClipViewHolder? {
        if (feedBinding == null || position < 0) return null
        return (feedBinding?.clipFeedViewPager?.getChildAt(0) as? androidx.recyclerview.widget.RecyclerView)
            ?.findViewHolderForAdapterPosition(position) as? ClipFeedAdapter.ClipViewHolder
    }

    private fun getCurrentViewHolder(): ClipFeedAdapter.ClipViewHolder? {
        return getViewHolder(currentPosition)
    }
    
    /**
     * Main method to play a clip at given position.
     */
    private fun playClipAtPosition(position: Int) {
        if (position < 0 || position >= clipsList.size) return
        
        val previousPosition = currentPosition
        val clip = clipsList[position]
        val clipUrl = clip.videoUrl ?: clip.clipUrl ?: return
        
        // Capture last frame from previous position
        if (previousPosition >= 0 && previousPosition != position) {
            captureLastFrame(previousPosition)
        }
        
        // Check if we already have a player for this position
        val existingPlayer = getPlayerForPosition(position)
        
        if (existingPlayer != null) {
            // Player already assigned to this position - just make it current
            val (_, player) = existingPlayer
            currentPosition = position
            startUpdatingTime(position)
            
            // Show this player
            val viewHolder = getViewHolder(position)
            viewHolder?.attachPlayer(player)
            player.setMuted(isMuted)
            // Sync mute UI
            viewHolder?.updateMuteIcon(isMuted)
            
            player.seekTo(0)
            player.setLooping(false) // Ensure looping is disabled so ENDED state fires for Auto Scroll
            player.play()
            
            if (player.state == Player.State.PLAYING) {
                viewHolder?.showPlayer()
            }
            
            // Stop the previous position's player if different
            if (previousPosition >= 0 && previousPosition != position) {
                val prevPlayer = getPlayerForPosition(previousPosition)
                prevPlayer?.second?.pause()
                getViewHolder(previousPosition)?.showThumbnail()
            }
            
            // Preload neighbors
            preloadNeighbors(position)
            return
        }
        
        // No player for this position - need to load
        val viewHolder = getViewHolder(position)
        
        // Show cached frame or thumbnail
        val cachedFrame = lastFrameCache[position]
        if (cachedFrame != null && !cachedFrame.isRecycled) {
            viewHolder?.showLastFrame(cachedFrame)
        }
        
        // Stop previous stream
        if (previousPosition >= 0 && previousPosition != position) {
            val prevPlayer = getPlayerForPosition(previousPosition)
            prevPlayer?.second?.pause()
            getViewHolder(previousPosition)?.showThumbnail()
        }
        
        currentPosition = position
        startUpdatingTime(position)
        
        // Get a player to use
        var playerToUse = getIdlePlayer()
        
        if (playerToUse == null) {
            // Need to reuse a player - get the one furthest from current position
            val keepPositions = setOf(position - 1, position, position + 1).filter { it >= 0 }.toSet()
            playerToUse = getPlayerToRelease(position, keepPositions)
            
            if (playerToUse != null) {
                // Stop and release the player from its current position
                playerToUse.second.pause()
                setPlayerPosition(playerToUse.first, -1)
            }
        }
         // Fallback if strictly all players are somehow locked/null (should not happen with logic above)
        if (playerToUse == null) {
            player1?.pause()
            player1Position = -1
            playerToUse = 1 to player1!!
        }
        
        val (playerIndex, player) = playerToUse
        setPlayerPosition(playerIndex, position)
        
        // Load the clip
        loadAndPlay(player, position, clipUrl, viewHolder)
    }
    
    private fun loadAndPlay(
        player: Player,
        position: Int,
        url: String,
        viewHolder: ClipFeedAdapter.ClipViewHolder?
    ) {
        viewHolder?.showLoading()
        viewHolder?.attachPlayer(player)
        
        player.load(android.net.Uri.parse(url))
        player.play()
        player.setMuted(isMuted)
        viewHolder?.updateMuteIcon(isMuted) // Sync UI
        
        if (player.state == Player.State.PLAYING) {
            activity.runOnUiThread {
                viewHolder?.hideLoading()
                viewHolder?.showPlayer()
            }
        }
        
        // Preload neighbors
        preloadNeighbors(position)
    }
    
    /**
     * Preload next and previous clips
     */
    private fun preloadNeighbors(currentPos: Int) {
        // Preload next
        if (currentPos + 1 < clipsList.size) {
            preloadPosition(currentPos + 1)
        }
        
        // Preload previous (if exists)
        if (currentPos - 1 >= 0) {
            preloadPosition(currentPos - 1)
        }
    }
    
    private fun preloadPosition(position: Int) {
        if (position < 0 || position >= clipsList.size) return
        
        // Already has a player?
        if (getPlayerForPosition(position) != null) return
        
        val clip = clipsList[position]
        val url = clip.videoUrl ?: clip.clipUrl ?: return
        
        // Get an idle player or release one
        var playerToUse = getIdlePlayer()
        
        if (playerToUse == null) {
            // Need to release a player - get the one furthest from current
            val keepPositions = setOf(currentPosition - 1, currentPosition, currentPosition + 1)
                .filter { it >= 0 }.toSet()
            playerToUse = getPlayerToRelease(currentPosition, keepPositions)
            
            if (playerToUse != null) {
                playerToUse.second.pause()
                setPlayerPosition(playerToUse.first, -1)
            }
        }
        
        if (playerToUse == null) return
        
        val (playerIndex, player) = playerToUse
        setPlayerPosition(playerIndex, position)
        
        activity.runOnUiThread {
            preloadClip(player, position, url)
        }
    }
    
    private fun preloadClip(player: Player, position: Int, url: String) {
        val viewHolder = getViewHolder(position)
        if (viewHolder != null) {
            viewHolder.prepareForPreload(player)
        }
        
        player.load(android.net.Uri.parse(url))
        player.setMuted(true)
        player.play()
    }
    
    private fun captureLastFrame(position: Int) {
        getViewHolder(position)?.let { holder ->
            holder.captureCurrentFrame()?.let { bitmap ->
                lastFrameCache[position]?.let { old ->
                    if (!old.isRecycled) old.recycle()
                }
                lastFrameCache[position] = bitmap
            }
        }
    }
    
    private fun setupAdapter() {
        feedAdapter = ClipFeedAdapter(
            clips = clipsList,
            onClipHold = { isHolding ->
                val player = getPlayerForPosition(currentPosition)?.second
                if (isHolding) {
                    player?.pause()
                } else {
                    player?.play()
                }
            },
            onClipTap = {
                toggleMute()
            },
            onShareClick = { clip ->
                shareClip(clip)
            },
            onFilterClick = {
                showFilterOptions()
            },
            onChannelClick = { clip ->
                pauseAndHideFeed()
                clip.channel?.slug?.let { slug ->
                    activity.channelProfileManager.openChannelProfileFromClipFeed(slug)
                }
            },
            onPlayInPlayerClick = { details ->
                val clip = mapDetailsToChannelClip(details)
                val channel = details.channel?.let { ch ->
                    dev.xacnio.kciktv.shared.data.model.ChannelItem(
                        id = ch.id?.toString() ?: "0",
                        slug = ch.slug ?: "",
                        username = ch.username ?: "",
                        title = details.title ?: "",
                        viewerCount = details.views ?: details.viewCount ?: 0,
                        thumbnailUrl = details.thumbnailUrl,
                        profilePicUrl = ch.profilePicture,
                        playbackUrl = details.videoUrl ?: details.clipUrl,
                        categoryName = details.category?.name,
                        categorySlug = details.category?.slug,
                        language = "tr",
                        isLive = false
                    )
                } ?: dev.xacnio.kciktv.shared.data.model.ChannelItem(
                    id = "0",
                    slug = "kick",
                    username = "kick",
                    title = details.title ?: "",
                    viewerCount = 0,
                    thumbnailUrl = null,
                    profilePicUrl = null,
                    playbackUrl = null,
                    categoryName = null,
                    categorySlug = null,
                    language = "tr"
                )
                
                // Pause instead of close
                getPlayerForPosition(currentPosition)?.second?.pause()
                
                activity.playClip(clip, channel)
            },
            onSeekChanged = { pos, progress ->
                if (pos == currentPosition) {
                    val player = getPlayerForPosition(currentPosition)?.second
                    val duration = player?.duration ?: 0
                    if (duration > 0) {
                        player?.seekTo((duration * progress).toLong())
                    }
                }
            },
            onMoreClick = { clip ->
                showMoreOptions(clip)
            },
            onScaleToggle = {
                toggleScale()
            },
            onViewAttached = { position, holder ->
                // Apply insets padding (status bar + nav bar)
                holder.applyInsetsPadding(currentStatusBarHeight, currentNavBarHeight)
                
                // Apply persistent scale mode
                holder.setScaleMode(activity.prefs.isClipContentFullscreen)

                // Show cached frame if available
                lastFrameCache[position]?.let { bitmap ->
                    if (!bitmap.isRecycled) holder.showLastFrame(bitmap)
                }
                
                if (position == currentPosition) {
                    getPlayerForPosition(position)?.second?.let { player ->
                        holder.attachPlayer(player)
                        if (player.state == Player.State.PLAYING) {
                            holder.showPlayer()
                        }
                    }
                }
            },
            onViewDetached = { position, holder ->
                holder.captureCurrentFrame()?.let { bitmap ->
                    lastFrameCache[position]?.let { old ->
                        if (!old.isRecycled) old.recycle()
                    }
                    lastFrameCache[position] = bitmap
                }
                holder.detachPlayer()
            }
        )
        
        feedBinding?.clipFeedViewPager?.adapter = feedAdapter
        syncFilterLabels()
    }
    
    private fun syncFilterLabels() {
        val sortName = when (currentClipsSort) {
            "date" -> activity.getString(R.string.clip_filter_newest)
            "view" -> activity.getString(R.string.clip_filter_popular)
            else -> activity.getString(R.string.clip_filter_featured)
        }
        
        val timeName = when (currentClipsTime) {
            "day" -> activity.getString(R.string.clip_filter_daily)
            "week" -> activity.getString(R.string.clip_filter_weekly)
            "month" -> activity.getString(R.string.clip_filter_monthly)
            "all" -> "(" + activity.getString(R.string.clip_filter_all) + ")" 
            else -> ""
        }

        feedAdapter?.filterName = sortName
        feedAdapter?.subFilterName = if (currentClipsSort == "view") timeName else ""
    }
    
    private fun startUpdatingTime(position: Int) {
        // Simple polling for seekbar update
        activity.lifecycleScope.launch {
            while (position == currentPosition && isFeedVisible) {
                val player = getPlayerForPosition(position)?.second
                if (player != null && player.duration > 0) {
                    val progress = player.position.toFloat() / player.duration
                    
                    activity.runOnUiThread {
                        getViewHolder(position)?.let { holder ->
                            holder.updateSeekBar(progress)
                            holder.updateTimeDisplay(player.position, player.duration)
                        }
                    }
                }
                kotlinx.coroutines.delay(50) // Update every 50ms for smooth UI
            }
        }
    }
    
    private fun toggleMute() {
        isMuted = !isMuted
        
        // Update all players immediately
        player1?.setMuted(isMuted)
        player2?.setMuted(isMuted)
        player3?.setMuted(isMuted)
        
        // Update adapter state (flag only)
        feedAdapter?.setMuted(isMuted)
        
        // Provide immediate visual feedback for the current item
        getViewHolder(currentPosition)?.updateMuteIcon(isMuted, true)
    }
    
    private fun setupViewPager(startIndex: Int) {
        feedBinding?.clipFeedViewPager?.apply {
            offscreenPageLimit = 1
            
            // Unregister old callback if exists
            viewPagerCallback?.let { unregisterOnPageChangeCallback(it) }
            
            val callback = object : ViewPager2.OnPageChangeCallback() {
                override fun onPageSelected(position: Int) {
                    super.onPageSelected(position)
                    playClipAtPosition(position)
                    
                    // Load more when reaching near the end
                    if (position >= clipsList.size - 3 && !isLoading && hasMore) {
                        loadNextPage()
                    }
                }
            }
            viewPagerCallback = callback
            registerOnPageChangeCallback(callback)

            // Use post to ensure the adapter is settled before changing item
            post {
                setCurrentItem(startIndex, false)
            }
        }
    }
    
    fun restoreVisibility() {
        showFeed()
    }

    private fun showFeed() {
        feedBinding?.root?.visibility = View.VISIBLE
        feedBinding?.root?.bringToFront()
        isFeedActive = true
    }
    
    /**
     * Pauses playback and hides the feed temporarily (e.g., when opening Channel Profile).
     * Feed can be resumed later with resumeFeed().
     */
    fun pauseAndHideFeed() {
        // Pause current playback
        getPlayerForPosition(currentPosition)?.second?.pause()
        
        // Clear screen on flag when hidden
        activity.window.clearFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        // Hide feed but don't destroy it
        // isFeedActive remains true so resumeFeed() knows there's an active session
        feedBinding?.root?.visibility = View.GONE
    }
    
    /**
     * Resumes the feed after it was hidden by pauseAndHideFeed().
     */
    fun resumeFeed() {
        if (!isFeedActive) return
        
        feedBinding?.root?.visibility = View.VISIBLE
        feedBinding?.root?.bringToFront()
        
        // Resume playback
        if (currentPosition >= 0) {
            getPlayerForPosition(currentPosition)?.second?.play()
        }
        
        // Hide other views but preserve previouslyVisibleView for correct back navigation
        val savedPreviousView = previouslyVisibleView
        hideOtherViews()
        previouslyVisibleView = savedPreviousView
    }
    
    private var previouslyVisibleView: View? = null

    private fun hideOtherViews() {
        previouslyVisibleView = when {
            binding.categoryDetailsContainer.root.visibility == View.VISIBLE -> binding.categoryDetailsContainer.root
            binding.homeScreenContainer.root.visibility == View.VISIBLE -> binding.homeScreenContainer.root
            binding.browseScreenContainer.root.visibility == View.VISIBLE -> binding.browseScreenContainer.root
            binding.channelProfileContainer.root.visibility == View.VISIBLE -> binding.channelProfileContainer.root
            binding.followingScreenContainer.root.visibility == View.VISIBLE -> binding.followingScreenContainer.root
            binding.searchContainer.visibility == View.VISIBLE -> binding.searchContainer
            else -> null
        }

        binding.categoryDetailsContainer.root.visibility = View.GONE
        binding.homeScreenContainer.root.visibility = View.GONE
        binding.browseScreenContainer.root.visibility = View.GONE
        binding.followingScreenContainer.root.visibility = View.GONE
        binding.searchContainer.visibility = View.GONE
        binding.bottomNavContainer.visibility = View.GONE
        binding.bottomNavGradient.visibility = View.GONE
        binding.channelProfileContainer.root.visibility = View.GONE
        binding.mobileHeader.visibility = View.GONE
        
        if (!activity.miniPlayerManager.isMiniPlayerMode) {
            binding.playerScreenContainer.visibility = View.GONE
        }
    }
    
    fun closeFeed() {
        preloadNextJob?.cancel()
        preloadPrevJob?.cancel()
        
        player1?.pause()
        player1?.release()
        player1 = null
        player1Position = -1
        
        player2?.pause()
        player2?.release()
        player2 = null
        player2Position = -1
        
        player3?.pause()
        player3?.release()
        player3 = null
        player3Position = -1
        
        currentPosition = -1
        clearCaches()
        
        // Clear screen on flag
        activity.window.clearFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        feedBinding?.root?.visibility = View.GONE
        isFeedActive = false
        
        // Restore previous state - prioritize Category Details if that was the origin
        val shouldReturnToCategoryDetails = previouslyVisibleView == binding.categoryDetailsContainer.root ||
                                            activity.browseManager.isCategoryDetailsVisible ||
                                            currentCategorySlug != "all"
        
        if (shouldReturnToCategoryDetails) {
            binding.categoryDetailsContainer.root.visibility = View.VISIBLE
            binding.mobileHeader.visibility = View.GONE
            activity.setCurrentScreen(MobilePlayerActivity.AppScreen.CATEGORY_DETAILS)
        } else if (previouslyVisibleView != null) {
            previouslyVisibleView?.visibility = View.VISIBLE
            val shouldShowHeader = previouslyVisibleView != binding.channelProfileContainer.root &&
                                   previouslyVisibleView != binding.playerScreenContainer
            binding.mobileHeader.visibility = if (shouldShowHeader) View.VISIBLE else View.GONE
        } else {
            binding.browseScreenContainer.root.visibility = View.VISIBLE
            binding.mobileHeader.visibility = View.VISIBLE
            activity.setCurrentScreen(MobilePlayerActivity.AppScreen.BROWSE)
        }
        
        clipsList = mutableListOf()
        nextCursor = null
        hasMore = true
        isLoading = false
    }
    
    private fun shareClip(clip: ClipPlayDetails) {
        val slug = clip.channel?.slug ?: "video"
        val shareUrl = "https://kick.com/$slug/clips/${clip.id}"
        val shareIntent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_SUBJECT, clip.title ?: "Clip")
            putExtra(Intent.EXTRA_TEXT, "${activity.getString(R.string.check_out_clip)}\n$shareUrl")
        }
        activity.startActivity(Intent.createChooser(shareIntent, activity.getString(R.string.share_via)))
    }
    
    private fun showMoreOptions(clip: ClipPlayDetails) {
        val dialog = com.google.android.material.bottomsheet.BottomSheetDialog(activity)
        val view = LayoutInflater.from(activity).inflate(R.layout.bottom_sheet_clip_options, null, false)
        dialog.setContentView(view)
        dialog.behavior.state = com.google.android.material.bottomsheet.BottomSheetBehavior.STATE_EXPANDED
        dialog.behavior.skipCollapsed = true
        
        // Auto Scroll
        val switchAutoScroll = view.findViewById<com.google.android.material.switchmaterial.SwitchMaterial>(R.id.switchAutoScroll)
        val optionAutoScroll = view.findViewById<View>(R.id.optionAutoScroll)
        
        switchAutoScroll.isChecked = isAutoScrollEnabled
        
        val toggleAction = {
            switchAutoScroll.toggle()
        }
        
        optionAutoScroll.setOnClickListener { toggleAction() }
        
        switchAutoScroll.setOnCheckedChangeListener { _, isChecked ->
            isAutoScrollEnabled = isChecked
        }

        // Download
        view.findViewById<View>(R.id.optionDownload).setOnClickListener {
            dialog.dismiss()
            downloadClip(clip)
        }
        
        dialog.show()
    }

    private fun downloadClip(clip: ClipPlayDetails) {
        val clipId = clip.id ?: return
        
        Toast.makeText(activity, activity.getString(R.string.download_getting_url), Toast.LENGTH_SHORT).show()
        
        activity.lifecycleScope.launch(Dispatchers.IO) {
            try {
                val token = activity.prefs.authToken
                val authHeader = if (!token.isNullOrEmpty()) "Bearer $token" else null
                
                val response = RetrofitClient.channelService.getClipDownload(clipId, authHeader)
                if (response.isSuccessful) {
                    val downloadUrl = response.body()?.url
                    if (!downloadUrl.isNullOrEmpty()) {
                        android.util.Log.d("ClipFeedManager", "Got download URL: $downloadUrl")
                        withContext(Dispatchers.Main) {
                            startDownload(clip, downloadUrl)
                        }
                    } else {
                        throw Exception("Download URL is empty")
                    }
                } else {
                    throw Exception("API Error: ${response.code()}")
                }
            } catch (e: Exception) {
                android.util.Log.e("ClipFeedManager", "Failed to get download URL", e)
                withContext(Dispatchers.Main) {
                    Toast.makeText(activity, activity.getString(R.string.download_url_failed, e.message), Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    private fun startDownload(clip: ClipPlayDetails, url: String) {
        val title = clip.title ?: "Clip_${clip.id}"
        val sanitizedTitle = title.replace(Regex("[^a-zA-Z0-9.-]"), "_").take(50)
        val filename = "${sanitizedTitle}_${System.currentTimeMillis()}.mp4"
        
        android.util.Log.d("ClipFeedManager", "Starting direct download: $url")
        Toast.makeText(activity, activity.getString(R.string.download_starting), Toast.LENGTH_SHORT).show()
        
        activity.lifecycleScope.launch(Dispatchers.IO) {
            try {
                // Create KCIK folder
                val kcikFolder = java.io.File(android.os.Environment.getExternalStoragePublicDirectory(android.os.Environment.DIRECTORY_DOWNLOADS), "KCIK")
                if (!kcikFolder.exists()) {
                    kcikFolder.mkdirs()
                }
                
                val outputFile = java.io.File(kcikFolder, filename)
                
                val client = okhttp3.OkHttpClient.Builder()
                    .connectTimeout(30, java.util.concurrent.TimeUnit.SECONDS)
                    .readTimeout(60, java.util.concurrent.TimeUnit.SECONDS)
                    .build()
                
                val request = okhttp3.Request.Builder()
                    .url(url)
                    .addHeader("User-Agent", "Mozilla/5.0 (Linux; Android 10) AppleWebKit/537.36")
                    .build()
                
                val response = client.newCall(request).execute()
                
                if (!response.isSuccessful) {
                    throw Exception("HTTP ${response.code}")
                }
                
                val body = response.body
                if (body == null) {
                    throw Exception("Empty response body")
                }
                
                val contentLength = body.contentLength()
                android.util.Log.d("ClipFeedManager", "Download size: $contentLength bytes")
                
                var downloadedBytes = 0L
                val buffer = ByteArray(8192)
                
                java.io.FileOutputStream(outputFile).use { fos ->
                    body.byteStream().use { inputStream ->
                        var bytesRead: Int
                        var lastProgress = 0
                        while (inputStream.read(buffer).also { bytesRead = it } != -1) {
                            fos.write(buffer, 0, bytesRead)
                            downloadedBytes += bytesRead
                            
                            // Log progress every 10%
                            if (contentLength > 0) {
                                val progress = ((downloadedBytes * 100) / contentLength).toInt()
                                if (progress >= lastProgress + 10) {
                                    lastProgress = progress
                                    android.util.Log.d("ClipFeedManager", "Download progress: $progress%")
                                }
                            }
                        }
                    }
                }
                
                // Notify media scanner
                android.media.MediaScannerConnection.scanFile(
                    activity,
                    arrayOf(outputFile.absolutePath),
                    arrayOf("video/mp4"),
                    null
                )
                
                android.util.Log.d("ClipFeedManager", "Download complete: ${outputFile.absolutePath}")
                
                withContext(Dispatchers.Main) {
                    Toast.makeText(activity, activity.getString(R.string.download_complete, filename), Toast.LENGTH_LONG).show()
                }
                
            } catch (e: Exception) {
                android.util.Log.e("ClipFeedManager", "Download failed", e)
                withContext(Dispatchers.Main) {
                    Toast.makeText(activity, activity.getString(R.string.download_failed_with_error, e.message), Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    private fun showFilterOptions() {
        val dialog = com.google.android.material.bottomsheet.BottomSheetDialog(activity)
        val view = activity.layoutInflater.inflate(R.layout.bottom_sheet_clips_filter, null)
        dialog.setContentView(view)
        dialog.behavior.state = com.google.android.material.bottomsheet.BottomSheetBehavior.STATE_EXPANDED
        dialog.behavior.skipCollapsed = true

        val sortChipGroup = view.findViewById<com.google.android.material.chip.ChipGroup>(R.id.sortChipGroup)
        val timeChipGroup = view.findViewById<com.google.android.material.chip.ChipGroup>(R.id.timeChipGroup)

        // Hide view mode as it's not applicable here
        view.findViewById<android.view.View>(R.id.viewModeGroup)?.visibility = android.view.View.GONE
        
        // Find and hide the label for view mode if possible
        // (This depends on the exact structure, but we'll try to find the label)
        try {
            val root = view as android.view.ViewGroup
            for (i in 0 until root.childCount) {
                val child = root.getChildAt(i)
                if (child is android.widget.TextView && (child.text?.contains(activity.getString(R.string.text_view_contains)) == true || child.text?.contains("View") == true)) {
                    child.visibility = android.view.View.GONE
                }
            }
        } catch (e: Exception) {}

        // Set current selection
        when (currentClipsSort) {
            "date" -> sortChipGroup?.check(R.id.chipSortDate)
            "view" -> sortChipGroup?.check(R.id.chipSortViews)
        }

        when (currentClipsTime) {
            "day" -> timeChipGroup?.check(R.id.chipTimeDay)
            "week" -> timeChipGroup?.check(R.id.chipTimeWeek)
            "month" -> timeChipGroup?.check(R.id.chipTimeMonth)
            "all" -> timeChipGroup?.check(R.id.chipTimeAll)
        }

        sortChipGroup?.setOnCheckedStateChangeListener { _, checkedIds ->
            val checkedId = checkedIds.firstOrNull() ?: View.NO_ID
            val newSort = when (checkedId) {
                R.id.chipSortDate -> "date"
                R.id.chipSortViews -> "view"
                else -> currentClipsSort
            }
            if (newSort != currentClipsSort) {
                currentClipsSort = newSort
                updateFilterAndReload(dialog)
            }
        }

        timeChipGroup?.setOnCheckedStateChangeListener { _, checkedIds ->
            val checkedId = checkedIds.firstOrNull() ?: View.NO_ID
            val newTime = when (checkedId) {
                R.id.chipTimeDay -> "day"
                R.id.chipTimeWeek -> "week"
                R.id.chipTimeMonth -> "month"
                R.id.chipTimeAll -> "all"
                else -> currentClipsTime
            }
            if (newTime != currentClipsTime) {
                currentClipsTime = newTime
                updateFilterAndReload(dialog)
            }
        }

        dialog.show()
    }

    private fun updateFilterAndReload(dialog: com.google.android.material.bottomsheet.BottomSheetDialog) {
        syncFilterLabels()
        loadClipsForCategory(currentCategorySlug)
        dialog.dismiss()
    }
    
    private var categoryAdapter: FeedCategoryAdapter? = null

    private fun toggleScale() {
        val newMode = !activity.prefs.isClipContentFullscreen
        activity.prefs.isClipContentFullscreen = newMode
        
        // Update all visible ViewHolders
        val recyclerView = feedBinding?.clipFeedViewPager?.getChildAt(0) as? androidx.recyclerview.widget.RecyclerView
        recyclerView?.let { rv ->
             for (i in 0 until rv.childCount) {
                 val child = rv.getChildAt(i)
                 val holder = rv.getChildViewHolder(child) as? ClipFeedAdapter.ClipViewHolder
                 holder?.setScaleMode(newMode)
             }
        }
    }

    private fun setupCategories() {
        if (currentChannelSlug != null) {
            feedBinding?.rvCategories?.visibility = View.GONE
            return
        }
        
        feedBinding?.rvCategories?.visibility = View.VISIBLE
        categoryAdapter = FeedCategoryAdapter { category ->
            if (currentCategorySlug != category.slug) {
                currentCategorySlug = category.slug
                categoryAdapter?.selectCategory(category.slug)
                loadClipsForCategory(category.slug)
            }
        }
        feedBinding?.rvCategories?.adapter = categoryAdapter
        
        loadCategories()
    }
    
    private fun loadCategories() {
        activity.lifecycleScope.launch(Dispatchers.IO) {
            try {
                // Hardcoded "All" + "Just Chatting" first
                val initialList = mutableListOf(
                    FeedCategory(activity.getString(R.string.clip_filter_all), "all", true),
                    FeedCategory(activity.getString(R.string.clip_category_just_chatting), "just-chatting", false)
                )

                // Fetch top categories
                val response = RetrofitClient.channelService.getTopCategories()
                if (response.isSuccessful) {
                    val topCats = response.body() ?: emptyList()
                    // Filter duplicates
                    topCats.forEach { cat ->
                        if (cat.slug != "just-chatting" && cat.slug != "irl") {
                             initialList.add(FeedCategory(cat.name, cat.slug, false))
                        }
                    }
                }
                
                withContext(Dispatchers.Main) {
                    categoryAdapter?.submitList(initialList)
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    categoryAdapter?.submitList(listOf(
                        FeedCategory(activity.getString(R.string.clip_filter_all), "all", true),
                        FeedCategory(activity.getString(R.string.clip_category_just_chatting), "just-chatting"),
                        FeedCategory("IRL", "irl")
                    ))
                }
            }
        }
    }
    
    private fun loadClipsForCategory(slug: String) {
        isLoading = true
        nextCursor = null
        hasMore = true
        feedBinding?.clipFeedLoadingOverlay?.visibility = View.VISIBLE
        
        activity.lifecycleScope.launch(Dispatchers.IO) {
            try {
                val responseData: Pair<List<ClipPlayDetails>, String?> = when {
                    currentChannelSlug != null -> {
                        val resp = RetrofitClient.channelService.getChannelClips(
                            slug = currentChannelSlug!!,
                            cursor = null,
                            sort = currentClipsSort,
                            time = currentClipsTime
                        )
                        if (resp.isSuccessful) {
                            val clips = resp.body()?.clips?.map { mapChannelClipToPlayDetails(it, currentChannelSlug!!) } ?: emptyList()
                            clips to resp.body()?.nextCursor
                        } else emptyList<ClipPlayDetails>() to null
                    }
                    slug == "all" -> {
                        val resp = RetrofitClient.channelService.getBrowseClips(sort = currentClipsSort, time = currentClipsTime)
                        if (resp.isSuccessful) {
                            val clips = resp.body()?.clips?.map { mapBrowseClipToPlayDetails(it) } ?: emptyList()
                            clips to resp.body()?.nextCursor
                        } else emptyList<ClipPlayDetails>() to null
                    }
                    else -> {
                        val resp = RetrofitClient.channelService.getCategoryClips(slug = slug, sort = currentClipsSort, time = currentClipsTime)
                        if (resp.isSuccessful) {
                            val clips = resp.body()?.clips?.map { mapBrowseClipToPlayDetails(it) } ?: emptyList()
                            clips to resp.body()?.nextCursor
                        } else emptyList<ClipPlayDetails>() to null
                    }
                }
                
                val converted = responseData.first
                val newCursor = responseData.second
                withContext(Dispatchers.Main) {
                    // Crucial: Reset state before notifying change
                    resetPlayersForNewList()
                    
                    // Update the backing list
                    clipsList.clear()
                    clipsList.addAll(converted)
                    
                    // Use post to avoid "Inconsistency detected" if ViewPager is mid-layout
                    feedBinding?.clipFeedViewPager?.post {
                        feedAdapter?.notifyDataSetChanged()
                        
                        if (converted.isNotEmpty()) {
                            // Reset ViewPager to first item
                            feedBinding?.clipFeedViewPager?.setCurrentItem(0, false)
                            
                            // Delay playback start to ensure first VH is bound/attached
                            feedBinding?.clipFeedViewPager?.post {
                                playClipAtPosition(0)
                            }
                        } else {
                             Toast.makeText(activity, activity.getString(R.string.no_clips_found), Toast.LENGTH_SHORT).show()
                        }
                    }
                    
                    this@ClipFeedManager.nextCursor = newCursor
                    this@ClipFeedManager.hasMore = !newCursor.isNullOrEmpty()
                    this@ClipFeedManager.isLoading = false
                    
                    feedBinding?.clipFeedLoadingOverlay?.visibility = View.GONE
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                     Toast.makeText(activity, activity.getString(R.string.error_format, e.message), Toast.LENGTH_SHORT).show()
                     feedBinding?.clipFeedLoadingOverlay?.visibility = View.GONE
                }
            }
        }
    }

    private fun loadNextPage() {
        if (isLoading || !hasMore || nextCursor == null) return
        
        isLoading = true
        val slug = currentCategorySlug
        
        activity.lifecycleScope.launch(Dispatchers.IO) {
            try {
                val responseData: Pair<List<ClipPlayDetails>, String?> = when {
                    currentChannelSlug != null -> {
                        val resp = RetrofitClient.channelService.getChannelClips(
                            slug = currentChannelSlug!!,
                            cursor = nextCursor,
                            sort = currentClipsSort,
                            time = currentClipsTime
                        )
                        if (resp.isSuccessful) {
                            val clips = resp.body()?.clips?.map { mapChannelClipToPlayDetails(it, currentChannelSlug!!) } ?: emptyList()
                            clips to resp.body()?.nextCursor
                        } else emptyList<ClipPlayDetails>() to null
                    }
                    slug == "all" -> {
                        val resp = RetrofitClient.channelService.getBrowseClips(sort = currentClipsSort, time = currentClipsTime, cursor = nextCursor)
                        if (resp.isSuccessful) {
                            val clips = resp.body()?.clips?.map { mapBrowseClipToPlayDetails(it) } ?: emptyList()
                            clips to resp.body()?.nextCursor
                        } else emptyList<ClipPlayDetails>() to null
                    }
                    else -> {
                        val resp = RetrofitClient.channelService.getCategoryClips(slug = slug, sort = currentClipsSort, time = currentClipsTime, cursor = nextCursor)
                        if (resp.isSuccessful) {
                            val clips = resp.body()?.clips?.map { mapBrowseClipToPlayDetails(it) } ?: emptyList()
                            clips to resp.body()?.nextCursor
                        } else emptyList<ClipPlayDetails>() to null
                    }
                }
                
                val newClips = responseData.first
                val newCursor = responseData.second
                
                withContext(Dispatchers.Main) {
                    // Filter out duplicates
                    val existingIds = clipsList.map { it.id }.toSet()
                    val uniqueNewClips = newClips.filter { it.id !in existingIds }
                    
                    if (uniqueNewClips.isNotEmpty()) {
                        val startPos = clipsList.size
                        clipsList.addAll(uniqueNewClips)
                        
                        // Use post to avoid layout collision
                        feedBinding?.clipFeedViewPager?.post {
                            feedAdapter?.notifyItemRangeInserted(startPos, uniqueNewClips.size)
                        }
                        
                        nextCursor = newCursor
                        hasMore = !newCursor.isNullOrEmpty()
                    } else if (newCursor != null && newCursor != nextCursor) {
                        // If all clips were duplicates but we have a new cursor, try next page automatically
                        nextCursor = newCursor
                        isLoading = false
                        loadNextPage()
                        return@withContext
                    } else {
                        hasMore = false
                    }
                    isLoading = false
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    isLoading = false
                    hasMore = false
                }
            }
        }
    }
    
    private fun resetPlayersForNewList() {
        player1?.pause(); player2?.pause(); player3?.pause()
        currentPosition = -1
        player1Position = -1; player2Position = -1; player3Position = -1
        lastFrameCache.clear()
    }
    
    private fun mapDetailsToChannelClip(details: ClipPlayDetails): dev.xacnio.kciktv.shared.data.model.ChannelClip {
        return dev.xacnio.kciktv.shared.data.model.ChannelClip(
            id = details.id ?: "",
            title = details.title,
            thumbnailUrl = details.thumbnailUrl,
            duration = details.duration,
            views = details.views ?: details.viewCount ?: 0,
            createdAt = details.createdAt,
            url = details.clipUrl ?: details.videoUrl,
            creator = details.creator,
            channel = details.channel
        )
    }

    private fun mapChannelClipToPlayDetails(clip: dev.xacnio.kciktv.shared.data.model.ChannelClip, channelSlug: String): ClipPlayDetails {
        return ClipPlayDetails(
            id = clip.id,
            livestreamId = null,
            categoryId = null,
            channelId = null,
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
            channel = clip.channel ?: dev.xacnio.kciktv.shared.data.model.ClipChannel(
                id = 0L,
                username = channelSlug,
                slug = channelSlug,
                profilePicture = null
            )
        )
    }

    private fun mapBrowseClipToPlayDetails(browseClip: dev.xacnio.kciktv.shared.data.model.BrowseClip): ClipPlayDetails {
        return ClipPlayDetails(
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
                    profilePicture = null
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

    private fun setupGlobalListeners() {
        // Listen for layout changes (resize, split screen) to update grid/feed constraints dynamically
        feedBinding?.root?.addOnLayoutChangeListener { _, left, top, right, bottom, oldLeft, oldTop, oldRight, oldBottom ->
            val w = right - left
            val h = bottom - top
            val widthChanged = w != (oldRight - oldLeft)
            val heightChanged = h != (oldBottom - oldTop)
            
            if (widthChanged || heightChanged) {
                updateLayout(width = w, height = h)
            }
        }
        
        // Apply navigation bar insets for bottom padding
        feedBinding?.root?.setOnApplyWindowInsetsListener { _, insets ->
            val statusBarHeight: Int
            val navBarHeight: Int
            
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
                statusBarHeight = insets.getInsets(android.view.WindowInsets.Type.statusBars()).top
                navBarHeight = insets.getInsets(android.view.WindowInsets.Type.navigationBars()).bottom
            } else {
                @Suppress("DEPRECATION")
                statusBarHeight = insets.systemWindowInsetTop
                @Suppress("DEPRECATION")
                navBarHeight = insets.systemWindowInsetBottom
            }
            
            // Store insets for ViewHolder padding
            currentStatusBarHeight = statusBarHeight
            currentNavBarHeight = navBarHeight
            
            // Apply to all visible ViewHolders
            applyInsetsPaddingToVisibleItems()
            
            insets
        }
        feedBinding?.root?.requestApplyInsets()
    }
    
    private var currentStatusBarHeight = 0
    private var currentNavBarHeight = 0
    
    private fun applyInsetsPaddingToVisibleItems() {
        val recyclerView = feedBinding?.clipFeedViewPager?.getChildAt(0) as? androidx.recyclerview.widget.RecyclerView ?: return
        for (i in 0 until recyclerView.childCount) {
            val holder = recyclerView.getChildViewHolder(recyclerView.getChildAt(i)) as? ClipFeedAdapter.ClipViewHolder
            holder?.applyInsetsPadding(currentStatusBarHeight, currentNavBarHeight)
        }
    }

    fun handleBackPress(): Boolean {
        if (isFeedVisible) {
            closeFeed()
            return true
        }
        return false
    }
    
    /**
     * Updates the layout constraints for all visible ViewHolders when screen size changes.
     * Should be called from onConfigurationChanged in the activity.
     */
    fun updateLayout(config: android.content.res.Configuration? = null, width: Int = -1, height: Int = -1) {
        if (!isFeedActive || feedBinding == null) return
        
        // Calculate dimensions if not provided
        var w = width
        var h = height
        if (w <= 0 || h <= 0) {
            val displayMetrics = activity.resources.displayMetrics
            w = feedBinding?.root?.width ?: displayMetrics.widthPixels
            h = feedBinding?.root?.height ?: displayMetrics.heightPixels
            if (w <= 0 || h <= 0) {
                w = displayMetrics.widthPixels
                h = displayMetrics.heightPixels
            }
        }
        
        // Apply constraints to the manager's UI wrapper (categories, viewpager)
        applyManagerWidthConstraint(w, h)
        
        // Get the RecyclerView from ViewPager2
        val recyclerView = feedBinding?.clipFeedViewPager?.getChildAt(0) as? androidx.recyclerview.widget.RecyclerView ?: return
        
        // Update all visible ViewHolders
        for (i in 0 until recyclerView.childCount) {
            val viewHolder = recyclerView.getChildViewHolder(recyclerView.getChildAt(i))
            if (viewHolder is ClipFeedAdapter.ClipViewHolder) {
                viewHolder.applyFeedWidthConstraint(w, h)
            }
        }
    }

    private fun applyManagerWidthConstraint(width: Int, height: Int) {
        val root = feedBinding?.root ?: return
        val context = root.context
        val displayMetrics = context.resources.displayMetrics
        val isPortrait = height > width
        
        // Check if tablet (sw600dp+)
        val sw600dp = (600 * displayMetrics.density).toInt()
        val minDimension = minOf(width, height)
        val isTablet = minDimension >= sw600dp
        
        // Determine target width logic
        // Landscape or Tablet Portrait: Constrain width
        // Phone Portrait: Full Width
        val shouldConstrain = !isPortrait || isTablet
        
        val targetWidth = if (shouldConstrain) {
             val maxWidth = (height * 9f / 16f).toInt()
             minOf(maxWidth, width)
        } else {
             android.widget.FrameLayout.LayoutParams.MATCH_PARENT
        }
        
        // Update ViewPager
        val viewPager = feedBinding?.clipFeedViewPager
        val pagerParams = viewPager?.layoutParams as? android.widget.FrameLayout.LayoutParams
        if (pagerParams != null) {
            pagerParams.width = targetWidth
            pagerParams.gravity = android.view.Gravity.CENTER
            viewPager?.layoutParams = pagerParams
        }
        
        // Update Categories
        val categories = feedBinding?.rvCategories
        val catParams = categories?.layoutParams as? android.widget.FrameLayout.LayoutParams
        if (catParams != null) {
            catParams.width = targetWidth
            catParams.gravity = android.view.Gravity.TOP or android.view.Gravity.CENTER_HORIZONTAL
            categories?.layoutParams = catParams
        }
    }
}
