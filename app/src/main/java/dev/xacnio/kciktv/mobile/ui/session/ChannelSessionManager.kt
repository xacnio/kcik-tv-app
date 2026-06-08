/**
 * File: ChannelSessionManager.kt
 *
 * Description: Manages business logic, state, and UI interactions for Channel Session.
 *
 * Author: Xacnio
 *
 */
package dev.xacnio.kciktv.mobile.ui.session

import android.util.Log
import android.view.View
import androidx.lifecycle.lifecycleScope
import dev.xacnio.kciktv.mobile.MobilePlayerActivity
import dev.xacnio.kciktv.R
import dev.xacnio.kciktv.shared.data.model.ChannelItem
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.launch
import dev.xacnio.kciktv.mobile.ui.chat.ChatStateManager
import dev.xacnio.kciktv.mobile.ui.chat.ChatUiManager
import dev.xacnio.kciktv.shared.data.model.ChannelUserMeResponse
import dev.xacnio.kciktv.shared.data.repository.ChannelRepository

class ChannelSessionManager(private val activity: MobilePlayerActivity) {

    private val TAG = "ChannelSessionManager"

    // Set by ChannelLoadManager when it pre-fetches these calls in parallel with getChannelDetails.
    // Consumed (and cleared) at the start of startChannelSession to avoid duplicate network calls.
    var pendingChatInfoDeferred: Deferred<Result<ChannelRepository.ChatInfo>>? = null
    var pendingUserMeDeferred: Deferred<Result<ChannelUserMeResponse>>? = null

