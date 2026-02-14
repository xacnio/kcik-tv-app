/**
 * File: FullscreenToggleManager.kt
 *
 * Description: Orchestrates toggling between Standard and Theatre fullscreen modes.
 * It manages the complex UI reparenting, layout constraints, and visibility states required to
 * switch between the default view and the immersive "Theatre Mode" with transparent chat overlay.
 *
 * Author: Xacnio
 *
 */
package dev.xacnio.kciktv.mobile.ui.player

import android.content.pm.ActivityInfo
import android.graphics.Color
import android.util.Log
import android.view.View
import android.view.WindowManager
import android.widget.Toast
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import dev.xacnio.kciktv.mobile.MobilePlayerActivity
import dev.xacnio.kciktv.R
import dev.xacnio.kciktv.mobile.ui.feed.StreamFeedAdapter
import dev.xacnio.kciktv.shared.data.model.PinnedMessage

class FullscreenToggleManager(private val activity: MobilePlayerActivity) {

    private val binding get() = activity.binding
    private val vodManager get() = activity.vodManager
    private val fullscreenManager get() = activity.fullscreenManager
    private val prefs get() = activity.prefs
    private val chatUiManager get() = activity.chatUiManager

    // Track if we're in theatre mode (portrait fullscreen with transparent chat)
    var isTheatreMode = false
        private set

    var hasAutoEnteredTheatreMode = false
        
    // Saved state for PiP transition
    private var savedVideoParams: ConstraintLayout.LayoutParams? = null
    private var savedResizeMode: com.amazonaws.ivs.player.ResizeMode? = null

    // Saved state for overlay reparenting
    private var originalOverlayParent: android.view.ViewGroup? = null
    private var originalOverlayIndex: Int = -1
    private var originalOverlayParams: android.view.ViewGroup.LayoutParams? = null
    
    private var originalInfoPanelParent: android.view.ViewGroup? = null
    private var originalInfoPanelIndex: Int = -1
    private var originalInfoPanelParams: android.view.ViewGroup.LayoutParams? = null

    private var originalActionBarParent: android.view.ViewGroup? = null
    private var originalActionBarIndex: Int = -1
    private var originalActionBarParams: android.view.ViewGroup.LayoutParams? = null

    private var originalPinnedParent: android.view.ViewGroup? = null
    private var originalPinnedIndex: Int = -1
    private var originalPinnedParams: android.view.ViewGroup.LayoutParams? = null

    private var originalPollParent: android.view.ViewGroup? = null
    private var originalPollIndex: Int = -1
    private var originalPollParams: android.view.ViewGroup.LayoutParams? = null

    private var originalPredictionParent: android.view.ViewGroup? = null
    private var originalPredictionIndex: Int = -1
    private var originalPredictionParams: android.view.ViewGroup.LayoutParams? = null

    private var theatreTopBar: android.widget.LinearLayout? = null
    
    private var originalViewerParent: android.view.ViewGroup? = null
    private var originalViewerIndex: Int = -1
    private var originalViewerParams: android.view.ViewGroup.LayoutParams? = null

    private var originalClipParent: android.view.ViewGroup? = null
    private var originalClipIndex: Int = -1
    private var originalClipParams: android.view.ViewGroup.LayoutParams? = null

    private var originalMuteParent: android.view.ViewGroup? = null
    private var originalMuteIndex: Int = -1
    private var originalMuteParams: android.view.ViewGroup.LayoutParams? = null

    private var originalDividerParent: android.view.ViewGroup? = null
    private var originalDividerIndex: Int = -1
    private var originalDividerParams: android.view.ViewGroup.LayoutParams? = null

    private var originalTimeParent: android.view.ViewGroup? = null
    private var originalTimeIndex: Int = -1
    private var originalTimeParams: android.view.ViewGroup.LayoutParams? = null

    private var originalFullscreenParent: android.view.ViewGroup? = null
    private var originalFullscreenIndex: Int = -1
    private var originalFullscreenParams: android.view.ViewGroup.LayoutParams? = null

    // Theatre Mode Top Bar Reparenting
    private var originalQualityParent: android.view.ViewGroup? = null
    private var originalQualityIndex: Int = -1
    private var originalQualityParams: android.view.ViewGroup.LayoutParams? = null

    private var originalSettingsParent: android.view.ViewGroup? = null
    private var originalSettingsIndex: Int = -1
    private var originalSettingsParams: android.view.ViewGroup.LayoutParams? = null

    private var originalReturnLiveParent: android.view.ViewGroup? = null
    private var originalReturnLiveIndex: Int = -1
    private var originalReturnLiveParams: android.view.ViewGroup.LayoutParams? = null

    // Emote Combo Reparenting
    private var originalEmoteComboParent: android.view.ViewGroup? = null
    private var originalEmoteComboIndex: Int = -1
    private var originalEmoteComboParams: android.view.ViewGroup.LayoutParams? = null

    // Floating Emote Reparenting
    private var originalFloatingEmoteParent: android.view.ViewGroup? = null
    private var originalFloatingEmoteIndex: Int = -1
    private var originalFloatingEmoteParams: android.view.ViewGroup.LayoutParams? = null

    private var originalPinnedGiftsParent: android.view.ViewGroup? = null
    private var originalPinnedGiftsIndex: Int = -1
    private var originalPinnedGiftsParams: android.view.ViewGroup.LayoutParams? = null

    // Side Chat State
    var isSideChatVisible = false
        private set
    private var originalChatParent: android.view.ViewGroup? = null
    private var originalChatIndex: Int = -1
    private var originalChatParams: android.view.ViewGroup.LayoutParams? = null

    // For Tablet Split View - tracking if ActionBar was moved to side
    private var isActionBarReparentedToSide = false

    // Theatre Mode Video Logic
    private var originalVideoDimensionRatio: String? = null
    private var isTheatreFitMode = false
    private var originalResizeMode: com.amazonaws.ivs.player.ResizeMode? = null
    
    private var currentVideoWidth: Int = 0
    private var currentVideoHeight: Int = 0

    fun updateVideoSize(width: Int, height: Int) {
        currentVideoWidth = width
        currentVideoHeight = height
        
        if (isTheatreMode) {
             val params = binding.videoContainer.layoutParams as ConstraintLayout.LayoutParams
             
             if (isTheatreFitMode) {
                 // FIT Mode: Always enforce 16:9 regardless of actual video size
                 params.dimensionRatio = "16:9"
             } else {
                 // ZOOM Mode: Use native video aspect ratio
                 if (width > 0 && height > 0) {
                     params.dimensionRatio = "$width:$height"
                 }
             }
             binding.videoContainer.layoutParams = params
        }
    }

    /**
     * Toggles between Theatre Fit modes (Fit vs Zoom)
     */
    fun toggleTheatreFitMode() {
        if (!isTheatreMode) return

        isTheatreFitMode = !isTheatreFitMode
        
        val metrics = activity.resources.displayMetrics
        val sHeight = metrics.heightPixels
        
        val params = binding.videoContainer.layoutParams as ConstraintLayout.LayoutParams
        if (isTheatreFitMode) {
            // FIT: Constrain strictly within the "stage" area (Top 15% to Bottom ~35%)
            // This ensures ANY aspect ratio fits without overflowing or covering chat
            params.width = ConstraintLayout.LayoutParams.MATCH_CONSTRAINT
            params.height = ConstraintLayout.LayoutParams.MATCH_CONSTRAINT
            
            val targetRatio = if (currentVideoWidth > 0 && currentVideoHeight > 0 && !isTheatreFitMode) {
                "$currentVideoWidth:$currentVideoHeight"
            } else {
                originalVideoDimensionRatio ?: "16:9"
            }
            params.dimensionRatio = targetRatio
            
            // Remove previous constraints
            params.matchConstraintPercentHeight = 1.0f 
            params.matchConstraintPercentWidth = 1.0f
            
            // Define the "Box"
            // Top: 15% down
            // Bottom: Leave 35% space for Chat (approx)
            params.topMargin = (sHeight * 0.15f).toInt()
            params.bottomMargin = (sHeight * 0.35f).toInt()
            
            params.topToTop = ConstraintLayout.LayoutParams.PARENT_ID
            params.bottomToBottom = ConstraintLayout.LayoutParams.PARENT_ID
            params.startToStart = ConstraintLayout.LayoutParams.PARENT_ID
            params.endToEnd = ConstraintLayout.LayoutParams.PARENT_ID
            
            params.verticalBias = 0.5f // Center vertically within the defined top/bottom margins
            
            // Increase Chat Height in Fit Mode
            theatreModeChatCollapsedHeight = (sHeight * 0.45f).toInt()
            theatreModeChatExpandedHeight = (sHeight * 0.70f).toInt()
            
            // Reset scaleX/Y just in case
            binding.videoContainer.scaleX = 1f
            binding.videoContainer.scaleY = 1f
        } else {
            // ZOOM: Height 100%, Width overflows (Center Crop effect)
            val ratioVal = if (currentVideoWidth > 0 && currentVideoHeight > 0) {
                 currentVideoWidth.toFloat() / currentVideoHeight.toFloat()
            } else {
                 16f / 9f
            }
            // Logic for "Fill Screen" / Zoom
            val zWidth = (sHeight * ratioVal).toInt()
            
            params.dimensionRatio = null
            params.width = zWidth
            params.height = sHeight
            params.matchConstraintPercentHeight = 1.0f // Reset
            params.matchConstraintPercentWidth = 1.0f // Reset
            
            params.topMargin = 0
            params.bottomMargin = 0
            params.topToTop = ConstraintLayout.LayoutParams.PARENT_ID
            params.bottomToBottom = ConstraintLayout.LayoutParams.PARENT_ID
            params.startToStart = ConstraintLayout.LayoutParams.PARENT_ID
            params.endToEnd = ConstraintLayout.LayoutParams.PARENT_ID
            
            // Center and remove margin
            params.verticalBias = 0.5f 
            
            // Restore Standard Chat Height
            theatreModeChatCollapsedHeight = (sHeight * 0.30f).toInt()
            theatreModeChatExpandedHeight = (sHeight * 0.55f).toInt()
        }
        binding.videoContainer.layoutParams = params
        
        // Apply updated height immediately
        val chatParams = binding.chatContainer.layoutParams as ConstraintLayout.LayoutParams
        chatParams.height = theatreModeChatCollapsedHeight
        binding.chatContainer.layoutParams = chatParams
    }

    /**
     * Cleans up side chat state and resets video container constraints.
     * @param forceReset If true, always perform full cleanup even if state appears clean.
     *                    Used during playChannel to ensure absolutely clean state.
     */
    internal fun cleanupSideChat(forceReset: Boolean = false) {
        // Use early return guard for normal calls, but skip it if forcing reset
        if (!forceReset && !isSideChatVisible && binding.sideChatContainer.visibility != View.VISIBLE) return
        
        val chat = binding.chatContainer
        binding.sideChatContainer.visibility = View.GONE
        
        // Remove chat from side container if it's there
        if (chat.parent == binding.sideChatContainer) {
            binding.sideChatContainer.removeView(chat)
            chat.setOnTouchListener(null)
            
            if (originalChatParent != null) {
                val safeIndex = if (originalChatIndex in 0..originalChatParent!!.childCount) originalChatIndex else -1
                originalChatParent?.addView(chat, safeIndex, originalChatParams)
            }
        }
        
        // --- Restore ActionBar (Tablets) ---
        if (isActionBarReparentedToSide) {
            val actionBar = binding.actionBar
            if (actionBar.parent == binding.sideChatContainer) {
                binding.sideChatContainer.removeView(actionBar)
                
                if (originalActionBarParent != null) {
                    val safeIndex = if (originalActionBarIndex in 0..originalActionBarParent!!.childCount) originalActionBarIndex else -1
                    originalActionBarParent?.addView(actionBar, safeIndex, originalActionBarParams)
                }
                isActionBarReparentedToSide = false
                originalActionBarParent = null
            }
        }

        // Restore default background
        chat.setBackgroundColor(android.graphics.Color.parseColor("#0f0f0f"))
        isSideChatVisible = false
        originalChatParent = null 
        
        // CRITICAL: Reset video layout params to prevent "Stuck Split Screen"
        val params = binding.videoContainer.layoutParams as ConstraintLayout.LayoutParams
        params.endToStart = ConstraintLayout.LayoutParams.UNSET
        params.endToEnd = ConstraintLayout.LayoutParams.PARENT_ID
        binding.videoContainer.layoutParams = params

        // Restore Chat Rules Container to default
        activity.chatRulesManager.updateContainer(activity.binding.bottomSheetCoordinator)
    }

