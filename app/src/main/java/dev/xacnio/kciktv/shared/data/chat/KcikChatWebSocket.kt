/**
 * File: KcikChatWebSocket.kt
 *
 * Description: Implementation of Kcik Chat Web Socket functionality.
 *
 * Author: Xacnio
 *
 */
package dev.xacnio.kciktv.shared.data.chat

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.google.gson.Gson
import java.util.concurrent.atomic.AtomicBoolean
import dev.xacnio.kciktv.R
import dev.xacnio.kciktv.shared.data.model.ChatBadge
import dev.xacnio.kciktv.shared.data.model.ChatMessage
import dev.xacnio.kciktv.shared.data.model.ChatSender
import dev.xacnio.kciktv.shared.data.model.PusherChatEvent
import dev.xacnio.kciktv.shared.data.model.PusherEvent
import dev.xacnio.kciktv.shared.util.TimeUtils
import okhttp3.*
import java.util.concurrent.TimeUnit
import dev.xacnio.kciktv.shared.data.chat.KcikChatWebSocket

/**
 * Kick Chat WebSocket client using Pusher protocol
 */
class KcikChatWebSocket(
    private val context: Context,
    private val onMessageReceived: (ChatMessage) -> Unit,
    private val onEventReceived: (String, String) -> Unit = { _, _ -> },
    private val onConnectionStateChanged: (Boolean) -> Unit,
    private var onSocketIdReceived: ((String) -> Unit)? = null,
    private val onReconnecting: ((current: Int, max: Int) -> Unit)? = null,
    private val onMaxRetriesReached: (() -> Unit)? = null
) {
    companion object {
        private const val TAG = "KcikChatWebSocket"
        private const val PUSHER_APP_KEY = "32cbd69e4b950bf97679"
        private const val PUSHER_CLUSTER = "us2"
        private const val PUSHER_VERSION = "8.4.0"
        
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
    private var socketId: String? = null
    
    // Auto-reconnect state
    private val shouldReconnect = AtomicBoolean(true)
    private var reconnectAttempt = 0
    private val maxReconnectAttempts = 10
    private val baseReconnectDelayMs = 1000L
    private val maxReconnectDelayMs = 8000L
    private val reconnectHandler = Handler(Looper.getMainLooper())
    private var reconnectRunnable: Runnable? = null
    
    // One-shot callback for when chat re-subscription succeeds (used by low battery resume)
    var onChatResubscribed: (() -> Unit)? = null
    
    // Store private channel auth for re-subscription
    private var privateChatroomAuth: String? = null
    private var channelPointsUserId: Long? = null
    private var channelPointsAuth: String? = null
    
    // Ping timer for keepalive (every 2 minutes)
    private val pingHandler = Handler(Looper.getMainLooper())
    private val pingIntervalMs = 120_000L // 2 minutes
    private val pingRunnable = object : Runnable {
        override fun run() {
            if (isConnected) {
                sendPing()
                pingHandler.postDelayed(this, pingIntervalMs)
            }
        }
    }
    
    /**
     * Connect to Pusher WebSocket
     */
    fun connect() {
        if (isConnected) return
        
        shouldReconnect.set(true)
        cancelReconnect()
        
        val request = Request.Builder()
            .url(buildWebSocketUrl())
            .build()
        
        webSocket = client.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                Log.d(TAG, "WebSocket connected")
                isConnected = true
                reconnectAttempt = 0 // Reset on successful connect
                
                // Start ping timer
                pingHandler.removeCallbacks(pingRunnable)
                pingHandler.postDelayed(pingRunnable, pingIntervalMs)
                
                // Re-subscribe to pending requests
                currentChatroomId?.let { subscribeToChat(it) }
                currentChannelId?.let { 
                    subscribeToChannelEvents(it)
                    subscribeToPredictions(it) 
                }
                
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
                pingHandler.removeCallbacks(pingRunnable)
                isConnected = false
                onConnectionStateChanged(false)
                scheduleReconnect()
            }
            
            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                Log.e(TAG, "WebSocket error: ${t.message}")
                pingHandler.removeCallbacks(pingRunnable)
                isConnected = false
                onConnectionStateChanged(false)
                scheduleReconnect()
            }
        })
    }
    
    /**
     * Schedule a reconnection attempt with exponential backoff
     */
    private fun scheduleReconnect() {
        if (!shouldReconnect.get()) {
            Log.d(TAG, "Reconnect disabled, not scheduling")
            return
        }
        
        if (reconnectAttempt >= maxReconnectAttempts) {
            Log.w(TAG, "Max reconnect attempts ($maxReconnectAttempts) reached, giving up")
            onMaxRetriesReached?.invoke()
            return
        }
        
        val delay = minOf(baseReconnectDelayMs * (1 shl reconnectAttempt), maxReconnectDelayMs)
        reconnectAttempt++
        
        Log.d(TAG, "Scheduling reconnect attempt $reconnectAttempt in ${delay}ms")
        
        // Notify UI about reconnection attempt
        onReconnecting?.invoke(reconnectAttempt, maxReconnectAttempts)
        
        reconnectRunnable = Runnable {
            if (shouldReconnect.get() && !isConnected) {
                Log.d(TAG, "Attempting reconnect ($reconnectAttempt/$maxReconnectAttempts)")
                connect()
            }
        }
        reconnectHandler.postDelayed(reconnectRunnable!!, delay)
    }
    
    /**
     * Cancel any pending reconnect
     */
    private fun cancelReconnect() {
        reconnectRunnable?.let { reconnectHandler.removeCallbacks(it) }
        reconnectRunnable = null
    }
    
    /**
     * Manual reconnect triggered by user (e.g., retry button)
     * Resets retry counter and attempts immediate connection
     */
    fun manualReconnect() {
        reconnectAttempt = 0
        shouldReconnect.set(true)
        cancelReconnect()
        connect()
    }
    
    fun subscribeToChat(chatroomId: Long) {
        currentChatroomId = chatroomId
        if (!isConnected) {
            Log.d(TAG, "Postponing chat subscription until connected")
            return
        }
        val subscribe = """{"event":"pusher:subscribe","data":{"auth":"","channel":"chatrooms.$chatroomId.v2"}}"""
        webSocket?.send(subscribe)
        
        // Also subscribe to the non-v2 chatrooms channel for mode events
        val subscribeMode = """{"event":"pusher:subscribe","data":{"auth":"","channel":"chatrooms.$chatroomId"}}"""
        webSocket?.send(subscribeMode)
        
        // Also subscribe to chatroom_{id} as requested (potential reward event channel)
        val subscribeReward = """{"event":"pusher:subscribe","data":{"auth":"","channel":"chatroom_$chatroomId"}}"""
        webSocket?.send(subscribeReward)
        
        Log.d(TAG, "Requested subscription to chatroom: $chatroomId (v2, meta, and reward)")
    }

    fun subscribeToChannelEvents(channelId: Long) {
        currentChannelId = channelId
        if (!isConnected) {
            Log.d(TAG, "Postponing channel events subscription until connected")
            return
        }
        val subscribe = """{"event":"pusher:subscribe","data":{"auth":"","channel":"channel.$channelId"}}"""
        webSocket?.send(subscribe)
        
        // Also subscribe to underscore format channel_{id}
        val subscribeUnderscore = """{"event":"pusher:subscribe","data":{"auth":"","channel":"channel_$channelId"}}"""
        webSocket?.send(subscribeUnderscore)
        
        Log.d(TAG, "Requested subscription to channel events: $channelId (dot and underscore)")
    }

    fun subscribeToPrivateChatroom(chatroomId: Long, auth: String) {
        // Store for re-subscription on reconnect
        privateChatroomAuth = auth
        
        if (!isConnected) {
            Log.d(TAG, "Postponing private chatroom subscription until connected")
            return
        }
        val subscribe = """{"event":"pusher:subscribe","data":{"auth":"$auth","channel":"private-chatroom_$chatroomId"}}"""
        webSocket?.send(subscribe)
        Log.d(TAG, "Requested subscription to private-chatroom: $chatroomId")
    }

    fun subscribeToChannelPoints(userId: Long, auth: String) {
        // Store for re-subscription on reconnect
        channelPointsUserId = userId
        channelPointsAuth = auth
        
        if (!isConnected) {
            Log.d(TAG, "Postponing channel points subscription until connected")
            return
        }
        val subscribe = """{"event":"pusher:subscribe","data":{"auth":"$auth","channel":"private-channelpoints-$userId"}}"""
        webSocket?.send(subscribe)
        Log.d(TAG, "Requested subscription to channel points: $userId")
    }

    fun subscribeToPredictions(channelId: Long) {
        currentChannelId = channelId
        if (!isConnected) {
            Log.d(TAG, "Postponing predictions subscription until connected")
            return
        }
        val subscribe = """{"event":"pusher:subscribe","data":{"auth":"","channel":"predictions-channel-$channelId"}}"""
        webSocket?.send(subscribe)
        Log.d(TAG, "Requested subscription to predictions: $channelId")
    }

    fun unsubscribeFromOnlyChat() {
        currentChatroomId?.let { id ->
            val unsubscribe = """{"event":"pusher:unsubscribe","data":{"channel":"chatrooms.$id.v2"}}"""
            webSocket?.send(unsubscribe)
        }
    }

    fun subscribeToOnlyChat() {
        currentChatroomId?.let { id ->
            val subscribe = """{"event":"pusher:subscribe","data":{"auth":"","channel":"chatrooms.$id.v2"}}"""
            webSocket?.send(subscribe)
        }
    }

    fun unsubscribeFromChat() {
        currentChatroomId?.let { id ->
            val unsubscribe = """{"event":"pusher:unsubscribe","data":{"channel":"chatrooms.$id.v2"}}"""
            webSocket?.send(unsubscribe)
            
            val unsubscribeMode = """{"event":"pusher:unsubscribe","data":{"channel":"chatrooms.$id"}}"""
            webSocket?.send(unsubscribeMode)
            
            val unsubscribeReward = """{"event":"pusher:unsubscribe","data":{"channel":"chatroom_$id"}}"""
            webSocket?.send(unsubscribeReward)
            
            val unsubscribePrivate = """{"event":"pusher:unsubscribe","data":{"channel":"private-chatroom_$id"}}"""
            webSocket?.send(unsubscribePrivate)
            
            currentChatroomId = null
        }
    }

    fun unsubscribeFromChannelEvents() {
        currentChannelId?.let { id ->
            val unsubscribe = """{"event":"pusher:unsubscribe","data":{"channel":"channel.$id"}}"""
            webSocket?.send(unsubscribe)
            
            val unsubscribeUnderscore = """{"event":"pusher:unsubscribe","data":{"channel":"channel_$id"}}"""
            webSocket?.send(unsubscribeUnderscore)
            
            val unsubscribePred = """{"event":"pusher:unsubscribe","data":{"channel":"predictions-channel-$id"}}"""
            webSocket?.send(unsubscribePred)
            
            currentChannelId = null
        }
    }
    
    /**
     * Fully disconnect and close WebSocket. Disables auto-reconnect.
     */
    fun disconnect() {
        shouldReconnect.set(false)
        cancelReconnect()
        
        // Stop ping timer
        pingHandler.removeCallbacks(pingRunnable)
        
        isConnected = false
        
        unsubscribeFromChat()
        unsubscribeFromChannelEvents()
        
        try {
            webSocket?.cancel() // Immediate termination to prevent late messages
        } catch (e: Exception) {
            // Ignore
        }
        
        webSocket = null
        socketId = null
        
        // Clear stored auth
        privateChatroomAuth = null
        channelPointsUserId = null
        channelPointsAuth = null
    }
    
    /**
     * Send a ping to keep connection alive
     */
    private fun sendPing() {
        if (!isConnected) return
        val ping = """{"event":"pusher:ping","data":{}}"""
        webSocket?.send(ping)
        Log.d(TAG, "Sent ping")
    }
    
    private fun subscribeToChannel(chatroomId: Long) {
        val subscribe = """{"event":"pusher:subscribe","data":{"auth":"","channel":"chatrooms.$chatroomId.v2"}}"""
        webSocket?.send(subscribe)
        Log.d(TAG, "Subscribed to chatroom: $chatroomId")
    }
    
    private fun handleMessage(text: String) {
        if (!isConnected) return
        
        try {
            val event = gson.fromJson(text, PusherEvent::class.java)
            
            if (event.event?.contains("ChatMessageEvent") != true && event.event?.contains("pusher:") != true) {
                 Log.d(TAG, "WS Msg: ${event.event} Data: ${event.data}")
            }
            
            when (event.event) {
                "pusher:connection_established" -> {
                    Log.d(TAG, "Pusher connection established")
                    try {
                        val data = gson.fromJson(event.data, Map::class.java)
                        val id = data["socket_id"] as? String
                        if (id != null) {
                            socketId = id
                            Log.d(TAG, "Socket ID received: $socketId")
                            onSocketIdReceived?.invoke(id)
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "Error parsing connection_established data", e)
                    }
                }
                "pusher_internal:subscription_succeeded" -> {
                    Log.d(TAG, "Subscription succeeded to channel: ${event.channel}")
                    // Fire one-shot callback when chatroom v2 subscription succeeds
                    if (event.channel?.contains(".v2") == true && onChatResubscribed != null) {
                        onChatResubscribed?.invoke()
                        onChatResubscribed = null
                    }
                }
                "pusher:pong" -> {
                    Log.d(TAG, "Pong received")
                }
                "App\\Events\\ChatMessageEvent", "ChatMessageEvent" -> {
                    // Parse chat message
                    event.data?.let { dataString ->
                        val chatEvent = gson.fromJson(dataString, PusherChatEvent::class.java)
                        parseChatMessage(chatEvent)?.let { message ->
                            onMessageReceived(message)
                        }
                    }
                }
                "App\\Events\\ChatroomUpdatedEvent", "ChatroomUpdatedEvent" -> {
                    event.data?.let { dataString ->
                        onEventReceived("App\\Events\\ChatroomUpdatedEvent", dataString)
                    }
                }
                "SubscribersModeActivated", "SubscribersModeDeactivated",
                "EmotesModeActivated", "EmotesModeDeactivated",
                "FollowersModeActivated", "FollowersModeDeactivated",
                "SlowModeActivated", "SlowModeDeactivated",
                "App\\Events\\PinnedMessageCreatedEvent",
                "App\\Events\\PinnedMessageDeletedEvent",
                "App\\Events\\PollUpdateEvent",
                "App\\Events\\PollDeleteEvent",
                "App\\Events\\PredictionCreated",
                "App\\Events\\PredictionUpdated",
                "PredictionCreated",
                "PredictionUpdated" -> {
                    event.data?.let { dataString ->
                        onEventReceived(event.event, dataString)
                    }
                }
                "App\\Events\\MessageDeletedEvent", "MessageDeletedEvent" -> {
                    event.data?.let { dataString ->
                        onEventReceived("App\\Events\\MessageDeletedEvent", dataString)
                    }
                }
                "App\\Events\\ChatroomClearEvent", "ChatroomClearEvent" -> {
                    event.data?.let { dataString ->
                        onEventReceived("App\\Events\\ChatroomClearEvent", dataString)
                    }
                }
                "App\\Events\\UserBannedEvent" -> {
                    event.data?.let { dataString ->
                        try {
                            val banData = gson.fromJson(dataString, dev.xacnio.kciktv.shared.data.model.UserBannedEventData::class.java)
                            val user = banData.user?.username ?: "Unknown"
                            val moderator = banData.bannedBy?.username ?: "Moderator"
                            
                            val typeText = if (banData.permanent == true) {
                                context.getString(R.string.chat_status_slow_mode_on) // Wait, this is not right, I should use the permanent string
                                // I added chat_user_banned_permanently which is a full template
                                "" 
                            } else {
                                TimeUtils.formatDuration(context, (banData.duration ?: 0))
                            }
                            
                            val content = if (banData.permanent == true) {
                                context.getString(R.string.chat_user_banned_permanently, user, moderator)
                            } else {
                                context.getString(R.string.chat_user_banned, user, moderator, typeText)
                            }
                            
                            val systemMessage = ChatMessage(
                                id = "ban_${banData.id ?: System.currentTimeMillis()}",
                                content = content,
                                sender = ChatSender(0, context.getString(R.string.chat_system_username), null, null),
                                type = dev.xacnio.kciktv.shared.data.model.MessageType.SYSTEM,
                                iconResId = if (banData.permanent == true) R.drawable.ic_block else R.drawable.ic_hourglass,
                                targetUsername = user,
                                moderatorUsername = moderator
                            )
                            onMessageReceived(systemMessage)
                            onEventReceived("App\\Events\\UserBannedEvent", dataString)
                        } catch (e: Exception) {
                            Log.e(TAG, "Error parsing UserBannedEvent", e)
                        }
                    }
                }
                "App\\Events\\UserUnbannedEvent" -> {
                    event.data?.let { dataString ->
                        try {
                            val unbanData = gson.fromJson(dataString, dev.xacnio.kciktv.shared.data.model.UserUnbannedEventData::class.java)
                            val user = unbanData.user?.username ?: "Unknown"
                            val moderator = unbanData.unbannedBy?.username ?: "Moderator"
                            val content = context.getString(R.string.chat_user_unbanned, user, moderator)
                            
                            val systemMessage = ChatMessage(
                                id = "unban_${unbanData.id ?: System.currentTimeMillis()}",
                                content = content,
                                sender = ChatSender(0, context.getString(R.string.chat_system_username), null, null),
                                type = dev.xacnio.kciktv.shared.data.model.MessageType.SYSTEM,
                                iconResId = R.drawable.ic_check,
                                targetUsername = user,
                                moderatorUsername = moderator
                            )
                            onMessageReceived(systemMessage)
                            onEventReceived("App\\Events\\UserUnbannedEvent", dataString)
                        } catch (e: Exception) {
                            Log.e(TAG, "Error parsing UserUnbannedEvent", e)
                        }
                    }
                }
                "App\\Events\\RewardRedeemedEvent",
                "RewardRedeemedEvent" -> {
                    Log.d(TAG, "Reward Redeemed: ${event.data}")
                    event.data?.let { dataString ->
                        try {
                            val rewardData = gson.fromJson(dataString, dev.xacnio.kciktv.shared.data.model.RewardRedeemedEventData::class.java)
                            
                            // Create a chat message mimicking the user with reward styling
                            val chatMessage = ChatMessage(
                                id = "reward_${System.currentTimeMillis()}",
                                content = rewardData.userInput ?: "", 
                                sender = ChatSender(
                                    id = rewardData.userId ?: 0,
                                    username = rewardData.username ?: "Unknown",
                                    color = rewardData.rewardBackgroundColor, // Highlight with reward color
                                    badges = emptyList()
                                ),
                                type = dev.xacnio.kciktv.shared.data.model.MessageType.REWARD,
                                rewardData = rewardData
                            )
                            onMessageReceived(chatMessage)
                            // Also notify generic event listener
                            onEventReceived("RewardRedeemedEvent", dataString)
                        } catch (e: Exception) {
                            Log.e(TAG, "Error parsing RewardRedeemedEvent", e)
                        }
                    }
                }
                "App\\Events\\KicksGifted", "KicksGifted" -> {
                    Log.d(TAG, "Kicks Gifted: ${event.data}")
                    event.data?.let { dataString ->
                         try {
                             gson.fromJson(dataString, dev.xacnio.kciktv.shared.data.model.KicksGiftedEventData::class.java)

                             
                              /* ChatSender parsing removed as it was unused */
                             
                             onEventReceived("KicksGifted", dataString)
                         } catch (e: Exception) {
                             Log.e(TAG, "Error parsing KicksGifted", e)
                         }
                    }
                }
                "App\\Events\\GiftedSubscriptionsEvent", "GiftedSubscriptionsEvent" -> {
                    Log.d(TAG, "Gifted Subscriptions: ${event.data}")
                    event.data?.let { dataString ->
                        try {
                            val giftData = gson.fromJson(dataString, dev.xacnio.kciktv.shared.data.model.GiftedSubscriptionsEventData::class.java)
                            val gifter = giftData.gifterUsername ?: "An Anonymous Gifter"
                            val total = giftData.giftedUsernames?.size ?: 0
                            val gifterTotal = giftData.gifterTotal ?: total
                            
                            // 1. Send main summary message
                            val mainContent = if (total > 1) {
                                context.getString(R.string.chat_gifted_subs_plural, gifter, total, gifterTotal)
                            } else {
                                context.getString(R.string.chat_gifted_sub_single, gifter, gifterTotal)
                            }
                            
                            val mainMessage = ChatMessage(
                                id = "gift_main_${System.currentTimeMillis()}",
                                content = mainContent,
                                sender = ChatSender(0, context.getString(R.string.chat_system_username), null, null),
                                type = dev.xacnio.kciktv.shared.data.model.MessageType.SYSTEM,
                                iconResId = R.drawable.ic_gift
                            )
                            onMessageReceived(mainMessage)

                            // 2. Send individual messages for each recipient
                            giftData.giftedUsernames?.forEachIndexed { index, recipient ->
                                val individualContent = context.getString(R.string.chat_gifted_sub_individual, gifter, recipient)
                                val individualMessage = ChatMessage(
                                    id = "gift_indiv_${System.currentTimeMillis()}_$index",
                                    content = individualContent,
                                    sender = ChatSender(0, context.getString(R.string.chat_system_username), null, null),
                                    type = dev.xacnio.kciktv.shared.data.model.MessageType.SYSTEM,
                                    iconResId = R.drawable.ic_heart // Using a heart icon for individual gift acknowledgments
                                )
                                onMessageReceived(individualMessage)
                            }
                            
                            onEventReceived("GiftedSubscriptionsEvent", dataString)
                        } catch (e: Exception) {
                            Log.e(TAG, "Error parsing GiftedSubscriptionsEvent", e)
                        }
                    }
                }
                "App\\Events\\PointsUpdated",
                "PointsUpdated" -> {
                    Log.d(TAG, "Points Updated Event: ${event.data}")
                    event.data?.let { dataString ->
                        onEventReceived("PointsUpdated", dataString)
                    }
                }
                "App\\Events\\SetupTvEvent", "SetupTvEvent" -> {
                    event.data?.let { dataString ->
                        onEventReceived("App\\Events\\SetupTvEvent", dataString)
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
                    } else if (rawEventName.contains("SubscribersModeActivated") || rawEventName.contains("SubscribersModeDeactivated") ||
                               rawEventName.contains("EmotesModeActivated") || rawEventName.contains("EmotesModeDeactivated") ||
                               rawEventName.contains("FollowersModeActivated") || rawEventName.contains("FollowersModeDeactivated") ||
                               rawEventName.contains("SlowModeActivated") || rawEventName.contains("SlowModeDeactivated")) {
                        // Handle potential App\Events\ prefix or other variations
                        val cleanEventName = rawEventName.substringAfterLast("\\")
                        event.data?.let { dataString ->
                            onEventReceived(cleanEventName, dataString)
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

    fun subscribeToTvSetup(uuid: String) {
        if (!isConnected) {
            Log.d(TAG, "Postponing TV setup subscription until connected")
            return
        }
        val subscribe = """{"event":"pusher:subscribe","data":{"channel":"tv-setup-$uuid"}}"""
        webSocket?.send(subscribe)
        Log.d(TAG, "Requested subscription to tv-setup: $uuid")
    }

    fun unsubscribeFromTvSetup(uuid: String) {
        val unsubscribe = """{"event":"pusher:unsubscribe","data":{"channel":"tv-setup-$uuid"}}"""
            webSocket?.send(unsubscribe)
    }
    
    private fun parseChatMessage(event: PusherChatEvent): ChatMessage? {
        val sender = event.sender ?: return null
        val content = event.content?.replace(Regex("[\\r\\n]+"), " ") ?: return null
        val id = event.id ?: return null
        
        val messageRef = event.metadata?.messageRef

        val metadata = event.metadata?.let { meta ->
            if (meta.originalSender != null || meta.originalMessage != null) {
                dev.xacnio.kciktv.shared.data.model.ChatMetadata(
                    originalSender = meta.originalSender?.let { s ->
                        ChatSender(
                            id = s.id ?: 0,
                            username = s.username ?: "Unknown",
                            color = s.identity?.color,
                            badges = s.identity?.badges?.mapNotNull { badge ->
                                badge.type?.let { type ->
                                    ChatBadge(type = type, text = badge.text, count = badge.count)
                                }
                            },
                            profilePicture = s.profilePicture
                        )
                    },
                    originalMessageContent = meta.originalMessage?.content?.replace(Regex("[\\r\\n]+"), " "),
                    originalMessageId = meta.originalMessage?.id
                )
            } else null
        }
        
        val typeValue = event.type ?: ""
        val messageType = when (typeValue) {
            "celebration" -> dev.xacnio.kciktv.shared.data.model.MessageType.CELEBRATION
            "gifted_sub", "sub_gift" -> dev.xacnio.kciktv.shared.data.model.MessageType.CELEBRATION // Treat as celebration
            else -> dev.xacnio.kciktv.shared.data.model.MessageType.CHAT
        }

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
                },
                profilePicture = sender.profilePicture
            ),
            messageRef = messageRef,
            metadata = metadata,
            type = messageType,
            celebrationData = event.metadata?.celebration
        )
    }
    
    fun isConnected(): Boolean = isConnected
}
