/**
 * File: PipManager.kt
 *
 * Description: Manages Android's System Picture-in-Picture (PiP) mode.
 * It constructs the PiP parameters (aspect ratio, actions), handles the `enterPictureInPictureMode` call,
 * and manages user triggers like the "Home" button leave hint.
 *
 * Author: Xacnio
 *
 */
package dev.xacnio.kciktv.mobile.ui.player

import android.app.PendingIntent
import android.content.Intent
import android.os.Build
import android.util.Log
import androidx.annotation.RequiresApi
import com.amazonaws.ivs.player.Player
import dev.xacnio.kciktv.mobile.MobilePlayerActivity
import dev.xacnio.kciktv.R

/**
 * Manages Picture-in-Picture (PiP) mode functionality.
 */
class PipManager(private val activity: MobilePlayerActivity) {

    private val prefs get() = activity.prefs
    private val ivsPlayer get() = activity.ivsPlayer

    companion object {
        private const val TAG = "PipManager"
    }

    /**
     * Handles user leave hint to potentially enter PiP mode.
     */
    fun handleUserLeaveHint() {
        // Ensure we exit mini-player mode before entering PIP to avoid UI glitches
        if (activity.miniPlayerManager.isMiniPlayerMode) {
            // Signal to PipStateManager that we were in mini-player mode before exiting it here
            activity.pipStateManager.setWasMiniPlayerModeBeforePip(true)
            activity.exitMiniPlayerMode()
        }

        // Enter PIP when user presses home button IF auto-pip is enabled
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && prefs.autoPipEnabled) {
            val isPlaying = ivsPlayer?.state == Player.State.PLAYING
            if (isPlaying) {
                enterPipMode()
            }
        }
    }

    /**
     * Enters Picture-in-Picture mode.
     */
    fun enterPipMode() {
        // Theatre mode check removed to allow PiP
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            try {
                // Ensure UI is prepared BEFORE entering PiP so the system snapshot is clean
                activity.pipStateManager.onPictureInPictureModeChanged(true)
                
                val params = getPipParams()
                activity.enterPictureInPictureMode(params)
                
                // Log analytics event
                activity.analytics.logPipModeEntered()

                // If low battery mode is enabled, pause chat
                if (prefs.lowBatteryModeEnabled) {
                    activity.pauseChatForLowBatteryMode()
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to enter PIP mode: ${e.message}")
            }
        }
    }

    /**
     * Updates the PiP UI with current state.
     */
    fun updatePiPUi(overrideIsPlaying: Boolean? = null) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            try {
                activity.setPictureInPictureParams(getPipParams(overrideIsPlaying))
            } catch (e: Exception) {
                // Ignore
            }
        }
    }

    /**
     * Builds PiP parameters with remote actions.
     */
    @RequiresApi(Build.VERSION_CODES.O)
    fun getPipParams(overrideIsPlaying: Boolean? = null): android.app.PictureInPictureParams {
        var quality = ivsPlayer?.quality
        val width = quality?.width ?: 0
        val height = quality?.height ?: 0
        
        val aspectRatio = if (width > 0 && height > 0) {
             val ratio = width.toFloat() / height.toFloat()
             // Clamp aspect ratio between 1/2.39 and 2.39/1 (Android Limits)
             val minRatio = 1 / 2.39f
             val maxRatio = 2.39f
             
             if (ratio < minRatio) {
                 android.util.Rational(100, 239)
             } else if (ratio > maxRatio) {
                 android.util.Rational(239, 100)
             } else {
                 android.util.Rational(width, height)
             }
        } else {
             android.util.Rational(16, 9)
        }
        val actions = mutableListOf<android.app.RemoteAction>()

        // 1. Mute Action
        val muteIntent = Intent(activity.PIP_CONTROL_ACTION).apply {
            setPackage(activity.packageName)
            putExtra(activity.EXTRA_CONTROL_TYPE, activity.CONTROL_TYPE_MUTE)
        }
        val mutePendingIntent = PendingIntent.getBroadcast(
            activity,
            activity.CONTROL_TYPE_MUTE,
            muteIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val isMuted = ivsPlayer?.volume == 0f
        val muteIconRes = if (isMuted) R.drawable.ic_volume_off else R.drawable.ic_volume

        actions.add(
            android.app.RemoteAction(
                android.graphics.drawable.Icon.createWithResource(activity, muteIconRes),
                if (isMuted) activity.getString(R.string.pip_action_unmute) else activity.getString(R.string.pip_action_mute),
                if (isMuted) activity.getString(R.string.pip_action_unmute) else activity.getString(R.string.pip_action_mute),
                mutePendingIntent
            )
        )

        // 2. Play/Pause Action
        val isPlaying = overrideIsPlaying ?: (ivsPlayer?.state == Player.State.PLAYING)
        val iconRes = if (isPlaying) android.R.drawable.ic_media_pause else android.R.drawable.ic_media_play
        val title = if (isPlaying) activity.getString(R.string.pause) else activity.getString(R.string.play)

        val intent = Intent(activity.PIP_CONTROL_ACTION).apply {
            setPackage(activity.packageName)
            putExtra(activity.EXTRA_CONTROL_TYPE, activity.CONTROL_TYPE_PLAY_PAUSE)
        }
        val pendingIntent = PendingIntent.getBroadcast(
            activity,
            activity.CONTROL_TYPE_PLAY_PAUSE,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        actions.add(
            android.app.RemoteAction(
                android.graphics.drawable.Icon.createWithResource(activity, iconRes),
                title,
                title,
                pendingIntent
            )
        )

        // 3. Audio Only Action
        val audioIntent = Intent(activity.PIP_CONTROL_ACTION).apply {
            setPackage(activity.packageName)
            putExtra(activity.EXTRA_CONTROL_TYPE, activity.CONTROL_TYPE_AUDIO_ONLY)
        }
        val audioPendingIntent = PendingIntent.getBroadcast(
            activity,
            activity.CONTROL_TYPE_AUDIO_ONLY,
            audioIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        actions.add(
            android.app.RemoteAction(
                android.graphics.drawable.Icon.createWithResource(activity, R.drawable.ic_pip_headset),
                activity.getString(R.string.pip_action_audio_only),
                activity.getString(R.string.pip_action_audio_only_desc),
                audioPendingIntent
            )
        )

        val builder = android.app.PictureInPictureParams.Builder()
            .setAspectRatio(aspectRatio)
            .setActions(actions)
        
        // Android 12+ (API 31): Enable auto-enter PIP when app goes to background
        // Theatre Mode: Disable auto-enter PiP explicitly to prevent unwanted transitions
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && prefs.autoPipEnabled) {
            // Theatre mode check removed from autoEnter logic
            val autoEnter = isPlaying
            android.util.Log.d("PipManager", "getPipParams: setAutoEnterEnabled($autoEnter) - isPlaying=$isPlaying")
            builder.setAutoEnterEnabled(autoEnter)
        }
        
        return builder.build()
    }
}
