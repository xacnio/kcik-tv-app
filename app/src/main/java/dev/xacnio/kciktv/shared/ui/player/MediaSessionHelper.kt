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
import android.support.v4.media.MediaMetadataCompat
import android.support.v4.media.session.MediaSessionCompat
import android.support.v4.media.session.PlaybackStateCompat
import dev.xacnio.kciktv.shared.data.model.ChannelItem

/**
 * Helper class to manage MediaSession for background audio and PIP controls.
 */
class MediaSessionHelper(
    private val context: Context,
    private val listener: MediaSessionListener
) {
    private var mediaSession: MediaSessionCompat? = null

    interface MediaSessionListener {
        fun onPlayRequested()
        fun onPauseRequested()
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
            })
            isActive = true
        }
    }

    fun updateMediaSessionState(isPlaying: Boolean, channel: ChannelItem?, profileBitmap: Bitmap?) {
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

        val stateBuilder = PlaybackStateCompat.Builder()
            .setActions(
                PlaybackStateCompat.ACTION_PLAY or
                        PlaybackStateCompat.ACTION_PAUSE or
                        PlaybackStateCompat.ACTION_PLAY_PAUSE
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
