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
    companion object {
        private const val TAG = "ChatIdentityManager"

        val PROFILE_COLORS = listOf(
            "#FFD899", "#FFC466", "#FF9D00", "#FBCFD8", "#F2708A", "#E9113C", "#DEB2FF", "#BC66FF",
            "#B9D6F6", "#72ACED", "#1475E1", "#BAFEA3", "#75FD46", "#93EBE0", "#31D6C2", "#00CCB3",

            "#00F1FF", "#4CFF75", "#55FFC7", "#6F87FF", "#AAA9FF", "#BDFF28", "#E26EFF", "#E4D88F",
            "#E5FFAB", "#FEA0CF", "#FF2C56", "#FF4117", "#FF55B3", "#FFA600", "#FFAE76", "#FFFFFF"
        )
    }

    /**
     * If enabled in settings, swaps the current user's chat name color to a different random
     * color from the profile palette and persists it. Called after a chat message is sent.
     */
    fun randomizeNameColorAfterMessage(channelId: Long) {
        if (!prefs.chatRandomizeNameColorOnSend) return
        val userId = prefs.userId
        val token = prefs.authToken
        if (channelId == 0L || userId <= 0L || token == null) return

        val sender = chatStateManager.currentUserSender ?: return
        val currentColor = sender.color
        val newColor = PROFILE_COLORS.filter { !it.equals(currentColor, ignoreCase = true) }.random()

        chatStateManager.currentUserSender = sender.copy(color = newColor)

        scope.launch {
            try {
                val badges = sender.badges?.map { it.type } ?: emptyList()
                val badgesV2 = sender.badgesV2?.mapNotNull { it.name } ?: emptyList()
                repository.updateChatIdentity(channelId, userId, token, badges, badgesV2, newColor)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to randomize name color", e)
            }
        }
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