    /**
     * Toggles between fullscreen and portrait mode.
     */
    fun toggleFullscreen() {
        if (activity.isFullscreen) {
            exitFullscreen()
        } else {
            enterFullscreen()
        }
    }

    fun onChatJumpToBottomClicked() {
        if (isTheatreMode) {
             collapseTheatreModeChat()
        }
    }

    /**
     * Enters Theatre Mode - Portrait fullscreen with transparent chat overlay.
     * Video covers entire screen, chat covers bottom half with gradient fade.
     */
    fun enterTheatreMode() {
        if (isTheatreMode) {
            exitTheatreMode()
            return
        }

        // Clean up tablet side chat if active to avoid ClassCastException
        if (isSideChatVisible) {
            cleanupSideChat()
        }

        // Enable theatre mode preference
        isTheatreMode = true

        // Update PiP state immediately to disable auto-PiP
        activity.updatePiPUi()

        // Keep portrait for theatre mode (it's a portrait-specific feature)
        activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
        // FULLSCREEN is handled by WindowInsetsControllerCompat below
        // activity.window.addFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN)

        // Hide status bar but keep navigation bar visible
        val windowInsetsController = WindowInsetsControllerCompat(activity.window, activity.window.decorView)
        windowInsetsController.hide(WindowInsetsCompat.Type.statusBars())
        windowInsetsController.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        
        // Update nav bar color (handled by Activity logic: Theatre -> Transparent, Standard -> #0f0f0f)
        activity.updateNavigationBarColor(true)

        // Calculate fullscreen dimensions (same approach as StreamFeedAdapter.setScaleMode)
        val displayMetrics = activity.resources.displayMetrics
        val screenHeight = displayMetrics.heightPixels
        val screenWidth = displayMetrics.widthPixels
        (screenHeight * (16f / 9f)).toInt()

        // Position chat container at bottom 30% of screen (smaller, will expand on scroll)
        // Position chat container at bottom 30% of screen (smaller, will expand on scroll)
        // Default to Fit Mode heights (larger chat area)
        theatreModeChatCollapsedHeight = (screenHeight * 0.45f).toInt()
        theatreModeChatExpandedHeight = (screenHeight * 0.70f).toInt()
        
        val chatParams = binding.chatContainer.layoutParams as ConstraintLayout.LayoutParams
        chatParams.topToTop = ConstraintLayout.LayoutParams.UNSET
        chatParams.topToBottom = ConstraintLayout.LayoutParams.UNSET
        chatParams.bottomToBottom = ConstraintLayout.LayoutParams.PARENT_ID
        chatParams.startToStart = ConstraintLayout.LayoutParams.PARENT_ID
        chatParams.endToEnd = ConstraintLayout.LayoutParams.PARENT_ID
        chatParams.width = 0
        chatParams.height = theatreModeChatCollapsedHeight
        chatParams.matchConstraintPercentHeight = 1f
        // No bottom margin - content extends to edge, nav bar overlays
        chatParams.bottomMargin = 0
        binding.chatContainer.layoutParams = chatParams
        
        // Hide seekbar in theatre mode
        binding.playerSeekBar.visibility = View.GONE
        
        // Override chat container insets listener for theatre mode
        // In theatre mode, the chat container has a fixed height. Using padding shrinks the content area.
        // Instead, we use bottomMargin to push the entire container up above the keyboard.
        androidx.core.view.ViewCompat.setOnApplyWindowInsetsListener(binding.chatContainer) { view, insets ->
            val imeInsets = insets.getInsets(androidx.core.view.WindowInsetsCompat.Type.ime())
            val systemBars = insets.getInsets(androidx.core.view.WindowInsetsCompat.Type.systemBars())
            
            val navHeight = systemBars.bottom
            val keyboardHeight = imeInsets.bottom
            
            val params = view.layoutParams as ConstraintLayout.LayoutParams
            // Push up by keyboard height MINUS nav height to remove gap
            // The container might already have nav bar padding from parent or insets logic
            params.bottomMargin = if (keyboardHeight > 0) {
                 kotlin.math.max(0, keyboardHeight - navHeight)
            } else {
                 0 
            }
            
            view.layoutParams = params
            
            // Ensure padding is reset to 0 (bottom) so we don't double-dip if some other logic added padding
            // Also force top padding to 0 for Theatre Mode
            view.setPadding(view.paddingLeft, 0, view.paddingRight, 0)
            
            // Adjust background opacity when keyboard is open (60% Black) to improve readability
            if (keyboardHeight > 0) {
                binding.chatListContainer.setBackgroundColor(android.graphics.Color.parseColor("#99000000"))
                binding.quickEmoteRecyclerView.setBackgroundColor(android.graphics.Color.parseColor("#99000000"))
            } else {
                binding.chatListContainer.setBackgroundColor(android.graphics.Color.TRANSPARENT)
                binding.quickEmoteRecyclerView.setBackgroundColor(android.graphics.Color.TRANSPARENT)
            }
            
            insets
        }

        // Style chat container for overlay
        binding.chatContainer.visibility = View.VISIBLE
        binding.chatContainer.setBackgroundColor(Color.TRANSPARENT)
        ViewCompat.setElevation(binding.chatContainer, 1000f)
        ViewCompat.setTranslationZ(binding.chatContainer, 1000f)
        // Ensure no shadow clipping/issues
        binding.chatContainer.outlineProvider = null
        
        // Disable clipping so translated views are visible above keyboard
        binding.chatContainer.clipChildren = false
        binding.chatContainer.clipToPadding = false
        binding.root.clipChildren = false
        binding.playerScreenContainer.clipChildren = false

        // Remove gradient from chat list container
        binding.chatListContainer.setBackgroundColor(android.graphics.Color.TRANSPARENT)
        binding.chatInputRoot.setBackgroundColor(android.graphics.Color.TRANSPARENT)
        binding.chatInput.setBackgroundResource(dev.xacnio.kciktv.R.drawable.bg_chat_input_border)
        
        // Elevate chat input to ensure it's always on top
        ViewCompat.setElevation(binding.chatInputRoot, 2000f)
        ViewCompat.setTranslationZ(binding.chatInputRoot, 2000f)
        
        // Make quick emote bar fully transparent and elevate
        binding.quickEmoteRecyclerView.setBackgroundColor(android.graphics.Color.TRANSPARENT)
        ViewCompat.setElevation(binding.quickEmoteRecyclerView, 2000f)
        ViewCompat.setTranslationZ(binding.quickEmoteRecyclerView, 2000f)
        
        // Force bring to front
        binding.chatContainer.bringToFront()
        binding.playerScreenContainer.invalidate()

        // Add bottom gradient to screen (30% from bottom up)
        binding.theatreBottomGradient.let { gradient ->
            gradient.visibility = View.VISIBLE
            val gradientParams = gradient.layoutParams as? ConstraintLayout.LayoutParams
            gradientParams?.let {
                it.height = (screenHeight * 0.50f).toInt()
                gradient.layoutParams = it
            }
            ViewCompat.setElevation(gradient, 1f)
            ViewCompat.setZ(gradient, 1f)
        }

        // Do NOT reparent video overlay to root, so chat remains on top
        // We keep it in its original hierarchy but lower elevation than chat
        
        /*
        // Code removed to prevent overlay from covering chat
        val overlay = binding.videoOverlay
        val navBarHeight = getNavigationBarHeight()
        val overlayBottomMargin = navBarHeight + (5 * activity.resources.displayMetrics.density).toInt()
        if (overlay.parent != binding.root) {
            originalOverlayParent = overlay.parent as android.view.ViewGroup
            originalOverlayIndex = originalOverlayParent?.indexOfChild(overlay) ?: -1
            originalOverlayParams = overlay.layoutParams
            
            originalOverlayParent?.removeView(overlay)
            
            // Add to root (ConstraintLayout)
            val root = binding.root as android.view.ViewGroup
            val newParams = ConstraintLayout.LayoutParams(ConstraintLayout.LayoutParams.MATCH_PARENT, ConstraintLayout.LayoutParams.WRAP_CONTENT)
            newParams.bottomToBottom = ConstraintLayout.LayoutParams.PARENT_ID
            newParams.startToStart = ConstraintLayout.LayoutParams.PARENT_ID
            newParams.endToEnd = ConstraintLayout.LayoutParams.PARENT_ID
            newParams.width = screenWidth
            newParams.bottomMargin = overlayBottomMargin
            
            root.addView(overlay, newParams)
        } else {
             // Just update params if already root
             val overlayParams = overlay.layoutParams as ConstraintLayout.LayoutParams
             overlayParams.width = screenWidth
             overlayParams.bottomToBottom = ConstraintLayout.LayoutParams.PARENT_ID
             overlayParams.bottomMargin = overlayBottomMargin
             overlay.layoutParams = overlayParams
        }
        */
        
        // Add background to video overlay for better visibility
        binding.videoOverlay.setBackgroundResource(R.drawable.gradient_bottom_dark)
        
        // Ensure elevation is lower than chat (which is set to 1000f/2000f above)
        ViewCompat.setElevation(binding.videoOverlay, 100f)
        ViewCompat.setZ(binding.videoOverlay, 100f)
        binding.videoOverlay.isClickable = true

        // Hide global header and other UI panels
        binding.mobileHeader.visibility = View.GONE
        binding.infoPanel.visibility = View.GONE
        binding.actionBar.visibility = View.GONE

        // --- Create Theatre Top Bar (Always Visible) ---
        if (theatreTopBar == null) {
            theatreTopBar = android.widget.LinearLayout(activity).apply {
                id = ViewCompat.generateViewId()
                orientation = android.widget.LinearLayout.HORIZONTAL
                layoutParams = ConstraintLayout.LayoutParams(ConstraintLayout.LayoutParams.MATCH_PARENT, ConstraintLayout.LayoutParams.WRAP_CONTENT).apply {
                    topToTop = ConstraintLayout.LayoutParams.PARENT_ID
                    startToStart = ConstraintLayout.LayoutParams.PARENT_ID
                    endToEnd = ConstraintLayout.LayoutParams.PARENT_ID
                }
                setPadding(
                    (16 * activity.resources.displayMetrics.density).toInt(),
                    (8 * activity.resources.displayMetrics.density).toInt(),
                    (16 * activity.resources.displayMetrics.density).toInt(),
                    (8 * activity.resources.displayMetrics.density).toInt()
                )
                background = android.graphics.drawable.GradientDrawable(
                    android.graphics.drawable.GradientDrawable.Orientation.TOP_BOTTOM,
                    intArrayOf(android.graphics.Color.parseColor("#D9000000"), android.graphics.Color.TRANSPARENT)
                ) 
                gravity = android.view.Gravity.CENTER_VERTICAL
                isClickable = true 
            }
            (binding.root as android.view.ViewGroup).addView(theatreTopBar)
            ViewCompat.setElevation(theatreTopBar!!, 160f)
            ViewCompat.setZ(theatreTopBar!!, 160f)

            // --- Reparent Buttons to Top Bar ---
            
            // Return to Live Button (Top Left)
            val returnLiveBtn = binding.returnToLiveButton
            originalReturnLiveParent = returnLiveBtn.parent as android.view.ViewGroup
            originalReturnLiveIndex = originalReturnLiveParent?.indexOfChild(returnLiveBtn) ?: -1
            originalReturnLiveParams = returnLiveBtn.layoutParams
            
            originalReturnLiveParent?.removeView(returnLiveBtn)
            
            // Style for top bar
             val returnLiveParams = android.widget.LinearLayout.LayoutParams(
                 android.widget.LinearLayout.LayoutParams.WRAP_CONTENT, 
                 (28 * activity.resources.displayMetrics.density).toInt()
             ).apply {
                 marginEnd = (8 * activity.resources.displayMetrics.density).toInt()
                 gravity = android.view.Gravity.CENTER_VERTICAL
             }
             returnLiveBtn.layoutParams = returnLiveParams
             theatreTopBar?.addView(returnLiveBtn)
            
            // Viewer
            val viewerLayout = binding.viewerLayout
            originalViewerParent = viewerLayout.parent as android.view.ViewGroup
            originalViewerIndex = originalViewerParent?.indexOfChild(viewerLayout) ?: -1
            originalViewerParams = viewerLayout.layoutParams
            originalViewerParent?.removeView(viewerLayout)
            theatreTopBar?.addView(viewerLayout)

            // Uptime Divider
            val uptimeDivider = binding.uptimeDivider
            if (uptimeDivider.visibility == View.VISIBLE || activity.prefs.isLoggedIn) {
                 originalDividerParent = uptimeDivider.parent as android.view.ViewGroup
                 originalDividerIndex = originalDividerParent?.indexOfChild(uptimeDivider) ?: -1
                 originalDividerParams = uptimeDivider.layoutParams
                 
                 originalDividerParent?.removeView(uptimeDivider)
                 
                 uptimeDivider.visibility = View.VISIBLE
                 theatreTopBar?.addView(uptimeDivider)
            }

            // Stream Time
            val timeBadge = binding.streamTimeBadge
            if (timeBadge.visibility == View.VISIBLE || activity.prefs.isLoggedIn) {
                 // Only move if it's potentially visible or enabled
                 originalTimeParent = timeBadge.parent as android.view.ViewGroup
                 originalTimeIndex = originalTimeParent?.indexOfChild(timeBadge) ?: -1
                 originalTimeParams = timeBadge.layoutParams
                 
                 originalTimeParent?.removeView(timeBadge)
                 
                 // Style for top bar
                 val timeParams = android.widget.LinearLayout.LayoutParams(android.widget.LinearLayout.LayoutParams.WRAP_CONTENT, android.widget.LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                     marginStart = (8 * activity.resources.displayMetrics.density).toInt()
                     gravity = android.view.Gravity.CENTER_VERTICAL
                 }
                 timeBadge.layoutParams = timeParams
                 timeBadge.visibility = View.VISIBLE // Ensure it's visible in top bar
                 theatreTopBar?.addView(timeBadge)
            }

            // Spacer
            theatreTopBar?.addView(android.view.View(activity).apply {
                 layoutParams = android.widget.LinearLayout.LayoutParams(0, 1, 1f)
            })

            // Video Quality Badge
            val qualityBadge = binding.videoQualityBadge
            originalQualityParent = qualityBadge.parent as android.view.ViewGroup
            originalQualityIndex = originalQualityParent?.indexOfChild(qualityBadge) ?: -1
            originalQualityParams = qualityBadge.layoutParams
            originalQualityParent?.removeView(qualityBadge)
            
            // Adjust params for LinearLayout
            val qualityParams = android.widget.LinearLayout.LayoutParams(android.widget.LinearLayout.LayoutParams.WRAP_CONTENT, android.widget.LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                 marginStart = (12 * activity.resources.displayMetrics.density).toInt()
                 gravity = android.view.Gravity.CENTER_VERTICAL
            }
            qualityBadge.layoutParams = qualityParams
            theatreTopBar?.addView(qualityBadge)

            // Video Settings Button
            val settingsBtn = binding.videoSettingsButton
            originalSettingsParent = settingsBtn.parent as android.view.ViewGroup
            originalSettingsIndex = originalSettingsParent?.indexOfChild(settingsBtn) ?: -1
            originalSettingsParams = settingsBtn.layoutParams
            originalSettingsParent?.removeView(settingsBtn)
            
            val settingsParams = android.widget.LinearLayout.LayoutParams((36 * activity.resources.displayMetrics.density).toInt(), (36 * activity.resources.displayMetrics.density).toInt()).apply {
                 marginStart = (8 * activity.resources.displayMetrics.density).toInt()
                 gravity = android.view.Gravity.CENTER_VERTICAL
            }
            settingsBtn.layoutParams = settingsParams
            // Update padding for smaller touch target in top bar
            settingsBtn.setPadding((6 * activity.resources.displayMetrics.density).toInt(), (6 * activity.resources.displayMetrics.density).toInt(), (6 * activity.resources.displayMetrics.density).toInt(), (6 * activity.resources.displayMetrics.density).toInt())
            theatreTopBar?.addView(settingsBtn)

            // Clip
            val clipContainer = binding.clipButtonContainer 
            originalClipParent = clipContainer.parent as android.view.ViewGroup
            originalClipIndex = originalClipParent?.indexOfChild(clipContainer) ?: -1
            originalClipParams = clipContainer.layoutParams
            originalClipParent?.removeView(clipContainer)
            theatreTopBar?.addView(clipContainer)

            // Mute
            val muteButton = binding.muteButton
            originalMuteParent = muteButton.parent as android.view.ViewGroup
            originalMuteIndex = originalMuteParent?.indexOfChild(muteButton) ?: -1
            originalMuteParams = muteButton.layoutParams
            originalMuteParent?.removeView(muteButton)
            theatreTopBar?.addView(muteButton)
            
            // Hide original fullscreen button and theatre mode button
            binding.fullscreenButton.visibility = android.view.View.GONE
            binding.theatreModeButton.visibility = android.view.View.GONE
            // binding.uptimeDivider.visibility = android.view.View.GONE // Now managed by reparenting
            
            // Adjust margins
            val displayDensity = activity.resources.displayMetrics.density
            val marginParams = android.widget.LinearLayout.LayoutParams(android.widget.LinearLayout.LayoutParams.WRAP_CONTENT, android.widget.LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                marginStart = (12 * displayDensity).toInt()
            }
            clipContainer.layoutParams = marginParams
            muteButton.layoutParams = marginParams
        }

        // --- Reparent Emote Combo (Top Right) ---
        val emoteCombo = binding.emoteComboContainer
        if (emoteCombo.parent != binding.root) {
             originalEmoteComboParent = emoteCombo.parent as android.view.ViewGroup
             originalEmoteComboIndex = originalEmoteComboParent?.indexOfChild(emoteCombo) ?: -1
             originalEmoteComboParams = emoteCombo.layoutParams
             
             originalEmoteComboParent?.removeView(emoteCombo)
             
             val root = binding.root as android.view.ViewGroup
             val newParams = ConstraintLayout.LayoutParams(ConstraintLayout.LayoutParams.WRAP_CONTENT, ConstraintLayout.LayoutParams.WRAP_CONTENT)
             newParams.topToBottom = theatreTopBar!!.id // Below Top Bar
             newParams.endToEnd = ConstraintLayout.LayoutParams.PARENT_ID
             newParams.topMargin = (16 * activity.resources.displayMetrics.density).toInt()
             newParams.marginEnd = (16 * activity.resources.displayMetrics.density).toInt()
             
             root.addView(emoteCombo, newParams)
             ViewCompat.setElevation(emoteCombo, 155f)
             ViewCompat.setZ(emoteCombo, 155f)
        }

        // --- Reparent Floating Emote (Full Screen Height) ---
        val floatingEmote = binding.floatingEmoteContainer
        if (floatingEmote.parent != binding.root) {
             originalFloatingEmoteParent = floatingEmote.parent as android.view.ViewGroup
             originalFloatingEmoteIndex = originalFloatingEmoteParent?.indexOfChild(floatingEmote) ?: -1
             originalFloatingEmoteParams = floatingEmote.layoutParams
             
             originalFloatingEmoteParent?.removeView(floatingEmote)
             
             val root = binding.root as android.view.ViewGroup
             val newParams = ConstraintLayout.LayoutParams((120 * activity.resources.displayMetrics.density).toInt(), ConstraintLayout.LayoutParams.MATCH_PARENT)
             newParams.topToTop = ConstraintLayout.LayoutParams.PARENT_ID
             newParams.bottomToBottom = ConstraintLayout.LayoutParams.PARENT_ID
             newParams.endToEnd = ConstraintLayout.LayoutParams.PARENT_ID
             
             root.addView(floatingEmote, newParams)
             ViewCompat.setElevation(floatingEmote, 140f) 
             ViewCompat.setZ(floatingEmote, 140f)
        }

        // --- Reparent InfoPanel to Root (Below TopBar) ---
        val infoPanel = binding.infoPanel
        if (infoPanel.parent != binding.root) {
            originalInfoPanelParent = infoPanel.parent as android.view.ViewGroup
            originalInfoPanelIndex = originalInfoPanelParent?.indexOfChild(infoPanel) ?: -1
            originalInfoPanelParams = infoPanel.layoutParams
            
            originalInfoPanelParent?.removeView(infoPanel)
            
            val root = binding.root as android.view.ViewGroup
            val newParams = ConstraintLayout.LayoutParams(ConstraintLayout.LayoutParams.MATCH_PARENT, ConstraintLayout.LayoutParams.WRAP_CONTENT)
            newParams.topToBottom = theatreTopBar!!.id // Below Top Bar
            newParams.startToStart = ConstraintLayout.LayoutParams.PARENT_ID
            newParams.endToEnd = ConstraintLayout.LayoutParams.PARENT_ID
            
            root.addView(infoPanel, newParams)
        } else {
             val params = infoPanel.layoutParams as ConstraintLayout.LayoutParams
             params.topToBottom = theatreTopBar!!.id
             params.topToTop = ConstraintLayout.LayoutParams.UNSET
             infoPanel.layoutParams = params
        }
        
        // Ensure visibility and styling
        binding.infoPanel.visibility = View.GONE
        binding.infoPanel.bringToFront()
        binding.infoPanel.setBackgroundColor(android.graphics.Color.parseColor("#CC0f0f0f")) // Semi-transparent black
        ViewCompat.setElevation(binding.infoPanel, 150f)
        ViewCompat.setZ(binding.infoPanel, 150f)

        // --- Reparent ActionBar to Root ---
        val actionBar = binding.actionBar
        if (actionBar.parent != binding.root) {
            originalActionBarParent = actionBar.parent as android.view.ViewGroup
            originalActionBarIndex = originalActionBarParent?.indexOfChild(actionBar) ?: -1
            originalActionBarParams = actionBar.layoutParams
            
            originalActionBarParent?.removeView(actionBar)
            
            val root = binding.root as android.view.ViewGroup
            val newParams = ConstraintLayout.LayoutParams(ConstraintLayout.LayoutParams.MATCH_PARENT, ConstraintLayout.LayoutParams.WRAP_CONTENT)
            newParams.topToBottom = binding.infoPanel.id // Below InfoPanel
            newParams.startToStart = ConstraintLayout.LayoutParams.PARENT_ID
            newParams.endToEnd = ConstraintLayout.LayoutParams.PARENT_ID
            
            root.addView(actionBar, newParams)
        } else {
             val params = actionBar.layoutParams as ConstraintLayout.LayoutParams
             params.topToBottom = binding.infoPanel.id
             actionBar.layoutParams = params
        }

        // Ensure visibility and styling
        binding.actionBar.visibility = View.GONE
        binding.actionBar.bringToFront()
        ViewCompat.setElevation(binding.actionBar, 150f)
        ViewCompat.setZ(binding.actionBar, 150f)

        // --- Reparent Pinned Gifts ---
        val pinnedGifts = binding.pinnedGiftsBlur 
        if (pinnedGifts.parent != binding.root) {
            originalPinnedGiftsParent = pinnedGifts.parent as android.view.ViewGroup
            originalPinnedGiftsIndex = originalPinnedGiftsParent?.indexOfChild(pinnedGifts) ?: -1
            originalPinnedGiftsParams = pinnedGifts.layoutParams
            
            originalPinnedGiftsParent?.removeView(pinnedGifts)
            
            val root = binding.root as android.view.ViewGroup
            val newParams = ConstraintLayout.LayoutParams(ConstraintLayout.LayoutParams.MATCH_PARENT, ConstraintLayout.LayoutParams.WRAP_CONTENT)
            newParams.topToBottom = theatreTopBar!!.id // Anchor to visible Top Bar
            newParams.startToStart = ConstraintLayout.LayoutParams.PARENT_ID
            newParams.endToEnd = ConstraintLayout.LayoutParams.PARENT_ID
            
            root.addView(pinnedGifts, newParams)
            ViewCompat.setElevation(pinnedGifts, 150f)
            ViewCompat.setZ(pinnedGifts, 150f)
            // pinnedGifts.background = null // Preserving background as it affects layout/visuals
            pinnedGifts.requestLayout()
        }

        // --- Reparent Pinned Message ---
        val pinnedContainer = binding.pinnedMessageContainer
        if (pinnedContainer.parent != binding.root) {
            originalPinnedParent = pinnedContainer.parent as android.view.ViewGroup
            originalPinnedIndex = originalPinnedParent?.indexOfChild(pinnedContainer) ?: -1
            originalPinnedParams = pinnedContainer.layoutParams
            
            originalPinnedParent?.removeView(pinnedContainer)
            
            val root = binding.root as android.view.ViewGroup
            val newParams = ConstraintLayout.LayoutParams(ConstraintLayout.LayoutParams.MATCH_PARENT, ConstraintLayout.LayoutParams.WRAP_CONTENT)
            newParams.topToBottom = binding.pinnedGiftsBlur.id // Below Pinned Gifts
            newParams.startToStart = ConstraintLayout.LayoutParams.PARENT_ID
            newParams.endToEnd = ConstraintLayout.LayoutParams.PARENT_ID
            newParams.marginStart = (12 * activity.resources.displayMetrics.density).toInt()
            newParams.marginEnd = (12 * activity.resources.displayMetrics.density).toInt()
            
            root.addView(pinnedContainer, newParams)
        }
        
        // --- Reparent Poll ---
        val pollContainer = binding.pollContainer
        if (pollContainer.parent != binding.root) {
            originalPollParent = pollContainer.parent as android.view.ViewGroup
            originalPollIndex = originalPollParent?.indexOfChild(pollContainer) ?: -1
            originalPollParams = pollContainer.layoutParams
            
            originalPollParent?.removeView(pollContainer)
            
            val root = binding.root as android.view.ViewGroup
            val newParams = ConstraintLayout.LayoutParams(ConstraintLayout.LayoutParams.MATCH_PARENT, ConstraintLayout.LayoutParams.WRAP_CONTENT)
            newParams.topToBottom = binding.pinnedMessageContainer.id 
            newParams.startToStart = ConstraintLayout.LayoutParams.PARENT_ID
            newParams.endToEnd = ConstraintLayout.LayoutParams.PARENT_ID
            newParams.marginStart = (12 * activity.resources.displayMetrics.density).toInt()
            newParams.marginEnd = (12 * activity.resources.displayMetrics.density).toInt()
            
            root.addView(pollContainer, newParams)
        }

        // --- Reparent Prediction ---
        val predictionContainer = binding.predictionContainer
        if (predictionContainer.parent != binding.root) {
            originalPredictionParent = predictionContainer.parent as android.view.ViewGroup
            originalPredictionIndex = originalPredictionParent?.indexOfChild(predictionContainer) ?: -1
            originalPredictionParams = predictionContainer.layoutParams
            
            originalPredictionParent?.removeView(predictionContainer)
            
            val root = binding.root as android.view.ViewGroup
            val newParams = ConstraintLayout.LayoutParams(ConstraintLayout.LayoutParams.MATCH_PARENT, ConstraintLayout.LayoutParams.WRAP_CONTENT)
            newParams.topToBottom = binding.pollContainer.id
            newParams.startToStart = ConstraintLayout.LayoutParams.PARENT_ID
            newParams.endToEnd = ConstraintLayout.LayoutParams.PARENT_ID
            newParams.marginStart = (12 * activity.resources.displayMetrics.density).toInt()
            newParams.marginEnd = (12 * activity.resources.displayMetrics.density).toInt()
            
            root.addView(predictionContainer, newParams)
        }

        ViewCompat.setElevation(binding.pinnedMessageContainer, 150f)
        ViewCompat.setZ(binding.pinnedMessageContainer, 150f)
        
        ViewCompat.setElevation(binding.pollContainer, 150f)
        ViewCompat.setZ(binding.pollContainer, 150f)
        
        ViewCompat.setElevation(binding.predictionContainer, 150f)
        ViewCompat.setZ(binding.predictionContainer, 150f)

        // Setup chat scroll listener for expand/collapse behavior
        setupTheatreModeChatScrollListener()

        // Update fullscreen state
        fullscreenManager.isFullscreen = true
        updateFullscreenButtonState()

        // --- Setup Video for Theatre Mode ---
        16f / 9f
        // val targetZoomWidth = (screenHeight * videoAspectRatio).toInt() // Initial calculation only needed for Zoom toggle

        val videoParams = binding.videoContainer.layoutParams as ConstraintLayout.LayoutParams
        originalVideoDimensionRatio = videoParams.dimensionRatio

        // Default: FIT (Width 100%, Height adapted by ratio)
        val targetRatio = if (currentVideoWidth > 0 && currentVideoHeight > 0) {
             "$currentVideoWidth:$currentVideoHeight"
        } else {
             originalVideoDimensionRatio ?: "16:9"
        }

        videoParams.dimensionRatio = targetRatio
        videoParams.width = ConstraintLayout.LayoutParams.MATCH_CONSTRAINT
        videoParams.height = ConstraintLayout.LayoutParams.MATCH_CONSTRAINT
        
        // Remove percentage constraints
        videoParams.matchConstraintPercentHeight = 1.0f
        videoParams.matchConstraintPercentWidth = 1.0f
        
        // Manual positioning with margin to define the "Box"
        // Top: 15%, Bottom: 35% (Space for chat)
        videoParams.topMargin = (screenHeight * 0.15f).toInt()
        videoParams.bottomMargin = (screenHeight * 0.35f).toInt()
        
        // Ensure constraints are set
        videoParams.topToTop = ConstraintLayout.LayoutParams.PARENT_ID
        videoParams.bottomToBottom = ConstraintLayout.LayoutParams.PARENT_ID
        videoParams.startToStart = ConstraintLayout.LayoutParams.PARENT_ID
        videoParams.endToEnd = ConstraintLayout.LayoutParams.PARENT_ID
        
        videoParams.verticalBias = 0.5f 
        
        binding.videoContainer.layoutParams = videoParams
        
        isTheatreFitMode = true
        
        // Auto-switch to Zoom (Fill) mode for Portrait content
        if (currentVideoHeight > 0 && currentVideoHeight > currentVideoWidth) {
             toggleTheatreFitMode()
        }

        val longClickListener = android.view.View.OnLongClickListener {
            toggleTheatreFitMode()
            true
        }
        binding.videoContainer.setOnLongClickListener(longClickListener)
        binding.videoOverlay.setOnLongClickListener(longClickListener)
        binding.theatreBackground.setOnLongClickListener(longClickListener)
        binding.playPauseOverlay.setOnLongClickListener(longClickListener)
        
        // --- Setup Theatre Background ---
        binding.theatreBackground.visibility = View.VISIBLE
        binding.theatreBackground.setImageDrawable(null)
        binding.theatreBackground.setBackgroundColor(android.graphics.Color.BLACK)

        binding.theatreBackground.setOnClickListener {
            if (isTheatreMode) {
                activity.handleScreenTap()
            }
        }

        // Show toast
        Toast.makeText(activity, R.string.theatre_mode_enabled, Toast.LENGTH_SHORT).show()
    }
    
