/**
 * File: VodManager.kt
 *
 * Description: Manages VOD (Video on Demand) and Clip playback logic.
 * It handles the specific UI layout for VODs, manages chat replay simulation by fetching historical messages,
 * and tracks/saves the user's watch progress.
 *
 * Author: Xacnio
 *
 */
package dev.xacnio.kciktv.mobile.ui.player

import android.annotation.SuppressLint
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.View
import android.widget.Toast
import androidx.lifecycle.lifecycleScope
import com.amazonaws.ivs.player.Player
import dev.xacnio.kciktv.mobile.MobilePlayerActivity
import dev.xacnio.kciktv.shared.data.model.ChannelItem
import dev.xacnio.kciktv.shared.data.model.ChatMessage
import dev.xacnio.kciktv.shared.data.model.ChannelClip
import dev.xacnio.kciktv.shared.data.repository.ChannelRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import dev.xacnio.kciktv.R
import java.text.SimpleDateFormat
import java.util.Collections
import java.util.Date
import java.util.Locale
import java.util.TimeZone

import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import com.bumptech.glide.Glide
import com.bumptech.glide.request.target.CustomTarget
import com.bumptech.glide.request.transition.Transition
import android.graphics.Bitmap
import android.graphics.drawable.Drawable
import dev.xacnio.kciktv.shared.data.model.ChannelDetailResponse
import dev.xacnio.kciktv.shared.data.model.ChannelVideo
import dev.xacnio.kciktv.shared.data.prefs.AppPreferences
import dev.xacnio.kciktv.shared.util.DateParseUtils

