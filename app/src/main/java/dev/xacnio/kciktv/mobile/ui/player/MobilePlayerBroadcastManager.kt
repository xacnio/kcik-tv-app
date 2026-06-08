/**
 * File: MobilePlayerBroadcastManager.kt
 *
 * Description: Handles Broadcast Receivers for player control events.
 * It listens for system-level or notification-based intents such as Play/Pause, Mute,
 * Audio-Only toggle, and Stop Playback commands, invoking the appropriate player actions.
 *
 * Author: Xacnio
 *
 */
package dev.xacnio.kciktv.mobile.ui.player

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.view.View
import android.widget.Toast
import androidx.core.content.ContextCompat
import com.amazonaws.ivs.player.Player
import dev.xacnio.kciktv.mobile.MobilePlayerActivity
import dev.xacnio.kciktv.R
import dev.xacnio.kciktv.shared.PlaybackService
import dev.xacnio.kciktv.shared.ui.utils.EmoteManager

class MobilePlayerBroadcastManager(private val activity: MobilePlayerActivity) {

    private val pipReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent == null || intent.action != activity.PIP_CONTROL_ACTION) return

            when (intent.getIntExtra(activity.EXTRA_CONTROL_TYPE, 0)) {
                activity.CONTROL_TYPE_PLAY_PAUSE -> {
                    // Prevent playing if we are in error/offline state
                    if (activity.binding.errorOverlay.visibility != View.VISIBLE) {
                        val willPlay = activity.ivsPlayer?.state != Player.State.PLAYING

                        if (willPlay) {
                            activity.ivsPlayer?.seekTo(activity.ivsPlayer?.duration ?: 0L)
                            activity.ivsPlayer?.play()
                        } else {
                            activity.ivsPlayer?.pause()
                        }
                        activity.updatePiPUi(willPlay)
                        activity.updateMediaSessionState(willPlay)

                        // Force update notification if command came from notification itself
                        val fromNotification = intent.getBooleanExtra("from_notification", false)

                        if (activity.isBackgroundAudioEnabled || fromNotification) {
                            activity.showNotification(willPlay)
                        }
                    }
                }
                activity.CONTROL_TYPE_LIVE -> {
                    activity.ivsPlayer?.seekTo(activity.ivsPlayer?.duration ?: 0L)
                    activity.ivsPlayer?.play()
                    activity.updateMediaSessionState()
                }
                activity.CONTROL_TYPE_AUDIO_ONLY -> {
                    activity.isExplicitAudioSwitch = true // Prevent stopping when exiting PiP via this action
                    activity.isBackgroundAudioEnabled = true
                    activity.showNotification(true) // Start foreground service before going to background, enforce ongoing
                    activity.moveTaskToBack(true)
                    Toast.makeText(activity, activity.getString(R.string.background_audio_active), Toast.LENGTH_SHORT).show()
                    activity.updateMediaSessionState(true)

                    // Enforce 360p limit for Audio Only (saves data in background)
                    activity.setForcedQualityLimit("360p")
                }
                activity.CONTROL_TYPE_MUTE -> {
                    activity.playerControlsManager.toggleMute()
                }
            }
        }
    }

    private val stopPlaybackReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == PlaybackService.ACTION_STOP_PLAYBACK) {
                // 1. Pause Player
                activity.ivsPlayer?.pause()

                // 2. Reset States
                activity.isBackgroundAudioEnabled = false
                // currentChannel = null // Keep channel loaded so user can resume if they open app

                // 3. Close PiP if active
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N && activity.isInPictureInPictureMode) {
                    activity.moveTaskToBack(true) 
                }

                // 4. Hide Notification & Stop Service
                activity.hideNotification()

                // 5. Update UI
            }
        }
    }

    // While audio playback continues with the screen locked, nothing is on screen to
    // animate, yet the shared emote animations keep decoding frames and scheduling work
    // on the UI thread (Glide is bound to the application context, so it never pauses on
    // its own). Stop all emote animations on screen-off and resume them on screen-on.
    private val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                Intent.ACTION_SCREEN_OFF -> {
                    val isPlaying = activity.ivsPlayer?.state == Player.State.PLAYING
                    val audioMode = activity.isBackgroundAudioEnabled ||
                        activity.prefs.backgroundAudioEnabled
                    if (isPlaying && audioMode) {
                        EmoteManager.pauseAllAnimations()
                    }
                }
                Intent.ACTION_SCREEN_ON -> {
                    EmoteManager.resumeAllAnimations()
                }
            }
        }
    }

    fun registerReceivers() {
        // Defensive: if a previous activity was killed while paused (screen off), the
        // static paused flag would survive and start fresh emotes static. Clear it here.
        EmoteManager.resumeAllAnimations()

        val pipFilter = IntentFilter(activity.PIP_CONTROL_ACTION)
        ContextCompat.registerReceiver(
            activity, pipReceiver, pipFilter, ContextCompat.RECEIVER_EXPORTED
        )

        val stopFilter = IntentFilter(PlaybackService.ACTION_STOP_PLAYBACK)
        ContextCompat.registerReceiver(
            activity, stopPlaybackReceiver, stopFilter, ContextCompat.RECEIVER_EXPORTED
        )

        // SCREEN_ON/OFF are protected broadcasts that can only be received by a
        // runtime-registered receiver (not the manifest). Sender is the system, so
        // NOT_EXPORTED is the correct, safest visibility.
        val screenFilter = IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_OFF)
            addAction(Intent.ACTION_SCREEN_ON)
        }
        ContextCompat.registerReceiver(
            activity, screenReceiver, screenFilter, ContextCompat.RECEIVER_NOT_EXPORTED
        )
    }

    fun unregisterReceivers() {
        try {
            activity.unregisterReceiver(pipReceiver)
            activity.unregisterReceiver(stopPlaybackReceiver)
            activity.unregisterReceiver(screenReceiver)
        } catch (e: Exception) {
            // Ignore if not registered
        }
    }
}