    // Theatre mode chat heights
    private var theatreModeChatCollapsedHeight = 0
    private var theatreModeChatExpandedHeight = 0
    private var chatCollapseHandler: android.os.Handler? = null
    private var chatCollapseRunnable: Runnable? = null
    
    /**
     * Sets up scroll listener for chat to expand/collapse in Theatre Mode
     */
    private fun setupTheatreModeChatScrollListener() {
        binding.chatRecyclerView.addOnScrollListener(object : androidx.recyclerview.widget.RecyclerView.OnScrollListener() {
            override fun onScrollStateChanged(recyclerView: androidx.recyclerview.widget.RecyclerView, newState: Int) {
                super.onScrollStateChanged(recyclerView, newState)
                if (!isTheatreMode) return
                if (isTheatreFitMode) return // Don't expand/collapse in Fit mode
                
                when (newState) {
                    androidx.recyclerview.widget.RecyclerView.SCROLL_STATE_DRAGGING -> {
                        // User started scrolling - expand chat
                        cancelChatCollapse()
                        expandTheatreModeChat()
                    }
                    androidx.recyclerview.widget.RecyclerView.SCROLL_STATE_IDLE -> {
                        // User stopped scrolling - schedule collapse if auto-scroll is enabled
                        if (chatUiManager.isChatAutoScrollEnabled) {
                            scheduleChatCollapse()
                        }
                    }
                }
            }
        })
    }
    
