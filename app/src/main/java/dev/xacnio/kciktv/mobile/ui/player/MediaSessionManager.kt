/**
 * File: MediaSessionManager.kt
 *
 * Description: Manages the Android MediaSession integration.
 * It handles the creation and release of the media session, updates playback state (playing/paused)
 * for external controllers (like Bluetooth headsets or lock screen), and coordinates with the notification manager.
 *
 * Author: Xacnio
 *
 */
package dev.xacnio.kciktv.mobile.ui.player

import dev.xacnio.kciktv.shared.ui.player.MediaSessionHelper

import com.amazonaws.ivs.player.Player
import dev.xacnio.kciktv.mobile.MobilePlayerActivity

/**
 * Manages media session setup, state updates, and playback notifications.
 */
class MediaSessionManager(
    private val activity: MobilePlayerActivity
) {
    private val playbackNotificationManager get() = activity.playbackNotificationManager
    
    var mediaSessionHelper: MediaSessionHelper? = null
        private set

    fun setupMediaSession() {
        mediaSessionHelper = MediaSessionHelper(activity, object : MediaSessionHelper.MediaSessionListener {
            override fun onPlayRequested() {
                activity.ivsPlayer?.play()
                updateMediaSessionState(true)
                activity.updatePiPUi(true)
                if (activity.isBackgroundAudioEnabled) showNotification(true)
            }
            override fun onPauseRequested() {
                activity.ivsPlayer?.pause()
                updateMediaSessionState(false)
                activity.updatePiPUi(false)
                if (activity.isBackgroundAudioEnabled) showNotification(false)
            }
        })
        mediaSessionHelper?.setupMediaSession()
    }

    fun updateMediaSessionState(overrideIsPlaying: Boolean? = null) {
        val isPlaying = overrideIsPlaying ?: (activity.ivsPlayer?.state == Player.State.PLAYING)
        mediaSessionHelper?.updateMediaSessionState(isPlaying, activity.currentChannel, activity.currentProfileBitmap)
        
        if (activity.isBackgroundAudioEnabled) {
            showNotification()
        }
    }

    fun hideNotification() {
        playbackNotificationManager.hideNotification(mediaSessionHelper)
    }

    fun showNotification(overrideIsPlaying: Boolean? = null) {
        playbackNotificationManager.showNotification(
            overrideIsPlaying,
            activity.ivsPlayer,
            activity.currentChannel,
            activity.currentProfileBitmap
        )
    }

    fun getThemeIconRes(): Int = playbackNotificationManager.getThemeIconRes()

    fun release() {
        mediaSessionHelper?.release()
        mediaSessionHelper = null
    }
}
