/**
 * File: MobilePlayerActivity.kt
 *
 * Description: The central activity for the mobile player experience. It orchestrates video playback,
 * chat integration, detailed channel information, and user interactions. This class manages the
 * lifecycle of the player, handles orientation changes, and coordinates various UI managers for
 * a seamless viewing experience.
 *
 * Author: Xacnio
 *
 */
package dev.xacnio.kciktv.mobile

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.pm.ActivityInfo
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.View
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Toast
import dev.xacnio.kciktv.shared.util.TimeUtils
import dev.xacnio.kciktv.shared.util.DateParseUtils
import dev.xacnio.kciktv.shared.util.FormatUtils
import dev.xacnio.kciktv.shared.util.Constants
import dev.xacnio.kciktv.shared.util.dpToPx
import dev.xacnio.kciktv.shared.data.util.PreloadCache
import android.widget.FrameLayout
import android.widget.TextView
import android.widget.LinearLayout
import android.widget.ImageView
import android.widget.GridLayout
import android.widget.Button
import android.widget.ProgressBar
import androidx.appcompat.app.AlertDialog
import com.google.android.material.bottomsheet.BottomSheetDialog
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import android.view.ViewGroup
import android.webkit.JavascriptInterface
import androidx.annotation.RequiresApi
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.IntentFilter
import android.content.res.Configuration
import android.content.res.ColorStateList
import android.graphics.Bitmap
import android.graphics.Color
import android.app.NotificationChannel
import android.app.NotificationManager
import android.os.Build
import android.support.v4.media.session.MediaSessionCompat
import android.support.v4.media.session.PlaybackStateCompat
import android.support.v4.media.MediaMetadataCompat
import androidx.media.app.NotificationCompat as MediaNotificationCompat
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.lifecycleScope
import dev.xacnio.kciktv.mobile.ui.following.FollowingManager
import androidx.recyclerview.widget.LinearLayoutManager

import com.amazonaws.ivs.player.Player

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import com.amazonaws.ivs.player.Quality
import com.bumptech.glide.Glide
import com.google.android.material.bottomsheet.BottomSheetBehavior
import dev.xacnio.kciktv.shared.ui.chat.ChatRulesManager
import dev.xacnio.kciktv.mobile.ui.chat.ChatStateManager
import dev.xacnio.kciktv.mobile.ui.chat.ChatUiManager
import dev.xacnio.kciktv.mobile.ui.chat.MentionsManager
import dev.xacnio.kciktv.shared.data.model.ChannelItem
import dev.xacnio.kciktv.shared.data.model.ChatMessage
import dev.xacnio.kciktv.shared.data.model.ChatSender
import dev.xacnio.kciktv.shared.data.model.Emote
import dev.xacnio.kciktv.shared.data.model.EmoteCategory
import dev.xacnio.kciktv.shared.data.model.BanItem
import dev.xacnio.kciktv.shared.data.model.PinnedGift
import dev.xacnio.kciktv.mobile.ui.clip.ClipManager
import dev.xacnio.kciktv.mobile.ui.chat.OverlayManager
import dev.xacnio.kciktv.mobile.ui.chat.ModActionsManager
import dev.xacnio.kciktv.mobile.ui.chat.ChatSettingsSheetManager
import dev.xacnio.kciktv.shared.data.repository.ChannelRepository
import dev.xacnio.kciktv.shared.data.model.ChannelVideo
import dev.xacnio.kciktv.shared.data.model.ChatHistoryResponse
import dev.xacnio.kciktv.shared.data.model.ChatIdentityResponse
import dev.xacnio.kciktv.shared.data.model.ChatIdentityBadge
import dev.xacnio.kciktv.shared.data.model.ChannelClip
import dev.xacnio.kciktv.shared.data.model.ChannelClipsResponse
import dev.xacnio.kciktv.shared.data.prefs.AppPreferences
import dev.xacnio.kciktv.databinding.ActivityMobilePlayerBinding
import dev.xacnio.kciktv.shared.ui.adapter.ChatAdapter
import dev.xacnio.kciktv.shared.ui.adapter.MobileChannelAdapter
import dev.xacnio.kciktv.shared.ui.adapter.UserHistoryAdapter
import dev.xacnio.kciktv.shared.util.ShimmerDrawable
import kotlinx.coroutines.launch
import androidx.core.view.WindowInsetsControllerCompat
import androidx.core.view.WindowInsetsCompat
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.NotificationCompat
import android.app.PictureInPictureParams
import android.util.Rational
import kotlinx.coroutines.async
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Deferred
import com.bumptech.glide.load.resource.bitmap.CenterCrop
import androidx.core.view.ViewCompat
import androidx.core.view.isVisible
import androidx.core.graphics.toColorInt
import androidx.core.net.toUri
import com.bumptech.glide.request.target.CustomTarget
import com.bumptech.glide.request.transition.Transition
import android.graphics.drawable.Drawable

import android.net.ConnectivityManager
import android.net.NetworkCapabilities

import dev.xacnio.kciktv.shared.analytics.AnalyticsManager
import dev.xacnio.kciktv.mobile.ui.account.AccountPopupManager
import dev.xacnio.kciktv.mobile.ui.auth.AuthManager
import dev.xacnio.kciktv.mobile.ui.browse.BrowseClipsManager
import dev.xacnio.kciktv.mobile.ui.browse.BrowseManager
import dev.xacnio.kciktv.mobile.ui.channel.ChannelLoadManager
import dev.xacnio.kciktv.mobile.ui.channel.ChannelProfileManager
import dev.xacnio.kciktv.mobile.ui.channel.ChannelUiManager
import dev.xacnio.kciktv.mobile.ui.feed.ClipFeedManager
import dev.xacnio.kciktv.mobile.ui.feed.StreamFeedManager
import dev.xacnio.kciktv.mobile.ui.filter.LanguageFilterManager
import dev.xacnio.kciktv.mobile.ui.home.HomeScreenManager
import dev.xacnio.kciktv.mobile.ui.search.SearchUiManager
import dev.xacnio.kciktv.mobile.ui.settings.SettingsPanelManager
import dev.xacnio.kciktv.mobile.ui.settings.WhatsNewSheetManager
import dev.xacnio.kciktv.mobile.ui.sheet.BottomSheetManager
import dev.xacnio.kciktv.mobile.ui.sheet.MessageActionsSheetManager
import dev.xacnio.kciktv.mobile.ui.sheet.UserActionsSheetManager
import dev.xacnio.kciktv.shared.data.api.RetrofitClient
import dev.xacnio.kciktv.shared.data.model.ChannelDetailResponse
import dev.xacnio.kciktv.shared.data.model.TopCategory
import dev.xacnio.kciktv.shared.data.repository.UpdateRepository
import dev.xacnio.kciktv.shared.ui.adapter.SearchResultAdapter
import dev.xacnio.kciktv.mobile.ui.chat.ChatConnectionManager
import dev.xacnio.kciktv.mobile.ui.chat.ChatIdentityManager
import dev.xacnio.kciktv.mobile.ui.chat.ChatInputStateManager
import dev.xacnio.kciktv.mobile.ui.chat.ChatModerationManager
import dev.xacnio.kciktv.mobile.ui.chat.ChatReplyManager
import dev.xacnio.kciktv.mobile.ui.chat.ChatSystemMessageManager
import dev.xacnio.kciktv.shared.ui.chat.EmoteComboManager
import dev.xacnio.kciktv.mobile.ui.chat.EmotePanelManager
import dev.xacnio.kciktv.shared.ui.chat.FloatingEmoteManager
import dev.xacnio.kciktv.mobile.ui.chat.PollTimerManager
import dev.xacnio.kciktv.mobile.ui.chat.QuickEmoteBarManager
import dev.xacnio.kciktv.mobile.ui.player.CustomEqDialogManager
import dev.xacnio.kciktv.mobile.ui.player.FullscreenManager
import dev.xacnio.kciktv.mobile.ui.player.FullscreenToggleManager
import dev.xacnio.kciktv.mobile.ui.player.InfoPanelManager
import dev.xacnio.kciktv.mobile.ui.player.LiveDvrManager
import dev.xacnio.kciktv.mobile.ui.player.LoyaltyPointsManager
import dev.xacnio.kciktv.shared.ui.player.MediaSessionHelper
import dev.xacnio.kciktv.mobile.ui.player.MediaSessionManager
import dev.xacnio.kciktv.mobile.ui.player.MiniPlayerManager
import dev.xacnio.kciktv.mobile.ui.player.MobilePlayerBroadcastManager
import dev.xacnio.kciktv.mobile.ui.player.MobilePlayerListener
import dev.xacnio.kciktv.mobile.ui.player.PipManager
import dev.xacnio.kciktv.mobile.ui.player.PipStateManager
import dev.xacnio.kciktv.mobile.ui.player.PlaybackControlManager
import dev.xacnio.kciktv.mobile.ui.player.PlaybackNotificationManager
import dev.xacnio.kciktv.mobile.ui.player.PlaybackStatusManager
import dev.xacnio.kciktv.mobile.ui.player.PlayerControlsManager
import dev.xacnio.kciktv.mobile.ui.player.PlayerGestureManager
import dev.xacnio.kciktv.mobile.ui.player.PlayerManager
import dev.xacnio.kciktv.mobile.ui.player.PlayerStatsSheetManager
import dev.xacnio.kciktv.mobile.ui.player.ScreenshotManager
import dev.xacnio.kciktv.mobile.ui.player.VideoSettingsDialogManager
import dev.xacnio.kciktv.mobile.ui.player.ViewerCountManager
import dev.xacnio.kciktv.mobile.ui.player.VodManager
import dev.xacnio.kciktv.mobile.ui.player.WebViewManager
import dev.xacnio.kciktv.mobile.ui.session.ChannelSessionManager
import dev.xacnio.kciktv.mobile.ui.state.LoadingStateManager
import dev.xacnio.kciktv.mobile.ui.chat.ChatEventHandler
import dev.xacnio.kciktv.mobile.ui.player.OverlayControlManager
import dev.xacnio.kciktv.R
import dev.xacnio.kciktv.BuildConfig

/**
 * MobilePlayerActivity - Portrait-first mobile streaming experience
 * Features:
 * - Video player at top (16:9)
 * - Live chat below
 * - Bottom sheet for channel switching
 * - Fullscreen mode (landscape)
 */
class MobilePlayerActivity : FragmentActivity() {

    // Navigation State Enum
    enum class AppScreen {
        HOME,
        BROWSE,
        CATEGORY_DETAILS,
        FOLLOWING,
        SEARCH,
        CHANNEL_PROFILE,
        STREAM_FEED,
        CLIP_FEED
    }

    internal lateinit var binding: ActivityMobilePlayerBinding
    internal lateinit var prefs: AppPreferences
    internal val repository = ChannelRepository()
    private val updateRepository = dev.xacnio.kciktv.shared.data.repository.UpdateRepository()
    
    // Analytics (Privacy-focused)
    internal val analytics: AnalyticsManager by lazy { AnalyticsManager.getInstance(this) }

    // Player
    internal lateinit var playerManager: dev.xacnio.kciktv.mobile.ui.player.PlayerManager
    internal var ivsPlayer: Player?
        get() = if (::playerManager.isInitialized) playerManager.ivsPlayer else null
        set(value) { if (::playerManager.isInitialized) playerManager.ivsPlayer = value }

    internal var isMuted = false
    private var manualMaxQuality: Quality?
        get() = if (::playerManager.isInitialized) playerManager.manualMaxQuality else null
        set(value) { if (::playerManager.isInitialized) playerManager.manualMaxQuality = value }
    internal var userSelectedQualityLimit: Quality?
        get() = if (::playerManager.isInitialized) playerManager.userSelectedQualityLimit else null
        set(value) { if (::playerManager.isInitialized) playerManager.userSelectedQualityLimit = value }

    internal var dvrPlaybackUrl: String? = null
    internal var dvrVideoUuid: String? = null
    internal var currentVodTitle: String? = null
    internal var currentDvrTitle: String? = null
    // System enforced limit (delegated)
    private var forcedQualityLimit: String?
        get() = if (::playerManager.isInitialized) playerManager.forcedQualityLimit else null
        set(value) { if (::playerManager.isInitialized) playerManager.forcedQualityLimit = value }

    // Chat
    internal lateinit var chatEventHandler: dev.xacnio.kciktv.mobile.ui.chat.ChatEventHandler
    internal lateinit var chatConnectionManager: dev.xacnio.kciktv.mobile.ui.chat.ChatConnectionManager
    private var currentChatroomId: Long? = null
    internal val sentMessageRefs = java.util.Collections.synchronizedList(mutableListOf<String>())
    private var currentUserSender: dev.xacnio.kciktv.shared.data.model.ChatSender?
        get() = chatStateManager.currentUserSender
        set(value) { chatStateManager.currentUserSender = value }
    internal var currentChatErrorMessage: String? = null // Persist chat error across PiP
    internal var isChatPausedForLowBattery = false
    internal var chatWasDisconnected = false

    // Channels
    internal var allChannels = mutableListOf<ChannelItem>()
    internal var currentChannel: ChannelItem? = null
    internal val chatAdapter get() = chatUiManager.chatAdapter
    internal var currentChannelIndex = -1
    internal val isFullscreen: Boolean
        get() = if (::fullscreenManager.isInitialized) fullscreenManager.isFullscreen else false
    internal lateinit var chatUiManager: dev.xacnio.kciktv.mobile.ui.chat.ChatUiManager
    internal lateinit var clipManager: dev.xacnio.kciktv.mobile.ui.clip.ClipManager
    internal lateinit var channelProfileManager: dev.xacnio.kciktv.mobile.ui.channel.ChannelProfileManager
    internal lateinit var followingManager: FollowingManager
    internal lateinit var browseManager: dev.xacnio.kciktv.mobile.ui.browse.BrowseManager
    internal lateinit var browseClipsManager: dev.xacnio.kciktv.mobile.ui.browse.BrowseClipsManager
    internal lateinit var homeScreenManager: dev.xacnio.kciktv.mobile.ui.home.HomeScreenManager
    internal lateinit var searchUiManager: dev.xacnio.kciktv.mobile.ui.search.SearchUiManager
    internal lateinit var miniPlayerManager: dev.xacnio.kciktv.mobile.ui.player.MiniPlayerManager
    internal lateinit var dragToMiniPlayerManager: dev.xacnio.kciktv.mobile.ui.player.DragToMiniPlayerManager
    internal lateinit var streamFeedManager: dev.xacnio.kciktv.mobile.ui.feed.StreamFeedManager
    internal lateinit var clipFeedManager: dev.xacnio.kciktv.mobile.ui.feed.ClipFeedManager
    internal lateinit var vodManager: dev.xacnio.kciktv.mobile.ui.player.VodManager
    internal lateinit var overlayManager: dev.xacnio.kciktv.mobile.ui.chat.OverlayManager
    internal lateinit var emoteComboManager: dev.xacnio.kciktv.shared.ui.chat.EmoteComboManager
    internal lateinit var floatingEmoteManager: dev.xacnio.kciktv.shared.ui.chat.FloatingEmoteManager
    internal lateinit var quickEmoteBarManager: dev.xacnio.kciktv.mobile.ui.chat.QuickEmoteBarManager
    internal lateinit var chatModerationManager: dev.xacnio.kciktv.mobile.ui.chat.ChatModerationManager
    internal lateinit var settingsPanelManager: dev.xacnio.kciktv.mobile.ui.settings.SettingsPanelManager
    internal lateinit var emotePanelManager: dev.xacnio.kciktv.mobile.ui.chat.EmotePanelManager
    internal lateinit var chatIdentityManager: dev.xacnio.kciktv.mobile.ui.chat.ChatIdentityManager
    internal lateinit var authManager: dev.xacnio.kciktv.mobile.ui.auth.AuthManager
    internal lateinit var screenshotManager: dev.xacnio.kciktv.mobile.ui.player.ScreenshotManager
    internal lateinit var customEqDialogManager: dev.xacnio.kciktv.mobile.ui.player.CustomEqDialogManager
    internal lateinit var fullscreenManager: dev.xacnio.kciktv.mobile.ui.player.FullscreenManager
    internal lateinit var videoSettingsDialogManager: dev.xacnio.kciktv.mobile.ui.player.VideoSettingsDialogManager
    internal lateinit var playerStatsSheetManager: dev.xacnio.kciktv.mobile.ui.player.PlayerStatsSheetManager
    internal lateinit var loyaltyPointsManager: dev.xacnio.kciktv.mobile.ui.player.LoyaltyPointsManager
    internal lateinit var webViewManager: dev.xacnio.kciktv.mobile.ui.player.WebViewManager
    internal var isHomeScreenVisible = true // Start with home screen visible
    internal val isSettingsVisible: Boolean
        get() = if (::settingsPanelManager.isInitialized) settingsPanelManager.isSettingsVisible else false
    internal var isErrorStateActive = false
    internal var showFullViewerCount = false
    internal val chatStateManager: dev.xacnio.kciktv.mobile.ui.chat.ChatStateManager by lazy {
        dev.xacnio.kciktv.mobile.ui.chat.ChatStateManager(this, prefs)
    }
    internal val chatRulesManager: dev.xacnio.kciktv.shared.ui.chat.ChatRulesManager by lazy {
        dev.xacnio.kciktv.shared.ui.chat.ChatRulesManager(this, binding.bottomSheetCoordinator, lifecycleScope, repository, prefs)
    }
    internal val broadcastManager: dev.xacnio.kciktv.mobile.ui.player.MobilePlayerBroadcastManager by lazy {
        dev.xacnio.kciktv.mobile.ui.player.MobilePlayerBroadcastManager(this)
    }
    internal val chatInputStateManager: dev.xacnio.kciktv.mobile.ui.chat.ChatInputStateManager by lazy {
        dev.xacnio.kciktv.mobile.ui.chat.ChatInputStateManager(this)
    }
    internal val playerGestureManager: dev.xacnio.kciktv.mobile.ui.player.PlayerGestureManager by lazy {
        dev.xacnio.kciktv.mobile.ui.player.PlayerGestureManager(this)
    }