    private fun expandTheatreModeChat() {
        val chatParams = binding.chatContainer.layoutParams as ConstraintLayout.LayoutParams
        if (chatParams.height == theatreModeChatExpandedHeight) return
        
        // Animate expansion
        val animator = android.animation.ValueAnimator.ofInt(chatParams.height, theatreModeChatExpandedHeight)
        animator.duration = 200
        animator.addUpdateListener { animation ->
            val params = binding.chatContainer.layoutParams as ConstraintLayout.LayoutParams
            params.height = animation.animatedValue as Int
            binding.chatContainer.layoutParams = params
        }
        animator.start()
    }
    
    private fun collapseTheatreModeChat() {
        val chatParams = binding.chatContainer.layoutParams as ConstraintLayout.LayoutParams
        if (chatParams.height == theatreModeChatCollapsedHeight) return
        
        // Animate collapse
        val animator = android.animation.ValueAnimator.ofInt(chatParams.height, theatreModeChatCollapsedHeight)
        animator.duration = 200
        animator.addUpdateListener { animation ->
            val params = binding.chatContainer.layoutParams as ConstraintLayout.LayoutParams
            params.height = animation.animatedValue as Int
            binding.chatContainer.layoutParams = params
        }
        animator.start()
    }
    
    private fun scheduleChatCollapse() {
        cancelChatCollapse()
        chatCollapseHandler = android.os.Handler(android.os.Looper.getMainLooper())
        chatCollapseRunnable = Runnable {
            if (isTheatreMode) {
                collapseTheatreModeChat()
            }
        }
        chatCollapseHandler?.postDelayed(chatCollapseRunnable!!, 3000) // 3 seconds
        
        // Ensure chat padding is reset for Theatre Mode (should be 0)
        activity.channelUiManager.updateChatPaddingForPanels(false)
    }
    
    private fun cancelChatCollapse() {
        chatCollapseRunnable?.let { chatCollapseHandler?.removeCallbacks(it) }
        chatCollapseRunnable = null
        chatCollapseHandler = null
    }

