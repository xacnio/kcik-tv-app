/**
 * File: StreamFeedManager.kt
 *
 * Description: Manages the TikTok-style vertical live stream feed.
 * It coordinates the ViewPager2, 3-player system for preloading and playback,
 * background data fetching, and user interactions within the stream feed environment.
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
import dev.xacnio.kciktv.shared.data.model.ChannelItem
import dev.xacnio.kciktv.databinding.LayoutStreamFeedBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap
import dev.xacnio.kciktv.shared.data.prefs.AppPreferences

/**
 * Manager class for TikTok-style vertical stream feed.
 * Allows swiping up/down to navigate between live streams.
 * 
 * Uses a 3-player system:
 * - Current player: plays current stream
 * - Previous player: keeps previous stream ready for back navigation
 * - Next player: preloads next stream for instant forward navigation
 * 
 * This is an EXPERIMENTAL feature for testing purposes.
 */
class StreamFeedManager(private val activity: MobilePlayerActivity) {
    
    private val binding = activity.binding
    private val repository = activity.repository
    private val prefs = activity.prefs
    
    private var feedBinding: LayoutStreamFeedBinding? = null
    private var feedAdapter: StreamFeedAdapter? = null
    private var streamsList = mutableListOf<ChannelItem>()
    
    private var nextCursor: String? = null
    private var isLoading = false
    private var hasMore = true
    
    // 3-player system
    private var player1: Player? = null
    private var player2: Player? = null
    private var player3: Player? = null
    
    // Track which position each player is assigned to (-1 = idle/unassigned)
    private var player1Position = -1
    private var player2Position = -1
    private var player3Position = -1
    
    // Current position and mute state
    private var currentPosition = -1
    private var isMuted = false

    // Filter properties
    private var currentSort = "featured"
    private var currentCategoryId: Long? = null
    private var currentCategoryName: String? = null
    private var activeOriginScreen: MobilePlayerActivity.AppScreen = MobilePlayerActivity.AppScreen.HOME
    
    // Scale mode: (managed by AppPreferences)
    
    // Cache for playback URLs
    private val playbackUrlCache = ConcurrentHashMap<String, String>()
    // Cache for follow status
    private val followStatusCache = ConcurrentHashMap<String, Boolean>()
    
    // Cache for last frames
    private val lastFrameCache = ConcurrentHashMap<Int, Bitmap>()
    
    // Cache for video dimensions (Slug -> Width/Height)
    private val videoSizeCache = ConcurrentHashMap<String, Pair<Int, Int>>()
    
    // Preload jobs for cancellation
    private var preloadNextJob: Job? = null
    private var preloadPrevJob: Job? = null
    
    // Feed is "active" (loaded but may be hidden temporarily)
    // Feed is "active" (loaded but may be hidden temporarily)
    var isFeedActive = false
        private set
    
    // Feed is truly visible on screen (used for back press handling)
    val isFeedVisible: Boolean
        get() = isFeedActive && feedBinding?.root?.visibility == View.VISIBLE

    val feedRootView: View? get() = feedBinding?.root
    
    // Track if playback was paused by lifecycle (app going to background)
    private var wasPausedByLifecycle = false
    
