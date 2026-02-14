/**
 * File: ClipFeedAdapter.kt
 *
 * Description: RecyclerView Adapter for the vertical Clip Feed (Shorts/TikTok style).
 * It manages the binding of clip video data, surface texture lifecycle for playback,
 * and user interactions such as pause-on-hold, mute toggling, and scaling.
 *
 * Author: Xacnio
 *
 */
package dev.xacnio.kciktv.mobile.ui.feed

import android.graphics.Bitmap
import android.graphics.SurfaceTexture
import android.view.LayoutInflater
import android.view.Surface
import android.view.TextureView
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.SeekBar
import androidx.recyclerview.widget.RecyclerView
import com.amazonaws.ivs.player.Player
import com.bumptech.glide.Glide
import com.bumptech.glide.load.resource.bitmap.CenterCrop
import dev.xacnio.kciktv.R
import dev.xacnio.kciktv.shared.data.model.ClipPlayDetails
import dev.xacnio.kciktv.databinding.LayoutFeedClipBinding
import jp.wasabeef.glide.transformations.BlurTransformation
import dev.xacnio.kciktv.shared.util.Constants

class ClipFeedAdapter(
    private val clips: MutableList<ClipPlayDetails>,
    private val onClipHold: (Boolean) -> Unit, // True for pause, False for resume
    private val onClipTap: () -> Unit, // Mute/Unmute
    private val onShareClick: (ClipPlayDetails) -> Unit,
    private val onFilterClick: () -> Unit,
    private val onChannelClick: (ClipPlayDetails) -> Unit,
    private val onPlayInPlayerClick: (ClipPlayDetails) -> Unit,
    private val onSeekChanged: (Int, Float) -> Unit,  // position, progress (0-1)
    private val onMoreClick: (ClipPlayDetails) -> Unit,
    private val onScaleToggle: () -> Unit,
    private val onViewAttached: (Int, ClipViewHolder) -> Unit,
    private val onViewDetached: ((Int, ClipViewHolder) -> Unit)? = null
) : RecyclerView.Adapter<ClipFeedAdapter.ClipViewHolder>() {

    private var isMuted = false
    var filterName: String = "Popular Clips"
    var subFilterName: String = "Daily"

    fun setMuted(muted: Boolean) {
        isMuted = muted
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ClipViewHolder {
        val binding = LayoutFeedClipBinding.inflate(
            LayoutInflater.from(parent.context),
            parent,
            false
        )
        return ClipViewHolder(binding)
    }

    override fun onBindViewHolder(holder: ClipViewHolder, position: Int) {
        holder.bind(clips[position])
    }

    override fun onBindViewHolder(holder: ClipViewHolder, position: Int, payloads: MutableList<Any>) {
        if (payloads.contains("MUTE_UPDATE")) {
            holder.updateMuteIcon(isMuted, true)
        } else {
            super.onBindViewHolder(holder, position, payloads)
        }
    }

    override fun getItemCount(): Int = clips.size

    override fun onViewAttachedToWindow(holder: ClipViewHolder) {
        super.onViewAttachedToWindow(holder)
        val position = holder.adapterPosition
        if (position != RecyclerView.NO_POSITION) {
            onViewAttached(position, holder)
        }
    }

    override fun onViewDetachedFromWindow(holder: ClipViewHolder) {
        super.onViewDetachedFromWindow(holder)
        val position = holder.adapterPosition
        if (position != RecyclerView.NO_POSITION) {
            onViewDetached?.invoke(position, holder)
        }
    }

    inner class ClipViewHolder(
        val binding: LayoutFeedClipBinding
    ) : RecyclerView.ViewHolder(binding.root) {

        private var currentPlayer: Player? = null
        private var currentSurface: Surface? = null
        private var isPlayerActive = false
        private var clipDuration: Long = 0
        private var isSeeking = false
        private var isFirstFrameRendered = false
        var isCurrentlyFullscreen = false
        private var isOverlayHidden = false
        private var currentCreatorName: String? = null
        
        private val surfaceTextureListener = object : TextureView.SurfaceTextureListener {
            override fun onSurfaceTextureAvailable(surface: SurfaceTexture, width: Int, height: Int) {
                val s = Surface(surface)
                currentSurface = s
                currentPlayer?.let { player ->
                    player.setSurface(s)
                    binding.clipPlayerTexture.alpha = 1f
                }
                applyScaleTransform()
            }

            override fun onSurfaceTextureSizeChanged(surface: SurfaceTexture, width: Int, height: Int) {
                applyScaleTransform()
            }

            override fun onSurfaceTextureDestroyed(surface: SurfaceTexture): Boolean {
                return false  // Keep alive for recycling
            }

            override fun onSurfaceTextureUpdated(surface: SurfaceTexture) {
                if (!isFirstFrameRendered && isPlayerActive) {
                    isFirstFrameRendered = true
                    binding.root.post {
                        binding.clipThumbnail.visibility = View.GONE
                        binding.lastFrameImage.visibility = View.GONE
                    }
                }
            }
        }
        
        /**
         * Sets the video scale mode
         * @param fullscreen true = fit to screen height (centered, 16:9/9:16 - center crop), false = fit width (top/center aligned with margin)
         */
        fun setScaleMode(fullscreen: Boolean) {
            isCurrentlyFullscreen = fullscreen
            val iconRes = if (fullscreen) R.drawable.ic_fullscreen_exit else R.drawable.ic_fullscreen
            binding.ivScaleType.setImageResource(iconRes)
            applyScaleTransform()
        }

        init {
            binding.clipPlayerTexture.surfaceTextureListener = surfaceTextureListener
            
            var startX = 0f
            var startY = 0f
            var isLongPressActive = false
            var isSwiping = false
            val handler = android.os.Handler(android.os.Looper.getMainLooper())
            val slop = android.view.ViewConfiguration.get(binding.root.context).scaledTouchSlop
            
            val longPressRunnable = Runnable {
                if (!isSwiping) {
                    isLongPressActive = true
                    onClipHold(true)
                    toggleOverlay(false)
                }
            }

            binding.root.setOnTouchListener { v, event ->
                when (event.action) {
                    android.view.MotionEvent.ACTION_DOWN -> {
                        startX = event.x
                        startY = event.y
                        isLongPressActive = false
                        isSwiping = false
                        handler.postDelayed(longPressRunnable, 180) // Slightly faster threshold
                    }
                    android.view.MotionEvent.ACTION_MOVE -> {
                        val dx = Math.abs(event.x - startX)
                        val dy = Math.abs(event.y - startY)
                        // Use a slightly larger slop for tap detection to ignore minor jitters
                        if (dx > slop * 1.5f || dy > slop * 1.5f) {
                            if (!isSwiping) {
                                isSwiping = true
                                handler.removeCallbacks(longPressRunnable)
                            }
                        }
                    }
                    android.view.MotionEvent.ACTION_UP, android.view.MotionEvent.ACTION_CANCEL -> {
                        handler.removeCallbacks(longPressRunnable)
                        if (isLongPressActive) {
                            onClipHold(false)
                            isLongPressActive = false
                            toggleOverlay(true)
                        } else if (!isSwiping && event.action == android.view.MotionEvent.ACTION_UP) {
                            // TAP! Call immediately
                            onClipTap()
                        }
                        v.performClick()
                    }
                }
                true
            }

            // Seekbar listener
            binding.clipSeekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                    if (fromUser && clipDuration > 0) {
                        val position = adapterPosition
                        if (position != RecyclerView.NO_POSITION) {
                            onSeekChanged(position, progress / 10000f)
                        }
                    }
                }

                override fun onStartTrackingTouch(seekBar: SeekBar?) {
                    isSeeking = true
                    seekBar?.thumbTintList = android.content.res.ColorStateList.valueOf(android.graphics.Color.parseColor("#53FC18"))
                }

                override fun onStopTrackingTouch(seekBar: SeekBar?) {
                    isSeeking = false
                    seekBar?.thumbTintList = android.content.res.ColorStateList.valueOf(android.graphics.Color.TRANSPARENT)
                }
            })
        }
        
        /**
         * Applies width constraint for tablet/portrait mode.
         * In portrait orientation or on tablets (600dp+), the feed content
         * is centered and constrained to 9:16 aspect ratio for optimal vertical video viewing.
         */
        fun applyFeedWidthConstraint(parentWidth: Int = -1, parentHeight: Int = -1) {
            val context = binding.root.context
            val resources = context.resources
            val displayMetrics = resources.displayMetrics
            
            var screenWidth = parentWidth
            var screenHeight = parentHeight
            
            if (screenWidth <= 0 || screenHeight <= 0) {
                 screenWidth = binding.root.rootView.width
                 screenHeight = binding.root.rootView.height
            }

            if (screenWidth <= 0 || screenHeight <= 0) {
                screenWidth = displayMetrics.widthPixels
                screenHeight = displayMetrics.heightPixels
            }

            val isPortrait = screenHeight > screenWidth
            
            // Check if tablet (sw600dp+)
            val sw600dp = (600 * displayMetrics.density).toInt()
            val minDimension = minOf(screenWidth, screenHeight)
            val isTablet = minDimension >= sw600dp
            
            val wrapper = binding.feedContentWrapper
            val params = wrapper.layoutParams as? FrameLayout.LayoutParams ?: return
            
            if (!isPortrait) {
                // Landscape (Any device): constrain width and show background
                val maxWidth = (screenHeight * 9f / 16f).toInt()
                params.width = minOf(maxWidth, screenWidth)
                params.height = FrameLayout.LayoutParams.MATCH_PARENT
                params.gravity = android.view.Gravity.CENTER
                wrapper.setBackgroundResource(R.drawable.bg_feed_content_wrapper)
            } else if (isTablet) {
                // Portrait Tablet: constrain width and show background
                val maxWidth = (screenHeight * 9f / 16f).toInt()
                params.width = minOf(maxWidth, screenWidth)
                params.height = FrameLayout.LayoutParams.MATCH_PARENT
                params.gravity = android.view.Gravity.CENTER
                wrapper.setBackgroundResource(R.drawable.bg_feed_content_wrapper)
            } else {
                // Phone: use full width, no background
                params.width = FrameLayout.LayoutParams.MATCH_PARENT
                params.height = FrameLayout.LayoutParams.MATCH_PARENT
                params.gravity = android.view.Gravity.CENTER
                wrapper.background = null
            }
            
            wrapper.layoutParams = params
        }

        fun bind(clip: ClipPlayDetails) {
            val context = binding.root.context
            
            // Apply tablet/portrait width constraint for vertical feed
            applyFeedWidthConstraint()
            // Load blurred background
            val defaultThumb = Constants.Urls.DEFAULT_LIVESTREAM_THUMBNAIL
            val thumbUrl = clip.thumbnailUrl ?: defaultThumb
            
            Glide.with(context)
                .load(thumbUrl)
                .transform(CenterCrop(), BlurTransformation(25, 3))
                .error(Glide.with(context).load(defaultThumb).transform(CenterCrop(), BlurTransformation(25, 3)))
                .into(binding.backgroundImage)

            // Thumbnail
            Glide.with(context)
                .load(thumbUrl)
                .centerCrop()
                .error(Glide.with(context).load(defaultThumb).centerCrop())
                .into(binding.clipThumbnail)

            // Load channel avatar
            clip.channel?.let { channel ->
                Glide.with(context)
                    .load(channel.getEffectiveProfilePicUrl())
                    .circleCrop()
                    .into(binding.channelAvatar)
            }
            // Clip info
            binding.channelName.text = clip.channel?.username ?: clip.channel?.slug ?: ""
            binding.clipTitle.text = clip.title ?: context.getString(R.string.untitled_clip)
            if (!clip.creator?.username.isNullOrEmpty()) {
                binding.clipCreator.text = context.getString(R.string.clipped_by, clip.creator?.username)
                binding.clipCreator.visibility = View.VISIBLE
            } else {
                binding.clipCreator.visibility = View.GONE
            }
            currentCreatorName = clip.creator?.username

            // Views
            val views = clip.views ?: clip.viewCount ?: 0
            binding.viewsCount.text = when {
                views >= 1000000 -> String.format("%.1fM", views / 1000000.0)
                views >= 1000 -> String.format("%.1fK", views / 1000.0)
                else -> views.toString()
            }

            // Mute state
            updateMuteIcon(isMuted, false)
            
            // Reset player state
            currentPlayer?.setSurface(null)
            currentPlayer = null
            currentSurface = null
            isPlayerActive = false
            videoW = 0
            videoH = 0
            videoW = 0
            videoH = 0
            
            // Scale Toggle
            val scaleIcon = if (isCurrentlyFullscreen) R.drawable.ic_fullscreen_exit else R.drawable.ic_fullscreen
            binding.ivScaleType.setImageResource(scaleIcon)
            
            binding.ivScaleType.setOnClickListener {
                 onScaleToggle()
            }
            
            // Reset view state
            binding.clipPlayerTexture.visibility = View.GONE
            binding.clipPlayerTexture.alpha = 1f
            binding.lastFrameImage.visibility = View.GONE
            binding.playOverlay.visibility = View.GONE
            binding.clipSeekBar.progress = 0

            // Click listeners
            binding.channelAvatar.setOnClickListener {
                onChannelClick(clip)
            }

            binding.channelName.setOnClickListener {
                onChannelClick(clip)
            }

            binding.viewsClickWrapper.setOnClickListener {
                onMoreClick(clip)
            }
            binding.btnShare.setOnClickListener {
                onShareClick(clip)
            }

            binding.btnPlayInPlayer.setOnClickListener {
                onPlayInPlayerClick(clip)
            }

            binding.headerFilterContainer.setOnClickListener {
                onFilterClick()
            }
            binding.headerFilterText.text = filterName
            
            if (subFilterName.isNotEmpty()) {
                binding.headerSubFilterText.text = subFilterName
                binding.headerSubFilterText.visibility = View.VISIBLE
            } else {
                binding.headerSubFilterText.visibility = View.GONE
            }

            binding.btnMore.setOnClickListener {
                onMoreClick(clip)
            }
        }
        
        fun toggleOverlay(visible: Boolean) {
            isOverlayHidden = !visible
            val visibility = if (visible) View.VISIBLE else View.INVISIBLE
            binding.actionsContainer.visibility = visibility
            binding.bottomInfoContainer.visibility = visibility
            binding.headerFilterContainer.visibility = visibility
            binding.ivScaleType.visibility = visibility
            binding.clipSeekBar.visibility = visibility
            binding.timeDisplay.visibility = visibility
            
            // Gradients
            binding.root.findViewById<View>(R.id.feedTopGradient)?.visibility = visibility
            binding.root.findViewById<View>(R.id.feedBottomGradient)?.visibility = visibility
        }
        
        private var videoW = 0
        private var videoH = 0

        private fun applyScaleTransform() {
            val params = binding.clipPreviewContainer.layoutParams as androidx.constraintlayout.widget.ConstraintLayout.LayoutParams
            val matrix = android.graphics.Matrix()
            matrix.reset()
            binding.clipPlayerTexture.setTransform(matrix)
            
            val hasDimensions = videoW > 0 && videoH > 0
            
            // Use wrapper dimensions instead of root dimensions for tablet/portrait mode
            val wrapperW = binding.feedContentWrapper.width
            val wrapperH = binding.feedContentWrapper.height
            val containerW = if (wrapperW > 0) wrapperW else binding.root.width
            val containerH = if (wrapperH > 0) wrapperH else binding.root.height
            
            val density = binding.root.context.resources.displayMetrics.density
            if (isCurrentlyFullscreen && hasDimensions && containerW > 0 && containerH > 0) {
                 // FILL / COVER using Layout Sizing logic
                 // Remove Aspect Ratio constraint
                 params.dimensionRatio = null
                 
                 // Center the oversized container
                 params.startToStart = androidx.constraintlayout.widget.ConstraintLayout.LayoutParams.PARENT_ID
                 params.endToEnd = androidx.constraintlayout.widget.ConstraintLayout.LayoutParams.PARENT_ID
                 params.topToTop = androidx.constraintlayout.widget.ConstraintLayout.LayoutParams.PARENT_ID
                 params.bottomToBottom = androidx.constraintlayout.widget.ConstraintLayout.LayoutParams.PARENT_ID
                 params.topMargin = 0 // No gap in full screen
                 
                 val videoRatio = videoW.toFloat() / videoH.toFloat()
                 val containerRatio = containerW.toFloat() / containerH.toFloat()
                 
                 if (containerRatio > videoRatio) {
                     // Container Wider. Match Width. Height grows (but will be clipped).
                     params.width = containerW
                     params.height = (containerW / videoRatio).toInt()
                 } else {
                     // Container Taller. Match Height. Width grows (but will be clipped).
                     params.height = containerH
                     params.width = (containerH * videoRatio).toInt()
                 }
            } else {
                // FIT / RATIO (Top Aligned)
                params.width = androidx.constraintlayout.widget.ConstraintLayout.LayoutParams.MATCH_PARENT
                params.height = 0
                val ratio = if (hasDimensions) "${videoW}:${videoH}" else "16:9"
                params.dimensionRatio = "H,$ratio"
                
                params.startToStart = androidx.constraintlayout.widget.ConstraintLayout.LayoutParams.PARENT_ID
                params.endToEnd = androidx.constraintlayout.widget.ConstraintLayout.LayoutParams.PARENT_ID
                params.topToTop = androidx.constraintlayout.widget.ConstraintLayout.LayoutParams.PARENT_ID
                params.bottomToBottom = androidx.constraintlayout.widget.ConstraintLayout.LayoutParams.UNSET
                
                // Restore the gap for non-fullscreen mode
                params.topMargin = (60 * density).toInt()
            }
            binding.clipPreviewContainer.layoutParams = params
        }
        
        private fun formatDuration(seconds: Int): String {
            val mins = seconds / 60
            val secs = seconds % 60
            return "$mins:${secs.toString().padStart(2, '0')}"
        }

        fun updateMuteIcon(muted: Boolean, showIndicator: Boolean = false) {
            binding.visualMuteIndicator.setImageResource(if (muted) R.drawable.ic_volume_off else R.drawable.ic_volume_on)
            if (showIndicator) {
                showMuteIndicator(muted)
            }
        }

        private fun showMuteIndicator(muted: Boolean) {
            binding.visualMuteIndicator.let { view ->
                view.setImageResource(if (muted) R.drawable.ic_volume_off else R.drawable.ic_volume_on)
                view.visibility = View.VISIBLE
                view.animate().alpha(1f).setDuration(200).withEndAction {
                    view.animate().alpha(0f).setDuration(200).setStartDelay(600).withEndAction {
                        view.visibility = View.GONE
                    }.start()
                }.start()
            }
        }

        fun showLoading() {
            binding.clipLoadingSpinner.visibility = View.VISIBLE
            binding.playOverlay.visibility = View.GONE
        }

        fun hideLoading() {
            binding.clipLoadingSpinner.visibility = View.GONE
        }

        fun showPlayer() {
            isPlayerActive = true
            binding.clipPlayerTexture.alpha = 1f
            binding.clipPlayerTexture.visibility = View.VISIBLE
            binding.playOverlay.visibility = View.GONE
            
            if (isFirstFrameRendered) {
                binding.clipThumbnail.visibility = View.GONE
                binding.lastFrameImage.visibility = View.GONE
            } else {
                binding.clipThumbnail.visibility = View.VISIBLE
            }
        }

        fun showThumbnail() {
            isPlayerActive = false
            binding.clipPlayerTexture.visibility = View.GONE
            binding.clipThumbnail.visibility = View.VISIBLE
            binding.lastFrameImage.visibility = View.GONE
            binding.playOverlay.visibility = View.GONE
        }

        fun showLastFrame(bitmap: Bitmap?) {
            if (bitmap != null && !bitmap.isRecycled) {
                binding.lastFrameImage.setImageBitmap(bitmap)
                binding.lastFrameImage.visibility = View.VISIBLE
                binding.clipPlayerTexture.visibility = View.GONE
            }
        }

        fun captureCurrentFrame(): Bitmap? {
            return try {
                if (binding.clipPlayerTexture.isAvailable && isPlayerActive) {
                    binding.clipPlayerTexture.bitmap?.let { originalBitmap ->
                        originalBitmap.copy(Bitmap.Config.ARGB_8888, false)
                    }
                } else {
                    null
                }
            } catch (e: Exception) {
                null
            }
        }

        fun updateSeekBar(progress: Float) {
            if (!isSeeking) {
                binding.clipSeekBar.progress = (progress * 10000).toInt()
            }
        }

        fun updateTimeDisplay(currentMs: Long, totalMs: Long) {
            this.clipDuration = totalMs
            val currentSecs = (currentMs / 1000).toInt()
            val totalSecs = (totalMs / 1000).toInt()
            val timeText = "${formatDuration(currentSecs)} / ${formatDuration(totalSecs)}"
            binding.timeDisplay.text = timeText
            if (!isOverlayHidden) {
                binding.timeDisplay.visibility = View.VISIBLE
            }
        }

        private val sizeListener = object : Player.Listener() {
            override fun onVideoSizeChanged(width: Int, height: Int) {
                if (width > 0 && height > 0) {
                    videoW = width
                    videoH = height
                    applyScaleTransform()
                }
            }
            override fun onStateChanged(state: Player.State) {}
            override fun onError(exception: com.amazonaws.ivs.player.PlayerException) {}
            override fun onCue(cue: com.amazonaws.ivs.player.Cue) {}
            override fun onDurationChanged(duration: Long) {}
            override fun onMetadata(type: String, data: java.nio.ByteBuffer) {}
            override fun onQualityChanged(quality: com.amazonaws.ivs.player.Quality) {}
            override fun onRebuffering() {}
            override fun onSeekCompleted(duration: Long) {}
        }

        fun attachPlayer(player: Player) {
            if (currentPlayer != player) {
                currentPlayer?.removeListener(sizeListener)
                currentPlayer?.setSurface(null)
            }
            
            currentPlayer = player
            player.addListener(sizeListener)
            
            // Check initial size
            player.quality.let { quality ->
                if (quality.width > 0 && quality.height > 0) {
                    videoW = quality.width
                    videoH = quality.height
                }
            }
            applyScaleTransform()

            isPlayerActive = true
            isFirstFrameRendered = false
            
            binding.clipPlayerTexture.alpha = 1f
            binding.clipPlayerTexture.visibility = View.VISIBLE
            binding.clipThumbnail.visibility = View.GONE
            binding.lastFrameImage.visibility = View.GONE
            binding.playOverlay.visibility = View.GONE
            
            if (binding.clipPlayerTexture.isAvailable) {
                val newSurface = Surface(binding.clipPlayerTexture.surfaceTexture)
                currentSurface = newSurface
                player.setSurface(newSurface)
            }
        }

        fun prepareForPreload(player: Player) {
            if (currentPlayer != player) {
                currentPlayer?.removeListener(sizeListener)
                currentPlayer?.setSurface(null)
            }
            
            currentPlayer = player
            player.addListener(sizeListener)
            isFirstFrameRendered = false
            
            binding.clipPlayerTexture.alpha = 0f
            binding.clipPlayerTexture.visibility = View.VISIBLE
            
            if (binding.clipPlayerTexture.isAvailable) {
                val newSurface = Surface(binding.clipPlayerTexture.surfaceTexture)
                currentSurface = newSurface
                player.setSurface(newSurface)
            }
        }

        fun detachPlayer() {
            currentPlayer?.removeListener(sizeListener)
            currentPlayer?.setSurface(null)
            currentPlayer = null
            currentSurface?.release()
            currentSurface = null
            isPlayerActive = false
            binding.clipPlayerTexture.visibility = View.GONE
        }

        fun isReady(): Boolean = binding.clipPlayerTexture.isAvailable
        
        /**
         * Applies status bar and navigation bar padding to UI elements
         */
        fun applyInsetsPadding(statusBarHeight: Int, navBarHeight: Int) {
            // Update top margins for header elements (status bar padding)
            binding.ivScaleType?.let { view ->
                val params = view.layoutParams as? androidx.constraintlayout.widget.ConstraintLayout.LayoutParams
                params?.topMargin = 8 + statusBarHeight // Original 8dp + status bar
                view.layoutParams = params
            }
            
            binding.headerFilterContainer?.let { view ->
                val params = view.layoutParams as? androidx.constraintlayout.widget.ConstraintLayout.LayoutParams
                // headerFilterContainer uses constraintTop_toTopOf="@id/ivScaleType" so no need to modify
            }
            
            // Update bottom margins for UI elements (nav bar padding)
            binding.bottomInfoContainer?.let { container ->
                val params = container.layoutParams as? androidx.constraintlayout.widget.ConstraintLayout.LayoutParams
                params?.bottomMargin = 4 + navBarHeight // Original 4dp + nav bar
                container.layoutParams = params
            }
            
            binding.actionsContainer?.let { container ->
                val params = container.layoutParams as? androidx.constraintlayout.widget.ConstraintLayout.LayoutParams
                params?.bottomMargin = 120 + navBarHeight // Original 120dp + nav bar  
                container.layoutParams = params
            }
            
            // Apply to seekbar
            binding.clipSeekBar?.let { seekBar ->
                val params = seekBar.layoutParams as? androidx.constraintlayout.widget.ConstraintLayout.LayoutParams
                params?.bottomMargin = navBarHeight
                seekBar.layoutParams = params
            }
        }
    }
}
