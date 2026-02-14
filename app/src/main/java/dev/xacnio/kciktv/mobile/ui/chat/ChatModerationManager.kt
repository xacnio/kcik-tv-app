/**
 * File: ChatModerationManager.kt
 *
 * Description: Facilitates moderation actions for privileged users.
 * It implements functionality for pinning messages, deleting messages, timing out users,
 * and banning users, handling both the UI confirmation interactions and the API requests.
 *
 * Author: Xacnio
 *
 */
package dev.xacnio.kciktv.mobile.ui.chat

import android.app.AlertDialog
import android.widget.Toast
import androidx.lifecycle.lifecycleScope
import dev.xacnio.kciktv.mobile.MobilePlayerActivity
import dev.xacnio.kciktv.R
import dev.xacnio.kciktv.shared.data.model.ChatMessage
import dev.xacnio.kciktv.shared.util.TimeUtils
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Locale

/**
 * Manages chat moderation actions: Pin, Delete, Timeout, Ban.
 */
class ChatModerationManager(private val activity: MobilePlayerActivity) {

    private val prefs get() = activity.prefs
    private val repository get() = activity.repository
    private val currentChannel get() = activity.currentChannel
    // Access currentChatroom via ChatStateManager to ensure we get the latest state
    private val currentChatroom get() = activity.chatStateManager.currentChatroom

    fun pinMessageToChannel(message: ChatMessage) {
        AlertDialog.Builder(activity)
            .setTitle(R.string.mod_pin_confirm_title)
            .setMessage(R.string.mod_pin_confirm_msg)
            .setPositiveButton(R.string.action_pin) { _, _ ->
                performPinMessage(message)
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun performPinMessage(message: ChatMessage) {
        val token = prefs.authToken ?: return
        val slug = currentChannel?.slug ?: return
        val chatroomId = currentChatroom?.id ?: 0

        // Construct message JSON for pinning
        val messageJson = """
        {
            "id": "${message.id}",
            "chatroom_id": $chatroomId,
            "content": "${message.content.replace("\"", "\\\"")}",
            "type": "message",
            "created_at": "${SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss+00:00", Locale.US).format(java.util.Date(message.createdAt))}",
            "sender": {
                "id": ${message.sender.id},
                "username": "${message.sender.username}",
                "slug": "${message.sender.username.lowercase()}",
                "identity": {
                    "color": "${message.sender.color ?: "#53FC18"}",
                    "badges": [${message.sender.badges?.joinToString(",") { 
                        """{"type":"${it.type}","text":"${it.text ?: ""}","count":${it.count ?: 0},"active":true}"""
                    } ?: ""}]
                }
            }
        }
        """.trimIndent()
        
        activity.lifecycleScope.launch {
            repository.pinMessage(slug, token, messageJson).onSuccess {
                activity.runOnUiThread {
                    Toast.makeText(activity, activity.getString(R.string.message_pinned), Toast.LENGTH_SHORT).show()
                }
            }.onFailure { error ->
                activity.runOnUiThread {
                    Toast.makeText(activity, activity.getString(R.string.error_format, error.message), Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    fun deleteMessageFromChat(message: ChatMessage) {
        val token = prefs.authToken ?: return
        val chatroomId = currentChatroom?.id ?: return
        
        AlertDialog.Builder(activity)
            .setTitle(R.string.action_delete_message)
            .setMessage(R.string.delete_message_confirm_msg)
            .setPositiveButton(R.string.action_delete) { _, _ ->
                activity.lifecycleScope.launch {
                    repository.deleteMessage(chatroomId, message.id, token).onSuccess {
                        activity.runOnUiThread {
                            Toast.makeText(activity, R.string.mod_delete_success, Toast.LENGTH_SHORT).show()
                            activity.chatUiManager.chatAdapter.markMessageAsDeleted(message.id)
                        }
                    }.onFailure { error ->
                        activity.runOnUiThread {
                            Toast.makeText(activity, activity.getString(R.string.chat_error_generic, error.message ?: ""), Toast.LENGTH_SHORT).show()
                        }
                    }
                }
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    fun timeoutUserFromChat(username: String, durationMinutes: Int) {
        val token = prefs.authToken ?: return
        val slug = currentChannel?.slug ?: return
        
        activity.lifecycleScope.launch {
            repository.timeoutUser(slug, username, durationMinutes, token).onSuccess {
                activity.runOnUiThread {
                    val durationStr = TimeUtils.formatDuration(activity, durationMinutes)
                    Toast.makeText(activity, activity.getString(R.string.mod_timeout_success, durationStr), Toast.LENGTH_SHORT).show()
                }
            }.onFailure { error ->
                activity.runOnUiThread {
                    val localizedMsg = getLocalizedModerationError(error.message ?: "")
                    Toast.makeText(activity, localizedMsg, Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    fun showBanConfirmationDialog(username: String) {
        AlertDialog.Builder(activity)
            .setTitle(R.string.mod_ban_confirm_title)
            .setMessage(activity.getString(R.string.mod_ban_confirm_msg, username))
            .setPositiveButton(R.string.confirm) { _, _ ->
                banUserFromChat(username)
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    fun banUserFromChat(username: String) {
        val token = prefs.authToken ?: return
        val slug = currentChannel?.slug ?: return
        
        activity.lifecycleScope.launch {
            repository.banUser(slug, username, token).onSuccess {
                activity.runOnUiThread {
                    Toast.makeText(activity, activity.getString(R.string.mod_ban_success, username), Toast.LENGTH_SHORT).show()
                }
            }.onFailure { error ->
                activity.runOnUiThread {
                    val localizedMsg = getLocalizedModerationError(error.message ?: "")
                    Toast.makeText(activity, localizedMsg, Toast.LENGTH_SHORT).show()
                }
            }
        }
    }
    
    private fun getLocalizedModerationError(error: String): String {
        return when {
            error.contains("You can not ban a moderator", ignoreCase = true) -> 
                activity.getString(R.string.error_cannot_ban_moderator)
            else -> activity.getString(R.string.error_moderation_failed_reason, error)
        }
    }
}
