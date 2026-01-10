package dev.xacnio.kciktv

import android.animation.ValueAnimator
import android.util.Log
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.InsetDrawable
import android.media.AudioManager
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.animation.AccelerateDecelerateInterpolator
import android.view.animation.DecelerateInterpolator
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import android.widget.CheckBox
import android.widget.LinearLayout
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.amazonaws.ivs.player.Cue
import com.amazonaws.ivs.player.Player
import com.amazonaws.ivs.player.PlayerException
import com.amazonaws.ivs.player.Quality
import com.bumptech.glide.Glide
import com.bumptech.glide.request.RequestOptions
import com.bumptech.glide.request.target.CustomTarget
import com.bumptech.glide.request.transition.Transition
import jp.wasabeef.glide.transformations.BlurTransformation
import dev.xacnio.kciktv.util.CategoryUtils
import android.graphics.Bitmap
import android.graphics.drawable.Drawable
import dev.xacnio.kciktv.data.chat.KcikChatWebSocket
import dev.xacnio.kciktv.data.model.ChannelItem
import dev.xacnio.kciktv.data.model.ChatMessage
import dev.xacnio.kciktv.data.model.LivestreamResponse
import dev.xacnio.kciktv.data.model.LiveStreamsResponse
import dev.xacnio.kciktv.data.prefs.AppPreferences
import dev.xacnio.kciktv.data.repository.AuthRepository
import dev.xacnio.kciktv.data.repository.ChannelRepository
import dev.xacnio.kciktv.data.repository.UpdateRepository
import dev.xacnio.kciktv.data.repository.LoginResult
import dev.xacnio.kciktv.data.model.GithubRelease
import dev.xacnio.kciktv.databinding.ActivityPlayerBinding
import androidx.media3.common.MediaItem
import androidx.media3.common.Player as ExoPlayerBase
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.common.VideoSize
import androidx.media3.exoplayer.hls.HlsMediaSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.common.util.UnstableApi
import androidx.annotation.OptIn
import androidx.media3.exoplayer.DefaultLoadControl

import android.content.Intent
import androidx.core.content.FileProvider
import java.io.File
import java.io.FileOutputStream
import okhttp3.OkHttpClient
import okhttp3.Request
import kotlinx.coroutines.withContext
import dev.xacnio.kciktv.ui.adapter.ChannelSidebarAdapter
import dev.xacnio.kciktv.ui.adapter.ChatAdapter
import dev.xacnio.kciktv.ui.adapter.GenericSelectionAdapter
import dev.xacnio.kciktv.ui.adapter.SelectionItem
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import dev.xacnio.kciktv.data.server.LocalLoginServer
import dev.xacnio.kciktv.util.QRUtils
import java.net.NetworkInterface
import java.net.InetAddress
import java.util.Collections
import java.nio.ByteBuffer
import java.text.SimpleDateFormat
import java.util.TimeZone
import java.util.Locale
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import kotlin.math.abs
import android.app.PendingIntent
import android.app.RemoteAction
import android.app.PictureInPictureParams
import android.util.Rational
import android.content.res.Configuration
import android.content.BroadcastReceiver
import android.content.Context
import android.content.IntentFilter
import android.graphics.drawable.Icon
import android.app.Service
import android.os.IBinder
import android.os.PowerManager
import android.support.v4.media.session.MediaSessionCompat
import android.support.v4.media.session.PlaybackStateCompat
import android.support.v4.media.MediaMetadataCompat
import androidx.media.session.MediaButtonReceiver
import android.app.NotificationChannel
import android.app.NotificationManager
import androidx.core.app.NotificationCompat
import androidx.media.app.NotificationCompat as MediaNotificationCompat
import android.app.Notification

class PlayerActivity : FragmentActivity() {
    
    private var lastSettingsFocusId: String? = null
    private var lastSubSettingsFocusId: String? = null
    private var originalThemeColor: Int = 0

    private lateinit var binding: ActivityPlayerBinding
    private lateinit var prefs: AppPreferences
    private var ivsPlayer: Player? = null
    private var exoPlayer: ExoPlayer? = null
    private var mediaSession: MediaSessionCompat? = null
    
    private var streamCreatedAtMillis: Long? = null
    private var serverClockOffset: Long = 0L // Offset to sync local clock with server
    private val uptimeHandler = Handler(Looper.getMainLooper())
    private val uptimeRunnable = object : Runnable {
        override fun run() {
            updateUptimeDisplay()
            uptimeHandler.postDelayed(this, 1000L)
        }
    }
    private val repository = ChannelRepository()
    private val authRepository = AuthRepository()
    private val updateRepository = UpdateRepository()
    private var loginServer: LocalLoginServer? = null
    private var loadingJob: Job? = null
    
    private var allChannels = mutableListOf<ChannelItem>()
    private var currentChannelIndex = 0
    private var nextCursor: String? = null
    private var isChatVisible = false
    private var isStatsVisible = false
    
    private val hideHandler = Handler(Looper.getMainLooper())
    private val hideRunnable = Runnable { hideAllOverlays() }
    private val statsHandler = Handler(Looper.getMainLooper())
    
    private enum class MenuState { NONE, INFO, QUICK_MENU, SIDEBAR, DRAWER, LOGIN, QUALITY, SEARCH, MATURE_WARNING }
    private var currentState = MenuState.NONE
    private var isChannelMenu = false
    
    private enum class ListMode { GLOBAL, FOLLOWING, SEARCH }
    private var currentListMode = ListMode.GLOBAL
    
    private var sidebarContext: String = ""
    private var channelSidebarAdapter: ChannelSidebarAdapter? = null
    private var isMatureAcceptedForSession = false
    private var tagScrollAnimator: ValueAnimator? = null
    private var currentProfileBitmap: Bitmap? = null

    private val refreshHandler = Handler(Looper.getMainLooper())
    
    // Channel number input
    private var channelInputBuffer = StringBuilder()
    private val channelInputHandler = Handler(Looper.getMainLooper())
    private val channelInputRunnable = Runnable { processChannelInput() }
    
    // Volume overlay
    private lateinit var audioManager: AudioManager
    private val volumeHandler = Handler(Looper.getMainLooper())
    private val hideVolumeRunnable = Runnable { hideVolumeOverlay() }
    
    // Retry mechanism
    private var retryCount = 0
    private val maxRetryCount = 10
    private val retryDelayMs = 3000L
    private val retryHandler = Handler(Looper.getMainLooper())
    
    // Stability Watchdog
    private val stabilityHandler = Handler(Looper.getMainLooper())
    private var zeroBitrateCount = 0
    private val stabilityRunnable = object : Runnable {
        override fun run() {
            val mode = prefs.catchUpMode
            val engine = prefs.playerEngine
            
            if (engine == "exo") {
                val player = exoPlayer
                if (player != null && player.playbackState == androidx.media3.common.Player.STATE_READY && player.playWhenReady) {
                    // Bitrate Watchdog for Exo
                    val bitRate = player.videoFormat?.bitrate ?: 1
                    if (bitRate <= 0) {
                        zeroBitrateCount++
                        if (zeroBitrateCount >= 10) {
                            zeroBitrateCount = 0
                            scheduleRetry()
                            return
                        }
                    } else {
                        zeroBitrateCount = 0
                    }
                    stabilityHandler.postDelayed(this, 1000L)
                }
            } else {
                val player = ivsPlayer
                if (player != null && player.state == Player.State.PLAYING) {
                    // IVS Catch-up
                    if (mode != "off") {
                        val latency = player.liveLatency
                        val startThreshold = if (mode == "high") 2500L else 5000L
                        val stopThreshold = if (mode == "high") 1500L else 2500L

                        if (latency > startThreshold) {
                            if (player.playbackRate != 1.1f) {
                                Log.d("PlayerActivity", "IVS Catch-up ($mode): High Latency ($latency ms). Speeding up.")
                                player.playbackRate = 1.1f
                            }
                        } else if (latency < stopThreshold && latency > 0) {
                            if (player.playbackRate != 1.0f) {
                                Log.d("PlayerActivity", "IVS Catch-up ($mode): Latency OK ($latency ms). Normal speed.")
                                player.playbackRate = 1.0f
                            }
                        }
                    } else if (player.playbackRate != 1.0f) {
                        player.playbackRate = 1.0f
                    }

                    val statsStr = player.statistics.toString()
                    val bitrate = Regex("bitRate=([\\d.]+)").find(statsStr)?.groupValues?.get(1)?.toLongOrNull() ?: 0L
                    if (bitrate <= 0) {
                        zeroBitrateCount++
                        if (zeroBitrateCount >= 10) {
                            Log.w("PlayerActivity", "IVS Stability watchdog triggered: 0 bitrate for 10s. Retrying...")
                            zeroBitrateCount = 0
                            scheduleRetry()
                            return
                        }
                    } else {
                        zeroBitrateCount = 0
                    }
                    stabilityHandler.postDelayed(this, 1000L)
                }
            }
        }
    }
    private val zapHandler = Handler(Looper.getMainLooper())
    private val zapDelayMs: Long get() = prefs.zapDelay.toLong()
    
    private var backPressedTime = 0L
    
    // Tier 1: Active Channel Refresh (Every 30 seconds - Hardcoded for "more frequent")
    private val activeChannelRefreshRunnable = object : Runnable {
        override fun run() {
            refreshActiveChannelDetails()
            refreshHandler.postDelayed(this, 30000L) // 30 seconds
        }
    }
    
    // Tier 2: Global List Refresh (User defined interval)
    private val globalListRefreshRunnable = object : Runnable {
        override fun run() {
            refreshGlobalList()
            if (prefs.autoRefreshInterval != -1) {
                val interval = prefs.autoRefreshInterval * 60000L
                refreshHandler.postDelayed(this, interval)
            }
        }
    }
    
    // Chat
    private var chatAdapter: ChatAdapter? = null
    private var chatWebSocket: KcikChatWebSocket? = null
    private val chatHandler = Handler(Looper.getMainLooper())
    private val pendingChatMessages = mutableListOf<ChatMessage>()
    private var chatConnectJob: Job? = null
    private var lastSubscribedSlug: String? = null
    
    // Quick Recovery Polling (For short disconnects)
    private val recoveryHandler = Handler(Looper.getMainLooper())
    private var recoveryAttempts = 0
    private val maxRecoveryAttempts = 12 // 12 * 5s = 60s
    private val recoveryRunnable: Runnable = object : Runnable {
        override fun run() {
            val self = this
            if (recoveryAttempts >= maxRecoveryAttempts) {
                Log.d("PlayerActivity", "Recovery polling timed out after 1 minute.")
                stopRecoveryPolling()
                return
            }
            
            recoveryAttempts++
            if (allChannels.isEmpty()) return
            val channel = allChannels[currentChannelIndex]
            
            Log.d("PlayerActivity", "Recovery attempt $recoveryAttempts/12 for: ${channel.slug}")
            
            lifecycleScope.launch {
                repository.getChannelDetails(channel.slug).onSuccess { details ->
                    if (details.livestream != null) {
                        // Stream is BACK!
                        Log.d("PlayerActivity", "Recovery successful! Stream is back online.")
                        stopRecoveryPolling()
                        chatHandler.post {
                            // Update local state and play
                            val updatedChannel = channel.copy(isLive = true)
                            allChannels[currentChannelIndex] = updatedChannel
                            playCurrentChannel(useZapDelay = false, showInfo = true, keepBanner = true)
                        }
                    } else {
                        // Still offline, check if we should keep polling
                        // The user said: "If livestream is null, stop immediately"
                        // But wait, if it's brief disconnect, it might be null for 2 seconds.
                        // Actually, 'livestream' being null in API usually means it's officially over.
                        // I'll follow user's strict rule: stop immediately if null.
                        Log.d("PlayerActivity", "Recovery: Channel is officially offline (livestream null). Stopping polling.")
                        stopRecoveryPolling()
                    }
                }.onFailure {
                    // Network error or something, keep trying
                    recoveryHandler.postDelayed(self, 5000L)
                }
            }
        }
    }
    
    // Touch Gesture Support
    private lateinit var gestureDetector: GestureDetector
    private lateinit var scaleGestureDetector: ScaleGestureDetector
    private var isFitToScreen = false // false = 16:9 (FIT), true = Fill screen (FILL)
    
    // Video Pan Support (for FILL mode)
    private var videoPanX = 0f
    private var videoPanY = 0f
    private var isPanning = false
    private var lastTouchX = 0f
    private var lastTouchY = 0f
    
    // PIP & Background Support
    private var isBackgroundAudioEnabled = false
    private val PIP_CONTROL_ACTION = "dev.xacnio.kciktv.PIP_CONTROL"
    private val EXTRA_CONTROL_TYPE = "type"
    private val CONTROL_TYPE_PLAY_PAUSE = 1
    private val CONTROL_TYPE_LIVE = 2
    private val CONTROL_TYPE_AUDIO_ONLY = 3
    private val CONTROL_TYPE_NEXT = 4
    private val CONTROL_TYPE_PREVIOUS = 5
    
    private val CHANNEL_ID = "kciktv_playback"
    private val NOTIFICATION_ID = 42
    