    internal val playbackControlManager: dev.xacnio.kciktv.mobile.ui.player.PlaybackControlManager by lazy {
        dev.xacnio.kciktv.mobile.ui.player.PlaybackControlManager(this, binding, mainHandler)
    }

    // Missing Fields
    internal var miniPlayerTranslationX = 0f
    internal var miniPlayerTranslationY = 0f
    internal var currentLoyaltyPoints: Int
        get() = if (::loyaltyPointsManager.isInitialized) loyaltyPointsManager.currentLoyaltyPoints else 0
        set(value) { if (::loyaltyPointsManager.isInitialized) loyaltyPointsManager.currentLoyaltyPoints = value }

    internal var nextCursor: String? = null
    internal var isLoadingMore = false

    internal var currentIsFollowing = false
    internal var isSubscribedToCurrentChannel = false
    internal var isSubscriptionEnabled = true
    internal var isModeratorOrOwner = false // Can manage pinned messages
    internal var isChannelOwner = false // Can always change title/category even offline
    internal var isBannedFromCurrentChannel = false
    internal var isCheckingBanStatus = false
    internal var lastMessageSentMillis: Long = 0
    internal var isPermanentBan = false
    internal var timeoutExpirationMillis: Long = 0
    
    // Screen Data Loading Flags
    internal var previousScreenId: Int = R.id.homeScreenContainer
    
    // New Navigation State
    internal var currentScreen: AppScreen = AppScreen.HOME
    internal var screenBeforePlayer: AppScreen = AppScreen.HOME

    fun setCurrentScreen(screen: AppScreen) {
        // Always enforce navigation visibility based on screen type
        val navVisibility = when (screen) {
            AppScreen.HOME,
            AppScreen.BROWSE,
            AppScreen.FOLLOWING,
            AppScreen.SEARCH -> View.VISIBLE
            else -> View.GONE
        }
        binding.bottomNavContainer.visibility = navVisibility
        binding.bottomNavGradient.visibility = navVisibility
            
        if (currentScreen != screen) {
            android.util.Log.d("MobilePlayerNav", "State Change: $currentScreen -> $screen")
            currentScreen = screen
            
            // Log screen view (anonymous - only screen name)
            analytics.logScreenView(screen.name)
            
            // Sync bottom nav visuals to match the current screen
            val navIndex = when (screen) {
                AppScreen.HOME -> 0
                AppScreen.BROWSE, AppScreen.CATEGORY_DETAILS -> 1
                AppScreen.FOLLOWING -> 2
                AppScreen.SEARCH -> 3
                else -> -1
            }
            if (navIndex >= 0) {
                updateNavVisuals(navIndex)
            }
        }
    }

    internal var homeDataLoaded = false
    internal var userMeJob: kotlinx.coroutines.Job? = null
    internal var followingDataLoaded = false
    internal var returnToProfileSlug: String? = null
    private var lastBackPressTime: Long = 0
    
    internal fun setForcedQualityLimit(limit: String?) {
        forcedQualityLimit = limit
        checkAndApplyQualityLimit()
    }

    internal fun checkAndApplyQualityLimit() {
        playerManager.checkAndApplyQualityLimit()
    }
    
    // Ban Data
    internal var currentChannelBans: List<dev.xacnio.kciktv.shared.data.model.BanItem> = emptyList()

    // Browse Pagination
    private var currentBrowseCategoryPage = 1
    private var isBrowseCategoriesLoading = false
    private var hasMoreBrowseCategories = true
    private var browseCategoriesList = mutableListOf<dev.xacnio.kciktv.shared.data.model.TopCategory>()
    private var browseCategoriesAdapter: RecyclerView.Adapter<*>? = null

    // Browse Live Pagination
    private var nextBrowseLiveCursor: String? = null
    private var isBrowseLiveLoading = false
    private var hasMoreBrowseLive = true
    private var browseLiveChannelsList = mutableListOf<ChannelItem>()
    private var browseLiveAdapter: RecyclerView.Adapter<*>? = null

    // VOD/DVR Mode - seek to live edge when switching from live to DVR
    internal var shouldSeekToLiveEdge: Boolean
        get() = if (::playerManager.isInitialized) playerManager.shouldSeekToLiveEdge else false
        set(value) { if (::playerManager.isInitialized) playerManager.shouldSeekToLiveEdge = value }
    internal var isReturningToLive = false
    
    internal val mentionMessages: MutableList<dev.xacnio.kciktv.shared.data.model.ChatMessage>
        get() = mentionsManager.mentionMessages
    internal var lastSeenMentionCount: Int
        get() = mentionsManager.lastSeenMentionCount
        set(value) { mentionsManager.lastSeenMentionCount = value }
    private var isEmoteConverting = false
    private val emoteTagPattern = java.util.regex.Pattern.compile("\\[emote:(\\d+):([^\\]]+)\\]")

    /**
     * Check if device should be treated as a tablet.
     * Uses physical screen diagonal size in inches.
     * Tablets are typically >= 7 inches diagonal.
     * NOTE: We don't use R.bool.is_tablet because it fails when window is portrait on Windows.
     */
    internal fun isTabletOrLargeWindow(): Boolean {
        // Get real screen dimensions (not window)
        val realMetrics = android.util.DisplayMetrics()
        @Suppress("DEPRECATION")
        windowManager.defaultDisplay.getRealMetrics(realMetrics)
        
        val widthPixels = realMetrics.widthPixels.toDouble()
        val heightPixels = realMetrics.heightPixels.toDouble()
        val xdpi = realMetrics.xdpi.toDouble()
        val ydpi = realMetrics.ydpi.toDouble()
        
        // Avoid division by zero
        if (xdpi <= 0 || ydpi <= 0) {
            Log.d(TAG, "isTabletOrLargeWindow: Invalid DPI (xdpi=$xdpi, ydpi=$ydpi)")
            return false
        }
        
        val widthInches = widthPixels / xdpi
        val heightInches = heightPixels / ydpi
        val diagonalInches = kotlin.math.sqrt(widthInches * widthInches + heightInches * heightInches)
        
        val isTablet = diagonalInches >= 7.0
        Log.d(TAG, "isTabletOrLargeWindow: ${widthPixels}x${heightPixels}px, ${xdpi}x${ydpi}dpi, diagonal=${String.format("%.2f", diagonalInches)}in, isTablet=$isTablet")
        
        // 7 inches or larger is typically a tablet
        return isTablet
    }

    /**
     * Check if auto-rotation is allowed.
     * Returns true if system auto-rotate is enabled (rotation lock is off).
     * Works for both phones and tablets.
     */
    internal fun isRotationAllowed(): Boolean {
        // Check system auto-rotate setting
        val autoRotateEnabled = android.provider.Settings.System.getInt(
            contentResolver,
            android.provider.Settings.System.ACCELEROMETER_ROTATION,
            0
        ) == 1
        
        return autoRotateEnabled
    }
    
    /**
     * Set orientation to sensor mode only if rotation is allowed.
     * Otherwise sets to portrait for phones, user preference for tablets.
     */
    internal fun setAllowedOrientation() {
        if (isRotationAllowed()) {
            requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR
        } else {
            // Rotation lock is on - use portrait for phones, keep current for tablets
            if (!isTabletOrLargeWindow()) {
                requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
            }
        }
        // For tablets with rotation lock, don't change orientation
    }

    internal fun updateTimeoutOverlayContent() {
        if (!isBannedFromCurrentChannel || isPermanentBan) return
        
        val remaining = timeoutExpirationMillis - System.currentTimeMillis()
        if (remaining <= 0) return

        val seconds = (remaining / 1000) % 60
        val minutes = (remaining / (1000 * 60)) % 60
        val hours = (remaining / (1000 * 60 * 60))
        val timeStr = String.format(java.util.Locale.US, "%02d:%02d:%02d", hours, minutes, seconds)
        
        runOnUiThread {
            binding.chatBannedText.text = getString(R.string.chat_timeout_remaining_format, timeStr)
        }
    }

    internal fun parseIsoDate(isoString: String?): Long = DateParseUtils.parseIsoDate(isoString)

    internal var currentSort = "featured" // featured, viewer_count_desc, viewer_count_asc
    private var isBrowseGridMode: Boolean
        get() = prefs.mobileLayoutMode == "grid"
        set(value) { prefs.mobileLayoutMode = if (value) "grid" else "list" }
    internal var currentChatroom: dev.xacnio.kciktv.shared.data.model.ChatroomInfo? = null
    internal var currentStreamUrl: String? = null
    internal var currentBlerpUrl: String? = null
    internal var currentPoll: dev.xacnio.kciktv.shared.data.model.PollData?
        get() = chatStateManager.currentPoll
        set(value) { chatStateManager.currentPoll = value }
    internal lateinit var pollTimerManager: dev.xacnio.kciktv.mobile.ui.chat.PollTimerManager
    
    private var currentPrediction: dev.xacnio.kciktv.shared.data.model.PredictionData?
        get() = chatStateManager.currentPrediction
        set(value) { chatStateManager.currentPrediction = value }
        
    private var predictionTimer: java.util.Timer? = null

    private lateinit var overlayGestureDetector: android.view.GestureDetector
    
    // Blerp Persistence Cache
    internal var cachedBlerpFragment: BlerpBottomSheetFragment? = null
    internal val blerpCleanupHandler = Handler(Looper.getMainLooper())
    internal val blerpCleanupRunnable = Runnable {
// Log.d(TAG, "Cleaning up cached Blerp WebView (3 mins inactive)")
        cachedBlerpFragment?.let {
            if (!it.isVisible) {
                it.dismissAllowingStateLoss()
                cachedBlerpFragment = null
            }
        }
    }
    
    fun startBlerpCleanupTimer() {
        blerpCleanupHandler.removeCallbacks(blerpCleanupRunnable)
        // 3 minutes = 180,000ms
        blerpCleanupHandler.postDelayed(blerpCleanupRunnable, 180_000L)
    }

    fun cancelBlerpCleanupTimer() {
        blerpCleanupHandler.removeCallbacks(blerpCleanupRunnable)
    }
    
    // Bottom Sheet

    internal val activeBottomSheets = mutableListOf<com.google.android.material.bottomsheet.BottomSheetDialog>()

    // Handlers
    internal val mainHandler = Handler(Looper.getMainLooper())
    internal val hideOverlayRunnable = Runnable { 
        hideOverlay()
        stopProgressUpdater()
    }
    private val hideStatusRunnable = Runnable { 
        if (::binding.isInitialized) binding.chatStatusContainer.visibility = View.GONE 
    }
    internal val startMarqueeRunnable = Runnable {
        if (::binding.isInitialized) binding.infoStreamTitle.isSelected = true 
    }
    
    internal val playbackStatusManager: dev.xacnio.kciktv.mobile.ui.player.PlaybackStatusManager by lazy {
        dev.xacnio.kciktv.mobile.ui.player.PlaybackStatusManager(this)
    }

    // Viewer count polling

    // Viewer count polling
    internal var currentLivestreamId: Long? = null
    internal val viewerCountHandler = Handler(Looper.getMainLooper())
    private val VIEWER_COUNT_INTERVAL = 60_000L // 60 seconds
    internal val viewerCountRunnable = object : Runnable {
        override fun run() {
            fetchCurrentViewerCount()
            viewerCountHandler.postDelayed(this, VIEWER_COUNT_INTERVAL)
        }
    }

    // PIP & Background Audio Support
    internal lateinit var playbackNotificationManager: dev.xacnio.kciktv.mobile.ui.player.PlaybackNotificationManager
    internal var isBackgroundAudioEnabled: Boolean
        get() = if (::playbackNotificationManager.isInitialized) playbackNotificationManager.isBackgroundAudioEnabled else false
        set(value) { if (::playbackNotificationManager.isInitialized) playbackNotificationManager.setBackgroundAudioEnabled(value) }
    internal lateinit var mediaSessionManager: dev.xacnio.kciktv.mobile.ui.player.MediaSessionManager
    internal val mediaSessionHelper: dev.xacnio.kciktv.shared.ui.player.MediaSessionHelper?
        get() = if (::mediaSessionManager.isInitialized) mediaSessionManager.mediaSessionHelper else null
    
    internal val liveDvrManager by lazy {
        dev.xacnio.kciktv.mobile.ui.player.LiveDvrManager(this, repository)
    }

    internal fun revealOverlay() = showOverlay()

    private val playerListener = dev.xacnio.kciktv.mobile.ui.player.MobilePlayerListener(this)

    internal var currentProfileBitmap: Bitmap? = null
    internal val PIP_CONTROL_ACTION = "dev.xacnio.kciktv.MOBILE_PIP_CONTROL"
    internal val EXTRA_CONTROL_TYPE = "type"
    internal val CONTROL_TYPE_PLAY_PAUSE = 1
    internal val CONTROL_TYPE_LIVE = 2
    internal val CONTROL_TYPE_AUDIO_ONLY = 3
    internal val CONTROL_TYPE_MUTE = 4
    // Notification constants moved to dev.xacnio.kciktv.mobile.ui.player.PlaybackNotificationManager
    internal val NOTIFICATION_ID = dev.xacnio.kciktv.mobile.ui.player.PlaybackNotificationManager.NOTIFICATION_ID

    companion object {
        private const val TAG = "MobilePlayerActivity"
    }

    private var profileSelectedBadges = mutableListOf<String>()
    private var profileSelectedColor: String = "#53fc18"
    private var hasProfileChanges: Boolean = false
    private var currentIdentityChannelId: Long = 0L
    
    // PiP State Tracking
    internal lateinit var pipStateManager: dev.xacnio.kciktv.mobile.ui.player.PipStateManager
    private var exitedPipMode: Boolean
        get() = if (::pipStateManager.isInitialized) pipStateManager.exitedPipMode else false
        set(value) { if (::pipStateManager.isInitialized) pipStateManager.exitedPipMode = value }
    internal var isExplicitAudioSwitch: Boolean
        get() = if (::pipStateManager.isInitialized) pipStateManager.isExplicitAudioSwitch else false
        set(value) { if (::pipStateManager.isInitialized) pipStateManager.isExplicitAudioSwitch = value }

    // Managers
    internal val mentionsManager by lazy {
        dev.xacnio.kciktv.mobile.ui.chat.MentionsManager(this, binding, prefs)
    }
    
    internal val chatSettingsSheetManager by lazy {
        dev.xacnio.kciktv.mobile.ui.chat.ChatSettingsSheetManager(
            this, prefs, chatRulesManager, chatUiManager, chatStateManager, repository, lifecycleScope
        )
    }

    internal val modActionsManager by lazy {
        dev.xacnio.kciktv.mobile.ui.chat.ModActionsManager(this, repository, prefs, overlayManager)
    }

    internal val infoPanelManager by lazy {
        dev.xacnio.kciktv.mobile.ui.player.InfoPanelManager(this)
    }

    internal val accountPopupManager by lazy {
        dev.xacnio.kciktv.mobile.ui.account.AccountPopupManager(this)
    }

    internal val languageFilterManager by lazy {
        dev.xacnio.kciktv.mobile.ui.filter.LanguageFilterManager(this)
    }

    internal val pipManager by lazy {
        dev.xacnio.kciktv.mobile.ui.player.PipManager(this)
    }

    internal val chatReplyManager by lazy {
        dev.xacnio.kciktv.mobile.ui.chat.ChatReplyManager(this)
    }

    internal lateinit var fullscreenToggleManager: dev.xacnio.kciktv.mobile.ui.player.FullscreenToggleManager

    internal val chatSystemMessageManager by lazy {
        dev.xacnio.kciktv.mobile.ui.chat.ChatSystemMessageManager(this)
    }

    internal val playerControlsManager by lazy {
        dev.xacnio.kciktv.mobile.ui.player.PlayerControlsManager(this)
    }

    internal val loadingStateManager by lazy {
        dev.xacnio.kciktv.mobile.ui.state.LoadingStateManager(this)
    }

    internal val viewerCountManager by lazy {
        dev.xacnio.kciktv.mobile.ui.player.ViewerCountManager(this)
    }

    internal val overlayControlManager by lazy {
        OverlayControlManager(this)
    }

    internal val channelUiManager by lazy {
        dev.xacnio.kciktv.mobile.ui.channel.ChannelUiManager(this)
    }

    internal val bottomSheetManager by lazy {
        dev.xacnio.kciktv.mobile.ui.sheet.BottomSheetManager(this)
    }

    internal val messageActionsSheetManager by lazy {
        dev.xacnio.kciktv.mobile.ui.sheet.MessageActionsSheetManager(this)
    }

    internal val userActionsSheetManager by lazy {
        dev.xacnio.kciktv.mobile.ui.sheet.UserActionsSheetManager(this)
    }

    internal val channelLoadManager by lazy {
        dev.xacnio.kciktv.mobile.ui.channel.ChannelLoadManager(this)
    }