    // Page change callback
    private val pageChangeCallback = object : ViewPager2.OnPageChangeCallback() {
        override fun onPageSelected(position: Int) {
            super.onPageSelected(position)
            playStreamAtPosition(position)
            
            if (position >= streamsList.size - 3 && hasMore && !isLoading) {
                loadMoreStreams()
            }
        }
    }
    
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
        }
    }

    fun resumeFeed() {
        if (!isFeedActive) return
        feedBinding?.root?.visibility = View.VISIBLE
        feedBinding?.root?.bringToFront()
        
        if (currentPosition >= 0) {
            getPlayerForPosition(currentPosition)?.second?.play()
        }
        hideOtherViews()
    }

    /**
     * Opens the stream feed with the provided list of channels.
     */
    fun openFeed(channels: List<ChannelItem>, startIndex: Int = 0) {
        if (channels.isEmpty()) {
            Toast.makeText(activity, activity.getString(R.string.no_streams_available), Toast.LENGTH_SHORT).show()
            return
        }
        
        if (feedBinding == null) {
            feedBinding = LayoutStreamFeedBinding.inflate(
                LayoutInflater.from(activity),
                binding.root as ViewGroup,
                false
            )
            (binding.root as ViewGroup).addView(feedBinding!!.root)
            setupGlobalListeners()
        }
        
        streamsList.clear()
        streamsList.addAll(channels)
        clearCaches()
        setupAdapter()
        setupViewPager(startIndex)
        showFeed()
        hideOtherViews()
        initializePlayers()
        
        // Capture origin
        this.activeOriginScreen = activity.currentScreen
        
        activity.setCurrentScreen(MobilePlayerActivity.AppScreen.STREAM_FEED)
        
        updateHeaderTitle()
        
        if (streamsList.isNotEmpty()) {
            playStreamAtPosition(startIndex)
        }
    }
    
    /**
     * Opens the feed using the provided list of streams.
     */
    fun openFeedFromBrowse(
        initialStreams: List<ChannelItem>, 
        startIndex: Int = 0, 
        sort: String = "featured", 
        categoryId: Long? = null,
        initialCursor: String? = null,
        categoryName: String? = null
    ) {
        this.currentSort = sort
        this.currentCategoryId = categoryId
        this.currentCategoryName = categoryName
        
        // Force Category Details origin if we have category info (more reliable than currentScreen snapshot)
        if (categoryId != null || categoryName != null) {
            this.activeOriginScreen = MobilePlayerActivity.AppScreen.CATEGORY_DETAILS
        } else {
            this.activeOriginScreen = activity.currentScreen
        }
        
        // Signal activity that we are in category context
        if (categoryId != null) {
            activity.returnToCategoryDetails = true
        }

        activity.setCurrentScreen(MobilePlayerActivity.AppScreen.STREAM_FEED)
        
        this.nextCursor = initialCursor
        
        if (feedBinding == null) {
            feedBinding = LayoutStreamFeedBinding.inflate(
                LayoutInflater.from(activity),
                binding.root as ViewGroup,
                false
            )
            (binding.root as ViewGroup).addView(feedBinding!!.root)
            setupGlobalListeners()
        }
        
        showFeed()
        feedBinding?.feedLoadingOverlay?.visibility = View.GONE
        hideOtherViews()
        
        streamsList = initialStreams.toMutableList()
        clearCaches()
        setupAdapter()
        setupViewPager(startIndex)
        initializePlayers()
        
        updateHeaderTitle()
        
        if (streamsList.isNotEmpty()) {
            playStreamAtPosition(startIndex)
        }
    }
    
    private fun clearCaches() {
        playbackUrlCache.clear()
        videoSizeCache.clear()
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
                        }
                    }

                    Player.State.BUFFERING -> {
                        activity.runOnUiThread {
                            viewHolder?.showLoading()
                        }
                    }
                    Player.State.ENDED -> {
                        activity.runOnUiThread {
                            viewHolder?.hideLoading()
                            viewHolder?.showStreamEnded()
                            activity.window.clearFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
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
                // Background Player (Preloading)
                if (state == Player.State.PLAYING) {
                     // Pause IMMEDIATELY to save data and prevent background audio/network usage
                     getPlayer(playerIndex)?.pause()
                }
            }
        }

        override fun onError(exception: com.amazonaws.ivs.player.PlayerException) {
            val position = getPlayerPosition(playerIndex)
            if (position == currentPosition) {
                val viewHolder = getViewHolder(position)
                activity.runOnUiThread {
                    viewHolder?.hideLoading()
                    // If stream not found or ended, show ended
                    // Error code for stream not found/offline often varies, but usually implies end for live streams
                    viewHolder?.showStreamEnded()
                }
            }
        }

        override fun onCue(cue: com.amazonaws.ivs.player.Cue) {}
        override fun onDurationChanged(duration: Long) {}
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
        
        override fun onVideoSizeChanged(width: Int, height: Int) {
            // Store and update size
            val position = getPlayerPosition(playerIndex)
            if (position >= 0 && position < streamsList.size) {
                 val slug = streamsList[position].slug
                 videoSizeCache[slug] = width to height
                 
                 activity.runOnUiThread {
                     getViewHolder(position)?.updateVideoSize(width, height)
                 }
            }
        }
    }

    private fun initializePlayers() {
        if (player1 == null) {
            player1 = Player.Factory.create(activity).apply {
                setMuted(true)
                addListener(createPlayerListener(1))
                setLiveLowLatencyEnabled(true)
            }
        }
        if (player2 == null) {
            player2 = Player.Factory.create(activity).apply {
                setMuted(true)
                addListener(createPlayerListener(2))
                setLiveLowLatencyEnabled(true)
            }
        }
        if (player3 == null) {
            player3 = Player.Factory.create(activity).apply {
                setMuted(true)
                addListener(createPlayerListener(3))
                setLiveLowLatencyEnabled(true)
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
    
    /**
     * Find which player is assigned to a position
     */
    private fun getPlayerForPosition(position: Int): Pair<Int, Player>? {
        if (player1Position == position && player1 != null) return 1 to player1!!
        if (player2Position == position && player2 != null) return 2 to player2!!
        if (player3Position == position && player3 != null) return 3 to player3!!
        return null
    }
    
    /**
     * Find an idle player (not assigned to any position)
     */
    private fun getIdlePlayer(): Pair<Int, Player>? {
        if (player1Position == -1 && player1 != null) return 1 to player1!!
        if (player2Position == -1 && player2 != null) return 2 to player2!!
        if (player3Position == -1 && player3 != null) return 3 to player3!!
        return null
    }
    
    /**
     * Find the player that should be released for reuse.
     * Priority: furthest from current position
     */
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
        
        // Return the one furthest from current position
        return candidates.maxByOrNull { kotlin.math.abs(it.first - currentPos) }?.second
    }
    
    private fun getViewHolder(position: Int): StreamFeedAdapter.StreamViewHolder? {
        if (feedBinding == null || position < 0) return null
        return (feedBinding?.feedViewPager?.getChildAt(0) as? androidx.recyclerview.widget.RecyclerView)
            ?.findViewHolderForAdapterPosition(position) as? StreamFeedAdapter.StreamViewHolder
    }

    private fun getCurrentViewHolder(): StreamFeedAdapter.StreamViewHolder? {
        return getViewHolder(currentPosition)
    }
    
    /**
     * Main method to play a stream at given position.
     */
    private fun playStreamAtPosition(position: Int) {
        if (position < 0 || position >= streamsList.size) return
        
        val previousPosition = currentPosition
        val stream = streamsList[position]
        
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
            
            // Show this player
            val viewHolder = getViewHolder(position)
            viewHolder?.attachPlayer(player)
            player.setMuted(isMuted)
            
            if (player.state == Player.State.PLAYING) {
                viewHolder?.showPlayer()
            } else if (player.state != Player.State.BUFFERING) {
                // Need to play
                player.play()
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
        
        // Get a player to use
        var playerToUse = getIdlePlayer()
        
        if (playerToUse == null) {
            // Need to reuse a player - get the one furthest from current position
            // But keep previous and next neighbors if possible
            val keepPositions = setOf(position - 1, position, position + 1).filter { it >= 0 }.toSet()
            playerToUse = getPlayerToRelease(position, keepPositions)
            
            if (playerToUse != null) {
                // Stop and release the player from its current position
                playerToUse.second.pause()
                setPlayerPosition(playerToUse.first, -1)
            }
        }
        
        if (playerToUse == null) {
            // Fallback - just use player1
            if (player1 == null) initializePlayers()
            
            player1?.let { p1 ->
                p1.pause()
                player1Position = -1
                playerToUse = 1 to p1
            }
        }

        if (playerToUse == null) return
        
        val (playerIndex, player) = playerToUse!!
        setPlayerPosition(playerIndex, position)
        
        // Load the stream
        val cachedUrl = playbackUrlCache[stream.slug]
        if (cachedUrl != null) {
            loadAndPlay(playerIndex, player, position, cachedUrl, viewHolder)
        } else {
            fetchAndPlay(playerIndex, player, position, stream, viewHolder)
        }
        
        checkFollowStatus(stream, viewHolder)
        
        // Connect viewer websocket to count as viewer
        // Only if it's the current stream
        if (position == currentPosition) {
            activity.webViewManager.startViewerWebSocket(stream.id, stream.slug, stream.livestreamId?.toString())
        }
    }
    
    private fun loadAndPlay(
        @Suppress("UNUSED_PARAMETER") playerIndex: Int,
        player: Player,
        position: Int,
        url: String,
        viewHolder: StreamFeedAdapter.StreamViewHolder?
    ) {
        viewHolder?.showLoading()
        viewHolder?.attachPlayer(player)
        
        player.load(android.net.Uri.parse(url))
        player.setRebufferToLive(true)
        player.play()
        player.setMuted(isMuted)
        
        if (player.state == Player.State.PLAYING) {
            activity.runOnUiThread {
                viewHolder?.hideLoading()
                viewHolder?.showPlayer()
            }
        }
        
        // Preload neighbors
        preloadNeighbors(position)
    }
    
    private fun fetchAndPlay(
        playerIndex: Int,
        player: Player,
        position: Int,
        stream: ChannelItem,
        viewHolder: StreamFeedAdapter.StreamViewHolder?
    ) {
        viewHolder?.showLoading()
        
        activity.lifecycleScope.launch {
            try {
                val detailsResult = withContext(Dispatchers.IO) {
                    repository.getChannelDetails(stream.slug)
                }
                
                val playbackUrl = detailsResult.getOrNull()?.playbackUrl
                
                if (playbackUrl != null) {
                    playbackUrlCache[stream.slug] = playbackUrl
                    
                    if (currentPosition == position) {
                        loadAndPlay(playerIndex, player, position, playbackUrl, getViewHolder(position))
                    }
                } else {
                    viewHolder?.hideLoading()
                    viewHolder?.showThumbnail()
                }
            } catch (e: Exception) {
                viewHolder?.hideLoading()
                viewHolder?.showThumbnail()
            }
        }
    }
    
    /**
     * Preload next and previous streams
     */
    private fun preloadNeighbors(currentPos: Int) {
        // Preload next
        if (currentPos + 1 < streamsList.size) {
            preloadPosition(currentPos + 1)
        }
        
        // Preload previous (if exists and not already loaded)
        if (currentPos - 1 >= 0) {
            preloadPosition(currentPos - 1)
        }
    }
    
    private fun preloadPosition(position: Int) {
        if (position < 0 || position >= streamsList.size) return
        
        // Already has a player?
        if (getPlayerForPosition(position) != null) return
        
        val stream = streamsList[position]
        
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
        
        // Get or fetch URL
        val cachedUrl = playbackUrlCache[stream.slug]
        if (cachedUrl != null) {
            preloadStream(playerIndex, player, position, cachedUrl)
        } else {
            // Fetch URL in background
            activity.lifecycleScope.launch {
                try {
                    val details = withContext(Dispatchers.IO) {
                        repository.getChannelDetails(stream.slug)
                    }
                    val url = details.getOrNull()?.playbackUrl
                    if (url != null) {
                        playbackUrlCache[stream.slug] = url
                        // Only preload if player still assigned to this position
                        if (getPlayerPosition(playerIndex) == position) {
                            activity.runOnUiThread {
                                preloadStream(playerIndex, player, position, url)
                            }
                        }
                    }
                } catch (e: Exception) {}
            }
        }
    }
    
    private fun preloadStream(@Suppress("UNUSED_PARAMETER") playerIndex: Int, player: Player, position: Int, url: String) {
        val viewHolder = getViewHolder(position)
        if (viewHolder != null) {
            viewHolder.prepareForPreload(player)
        }
        
        player.load(android.net.Uri.parse(url))
        player.setRebufferToLive(true)
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
    
    private fun stopCurrentStream() {
        if (currentPosition >= 0) {
            captureLastFrame(currentPosition)
        }
        
        getPlayerForPosition(currentPosition)?.second?.pause()
        getCurrentViewHolder()?.showThumbnail()
    }
    
    private fun setupAdapter() {
        feedAdapter = StreamFeedAdapter(
            streams = streamsList,
            onStreamClick = { stream ->
                // Pause current player
                getPlayerForPosition(currentPosition)?.second?.pause()
                
                // Directly call playChannel with explicit origin screen to ensure correct return behavior
                activity.playChannel(stream, MobilePlayerActivity.AppScreen.STREAM_FEED)
            },
            onFollowClick = { stream ->
                toggleFollow(stream)
            },
            onShareClick = { stream ->
                shareStream(stream)
            },
            onChannelClick = { stream ->
                pauseAndHideFeed()
                activity.channelProfileManager.openChannelProfileFromFeed(stream.slug)
            },
            onMuteToggle = {
                toggleMute()
            },
            onViewAttached = { position, holder ->
                // Guard against invalid position (can happen during recycler view updates)
                if (position < 0 || position >= streamsList.size) return@StreamFeedAdapter
                
                // Apply insets padding (status bar + nav bar)
                holder.applyInsetsPadding(currentStatusBarHeight, currentNavBarHeight)
                
                // Apply persistent scale mode
                val cachedSize = videoSizeCache[streamsList[position].slug]
                if (cachedSize != null) {
                    holder.updateVideoSize(cachedSize.first, cachedSize.second)
                }
                holder.setScaleMode(activity.prefs.isFeedContentFullscreen)
                
                // Check follow status
                checkFollowStatus(streamsList[position], holder)
                
                // Show cached frame if available
                lastFrameCache[position]?.let { bitmap ->
                    if (!bitmap.isRecycled) holder.showLastFrame(bitmap)
                }
                
                // Attach player if this is the current position
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
        
        feedBinding?.feedViewPager?.adapter = feedAdapter
    }
    private fun toggleMute() {
        isMuted = !isMuted
        getPlayerForPosition(currentPosition)?.second?.setMuted(isMuted)
        feedAdapter?.setMuted(isMuted)
    }
    
    private fun checkFollowStatus(stream: ChannelItem, holder: StreamFeedAdapter.StreamViewHolder? = null) {
        val slug = stream.slug
        
        // Apply cached status immediately if available
        if (followStatusCache.containsKey(slug)) {
            val isFollowing = followStatusCache[slug] == true
            activity.runOnUiThread {
                holder?.setFollowingState(isFollowing)
                // If no specific holder provided, try to find current one if matching
                if (holder == null && currentPosition >= 0 && currentPosition < streamsList.size && streamsList[currentPosition].slug == slug) {
                    getViewHolder(currentPosition)?.setFollowingState(isFollowing)
                }
            }
        }
        
        activity.lifecycleScope.launch {
            val token = activity.prefs.authToken
            if (token != null) {
                val result = repository.getChannelUserMe(slug, token)
                val isFollowing = result.getOrNull()?.isFollowing == true
                followStatusCache[slug] = isFollowing
                
                activity.runOnUiThread {
                    holder?.setFollowingState(isFollowing)
                    if (holder == null && currentPosition >= 0 && currentPosition < streamsList.size && streamsList[currentPosition].slug == slug) {
                        getViewHolder(currentPosition)?.setFollowingState(isFollowing)
                    }
                }
            }
        }
    }

    private fun toggleFollow(stream: ChannelItem) {
        val token = activity.prefs.authToken
        if (token == null) {
            Toast.makeText(activity, activity.getString(R.string.login_required), Toast.LENGTH_SHORT).show()
            return
        }
            
        val currentStatus = followStatusCache[stream.slug] == true
        val newStatus = !currentStatus
        
        fun executeAction() {
            activity.runOnUiThread {
                if (currentPosition >= 0 && currentPosition < streamsList.size && streamsList[currentPosition].slug == stream.slug) {
                    getViewHolder(currentPosition)?.setFollowLoading(true)
                }
            }
            
            activity.webViewManager.performFollowViaWebView(
                stream.slug,
                token,
                newStatus,
                onSuccess = { resultStatus ->
                    followStatusCache[stream.slug] = resultStatus
                    activity.runOnUiThread {
                         if (currentPosition >= 0 && currentPosition < streamsList.size && streamsList[currentPosition].slug == stream.slug) {
                             val holder = getViewHolder(currentPosition)
                             holder?.setFollowLoading(false)
                             holder?.setFollowingState(resultStatus)
                         }
                    }
                },
                onError = { error ->
                    activity.runOnUiThread {
                        Toast.makeText(activity, activity.getString(R.string.error_format, error), Toast.LENGTH_SHORT).show()
                         if (currentPosition >= 0 && currentPosition < streamsList.size && streamsList[currentPosition].slug == stream.slug) {
                             val holder = getViewHolder(currentPosition)
                             holder?.setFollowLoading(false)
                             holder?.setFollowingState(currentStatus)
                         }
                    }
                }
            )
        }

        if (currentStatus) {
            // Confirm Unfollow
            androidx.appcompat.app.AlertDialog.Builder(activity)
                .setTitle(activity.getString(R.string.unfollow))
                .setMessage(activity.getString(R.string.unfollow_confirm_message, stream.slug))
                .setPositiveButton(activity.getString(R.string.yes)) { _, _ -> executeAction() }
                .setNegativeButton(activity.getString(R.string.cancel), null)
                .show()
        } else {
            executeAction()
        }
    }

    private fun toggleScale() {
        val newMode = !activity.prefs.isFeedContentFullscreen
        activity.prefs.isFeedContentFullscreen = newMode
        
        // Update global icon
        val iconRes = if (newMode) R.drawable.ic_fullscreen_exit else R.drawable.ic_fullscreen
        feedBinding?.ivScaleType?.setImageResource(iconRes)
        
        // Update all visible ViewHolders to ensure smooth transition
        val recyclerView = feedBinding?.feedViewPager?.getChildAt(0) as? androidx.recyclerview.widget.RecyclerView
        recyclerView?.let { rv ->
             for (i in 0 until rv.childCount) {
                 val child = rv.getChildAt(i)
                 val holder = rv.getChildViewHolder(child) as? StreamFeedAdapter.StreamViewHolder
                 if (holder != null) {
                     holder.setScaleMode(newMode)
                 }
             }
        }
    }
    
    private fun setupViewPager(startIndex: Int) {
        feedBinding?.feedViewPager?.apply {
            offscreenPageLimit = 1
            setCurrentItem(startIndex, false)
            
            unregisterOnPageChangeCallback(pageChangeCallback)
            registerOnPageChangeCallback(pageChangeCallback)
        }
    }
    
    private fun loadMoreStreams() {
        if (isLoading || !hasMore || nextCursor == null) return
        
        isLoading = true
        
        activity.lifecycleScope.launch {
            try {
                val langs = prefs.streamLanguages.toList()
                val result = withContext(Dispatchers.IO) {
                    repository.getFilteredLiveStreams(
                        sort = currentSort,
                        categoryId = currentCategoryId,
                        languages = if (langs.isNotEmpty()) langs else null,
                        after = nextCursor
                    )
                }
                
                val response = result.getOrNull()
                val newChannels = response?.channels ?: emptyList()
                
                val existingIds = streamsList.map { it.id }.toSet()
                val uniqueChannels = newChannels.filter { it.id !in existingIds }
                
                if (uniqueChannels.isNotEmpty()) {
                    feedAdapter?.addStreams(uniqueChannels)
                }
                
                nextCursor = response?.nextCursor
                hasMore = !nextCursor.isNullOrEmpty() && uniqueChannels.isNotEmpty()
                isLoading = false
            } catch (e: Exception) {
                isLoading = false
            }
        }
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
    
    fun restoreVisibility() {
        showFeed()
    }

    private fun showFeed() {
        feedBinding?.root?.visibility = View.VISIBLE
        feedBinding?.root?.bringToFront()
        isFeedActive = true
        applyFeedWidthConstraint()
    }
    
    /**
     * Constrains the feedContentWrapper width on tablets in portrait mode
     * to maintain a comfortable viewing aspect ratio.
     */
    private fun applyFeedWidthConstraint() {
        val context = activity
        val resources = context.resources
        val displayMetrics = resources.displayMetrics
        
        val screenWidth = displayMetrics.widthPixels
        val screenHeight = displayMetrics.heightPixels
        
        // Check if tablet (sw600dp+)
        val sw600dp = (600 * displayMetrics.density).toInt()
        val minDimension = minOf(screenWidth, screenHeight)
        val isTablet = minDimension >= sw600dp
        
        val wrapper = feedBinding?.feedContentWrapper ?: return
        val params = wrapper.layoutParams as? android.widget.FrameLayout.LayoutParams ?: return
        
        if (isTablet) {
            // Tablet: constrain width and show background
            val maxWidth = (screenHeight * 9f / 16f).toInt()
            params.width = minOf(maxWidth, screenWidth)
            params.height = android.widget.FrameLayout.LayoutParams.MATCH_PARENT
            params.gravity = android.view.Gravity.CENTER
            wrapper.setBackgroundResource(R.drawable.bg_feed_content_wrapper)
        } else {
            // Phone: use full width, no background
            params.width = android.widget.FrameLayout.LayoutParams.MATCH_PARENT
            params.height = android.widget.FrameLayout.LayoutParams.MATCH_PARENT
            params.gravity = android.view.Gravity.CENTER
            wrapper.background = null
        }
        
        wrapper.layoutParams = params
    }
    
    private fun hideOtherViews() {
        binding.homeScreenContainer.root.visibility = View.GONE
        binding.browseScreenContainer.root.visibility = View.GONE
        binding.followingScreenContainer.root.visibility = View.GONE
        binding.searchContainer.visibility = View.GONE
        binding.channelProfileContainer.root.visibility = View.GONE
        binding.categoryDetailsContainer.root.visibility = View.GONE
        binding.mobileHeader.visibility = View.GONE
        
        if (!activity.miniPlayerManager.isMiniPlayerMode) {
            binding.playerScreenContainer.visibility = View.GONE
        }
        binding.bottomNavContainer.visibility = View.GONE
        binding.bottomNavGradient.visibility = View.GONE
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
        
        // Restore previous screen state
        // Use robust origin screen tracking + fallback to legacy checks
        if (activeOriginScreen == MobilePlayerActivity.AppScreen.CATEGORY_DETAILS || 
            currentCategoryId != null || 
            activity.browseManager.isCategoryDetailsVisible) {
            activity.returnToCategoryDetails = true
            binding.categoryDetailsContainer.root.visibility = View.VISIBLE
            binding.mobileHeader.visibility = View.GONE
            activity.setCurrentScreen(MobilePlayerActivity.AppScreen.CATEGORY_DETAILS)
        } else {
            binding.browseScreenContainer.root.visibility = View.VISIBLE
            binding.mobileHeader.visibility = View.VISIBLE
            activity.setCurrentScreen(MobilePlayerActivity.AppScreen.BROWSE)
        }
        
        streamsList = mutableListOf()
        nextCursor = null
        hasMore = true
        isLoading = false
        
        // Reset category context
        currentCategoryId = null
        currentCategoryName = null
    }
    
    private fun shareStream(stream: ChannelItem) {
        val shareUrl = "https://kick.com/${stream.slug}"
        val channelName = stream.username.ifEmpty { stream.slug }
        val shareIntent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_SUBJECT, stream.title ?: channelName)
            putExtra(Intent.EXTRA_TEXT, "$channelName ${activity.getString(R.string.is_live_on_kick)}\n$shareUrl")
        }
        activity.startActivity(Intent.createChooser(shareIntent, activity.getString(R.string.share_via)))
    }
    
    private fun showFilterOptions() {
        val dialogView = LayoutInflater.from(activity).inflate(R.layout.bottom_sheet_browse_filter, null)
        val dialog = com.google.android.material.bottomsheet.BottomSheetDialog(activity)
        dialog.setContentView(dialogView)
        
        // Hide View Mode (Not applicable for Feed)
        dialogView.findViewById<View>(R.id.viewModeGroup)?.visibility = View.GONE
        
        // Sort setup
        val sortGroup = dialogView.findViewById<android.widget.RadioGroup>(R.id.sortGroup)
        val sortRecommended = dialogView.findViewById<android.widget.RadioButton>(R.id.sortRecommended)
        val sortViewersDesc = dialogView.findViewById<android.widget.RadioButton>(R.id.sortViewersDesc)
        val sortViewersAsc = dialogView.findViewById<android.widget.RadioButton>(R.id.sortViewersAsc)
        
        when (currentSort) {
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
            if (newSort != currentSort) {
                currentSort = newSort
                reloadFeed()
                dialog.dismiss()
            }
        }
        
        // Language setup
        val chipGroup = dialogView.findViewById<com.google.android.material.chip.ChipGroup>(R.id.languageChipGroup)
        val languageCodes = activity.resources.getStringArray(R.array.stream_language_codes)
        val languageNames = activity.resources.getStringArray(R.array.stream_language_names)
        
        val selectedLanguages = prefs.streamLanguages.toMutableSet()
        
        chipGroup?.visibility = View.VISIBLE
        
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
                    reloadFeed()
                }
            }
            chipGroup.addView(chip)
        }
        
        dialog.show()
        
        // Force expand on wide screens
        dialog.behavior.state = com.google.android.material.bottomsheet.BottomSheetBehavior.STATE_EXPANDED
        dialog.behavior.skipCollapsed = true
    }
    
    private fun reloadFeed() {
        if (isLoading) return
        isLoading = true
        
        feedBinding?.feedLoadingOverlay?.visibility = View.VISIBLE
        
        // Load new initial batch
        activity.lifecycleScope.launch {
            try {
                val langs = prefs.streamLanguages.toList()
                val result = withContext(Dispatchers.IO) {
                    repository.getFilteredLiveStreams(
                        sort = currentSort,
                        categoryId = currentCategoryId,
                        languages = if (langs.isNotEmpty()) langs else null,
                        after = null
                    )
                }
                
                val response = result.getOrNull()
                val channels = response?.channels ?: emptyList()
                val newCursor = response?.nextCursor
                
                withContext(Dispatchers.Main) {
                    stopCurrentStream()
                    clearCaches()
                    
                    streamsList.clear()
                    streamsList.addAll(channels)
                    feedAdapter?.notifyDataSetChanged()
                    
                    nextCursor = newCursor
                    hasMore = !nextCursor.isNullOrEmpty()
                    
                    // Reset player assignments for new list
                    player1Position = -1
                    player2Position = -1
                    player3Position = -1
                    currentPosition = -1
                    
                    setupViewPager(0)
                    initializePlayers() // re-check
                    updateHeaderTitle()
                    
                    isLoading = false
                    feedBinding?.feedLoadingOverlay?.visibility = View.GONE
                    
                    if (streamsList.isNotEmpty()) {
                        feedBinding?.feedViewPager?.post {
                            playStreamAtPosition(0)
                        }
                    } else {
                        Toast.makeText(activity, activity.getString(R.string.no_streams_available), Toast.LENGTH_SHORT).show()
                    }
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                     isLoading = false
                     feedBinding?.feedLoadingOverlay?.visibility = View.GONE
                     Toast.makeText(activity, activity.getString(R.string.streams_load_failed), Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private fun updateHeaderTitle() {
        val sortTitle = activity.getString(when (currentSort) {
            "featured" -> R.string.sort_title_featured
            "viewer_count_desc" -> R.string.sort_title_viewers_desc
            "viewer_count_asc" -> R.string.sort_title_viewers_asc
            else -> R.string.sort_title_featured
        })

        val fullTitle = if (!currentCategoryName.isNullOrEmpty()) {
            "$currentCategoryName • $sortTitle"
        } else {
            sortTitle
        }

        feedBinding?.headerFilterText?.text = fullTitle
    }

    private fun setupGlobalListeners() {
        feedBinding?.ivScaleType?.setOnClickListener { toggleScale() }
        feedBinding?.headerFilterContainer?.setOnClickListener { showFilterOptions() }

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
            
            // Apply to header elements (they are in container, not ViewHolders)
            feedBinding?.ivScaleType?.let { view ->
                val params = view.layoutParams as? android.widget.FrameLayout.LayoutParams
                params?.topMargin = 12 + statusBarHeight // Original 12dp + status bar
                view.layoutParams = params
            }
            
            feedBinding?.headerFilterContainer?.let { view ->
                val params = view.layoutParams as? android.widget.FrameLayout.LayoutParams
                params?.topMargin = 12 + statusBarHeight // Original 12dp + status bar
                view.layoutParams = params
            }
            
            // Apply to all visible ViewHolders
            applyInsetsPaddingToVisibleItems()
            
            insets
        }
        feedBinding?.root?.requestApplyInsets()
    }
    
    private var currentStatusBarHeight = 0
    private var currentNavBarHeight = 0
    
    private fun applyInsetsPaddingToVisibleItems() {
        val recyclerView = feedBinding?.feedViewPager?.getChildAt(0) as? androidx.recyclerview.widget.RecyclerView ?: return
        for (i in 0 until recyclerView.childCount) {
            val holder = recyclerView.getChildViewHolder(recyclerView.getChildAt(i)) as? StreamFeedAdapter.StreamViewHolder
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
        
        // Apply constraints to the manager's UI wrapper (headers, buttons, etc.)
        applyManagerWidthConstraint(w, h)
        
        // Get the RecyclerView from ViewPager2
        val recyclerView = feedBinding?.feedViewPager?.getChildAt(0) as? androidx.recyclerview.widget.RecyclerView ?: return
        
        // Update all visible ViewHolders
        for (i in 0 until recyclerView.childCount) {
            val viewHolder = recyclerView.getChildViewHolder(recyclerView.getChildAt(i))
            if (viewHolder is StreamFeedAdapter.StreamViewHolder) {
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
        val isTablet = minDimension >= sw600dp // Using min dimension and density
        
        // Also check configuration if available via resources
        // val isTabletResource = context.resources.configuration.smallestScreenWidthDp >= 600
        
        val wrapper = feedBinding?.feedContentWrapper ?: return
        val params = wrapper.layoutParams as? android.widget.FrameLayout.LayoutParams ?: return
        
        if (!isPortrait) {
            // Landscape (Any Device): Constrain width to 9:16 based on height
            val maxWidth = (height * 9f / 16f).toInt()
            params.width = minOf(maxWidth, width)
            params.height = android.widget.FrameLayout.LayoutParams.MATCH_PARENT
            params.gravity = android.view.Gravity.CENTER
        } else if (isTablet) {
            // Portrait Tablet: Constrain width
            val maxWidth = (height * 9f / 16f).toInt()
            params.width = minOf(maxWidth, width)
            params.height = android.widget.FrameLayout.LayoutParams.MATCH_PARENT
            params.gravity = android.view.Gravity.CENTER
        } else {
            // Portrait Phone: Full Width
            params.width = android.widget.FrameLayout.LayoutParams.MATCH_PARENT
            params.height = android.widget.FrameLayout.LayoutParams.MATCH_PARENT
            params.gravity = android.view.Gravity.CENTER
        }
        
        wrapper.layoutParams = params
    }
}