    /**
     * Exits Theatre Mode back to normal portrait view.
     */
    fun exitTheatreMode() {
        if (!isTheatreMode) return

        // Cancel any pending chat collapse
        cancelChatCollapse()
        
        // Restore original insets listener for chat container
        // This ensures normal behavior when exiting theatre mode
        activity.setupChatContainerInsetsListener()
        
        // Remove old keyboard listener if any
        removeTheatreModeKeyboardListener()
        binding.chatInputRoot.translationY = 0f
        binding.quickEmoteRecyclerView.translationY = 0f
        
        // Reset clipping
        binding.chatContainer.clipChildren = true
        binding.chatContainer.clipToPadding = true
        binding.root.clipChildren = true
        binding.playerScreenContainer.clipChildren = true
        
        // Show seekbar again
        binding.playerSeekBar.visibility = View.VISIBLE

        isTheatreMode = false

        // Respect system rotation setting when exiting theatre mode
        if (activity.isRotationAllowed()) {
            activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR
        } else {
            activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
        }
        // activity.window.clearFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN)

        // --- Restore Video ---
        if (originalVideoDimensionRatio != null) {
            val videoParams = binding.videoContainer.layoutParams as ConstraintLayout.LayoutParams
            videoParams.dimensionRatio = originalVideoDimensionRatio
            videoParams.width = ConstraintLayout.LayoutParams.MATCH_PARENT
            videoParams.height = ConstraintLayout.LayoutParams.MATCH_CONSTRAINT
            binding.videoContainer.layoutParams = videoParams
        }
        
        binding.videoContainer.setOnLongClickListener(null)
        binding.videoOverlay.setOnLongClickListener(null)
        binding.theatreBackground.setOnLongClickListener(null)
        binding.playPauseOverlay.setOnLongClickListener(null)
        binding.theatreBackground.setOnClickListener(null)
        binding.theatreBackground.visibility = View.GONE

        // Show system bars
        WindowInsetsControllerCompat(activity.window, activity.window.decorView).show(WindowInsetsCompat.Type.systemBars())
        
        // Restore standard player nav bar color (#0f0f0f)
        activity.updateNavigationBarColor(true)

        // Show global header (Disabled by user request)
        // binding.mobileHeader.visibility = View.VISIBLE
        binding.fullscreenButton.visibility = View.VISIBLE
        binding.theatreModeButton.visibility = View.VISIBLE
        // binding.uptimeDivider.visibility = View.VISIBLE // Restored via reparenting

        // --- Restore InfoPanel ---
        val infoPanel = binding.infoPanel
        val rootGroup = binding.root as android.view.ViewGroup
        
        if (originalInfoPanelParent != null && infoPanel.parent == rootGroup) {
            rootGroup.removeView(infoPanel)
            val parent = originalInfoPanelParent!!
            try {
                val safeIndex = if (originalInfoPanelIndex >= 0 && originalInfoPanelIndex <= parent.childCount) originalInfoPanelIndex else -1
                parent.addView(infoPanel, safeIndex, originalInfoPanelParams)
            } catch (e: Exception) {
                Log.e("FullscreenToggleManager", "Failed to restore InfoPanel at index $originalInfoPanelIndex", e)
                parent.addView(infoPanel, originalInfoPanelParams)
            }
        }
        originalInfoPanelParent = null
        binding.infoPanel.setBackgroundColor(android.graphics.Color.parseColor("#CC0f0f0f")) 

        // --- Restore ActionBar ---
        val actionBar = binding.actionBar
        if (originalActionBarParent != null && actionBar.parent == rootGroup) {
            rootGroup.removeView(actionBar)
            val parent = originalActionBarParent!!
            try {
                val safeIndex = if (originalActionBarIndex >= 0 && originalActionBarIndex <= parent.childCount) originalActionBarIndex else -1
                parent.addView(actionBar, safeIndex, originalActionBarParams)
            } catch (e: Exception) {
                Log.e("FullscreenToggleManager", "Failed to restore ActionBar at index $originalActionBarIndex", e)
                parent.addView(actionBar, originalActionBarParams)
            }
        }
        originalActionBarParent = null
        binding.actionBar.setBackgroundColor(android.graphics.Color.parseColor("#CC121212"))

        // --- Restore Pinned Gifts ---
        val pinnedGifts = binding.pinnedGiftsBlur
        if (originalPinnedGiftsParent != null && pinnedGifts.parent == rootGroup) {
            rootGroup.removeView(pinnedGifts)
            val parent = originalPinnedGiftsParent!!
            val safeIndex = if (originalPinnedGiftsIndex in 0..parent.childCount) originalPinnedGiftsIndex else -1
            parent.addView(pinnedGifts, safeIndex, originalPinnedGiftsParams)
        }
        originalPinnedGiftsParent = null
        val displayDensity = activity.resources.displayMetrics.density
        ViewCompat.setElevation(pinnedGifts, 10f * displayDensity) 
        ViewCompat.setZ(pinnedGifts, 0f)
        pinnedGifts.setBackgroundColor(android.graphics.Color.parseColor("#CC0f0f0f"))

        // --- Restore Pinned Message ---
        val pinnedContainer = binding.pinnedMessageContainer
        if (originalPinnedParent != null && pinnedContainer.parent == rootGroup) {
            rootGroup.removeView(pinnedContainer)
            val parent = originalPinnedParent!!
            try {
                val safeIndex = if (originalPinnedIndex >= 0 && originalPinnedIndex <= parent.childCount) originalPinnedIndex else -1
                parent.addView(pinnedContainer, safeIndex, originalPinnedParams)
            } catch (e: Exception) {
                Log.e("FullscreenToggleManager", "Failed to restore PinnedMessage at index $originalPinnedIndex", e)
                parent.addView(pinnedContainer, originalPinnedParams)
            }
        }
        originalPinnedParent = null

        // --- Restore Poll ---
        val pollContainer = binding.pollContainer
        if (originalPollParent != null && pollContainer.parent == rootGroup) {
            rootGroup.removeView(pollContainer)
            val parent = originalPollParent!!
            try {
                val safeIndex = if (originalPollIndex >= 0 && originalPollIndex <= parent.childCount) originalPollIndex else -1
                parent.addView(pollContainer, safeIndex, originalPollParams)
            } catch (e: Exception) {
                Log.e("FullscreenToggleManager", "Failed to restore Poll at index $originalPollIndex", e)
                parent.addView(pollContainer, originalPollParams)
            }
        }
        originalPollParent = null

        // --- Restore Prediction ---
        val predictionContainer = binding.predictionContainer
        if (originalPredictionParent != null && predictionContainer.parent == rootGroup) {
            rootGroup.removeView(predictionContainer)
            val parent = originalPredictionParent!!
            try {
                val safeIndex = if (originalPredictionIndex >= 0 && originalPredictionIndex <= parent.childCount) originalPredictionIndex else -1
                parent.addView(predictionContainer, safeIndex, originalPredictionParams)
            } catch (e: Exception) {
                Log.e("FullscreenToggleManager", "Failed to restore Prediction at index $originalPredictionIndex", e)
                parent.addView(predictionContainer, originalPredictionParams)
            }
        }
        originalPredictionParent = null

        // --- Restore Buttons from Top Bar (In Reverse Order of Removal) ---
        
        // 1. Mute Button
        val muteButton = binding.muteButton
        if (originalMuteParent != null && muteButton.parent == theatreTopBar) {
            theatreTopBar?.removeView(muteButton)
            val parent = originalMuteParent!!
            try {
                val safeIndex = if (originalMuteIndex >= 0 && originalMuteIndex <= parent.childCount) originalMuteIndex else -1
                parent.addView(muteButton, safeIndex, originalMuteParams)
            } catch (e: Exception) {
                Log.e("FullscreenToggleManager", "Failed to restore MuteButton", e)
                parent.addView(muteButton, originalMuteParams)
            }
        }
        originalMuteParent = null

        // 2. Clip Button
        val clipContainer = binding.clipButtonContainer 
        if (originalClipParent != null && clipContainer.parent == theatreTopBar) {
            theatreTopBar?.removeView(clipContainer)
            val parent = originalClipParent!!
            try {
                val safeIndex = if (originalClipIndex >= 0 && originalClipIndex <= parent.childCount) originalClipIndex else -1
                parent.addView(clipContainer, safeIndex, originalClipParams)
            } catch (e: Exception) {
                Log.e("FullscreenToggleManager", "Failed to restore ClipButton", e)
                parent.addView(clipContainer, originalClipParams)
            }
        }
        originalClipParent = null

        // 3. Settings Button
        val settingsBtn = binding.videoSettingsButton
        if (originalSettingsParent != null && settingsBtn.parent == theatreTopBar) {
            theatreTopBar?.removeView(settingsBtn)
            val parent = originalSettingsParent!!
            try {
                val displayDensity = activity.resources.displayMetrics.density
                settingsBtn.setPadding((8 * displayDensity).toInt(), (8 * displayDensity).toInt(), (8 * displayDensity).toInt(), (8 * displayDensity).toInt())
                
                val safeIndex = if (originalSettingsIndex >= 0 && originalSettingsIndex <= parent.childCount) originalSettingsIndex else -1
                parent.addView(settingsBtn, safeIndex, originalSettingsParams)
            } catch (e: Exception) {
                Log.e("FullscreenToggleManager", "Failed to restore SettingsButton", e)
                parent.addView(settingsBtn, originalSettingsParams)
            }
        }
        originalSettingsParent = null
        
        // 4. Quality Badge
        val qualityBadge = binding.videoQualityBadge
        if (originalQualityParent != null && qualityBadge.parent == theatreTopBar) {
            theatreTopBar?.removeView(qualityBadge)
            val parent = originalQualityParent!!
            try {
                val safeIndex = if (originalQualityIndex >= 0 && originalQualityIndex <= parent.childCount) originalQualityIndex else -1
                parent.addView(qualityBadge, safeIndex, originalQualityParams)
            } catch (e: Exception) {
                 Log.e("FullscreenToggleManager", "Failed to restore QualityBadge", e)
                 parent.addView(qualityBadge, originalQualityParams)
            }
        }
        originalQualityParent = null

        // 5. Stream Time
        val timeBadge = binding.streamTimeBadge
        if (originalTimeParent != null && timeBadge.parent == theatreTopBar) {
            theatreTopBar?.removeView(timeBadge)
             val parent = originalTimeParent!!
            try {
                val safeIndex = if (originalTimeIndex >= 0 && originalTimeIndex <= parent.childCount) originalTimeIndex else -1
                parent.addView(timeBadge, safeIndex, originalTimeParams)
            } catch (e: Exception) {
                Log.e("FullscreenToggleManager", "Failed to restore TimeBadge", e)
                parent.addView(timeBadge, originalTimeParams)
            }
        }
        originalTimeParent = null
        
        // 6. Uptime Divider
        val uptimeDivider = binding.uptimeDivider
        if (originalDividerParent != null && uptimeDivider.parent == theatreTopBar) {
            theatreTopBar?.removeView(uptimeDivider)
            val parent = originalDividerParent!!
            try {
                val safeIndex = if (originalDividerIndex >= 0 && originalDividerIndex <= parent.childCount) originalDividerIndex else -1
                parent.addView(uptimeDivider, safeIndex, originalDividerParams)
                uptimeDivider.visibility = View.VISIBLE 
            } catch (e: Exception) {
                Log.e("FullscreenToggleManager", "Failed to restore UptimeDivider", e)
                parent.addView(uptimeDivider, originalDividerParams)
            }
        }
        originalDividerParent = null
        
        // 7. Viewer Layout
        val viewerLayout = binding.viewerLayout
        if (originalViewerParent != null && viewerLayout.parent == theatreTopBar) {
            theatreTopBar?.removeView(viewerLayout)
            val parent = originalViewerParent!!
            try {
                val safeIndex = if (originalViewerIndex >= 0 && originalViewerIndex <= parent.childCount) originalViewerIndex else -1
                parent.addView(viewerLayout, safeIndex, originalViewerParams)
            } catch (e: Exception) {
                Log.e("FullscreenToggleManager", "Failed to restore Viewer", e)
                parent.addView(viewerLayout, originalViewerParams)
            }
        }
        originalViewerParent = null

        // NOTE: Settings Button restoration moved above Clip restoration to maintain correct index order

        // Emote Combo
        val emoteCombo = binding.emoteComboContainer
        if (originalEmoteComboParent != null && emoteCombo.parent == rootGroup) {
            rootGroup.removeView(emoteCombo)
            val parent = originalEmoteComboParent!!
            try {
                val safeIndex = if (originalEmoteComboIndex >= 0 && originalEmoteComboIndex <= parent.childCount) originalEmoteComboIndex else -1
                parent.addView(emoteCombo, safeIndex, originalEmoteComboParams)
            } catch (e: Exception) {
                Log.e("FullscreenToggleManager", "Failed to restore EmoteCombo at index $originalEmoteComboIndex", e)
                parent.addView(emoteCombo, originalEmoteComboParams)
            }
        }
        originalEmoteComboParent = null

        // Floating Emote
        val floatingEmote = binding.floatingEmoteContainer
        if (originalFloatingEmoteParent != null && floatingEmote.parent == rootGroup) {
            rootGroup.removeView(floatingEmote)
            val parent = originalFloatingEmoteParent!!
            try {
                val safeIndex = if (originalFloatingEmoteIndex >= 0 && originalFloatingEmoteIndex <= parent.childCount) originalFloatingEmoteIndex else -1
                parent.addView(floatingEmote, safeIndex, originalFloatingEmoteParams)
            } catch (e: Exception) {
                Log.e("FullscreenToggleManager", "Failed to restore FloatingEmote at index $originalFloatingEmoteIndex", e)
                parent.addView(floatingEmote, originalFloatingEmoteParams)
            }
        }
        originalFloatingEmoteParent = null

        // Remove Theatre Top Bar
        if (theatreTopBar != null) {
            rootGroup.removeView(theatreTopBar)
            theatreTopBar = null
        }

        // Reset elevations for Pinned/Poll/Prediction
        ViewCompat.setElevation(binding.pinnedMessageContainer, 0f)
        ViewCompat.setZ(binding.pinnedMessageContainer, 0f)
        
        ViewCompat.setElevation(binding.pollContainer, 0f)
        ViewCompat.setZ(binding.pollContainer, 0f)
        
        ViewCompat.setElevation(binding.predictionContainer, 0f)
        ViewCompat.setZ(binding.predictionContainer, 0f)

        // Reset info panel and action bar settings
        ViewCompat.setElevation(binding.infoPanel, 0f)
        ViewCompat.setZ(binding.infoPanel, 0f)
        
        ViewCompat.setElevation(binding.actionBar, 10f * displayDensity)
        ViewCompat.setZ(binding.actionBar, 10f * displayDensity)

        // Show action bar and info panel based on state
        binding.actionBar.visibility = if (vodManager.currentPlaybackMode != dev.xacnio.kciktv.mobile.ui.player.VodManager.PlaybackMode.LIVE) View.GONE else View.VISIBLE
        binding.infoPanel.visibility = View.VISIBLE

        // Reset video container to 16:9 at top
        val videoParams = binding.videoContainer.layoutParams as ConstraintLayout.LayoutParams
        videoParams.width = 0
        videoParams.height = 0
        videoParams.dimensionRatio = "16:9"
        videoParams.topToTop = ConstraintLayout.LayoutParams.PARENT_ID
        videoParams.bottomToBottom = ConstraintLayout.LayoutParams.UNSET
        videoParams.startToStart = ConstraintLayout.LayoutParams.PARENT_ID
        videoParams.endToEnd = ConstraintLayout.LayoutParams.PARENT_ID
        videoParams.topMargin = 0
        binding.videoContainer.layoutParams = videoParams

        // Reset chat container to normal position below video
        val chatParams = binding.chatContainer.layoutParams as ConstraintLayout.LayoutParams
        chatParams.topToTop = ConstraintLayout.LayoutParams.UNSET
        chatParams.topToBottom = R.id.videoContainer
        chatParams.bottomToBottom = ConstraintLayout.LayoutParams.PARENT_ID
        chatParams.startToStart = ConstraintLayout.LayoutParams.PARENT_ID
        chatParams.endToEnd = ConstraintLayout.LayoutParams.PARENT_ID
        chatParams.width = 0
        chatParams.height = 0
        chatParams.matchConstraintPercentHeight = 1f
        chatParams.bottomMargin = 0 // Reset theatre mode margin
        binding.chatContainer.layoutParams = chatParams

        // Reset chat container styling
        binding.chatContainer.setBackgroundColor(Color.parseColor("#0f0f0f"))
        ViewCompat.setElevation(binding.chatContainer, 0f)
        ViewCompat.setZ(binding.chatContainer, 0f)
        
        // Reset chat container padding
        binding.chatContainer.setPadding(
            binding.chatContainer.paddingLeft,
            binding.chatContainer.paddingTop,
            binding.chatContainer.paddingRight,
            0 // Reset bottom padding
        )

        // Reset chat list container background
        binding.chatListContainer.setBackgroundColor(android.graphics.Color.TRANSPARENT)
        binding.chatInputRoot.setBackgroundColor(android.graphics.Color.parseColor("#1a1a1a"))
        binding.chatInput.setBackgroundResource(dev.xacnio.kciktv.R.drawable.bg_chat_input)
        
        // Reset chatInputRoot bottom margin and elevation
        val chatInputParams = binding.chatInputRoot.layoutParams as ConstraintLayout.LayoutParams
        chatInputParams.bottomMargin = 0
        binding.chatInputRoot.layoutParams = chatInputParams
        ViewCompat.setElevation(binding.chatInputRoot, 0f)
        ViewCompat.setZ(binding.chatInputRoot, 0f)
        ViewCompat.setElevation(binding.quickEmoteRecyclerView, 0f)
        ViewCompat.setZ(binding.quickEmoteRecyclerView, 0f)
        
        // Restore Return to Live Button
        val returnLiveBtn = binding.returnToLiveButton
        if (originalReturnLiveParent != null) {
            (returnLiveBtn.parent as? android.view.ViewGroup)?.removeView(returnLiveBtn)
            val parent = originalReturnLiveParent!!
            try {
                val safeIndex = if (originalReturnLiveIndex >= 0 && originalReturnLiveIndex <= parent.childCount) originalReturnLiveIndex else -1
                parent.addView(returnLiveBtn, safeIndex, originalReturnLiveParams)
            } catch (e: Exception) {
                 parent.addView(returnLiveBtn, originalReturnLiveParams)
            }
        }
        originalReturnLiveParent = null
        
        // Reset quick emote bar background
        
        // Reset quick emote bar background
        binding.quickEmoteRecyclerView.setBackgroundColor(android.graphics.Color.parseColor("#151515"))
        
        // Hide bottom gradient
        binding.theatreBottomGradient.visibility = View.GONE

        // Restore video overlay to original parent
        val overlay = binding.videoOverlay
        val root = binding.root as android.view.ViewGroup
        
        if (originalOverlayParent != null && overlay.parent == root) {
            root.removeView(overlay)
            val parent = originalOverlayParent!!
            try {
                val safeIndex = if (originalOverlayIndex >= 0 && originalOverlayIndex <= parent.childCount) originalOverlayIndex else -1
                parent.addView(overlay, safeIndex, originalOverlayParams)
            } catch (e: Exception) {
                Log.e("FullscreenToggleManager", "Failed to restore Overlay at index $originalOverlayIndex", e)
                parent.addView(overlay, originalOverlayParams)
            }
        } else if (overlay.parent == root) {
            // Fallback if original parent lost but it is at root
             val overlayParams = android.widget.FrameLayout.LayoutParams(
                android.widget.FrameLayout.LayoutParams.MATCH_PARENT, 
                android.widget.FrameLayout.LayoutParams.MATCH_PARENT
            )
            overlayParams.gravity = android.view.Gravity.BOTTOM
            overlay.layoutParams = overlayParams
        }
        originalOverlayParent = null
        
        // Reset video overlay to normal (if restored to FrameLayout)
        if (binding.videoOverlay.layoutParams is android.widget.FrameLayout.LayoutParams) {
            val overlayParams = binding.videoOverlay.layoutParams as android.widget.FrameLayout.LayoutParams
            overlayParams.width = android.widget.FrameLayout.LayoutParams.MATCH_PARENT
            overlayParams.gravity = android.view.Gravity.BOTTOM
            binding.videoOverlay.layoutParams = overlayParams
        }
        
        // Clear overlay background and reset properties
        binding.videoOverlay.background = null
        ViewCompat.setElevation(binding.videoOverlay, 0f)
        ViewCompat.setZ(binding.videoOverlay, 0f)
        binding.videoOverlay.isClickable = false
        
        // Restore Standard UI - info panel visible, header stays hidden while player is active
        // mobileHeader should remain GONE during player - it gets restored when navigating away from player
        binding.infoPanel.visibility = View.VISIBLE
        
        // Action Bar logic depends on VOD/Live status
        binding.actionBar.visibility = if (vodManager.currentPlaybackMode != dev.xacnio.kciktv.mobile.ui.player.VodManager.PlaybackMode.LIVE) View.GONE else View.VISIBLE
        
        // Ensure InfoPanel is above chat container (which is 0f)
        ViewCompat.setElevation(binding.infoPanel, 100f)
        ViewCompat.setZ(binding.infoPanel, 100f)
        binding.infoPanel.translationY = 0f

        // Force layout update to fix positioning issues using post to run after current frame
        binding.root.post {
             binding.playerScreenContainer.requestLayout()
             binding.root.requestLayout()
             binding.infoPanel.requestLayout()
             
             // Force show controls and UI to ensure consistent state when exiting theatre mode
             // This solves the issue where UI elements might remain hidden if controls were hidden in theatre mode
             binding.videoOverlay.visibility = android.view.View.VISIBLE
             binding.videoOverlay.alpha = 1f
             
             binding.infoPanel.visibility = android.view.View.VISIBLE
             
             // Update chat padding to account for restored panels
             activity.channelUiManager.updateChatPaddingForPanels(false)
        }
        
        isTheatreMode = false
        fullscreenManager.isFullscreen = false
        updateFullscreenButtonState()
        
        // Update PiP state to re-enable auto-PiP
        Log.d("FullscreenToggleManager", "exitTheatreMode: Updating PiP UI to re-enable auto-PiP")
        activity.updatePiPUi()
    }