    override fun attachBaseContext(newBase: Context) {
        // Apply saved language before context is attached
        val prefs = AppPreferences(newBase)
        val savedLang = prefs.language
        
        val locale = if (savedLang == "system" || savedLang.isEmpty()) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                android.content.res.Resources.getSystem().configuration.locales.get(0)
            } else {
                @Suppress("DEPRECATION")
                android.content.res.Resources.getSystem().configuration.locale
            }
        } else {
            java.util.Locale(savedLang)
        }
        
        java.util.Locale.setDefault(locale)
        val config = newBase.resources.configuration
        config.setLocale(locale)
        
        val localizedContext = newBase.createConfigurationContext(config)
        super.attachBaseContext(localizedContext)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = AppPreferences(this)
        
        // Apply language settings
        applySavedLanguage()
        
        binding = ActivityMobilePlayerBinding.inflate(layoutInflater)
        setContentView(binding.root)
        
        // Immediate check for preloaded data to avoid flicker
        if (PreloadCache.categories != null && PreloadCache.featuredStreams != null) {
            binding.startupLoadingOverlay.visibility = View.GONE
        }
        
        // Force LTR layout regardless of language (Arabic text will still render RTL naturally)
        window.decorView.layoutDirection = View.LAYOUT_DIRECTION_LTR
        
        // Enable edge-to-edge display for transparent navigation bar
        androidx.core.view.WindowCompat.setDecorFitsSystemWindows(window, false)
        window.navigationBarColor = Color.TRANSPARENT
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            window.isNavigationBarContrastEnforced = false
        }
        
        // Apply system bar insets to bottom navigation container
        androidx.core.view.ViewCompat.setOnApplyWindowInsetsListener(binding.bottomNavContainer) { view, windowInsets ->
            val insets = windowInsets.getInsets(androidx.core.view.WindowInsetsCompat.Type.systemBars())
            val params = view.layoutParams as androidx.constraintlayout.widget.ConstraintLayout.LayoutParams
            params.bottomMargin = insets.bottom + 12.dpToPx(resources)
            view.layoutParams = params
            windowInsets
        }
        
        // Also adjust gradient view height to cover system nav bar
        androidx.core.view.ViewCompat.setOnApplyWindowInsetsListener(binding.bottomNavGradient) { view, windowInsets ->
            val insets = windowInsets.getInsets(androidx.core.view.WindowInsetsCompat.Type.systemBars())
            val params = view.layoutParams as androidx.constraintlayout.widget.ConstraintLayout.LayoutParams
            params.height = insets.bottom + 100.dpToPx(resources)
            view.layoutParams = params
            windowInsets
        }
        
        // Also adjust header top padding for status bar
        androidx.core.view.ViewCompat.setOnApplyWindowInsetsListener(binding.mobileHeader) { view, windowInsets ->
            val insets = windowInsets.getInsets(androidx.core.view.WindowInsetsCompat.Type.systemBars())
            val density = resources.displayMetrics.density
            val extraTop = (12 * density).toInt()
            val extraBottom = (12 * density).toInt()
            
            // Add status bar height + extra top padding, and some bottom padding for breathing room
            view.setPadding(view.paddingLeft, insets.top + extraTop, view.paddingRight, extraBottom)
            windowInsets
        }
        
        // Apply system bar insets to player screen container for status bar and navigation bar padding
        androidx.core.view.ViewCompat.setOnApplyWindowInsetsListener(binding.playerScreenContainer) { view, windowInsets ->
            val insets = windowInsets.getInsets(androidx.core.view.WindowInsetsCompat.Type.systemBars())
            view.setPadding(view.paddingLeft, insets.top, view.paddingRight, insets.bottom)
            windowInsets
        }
        
        // Apply keyboard (IME) insets to chat container so chat input appears above keyboard
        // Apply keyboard (IME) insets to chat container so chat input appears above keyboard
        setupChatContainerInsetsListener()
        
        // --- Custom Spacing Fixes ---
        
        // Category Details: Add status bar padding to top
        ViewCompat.setOnApplyWindowInsetsListener(binding.categoryDetailsContainer.root) { view, windowInsets ->
            val insets = windowInsets.getInsets(WindowInsetsCompat.Type.systemBars())
            view.setPadding(view.paddingLeft, insets.top, view.paddingRight, view.paddingBottom)
            windowInsets
        }

        // Browse & Following: Add navigation bar padding to bottom of scrollable content
        val bottomPaddingViews = listOf(
            binding.browseScreenContainer.browseCategoriesRecycler,
            binding.browseScreenContainer.browseLiveChannelsRecycler,
            binding.browseScreenContainer.browseClipsRecycler,
            binding.followingScreenContainer.followingContentContainer,
            binding.categoryDetailsContainer.categoryStreamsRecycler,
            binding.categoryDetailsContainer.categoryClipsRecycler,
            binding.homeScreenContainer.homeContentContainer
        )
        
        bottomPaddingViews.forEach { view ->
            ViewCompat.setOnApplyWindowInsetsListener(view) { v, windowInsets ->
                val insets = windowInsets.getInsets(WindowInsetsCompat.Type.systemBars())
                // Base padding of 130dp to clear the floating bottom nav
                val basePadding = 130.dpToPx(resources) 
                v.setPadding(v.paddingLeft, v.paddingTop, v.paddingRight, basePadding + insets.bottom)
                windowInsets
            }
        }
        
        // Initialize Firebase Analytics (Privacy-focused - no personal data collected)
        analytics.initialize()
        
        // Allow auto-rotation on tablets (if system rotation lock is off)
        setAllowedOrientation()
        analytics.setAnalyticsEnabled(prefs.analyticsEnabled)
        analytics.logAppOpen()

        onBackPressedDispatcher.addCallback(this, object : androidx.activity.OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                // 1. Emote Panel
                if (::binding.isInitialized && binding.emotePanelContainer.visibility == View.VISIBLE) {
                    emotePanelManager.toggleEmotePanel(false)
                    return
                }

                // 2. Settings Panel
                if (isSettingsVisible) {
                    hideSettingsPanel()
                    return
                }

                // 3. Side Chat / Fullscreen
                if (::fullscreenToggleManager.isInitialized) {
                    if (fullscreenToggleManager.isSideChatVisible) {
                        fullscreenToggleManager.hideSideChat()
                        return
                    }
                    if (isFullscreen) {
                        fullscreenToggleManager.exitFullscreen()
                        return
                    }
                }

                // 4. Default
                isEnabled = false
                onBackPressedDispatcher.onBackPressed()
            }
        })

        // Note: FLAG_KEEP_SCREEN_ON is now managed dynamically in playerListener
        // It's added when playing and removed when paused/idle

        // setupPlayerControls() - called later

        setupPlayerControls()

        // Custom Navigation Logic to replace invisible BottomNavigationView
        binding.root.post {
            try {
                // Initial visuals
                updateNavVisuals(0)

                val navButtons = listOf(binding.btnNavHome, binding.btnNavBrowse, binding.btnNavFollowing, binding.btnNavSearch)
                val navIds = listOf(
                    dev.xacnio.kciktv.R.id.nav_home, 
                    dev.xacnio.kciktv.R.id.nav_browse, 
                    dev.xacnio.kciktv.R.id.nav_following, 
                    dev.xacnio.kciktv.R.id.nav_search
                )
                
                // Click listeners
                for (i in navButtons.indices) {
                    navButtons[i].setOnClickListener {
                        // Directly trigger navigation logic
                        handleNavigation(navIds[i])
                        
                        // Sync invisible BottomNavigationView state
                        // This triggers OnItemSelectedListener which now calls updateNavVisualsById
                        isNavigationProgrammatic = true
                        binding.mainBottomNavigation.selectedItemId = navIds[i]
                        isNavigationProgrammatic = false
                    }
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }

        // Check for What's New on first launch of this version
        binding.root.post {
            if (prefs.lastSeenWhatsNewVersion != dev.xacnio.kciktv.BuildConfig.VERSION_NAME) {
                dev.xacnio.kciktv.mobile.ui.settings.WhatsNewSheetManager(this@MobilePlayerActivity, updateRepository).show(isAutoPopup = true)
            }
        }

        var wasKeyboardOrEmoteOpen = false
        binding.root.viewTreeObserver.addOnGlobalLayoutListener {
            val r = android.graphics.Rect()
            binding.root.getWindowVisibleDisplayFrame(r)
            val screenHeight = binding.root.rootView.height
            val keypadHeight = screenHeight - r.bottom
            val isKeyboardOpen = keypadHeight > screenHeight * 0.15
            val isEmotePanelOpen = binding.emotePanelContainer.visibility == View.VISIBLE

            val isOpen = isKeyboardOpen || isEmotePanelOpen
            if (isOpen != wasKeyboardOrEmoteOpen) {
                wasKeyboardOrEmoteOpen = isOpen
                if (isOpen) {
                    if (binding.chatOverlayContainer.visibility != View.GONE) {
                        binding.chatOverlayContainer.visibility = View.GONE
                    }
                } else {
                    if (binding.chatContainer.visibility == View.VISIBLE && binding.chatOverlayContainer.visibility != View.VISIBLE) {
                        binding.chatOverlayContainer.visibility = View.VISIBLE
                    }
                }
            }
        }
        
        chatUiManager = dev.xacnio.kciktv.mobile.ui.chat.ChatUiManager(
            this, binding, prefs, repository, chatStateManager, lifecycleScope
        )
        chatUiManager.setup()
        
        // Bind slow mode callbacks
        chatStateManager.onSlowModeCountdownUpdate = { remainingSeconds ->
            binding.chatSlowModeCountdown.text = getString(R.string.slow_mode_countdown_format, remainingSeconds)
            binding.chatSlowModeCountdown.visibility = View.VISIBLE
            binding.chatSendButton.isEnabled = false
        }
        chatStateManager.onSlowModeFinished = {
            binding.chatSlowModeCountdown.visibility = View.GONE
            chatInputStateManager.updateInputState()
        }
        
        // Chat connection retry (tap floating indicator to reconnect)
        binding.chatConnectionContainer.setOnClickListener {
            // Reset UI state and attempt reconnection
            binding.chatConnectionProgress.visibility = View.VISIBLE
            binding.chatConnectionContainer.isClickable = false
            chatConnectionManager.manualReconnect()
        }
        
        chatEventHandler = ChatEventHandler(this, binding, chatUiManager.chatAdapter, chatStateManager)
        setupClickListeners()
        clipManager = dev.xacnio.kciktv.mobile.ui.clip.ClipManager(this, binding, prefs, repository, lifecycleScope)
        channelProfileManager = dev.xacnio.kciktv.mobile.ui.channel.ChannelProfileManager(this, binding, prefs, repository, lifecycleScope)
        followingManager = FollowingManager(this)
        browseManager = dev.xacnio.kciktv.mobile.ui.browse.BrowseManager(this)
        browseClipsManager = dev.xacnio.kciktv.mobile.ui.browse.BrowseClipsManager(this)
        homeScreenManager = dev.xacnio.kciktv.mobile.ui.home.HomeScreenManager(this)
        chatConnectionManager = dev.xacnio.kciktv.mobile.ui.chat.ChatConnectionManager(this)
        searchUiManager = dev.xacnio.kciktv.mobile.ui.search.SearchUiManager(this)
        playbackNotificationManager = dev.xacnio.kciktv.mobile.ui.player.PlaybackNotificationManager(this, prefs)
        playbackNotificationManager.createNotificationChannel()
        playerManager = dev.xacnio.kciktv.mobile.ui.player.PlayerManager(this, binding, prefs, repository, lifecycleScope)
        playerManager.externalListener = playerListener

        miniPlayerManager = dev.xacnio.kciktv.mobile.ui.player.MiniPlayerManager(this)
        dragToMiniPlayerManager = dev.xacnio.kciktv.mobile.ui.player.DragToMiniPlayerManager(this)
        streamFeedManager = dev.xacnio.kciktv.mobile.ui.feed.StreamFeedManager(this)
        clipFeedManager = dev.xacnio.kciktv.mobile.ui.feed.ClipFeedManager(this)
        vodManager = dev.xacnio.kciktv.mobile.ui.player.VodManager(this, repository)
        overlayManager = dev.xacnio.kciktv.mobile.ui.chat.OverlayManager(this, repository)
        pollTimerManager = dev.xacnio.kciktv.mobile.ui.chat.PollTimerManager(this, binding, prefs, repository, lifecycleScope, chatStateManager, mainHandler)
        emoteComboManager = dev.xacnio.kciktv.shared.ui.chat.EmoteComboManager(this, binding.emoteComboContainer, prefs)
        floatingEmoteManager = dev.xacnio.kciktv.shared.ui.chat.FloatingEmoteManager(this, binding.floatingEmoteContainer, prefs)
        quickEmoteBarManager = dev.xacnio.kciktv.mobile.ui.chat.QuickEmoteBarManager(this, binding, prefs, mainHandler)
        quickEmoteBarManager.onEmoteSend = { message -> chatUiManager.sendChatMessage(message) }
        quickEmoteBarManager.onEmoteAppend = { emote -> appendEmoteToInput(binding.chatInput, emote) }
        chatModerationManager = dev.xacnio.kciktv.mobile.ui.chat.ChatModerationManager(this)
        settingsPanelManager = dev.xacnio.kciktv.mobile.ui.settings.SettingsPanelManager(this, binding, prefs, updateRepository)
        pipStateManager = dev.xacnio.kciktv.mobile.ui.player.PipStateManager(this)
        emotePanelManager = dev.xacnio.kciktv.mobile.ui.chat.EmotePanelManager(this, binding, repository, lifecycleScope)
        authManager = dev.xacnio.kciktv.mobile.ui.auth.AuthManager(this, binding, prefs, lifecycleScope)
        chatIdentityManager = dev.xacnio.kciktv.mobile.ui.chat.ChatIdentityManager(repository, prefs, lifecycleScope, chatStateManager) {
            runOnUiThread {
                updateEmoteSubscriptionStatus()
            }
        }
        screenshotManager = dev.xacnio.kciktv.mobile.ui.player.ScreenshotManager(this)
        customEqDialogManager = dev.xacnio.kciktv.mobile.ui.player.CustomEqDialogManager(this, prefs)
        fullscreenManager = dev.xacnio.kciktv.mobile.ui.player.FullscreenManager(this)
        fullscreenToggleManager = dev.xacnio.kciktv.mobile.ui.player.FullscreenToggleManager(this)
        videoSettingsDialogManager = dev.xacnio.kciktv.mobile.ui.player.VideoSettingsDialogManager(this, prefs)
        playerStatsSheetManager = dev.xacnio.kciktv.mobile.ui.player.PlayerStatsSheetManager(this)
        loyaltyPointsManager = dev.xacnio.kciktv.mobile.ui.player.LoyaltyPointsManager(this, prefs)
        webViewManager = dev.xacnio.kciktv.mobile.ui.player.WebViewManager(this, prefs)
        mentionsManager.onReplyClick = ::prepareReply
        mentionsManager.onGoToMessageClick = { message -> scrollToRepliedMessage(message.id) }
        playerManager.resetPlayer()
        playerGestureManager.setupPlayerGestureDetector()
        
        webViewManager.setupWebView()
        setupNotifications()
        quickEmoteBarManager.setupQuickEmoteBar()
        
        searchUiManager.setupSearchListeners()
        setupMediaSession()
        setupPipReceivers()
        miniPlayerManager.setupMiniPlayerDraggable()

        // Refresh Auth Session on Startup
        if (prefs.isLoggedIn) {
            lifecycleScope.launch(Dispatchers.IO) {
                try {
                    val authService = dev.xacnio.kciktv.shared.data.api.RetrofitClient.authService
                    val response = authService.getUser("Bearer ${prefs.authToken}")
                    
                    if (response.isSuccessful && response.body() != null) {
                        val user = response.body()!!
                        val slug = user.streamerChannel?.slug ?: user.username ?: ""
                        val pic = user.profilePic
                        
                        // Update Prefs with latest data
                        prefs.saveAuth(
                            token = prefs.authToken ?: "",
                            user = user.username ?: "User",
                            pic = pic,
                            id = user.id,
                            slug = slug
                        )
                        Log.d(TAG, "✅ Auth Refreshed: ${user.username} (Slug: $slug)")
                    } else if (response.code() == 401) {
                         Log.w(TAG, "⚠️ Session Expired (401)")
                         withContext(Dispatchers.Main) {
                             prefs.clearAuth()
 
                             Toast.makeText(this@MobilePlayerActivity, getString(R.string.session_expired), Toast.LENGTH_SHORT).show()
                         }
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Auth Refresh Failed", e)
                }
            }
        }

        // Setup and show home screen initially
        setupHomeScreen()
        loadHomeScreenData()
        
        // Removed duplicate OnBackPressedCallback. Navigation is handled in setupHomeScreen.
        
        // MiniPlayer Controls
        binding.miniPlayerCloseButton.setOnClickListener {
            miniPlayerManager.closeMiniPlayer()
            
            // If profile is visible, keep profile state - user is still on profile page
            // If profile is NOT visible (e.g. on home screen), reset everything
            if (!channelProfileManager.isChannelProfileVisible) {
                returnToProfileSlug = null
            }
        }

        binding.miniPlayerMaximizeArea.setOnClickListener {
            if (channelProfileManager.isChannelProfileVisible) {
                // Save the currently visible profile's slug BEFORE closing it.
                // This way, pressing back from the maximized player returns to
                // THIS profile (B) — not the stale one from when the stream started (A).
                val profileSlug = channelProfileManager.currentProfileSlug
                channelProfileManager.closeChannelProfile()
                exitMiniPlayerMode()
                // Update navigation state so back returns to B's profile
                if (!profileSlug.isNullOrEmpty()) {
                    returnToProfileSlug = profileSlug
                    screenBeforePlayer = AppScreen.CHANNEL_PROFILE
                }
            } else {
                exitMiniPlayerMode()
            }
        }
        
        // Auto-update check on startup
        if (prefs.autoUpdateEnabled) {
            settingsPanelManager.checkForUpdates(isSilent = true)
        }
    }

    internal fun enterMiniPlayerMode() {
        miniPlayerManager.enterMiniPlayerMode()
        
        // Always pause chat UI in mini player mode (chat is hidden)
        chatUiManager.isChatUiPaused = true
        
        // If low battery mode is enabled, pause chat
        if (prefs.lowBatteryModeEnabled && miniPlayerManager.isMiniPlayerMode) {
            pauseChatForLowBatteryMode()
        }
    }

    internal fun exitMiniPlayerMode() {
        val wasInMiniPlayer = miniPlayerManager.isMiniPlayerMode
        
        // Snapshot the current visible screen behind the mini player.
        // When user presses back from the maximized player, they return
        // to whatever screen was visible at the moment they maximized.
        if (wasInMiniPlayer) {
            when {
                channelProfileManager.isChannelProfileVisible -> {
                    screenBeforePlayer = AppScreen.CHANNEL_PROFILE
                    returnToProfileSlug = channelProfileManager.currentProfileSlug
                }
                browseManager.isCategoryDetailsVisible -> {
                    screenBeforePlayer = AppScreen.CATEGORY_DETAILS
                    returnToProfileSlug = null
                }
                binding.searchContainer.visibility == View.VISIBLE -> {
                    screenBeforePlayer = AppScreen.SEARCH
                    returnToProfileSlug = null
                }
                binding.browseScreenContainer.root.visibility == View.VISIBLE -> {
                    screenBeforePlayer = AppScreen.BROWSE
                    returnToProfileSlug = null
                }
                binding.followingScreenContainer.root.visibility == View.VISIBLE -> {
                    screenBeforePlayer = AppScreen.FOLLOWING
                    returnToProfileSlug = null
                }
                else -> {
                    screenBeforePlayer = AppScreen.HOME
                    returnToProfileSlug = null
                }
            }
            Log.d(TAG, "exitMiniPlayerMode: snapshot screenBeforePlayer=$screenBeforePlayer, returnToProfileSlug=$returnToProfileSlug")
        }
        
        miniPlayerManager.exitMiniPlayerMode()
        
        // Resume Chat UI
        chatUiManager.isChatUiPaused = false
        
        if (prefs.lowBatteryModeEnabled && wasInMiniPlayer) {
            resumeChatForLowBatteryMode()
        }
    }

    // Track if chat is paused for low battery mode

    /**
     * Pauses chat subscription for low battery mode (PiP/MiniPlayer)
     */
    internal fun pauseChatForLowBatteryMode() {
        if (isChatPausedForLowBattery) return
        isChatPausedForLowBattery = true
        
        // Unsubscribe from chat to stop receiving messages
        chatConnectionManager.unsubscribeFromOnlyChat()
        
        // Add system message to chat
        val systemMessage = ChatMessage(
            id = "low_battery_pause_${System.currentTimeMillis()}",
            content = getString(R.string.chat_low_battery_paused),
            sender = ChatSender(0, getString(R.string.chat_system_username), null, null),
            type = dev.xacnio.kciktv.shared.data.model.MessageType.SYSTEM,
            iconResId = R.drawable.ic_hourglass
        )
        chatUiManager.handleIncomingMessage(systemMessage)
        chatUiManager.flushPendingMessages()
    }

    /**
     * Resumes chat subscription after returning from low battery mode
     */
    internal fun resumeChatForLowBatteryMode() {
        if (!isChatPausedForLowBattery) return
        isChatPausedForLowBattery = false
        
        // Show floating reconnect indicator (hides automatically when connected)
        binding.chatConnectionContainer.visibility = View.VISIBLE
        binding.chatConnectionProgress.visibility = View.VISIBLE
        binding.chatConnectionContainer.isClickable = false
        
        // Re-subscribe to chat
        chatConnectionManager.subscribeToOnlyChat()
    }

    // createNotificationChannel() is now delegated to playbackNotificationManager

    private fun setupPipReceivers() {
        broadcastManager.registerReceivers()
    }

    private fun setupMediaSession() {
        mediaSessionManager = dev.xacnio.kciktv.mobile.ui.player.MediaSessionManager(this)
        mediaSessionManager.setupMediaSession()
    }

    internal fun updateMediaSessionState(overrideIsPlaying: Boolean? = null) =
        mediaSessionManager.updateMediaSessionState(overrideIsPlaying)

    internal fun hideNotification() = mediaSessionManager.hideNotification()

    private fun getThemeIconRes(): Int = mediaSessionManager.getThemeIconRes()

    internal fun showNotification(overrideIsPlaying: Boolean? = null) =
        mediaSessionManager.showNotification(overrideIsPlaying)

    private fun applySavedLanguage() {
        val savedLang = prefs.language
        val locale = if (savedLang == "system") {
            // Get original system locale properly
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                @Suppress("NewApi")
                android.content.res.Resources.getSystem().configuration.locales.get(0)
            } else {
                @Suppress("DEPRECATION")
                android.content.res.Resources.getSystem().configuration.locale
            }
        } else {
            java.util.Locale(savedLang)
        }
        
        java.util.Locale.setDefault(locale)
        val config = resources.configuration
        config.setLocale(locale)
        
        @Suppress("DEPRECATION")
        resources.updateConfiguration(config, resources.displayMetrics)
    }

    private fun setupPlayer() {
        playerManager.externalListener = playerListener
        playerManager.setupPlayer()
        playerGestureManager.setupPlayerGestureDetector()
        setupFollowButton()
    }

    @SuppressLint("ClickableViewAccessibility")

    override fun onConfigurationChanged(newConfig: android.content.res.Configuration) {
        super.onConfigurationChanged(newConfig)
        
        val isTabletLike = isTabletOrLargeWindow()
        
        // CRITICAL: Skip entirely if we are in PiP mode or just exited from it
        // This check happens BEFORE the post to catch early timing issues
        val shouldSkipAutoLayout = if (::pipStateManager.isInitialized) {
            isInPictureInPictureMode || pipStateManager.suppressAutoFullscreen || pipStateManager.exitedPipMode
        } else {
            isInPictureInPictureMode
        }
        
        if (shouldSkipAutoLayout) {
            Log.d(TAG, "onConfigurationChanged: Skipping auto-layout (inPiP=$isInPictureInPictureMode, suppress=${if (::pipStateManager.isInitialized) pipStateManager.suppressAutoFullscreen else false}, exited=${if (::pipStateManager.isInitialized) pipStateManager.exitedPipMode else false})")
            // Clear exitedPipMode flag
            if (::pipStateManager.isInitialized && pipStateManager.exitedPipMode) {
                pipStateManager.exitedPipMode = false
            }
            // Still update layout managers even when skipping auto-layout
            if (::followingManager.isInitialized) {
                followingManager.updateLayout(newConfig)
            }
            if (::browseManager.isInitialized) {
                browseManager.updateLayout(newConfig)
            }
            return
        }
        
        // Use a post to ensure we get updated root dimensions after configuration change
        binding.root.post {
            // Double-check flags inside post in case they changed during the queue wait
            if (::pipStateManager.isInitialized && (pipStateManager.suppressAutoFullscreen || pipStateManager.exitedPipMode)) {
                Log.d(TAG, "onConfigurationChanged POST: Suppressing auto-fullscreen after PiP exit")
                pipStateManager.exitedPipMode = false
                return@post
            }
            
            val rootWidth = binding.root.width
            val rootHeight = binding.root.height
            val isActuallyLandscape = rootWidth > rootHeight
            
            Log.d(TAG, "onConfigurationChanged: isActuallyLandscape=$isActuallyLandscape, root=${rootWidth}x${rootHeight}, config=${newConfig.orientation}")
            
            if (isActuallyLandscape) {
                // Only auto-switch for player-related modes if the player screen is visible
                if (binding.playerScreenContainer.visibility == View.VISIBLE) {
                    if (isTabletLike) {
                        // Tablet: Auto split-screen
                        if (::fullscreenToggleManager.isInitialized && !fullscreenToggleManager.isSideChatVisible && !fullscreenToggleManager.isTheatreMode) {
                            fullscreenToggleManager.showSideChat(skipAnimation = true)
                        }
                    } else {
                        // Phone: Auto full-screen
                        if (::fullscreenToggleManager.isInitialized && !isFullscreen && !fullscreenToggleManager.isTheatreMode) {
                            fullscreenToggleManager.enterFullscreen()
                        }
                    }
                }
            } else {
                // Portrait mode
                // 1. Unlock tablet orientation to prevent "Stuck" rotation state (respects system lock)
                setAllowedOrientation()

                if (::fullscreenToggleManager.isInitialized) {
                    // Always return to standard portrait layout when rotating back to portrait
                    // Check boolean flags OR if container is physically visible (to catch de-syncs)
                    val isSideChatPhysicallyVisible = binding.sideChatContainer.visibility == View.VISIBLE
                    
                    if (fullscreenToggleManager.isSideChatVisible || isSideChatPhysicallyVisible || isFullscreen || fullscreenToggleManager.isTheatreMode) {
                        fullscreenToggleManager.exitFullscreen()
                        
                        // Double check cleanup if side chat was somehow stuck
                        if (isSideChatPhysicallyVisible) {
                            fullscreenToggleManager.cleanupSideChat()
                        }
                    }
                }
            }
        }
        
        // Update layout managers at the end (outside of early return paths)
        if (::followingManager.isInitialized) {
            followingManager.updateLayout(newConfig)
        }
        if (::browseManager.isInitialized) {
            browseManager.updateLayout(newConfig)
        }
        if (::streamFeedManager.isInitialized) {
            streamFeedManager.updateLayout(newConfig)
        }
        if (::clipFeedManager.isInitialized) {
            clipFeedManager.updateLayout(newConfig)
        }
        if (::channelProfileManager.isInitialized) {
            channelProfileManager.updateLayoutsForOrientation(newConfig)
        }
    }

    override fun onPictureInPictureModeChanged(isInPictureInPictureMode: Boolean, newConfig: android.content.res.Configuration) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig)
        
        if (::fullscreenToggleManager.isInitialized) {
            if (isInPictureInPictureMode) {
                 // Hide Side Chat if open when entering PiP
                 if (fullscreenToggleManager.isSideChatVisible) {
                     fullscreenToggleManager.hideSideChat()
                 }
                 // Handle Theatre Mode UI hiding
                 fullscreenToggleManager.onEnterPipMode()
            } else {
                 // Restore Theatre Mode UI
                 fullscreenToggleManager.onExitPipMode()
            }
        }
        
        // CRITICAL: Set suppress flag BEFORE calling pipStateManager
        // This ensures the flag is set before any onConfigurationChanged post blocks execute
        if (!isInPictureInPictureMode && ::pipStateManager.isInitialized) {
            pipStateManager.setSuppressAutoFullscreen(true)
        }
        
        pipStateManager.onPictureInPictureModeChanged(isInPictureInPictureMode)
    }

    private fun restoreUiFromPip() {
        // Handled by PipStateManager
    }
    
    override fun onPause() {
        super.onPause()
        // Pause feed videos when app goes to background
        if (::clipFeedManager.isInitialized) {
            clipFeedManager.onPause()
        }
        if (::streamFeedManager.isInitialized) {
            streamFeedManager.onPause()
        }
        
        // Update PiP params before pause to ensure auto-enter works correctly on Android 12+
        // This is needed when an intent is started (e.g., clicking a link in chat)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && prefs.autoPipEnabled) {
            val isPlaying = ivsPlayer?.state == Player.State.PLAYING
            if (isPlaying && !fullscreenToggleManager.isTheatreMode) {
                updatePiPUi()
            }
        }
    }

    internal fun handleRewindRequest() {
        liveDvrManager.handleRewindRequest()
    }

    internal fun returnToLive() {
        liveDvrManager.returnToLive()
    }

    private fun setupPlayerControls() {
        playbackControlManager.setupPlayerControls()
    }

    internal fun startProgressUpdater() = playbackControlManager.startProgressUpdater()

    internal fun stopProgressUpdater() = playbackControlManager.stopProgressUpdater()

    // Accessor for manager
    fun getHideOverlayRunnable() = hideOverlayRunnable

    internal fun updateSeekBarProgress() = playbackControlManager.updateSeekBarProgress()

    internal fun updateChatroomHint(chatroom: dev.xacnio.kciktv.shared.data.model.ChatroomInfo?) {
        currentChatroom = chatroom
        // Sync chatroom info to ChatStateManager for ChatUiManager to use
        if (chatroom != null) {
            chatStateManager.updateChatroom(chatroom)
        }
        updateChatLoginState() // Re-check subscriber mode or ban status
        
        // User state checks
        val isSubscribed = chatStateManager.isSubscribedToCurrentChannel
        val isFollowing = chatStateManager.isFollowingCurrentChannel
        val followingSince = chatStateManager.followingSince
        
        // Calculate follow duration in minutes
        val followDurationMinutes: Long = if (isFollowing && followingSince != null) {
            try {
                val followDate = java.time.ZonedDateTime.parse(followingSince)
                val now = java.time.ZonedDateTime.now()
                java.time.Duration.between(followDate, now).toMinutes()
            } catch (e: Exception) {
                0L
            }
        } else {
            0L
        }
        
        val followersMinDuration = chatroom?.followersMinDuration ?: 0
        val meetsFollowRequirement = isFollowing && (followersMinDuration == 0 || followDurationMinutes >= followersMinDuration)
        
        // Determine hint based on chat mode and user status
        // Priority: Not-Following > Not-Subscribed > Emotes > Default
        // User must meet follow requirement first before subscription matters
        val hint = when {
            isModeratorOrOwner -> getString(R.string.chat_hint_default)
            // Followers mode - check if user is blocked by follow requirement first
            chatroom?.followersMode == true && !meetsFollowRequirement -> {
                if (!isFollowing) {
                    // Not following - show required duration
                    if (followersMinDuration > 0) {
                        getString(R.string.chat_hint_follower_min_duration, followersMinDuration) // "Followers only (10 min)"
                    } else {
                        getString(R.string.chat_hint_followers_only) // "Followers only chat"
                    }
                } else {
                    // Following but not long enough - show remaining time
                    val remainingMinutes = (followersMinDuration - followDurationMinutes).coerceAtLeast(1)
                    getString(R.string.chat_hint_follower_remaining, remainingMinutes.toInt()) // "X min remaining for chat"
                }
            }
            // Subscribers mode - only shown if follow requirement is met (or not active)
            chatroom?.subscribersMode == true -> {
                if (isSubscribed) {
                    getString(R.string.chat_hint_default) // "Type a message..."
                } else {
                    getString(R.string.chat_hint_subscribers_only) // "Subscribers only"
                }
            }
            // Emotes mode
            chatroom?.emotesMode == true -> {
                getString(R.string.chat_hint_emotes_only) // "Emote kullanın..."
            }
            // Default (includes slow mode which doesn't block)
            else -> getString(R.string.chat_hint_default)
        }
        binding.chatInput.hint = hint
        
        // Manage followers countdown timer
        chatInputStateManager.startFollowersCountdown()
        
        // Update chat mode icons
        chatUiManager.updateChatModeIndicators()
        
        // Update Moderator Panel if open
        modActionsManager.notifyModActionsUpdate()
    }

    private fun handleChatroomEvent(event: String, data: String? = null) {
        chatEventHandler.handleEvent(event, data)
    }

    internal fun onStreamerIsLive() {
        runOnUiThread {
            currentChannel?.let { loadStreamUrl(it) }
        }
    }

    internal fun updateChatOverlayState() {
        overlayManager.updateChatOverlayState()
    }

    private fun swapOverlayStack() {
        overlayManager.swapOverlayStack()
    }

    internal fun updatePollUI(poll: dev.xacnio.kciktv.shared.data.model.PollData) {
        overlayManager.updatePollUI(poll)
    }

    private fun startPollTimer() = pollTimerManager.startPollTimer()

    private fun handlePollCompletion(poll: dev.xacnio.kciktv.shared.data.model.PollData) = pollTimerManager.handlePollCompletion(poll)

    internal fun stopPollTimer() = pollTimerManager.stopPollTimer()

    private fun voteInPoll(optionId: Int) = pollTimerManager.voteInPoll(optionId)

    internal fun addSystemMessage(content: String, iconResId: Int? = null) =
        chatSystemMessageManager.addSystemMessage(content, iconResId)

    internal fun addInfoMessage(content: String) = chatSystemMessageManager.addInfoMessage(content)

    internal fun updateChatLoginState() {
        authManager.updateChatLoginState()
    }

    internal fun isUserLockedByChatMode(): Boolean {
        if (isModeratorOrOwner) return false
        
        // Subscriber mode lock
        if (currentChatroom?.subscribersMode == true && !isSubscribedToCurrentChannel) {
            return true
        }
        
        return false
    }

    internal fun updateUserHeaderState() {
        authManager.updateUserHeaderState()
    }

    private fun updateLoginButtonState() {
        // ... (removed)
    }

    private fun showLanguageFilterDialog() = languageFilterManager.showLanguageFilterDialog()

    private fun setupBottomNavigation() {
        binding.mainBottomNavigation.setOnItemSelectedListener { item ->
             // Update visuals
             updateNavVisualsById(item.itemId)
             
             if (isNavigationProgrammatic) return@setOnItemSelectedListener true
             
             handleNavigation(item.itemId)
        }
    }

    private fun setupClickListeners() {
        setupBottomNavigation()

        // Login Button (Header)
        binding.mobileLoginBtn.setOnClickListener {
            startActivity(Intent(this, LoginActivity::class.java))
        }

        // Settings Button (Visible when logged out)
        binding.mobileSettingsBtn.setOnClickListener {
            settingsPanelManager.showSettingsPanel()
        }
        // Video settings button on video overlay (Top Right)
        binding.videoSettingsButton.setOnClickListener {
            showVideoSettingsDialog()
        }
        // Fullscreen button
        binding.fullscreenButton.setOnClickListener {
            if (isFullscreen) exitFullscreen() else enterFullscreen()
        }
        // Fullscreen button long press - Theatre Mode (Dikey İzleme Modu)
        binding.fullscreenButton.setOnLongClickListener {
            fullscreenToggleManager.enterTheatreMode()
            true
        }
        
        binding.theatreModeButton.setOnClickListener {
            if (!fullscreenToggleManager.isTheatreMode) {
                fullscreenToggleManager.enterTheatreMode()
            } else {
                fullscreenToggleManager.exitTheatreMode()
            }
        }
        // Mute button
        binding.muteButton.setOnClickListener {
            toggleMute()
            updatePiPUi(overrideIsPlaying = null)
            if (isBackgroundAudioEnabled) showNotification()
        }
        // Quality badge tap to open quality selector
        binding.videoQualityBadge.setOnClickListener {
            val mode = vodManager.currentPlaybackMode
            // Disable quality change for Clips (single quality mp4)
            if (mode != dev.xacnio.kciktv.mobile.ui.player.VodManager.PlaybackMode.CLIP) {
                showVideoSettingsDialog()
            }
        }

        // Retry button
        binding.retryButton.setOnClickListener {
            playCurrentChannel()
        }
        // Video tap to show/hide controls
        binding.videoContainer.setOnClickListener {
            toggleOverlay()
        }
        // Play/Pause Overlay click toggles state
        binding.playPauseOverlay.setOnClickListener {
            toggleOverlay() // Hide it when background is clicked
        }
        binding.playPauseButton.setOnClickListener {
            togglePlayPause()
        }
        // Mentions Actions
        binding.mentionsButton.setOnClickListener {
            showMentionsBottomSheet()
        }
        // Settings back button
        binding.settingsBackButton.setOnClickListener {
            hideSettingsPanel()
        }
        // Pinned Message Actions
        binding.pinnedExpand.setOnClickListener {
            overlayManager.togglePinnedMessageExpansion()
        }
        binding.pinnedClose.setOnClickListener {
            overlayManager.hidePinnedMessageManually()
        }
        binding.restorePinnedMessage.setOnClickListener {
            overlayManager.restorePinnedMessageManually()
        } 
        // Poll Actions
        binding.pollExpand.setOnClickListener {
            overlayManager.togglePollExpansion()
        }
        binding.pollCloseButton.setOnClickListener {
            overlayManager.hidePollManually()
        }
        binding.restorePoll.setOnClickListener {
            overlayManager.restorePollManually()
        }
        // Prediction Actions
        binding.predictionCloseButton.setOnClickListener {
            overlayManager.hidePredictionManually()
        }
        binding.restorePrediction.setOnClickListener {
            overlayManager.restorePredictionManually()
        }
        // Long press on pinned message to unpin (for moderators/owners only)
        binding.pinnedMessageContainer.setOnLongClickListener {
            if (isModeratorOrOwner && prefs.isLoggedIn) {
                overlayManager.showUnpinConfirmationDialog()
                true
            } else {
                false
            }
        }
        binding.chatErrorClose.setOnClickListener {
            binding.chatErrorContainer.visibility = View.GONE
        }
        binding.chatStatusClose.setOnClickListener {
            binding.chatStatusContainer.visibility = View.GONE
            mainHandler.removeCallbacks(hideStatusRunnable)
        }
        binding.blerpButton.setOnClickListener {
            currentBlerpUrl?.let { url ->
                // Cancel cleanup timer when opening/reopening
                blerpCleanupHandler.removeCallbacks(blerpCleanupRunnable)

                if (cachedBlerpFragment == null) {
                    cachedBlerpFragment = BlerpBottomSheetFragment.newInstance(url)
                }
                
                cachedBlerpFragment?.let { fragment ->
                    if (!fragment.isAdded) {
                        fragment.show(supportFragmentManager, "blerp_sheet")
                    }
                }
            }
        }

        // Moderator Actions Menu Button
        binding.modMenuButton.setOnClickListener {
            modActionsManager.showModActionsSheet()
        }

        // Initialize Gesture Detector for ViewFlipper (Pinned/Poll Swipe)
        overlayGestureDetector = android.view.GestureDetector(this, object : android.view.GestureDetector.SimpleOnGestureListener() {
            override fun onDown(e: android.view.MotionEvent): Boolean {
                return true
            }

            override fun onFling(
                e1: android.view.MotionEvent?,
                e2: android.view.MotionEvent,
                velocityX: Float,
                velocityY: Float
            ): Boolean {
                val e1Val = e1 ?: return false
                val diffY = e2.y - e1Val.y
                val diffX = e2.x - e1Val.x
                
// Log.d("OverlayStack", "onFling: diffX=$diffX, diffY=$diffY, vX=$velocityX, vY=$velocityY")
                
                // HIGH sensitivity flings for switching stack
                if (kotlin.math.abs(diffY) > 20 || kotlin.math.abs(diffX) > 20) {
                    if (kotlin.math.abs(velocityX) > 50 || kotlin.math.abs(velocityY) > 50) {
// Log.d("OverlayStack", "Fling detected, swapping!")
                        swapOverlayStack()
                        return true
                    }
                }
                
                return false
            }
        })
        // Layout Toggle Button
    }

    private var isTrackingOverlayGesture = false

    override fun dispatchTouchEvent(ev: android.view.MotionEvent): Boolean {
        // Auto-close emote panel when touching outside
        if (ev.action == android.view.MotionEvent.ACTION_DOWN) {
            try {
                if (::binding.isInitialized && binding.emotePanelContainer.visibility == View.VISIBLE) {
                    val panelRect = android.graphics.Rect()
                    binding.emotePanelContainer.getGlobalVisibleRect(panelRect)

                    val inputRect = android.graphics.Rect()
                    binding.chatInputRoot.getGlobalVisibleRect(inputRect)

                    val x = ev.rawX.toInt()
                    val y = ev.rawY.toInt()

                    if (!panelRect.contains(x, y) && !inputRect.contains(x, y)) {
                        emotePanelManager.toggleEmotePanel(false)
                        return true
                    }
                }
            } catch (e: Exception) {
                // Ignore touch calculation errors
            }
        }

        if (::overlayGestureDetector.isInitialized && binding.chatOverlayContainer.visibility == View.VISIBLE) {
            val rect = android.graphics.Rect()
            binding.chatOverlayContainer.getGlobalVisibleRect(rect)
            
            if (ev.action == android.view.MotionEvent.ACTION_DOWN) {
                isTrackingOverlayGesture = rect.contains(ev.rawX.toInt(), ev.rawY.toInt())
            }
            
            if (isTrackingOverlayGesture) {
                overlayGestureDetector.onTouchEvent(ev)
            }
            
            if (ev.action == android.view.MotionEvent.ACTION_UP || ev.action == android.view.MotionEvent.ACTION_CANCEL) {
                isTrackingOverlayGesture = false
            }
        }
        return super.dispatchTouchEvent(ev)
    }

    private fun togglePlayPause() = playerControlsManager.togglePlayPause()

    internal fun showAboutDialog() {
        val dialog = com.google.android.material.bottomsheet.BottomSheetDialog(this, R.style.BottomSheetDialogTheme)
        val view = layoutInflater.inflate(R.layout.bottom_sheet_about, null)
        dialog.setContentView(view)

        // Version
        try {
            val pInfo = packageManager.getPackageInfo(packageName, 0)
            view.findViewById<android.widget.TextView>(R.id.aboutVersionText)?.text = 
                getString(R.string.about_version_format, pInfo.versionName)
        } catch (_: Exception) {}

        // Links helper - open via link confirmation dialog
        val openLink = { url: String ->
            dev.xacnio.kciktv.mobile.util.DialogUtils.showLinkConfirmationDialog(this, url)
        }

        view.findViewById<android.view.View>(R.id.btnRepo)?.setOnClickListener {
            openLink(getString(R.string.url_repo))
        }
        
        view.findViewById<android.view.View>(R.id.btnGithub)?.setOnClickListener {
            openLink(getString(R.string.url_github))
        }
        
        view.findViewById<android.view.View>(R.id.btnCoffee)?.setOnClickListener {
            openLink(getString(R.string.url_coffee))
        }

        // Configure bottom sheet behavior
        dialog.findViewById<android.view.View>(com.google.android.material.R.id.design_bottom_sheet)?.let {
            val behavior = com.google.android.material.bottomsheet.BottomSheetBehavior.from(it)
            behavior.state = com.google.android.material.bottomsheet.BottomSheetBehavior.STATE_EXPANDED
            behavior.skipCollapsed = true
            it.setBackgroundResource(R.drawable.bg_bottom_sheet)
        }

        dialog.show()
    }

    internal fun showSettingsPanel() = settingsPanelManager.showSettingsPanel()
    
    private fun hideSettingsPanel() = settingsPanelManager.hideSettingsPanel()

    // Settings helper functions now delegated to SettingsPanelManager

    private fun showLogoutConfirmDialog() = authManager.showLogoutConfirmDialog()

    // Moved to SearchUiManager: showSearchScreen, hideKeyboard, setupSearchListeners, performEmbeddedSearch
    // Removed searchJob

    internal fun loadChannelBySlug(slug: String) = channelLoadManager.loadChannelBySlug(slug)

     /**
     * Adapter for search results (channels and categories)
     * Moved to dev.xacnio.kciktv.shared.ui.adapter.SearchResultAdapter
     */
     // Removed

    @SuppressLint("InflateParams")
    private fun showVideoSettingsDialog() = videoSettingsDialogManager.showVideoSettingsDialog()

    private fun showQualitySelector() {
        // Obsolete - removed in favor of integrated settings
    }

    internal fun updateContextualChannelInfo(username: String, verified: Boolean, followers: Int, isFollowing: Boolean) =
        channelUiManager.updateContextualChannelInfo(username, verified, followers, isFollowing)

    internal fun playChannel(channel: ChannelItem, originScreen: AppScreen? = null) {
        fullscreenToggleManager.hasAutoEnteredTheatreMode = false
        Log.d(TAG, "playChannel() called: slug=${channel.slug}, originScreen=$originScreen")
        Log.d(TAG, "  returnToProfileSlug=$returnToProfileSlug, isChannelProfileVisible=${channelProfileManager.isChannelProfileVisible}")
        Log.d(TAG, "  isMiniPlayerMode=${miniPlayerManager.isMiniPlayerMode}, currentChannel=${currentChannel?.slug}")
        
        // returnToProfileSlug is already set by openChannel() before calling playChannel()
        
        // Save robust navigation state (Prefer explicit origin if provided)
        screenBeforePlayer = originScreen ?: currentScreen
        
        // Explicitly track if we should return to category details (Legacy/Hybrid check)
        // Check if set externally first, otherwise check manager/visibility logic
        if (!returnToCategoryDetails) {
            returnToCategoryDetails = (screenBeforePlayer == AppScreen.CATEGORY_DETAILS) ||
                                     (::browseManager.isInitialized && browseManager.isCategoryDetailsVisible) || 
                                     binding.categoryDetailsContainer.root.visibility == View.VISIBLE
        }
        
        // Save navigation state (Legacy)
        // Prioritize Manager states over View visibility for reliability
        previousScreenId = when {
            ::channelProfileManager.isInitialized && channelProfileManager.isChannelProfileVisible -> R.id.channelProfileContainer
            returnToProfileSlug != null -> R.id.channelProfileContainer // Explicit override from Profile Manager
            ::browseManager.isInitialized && browseManager.isCategoryDetailsVisible -> R.id.categoryDetailsContainer
            binding.channelProfileContainer.root.visibility == View.VISIBLE -> R.id.channelProfileContainer
            binding.categoryDetailsContainer.root.visibility == View.VISIBLE -> R.id.categoryDetailsContainer
            binding.browseScreenContainer.root.visibility == View.VISIBLE -> R.id.browseScreenContainer
            binding.followingScreenContainer.root.visibility == View.VISIBLE -> R.id.followingScreenContainer
            binding.searchContainer.visibility == View.VISIBLE -> R.id.searchContainer
            else -> R.id.homeScreenContainer
        }

        if (miniPlayerManager.isMiniPlayerMode) exitMiniPlayerMode()
        vodManager.currentPlaybackMode = dev.xacnio.kciktv.mobile.ui.player.VodManager.PlaybackMode.LIVE
        updatePlaybackUI()
        
        // Cancel any ongoing loading operations to prevent race conditions
        playerManager.cancelLoadingOperations()
        
        // Stop previous playback and clean up previous channel state
        ivsPlayer?.pause()
        stopChatWebSocket()
        vodManager.stopVodChatReplay()
        viewerCountHandler.removeCallbacks(viewerCountRunnable)
        currentLivestreamId = null
        playbackStatusManager.stopUptimeUpdater()
        playbackControlManager.stopProgressUpdater()
        
        // Reset chat UI for new channel
        chatUiManager.reset()
        chatUiManager.isChatUiPaused = false

        // FORCE RESET PLAYER LAYOUT STATE
        // Fix for "stuck layout" when closing video in landscape vs portrait
        // Strategy: ALWAYS start with portrait layout. If device is actually in landscape,
        // onConfigurationChanged will fire and apply split-screen automatically.
        if (::fullscreenToggleManager.isInitialized) {
             // STEP 1: Clean up any existing side chat state
             fullscreenToggleManager.cleanupSideChat(forceReset = true)
             fullscreenToggleManager.exitFullscreen(forceCleanReset = true)
             
             // STEP 2: Force portrait constraints - onConfigurationChanged handles landscape
             val params = binding.videoContainer.layoutParams as ConstraintLayout.LayoutParams
             params.dimensionRatio = "16:9"
             params.width = 0 
             params.height = 0
             params.startToStart = ConstraintLayout.LayoutParams.PARENT_ID
             params.endToEnd = ConstraintLayout.LayoutParams.PARENT_ID
             params.topToTop = ConstraintLayout.LayoutParams.PARENT_ID
             params.bottomToBottom = ConstraintLayout.LayoutParams.UNSET
             params.endToStart = ConstraintLayout.LayoutParams.UNSET
             binding.videoContainer.layoutParams = params
             
             // STEP 3: Check REAL orientation after layout completes
             if (isTabletOrLargeWindow()) {
                  // Wait for layout to complete, then check actual root view dimensions
                  binding.root.viewTreeObserver.addOnGlobalLayoutListener(object : android.view.ViewTreeObserver.OnGlobalLayoutListener {
                       override fun onGlobalLayout() {
                            binding.root.viewTreeObserver.removeOnGlobalLayoutListener(this)
                            
                            val rootWidth = binding.root.width
                            val rootHeight = binding.root.height
                            val isActuallyLandscape = rootWidth > rootHeight
                            val isSideChatAlreadyVisible = fullscreenToggleManager.isSideChatVisible
                            Log.d(TAG, "playChannel layout check: isActuallyLandscape=$isActuallyLandscape, isSideChatVisible=$isSideChatAlreadyVisible, root=${rootWidth}x${rootHeight}")
                            
                            if (isActuallyLandscape && !isSideChatAlreadyVisible) {
                                 Log.d(TAG, "playChannel: Calling showSideChat")
                                 fullscreenToggleManager.showSideChat(skipAnimation = true)
                            } else {
                                 Log.d(TAG, "playChannel: NOT calling showSideChat (landscape=$isActuallyLandscape, sideChatVisible=$isSideChatAlreadyVisible)")
                            }
                       }
                  })
             }
        }

        // Clear mentions for previous channel
        mentionMessages.clear()
        lastSeenMentionCount = 0
        updateMentionsBadge()
        
        currentChannel = channel
        currentChannelIndex = allChannels.indexOfFirst { it.slug == channel.slug }
        
        // Clear previous channel state
        emoteComboManager.clear()
        floatingEmoteManager.clear()
        
        // Save as last watched
        prefs.lastWatchedChannelSlug = channel.slug
        
        // Show player screen
        // actionBar visibility is managed by ChannelUiManager (animateActionBarIn/Out)
        // Do NOT set it here - it would override the GONE state and prevent animations
        binding.chatContainer.visibility = View.VISIBLE
        
        val wasPlayerVisible = binding.playerScreenContainer.visibility == View.VISIBLE
        binding.playerScreenContainer.visibility = View.VISIBLE
        updateNavigationBarColor(true) // Set navigation bar to black for player screen
        
        val hideOtherScreens = {
            binding.mobileHeader.visibility = View.GONE
            binding.homeScreenContainer.root.visibility = View.GONE
            binding.browseScreenContainer.root.visibility = View.GONE
            binding.followingScreenContainer.root.visibility = View.GONE
            binding.searchContainer.visibility = View.GONE
            binding.startupLoadingOverlay.visibility = View.GONE
            binding.categoryDetailsContainer.root.visibility = View.GONE // Ensure category details is hidden
            // Hide channel profile too - we don't return to it anymore
            binding.channelProfileContainer.root.visibility = View.GONE
            if (::channelProfileManager.isInitialized) {
                channelProfileManager.isChannelProfileVisible = false
            }
            binding.bottomNavContainer.visibility = View.GONE
            binding.bottomNavGradient.visibility = View.GONE
            
            // Hide Feed Views
            if (::streamFeedManager.isInitialized) {
                streamFeedManager.feedRootView?.visibility = View.GONE
            }
            if (::clipFeedManager.isInitialized) {
                clipFeedManager.feedRootView?.visibility = View.GONE
            }
        }
        
        if (!wasPlayerVisible) {
            binding.playerScreenContainer.translationY = resources.displayMetrics.heightPixels.toFloat()
            binding.playerScreenContainer.animate()
                .translationY(0f)
                .setDuration(300)
                .setInterpolator(android.view.animation.DecelerateInterpolator())
                .withEndAction { hideOtherScreens() }
                .start()
        } else {
            hideOtherScreens()
        }

        isHomeScreenVisible = false
        
        // Log analytics event (anonymous - no channel data)
        analytics.logChannelView()
        
        showLoading()
        updateChannelUI(channel)

        // Vertical stream detection from thumbnail

        val thumbUrl = channel.thumbnailUrl ?: Constants.Urls.DEFAULT_LIVESTREAM_THUMBNAIL
        com.bumptech.glide.Glide.with(this)
            .asBitmap()
            .load(thumbUrl)
            .into(object : com.bumptech.glide.request.target.CustomTarget<android.graphics.Bitmap>() {
                override fun onResourceReady(resource: android.graphics.Bitmap, transition: com.bumptech.glide.request.transition.Transition<in android.graphics.Bitmap>?) {
                        if (resource.height > resource.width && !fullscreenToggleManager.isTheatreMode) {
                            fullscreenToggleManager.hasAutoEnteredTheatreMode = true
                            fullscreenToggleManager.updateVideoSize(resource.width, resource.height)
                            fullscreenToggleManager.enterTheatreMode()
                        }
                }
                override fun onLoadCleared(placeholder: android.graphics.drawable.Drawable?) {}
            })
  
        loadStreamUrl(channel)
        
        // (Split screen logic moved to start of function to prevent flicker)
        
        if (vodManager.currentPlaybackMode == dev.xacnio.kciktv.mobile.ui.player.VodManager.PlaybackMode.LIVE) {
            connectToChat(channel)
        }
        fetchCurrentChannelBans(channel.slug)
        fetchChannelFollowStatus(channel.slug)
        
        // Reset pinned message UI state for new channel
        chatStateManager.isPinnedMessageExpanded = false
        chatStateManager.isPinnedMessageHiddenByManual = false
        binding.restorePinnedMessage.visibility = View.GONE
        updatePinnedMessageUIState()
        
        // Reset prediction state
        chatStateManager.currentPrediction = null
        overlayManager.fetchInitialPrediction(channel.slug)
        
        // Reset poll state for new channel
        binding.pollContainer.visibility = View.GONE
        binding.restorePoll.visibility = View.GONE
        currentPoll = null
        chatStateManager.isPollHiddenManually = false
        chatStateManager.isPollCompleting = false
        pollTimerManager.stopPollTimer()
        
        // Update adapter selection

        // Show info panel and overlay for a few seconds
        showOverlay()

        // Check for Chat Rules
        chatRulesManager.checkAndShowRules(channel)
    }

    internal fun playCurrentChannel() = channelLoadManager.playCurrentChannel()

    private fun fetchCurrentChannelBans(slug: String) = channelLoadManager.fetchCurrentChannelBans(slug)

    private fun fetchChannelFollowStatus(channelSlug: String) = channelUiManager.fetchChannelFollowStatus(channelSlug)

    private fun updateChannelUI(channel: ChannelItem) = channelUiManager.updateChannelUI(channel)

    internal fun updatePlaybackUI() = channelUiManager.updatePlaybackUI()

    internal fun stopChatWebSocket() {
        android.util.Log.d("MobilePlayerActivity", "stopChatWebSocket() called")
        chatUiManager.stopFlushing()
        chatConnectionManager.disconnect()
    }

    internal fun playVideo(video: dev.xacnio.kciktv.shared.data.model.ChannelVideo, channelData: Any, startPositionMs: Long = 0L) {
        // Save return state if coming from profile
        if (channelProfileManager.isChannelProfileVisible) {
            val slug = when (channelData) {
                is dev.xacnio.kciktv.shared.data.model.ChannelDetailResponse -> channelData.slug
                is ChannelItem -> channelData.slug
                else -> null
            }
            if (!slug.isNullOrEmpty()) {
                returnToProfileSlug = slug
            }
            screenBeforePlayer = AppScreen.CHANNEL_PROFILE
            previousScreenId = R.id.channelProfileContainer
        } else if (binding.followingScreenContainer.root.visibility == View.VISIBLE) {
            returnToProfileSlug = null
            screenBeforePlayer = AppScreen.FOLLOWING
            previousScreenId = R.id.followingScreenContainer
        } else {
            returnToProfileSlug = null
            // Try to capture other states if possible, or leave as default (Home)
            if (binding.browseScreenContainer.root.visibility == View.VISIBLE) {
                 screenBeforePlayer = AppScreen.BROWSE
                 previousScreenId = R.id.browseScreenContainer
            } else if (binding.searchContainer.visibility == View.VISIBLE) {
                 screenBeforePlayer = AppScreen.SEARCH
                 previousScreenId = R.id.searchContainer
            }
        }

        dvrPlaybackUrl = null // Reset DVR mode for VOD
        
        var pos = startPositionMs
        // If starting from scratch (or unknown), check history
        if (pos == 0L) {
            val videoId = video.uuid ?: video.id?.toString()
            if (videoId != null) {
                val saved = prefs.getVodProgress(videoId)
                if (saved != null) {
                    // Start from saving point, but maybe rewind a few seconds for context?
                    // For now exact position.
                    pos = saved.watchedDuration
                    // If video was finished (e.g. within last 30 seconds or 95%), maybe restart?
                    // Logic: if (duration > 0 && pos > duration * 0.95) pos = 0
                    if (saved.duration > 0 && pos > saved.duration - 30000) {
                       pos = 0L
                    }
                }
            }
        }
        
        vodManager.playVideo(video, channelData, pos)
        // NOTE: Split screen is handled in playChannel, not here
    }

    internal fun playClip(clip: dev.xacnio.kciktv.shared.data.model.ChannelClip, channelData: Any) {
        // Save return state if coming from profile
        if (channelProfileManager.isChannelProfileVisible) {
            val slug = when (channelData) {
                is dev.xacnio.kciktv.shared.data.model.ChannelDetailResponse -> channelData.slug
                is ChannelItem -> channelData.slug
                else -> null
            }
            if (!slug.isNullOrEmpty()) {
                returnToProfileSlug = slug
            }
            screenBeforePlayer = AppScreen.CHANNEL_PROFILE
            previousScreenId = R.id.channelProfileContainer
        } else if (binding.followingScreenContainer.root.visibility == View.VISIBLE) {
            returnToProfileSlug = null
            screenBeforePlayer = AppScreen.FOLLOWING
            previousScreenId = R.id.followingScreenContainer
        } else {
            returnToProfileSlug = null
            // Try to capture other states if possible
            if (binding.browseScreenContainer.root.visibility == View.VISIBLE) {
                 screenBeforePlayer = AppScreen.BROWSE
                 previousScreenId = R.id.browseScreenContainer
            } else if (binding.searchContainer.visibility == View.VISIBLE) {
                 screenBeforePlayer = AppScreen.SEARCH
                 previousScreenId = R.id.searchContainer
            }
        }

        dvrPlaybackUrl = null // Reset DVR mode for Clip
        vodManager.playClip(clip, channelData)
        // NOTE: Split screen handled by layout reset logic, not here
    }

    // Returns true for both VOD and CLIP modes (non-live playback)
    internal val isVodPlaying: Boolean get() = vodManager.currentPlaybackMode != dev.xacnio.kciktv.mobile.ui.player.VodManager.PlaybackMode.LIVE
    internal val currentVodUuid: String? get() = vodManager.currentVideo?.uuid

    internal fun loadStreamUrl(channel: ChannelItem) {
        playerManager.loadStreamUrl(channel)
    }

    private val sessionManager = dev.xacnio.kciktv.mobile.ui.session.ChannelSessionManager(this)

    private fun connectToChat(channel: ChannelItem) {
        sessionManager.startChannelSession(channel)
    }
    
    private fun setupQuickEmoteBar() = quickEmoteBarManager.setupQuickEmoteBar()

    internal fun updateQuickEmoteBar() = quickEmoteBarManager.updateQuickEmoteBar()

    internal fun addRecentEmote(emote: Emote) = quickEmoteBarManager.addRecentEmote(emote)
    
    private fun updateEmoteSubscriptionStatus() {
        emotePanelManager.updateSubscriptionStatus()
        
        // Update quick emote bar subscription status
        quickEmoteBarManager.updateSubscriptionStatus(chatStateManager.isSubscribedToCurrentChannel)
        
        // Also refresh quick bar since usability changed
        runOnUiThread {
            quickEmoteBarManager.updateQuickEmoteBar()
        }
    }

    private var seekAnimAccumulator = 0
    private val seekAnimResetRunnable = Runnable { seekAnimAccumulator = 0 }

    internal fun showSeekAnimation(seconds: Int) {
        // Cancel reset
        mainHandler.removeCallbacks(seekAnimResetRunnable)
        
        // Check direction change and reset if needed
        if ((seconds > 0 && seekAnimAccumulator < 0) || (seconds < 0 && seekAnimAccumulator > 0)) {
            seekAnimAccumulator = 0
        }
        
        seekAnimAccumulator += seconds
        
        val text = if (seekAnimAccumulator > 0) "+${seekAnimAccumulator}s" else "${seekAnimAccumulator}s"
        val view = if (seconds > 0) binding.forwardSeekAnim else binding.rewindSeekAnim
        val otherView = if (seconds > 0) binding.rewindSeekAnim else binding.forwardSeekAnim
        
        // Hide other view immediately
        otherView.visibility = View.GONE
        otherView.animate().cancel()
        
        view.apply {
            this.text = text
            visibility = View.VISIBLE
            alpha = 1f
            // Cancel previous animation on this view
            animate().cancel()
            
            // Start fade out with delay
            animate()
                .alpha(0f)
                .setDuration(600)
                .setStartDelay(500)
                .withEndAction { visibility = View.GONE }
                .start()
        }
        
        // Reset accumulator after delay
        mainHandler.postDelayed(seekAnimResetRunnable, 1000)
    }

    private fun togglePinnedMessageExpansion() {
        overlayManager.togglePinnedMessageExpansion()
    }

    internal fun updatePinnedMessageUIState() {
        overlayManager.updatePinnedMessageUIState()
    }

    private fun hidePinnedMessageManually() {
        overlayManager.hidePinnedMessageManually()
    }

    private fun togglePollExpansion() {
        overlayManager.togglePollExpansion()
    }

    internal fun updatePollUIState() {
        overlayManager.updatePollUIState()
    }

    private fun hidePollManually() {
        overlayManager.hidePollManually()
    }

    private fun restorePollManually() {
        overlayManager.restorePollManually()
    }

    private fun showUnpinConfirmationDialog() {
        overlayManager.showUnpinConfirmationDialog()
    }
    
    private fun unpinMessageFromChannel(slug: String) {
        val token = prefs.authToken
        if (token == null) {
            Toast.makeText(this, getString(R.string.login_required), Toast.LENGTH_SHORT).show()
            return
        }
        
        lifecycleScope.launch {
            val result = repository.unpinMessage(slug, token)
            result.onSuccess {
                runOnUiThread {
                    Toast.makeText(this@MobilePlayerActivity, getString(R.string.pin_removed), Toast.LENGTH_SHORT).show()
                    // Hide pinned message UI (server will also send PinnedMessageDeletedEvent)
                    // clearPinnedEmotes() removed
                    android.transition.TransitionManager.beginDelayedTransition(binding.chatContainer)
                    binding.pinnedMessageContainer.visibility = View.GONE
                    binding.restorePinnedMessage.visibility = View.GONE
                    chatStateManager.isPinnedMessageHiddenByManual = false
                }
            }.onFailure { error ->
                runOnUiThread {
                    Toast.makeText(this@MobilePlayerActivity, getString(R.string.error_format, error.message), Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    internal fun showOfflineState(bannerUrl: String?, bannerSrc: String?, defaultBannerUrl: String?, bannerSrcset: String? = null) {
        playerManager.showOfflineState(bannerUrl, bannerSrc, defaultBannerUrl, bannerSrcset)
    }

    internal fun updateMentionsBadge() {
        mentionsManager.updateMentionsBadge()
    }

    private fun showMentionsBottomSheet() {
        mentionsManager.showMentionsBottomSheet()
    }

    private fun showAccountPopupMenu(anchor: View) = accountPopupManager.showAccountPopupMenu(anchor)

    @SuppressLint("InflateParams")
    internal fun showChatSettingsSheet(startOnProfile: Boolean = false) {
        chatSettingsSheetManager.showChatSettingsSheet(startOnProfile)
    }

    internal fun toggleEmotePanel() {
        emotePanelManager.toggleEmotePanel()
    }

    internal fun appendEmoteToInput(editText: android.widget.EditText, emote: Emote) {
        val name = emote.name
        val editable = editText.text ?: return
        
        val start = editText.selectionStart.coerceAtLeast(0)
        val needsLeadingSpace = start > 0 && editable[start - 1] != ' '
        
        val insertText = (if (needsLeadingSpace) " " else "") + name + " "
        editable.insert(start, insertText)
        
        // Move cursor past the inserted word and space
        editText.setSelection((start + insertText.length).coerceAtMost(editable.length))
    }

    internal fun captureVideoScreenshot() = screenshotManager.captureVideoScreenshot()

    internal fun showError(message: String) = loadingStateManager.showError(message)

    internal fun showLoading() = loadingStateManager.showLoading()

    internal fun hideLoading() = loadingStateManager.hideLoading()

    private fun toggleOverlay() = overlayControlManager.toggleOverlay()

    internal fun showOverlay() = overlayControlManager.showOverlay()

    internal fun hideOverlay() = overlayControlManager.hideOverlay()

    internal fun handleScreenTap() = overlayControlManager.handleScreenTap()

    private fun fetchCurrentViewerCount() = viewerCountManager.fetchCurrentViewerCount()

    internal fun formatStreamTime(seconds: Long): String = viewerCountManager.formatStreamTime(seconds)

    internal fun getLocalizedLanguageName(isoCode: String): String =
        languageFilterManager.getLocalizedLanguageName(isoCode)

    private fun enterFullscreen() = fullscreenToggleManager.enterFullscreen()

    private fun exitFullscreen() = fullscreenToggleManager.exitFullscreen()

    private fun toggleMute() = playerControlsManager.toggleMute()

    // onBackPressed override removed to use OnBackPressedDispatcher (setup in setupHomeScreen)
    // This fixes the priority issue where managers (like BrowseManager) were handling back events
    // even when the player was full screen.

    internal fun formatViewerCount(count: Long): String = viewerCountManager.formatViewerCount(count)

    internal fun getLanguageName(code: String?): String =
        languageFilterManager.getLanguageName(code)

    // ==================== Follow Button Logic ====================
    
    internal fun setupFollowButton() = infoPanelManager.setupFollowButton()
    
    // ==================== WebView Operations ====================
    // All WebView operations are delegated to webViewManager

    internal fun restoreSavedCookies() = webViewManager.restoreSavedCookies()
    
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    internal suspend fun fetchHistoryViaWebView(channelId: Long, userId: Long, cursor: String?) =
        webViewManager.fetchHistoryViaWebView(channelId, userId, cursor)

    internal fun performFollowViaWebView(slug: String, token: String, isFollow: Boolean) =
        webViewManager.performFollowViaWebView(slug, token, isFollow)

    private fun setupNotifications() {
        // Cancel any existing background notification work
        try {
            androidx.work.WorkManager.getInstance(applicationContext).cancelUniqueWork("StreamNotificationWork")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to cancel notification work", e)
        }
        
        // Request Permission
        if (Build.VERSION.SDK_INT >= 33) {
            if (checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                requestPermissions(arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), 101)
            }
        }
    }
    
    internal fun updateFollowButtonState() = infoPanelManager.updateFollowButtonState()

    private fun setupInfoPanelListeners() = infoPanelManager.setupInfoPanelListeners()

    internal fun followChannel(channel: ChannelItem) = infoPanelManager.followChannel(channel)
    
    internal fun showUnfollowDialog(channel: ChannelItem) = infoPanelManager.showUnfollowDialog(channel)

    // unfollowChannel is now handled internally by InfoPanelManager

    // ==================== Picture-in-Picture (PIP) Support ====================
    
    override fun onUserLeaveHint() {
        pipManager.handleUserLeaveHint()
        super.onUserLeaveHint()
    }

    private fun enterPipMode() = pipManager.enterPipMode()

    internal fun updatePiPUi(overrideIsPlaying: Boolean? = null) = pipManager.updatePiPUi(overrideIsPlaying)

    @RequiresApi(Build.VERSION_CODES.O)
    private fun getPipParams(overrideIsPlaying: Boolean? = null) = pipManager.getPipParams(overrideIsPlaying)

    internal fun dismissAllBottomSheets() = bottomSheetManager.dismissAllBottomSheets()

    internal fun trackBottomSheet(sheet: com.google.android.material.bottomsheet.BottomSheetDialog) =
        bottomSheetManager.trackBottomSheet(sheet)

    override fun onStart() {
        super.onStart()
        // If we started (came to foreground/fullscreen), reset exit flag
        exitedPipMode = false
        
        // Reset forced quality limit (return to user preference)
        // Note: This is redundant if onPictureInPictureModeChanged handled it, but safe for other cases (like returning from background audio)
        setForcedQualityLimit(null)
        
        // Restore UI from background audio mode
        // This is needed when coming back from background audio (not PiP)
        // PiP restoration is handled by PipStateManager.exitPipMode()
        val isInPip = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) isInPictureInPictureMode else false
        if (!isInPip && !miniPlayerManager.isMiniPlayerMode) {
            // Always restore global bottom navigation bar (they may have been hidden in PiP)
            // Only show if not in player screen
            val shouldShowNav = binding.playerScreenContainer.visibility != View.VISIBLE
            binding.bottomNavContainer.visibility = if (shouldShowNav) View.VISIBLE else View.GONE
            binding.bottomNavGradient.visibility = if (shouldShowNav) View.VISIBLE else View.GONE
            
            // Restore player-specific UI if watching a stream
            if (currentChannel != null) {
                // Ensure player screen UI is visible when returning from background audio
                if (binding.chatContainer.visibility != View.VISIBLE) {
                    binding.chatContainer.visibility = View.VISIBLE
                }
                if (binding.playerScreenContainer.visibility == View.VISIBLE) {
                    binding.bottomSheetCoordinator.visibility = View.VISIBLE
                    // Restore chatroom hint
                    updateChatroomHint(currentChatroom)
                }
            }
        }
        
        // Resume UI updates and flush buffer
        chatUiManager.isChatUiPaused = false
        chatUiManager.startFlushing() // Ensure the loop is running (restarts if dead)
        chatUiManager.flushPendingMessages()
        
        // Resume chat if paused for low battery mode (Legacy/Safety check)
        if (isChatPausedForLowBattery) {
            Log.d(TAG, "Resuming chat after low battery pause")
            resumeChatForLowBatteryMode()
            isChatPausedForLowBattery = false
        }
    }

    override fun onStop() {
        super.onStop()
        
        // If we explicitly exited PiP mode (closed the window), stop playback
        if (exitedPipMode) {
            Log.d(TAG, "PiP mode exited explicitely - stopping playback")
            ivsPlayer?.pause()
            isBackgroundAudioEnabled = false
            hideNotification()
            exitedPipMode = false
            return
        }

        playbackStatusManager.stopUptimeUpdater()
        
        // Don't do anything if already in PIP mode
        val isInPip = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) isInPictureInPictureMode else false
        if (isInPip && !isExplicitAudioSwitch) return
        
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && 
            Build.VERSION.SDK_INT < Build.VERSION_CODES.S && 
            prefs.autoPipEnabled) {
            
            // For Android 8-11: Enter PIP mode when going to background
            val isPlaying = ivsPlayer?.state == Player.State.PLAYING
            if (isPlaying && !isInPip) {
                try {
                    pipManager.enterPipMode()
                    return // Don't continue with background audio logic if entering PIP
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to enter PIP in onStop", e)
                }
            }
        }
        
        // If background audio is enabled (via settings or PIP button), just show notification
        // Don't pause - let the player continue in background
        // FIX: Ensure player is actually active before showing notification
        val isPlayerActive = ivsPlayer != null && currentStreamUrl != null && 
                             ivsPlayer?.state != Player.State.IDLE && ivsPlayer?.state != Player.State.ENDED
        
        // If the mini player (internal) or normal player is NOT visible, we should not do background playback
        // unless explicitly requested or via PiP (which is already handled above)
        val isPlayerVisible = binding.playerScreenContainer.visibility == View.VISIBLE
        
        val shouldKeepPlaying = (prefs.backgroundAudioEnabled || isBackgroundAudioEnabled) && 
                               isPlayerActive && isPlayerVisible
                               
        if (shouldKeepPlaying) {
            showNotification()
            // Enforce 360p for background playback
            setForcedQualityLimit("360p")
            
            // Battery Optimization:
            // Pause UI updates for chat while in background to save CPU/Battery.
            // Data will still be received and buffered.
            chatUiManager.isChatUiPaused = true
        } else {
            // Stop playback and notification if we are not staying in background mode
            if (!isInPip) {
                ivsPlayer?.pause()
                hideNotification()
            }
        }
    }

    override fun onResume() {
        super.onResume()
        
        // Ensure UI state matches physical orientation (Fix for persistent split-screen bug)
        val isLandscape = resources.configuration.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE
        
        // 1. Ensure tablets can always rotate (Fix "stuck orientation" bug, respects system lock)
        setAllowedOrientation()

        // 2. Cleanup split-screen artifacts if we are physically in portrait
        if (!isLandscape && ::fullscreenToggleManager.isInitialized) {
             if (fullscreenToggleManager.isSideChatVisible || binding.sideChatContainer.visibility == View.VISIBLE) {
                  fullscreenToggleManager.cleanupSideChat()
                  fullscreenToggleManager.exitFullscreen() 
             }
        }
        
        // Reset exit flag in case onStart wasn't called (e.g. from Paused state)
        exitedPipMode = false

        updateUserHeaderState()
        
        // We keep the notification visible for persistent controls (TikTok style)
        // hideNotification()
        // isBackgroundAudioEnabled = false
        
        // Clear focus from input to ensure quick emotes work correctly (direct send)
        // unless the user specifically clicks it again
        binding.chatInput.clearFocus()
        
        // Resume feed videos if they were paused by lifecycle
        if (::clipFeedManager.isInitialized) {
            clipFeedManager.onResume()
        }
        if (::streamFeedManager.isInitialized) {
            streamFeedManager.onResume()
        }
        
        // Resume chat UI - ensures chat works after screen lock/unlock
        // This is critical because onStart may not be called in some scenarios
        // (e.g., when background audio is enabled or certain lifecycle transitions)
        if (::chatUiManager.isInitialized && !miniPlayerManager.isMiniPlayerMode) {
            chatUiManager.isChatUiPaused = false
            chatUiManager.startFlushing()
        }
        
        // Refresh mini player interactions after PiP or other lifecycle changes
        if (::miniPlayerManager.isInitialized && miniPlayerManager.isMiniPlayerMode) {
            binding.root.postDelayed({
                miniPlayerManager.refreshInteractions()
            }, 200)
        }
        
        // Only auto-play if the player UI is visible and we have a valid stream
        if (binding.playerScreenContainer.visibility == View.VISIBLE && currentStreamUrl != null) {
            ivsPlayer?.play()
        }
    }

    override fun onDestroy() {
        if (::chatUiManager.isInitialized) chatUiManager.stopFlushing()
        super.onDestroy()
        
        // Clean up handlers
        viewerCountHandler.removeCallbacks(viewerCountRunnable)
        playbackStatusManager.stopUptimeUpdater()
        mainHandler.removeCallbacksAndMessages(null)
        
        // Clean up managers
        try {
            emoteComboManager.clear()
            floatingEmoteManager.clear()
            overlayManager.cleanup()
            chatStateManager.cleanup()
            vodManager.stopVodChatReplay()
        } catch (e: Exception) {
            Log.e(TAG, "Error cleaning up managers", e)
        }
        
        // Clean up receivers
        broadcastManager.unregisterReceivers()
        try {
            // Unregister others if needed
        } catch (e: Exception) {
            // Ignore
        }
        
        // Clear screen wake lock
        window.clearFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        
        ivsPlayer?.removeListener(playerListener)
        ivsPlayer?.release()
        ivsPlayer = null
        
        chatConnectionManager.disconnect()
        mediaSessionHelper?.release()
        hideNotification()
    }

    private fun scrollToBottom() = chatSystemMessageManager.scrollToBottom()

    private fun hideInternalSpinners(view: View) {
        if (view is android.widget.ProgressBar) {
            view.visibility = View.GONE
        } else if (view is android.view.ViewGroup) {
            for (i in 0 until view.childCount) {
                hideInternalSpinners(view.getChildAt(i))
            }
        }
    }

    private fun scrollToRepliedMessage(messageId: String) = chatReplyManager.scrollToRepliedMessage(messageId)

    internal var currentReplyMessageId: String? = null

    private fun prepareReply(message: dev.xacnio.kciktv.shared.data.model.ChatMessage) = chatReplyManager.prepareReply(message)

    private fun cancelReply() = chatReplyManager.cancelReply()

    // ==================== MESSAGE ACTIONS BOTTOM SHEET ====================
    // Delegated to MessageActionsSheetManager
    
    internal var currentChannelId: Long? = null
    
    private val messageActionsSheet get() = messageActionsSheetManager.messageActionsSheet
    private val selectedMessage get() = messageActionsSheetManager.selectedMessage

    internal fun showMessageActionsSheet(message: dev.xacnio.kciktv.shared.data.model.ChatMessage) =
        messageActionsSheetManager.showMessageActionsSheet(message)

    internal fun showUserActionsSheet(sender: dev.xacnio.kciktv.shared.data.model.ChatSender) =
        userActionsSheetManager.showUserActionsSheet(sender)

    private fun pinMessageToChannel(message: dev.xacnio.kciktv.shared.data.model.ChatMessage) = chatModerationManager.pinMessageToChannel(message)

    private fun deleteMessageFromChat(message: dev.xacnio.kciktv.shared.data.model.ChatMessage) = chatModerationManager.deleteMessageFromChat(message)

    private fun timeoutUserFromChat(username: String, durationMinutes: Int) = chatModerationManager.timeoutUserFromChat(username, durationMinutes)

    private fun showBanConfirmationDialog(username: String) = chatModerationManager.showBanConfirmationDialog(username)

    private fun banUserFromChat(username: String) = chatModerationManager.banUserFromChat(username)

    
    // ==================== Loyalty Points Logic ====================
    internal fun fetchLoyaltyPoints() = loyaltyPointsManager.fetchLoyaltyPoints()

    internal fun handleChannelPointsEvent(data: String) = loyaltyPointsManager.handleChannelPointsEvent(data)

    internal fun startViewerWebSocket(channelId: String, channelSlug: String, livestreamId: String?) =
        webViewManager.startViewerWebSocket(channelId, channelSlug, livestreamId)
    
    /**
     * Fetch a new viewer token for websocket reconnection
     * This is called by OverlayManager when viewer websocket needs to reconnect
     */
    internal fun fetchViewerToken(channelId: String, callback: (String?) -> Unit) =
        webViewManager.fetchViewerToken(channelId, callback)

    // ==================== HOME SCREEN ====================
    
    private fun setupHomeScreen() {
        // Initially show home screen, hide player
        binding.homeScreenContainer.root.visibility = View.VISIBLE
        binding.playerScreenContainer.visibility = View.GONE
        isHomeScreenVisible = true
        
        // Setup back button handling
        onBackPressedDispatcher.addCallback(this, object : androidx.activity.OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                Log.d(TAG, "=== BACK PRESSED (Dispatcher) ===")
                Log.d(TAG, "States: isFullscreen=$isFullscreen, isSettingsVisible=$isSettingsVisible")
                Log.d(TAG, "States: isMiniPlayerMode=${miniPlayerManager.isMiniPlayerMode}, isHomeScreenVisible=$isHomeScreenVisible")
                Log.d(TAG, "States: isChannelProfileVisible=${channelProfileManager.isChannelProfileVisible}")
                Log.d(TAG, "States: returnToProfileSlug=$returnToProfileSlug, currentChannel=${currentChannel?.slug}")
                Log.d(TAG, "States: playerVisible=${binding.playerScreenContainer.visibility == View.VISIBLE}")
                
                when {
                    // 0. Close emote panel first if visible
                    ::binding.isInitialized && binding.emotePanelContainer.visibility == View.VISIBLE -> {
                        Log.d(TAG, "BACK: Branch -> emotePanelVisible -> close emote panel")
                        emotePanelManager.toggleEmotePanel(false)
                        return@handleOnBackPressed
                    }
                    fullscreenToggleManager.isSideChatVisible -> {
                        Log.d(TAG, "BACK: Branch -> isSideChatVisible -> hideSideChat()")
                        fullscreenToggleManager.hideSideChat()
                        // Don't return - fall through to potentially exit fullscreen if user presses back again? 
                        // Actually, user expects one action per press. So we return.
                        return@handleOnBackPressed
                    }
                    fullscreenToggleManager.isTheatreMode -> {
                        Log.d(TAG, "BACK: Branch -> isTheatreMode -> exit theatre mode only")
                        fullscreenToggleManager.exitTheatreMode()
                        return@handleOnBackPressed // Explicitly stop here to prevent any unintended fall-through
                    }
                    isFullscreen -> {
                        Log.d(TAG, "BACK: Branch -> isFullscreen -> exitFullscreen()")
                        exitFullscreen()
                        return@handleOnBackPressed
                    }
                    streamFeedManager.isFeedVisible -> {
                        Log.d(TAG, "BACK: Branch -> isFeedVisible -> closeFeed()")
                        streamFeedManager.closeFeed()
                        return@handleOnBackPressed
                    }
                    clipFeedManager.isFeedVisible -> {
                         Log.d(TAG, "BACK: Branch -> isClipFeedVisible -> closeFeed()")
                         clipFeedManager.closeFeed()
                         return@handleOnBackPressed
                    }
                    isSettingsVisible -> {
                        Log.d(TAG, "BACK: Branch -> isSettingsVisible -> hideSettingsPanel()")
                        hideSettingsPanel()
                        return@handleOnBackPressed
                    }
                    searchUiManager.handleBack() -> {
                        Log.d(TAG, "BACK: Branch -> searchUiManager.handleBack() handled it")
                        return@handleOnBackPressed
                    }
                    channelProfileManager.isChannelProfileVisible && !miniPlayerManager.isMiniPlayerMode && binding.playerScreenContainer.visibility != View.VISIBLE -> {
                        Log.d(TAG, "BACK: Branch -> profileVisible (player not visible) -> closeChannelProfile()")
                        channelProfileManager.closeChannelProfile()
                        return@handleOnBackPressed
                    }
                    browseManager.isCategoryDetailsVisible && !channelProfileManager.isChannelProfileVisible && binding.playerScreenContainer.visibility != View.VISIBLE -> {
                        // Only handle category details if player is NOT visible
                        // If player is visible, let it go to mini mode first
                        Log.d(TAG, "BACK: Branch -> isCategoryDetailsVisible (player not visible) -> closeCategoryDetails()")
                        browseManager.closeCategoryDetails()
                        return@handleOnBackPressed
                    }
                    miniPlayerManager.isMiniPlayerMode -> {
                        Log.d(TAG, "BACK: Branch -> isMiniPlayerMode")
                        // If in Profile with mini player, go back to Home while keeping mini player
                        if (channelProfileManager.isChannelProfileVisible) {
                             Log.d(TAG, "BACK: isMiniPlayerMode + profileVisible -> close profile, show home, keep mini player")
                             // Close profile correctly, respecting previous container (e.g. Search)
                             channelProfileManager.closeChannelProfile()
                        } else if (browseManager.isCategoryDetailsVisible) {
                             Log.d(TAG, "BACK: isMiniPlayerMode + categoryVisible -> close category details")
                             browseManager.closeCategoryDetails()

                        } else if (binding.browseScreenContainer.root.visibility == View.VISIBLE) {
                             Log.d(TAG, "BACK: isMiniPlayerMode + browseVisible -> showHomeScreen")
                             showHomeScreen()
                        } else if (binding.followingScreenContainer.root.visibility == View.VISIBLE) {
                             Log.d(TAG, "BACK: isMiniPlayerMode + followingVisible -> showHomeScreen")
                             showHomeScreen()
                        } else if (binding.searchContainer.visibility == View.VISIBLE) {
                             Log.d(TAG, "BACK: isMiniPlayerMode + searchVisible -> showHomeScreen")
                             showHomeScreen()

                        } else if (binding.homeScreenContainer.root.visibility == View.VISIBLE) {
                             Log.d(TAG, "BACK: isMiniPlayerMode + homeVisible -> exit app")
                             // Already on home screen with mini player - exit app
                             isEnabled = false
                             onBackPressedDispatcher.onBackPressed()
                             isEnabled = true
                        } else {
                             Log.d(TAG, "BACK: isMiniPlayerMode + otherScreen -> showHomeScreen()")
                             // Some other screen with mini player - show home
                             showHomeScreen()
                        }
                        return@handleOnBackPressed
                    }
                    !isHomeScreenVisible -> {
                        Log.d(TAG, "BACK: Branch -> !isHomeScreenVisible")
                        // Priority 1: If Player is visible (Full Screen), close it to Profile
                        if (binding.playerScreenContainer.visibility == View.VISIBLE && !miniPlayerManager.isMiniPlayerMode) {
                              Log.d(TAG, "BACK: playerVisible + !miniPlayer")
                              
                              if (vodManager.restorePreviousState()) {
                                  Log.d(TAG, "BACK: Previous state restored")
                                  return@handleOnBackPressed
                              }
                              
                              Log.d(TAG, "BACK: No previous state -> closePlayerToProfile()")
                              // Ensure we have a slug to return to
                              if (returnToProfileSlug == null && currentChannel != null) {
                                  returnToProfileSlug = currentChannel?.slug
                              }
                              closePlayerToProfile()
                        } 
                        // Priority 2: If Profile is visible, close it to Home
                        else if (channelProfileManager.isChannelProfileVisible) {
                             Log.d(TAG, "BACK: profileVisible -> closeChannelProfile()")
                             channelProfileManager.closeChannelProfile()
                        }
                        // Fallback
                        else {
                             Log.d(TAG, "BACK: fallback -> showHomeScreen()")
                             showHomeScreen()
                        }
                        return@handleOnBackPressed
                    }
                    else -> {
                        Log.d(TAG, "BACK: Branch -> else (Home) -> exit check")
                        if (System.currentTimeMillis() - lastBackPressTime < 2000) {
                            isEnabled = false
                            onBackPressedDispatcher.onBackPressed()
                        } else {
                            lastBackPressTime = System.currentTimeMillis()
                            Toast.makeText(this@MobilePlayerActivity, R.string.press_again_to_exit, Toast.LENGTH_SHORT).show()
                        }
                    }
                }
            }
        })
        
        // Setup browse logic
        browseManager.setupClickListeners()
        
        // Setup profile picture click
        val profilePicView = binding.mobileProfilePic
        profilePicView.setOnClickListener {
            showAccountPopupMenu(it)
        }
        
        // Load user profile pic if logged in
        if (prefs.isLoggedIn) {
            val effectivePic = if (prefs.profilePic.isNullOrEmpty()) {
                val hash = (prefs.username ?: "Guest").hashCode()
                val index = (if (hash < 0) -hash else hash) % 6 + 1
                "https://kick.com/img/default-profile-pictures/default-avatar-$index.webp"
            } else {
                prefs.profilePic
            }

            Glide.with(this)
                .load(effectivePic)
                .circleCrop()
                .placeholder(R.drawable.default_avatar)
                .into(profilePicView)
        }
        
        // Navigation from home headers
        binding.homeScreenContainer.homeFeaturedHeader.setOnClickListener {
            browseManager.showBrowseScreen()
        }
        
        binding.homeScreenContainer.homeCategoriesHeader.setOnClickListener {
            browseManager.showBrowseScreen(1) // 1 is Categories - open directly
        }
        
        binding.homeScreenContainer.homeFollowingHeader.setOnClickListener {
            handleNavigation(R.id.nav_following)
        }
        
        binding.homeScreenContainer.homeSwipeRefresh.setOnRefreshListener {
            loadHomeScreenData()
        }
    }
    
    private fun loadHomeScreenData() = homeScreenManager.loadHomeScreenData()
    
    internal fun openChannel(channel: ChannelItem) {
        Log.d(TAG, "openChannel() called: slug=${channel.slug}, isLive=${channel.isLive}")
        Log.d(TAG, "  before: returnToProfileSlug=$returnToProfileSlug, isChannelProfileVisible=${channelProfileManager.isChannelProfileVisible}")
        
        // Only set returnToProfileSlug if coming from profile screen
        // For other screens (Home, Browse, etc.), we don't want to return to profile
        if (channelProfileManager.isChannelProfileVisible) {
            returnToProfileSlug = channel.slug
            Log.d(TAG, "  set returnToProfileSlug=${channel.slug} (from profile)")
        } else {
            returnToProfileSlug = null
            Log.d(TAG, "  cleared returnToProfileSlug (not from profile)")
        }
        
        if (channel.isLive) {
            Log.d(TAG, "  channel is live -> playChannel()")
            // Channel is live - play it directly
            playChannel(channel)
        } else {
            Log.d(TAG, "  channel is offline -> openChannelProfile()")
            // Channel is offline - just show the profile
            channelProfileManager.openChannelProfile(channel.slug)
        }
    }

    internal fun closePlayerToProfile() {
        Log.d(TAG, "closePlayerToProfile() called")
        Log.d(TAG, "  previousScreenId=$previousScreenId, returnToProfileSlug=$returnToProfileSlug")
        Log.d(TAG, "  currentChannel=${currentChannel?.slug}")
        
        // Enter mini player mode first
        if (!miniPlayerManager.isMiniPlayerMode) {
            Log.d(TAG, "  entering mini player mode")
            enterMiniPlayerMode()
        }
        
        // Priority Check: If Feed is active (paused in background), return to it
        // This ensures correct behavior even if screenBeforePlayer state is stale
        if (::streamFeedManager.isInitialized && streamFeedManager.isFeedActive) {
             Log.d(TAG, "  isFeedActive=true -> returning to StreamFeed")
             streamFeedManager.resumeFeed()
             return
        }
        if (::clipFeedManager.isInitialized && clipFeedManager.isFeedActive) {
             Log.d(TAG, "  isFeedActive=true -> returning to ClipFeed")
             clipFeedManager.resumeFeed()
             return
        }
        
        // Return to the previous screen based on previousScreenId or Manager State
        // Force check Manager state first - it's the ultimate source of truth
        var targetScreenId = previousScreenId
        
        // Use local explicit overrides first
        if (returnToCategoryDetails) {
            targetScreenId = R.id.categoryDetailsContainer
        } else {
            // Use robust state tracking
            when (screenBeforePlayer) {
                AppScreen.CATEGORY_DETAILS -> targetScreenId = R.id.categoryDetailsContainer
                AppScreen.BROWSE -> targetScreenId = R.id.browseScreenContainer
                AppScreen.FOLLOWING -> targetScreenId = R.id.followingScreenContainer
                AppScreen.SEARCH -> targetScreenId = R.id.searchContainer
                AppScreen.CHANNEL_PROFILE -> targetScreenId = R.id.channelProfileContainer
                AppScreen.HOME -> targetScreenId = R.id.homeScreenContainer
                else -> {
                     // Fallback to legacy checks
                     if (::browseManager.isInitialized && browseManager.isCategoryDetailsVisible) {
                        targetScreenId = R.id.categoryDetailsContainer
                     }
                }
            }
        }

        when (targetScreenId) {
            R.id.categoryDetailsContainer -> {
                Log.d(TAG, "  returning to category details")
                
                // Hide other screens
                binding.homeScreenContainer.root.visibility = View.GONE
                binding.followingScreenContainer.root.visibility = View.GONE
                binding.browseScreenContainer.root.visibility = View.GONE
                binding.searchContainer.visibility = View.GONE
                binding.channelProfileContainer.root.visibility = View.GONE
                
                binding.categoryDetailsContainer.root.visibility = View.VISIBLE
                binding.mobileHeader.visibility = View.GONE // Category details has its own header
                
                // Ensure header remains hidden (fix for potential race conditions with mini player animations)
                binding.mobileHeader.post {
                    if (binding.categoryDetailsContainer.root.visibility == View.VISIBLE) {
                        binding.mobileHeader.visibility = View.GONE
                    }
                }
                
                binding.playerScreenContainer.bringToFront()
                
                // Sync State so Dispatcher knows where we are
                if (::browseManager.isInitialized) {
                    browseManager.isCategoryDetailsVisible = true
                }
                setCurrentScreen(AppScreen.CATEGORY_DETAILS)
                
                // Do not reset returnToCategoryDetails here immediately if we want persistence, 
                // but usually one-off return is enough.
                returnToCategoryDetails = false 
            }
            R.id.browseScreenContainer -> {
                Log.d(TAG, "  returning to browse screen")
                binding.mobileHeader.visibility = View.VISIBLE
                binding.browseScreenContainer.root.visibility = View.VISIBLE
                binding.playerScreenContainer.bringToFront()
                setCurrentScreen(AppScreen.BROWSE)
            }
            R.id.followingScreenContainer -> {
                Log.d(TAG, "  returning to following screen")
                
                // Hide other screens first to prevent multiple visible screens
                binding.homeScreenContainer.root.visibility = View.GONE
                binding.browseScreenContainer.root.visibility = View.GONE
                binding.searchContainer.visibility = View.GONE
                binding.channelProfileContainer.root.visibility = View.GONE
                isHomeScreenVisible = false
                
                // Show following screen
                binding.mobileHeader.visibility = View.VISIBLE
                binding.followingScreenContainer.root.visibility = View.VISIBLE
                binding.playerScreenContainer.bringToFront()
                
                setCurrentScreen(AppScreen.FOLLOWING)
            }
            R.id.searchContainer -> {
                Log.d(TAG, "  returning to search")
                binding.searchContainer.visibility = View.VISIBLE
                binding.playerScreenContainer.bringToFront()
                setCurrentScreen(AppScreen.SEARCH)
            }
            R.id.channelProfileContainer -> {
                // Return to channel profile (original behavior)
                val slug = returnToProfileSlug ?: currentChannel?.slug
                Log.d(TAG, "  returning to profile, slug=$slug")
                
                if (slug.isNullOrEmpty()) {
                    Log.d(TAG, "  slug is empty -> showHomeScreen()")
                    showHomeScreen()
                    return
                }
                
                if (channelProfileManager.isChannelProfileVisible) {
                    Log.d(TAG, "  profile already visible -> just show container")
                    binding.channelProfileContainer.root.visibility = View.VISIBLE
                    binding.channelProfileContainer.root.bringToFront()
                    binding.playerScreenContainer.bringToFront()
                } else {
                    Log.d(TAG, "  profile not visible -> openChannelProfile($slug)")
                    channelProfileManager.openChannelProfile(slug, isRestore = true)
                }
                returnToProfileSlug = slug
            }
            else -> {
                Log.d(TAG, "  returning to home screen (default)")
                
                // Hide other screens first to prevent multiple visible screens
                binding.followingScreenContainer.root.visibility = View.GONE
                binding.browseScreenContainer.root.visibility = View.GONE
                binding.searchContainer.visibility = View.GONE
                binding.channelProfileContainer.root.visibility = View.GONE
                
                // Show home screen
                binding.mobileHeader.visibility = View.VISIBLE
                binding.homeScreenContainer.root.visibility = View.VISIBLE
                isHomeScreenVisible = true
                binding.playerScreenContainer.bringToFront()
                
                setCurrentScreen(AppScreen.HOME)
            }
        }
    }
    
    internal var isNavigationProgrammatic = false
    internal var returnToCategoryDetails = false

    internal fun showHomeScreen() {
        returnToCategoryDetails = false // Reset state
        setCurrentScreen(AppScreen.HOME)
        
        // Only pause player if NOT in mini-player mode
        if (!miniPlayerManager.isMiniPlayerMode) {
            ivsPlayer?.pause()
        }
        
        // Show home screen, hide others
        binding.mobileHeader.visibility = View.VISIBLE
        binding.homeScreenContainer.root.visibility = View.VISIBLE
        binding.browseScreenContainer.root.visibility = View.GONE
        binding.followingScreenContainer.root.visibility = View.GONE
        binding.searchContainer.visibility = View.GONE
        binding.channelProfileContainer.root.visibility = View.GONE
        
        // Reset profile state to keep it in sync with visibility
        if (::channelProfileManager.isInitialized) {
            channelProfileManager.isChannelProfileVisible = false
        }
        
        if (!miniPlayerManager.isMiniPlayerMode) {
            binding.playerScreenContainer.visibility = View.GONE
            updateNavigationBarColor(false) // Set navigation bar to transparent for home screen
        }
        isHomeScreenVisible = true
        
        // Sync Nav
        if (binding.mainBottomNavigation.selectedItemId != R.id.nav_home) {
            isNavigationProgrammatic = true
            binding.mainBottomNavigation.selectedItemId = R.id.nav_home
            isNavigationProgrammatic = false
        }
        
        // Only load data if not already loaded (pull-to-refresh still works)
        if (!homeDataLoaded) {
            loadHomeScreenData()
        }
    }
    
    private fun Int.dpToPx(): Int {
        return (this * resources.displayMetrics.density).toInt()
    }
    
    /**
     * Updates the system navigation bar color based on current screen.
     * @param isPlayerScreen true when player screen is visible, false for home/browse screens
     */
    internal fun updateNavigationBarColor(isPlayerScreen: Boolean) {
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
            window.isNavigationBarContrastEnforced = false
        }

        if (isPlayerScreen) {
            // Check theatre mode state
            // If theatre mode, use Transparent to allow "reduced margin" visual (background is black)
            // If standard player, use #0f0f0f
            val isTheatre = try { fullscreenToggleManager.isTheatreMode } catch (e: Exception) { false }
            
            if (isTheatre) {
                window.navigationBarColor = Color.BLACK
            } else {
                window.navigationBarColor = Color.parseColor("#1a1a1a")
            }
        } else {
            // Home, Browse, MiniPlayer -> Transparent
            window.navigationBarColor = Color.TRANSPARENT
        }
    }
    
    internal fun handleNavigation(itemId: Int): Boolean {
        // Reset orientation on tab switch for tablets to ensure we don't get stuck in Landscape (respects system lock)
        setAllowedOrientation()
        
        return when (itemId) {
            R.id.nav_home -> {
                showHomeScreen()
                true
            }
            R.id.nav_browse -> {
                browseManager.showBrowseScreen()
                true
            }
            R.id.nav_following -> {
                followingManager.showFollowingScreen()
                true
            }
            R.id.nav_search -> {
                searchUiManager.showSearchScreen()
                true
            }
            else -> false
        }
    }

    fun enterPiPNow() {
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            try {
                if (ivsPlayer?.state != com.amazonaws.ivs.player.Player.State.PLAYING) return
                
                val width = ivsPlayer?.quality?.width ?: 0
                val height = ivsPlayer?.quality?.height ?: 0
                
                val aspectRatio = if (width > 0 && height > 0) {
                     android.util.Rational(width, height)
                } else {
                     android.util.Rational(16, 9)
                }
                
                val builder = android.app.PictureInPictureParams.Builder()
                    .setAspectRatio(aspectRatio)
                
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
                    builder.setAutoEnterEnabled(true)
                }
                    
                enterPictureInPictureMode(builder.build())
            } catch (e: Exception) {
                Log.e(TAG, "Error entering PiP", e)
            }
        }
    }
    
    private fun toggleFullscreen() = fullscreenToggleManager.toggleFullscreen()

    private fun updateFullscreenButtonState() = fullscreenToggleManager.updateFullscreenButtonState()

    internal fun stopPlayer() = playerControlsManager.stopPlayer()
    
    private fun closeChatWebSocket() {
        chatConnectionManager.disconnect()
    }

    @SuppressLint("InflateParams")
    internal fun showPlayerStatsSheet() = playerStatsSheetManager.showPlayerStatsSheet()
    internal fun updateChatOverlayMargin() {
        binding.root.post {
            try {
                val density = resources.displayMetrics.density
                
                val giftsHeight = if (binding.pinnedGiftsBlur.visibility == View.VISIBLE) {
                    binding.pinnedGiftsBlur.height.takeIf { it > 0 } ?: 0
                } else 0
                
                // NOTE: infoPanelHeight + actionBarHeight are NOT included here because
                // chatContainer.paddingTop already pushes all children (including overlay) down.
                val params = binding.chatOverlayContainer.layoutParams as? ViewGroup.MarginLayoutParams
                if (params != null) {
                    val newMargin = giftsHeight + (8 * density).toInt()
                    if (params.topMargin != newMargin) {
                        params.topMargin = newMargin
                        binding.chatOverlayContainer.layoutParams = params
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error updating chat overlay margin", e)
            }
        }
    }

    private fun updateNavVisualsById(itemId: Int) {
        val index = when(itemId) {
            dev.xacnio.kciktv.R.id.nav_home -> 0
            dev.xacnio.kciktv.R.id.nav_browse -> 1
            dev.xacnio.kciktv.R.id.nav_following -> 2
            dev.xacnio.kciktv.R.id.nav_search -> 3
            else -> 0
        }
        updateNavVisuals(index)
    }

    private fun updateNavVisuals(selectedIndex: Int) {
        try {
            if (!::binding.isInitialized) return
            
            val indicators = listOf(binding.indicatorNavHome, binding.indicatorNavBrowse, binding.indicatorNavFollowing, binding.indicatorNavSearch)
            val icons = listOf(binding.iconNavHome, binding.iconNavBrowse, binding.iconNavFollowing, binding.iconNavSearch)
            val texts = listOf(binding.textNavHome, binding.textNavBrowse, binding.textNavFollowing, binding.textNavSearch)

            for (i in indicators.indices) {
                val isSelected = (i == selectedIndex)
                
                if (isSelected) {
                     // indicators[i].setBackgroundResource(dev.xacnio.kciktv.R.drawable.bg_nav_indicator_custom)
                     indicators[i].background = null // Ensure no background
                     val color = android.graphics.Color.parseColor("#53FC18")
                     icons[i].setColorFilter(color)
                     texts[i].setTextColor(color)
                } else {
                     indicators[i].background = null
                     val color = android.graphics.Color.parseColor("#808080")
                     icons[i].setColorFilter(color)
                     texts[i].setTextColor(color)
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    /**
     * Sets up the window insets listener for the chat container.
     * This ensures the chat input moves up when the keyboard is shown.
     * Made public to allow FullscreenToggleManager to restore it after theatre mode changes.
     */
    fun setupChatContainerInsetsListener() {
        if (!::binding.isInitialized) return
        
        androidx.core.view.ViewCompat.setOnApplyWindowInsetsListener(binding.chatContainer) { view, windowInsets ->
            val imeInsets = windowInsets.getInsets(androidx.core.view.WindowInsetsCompat.Type.ime())
            // Only apply padding when keyboard is visible using IME insets 
            // In standard mode, playerScreenContainer handles nav bar padding via systemBars()
            val bottomPadding = if (imeInsets.bottom > 0) imeInsets.bottom else 0
            
            // Apply padding
            view.setPadding(view.paddingLeft, view.paddingTop, view.paddingRight, bottomPadding)
            
            // Return insets to allow propagation if needed
            windowInsets
        }
    }
}
