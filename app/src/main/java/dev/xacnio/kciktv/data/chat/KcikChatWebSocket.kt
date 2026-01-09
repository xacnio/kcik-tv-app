package dev.xacnio.kciktv.data.chat

import android.util.Log
import com.google.gson.Gson
import dev.xacnio.kciktv.data.model.ChatBadge
import dev.xacnio.kciktv.data.model.ChatMessage
import dev.xacnio.kciktv.data.model.ChatSender
import dev.xacnio.kciktv.data.model.PusherChatEvent
import dev.xacnio.kciktv.data.model.PusherEvent
import okhttp3.*
import java.util.concurrent.TimeUnit

/**
 * Kick Chat WebSocket client using Pusher protocol
 */
class KcikChatWebSocket(
    private val onMessageReceived: (ChatMessage) -> Unit,
    private val onConnectionStateChanged: (Boolean) -> Unit
) {
    companion object {
        private const val TAG = "KcikChatWebSocket"
        private const val PUSHER_APP_KEY = "32cbd69e4b950bf97679"
        private const val PUSHER_CLUSTER = "us2"
        private const val PUSHER_VERSION = "7.6.0"
        
        private fun buildWebSocketUrl(): String {
            return "wss://ws-$PUSHER_CLUSTER.pusher.com/app/$PUSHER_APP_KEY" +
                   "?protocol=7&client=js&version=$PUSHER_VERSION&flash=false"
        }
    }
    
    private val gson = Gson()
    private val client = OkHttpClient.Builder()
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .pingInterval(30, TimeUnit.SECONDS)
        .build()
    
    private var webSocket: WebSocket? = null
    private var currentChatroomId: Long? = null
    private var isConnected = false
    
    /**
     * Connect to a chatroom
     */
    fun connect(chatroomId: Long) {
        // Disconnect from previous chatroom if any
        disconnect()
        
        currentChatroomId = chatroomId
        
        val request = Request.Builder()
            .url(buildWebSocketUrl())
            .build()
        
        webSocket = client.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                Log.d(TAG, "WebSocket connected")
                isConnected = true
                onConnectionStateChanged(true)
                
                // Subscribe to chatroom
                subscribeToChannel(chatroomId)
            }
            
            override fun onMessage(webSocket: WebSocket, text: String) {
                handleMessage(text)
            }
            
            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                Log.d(TAG, "WebSocket closing: $reason")
            }
            
            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                Log.d(TAG, "WebSocket closed: $reason")
                isConnected = false
                onConnectionStateChanged(false)
            }
            
            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                Log.e(TAG, "WebSocket error: ${t.message}")
                isConnected = false
                onConnectionStateChanged(false)
            }
        })
    }
    
    /**
     * Disconnect from current chatroom
     */
    fun disconnect() {
        currentChatroomId?.let { chatroomId ->
            // Unsubscribe from channel
            val unsubscribe = """{"event":"pusher:unsubscribe","data":{"channel":"chatrooms.$chatroomId.v2"}}"""
            webSocket?.send(unsubscribe)
        }
        webSocket?.close(1000, "User disconnected")
        webSocket = null
        currentChatroomId = null
        isConnected = false
    }
    
    private fun subscribeToChannel(chatroomId: Long) {
        val subscribe = """{"event":"pusher:subscribe","data":{"auth":"","channel":"chatrooms.$chatroomId.v2"}}"""
        webSocket?.send(subscribe)
        Log.d(TAG, "Subscribed to chatroom: $chatroomId")
    }
    
    private fun handleMessage(text: String) {
        try {
            val event = gson.fromJson(text, PusherEvent::class.java)
            
            when (event.event) {
                "pusher:connection_established" -> {
                    Log.d(TAG, "Pusher connection established")
                }
                "pusher_internal:subscription_succeeded" -> {
                    Log.d(TAG, "Subscription succeeded to channel: ${event.channel}")
                }
                "App\\Events\\ChatMessageEvent" -> {
                    // Parse chat message
                    event.data?.let { dataString ->
                        val chatEvent = gson.fromJson(dataString, PusherChatEvent::class.java)
                        parseChatMessage(chatEvent)?.let { message ->
                            onMessageReceived(message)
                        }
                    }
                }
                else -> {
                    Log.d(TAG, "Unhandled event: ${event.event}")
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error parsing message: ${e.message}")
        }
    }
    
    private fun parseChatMessage(event: PusherChatEvent): ChatMessage? {
        val sender = event.sender ?: return null
        val content = event.content ?: return null
        val id = event.id ?: return null
        
        return ChatMessage(
            id = id,
            content = content,
            sender = ChatSender(
                id = sender.id ?: 0,
                username = sender.username ?: "Unknown",
                color = sender.identity?.color,
                badges = sender.identity?.badges?.mapNotNull { badge ->
                    badge.type?.let { type ->
                        ChatBadge(type = type, text = badge.text, count = badge.count)
                    }
                }
            )
        )
    }
    
    fun isConnected(): Boolean = isConnected
}
