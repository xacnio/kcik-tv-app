/**
 * File: InfoPanelManager.kt
 *
 * Description: Manages the Channel Info Panel displayed below the video player.
 * It handles the display of channel details (avatar, name, title, category) and
 * manages the Follow/Unfollow user interactions and button states.
 *
 * Author: Xacnio
 *
 */
package dev.xacnio.kciktv.mobile.ui.player

import android.util.Log
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import dev.xacnio.kciktv.mobile.MobilePlayerActivity
import dev.xacnio.kciktv.R
import dev.xacnio.kciktv.shared.data.model.ChannelItem

/**
 * Manages the info panel (channel header) and follow button logic.
 */
class InfoPanelManager(private val activity: MobilePlayerActivity) {

    private val binding get() = activity.binding
    private val prefs get() = activity.prefs
    private val channelProfileManager get() = activity.channelProfileManager

    companion object {
        private const val TAG = "InfoPanelManager"
    }

    /**
     * Sets up the follow button click listener.
     */
    fun setupFollowButton() {
        binding.infoFollowButton.setOnClickListener {
            if (!prefs.isLoggedIn) {
                Toast.makeText(activity, activity.getString(R.string.login_required_following), Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            val channel = activity.currentChannel ?: return@setOnClickListener

            if (activity.currentIsFollowing) {
                showUnfollowDialog(channel)
            } else {
                followChannel(channel)
            }
        }
    }

    /**
     * Updates the follow button visual state based on current follow status.
     */
    fun updateFollowButtonState() {
        // Hide button if user is channel owner
        if (activity.isChannelOwner) {
            binding.infoFollowButton.visibility = View.GONE
            return
        }

        // Hide button if not logged in
        if (prefs.authToken.isNullOrEmpty()) {
            binding.infoFollowButton.visibility = View.GONE
            return
        }

        // Only show button when we have follow data from API
        binding.infoFollowButton.visibility = View.VISIBLE

        val themeColorStateList = android.content.res.ColorStateList.valueOf(prefs.themeColor)

        if (activity.currentIsFollowing) {
            binding.infoFollowButton.text = activity.getString(R.string.following_status)
            binding.infoFollowButton.setIconResource(R.drawable.ic_check)
            binding.infoFollowButton.setTextColor(android.graphics.Color.parseColor("#AAAAAA"))
            binding.infoFollowButton.iconTint = themeColorStateList
            binding.infoFollowButton.backgroundTintList = android.content.res.ColorStateList.valueOf(android.graphics.Color.parseColor("#1A1A1A"))
            binding.infoFollowButton.strokeColor = android.content.res.ColorStateList.valueOf(android.graphics.Color.parseColor("#333333"))
            binding.infoFollowButton.strokeWidth = 2
        } else {
            binding.infoFollowButton.text = activity.getString(R.string.follow)
            binding.infoFollowButton.setIconResource(R.drawable.ic_heart)
            binding.infoFollowButton.setTextColor(android.graphics.Color.BLACK)
            binding.infoFollowButton.iconTint = android.content.res.ColorStateList.valueOf(android.graphics.Color.BLACK)
            binding.infoFollowButton.backgroundTintList = themeColorStateList
            binding.infoFollowButton.strokeWidth = 0
        }

        // Sync with Profile UI if visible
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

        // Follow button (now Container)
        binding.infoFollowContainer.setOnClickListener {
            val channel = activity.currentChannel ?: return@setOnClickListener
            if (!binding.infoFollowButton.isEnabled) return@setOnClickListener

            if (activity.currentIsFollowing) {
                showUnfollowDialog(channel)
            } else {
                followChannel(channel)
            }
        }
    }

    /**
     * Initiates follow action for a channel.
     */
    fun followChannel(channel: ChannelItem) {
        val token = prefs.authToken ?: return

        // Show Loading
        binding.infoFollowButton.visibility = View.INVISIBLE
        binding.infoFollowLoader.visibility = View.VISIBLE
        binding.infoFollowButton.isEnabled = false

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

        // Show Loading
        binding.infoFollowButton.visibility = View.INVISIBLE
        binding.infoFollowLoader.visibility = View.VISIBLE
        binding.infoFollowButton.isEnabled = false

        // Also update channel profile if open
        if (channelProfileManager.isChannelProfileVisible) {
            channelProfileManager.showChannelProfileFollowLoading()
        }

        // Direct WebView Bypass
        Log.d(TAG, "Starting Unfollow via WebView...")
        activity.performFollowViaWebView(channel.slug, token, false)
    }
}
