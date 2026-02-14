/**
 * File: ChatSystemMessageManager.kt
 *
 * Description: Handles the injection of local system and information messages into the chat stream.
 * It provides methods to insert administrative notices or feedback directly into the message list
 * and ensures the chat view is properly scrolled to display new updates.
 *
 * Author: Xacnio
 *
 */
package dev.xacnio.kciktv.mobile.ui.chat

import dev.xacnio.kciktv.mobile.MobilePlayerActivity
import dev.xacnio.kciktv.R
import dev.xacnio.kciktv.shared.data.model.ChatMessage
import dev.xacnio.kciktv.shared.data.model.ChatSender
import dev.xacnio.kciktv.shared.data.model.MessageType

/**
 * Manages chat system messages and scroll functionality.
 */
class ChatSystemMessageManager(private val activity: MobilePlayerActivity) {

    private val binding get() = activity.binding
    private val chatUiManager get() = activity.chatUiManager
    private val chatAdapter get() = activity.chatAdapter

    /**
     * Adds a system message to the chat.
     */
    fun addSystemMessage(content: String, iconResId: Int? = null) {
        activity.runOnUiThread {
            val systemMessage = ChatMessage(
                id = "system_${System.nanoTime()}",
                content = content,
                sender = ChatSender(0, activity.getString(R.string.chat_system_username), null, null),
                type = MessageType.SYSTEM,
                iconResId = iconResId
            )
            chatUiManager.chatAdapter.addMessages(listOf(systemMessage))
            if (chatUiManager.isChatAutoScrollEnabled) {
                scrollToBottom()
            }
        }
    }

    /**
     * Adds an info message to the chat.
     */
    fun addInfoMessage(content: String) {
        activity.runOnUiThread {
            val infoMessage = ChatMessage(
                id = "info_${System.nanoTime()}",
                content = content,
                sender = ChatSender(0, activity.getString(R.string.chat_system_username), null, null),
                type = MessageType.INFO
            )
            chatUiManager.chatAdapter.addMessages(listOf(infoMessage))
            if (chatUiManager.isChatAutoScrollEnabled) {
                scrollToBottom()
            }
        }
    }

    /**
     * Scrolls the chat to the bottom.
     */
    fun scrollToBottom() {
        val count = chatAdapter.itemCount
        if (count > 0) {
            binding.chatRecyclerView.post {
                binding.chatRecyclerView.scrollToPosition(count - 1)
            }
        }
    }
}
