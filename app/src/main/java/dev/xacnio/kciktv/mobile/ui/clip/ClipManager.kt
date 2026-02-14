/**
 * File: ClipManager.kt
 *
 * Description: Handles the workflow for creating and editing clips.
 * It manages the bottom sheet interface for clip generation, including the WebView-based
 * authentication bypass for KPSDK validation and the subsequent clip trimming editor.
 *
 * Author: Xacnio
 *
 */
package dev.xacnio.kciktv.mobile.ui.clip

import android.content.Context
import android.util.Log
import android.view.View
import android.widget.Toast
import androidx.lifecycle.LifecycleCoroutineScope
import com.amazonaws.ivs.player.Player
import com.bumptech.glide.Glide
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog
import dev.xacnio.kciktv.mobile.MobilePlayerActivity
import dev.xacnio.kciktv.R
import dev.xacnio.kciktv.shared.data.model.ChannelItem
import dev.xacnio.kciktv.shared.data.model.ClipResponse
import dev.xacnio.kciktv.shared.data.prefs.AppPreferences
import dev.xacnio.kciktv.shared.data.repository.ChannelRepository
import dev.xacnio.kciktv.databinding.ActivityMobilePlayerBinding
import dev.xacnio.kciktv.shared.ui.widget.ShimmerLayout
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

import dev.xacnio.kciktv.mobile.LoginActivity
import dev.xacnio.kciktv.shared.data.model.ChannelClip
import dev.xacnio.kciktv.shared.util.DateParseUtils