    /**
     * Enters fullscreen (landscape) mode.
     */
    fun enterFullscreen() {
        // Exit theatre mode first if active
        if (isTheatreMode) {
            exitTheatreMode()
        }

        val isTablet = activity.isTabletOrLargeWindow()
        
        // For phones: allow 180° landscape flip if system auto-rotate is ON
        // For tablets: allow full sensor rotation
        if (isTablet) {
            activity.setAllowedOrientation() // Tablets use full sensor
        } else {
            // Check if system auto-rotate is enabled
            val autoRotateEnabled = android.provider.Settings.System.getInt(
                activity.contentResolver,
                android.provider.Settings.System.ACCELEROMETER_ROTATION,
                0
            ) == 1
            
            if (autoRotateEnabled) {
                // Allow flipping between landscape left and landscape right
                activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
            } else {
                // Lock to current landscape
                activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
            }
        }
        // FULLSCREEN is handled by WindowInsetsControllerCompat below

        // Hide system bars
        val windowInsetsController = WindowInsetsControllerCompat(activity.window, activity.window.decorView)
        windowInsetsController.hide(WindowInsetsCompat.Type.systemBars())
        windowInsetsController.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE

        // Reset playerScreenContainer padding (system bars are hidden in fullscreen)
        binding.playerScreenContainer.setPadding(0, 0, 0, 0)

        // Hide chat and other UI
        binding.chatContainer.visibility = View.GONE
        binding.actionBar.visibility = View.GONE
        binding.infoPanel.visibility = View.GONE
        binding.theatreModeButton.visibility = View.GONE

        // Expand player to match parent
        val params = binding.videoContainer.layoutParams as ConstraintLayout.LayoutParams
        params.dimensionRatio = null
        params.width = ConstraintLayout.LayoutParams.MATCH_PARENT
        params.height = ConstraintLayout.LayoutParams.MATCH_PARENT
        // Clear any margins left from theatre mode
        params.topMargin = 0
        params.bottomMargin = 0
        params.marginStart = 0
        params.marginEnd = 0
        params.verticalBias = 0.5f
        binding.videoContainer.layoutParams = params

        fullscreenManager.isFullscreen = true
        updateFullscreenButtonState()

        // In fullscreen mode, hide side chat if visible (tablet landscape)
        if (isSideChatVisible) {
            cleanupSideChat()
        }
    }

