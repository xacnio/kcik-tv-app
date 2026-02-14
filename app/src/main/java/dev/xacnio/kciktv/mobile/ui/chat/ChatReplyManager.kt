/**
 * File: ChatReplyManager.kt
 *
 * Description: Manages the reply context within the chat interface.
 * It handles the UI state for composing a reply, including the "Replying to" indicator,
 * and facilitates navigation to original messages by scrolling and highlighting them.
 *
 * Author: Xacnio
 *
 */
package dev.xacnio.kciktv.mobile.ui.chat

import android.os.Handler
import android.os.Looper
import android.view.View
import android.widget.Toast
import androidx.recyclerview.widget.LinearLayoutManager
import dev.xacnio.kciktv.mobile.MobilePlayerActivity
import dev.xacnio.kciktv.R
import dev.xacnio.kciktv.shared.data.model.ChatMessage

/**
 * Manages chat reply functionality (preparing, canceling, scrolling to replies).
 */
class ChatReplyManager(private val activity: MobilePlayerActivity) {

    private val prefs get() = activity.prefs
    private val binding get() = activity.binding
    private val chatAdapter get() = activity.chatAdapter
    private val chatUiManager get() = activity.chatUiManager

    /**
     * Prepares the chat UI for replying to a message.
     */
    fun prepareReply(message: ChatMessage) {
        if (!prefs.isLoggedIn) {
            Toast.makeText(activity, activity.getString(R.string.login_required_chat), Toast.LENGTH_SHORT).show()
            return
        }

        activity.currentReplyMessageId = message.id

        // Show reply UI indicator above chat input
        binding.replyContainer.visibility = View.VISIBLE
        binding.replyUsername.text = activity.getString(R.string.chat_replying_to, "@${message.sender.username}")
        binding.replyCloseButton.setOnClickListener {
            cancelReply()
        }

        // Focus input and show keyboard with slight delay to ensure UI stability
        binding.chatInput.postDelayed({
            binding.chatInput.requestFocus()
            val imm = activity.getSystemService(android.content.Context.INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager
            imm.showSoftInput(binding.chatInput, android.view.inputmethod.InputMethodManager.SHOW_IMPLICIT)
        }, 200)
    }

    /**
     * Cancels the current reply.
     */
    fun cancelReply() {
        activity.currentReplyMessageId = null
        binding.replyContainer.visibility = View.GONE
        binding.replyUsername.text = ""
    }

    /**
     * Scrolls to and highlights a replied message.
     */
    fun scrollToRepliedMessage(messageId: String) {
        val position = chatAdapter.currentList.indexOfFirst { it.id == messageId }
        if (position != -1) {
            chatUiManager.isChatAutoScrollEnabled = false
            binding.chatJumpToBottom.visibility = View.VISIBLE

            // Scroll to position with offset to center it
            val layoutManager = binding.chatRecyclerView.layoutManager as LinearLayoutManager
            val offset = binding.chatRecyclerView.height / 2
            layoutManager.scrollToPositionWithOffset(position, offset)

            // Highlight effect
            chatAdapter.highlightMessage(messageId)

            // Clear highlight after 2 seconds
            Handler(Looper.getMainLooper()).postDelayed({
                chatAdapter.highlightMessage(null)
            }, 2000)

        } else {
            // Maybe show a toast or fetch history if not found locally?
        }
    }
}
