/**
 * File: InfoPanelManager.kt
 *
 * Description: Manages the Channel Info Panel displayed below the video player.
 * It handles the display of channel details (avatar, name, title, category) and
 * the shared Follow/Unfollow business logic (also used by the channel profile screen).
 *
 * Author: Xacnio
 *
 */
package dev.xacnio.kciktv.mobile.ui.player

import android.util.Log
import androidx.appcompat.app.AlertDialog
import dev.xacnio.kciktv.mobile.MobilePlayerActivity
import dev.xacnio.kciktv.R
import dev.xacnio.kciktv.shared.data.model.ChannelItem

/**
 * Manages the info panel (channel header) and follow/unfollow logic.
 */
class InfoPanelManager(private val activity: MobilePlayerActivity) {

    private val binding get() = activity.binding
    private val prefs get() = activity.prefs
    private val channelProfileManager get() = activity.channelProfileManager

    companion object {
        private const val TAG = "InfoPanelManager"
    }

    /**
     * Propagates the current follow status to the channel profile screen's own follow button.
     * The video player's info panel no longer shows its own follow button.
     */
    fun updateFollowButtonState() {
        if (activity.isChannelOwner) return
        if (prefs.authToken.isNullOrEmpty()) return
        channelProfileManager.updateChannelProfileFollowButton(activity.currentIsFollowing)
    }

    /**
     * Sets up click listeners for info panel elements.
     */
    fun setupInfoPanelListeners() {
        // Parent container click (allows clicking anywhere in the header)
        binding.infoPanelContent.setOnClickListener {
            activity.currentChannel?.slug?.let { slug -> channelProfileManager.openChannelProfile(slug) }
        }

        // Avatar click -> open profile
        binding.infoProfileImage.setOnClickListener {
            activity.currentChannel?.slug?.let { slug -> channelProfileManager.openChannelProfile(slug) }
        }

        // Channel name click -> open profile
        binding.infoChannelName.setOnClickListener {
            activity.currentChannel?.slug?.let { slug -> channelProfileManager.openChannelProfile(slug) }
        }

        // Stream title click -> open profile
        binding.infoStreamTitle.setOnClickListener {
            activity.currentChannel?.slug?.let { slug -> channelProfileManager.openChannelProfile(slug) }
        }

        // Category name click -> open profile
        binding.infoCategoryName.setOnClickListener {
            activity.currentChannel?.slug?.let { slug -> channelProfileManager.openChannelProfile(slug) }
        }

    }

    /**
     * Initiates follow action for a channel.
     */
    fun followChannel(channel: ChannelItem) {
        val token = prefs.authToken ?: return

        // Also update channel profile if open
        if (channelProfileManager.isChannelProfileVisible) {
            channelProfileManager.showChannelProfileFollowLoading()
        }

        // Direct WebView Bypass
        Log.d(TAG, "Starting Follow via WebView...")
        activity.performFollowViaWebView(channel.slug, token, true)
    }

    /**
     * Shows confirmation dialog before unfollowing.
     */
    fun showUnfollowDialog(channel: ChannelItem) {
        AlertDialog.Builder(activity)
            .setTitle(activity.getString(R.string.unfollow_confirm_title, channel.username))
            .setPositiveButton(R.string.yes) { _, _ ->
                unfollowChannel(channel)
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    /**
     * Initiates unfollow action for a channel.
     */
    private fun unfollowChannel(channel: ChannelItem) {
        val token = prefs.authToken ?: return

        // Also update channel profile if open
        if (channelProfileManager.isChannelProfileVisible) {
            channelProfileManager.showChannelProfileFollowLoading()
        }

        // Direct WebView Bypass
        Log.d(TAG, "Starting Unfollow via WebView...")
        activity.performFollowViaWebView(channel.slug, token, false)
    }
}
