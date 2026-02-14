/**
 * File: StreamFeedAdapter.kt
 *
 * Description: RecyclerView Adapter for the vertical Stream Feed.
 * It manages the display of live stream previews, binding video players to SurfaceTextures,
 * and handling user interactions such as mute, follow, and channel navigation within the feed.
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
import androidx.recyclerview.widget.RecyclerView
import com.amazonaws.ivs.player.Player
import com.bumptech.glide.Glide
import com.bumptech.glide.load.resource.bitmap.CenterCrop
import dev.xacnio.kciktv.R
import dev.xacnio.kciktv.shared.data.model.ChannelItem
import dev.xacnio.kciktv.databinding.LayoutFeedStreamBinding
import jp.wasabeef.glide.transformations.BlurTransformation
import dev.xacnio.kciktv.shared.util.CategoryUtils
import dev.xacnio.kciktv.shared.util.Constants

class StreamFeedAdapter(
    private val streams: MutableList<ChannelItem>,
    private val onStreamClick: (ChannelItem) -> Unit,
    private val onFollowClick: (ChannelItem) -> Unit,
    private val onShareClick: (ChannelItem) -> Unit,
    private val onChannelClick: (ChannelItem) -> Unit,
    private val onMuteToggle: () -> Unit,
    private val onViewAttached: (Int, StreamViewHolder) -> Unit,
    private val onViewDetached: ((Int, StreamViewHolder) -> Unit)? = null
) : RecyclerView.Adapter<StreamFeedAdapter.StreamViewHolder>() {

    override fun onViewAttachedToWindow(holder: StreamViewHolder) {
        super.onViewAttachedToWindow(holder)
        val pos = holder.adapterPosition
        if (pos != RecyclerView.NO_POSITION) {
            onViewAttached(pos, holder)
        }
    }

    override fun onViewDetachedFromWindow(holder: StreamViewHolder) {
        super.onViewDetachedFromWindow(holder)
        val pos = holder.adapterPosition
        if (pos != RecyclerView.NO_POSITION) {
            onViewDetached?.invoke(pos, holder)
        }
    }

    private var isMuted = true

    fun setMuted(muted: Boolean) {
        isMuted = muted
        notifyItemRangeChanged(0, streams.size, "MUTE_UPDATE")
    }
    
    fun getMuted(): Boolean = isMuted

    fun updateStreams(newStreams: List<ChannelItem>) {
        val oldSize = streams.size
        if (oldSize > 0) {
            streams.clear()
            notifyItemRangeRemoved(0, oldSize)
        }
        streams.addAll(newStreams)
        notifyItemRangeInserted(0, newStreams.size)
    }

    fun addStreams(additionalStreams: List<ChannelItem>) {
        val existingIds = streams.map { it.id }.toSet()
        val newUniqueStreams = additionalStreams.filter { it.id !in existingIds }
        if (newUniqueStreams.isNotEmpty()) {
            val startPos = streams.size
            streams.addAll(newUniqueStreams)
            notifyItemRangeInserted(startPos, newUniqueStreams.size)
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): StreamViewHolder {
        val binding = LayoutFeedStreamBinding.inflate(
            LayoutInflater.from(parent.context),
            parent,
            false
        )
        return StreamViewHolder(binding)
    }

    override fun onBindViewHolder(holder: StreamViewHolder, position: Int) {
        holder.bind(streams[position])
    }

    override fun onBindViewHolder(holder: StreamViewHolder, position: Int, payloads: MutableList<Any>) {
        if (payloads.contains("MUTE_UPDATE")) {
            // Mute state updated - visual indicator shown on tap instead
        } else {
            super.onBindViewHolder(holder, position, payloads)
        }
    }

    override fun getItemCount(): Int = streams.size

    inner class StreamViewHolder(
        val binding: LayoutFeedStreamBinding
    ) : RecyclerView.ViewHolder(binding.root) {

        private var currentPlayer: Player? = null
        private var currentSurface: Surface? = null
        private var isPlayerActive = false
        private var isCurrentlyFullscreen = false
        private var videoWidth = 0
        private var videoHeight = 0
        
        // TextureView callback
        private val surfaceTextureListener = object : TextureView.SurfaceTextureListener {
            override fun onSurfaceTextureAvailable(surface: SurfaceTexture, width: Int, height: Int) {
                val s = Surface(surface)
                currentSurface = s
                currentPlayer?.let { player ->
                    player.setSurface(s)
                    // Ensure texture is visible when surface becomes available
                    binding.feedPlayerTexture.alpha = 1f
                }
            }

            override fun onSurfaceTextureSizeChanged(surface: SurfaceTexture, width: Int, height: Int) {
                // Handle resize if needed
            }

            override fun onSurfaceTextureDestroyed(surface: SurfaceTexture): Boolean {
                // Return false to keep the SurfaceTexture alive
                // This prevents video freeze when views are recycled during scroll
                // We'll manually clean up in detachPlayer()
                return false
            }

            override fun onSurfaceTextureUpdated(surface: SurfaceTexture) {
                // Frame updated - player is rendering
            }
        }

        init {
            binding.feedPlayerTexture.surfaceTextureListener = surfaceTextureListener
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
            
            // Use provided dimensions, or fallback to rootView/displayMetrics
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
                // Landscape mode (Any device): constrain width to 9:16 ratio based on screen height
                val maxWidth = (screenHeight * 9f / 16f).toInt()
                params.width = minOf(maxWidth, screenWidth)
                params.height = FrameLayout.LayoutParams.MATCH_PARENT
                params.gravity = android.view.Gravity.CENTER
            } else if (isTablet) {
                // Portrait mode on tablet: constrain width to 9:16 ratio based on screen height
                val maxWidth = (screenHeight * 9f / 16f).toInt()
                params.width = minOf(maxWidth, screenWidth)
                params.height = FrameLayout.LayoutParams.MATCH_PARENT
                params.gravity = android.view.Gravity.CENTER
            } else {
                // Portrait Phone: use full width
                params.width = FrameLayout.LayoutParams.MATCH_PARENT
                params.height = FrameLayout.LayoutParams.MATCH_PARENT
                params.gravity = android.view.Gravity.CENTER
            }
            
            wrapper.layoutParams = params
        }

        fun bind(stream: ChannelItem) {
            val context = binding.root.context
            
            // Apply tablet/portrait width constraint for vertical feed
            applyFeedWidthConstraint()

            // Load blurred background
            val defaultThumb = Constants.Urls.DEFAULT_LIVESTREAM_THUMBNAIL
            val thumbUrl = stream.thumbnailUrl ?: defaultThumb
            
            // Check aspect ratio from thumbnail
            Glide.with(context)
                .asBitmap()
                .load(thumbUrl)
                .error(Glide.with(context).asBitmap().load(defaultThumb))
                .into(object : com.bumptech.glide.request.target.CustomTarget<Bitmap>() {
                    override fun onResourceReady(resource: Bitmap, transition: com.bumptech.glide.request.transition.Transition<in Bitmap>?) {
                        if (videoWidth == 0 || videoHeight == 0) {
                            updateVideoSize(resource.width, resource.height)
                        }
                    }
                    override fun onLoadCleared(placeholder: android.graphics.drawable.Drawable?) {}
                })

            Glide.with(context)
                .load(thumbUrl)
                .transform(CenterCrop(), BlurTransformation(25, 3))
                .error(Glide.with(context).load(defaultThumb).transform(CenterCrop(), BlurTransformation(25, 3)))
                .into(binding.backgroundImage)
            // Thumbnail hidden by request
            binding.streamThumbnail.visibility = View.GONE

            // Load avatar
            val avatarUrl = stream.getEffectiveProfilePicUrl()
            Glide.with(context)
                .load(avatarUrl)
                .circleCrop()
                .into(binding.channelAvatar)
            // Stream info
            binding.channelName.text = stream.username.ifEmpty { stream.slug }
            binding.streamTitle.text = stream.title ?: context.getString(R.string.live_now)
            
            // Category info
            binding.categoryName.text = stream.categoryName ?: context.getString(R.string.unknown_category)
            
            val iconRes = dev.xacnio.kciktv.shared.util.CategoryUtils.getCategoryIcon(stream.categorySlug)
            binding.categoryIcon.setImageResource(iconRes)
            binding.categoryIcon.imageTintList = android.content.res.ColorStateList.valueOf(android.graphics.Color.parseColor("#888888"))

            // Viewers
            val viewers = stream.viewerCount
            binding.viewersCount.text = when {
                viewers >= 1000000 -> String.format("%.1fM", viewers / 1000000.0)
                viewers >= 1000 -> String.format("%.1fK", viewers / 1000.0)
                else -> viewers.toString()
            }

            // Live badge
            binding.liveBadge.visibility = if (stream.isLive) View.VISIBLE else View.GONE
            
            // IMPORTANT: Reset player state when ViewHolder is rebound
            // This fixes issues with ViewHolder recycling
            currentPlayer?.setSurface(null)
            currentPlayer = null
            currentSurface = null
            isPlayerActive = false
            
            // Reset view state
            binding.feedPlayerTexture.visibility = View.GONE
            binding.feedPlayerTexture.alpha = 1f
            binding.lastFrameImage.visibility = View.GONE
            binding.playOverlay.visibility = View.GONE
            binding.playOverlay.visibility = View.GONE
            binding.visualMuteIndicator.visibility = View.GONE
            binding.streamEndedText.visibility = View.GONE

            // Click listeners
            // Tap on video area to toggle mute
            binding.streamPreviewContainer.setOnClickListener {
                onMuteToggle()
                showMuteIndicator()
            }
            
            // Long press on video to go to stream
            binding.streamPreviewContainer.setOnLongClickListener {
                onStreamClick(stream)
                true
            }

            binding.channelAvatar.setOnClickListener {
                onChannelClick(stream)
            }

            binding.channelName.setOnClickListener {
                onChannelClick(stream)
            }

            binding.btnFollow.setOnClickListener {
                onFollowClick(stream)
            }

            binding.btnShare.setOnClickListener {
                onShareClick(stream)
            }

            binding.btnMore.setOnClickListener {
                // Go to stream on more click
                onStreamClick(stream)
            }
        }
        
        fun showMuteIndicator() {
            val indicator = binding.visualMuteIndicator
            indicator.setImageResource(if (isMuted) R.drawable.ic_volume_off else R.drawable.ic_volume_on)
            indicator.visibility = View.VISIBLE
            indicator.alpha = 0f
            indicator.animate()
                .alpha(1f)
                .setDuration(150)
                .withEndAction {
                    indicator.postDelayed({
                        indicator.animate()
                            .alpha(0f)
                            .setDuration(150)
                            .withEndAction {
                                indicator.visibility = View.GONE
                            }
                            .start()
                    }, 400)
                }
                .start()
        }

        fun showLoading() {
            binding.feedLoadingSpinner.visibility = View.VISIBLE
            binding.playOverlay.visibility = View.GONE
            binding.streamEndedText.visibility = View.GONE
        }

        fun hideLoading() {
            binding.feedLoadingSpinner.visibility = View.GONE
        }

        fun showPlayer() {
            isPlayerActive = true
            binding.feedPlayerTexture.alpha = 1f
            binding.feedPlayerTexture.visibility = View.VISIBLE
            binding.lastFrameImage.visibility = View.GONE
            binding.playOverlay.visibility = View.GONE
            binding.streamEndedText.visibility = View.GONE
        }

        fun showThumbnail() {
            isPlayerActive = false
            binding.feedPlayerTexture.visibility = View.GONE
            binding.lastFrameImage.visibility = View.GONE
            binding.playOverlay.visibility = View.GONE
            binding.streamEndedText.visibility = View.GONE
        }

        fun showStreamEnded() {
            binding.streamEndedText.visibility = View.VISIBLE
            binding.feedLoadingSpinner.visibility = View.GONE
            binding.playOverlay.visibility = View.GONE
        }

        /**
         * Shows cached last frame bitmap for instant visual when returning to this stream
         */
        fun showLastFrame(bitmap: Bitmap?) {
            if (bitmap != null && !bitmap.isRecycled) {
                binding.lastFrameImage.setImageBitmap(bitmap)
                binding.lastFrameImage.visibility = View.VISIBLE
                // Keep texture hidden until player is ready
                binding.feedPlayerTexture.visibility = View.GONE
            }
        }

        /**
         * Captures current frame from TextureView
         * Returns null if TextureView is not available or has no content
         */
        fun captureCurrentFrame(): Bitmap? {
            return try {
                if (binding.feedPlayerTexture.isAvailable && isPlayerActive) {
                    binding.feedPlayerTexture.bitmap?.let { originalBitmap ->
                        // Create a copy since original bitmap may be reused
                        originalBitmap.copy(Bitmap.Config.ARGB_8888, false)
                    }
                } else {
                    null
                }
            } catch (e: Exception) {
                null
            }
        }

        fun attachPlayer(player: Player) {
            // Disconnect old player from surface
            if (currentPlayer != player) {
                currentPlayer?.setSurface(null)
            }
            
            currentPlayer = player
            isPlayerActive = true
            
            // Make texture visible immediately
            binding.feedPlayerTexture.alpha = 1f
            binding.feedPlayerTexture.visibility = View.VISIBLE
            binding.lastFrameImage.visibility = View.GONE
            binding.playOverlay.visibility = View.GONE
            binding.streamEndedText.visibility = View.GONE
            
            // Create new Surface from SurfaceTexture
            if (binding.feedPlayerTexture.isAvailable) {
                val newSurface = Surface(binding.feedPlayerTexture.surfaceTexture)
                currentSurface = newSurface
                player.setSurface(newSurface)
            }
        }

        /**
         * Prepares the view for preload player attachment
         * Makes texture available without showing it
         */
        fun prepareForPreload(player: Player) {
            // Disconnect old player from surface
            if (currentPlayer != player) {
                currentPlayer?.setSurface(null)
            }
            
            currentPlayer = player
            // Keep invisible but create surface
            binding.feedPlayerTexture.alpha = 0f
            binding.feedPlayerTexture.visibility = View.VISIBLE
            
            // Create new Surface from SurfaceTexture
            if (binding.feedPlayerTexture.isAvailable) {
                val newSurface = Surface(binding.feedPlayerTexture.surfaceTexture)
                currentSurface = newSurface
                player.setSurface(newSurface)
                // Don't release old Surface - it shares SurfaceTexture
            }
        }

        fun detachPlayer() {
            currentPlayer?.setSurface(null)
            currentPlayer = null
            currentSurface?.release()
            currentSurface = null
            isPlayerActive = false
            binding.feedPlayerTexture.visibility = View.GONE
        }

        fun isReady(): Boolean = binding.feedPlayerTexture.isAvailable
        
        fun setFollowingState(isFollowing: Boolean) {
             val context = binding.root.context
             binding.btnFollow.visibility = View.VISIBLE
             binding.followBtnSpinner.visibility = View.GONE
             
             if (isFollowing) {
                 binding.btnFollow.text = context.getString(R.string.following_status)
                 binding.btnFollow.setIconResource(R.drawable.ic_check)
             } else {
                 binding.btnFollow.text = context.getString(R.string.follow)
                 binding.btnFollow.setIconResource(R.drawable.ic_heart_outline)
             }
        }
        
        fun setFollowLoading(isLoading: Boolean) {
            if (isLoading) {
                binding.btnFollow.visibility = View.INVISIBLE
                binding.followBtnSpinner.visibility = View.VISIBLE
            } else {
                binding.btnFollow.visibility = View.VISIBLE
                binding.followBtnSpinner.visibility = View.GONE
            }
        }
        
        fun updateVideoSize(width: Int, height: Int) {
            if (width > 0 && height > 0) {
                this.videoWidth = width
                this.videoHeight = height
                // Re-apply scale with new dimensions
                setScaleMode(isCurrentlyFullscreen)
            }
        }

        /**
         * Sets the video scale mode
         * @param fullscreen true = Zoom/Fill Screen, false = Fit Center/Top
         */
        fun setScaleMode(fullscreen: Boolean) {
            isCurrentlyFullscreen = fullscreen
            val container = binding.streamPreviewContainer
            val params = container.layoutParams as? androidx.constraintlayout.widget.ConstraintLayout.LayoutParams ?: return
            
            val resources = container.context.resources
            val displayMetrics = resources.displayMetrics
            val density = displayMetrics.density
            val defaultTopMargin = (64 * density).toInt() 
            
            val ratio = if (videoWidth > 0 && videoHeight > 0) {
                videoWidth.toFloat() / videoHeight.toFloat()
            } else {
                16f / 9f
            }
            val isPortrait = ratio < 1.0f

            if (fullscreen) {
                // Fullscreen (Zoom/Fill): Scale container to cover the entire screen based on ratio
                // This simulates "Center Crop"
                val screenHeight = displayMetrics.heightPixels
                
                // Calculate width required to maintain aspect ratio with full screen height
                val targetWidth = (screenHeight * ratio).toInt()
                
                params.width = targetWidth
                params.height = screenHeight
                
                params.topToTop = androidx.constraintlayout.widget.ConstraintLayout.LayoutParams.PARENT_ID
                params.bottomToBottom = androidx.constraintlayout.widget.ConstraintLayout.LayoutParams.PARENT_ID
                params.startToStart = androidx.constraintlayout.widget.ConstraintLayout.LayoutParams.PARENT_ID
                params.endToEnd = androidx.constraintlayout.widget.ConstraintLayout.LayoutParams.PARENT_ID
                
                params.dimensionRatio = null
                params.topMargin = 0
            } else {
                // Fit Mode: Keep aspect ratio within constraints
                params.topToTop = androidx.constraintlayout.widget.ConstraintLayout.LayoutParams.PARENT_ID
                params.startToStart = androidx.constraintlayout.widget.ConstraintLayout.LayoutParams.PARENT_ID
                params.endToEnd = androidx.constraintlayout.widget.ConstraintLayout.LayoutParams.PARENT_ID
                
                params.width = 0 // Match constraints
                params.height = 0 // Match constraints (ratio)
                
                if (videoWidth > 0 && videoHeight > 0) {
                     params.dimensionRatio = "H,${videoWidth}:${videoHeight}"
                } else {
                     params.dimensionRatio = "H,16:9"
                }

                if (isPortrait) {
                    // Portrait: Center vertically, remove top margin (User Request: "Stand in middle")
                    params.bottomToBottom = androidx.constraintlayout.widget.ConstraintLayout.LayoutParams.PARENT_ID
                    params.topMargin = 0
                } else {
                    // Landscape: Align top with margin (Standard UI)
                    params.bottomToBottom = androidx.constraintlayout.widget.ConstraintLayout.LayoutParams.UNSET
                    params.topMargin = defaultTopMargin
                }
            }
            
            container.layoutParams = params
        }
        
        /**
         * Applies status bar and navigation bar padding to UI elements
         */
        fun applyInsetsPadding(statusBarHeight: Int, navBarHeight: Int) {
            // Update top margin for stream preview (status bar padding)
            binding.streamPreviewContainer?.let { container ->
                val params = container.layoutParams as? androidx.constraintlayout.widget.ConstraintLayout.LayoutParams
                val density = container.context.resources.displayMetrics.density
                val defaultTopMargin = (64 * density).toInt()
                params?.topMargin = defaultTopMargin + statusBarHeight
                container.layoutParams = params
            }
            
            // Update bottom margins for UI elements (nav bar padding)
            binding.bottomInfoContainer?.let { container ->
                val params = container.layoutParams as? androidx.constraintlayout.widget.ConstraintLayout.LayoutParams
                params?.bottomMargin = 32 + navBarHeight // Original 32dp + nav bar
                container.layoutParams = params
            }
            
            binding.actionsContainer?.let { container ->
                val params = container.layoutParams as? androidx.constraintlayout.widget.ConstraintLayout.LayoutParams
                params?.bottomMargin = 80 + navBarHeight // Original 80dp + nav bar  
                container.layoutParams = params
            }
        }
    }
}
