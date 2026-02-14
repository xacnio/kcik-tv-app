/**
 * File: ChannelLoadManager.kt
 *
 * Description: Facilitates the loading of channel data by slug. It interfaces with the repository
 * to fetch channel details, converts the response into a usable domain model, and initiates the
 * channel playback or profile view upon successful data retrieval.
 *
 * Author: Xacnio
 *
 */
package dev.xacnio.kciktv.mobile.ui.channel

import android.util.Log
import android.widget.Toast
import androidx.lifecycle.lifecycleScope
import dev.xacnio.kciktv.mobile.MobilePlayerActivity
import dev.xacnio.kciktv.R
import dev.xacnio.kciktv.shared.data.model.ChannelItem
import kotlinx.coroutines.launch

/**
 * Manages channel loading operations.
 */
class ChannelLoadManager(private val activity: MobilePlayerActivity) {

    private val prefs get() = activity.prefs
    private val repository get() = activity.repository

    companion object {
        private const val TAG = "ChannelLoadManager"
    }

    /**
     * Loads a channel by its slug and starts playing it.
     */
    fun loadChannelBySlug(slug: String) {
        activity.lifecycleScope.launch {
            activity.showLoading()

            val result = repository.getChannelDetails(slug)
            result.onSuccess { channelDetail ->
                // Create a ChannelItem from the channel detail
                val newChannel = ChannelItem(
                    id = (channelDetail.id ?: 0L).toString(),
                    slug = channelDetail.slug ?: slug,
                    username = channelDetail.user?.username ?: slug,
                    title = channelDetail.livestream?.sessionTitle ?: "",
                    categoryName = channelDetail.livestream?.categories?.firstOrNull()?.name,
                    categorySlug = channelDetail.livestream?.categories?.firstOrNull()?.slug,
                    viewerCount = channelDetail.livestream?.viewerCount ?: 0,
                    isLive = channelDetail.livestream != null,
                    profilePicUrl = channelDetail.user?.profilePic,
                    thumbnailUrl = channelDetail.livestream?.thumbnail?.url,
                    playbackUrl = channelDetail.playbackUrl,
                    language = null
                )

                activity.runOnUiThread {
                    activity.isSubscriptionEnabled = channelDetail.subscriptionEnabled == true
                    activity.hideLoading()
                    activity.playChannel(newChannel)
                }
            }.onFailure {
                activity.runOnUiThread {
                    activity.hideLoading()
                    Toast.makeText(
                        activity,
                        activity.getString(R.string.channel_not_found, slug),
                        Toast.LENGTH_SHORT
                    ).show()
                }
            }
        }
    }

    /**
     * Replays the current channel.
     */
    fun playCurrentChannel() {
        activity.currentChannel?.let {
            activity.playChannel(it)
        }
    }

    /**
     * Fetches bans for the current channel.
     */
    fun fetchCurrentChannelBans(slug: String) {
        val token = prefs.authToken ?: return
        activity.lifecycleScope.launch {
            repository.getBans(slug, token).onSuccess { bans ->
                activity.currentChannelBans = bans
            }.onFailure {
                Log.e(TAG, "Failed to fetch bans for $slug")
                activity.currentChannelBans = emptyList()
            }
        }
    }
}
