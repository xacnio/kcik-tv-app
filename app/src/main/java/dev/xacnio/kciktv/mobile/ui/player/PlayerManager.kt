/**
 * File: PlayerManager.kt
 *
 * Description: Core manager for the Amazon IVS Player instance.
 * It handles player initialization, listener registration, state changes (Idle, Buffering, Playing),
 * error handling, quality selection logic, and audio effect processing (DynamicsProcessing).
 *
 * Author: Xacnio
 *
 */
package dev.xacnio.kciktv.mobile.ui.player

import android.content.Context
import android.media.audiofx.DynamicsProcessing
import android.media.audiofx.NoiseSuppressor
import android.media.audiofx.AutomaticGainControl
import android.media.audiofx.AcousticEchoCanceler
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.View
import android.view.WindowManager
import com.amazonaws.ivs.player.Player
import com.amazonaws.ivs.player.Quality
import dev.xacnio.kciktv.mobile.MobilePlayerActivity
import dev.xacnio.kciktv.R
import dev.xacnio.kciktv.shared.data.prefs.AppPreferences
import dev.xacnio.kciktv.databinding.ActivityMobilePlayerBinding
import java.nio.ByteBuffer
import dev.xacnio.kciktv.shared.data.model.ChannelItem
import androidx.core.net.toUri
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import com.bumptech.glide.Glide
import dev.xacnio.kciktv.shared.data.repository.ChannelRepository
import androidx.lifecycle.LifecycleCoroutineScope
import dev.xacnio.kciktv.mobile.BlerpBottomSheetFragment
import android.net.Uri

