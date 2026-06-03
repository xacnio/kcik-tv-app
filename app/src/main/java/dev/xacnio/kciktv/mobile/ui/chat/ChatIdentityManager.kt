/**
 * File: ChatIdentityManager.kt
 *
 * Description: Responsible for fetching and maintaining the current user's chat identity.
 * It retrieves user-specific data such as username color, badges, and subscription status
 * for the current channel, ensuring the user is correctly represented in the chat.
 *
 * Author: Xacnio
 *
 */
package dev.xacnio.kciktv.mobile.ui.chat

import dev.xacnio.kciktv.mobile.ui.chat.ChatStateManager

import android.util.Log
import dev.xacnio.kciktv.shared.data.model.ChatBadge
import dev.xacnio.kciktv.shared.data.model.ChatSender
import dev.xacnio.kciktv.shared.data.prefs.AppPreferences
import dev.xacnio.kciktv.shared.data.repository.ChannelRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * Manages chat identity and subscription status.
 */
class ChatIdentityManager(
    private val repository: ChannelRepository,
    private val prefs: AppPreferences,
    private val scope: CoroutineScope,
    private val chatStateManager: ChatStateManager,
    private val onSubscriptionUpdate: () -> Unit
) {
    private companion object {
        const val TAG = "ChatIdentityManager"
    }

    fun fetchChatIdentity(channelId: Long) {
        if (!prefs.isLoggedIn) {
            updateState(false, null)
            return
        }

        val currentUserId = prefs.userId
        if (currentUserId > 0) {
            scope.launch {
                repository.getChatIdentity(channelId, currentUserId, prefs.authToken)
                    .onSuccess { response ->
                        val identity = response.data?.identity

                        val isSubscribed = identity?.badges?.any {
                            it.type == "subscriber"
                        } == true

                        val sender = ChatSender(
                            id = currentUserId,
                            username = prefs.username ?: "Me",
                            color = identity?.color ?: "#53fc18",
                            badges = identity?.badges
                                ?.filter { it.active == true }
                                ?.sortedBy { it.sortOrder ?: Int.MAX_VALUE }
                                ?.map { ChatBadge(type = it.type ?: "", text = it.text, count = it.count) }
                                ?: emptyList(),
                            badgesV2 = identity?.badgesV2
                                ?.sortedBy { it.sortOrder ?: Int.MAX_VALUE }
                                ?.map {
                                    dev.xacnio.kciktv.shared.data.model.ChatBadgeV2(
                                        name = it.name,
                                        imageUrl = it.imageUrl,
                                        selected = it.selected,
                                        sortOrder = it.sortOrder
                                    )
                                }
                        )
                        
                        updateState(isSubscribed, sender)
                        onSubscriptionUpdate()
                        
                    }.onFailure {
                        Log.e(TAG, "Failed to fetch chat identity", it)
                    }
            }
        } else {
            updateState(false, null)
        }
    }
    
    private fun updateState(isSubscribed: Boolean, sender: ChatSender?) {
        chatStateManager.isSubscribedToCurrentChannel = isSubscribed
        chatStateManager.currentUserSender = sender
    }
}
