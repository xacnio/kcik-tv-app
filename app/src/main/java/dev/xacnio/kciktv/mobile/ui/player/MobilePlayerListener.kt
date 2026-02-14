/**
 * File: MobilePlayerListener.kt
 *
 * Description: Implements the IVS Player Listener interface.
 * It handles critical player events including state changes (Playing, Ended), buffering, errors,
 * video size changes, and metadata updates (used for stream uptime and latency calculations).
 *
 * Author: Xacnio
 *
 */
package dev.xacnio.kciktv.mobile.ui.player

import android.os.Build
import android.util.Log
import androidx.lifecycle.lifecycleScope
import com.amazonaws.ivs.player.Cue
import com.amazonaws.ivs.player.Player
import com.amazonaws.ivs.player.PlayerException
import com.amazonaws.ivs.player.Quality
import dev.xacnio.kciktv.mobile.MobilePlayerActivity
import dev.xacnio.kciktv.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MobilePlayerListener(private val activity: MobilePlayerActivity) : Player.Listener() {

    private val TAG = "MobilePlayerListener"

    override fun onStateChanged(state: Player.State) {
        activity.runOnUiThread {
            when (state) {
                Player.State.PLAYING -> {
                    activity.hideLoading()
                    activity.binding.playPauseButton.setImageResource(R.drawable.ic_pause)
                    activity.startProgressUpdater()
                }
                Player.State.ENDED -> {
                    activity.hideLoading()
                    activity.binding.playPauseButton.setImageResource(R.drawable.ic_play_arrow)
                    
                    // Check if we're in VOD mode (DVR playback OR VodManager VOD/CLIP)
                    val isNormalVodMode = activity.vodManager.currentPlaybackMode != dev.xacnio.kciktv.mobile.ui.player.VodManager.PlaybackMode.LIVE
                    val isDvrMode = activity.currentStreamUrl == activity.dvrPlaybackUrl && activity.dvrPlaybackUrl != null
                    val isVodMode = isNormalVodMode || isDvrMode
                    
                    Log.d(TAG, "Player ENDED - mode: ${activity.vodManager.currentPlaybackMode}, isDvr: $isDvrMode, isVodMode: $isVodMode")
                    
                    // Skip VOD logic if we're returning to live
                    if (activity.isReturningToLive) {
                        Log.d(TAG, "Returning to live in progress, ignoring ENDED state")
                        // Don't treat this as VOD end or stream check
                    } else if (isVodMode) {
                        if (isDvrMode) {
                            // Calculate Real Latency to decide strategy
                            val start = activity.playbackStatusManager.streamCreatedAtMillis
                            val playPos = activity.ivsPlayer?.position ?: 0
                            val now = System.currentTimeMillis()
                            val realLatency = if (start != null) (now - start) - playPos else 0
                            
                            // If we are at the edge (latency < 30s), switch to REAL Live Source
                            // This handles "segment waiting" better by using the Live manifest
                            if (realLatency < 30000) {
                                Log.d(TAG, "DVR Ended near live edge -> Switching to Live Source")
                                activity.liveDvrManager.returnToLive()
                            } else {
                                // We are behind, reload DVR to fetch new segments
                                Log.d(TAG, "DVR playback ended behind live - reloading to fetch new segments")
                                activity.showLoading()
                                
                                activity.binding.root.postDelayed({
                                    if (activity.isDestroyed || activity.isFinishing) return@postDelayed
                                    
                                    val url = activity.currentStreamUrl
                                    if (url != null) {
                                        try {
                                            activity.ivsPlayer?.load(android.net.Uri.parse(url))
                                            val resumePos = (playPos - 2000).coerceAtLeast(0)
                                            activity.ivsPlayer?.seekTo(resumePos)
                                            activity.ivsPlayer?.play()
                                        } catch (e: Exception) {
                                            Log.e(TAG, "Failed to reload DVR", e)
                                        }
                                    }
                                }, 2000)
                            }
                        } else {
                            // VOD playback ended - just pause, don't show offline screen
                            Log.d(TAG, "VOD playback ended")
                            // Keep controls visible so user can replay or seek
                            activity.showOverlay()
                        }
                    } else {
                        // Stream ended, verify status (User Logic: 10 tries, 3s interval, stop on 404, resume on body change)
                        val streamUrl = activity.currentStreamUrl
                        if (streamUrl != null) {
                            activity.lifecycleScope.launch {
                                var cachedBody: String? = null
                                val maxAttempts = 10
                                
                                for (attempt in 1..maxAttempts) {
                                    val (code, body) = activity.repository.checkStreamStatus(streamUrl)
                                    Log.d(TAG, "Stream Check Attempt $attempt/$maxAttempts -> Status: $code, BodyLength: ${body?.length}")
                                    
                                    val channel = activity.currentChannel
                                    activity.showOfflineState(null, null, channel?.offlineBannerUrl)
                                    
                                    // 2. Cache first response body
                                    if (attempt == 1) {
                                        cachedBody = body
                                    } else {
                                        // 3. If response body changes (and not 404), stream is likely valid/updated -> continue playing
                                        if (body != cachedBody && code != -1 && code != 404) {
                                            Log.d(TAG, "Analysis: Playlist content changed -> Restarting Playback")
                                            activity.runOnUiThread {
                                                channel?.let { activity.loadStreamUrl(it) }
                                            }
                                            return@launch
                                        }
                                    }
                                    
                                    if (attempt < maxAttempts) {
                                        kotlinx.coroutines.delay(3000L)
                                    }
                                }
                            }
                        }
                    }
                }
                else -> {}
            }
        }
    }

    override fun onError(error: PlayerException) {
        Log.e(TAG, "Player error: ${error.errorMessage} code: ${error.code}")
        activity.runOnUiThread {
            if (error.code == 404 || error.errorMessage.contains("Failed to load playlist", ignoreCase = true) == true) {
                // Don't show technical error, just ensure offline state is visible
                val channel = activity.currentChannel
                // bannerUrl param 1 seems to be unused in original call? original used: showOfflineState(bannerUrl, null, channel?.getEffectiveOfflineBannerUrl())
                // Original: val bannerUrl = channel?.offlineBannerUrl
                // So pass channel?.offlineBannerUrl as first arg
                activity.showOfflineState(channel?.offlineBannerUrl, null, channel?.getEffectiveOfflineBannerUrl())
            } else {
                activity.showError("Player Error: ${error.errorMessage}")
            }
        }
    }

    override fun onCue(cue: Cue) {}
    override fun onDurationChanged(duration: Long) {}
    override fun onQualityChanged(quality: Quality) {
        activity.runOnUiThread {
            activity.binding.videoQualityBadge.text = activity.getString(R.string.video_quality_format, quality.height, quality.framerate.toInt())
            // Verify if new quality respects mobile data limits
            activity.checkAndApplyQualityLimit()
            
            // Update notification with new quality if active
            if (activity.isBackgroundAudioEnabled || (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N && activity.isInPictureInPictureMode)) {
                 activity.showNotification()
            }
        }
    }
    
    override fun onRebuffering() {}
    override fun onSeekCompleted(position: Long) {}
    override fun onVideoSizeChanged(width: Int, height: Int) {
        activity.runOnUiThread {
            // Adjust video container or aspect ratio if needed
        }
    }
    
    override fun onMetadata(type: String, data: java.nio.ByteBuffer) {
        val dataCopy = data.duplicate()
        activity.lifecycleScope.launch(Dispatchers.Default) {
            try {
                val metadataString = java.nio.charset.StandardCharsets.UTF_8.decode(dataCopy).toString()
                if (metadataString.trim().startsWith("{")) {
                    val json = org.json.JSONObject(metadataString)
                    
                    // Direct Uptime Update from Metadata
                    val streamTime = when {
                        json.has("STREAM-TIME") -> json.optDouble("STREAM-TIME", -1.0)
                        json.has("X-STREAM-TIME") -> json.optDouble("X-STREAM-TIME", -1.0)
                        else -> -1.0
                    }
                    
                    if (streamTime >= 0) {
                        withContext(Dispatchers.Main) {
                            activity.playbackStatusManager.onMetadataTimeReceived(streamTime)
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Metadata parse error", e)
            }
        }
    }
}