    /**
     * Exits fullscreen mode back to portrait.
     * @param forceCleanReset If true, always reset to portrait mode without auto-applying split screen.
     *                         Used during playChannel to ensure clean state before re-applying layout.
     */
    fun exitFullscreen(forceCleanReset: Boolean = false) {
        // Check if we're in theatre mode and exit that instead
        if (isTheatreMode) {
            exitTheatreMode()
            return
        }

        val isTablet = activity.isTabletOrLargeWindow()
        val isLandscape = activity.resources.configuration.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE

        // Only apply tablet landscape special handling if NOT doing a force reset
        if (isTablet && isLandscape && !forceCleanReset) {
            // On tablets in landscape, exiting fullscreen should just show system bars
            // but keep the split-screen (side chat) layout.
            WindowInsetsControllerCompat(activity.window, activity.window.decorView).show(WindowInsetsCompat.Type.systemBars())
            
            // Request insets reapplication for playerScreenContainer padding
            ViewCompat.requestApplyInsets(binding.playerScreenContainer)
            
            // CRITICAL: Unlock orientation so user can rotate back to portrait manually (respects system lock)
            activity.setAllowedOrientation()
            
            fullscreenManager.isFullscreen = false
            updateFullscreenButtonState()
            
            // Ensure side chat is shown (it should be already, but safety first)
            if (!isSideChatVisible && !isTheatreMode) {
                showSideChat(skipAnimation = true)
            }
            return
        }

        // For phones, stay in portrait; for tablets, allow sensor rotation (respects system lock)
        if (isTablet) {
            activity.setAllowedOrientation()
        } else {
            // For phones, respect system rotation setting
            if (activity.isRotationAllowed()) {
                activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR
            } else {
                activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
            }
        }
        // FULLSCREEN flag cleared via systemBars() show below
        
        // Show system bars
        WindowInsetsControllerCompat(activity.window, activity.window.decorView).show(WindowInsetsCompat.Type.systemBars())

        // Request insets reapplication for playerScreenContainer padding
        ViewCompat.requestApplyInsets(binding.playerScreenContainer)

        // Force cleanup side chat without animation if visible
        cleanupSideChat()

        // Show chat and other UI
        binding.chatContainer.visibility = View.VISIBLE
        binding.actionBar.visibility = if (vodManager.currentPlaybackMode != dev.xacnio.kciktv.mobile.ui.player.VodManager.PlaybackMode.LIVE) View.GONE else View.VISIBLE
        binding.infoPanel.visibility = View.VISIBLE
        binding.theatreModeButton.visibility = View.VISIBLE

        // Reset player layout params for Portrait (16:9, Top of screen)
        val params = binding.videoContainer.layoutParams as ConstraintLayout.LayoutParams
        params.dimensionRatio = "16:9"
        params.width = 0 // MATCH_CONSTRAINT
        params.height = 0 // MATCH_CONSTRAINT
        
        // Restore Standard Constraints
        params.startToStart = ConstraintLayout.LayoutParams.PARENT_ID
        params.endToEnd = ConstraintLayout.LayoutParams.PARENT_ID
        params.topToTop = ConstraintLayout.LayoutParams.PARENT_ID
        
        // Clear Fullscreen Constraints
        params.bottomToBottom = ConstraintLayout.LayoutParams.UNSET
        params.endToStart = ConstraintLayout.LayoutParams.UNSET
        
        binding.videoContainer.layoutParams = params

        fullscreenManager.isFullscreen = false
        updateFullscreenButtonState()
        
        // Update chat padding to account for restored panels
        activity.channelUiManager.updateChatPaddingForPanels(false)
    }

    /**
     * Updates the fullscreen button icon based on current state.
     */
    fun updateFullscreenButtonState() {
        val iconRes = if (activity.isFullscreen) R.drawable.ic_fullscreen_exit else R.drawable.ic_fullscreen
        binding.fullscreenButton.setImageResource(iconRes)
    }

    /**
     * Toggles side chat in landscape mode.
     */
    fun toggleSideChat() {
        if (isSideChatVisible) {
            hideSideChat()
        } else {
            showSideChat()
        }
    }

    /**
     * Shows the chat as a side overlay in landscape mode.
     * Resizes the video to fit in the remaining space on the left.
     */
    fun showSideChat(skipAnimation: Boolean = false) {
        // Use actual root dimensions for landscape detection (more reliable than configuration)
        val checkWidth = binding.root.width
        val checkHeight = binding.root.height
        val isLandscape = if (checkWidth > 0 && checkHeight > 0) {
            checkWidth > checkHeight
        } else {
            activity.resources.configuration.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE
        }
        
        android.util.Log.d("FullscreenToggle", "showSideChat: isLandscape=$isLandscape, isSideChatVisible=$isSideChatVisible, isTheatreMode=$isTheatreMode, root=${checkWidth}x${checkHeight}")
        
        // Side-by-side split screen is EXCLUSIVELY for landscape tablet mode
        if (isSideChatVisible || !isLandscape || isTheatreMode) return
        
        val displayMetrics = activity.resources.displayMetrics
        var rootWidth = checkWidth
        var rootHeight = checkHeight
        
        // If dimensions are zero or don't match the actual device orientation (stale after rotate)
        // use display metrics as a reliable fallback
        val isLandscapeDevice = displayMetrics.widthPixels > displayMetrics.heightPixels
        val dimensionsMatch = if (isLandscape) isLandscapeDevice else !isLandscapeDevice
        
        if (rootWidth == 0) rootWidth = displayMetrics.widthPixels
        
        val chat = binding.chatContainer
        val isTablet = activity.isTabletOrLargeWindow()
        
        // Save original parent and params
        originalChatParent = chat.parent as? android.view.ViewGroup
        originalChatIndex = originalChatParent?.indexOfChild(chat) ?: -1
        originalChatParams = chat.layoutParams
        
        // Remove from current parent
        originalChatParent?.removeView(chat)
        
        // Add to side container
        binding.sideChatContainer.visibility = View.VISIBLE
        binding.sideChatContainer.addView(chat)
        
        // Update params for the new LinearLayout container
        val chatParams = if (isTablet) {
            android.widget.LinearLayout.LayoutParams(
                android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
                0,
                1.0f
            )
        } else {
            android.widget.LinearLayout.LayoutParams(
                android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
                android.widget.LinearLayout.LayoutParams.MATCH_PARENT
            )
        }
        chat.layoutParams = chatParams
        chat.visibility = View.VISIBLE

        // --- Reparent ActionBar (Tablets) ---
        if (isTablet) {
            val actionBar = binding.actionBar
            if (actionBar.parent != binding.sideChatContainer) {
                originalActionBarParent = actionBar.parent as? android.view.ViewGroup
                originalActionBarIndex = originalActionBarParent?.indexOfChild(actionBar) ?: -1
                originalActionBarParams = actionBar.layoutParams
                
                originalActionBarParent?.removeView(actionBar)
                binding.sideChatContainer.addView(actionBar, 0) // Add to top of right side
                
                // Adjust actionBar params for the new LinearLayout parent
                val aParams = android.widget.LinearLayout.LayoutParams(
                    android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
                    (44 * activity.resources.displayMetrics.density).toInt()
                )
                actionBar.layoutParams = aParams
                actionBar.visibility = View.VISIBLE
                isActionBarReparentedToSide = true
            }
        }
        
        // Ensure background is solid for tablets (split view feel), semi-transparent for phones
        if (isTablet) {
            chat.setBackgroundColor(android.graphics.Color.parseColor("#0f0f0f"))
            binding.sideChatContainer.setBackgroundColor(android.graphics.Color.parseColor("#0f0f0f"))
            binding.sideChatContainer.elevation = 0f
            binding.infoPanel.visibility = View.VISIBLE
            
            // Update Chat Rules Container to Side Chat
            activity.chatRulesManager.updateContainer(binding.sideChatContainer)
        } else {
            chat.setBackgroundColor(android.graphics.Color.parseColor("#FF0F0F0F"))
            // Restore elevation for phones as it's an overlay there
            val density = activity.resources.displayMetrics.density
            binding.sideChatContainer.elevation = 180f * density
        }
        
        // Attach touch listener to chat so we can swipe it away too
        chat.setOnTouchListener { _, event ->
             activity.playerGestureManager.playerGestureDetector.onTouchEvent(event)
             false // Return false to allow clicks/scrolls to pass through
        }
        
        isSideChatVisible = true
        
        // Use wider chat for tablets
        val chatWidth = if (isTablet) {
            (400 * displayMetrics.density).toInt()
        } else {
            (350 * displayMetrics.density).toInt()
        }
        
        // Initial setup for chat
        binding.sideChatContainer.layoutParams.width = chatWidth
        
        // Setup video container for manual sizing
        val videoParams = binding.videoContainer.layoutParams as ConstraintLayout.LayoutParams
        
        if (isTablet) {
            // For tablets, we maintain 16:9 ratio so info bar is visible below on the left
            videoParams.dimensionRatio = "16:9"
            videoParams.height = 0 // MATCH_CONSTRAINT
            videoParams.bottomToBottom = ConstraintLayout.LayoutParams.UNSET
        } else {
            videoParams.dimensionRatio = null
            videoParams.height = 0 // MATCH_CONSTRAINT (Fill height)
            videoParams.bottomToBottom = ConstraintLayout.LayoutParams.PARENT_ID
        }
        
        videoParams.width = rootWidth // Start full width
        videoParams.startToStart = ConstraintLayout.LayoutParams.PARENT_ID
        videoParams.endToStart = ConstraintLayout.LayoutParams.UNSET 
        videoParams.endToEnd = ConstraintLayout.LayoutParams.UNSET
        videoParams.topToTop = ConstraintLayout.LayoutParams.PARENT_ID
        
        binding.videoContainer.layoutParams = videoParams

        if (skipAnimation) {
            binding.sideChatContainer.translationX = 0f
            // Use constraints for skipped animation
            videoParams.width = 0 // MATCH_CONSTRAINT
            videoParams.endToStart = R.id.sideChatContainer
            videoParams.endToEnd = ConstraintLayout.LayoutParams.UNSET
            binding.videoContainer.layoutParams = videoParams
            return
        }

        binding.sideChatContainer.translationX = chatWidth.toFloat()
        
        // Create synchronized animator
        val animator = android.animation.ValueAnimator.ofFloat(0f, 1f)
        animator.duration = 300
        animator.interpolator = android.view.animation.DecelerateInterpolator()
        
        animator.addUpdateListener { animation ->
            val fraction = animation.animatedFraction
            
            // 1. Update Video Width
            val newWidth = (rootWidth - (chatWidth * fraction)).toInt()
            val vParams = binding.videoContainer.layoutParams as ConstraintLayout.LayoutParams
            if (vParams.width != newWidth) {
                vParams.width = newWidth
                if (isTablet) {
                    vParams.dimensionRatio = "16:9"
                    vParams.height = 0
                } else {
                    vParams.dimensionRatio = null
                    vParams.height = 0 // MATCH_CONSTRAINT
                    vParams.bottomToBottom = ConstraintLayout.LayoutParams.PARENT_ID
                }
                binding.videoContainer.layoutParams = vParams
            }
            
            // 2. Update Chat Translation
            val translation = chatWidth * (1 - fraction)
            binding.sideChatContainer.translationX = translation
        }
        
        animator.addListener(object : android.animation.AnimatorListenerAdapter() {
            override fun onAnimationEnd(animation: android.animation.Animator) {
                // Finalize constraints to ensure child views (InfoPanel) resize correctly
                val vParams = binding.videoContainer.layoutParams as ConstraintLayout.LayoutParams
                vParams.width = 0 // MATCH_CONSTRAINT
                vParams.endToStart = R.id.sideChatContainer
                vParams.endToEnd = ConstraintLayout.LayoutParams.UNSET
                
                if (!isTablet) {
                    vParams.height = 0 // MATCH_CONSTRAINT
                    vParams.bottomToBottom = ConstraintLayout.LayoutParams.PARENT_ID
                }
                
                binding.videoContainer.layoutParams = vParams
            }
        })
        
        animator.start()
        
        // Force chat padding to 0 for phones to avoid gaps from overlay
        if (!isTablet) {
            binding.chatContainer.setPadding(binding.chatContainer.paddingLeft, 0, binding.chatContainer.paddingRight, binding.chatContainer.paddingBottom)
        }
    }

