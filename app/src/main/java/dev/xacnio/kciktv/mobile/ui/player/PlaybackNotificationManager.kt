/**
 * File: PlaybackNotificationManager.kt
 *
 * Description: Manages the persistent playback notification for background audio and PiP.
 * It handles the creation of the Notification Channel, builds custom RemoteViews for the notification,
 * and updates it with current stream info and playback state.
 *
 * Author: Xacnio
 *
 */
package dev.xacnio.kciktv.mobile.ui.player

import dev.xacnio.kciktv.shared.ui.player.MediaSessionHelper

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.os.Build
import android.widget.RemoteViews
import androidx.core.app.NotificationCompat
import com.amazonaws.ivs.player.Player
import dev.xacnio.kciktv.mobile.MobilePlayerActivity
import dev.xacnio.kciktv.shared.PlaybackService
import dev.xacnio.kciktv.R
import dev.xacnio.kciktv.shared.data.model.ChannelItem
import dev.xacnio.kciktv.shared.data.prefs.AppPreferences

/**
 * Manages media playback notifications for background/PiP playback.
 * Handles notification channel creation, display, and updates.
 */
class PlaybackNotificationManager(
    private val activity: MobilePlayerActivity,
    private val prefs: AppPreferences
) {
    companion object {
        const val CHANNEL_ID = "kciktv_mobile_playback"
        const val NOTIFICATION_ID = 43
        private const val PIP_CONTROL_ACTION = "dev.xacnio.kciktv.MOBILE_PIP_CONTROL"
        private const val EXTRA_CONTROL_TYPE = "type"
        private const val CONTROL_TYPE_PLAY_PAUSE = 1
        private const val CONTROL_TYPE_MUTE = 4
    }

    var isBackgroundAudioEnabled = false
        private set

    fun setBackgroundAudioEnabled(enabled: Boolean) {
        isBackgroundAudioEnabled = enabled
    }

    fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Mobile Playback",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Arka plan ses oynatma bildirimleri"
                setShowBadge(false)
            }
            val manager = activity.getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }
    }

    fun hideNotification(mediaSessionHelper: MediaSessionHelper?) {
        isBackgroundAudioEnabled = false
        val notificationManager = activity.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        notificationManager.cancel(NOTIFICATION_ID)

        // Also update media session state to stopped
        mediaSessionHelper?.setStopped()

        activity.stopService(Intent(activity, PlaybackService::class.java))
    }

    fun getThemeIconRes(): Int {
        return R.drawable.ic_notification
    }

    fun showNotification(
        overrideIsPlaying: Boolean? = null,
        ivsPlayer: Player?,
        currentChannel: ChannelItem?,
        currentProfileBitmap: Bitmap?
    ) {
        val isPlaying = overrideIsPlaying ?: (ivsPlayer?.state == Player.State.PLAYING)
        val channel = currentChannel

        val notificationIntent = Intent(activity, MobilePlayerActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        val pendingIntent = PendingIntent.getActivity(activity, 0, notificationIntent, PendingIntent.FLAG_IMMUTABLE)

        val deleteIntent = Intent(activity, PlaybackService::class.java).apply {
            action = "STOP"
        }
        val deletePendingIntent = PendingIntent.getService(activity, 100, deleteIntent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)

        // Setup PendingIntents
        val playPauseIntent = PendingIntent.getBroadcast(activity, CONTROL_TYPE_PLAY_PAUSE + 100,
            Intent(PIP_CONTROL_ACTION).apply {
                setPackage(activity.packageName)
                putExtra(EXTRA_CONTROL_TYPE, CONTROL_TYPE_PLAY_PAUSE)
                putExtra("from_notification", true)
            },
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_CANCEL_CURRENT)

        val stopIntentBroadcast = PendingIntent.getService(activity, 101,
            Intent(activity, PlaybackService::class.java).apply { action = "STOP" },
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_CANCEL_CURRENT)

        // Mute Intent
        val muteIntent = PendingIntent.getBroadcast(activity, CONTROL_TYPE_MUTE,
            Intent(PIP_CONTROL_ACTION).apply {
                setPackage(activity.packageName)
                putExtra(EXTRA_CONTROL_TYPE, CONTROL_TYPE_MUTE)
                putExtra("from_notification", true)
            },
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)

        // Current Mute State
        val isMuted = ivsPlayer?.volume == 0f
        val muteIconRes = if (isMuted) R.drawable.ic_volume_off else R.drawable.ic_volume

        // Collapsed View (No Profile Picture)
        val remoteViewsCollapsed = RemoteViews(activity.packageName, R.layout.notification_tiktok_style)
        remoteViewsCollapsed.setImageViewResource(R.id.btn_notification_mute, muteIconRes)
        remoteViewsCollapsed.setOnClickPendingIntent(R.id.btn_notification_mute, muteIntent)

        val qualityText = ivsPlayer?.quality?.let { "${it.height}p" } ?: ""
        val notifText = if (qualityText.isNotEmpty()) "${activity.getString(R.string.currently_live)} • $qualityText" else activity.getString(R.string.currently_live)

        remoteViewsCollapsed.setTextViewText(R.id.notification_title, channel?.username ?: "KCIKTV")
        remoteViewsCollapsed.setTextViewText(R.id.notification_text, notifText)
        remoteViewsCollapsed.setImageViewResource(R.id.btn_notification_play_pause,
            if (isPlaying) android.R.drawable.ic_media_pause else android.R.drawable.ic_media_play)
        remoteViewsCollapsed.setOnClickPendingIntent(R.id.btn_notification_play_pause, playPauseIntent)
        remoteViewsCollapsed.setOnClickPendingIntent(R.id.btn_notification_stop, stopIntentBroadcast)

        // Expanded View (With Profile Picture)
        val remoteViewsExpanded = RemoteViews(activity.packageName, R.layout.notification_tiktok_style_expanded)
        remoteViewsExpanded.setImageViewResource(R.id.btn_notification_mute, muteIconRes)
        remoteViewsExpanded.setOnClickPendingIntent(R.id.btn_notification_mute, muteIntent)

        remoteViewsExpanded.setTextViewText(R.id.notification_title, channel?.username ?: "KCIKTV")
        remoteViewsExpanded.setTextViewText(R.id.notification_text, notifText)

        if (currentProfileBitmap != null) {
            remoteViewsExpanded.setImageViewBitmap(R.id.notification_large_icon, currentProfileBitmap)
        } else {
            remoteViewsExpanded.setImageViewResource(R.id.notification_large_icon, getThemeIconRes())
        }

        remoteViewsExpanded.setImageViewResource(R.id.btn_notification_play_pause,
            if (isPlaying) android.R.drawable.ic_media_pause else android.R.drawable.ic_media_play)
        remoteViewsExpanded.setOnClickPendingIntent(R.id.btn_notification_play_pause, playPauseIntent)
        remoteViewsExpanded.setOnClickPendingIntent(R.id.btn_notification_stop, stopIntentBroadcast)

        val notification = NotificationCompat.Builder(activity, CHANNEL_ID)
            .setSmallIcon(getThemeIconRes())
            .setColor(prefs.themeColor)
            .setWhen(System.currentTimeMillis())
            .setCustomContentView(remoteViewsCollapsed)
            .setCustomBigContentView(remoteViewsExpanded)
            .setStyle(NotificationCompat.DecoratedCustomViewStyle())
            .setContentIntent(pendingIntent)
            .setDeleteIntent(deletePendingIntent)
            .setOngoing(isPlaying)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .build()

        // Create/Update notification
        val notificationManager = activity.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        notificationManager.notify(NOTIFICATION_ID, notification)

        // Update service even if paused
        if (isBackgroundAudioEnabled) {
            val serviceIntent = Intent(activity, PlaybackService::class.java).apply {
                putExtra("notification", notification)
                putExtra("notificationId", NOTIFICATION_ID)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                activity.startForegroundService(serviceIntent)
            } else {
                activity.startService(serviceIntent)
            }
        }
    }
}