class VodManager(
    private val activity: MobilePlayerActivity,
    private val repository: ChannelRepository
) : DefaultLifecycleObserver {

    init {
        activity.lifecycle.addObserver(this)
    }

    override fun onDestroy(owner: LifecycleOwner) {
        stopVodChatReplay()
        super.onDestroy(owner)
    }
    private val TAG = "VodManager"
    private val binding = activity.binding
    private val prefs = activity.prefs
    private val lifecycleScope = activity.lifecycleScope

    enum class PlaybackMode { LIVE, VOD, CLIP }

    var currentPlaybackMode = PlaybackMode.LIVE

    // VOD/Clip Chat Replay
    private var vodChatStartTime: String? = null
    private var vodChannelId: Long? = null
    private var lastVodChatFetchPosition: Long = 0
    private var vodChatStartTimeMillis: Long = 0L
    private val vodChatFetchedIds = mutableSetOf<String>()
    private val vodChatBuckets =
        java.util.concurrent.ConcurrentHashMap<Long, MutableList<ChatMessage>>()
    private var lastProcessedSecond: Long = -1L
    
    // Accessed by MobilePlayerActivity for Clip creation
    var currentVideo: dev.xacnio.kciktv.shared.data.model.ChannelVideo? = null
    var currentClip: dev.xacnio.kciktv.shared.data.model.ChannelClip? = null

    data class PlaybackState(
        val mode: PlaybackMode,
        val channel: ChannelItem,
        val video: dev.xacnio.kciktv.shared.data.model.ChannelVideo? = null,
        val clip: dev.xacnio.kciktv.shared.data.model.ChannelClip? = null,
        val position: Long = 0L
    )

    private var previousPlaybackState: PlaybackState? = null

    fun saveCurrentState() {
        val channel = activity.currentChannel ?: return
        previousPlaybackState = PlaybackState(
            mode = currentPlaybackMode,
            channel = channel,
            video = currentVideo,
            clip = currentClip,
            position = activity.ivsPlayer?.position ?: 0L
        )
        Log.d(TAG, "Saved playback state: mode=$currentPlaybackMode, channel=${channel.slug}")
    }

    fun canRestorePreviousState(): Boolean = previousPlaybackState != null

    fun restorePreviousState(): Boolean {
        val state = previousPlaybackState ?: return false
        previousPlaybackState = null 
        
        Log.d(TAG, "Restoring playback state: mode=${state.mode}, channel=${state.channel.slug}")
        
        when (state.mode) {
            PlaybackMode.LIVE -> activity.playChannel(state.channel)
            PlaybackMode.VOD -> state.video?.let { playVideo(it, state.channel, state.position) }
            PlaybackMode.CLIP -> state.clip?.let { playClip(it, state.channel) }
        }
        return true
    }

    // Buffer tracking - how far ahead we've fetched (in absolute milliseconds)
    private var vodChatBufferedUntilMs: Long = 0L
    private val BUFFER_AHEAD_MS = 60_000L  // Buffer 1 minute ahead
    private var isVodFetching = false
    private var vodFetchJob: kotlinx.coroutines.Job? = null

    // Seek detection - track last known position to detect jumps
    private var lastKnownPositionMs: Long = 0L

    private val vodChatHandler = Handler(Looper.getMainLooper())
    private val vodChatRunnable = object : Runnable {
        override fun run() {
            checkAndFetchVodChat()
            vodChatHandler.postDelayed(this, 2000)  // Check every 2 seconds
        }
    }

    private val vodProgressHandler = Handler(Looper.getMainLooper())
    private val vodProgressRunnable = object : Runnable {
        override fun run() {
            // Skip UI updates in mini player mode
            if (!activity.miniPlayerManager.isMiniPlayerMode) {
                updateVodProgress()
            }
            vodProgressHandler.postDelayed(this, 500)
        }
    }

    private val vodSimulationHandler = Handler(Looper.getMainLooper())
    private val vodSimulationRunnable = object : Runnable {
        override fun run() {
            // Skip chat simulation in mini player mode (chat is not visible)
            if (!activity.miniPlayerManager.isMiniPlayerMode) {
                simulateVodChat()
            }
            vodSimulationHandler.postDelayed(this, 200)
        }
    }

    fun startVodChatReplay(channelId: Long, startTime: String) {
        // Legacy: parse string
        var clean = startTime
        if (!clean.contains("T")) clean = clean.replace(" ", "T") + ".000Z"
        startVodChatReplay(channelId, dev.xacnio.kciktv.shared.util.DateParseUtils.parseIsoDate(clean))
    }

    fun startVodChatReplay(channelId: Long, startTimeMillis: Long, initialOffsetMs: Long = 0L) {
        // IMPORTANT: Stop live chat WebSocket first
        activity.stopChatWebSocket()

        vodChannelId = channelId
        vodChatStartTimeMillis = startTimeMillis
        vodChatStartTime = dev.xacnio.kciktv.shared.util.DateParseUtils.toIsoString(startTimeMillis)

        lastVodChatFetchPosition = 0
        vodChatBufferedUntilMs = vodChatStartTimeMillis + initialOffsetMs // Apply offset for seek
        vodChatFetchedIds.clear()
        vodChatShownIds.clear()
        vodChatBuckets.clear()
        lastProcessedSecond = -1L
        isVodFetching = false
        lastKnownPositionMs = 0L

        Log.d(
            TAG,
            "VOD Chat: Starting replay - channelId=$channelId, startTimeMillis=$vodChatStartTimeMillis"
        )

        // Clear chat and start fetching
        activity.chatUiManager.chatAdapter.clearMessages()
        vodChatHandler.removeCallbacks(vodChatRunnable)
        vodSimulationHandler.removeCallbacks(vodSimulationRunnable)

        // Start buffer check loop
        vodChatHandler.postDelayed(vodChatRunnable, 500)

        // Start simulation loop (frequent updates)
        vodSimulationHandler.postDelayed(vodSimulationRunnable, 200)

        activity.binding.chatContainer.visibility = View.VISIBLE
    }

    fun stopVodChatReplay() {
        vodChatHandler.removeCallbacks(vodChatRunnable)
        vodProgressHandler.removeCallbacks(vodProgressRunnable)
        vodSimulationHandler.removeCallbacks(vodSimulationRunnable)
        vodChatStartTime = null
        vodChannelId = null
        lastVodChatFetchPosition = 0
        vodChatBufferedUntilMs = 0L
        vodChatFetchedIds.clear()
        vodChatShownIds.clear()
        vodChatBuckets.clear()
        lastProcessedSecond = -1L
        isVodFetching = false
        lastKnownPositionMs = 0L
        saveWatchProgress()
        currentVideo = null
        
        // Restore UI elements hidden during VOD
        activity.binding.infoFollowButton.visibility = View.VISIBLE
        activity.binding.mentionsButton.visibility = View.VISIBLE
        // Reset VOD mode in Chat UI (removes padding)
        activity.chatUiManager.setVodMode(false)
    }

    /**
     * Check if we need to fetch more chat and trigger fetch if needed
     */
    private fun checkAndFetchVodChat() {
        val player = activity.ivsPlayer
        if (player == null) {
            Log.d(TAG, "VOD Chat Check: Player is null")
            return
        }
        if (vodChatStartTimeMillis == 0L) {
            Log.d(TAG, "VOD Chat Check: startTimeMillis is 0")
            return
        }

        val playerState = player.state
        Log.d(TAG, "VOD Chat Check: playerState=$playerState")

        // If player is paused, don't fetch
        if (playerState == com.amazonaws.ivs.player.Player.State.IDLE ||
            playerState == com.amazonaws.ivs.player.Player.State.ENDED
        ) {
            Log.d(TAG, "VOD Chat Check: Player paused/ended, skipping")
            return
        }

        val currentPositionMs = player.position
        if (currentPositionMs <= 0) {
            Log.d(TAG, "VOD Chat Check: Position is 0 or negative: $currentPositionMs")
            return
        }

        val currentReplayTimeMs = vodChatStartTimeMillis + currentPositionMs
        val targetBufferTimeMs = currentReplayTimeMs + BUFFER_AHEAD_MS

        Log.d(
            TAG,
            "VOD Chat Check: position=$currentPositionMs, bufferedUntil=$vodChatBufferedUntilMs, target=$targetBufferTimeMs, isFetching=$isVodFetching"
        )

        // If we haven't buffered enough ahead, start fetching
        if (vodChatBufferedUntilMs < targetBufferTimeMs && !isVodFetching) {
            Log.d(TAG, "VOD Chat Check: Starting fetch...")
            fetchVodChatMessagesUntil(targetBufferTimeMs)
        }
    }

    /**
     * Fetch chat messages until we have buffered up to targetTimeMs
     */
    private fun fetchVodChatMessagesUntil(targetTimeMs: Long) {
        val channelId = vodChannelId ?: return
        if (isVodFetching) return

        isVodFetching = true

        vodFetchJob = lifecycleScope.launch(Dispatchers.IO) {
            try {
                val sdf =
                    java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", java.util.Locale.US)
                sdf.timeZone = java.util.TimeZone.getTimeZone("UTC")

                // Track next fetch time
                var nextFetchMillis = vodChatBufferedUntilMs

                // Fetch loop
                while (vodChatBufferedUntilMs < targetTimeMs) {

                    val nextFetchIso = sdf.format(java.util.Date(nextFetchMillis))
                    Log.d(TAG, "VOD Chat Fetch: channelId=$channelId, startTime=$nextFetchIso")

                    val result = repository.getChatHistoryForVod(channelId, nextFetchIso)
                    var fetchContinued = false

                    result.onSuccess { chatResult ->
                        val messages = chatResult.messages
                        val cursor = chatResult.cursor

                        // Check for duplicate start (stuck loop prevention)
                        val firstMsg = messages.firstOrNull()
                        val isDuplicateStart =
                            firstMsg != null && vodChatFetchedIds.contains(firstMsg.id)

                        if (isDuplicateStart) {
                            Log.w(TAG, "VOD Chat: Duplicate start message detected. Advancing +1s.")
                            nextFetchMillis += 1000
                            fetchContinued = true
                        } else {
                            val newMessages = messages.filter { !vodChatFetchedIds.contains(it.id) }

                            if (newMessages.isNotEmpty()) {
                                // Add messages to buckets
                                val debugBuckets = mutableMapOf<Long, Int>()

                                newMessages.forEachIndexed { _, msg ->
                                    vodChatFetchedIds.add(msg.id)

                                    val offsetSeconds =
                                        (msg.createdAt - vodChatStartTimeMillis) / 1000
                                    val bucketSec = if (offsetSeconds < 0) 0L else offsetSeconds

                                    val randomMs = (0..200).random().toLong()
                                    val baseTime = (msg.createdAt / 1000) * 1000
                                    val randomizedMsg = msg.copy(createdAt = baseTime + randomMs)

                                    vodChatBuckets.computeIfAbsent(bucketSec) {
                                        java.util.concurrent.CopyOnWriteArrayList()
                                    }.add(randomizedMsg)

                                    debugBuckets[bucketSec] = (debugBuckets[bucketSec] ?: 0) + 1
                                }

                                // Log Dump
                                debugBuckets.toSortedMap().forEach { (sec, count) ->
                                    val timeStr = formatDuration(sec * 1000)
                                    // Get first few random msgs from bucket for verification
                                    val sample = vodChatBuckets[sec]?.take(3)
                                        ?.map { "${it.createdAt}(${it.content})" }
                                    Log.d(
                                        TAG,
                                        "VOD Dump: Bucket $sec ($timeStr) -> Added $count msgs. Samples: $sample"
                                    )
                                }

                                val max = newMessages.maxOf { it.createdAt }
                                // add 1 second
                                if (max > vodChatBufferedUntilMs) {
                                    vodChatBufferedUntilMs = max
                                }
                            } else {
                                // Gap handling from cursor
                                cursor?.toLongOrNull()?.let { micros ->
                                    val millis = micros / 1000
                                    if (millis > vodChatBufferedUntilMs) vodChatBufferedUntilMs =
                                        millis
                                }
                            }

                            // Update next time based on latest message (createdAt + 1s)
                            val latestMsgTime = messages.maxOfOrNull { it.createdAt }

                            if (latestMsgTime != null) {
                                nextFetchMillis = latestMsgTime + 1000
                                fetchContinued = true
                            } else if (cursor != null) {
                                // Fallback to cursor if no messages
                                val micros = cursor.toLongOrNull()
                                if (micros != null) {
                                    val millis = micros / 1000
                                    nextFetchMillis = millis + 1000
                                    fetchContinued = true
                                }
                            }
                        }
                    }.onFailure { e ->
                        Log.e(TAG, "VOD Chat: Error fetching history: ${e.message}", e)
                    }

                    if (!fetchContinued) break

                    kotlinx.coroutines.delay(100)
                }

            } catch (e: Exception) {
                Log.e(TAG, "VOD Chat: Fetch loop error: ${e.message}")
            } finally {
                isVodFetching = false
            }
        }
    }

    // Track which messages have been shown (separate from fetched IDs)
    private val vodChatShownIds = mutableSetOf<String>()

    private fun simulateVodChat() {
        val player = activity.ivsPlayer ?: return
        if (vodChatStartTimeMillis == 0L) return

        val currentPositionMs = player.position
        if (currentPositionMs < 0) return

        val currentVideoSecond = currentPositionMs / 1000
        val currentReplayTimeMs = vodChatStartTimeMillis + currentPositionMs

        // Detect seek
        val positionDiff = kotlin.math.abs(currentPositionMs - lastKnownPositionMs)
        if (positionDiff > 2000 && lastKnownPositionMs > 0) {
            Log.d(TAG, "VOD Chat: Seek detected! Resetting state.")

            // Cancel ongoing fetch immediately to stop old loop
            vodFetchJob?.cancel()
            isVodFetching = false

            activity.chatUiManager.chatAdapter.clearMessages()
            vodChatShownIds.clear()

            // Reset buffer if needed
            if (currentReplayTimeMs < vodChatBufferedUntilMs - BUFFER_AHEAD_MS || currentReplayTimeMs > vodChatBufferedUntilMs) {
                vodChatBufferedUntilMs = currentReplayTimeMs
                vodChatFetchedIds.clear()
                vodChatBuckets.clear()
            }
        }
        lastKnownPositionMs = currentPositionMs

        // Efficient Window: Scan -5s to +2s
        // Enough to catch message boundaries without scanning too much
        val startScan = (currentVideoSecond - 5).coerceAtLeast(0)
        val endScan = currentVideoSecond + 2

        // Log scanning occasionally
        if (currentVideoSecond % 5 == 0L) {
            Log.d(
                TAG,
                "VOD Sim: Scanning buckets $startScan..$endScan. ReplayTime=$currentReplayTimeMs"
            )
        }

        val messagesToShow = mutableListOf<ChatMessage>()
        var skippedFuture = 0
        var skippedShown = 0

        for (sec in startScan..endScan) {
            val bucket = vodChatBuckets[sec]
            bucket?.forEach { msg ->
                // Show message only if its time has passed
                if (msg.createdAt <= currentReplayTimeMs) {
                    if (!vodChatShownIds.contains(msg.id)) {
                        messagesToShow.add(msg)
                    } else {
                        skippedShown++
                    }
                } else {
                    skippedFuture++
                    // Log sample future message occasionally
                    if (skippedFuture == 1 && currentVideoSecond % 5 == 0L) {
                        val diff = msg.createdAt - currentReplayTimeMs
                        Log.d(TAG, "VOD Sim: Future msg ${msg.id} in bucket $sec. Diff=${diff}ms")
                    }
                }
            }
        }

        if (messagesToShow.isNotEmpty()) {
            messagesToShow.sortBy { it.createdAt }

            Log.d(
                TAG,
                "VOD Sim: Adding ${messagesToShow.size} messages to UI. (Future=$skippedFuture, Shown=$skippedShown)"
            )

            messagesToShow.forEach { msg ->
                vodChatShownIds.add(msg.id)
            }

            activity.chatUiManager.chatAdapter.addMessages(messagesToShow)
            if (activity.chatUiManager.isChatAutoScrollEnabled) {
                val itemCnt = activity.chatUiManager.chatAdapter.itemCount
                if (itemCnt > 0) {
                    binding.chatRecyclerView.scrollToPosition(itemCnt - 1)
                }
            }
        }
    }

    private var lastSaveTime = 0L

    fun updateVodProgress() {
        val player = activity.ivsPlayer ?: return
        if (currentPlaybackMode == PlaybackMode.LIVE) return

        val currentPos = player.position
        val duration = player.duration

        if (duration > 0) {
            val progress = ((currentPos.toFloat() / duration) * 1000).toInt()
            binding.playerSeekBar.progress = progress

            val currentTimeStr = formatDuration(currentPos)
            val totalTimeStr = formatDuration(duration)
            binding.streamTimeBadge.text = "$currentTimeStr / $totalTimeStr"
            binding.streamTimeBadge.visibility = View.VISIBLE
            // Hide uptimeDivider in VOD/Clip mode - it's only for live streams
            binding.uptimeDivider.visibility = View.GONE
            
            // Check if video is finished (less than 10s remaining)
            if (duration - currentPos < 10000) {
                 val videoId = currentVideo?.uuid ?: currentVideo?.id?.toString()
                 if (videoId != null) {
                     prefs.removeVodProgress(videoId)
                     // If user is on Following screen or background, try to refresh continue watching list
                     if (activity.currentScreen == MobilePlayerActivity.AppScreen.FOLLOWING) {
                         activity.followingManager.loadContinueWatching()
                         Log.d(TAG, "VOD Sim: Refreshed continue watching list")
                     }
                 }
                 // Prevent saving again
                 lastSaveTime = System.currentTimeMillis() + 60000 // Push back save time
            } else {
                // Save progress every 5 seconds
                if (System.currentTimeMillis() - lastSaveTime > 5000) {
                    saveWatchProgress()
                    // Refresh UI if needed
                    if (activity.currentScreen == MobilePlayerActivity.AppScreen.FOLLOWING) {
                         val videoId = currentVideo?.uuid ?: currentVideo?.id?.toString()
                         if (videoId != null) {
                             activity.followingManager.updateVideoProgress(videoId, currentPos, duration)
                             Log.d(TAG, "VOD Sim: Updated video progress for video $videoId")
                         }
                    }
                    lastSaveTime = System.currentTimeMillis()
                }
            }
        }
    }
    
    fun saveWatchProgress() {
        if (currentPlaybackMode != PlaybackMode.VOD) return
        val video = currentVideo ?: return
        val player = activity.ivsPlayer ?: return
        val channel = activity.currentChannel ?: return
        
        val duration = player.duration
        val position = player.position
        
        if (position > 0 && duration > 0) {
            // Do NOT save if within last 10 seconds (use same logic as updateVodProgress)
            if (duration - position < 10000) {
                return
            }

            val state = dev.xacnio.kciktv.shared.data.prefs.AppPreferences.VodWatchState(
                videoId = video.uuid ?: video.id?.toString() ?: return,
                videoTitle = video.sessionTitle ?: "VOD",
                thumbnailUrl = video.thumbnail?.src,
                duration = duration,
                watchedDuration = position,
                sourceUrl = video.source,
                channelName = channel.username,
                channelSlug = channel.slug,
                profilePic = channel.getEffectiveProfilePicUrl(),
                categoryName = video.categories?.firstOrNull()?.name,
                categorySlug = video.categories?.firstOrNull()?.slug,
                channelId = channel.id
            )
            prefs.saveVodProgress(state)
        }
    }

    private fun formatDuration(ms: Long): String {
        val totalSeconds = ms / 1000
        val hours = totalSeconds / 3600
        val minutes = (totalSeconds % 3600) / 60
        val seconds = totalSeconds % 60

        return if (hours > 0) {
            String.format("%02d:%02d:%02d", hours, minutes, seconds)
        } else {
            String.format("%02d:%02d", minutes, seconds)
        }
    }

    fun parseIsoDateToMillis(isoString: String): Long {
        return dev.xacnio.kciktv.shared.util.DateParseUtils.parseIsoDate(isoString)
    }

    fun playVideo(video: dev.xacnio.kciktv.shared.data.model.ChannelVideo, channelData: Any, startPositionMs: Long = 0L) {
        val channel: ChannelItem
        val fullChannel: dev.xacnio.kciktv.shared.data.model.ChannelDetailResponse?

        if (channelData is dev.xacnio.kciktv.shared.data.model.ChannelDetailResponse) {
            fullChannel = channelData
            channel = ChannelItem.fromChannelDetailResponse(channelData)
        } else if (channelData is ChannelItem) {
            channel = channelData
            fullChannel = null
        } else {
            Log.e(TAG, "playVideo: Invalid channelData type: ${channelData.javaClass.name}")
            return
        }

        // FIRST: Stop live WebSocket immediately
        activity.stopChatWebSocket()
        activity.overlayManager.resetForNewChannel()
        
        // Cancel any ongoing loading operations to prevent race conditions
        activity.playerManager.cancelLoadingOperations()
        
        // Stop any existing VOD chat replay
        stopVodChatReplay()
        
        // Disable DVR/Live specific timers
        activity.playbackStatusManager.streamCreatedAtMillis = null
        activity.playbackStatusManager.stopUptimeUpdater()
        activity.playbackControlManager.stopProgressUpdater()
        activity.viewerCountHandler.removeCallbacks(activity.viewerCountRunnable)
        activity.currentLivestreamId = null

        // Exit Mini Player if active
        if (activity.miniPlayerManager.isMiniPlayerMode) {
             activity.miniPlayerManager.exitMiniPlayerMode()
        }

        // Reset chat UI for new content
        activity.chatUiManager.reset()
        activity.chatUiManager.isChatUiPaused = false
        activity.chatUiManager.chatAdapter.setSubscriberBadges(emptyMap())
        
        currentPlaybackMode = PlaybackMode.VOD
        activity.updatePlaybackUI()
        
        // Enable VOD mode in Chat UI
        activity.chatUiManager.setVodMode(true)

        activity.ivsPlayer?.pause()
        currentVideo = video
        currentClip = null // Clear clip when playing VOD

        // activity.currentChannelIndex and allChannels are private in activity, 
        // they should remain handled by activity if possible or made internal.
        // For now, let's see if we can skip updating currentChannelIndex here or make it internal.
        
        activity.currentChannel = channel

        binding.mobileHeader.visibility = View.GONE
        binding.playerScreenContainer.visibility = View.VISIBLE
        binding.homeScreenContainer.root.visibility = View.GONE
        binding.browseScreenContainer.root.visibility = View.GONE
        binding.followingScreenContainer.root.visibility = View.GONE
        binding.searchContainer.visibility = View.GONE
        binding.categoryDetailsContainer.root.visibility = View.GONE
        binding.channelProfileContainer.root.visibility = View.GONE
        binding.bottomNavContainer.visibility = View.GONE
        binding.bottomNavGradient.visibility = View.GONE
        activity.isHomeScreenVisible = false

        activity.showLoading()
        
        // Log analytics event (anonymous)
        activity.analytics.logVodView()

        binding.infoFollowButton.visibility = View.GONE 
        binding.mentionsButton.visibility = View.GONE
        val playbackUrl = video.source

        if (playbackUrl != null) {
            loadDirectUrl(playbackUrl, startPositionMs)
        } else {
            Toast.makeText(activity, activity.getString(R.string.video_url_not_found), Toast.LENGTH_SHORT).show()
        }

        binding.root.post {
            binding.infoChannelName.text = channel.username
            binding.infoStreamTitle.text = video.sessionTitle ?: activity.getString(R.string.vod_title)
            activity.currentVodTitle = video.sessionTitle ?: activity.getString(R.string.vod_title)
            binding.infoCategoryName.text = video.categories?.firstOrNull()?.name ?: activity.getString(R.string.unknown_category)
            binding.viewerCount.text = activity.formatViewerCount((video.views ?: 0).toLong())
            
            Glide.with(activity)
                .asBitmap()
                .load(channel.getEffectiveProfilePicUrl())
                .circleCrop()
                .into(object : CustomTarget<Bitmap>() {
                    override fun onResourceReady(resource: Bitmap, transition: Transition<in Bitmap>?) {
                        try {
                            activity.currentProfileBitmap = resource
                            binding.infoProfileImage.setImageBitmap(resource)
                            // if (activity.isBackgroundAudioEnabled) activity.showNotification() 
                        } catch (e: Exception) {}
                    }
                    override fun onLoadCleared(placeholder: Drawable?) {
                        // activity.currentProfileBitmap = null // Optional cleanup
                    }
                })
        }

        val vodCreatedStr = video.video?.createdAt
        lifecycleScope.launch(Dispatchers.IO) {
            var chatroomIdValue = channel.id.toLongOrNull()
            
            // If ID is missing (e.g. from Continue Watching), try to fetch it using slug
            if (chatroomIdValue == null && !channel.slug.isNullOrEmpty()) {
                 try {
                     // Use specific type to help inference
                     val result: Result<dev.xacnio.kciktv.shared.data.model.ChannelDetailResponse> = repository.getChannelDetails(channel.slug)
                     result.onSuccess { ch ->
                         chatroomIdValue = ch.id
                         Log.d(TAG, "playVideo: Fetched missing channel ID: $chatroomIdValue")
                     }
                 } catch(e: Exception) {
                     Log.e(TAG, "playVideo: Failed to fetch channel ID", e)
                 }
            }

            Log.d(TAG, "playVideo: vodCreatedStr=$vodCreatedStr, chatroomId=$chatroomIdValue")
            
            val finalChatroomId = chatroomIdValue

            if (finalChatroomId != null) {
                var finalStartTime = 0L

                // 1. Try to fetch precise time from m3u8
                if (playbackUrl != null) {
                    try {
                        val hlsTime = fetchHlsStartTime(playbackUrl)
                        if (hlsTime != null && hlsTime > 0) {
                            Log.d(TAG, "playVideo: Found precise HLS time: $hlsTime")
                            finalStartTime = hlsTime
                        }
                    } catch (e: Exception) {
                        Log.w(TAG, "playVideo: HLS fetch failed", e)
                    }
                }

                // 2. Fallback to createdAt string
                if (finalStartTime == 0L && vodCreatedStr != null) {
                    var cleanStr = vodCreatedStr
                    if (!cleanStr.contains("T")) {
                         cleanStr = cleanStr.trim().replace(" ", "T") + ".000Z"
                    }
                    finalStartTime = parseIsoDateToMillis(cleanStr)
                    Log.d(TAG, "playVideo: Fallback to createdAt: $finalStartTime (str=$cleanStr)")
                }
                
                // 3. (Handled by refreshChatIdentity below)
                
                // 4. Fetch Subscriber Badges and User Identity for Chat Replay
                refreshChatIdentity(channel.slug, fullChannel)
                
                withContext(Dispatchers.Main) {
                    activity.chatUiManager.setVodMode(true)
                    
                    if (finalStartTime > 0) {
                        startVodChatReplay(finalChatroomId, finalStartTime, startPositionMs)
                    } else {
                        Log.e(TAG, "playVideo: Could not determine start time")
                    }
                }
            }
        }
    }

    fun playClipById(clipId: String) {
        // Cancel any ongoing loading operations
        activity.playerManager.cancelLoadingOperations()
        
        saveCurrentState()
        activity.showLoading()
        lifecycleScope.launch {
            repository.getClipPlayDetails(clipId).onSuccess { response ->
                val clipDetails = response.clip ?: return@onSuccess
                val channel = clipDetails.channel
                
                withContext(Dispatchers.Main) {
                    val channelItem = if (channel != null) {
                        ChannelItem(
                            id = channel.id?.toString() ?: "",
                            slug = channel.slug ?: "",
                            username = channel.username ?: "",
                            title = null,
                            viewerCount = 0,
                            thumbnailUrl = null,
                            profilePicUrl = channel.profilePicture,
                            playbackUrl = null,
                            categoryName = null,
                            categorySlug = null,
                            language = null
                        )
                    } else {
                        activity.currentChannel ?: ChannelItem(
                            id = "",
                            slug = "",
                            username = "",
                            title = null,
                            viewerCount = 0,
                            thumbnailUrl = null,
                            profilePicUrl = null,
                            playbackUrl = null,
                            categoryName = null,
                            categorySlug = null,
                            language = null
                        )
                    }
                    
                    val clip = ChannelClip(
                        id = clipId,
                        title = clipDetails.title,
                        thumbnailUrl = clipDetails.thumbnailUrl,
                        duration = clipDetails.duration,
                        views = clipDetails.viewCount,
                        createdAt = null,
                        url = clipDetails.videoUrl ?: clipDetails.clipUrl,
                        creator = null,
                        channel = channel
                    )
                    
                    playClip(clip, channelItem)
                }
            }.onFailure {
                withContext(Dispatchers.Main) {
                    activity.hideLoading()
                    Toast.makeText(activity, activity.getString(R.string.error_loading_clip, it.message), Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    fun playClip(clip: dev.xacnio.kciktv.shared.data.model.ChannelClip, channelData: Any?) {
        val channel: ChannelItem
        val fullChannel: dev.xacnio.kciktv.shared.data.model.ChannelDetailResponse?

        if (channelData is dev.xacnio.kciktv.shared.data.model.ChannelDetailResponse) {
            fullChannel = channelData
            channel = ChannelItem.fromChannelDetailResponse(channelData)
        } else if (channelData is ChannelItem) {
            channel = channelData
            fullChannel = null
        } else {
            // Fallback to channel info in clip if available
            val clipChannel = clip.channel
            if (clipChannel != null) {
                channel = ChannelItem(
                    id = clipChannel.id?.toString() ?: "",
                    slug = clipChannel.slug ?: "",
                    username = clipChannel.username ?: "",
                    title = null,
                    viewerCount = 0,
                    thumbnailUrl = null,
                    profilePicUrl = clipChannel.profilePicture,
                    playbackUrl = null,
                    categoryName = null,
                    categorySlug = null,
                    language = null
                )
                fullChannel = null
            } else {
                Log.e(TAG, "playClip: Invalid channelData type: ${channelData?.javaClass?.name}")
                return
            }
        }

        // FIRST: Stop live WebSocket immediately to prevent race conditions
        activity.stopChatWebSocket()
        activity.overlayManager.resetForNewChannel()
        
        // Cancel any ongoing loading operations
        activity.playerManager.cancelLoadingOperations()
        
        // Stop any existing VOD chat replay
        stopVodChatReplay()
        
        // Disable DVR/Live specific timers
        activity.playbackStatusManager.streamCreatedAtMillis = null
        activity.playbackStatusManager.stopUptimeUpdater()
        activity.playbackControlManager.stopProgressUpdater()
        activity.viewerCountHandler.removeCallbacks(activity.viewerCountRunnable)
        activity.currentLivestreamId = null
        
        // Exit Mini Player if active
        if (activity.miniPlayerManager.isMiniPlayerMode) {
             activity.miniPlayerManager.exitMiniPlayerMode()
        }

        // Reset chat UI for new content
        activity.chatUiManager.reset()
        activity.chatUiManager.isChatUiPaused = false
        activity.chatUiManager.chatAdapter.setSubscriberBadges(emptyMap())
        
        currentPlaybackMode = PlaybackMode.CLIP
        currentClip = clip
        currentVideo = null
        activity.updatePlaybackUI()

        activity.ivsPlayer?.pause()
        activity.currentChannel = channel

        binding.mobileHeader.visibility = View.GONE
        binding.channelProfileContainer.root.visibility = View.GONE
        binding.homeScreenContainer.root.visibility = View.GONE
        binding.browseScreenContainer.root.visibility = View.GONE
        binding.followingScreenContainer.root.visibility = View.GONE
        binding.searchContainer.visibility = View.GONE
        binding.categoryDetailsContainer.root.visibility = View.GONE
        binding.playerScreenContainer.visibility = View.VISIBLE
        binding.playerScreenContainer.bringToFront()
        binding.bottomNavContainer.visibility = View.GONE
        binding.bottomNavGradient.visibility = View.GONE
        activity.isHomeScreenVisible = false
        
        binding.videoTopBar.visibility = View.GONE   
        binding.infoFollowButton.visibility = View.GONE
        binding.mentionsButton.visibility = View.GONE
        activity.showLoading()
        
        // Log analytics event (anonymous)
        activity.analytics.logClipView()

        lifecycleScope.launch {
            repository.getClipPlayDetails(clip.id).onSuccess { response ->
                val clipDetails = response.clip
                withContext(Dispatchers.Main) {
                     // Load Profile Pic for Clips too
                     Glide.with(activity)
                        .asBitmap()
                        .load(channel.getEffectiveProfilePicUrl())
                        .circleCrop()
                        .into(object : CustomTarget<Bitmap>() {
                            override fun onResourceReady(resource: Bitmap, transition: Transition<in Bitmap>?) {
                                try {
                                    activity.currentProfileBitmap = resource
                                    binding.infoProfileImage.setImageBitmap(resource)
                                } catch (e: Exception) {}
                            }
                            override fun onLoadCleared(placeholder: Drawable?) {}
                        })
                    
                    val streamUrl = clipDetails?.videoUrl ?: clipDetails?.clipUrl ?: clip.url
                    if (streamUrl != null) {
                        loadDirectUrl(streamUrl)
                        binding.root.post {
                            binding.infoChannelName.text =
                                clipDetails?.channel?.username ?: channel.username
                            binding.infoStreamTitle.text = clipDetails?.title ?: clip.title
                            binding.infoCategoryName.text = clipDetails?.category?.name ?: activity.getString(R.string.category_clip)
                            binding.viewerCount.text = activity.formatViewerCount(
                                (clipDetails?.viewCount ?: clip.views ?: 0).toLong()
                            )
                            
                            // Watch Full VOD Button
                            if (clipDetails?.vod?.id != null) {
                                setupWatchFullVodButton(clipDetails.vod.id, clipDetails.vodStartsAt ?: 0, fullChannel = fullChannel)
                            }
                        }
                        val startedAt = clipDetails?.startedAt
                        // Use channel.id for chat history API
                        val chatroomIdValue = channel.id.toLongOrNull()
                        Log.d(TAG, "playClip: startedAt=$startedAt, chatroomId=$chatroomIdValue")
                        if (startedAt != null && chatroomIdValue != null) {
                            startVodChatReplay(chatroomIdValue, startedAt)
                        }
                        
                        // Fetch identity for clips too (subscriber badges, moderator status)
                        refreshChatIdentity(channel.slug, fullChannel)
                    } else {
                        Toast.makeText(activity, activity.getString(R.string.clip_url_not_found), Toast.LENGTH_SHORT)
                            .show()
                    }
                }
            }.onFailure {
                withContext(Dispatchers.Main) {
                    val playbackUrl = clip.url
                    if (playbackUrl != null) {
                        loadDirectUrl(playbackUrl)
                        binding.root.post {
                            binding.infoChannelName.text = channel.username
                            binding.infoStreamTitle.text = clip.title
                            binding.infoCategoryName.text = activity.getString(R.string.category_clip)
                            binding.viewerCount.text =
                                activity.formatViewerCount((clip.views ?: 0).toLong())
                        }
                    } else {
                        Toast.makeText(activity, activity.getString(R.string.clip_url_not_found), Toast.LENGTH_SHORT)
                            .show()
                    }
                }
            }
        }
    }

    fun loadDirectUrl(url: String, startPositionMs: Long = 0L) {
        activity.playerManager.resetPlayer()
        activity.ivsPlayer?.load(android.net.Uri.parse(url))
        activity.ivsPlayer?.play()
        
        if (startPositionMs > 0) {
            vodProgressHandler.postDelayed({
                try {
                    activity.ivsPlayer?.seekTo(startPositionMs)
                } catch(e: Exception) {}
            }, 1000)
        }

        // Don't show seekbar in theatre mode
        if (!activity.fullscreenToggleManager.isTheatreMode) {
            binding.playerSeekBar.visibility = View.VISIBLE
        }
        binding.rewindButton.visibility = View.VISIBLE
        binding.forwardButton.visibility = View.VISIBLE
        binding.playPauseOverlay.visibility = View.VISIBLE

        vodProgressHandler.removeCallbacks(vodProgressRunnable)
        vodProgressHandler.postDelayed(vodProgressRunnable, 500)
    }

    private fun fetchHlsStartTime(masterUrl: String): Long? {
        try {
            // 1. Fetch Master Playlist
            val masterContent = java.net.URL(masterUrl).readText()

            // 2. Find Variant (assuming first stream)
            // Look for lines not starting with #
            val lines = masterContent.lines()
            var variantUrl: String? = null

            for (line in lines) {
                if (line.isNotBlank() && !line.startsWith("#")) {
                    variantUrl = line.trim()
                    break
                }
            }

            if (variantUrl == null) return null

            // Resolve relative URL
            // If variantUrl starts with http, it's absolute. Else relative to master.
            val finalVariantUrl = if (variantUrl.startsWith("http")) {
                variantUrl
            } else {
                val base = masterUrl.substringBeforeLast("/")
                "$base/$variantUrl"
            }

            // 3. Fetch Variant Playlist
            val variantContent = java.net.URL(finalVariantUrl).readText()

            // 4. Find EXT-X-PROGRAM-DATE-TIME
            // #EXT-X-PROGRAM-DATE-TIME:2025-06-13T14:12:35.054Z
            val programDatePrefix = "#EXT-X-PROGRAM-DATE-TIME:"
            val dateLine = variantContent.lines().find { it.startsWith(programDatePrefix) }

            if (dateLine != null) {
                val isoDate = dateLine.removePrefix(programDatePrefix).trim()
                // Use parser that supports Milliseconds
                return dev.xacnio.kciktv.shared.util.DateParseUtils.parseIsoDateWithMs(isoDate)
            }

        } catch (e: Exception) {
            Log.e(TAG, "IVS HLS fetch failed: ${e.message}")
        }
        return null
    }
    private fun setupWatchFullVodButton(vodId: String, offsetSeconds: Long, fullChannel: dev.xacnio.kciktv.shared.data.model.ChannelDetailResponse? = null) {
         val parent = binding.infoCategoryName.parent as? android.view.ViewGroup
         if (parent != null) {
              var btn = parent.findViewWithTag<android.widget.Button>("vod_btn")
              if (btn == null) {
                   btn = android.widget.Button(activity).apply {
                       tag = "vod_btn"
                       textSize = 12f
                       setTextColor(android.graphics.Color.WHITE)
                       background = androidx.core.content.ContextCompat.getDrawable(activity, R.drawable.bg_item_ripple)
                       minHeight = 0
                       minimumHeight = 0
                       setPadding(20, 10, 20, 10)
                       layoutParams = android.widget.LinearLayout.LayoutParams(
                           android.widget.LinearLayout.LayoutParams.WRAP_CONTENT,
                           android.widget.LinearLayout.LayoutParams.WRAP_CONTENT
                       ).apply { topMargin = 8 }
                   }
                   parent.addView(btn)
              }
              btn.text = activity.getString(R.string.watch_full_vod)
              btn.visibility = View.VISIBLE
              btn.setOnClickListener {
                   watchFullVod(vodId, offsetSeconds, fullChannel = fullChannel) 
               }
         }
    }

    private fun watchFullVod(vodId: String, offsetSeconds: Long, fullChannel: dev.xacnio.kciktv.shared.data.model.ChannelDetailResponse? = null) {
         activity.showLoading()
         lifecycleScope.launch {
             repository.getVideo(vodId).onSuccess { video ->
                 withContext(Dispatchers.Main) {
                      val vChannel = video.channel
                      val currentChannel = if (vChannel != null) {
                          ChannelItem(
                              id = vChannel.id?.toString() ?: "0",
                              slug = vChannel.slug ?: "unknown",
                              username = vChannel.username ?: "unknown",
                              title = video.sessionTitle ?: "VOD",
                              viewerCount = video.views ?: 0,
                              thumbnailUrl = video.thumbnail?.src,
                              profilePicUrl = vChannel.profilePicture,
                              playbackUrl = null,
                              categoryName = video.categories?.firstOrNull()?.name,
                              categorySlug = video.categories?.firstOrNull()?.slug,
                              language = video.language
                          )
                      } else {
                          activity.currentChannel ?: ChannelItem(
                              id = "0",
                              slug = "unknown", 
                              username = "unknown",
                              title = "Saved Video",
                              viewerCount = 0,
                              thumbnailUrl = null,
                              profilePicUrl = null,
                              playbackUrl = null,
                              categoryName = null,
                              categorySlug = null,
                              language = null
                          )
                      }
                      
                       // PlayVideo will clear and setup UI for VOD
                       playVideo(video, fullChannel ?: currentChannel, offsetSeconds * 1000L)
                 }
             }.onFailure {
                 withContext(Dispatchers.Main) {
                      Toast.makeText(activity, activity.getString(R.string.video_load_failed), Toast.LENGTH_SHORT).show()
                      // Ideally allow user to stay on clip
                 }
             }
         }
    }
    /**
     * Fetches current user's identity and channel subscriber badges for the given slug.
     */
    private fun refreshChatIdentity(channelSlug: String, fullChannel: dev.xacnio.kciktv.shared.data.model.ChannelDetailResponse? = null) {
        lifecycleScope.launch(Dispatchers.IO) {
            val token = prefs.authToken ?: return@launch
            
            // 1. Process Subscriber Badges immediately if provided in fullChannel
            if (fullChannel != null) {
                fullChannel.subscriberBadges?.let { badges ->
                    val badgeMap = badges.associate { (it.months ?: 0) to (it.badgeImage?.src ?: "") }
                    withContext(Dispatchers.Main) {
                        activity.chatUiManager.chatAdapter.setSubscriberBadges(badgeMap)
                    }
                }
            } else {
                // Fetch Chat Info for Subscriber Badges (the actual icons)
                try {
                    repository.getChatInfo(channelSlug, token).onSuccess { chatInfo ->
                        withContext(Dispatchers.Main) {
                            activity.chatUiManager.chatAdapter.setSubscriberBadges(chatInfo.subscriberBadges)
                        }
                    }.onFailure { e ->
                        Log.e(TAG, "ChatInfo fetch failed for $channelSlug: ${e.message}")
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Error fetching chat badges", e)
                }
            }

            // 2. Fetch User Me for Moderator/Identity info (This is unique to the user-channel relation)
            try {
                repository.getChannelUserMe(channelSlug, token).onSuccess { me ->
                    withContext(Dispatchers.Main) {
                        val badges = mutableListOf<dev.xacnio.kciktv.shared.data.model.ChatBadge>()
                        if (me.isBroadcaster == true) badges.add(dev.xacnio.kciktv.shared.data.model.ChatBadge("broadcaster", "Broadcaster", null))
                        if (me.isModerator == true) badges.add(dev.xacnio.kciktv.shared.data.model.ChatBadge("moderator", "Moderator", null))
                        if (me.isSuperAdmin == true) badges.add(dev.xacnio.kciktv.shared.data.model.ChatBadge("staff", "Staff", null))
                        if (me.subscription != null) badges.add(dev.xacnio.kciktv.shared.data.model.ChatBadge("subscriber", "Subscriber", null))
                        
                        val sender = dev.xacnio.kciktv.shared.data.model.ChatSender(
                            id = prefs.userId,
                            username = prefs.username ?: "User",
                            color = "#53fc18",
                            badges = badges
                        )
                        activity.chatUiManager.setCurrentUserSender(sender)
                        val isMod = me.isModerator == true || me.isBroadcaster == true
                        activity.chatUiManager.setModeratorStatus(isMod)
                    }
                }.onFailure { e ->
                    Log.e(TAG, "Identity fetch failed for $channelSlug: ${e.message}")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error fetching user identity", e)
            }
        }
    }
}