    /**
     * Hides the side overlay chat.
     * Restores the video to full screen size.
     */
    fun hideSideChat() {
        if (!isSideChatVisible) return
        
        val chat = binding.chatContainer
        val isTablet = activity.resources.getBoolean(R.bool.is_tablet)
        
        // Calculate dimensions
        val displayMetrics = activity.resources.displayMetrics
        var rootWidth = binding.root.width
        if (rootWidth == 0) rootWidth = displayMetrics.widthPixels
        val rootHeight = binding.root.height // Not critical for horizontal logic
        
        val chatWidth = binding.sideChatContainer.width.takeIf { it > 0 } 
            ?: (350 * displayMetrics.density).toInt()
            
        val currentVideoWidth = binding.videoContainer.width
        
        // Create synchronized animator
        val animator = android.animation.ValueAnimator.ofFloat(0f, 1f)
        animator.duration = 250
        animator.interpolator = android.view.animation.AccelerateInterpolator()
        
        animator.addUpdateListener { animation ->
            val fraction = animation.animatedFraction
            
            // 1. Update Video Width (Grow back to rootWidth)
            val newWidth = (currentVideoWidth + ((rootWidth - currentVideoWidth) * fraction)).toInt()
            val params = binding.videoContainer.layoutParams as ConstraintLayout.LayoutParams
            if (params.width != newWidth) {
                params.width = newWidth
                if (isTablet) {
                    params.dimensionRatio = "16:9"
                    params.height = 0
                } else {
                    params.dimensionRatio = null
                    params.height = 0 // MATCH_CONSTRAINT
                    params.bottomToBottom = ConstraintLayout.LayoutParams.PARENT_ID
                }
                binding.videoContainer.layoutParams = params
            }
            
            // 2. Update Chat Translation (Slide out)
            val translation = chatWidth * fraction
            binding.sideChatContainer.translationX = translation
        }
        
        animator.addListener(object : android.animation.AnimatorListenerAdapter() {
            override fun onAnimationEnd(animation: android.animation.Animator) {
                binding.sideChatContainer.visibility = View.GONE
                binding.sideChatContainer.removeView(chat)
                chat.setOnTouchListener(null)
                
                // Restore video container to standard full screen constraints
                val isLandscapeNow = activity.resources.configuration.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE
                val params = binding.videoContainer.layoutParams as ConstraintLayout.LayoutParams
                params.width = ConstraintLayout.LayoutParams.MATCH_PARENT
                
                if (isLandscapeNow || activity.isFullscreen) {
                    // Landscape or Fullscreen -> Full screen video
                    params.height = ConstraintLayout.LayoutParams.MATCH_PARENT
                    params.dimensionRatio = null
                } else {
                    // Portrait -> 16:9 Video at top
                    params.height = 0
                    params.dimensionRatio = "16:9"
                }
                
                params.endToStart = ConstraintLayout.LayoutParams.UNSET
                params.endToEnd = ConstraintLayout.LayoutParams.PARENT_ID
                binding.videoContainer.layoutParams = params
                
                 // Restore to original parent
                if (originalChatParent != null) {
                    val safeIndex = if (originalChatIndex in 0..originalChatParent!!.childCount) originalChatIndex else -1
                    originalChatParent?.addView(chat, safeIndex, originalChatParams)
                }
                
                // If still in landscape fullscreen, keep hidden
                if (activity.isFullscreen && !isTheatreMode) {
                    chat.visibility = View.GONE
                } else {
                    // Portrait - restore background
                    chat.setBackgroundColor(android.graphics.Color.parseColor("#0f0f0f"))
                    chat.visibility = View.VISIBLE
                }
                
                // Restore ActionBar if reparented
                if (isActionBarReparentedToSide) {
                    val actionBar = binding.actionBar
                    binding.sideChatContainer.removeView(actionBar)
                    if (originalActionBarParent != null) {
                        val safeIndex = if (originalActionBarIndex in 0..originalActionBarParent!!.childCount) originalActionBarIndex else -1
                        originalActionBarParent?.addView(actionBar, safeIndex, originalActionBarParams)
                    }
                    isActionBarReparentedToSide = false
                }
                
                val isPortrait = activity.resources.configuration.orientation == android.content.res.Configuration.ORIENTATION_PORTRAIT
                if (isTablet && isPortrait && !isTheatreMode) {
                    binding.infoPanel.visibility = View.VISIBLE
                }

                isSideChatVisible = false
                originalChatParent = null
                originalActionBarParent = null
            }
        })
        
        animator.start()
    }
    fun onEnterPipMode() {
        // 1. Save Current Component State
        val currentParams = binding.videoContainer.layoutParams as ConstraintLayout.LayoutParams
        savedVideoParams = ConstraintLayout.LayoutParams(currentParams)
        savedResizeMode = binding.playerView.resizeMode

        // 2. Hide Common Overlays
        binding.videoOverlay.visibility = View.GONE
        binding.playPauseOverlay.visibility = View.GONE
        binding.actionBar.visibility = View.GONE
        binding.infoPanel.visibility = View.GONE

        // 3. Hide Theatre Specifics
        if (isTheatreMode) {
             theatreTopBar?.visibility = View.GONE
             binding.theatreBackground.visibility = View.GONE
             binding.chatContainer.visibility = View.GONE
             binding.theatreBottomGradient.visibility = View.GONE
             
             binding.emoteComboContainer.visibility = View.GONE
             binding.floatingEmoteContainer.visibility = View.GONE
             binding.pinnedGiftsBlur.visibility = View.GONE
             binding.pinnedMessageContainer.visibility = View.GONE
             binding.pollContainer.visibility = View.GONE
             binding.predictionContainer.visibility = View.GONE
        }

        // 4. Force FIT Mode and FILL PARENT for PiP Window
        binding.playerView.resizeMode = com.amazonaws.ivs.player.ResizeMode.FIT
        
        val params = binding.videoContainer.layoutParams as ConstraintLayout.LayoutParams
        params.width = ConstraintLayout.LayoutParams.MATCH_PARENT
        params.height = ConstraintLayout.LayoutParams.MATCH_PARENT
        
        // Clear all constraints that might limit size or aspect ratio
        params.dimensionRatio = null 
        params.matchConstraintPercentWidth = 1f
        params.matchConstraintPercentHeight = 1f
        params.matchConstraintDefaultWidth = ConstraintLayout.LayoutParams.MATCH_CONSTRAINT_SPREAD
        params.matchConstraintDefaultHeight = ConstraintLayout.LayoutParams.MATCH_CONSTRAINT_SPREAD
        
        params.topMargin = 0
        params.bottomMargin = 0
        params.topToTop = ConstraintLayout.LayoutParams.PARENT_ID
        params.bottomToBottom = ConstraintLayout.LayoutParams.PARENT_ID
        params.startToStart = ConstraintLayout.LayoutParams.PARENT_ID
        params.endToEnd = ConstraintLayout.LayoutParams.PARENT_ID
        
        // Center it
        params.verticalBias = 0.5f 
        params.horizontalBias = 0.5f
        
        binding.videoContainer.layoutParams = params
    }

    fun onExitPipMode() {
        val isTablet = activity.resources.getBoolean(R.bool.is_tablet)
        val isPortrait = activity.resources.configuration.orientation == android.content.res.Configuration.ORIENTATION_PORTRAIT
        if (isTablet && isSideChatVisible) {
            binding.actionBar.visibility = View.VISIBLE
        }
        if (isTablet && isPortrait && !isTheatreMode) {
            binding.infoPanel.visibility = View.VISIBLE
        }
        
        // 1. Restore Theatre Specifics
        if (isTheatreMode) {
             theatreTopBar?.visibility = View.VISIBLE
             binding.theatreBackground.visibility = View.VISIBLE
             binding.chatContainer.visibility = View.VISIBLE
             binding.theatreBottomGradient.visibility = View.VISIBLE
             
             binding.emoteComboContainer.visibility = View.VISIBLE
             binding.floatingEmoteContainer.visibility = View.VISIBLE
             
             activity.overlayManager.updatePinnedGiftsUI()
             
             if (activity.chatStateManager.isPinnedMessageActive) {
                 binding.pinnedMessageContainer.visibility = View.VISIBLE
             }
             if (activity.chatStateManager.currentPoll != null) {
                 binding.pollContainer.visibility = View.VISIBLE
             }
             if (activity.chatStateManager.currentPrediction != null) {
                 binding.predictionContainer.visibility = View.VISIBLE
             }
        }
        
        // 2. Restore Video Layout & ResizeMode
        savedResizeMode?.let {
            binding.playerView.resizeMode = it
        }
        savedVideoParams?.let {
            binding.videoContainer.layoutParams = it
        }
        
        // Clear saved state
        savedVideoParams = null
        savedResizeMode = null
    }
    
    /**
     * Gets the navigation bar height in pixels
     */
    private fun getNavigationBarHeight(): Int {
        return if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
            val windowMetrics = activity.windowManager.currentWindowMetrics
            val insets = windowMetrics.windowInsets.getInsetsIgnoringVisibility(
                android.view.WindowInsets.Type.navigationBars()
            )
            insets.bottom
        } else {
            val resourceId = activity.resources.getIdentifier("navigation_bar_height", "dimen", "android")
            if (resourceId > 0) {
                activity.resources.getDimensionPixelSize(resourceId)
            } else {
                0
            }
        }
    }
    
    // Theatre mode keyboard listener
    private var theatreKeyboardListener: android.view.ViewTreeObserver.OnGlobalLayoutListener? = null
    
    /**
     * Setup keyboard listener for theatre mode.
     * Currently disabled - relying on Activity's WindowInsetsListener for keyboard handling.
     * Chat input uses high elevation (2000f) to stay above video content.
     */
    private fun setupTheatreModeKeyboardListener() {
        // Disabled - relying on WindowInsetsListener in Activity
        theatreKeyboardListener = null
    }
    
    /**
     * Remove keyboard listener when exiting theatre mode
     */
    private fun removeTheatreModeKeyboardListener() {
        theatreKeyboardListener?.let {
            binding.root.viewTreeObserver.removeOnGlobalLayoutListener(it)
        }
        theatreKeyboardListener = null
    }

    /**
     * Updates the side chat layout based on overlay visibility.
     * When video overlay is visible on phones (fullscreen landscape),
     * shrink chat height and show action bar above it.
     */
    fun updateSideChatOverlayState(isOverlayVisible: Boolean) {
        if (!isSideChatVisible) return
        val isTablet = activity.resources.getBoolean(R.bool.is_tablet)
        if (isTablet) return 
        
        // Only apply this logic in Landscape mode (Fullscreen on phone)
        // In Portrait, ActionBar is in normal position, not side container
        val isPortrait = activity.resources.configuration.orientation == android.content.res.Configuration.ORIENTATION_PORTRAIT
        if (isPortrait) return
        
        val chat = binding.chatContainer
        // If chat isn't attached to side container, nothing to do
        if (chat.parent != binding.sideChatContainer) return
        
        // Force reset padding to avoid gaps from other animations
        chat.setPadding(chat.paddingLeft, 0, chat.paddingRight, chat.paddingBottom)
        
        val actionBar = binding.actionBar
        
        if (isOverlayVisible) {
            // Reparent ActionBar to side container if needed
            if (actionBar.parent != binding.sideChatContainer) {
                (actionBar.parent as? android.view.ViewGroup)?.removeView(actionBar)
                // Add at top (index 0)
                binding.sideChatContainer.addView(actionBar, 0)
                isActionBarReparentedToSide = true
            }
            
            // Show ActionBar
            actionBar.visibility = View.VISIBLE
            
            // Set params for ActionBar (wrap content, no weight)
            val actionParams = android.widget.LinearLayout.LayoutParams(
                android.widget.LinearLayout.LayoutParams.MATCH_PARENT, 
                android.widget.LinearLayout.LayoutParams.WRAP_CONTENT
            )
            actionParams.weight = 0f
            actionParams.setMargins(0, 0, 0, 0) // Reset margins
            actionBar.layoutParams = actionParams
            
            // Remove any excessive padding that might cause gaps
            actionBar.setPadding(0, 0, 0, 0)
            
            // Shrink chat (weight 1, match parent width)
            val chatParams = chat.layoutParams as android.widget.LinearLayout.LayoutParams
            chatParams.width = android.widget.LinearLayout.LayoutParams.MATCH_PARENT
            chatParams.height = 0
            chatParams.weight = 1f
            chatParams.setMargins(0, 0, 0, 0)
            
            // Force padding 0 here too just in case
            chat.setPadding(chat.paddingLeft, 0, chat.paddingRight, chat.paddingBottom)
            
            chat.layoutParams = chatParams
            
        } else {
            // Hide ActionBar
            actionBar.visibility = View.GONE
            
            // Restore chat to full height
            val chatParams = chat.layoutParams as android.widget.LinearLayout.LayoutParams
            chatParams.width = android.widget.LinearLayout.LayoutParams.MATCH_PARENT
            chatParams.height = android.widget.LinearLayout.LayoutParams.MATCH_PARENT
            chatParams.weight = 0f
            chatParams.setMargins(0, 0, 0, 0)
            chat.layoutParams = chatParams
        }
    }
}