    fun reconnectForAccountSwitch() {
        val channel = activity.currentChannel ?: return
        val chatroomId = activity.chatStateManager.currentChatroomId ?: return
        val channelId = activity.currentChannelId ?: return
        val binding = activity.binding
        val prefs = activity.prefs
        val state = activity.chatStateManager

        activity.userMeJob?.cancel()
        activity.chatConnectionManager.disconnect()
        activity.overlayManager.disconnectViewerWebSocket()

        // Reset slow mode for the new account (old account's send time is irrelevant)
        state.lastMessageSentMillis = 0
        state.stopSlowModeCountdown()

        // Reconnect chat and viewer WebSockets with the new account's token
        activity.chatConnectionManager.connectToChat(prefs.authToken ?: "", chatroomId, channelId)
        activity.startViewerWebSocket(channelId.toString(), channel.slug, channel.livestreamId?.toString())

        // Fetch /me in background to update mod/subscription/ban status
        if (prefs.isLoggedIn && prefs.authToken != null) {
            activity.isCheckingBanStatus = true
            activity.runOnUiThread { activity.updateChatLoginState() }
            activity.userMeJob = activity.lifecycleScope.launch {
                try {
                    val token = prefs.authToken ?: return@launch
                    activity.repository.getChannelUserMe(channel.slug, token).onSuccess { me ->
                        activity.isChannelOwner = me.isBroadcaster == true
                        activity.isModeratorOrOwner = me.isModerator == true || activity.isChannelOwner
                        activity.isSubscribedToCurrentChannel = me.subscription != null || activity.isChannelOwner

                        state.isModeratorOrOwner = activity.isModeratorOrOwner
                        state.isChannelOwner = activity.isChannelOwner
                        state.isSubscribedToCurrentChannel = activity.isSubscribedToCurrentChannel
                        state.isFollowingCurrentChannel = me.isFollowing == true
                        state.followingSince = me.followingSince

                        val banInfo = me.banned
                        activity.isBannedFromCurrentChannel = banInfo != null
                        activity.isPermanentBan = banInfo?.permanent == true
                        activity.timeoutExpirationMillis = activity.parseIsoDate(banInfo?.expiresAt)
                        state.setBanStatus(activity.isBannedFromCurrentChannel, activity.isPermanentBan, activity.timeoutExpirationMillis)
                        state.isCheckingBanStatus = false
                        activity.isCheckingBanStatus = false

                        activity.runOnUiThread {
                            val isMod = activity.isModeratorOrOwner && prefs.isLoggedIn
                            binding.modMenuButton.visibility = if (isMod) View.VISIBLE else View.GONE
                            binding.rewardQueueButton.visibility = if (isMod) View.VISIBLE else View.GONE
                            binding.activityFeedButton.visibility = if (isMod) View.VISIBLE else View.GONE
                            binding.modLogButton.visibility = if (isMod) View.VISIBLE else View.GONE
                            if (isMod) {
                                val slug = activity.currentChannel?.slug ?: ""
                                if (slug.isNotEmpty()) {
                                    activity.activityFeedManager.prefetch(slug)
                                    activity.rewardQueueManager.prefetchData()
                                    activity.modLogManager.prefetch()
                                }
                            }
                            activity.updateChatLoginState()
                            activity.updateQuickEmoteBar()
                            activity.updateChatroomHint(activity.currentChatroom)
                        }
                    }.onFailure {
                        state.isCheckingBanStatus = false
                        activity.isCheckingBanStatus = false
                        activity.runOnUiThread { activity.updateChatLoginState() }
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to fetch /me after account switch", e)
                    state.isCheckingBanStatus = false
                    activity.isCheckingBanStatus = false
                    activity.runOnUiThread { activity.updateChatLoginState() }
                }
            }
        } else {
            activity.runOnUiThread { activity.updateChatLoginState() }
        }

        // Reload emotes silently (no shimmer, no message clear)
        activity.lifecycleScope.launch {
            activity.emotePanelManager.loadChannelEmotes(channel.slug)
        }
    }

    fun startChannelSession(channel: ChannelItem) {
        val binding = activity.binding
        val prefs = activity.prefs
        val state = activity.chatStateManager
        
        // Disconnect old chat
        activity.userMeJob?.cancel()
        activity.chatConnectionManager.disconnect()
        activity.overlayManager.disconnectViewerWebSocket()
        activity.chatUiManager.reset()
        // Messages cleared in reset(), redundant clear removed
        
        // Clear any previous chat error
        activity.currentChatErrorMessage = null
        binding.chatErrorContainer.visibility = View.GONE
        
        // Reset ChatStateManager for new channel (clears chatroom, user state, etc.)
        state.resetForNewChannel()
        // Activity-level poll field
        activity.currentPoll = null
        activity.overlayManager.resetForNewChannel()
        activity.rewardQueueManager.resetForChannel(channel.slug)
        activity.activityFeedManager.resetCache()
        activity.modLogManager.resetCache()

        // Clear stale emote categories immediately so the quick bar shows shimmer
        // (not the previous channel's emotes) while the new session loads
        activity.emotePanelManager.resetForNewChannel()
        if (prefs.isLoggedIn) {
            binding.quickEmoteBarContainer.visibility = View.VISIBLE
            binding.quickEmoteShimmer.root.visibility = View.VISIBLE
            binding.quickEmoteRecyclerView.visibility = View.GONE
        } else {
            binding.quickEmoteBarContainer.visibility = View.GONE
        }

        binding.pinnedMessageContainer.visibility = View.GONE
        binding.pollContainer.visibility = View.GONE
        binding.predictionContainer.visibility = View.GONE
        binding.restorePinnedMessage.visibility = View.GONE
        binding.restorePoll.visibility = View.GONE
        activity.updateChatOverlayState()
        
        // Show Loading Bar
        binding.chatShimmer.root.visibility = View.VISIBLE
        binding.chatRecyclerView.visibility = View.GONE
        binding.emptyChatText.visibility = View.GONE

        // Reset mod/ban state and hide mod UI immediately for the new channel
        activity.isModeratorOrOwner = false
        activity.isChannelOwner = false
        activity.isBannedFromCurrentChannel = false
        binding.modMenuButton.visibility = View.GONE
        binding.rewardQueueButton.visibility = View.GONE
        binding.activityFeedButton.visibility = View.GONE
        binding.modLogButton.visibility = View.GONE
        binding.chatBannedOverlay.visibility = View.GONE

        // Fire /me immediately, parallel to getChatInfo — only needs channel slug
        activity.isCheckingBanStatus = prefs.isLoggedIn && prefs.authToken != null
        activity.updateChatLoginState()
        if (prefs.isLoggedIn && prefs.authToken != null) {
            val meToken = prefs.authToken!!
            val capturedUserMeDeferred = pendingUserMeDeferred
            pendingUserMeDeferred = null
            activity.userMeJob = activity.lifecycleScope.launch {
                try {
                    val meResult = capturedUserMeDeferred?.await()
                        ?: activity.repository.getChannelUserMe(channel.slug, meToken)
                    meResult.onSuccess { me ->
                        val isFollowingMe = me.isFollowing == true
                        activity.currentIsFollowing = isFollowingMe
                        activity.isChannelOwner = me.isBroadcaster == true
                        activity.isModeratorOrOwner = me.isModerator == true || activity.isChannelOwner
                        activity.isSubscribedToCurrentChannel = me.subscription != null || activity.isChannelOwner

                        state.isModeratorOrOwner = activity.isModeratorOrOwner
                        state.isChannelOwner = activity.isChannelOwner
                        state.isSubscribedToCurrentChannel = activity.isSubscribedToCurrentChannel
                        state.isFollowingCurrentChannel = isFollowingMe
                        state.followingSince = me.followingSince

                        activity.quickEmoteBarManager.updateSubscriptionStatus(activity.isSubscribedToCurrentChannel)

                        val banInfo = me.banned
                        activity.isBannedFromCurrentChannel = banInfo != null
                        activity.isPermanentBan = banInfo?.permanent == true
                        activity.timeoutExpirationMillis = activity.parseIsoDate(banInfo?.expiresAt)

                        state.setBanStatus(activity.isBannedFromCurrentChannel, activity.isPermanentBan, activity.timeoutExpirationMillis)
                        state.isCheckingBanStatus = false
                        activity.isCheckingBanStatus = false

                        if (activity.isBannedFromCurrentChannel) {
                            activity.addSystemMessage(
                                activity.getString(if (activity.isPermanentBan) R.string.chat_error_banned else R.string.chat_timed_out_overlay),
                                R.drawable.ic_block
                            )
                        }

                        activity.runOnUiThread {
                            activity.updateFollowButtonState()
                            val isMod = activity.isModeratorOrOwner && prefs.isLoggedIn
                            binding.modMenuButton.visibility = if (isMod) View.VISIBLE else View.GONE
                            binding.rewardQueueButton.visibility = if (isMod) View.VISIBLE else View.GONE
                            binding.activityFeedButton.visibility = if (isMod) View.VISIBLE else View.GONE
                            binding.modLogButton.visibility = if (isMod) View.VISIBLE else View.GONE
                            if (isMod) {
                                activity.activityFeedManager.prefetch(channel.slug)
                                activity.rewardQueueManager.prefetchData()
                                activity.modLogManager.prefetch()
                            }
                            state.currentPrediction?.let { activity.overlayManager.updatePredictionUI(it) }
                            activity.updateChatLoginState()
                            activity.updateQuickEmoteBar()
                            activity.updateChatroomHint(activity.currentChatroom)
                            val cels = me.celebrations ?: emptyList()
                            if (cels.isNotEmpty()) {
                                activity.chatUiManager.handleCelebrations(cels, channel.slug)
                            }
                        }
                    }.onFailure { e ->
                        Log.e(TAG, "Failed to fetch channel users/me for status", e)
                        state.isCheckingBanStatus = false
                        activity.isCheckingBanStatus = false
                        activity.runOnUiThread { activity.updateChatLoginState() }
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Error initiating user/me check", e)
                    state.isCheckingBanStatus = false
                    activity.isCheckingBanStatus = false
                    activity.runOnUiThread { activity.updateChatLoginState() }
                }
            }
        }

        val capturedChatInfoDeferred = pendingChatInfoDeferred
        pendingChatInfoDeferred = null
        activity.lifecycleScope.launch {
            try {
                val result = capturedChatInfoDeferred?.await()
                    ?: activity.repository.getChatInfo(channel.slug, activity.prefs.authToken)
                result.onSuccess { chatInfo ->
                    state.subscriberBadges = chatInfo.subscriberBadges
                    activity.sentMessageRefs.clear() // Clear queue on channel change
                    
                    // Update numeric channel ID for emote locking logic
                    activity.runOnUiThread {
                        // Show Quick Emote Shimmer (only for logged in users)
                        if (prefs.isLoggedIn) {
                            binding.quickEmoteBarContainer.visibility = View.VISIBLE
                            binding.quickEmoteShimmer.root.visibility = View.VISIBLE
                            binding.quickEmoteRecyclerView.visibility = View.GONE
                        } else {
                            binding.quickEmoteBarContainer.visibility = View.GONE
                        }
                        
                        val channelIdLong = chatInfo.channelId

                        activity.emotePanelManager.updateCurrentChannelId(channelIdLong)
                        activity.quickEmoteBarManager.updateCurrentChannelId(channelIdLong)
                    }

                    // Fetch accurate chat settings from web.kick.com and override stale values
                    val channelId = chatInfo.channelId
                    if (channelId != null && channelId > 0) {
                        try {
                            activity.repository.getChatSettings(channelId).onSuccess { settings ->
                                state.chatSettings = settings
                                val current = chatInfo.chatroomInfo
                                val patched = current?.copy(
                                    slowMode = settings.slowMode?.enabled,
                                    slowModeInterval = settings.slowMode?.durationSeconds,
                                    followersMode = settings.followersOnlyMode?.enabled,
                                    followersMinDuration = settings.followersOnlyMode
                                        ?.durationSeconds?.let { (it / 60).coerceAtLeast(0) },
                                    subscribersMode = settings.subscribersOnlyMode?.enabled,
                                    emotesMode = settings.emotesOnlyMode?.enabled
                                )
                                activity.runOnUiThread {
                                    if (patched != null) {
                                        state.updateChatroom(patched)
                                        activity.updateChatroomHint(patched)
                                    }
                                }
                            }
                        } catch (e: Exception) {
                            Log.w(TAG, "Chat settings fetch failed", e)
                        }
                    }
                    
                    // Update uptime from chatInfo if available
                    chatInfo.startTimeMillis?.let {
                        activity.playbackStatusManager.streamCreatedAtMillis = it
                        activity.playbackStatusManager.updateUptimeDisplay()
                    }

                    // Follow status from chatInfo (used for updateContextualChannelInfo below);
                    // authoritative value comes from the parallel /me call via activity.currentIsFollowing
                    var isFollowing = chatInfo.isFollowing

                    // If offline and owner, fetch fresh metadata from stream-info API
                    if (!channel.isLive && activity.isChannelOwner) {
                        activity.lifecycleScope.launch {
                            activity.repository.getStreamInfo(channel.slug, prefs.authToken).onSuccess { info ->
                                activity.runOnUiThread {
                                    val index = activity.allChannels.indexOfFirst { it.slug == channel.slug }
                                    if (index != -1) {
                                        val old = activity.allChannels[index]
                                        activity.allChannels[index] = old.copy(
                                            title = info.streamTitle ?: old.title,
                                            categoryName = info.category?.name ?: old.categoryName,
                                            categorySlug = info.category?.slug ?: old.categorySlug,
                                            categoryId = info.category?.id ?: old.categoryId
                                        )
                                        // Update the main UI fields if this is still the current channel
                                        if (activity.currentChannelIndex == index) {
                                            binding.infoStreamTitle.text = activity.allChannels[index].title
                                            binding.infoCategoryName.text = activity.allChannels[index].categoryName ?: activity.getString(R.string.off)
                                        }
                                    }
                                }
                            }.onFailure { e ->
                                Log.e(TAG, "Failed to fetch offline stream info in connectToChat", e)
                            }
                        }
                    }

                    activity.runOnUiThread {
                        activity.updateContextualChannelInfo(channel.username, chatInfo.verified, chatInfo.followersCount, isFollowing)
                    }

                    // Check subscription status and fetch user identity if logged in
                    // Load chat identity using manager
                    activity.currentChannelId = chatInfo.channelId
                    activity.chatIdentityManager.fetchChatIdentity(chatInfo.channelId)

                    // Load emotes for this channel
                    launch {
                        activity.emotePanelManager.loadChannelEmotes(channel.slug)
                    }

                    activity.runOnUiThread {
                        // Reset disconnection state for new channel - don't show messages on first connect
                        activity.chatWasDisconnected = false
                        
                        // Start buffering via Manager
                        activity.chatUiManager.startFlushing()

                        Log.d(TAG, "Initiating chat connection: chatroom=${chatInfo.chatroomId}, channel=${chatInfo.channelId}")
                        activity.chatConnectionManager.connectToChat(prefs.authToken ?: "", chatInfo.chatroomId, chatInfo.channelId)

                        activity.runOnUiThread {
                            activity.chatAdapter.setSubscriberBadges(state.subscriberBadges)
                        }

                        // Load chat history using manager
                        activity.chatUiManager.loadChatHistory(chatInfo.channelId)
                        
                        // Fetch Loyalty Points and Pinned Gifts
                        activity.runOnUiThread {
                            activity.fetchLoyaltyPoints()
                            activity.overlayManager.fetchPinnedGifts(chatInfo.channelId)
                        }

                        // Start Viewer WebSocket
                        activity.startViewerWebSocket(chatInfo.channelId.toString(), channel.slug, channel.livestreamId?.toString())
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to connect to chat", e)
            }
        }
    }
}
