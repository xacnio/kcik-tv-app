/**
 * File: ChatConnectionManager.kt
 *
 * Description: Manages the WebSocket connection lifecycle for the chat system.
 * It handles connecting to Pusher/WebSocket, managing subscriptions to public and private channels,
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
import dev.xacnio.kciktv.shared.data.api.RetrofitClient
import dev.xacnio.kciktv.shared.data.chat.KcikChatWebSocket
import dev.xacnio.kciktv.shared.data.model.ChatMessage
import kotlinx.coroutines.launch

class ChatConnectionManager(private val activity: MobilePlayerActivity) {
    private val TAG = "ChatConnectionManager"
    private var chatWebSocket: KcikChatWebSocket? = null
    
    private val prefs get() = activity.prefs
    private val chatStateManager get() = activity.chatStateManager
    private val chatUiManager get() = activity.chatUiManager
    private val chatEventHandler get() = activity.chatEventHandler
    
    private var chatWasDisconnected = false
    
    fun connectToChat(token: String, chatroomId: Long, channelId: Long) {
        disconnect()

        activity.runOnUiThread {
             chatWasDisconnected = false
             
             chatWebSocket = KcikChatWebSocket(
                 activity.applicationContext,
                 onMessageReceived = chatListener@{ message ->
                     // Update identity cache
                     if (message.sender.username == prefs.username) {
                         chatStateManager.currentUserSender = message.sender
                     }

                     // Check for message confirmation
                     val ref = message.messageRef
                     if (ref != null && chatStateManager.shouldConfirmSentMessage(ref)) {
                         chatStateManager.confirmSentMessage(ref)
                         activity.runOnUiThread {
                             chatUiManager.chatAdapter.confirmSentMessage(ref, message)
                         }
                         return@chatListener
                     }

                     // Delegate message buffering/handling to UI Manager
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
                 onSocketIdReceived = { socketId ->
                     if (prefs.isLoggedIn && prefs.authToken != null) {
                         activity.lifecycleScope.launch {
                             try {
                                 val channelName = "private-chatroom_${chatroomId}"
                                 val authRes = dev.xacnio.kciktv.shared.data.api.RetrofitClient.authService.getPusherAuth(
                                     "Bearer ${prefs.authToken}",
                                     socketId,
                                     channelName
                                 )
                                 if (authRes.isSuccessful && authRes.body() != null) {
                                     val auth = authRes.body()!!.auth
                                     chatWebSocket?.subscribeToPrivateChatroom(chatroomId, auth)
                                 }
                                 
                                 val userId = prefs.userId
                                 if (userId > 0) {
                                     val pointsChannelName = "private-channelpoints-$userId"
                                     val pointsAuthRes = dev.xacnio.kciktv.shared.data.api.RetrofitClient.authService.getPusherAuth(
                                         "Bearer ${prefs.authToken}",
                                         socketId,
                                         pointsChannelName
                                     )
                                     if (pointsAuthRes.isSuccessful && pointsAuthRes.body() != null) {
                                         val auth = pointsAuthRes.body()!!.auth
                                         chatWebSocket?.subscribeToChannelPoints(userId, auth)
                                     }
                                 }
                             } catch (e: Exception) {
                                 Log.e(TAG, "Failed to authenticate private channels", e)
                             }
                         }
                     }
                 },
                 onReconnecting = { current, max ->
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
                             connectToChat(token, chatroomId, channelId)
                         }
                     }
                 }
             )
             
             chatWebSocket?.connect()
             // Subscribe to channels immediately
             chatWebSocket?.subscribeToChat(chatroomId)
             chatWebSocket?.subscribeToChannelEvents(channelId)
             chatWebSocket?.subscribeToPredictions(channelId)
        }
    }
    
    fun disconnect() {
        chatWebSocket?.disconnect()
        chatWebSocket = null
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
