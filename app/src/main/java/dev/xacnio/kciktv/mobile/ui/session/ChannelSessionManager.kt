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
import kotlinx.coroutines.launch
import dev.xacnio.kciktv.mobile.ui.chat.ChatStateManager
import dev.xacnio.kciktv.mobile.ui.chat.ChatUiManager

class ChannelSessionManager(private val activity: MobilePlayerActivity) {

    private val TAG = "ChannelSessionManager"

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
        
        activity.lifecycleScope.launch {
            try {
                val result = activity.repository.getChatInfo(channel.slug, activity.prefs.authToken)
                result.onSuccess { chatInfo ->
                    chatInfo.chatroomInfo?.let { state.updateChatroom(it) }
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
                    
                    // Set initial chatroom info (modes like slow mode)
                    activity.runOnUiThread {
                        activity.updateChatroomHint(chatInfo.chatroomInfo)
                    }
                    
                    // Update uptime from chatInfo if available
                    chatInfo.startTimeMillis?.let {
                        activity.playbackStatusManager.streamCreatedAtMillis = it
                        activity.playbackStatusManager.updateUptimeDisplay()
                    }

                    // Check follow status and ID via getChannelUserInfo (authoritative source)
                    var isFollowing = chatInfo.isFollowing
                    // Reset moderator/owner status for new channel
                    activity.isModeratorOrOwner = false
                    activity.isChannelOwner = false
                    activity.isBannedFromCurrentChannel = false
                    activity.isCheckingBanStatus = prefs.isLoggedIn // Start check if logged in
                    activity.runOnUiThread { 
                        binding.modMenuButton.visibility = View.GONE 
                        binding.chatBannedOverlay.visibility = View.GONE
                        // Initial update to hide input if checking
                        activity.updateChatLoginState()
                    }
                    
                    if (prefs.isLoggedIn && prefs.authToken != null) {
                        try {
                            val token = prefs.authToken!!
                            activity.userMeJob = activity.lifecycleScope.launch {
                                activity.repository.getChannelUserMe(channel.slug, token).onSuccess { me ->
                                    isFollowing = me.isFollowing == true
                                    activity.currentIsFollowing = isFollowing
                                    activity.isChannelOwner = me.isBroadcaster == true
                                    activity.isModeratorOrOwner = me.isModerator == true || activity.isChannelOwner
                                    activity.isSubscribedToCurrentChannel = me.subscription != null || activity.isChannelOwner
                                    
                                    // Sync to ChatStateManager for ChatUiManager to use
                                    state.isModeratorOrOwner = activity.isModeratorOrOwner
                                    state.isChannelOwner = activity.isChannelOwner
                                    state.isSubscribedToCurrentChannel = activity.isSubscribedToCurrentChannel
                                    state.isFollowingCurrentChannel = isFollowing
                                    state.followingSince = me.followingSince
                                    
                                    // Update Quick Emote Bar Subscription Status
                                    activity.quickEmoteBarManager.updateSubscriptionStatus(activity.isSubscribedToCurrentChannel)
                                    
                                    // Banned check from /me endpoint
                                    val banInfo = me.banned
                                    activity.isBannedFromCurrentChannel = banInfo != null
                                    activity.isPermanentBan = banInfo?.permanent == true
                                    activity.timeoutExpirationMillis = activity.parseIsoDate(banInfo?.expiresAt)
                                    
                                    // Sync ban status to ChatStateManager
                                    state.setBanStatus(activity.isBannedFromCurrentChannel, activity.isPermanentBan, activity.timeoutExpirationMillis)
                                    state.isCheckingBanStatus = false
                                    
                                    if (activity.isBannedFromCurrentChannel) {
                                        activity.addSystemMessage(activity.getString(if (activity.isPermanentBan) R.string.chat_error_banned else R.string.chat_timed_out_overlay), R.drawable.ic_block)
                                    }

                                    activity.isCheckingBanStatus = false
                                    activity.runOnUiThread {
                                        activity.updateFollowButtonState()
                                        // Update moderator menu button visibility
                                        binding.modMenuButton.visibility = if (activity.isModeratorOrOwner && prefs.isLoggedIn) View.VISIBLE else View.GONE
                                        
                                        // Update prediction UI to reflect new mod status
                                        state.currentPrediction?.let { activity.overlayManager.updatePredictionUI(it) }
                                        
                                        activity.updateChatLoginState()
                                        
                                        // Refresh chat hint with user's actual status
                                        activity.updateChatroomHint(activity.currentChatroom)
                                        
                                        // Handle Celebrations
                                        val cels = me.celebrations ?: emptyList()
                                        
                                        if (cels.isNotEmpty()) {
                                             activity.runOnUiThread {
                                                 activity.chatUiManager.handleCelebrations(cels, channel.slug)
                                             }
                                        }
                                    }
                                }.onFailure { e ->
                                    Log.e(TAG, "Failed to fetch channel users/me for status", e)
                                    activity.isCheckingBanStatus = false
                                    activity.runOnUiThread { activity.updateChatLoginState() }
                                }
                            }
                        } catch (e: Exception) {
                            Log.e(TAG, "Error initiating user/me check", e)
                            activity.isCheckingBanStatus = false
                            activity.runOnUiThread { activity.updateChatLoginState() }
                        }
                    } else {
                        activity.isCheckingBanStatus = false
                        activity.runOnUiThread { activity.updateChatLoginState() }
                    }

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