class ClipManager(
    private val activity: MobilePlayerActivity,
    private val binding: ActivityMobilePlayerBinding,
    private val prefs: AppPreferences,
    private val repository: ChannelRepository,
    private val lifecycleScope: LifecycleCoroutineScope
) {
    private var clipEditorShown = false
    private var clipLoadingDialog: BottomSheetDialog? = null
    private var isClipLoadingCancelled = false

    fun handleClipRequest(channel: ChannelItem) {
        // Reset cancellation flag
        isClipLoadingCancelled = false
        
        // Immediately show bottom sheet with shimmer loading
        val dialog = BottomSheetDialog(activity, R.style.BottomSheetDialogTheme)
        val sheetView = activity.layoutInflater.inflate(R.layout.bottom_sheet_create_clip, null)
        dialog.setContentView(sheetView)
        
        // Force expanded state
        dialog.behavior.state = BottomSheetBehavior.STATE_EXPANDED
        dialog.behavior.skipCollapsed = true
        dialog.behavior.isDraggable = false // Prevent accidental drag during loading
        
        val displayMetrics = activity.resources.displayMetrics
        dialog.behavior.peekHeight = displayMetrics.heightPixels
        
        // Store reference for cleanup
        clipLoadingDialog = dialog
        
        // Setup dismiss listener - cancels loading if user closes during shimmer
        dialog.setOnDismissListener {
            if (!clipEditorShown) {
                // User closed during loading - cancel everything
                isClipLoadingCancelled = true
                Log.d("ClipManager", "Clip loading cancelled by user")
                
                // Unmute live stream
                activity.ivsPlayer?.isMuted = false
                
                // Reset button state (if applicable - might need to expose a method in Activity)
                // Assuming we can access binding directly
                binding.clipButton.visibility = View.VISIBLE
                binding.clipButtonSpinner.visibility = View.GONE
            }
            clipLoadingDialog = null
        }
        
        dialog.show()
        
        // Start loading in background
        lifecycleScope.launch {
            var livestreamSlug = channel.livestreamSlug

            if (livestreamSlug.isNullOrEmpty()) {
                // Try to fetch latest details if slug is missing
                repository.getChannelDetails(channel.slug).onSuccess { details ->
                    livestreamSlug = details.livestream?.slug
                }
            }
            
            // Check if cancelled
            if (isClipLoadingCancelled) return@launch
            
            Log.d("ClipManager", "Resolved Livestream slug: $livestreamSlug")

            val isVodActiveCheck = activity.isVodPlaying && activity.currentVodUuid != null
            val isDvrActiveCheck = activity.dvrPlaybackUrl != null && activity.currentStreamUrl == activity.dvrPlaybackUrl && activity.dvrVideoUuid != null

            if (livestreamSlug.isNullOrEmpty() && !isVodActiveCheck && !isDvrActiveCheck) {
                withContext(Dispatchers.Main) {
                    dialog.dismiss()
                    Toast.makeText(activity, activity.getString(R.string.clip_only_live), Toast.LENGTH_SHORT).show()
                }
                return@launch
            }

            val token = prefs.authToken
            if (token.isNullOrEmpty()) {
                withContext(Dispatchers.Main) {
                    dialog.dismiss()
                    Toast.makeText(activity, activity.getString(R.string.clip_login_required), Toast.LENGTH_SHORT).show()
                }
                return@launch
            }

            // Mute live stream
            activity.ivsPlayer?.isMuted = true

            // Use WebView Bypass for KPSDK (Cloudflare)
            withContext(Dispatchers.Main) {
                val isDvrActive = activity.dvrPlaybackUrl != null && activity.currentStreamUrl == activity.dvrPlaybackUrl && activity.dvrVideoUuid != null
                val isVodActive = activity.isVodPlaying && activity.currentVodUuid != null

                if (isDvrActive) {
                     val posMs = activity.ivsPlayer?.position ?: 0L
                     val startSeconds = (posMs / 1000).toInt()
                     Log.d("ClipManager", "Creating DVR Clip: uuid=${activity.dvrVideoUuid}, start=$startSeconds")
                     createClipViaWebView(activity.dvrVideoUuid!!, token, dialog, sheetView, true, startSeconds, activity.currentChannel?.slug ?: "")
                } else if (isVodActive) {
                     val posMs = activity.ivsPlayer?.position ?: 0L
                     val startSeconds = (posMs / 1000).toInt()
                     Log.d("ClipManager", "Creating VOD Clip: uuid=${activity.currentVodUuid}, start=$startSeconds")
                     createClipViaWebView(activity.currentVodUuid!!, token, dialog, sheetView, true, startSeconds, activity.currentChannel?.slug ?: "")
                } else {
                     createClipViaWebView(livestreamSlug!!, token, dialog, sheetView, false, null, activity.currentChannel?.slug ?: "")
                }
            }
        }
    }

    private fun createClipViaWebView(targetId: String, token: String, dialog: BottomSheetDialog, sheetView: View, isDvrVideo: Boolean, startTimeSeconds: Int? = null, channelSlug: String) {
        val apiUrl = if (isDvrVideo) "https://kick.com/api/internal/v1/videos/$targetId/clips" else "https://kick.com/api/internal/v1/livestreams/$targetId/clips"
        val apiBody = if (isDvrVideo) "{\"start_time\": $startTimeSeconds, \"duration\": 180}" else "{\"duration\": 180}"

        Log.d("ClipManager", "Creating clip via WebViewManager: $apiUrl")

        activity.webViewManager.createClip(apiUrl, apiBody, token) { result ->
            activity.runOnUiThread {
                // Check cancellation
                if (isClipLoadingCancelled) return@runOnUiThread

                result.onSuccess { json ->
                    try {
                        val responseJson = org.json.JSONObject(json)
                        val success = responseJson.optBoolean("ok")
                        val code = responseJson.optInt("code")
                        val body = responseJson.optString("body")

                        if (success && body.isNotEmpty()) {
                             val clipResponse = com.google.gson.Gson().fromJson(body, dev.xacnio.kciktv.shared.data.model.ClipResponse::class.java)
                             
                             // Check class-level flag and cancellation
                             if (!clipEditorShown && !isClipLoadingCancelled) {
                                 Log.d("ClipManager", "Clip loaded successfully, transitioning to editor...")
                                 clipEditorShown = true
                                 
                                 // Transition from shimmer to content
                                 val vodTitle = activity.currentVodTitle
                                 val dvrTitle = activity.currentDvrTitle
                                 val channelTitle = activity.currentChannel?.title
                                 
                                 val title = if (!vodTitle.isNullOrEmpty()) vodTitle
                                             else if (!dvrTitle.isNullOrEmpty()) dvrTitle
                                             else if (!channelTitle.isNullOrEmpty()) channelTitle
                                             else "Clip"

                                 transitionToClipEditor(dialog, sheetView, clipResponse, token, targetId, title, activity.currentChannel?.slug ?: (if (isDvrVideo) "" else targetId), isDvrVideo)
                             }
                        } else {
                            if (!isClipLoadingCancelled) {
                                dialog.dismiss()
                                Toast.makeText(activity, activity.getString(R.string.clip_error, code.toString()), Toast.LENGTH_SHORT).show()
                                activity.ivsPlayer?.isMuted = false
                            }
                        }
                    } catch (e: Exception) {
                        Log.e("ClipManager", "Error parsing clip creation response", e)
                        if (!isClipLoadingCancelled) {
                            dialog.dismiss()
                            Toast.makeText(activity, activity.getString(R.string.clip_error, e.message), Toast.LENGTH_SHORT).show()
                            activity.ivsPlayer?.isMuted = false
                        }
                    }
                }.onFailure { e ->
                    Log.e("ClipManager", "Clip creation failed", e)
                    if (!isClipLoadingCancelled) {
                        dialog.dismiss()
                        Toast.makeText(activity, activity.getString(R.string.clip_error, e.message), Toast.LENGTH_SHORT).show()
                        activity.ivsPlayer?.isMuted = false
                    }
                }
            }
        }
    }

    private fun transitionToClipEditor(
        dialog: com.google.android.material.bottomsheet.BottomSheetDialog,
        sheetView: View,
        clipResponse: dev.xacnio.kciktv.shared.data.model.ClipResponse,
        token: String,
        livestreamSlug: String,
        streamTitle: String,
        channelSlug: String,
        isDvrVideo: Boolean
    ) {
        // ===== TRANSITION: Hide shimmer, show content =====
        val shimmerContainer = sheetView.findViewById<View>(R.id.clipLoadingShimmer)
        val editorContent = sheetView.findViewById<View>(R.id.clipEditorContent)
        
        // Stop shimmer animation if using ShimmerLayout
        (shimmerContainer as? dev.xacnio.kciktv.shared.ui.widget.ShimmerLayout)?.stopShimmer()
        
        // Animate transition
        shimmerContainer?.animate()
            ?.alpha(0f)
            ?.setDuration(200)
            ?.withEndAction {
                shimmerContainer.visibility = View.GONE
                editorContent?.visibility = View.VISIBLE
                editorContent?.alpha = 0f
                editorContent?.animate()
                    ?.alpha(1f)
                    ?.setDuration(200)
                    ?.start()
            }
            ?.start()
        
        // Allow dragging now that content is loaded
        dialog.behavior.isDraggable = true
        
        // Update dismiss listener for editor mode
        dialog.setOnDismissListener(null) // Clear loading dismiss listener
        
        var isTrimming = false
        var isSeeking = false

        // UI Elements
        val playerView = sheetView.findViewById<com.amazonaws.ivs.player.PlayerView>(R.id.clipPlayerView)
        
        // Disable IVS Player's native controls overlay to prevent duplicate controls
        playerView.setControlsEnabled(false)
        val rangeSlider = sheetView.findViewById<com.google.android.material.slider.RangeSlider>(R.id.clipRangeSlider)
        val titleInput = sheetView.findViewById<com.google.android.material.textfield.TextInputEditText>(R.id.clipTitleInput)
        val btnPublish = sheetView.findViewById<android.widget.Button>(R.id.btnPublish)
        val btnCancel = sheetView.findViewById<android.widget.Button>(R.id.btnCancel)
        val durationText = sheetView.findViewById<android.widget.TextView>(R.id.clipDurationText)
        val timeRangeText = sheetView.findViewById<android.widget.TextView>(R.id.clipTimeRangeText)
        val channelName = sheetView.findViewById<android.widget.TextView>(R.id.channelName)
        val loading = sheetView.findViewById<android.widget.ProgressBar>(R.id.clipLoading)
        val closeButton = sheetView.findViewById<android.widget.ImageButton>(R.id.closeButton)
        
        // New custom controls
        val playPauseButton = sheetView.findViewById<android.widget.ImageButton>(R.id.clipPlayPauseButton)
        val clipSeekBar = sheetView.findViewById<android.widget.SeekBar>(R.id.clipSeekBar)
        val clipCurrentTime = sheetView.findViewById<android.widget.TextView>(R.id.clipCurrentTime)
        val clipEndTime = sheetView.findViewById<android.widget.TextView>(R.id.clipEndTime)
        val clipTrimStartTime = sheetView.findViewById<android.widget.TextView>(R.id.clipTrimStartTime)
        val clipTrimEndTime = sheetView.findViewById<android.widget.TextView>(R.id.clipTrimEndTime)
        val seekBarContainer = sheetView.findViewById<android.widget.FrameLayout>(R.id.seekBarContainer)
        val trimStartMarker = sheetView.findViewById<View>(R.id.trimStartMarker)
        val trimEndMarker = sheetView.findViewById<View>(R.id.trimEndMarker)
        val trimRangeHighlight = sheetView.findViewById<View>(R.id.trimRangeHighlight)
        
        channelName.text = activity.getString(R.string.create_clip, activity.currentChannel?.username ?: channelSlug)

        // Format time helper
        fun formatTime(seconds: Float): String {
            val mins = (seconds / 60).toInt()
            val secs = (seconds % 60).toInt()
            return String.format("%d:%02d", mins, secs)
        }

        // Initialize startTime/endTime with safe values
        val totalSourceDur = clipResponse.sourceDuration.toFloat()
        // Default to last 60 seconds
        val defaultStart = (totalSourceDur - 60f).coerceAtLeast(0f)
        var startTime = defaultStart
        var endTime: Float

        // Setup ExoPlayer
        val clipPlayer = playerView.player
        var url: String? = clipResponse.url
        if (url == null) {
            url = clipResponse.clip_editor?.video_sources?.firstOrNull()?.source
        }
        
        if (url == null) {
            Toast.makeText(activity, activity.getString(R.string.clip_url_missing), Toast.LENGTH_SHORT).show()
            dialog.dismiss()
            return
        }
        clipPlayer.load(android.net.Uri.parse(url))
        
        // Play/Pause button logic
        fun updatePlayPauseIcon() {
            val iconRes = if (clipPlayer.state == com.amazonaws.ivs.player.Player.State.PLAYING) R.drawable.ic_pause else R.drawable.ic_play_arrow
            playPauseButton.setImageResource(iconRes)
        }
        
        // Seek to trim start position when ready (only once)
        var initialSeekDone = false
        clipPlayer.addListener(object : com.amazonaws.ivs.player.Player.Listener() {
            override fun onStateChanged(state: com.amazonaws.ivs.player.Player.State) {
                loading.visibility = if (state == com.amazonaws.ivs.player.Player.State.BUFFERING) View.VISIBLE else View.GONE
                
                if (state == com.amazonaws.ivs.player.Player.State.READY && !initialSeekDone) {
                    initialSeekDone = true
                    val playStartSec = startTime 
                    val initialSeekMs = (playStartSec * 1000L).toLong()
                    clipPlayer.seekTo(initialSeekMs)
                    clipPlayer.play()
                    
                    // Force update UI for initial state
                    val totalDuration = clipResponse.sourceDuration.toFloat()
                    val progress = if (totalDuration > 0) (playStartSec / totalDuration * 1000).toInt() else 0
                    clipSeekBar.progress = progress
                    clipCurrentTime.text = formatTime(playStartSec) 
                    clipEndTime.text = formatTime(totalDuration)
                }
                
                if (state == com.amazonaws.ivs.player.Player.State.PLAYING) {
                    updatePlayPauseIcon()
                } else {
                    updatePlayPauseIcon()
                }
            }
            override fun onCue(cue: com.amazonaws.ivs.player.Cue) {}
            override fun onDurationChanged(duration: Long) {}
            override fun onError(exception: com.amazonaws.ivs.player.PlayerException) { Log.e("ClipEditor", "Error: ${exception.message}") }
            override fun onMetadata(type: String, data: java.nio.ByteBuffer) {}
            override fun onQualityChanged(quality: com.amazonaws.ivs.player.Quality) {}
            override fun onRebuffering() {}
            override fun onSeekCompleted(position: Long) {
                // Delay unlocking UI to prevent jump-back
                mainHandlerForClips.postDelayed({ isSeeking = false }, 500)
            }
            override fun onVideoSizeChanged(width: Int, height: Int) {}
        })
        
        // Initial Slider Values (Defensive)
        val totalDur = clipResponse.sourceDuration.toFloat().coerceAtLeast(10f)
        rangeSlider.valueFrom = 0f
        rangeSlider.valueTo = totalDur
        rangeSlider.setMinSeparationValue(10f)
        
        val safeStart = defaultStart.coerceIn(0f, totalDur - 10f)
        val safeEnd = totalDur
        rangeSlider.setValues(safeStart, safeEnd)
        
        startTime = safeStart
        endTime = safeEnd
        
        // Update clip time display based on trim range
        fun updateClipTimeDisplay() {
            clipEndTime.text = formatTime(endTime - startTime)
            clipTrimStartTime.text = formatTime(startTime)
            clipTrimEndTime.text = formatTime(endTime)
        }
        
        // Update trim markers on seekbar
        fun updateTrimMarkers() {
            val doUpdate = {
                val totalDuration = clipResponse.sourceDuration.toFloat()
                val containerWidth = seekBarContainer.width
                val padding = clipSeekBar.paddingStart + clipSeekBar.paddingEnd
                val usableWidth = containerWidth - padding
                
                if (totalDuration > 10f && usableWidth > 0) {
                    val startX = (startTime / totalDuration * usableWidth + clipSeekBar.paddingStart).toInt()
                    val endX = (endTime / totalDuration * usableWidth + clipSeekBar.paddingStart).toInt()
                    
                    // Position start marker
                    val startParams = trimStartMarker.layoutParams as android.widget.FrameLayout.LayoutParams
                    startParams.leftMargin = startX
                    trimStartMarker.layoutParams = startParams
                    
                    // Position end marker
                    val endParams = trimEndMarker.layoutParams as android.widget.FrameLayout.LayoutParams
                    endParams.leftMargin = endX
                    trimEndMarker.layoutParams = endParams
                    
                    // Position highlight range
                    val highlightParams = trimRangeHighlight.layoutParams as android.widget.FrameLayout.LayoutParams
                    highlightParams.leftMargin = startX
                    highlightParams.width = (endX - startX).coerceAtLeast(0)
                    trimRangeHighlight.layoutParams = highlightParams
                }
            }
            
            // Check if layout is complete
            if (seekBarContainer.width > 0) {
                seekBarContainer.post { doUpdate() }
            } else {
                // Layout not complete yet, wait for it
                seekBarContainer.viewTreeObserver.addOnGlobalLayoutListener(object : android.view.ViewTreeObserver.OnGlobalLayoutListener {
                    override fun onGlobalLayout() {
                        seekBarContainer.viewTreeObserver.removeOnGlobalLayoutListener(this)
                        doUpdate()
                    }
                })
            }
        }
        
        // Update texts
        fun updateInfo() {
            val duration = (endTime - startTime).toInt()
            durationText.text = activity.getString(R.string.duration_format, duration)
            
            val startMin = (startTime / 60).toInt()
            val startSec = (startTime % 60).toInt()
            val endMin = (endTime / 60).toInt()
            val endSec = (endTime % 60).toInt()
            timeRangeText.text = String.format("%d:%02d - %d:%02d", startMin, startSec, endMin, endSec)
            
            val isValid = duration in 15..180
            btnPublish.isEnabled = isValid
            btnPublish.alpha = if (isValid) 1.0f else 0.5f
            
            updateClipTimeDisplay()
            updateTrimMarkers()
        }
        
        updateInfo()
        updateInfo()
        // clipPlayer.play() // Handled in listener when READY
        
        playPauseButton.setOnClickListener {
            if (clipPlayer.state == com.amazonaws.ivs.player.Player.State.PLAYING) {
                clipPlayer.pause()
            } else {
                clipPlayer.play()
            }
            updatePlayPauseIcon()
        }
        
        // Player Loop Logic + SeekBar Update (Check every 100ms)
        val progressHandler = android.os.Handler(android.os.Looper.getMainLooper())
        val progressRunnable = object : Runnable {
            override fun run() {
                val currentPos = clipPlayer.position / 1000f
                
                // Loop within range
                if (clipPlayer.state == com.amazonaws.ivs.player.Player.State.PLAYING && (currentPos > endTime + 0.3f || currentPos < startTime - 0.5f)) { 
                    clipPlayer.seekTo((startTime * 1000).toLong())
                }
                
                // Update SeekBar and time display (only if not seeking and not trimming)
                if (!isSeeking && !isTrimming) {
                    val totalDuration = clipResponse.sourceDuration.toFloat()
                    val progress = if (totalDuration > 0) (currentPos / totalDuration * 1000).toInt() else 0
                    
                    // Avoid jitter by only updating if change is significant (>1%) or precise time changed
                    if (kotlin.math.abs(clipSeekBar.progress - progress) > 0) {
                        clipSeekBar.progress = progress
                    }
                    clipCurrentTime.text = formatTime(currentPos)
                }
                
                progressHandler.postDelayed(this, 100)
            }
        }
        progressHandler.post(progressRunnable)
        
        // SeekBar user interaction
        clipSeekBar.setOnSeekBarChangeListener(object : android.widget.SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: android.widget.SeekBar?, progress: Int, fromUser: Boolean) {
                if (fromUser) {
                    val totalDuration = clipResponse.sourceDuration.toFloat()
                    val seekTime = (progress / 1000f) * totalDuration
                    clipCurrentTime.text = formatTime(seekTime)
                }
            }
            
            override fun onStartTrackingTouch(seekBar: android.widget.SeekBar?) {
                isSeeking = true
            }
            
            override fun onStopTrackingTouch(seekBar: android.widget.SeekBar?) {
                seekBar?.let {
                    val totalDuration = clipResponse.sourceDuration.toFloat()
                    val seekTime = (it.progress / 1000f) * totalDuration
                    Log.d("ClipEditor", "clipSeekBar seeking to absolute: $seekTime s")
                    clipPlayer.seekTo((seekTime * 1000).toLong())
                    
                    // Failsafe
                    it.postDelayed({ isSeeking = false }, 2000)
                }
                // isSeeking will be reset in onSeekCompleted
            }
        })
        
        // Slider Logic with Instant Seek
        var lastMovingThumb = 0 // 0: Start, 1: End
        var previousSliderStart = startTime
        var previousSliderEnd = endTime

        rangeSlider.addOnChangeListener { slider, _, fromUser ->
            val values = slider.values
            var start = values[0]
            var end = values[1]
            var correctionNeeded = false
            
            // Detect which thumb moved by comparing with previous values
            val startChanged = kotlin.math.abs(start - previousSliderStart) > 0.01f
            val endChanged = kotlin.math.abs(end - previousSliderEnd) > 0.01f
            
            if (startChanged && !endChanged) {
                lastMovingThumb = 0
            } else if (endChanged && !startChanged) {
                lastMovingThumb = 1
            }
            // If both changed (due to correction), keep previous lastMovingThumb

            // Constraints (10s - 180s)
            val duration = end - start
            if (duration < 10f) {
                if (lastMovingThumb == 1) start = (end - 10f).coerceAtLeast(0f)
                else end = (start + 10f).coerceAtMost(clipResponse.sourceDuration.toFloat())
                
                // Final boundary verification
                if (end - start < 10f) {
                    if (lastMovingThumb == 1) end = (start + 10f).coerceAtMost(clipResponse.sourceDuration.toFloat())
                    else start = (end - 10f).coerceAtLeast(0f)
                }
                correctionNeeded = true
            } else if (duration > 180f) {
                 if (lastMovingThumb == 1) start = end - 180f
                 else end = start + 180f
                 correctionNeeded = true
            }

            if (correctionNeeded) {
                 slider.setValues(start, end)
                 // Update previous values even on correction
                 previousSliderStart = start
                 previousSliderEnd = end
                 return@addOnChangeListener
            }

            // Update state
            startTime = start
            endTime = end
            updateInfo()
            
            // Seek to the thumb position being moved
            if (fromUser) {
                // Seek to the position of the thumb that was actually moved
                val seekPosition = if (lastMovingThumb == 0) start else end
                val seekMs = (seekPosition * 1000).toLong()
                
                // Force frame update
                clipPlayer.seekTo(seekMs)
                
                // Log position
                playerView.postDelayed({
                    Log.d("ClipEditor", "After seek delay: currentPosition=${clipPlayer.position}ms, target=$seekMs")
                }, 100)
                
                // Update video player controls to show ABSOLUTE position during trimming
                val totalDuration = clipResponse.sourceDuration.toFloat()
                val absolutePosition = seekPosition
                
                val absoluteProgress = if (totalDuration > 0) (absolutePosition / totalDuration * 1000).toInt() else 0
                clipSeekBar.progress = absoluteProgress
                clipCurrentTime.text = formatTime(absolutePosition)
                clipEndTime.text = formatTime(totalDuration)
            }
            
            previousSliderStart = start
            previousSliderEnd = end
        }

        rangeSlider.addOnSliderTouchListener(object : com.google.android.material.slider.RangeSlider.OnSliderTouchListener {
            override fun onStartTrackingTouch(slider: com.google.android.material.slider.RangeSlider) {
                isTrimming = true
                clipPlayer.pause()
            }

            override fun onStopTrackingTouch(slider: com.google.android.material.slider.RangeSlider) {
                isTrimming = false
                val values = slider.values
                val seekPos = if (lastMovingThumb == 1) values[1] else values[0]
                val seekMs = (seekPos * 1000).toLong()
                clipPlayer.seekTo(seekMs)
            }
        })
        
        titleInput.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) { updateInfo() }
            override fun afterTextChanged(s: android.text.Editable?) {}
        })
        
        btnCancel.setOnClickListener { dialog.dismiss() }
        closeButton.setOnClickListener { dialog.dismiss() }
        
        btnPublish.setOnClickListener {
            btnPublish.isEnabled = false
            btnPublish.text = activity.getString(R.string.publishing)
            
            val duration = (endTime - startTime).toInt()
            val start = startTime.toInt()
            val inputTitle = titleInput.text.toString().trim()
            val finalTitle = if (inputTitle.isEmpty()) streamTitle else inputTitle
            
            // Escape title for JSON
            val escapedTitle = finalTitle
                .replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "\\r")
            
            // Finalize clip using WebView for KPSDK bypass
            val finalizeUrl = if (isDvrVideo) "https://kick.com/api/internal/v1/videos/$livestreamSlug/clips/${clipResponse.id}/finalize" else "https://kick.com/api/internal/v1/livestreams/$livestreamSlug/clips/${clipResponse.id}/finalize"
            val jsonBody = """{"duration":$duration,"start_time":$start,"title":"$escapedTitle"}"""
            
            Log.d("ClipEditor", "Finalizing Clip: $finalizeUrl")

            activity.webViewManager.finalizeClip(finalizeUrl, jsonBody, token) { result ->
                activity.runOnUiThread {
                     result.onSuccess { json ->
                        try {
                            val responseJson = org.json.JSONObject(json)
                            val success = responseJson.optBoolean("ok")
                            val code = responseJson.optInt("code")
                            val body = responseJson.optString("body")

                            if (success && body.isNotEmpty()) {
                                 val responseObj = org.json.JSONObject(body)
                                 val clipId = responseObj.optString("id")
                                 val thumbnailUrl = responseObj.optString("thumbnail_url")
                                 val durationSecs = responseObj.optInt("duration", 0)
                                 
                                 dialog.dismiss()
                                 showClipCreatedSheet(clipId, channelSlug, thumbnailUrl, durationSecs, escapedTitle)
                            } else {
                                Toast.makeText(activity, activity.getString(R.string.clip_publish_failed, code), Toast.LENGTH_SHORT).show()
                                btnPublish.isEnabled = true
                                btnPublish.text = activity.getString(R.string.publish)
                            }
                        } catch (e: Exception) {
                            Log.e("ClipEditor", "JSON Parse Error during finalize", e)
                            Toast.makeText(activity, activity.getString(R.string.clip_prepared_info_error), Toast.LENGTH_SHORT).show()
                            btnPublish.isEnabled = true
                            btnPublish.text = activity.getString(R.string.publish)
                        }
                     }.onFailure { e ->
                        Log.e("ClipManager", "Clip finalization failed", e)
                        Toast.makeText(activity, activity.getString(R.string.error_format, e.message), Toast.LENGTH_SHORT).show()
                        btnPublish.isEnabled = true
                        btnPublish.text = activity.getString(R.string.publish)
                     }
                }
            }
        }
        
        dialog.setOnDismissListener {
            progressHandler.removeCallbacks(progressRunnable)
            clipPlayer.release()
            activity.ivsPlayer?.isMuted = false
            clipEditorShown = false
            clipLoadingDialog = null
        }
    }

    private fun showClipCreatedSheet(clipId: String, channelSlug: String, thumbnailUrl: String, durationSecs: Int, title: String) {
        val dialog = BottomSheetDialog(activity, R.style.BottomSheetDialogTheme)
        val sheetView = activity.layoutInflater.inflate(R.layout.bottom_sheet_clip_created, null)
        dialog.setContentView(sheetView)

        val clipUrl = "https://kick.com/$channelSlug/clips/$clipId"
        val urlInput = sheetView.findViewById<com.google.android.material.textfield.TextInputEditText>(R.id.clipUrlInput)
        val btnCopy = sheetView.findViewById<android.widget.ImageButton>(R.id.btnCopyUrl)
        val btnShare = sheetView.findViewById<com.google.android.material.button.MaterialButton>(R.id.btnShareClip)
        val btnOpenClip = sheetView.findViewById<com.google.android.material.button.MaterialButton>(R.id.btnOpenClip)
        val btnClose = sheetView.findViewById<android.widget.ImageButton>(R.id.btnCloseSuccess)
        val imgThumbnail = sheetView.findViewById<android.widget.ImageView>(R.id.clipThumbnail)
        val txtDuration = sheetView.findViewById<android.widget.TextView>(R.id.clipDuration)
        
        urlInput.setText(clipUrl)
        
        if (thumbnailUrl.isNotEmpty()) {
            Glide.with(activity)
                .load(thumbnailUrl)
                .placeholder(R.drawable.bg_bottom_sheet)
                .into(imgThumbnail)
        }
        
        val mins = durationSecs / 60
        val secs = durationSecs % 60
        txtDuration.text = String.format("%02d:%02d", mins, secs)
        
        btnCopy.setOnClickListener {
            val clipboard = activity.getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
            val clip = android.content.ClipData.newPlainText("Clip URL", clipUrl)
            clipboard.setPrimaryClip(clip)
            Toast.makeText(activity, activity.getString(R.string.clip_url_copied), Toast.LENGTH_SHORT).show()
        }
        
        btnShare.setOnClickListener {
            val shareIntent = android.content.Intent(android.content.Intent.ACTION_SEND)
            shareIntent.type = "text/plain"
            shareIntent.putExtra(android.content.Intent.EXTRA_TEXT, "$clipUrl")
            activity.startActivity(android.content.Intent.createChooser(shareIntent, activity.getString(R.string.share_clip)))
        }

        btnOpenClip.setOnClickListener {
            // Internal Player Logic
            if (activity.currentChannel != null) {
                dialog.dismiss()
                
                // Use existing channel or dummy if needed
                val currentChan = activity.currentChannel!! 
                
                // Construct a temporary Clip object
                val tempClip = dev.xacnio.kciktv.shared.data.model.ChannelClip(
                    id = clipId,
                    title = title, // Title passed from creation
                    url = clipUrl,
                    thumbnailUrl = thumbnailUrl,
                    views = 0,
                    duration = durationSecs,
                    createdAt = dev.xacnio.kciktv.shared.util.DateParseUtils.toIsoString(System.currentTimeMillis()),
                    creator = null,
                    channel = dev.xacnio.kciktv.shared.data.model.ClipChannel(
                        id = currentChan.id.toLongOrNull() ?: 0L,
                        username = currentChan.username,
                        slug = currentChan.slug,
                        profilePicture = currentChan.profilePicUrl
                    )
                )
                
                // Play
                activity.vodManager.playClip(tempClip, currentChan)
            } else {
                Toast.makeText(activity, activity.getString(R.string.error_occurred), Toast.LENGTH_SHORT).show()
            }
        }

        btnClose.setOnClickListener { dialog.dismiss() }
        dialog.show()
    }

    private val mainHandlerForClips = android.os.Handler(android.os.Looper.getMainLooper())
}