class PlayerManager(
    private val activity: MobilePlayerActivity,
    private val binding: ActivityMobilePlayerBinding,
    private val prefs: AppPreferences,
    private val repository: ChannelRepository,
    private val lifecycleScope: LifecycleCoroutineScope
) {
    private val TAG = "PlayerManager"
    private val mainHandler = Handler(Looper.getMainLooper())

    var ivsPlayer: Player? = null
    var manualMaxQuality: Quality? = null
    var userSelectedQualityLimit: Quality? = null
    var forcedQualityLimit: String? = null
    var shouldSeekToLiveEdge = false
    var externalListener: Player.Listener? = null
    // Audio Processing - Only EQ works with IVS Player
    private var audioProcessor: DynamicsProcessing? = null
    private var activeSessionId = 0
    
    // Track loading operations so they can be cancelled
    private var loadStreamJob: Job? = null

    private val playerListener = object : Player.Listener() {
        override fun onStateChanged(state: Player.State) {
            activity.runOnUiThread {
                when (state) {
                    Player.State.PLAYING -> {
                        activity.hideLoading()
                        binding.playerView.visibility = View.VISIBLE
                        binding.offlineBanner.visibility = View.GONE
                        binding.errorOverlay.visibility = View.GONE
                        binding.playPauseButton.setImageResource(R.drawable.ic_pause)

                        // Keep screen on while playing
                        activity.window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

                        // Check mobile data quality limit
                        checkAndApplyQualityLimit()

                        // Update background audio & MediaSession
                        activity.playbackNotificationManager.setBackgroundAudioEnabled(true)
                        activity.updateMediaSessionState()

                        // Apply audio processing
                        if (!CustomEqDialogManager(activity, prefs).allEqSettingsFlat()) {
                            updateAudioProcessing()
                        }

                        // If we just loaded DVR, seek to live edge
                        if (shouldSeekToLiveEdge) {
                            ivsPlayer?.let { player ->
                                val duration = player.duration
                                if (duration > 0) {
                                    val targetPosition = (duration - 10000).coerceAtLeast(0)
                                    Log.d(TAG, "Seeking to 10s before live edge: $targetPosition ms")
                                    player.seekTo(targetPosition)
                                    shouldSeekToLiveEdge = false

                                    mainHandler.postDelayed({
                                        activity.updateSeekBarProgress()
                                    }, 200)
                                }
                            }
                        }
                    }
                    Player.State.BUFFERING -> {
                        hideInternalSpinners(binding.playerView)
                        binding.playerView.visibility = View.VISIBLE
                        binding.loadingIndicator.visibility = View.VISIBLE
                        binding.loadingOverlay.visibility = View.GONE
                    }
                    Player.State.IDLE -> {
                        activity.hideLoading()
                        binding.playPauseButton.setImageResource(R.drawable.ic_play_arrow)
                        activity.window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                    }
                    Player.State.ENDED -> {
                        activity.hideLoading()
                        binding.playPauseButton.setImageResource(R.drawable.ic_play_arrow)
                    }
                    else -> {}
                }
                
                // Update PIP params so setAutoEnterEnabled reflects current state
                activity.updatePiPUi()
            }
        }

        override fun onError(exception: com.amazonaws.ivs.player.PlayerException) {
            activity.runOnUiThread {
                Log.e(TAG, "Player Error: ${exception.message} (${exception.code})")

                // Log analytics event (anonymous - just error type/code)
                activity.analytics.logError("player_error_${exception.code}")

                if (exception.code == 404 || exception.message?.contains("Failed to load playlist", ignoreCase = true) == true) {
                    val channel = activity.currentChannel
                    showOfflineState(channel?.offlineBannerUrl, null, channel?.getEffectiveOfflineBannerUrl())
                } else {
                    binding.errorOverlay.visibility = View.VISIBLE
                    binding.errorText.text = activity.getString(R.string.error_format, exception.message)
                    binding.retryButton.visibility = View.VISIBLE
                    binding.loadingIndicator.visibility = View.GONE
                    binding.playerView.visibility = View.INVISIBLE
                    activity.window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                }
            }
        }

        override fun onQualityChanged(quality: Quality) {}
        override fun onCue(cue: com.amazonaws.ivs.player.Cue) {}
        override fun onDurationChanged(duration: Long) {}
        override fun onMetadata(type: String, data: ByteBuffer) {}
        override fun onRebuffering() {}
        override fun onSeekCompleted(position: Long) {}
        override fun onVideoSizeChanged(width: Int, height: Int) {
            if (width <= 0 || height <= 0) return
            activity.runOnUiThread {
                activity.fullscreenToggleManager.updateVideoSize(width, height)
                
                // Auto-enter Theatre Mode for Portrait (Only once per session)
                if (!activity.fullscreenToggleManager.hasAutoEnteredTheatreMode) {
                    val isPortrait = height > width
                    if (isPortrait && !activity.fullscreenToggleManager.isTheatreMode) {
                         activity.fullscreenToggleManager.hasAutoEnteredTheatreMode = true
                         activity.fullscreenToggleManager.enterTheatreMode()
                    }
                }
            }
        }
    }

    fun setupPlayer() {
        binding.playerView.controlsEnabled = false
        resetPlayer()
    }

    fun resetPlayer() {
        try {
            forcedQualityLimit = null
            manualMaxQuality = null
            
            ivsPlayer?.removeListener(playerListener)
            ivsPlayer?.pause()
            
            // Safe access - may not be initialized during onCreate
            activity.isBackgroundAudioEnabled = false
            androidx.core.app.NotificationManagerCompat.from(activity).cancel(101)

            binding.playerView.visibility = View.INVISIBLE
            binding.errorOverlay.visibility = View.GONE
            binding.offlineBanner.visibility = View.GONE
            binding.retryButton.visibility = View.GONE
            activity.isErrorStateActive = false

            ivsPlayer = binding.playerView.player
            binding.playerView.controlsEnabled = false

            val isAutoDesired = userSelectedQualityLimit == null || prefs.dynamicQualityEnabled
            ivsPlayer?.isAutoQualityMode = isAutoDesired

            userSelectedQualityLimit?.let { if (!isAutoDesired) ivsPlayer?.quality = it }

            // Default buffer strategy: Low Latency for Live, High Buffer for VOD/Clip/DVR
            val isLiveMode = activity.vodManager.currentPlaybackMode == dev.xacnio.kciktv.mobile.ui.player.VodManager.PlaybackMode.LIVE && activity.dvrPlaybackUrl == null
            ivsPlayer?.setRebufferToLive(isLiveMode)
            ivsPlayer?.setLooping(false)
            
            if (!isLiveMode) {
                // Increase buffer size indirectly by disabling jump-to-live logic
                Log.d(TAG, "High buffer mode enabled (VOD/DVR/Clip)")
            }
            ivsPlayer?.playbackRate = 1.0f
            ivsPlayer?.addListener(playerListener)
            externalListener?.let { ivsPlayer?.addListener(it) }
            
            ivsPlayer?.addListener(object : Player.Listener() {
                override fun onStateChanged(state: Player.State) {
                     if (state == Player.State.PLAYING) {
                         binding.root.postDelayed({ updateAudioProcessing() }, 500)
                         // Handle DVR seek if pending
                         activity.liveDvrManager.onPlayerPlaying()
                         
                         // Start progress updater to refresh UI (seekbar, timers)
                         activity.playbackControlManager.startProgressUpdater()
                     }
                }
                override fun onCue(cue: com.amazonaws.ivs.player.Cue) {}
                override fun onDurationChanged(duration: Long) {}
                override fun onError(exception: com.amazonaws.ivs.player.PlayerException) {}
                override fun onMetadata(type: String, data: ByteBuffer) {}
                override fun onQualityChanged(quality: Quality) {}
                override fun onRebuffering() {}
                override fun onSeekCompleted(position: Long) {}
                override fun onVideoSizeChanged(width: Int, height: Int) {}
            })

            checkAndApplyQualityLimit()
            hideInternalSpinners(binding.playerView)
        } catch (e: Exception) {
            Log.e(TAG, "Error resetting player", e)
        }
    }

    fun checkAndApplyQualityLimit() {
         val forced = forcedQualityLimit
         val mobileLimitPref = prefs.mobileQualityLimit
         
         val systemLimitHeight = when {
             forced != null -> forced.replace("p", "").toIntOrNull()
             isUsingMobileData() && mobileLimitPref != "none" -> mobileLimitPref.replace("p", "").toIntOrNull()
             else -> null
         }

         val userLimitHeight = userSelectedQualityLimit?.height
         val effectiveLimitHeight = when {
             systemLimitHeight != null && userLimitHeight != null -> Math.min(systemLimitHeight, userLimitHeight)
             systemLimitHeight != null -> systemLimitHeight
             userLimitHeight != null -> userLimitHeight
             else -> null
         }

         if (effectiveLimitHeight == null) {
             manualMaxQuality = null
             ivsPlayer?.setAutoMaxQuality(null)
             return
         }

         val availableQualities = ivsPlayer?.qualities ?: return
         availableQualities
             .filter { 
                 // Use short edge for limit comparison to handle Portrait video (9:16) correctly
                 // "360p" should mean 360 short edge (e.g. 640x360 or 360x640)
                 // If we strictly checked height, 360x640 (Portrait) has height 640 > 360, so it would be filtered out!
                 val shortEdge = Math.min(it.width, it.height)
                 shortEdge <= effectiveLimitHeight 
             }
             .maxByOrNull { it.height } // Still pick the highest resolution available within that limit
             ?.let { targetQuality ->
                 if (targetQuality != manualMaxQuality || (ivsPlayer?.quality?.height ?: 0) > effectiveLimitHeight) {
                      manualMaxQuality = targetQuality
                      ivsPlayer?.setAutoMaxQuality(targetQuality)
                      // Only switch mode if we are effectively exceeding the limit
                      if (ivsPlayer?.isAutoQualityMode == false && (ivsPlayer?.quality?.height ?: 0) > effectiveLimitHeight) {
                           ivsPlayer?.isAutoQualityMode = true
                      }
                 }
             }
    }

    fun updateAudioProcessing() {
        // DynamicsProcessing is only available on Android 9 (API 28) and above
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) {
            return
        }

        val sessionId = ivsPlayer?.audioSessionId ?: 0
        if (sessionId == 0) {
            Log.w(TAG, "Cannot update audio processing: audioSessionId is 0")
            return
        }

        // Only release effects when Session ID changed (new player instance)
        val needsReinit = activeSessionId != sessionId
        
        if (needsReinit) {
            Log.d(TAG, "Releasing audio effects: needsReinit=$needsReinit")
            try {
                audioProcessor?.enabled = false
                audioProcessor?.release()
                audioProcessor = null
            } catch (e: Exception) {
                Log.e(TAG, "Audio Cleanup Failed", e)
            }
            activeSessionId = sessionId
        }
        
        try {
            // DynamicsProcessing (EQ) - The only effect that works with IVS Player
            if (audioProcessor == null) {
                val config = DynamicsProcessing.Config.Builder(
                    DynamicsProcessing.VARIANT_FAVOR_FREQUENCY_RESOLUTION, 
                    1, true, 5, true, 1, false, 0, true
                ).build()
                audioProcessor = DynamicsProcessing(10000, sessionId, config)
                audioProcessor?.enabled = true
                Log.d(TAG, "DynamicsProcessing created for session $sessionId")
            }
            
            // ALWAYS update EQ parameters - this runs every time regardless of other effects
            audioProcessor?.let { processor ->
                if (!processor.enabled) processor.enabled = true
                
                val limiter = DynamicsProcessing.Limiter(true, true, 0, 1f, 50f, 10f, -0.1f, 0f)
                processor.setLimiterAllChannelsTo(limiter)
                processor.setInputGainAllChannelsTo(prefs.eqPreAmpGain)
                
                val eq = DynamicsProcessing.Eq(true, true, 5)
                eq.getBand(0).apply { cutoffFrequency = 60f; gain = prefs.eqBassGain }
                eq.getBand(1).apply { cutoffFrequency = 230f; gain = prefs.eqLowMidGain }
                eq.getBand(2).apply { cutoffFrequency = 910f; gain = prefs.eqMidGain }
                eq.getBand(3).apply { cutoffFrequency = 4000f; gain = prefs.eqHighMidGain }
                eq.getBand(4).apply { cutoffFrequency = 14000f; gain = prefs.eqTrebleGain }
                processor.setPreEqAllChannelsTo(eq)
                
                Log.d(TAG, "EQ updated: PreAmp=${prefs.eqPreAmpGain}, Bass=${prefs.eqBassGain}, LowMid=${prefs.eqLowMidGain}, Mid=${prefs.eqMidGain}, HighMid=${prefs.eqHighMidGain}, Treble=${prefs.eqTrebleGain}")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Audio Init Failed", e)
        }
    }

    private fun isUsingMobileData(): Boolean {
        return try {
            val cm = activity.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            val activeNetwork = cm.activeNetwork ?: return false
            val caps = cm.getNetworkCapabilities(activeNetwork) ?: return false
            caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)
        } catch (e: Exception) {
            false
        }
    }

    /**
     * Cancels any ongoing stream loading operations.
     * Call this before loading a new stream or when stopping the player.
     */
    fun cancelLoadingOperations() {
        loadStreamJob?.cancel()
        loadStreamJob = null
    }
    
    fun loadStreamUrl(channel: ChannelItem) {
        // Cancel any previous loading operation
        cancelLoadingOperations()
        
        activity.isErrorStateActive = false
        resetPlayer()
        activity.vodManager.currentPlaybackMode = dev.xacnio.kciktv.mobile.ui.player.VodManager.PlaybackMode.LIVE
        activity.dvrPlaybackUrl = null
        
        loadStreamJob = lifecycleScope.launch {
            activity.currentBlerpUrl = null
            activity.blerpCleanupHandler.removeCallbacks(activity.blerpCleanupRunnable)
            activity.cachedBlerpFragment?.let { if (it.isAdded) it.dismissAllowingStateLoss() }
            activity.cachedBlerpFragment = null
            activity.runOnUiThread { binding.blerpButton.visibility = View.GONE }

            // Fetch Blerp URL using new GraphQL API
            repository.getBlerpUrl(channel.slug).onSuccess { blerpUrl ->
                if (!blerpUrl.isNullOrEmpty()) {
                    activity.currentBlerpUrl = blerpUrl
                    activity.runOnUiThread { binding.blerpButton.visibility = if (prefs.blerpEnabled) View.VISIBLE else View.GONE }
                }
            }

            var apiPlaybackUrl: String? = null
            try {
                repository.getChannelDetails(channel.slug).onSuccess { details ->
                    val isLive = details.livestream?.isLive == true
                    apiPlaybackUrl = details.playbackUrl
                    
                    val lsId = details.livestream?.id
                    if (lsId != null) {
                        try {
                            val videos = repository.getChannelVideos(channel.slug).getOrNull()
                            val matched = videos?.find { it.id == lsId }
                            Log.d(TAG, "Rewind matched video: ${matched?.uuid} (lsId: $lsId)")
                            activity.dvrPlaybackUrl = matched?.source
                            activity.dvrVideoUuid = matched?.video?.uuid ?: matched?.uuid
                            if (activity.dvrPlaybackUrl != null) {
                                activity.runOnUiThread {
                                    // Trigger immediate UI update to show seekbar
                                    activity.playbackControlManager.updateSeekBarProgress()
                                }
                            }
                        } catch (e: Exception) {
                            Log.e(TAG, "Rewind check failed", e)
                        }
                    }
                    
                    activity.runOnUiThread {
                        activity.isSubscriptionEnabled = details.subscriptionEnabled == true
                        activity.updateChatroomHint(details.chatroom)
                        details.livestream?.startTime?.let { startTimeStr ->
                            try {
                                val sdf = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.US).apply { timeZone = java.util.TimeZone.getTimeZone("UTC") }
                                sdf.parse(startTimeStr)?.time?.let { startTime ->
                                    activity.playbackStatusManager.streamCreatedAtMillis = startTime
                                    activity.playbackStatusManager.startUptimeUpdater()
                                    binding.streamTimeBadge.visibility = View.VISIBLE
                                    binding.uptimeDivider.visibility = View.VISIBLE
                                }
                            } catch (e: Exception) { Log.e(TAG, "Error parsing start time", e) }
                        }
                        
                        details.livestream?.id?.let { lsIdValue ->
                            if (activity.currentLivestreamId != lsIdValue) {
                                activity.currentLivestreamId = lsIdValue
                                activity.viewerCountHandler.removeCallbacks(activity.viewerCountRunnable)
                                activity.viewerCountHandler.post(activity.viewerCountRunnable)
                            }
                        }
                        
                        val tagsList = mutableListOf<String>()
                        details.livestream?.langIso?.let { lang ->
                            val loc = activity.getLocalizedLanguageName(lang)
                            if (loc.isNotEmpty()) tagsList.add(loc)
                        }
                        details.livestream?.tags?.take(4)?.let { tagsList.addAll(it) }
                        if (tagsList.isNotEmpty()) {
                            binding.infoTagsText.text = tagsList.joinToString(" • ")
                            binding.infoTagsText.isSelected = true
                        }
                        
                        details.livestream?.sessionTitle?.let { if (it.isNotEmpty()) { binding.infoStreamTitle.text = it; binding.infoStreamTitle.isSelected = true } }
                    }

                    if (!isLive) {
                        showOfflineState(details.offlineBannerImage?.url, details.offlineBannerImage?.src, channel.offlineBannerUrl, details.offlineBannerImage?.srcset)
                    }
                }
            } catch (e: Exception) { Log.e(TAG, "Error fetching details", e) }

            try {
                val url = apiPlaybackUrl ?: repository.getStreamUrl(channel.slug).getOrNull() ?: channel.getStreamUrl()
                if (url == null) {
                    activity.runOnUiThread {
                        showOfflineState(channel.offlineBannerUrl, null, channel.getEffectiveOfflineBannerUrl())
                    }
                } else {
                    activity.runOnUiThread {
                        activity.currentStreamUrl = url
                        ivsPlayer?.load(url.toUri())
                        ivsPlayer?.play()
                    }
                }
            } catch (e: Exception) { Log.e(TAG, "Error loading stream", e) }
        }
    }

    fun showOfflineState(bannerUrl: String?, bannerSrc: String?, defaultBannerUrl: String?, bannerSrcset: String? = null) {
        activity.runOnUiThread {
            activity.hideLoading()
            activity.isErrorStateActive = true
            binding.errorText.text = activity.getString(R.string.stream_offline)
            activity.showOverlay()
            
            var finalBanner = bannerUrl ?: bannerSrc
            if (!bannerSrcset.isNullOrEmpty()) {
                try {
                    val sources = bannerSrcset.split(",")
                    if (sources.isNotEmpty()) {
                        val lastSource = sources.last().trim()
                        val url = lastSource.split(" ").firstOrNull()
                        if (!url.isNullOrEmpty()) finalBanner = url
                    }
                } catch (e: Exception) { Log.e(TAG, "Error parsing srcset", e) }
            }
            
            val finalUrl = finalBanner ?: defaultBannerUrl ?: "https://kick.com/img/default-channel-banners/offline-banner.webp"
            binding.offlineBanner.visibility = View.VISIBLE
            Glide.with(activity).load(finalUrl).into(binding.offlineBanner)
            binding.playerView.visibility = View.GONE
            ivsPlayer?.pause()
        }
    }

    private fun hideInternalSpinners(view: View) {
        if (view is android.widget.ProgressBar) {
            view.visibility = View.GONE
        } else if (view is android.view.ViewGroup) {
            for (i in 0 until view.childCount) {
                hideInternalSpinners(view.getChildAt(i))
            }
        }
    }
}
