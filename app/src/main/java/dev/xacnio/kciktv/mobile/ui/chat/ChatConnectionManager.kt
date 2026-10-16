/**
 * File: ChatConnectionManager.kt
 *
 * Description: Manages the WebSocket connection lifecycle for the chat system.
 * It handles connecting to Kick's realtime (Centrifugo) WebSocket, managing subscriptions to public and private channels,
 * and implementing auto-reconnect logic to ensure persistent chat connectivity.
 *
 * Author: Xacnio
 *
 */
package dev.xacnio.kciktv.mobile.ui.chat

import android.util.Log
import android.view.View
import androidx.lifecycle.lifecycleScope
import dev.xacnio.kciktv.mobile.MobilePlayerActivity
import dev.xacnio.kciktv.R
import dev.xacnio.kciktv.shared.data.chat.KcikChatWebSocket
import dev.xacnio.kciktv.shared.data.mock.MockChatSource
import dev.xacnio.kciktv.shared.data.mock.MockConfig
import dev.xacnio.kciktv.shared.data.model.ChatMessage

class ChatConnectionManager(private val activity: MobilePlayerActivity) {
    private val TAG = "ChatConnectionManager"
    private var chatWebSocket: KcikChatWebSocket? = null
    private var mockChatSource: MockChatSource? = null

    private val prefs get() = activity.prefs
    private val chatStateManager get() = activity.chatStateManager
    private val chatUiManager get() = activity.chatUiManager
    private val chatEventHandler get() = activity.chatEventHandler

    private var chatWasDisconnected = false
    private var currentLivestreamId: Long? = null

    fun connectToChat(token: String, chatroomId: Long, channelId: Long, livestreamId: Long? = null) {
        disconnect()

        currentLivestreamId = livestreamId

        if (MockConfig.enabled) {
            chatWasDisconnected = false
            mockChatSource = MockChatSource(chatroomId) { message ->
                chatUiManager.handleIncomingMessage(message)
            }
            mockChatSource?.start(activity.lifecycleScope)
            activity.runOnUiThread {
                activity.binding.chatConnectionContainer.visibility = View.GONE
            }
            return
        }

        activity.runOnUiThread {
             chatWasDisconnected = false

             chatWebSocket = KcikChatWebSocket(
                 activity.applicationContext,
                 onMessageReceived = chatListener@{ message ->
                     // Update identity cache (cheap — prefs.username is in-memory cached)
                     if (message.sender.username == prefs.username) {
                         chatStateManager.currentUserSender = message.sender
                     }

                     val ref = message.messageRef
                     if (ref != null && chatStateManager.shouldConfirmSentMessage(ref)) {
                         chatStateManager.confirmSentMessage(ref)
                         activity.runOnUiThread {
                             chatUiManager.chatAdapter.confirmSentMessage(ref, message)
                         }
                         return@chatListener
                     }

                     activity.runOnUiThread {
                        chatUiManager.handleIncomingMessage(message)
                     }
                 },
                 onEventReceived = { event, data -> 
                     if (event == "PointsUpdated" || event == "App\\Events\\PointsUpdated") {
                         activity.handleChannelPointsEvent(data)
                     } else {
                         chatEventHandler.handleEvent(event, data)
                     }
                 },
                 onConnectionStateChanged = { connected ->
                     Log.d(TAG, "Chat connection state: $connected")
                     activity.runOnUiThread {
                         if (connected) {
                             activity.binding.chatConnectionContainer.visibility = View.GONE         
                             chatWasDisconnected = false
                         } else {
                             if (!chatWasDisconnected) {
                                 chatWasDisconnected = true
                                 activity.binding.chatConnectionContainer.visibility = View.VISIBLE
                             }
                         }
                     }
                 },
                 onReconnecting = { _, _ ->
                     activity.runOnUiThread {
                         activity.binding.chatConnectionContainer.visibility = View.VISIBLE
                         activity.binding.chatConnectionProgress.visibility = View.VISIBLE
                         activity.binding.chatConnectionContainer.isClickable = false
                     }
                 },
                 onMaxRetriesReached = {
                     activity.runOnUiThread {
                         activity.binding.chatConnectionContainer.visibility = View.VISIBLE
                         activity.binding.chatConnectionProgress.visibility = View.GONE
                         activity.binding.chatConnectionContainer.isClickable = true
                         activity.binding.chatConnectionContainer.setOnClickListener {
                             // Immediately show reconnecting UI before starting new connection
                             activity.binding.chatConnectionProgress.visibility = View.VISIBLE
                             activity.binding.chatConnectionContainer.isClickable = false
                             connectToChat(token, chatroomId, channelId, currentLivestreamId)
                         }
                     }
                 }
             )
             
             chatWebSocket?.connect()
             // Subscribe to channels immediately
             chatWebSocket?.subscribeToChat(chatroomId)
             chatWebSocket?.subscribeToChannelEvents(channelId)
             chatWebSocket?.subscribeToPredictions(channelId)

             // Private channels; the socket fetches their tokens
             if (prefs.isLoggedIn) {
                 chatWebSocket?.subscribeToPrivateChatroom(chatroomId)
                 prefs.userId.takeIf { it > 0 }?.let { chatWebSocket?.subscribeToChannelPoints(it) }
                 livestreamId?.let { chatWebSocket?.subscribeToLivestream(it) }
             }
        }
    }
    
    fun disconnect() {
        mockChatSource?.stop()
        mockChatSource = null
        chatWebSocket?.disconnect()
        chatWebSocket = null
    }

    fun isConnected(): Boolean {
        if (MockConfig.enabled) return mockChatSource != null
        return chatWebSocket?.isConnected() == true
    }

    fun manualReconnect() {
        chatWebSocket?.manualReconnect()
    }

    fun unsubscribeFromOnlyChat() {
        chatWebSocket?.unsubscribeFromOnlyChat()
    }

    fun subscribeToOnlyChat() {
        // Set one-shot callback to hide reconnect indicator when subscription succeeds
        chatWebSocket?.onChatResubscribed = {
            activity.runOnUiThread {
                activity.binding.chatConnectionContainer.visibility = View.GONE
            }
        }
        chatWebSocket?.subscribeToOnlyChat()
    }
}