    private val pipReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent == null || intent.action != PIP_CONTROL_ACTION) return
            
            when (intent.getIntExtra(EXTRA_CONTROL_TYPE, 0)) {
                CONTROL_TYPE_PLAY_PAUSE -> {
                    if (prefs.playerEngine == "exo") {
                        if (exoPlayer?.isPlaying == true) exoPlayer?.pause() else exoPlayer?.play()
                    } else {
                        if (ivsPlayer?.state == com.amazonaws.ivs.player.Player.State.PLAYING) {
                            ivsPlayer?.pause()
                        } else {
                            ivsPlayer?.seekTo(ivsPlayer?.duration ?: 0L)
                            ivsPlayer?.play()
                        }
                    }
                    updatePictureInPictureParams()
                    updateMediaSessionState()
                }
                CONTROL_TYPE_LIVE -> {
                    if (prefs.playerEngine == "exo") {
                        exoPlayer?.seekToDefaultPosition()
                        exoPlayer?.play()
                    } else {
                        ivsPlayer?.seekTo(ivsPlayer?.duration ?: 0L)
                        ivsPlayer?.play()
                    }
                    updateMediaSessionState()
                }
                CONTROL_TYPE_AUDIO_ONLY -> {
                    isBackgroundAudioEnabled = true
                    setPowerSavingPlayback(true)
                    // Move to back to exit PIP and hide app
                    moveTaskToBack(true)
                    Toast.makeText(this@PlayerActivity, "Arka Plan Ses Modu Aktif", Toast.LENGTH_SHORT).show()
                    updateMediaSessionState()
                }
                CONTROL_TYPE_NEXT -> {
                    nextChannel()
                    updateMediaSessionState()
                }
                CONTROL_TYPE_PREVIOUS -> {
                    previousChannel()
                    updateMediaSessionState()
                }
            }
        }
    }

    private val screenStateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == Intent.ACTION_SCREEN_OFF) {
                // Screen turned off - service is already running, just ensure power saving mode
                if (ivsPlayer?.state == com.amazonaws.ivs.player.Player.State.PLAYING) {
                    Log.d("PlayerActivity", "Screen turned off - ensuring power saving mode")
                    setPowerSavingPlayback(true)
                }
            }
        }
    }

    private val stopPlaybackReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == PlaybackService.ACTION_STOP_PLAYBACK) {
                Log.d("PlayerActivity", "Received stop playback broadcast - stopping player")
                ivsPlayer?.pause()
                isBackgroundAudioEnabled = false
            }
        }
    }

    private val powerSavingHandler = Handler(Looper.getMainLooper())
    private var powerSavingRunnable: Runnable? = null

    private fun setPowerSavingPlayback(enabled: Boolean) {
        // Cancel any pending power saving change
        powerSavingRunnable?.let { powerSavingHandler.removeCallbacks(it) }
        
        // Don't change quality - this causes audio interruption
        // Just log the state change. Audio will continue at current quality.
        // This uses more data but provides seamless background playback.
        if (enabled) {
            Log.d("PlayerActivity", "Background mode: Keeping current quality for seamless audio")
        } else {
            Log.d("PlayerActivity", "Foreground mode: Resuming normal playback")
        }
    }

    private fun setupMediaSession() {
        mediaSession = MediaSessionCompat(this, "KcikTV").apply {
            setCallback(object : MediaSessionCompat.Callback() {
                override fun onPlay() {
                    if (prefs.playerEngine == "exo") exoPlayer?.play() else ivsPlayer?.play()
                    updateMediaSessionState()
                    updatePictureInPictureParams()
                }
                override fun onPause() {
                    if (prefs.playerEngine == "exo") exoPlayer?.pause() else ivsPlayer?.pause()
                    updateMediaSessionState()
                    updatePictureInPictureParams()
                }
                override fun onSkipToNext() {
                    nextChannel()
                }
                override fun onSkipToPrevious() {
                    previousChannel()
                }
            })
            isActive = true
        }
    }

    private fun updateMediaSessionState() {
        val isPlaying = if (prefs.playerEngine == "exo") {
            exoPlayer?.isPlaying ?: false
        } else {
            ivsPlayer?.state == com.amazonaws.ivs.player.Player.State.PLAYING
        }
        val state = if (isPlaying) PlaybackStateCompat.STATE_PLAYING else PlaybackStateCompat.STATE_PAUSED
        
        // Update Metadata (Channel & Title)
        if (allChannels.isNotEmpty() && currentChannelIndex < allChannels.size) {
            val channel = allChannels[currentChannelIndex]
            val metadataBuilder = MediaMetadataCompat.Builder()
                .putString(MediaMetadataCompat.METADATA_KEY_TITLE, channel.username)
                .putString(MediaMetadataCompat.METADATA_KEY_ARTIST, channel.title)
            
            currentProfileBitmap?.let {
                metadataBuilder.putBitmap(MediaMetadataCompat.METADATA_KEY_ALBUM_ART, it)
                metadataBuilder.putBitmap(MediaMetadataCompat.METADATA_KEY_DISPLAY_ICON, it)
            }
            
            mediaSession?.setMetadata(metadataBuilder.build())
        }

        val stateBuilder = PlaybackStateCompat.Builder()
            .setActions(
                PlaybackStateCompat.ACTION_PLAY or
                PlaybackStateCompat.ACTION_PAUSE or
                PlaybackStateCompat.ACTION_STOP or
                PlaybackStateCompat.ACTION_PLAY_PAUSE or
                PlaybackStateCompat.ACTION_SEEK_TO or
                PlaybackStateCompat.ACTION_SKIP_TO_NEXT or
                PlaybackStateCompat.ACTION_SKIP_TO_PREVIOUS
            )
            .setState(state, PlaybackStateCompat.PLAYBACK_POSITION_UNKNOWN, 1.0f)
        
        mediaSession?.setPlaybackState(stateBuilder.build())
        
        // Push notification if in Background or PIP
        if (isBackgroundAudioEnabled || isInPictureInPictureMode) {
            showNotification()
        } else {
            // Dismiss notification when in foreground
            val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            notificationManager.cancel(NOTIFICATION_ID)
            // Also stop the service
            stopService(Intent(this, PlaybackService::class.java))
        }
    }

    private fun showNotification() {
        val isPlaying = ivsPlayer?.state == com.amazonaws.ivs.player.Player.State.PLAYING
        val channel = if (allChannels.isNotEmpty() && currentChannelIndex < allChannels.size) allChannels[currentChannelIndex] else null
        
        val notificationIntent = Intent(this, PlayerActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        val pendingIntent = PendingIntent.getActivity(this, 0, notificationIntent, PendingIntent.FLAG_IMMUTABLE)

        // Previous channel action
        val previousAction = NotificationCompat.Action(android.R.drawable.ic_media_previous, "Önceki",
            PendingIntent.getBroadcast(this, CONTROL_TYPE_PREVIOUS,
            Intent(PIP_CONTROL_ACTION).apply { setPackage(packageName); putExtra(EXTRA_CONTROL_TYPE, CONTROL_TYPE_PREVIOUS) },
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT))

        val playPauseAction = if (isPlaying) {
            NotificationCompat.Action(android.R.drawable.ic_media_pause, "Duraklat", 
                PendingIntent.getBroadcast(this, CONTROL_TYPE_PLAY_PAUSE, 
                Intent(PIP_CONTROL_ACTION).apply { setPackage(packageName); putExtra(EXTRA_CONTROL_TYPE, CONTROL_TYPE_PLAY_PAUSE) }, 
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT))
        } else {
            NotificationCompat.Action(android.R.drawable.ic_media_play, "Oynat", 
                PendingIntent.getBroadcast(this, CONTROL_TYPE_PLAY_PAUSE, 
                Intent(PIP_CONTROL_ACTION).apply { setPackage(packageName); putExtra(EXTRA_CONTROL_TYPE, CONTROL_TYPE_PLAY_PAUSE) }, 
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT))
        }

        // Next channel action
        val nextAction = NotificationCompat.Action(android.R.drawable.ic_media_next, "Sonraki",
            PendingIntent.getBroadcast(this, CONTROL_TYPE_NEXT,
            Intent(PIP_CONTROL_ACTION).apply { setPackage(packageName); putExtra(EXTRA_CONTROL_TYPE, CONTROL_TYPE_NEXT) },
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT))

        // Delete intent - when notification is dismissed, stop playback
        val deleteIntent = Intent(this, PlaybackService::class.java).apply {
            action = "STOP"
        }
        val deletePendingIntent = PendingIntent.getService(this, 100, deleteIntent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)

        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle(channel?.username ?: "KcikTV")
            .setContentText(channel?.title ?: "Canlı Yayın")
            .setContentIntent(pendingIntent)
            .setDeleteIntent(deletePendingIntent)
            .setOngoing(isPlaying)
            .setPriority(NotificationCompat.PRIORITY_LOW) // Required for background media
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setLargeIcon(currentProfileBitmap)
            .addAction(previousAction)
            .addAction(playPauseAction)
            .addAction(nextAction)
            .setStyle(MediaNotificationCompat.MediaStyle()
                .setMediaSession(mediaSession?.sessionToken)
                .setShowActionsInCompactView(0, 1, 2)) // Show all 3 buttons in compact view
            .build()

        val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        notificationManager.notify(NOTIFICATION_ID, notification)
        
        // Start Foreground Service to prevent background kill
        if (isBackgroundAudioEnabled && isPlaying) {
            val serviceIntent = Intent(this, PlaybackService::class.java).apply {
                putExtra("notification", notification)
                putExtra("notificationId", NOTIFICATION_ID)
            }
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                startForegroundService(serviceIntent)
            } else {
                startService(serviceIntent)
            }
        }
    }

    private fun createNotificationChannel() {
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            val name = "KcikTV Playback"
            val descriptionText = "Media controls for background playback"
            val importance = NotificationManager.IMPORTANCE_LOW
            val channel = NotificationChannel(CHANNEL_ID, name, importance).apply {
                description = descriptionText
                setShowBadge(false)
            }
            val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            notificationManager.createNotificationChannel(channel)
        }
    }

    /**
     * Start foreground service immediately when playback begins.
     * This ensures seamless audio when screen is locked (no interruption).
     * The notification will show, but this is necessary for uninterrupted background playback.
     */
    private fun startBackgroundPlayback() {
        val isPlaying = ivsPlayer?.state == com.amazonaws.ivs.player.Player.State.PLAYING
        if (!isPlaying) return
        
        val channel = if (allChannels.isNotEmpty() && currentChannelIndex < allChannels.size) allChannels[currentChannelIndex] else null
        
        val notificationIntent = Intent(this, PlayerActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        val pendingIntent = PendingIntent.getActivity(this, 0, notificationIntent, PendingIntent.FLAG_IMMUTABLE)

        // Previous channel action
        val previousAction = NotificationCompat.Action(android.R.drawable.ic_media_previous, "Önceki",
            PendingIntent.getBroadcast(this, CONTROL_TYPE_PREVIOUS,
            Intent(PIP_CONTROL_ACTION).apply { setPackage(packageName); putExtra(EXTRA_CONTROL_TYPE, CONTROL_TYPE_PREVIOUS) },
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT))

        val playPauseAction = NotificationCompat.Action(android.R.drawable.ic_media_pause, "Duraklat", 
            PendingIntent.getBroadcast(this, CONTROL_TYPE_PLAY_PAUSE, 
            Intent(PIP_CONTROL_ACTION).apply { setPackage(packageName); putExtra(EXTRA_CONTROL_TYPE, CONTROL_TYPE_PLAY_PAUSE) }, 
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT))

        // Next channel action
        val nextAction = NotificationCompat.Action(android.R.drawable.ic_media_next, "Sonraki",
            PendingIntent.getBroadcast(this, CONTROL_TYPE_NEXT,
            Intent(PIP_CONTROL_ACTION).apply { setPackage(packageName); putExtra(EXTRA_CONTROL_TYPE, CONTROL_TYPE_NEXT) },
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT))

        // Delete intent - when notification is dismissed, stop playback
        val deleteIntent = Intent(this, PlaybackService::class.java).apply {
            action = "STOP"
        }
        val deletePendingIntent = PendingIntent.getService(this, 100, deleteIntent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)

        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle(channel?.username ?: "KcikTV")
            .setContentText(channel?.title ?: "Canlı Yayın")
            .setContentIntent(pendingIntent)
            .setDeleteIntent(deletePendingIntent)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setLargeIcon(currentProfileBitmap)
            .addAction(previousAction)
            .addAction(playPauseAction)
            .addAction(nextAction)
            .setStyle(MediaNotificationCompat.MediaStyle()
                .setMediaSession(mediaSession?.sessionToken)
                .setShowActionsInCompactView(0, 1, 2))
            .build()

        // Start Foreground Service immediately
        val serviceIntent = Intent(this, PlaybackService::class.java).apply {
            putExtra("notification", notification)
            putExtra("notificationId", NOTIFICATION_ID)
        }
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            startForegroundService(serviceIntent)
        } else {
            startService(serviceIntent)
        }
        
        Log.d("PlayerActivity", "Background playback service started immediately")
    }
    
    override fun onCreate(savedInstanceState: Bundle?) {
        prefs = AppPreferences(this)
        applyLocale()
        super.onCreate(savedInstanceState)
        binding = ActivityPlayerBinding.inflate(layoutInflater)
        setContentView(binding.root)
        
        // Enable immersive fullscreen mode
        enableImmersiveMode()
        
        // Restore last list mode
        try {
            currentListMode = ListMode.valueOf(prefs.lastListMode ?: "GLOBAL")
            // If Following but not logged in, fallback to Global
            if (currentListMode == ListMode.FOLLOWING && !prefs.isLoggedIn) {
                currentListMode = ListMode.GLOBAL
            }
        } catch (e: Exception) {
            currentListMode = ListMode.GLOBAL
        }

        // Register PIP receiver early
        val filter = IntentFilter(PIP_CONTROL_ACTION)
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(pipReceiver, filter, Context.RECEIVER_EXPORTED) // Exported for system broadcast
        } else {
            registerReceiver(pipReceiver, filter)
        }
        
        // Register Screen State Receiver
        registerReceiver(screenStateReceiver, IntentFilter(Intent.ACTION_SCREEN_OFF))
        
        // Register Stop Playback Receiver (when notification is dismissed)
        val stopFilter = IntentFilter(PlaybackService.ACTION_STOP_PLAYBACK)
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(stopPlaybackReceiver, stopFilter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(stopPlaybackReceiver, stopFilter)
        }

        createNotificationChannel()
        setupMediaSession()
        audioManager = getSystemService(AUDIO_SERVICE) as AudioManager
        
        setupGestureDetectors()
        setupQuickMenu()
        setupRecyclerView()
        setupChat()
        if (prefs.playerEngine == "exo") {
            binding.playerView.visibility = View.GONE
            binding.exoPlayerView.visibility = View.VISIBLE
            initializeExoPlayer()
        } else {
            binding.exoPlayerView.visibility = View.GONE
            binding.playerView.visibility = View.VISIBLE
            initializeIVSPlayer()
        }
        applySettings() // Call after setup
        setupDrawer()
        setupSearch()
        setupMatureWarning()
        updateLanguageFilterButtonText()
        
        // Setup startup loading screen version info
        try {
            val versionName = packageManager.getPackageInfo(packageName, 0).versionName
            binding.startupVersionText.text = getString(R.string.app_version_label, versionName)
        } catch (e: Exception) {
            binding.startupVersionText.text = getString(R.string.app_name)
        }
        
        loadInitialChannels()
        
        checkForUpdates()
    }

    private fun enableImmersiveMode() {
        @Suppress("DEPRECATION")
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
            window.setDecorFitsSystemWindows(false)
            window.insetsController?.let {
                it.hide(android.view.WindowInsets.Type.statusBars() or android.view.WindowInsets.Type.navigationBars())
                it.systemBarsBehavior = android.view.WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            }
        } else {
            window.decorView.systemUiVisibility = (
                View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                or View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                or View.SYSTEM_UI_FLAG_FULLSCREEN
            )
        }
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus && !isInPictureInPictureMode) {
            enableImmersiveMode()
        }
    }

    /**
     * Check if running on Android TV
     */
    private fun isTvDevice(): Boolean {
        val uiModeManager = getSystemService(Context.UI_MODE_SERVICE) as android.app.UiModeManager
        return uiModeManager.currentModeType == android.content.res.Configuration.UI_MODE_TYPE_TELEVISION
    }

    // ==================== Picture-in-Picture (PIP) Support ====================
    
    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        // On mobile: Enter PIP when user presses home button IF auto-pip is enabled
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O && !isTvDevice() && prefs.autoPipEnabled) {
            val isPlaying = if (prefs.playerEngine == "exo") exoPlayer?.isPlaying == true else ivsPlayer?.state == Player.State.PLAYING
            if (isPlaying) {
                enterPipMode()
            }
        }
    }

    private fun enterPipMode() {
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            try {
                // Receiver is now registered in onCreate
                val params = getPipParams()
                enterPictureInPictureMode(params)
            } catch (e: Exception) {
                Log.e("PlayerActivity", "Failed to enter PIP mode: ${e.message}")
            }
        }
    }

    private fun getPipParams(): PictureInPictureParams {
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            val aspectRatio = Rational(16, 9)
            val actions = mutableListOf<RemoteAction>()
            
            // 1. Live Action (Left)
            val liveIntent = Intent(PIP_CONTROL_ACTION).apply {
                setPackage(packageName)
                putExtra(EXTRA_CONTROL_TYPE, CONTROL_TYPE_LIVE)
            }
            val livePendingIntent = PendingIntent.getBroadcast(this, CONTROL_TYPE_LIVE, liveIntent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
            
            actions.add(RemoteAction(
                Icon.createWithResource(this, R.drawable.ic_pip_live),
                "Canlı",
                "Canlı Yayına Git",
                livePendingIntent
            ))

            // 2. Play/Pause Action (Middle)
            val isPlaying = if (prefs.playerEngine == "exo") exoPlayer?.isPlaying == true else ivsPlayer?.state == com.amazonaws.ivs.player.Player.State.PLAYING
            val iconRes = if (isPlaying) android.R.drawable.ic_media_pause else android.R.drawable.ic_media_play
            val title = if (isPlaying) "Duraklat" else "Oynat"
            
            val intent = Intent(PIP_CONTROL_ACTION).apply {
                setPackage(packageName)
                putExtra(EXTRA_CONTROL_TYPE, CONTROL_TYPE_PLAY_PAUSE)
            }
            val pendingIntent = PendingIntent.getBroadcast(this, CONTROL_TYPE_PLAY_PAUSE, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
            
            actions.add(RemoteAction(
                Icon.createWithResource(this, iconRes),
                title,
                title,
                pendingIntent
            ))
            
            // 3. Audio Only Action (Right)
            val audioIntent = Intent(PIP_CONTROL_ACTION).apply {
                setPackage(packageName)
                putExtra(EXTRA_CONTROL_TYPE, CONTROL_TYPE_AUDIO_ONLY)
            }
            val audioPendingIntent = PendingIntent.getBroadcast(this, CONTROL_TYPE_AUDIO_ONLY, audioIntent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
            
            actions.add(RemoteAction(
                Icon.createWithResource(this, R.drawable.ic_pip_headset),
                "Ses",
                "Arka Plan Ses Modu",
                audioPendingIntent
            ))

            return PictureInPictureParams.Builder()
                .setAspectRatio(aspectRatio)
                .setActions(actions)
                .build()
        }
        return PictureInPictureParams.Builder().build()
    }

    private fun updatePictureInPictureParams() {
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O && isInPictureInPictureMode) {
            setPictureInPictureParams(getPipParams())
        }
    }

    override fun onPictureInPictureModeChanged(isInPictureInPictureMode: Boolean, newConfig: Configuration) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig)
        
        if (isInPictureInPictureMode) {
            // Hide UI elements in PIP mode without affecting player state
            binding.channelInfoOverlay.visibility = View.GONE
            binding.quickMenuOverlay.visibility = View.GONE
            binding.sidebarMenu.visibility = View.GONE
            binding.menuScrim.visibility = View.GONE
            binding.drawerPanel.visibility = View.GONE
            binding.loginPanel.visibility = View.GONE
            binding.qualityPopup.visibility = View.GONE
            binding.chatPanel.visibility = View.GONE
            binding.statsOverlay.visibility = View.GONE
            binding.searchPanel.visibility = View.GONE
            binding.startupLoadingOverlay.visibility = View.GONE
            isChatVisible = false
            isStatsVisible = false
            currentState = MenuState.NONE
            
            if (ivsPlayer?.state != Player.State.PLAYING && prefs.playerEngine != "exo") {
                ivsPlayer?.play()
            } else if (exoPlayer?.isPlaying != true && prefs.playerEngine == "exo") {
                exoPlayer?.play()
            }
        } else {
            // Restore immersive mode when exiting PIP
            enableImmersiveMode()
            
            // User requirement: If background audio is OFF, stop playback when PIP ends
            if (!prefs.backgroundAudioEnabled && !isTvDevice()) {
                ivsPlayer?.pause()
                exoPlayer?.pause()
                isBackgroundAudioEnabled = false
                stopService(Intent(this, PlaybackService::class.java))
            }
        }
    }

    private fun setupSearch() {
        binding.searchEditText.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == android.view.inputmethod.EditorInfo.IME_ACTION_SEARCH) {
                performSearch(binding.searchEditText.text.toString())
                true
            } else false
        }
    }

    private fun setupGestureDetectors() {
        // Swipe gesture detector
        gestureDetector = GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
            private val SWIPE_THRESHOLD = 100
            private val SWIPE_VELOCITY_THRESHOLD = 100
            private val EDGE_DEADZONE_DP = 24 // Reduced from 48 for better mobile responsiveness
            private val TOP_DEADZONE_DP = 80 // Top edge deadzone for notification panel



            private fun isInDeadzone(x: Float): Boolean {
                val deadzonePx = EDGE_DEADZONE_DP * resources.displayMetrics.density
                val screenWidth = resources.displayMetrics.widthPixels
                return x < deadzonePx || x > screenWidth - deadzonePx
            }
            
            private fun isInTopDeadzone(y: Float): Boolean {
                val deadzonePx = TOP_DEADZONE_DP * resources.displayMetrics.density
                return y < deadzonePx
            }

            override fun onFling(
                e1: MotionEvent?,
                e2: MotionEvent,
                velocityX: Float,
                velocityY: Float
            ): Boolean {
                if (scaleGestureDetector.isInProgress || isPanning) return false
                if (e1 == null) return false
                
                // If touch starts inside an active menu area, ignore it (let the menu itself handle it)
                val insideMenu = isInsideMenu(e1.x)
                val inDeadzone = isInDeadzone(e1.x)
                val inTopDeadzone = isInTopDeadzone(e1.y)
                
                if (insideMenu || inDeadzone || inTopDeadzone) {
                    return false
                }
                
                val diffX = e2.x - e1.x
                val diffY = e2.y - e1.y
                
                // Determine if it's a horizontal or vertical swipe
                if (kotlin.math.abs(diffX) > kotlin.math.abs(diffY)) {
                    // Horizontal swipe
                    if (kotlin.math.abs(diffX) > SWIPE_THRESHOLD && kotlin.math.abs(velocityX) > SWIPE_VELOCITY_THRESHOLD) {
                        if (diffX > 0) {
                            // Swipe right -> Chat (in NONE) or BACK (if menu open)
                            if (currentState == MenuState.NONE) {
                                onKeyDown(KeyEvent.KEYCODE_DPAD_RIGHT, null)
                            } else {
                                onKeyDown(KeyEvent.KEYCODE_BACK, null)
                            }
                        } else {
                            // Swipe left -> Progression logic and not settings
                            if (currentState == MenuState.SIDEBAR && isChannelMenu) {
                                showDrawer()
                            } else if (currentState == MenuState.NONE || currentState == MenuState.INFO) {
                                showChannelSidebar()
                            } else {
                                onKeyDown(KeyEvent.KEYCODE_DPAD_LEFT, null)
                            }
                        }
                        return true
                    }
                } else {
                    // Vertical swipe (Context-aware: Channel change if NONE, Navigation if Menu open)
                    if (kotlin.math.abs(diffY) > SWIPE_THRESHOLD && kotlin.math.abs(velocityY) > SWIPE_VELOCITY_THRESHOLD) {
                        if (diffY > 0) {
                            onKeyDown(KeyEvent.KEYCODE_DPAD_DOWN, null)
                        } else {
                            onKeyDown(KeyEvent.KEYCODE_DPAD_UP, null)
                        }
                        return true
                    }
                }
                return false
            }

            override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
                if (scaleGestureDetector.isInProgress) return false
                val insideMenu = isInsideMenu(e.x)
                
                if (insideMenu) return false
                
                // If a menu is open, a click in empty area acts as "Select/OK" for that menu's focused item
                if (currentState == MenuState.SIDEBAR || currentState == MenuState.DRAWER || currentState == MenuState.QUICK_MENU || currentState == MenuState.QUALITY ||
                    currentState == MenuState.LOGIN) {
                    onKeyDown(KeyEvent.KEYCODE_DPAD_CENTER, null)
                    return true
                }
                
                // Otherwise (NONE or INFO), handle the Remote OK logic (Info -> Quick Menu)
                if (binding.channelInfoOverlay.visibility == View.VISIBLE) {
                    showQuickMenu()
                } else {
                    showInfoOverlay()
                }
                return true
            }

            override fun onDoubleTap(e: MotionEvent): Boolean {
                if (scaleGestureDetector.isInProgress) return false
                if (currentState != MenuState.NONE) return false
                // Double tap -> Toggle video format
                toggleVideoFormat()
                return true
            }
        })

        // Pinch zoom gesture detector for video format toggle
        scaleGestureDetector = ScaleGestureDetector(this, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            private var scaleFactor = 1f

            override fun onScaleBegin(detector: ScaleGestureDetector): Boolean {
                scaleFactor = 1f
                return true
            }

            override fun onScale(detector: ScaleGestureDetector): Boolean {
                scaleFactor *= detector.scaleFactor
                return true
            }

            override fun onScaleEnd(detector: ScaleGestureDetector) {
                // If significant scale change occurred, toggle format
                if (scaleFactor > 1.3f || scaleFactor < 0.7f) {
                    toggleVideoFormat()
                }
            }
        })
    }

    private fun toggleVideoFormat() {
        isFitToScreen = !isFitToScreen
        // Reset pan when changing format
        resetVideoPan()
        
        if (isFitToScreen) {
            // Fit to screen (default) - video fills screen, may crop edges
            binding.playerView.resizeMode = com.amazonaws.ivs.player.ResizeMode.FILL
            Toast.makeText(this, getString(R.string.format_fit), Toast.LENGTH_SHORT).show()
        } else {
            // 16:9 format (may have black bars, no cropping)
            binding.playerView.resizeMode = com.amazonaws.ivs.player.ResizeMode.FIT
            Toast.makeText(this, "16:9", Toast.LENGTH_SHORT).show()
        }
    }

    private fun resetVideoPan() {
        videoPanX = 0f
        videoPanY = 0f
        binding.playerView.translationX = 0f
        binding.playerView.translationY = 0f
    }

    private fun isInsideMenu(x: Float): Boolean {
        val density = resources.displayMetrics.density
        var maxRight = 0f
        
        // Check Drawer boundary
        if (binding.drawerPanel.visibility == View.VISIBLE) {
            maxRight = kotlin.math.max(maxRight, binding.drawerPanel.x + 200f * density)
        }
        
        // Check Sidebar boundary
        if (binding.sidebarMenu.visibility == View.VISIBLE) {
            maxRight = kotlin.math.max(maxRight, binding.sidebarMenu.x + 350f * density)
        }
        
        val result = x < maxRight
        return result
    }

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        // Feed both gesture detectors
        scaleGestureDetector.onTouchEvent(ev)
        val handledByGesture = gestureDetector.onTouchEvent(ev)
        
        val insideMenu = isInsideMenu(ev.x)
        
        // If it's a gesture in the empty area, consume it here to prevent 
        // underlying views (like RecyclerView or Player) from reacting.
        if (!insideMenu && handledByGesture && currentState == MenuState.NONE) {
            handlePanning(ev)
            return true 
        }

        handlePanning(ev)
        
        if (ev.actionMasked == MotionEvent.ACTION_UP || ev.actionMasked == MotionEvent.ACTION_CANCEL) {
            isPanning = false
        }
        
        return super.dispatchTouchEvent(ev)
    }

    private fun handlePanning(ev: MotionEvent) {
        if (isFitToScreen && ev.pointerCount == 2) {
            when (ev.actionMasked) {
                MotionEvent.ACTION_POINTER_DOWN -> {
                    isPanning = true
                    lastTouchX = (ev.getX(0) + ev.getX(1)) / 2
                    lastTouchY = (ev.getY(0) + ev.getY(1)) / 2
                }
                MotionEvent.ACTION_MOVE -> {
                    if (isPanning && ev.pointerCount >= 2) {
                        val currentX = (ev.getX(0) + ev.getX(1)) / 2
                        val currentY = (ev.getY(0) + ev.getY(1)) / 2
                        
                        val deltaX = currentX - lastTouchX
                        val deltaY = currentY - lastTouchY
                        
                        val screenWidth = binding.playerContainer.width.toFloat()
                        val screenHeight = binding.playerContainer.height.toFloat()
                        
                        val quality = ivsPlayer?.quality
                        val videoAspect = (quality?.width?.toFloat() ?: 1920f) / (quality?.height?.toFloat() ?: 1080f)
                        val screenAspect = screenWidth / screenHeight
                        
                        var maxPanX = 0f
                        var maxPanY = 0f
                        
                        if (videoAspect > screenAspect) {
                            val scaledVideoWidth = screenHeight * videoAspect
                            maxPanX = (scaledVideoWidth - screenWidth) / 2
                        } else {
                            val scaledVideoHeight = screenWidth / videoAspect
                            maxPanY = (scaledVideoHeight - screenHeight) / 2
                        }
                        
                        videoPanX = (videoPanX + deltaX).coerceIn(-maxPanX, maxPanX)
                        videoPanY = (videoPanY + deltaY).coerceIn(-maxPanY, maxPanY)
                        
                        binding.playerView.translationX = videoPanX
                        binding.playerView.translationY = videoPanY
                        
                        lastTouchX = currentX
                        lastTouchY = currentY
                    }
                }
            }
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        return super.onTouchEvent(event)
    }

    private fun setupRecyclerView() {
        binding.sidebarRecyclerView.layoutManager = LinearLayoutManager(this)
        channelSidebarAdapter = ChannelSidebarAdapter(mutableListOf(), 0, prefs.themeColor, prefs.language, { pos ->
            currentChannelIndex = pos
            playCurrentChannelInternal()
        }, { loadMoreChannels() })
        binding.sidebarRecyclerView.adapter = channelSidebarAdapter
    }
    
    private fun setupDrawer() {
        val drawerWidth = 200f * resources.displayMetrics.density
        binding.drawerPanel.translationX = -drawerWidth
        binding.drawerPanel.visibility = View.GONE
        
        binding.btnTogglePassword.setOnClickListener {
            val isPassword = binding.loginPasswordInput.inputType and android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD != 0
            if (isPassword) {
                binding.loginPasswordInput.inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD
                binding.btnTogglePassword.setImageResource(R.drawable.ic_eye)
            } else {
                binding.loginPasswordInput.inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD
                binding.btnTogglePassword.setImageResource(R.drawable.ic_eye_off)
            }
            // Move cursor to end
            binding.loginPasswordInput.setSelection(binding.loginPasswordInput.text.length)
        }
        
        binding.btnSortViewersDesc.setOnClickListener { switchGlobalSortMode("viewer_count_desc") }
        binding.btnSortViewersAsc.setOnClickListener { switchGlobalSortMode("viewer_count_asc") }
        binding.btnSortFeatured.setOnClickListener { switchGlobalSortMode("featured") }
        binding.btnListFollowing.setOnClickListener { switchListMode(ListMode.FOLLOWING) }
        binding.btnDrawerMyChannel.setOnClickListener { playMyChannel() }
        binding.btnDrawerSearch.setOnClickListener { showSearchPanel() }
        binding.btnDrawerLanguageFilter.setOnClickListener { showLanguageFilterSidebar() }
        binding.btnDrawerSettings.setOnClickListener { 
            hideDrawer(immediate = true)
            showSettingsSidebar() 
        }
        
        binding.drawerUserContainer.setOnClickListener {
            if (prefs.isLoggedIn) {
                // Logout logic
                prefs.clearAuth()
                updateDrawerUserInfo()
                Toast.makeText(this, getString(R.string.logged_out), Toast.LENGTH_SHORT).show()
                if (currentListMode == ListMode.FOLLOWING) {
                    switchListMode(ListMode.GLOBAL)
                }
            } else {
                hideDrawer(immediate = true)
                showLoginPanel()
            }
        }
        
        // Login panel setup
        binding.loginSubmitBtn.setOnClickListener { performLogin() }
        binding.loginCancelBtn.setOnClickListener { hideLoginPanel() }
        
        updateDrawerUserInfo()

        // Mobile Touch Optimization: Panel click acts as OK, Scrim click acts as BACK
        binding.drawerPanel.setOnClickListener { onKeyDown(KeyEvent.KEYCODE_DPAD_CENTER, null) }
        //binding.menuScrim.setOnClickListener { onKeyDown(KeyEvent.KEYCODE_BACK, null) }
    }

    private fun switchListMode(mode: ListMode) {
        if (currentListMode == mode && allChannels.isNotEmpty()) {
            hideDrawer()
            return
        }
        
        if (mode == ListMode.FOLLOWING && !prefs.isLoggedIn) {
            Toast.makeText(this, getString(R.string.not_logged_in), Toast.LENGTH_SHORT).show()
            return
        }
        
        currentListMode = mode
        prefs.lastListMode = mode.name 
        updateDrawerUserInfo()
        allChannels.clear()
        currentChannelIndex = 0
        channelSidebarAdapter?.replaceChannels(allChannels, 0)
        
        hideDrawer()
        loadInitialChannels()
    }

    private fun switchGlobalSortMode(sortMode: String) {
        if (currentListMode == ListMode.GLOBAL && prefs.globalSortMode == sortMode && allChannels.isNotEmpty()) {
            hideDrawer()
            return
        }

        currentListMode = ListMode.GLOBAL
        prefs.lastListMode = ListMode.GLOBAL.name
        prefs.globalSortMode = sortMode
        
        updateDrawerUserInfo()
        allChannels.clear()
        currentChannelIndex = 0
        channelSidebarAdapter?.replaceChannels(allChannels, 0)
        
        hideDrawer()
        loadInitialChannels()
    }


    private fun updateDrawerUserInfo() {
        val isLoggedIn = prefs.isLoggedIn
        val themeColor = prefs.themeColor

        if (isLoggedIn) {
            binding.drawerUsername.text = prefs.username ?: getString(R.string.guest)
            binding.btnDrawerLogin.text = getString(R.string.logout)
            binding.btnDrawerLogin.setTextColor(Color.parseColor("#FF4444")) // Soft Red for Logout
            
            Glide.with(this)
                .load(prefs.profilePic)
                .placeholder(R.drawable.ic_user)
                .error(R.drawable.ic_user)
                .circleCrop()
                .into(binding.drawerProfileImage)
            
            binding.drawerProfileImage.strokeColor = android.content.res.ColorStateList.valueOf(themeColor)

            // If profile pic is missing, try to fetch it from channel API as fallback
            if (prefs.profilePic.isNullOrEmpty() && !prefs.username.isNullOrEmpty()) {
                lifecycleScope.launch {
                    repository.getChannelDetails(prefs.username!!).onSuccess { details ->
                        val pic = details.user?.profilePic
                        if (!pic.isNullOrEmpty()) {
                            prefs.profilePic = pic
                            Glide.with(this@PlayerActivity)
                                .load(pic)
                                .placeholder(R.drawable.ic_user)
                                .circleCrop()
                                .into(binding.drawerProfileImage)
                        }
                    }
                }
            }
                
            binding.btnListFollowing.visibility = View.VISIBLE
        } else {
            binding.drawerUsername.text = getString(R.string.guest)
            binding.btnDrawerLogin.text = getString(R.string.login)
            binding.btnDrawerLogin.setTextColor(themeColor) // Theme color for Login
            
            binding.drawerProfileImage.setImageResource(R.drawable.ic_user)
            binding.drawerProfileImage.strokeColor = android.content.res.ColorStateList.valueOf(themeColor)
            
            binding.btnListFollowing.visibility = View.VISIBLE
            
            if (currentListMode == ListMode.FOLLOWING) {
                currentListMode = ListMode.GLOBAL
                prefs.lastListMode = ListMode.GLOBAL.name
            }
        }

        // Apply theme and focus behavior to all buttons
        val sort = prefs.globalSortMode
        applyThemeToDynamicButton(binding.btnSortViewersDesc, themeColor, currentListMode == ListMode.GLOBAL && sort == "viewer_count_desc")
        applyThemeToDynamicButton(binding.btnSortViewersAsc, themeColor, currentListMode == ListMode.GLOBAL && sort == "viewer_count_asc")
        applyThemeToDynamicButton(binding.btnSortFeatured, themeColor, currentListMode == ListMode.GLOBAL && sort == "featured")
        
        applyThemeToDynamicButton(binding.btnListFollowing, themeColor, currentListMode == ListMode.FOLLOWING)
        applyThemeToDynamicButton(binding.btnDrawerMyChannel, themeColor, false)
        applyThemeToDynamicButton(binding.btnDrawerSearch, themeColor, currentListMode == ListMode.SEARCH)
        applyThemeToDynamicButton(binding.btnDrawerLanguageFilter, themeColor, sidebarContext == "LANGUAGE_FILTER")
        applyThemeToDynamicButton(binding.btnDrawerSettings, themeColor, false)
        applyThemeToDynamicButton(binding.drawerUserContainer, themeColor, false)
    }

    private fun applyThemeToDynamicButton(view: View, color: Int, isActive: Boolean = false) {
        view.setOnFocusChangeListener { v, hasFocus ->
            updateButtonStyle(v, color, isActive, hasFocus)
        }
        updateButtonStyle(view, color, isActive, view.hasFocus())
    }

    private fun updateButtonStyle(view: View, color: Int, isActive: Boolean, isFocused: Boolean) {
        val bg = GradientDrawable()
        val density = resources.displayMetrics.density
        bg.cornerRadius = 14f * density // Consistent with others
        
        if (isFocused) {
            // Background: 30% alpha of theme color
            val alphaColor = (color and 0x00FFFFFF) or 0x4D000000 
            bg.setColor(alphaColor)
            // Border: Solid theme color
            bg.setStroke((2.5f * density).toInt(), color)
            
            view.setAllChildrenColor(Color.WHITE)
            // Bold text on focus if it's a menu item using children coloring
            (view as? ViewGroup)?.let { group ->
                for (i in 0 until group.childCount) {
                    val child = group.getChildAt(i)
                    if (child is TextView) child.typeface = android.graphics.Typeface.DEFAULT_BOLD
                }
            }
        } else {
            bg.setStroke(0, Color.TRANSPARENT)
            if (isActive) {
                // Subtle indicator for current selection (15% alpha)
                val alphaColor = (color and 0x00FFFFFF) or 0x26000000 
                bg.setColor(alphaColor)
                view.setAllChildrenColor(color)
            } else {
                bg.setColor(Color.TRANSPARENT)
                view.setAllChildrenColor(Color.parseColor("#BBBBBB")) // greyColor
            }
            
            // Normal weight text
            (view as? ViewGroup)?.let { group ->
                for (i in 0 until group.childCount) {
                    val child = group.getChildAt(i)
                    if (child is TextView) child.typeface = android.graphics.Typeface.DEFAULT
                }
            }
        }
        
        view.background = bg
    }

    private fun showDrawer(focusType: String = "default") {
        val drawerWidth = 200f * resources.displayMetrics.density
        val isSidebarVisible = binding.sidebarMenu.visibility == View.VISIBLE
        
        // Hide only non-menu overlays
        binding.quickMenuOverlay.visibility = View.GONE
        binding.channelInfoOverlay.visibility = View.GONE
        binding.qualityPopup.visibility = View.GONE
        binding.searchPanel.visibility = View.GONE
        binding.errorPanel.visibility = View.GONE
        
        binding.menuScrim.visibility = View.VISIBLE
        binding.drawerPanel.visibility = View.VISIBLE
        currentState = MenuState.DRAWER
        
        if (prefs.animationsEnabled) {
            binding.menuScrim.animate().alpha(1f).setDuration(300).start()
            binding.drawerPanel.animate()
                .translationX(0f)
                .setDuration(300)
                .setInterpolator(DecelerateInterpolator())
                .start()
            
            if (isSidebarVisible) {
                binding.sidebarMenu.animate().translationX(drawerWidth).setDuration(300).start()
            }
        } else {
            binding.menuScrim.alpha = 1f
            binding.drawerPanel.translationX = 0f
            if (isSidebarVisible) {
                binding.sidebarMenu.translationX = drawerWidth
            }
        }
        
        binding.drawerPanel.postDelayed({
            val target = when (focusType) {
                "settings" -> binding.btnDrawerSettings
                "login" -> binding.drawerUserContainer
                else -> {
                    when (currentListMode) {
                        ListMode.GLOBAL -> binding.btnSortViewersDesc
                        ListMode.FOLLOWING -> binding.btnListFollowing
                        ListMode.SEARCH -> binding.btnDrawerSearch
                    }
                }
            }
            
            // Force focus on target to enable UP/DOWN navigation within drawer
            target.isFocusableInTouchMode = true
            target.requestFocus()
            
            // Secondary check: if still not focused, try harder
            if (!target.isFocused) {
                 target.requestFocusFromTouch()
            }
        }, 100)
    }

    private fun hideDrawer(immediate: Boolean = false, animateSidebar: Boolean = true) {
        val drawerWidth = 200f * resources.displayMetrics.density
        val sidebarVisible = binding.sidebarMenu.visibility == View.VISIBLE
        
        if (immediate || !prefs.animationsEnabled) {
            binding.drawerPanel.visibility = View.GONE
            binding.drawerPanel.translationX = -drawerWidth
            if (animateSidebar && sidebarVisible) binding.sidebarMenu.translationX = 0f
            else if (!sidebarVisible) binding.menuScrim.visibility = View.GONE
        } else {
            binding.drawerPanel.animate()
                .translationX(-drawerWidth)
                .setDuration(250)
                .withEndAction { 
                    binding.drawerPanel.visibility = View.GONE 
                    if (!sidebarVisible) binding.menuScrim.visibility = View.GONE
                }
                .start()
            if (animateSidebar && sidebarVisible) {
                binding.sidebarMenu.animate().translationX(0f).setDuration(250).start()
            } else if (!sidebarVisible) {
                binding.menuScrim.animate().alpha(0f).setDuration(250).start()
            }
        }
        
        if (binding.drawerLanguageContent.visibility == View.VISIBLE) {
            hideLanguageFilter()
        }
        
        if (sidebarVisible) {
            currentState = MenuState.SIDEBAR
            isChannelMenu = true
            binding.sidebarRecyclerView.requestFocus()
        } else {
            currentState = MenuState.NONE
            sidebarContext = ""
        }
    }

    private fun showLoginPanel() {
        hideAllOverlays()
        currentState = MenuState.LOGIN
        binding.loginPanel.visibility = View.VISIBLE
        binding.loginEmailInput.requestFocus()
        
        // Reset form
        binding.loginErrorText.visibility = View.GONE
        binding.loginOtpContainer.visibility = View.GONE
        binding.loginLoadingSpinner.visibility = View.GONE
        binding.loginSubmitText.visibility = View.VISIBLE
        
        // Setup and start QR Server
        startQrLoginServer()
        
        if (prefs.animationsEnabled) {
            binding.loginPanel.alpha = 0f
            binding.loginPanel.animate().alpha(1f).setDuration(200).start()
        } else {
            binding.loginPanel.alpha = 1f
        }
    }

    private fun startQrLoginServer() {
        val ip = getLocalIpAddress()
        if (ip == null) {
            binding.qrLoadingSpinner.visibility = View.GONE
            binding.qrLoginUrl.text = getString(R.string.ip_not_found)
            return
        }

        binding.qrLoadingSpinner.visibility = View.VISIBLE
        binding.loginQrImage.setImageBitmap(null)
        
        lifecycleScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            try {
                loginServer?.stop()
                loginServer = LocalLoginServer(7171, authRepository) { token, user, pic ->
                    runOnUiThread {
                        prefs.saveAuth(token, user, pic)
                        updateDrawerUserInfo()
                        Toast.makeText(this@PlayerActivity, getString(R.string.login_success, user), Toast.LENGTH_SHORT).show()
                        hideLoginPanel()
                    }
                }
                loginServer?.start()
                
                val loginUrl = loginServer?.getLoginUrl(ip) ?: ""
                val qrBitmap = QRUtils.generateQRCode(loginUrl, 512)
                
                runOnUiThread {
                    binding.qrLoadingSpinner.visibility = View.GONE
                    binding.loginQrImage.setImageBitmap(qrBitmap)
                    binding.qrLoginUrl.text = loginUrl
                }
            } catch (e: Exception) {
                e.printStackTrace()
                runOnUiThread {
                    binding.qrLoadingSpinner.visibility = View.GONE
                    binding.qrLoginUrl.text = getString(R.string.server_error, e.message)
                }
            }
        }
    }

    private fun getLocalIpAddress(): String? {
        try {
            val interfaces = java.util.Collections.list(java.net.NetworkInterface.getNetworkInterfaces())
            for (intf in interfaces) {
                val addrs = java.util.Collections.list(intf.inetAddresses)
                for (addr in addrs) {
                    if (!addr.isLoopbackAddress) {
                        val sAddr = addr.hostAddress
                        val isIPv4 = sAddr?.indexOf(':')?.let { it < 0 } ?: false
                        if (isIPv4 && sAddr != null) return sAddr
                    }
                }
            }
        } catch (ex: Exception) {
            ex.printStackTrace()
        }
        return null
    }

    private fun hideLoginPanel() {
        if (currentState != MenuState.LOGIN) return
        
        loginServer?.stop()
        loginServer = null
        
        showChannelSidebar(requestFocus = false)
        
        if (prefs.animationsEnabled) {
            binding.loginPanel.animate().alpha(0f).setDuration(200).withEndAction { 
                binding.loginPanel.visibility = View.GONE 
                showDrawer(focusType = "login")
            }.start()
        } else {
            binding.loginPanel.visibility = View.GONE
            showDrawer(focusType = "login")
        }
    }

    private fun performLogin() {
        val email = binding.loginEmailInput.text.toString()
        val password = binding.loginPasswordInput.text.toString()
        val otp = binding.loginOtpInput.text.toString().takeIf { binding.loginOtpContainer.visibility == View.VISIBLE }

        if (email.isEmpty() || password.isEmpty()) {
            binding.loginErrorText.text = getString(R.string.login_fields_required)
            binding.loginErrorText.visibility = View.VISIBLE
            return
        }

        binding.loginLoadingSpinner.visibility = View.VISIBLE
        binding.loginSubmitText.visibility = View.GONE
        binding.loginErrorText.visibility = View.GONE

        lifecycleScope.launch {
            val result = authRepository.login(email, password, otp)
            
            binding.loginLoadingSpinner.visibility = View.GONE
            binding.loginSubmitText.visibility = View.VISIBLE
            
            when (result) {
                is LoginResult.Success -> {
                    val token = result.token
                    val user = result.user
                    prefs.saveAuth(token, user?.username ?: email, user?.profilePic ?: user?.profilePicture)
                    updateDrawerUserInfo()
                    
                    Toast.makeText(this@PlayerActivity, getString(R.string.login_success, user?.username ?: email), Toast.LENGTH_SHORT).show()
                    hideLoginPanel()
                }
                is LoginResult.TwoFARequired -> {
                    binding.loginOtpContainer.visibility = View.VISIBLE
                    binding.loginOtpInput.requestFocus()
                    binding.loginErrorText.text = getString(R.string.two_fa_required)
                    binding.loginErrorText.visibility = View.VISIBLE
                }
                is LoginResult.Error -> {
                    binding.loginErrorText.text = result.message
                    binding.loginErrorText.visibility = View.VISIBLE
                }
            }
        }
    }

    private fun setupChat() {
        chatAdapter = ChatAdapter()
        binding.chatRecyclerView.apply {
            layoutManager = LinearLayoutManager(this@PlayerActivity).apply {
                stackFromEnd = true
            }
            adapter = chatAdapter
            itemAnimator = null // Disable animations for performance
        }
        
        // Initialize chat panel as hidden (translated off screen)
        binding.chatPanel.translationX = 280f * resources.displayMetrics.density
        binding.chatPanel.visibility = View.GONE
        
        // Initialize WebSocket early to listen for channel events (even if chat is closed)
        setupWebSocket()
    }

    private fun setupWebSocket() {
        chatWebSocket = KcikChatWebSocket(
            onMessageReceived = { message ->
                chatHandler.post {
                    addChatMessage(message)
                }
            },
            onEventReceived = { event, data ->
                chatHandler.post {
                    handleChannelEvent(event, data)
                }
            },
            onConnectionStateChanged = { _ ->
                chatHandler.post {
                    updateChatConnectionIndicator()
                }
            }
        )
        chatWebSocket?.connect()
    }

    private fun handleChannelEvent(event: String, data: String) {
        if (event.contains("StreamerIsLive")) {
            Log.d("PlayerActivity", "Received StreamerIsLive event! Refreshing channel to start playback...")
            stopRecoveryPolling() // Event is better than polling
            // Small delay to ensure HLS playlist is fully ready on server after event
            hideHandler.postDelayed({
                if (allChannels.isNotEmpty() && currentChannelIndex in allChannels.indices) {
                    val channel = allChannels[currentChannelIndex]
                    // Update local state so playCurrentChannel doesn't return early
                    val updatedChannel = channel.copy(isLive = true)
                    allChannels[currentChannelIndex] = updatedChannel
                    
                    playCurrentChannel(useZapDelay = false, showInfo = true)
                }
            }, 2500L)
        }
    }

    private fun applySettings() {
        val alphaFloat = prefs.infoTransparency / 100f
        val alphaInt = (alphaFloat * 255).toInt()
        
        // Background alpha (only gradient)
        binding.channelInfoOverlay.background?.mutate()?.alpha = alphaInt
        binding.quickMenuOverlay.background?.mutate()?.alpha = alphaInt
        
        // Ensure View itself is opaque so text/images are crystal clear
        binding.channelInfoOverlay.alpha = 1.0f
        binding.quickMenuOverlay.alpha = 1.0f
        
        val themeColor = prefs.themeColor
        binding.channelNumber.setTextColor(themeColor)
        binding.sidebarTitle.setTextColor(themeColor)
        
        // Info Overlay Tints
        binding.qualityBadge.backgroundTintList = ColorStateList.valueOf(themeColor)
        binding.fpsBadge.backgroundTintList = ColorStateList.valueOf(themeColor)
        binding.viewerIcon.setColorFilter(themeColor)
        binding.sidebarLoadingBar.indeterminateTintList = ColorStateList.valueOf(themeColor)
        
        // Apply to Quick Menu Buttons
        applyThemeToQuickButton(binding.btnQualityQuick, themeColor)
        applyThemeToQuickButton(binding.btnRefreshQuick, themeColor)
        applyThemeToQuickButton(binding.btnStatsQuick, themeColor)
        applyThemeToQuickButton(binding.btnPipQuick, themeColor, Color.parseColor("#1976D2")) // Distinct Blue for PIP
        
        // Update Adapter Themes
        // Update Sidebar themes
        channelSidebarAdapter?.updateThemeColor(themeColor)
        (binding.sidebarRecyclerView.adapter as? GenericSelectionAdapter)?.updateThemeColor(themeColor)
        chatAdapter?.updateThemeColor(themeColor)
        
        // Update Drawer theme
        updateDrawerUserInfo()
        
        // Update language adapter if visible
        if (binding.drawerLanguageContent.visibility == View.VISIBLE) {
            (binding.drawerLanguageRecyclerView.adapter as? GenericSelectionAdapter)?.updateThemeColor(themeColor)
        }
    }

    private fun applyThemeToQuickButton(view: View, themeColor: Int, focusColor: Int? = null) {
        view.background = null // Remove any legacy rectangle background
        val greyColor = Color.parseColor("#BBBBBB")
        val finalFocusColor = focusColor ?: themeColor
        
        view.setOnFocusChangeListener { v, hasFocus ->
            // Find the FrameLayout (selection background) which is the first child in our new XML
            val selectionBg = (v as? ViewGroup)?.getChildAt(0) as? FrameLayout
            if (selectionBg != null) {
                val bg = GradientDrawable()
                bg.shape = GradientDrawable.OVAL
                if (hasFocus) {
                    bg.setColor(finalFocusColor)
                    v.setAllChildrenColor(Color.WHITE)
                } else {
                    bg.setColor(Color.TRANSPARENT)
                    v.setAllChildrenColor(greyColor)
                }
                selectionBg.background = bg
            }
        }
        // Initial state
        view.background = null
        view.setAllChildrenColor(greyColor)
    }

    private fun View.setAllChildrenColor(color: Int) {
        if (this is android.view.ViewGroup) {
            for (i in 0 until childCount) {
                getChildAt(i).setAllChildrenColor(color)
            }
        } else if (this is TextView) {
            this.setTextColor(color)
        } else if (this is ImageView) {
            this.setColorFilter(color)
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

    private fun setupQuickMenu() {
        binding.btnQualityQuick.setOnClickListener { toggleQualityMenu() }
        binding.btnRefreshQuick.setOnClickListener { hideAllOverlays(); playCurrentChannel() }
        binding.btnStatsQuick.setOnClickListener { toggleStats() }
    }
    
    private fun toggleStats() {
        isStatsVisible = !isStatsVisible
        binding.statsOverlay.visibility = if (isStatsVisible) View.VISIBLE else View.GONE
        if (isStatsVisible) {
            startStatsUpdate()
        } else {
            stopStatsUpdate()
        }
    }
    
    private val statsRunnable = object : Runnable {
        override fun run() {
            updateStats()
            if (isStatsVisible) {
                statsHandler.postDelayed(this, 1000L)
            }
        }
    }
    
    private fun startStatsUpdate() {
        statsHandler.removeCallbacks(statsRunnable)
        statsHandler.post(statsRunnable)
    }
    
    private fun stopStatsUpdate() {
        statsHandler.removeCallbacks(statsRunnable)
    }
    
    private fun updateStats() {
        if (prefs.playerEngine == "exo") {
            val player = exoPlayer ?: return
            val videoFormat = player.videoFormat
            val resolution = if (videoFormat != null) "${videoFormat.width}x${videoFormat.height}" else "Unknown"
            val bitRateMbps = if (videoFormat != null && videoFormat.bitrate > 0) String.format("%.2f Mbps", videoFormat.bitrate / 1000000.0) else "Unknown"
            
            // Latency
            val latencyMs = player.currentLiveOffset
            val latencyStr = if (latencyMs != -1L) String.format("%.2fs", latencyMs / 1000.0) else "N/A"
            
            // Buffer
            val bufferMs = Math.max(0L, player.bufferedPosition - player.currentPosition)
            val bufferStr = String.format("%.2fs", bufferMs / 1000.0)
            
            // Dropped Frames
            val counters = player.videoDecoderCounters
            val dropped = counters?.droppedBufferCount ?: 0
            val rendered = counters?.renderedOutputBufferCount ?: 0
            val decoded = (counters?.skippedInputBufferCount ?: 0) + (counters?.skippedOutputBufferCount ?: 0) + rendered
            val dropPercent = if (decoded > 0) String.format("%.2f%%", (dropped.toDouble() / (decoded + dropped).toDouble()) * 100.0) else "0.00%"
            
            val yesStr = getString(R.string.yes)
            val noStr = getString(R.string.no)

            val content = """
                ${getString(R.string.stat_resolution)}:      $resolution
                ${getString(R.string.stat_bitrate)}:         $bitRateMbps
                ${getString(R.string.stat_codec)}:           ${videoFormat?.sampleMimeType ?: "AVC/H.264"} (Exo)
                
                ${getString(R.string.stat_dropped_frames)}:  $dropped / $decoded ($dropPercent)
                ${getString(R.string.stat_buffer_health)}:   $bufferStr
                ${getString(R.string.stat_latency)}:         $latencyStr
                ${getString(R.string.stat_playback_rate)}:   ${String.format("%.2f", player.playbackParameters.speed)}x
                
                ${getString(R.string.stat_volume)}:          ${(player.volume * 100).toInt()}%
            """.trimIndent()
            binding.statsContent.text = content
            return
        }

        val player = ivsPlayer ?: return
        val quality = player.quality ?: return
        val stats = player.statistics
        
        val statsStr = stats.toString()
        val resolution = "${quality.width}x${quality.height}"
        
        fun parseField(key: String): String {
            return Regex("$key=([\\d.]+)").find(statsStr)?.groupValues?.get(1) ?: "0"
        }
        
        val rawBitrate = parseField("bitRate").toDoubleOrNull() ?: 0.0
        val bitrate = String.format("%.2f Mbps", rawBitrate / 1000000.0)
        val frameRate = parseField("frameRate")
        val decoded = parseField("decodedFrames").toLongOrNull() ?: 0L
        val dropped = parseField("droppedFrames").toLongOrNull() ?: 0L
        val rendered = parseField("renderedFrames").toLongOrNull() ?: 0L
        
        val dropPercent = if (decoded > 0) String.format("%.2f%%", (dropped.toDouble() / (decoded + dropped).toDouble()) * 100.0) else "0.00%"
        val latency = String.format("%.2fs", player.liveLatency / 1000.0)
        
        // Calculate buffer health: bufferedPosition - current position
        val bufferMs = player.bufferedPosition - player.position
        val buffer = String.format("%.2fs", (if (bufferMs > 0) bufferMs else 0L) / 1000.0)
        
        val yesStr = getString(R.string.yes)
        val noStr = getString(R.string.no)

        val content = """
            ${getString(R.string.stat_resolution)}:      $resolution
            ${getString(R.string.stat_fps)}:             $frameRate fps
            ${getString(R.string.stat_bitrate)}:         $bitrate
            ${getString(R.string.stat_codec)}:           H.264
            
            ${getString(R.string.stat_dropped_frames)}:  $dropped / $decoded ($dropPercent)
            ${getString(R.string.stat_decoded_frames)}:  $decoded
            ${getString(R.string.stat_rendered_frames)}: $rendered
            
            ${getString(R.string.stat_buffer_health)}:   $buffer
            ${getString(R.string.stat_latency)}:         $latency
            ${getString(R.string.stat_playback_rate)}:   ${player.playbackRate}x
            
            ${getString(R.string.stat_ready_state)}:     ${player.state}
            ${getString(R.string.stat_volume)}:          ${(player.volume * 100).toInt()}%
            ${getString(R.string.stat_muted)}:           ${if (player.isMuted) yesStr else noStr}
        """.trimIndent()
        
        binding.statsContent.text = content
    }
    
    private fun initializeIVSPlayer() {
        binding.playerView.setControlsEnabled(false)
        // Set initial video format to match isFitToScreen variable
        binding.playerView.resizeMode = if (isFitToScreen) {
            com.amazonaws.ivs.player.ResizeMode.FILL
        } else {
            com.amazonaws.ivs.player.ResizeMode.FIT
        }
        // Disable auto-pause when surface visibility changes (for PIP support)
        ivsPlayer = binding.playerView.player
        ivsPlayer?.setAutoQualityMode(true)
        ivsPlayer?.setRebufferToLive(true) // Crucial for low-latency stability: jumps to live edge instead of buffering
        ivsPlayer?.playbackRate = 1.0f    // Ensure normal speed by default (IVS auto-adjusts to catch up)
        
        ivsPlayer?.addListener(object : Player.Listener() {
            override fun onStateChanged(state: Player.State) {
                // Force hide any sub-views (like IVS's own ProgressBar)
                if (state == Player.State.BUFFERING) {
                    hideInternalSpinners(binding.playerView)
                    binding.sidebarLoadingBar.visibility = View.VISIBLE
                    stopStabilityWatchdog()
                } else if (state == Player.State.PLAYING) {
                    // Start background audio service immediately when playback starts (like a music player)
                    // This prevents audio interruption when screen is locked
                    // Start background audio service if enabled
                    if (!isBackgroundAudioEnabled && prefs.backgroundAudioEnabled && !isTvDevice()) {
                        isBackgroundAudioEnabled = true
                        startBackgroundPlayback()
                    }
                    
                    // Give the player a tiny bit of time (150ms) to swap the first frame into the surface
                    // This prevents seeing the last frame of the previous channel
                    binding.playerView.postDelayed({
                        if (ivsPlayer?.state == Player.State.PLAYING) {
                            binding.playerView.visibility = View.VISIBLE
                            binding.loadingThumbnailView.visibility = View.GONE
                            binding.sidebarLoadingBar.visibility = View.GONE
                            binding.offlineBannerView.visibility = View.GONE
                            hideError()
                            resetRetryCount()
                            startStabilityWatchdog()
                        }
                    }, 150)
                } else if (state == Player.State.READY) {
                    binding.sidebarLoadingBar.visibility = View.GONE
                    stopStabilityWatchdog()
                } else if (state == Player.State.ENDED) {
                    stopStabilityWatchdog()
                    allChannels.getOrNull(currentChannelIndex)?.let { updateChannelUIForOffline(it) }
                    startRecoveryPolling()
                } else {
                    stopStabilityWatchdog()
                }
                // Update PIP and Notification controls when state changes
                updatePictureInPictureParams()
                updateMediaSessionState()
            }
            override fun onError(exception: PlayerException) {
                scheduleRetry()
            }
            override fun onQualityChanged(quality: Quality) {
                // Resolution Badge
                binding.qualityBadge.text = if (quality.height >= 1080) "1080P" 
                                          else if (quality.height >= 720) "720P"
                                          else "${quality.height}P"
                
                // FPS Badge
                binding.fpsBadge.text = String.format("%.0f FPS", quality.framerate)
                binding.fpsBadge.visibility = if (quality.framerate > 0) View.VISIBLE else View.GONE
            }
            override fun onCue(cue: Cue) {}
            override fun onDurationChanged(duration: Long) {}
            override fun onMetadata(type: String, data: ByteBuffer) {
                // Optimization: Parse metadata on a background thread to avoid UI jank
                val dataCopy = data.duplicate()
                lifecycleScope.launch(Dispatchers.Default) {
                    try {
                        val metadataString = java.nio.charset.StandardCharsets.UTF_8.decode(dataCopy).toString()
                        if (metadataString.trim().startsWith("{")) {
                            val json = org.json.JSONObject(metadataString)
                            
                            // 1. Sync System Clock with Server Time
                            val serverTimeSec = when {
                                json.has("X-SERVER-TIME") -> json.optDouble("X-SERVER-TIME", 0.0)
                                json.has("X-TIMESTAMP") -> json.optDouble("X-TIMESTAMP", 0.0)
                                else -> 0.0
                            }
                            
                            var serverTimeFromDate = 0L
                            if (json.has("START-DATE")) {
                                parseIsoDate(json.getString("START-DATE"))?.let { serverTimeFromDate = it }
                            }
                            
                            val finalServerTimeMillis = if (serverTimeSec > 0) (serverTimeSec * 1000).toLong() else serverTimeFromDate
                            
                            if (finalServerTimeMillis > 0) {
                                serverClockOffset = finalServerTimeMillis - System.currentTimeMillis()
                            }
                            
                            // 2. Direct Uptime Update
                            val streamTime = when {
                                json.has("STREAM-TIME") -> json.optDouble("STREAM-TIME", -1.0)
                                json.has("X-STREAM-TIME") -> json.optDouble("X-STREAM-TIME", -1.0)
                                else -> -1.0
                            }
                            
                            if (streamTime >= 0) {
                                withContext(Dispatchers.Main) {
                                    binding.streamTimeBadge.text = formatStreamTime(streamTime.toLong())
                                    binding.streamTimeBadge.visibility = View.VISIBLE
                                }
                            }
                        }
                    } catch (e: Exception) {
                        Log.e("PlayerActivity", "Metadata parse error", e)
                    }
                }
            }
            override fun onRebuffering() {}
            override fun onSeekCompleted(position: Long) {}
            override fun onVideoSizeChanged(width: Int, height: Int) {}
            override fun onAnalyticsEvent(name: String, properties: String) {}
        })
    }

    private fun initializeExoPlayer() {
        // Optimization: Custom LoadControl for lower latency
        val loadControl = DefaultLoadControl.Builder()
            .setBufferDurationsMs(
                500,  // Min buffer (0.5s)
                1500, // Max buffer (1.5s)
                250,  // Buffer for playback start (0.25s)
                500   // Buffer for playback after rebuffer
            )
            .build()

        exoPlayer = ExoPlayer.Builder(this)
            .setLoadControl(loadControl)
            .build()
        
        binding.exoPlayerView.player = exoPlayer
        
        exoPlayer?.addListener(object : ExoPlayerBase.Listener {
            override fun onPlaybackStateChanged(playbackState: Int) {
                when (playbackState) {
                    ExoPlayerBase.STATE_BUFFERING -> {
                        binding.sidebarLoadingBar.visibility = View.VISIBLE
                        stopStabilityWatchdog()
                    }
                    ExoPlayerBase.STATE_READY -> {
                         binding.sidebarLoadingBar.visibility = View.GONE
                         if (exoPlayer?.playWhenReady == true) {
                            binding.exoPlayerView.visibility = View.VISIBLE
                            binding.loadingThumbnailView.visibility = View.GONE
                            binding.offlineBannerView.visibility = View.GONE
                            hideError()
                            resetRetryCount()
                            startStabilityWatchdog()
                            
                            // Start background audio service if enabled
                            if (!isBackgroundAudioEnabled && prefs.backgroundAudioEnabled && !isTvDevice()) {
                                isBackgroundAudioEnabled = true
                                startBackgroundPlayback()
                            }
                         }
                         updatePictureInPictureParams()
                         updateMediaSessionState()
                    }
                    ExoPlayerBase.STATE_ENDED -> {
                        stopStabilityWatchdog()
                        allChannels.getOrNull(currentChannelIndex)?.let { updateChannelUIForOffline(it) }
                        startRecoveryPolling()
                    }
                }
            }

            override fun onPlayerError(error: androidx.media3.common.PlaybackException) {
                Log.e("ExoPlayer", "Error: ${error.message}", error)
                scheduleRetry()
            }

            override fun onVideoSizeChanged(videoSize: VideoSize) {
                if (videoSize.width > 0 && videoSize.height > 0) {
                    binding.qualityBadge.text = if (videoSize.height >= 1080) "1080P" 
                                              else if (videoSize.height >= 720) "720P"
                                              else "${videoSize.height}P"
                    binding.fpsBadge.visibility = View.GONE // ExoPlayer doesn't expose FPS easily
                }
            }
        })
    }

    private fun hideStartupLoading() {
        if (binding.startupLoadingOverlay.visibility == View.VISIBLE) {
            if (prefs.animationsEnabled) {
                binding.startupLoadingOverlay.animate()
                    .alpha(0f)
                    .setDuration(300)
                    .withEndAction {
                        binding.startupLoadingOverlay.visibility = View.GONE
                    }
                    .start()
            } else {
                binding.startupLoadingOverlay.visibility = View.GONE
            }
        }
    }
    
    private fun loadInitialChannels() {
        nextCursor = null
        val languages = prefs.streamLanguages.toList().takeIf { it.isNotEmpty() }
        lifecycleScope.launch {
            val result = if (currentListMode == ListMode.FOLLOWING && prefs.authToken != null) {
                repository.getFollowingLiveStreams(prefs.authToken!!)
            } else if (prefs.globalSortMode == "featured") {
                // Featured API specifically requires a language to work correctly
                val featuredLanguages = languages ?: listOf(prefs.language)
                repository.getFeaturedStreams(featuredLanguages)
            } else {
                repository.getFilteredLiveStreams(languages, nextCursor, prefs.globalSortMode)
            }

            result.onSuccess { data: dev.xacnio.kciktv.data.repository.ChannelListData ->
                val filtered = data.channels.filter { !prefs.isCategoryBlocked(it.categoryName) }
                nextCursor = data.nextCursor
                if (filtered.isNotEmpty()) {
                    allChannels.clear()
                    allChannels.addAll(filtered)
                    
                    // On initial load, try to find the first non-mature channel if mature warning is not accepted
                    currentChannelIndex = 0
                    if (!isMatureAcceptedForSession) {
                        val firstNonMature = allChannels.indexOfFirst { !it.isMature }
                        if (firstNonMature != -1) {
                            currentChannelIndex = firstNonMature
                        }
                    }
                    
                    channelSidebarAdapter?.replaceChannels(allChannels, currentChannelIndex)
                    playCurrentChannel(useZapDelay = false)
                    showInfoOverlay() // Show info for the first channel automatically
                    hideStartupLoading()
                } else if (nextCursor != null && currentListMode == ListMode.GLOBAL) {
                    loadInitialChannels()
                } else if (filtered.isEmpty()) {
                    showError(getString(R.string.list_empty))
                    hideStartupLoading()
                }
            }.onFailure {
                showError(getString(R.string.channels_load_failed))
                hideStartupLoading()
            }
        }
    }

    private fun loadMoreChannels() {
        if (currentListMode == ListMode.SEARCH || nextCursor == null) return
        val currentCursor = nextCursor
        val languages = prefs.streamLanguages.toList().takeIf { it.isNotEmpty() }
        lifecycleScope.launch {
            val result = if (currentListMode == ListMode.FOLLOWING && prefs.authToken != null) {
                repository.getFollowingLiveStreams(prefs.authToken!!, currentCursor)
            } else if (prefs.globalSortMode == "featured") {
                Result.success(dev.xacnio.kciktv.data.repository.ChannelListData(emptyList(), null)) // No pagination for featured
            } else {
                repository.getFilteredLiveStreams(languages, currentCursor, prefs.globalSortMode)
            }

            result.onSuccess { data: dev.xacnio.kciktv.data.repository.ChannelListData ->
                val filtered = data.channels.filter { !prefs.isCategoryBlocked(it.categoryName) }
                val firstNewItemIndex = allChannels.size
                nextCursor = data.nextCursor
                if (filtered.isNotEmpty()) {
                    allChannels.addAll(filtered)
                    channelSidebarAdapter?.addChannels(filtered)
                    
                    // Focus on the first newly loaded item
                    binding.sidebarRecyclerView.post {
                        binding.sidebarRecyclerView.scrollToPosition(firstNewItemIndex)
                        binding.sidebarRecyclerView.postDelayed({
                            binding.sidebarRecyclerView.findViewHolderForAdapterPosition(firstNewItemIndex)?.itemView?.requestFocus()
                        }, 100)
                    }
                } else if (nextCursor != null) {
                    loadMoreChannels()
                }
            }
        }
    }
    
    private fun playCurrentChannel(useZapDelay: Boolean = false, showInfo: Boolean = true, keepBanner: Boolean = false, isRetry: Boolean = false) {
        if (allChannels.isEmpty()) return
        
        stopRecoveryPolling()
        if (!isRetry) {
            hideError()
            resetRetryCount()
        }
        
        // Reset refresh timer
        refreshHandler.removeCallbacks(activeChannelRefreshRunnable)
        refreshHandler.postDelayed(activeChannelRefreshRunnable, 30000L)
        
        // Update selection in sidebar
        channelSidebarAdapter?.setCurrentIndex(currentChannelIndex)
        
        // Safety check for index
        if (currentChannelIndex < 0 || currentChannelIndex >= allChannels.size) {
            currentChannelIndex = 0
        }
        
        if (allChannels.isEmpty()) return
        val channel = allChannels[currentChannelIndex]

        // Mature Content Check
        if (channel.isMature && !isMatureAcceptedForSession) {
            showMatureWarning(channel)
            return
        }
        
        ivsPlayer?.pause()
        // RESET VIDEO VIEW IMMEDIATELY (Prevent frozen frame)
        binding.playerView.visibility = View.GONE
        if (!keepBanner) {
            binding.offlineBannerView.visibility = View.GONE
        }
        
        // Reset uptime
        streamCreatedAtMillis = null
        binding.streamTimeBadge.visibility = View.GONE
        uptimeHandler.removeCallbacks(uptimeRunnable)
        
        // Ensure thumbnail is visible as placeholder
        binding.loadingThumbnailView.visibility = View.VISIBLE
        
        // Show Info Overlay so user sees where they are
        updateChannelUI(channel)
        if (showInfo) showInfoOverlay() // Show info immediately on explicit play/list change

        // Connect/Subscribe to channel events (StreamerIsLive) and Chat if needed
        connectToChat(channel.slug)

        if (!channel.isLive) {
            updateChannelUIForOffline(channel)
            return
        }

        // Show thumbnail as placeholder immediately with Blur Effect
        if (channel.thumbnailUrl != null) {
            binding.loadingThumbnailView.visibility = View.VISIBLE
            Glide.with(this)
                .load(channel.thumbnailUrl)
                .apply(RequestOptions.bitmapTransform(BlurTransformation(25, 4)))
                .placeholder(ColorDrawable(Color.BLACK))
                .into(binding.loadingThumbnailView)
        } else {
            binding.loadingThumbnailView.visibility = View.GONE
        }

        val playAction = Runnable {
            loadingJob?.cancel()
            loadingJob = lifecycleScope.launch {
                repository.getStreamUrl(channel.slug).onSuccess { url ->
                    if (prefs.playerEngine == "exo") {
                        ivsPlayer?.pause()
                        exoPlayer?.stop()
                        
                        val mode = prefs.catchUpMode
                        val liveConfigBuilder = MediaItem.LiveConfiguration.Builder()
                        
                        if (mode == "off") {
                            liveConfigBuilder.setTargetOffsetMs(8000)
                                .setMinPlaybackSpeed(1.0f)
                                .setMaxPlaybackSpeed(1.0f)
                        } else if (mode == "high") {
                            liveConfigBuilder.setTargetOffsetMs(4000)
                                .setMinPlaybackSpeed(0.97f)
                                .setMaxPlaybackSpeed(1.08f)
                        } else { // low
                            liveConfigBuilder.setTargetOffsetMs(6000)
                                .setMinPlaybackSpeed(0.98f)
                                .setMaxPlaybackSpeed(1.04f)
                        }

                        val liveConfig = liveConfigBuilder.build()
                            
                        val mediaItem = MediaItem.Builder()
                            .setUri(url)
                            .setLiveConfiguration(liveConfig)
                            .build()
                            
                        exoPlayer?.setMediaItem(mediaItem)
                        exoPlayer?.prepare()
                        exoPlayer?.play()
                    } else {
                        exoPlayer?.pause()
                        ivsPlayer?.pause()
                        ivsPlayer?.load(Uri.parse(url))
                        ivsPlayer?.play()
                    }
                }.onFailure {
                    scheduleRetry()
                }
            }
        }

        if (useZapDelay) {
            zapHandler.postDelayed(playAction, zapDelayMs)
        } else {
            playAction.run()
        }
    }
    
    private fun updateChannelUI(channel: ChannelItem) {
        binding.channelName.text = channel.username
        binding.streamTitle.text = channel.title
        binding.channelNumber.text = String.format("%02d", currentChannelIndex + 1)
        binding.viewerCount.text = formatViewerCount(channel.viewerCount)
        
        // Localized Category Name
        val rawCategory = channel.categoryName ?: getString(R.string.live_stream)
        binding.categoryName.text = CategoryUtils.getLocalizedCategoryName(this, rawCategory, channel.categorySlug, prefs.language)
        
        // Load Profile Image as Bitmap for Notifications & UI
        currentProfileBitmap = null
        Glide.with(this)
            .asBitmap()
            .load(channel.getEffectiveProfilePicUrl())
            .circleCrop()
            .into(object : CustomTarget<Bitmap>() {
                override fun onResourceReady(resource: Bitmap, transition: Transition<in Bitmap>?) {
                    currentProfileBitmap = resource
                    binding.profileImage.setImageBitmap(resource)
                    // Refresh notification if needed
                    if (isInPictureInPictureMode || isBackgroundAudioEnabled) {
                        updateMediaSessionState()
                    }
                }
                override fun onLoadCleared(placeholder: Drawable?) {
                    currentProfileBitmap = null
                    binding.profileImage.setImageDrawable(placeholder)
                }
            })
        
        // Restore visibility after offline state
        binding.viewerCount.visibility = View.VISIBLE
        binding.categoryName.visibility = View.VISIBLE
        binding.qualityBadge.visibility = View.VISIBLE
        binding.viewerIcon.visibility = View.VISIBLE
        binding.tagContainer.visibility = View.VISIBLE
        
        // Initialize Quality/FPS badges from current player state
        ivsPlayer?.quality?.let { quality ->
            binding.qualityBadge.text = if (quality.height >= 1080) "1080P" 
                                      else if (quality.height >= 720) "720P"
                                      else "${quality.height}P"
            binding.fpsBadge.text = String.format("%.0f FPS", quality.framerate)
            binding.fpsBadge.visibility = if (quality.framerate > 0) View.VISIBLE else View.GONE
        } ?: run {
            binding.qualityBadge.text = "AUTO"
            binding.fpsBadge.visibility = View.GONE
        }

        // Update tags
        binding.tagContainer.removeAllViews()
        val tagsToShow = mutableListOf<String>()
        
        // Add language as first tag
        channel.language?.let { code ->
            val langName = getStreamLanguages().find { it.first == code }?.second ?: code.uppercase()
            tagsToShow.add(langName)
        }
        
        // Add other tags
        channel.tags?.let { tagsToShow.addAll(it) }
        
        tagsToShow.forEach { tag ->
            val tagView = LayoutInflater.from(this).inflate(R.layout.item_tag, binding.tagContainer, false) as TextView
            tagView.text = tag
            binding.tagContainer.addView(tagView)
        }

        // Start tag scroll marquee if needed
        startTagMarquee()
        
        // Enable Marquee
        binding.channelName.isSelected = true
        binding.streamTitle.isSelected = true
        binding.categoryName.isSelected = true

        // Initialize uptime from initial channel data if available
        channel.startTimeMillis?.let {
            streamCreatedAtMillis = it
            updateUptimeDisplay()
        } ?: run {
            binding.streamTimeBadge.visibility = View.GONE
        }
        
        // Ensure MediaSession metadata and state are updated immediately
        updateMediaSessionState()
    }

    // ... formatViewerCount ...

    // ... showInfoOverlay ...

    // ... showQuickMenu ...

    // ... showChannelSidebar ...

    // ... playCurrentChannelInternal ...

    // ... showSidebar ...
    
    // ... setupMatureWarning ...
    
    // ... showMatureWarning ...
    
    // ... hideAllOverlays ...
    
    // ... resetHideTimer ...

    // ... toggleQualityMenu ...
    
    // ... showSettingsSidebar ... (lines 2342-2490)
    
    // Update showFilterSidebar
    private fun showFilterSidebar() {
        // List of categories (slugs or common names)
        val categories = listOf(
            "Just Chatting", "Knight Online", "Pools, Hot Tubs & Bikinis", "Counter-Strike 2", 
            "League of Legends", "Grand Theft Auto V", "Slots & Casino", 
            "Art", "Music", "Sports"
        )
        
        val items = categories.map { cat -> 
            // Display localized name, but save/check using the original English name/Slug
            val displayName = CategoryUtils.getLocalizedCategoryName(this, cat, null, prefs.language)
            SelectionItem(cat, displayName, prefs.isCategoryBlocked(cat), cat, showCheckbox = true) 
        }
        
        // Pass animate = false if already in settings_sub context to prevent flicker
        val shouldAnimate = sidebarContext != "settings_sub"
        
        showSidebar(getString(R.string.sidebar_title_filter_hide), items, "settings_sub", shouldAnimate, initialFocusId = lastSubSettingsFocusId ?: items.firstOrNull()?.id) { item ->
            lastSubSettingsFocusId = item.id
            // Toggle blocking
            val isBlocked = prefs.isCategoryBlocked(item.id)
            if (isBlocked) prefs.removeBlockedCategory(item.id) else prefs.addBlockedCategory(item.id)
            
            (binding.sidebarRecyclerView.adapter as? GenericSelectionAdapter)?.toggleItemSelection(item.id)
        }
    }

    private fun formatViewerCount(count: Int): String = when {
        count >= 1000000 -> String.format("%.1fM", count / 1000000.0)
        count >= 1000 -> String.format("%.1fK", count / 1000.0)
        else -> count.toString()
    }

    private fun showInfoOverlay() {
        hideHandler.removeCallbacks(hideRunnable)
        
        val isAlreadyVisible = binding.channelInfoOverlay.visibility == View.VISIBLE && binding.channelInfoOverlay.translationY == 0f

        // Don't close sidebar/drawer for info overlay, they can coexist
        val isMenuOpen = currentState == MenuState.SIDEBAR || currentState == MenuState.DRAWER
        if (!isMenuOpen && currentState != MenuState.INFO) {
            hideAllOverlays()
        }
        
        binding.channelInfoOverlay.visibility = View.VISIBLE
        binding.channelInfoOverlay.translationX = 0f // Reset horizontal translation
        
        if (isAlreadyVisible) {
            // Clean up any ongoing animations and stay at final position
            binding.channelInfoOverlay.animate().cancel()
            binding.channelInfoOverlay.translationY = 0f
            binding.channelInfoOverlay.alpha = 1f
        } else if (prefs.animationsEnabled) {
            binding.channelInfoOverlay.translationY = 220.toPx()
            binding.channelInfoOverlay.alpha = 0f
            binding.channelInfoOverlay.animate()
                .translationY(0f)
                .alpha(1f)
                .setDuration(400)
                .setInterpolator(android.view.animation.DecelerateInterpolator())
                .withStartAction {
                    uptimeHandler.removeCallbacks(uptimeRunnable)
                    uptimeHandler.post(uptimeRunnable)
                }
                .start()
        } else {
            binding.channelInfoOverlay.translationY = 0f
            binding.channelInfoOverlay.alpha = 1f
            uptimeHandler.removeCallbacks(uptimeRunnable)
            uptimeHandler.post(uptimeRunnable)
        }
        
        // Only change state if we are not in a menu (Sidebar/Drawer has higher priority for keys)
        if (!isMenuOpen) {
            currentState = MenuState.INFO
        }
        resetHideTimer()
    }

    private fun showQuickMenu() {
        hideHandler.removeCallbacks(hideRunnable) 
        hideAllOverlays()
        binding.quickMenuOverlay.visibility = View.VISIBLE
        // No bringToFront, rely on XML elevation (10dp)
        
        currentState = MenuState.QUICK_MENU
        
        // Quality available only for live streams
        val currentChannel = allChannels.getOrNull(currentChannelIndex)
        val isLive = currentChannel?.isLive == true
        
        binding.btnQualityQuick.visibility = if (isLive) View.VISIBLE else View.GONE
        binding.btnStatsQuick.visibility = if (isLive) View.VISIBLE else View.GONE
        binding.btnRefreshQuick.visibility = View.VISIBLE
        
        // Show PIP button: Always on TV, only for "Manual" mode on Mobile
        val showPipButton = android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O && 
                           (isTvDevice() || !prefs.autoPipEnabled)
        binding.btnPipQuick.visibility = if (showPipButton) View.VISIBLE else View.GONE
        
        // Set focus navigation for PIP button based on visible buttons
        if (showPipButton) {
            if (isLive) {
                // Stats visible: Stats -> PIP
                binding.btnStatsQuick.nextFocusRightId = R.id.btnPipQuick
                binding.btnPipQuick.nextFocusLeftId = R.id.btnStatsQuick
            } else {
                // Stats hidden: Refresh -> PIP
                binding.btnRefreshQuick.nextFocusRightId = R.id.btnPipQuick
                binding.btnPipQuick.nextFocusLeftId = R.id.btnRefreshQuick
            }
        } else {
            // Reset focus navigation
            binding.btnStatsQuick.nextFocusRightId = View.NO_ID
            binding.btnRefreshQuick.nextFocusRightId = View.NO_ID
        }
        
        // PIP button click handler
        binding.btnPipQuick.setOnClickListener {
            hideAllOverlays()
            enterPipMode()
        }
        
        val targetButton = if (isLive) binding.btnQualityQuick else binding.btnRefreshQuick
        
        if (prefs.animationsEnabled) {
            binding.quickMenuOverlay.translationY = 50f
            binding.quickMenuOverlay.alpha = 0f
            binding.quickMenuOverlay.animate()
                .translationY(0f)
                .alpha(1f)
                .setDuration(300)
                .withEndAction {
                    targetButton.requestFocus()
                }
                .start()
        } else {
            binding.quickMenuOverlay.translationY = 0f
            binding.quickMenuOverlay.alpha = 1f
            targetButton.requestFocus()
        }

        // Mobile Touch Optimization: Overlay click acts as OK
        binding.quickMenuOverlay.setOnClickListener { onKeyDown(KeyEvent.KEYCODE_DPAD_CENTER, null) }
    }

    private fun showChannelSidebar(requestFocus: Boolean = true) {
        hideHandler.removeCallbacks(hideRunnable)
        
        // Auto-hide chat when sidebar is opened (for mobile usability)
        if (isChatVisible) {
            hideChat()
        }
        
        // Hide only non-menu overlays
        binding.quickMenuOverlay.visibility = View.GONE
        binding.channelInfoOverlay.visibility = View.GONE
        binding.qualityPopup.visibility = View.GONE
        binding.searchPanel.visibility = View.GONE
        
        // Mobile Touch Optimization: Sidebar click acts as OK
        binding.sidebarMenu.setOnClickListener { onKeyDown(KeyEvent.KEYCODE_DPAD_CENTER, null) }
        
        sidebarContext = "channels"
        binding.sidebarTitle.text = getString(R.string.sidebar_channels)
        
        val drawerWidth = 200f * resources.displayMetrics.density
        val isDrawerVisible = binding.drawerPanel.visibility == View.VISIBLE
        val targetX = if (isDrawerVisible) drawerWidth else 0f
        
        channelSidebarAdapter?.setCurrentIndex(currentChannelIndex)
        
        if (prefs.animationsEnabled) {
            binding.sidebarMenu.translationX = targetX - 100f
            binding.sidebarMenu.alpha = 0f
            binding.menuScrim.alpha = binding.menuScrim.alpha // Keep current alpha if already visible
        } else {
            binding.sidebarMenu.translationX = targetX
            binding.sidebarMenu.alpha = 1f
            binding.menuScrim.alpha = 1f
        }
        
        binding.sidebarMenu.visibility = View.VISIBLE
        binding.menuScrim.visibility = View.VISIBLE
        currentState = MenuState.SIDEBAR
        isChannelMenu = true
        
        if (prefs.animationsEnabled) {
            binding.sidebarMenu.animate().translationX(targetX).alpha(1f).setDuration(200).start()
            binding.menuScrim.animate().alpha(1f).setDuration(200).start()
        }

        binding.sidebarRecyclerView.apply {
            if (adapter != channelSidebarAdapter) adapter = channelSidebarAdapter
            
            val lm = layoutManager as? LinearLayoutManager
            lm?.scrollToPositionWithOffset(currentChannelIndex, 200)
            
            if (requestFocus) {
                post {
                    val vh = findViewHolderForAdapterPosition(currentChannelIndex)
                    if (vh != null) {
                        vh.itemView.requestFocus()
                    } else {
                        postDelayed({
                            findViewHolderForAdapterPosition(currentChannelIndex)?.itemView?.requestFocus()
                        }, 50)
                    }
                }
            }
        }
    }

    private fun playCurrentChannelInternal() {
        // From sidebar, don't show info as sidebar is already open/closing
        playCurrentChannel(useZapDelay = false, showInfo = false)
    }

    private fun showSidebar(title: String, items: List<SelectionItem>, context: String, animate: Boolean = true, initialFocusId: String? = null, onFocused: ((SelectionItem) -> Unit)? = null, onSelected: (SelectionItem) -> Unit) {
        hideHandler.removeCallbacks(hideRunnable)
        if (sidebarContext != context) hideAllOverlays() // Only hide if context changed to prevent flicker
        
        sidebarContext = context
        binding.sidebarTitle.text = title
        
        // Preserve scroll position if refreshing same context
        val layoutManager = binding.sidebarRecyclerView.layoutManager as? LinearLayoutManager
        val savedState = if (!animate && currentState == MenuState.SIDEBAR) layoutManager?.onSaveInstanceState() else null

        val drawerWidth = 200f * resources.displayMetrics.density
        val targetX = if (binding.drawerPanel.visibility == View.VISIBLE) drawerWidth else 0f

        if (animate && prefs.animationsEnabled) {
            binding.sidebarMenu.translationX = targetX - 100f
            binding.sidebarMenu.alpha = 0f
            binding.sidebarMenu.animate().translationX(targetX).alpha(1f).setDuration(300).start()
            
            binding.menuScrim.alpha = 0f
            binding.menuScrim.animate().alpha(1f).duration = 300
        } else {
            binding.sidebarMenu.translationX = targetX
            binding.sidebarMenu.alpha = 1f
            binding.menuScrim.alpha = 1f
        }
        
        binding.sidebarMenu.visibility = View.VISIBLE
        binding.menuScrim.visibility = View.VISIBLE
        
        currentState = MenuState.SIDEBAR
        isChannelMenu = false
        binding.sidebarRecyclerView.apply {
            val currentAdapter = this.adapter as? GenericSelectionAdapter
            val isRefresh = !animate && sidebarContext == context && currentAdapter != null
            
            if (!isRefresh) {
                this.layoutManager = LinearLayoutManager(this@PlayerActivity)
                this.adapter = GenericSelectionAdapter(items, prefs.themeColor, onFocused) { onSelected(it) }
            } else {
                currentAdapter?.updateItems(items)
                // Critical: Update listeners because we might be in a different sub-menu sharing the same context ID
                currentAdapter?.setOnItemSelectListener { onSelected(it) }
                currentAdapter?.setOnItemFocusListener(onFocused)
            }
            
            if (savedState != null) {
                (this.layoutManager as LinearLayoutManager).onRestoreInstanceState(savedState)
            }
            
            if (initialFocusId != null) {
                val index = items.indexOfFirst { it.id == initialFocusId }
                if (index != -1) {
                    val lm = this.layoutManager as LinearLayoutManager
                    lm.scrollToPositionWithOffset(index, 200)
                    
                    post {
                        val holder = findViewHolderForAdapterPosition(index)
                        if (holder != null) {
                            holder.itemView.requestFocus()
                        } else {
                            postDelayed({
                                findViewHolderForAdapterPosition(index)?.itemView?.requestFocus()
                            }, 50)
                        }
                    }
                } else {
                    requestFocus()
                }
            } else if (savedState == null && !isRefresh) {
                requestFocus()
            }
        }
    }

    private fun setupMatureWarning() {
        binding.btnMatureAccept.setOnClickListener {
            val channel = allChannels.getOrNull(currentChannelIndex)
            if (channel != null) {
                isMatureAcceptedForSession = true
                hideAllOverlays()
                playCurrentChannel()
            }
        }
        
        binding.btnMatureExit.setOnClickListener {
            hideAllOverlays()
            // Optionally: go to previous channel or just stay on black screen
            // Staying on black screen is safer.
        }
    }

    private fun showMatureWarning(channel: ChannelItem) {
        hideAllOverlays()
        binding.matureWarningOverlay.visibility = View.VISIBLE
        currentState = MenuState.MATURE_WARNING
        
        if (prefs.animationsEnabled) {
            binding.matureWarningOverlay.alpha = 0f
            binding.matureWarningOverlay.animate().alpha(1f).setDuration(400).start()
        } else {
            binding.matureWarningOverlay.alpha = 1f
        }
        
        binding.btnMatureAccept.requestFocus()
    }

    internal fun hideAllOverlays() {
        if (currentState == MenuState.NONE) {
            sidebarContext = ""
            tagScrollAnimator?.cancel()
            binding.tagScrollView.scrollTo(0, 0)
            return
        }
        
        tagScrollAnimator?.cancel()
        binding.tagScrollView.scrollTo(0, 0)
        
        val animDuration = 250L
        if (prefs.animationsEnabled) {
            binding.menuScrim.animate().alpha(0f).setDuration(animDuration).withEndAction { binding.menuScrim.visibility = View.GONE }.start()
            
            if (binding.sidebarMenu.visibility == View.VISIBLE) 
                binding.sidebarMenu.animate().translationX(-400f).alpha(0f).setDuration(animDuration).withEndAction { binding.sidebarMenu.visibility = View.GONE }.start()
            
            if (binding.channelInfoOverlay.visibility == View.VISIBLE)
                 binding.channelInfoOverlay.animate().translationY(200.toPx()).alpha(0f).setDuration(animDuration).withEndAction { binding.channelInfoOverlay.visibility = View.GONE }.start()
            
            if (binding.quickMenuOverlay.visibility == View.VISIBLE)
                 binding.quickMenuOverlay.animate().translationY(50f).alpha(0f).setDuration(animDuration).withEndAction { binding.quickMenuOverlay.visibility = View.GONE }.start()
            
            if (binding.drawerPanel.visibility == View.VISIBLE)
                binding.drawerPanel.animate().translationX(-200f * resources.displayMetrics.density).setDuration(animDuration).withEndAction { binding.drawerPanel.visibility = View.GONE }.start()
            
            if (binding.loginPanel.visibility == View.VISIBLE)
                binding.loginPanel.animate().alpha(0f).setDuration(animDuration).withEndAction { binding.loginPanel.visibility = View.GONE }.start()
                
            if (binding.qualityPopup.visibility == View.VISIBLE)
                binding.qualityPopup.animate().alpha(0f).translationY(20f).setDuration(animDuration).withEndAction { binding.qualityPopup.visibility = View.GONE }.start()
            
            if (binding.matureWarningOverlay.visibility == View.VISIBLE)
                binding.matureWarningOverlay.animate().alpha(0f).setDuration(animDuration).withEndAction { binding.matureWarningOverlay.visibility = View.GONE }.start()
        } else {
            binding.channelInfoOverlay.visibility = View.GONE
            binding.quickMenuOverlay.visibility = View.GONE
            binding.sidebarMenu.visibility = View.GONE
            binding.menuScrim.visibility = View.GONE
            binding.drawerPanel.visibility = View.GONE
            binding.loginPanel.visibility = View.GONE
            binding.qualityPopup.visibility = View.GONE
            binding.matureWarningOverlay.visibility = View.GONE
            binding.sidebarMenu.translationX = 0f
        }
        
        uptimeHandler.removeCallbacks(uptimeRunnable)
        currentState = MenuState.NONE
        sidebarContext = ""
        binding.focusSink.requestFocus()
    }
    
    private fun resetHideTimer() {
        hideHandler.removeCallbacks(hideRunnable)
        if (currentState == MenuState.INFO && prefs.infoDelay > 0) {
            hideHandler.postDelayed(hideRunnable, prefs.infoDelay * 1000L)
        }
    }

    private fun toggleQualityMenu() {
        if (currentState == MenuState.QUALITY) {
            hideAllOverlays()
            binding.btnQualityQuick.requestFocus()
            return
        }

        val qList = ivsPlayer?.qualities ?: return
        val currentQ = ivsPlayer?.quality
        val isAuto = ivsPlayer?.isAutoQualityMode == true
        
        val items = mutableListOf<SelectionItem>()
        items.add(SelectionItem("auto", getString(R.string.auto), isAuto))
        qList.toList().sortedByDescending { it.height }.forEach { q ->
            items.add(SelectionItem(q.name, q.name.uppercase(), !isAuto && currentQ?.name == q.name, q))
        }

        binding.qualityRecyclerView.apply {
            layoutManager = LinearLayoutManager(this@PlayerActivity)
            adapter = GenericSelectionAdapter(items, prefs.themeColor) { item ->
                if (item.id == "auto") ivsPlayer?.setAutoQualityMode(true)
                else (item.payload as? Quality)?.let { ivsPlayer?.setQuality(it) }
                hideAllOverlays()
                binding.btnQualityQuick.requestFocus()
            }
        }

        binding.qualityPopup.visibility = View.VISIBLE
        if (prefs.animationsEnabled) {
            binding.qualityPopup.alpha = 0f
            binding.qualityPopup.translationY = 20f
            binding.qualityPopup.animate().alpha(1f).translationY(0f).setDuration(200).start()
        } else {
            binding.qualityPopup.alpha = 1f
            binding.qualityPopup.translationY = 0f
        }

        currentState = MenuState.QUALITY
        
        binding.qualityRecyclerView.post {
            val lm = binding.qualityRecyclerView.layoutManager as? LinearLayoutManager
            lm?.scrollToPositionWithOffset(0, 0)
            binding.qualityRecyclerView.post {
                val vh = binding.qualityRecyclerView.findViewHolderForAdapterPosition(0)
                if (vh != null) {
                    vh.itemView.requestFocus()
                } else {
                    binding.qualityRecyclerView.postDelayed({
                        binding.qualityRecyclerView.findViewHolderForAdapterPosition(0)?.itemView?.requestFocus()
                    }, 50)
                }
            }
        }
    }

    private fun showSettingsSidebar() {
        val items = listOf(
            SelectionItem("cat_general", getString(R.string.settings_category_general)),
            SelectionItem("cat_player", getString(R.string.settings_category_player)),
            SelectionItem("cat_appearance", getString(R.string.settings_category_appearance)),
            // Direct items for faster access
            SelectionItem("update_channel", getString(R.string.setting_update_channel)),
            SelectionItem("about", getString(R.string.setting_about))
        )
        
        showSidebar(getString(R.string.settings), items, "settings", animate = true, initialFocusId = lastSettingsFocusId) { item ->
            lastSettingsFocusId = item.id
            when (item.id) {
                "cat_general" -> showGeneralSettings()
                "cat_player" -> showPlayerSettings()
                "cat_appearance" -> showAppearanceSettings()
                "update_channel" -> { lastSubSettingsFocusId = null; showUpdateChannelSidebar() }
                "about" -> { lastSubSettingsFocusId = null; showAboutSidebar() }
            }
        }
    }

    private fun showGeneralSettings() {
        val items = listOf(
            SelectionItem("lang", getString(R.string.setting_language)),
            SelectionItem("filter", getString(R.string.setting_filter)),
            SelectionItem("refresh", getString(R.string.setting_refresh_interval))
        )
        
        showSidebar(getString(R.string.settings_category_general), items, "settings_sub") { item ->
            when (item.id) {
                "lang" -> { lastSubSettingsFocusId = null; showLanguageSidebar() }
                "filter" -> { lastSubSettingsFocusId = null; showFilterSidebar() }
                "refresh" -> { lastSubSettingsFocusId = null; showRefreshSidebar() }
            }
        }
    }

    private fun showPlayerSettings() {
        val items = mutableListOf(
            SelectionItem("engine", getString(R.string.setting_player_engine)),
            SelectionItem("catch_up", getString(R.string.setting_catch_up)),
            SelectionItem("zap", getString(R.string.setting_zap_delay))
        )
        
        if (!isTvDevice()) {
            items.add(SelectionItem("background", getString(R.string.setting_background_audio)))
            items.add(SelectionItem("auto_pip", getString(R.string.setting_auto_pip)))
        }
        
        showSidebar(getString(R.string.settings_category_player), items, "settings_sub") { item ->
            when (item.id) {
                "engine" -> { lastSubSettingsFocusId = null; showPlayerEngineSidebar() }
                "catch_up" -> { lastSubSettingsFocusId = null; showCatchUpSidebar() }
                "zap" -> { lastSubSettingsFocusId = null; showZapDelaySidebar() }
                "background" -> { lastSubSettingsFocusId = null; showBackgroundAudioSidebar() }
                "auto_pip" -> { lastSubSettingsFocusId = null; showAutoPipSidebar() }
            }
        }
    }

    private fun showCatchUpSidebar() {
        val items = listOf(
            SelectionItem("off", getString(R.string.catch_up_off), isSelected = prefs.catchUpMode == "off"),
            SelectionItem("low", getString(R.string.catch_up_low), isSelected = prefs.catchUpMode == "low"),
            SelectionItem("high", getString(R.string.catch_up_high), isSelected = prefs.catchUpMode == "high")
        )
        
        showSidebar(getString(R.string.sidebar_title_catch_up), items, "settings_sub") { item ->
            prefs.catchUpMode = item.id
            // Update IVS immediately
            if (item.id == "off") {
                ivsPlayer?.playbackRate = 1.0f
            }
            showCatchUpSidebar()
        }
    }

    private fun showBackgroundAudioSidebar() {
        val items = listOf(
            SelectionItem("on", getString(R.string.yes), isSelected = prefs.backgroundAudioEnabled),
            SelectionItem("off", getString(R.string.no), isSelected = !prefs.backgroundAudioEnabled)
        )
        
        showSidebar(getString(R.string.sidebar_title_background_audio), items, "settings_background") { item ->
            prefs.backgroundAudioEnabled = item.id == "on"
            (binding.sidebarRecyclerView.adapter as? GenericSelectionAdapter)?.updateSingleSelection(item.id)
        }
    }

    private fun showAutoPipSidebar() {
        val items = listOf(
            SelectionItem("on", getString(R.string.pip_automatic), isSelected = prefs.autoPipEnabled),
            SelectionItem("off", getString(R.string.pip_manual), isSelected = !prefs.autoPipEnabled)
        )
        
        showSidebar(getString(R.string.sidebar_title_auto_pip), items, "settings_auto_pip") { item ->
            prefs.autoPipEnabled = item.id == "on"
            (binding.sidebarRecyclerView.adapter as? GenericSelectionAdapter)?.updateSingleSelection(item.id)
        }
    }

    private fun showPlayerEngineSidebar() {
        val currentEngine = prefs.playerEngine
        val items = listOf(
            SelectionItem("amazon_ivs", "Amazon IVS (Native/Low-Latency)", isSelected = currentEngine == "amazon_ivs"),
            SelectionItem("exo", "ExoPlayer (Media3 - Alternative)", isSelected = currentEngine == "exo")
        )
        
        showSidebar(getString(R.string.sidebar_title_engine), items, "settings_engine") { item ->
            if (prefs.playerEngine != item.id) {
                prefs.playerEngine = item.id
                // Re-initialize players or recreate
                recreate()
            }
        }
    }

    private fun showAppearanceSettings() {
        val items = listOf(
            SelectionItem("theme", getString(R.string.setting_theme)),
            SelectionItem("trans", getString(R.string.setting_transparency)),
            SelectionItem("delay", getString(R.string.setting_info_delay)),
            SelectionItem("anim", getString(R.string.setting_animations), prefs.animationsEnabled, showCheckbox = true)
        )
        
        showSidebar(getString(R.string.settings_category_appearance), items, "settings_sub") { item ->
            when (item.id) {
                "theme" -> { lastSubSettingsFocusId = null; showThemeSidebar() }
                "trans" -> { lastSubSettingsFocusId = null; showTransparencySidebar() }
                "delay" -> { lastSubSettingsFocusId = null; showDelaySidebar() }
                "anim" -> {
                    prefs.animationsEnabled = !prefs.animationsEnabled
                    (binding.sidebarRecyclerView.adapter as? GenericSelectionAdapter)?.toggleItemSelection(item.id)
                }
            }
        }
    }

    private fun showAboutSidebar() {
        val version = BuildConfig.VERSION_NAME
        val items = listOf(
            SelectionItem("app_name", getString(R.string.app_name), false),
            SelectionItem("version", getString(R.string.app_version_label, version), false),
            SelectionItem("check_update", getString(R.string.check_update), false),
            SelectionItem("dev", getString(R.string.developer_name), false),
            SelectionItem("github", getString(R.string.visit_github), false),
            SelectionItem("coffee", getString(R.string.support_developer), false)
        )
        // Pass false to animate to keep same context without flicker
        showSidebar(getString(R.string.setting_about), items, "settings_sub", animate = sidebarContext != "settings_sub", initialFocusId = lastSubSettingsFocusId) { item ->
            lastSubSettingsFocusId = item.id
            if (item.id == "github") {
                val url = getString(R.string.github_url)
                val intent = android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(url))
                startActivity(intent)
            } else if (item.id == "coffee") {
                val url = "https://buymeacoffee.com/xacnio"
                val intent = android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(url))
                startActivity(intent)
            } else if (item.id == "version" || item.id == "check_update") {
                checkForUpdates(manual = true)
            }
        }
    }
    private fun showRefreshSidebar() {
        val options = listOf(
            -1 to getString(R.string.only_manual),
            1 to getString(R.string.x_minutes, 1),
            2 to getString(R.string.x_minutes, 2),
            5 to getString(R.string.x_minutes, 5),
            10 to getString(R.string.x_minutes, 10)
        )
        val items = options.map { (min, label) -> 
            SelectionItem(min.toString(), label, prefs.autoRefreshInterval == min, min) 
        }
        showSidebar(getString(R.string.sidebar_title_refresh), items, "settings_sub", animate = sidebarContext != "settings_sub", initialFocusId = lastSubSettingsFocusId ?: items.find { it.isSelected }?.id) { item ->
            lastSubSettingsFocusId = item.id
            prefs.autoRefreshInterval = item.payload as Int
            startRefreshTimer()
            (binding.sidebarRecyclerView.adapter as? GenericSelectionAdapter)?.updateSingleSelection(item.id)
        }
    }
    private fun showThemeSidebar(keepFocusId: String? = null) {
        if (sidebarContext != "settings_theme") {
            originalThemeColor = prefs.themeColor
        }
        
        val colors = listOf(
            getString(R.string.kciktv_cyan) to 0xFF00F2FF.toInt(),
            getString(R.string.premium_emerald) to 0xFF10B981.toInt(),
            getString(R.string.premium_sapphire) to 0xFF3B82F6.toInt(),
            getString(R.string.premium_ruby) to 0xFFEF4444.toInt(),
            getString(R.string.premium_gold) to 0xFFF59E0B.toInt(),
            getString(R.string.premium_violet) to 0xFF8B5CF6.toInt(),
            getString(R.string.premium_teal) to 0xFF14B8A6.toInt(),
            getString(R.string.premium_rose) to 0xFFF43F5E.toInt()
        )
        val items = colors.map { (name, color) -> SelectionItem(color.toString(), name, prefs.themeColor == color, color) }
        showSidebar(
            getString(R.string.sidebar_title_theme), 
            items, 
            "settings_theme", 
            animate = sidebarContext != "settings_theme", 
            initialFocusId = keepFocusId ?: lastSubSettingsFocusId ?: items.find { it.isSelected }?.id,
            onFocused = { item ->
                val newColor = item.payload as Int
                prefs.themeColor = newColor
                applySettings()
            }
        ) { item ->
            lastSubSettingsFocusId = item.id
            val finalColor = item.payload as Int
            prefs.themeColor = finalColor
            originalThemeColor = finalColor // Mark as selected
            applySettings()
            (binding.sidebarRecyclerView.adapter as? GenericSelectionAdapter)?.updateSingleSelection(item.id)
        }
    }


    private fun showLanguageSidebar() {
        val items = listOf(
            SelectionItem("system", getString(R.string.system_default), prefs.languageRaw == "system"),
            SelectionItem("tr", "Türkçe", prefs.languageRaw == "tr"),
            SelectionItem("en", "English", prefs.languageRaw == "en")
        )
        showSidebar(getString(R.string.sidebar_title_language), items, "settings_sub", animate = sidebarContext != "settings_sub", initialFocusId = lastSubSettingsFocusId ?: items.find { it.isSelected }?.id) { item -> 
            lastSubSettingsFocusId = item.id
            setLanguage(item.id)
        }
    }
    private fun showDelaySidebar() {
        val delays = listOf(3, 5, 10, 20, 0)
        val items = delays.map { SelectionItem(it.toString(), if (it == 0) getString(R.string.only_manual) else getString(R.string.x_seconds, it), prefs.infoDelay == it, it) }
        showSidebar(getString(R.string.sidebar_title_delay), items, "settings_sub", animate = sidebarContext != "settings_sub", initialFocusId = lastSubSettingsFocusId ?: items.find { it.isSelected }?.id) { item -> 
            lastSubSettingsFocusId = item.id
            prefs.infoDelay = item.payload as Int
            (binding.sidebarRecyclerView.adapter as? GenericSelectionAdapter)?.updateSingleSelection(item.id)
        }
    }
    private fun showTransparencySidebar() {
        val levels = listOf(50, 60, 70, 80, 90, 100)
        val items = levels.map { SelectionItem(it.toString(), "%$it", prefs.infoTransparency == it, it) }
        showSidebar(getString(R.string.sidebar_title_transparency), items, "settings_sub", animate = sidebarContext != "settings_sub", initialFocusId = lastSubSettingsFocusId ?: items.find { it.isSelected }?.id) { item -> 
            lastSubSettingsFocusId = item.id
            prefs.infoTransparency = item.payload as Int
            applySettings()
            (binding.sidebarRecyclerView.adapter as? GenericSelectionAdapter)?.updateSingleSelection(item.id)
        }
    }
    private fun showZapDelaySidebar() {
        val current = prefs.zapDelay
        val items = listOf(
            SelectionItem("0", getString(R.string.instant_ms), current == 0),
            SelectionItem("300", getString(R.string.fast_ms), current == 300),
            SelectionItem("700", getString(R.string.normal_ms), current == 700),
            SelectionItem("1200", getString(R.string.slow_ms), current == 1200)
        )
        showSidebar(getString(R.string.sidebar_title_zap), items, "settings_sub", animate = sidebarContext != "settings_sub", initialFocusId = lastSubSettingsFocusId ?: items.find { it.isSelected }?.id) { item ->
            lastSubSettingsFocusId = item.id
            prefs.zapDelay = item.id.toInt()
            (binding.sidebarRecyclerView.adapter as? GenericSelectionAdapter)?.updateSingleSelection(item.id)
        }
    }

    private fun showUpdateChannelSidebar() {
        val items = listOf(
            SelectionItem("stable", getString(R.string.stable), prefs.updateChannel == "stable"),
            SelectionItem("beta", getString(R.string.beta), prefs.updateChannel == "beta")
        )
        showSidebar(getString(R.string.sidebar_title_update_channel), items, "settings_sub", animate = sidebarContext != "settings_sub", initialFocusId = lastSubSettingsFocusId ?: items.find { it.isSelected }?.id) { item ->
            lastSubSettingsFocusId = item.id
            prefs.updateChannel = item.id
            (binding.sidebarRecyclerView.adapter as? GenericSelectionAdapter)?.updateSingleSelection(item.id)
        }
    }

    private fun checkForUpdates(manual: Boolean = false) {
        if (manual) showError(getString(R.string.checking_updates))
        
        lifecycleScope.launch {
            updateRepository.getLatestRelease(prefs.updateChannel).onSuccess { release ->
                if (release != null) {
                    val currentVersion = BuildConfig.VERSION_NAME
                    val newVersion = release.tagName.replace("v", "")
                    
                    if (newVersion != currentVersion) {
                        if (manual) hideError()
                        showSidebar(
                            getString(R.string.update_available, release.tagName),
                            listOf(
                                SelectionItem("update", getString(R.string.update_now)),
                                SelectionItem("cancel", getString(R.string.cancel))
                            ),
                            "update_prompt"
                        ) { item ->
                            if (item.id == "update") {
                                downloadAndInstallApk(release)
                            } else {
                                hideAllOverlays()
                            }
                        }
                    } else if (manual) {
                        showError(getString(R.string.up_to_date))
                        Handler(Looper.getMainLooper()).postDelayed({ hideError() }, 2000)
                    }
                }
            }.onFailure {
                if (manual) {
                    showError("Update check failed: ${it.message}")
                    Handler(Looper.getMainLooper()).postDelayed({ hideError() }, 2000)
                }
            }
        }
    }

    private fun downloadAndInstallApk(release: GithubRelease) {
        val asset = release.assets.find { it.name.endsWith(".apk") } ?: return
        
        showError(getString(R.string.downloading))
        
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val client = OkHttpClient()
                val request = Request.Builder().url(asset.downloadUrl).build()
                val response = client.newCall(request).execute()
                
                if (response.isSuccessful) {
                    val apkFile = File(cacheDir, "update.apk")
                    val fos = FileOutputStream(apkFile)
                    fos.write(response.body?.bytes() ?: return@launch)
                    fos.close()
                    
                    withContext(Dispatchers.Main) {
                        hideError()
                        installApk(apkFile)
                    }
                } else {
                    withContext(Dispatchers.Main) { showError(getString(R.string.download_failed)) }
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) { showError(getString(R.string.download_failed) + ": ${e.message}") }
            }
        }
    }

    private fun installApk(file: File) {
        val uri = FileProvider.getUriForFile(this, "${packageName}.fileprovider", file)
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        startActivity(intent)
    }

    private fun applyLocale() {
        val lang = prefs.language
        Log.d("PlayerActivity", "Applying locale: $lang (Raw: ${prefs.languageRaw})")
        val locale = java.util.Locale(lang)
        java.util.Locale.setDefault(locale)
        
        val resources = resources
        val config = resources.configuration
        config.setLocale(locale)
        
        // Update both activity and application context resources
        @Suppress("DEPRECATION")
        resources.updateConfiguration(config, resources.displayMetrics)
        @Suppress("DEPRECATION")
        applicationContext.resources.updateConfiguration(config, resources.displayMetrics)
    }

    private fun setLanguage(lang: String) {
        prefs.language = lang
        applyLocale()
        recreate()
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        // Isolate Drawer, Login, and Quality states from global interactions
        if (currentState == MenuState.DRAWER || currentState == MenuState.LOGIN || currentState == MenuState.QUALITY || currentState == MenuState.SEARCH || currentState == MenuState.MATURE_WARNING) {
            when (keyCode) {
                KeyEvent.KEYCODE_DPAD_LEFT -> {
                    if (currentState == MenuState.DRAWER || currentState == MenuState.QUALITY) return true // Trap/Ignore
                    return super.onKeyDown(keyCode, event)
                }
                KeyEvent.KEYCODE_DPAD_RIGHT -> {
                    if (currentState == MenuState.DRAWER || currentState == MenuState.QUALITY || currentState == MenuState.SEARCH) {
                        if (currentState == MenuState.DRAWER) {
                            if (binding.sidebarMenu.visibility == View.VISIBLE) {
                                hideDrawer()
                            } else {
                                showChannelSidebar()
                            }
                        }
                        return true
                    }
                    return super.onKeyDown(keyCode, event)
                }
                KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN -> {
                    val root = when (currentState) {
                        MenuState.DRAWER -> binding.drawerPanel
                        MenuState.LOGIN -> binding.loginPanel
                        MenuState.QUALITY -> binding.qualityPopup
                        MenuState.SEARCH -> binding.searchPanel
                        MenuState.MATURE_WARNING -> binding.matureWarningOverlay
                        else -> binding.root
                    }
                    val focused = root.findFocus()
                    if (focused != null) {
                        val direction = if (keyCode == KeyEvent.KEYCODE_DPAD_UP) View.FOCUS_UP else View.FOCUS_DOWN
                        val nextFocus = android.view.FocusFinder.getInstance().findNextFocus(
                            root, focused, direction
                        )
                        if (nextFocus != null) {
                            nextFocus.requestFocus()
                            return true // Consume event: successfully moved focus within panel
                        } else {
                            return true // Consume event: trap focus at boundaries
                        }
                    }
                    return super.onKeyDown(keyCode, event)
                }
                KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> {
                    super.onKeyDown(keyCode, event)
                }
                KeyEvent.KEYCODE_BACK, KeyEvent.KEYCODE_ESCAPE -> {
                    if (binding.drawerLanguageContent.visibility == View.VISIBLE) {
                        hideLanguageFilter()
                    }
                    else if (currentState == MenuState.DRAWER) hideDrawer()
                    else if (currentState == MenuState.LOGIN) hideLoginPanel()
                    else if (currentState == MenuState.SEARCH) hideSearchPanel()
                    else if (currentState == MenuState.MATURE_WARNING) hideAllOverlays()
                    else if (currentState == MenuState.QUALITY) {
                        // Close quality popup and return to quick menu state
                        if (prefs.animationsEnabled) {
                            binding.qualityPopup.animate().alpha(0f).translationY(20f).setDuration(200).withEndAction { 
                                binding.qualityPopup.visibility = View.GONE 
                            }.start()
                        } else {
                            binding.qualityPopup.visibility = View.GONE
                        }
                        currentState = MenuState.QUICK_MENU
                        binding.btnQualityQuick.requestFocus()
                    }
                    return true
                }
                // Block all other keys (Channel switch, numbers, volume etc)
                KeyEvent.KEYCODE_CHANNEL_UP, KeyEvent.KEYCODE_CHANNEL_DOWN,
                KeyEvent.KEYCODE_PAGE_UP, KeyEvent.KEYCODE_PAGE_DOWN -> return true
            }
        }

        if (currentState == MenuState.SIDEBAR) {
            val layoutManager = binding.sidebarRecyclerView.layoutManager as? LinearLayoutManager
            val focusView = binding.sidebarRecyclerView.focusedChild
            if (focusView != null && layoutManager != null) {
                val position = layoutManager.getPosition(focusView)
                val totalCount = binding.sidebarRecyclerView.adapter?.itemCount ?: 0
                val repeatCount = event?.repeatCount ?: 0
                
                when (keyCode) {
                    KeyEvent.KEYCODE_DPAD_LEFT -> {
                        if (!isChannelMenu)
                            return true
                    }
                    KeyEvent.KEYCODE_DPAD_UP -> {
                        if (position == 0) {
                            // At the beginning: only wrap-around on first press
                            if (repeatCount == 0) {
                                binding.sidebarRecyclerView.scrollToPosition(totalCount - 1)
                                binding.sidebarRecyclerView.post { 
                                    val vh = binding.sidebarRecyclerView.findViewHolderForAdapterPosition(totalCount - 1)
                                    if (vh != null) {
                                        vh.itemView.requestFocus()
                                    } else {
                                        binding.sidebarRecyclerView.postDelayed({
                                            binding.sidebarRecyclerView.findViewHolderForAdapterPosition(totalCount - 1)?.itemView?.requestFocus()
                                        }, 50)
                                    }
                                }
                            }
                            // Even if held down, consume event (prevent sticking)
                            return true
                        } else {
                            // Normal up movement
                            val newPos = position - 1
                            binding.sidebarRecyclerView.scrollToPosition(newPos)
                            binding.sidebarRecyclerView.post { 
                                val vh = binding.sidebarRecyclerView.findViewHolderForAdapterPosition(newPos)
                                if (vh != null) {
                                    vh.itemView.requestFocus()
                                } else {
                                    binding.sidebarRecyclerView.postDelayed({
                                        binding.sidebarRecyclerView.findViewHolderForAdapterPosition(newPos)?.itemView?.requestFocus()
                                    }, 50)
                                }
                            }
                            return true
                        }
                    }
                    KeyEvent.KEYCODE_DPAD_DOWN -> {
                        if (position == totalCount - 1) {
                            // At the end: only wrap-around on first press
                            if (repeatCount == 0) {
                                binding.sidebarRecyclerView.scrollToPosition(0)
                                binding.sidebarRecyclerView.post { 
                                    val vh = binding.sidebarRecyclerView.findViewHolderForAdapterPosition(0)
                                    if (vh != null) {
                                        vh.itemView.requestFocus()
                                    } else {
                                        binding.sidebarRecyclerView.postDelayed({
                                            binding.sidebarRecyclerView.findViewHolderForAdapterPosition(0)?.itemView?.requestFocus()
                                        }, 50)
                                    }
                                }
                            }
                            // Even if held down, consume event (prevent sticking)
                            return true
                        } else {
                            // Normal down movement
                            val newPos = position + 1
                            binding.sidebarRecyclerView.scrollToPosition(newPos)
                            binding.sidebarRecyclerView.post { 
                                val vh = binding.sidebarRecyclerView.findViewHolderForAdapterPosition(newPos)
                                if (vh != null) {
                                    vh.itemView.requestFocus()
                                } else {
                                    binding.sidebarRecyclerView.postDelayed({
                                        binding.sidebarRecyclerView.findViewHolderForAdapterPosition(newPos)?.itemView?.requestFocus()
                                    }, 50)
                                }
                            }
                            return true
                        }
                    }
                }
            }
            if (keyCode == KeyEvent.KEYCODE_DPAD_LEFT) {
                if (sidebarContext == "channels") {
                    showDrawer()
                }
                return true
            }
        }
        when (keyCode) {
            KeyEvent.KEYCODE_DPAD_UP -> {
                if (currentState == MenuState.INFO) {
                    nextChannel()
                    return true
                }
                if (currentState == MenuState.NONE) {
                    nextChannel()
                    return true
                }
                // Trap up in Quick Menu to prevent focus escaping
                if (currentState == MenuState.QUICK_MENU) {
                    return true
                }
            }
            KeyEvent.KEYCODE_DPAD_DOWN -> {
                if (currentState == MenuState.INFO) {
                    previousChannel()
                    return true
                }
                if (currentState == MenuState.NONE) {
                    previousChannel()
                    return true
                }
                // Trap down in Quick Menu to prevent focus escaping
                if (currentState == MenuState.QUICK_MENU) {
                    return true
                }
            }
            KeyEvent.KEYCODE_DPAD_RIGHT -> {
                if (currentState == MenuState.NONE || currentState == MenuState.INFO) {
                    if (!isChatVisible) showChat() else hideChat()
                    return true
                }
                // In Quick Menu: allow right navigation between buttons, trap only at last visible button
                if (currentState == MenuState.QUICK_MENU) {
                    val focused = currentFocus
                    val isPipVisible = binding.btnPipQuick.visibility == View.VISIBLE
                    // Trap at PIP if visible, otherwise trap at Stats
                    val lastButton = if (isPipVisible) binding.btnPipQuick else binding.btnStatsQuick
                    if (focused == lastButton) {
                        return true // At last button, trap
                    }
                    // Otherwise let default focus handling move to next button
                }
            }
            KeyEvent.KEYCODE_DPAD_LEFT -> {
                if (currentState == MenuState.NONE || currentState == MenuState.INFO) {
                    showChannelSidebar()
                    return true
                }
                // In Quick Menu: allow left navigation between buttons, trap only at first visible button
                if (currentState == MenuState.QUICK_MENU) {
                    val focused = currentFocus
                    val isQualityVisible = binding.btnQualityQuick.visibility == View.VISIBLE
                    // Trap at Quality if visible, otherwise trap at Refresh
                    val firstButton = if (isQualityVisible) binding.btnQualityQuick else binding.btnRefreshQuick
                    if (focused == firstButton) {
                        return true // At first button, trap
                    }
                    // Otherwise let default focus handling move to previous button
                }
            }
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> {
                if (currentState == MenuState.NONE) {
                    showInfoOverlay()
                    return true
                } else if (currentState == MenuState.INFO) {
                    showQuickMenu() // OK while info is visible opens Quick Menu
                    return true
                } else {
                    // In menus, simulate click on focused item (useful for mobile touch)
                    val focused = currentFocus
                    if (focused != null && focused.isFocusable) {
                        focused.performClick()
                        return true
                    }
                }
            }
            // CH+ / CH- and Media Next/Previous buttons
            KeyEvent.KEYCODE_CHANNEL_UP, KeyEvent.KEYCODE_PAGE_UP, KeyEvent.KEYCODE_MEDIA_NEXT -> {
                nextChannel()
                return true
            }
            KeyEvent.KEYCODE_CHANNEL_DOWN, KeyEvent.KEYCODE_PAGE_DOWN, KeyEvent.KEYCODE_MEDIA_PREVIOUS -> {
                previousChannel()
                return true
            }
            // Numeric keys (0-9)
            in KeyEvent.KEYCODE_0..KeyEvent.KEYCODE_9 -> {
                val digit = keyCode - KeyEvent.KEYCODE_0
                handleChannelNumberInput(digit)
                return true
            }
            KeyEvent.KEYCODE_NUMPAD_0, KeyEvent.KEYCODE_NUMPAD_1, KeyEvent.KEYCODE_NUMPAD_2,
            KeyEvent.KEYCODE_NUMPAD_3, KeyEvent.KEYCODE_NUMPAD_4, KeyEvent.KEYCODE_NUMPAD_5,
            KeyEvent.KEYCODE_NUMPAD_6, KeyEvent.KEYCODE_NUMPAD_7, KeyEvent.KEYCODE_NUMPAD_8,
            KeyEvent.KEYCODE_NUMPAD_9 -> {
                val digit = keyCode - KeyEvent.KEYCODE_NUMPAD_0
                handleChannelNumberInput(digit)
                return true
            }

            // Info / Guide / Menu buttons (toggle)
            KeyEvent.KEYCODE_INFO, KeyEvent.KEYCODE_GUIDE, KeyEvent.KEYCODE_MENU, KeyEvent.KEYCODE_PROG_RED -> {
                if (currentState == MenuState.INFO) hideAllOverlays() else showInfoOverlay()
                return true
            }
            KeyEvent.KEYCODE_BACK, KeyEvent.KEYCODE_ESCAPE -> {
                if (isChatVisible) {
                    hideChat()
                    return true
                }
                when (currentState) {
                    MenuState.SIDEBAR -> { 
                        if (sidebarContext == "settings_sub" || sidebarContext == "settings_theme" || 
                            sidebarContext == "settings_engine" || sidebarContext == "settings_background" || 
                            sidebarContext == "settings_auto_pip") {
                            
                            if (sidebarContext == "settings_theme") {
                                prefs.themeColor = originalThemeColor
                                applySettings()
                                showAppearanceSettings()
                            } else if (sidebarContext == "settings_engine" || sidebarContext == "settings_background" || 
                                     sidebarContext == "settings_auto_pip") {
                                showPlayerSettings()
                            } else {
                                showSettingsSidebar()
                            }
                        } else if (sidebarContext == "settings") {
                            showChannelSidebar(requestFocus = false)
                            showDrawer(focusType = "settings") // Drawer focus wins
                        } else {
                            hideAllOverlays()
                        }
                        return true 
                    }
                    MenuState.QUICK_MENU -> { hideAllOverlays(); return true }
                    MenuState.INFO -> { hideAllOverlays(); return true }
                    MenuState.NONE -> {
                        if (backPressedTime + 2000 > System.currentTimeMillis()) finishAffinity()
                        else { 
                            Toast.makeText(this, getString(R.string.press_again_to_exit), Toast.LENGTH_SHORT).show()
                            backPressedTime = System.currentTimeMillis() 
                        }
                        return true
                    }
                    MenuState.LOGIN -> {
                        hideLoginPanel()
                        return true
                    }
                    MenuState.SEARCH -> {
                        showChannelSidebar(requestFocus = false)
                        showDrawer()
                        return true
                    }
                    MenuState.QUALITY -> {
                        showDrawer()
                        return true
                    }
                    else -> { hideAllOverlays(); return true }
                }
            }
        }
        return super.onKeyDown(keyCode, event)
    }

    override fun onGenericMotionEvent(event: android.view.MotionEvent): Boolean {
        // Handle right click as BACK (ESC)
        if (event.action == android.view.MotionEvent.ACTION_BUTTON_PRESS &&
            event.buttonState == android.view.MotionEvent.BUTTON_SECONDARY) {
            // Manually trigger onKeyDown for BACK state
            return onKeyDown(KeyEvent.KEYCODE_BACK, null)
        }
        return super.onGenericMotionEvent(event)
    }

    private fun nextChannel() { 
        if (currentListMode == ListMode.SEARCH) return
        currentChannelIndex = (currentChannelIndex + 1) % allChannels.size; 
        playCurrentChannel(useZapDelay = true) 
    }
    private fun previousChannel() { 
        if (currentListMode == ListMode.SEARCH) return
        currentChannelIndex = if (currentChannelIndex > 0) currentChannelIndex - 1 else allChannels.size - 1; 
        playCurrentChannel(useZapDelay = true) 
    }
    private fun refreshActiveChannelDetails() {
        if (allChannels.isEmpty() || currentChannelIndex >= allChannels.size) return
        val activeChannel = allChannels[currentChannelIndex]
        lifecycleScope.launch {
            repository.getLiveStreamDetails(activeChannel.slug).onSuccess { details: LivestreamResponse ->
                // Check safety again inside coroutine as list might have cleared during API call
                if (allChannels.isEmpty() || currentChannelIndex >= allChannels.size) return@onSuccess
                
                // Use details.viewers if available, otherwise keep current count
                val viewers = details.viewers ?: activeChannel.viewerCount
                binding.viewerCount.text = formatViewerCount(viewers)
                binding.streamTitle.text = details.sessionTitle ?: activeChannel.title
                
                // Update underlying data list
                val newStartTime = details.createdAt?.let { parseIsoDate(it) } ?: activeChannel.startTimeMillis
                val updatedChannel = activeChannel.copy(
                    viewerCount = viewers,
                    title = details.sessionTitle ?: activeChannel.title,
                    startTimeMillis = newStartTime
                )
                allChannels[currentChannelIndex] = updatedChannel
                
                // Update UI elements directly for current channel
                binding.viewerCount.text = formatViewerCount(viewers)
                binding.streamTitle.text = updatedChannel.title
                
                // Parse created_at for uptime
                details.createdAt?.let { dateStr ->
                    streamCreatedAtMillis = parseIsoDate(dateStr)
                }

                // Re-enable Marquee
                binding.streamTitle.isSelected = true
            }
        }
    }

    private fun refreshGlobalList() {
        if (allChannels.isEmpty()) return
        
        // Save the slug of the currently watched channel
        val currentSlug = allChannels.getOrNull(currentChannelIndex)?.slug
        
        val languages = prefs.streamLanguages.toList().takeIf { it.isNotEmpty() }
        lifecycleScope.launch {
            val result = if (currentListMode == ListMode.FOLLOWING && prefs.authToken != null) {
                repository.getFollowingLiveStreams(prefs.authToken!!)
            } else if (prefs.globalSortMode == "featured") {
                repository.getFeaturedStreams(languages)
            } else {
                repository.getFilteredLiveStreams(languages, null, prefs.globalSortMode)
            }

            result.onSuccess { data: dev.xacnio.kciktv.data.repository.ChannelListData ->
                val filtered = data.channels.filter { !prefs.isCategoryBlocked(it.categoryName) }
                if (filtered.isNotEmpty()) {
                    // Check if the watched channel is in the new data
                    val newIndex = if (currentSlug != null) {
                        filtered.indexOfFirst { it.slug == currentSlug }
                    } else -1
                    
                    // CRITICAL FIX: 
                    // If the watched channel is NOT on the first page and we have already loaded more (paginated)
                    // channels, we don't reset the list completely and throw the user to the beginning.
                    if (newIndex == -1 && allChannels.size > filtered.size) {
                        Log.d("PlayerActivity", "Active channel not in first page, skipping global list reset to preserve position.")
                        return@onSuccess
                    }

                    // Update the list completely
                    allChannels.clear()
                    allChannels.addAll(filtered)
                    
                    if (newIndex != -1) {
                        currentChannelIndex = newIndex
                        val currentChannel = allChannels[currentChannelIndex]
                        
                        // Update UI
                        binding.viewerCount.text = formatViewerCount(currentChannel.viewerCount)
                        binding.streamTitle.text = currentChannel.title
                        binding.categoryName.text = currentChannel.categoryName ?: getString(R.string.live_stream)
                        binding.channelNumber.text = String.format("%02d", currentChannelIndex + 1)
                    } else {
                        // Find first safe channel (non-mature)
                        val safeIndex = allChannels.indexOfFirst { !it.isMature }
                        currentChannelIndex = if (safeIndex != -1) safeIndex else 0
                        
                        if (allChannels.isNotEmpty()) {
                            playCurrentChannel()
                        }
                    }
                    
                    // Update adapter
                    channelSidebarAdapter?.replaceChannels(allChannels, currentChannelIndex)

                    // If sidebar is visible (e.g. user just switched list mode), shift focus to the new channel
                    if (binding.sidebarMenu.visibility == View.VISIBLE && allChannels.isNotEmpty()) {
                        binding.sidebarRecyclerView.post {
                            val lm = binding.sidebarRecyclerView.layoutManager as? androidx.recyclerview.widget.LinearLayoutManager
                            lm?.scrollToPositionWithOffset(currentChannelIndex, 200)
                            
                            binding.sidebarRecyclerView.post {
                                val vh = binding.sidebarRecyclerView.findViewHolderForAdapterPosition(currentChannelIndex)
                                if (vh != null) {
                                    vh.itemView.requestFocus()
                                } else {
                                    // Retry if viewholder not ready
                                    binding.sidebarRecyclerView.postDelayed({
                                        binding.sidebarRecyclerView.findViewHolderForAdapterPosition(currentChannelIndex)?.itemView?.requestFocus()
                                    }, 100)
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    private fun startRefreshTimer() {
        refreshHandler.removeCallbacks(activeChannelRefreshRunnable)
        refreshHandler.removeCallbacks(globalListRefreshRunnable)
        
        // Always refresh active channel every 30s while player is active
        refreshHandler.postDelayed(activeChannelRefreshRunnable, 30000L)
        
        // Refresh list based on settings
        if (prefs.autoRefreshInterval != -1) {
            val interval = prefs.autoRefreshInterval * 60000L
            refreshHandler.postDelayed(globalListRefreshRunnable, interval)
        }
    }

    private fun showError(msg: String) {
        binding.errorPanel.visibility = View.VISIBLE
        binding.errorText.text = msg
    }
    
    private fun hideError() {
        binding.errorPanel.visibility = View.GONE
    }
    
    private fun scheduleRetry() {
        // Cancel previous retry
        retryHandler.removeCallbacksAndMessages(null)
        
        // Check maximum retry count
        if (retryCount >= maxRetryCount) {
            showError(getString(R.string.connection_failed))
            markCurrentChannelOffline()
            return
        }
        
        retryCount++
        showError(getString(R.string.stream_error_retrying, retryCount, maxRetryCount))
        
        // Retry after 3 seconds
        retryHandler.postDelayed({
            retryCurrentChannel()
        }, retryDelayMs)
    }
    
    private fun resetRetryCount() {
        retryCount = 0
        retryHandler.removeCallbacksAndMessages(null)
    }
    
    private fun retryCurrentChannel() {
        if (allChannels.isEmpty()) return
        val channel = allChannels[currentChannelIndex]
        
        lifecycleScope.launch {
            // First get the current details of the channel (for Offline banner etc.)
            repository.getChannelDetails(channel.slug).onSuccess { fullDetails ->
                val isLive = fullDetails.livestream != null
                
                if (isLive) {
                    // standardize retry to use playCurrentChannel
                    playCurrentChannel(useZapDelay = false, showInfo = false, keepBanner = true, isRetry = true)
                } else {
                    // Channel is truly offline
                    resetRetryCount()
                    markCurrentChannelWithOfflineBanner(fullDetails)
                }
            }.onFailure {
                // If we get an API error, fall back to default offline state
                resetRetryCount()
                markCurrentChannelOffline()
            }
        }
    }

    private fun markCurrentChannelWithOfflineBanner(details: dev.xacnio.kciktv.data.model.ChannelDetailResponse) {
        if (allChannels.isEmpty()) return
        val currentIndex = currentChannelIndex
        val channel = allChannels[currentIndex]
        
        // Preserve current banner if API returns null
        val offlineBanner = details.offlineBannerImage?.src ?: details.offlineBannerImage?.url ?: channel.offlineBannerUrl
        
        val offlineChannel = channel.copy(
            isLive = false,
            viewerCount = 0,
            title = details.livestream?.sessionTitle ?: getString(R.string.stream_offline),
            categoryName = details.livestream?.categories?.firstOrNull()?.name,
            offlineBannerUrl = offlineBanner
        )
        allChannels[currentIndex] = offlineChannel
        updateChannelUIForOffline(offlineChannel)
        
        ivsPlayer?.pause()
        binding.playerView.visibility = View.GONE
        channelSidebarAdapter?.notifyItemChanged(currentIndex)
        showError(getString(R.string.stream_offline))
    }
    
    private fun markCurrentChannelOffline() {
        if (allChannels.isEmpty()) return
        
        val channel = allChannels[currentChannelIndex]
        
        // Update channel as offline
        val offlineChannel = channel.copy(
            isLive = false,
            viewerCount = 0,
            title = "",
            categoryName = null
        )
        allChannels[currentChannelIndex] = offlineChannel
        
        // Update UI - Show offline state
        updateChannelUIForOffline(offlineChannel)
        
        // Stop player
        ivsPlayer?.pause()
        binding.playerView.visibility = View.GONE
        
        // Update adapter if sidebar is open
        channelSidebarAdapter?.notifyItemChanged(currentChannelIndex)
        
        showError(getString(R.string.stream_offline))
    }
    private fun updateChannelUIForOffline(channel: ChannelItem) {
        binding.channelName.text = channel.username
        binding.streamTitle.text = getString(R.string.stream_offline)
        binding.streamTitle.isSelected = false
        binding.channelNumber.text = String.format("%02d", currentChannelIndex + 1)
        
        // Hide ALL live-only components explicitly
        binding.viewerCount.visibility = View.GONE
        binding.viewerIcon.visibility = View.GONE
        binding.categoryName.visibility = View.GONE
        binding.qualityBadge.visibility = View.GONE
        binding.fpsBadge.visibility = View.GONE
        binding.tagContainer.visibility = View.GONE
        
        binding.sidebarLoadingBar.visibility = View.GONE
        binding.loadingThumbnailView.visibility = View.GONE 
        
        Glide.with(this).load(channel.getEffectiveProfilePicUrl()).circleCrop().into(binding.profileImage)
        
        // Ensure banner is behind menus (Menus are 10dp, Banner 1dp is fine)
        binding.playerView.visibility = View.GONE
        binding.offlineBannerView.visibility = View.VISIBLE
        
        val bannerUrl = channel.getEffectiveOfflineBannerUrl()
        val currentBannerUrl = binding.offlineBannerView.tag as? String
        
        // Only reload if the URL has actually changed
        if (currentBannerUrl != bannerUrl) {
            binding.offlineBannerView.tag = bannerUrl
            Glide.with(this)
                .load(bannerUrl)
                .centerCrop()
                .placeholder(binding.offlineBannerView.drawable ?: ColorDrawable(Color.BLACK))
                .error(ColorDrawable(Color.BLACK))
                .into(binding.offlineBannerView)
        }
    }
    private fun Int.toPx(): Float = this * resources.displayMetrics.density
    
    // ==================== Channel Number Input ====================
    
    private fun handleChannelNumberInput(digit: Int) {
        channelInputBuffer.append(digit)
        updateChannelInputOverlay()
        
        // Cancel previous timer
        channelInputHandler.removeCallbacks(channelInputRunnable)
        
        // Process after 1.5 seconds (no digit limit)
        channelInputHandler.postDelayed(channelInputRunnable, 1500)
    }
    
    private fun updateChannelInputOverlay() {
        binding.channelInputOverlay.visibility = View.VISIBLE
        binding.channelInputText.text = channelInputBuffer.toString()
        
        // Animation
        if (prefs.animationsEnabled) {
            binding.channelInputOverlay.alpha = 0f
            binding.channelInputOverlay.scaleX = 1.2f
            binding.channelInputOverlay.scaleY = 1.2f
            binding.channelInputOverlay.animate()
                .alpha(1f)
                .scaleX(1f)
                .scaleY(1f)
                .setDuration(150)
                .start()
        } else {
            binding.channelInputOverlay.alpha = 1f
        }
    }
    
    private fun processChannelInput() {
        channelInputHandler.removeCallbacks(channelInputRunnable)
        
        val input = channelInputBuffer.toString()
        channelInputBuffer.clear()
        
        val targetChannel = input.toIntOrNull()
        if (targetChannel != null && targetChannel in 1..allChannels.size) {
            currentChannelIndex = targetChannel - 1
            playCurrentChannel()
        } else {
            Toast.makeText(this, getString(R.string.channel_not_found, input), Toast.LENGTH_SHORT).show()
        }
        
        hideChannelInputOverlay()
    }
    
    private fun hideChannelInputOverlay() {
        if (prefs.animationsEnabled) {
            binding.channelInputOverlay.animate()
                .alpha(0f)
                .setDuration(200)
                .withEndAction { binding.channelInputOverlay.visibility = View.GONE }
                .start()
        } else {
            binding.channelInputOverlay.visibility = View.GONE
        }
    }
    
    // ==================== Volume Indicator ====================
    
    private fun showVolumeOverlay() {
        val maxVolume = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        val currentVolume = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)
        val volumePercent = ((currentVolume.toFloat() / maxVolume) * 100).toInt()
        
        // Update volume icon
        val isMuted = currentVolume == 0
        binding.volumeIcon.setImageResource(if (isMuted) R.drawable.ic_volume_off else R.drawable.ic_volume)
        
        // Update bar height (120dp max)
        val barHeight = (120 * resources.displayMetrics.density * currentVolume / maxVolume).toInt()
        val params = binding.volumeBarFill.layoutParams
        params.height = barHeight
        binding.volumeBarFill.layoutParams = params
        
        // Set bar color to theme color
        val fillDrawable = binding.volumeBarFill.background as? GradientDrawable
        fillDrawable?.setColor(prefs.themeColor)
        
        // Show percentage
        binding.volumeText.text = "$volumePercent%"
        
        // Show overlay
        binding.volumeOverlay.visibility = View.VISIBLE
        
        if (prefs.animationsEnabled) {
            binding.volumeOverlay.alpha = 0f
            binding.volumeOverlay.translationX = 50f
            binding.volumeOverlay.animate()
                .alpha(1f)
                .translationX(0f)
                .setDuration(200)
                .start()
        } else {
            binding.volumeOverlay.alpha = 1f
            binding.volumeOverlay.translationX = 0f
        }
        
        // hide after 2 seconds
        volumeHandler.removeCallbacks(hideVolumeRunnable)
        volumeHandler.postDelayed(hideVolumeRunnable, 2000)
    }
    
    private fun hideVolumeOverlay() {
        if (prefs.animationsEnabled) {
            binding.volumeOverlay.animate()
                .alpha(0f)
                .translationX(50f)
                .setDuration(200)
                .withEndAction { binding.volumeOverlay.visibility = View.GONE }
                .start()
        } else {
            binding.volumeOverlay.visibility = View.GONE
        }
    }
    
    // ==================== Chat ====================
    
    private fun toggleChat() {
        hideAllOverlays()
        if (isChatVisible) {
            hideChat()
        } else {
            showChat()
        }
    }
    
    private fun showChat() {
        if (allChannels.isEmpty()) return
        
        isChatVisible = true
        val channel = allChannels[currentChannelIndex]
        
        // Make panel visible
        binding.chatPanel.visibility = View.VISIBLE
        
        // Animation values
        val chatWidthPx = (300f * resources.displayMetrics.density).toInt()
        
        if (prefs.animationsEnabled) {
            // Chat slide in from right
            binding.chatPanel.translationX = chatWidthPx.toFloat()
            binding.chatPanel.animate()
                .translationX(0f)
                .setDuration(300)
                .setInterpolator(AccelerateDecelerateInterpolator())
                .start()
            
            // Player container smooth shrink with margin animation
            val marginAnimator = ValueAnimator.ofInt(0, chatWidthPx)
            marginAnimator.duration = 300
            marginAnimator.interpolator = AccelerateDecelerateInterpolator()
            marginAnimator.addUpdateListener { animation ->
                val animatedValue = animation.animatedValue as Int
                val lp = binding.playerContainer.layoutParams as FrameLayout.LayoutParams
                lp.marginEnd = animatedValue
                binding.playerContainer.layoutParams = lp
            }
            marginAnimator.start()
        } else {
            binding.chatPanel.translationX = 0f
            val lp = binding.playerContainer.layoutParams as FrameLayout.LayoutParams
            lp.marginEnd = chatWidthPx
            binding.playerContainer.layoutParams = lp
        }
        
        // Start/Update chat session (will subscribe to chat since isChatVisible is now true)
        connectToChat(channel.slug)
    }
    
    private fun hideChat() {
        isChatVisible = false
        
        // Unsubscribe from chat to save data, but keep connection for channel events
        chatWebSocket?.unsubscribeFromChat()
        chatAdapter?.clearMessages()
        
        val chatWidthPx = (300f * resources.displayMetrics.density).toInt()
        val currentMargin = (binding.playerContainer.layoutParams as FrameLayout.LayoutParams).marginEnd
        
        if (prefs.animationsEnabled) {
            // Chat slide out to right
            binding.chatPanel.animate()
                .translationX(chatWidthPx.toFloat())
                .setDuration(250)
                .setInterpolator(AccelerateDecelerateInterpolator())
                .withEndAction {
                    binding.chatPanel.visibility = View.GONE
                    chatAdapter?.clearMessages()
                }
                .start()
            
            // Player container smooth grow with margin animation
            val marginAnimator = ValueAnimator.ofInt(currentMargin, 0)
            marginAnimator.duration = 250
            marginAnimator.interpolator = AccelerateDecelerateInterpolator()
            marginAnimator.addUpdateListener { animation ->
                val animatedValue = animation.animatedValue as Int
                val lp = binding.playerContainer.layoutParams as FrameLayout.LayoutParams
                lp.marginEnd = animatedValue
                binding.playerContainer.layoutParams = lp
            }
            marginAnimator.start()
        } else {
            binding.chatPanel.visibility = View.GONE
            binding.chatPanel.translationX = chatWidthPx.toFloat()
            chatAdapter?.clearMessages()
            val lp = binding.playerContainer.layoutParams as FrameLayout.LayoutParams
            lp.marginEnd = 0
            binding.playerContainer.layoutParams = lp
        }
    }
    
    private fun connectToChat(slug: String) {
        val slugChanged = lastSubscribedSlug != slug
        Log.d("PlayerActivity", "Syncing WebSocket - slug: $slug, changed: $slugChanged, chatVisible: $isChatVisible")
        
        // Ensure WebSocket is physically connected to Pusher
        chatWebSocket?.connect()
        
        // Cancel any previous connection attempt for other slugs
        chatConnectJob?.cancel()
        
        chatConnectJob = lifecycleScope.launch {
            repository.getChatInfo(slug).onSuccess { chatInfo ->
                // Update tracker
                lastSubscribedSlug = slug
                
                // 1. Subscribe to channel events (e.g., StreamerIsLive)
                // Always refresh if slug changed
                if (slugChanged) {
                    chatWebSocket?.unsubscribeFromChannelEvents()
                    chatWebSocket?.subscribeToChannelEvents(chatInfo.channelId)
                }
                
                // 2. Manage Chat Subscription
                if (isChatVisible) {
                    chatHandler.post {
                        chatAdapter?.setSubscriberBadges(chatInfo.subscriberBadges)
                        // Only clear if we actually changed channel or need fresh start
                        if (slugChanged) chatAdapter?.clearMessages()
                    }
                    
                    // Always subscribe to chat if visible (the socket method handles redundant calls)
                    chatWebSocket?.subscribeToChat(chatInfo.chatroomId)
                    
                    // Fetch history only if it's a new channel for the chat
                    if (slugChanged) {
                        repository.getChatHistory(chatInfo.channelId).onSuccess { historyMessages ->
                            chatHandler.post {
                                if (historyMessages.isNotEmpty()) {
                                    chatAdapter?.submitList(historyMessages) {
                                        binding.chatRecyclerView.scrollToPosition(historyMessages.size - 1)
                                    }
                                }
                            }
                        }.onFailure { error ->
                            Log.e("PlayerActivity", "Chat history failed: ${error.message}")
                        }
                    }
                } else {
                    // Chat hidden: Unsubscribe from chat to save data, but keep connection for channel events
                    chatWebSocket?.unsubscribeFromChat()
                    chatHandler.post {
                        chatAdapter?.clearMessages()
                    }
                }
                
                chatHandler.post {
                    updateChatConnectionIndicator()
                }
            }.onFailure { error ->
                Log.e("PlayerActivity", "getChatInfo failed: ${error.message}")
                chatHandler.post {
                    updateChatConnectionIndicator()
                }
            }
        }
    }
    
    private fun addChatMessage(message: ChatMessage) {
        val currentList = chatAdapter?.currentList?.toMutableList() ?: mutableListOf()
        currentList.add(message)
        
        // Keep only last 100 messages for performance
        val trimmedList = if (currentList.size > 100) {
            currentList.takeLast(100)
        } else {
            currentList
        }
        
        chatAdapter?.submitList(trimmedList) {
            // Scroll to bottom
            binding.chatRecyclerView.scrollToPosition(trimmedList.size - 1)
        }
    }
    
    private fun updateChatConnectionIndicator() {
        // UI element removed
    }
    
    private fun reconnectChatIfNeeded() {
        if (allChannels.isNotEmpty()) {
            val channel = allChannels[currentChannelIndex]
            connectToChat(channel.slug)
        }
    }
    
    override fun onResume() { 
        super.onResume()
        // Restore high quality when returning to foreground (service keeps running)
        setPowerSavingPlayback(false)
        // Don't handle resume if in PIP mode (handled in onPictureInPictureModeChanged)
        if (isInPictureInPictureMode) {
            return
        }
        ivsPlayer?.play()
        startRefreshTimer()
        // Reconnect chat if it was visible
        if (isChatVisible && allChannels.isNotEmpty()) {
            val channel = allChannels[currentChannelIndex]
            connectToChat(channel.slug)
        }
    }
    override fun onDestroy() { 
        super.onDestroy()
        try {
            unregisterReceiver(pipReceiver)
            unregisterReceiver(screenStateReceiver)
            unregisterReceiver(stopPlaybackReceiver)
        } catch (e: Exception) {}
        mediaSession?.release()
        mediaSession = null
        hideHandler.removeCallbacks(hideRunnable)
        channelInputHandler.removeCallbacks(channelInputRunnable)
        volumeHandler.removeCallbacks(hideVolumeRunnable)
        powerSavingHandler.removeCallbacksAndMessages(null)
        retryHandler.removeCallbacksAndMessages(null)
        chatHandler.removeCallbacksAndMessages(null)
        chatWebSocket?.disconnect()
        chatWebSocket = null
        loginServer?.stop()
        loginServer = null
        ivsPlayer?.release()
        ivsPlayer = null 
        exoPlayer?.release()
        exoPlayer = null
    }

    private fun formatStreamTime(seconds: Long): String {
        val h = seconds / 3600
        val m = (seconds % 3600) / 60
        val s = seconds % 60
        return String.format("%02d:%02d:%02d", h, m, s)
    }

    private fun parseIsoDate(dateStr: String): Long? {
        return try {
            // Kick uses ISO-8601 (e.g., 2026-01-08T20:06:13.629Z)
            val sdf = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US)
            sdf.timeZone = TimeZone.getTimeZone("UTC")
            sdf.parse(dateStr)?.time
        } catch (e: Exception) {
            null
        }
    }

    private fun updateUptimeDisplay() {
        val start = streamCreatedAtMillis ?: return
        val correctedNow = System.currentTimeMillis() + serverClockOffset
        val diffSeconds = (correctedNow - start) / 1000
        
        if (diffSeconds >= 0) {
            binding.streamTimeBadge.text = formatStreamTime(diffSeconds)
            binding.streamTimeBadge.visibility = View.VISIBLE
        }
    }

    private fun startStabilityWatchdog() {
        stabilityHandler.removeCallbacks(stabilityRunnable)
        zeroBitrateCount = 0
        stabilityHandler.postDelayed(stabilityRunnable, 5000L) // Wait 5s before first check
    }

    private fun stopStabilityWatchdog() {
        stabilityHandler.removeCallbacks(stabilityRunnable)
        zeroBitrateCount = 0
    }

    private fun getStreamLanguages(): List<Pair<String, String>> {
        val codes = resources.getStringArray(R.array.stream_language_codes)
        val names = resources.getStringArray(R.array.stream_language_names)
        return codes.zip(names)
    }

    private fun handleLanguageToggle(code: String) {
        val current = prefs.streamLanguages.toMutableSet()
        if (current.contains(code)) {
            current.remove(code)
        } else {
            current.add(code)
        }
        prefs.streamLanguages = current
        showLanguageFilterSidebar() // Re-populate with updated selections
        updateLanguageFilterButtonText()
        if (currentListMode == ListMode.GLOBAL || prefs.globalSortMode == "featured") {
            loadInitialChannels()
        }
    }

    private fun showLanguageFilterSidebar() {
        // We stay in MenuState.DRAWER but switch sub-views
        sidebarContext = "LANGUAGE_FILTER"
        
        val selectedCodes = prefs.streamLanguages
        val items = getStreamLanguages().map { (code, name) ->
            SelectionItem(
                id = code,
                title = name,
                isSelected = selectedCodes.contains(code),
                showCheckbox = true
            )
        }

        binding.drawerLanguageRecyclerView.apply {
            layoutManager = LinearLayoutManager(this@PlayerActivity)
            adapter = GenericSelectionAdapter(items, prefs.themeColor) { item ->
                handleLanguageToggle(item.id)
            }
        }

        binding.drawerMainContent.visibility = View.GONE
        binding.drawerLanguageContent.visibility = View.VISIBLE
        
        binding.drawerLanguageRecyclerView.post {
            binding.drawerLanguageRecyclerView.requestFocus()
        }
    }

    private fun hideLanguageFilter() {
        binding.drawerLanguageContent.visibility = View.GONE
        binding.drawerMainContent.visibility = View.VISIBLE
        sidebarContext = ""
        binding.btnDrawerLanguageFilter.requestFocus()
    }

    private fun updateLanguageFilterButtonText() {
        val count = prefs.streamLanguages.size
        val baseText = getString(R.string.stream_language)
        binding.txtDrawerLanguageFilter.text = if (count > 0) {
            getString(R.string.language_selected_format, baseText, count)
        } else {
            baseText
        }
    }

    private fun showSearchPanel() {
        hideAllOverlays()
        binding.searchPanel.visibility = View.VISIBLE
        binding.searchPanel.alpha = 0f
        binding.searchPanel.animate().alpha(1f).setDuration(300).start()
        binding.searchEditText.requestFocus()
        currentState = MenuState.SEARCH
    }

    private fun hideSearchPanel() {
        binding.searchPanel.animate().alpha(0f).setDuration(300).withEndAction { 
            binding.searchPanel.visibility = View.GONE 
        }.start()
        currentState = MenuState.NONE
        // Hide keyboard
        val imm = getSystemService(INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager
        imm.hideSoftInputFromWindow(binding.searchEditText.windowToken, 0)
    }

    private fun performSearch(slug: String) {
        if (slug.isBlank()) return
        
        binding.searchLoading.visibility = View.VISIBLE
        binding.searchErrorText.visibility = View.GONE
        
        lifecycleScope.launch {
            repository.getChannelDetails(slug.trim().lowercase()).onSuccess { detail ->
                val channelItem = ChannelItem(
                    id = detail.id?.toString() ?: "0",
                    slug = detail.slug ?: slug,
                    username = detail.user?.username ?: slug,
                    title = detail.livestream?.sessionTitle ?: "Live Stream",
                    viewerCount = detail.livestream?.viewerCount ?: 0,
                    thumbnailUrl = detail.livestream?.thumbnail?.src,
                    profilePicUrl = detail.user?.profilePic,
                    playbackUrl = detail.playbackUrl,
                    categoryName = detail.livestream?.categories?.firstOrNull()?.name,
                    categorySlug = detail.livestream?.categories?.firstOrNull()?.slug,
                    language = "tr",
                    isLive = detail.livestream != null,
                    startTimeMillis = detail.livestream?.createdAt?.let { parseIsoDate(it) }
                )
                
                currentListMode = ListMode.SEARCH
                allChannels.clear()
                allChannels.add(channelItem)
                currentChannelIndex = 0
                channelSidebarAdapter?.replaceChannels(allChannels, 0)
                
                binding.searchLoading.visibility = View.GONE
                hideSearchPanel()
                hideDrawer(immediate = true)
                playCurrentChannel(showInfo = true)
                
            }.onFailure {
                binding.searchLoading.visibility = View.GONE
                binding.searchErrorText.text = getString(R.string.search_no_result)
                binding.searchErrorText.visibility = View.VISIBLE
            }
        }
    }

    private fun playMyChannel() {
        val myUsername = prefs.username
        if (myUsername != null) {
            performSearch(myUsername)
        } else {
            Toast.makeText(this, getString(R.string.not_logged_in), Toast.LENGTH_SHORT).show()
        }
    }

    private fun startTagMarquee() {
        tagScrollAnimator?.cancel()
        binding.tagScrollView.scrollTo(0, 0)
        
        binding.tagScrollView.post {
            val contentWidth = binding.tagContainer.width
            val viewWidth = binding.tagScrollView.width
            
            if (contentWidth > viewWidth) {
                val scrollRange = contentWidth - viewWidth
                tagScrollAnimator = ValueAnimator.ofInt(0, scrollRange).apply {
                    duration = (scrollRange * 35L).coerceAtLeast(3000L)
                    startDelay = 2000L
                    interpolator = android.view.animation.LinearInterpolator()
                    
                    addUpdateListener { animator ->
                        binding.tagScrollView.scrollTo(animator.animatedValue as Int, 0)
                    }
                    
                    addListener(object : android.animation.AnimatorListenerAdapter() {
                        private var isCancelled = false
                        override fun onAnimationCancel(animation: android.animation.Animator) {
                            isCancelled = true
                        }
                        
                        override fun onAnimationEnd(animation: android.animation.Animator) {
                            if (!isCancelled) {
                                binding.tagScrollView.postDelayed({
                                    if (!isCancelled && currentState == MenuState.INFO) {
                                        startTagMarquee()
                                    }
                                }, 2000L)
                            }
                        }
                    })
                    
                    start()
                }
            }
        }
    }

    override fun onPause() {
        super.onPause()
        
        // Don't do anything if entering PIP mode - player continues seamlessly
        if (isInPictureInPictureMode) {
            Log.d("PlayerActivity", "onPause: In PIP mode, doing nothing")
            return
        }
        
        // If activity is finishing, clean up
        if (isFinishing) {
            ivsPlayer?.pause()
            exoPlayer?.stop()
            refreshHandler.removeCallbacks(activeChannelRefreshRunnable)
            refreshHandler.removeCallbacks(globalListRefreshRunnable)
            retryHandler.removeCallbacksAndMessages(null)
            chatHandler.removeCallbacksAndMessages(null)
            stopRecoveryPolling()
            chatWebSocket?.disconnect()
            return
        }
        
        // Background audio - nothing to do, service is already running
        Log.d("PlayerActivity", "onPause: Background mode active, audio continues")
    }

    override fun onStop() {
        super.onStop()
        
        // On TV: Always stop playback when going to background (no background audio on TV)
        if (isTvDevice()) {
            Log.d("PlayerActivity", "onStop: TV device - stopping playback")
            ivsPlayer?.pause()
            exoPlayer?.pause()
            isBackgroundAudioEnabled = false
            // Stop the service
            stopService(Intent(this, PlaybackService::class.java))
            val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            notificationManager.cancel(NOTIFICATION_ID)
            return
        }
        
        // On mobile: Pause ONLY if not in background mode AND not in PIP
        if (!isBackgroundAudioEnabled && !isInPictureInPictureMode) {
             ivsPlayer?.pause()
             exoPlayer?.pause()
        }
    }

    private fun startRecoveryPolling() {
        stopRecoveryPolling()
        recoveryAttempts = 0
        Log.d("PlayerActivity", "Starting quick recovery polling for 1 minute...")
        recoveryHandler.postDelayed(recoveryRunnable, 5000L)
    }

    private fun stopRecoveryPolling() {
        recoveryHandler.removeCallbacks(recoveryRunnable)
    }
}
