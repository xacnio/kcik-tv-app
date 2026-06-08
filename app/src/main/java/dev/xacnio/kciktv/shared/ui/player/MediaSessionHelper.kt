/**
 * File: MediaSessionHelper.kt
 *
 * Description: Implementation of Media Session Helper functionality.
 *
 * Author: Xacnio
 *
 */
package dev.xacnio.kciktv.shared.ui.player

import android.content.Context
import android.graphics.Bitmap
import android.os.Bundle
import android.support.v4.media.MediaMetadataCompat
import android.support.v4.media.session.MediaSessionCompat
import android.support.v4.media.session.PlaybackStateCompat
import dev.xacnio.kciktv.R
import dev.xacnio.kciktv.shared.data.model.ChannelItem

/**
 * Helper class to manage MediaSession for background audio and PIP controls.
 */
class MediaSessionHelper(
    private val context: Context,
    private val listener: MediaSessionListener
) {
    private var mediaSession: MediaSessionCompat? = null

    companion object {
        const val CUSTOM_ACTION_MUTE = "dev.xacnio.kciktv.action.MUTE"
        const val CUSTOM_ACTION_STOP = "dev.xacnio.kciktv.action.STOP"
    }

    interface MediaSessionListener {
        fun onPlayRequested()
        fun onPauseRequested()
        fun onMuteRequested()
        fun onStopRequested()
    }

    fun setupMediaSession() {
        mediaSession = MediaSessionCompat(context, "MobileKcikTV").apply {
            setCallback(object : MediaSessionCompat.Callback() {
                override fun onPlay() {
                    listener.onPlayRequested()
                }
                override fun onPause() {
                    listener.onPauseRequested()
                }
                override fun onStop() {
                    listener.onStopRequested()
                }
                override fun onCustomAction(action: String?, extras: Bundle?) {
                    when (action) {
                        CUSTOM_ACTION_MUTE -> listener.onMuteRequested()
                        CUSTOM_ACTION_STOP -> listener.onStopRequested()
                    }
                }
            })
            isActive = true
        }
    }

    fun updateMediaSessionState(isPlaying: Boolean, isMuted: Boolean, channel: ChannelItem?, profileBitmap: Bitmap?) {
        val state = if (isPlaying) PlaybackStateCompat.STATE_PLAYING else PlaybackStateCompat.STATE_PAUSED

        channel?.let { ch ->
            val metadataBuilder = MediaMetadataCompat.Builder()
                .putString(MediaMetadataCompat.METADATA_KEY_TITLE, ch.username)
                .putString(MediaMetadataCompat.METADATA_KEY_ARTIST, ch.title)

            profileBitmap?.let {
                metadataBuilder.putBitmap(MediaMetadataCompat.METADATA_KEY_ALBUM_ART, it)
            }

            mediaSession?.setMetadata(metadataBuilder.build())
        }

        val muteIcon = if (isMuted) R.drawable.ic_volume_off else R.drawable.ic_volume
        val muteLabel = if (isMuted) "Unmute" else "Mute"

        val stateBuilder = PlaybackStateCompat.Builder()
            .setActions(
                PlaybackStateCompat.ACTION_PLAY or
                        PlaybackStateCompat.ACTION_PAUSE or
                        PlaybackStateCompat.ACTION_PLAY_PAUSE or
                        PlaybackStateCompat.ACTION_STOP
            )
            .addCustomAction(
                PlaybackStateCompat.CustomAction.Builder(CUSTOM_ACTION_MUTE, muteLabel, muteIcon).build()
            )
            .addCustomAction(
                PlaybackStateCompat.CustomAction.Builder(CUSTOM_ACTION_STOP, "Stop", R.drawable.ic_close).build()
            )
            .setState(state, PlaybackStateCompat.PLAYBACK_POSITION_UNKNOWN, 1.0f)

        mediaSession?.setPlaybackState(stateBuilder.build())
    }


    fun setStopped() {
        val stateBuilder = PlaybackStateCompat.Builder()
            .setState(PlaybackStateCompat.STATE_STOPPED, PlaybackStateCompat.PLAYBACK_POSITION_UNKNOWN, 1.0f)
        mediaSession?.setPlaybackState(stateBuilder.build())
    }

    fun release() {
        mediaSession?.release()
        mediaSession = null
    }
    
    fun getSessionToken(): MediaSessionCompat.Token? {
        return mediaSession?.sessionToken
    }
}
