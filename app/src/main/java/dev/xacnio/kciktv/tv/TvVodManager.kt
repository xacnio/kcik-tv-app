/**
 * File: TvVodManager.kt
 *
 * Description: Manages Google TV / Android TV VOD playback, D-Pad timeline scrubbing,
 * watch-progress persistence, and 250ms playhead-synchronized chat replay with instant
 * cursor-based seek backfill.
 */
package dev.xacnio.kciktv.tv

import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.widget.Toast
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.lifecycleScope
import com.amazonaws.ivs.player.Player
import dev.xacnio.kciktv.shared.data.api.RetrofitClient
import dev.xacnio.kciktv.shared.data.model.ChannelItem
import dev.xacnio.kciktv.shared.data.model.ChannelVideo
import dev.xacnio.kciktv.shared.data.model.ChatMessage
import dev.xacnio.kciktv.shared.data.model.ListChannelVideo
import dev.xacnio.kciktv.shared.data.prefs.AppPreferences
import dev.xacnio.kciktv.shared.data.repository.ChannelRepository
import dev.xacnio.kciktv.shared.util.DateParseUtils
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.Request
import java.text.SimpleDateFormat
import java.util.Collections
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.abs

class TvVodManager(
    private val activity: PlayerActivity,
    private val repository: ChannelRepository
) : DefaultLifecycleObserver {

    companion object {
        private const val TAG = "TvVodManager"
        private const val SYNC_TICK_MS = 250L
        private const val LOOKAHEAD_WINDOW_MS = 60_000L
        private const val SEEK_JUMP_THRESHOLD_MS = 6_500L
        private const val SCRUB_COMMIT_DELAY_MS = 420L

        private val KNOWN_BOT_NAMES = setOf(
            "botrix",
            "kickbot",
            "nightbot",
            "streamelements",
            "moobot",
            "fossabot",
            "wizebot",
            "wzbot"
        )

        fun formatDurationMs(ms: Long): String {
            val clampedSec = (ms.coerceAtLeast(0L)) / 1000L
            val h = clampedSec / 3600L
            val m = (clampedSec % 3600L) / 60L
            val s = clampedSec % 60L
            return if (h > 0L) {
                String.format(Locale.US, "%d:%02d:%02d", h, m, s)
            } else {
                String.format(Locale.US, "%02d:%02d", m, s)
            }
        }
    }

    init {
        activity.lifecycle.addObserver(this)
    }

    var isVodMode: Boolean = false
        private set

    var isLiveDvrChatSyncActive: Boolean = false
        private set

    var currentVideo: ChannelVideo? = null
        private set

    var currentChannel: ChannelItem? = null
        private set

    var vodStartTimeMillis: Long = 0L
        private set

    private var vodChannelId: Long = 0L
    private var pendingInitialSeekMs: Long = 0L
    private var lastSyncedPositionMs: Long = -1L
    private var bufferedUntilEpochMs: Long = 0L
    private var lastLookaheadTriggerElapsedMs: Long = 0L
    private var lastProgressSaveElapsedMs: Long = 0L

    // Interactive D-Pad Scrub State
    private var scrubBasePositionMs: Long = 0L
    private var pendingScrubTargetMs: Long? = null

    val hasPendingScrub: Boolean
        get() = pendingScrubTargetMs != null

    private val syncGeneration = AtomicInteger(0)
    private var vodLoadJob: Job? = null
    private var vodFetchJob: Job? = null
    private var vodBackfillJob: Job? = null
    @Volatile
    private var isFetchingLookahead: Boolean = false

    private val fetchedMessageIds: MutableSet<String> =
        Collections.synchronizedSet(HashSet())
    private val shownMessageIds: MutableSet<String> =
        Collections.synchronizedSet(HashSet())
    private val chatBucketsBySecond =
        ConcurrentHashMap<Long, CopyOnWriteArrayList<ChatMessage>>()

    private val mainHandler = Handler(Looper.getMainLooper())

    private val syncTickRunnable = object : Runnable {
        override fun run() {
            if (!isVodMode && !isLiveDvrChatSyncActive) return
            try {
                onSyncTick()
            } catch (t: Throwable) {
                Log.e(TAG, "Error in syncTickRunnable: ${t.message}", t)
            } finally {
                if (isVodMode || isLiveDvrChatSyncActive) {
                    mainHandler.postDelayed(this, SYNC_TICK_MS)
                }
            }
        }
    }

    private val commitScrubRunnable = Runnable {
        commitPendingScrub()
    }

    override fun onDestroy(owner: LifecycleOwner) {
        stopVodPlayback(restoreLiveFlags = false)
        super.onDestroy(owner)
    }

    /**
     * Fetches the chronological latest VOD for [slug] and immediately launches fullscreen
     * VOD playback with synchronized translucent chat overlay.
     */
    fun playChannelLatestVod(slug: String, forceFromStart: Boolean = false) {
        val cleanSlug = slug.trim().lowercase()
        if (cleanSlug.isEmpty()) return

        Toast.makeText(
            activity,
            "Loading $cleanSlug's latest VOD...",
            Toast.LENGTH_SHORT
        ).show()

        vodLoadJob?.cancel()
        vodLoadJob = activity.lifecycleScope.launch {
            val detailsResult = repository.getChannelDetails(cleanSlug)
            val videosResult = repository.getChannelVideos(cleanSlug)

            videosResult.onSuccess { videos ->
                val latest = videos.firstOrNull()
                if (latest == null) {
                    Toast.makeText(
                        activity,
                        "No VODs found for $cleanSlug",
                        Toast.LENGTH_LONG
                    ).show()
                    return@onSuccess
                }

                val detail = detailsResult.getOrNull()
                val channelItem = if (detail != null) {
                    ChannelItem.fromChannelDetailResponse(detail)
                } else {
                    ChannelItem(
                        id = (latest.channelId ?: latest.channel?.id ?: 0L).toString(),
                        slug = cleanSlug,
                        username = latest.channel?.user?.username ?: cleanSlug,
                        title = latest.sessionTitle,
                        viewerCount = latest.views ?: 0,
                        thumbnailUrl = latest.thumbnail?.src,
                        profilePicUrl = latest.channel?.user?.profilePic,
                        playbackUrl = latest.channel?.playbackUrl,
                        categoryName = latest.categories?.firstOrNull()?.name,
                        categorySlug = latest.categories?.firstOrNull()?.slug,
                        language = latest.language,
                        isLive = latest.isLive == true
                    )
                }

                playListVideo(latest, channelItem, forceFromStart = forceFromStart)
            }.onFailure { err ->
                Log.e(TAG, "Failed to load VODs for $cleanSlug: ${err.message}", err)
                Toast.makeText(
                    activity,
                    "Could not load VODs for $cleanSlug",
                    Toast.LENGTH_LONG
                ).show()
            }
        }
    }

    fun playListVideo(
        item: ListChannelVideo,
        channel: ChannelItem,
        forceFromStart: Boolean = false,
        explicitStartPositionMs: Long = -1L
    ) {
        val video = item.toChannelVideo()
        val startHint = item.startTime ?: item.createdAt ?: item.video?.createdAt
        val channelIdHint = item.channelId ?: item.channel?.id
        playVod(
            video = video,
            channel = channel,
            startTimeHint = startHint,
            channelIdHint = channelIdHint,
            forceFromStart = forceFromStart,
            explicitStartPositionMs = explicitStartPositionMs
        )
    }

    fun playVod(
        video: ChannelVideo,
        channel: ChannelItem,
        startTimeHint: String? = null,
        channelIdHint: Long? = null,
        forceFromStart: Boolean = false,
        explicitStartPositionMs: Long = -1L
    ) {
        // Save progress of any currently running VOD before switching
        if (isVodMode) {
            saveWatchProgress()
        }

        val gen = syncGeneration.incrementAndGet()
        vodLoadJob?.cancel()
        vodFetchJob?.cancel()
        vodBackfillJob?.cancel()
        mainHandler.removeCallbacks(syncTickRunnable)
        mainHandler.removeCallbacks(commitScrubRunnable)

        isVodMode = true
        isLiveDvrChatSyncActive = false
        currentVideo = video
        currentChannel = channel
        vodStartTimeMillis = 0L
        vodChannelId = channelIdHint ?: channel.id.toLongOrNull() ?: 0L
        pendingScrubTargetMs = null
        lastSyncedPositionMs = -1L
        bufferedUntilEpochMs = 0L
        isFetchingLookahead = false
        fetchedMessageIds.clear()
        shownMessageIds.clear()
        chatBucketsBySecond.clear()

        val videoKey = video.uuid ?: video.id?.toString()
        val savedProgress = if (!forceFromStart && explicitStartPositionMs < 0L && videoKey != null) {
            activity.prefs.getVodProgress(videoKey)
        } else {
            null
        }

        val resumeMs = when {
            explicitStartPositionMs >= 0L -> explicitStartPositionMs
            savedProgress != null && savedProgress.watchedDuration > 15_000L -> savedProgress.watchedDuration
            else -> 0L
        }
        pendingInitialSeekMs = resumeMs

        val vodTitle = video.sessionTitle ?: "VOD Broadcast"
        val catName = video.categories?.firstOrNull()?.name ?: channel.categoryName
        val views = video.views ?: video.viewerCount ?: 0

        activity.preparePlayerForVod(
            channel = channel,
            vodTitle = vodTitle,
            categoryName = catName,
            views = views,
            thumbnailUrl = video.thumbnail?.src ?: channel.thumbnailUrl
        )

        if (resumeMs > 15_000L) {
            Toast.makeText(
                activity,
                "Resuming VOD at ${formatDurationMs(resumeMs)}",
                Toast.LENGTH_SHORT
            ).show()
        }

        vodLoadJob = activity.lifecycleScope.launch {
            try {
                // 1. Resolve playable HLS (.m3u8) URL
                var resolvedSource = video.source
                var resolvedVideo = video

                val videoUuid = video.uuid
                if (resolvedSource.isNullOrBlank() && !videoUuid.isNullOrBlank()) {
                    repository.getVideo(videoUuid).onSuccess { fullVideo ->
                        resolvedVideo = fullVideo
                        resolvedSource = fullVideo.source
                    }
                }
                if (resolvedSource.isNullOrBlank() && video.isLive == true && channel.slug.isNotBlank()) {
                    repository.getStreamUrl(channel.slug).onSuccess { liveUrl ->
                        resolvedSource = liveUrl
                    }
                }

                if (gen != syncGeneration.get() || !isVodMode) return@launch

                val finalPlaybackUrl = resolvedSource
                if (finalPlaybackUrl.isNullOrBlank()) {
                    Toast.makeText(
                        activity,
                        "VOD stream URL unavailable for this broadcast",
                        Toast.LENGTH_LONG
                    ).show()
                    return@launch
                }

                currentVideo = resolvedVideo

                // Start IVS video playback immediately on Main thread
                withContext(Dispatchers.Main) {
                    if (gen != syncGeneration.get() || !isVodMode) return@withContext
                    activity.ivsPlayer?.apply {
                        pause()
                        setRebufferToLive(false)
                        playbackRate = 1.0f
                        setAutoQualityMode(true)
                        load(Uri.parse(finalPlaybackUrl))
                        play()
                    }
                    activity.ensureChatVisibleForVod()
                }

                // 2. Resolve numeric channelId (required for /api/v1/chat/{channelId}/history)
                var numericChannelId = vodChannelId
                if (numericChannelId <= 0L && channel.slug.isNotBlank()) {
                    repository.getChannelDetails(channel.slug).onSuccess { details ->
                        details.id?.let { numericChannelId = it }
                    }
                }

                // 3. Fetch subscriber badges for rich chat rendering
                if (channel.slug.isNotBlank()) {
                    repository.getChatInfo(channel.slug, activity.prefs.authToken).onSuccess { chatInfo ->
                        if (numericChannelId <= 0L && chatInfo.channelId > 0L) {
                            numericChannelId = chatInfo.channelId
                        }
                        withContext(Dispatchers.Main) {
                            if (gen == syncGeneration.get() && isVodMode) {
                                activity.chatAdapter?.setSubscriberBadges(chatInfo.subscriberBadges)
                            }
                        }
                    }
                }

                // 4. Resolve exact VOD start epoch milliseconds for zero-drift chat sync
                var startEpochMs = fetchHlsProgramStartTimeMs(finalPlaybackUrl) ?: 0L
                if (startEpochMs <= 0L) {
                    val dateCandidate = startTimeHint
                        ?: resolvedVideo.livestream?.startTime
                        ?: resolvedVideo.createdAt
                        ?: resolvedVideo.video?.createdAt
                    startEpochMs = DateParseUtils.parseIsoDateWithMs(dateCandidate)
                }
                if (startEpochMs <= 0L) {
                    startEpochMs = DateParseUtils.extractUuidV7TimestampMs(resolvedVideo.uuid)
                }

                if (gen != syncGeneration.get() || !isVodMode) return@launch

                vodChannelId = numericChannelId
                vodStartTimeMillis = startEpochMs

                Log.d(
                    TAG,
                    "VOD ready: slug=${channel.slug}, channelId=$vodChannelId, startEpochMs=$vodStartTimeMillis, resumeMs=$resumeMs"
                )

                withContext(Dispatchers.Main) {
                    if (gen != syncGeneration.get() || !isVodMode) return@withContext
                    val livePosMs = maxOf(resumeMs, activity.ivsPlayer?.position?.coerceAtLeast(0L) ?: 0L)
                    lastSyncedPositionMs = livePosMs
                    val lookbackPosMs = (livePosMs - 5_000L).coerceAtLeast(0L)
                    bufferedUntilEpochMs = ((startEpochMs + lookbackPosMs) / 5_000L) * 5_000L

                    activity.chatAdapter?.apply {
                        isVodMode = true
                        setVodStartTimeMillis(this@TvVodManager.vodStartTimeMillis)
                        setShowTimestamps(activity.prefs.tvVodShowOffsetTimestamps)
                        clearMessages()
                    }

                    // Populate initial backfill and kick off continuous 250ms sync loop
                    if (vodChannelId > 0L && vodStartTimeMillis > 0L) {
                        if (livePosMs > 2_000L) {
                            launchSeekBackfill(livePosMs, gen)
                        }
                        checkAndTriggerLookahead(livePosMs, gen)
                    }

                    mainHandler.removeCallbacks(syncTickRunnable)
                    mainHandler.post(syncTickRunnable)
                }
            } catch (ce: CancellationException) {
                throw ce
            } catch (e: Exception) {
                Log.e(TAG, "Error starting VOD playback: ${e.message}", e)
            }
        }
    }

    fun stopVodPlayback(restoreLiveFlags: Boolean = true) {
        if (isVodMode) {
            saveWatchProgress()
        }
        syncGeneration.incrementAndGet()
        isVodMode = false
        isLiveDvrChatSyncActive = false
        vodLoadJob?.cancel()
        vodFetchJob?.cancel()
        vodBackfillJob?.cancel()
        mainHandler.removeCallbacks(syncTickRunnable)
        mainHandler.removeCallbacks(commitScrubRunnable)

        currentVideo = null
        pendingInitialSeekMs = 0L
        pendingScrubTargetMs = null
        lastSyncedPositionMs = -1L
        vodStartTimeMillis = 0L
        vodChannelId = 0L
        isFetchingLookahead = false
        fetchedMessageIds.clear()
        shownMessageIds.clear()
        chatBucketsBySecond.clear()

        activity.chatAdapter?.apply {
            isVodMode = false
            setVodStartTimeMillis(0L)
            setShowTimestamps(activity.prefs.chatShowTimestamps)
            clearMessages()
        }

        if (restoreLiveFlags) {
            activity.ivsPlayer?.apply {
                setRebufferToLive(true)
                playbackRate = 1.0f
            }
        }
    }

    /**
     * Synchronizes chat overlay with rewound Live DVR playback so rewinding/forwarding a live
     * stream replays historical chat in lockstep with the rewound broadcast timestamp.
     */
    fun startLiveDvrChatSync(
        channel: ChannelItem,
        dvrPlaybackUrl: String,
        fallbackStartEpochMs: Long?,
        initialPositionMs: Long
    ) {
        if (isVodMode) return

        val gen = syncGeneration.incrementAndGet()
        vodLoadJob?.cancel()
        vodFetchJob?.cancel()
        vodBackfillJob?.cancel()
        mainHandler.removeCallbacks(syncTickRunnable)

        isLiveDvrChatSyncActive = true
        currentChannel = channel
        vodStartTimeMillis = 0L
        vodChannelId = channel.id.toLongOrNull() ?: 0L
        pendingScrubTargetMs = null
        lastSyncedPositionMs = -1L
        bufferedUntilEpochMs = 0L
        isFetchingLookahead = false
        fetchedMessageIds.clear()
        shownMessageIds.clear()
        chatBucketsBySecond.clear()

        vodLoadJob = activity.lifecycleScope.launch {
            try {
                var numericChannelId = vodChannelId
                if (numericChannelId <= 0L && channel.slug.isNotBlank()) {
                    repository.getChannelDetails(channel.slug).onSuccess { details ->
                        details.id?.let { numericChannelId = it }
                    }
                }
                if (channel.slug.isNotBlank()) {
                    repository.getChatInfo(channel.slug, activity.prefs.authToken).onSuccess { chatInfo ->
                        if (numericChannelId <= 0L && chatInfo.channelId > 0L) {
                            numericChannelId = chatInfo.channelId
                        }
                        withContext(Dispatchers.Main) {
                            if (gen == syncGeneration.get() && isLiveDvrChatSyncActive) {
                                activity.chatAdapter?.setSubscriberBadges(chatInfo.subscriberBadges)
                            }
                        }
                    }
                }

                var startEpochMs = fetchHlsProgramStartTimeMs(dvrPlaybackUrl) ?: 0L
                if (startEpochMs <= 0L && fallbackStartEpochMs != null && fallbackStartEpochMs > 0L) {
                    startEpochMs = fallbackStartEpochMs
                }

                if (gen != syncGeneration.get() || !isLiveDvrChatSyncActive) return@launch

                vodChannelId = numericChannelId
                vodStartTimeMillis = startEpochMs

                withContext(Dispatchers.Main) {
                    if (gen != syncGeneration.get() || !isLiveDvrChatSyncActive) return@withContext
                    val posMs = activity.ivsPlayer?.position?.takeIf { it > 0L } ?: initialPositionMs.coerceAtLeast(0L)
                    lastSyncedPositionMs = posMs
                    val lookbackPosMs = (posMs - 5_000L).coerceAtLeast(0L)
                    bufferedUntilEpochMs = ((startEpochMs + lookbackPosMs) / 5_000L) * 5_000L

                    activity.chatAdapter?.apply {
                        isVodMode = true
                        setVodStartTimeMillis(startEpochMs)
                        setShowTimestamps(activity.prefs.tvVodShowOffsetTimestamps)
                        clearMessages()
                    }

                    if (vodChannelId > 0L && vodStartTimeMillis > 0L) {
                        if (posMs > 2_000L) {
                            launchSeekBackfill(posMs, gen)
                        }
                        checkAndTriggerLookahead(posMs, gen)
                    }

                    mainHandler.removeCallbacks(syncTickRunnable)
                    mainHandler.post(syncTickRunnable)
                }
            } catch (ce: CancellationException) {
                throw ce
            } catch (e: Exception) {
                Log.e(TAG, "Error starting Live DVR chat sync: ${e.message}", e)
            }
        }
    }

    fun onLiveDvrSeeked(targetPosMs: Long) {
        if (!isLiveDvrChatSyncActive || isVodMode) return
        lastSyncedPositionMs = targetPosMs
        handleSeekResetAndBackfill(targetPosMs)
    }

    fun stopLiveDvrChatSync() {
        if (!isLiveDvrChatSyncActive) return
        syncGeneration.incrementAndGet()
        isLiveDvrChatSyncActive = false
        vodLoadJob?.cancel()
        vodFetchJob?.cancel()
        vodBackfillJob?.cancel()
        mainHandler.removeCallbacks(syncTickRunnable)

        lastSyncedPositionMs = -1L
        vodStartTimeMillis = 0L
        vodChannelId = 0L
        isFetchingLookahead = false
        fetchedMessageIds.clear()
        shownMessageIds.clear()
        chatBucketsBySecond.clear()

        activity.chatAdapter?.apply {
            isVodMode = false
            setVodStartTimeMillis(0L)
            setShowTimestamps(activity.prefs.chatShowTimestamps)
            clearMessages()
        }
    }

    fun onChatPanelShown() {
        if (!isVodMode && !isLiveDvrChatSyncActive) return
        val pos = activity.ivsPlayer?.position?.coerceAtLeast(0L) ?: 0L
        val gen = syncGeneration.get()
        if (vodChannelId > 0L && vodStartTimeMillis > 0L) {
            if ((activity.chatAdapter?.itemCount ?: 0) == 0 && pos > 2_000L) {
                launchSeekBackfill(pos, gen)
            }
            checkAndTriggerLookahead(pos, gen)
        }
        mainHandler.removeCallbacks(syncTickRunnable)
        mainHandler.post(syncTickRunnable)
    }

    /**
     * Interactive D-Pad scrubbing (-10s / +10s with repeat acceleration).
     */
    fun scrubBy(directionSign: Int, repeatCount: Int) {
        val player = activity.ivsPlayer ?: return
        val duration = player.duration.takeIf { it > 0L } ?: (currentVideo?.duration ?: 0L)
        if (duration <= 0L) return

        val stepMs = when {
            repeatCount >= 20 -> 120_000L // ±2m on sustained hold
            repeatCount >= 10 -> 60_000L  // ±1m
            repeatCount >= 4 -> 30_000L   // ±30s
            else -> 10_000L               // ±10s tap
        }

        val currentPos = player.position.coerceAtLeast(0L)
        val basePos = if (pendingScrubTargetMs == null) {
            scrubBasePositionMs = currentPos
            currentPos
        } else {
            pendingScrubTargetMs!!
        }

        val targetPos = (basePos + directionSign * stepMs).coerceIn(0L, (duration - 1000L).coerceAtLeast(0L))
        pendingScrubTargetMs = targetPos

        activity.onVodSeekbarUpdated(
            positionMs = currentPos,
            durationMs = duration,
            bufferedMs = player.bufferedPosition.coerceAtLeast(0L),
            isPlaying = player.state == Player.State.PLAYING,
            scrubPreviewPosMs = targetPos,
            scrubDeltaMs = targetPos - scrubBasePositionMs
        )

        mainHandler.removeCallbacks(commitScrubRunnable)
        mainHandler.postDelayed(commitScrubRunnable, SCRUB_COMMIT_DELAY_MS)
    }

    fun commitPendingScrub() {
        val targetPos = pendingScrubTargetMs ?: return
        mainHandler.removeCallbacks(commitScrubRunnable)
        pendingScrubTargetMs = null

        val player = activity.ivsPlayer ?: return
        val duration = player.duration.takeIf { it > 0L } ?: (currentVideo?.duration ?: 0L)
        try {
            player.seekTo(targetPos)
            if (player.state != Player.State.PLAYING) {
                player.play()
            }
        } catch (e: Exception) {
            Log.w(TAG, "seekTo failed: ${e.message}")
        }

        lastSyncedPositionMs = targetPos
        handleSeekResetAndBackfill(targetPos)

        activity.onVodSeekbarUpdated(
            positionMs = targetPos,
            durationMs = duration,
            bufferedMs = player.bufferedPosition.coerceAtLeast(0L),
            isPlaying = true,
            scrubPreviewPosMs = null,
            scrubDeltaMs = 0L
        )
    }

    fun togglePlayPause(): Boolean {
        if (pendingScrubTargetMs != null) {
            commitPendingScrub()
            return true
        }
        val player = activity.ivsPlayer ?: return false
        val willPlay = player.state != Player.State.PLAYING
        if (willPlay) {
            player.play()
        } else {
            player.pause()
            saveWatchProgress()
        }
        val duration = player.duration.takeIf { it > 0L } ?: (currentVideo?.duration ?: 0L)
        activity.onVodSeekbarUpdated(
            positionMs = player.position.coerceAtLeast(0L),
            durationMs = duration,
            bufferedMs = player.bufferedPosition.coerceAtLeast(0L),
            isPlaying = willPlay,
            scrubPreviewPosMs = null,
            scrubDeltaMs = 0L
        )
        return willPlay
    }

    private fun onSyncTick() {
        val player = activity.ivsPlayer ?: return
        val state = player.state
        val pos = player.position
        val dur = player.duration.takeIf { it > 0L } ?: (currentVideo?.duration ?: 0L)

        if (isVodMode) {
            // Apply initial resume seek as soon as IVS reports valid duration
            if (pendingInitialSeekMs > 0L && dur > 0L && (state == Player.State.READY || state == Player.State.PLAYING)) {
                val target = pendingInitialSeekMs.coerceAtMost((dur - 2_000L).coerceAtLeast(0L))
                pendingInitialSeekMs = 0L
                try {
                    player.seekTo(target)
                    lastSyncedPositionMs = target
                } catch (e: Exception) {
                    Log.w(TAG, "Initial seek failed: ${e.message}")
                }
            }

            val scrubPreview = pendingScrubTargetMs
            activity.onVodSeekbarUpdated(
                positionMs = pos.coerceAtLeast(0L),
                durationMs = dur,
                bufferedMs = player.bufferedPosition.coerceAtLeast(0L),
                isPlaying = state == Player.State.PLAYING,
                scrubPreviewPosMs = scrubPreview,
                scrubDeltaMs = if (scrubPreview != null) scrubPreview - scrubBasePositionMs else 0L
            )
        }

        if (pos < 0L) return

        val nowElapsed = android.os.SystemClock.elapsedRealtime()
        if (isVodMode && state == Player.State.PLAYING && nowElapsed - lastProgressSaveElapsedMs >= 5_000L) {
            lastProgressSaveElapsedMs = nowElapsed
            if (dur > 0L && dur - pos < 15_000L) {
                val videoKey = currentVideo?.uuid ?: currentVideo?.id?.toString()
                if (videoKey != null) activity.prefs.removeVodProgress(videoKey)
            } else {
                saveWatchProgress()
            }
        }

        // Hold chat emission while user is actively scrubbing the seekbar
        if (pendingScrubTargetMs != null) return
        if (vodChannelId <= 0L || vodStartTimeMillis <= 0L) return

        // Detect external/unexpected seek jumps (> 6.5s)
        if (lastSyncedPositionMs >= 0L && abs(pos - lastSyncedPositionMs) > SEEK_JUMP_THRESHOLD_MS) {
            Log.d(TAG, "Seek jump detected: $lastSyncedPositionMs -> $pos ms")
            lastSyncedPositionMs = pos
            handleSeekResetAndBackfill(pos)
            return
        }
        lastSyncedPositionMs = pos

        // Emit buffered chat messages whose timestamp has been reached by the video playhead
        val replayEpochMs = vodStartTimeMillis + pos
        val currentSec = pos / 1000L
        val startScanSec = (currentSec - 25L).coerceAtLeast(0L)
        val endScanSec = currentSec + 1L
        val hideBots = activity.prefs.tvVodHideBots

        val readyMessages = ArrayList<ChatMessage>()
        for (sec in startScanSec..endScanSec) {
            val bucket = chatBucketsBySecond[sec] ?: continue
            for (msg in bucket) {
                if (msg.createdAt <= replayEpochMs && !shownMessageIds.contains(msg.id)) {
                    if (hideBots && isBotUsername(msg.sender.username)) {
                        shownMessageIds.add(msg.id)
                        continue
                    }
                    readyMessages.add(msg)
                }
            }
        }

        if (readyMessages.isNotEmpty()) {
            readyMessages.sortBy { it.createdAt }
            for (msg in readyMessages) {
                shownMessageIds.add(msg.id)
            }
            activity.appendVodChatMessages(readyMessages, smoothScroll = true)
        }

        // Continuously top up 60s lookahead buffer while playing, ready, or buffering
        if ((state == Player.State.PLAYING || state == Player.State.READY || state == Player.State.BUFFERING) &&
            nowElapsed - lastLookaheadTriggerElapsedMs >= 1_200L
        ) {
            lastLookaheadTriggerElapsedMs = nowElapsed
            checkAndTriggerLookahead(pos, syncGeneration.get())
        }
    }

    private fun handleSeekResetAndBackfill(targetPosMs: Long) {
        val gen = syncGeneration.incrementAndGet()
        vodFetchJob?.cancel()
        vodBackfillJob?.cancel()
        isFetchingLookahead = false

        shownMessageIds.clear()
        fetchedMessageIds.clear()
        chatBucketsBySecond.clear()
        val lookbackPosMs = (targetPosMs - 5_000L).coerceAtLeast(0L)
        bufferedUntilEpochMs = ((vodStartTimeMillis + lookbackPosMs) / 5_000L) * 5_000L

        activity.chatAdapter?.clearMessages()

        if (vodChannelId > 0L && vodStartTimeMillis > 0L) {
            if (targetPosMs > 2_000L) {
                launchSeekBackfill(targetPosMs, gen)
            }
            checkAndTriggerLookahead(targetPosMs, gen)
        }
    }

    private fun launchSeekBackfill(atPosMs: Long, expectedGen: Int) {
        val chId = vodChannelId
        val startMs = vodStartTimeMillis
        if (chId <= 0L || startMs <= 0L) return

        vodBackfillJob?.cancel()
        vodBackfillJob = activity.lifecycleScope.launch {
            val targetEpochMs = startMs + atPosMs
            repository.getVodChatBackfill(chId, targetEpochMs, startMs).onSuccess { backfill ->
                if (expectedGen != syncGeneration.get() || (!isVodMode && !isLiveDvrChatSyncActive)) return@onSuccess
                val hideBots = activity.prefs.tvVodHideBots
                val filtered = backfill.filter { msg ->
                    val botBlocked = hideBots && isBotUsername(msg.sender.username)
                    if (botBlocked) {
                        shownMessageIds.add(msg.id)
                        fetchedMessageIds.add(msg.id)
                        false
                    } else {
                        shownMessageIds.add(msg.id)
                        fetchedMessageIds.add(msg.id)
                        true
                    }
                }
                if (filtered.isNotEmpty()) {
                    withContext(Dispatchers.Main) {
                        if (expectedGen == syncGeneration.get() && (isVodMode || isLiveDvrChatSyncActive)) {
                            // Prepend/seed backfill before any newly streamed messages
                            val existing = activity.chatAdapter?.currentList?.toList() ?: emptyList()
                            if (existing.isEmpty()) {
                                activity.appendVodChatMessages(filtered, smoothScroll = false)
                            } else {
                                val merged = (filtered + existing)
                                    .distinctBy { it.id }
                                    .sortedBy { it.createdAt }
                                activity.chatAdapter?.clearMessages()
                                activity.appendVodChatMessages(merged, smoothScroll = false)
                            }
                        }
                    }
                }
            }
        }
    }

    private fun checkAndTriggerLookahead(currentPosMs: Long, expectedGen: Int) {
        if (isFetchingLookahead || vodChannelId <= 0L || vodStartTimeMillis <= 0L) return
        val maxSafeNowMs = System.currentTimeMillis() - 2_500L
        val desiredBufferMs = vodStartTimeMillis + currentPosMs + LOOKAHEAD_WINDOW_MS
        val isGrowingLiveStream = isLiveDvrChatSyncActive || currentVideo?.isLive == true
        val targetBufferEpochMs = if (isGrowingLiveStream) minOf(desiredBufferMs, maxSafeNowMs) else desiredBufferMs
        if (bufferedUntilEpochMs + 5_000L <= targetBufferEpochMs) {
            fetchLookaheadUntil(targetBufferEpochMs, expectedGen)
        }
    }

    /**
     * Fetches 5-second aligned chat windows (`[t, t+5000ms)`) in batches of up to 3 concurrent
     * requests. IMPORTANT: Kick's `data.cursor` in `?start_time=` responses points 25 messages into
     * the future (often 5–20 minutes ahead!), so we NEVER advance `bufferedUntilEpochMs` using
     * `cursor` — we strictly step 5,000ms per verified 5s bucket so quiet periods never skip chat!
     */
    private fun fetchLookaheadUntil(targetEpochMs: Long, expectedGen: Int) {
        val chId = vodChannelId
        val startMs = vodStartTimeMillis
        if (chId <= 0L || startMs <= 0L || isFetchingLookahead) return

        isFetchingLookahead = true
        vodFetchJob = activity.lifecycleScope.launch(Dispatchers.IO) {
            try {
                val isoFmt = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US).apply {
                    timeZone = TimeZone.getTimeZone("UTC")
                }
                var nextBucketMs = ((bufferedUntilEpochMs.coerceAtLeast(startMs)) / 5_000L) * 5_000L

                while (
                    isActive &&
                    expectedGen == syncGeneration.get() &&
                    (isVodMode || isLiveDvrChatSyncActive) &&
                    nextBucketMs < targetEpochMs
                ) {
                    val bucketStarts = ArrayList<Long>(3)
                    var probe = nextBucketMs
                    while (bucketStarts.size < 3 && probe < targetEpochMs) {
                        bucketStarts.add(probe)
                        probe += 5_000L
                    }
                    if (bucketStarts.isEmpty()) break

                    val batchResults = coroutineScope {
                        bucketStarts.map { bStart ->
                            val iso = synchronized(isoFmt) { isoFmt.format(Date(bStart)) }
                            async {
                                bStart to repository.getChatHistoryForVod(chId, iso)
                            }
                        }.awaitAll()
                    }

                    if (expectedGen != syncGeneration.get() || (!isVodMode && !isLiveDvrChatSyncActive)) break

                    var hitFailure = false
                    for ((bStart, result) in batchResults) {
                        if (result.isFailure) {
                            Log.w(TAG, "VOD 5s bucket fetch failed at $bStart: ${result.exceptionOrNull()?.message}")
                            hitFailure = true
                            break
                        }

                        val messages = result.getOrNull()?.messages ?: emptyList()
                        val newMessages = messages.filter { !fetchedMessageIds.contains(it.id) }

                        if (newMessages.isNotEmpty()) {
                            // Spread messages sharing identical whole-second timestamps across 0..850ms
                            // so rapid chat bursts flow organically at 4fps rather than clumping on .000s
                            val byCreatedMs = newMessages.groupBy { it.createdAt }
                            for ((tsMs, group) in byCreatedMs) {
                                val hasExactSubSecond = (tsMs % 1000L) != 0L
                                val count = group.size
                                group.forEachIndexed { idx, msg ->
                                    fetchedMessageIds.add(msg.id)
                                    val spreadMs = if (!hasExactSubSecond && count > 1) {
                                        (idx * 850L) / count
                                    } else {
                                        0L
                                    }
                                    val adjusted = if (spreadMs > 0L) msg.copy(createdAt = tsMs + spreadMs) else msg
                                    val bucketSec = ((adjusted.createdAt - startMs) / 1000L).coerceAtLeast(0L)
                                    chatBucketsBySecond.computeIfAbsent(bucketSec) {
                                        CopyOnWriteArrayList()
                                    }.add(adjusted)
                                }
                            }
                        }

                        val bucketEndMs = bStart + 5_000L
                        nextBucketMs = bucketEndMs
                        bufferedUntilEpochMs = maxOf(bufferedUntilEpochMs, bucketEndMs)
                    }

                    if (hitFailure) break
                    delay(90L)
                }
            } catch (ce: CancellationException) {
                throw ce
            } catch (e: Exception) {
                Log.e(TAG, "VOD lookahead error: ${e.message}", e)
            } finally {
                if (expectedGen == syncGeneration.get()) {
                    isFetchingLookahead = false
                }
            }
        }
    }

    fun saveWatchProgress() {
        if (!isVodMode) return
        val video = currentVideo ?: return
        val channel = currentChannel ?: return
        val player = activity.ivsPlayer ?: return

        val pos = player.position
        val dur = player.duration.takeIf { it > 0L } ?: (video.duration ?: 0L)
        if (pos <= 5_000L || dur <= 0L || dur - pos < 15_000L) return

        val videoId = video.uuid ?: video.id?.toString() ?: return
        val state = AppPreferences.VodWatchState(
            videoId = videoId,
            videoTitle = video.sessionTitle ?: "VOD",
            thumbnailUrl = video.thumbnail?.src ?: channel.thumbnailUrl,
            duration = dur,
            watchedDuration = pos,
            sourceUrl = video.source,
            channelName = channel.username,
            channelSlug = channel.slug,
            profilePic = channel.getEffectiveProfilePicUrl(),
            categoryName = video.categories?.firstOrNull()?.name ?: channel.categoryName,
            categorySlug = video.categories?.firstOrNull()?.slug ?: channel.categorySlug,
            channelId = (vodChannelId.takeIf { it > 0L }?.toString() ?: channel.id)
        )
        activity.prefs.saveVodProgress(state)
    }

    private fun isBotUsername(username: String?): Boolean {
        if (username.isNullOrBlank()) return false
        return KNOWN_BOT_NAMES.contains(username.trim().lowercase())
    }

    /**
     * Reads `#EXT-X-PROGRAM-DATE-TIME:` from the first variant HLS playlist for millisecond-exact
     * alignment between segment 0.000s and historical Kick chat timestamps.
     */
    private suspend fun fetchHlsProgramStartTimeMs(masterUrl: String): Long? = withContext(Dispatchers.IO) {
        try {
            val masterReq = Request.Builder().url(masterUrl).get().build()
            val masterBody = RetrofitClient.okHttpClient.newCall(masterReq).execute().use { resp ->
                if (!resp.isSuccessful) return@withContext null
                resp.body?.string()
            } ?: return@withContext null

            val variantLine = masterBody.lineSequence()
                .map { it.trim() }
                .firstOrNull { it.isNotEmpty() && !it.startsWith("#") }
                ?: return@withContext null

            val variantUrl = if (variantLine.startsWith("http")) {
                variantLine
            } else {
                val base = masterUrl.substringBeforeLast("/")
                "$base/$variantLine"
            }

            val variantReq = Request.Builder().url(variantUrl).get().build()
            val variantBody = RetrofitClient.okHttpClient.newCall(variantReq).execute().use { resp ->
                if (!resp.isSuccessful) return@withContext null
                resp.body?.string()
            } ?: return@withContext null

            val prefix = "#EXT-X-PROGRAM-DATE-TIME:"
            val dateLine = variantBody.lineSequence()
                .firstOrNull { it.startsWith(prefix) }
                ?: return@withContext null

            val iso = dateLine.removePrefix(prefix).trim()
            DateParseUtils.parseIsoDateWithMs(iso).takeIf { it > 0L }
        } catch (e: Exception) {
            Log.w(TAG, "HLS EXT-X-PROGRAM-DATE-TIME probe skipped: ${e.message}")
            null
        }
    }
}
