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
    private val onEventReceived: (String, String) -> Unit = { _, _ -> },
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
    private var currentChannelId: Long? = null
    private var isConnected = false
    
    /**
     * Connect to Pusher WebSocket
     */
    fun connect() {
        if (isConnected) return
        
        val request = Request.Builder()
            .url(buildWebSocketUrl())
            .build()
        
        webSocket = client.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                Log.d(TAG, "WebSocket connected")
                isConnected = true
                
                // Re-subscribe to pending requests
                currentChatroomId?.let { subscribeToChat(it) }
                currentChannelId?.let { subscribeToChannelEvents(it) }
                
                onConnectionStateChanged(true)
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
    
    fun subscribeToChat(chatroomId: Long) {
        currentChatroomId = chatroomId
        if (!isConnected) {
            Log.d(TAG, "Postponing chat subscription until connected")
            return
        }
        val subscribe = """{"event":"pusher:subscribe","data":{"auth":"","channel":"chatrooms.$chatroomId.v2"}}"""
        webSocket?.send(subscribe)
        Log.d(TAG, "Requested subscription to chatroom: $chatroomId")
    }

    fun subscribeToChannelEvents(channelId: Long) {
        currentChannelId = channelId
        if (!isConnected) {
            Log.d(TAG, "Postponing channel events subscription until connected")
            return
        }
        val subscribe = """{"event":"pusher:subscribe","data":{"auth":"","channel":"channel.$channelId"}}"""
        webSocket?.send(subscribe)
        Log.d(TAG, "Requested subscription to channel events: $channelId")
    }

    fun unsubscribeFromChat() {
        currentChatroomId?.let { id ->
            val unsubscribe = """{"event":"pusher:unsubscribe","data":{"channel":"chatrooms.$id.v2"}}"""
            webSocket?.send(unsubscribe)
            currentChatroomId = null
        }
    }

    fun unsubscribeFromChannelEvents() {
        currentChannelId?.let { id ->
            val unsubscribe = """{"event":"pusher:unsubscribe","data":{"channel":"channel.$id"}}"""
            webSocket?.send(unsubscribe)
            currentChannelId = null
        }
    }
    
    /**
     * Fully disconnect and close WebSocket
     */
    fun disconnect() {
        unsubscribeFromChat()
        unsubscribeFromChannelEvents()
        webSocket?.close(1000, "User disconnected")
        webSocket = null
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
                    // Try flexible matching for events with different backslash escaping
                    val rawEventName = event.event ?: ""
                    if (rawEventName.contains("StreamerIsLive")) {
                        event.data?.let { dataString ->
                            Log.d(TAG, "Matched StreamerIsLive event (flexible match: $rawEventName)")
                            onEventReceived("App\\Events\\StreamerIsLive", dataString)
                        }
                    } else {
                        Log.d(TAG, "Unhandled event: $rawEventName on channel ${event.channel}")
                    }
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
