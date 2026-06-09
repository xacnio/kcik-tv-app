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

/**
 * Kick Chat WebSocket client using the Pusher protocol.
 *
 * Connects to all three Pusher clusters and mirrors every subscription onto each, since a
 * channel may live on any of them. Duplicate frames are filtered by [seenPayloads]. Reports
 * "connected" while at least one cluster is up.
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

        // authCluster: private (auth'd) channels are only served by this cluster, so we
        // request auth and subscribe to them on this connection only.
        private data class ClusterConfig(val base: String, val version: String, val authCluster: Boolean = false)

        private val CLUSTERS = listOf(
            ClusterConfig("wss://ws-us2.pusher.com/app/32cbd69e4b950bf97679", "8.4.0-rc2", authCluster = true)
            //ClusterConfig("wss://ws-us3.pusher.com/app/dd11c46dae0376080879", "8.5.0"),  
            //ClusterConfig("wss://ws-mt1.pusher.com/app/73aa60a071d0943a6b3e", "8.5.0")
        )

        private fun buildWebSocketUrl(cfg: ClusterConfig): String {
            return "${cfg.base}?protocol=7&client=js&version=${cfg.version}&flash=false"
        }

        private const val MAX_RECONNECT_ATTEMPTS = 10
        private const val BASE_RECONNECT_DELAY_MS = 1000L
        private const val MAX_RECONNECT_DELAY_MS = 8000L
        private const val DEDUP_MAX_ENTRIES = 400
    }

    private val gson = Gson()
    // Reuse the app-wide OkHttpClient (RetrofitClient) so we share its connection pool
    // and dispatcher thread pool. Previously every chat session built a fresh client +
    // thread pool — switching channels piled up duplicates. newBuilder() inherits the
    // SSL/cert config and the User-Agent interceptor; we override the timeouts and add
    // the WS keep-alive ping that Retrofit doesn't need.
    private val client = dev.xacnio.kciktv.shared.data.api.RetrofitClient.okHttpClient.newBuilder()
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .pingInterval(30, TimeUnit.SECONDS)
        .build()

    // Desired subscription state, replayed onto every cluster connection when it (re)opens.
    private var currentChatroomId: Long? = null
    private var currentChannelId: Long? = null
    private var currentTvSetupUuid: String? = null
    private var currentLivestreamId: Long? = null

    // Cleared by disconnect() to stop all reconnect loops and drop late in-flight frames.
    private val shouldReconnect = AtomicBoolean(true)
    private val aggregateConnected = AtomicBoolean(false)

    // Keepalive ping interval (default 2 min; low-battery mode raises it to 5 min).
    private var pingIntervalMs = 120_000L

    // One-shot callback for when chat re-subscription succeeds (used by low battery resume).
    var onChatResubscribed: (() -> Unit)? = null

    // LRU of recently seen data-frame payloads, used to drop duplicates across clusters.
    private val seenPayloads = java.util.Collections.synchronizedMap(
        object : LinkedHashMap<String, Boolean>(DEDUP_MAX_ENTRIES, 0.75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Boolean>): Boolean {
                return size > DEDUP_MAX_ENTRIES
            }
        }
    )

    private val connections: List<Connection> = CLUSTERS.map { Connection(it) }

    /** Adjust the keepalive ping interval (e.g. low-battery mode → 300 000 ms). */
    fun setPingInterval(intervalMs: Long) {
        pingIntervalMs = intervalMs
    }

    /** A single WebSocket connection to one Pusher cluster, with its own socket id, backoff and ping. */
    private inner class Connection(val config: ClusterConfig) {
        private var webSocket: WebSocket? = null
        @Volatile var isConnected = false
        @Volatile var socketId: String? = null
        @Volatile var gaveUp = false

        private var reconnectAttempt = 0
        private val handler = Handler(Looper.getMainLooper())
        private var reconnectRunnable: Runnable? = null

        private val pingRunnable = object : Runnable {
            override fun run() {
                if (isConnected) {
                    sendPing()
                    handler.postDelayed(this, pingIntervalMs)
                }
            }
        }

        fun connect() {
            if (isConnected) return
            cancelReconnect()

            val request = Request.Builder()
                .url(buildWebSocketUrl(config))
                .build()

            webSocket = client.newWebSocket(request, object : WebSocketListener() {
                override fun onOpen(ws: WebSocket, response: Response) {
                    Log.d(TAG, "[${config.base}] WebSocket connected")
                    isConnected = true
                    reconnectAttempt = 0
                    gaveUp = false

                    handler.removeCallbacks(pingRunnable)
                    handler.postDelayed(pingRunnable, pingIntervalMs)

                    // Public channels are replayed here; private (auth'd) channels re-subscribe
                    // via the fresh socket id from the connection_established frame.
                    resubscribePublic(this@Connection)

                    onAnyConnected()
                }

                override fun onMessage(ws: WebSocket, text: String) {
                    handleMessage(this@Connection, text)
                }

                override fun onClosing(ws: WebSocket, code: Int, reason: String) {
                    Log.d(TAG, "[${config.base}] WebSocket closing: $reason")
                }

                override fun onClosed(ws: WebSocket, code: Int, reason: String) {
                    Log.d(TAG, "[${config.base}] WebSocket closed: $reason")
                    handleDown()
                }

                override fun onFailure(ws: WebSocket, t: Throwable, response: Response?) {
                    Log.e(TAG, "[${config.base}] WebSocket error: ${t.message}")
                    handleDown()
                }
            })
        }

        private fun handleDown() {
            handler.removeCallbacks(pingRunnable)
            isConnected = false
            socketId = null
            onAnyDisconnected()
            scheduleReconnect()
        }

        private fun scheduleReconnect() {
            if (!shouldReconnect.get()) {
                Log.d(TAG, "[${config.base}] Reconnect disabled, not scheduling")
                return
            }

            if (reconnectAttempt >= MAX_RECONNECT_ATTEMPTS) {
                Log.w(TAG, "[${config.base}] Max reconnect attempts reached, giving up")
                gaveUp = true
                onConnectionGaveUp()
                return
            }

            val delay = minOf(BASE_RECONNECT_DELAY_MS * (1 shl reconnectAttempt), MAX_RECONNECT_DELAY_MS)
            reconnectAttempt++

            Log.d(TAG, "[${config.base}] Scheduling reconnect attempt $reconnectAttempt in ${delay}ms")
            onConnectionReconnecting(reconnectAttempt)

            reconnectRunnable = Runnable {
                if (shouldReconnect.get() && !isConnected) {
                    Log.d(TAG, "[${config.base}] Attempting reconnect ($reconnectAttempt/$MAX_RECONNECT_ATTEMPTS)")
                    connect()
                }
            }
            handler.postDelayed(reconnectRunnable!!, delay)
        }

        fun cancelReconnect() {
            reconnectRunnable?.let { handler.removeCallbacks(it) }
            reconnectRunnable = null
        }

        /** Reset backoff so a user-triggered reconnect starts fresh. */
        fun resetBackoff() {
            reconnectAttempt = 0
            gaveUp = false
        }

        private fun sendPing() {
            if (!isConnected) return
            webSocket?.send("""{"event":"pusher:ping","data":{}}""")
            Log.d(TAG, "[${config.base}] Sent ping")
        }

        fun send(text: String) {
            webSocket?.send(text)
        }

        fun close() {
            cancelReconnect()
            handler.removeCallbacks(pingRunnable)
            isConnected = false
            socketId = null
            try {
                webSocket?.cancel() // Immediate termination to prevent late messages
            } catch (e: Exception) {
                // Ignore
            }
            webSocket = null
        }
    }

    private fun anyConnected(): Boolean = connections.any { it.isConnected }

    private fun onAnyConnected() {
        if (aggregateConnected.compareAndSet(false, true)) {
            onConnectionStateChanged(true)
        }
    }

    private fun onAnyDisconnected() {
        if (!anyConnected() && aggregateConnected.compareAndSet(true, false)) {
            onConnectionStateChanged(false)
        }
    }

    private fun onConnectionReconnecting(attempt: Int) {
        // Only show the reconnect UI when no cluster is up; a single flaky one shouldn't.
        if (!anyConnected()) {
            onReconnecting?.invoke(attempt, MAX_RECONNECT_ATTEMPTS)
        }
    }

    private fun onConnectionGaveUp() {
        // Report a hard failure only when every cluster has exhausted its retries.
        if (!anyConnected() && connections.all { it.gaveUp }) {
            onMaxRetriesReached?.invoke()
        }
    }

    /**
     * Connect to all Pusher clusters.
     */
    fun connect() {
        shouldReconnect.set(true)
        connections.forEach { it.connect() }
    }

    /**
     * Manual reconnect triggered by user (e.g., retry button). Resets retry counters and
     * attempts an immediate connection on every cluster.
     */
    fun manualReconnect() {
        shouldReconnect.set(true)
        connections.forEach {
            it.resetBackoff()
            it.cancelReconnect()
            it.connect()
        }
    }

    private fun chatSubscribeMessages(chatroomId: Long) = listOf(
        """{"event":"pusher:subscribe","data":{"auth":"","channel":"chatrooms.$chatroomId.v2"}}""",
        """{"event":"pusher:subscribe","data":{"auth":"","channel":"chatrooms.$chatroomId"}}""",
        """{"event":"pusher:subscribe","data":{"auth":"","channel":"chatroom_$chatroomId"}}"""
    )

    private fun channelEventMessages(channelId: Long) = listOf(
        """{"event":"pusher:subscribe","data":{"auth":"","channel":"channel.$channelId"}}""",
        """{"event":"pusher:subscribe","data":{"auth":"","channel":"channel_$channelId"}}"""
    )

    private fun predictionMessages(channelId: Long) = listOf(
        """{"event":"pusher:subscribe","data":{"auth":"","channel":"predictions-channel-$channelId"}}"""
    )

    private fun tvSetupMessage(uuid: String) =
        """{"event":"pusher:subscribe","data":{"channel":"tv-setup-$uuid"}}"""

    /** Send a message to every currently-connected cluster. */
    private fun sendToAll(message: String) {
        connections.forEach { if (it.isConnected) it.send(message) }
    }

    /** Replay the desired public subscriptions onto a single (freshly opened) connection. */
    private fun resubscribePublic(conn: Connection) {
        currentChatroomId?.let { id -> chatSubscribeMessages(id).forEach { conn.send(it) } }
        currentChannelId?.let { id ->
            channelEventMessages(id).forEach { conn.send(it) }
            predictionMessages(id).forEach { conn.send(it) }
        }
        currentTvSetupUuid?.let { conn.send(tvSetupMessage(it)) }
    }

    fun subscribeToChat(chatroomId: Long) {
        currentChatroomId = chatroomId
        chatSubscribeMessages(chatroomId).forEach { sendToAll(it) }
        Log.d(TAG, "Requested subscription to chatroom: $chatroomId (v2, meta, and reward)")
    }

    fun subscribeToChannelEvents(channelId: Long) {
        currentChannelId = channelId
        channelEventMessages(channelId).forEach { sendToAll(it) }
        Log.d(TAG, "Requested subscription to channel events: $channelId (dot and underscore)")
    }

    fun subscribeToPredictions(channelId: Long) {
        currentChannelId = channelId
        predictionMessages(channelId).forEach { sendToAll(it) }
        Log.d(TAG, "Requested subscription to predictions: $channelId")
    }

    /**
     * Subscribe to a private chatroom channel. The [auth] token is socket-id-bound, so it is
     * routed only to the cluster connection that owns [socketId].
     */
    fun subscribeToPrivateChatroom(socketId: String, chatroomId: Long, auth: String) {
        val conn = connections.firstOrNull { it.socketId == socketId && it.isConnected } ?: return
        conn.send("""{"event":"pusher:subscribe","data":{"auth":"$auth","channel":"private-chatroom_$chatroomId"}}""")
        Log.d(TAG, "Requested subscription to private-chatroom: $chatroomId on ${conn.config.base}")
    }

    /** Subscribe to the private channel-points channel, routed by [socketId]. */
    fun subscribeToChannelPoints(socketId: String, userId: Long, auth: String) {
        val conn = connections.firstOrNull { it.socketId == socketId && it.isConnected } ?: return
        conn.send("""{"event":"pusher:subscribe","data":{"auth":"$auth","channel":"private-channelpoints-$userId"}}""")
        Log.d(TAG, "Requested subscription to channel points: $userId on ${conn.config.base}")
    }

    /** Subscribe to the private livestream channel to receive LivestreamUpdated events, routed by [socketId]. */
    fun subscribeToLivestream(socketId: String, livestreamId: Long, auth: String) {
        val conn = connections.firstOrNull { it.socketId == socketId && it.isConnected } ?: return
        currentLivestreamId = livestreamId
        conn.send("""{"event":"pusher:subscribe","data":{"auth":"$auth","channel":"private-livestream.$livestreamId"}}""")
        Log.d(TAG, "Requested subscription to private-livestream: $livestreamId on ${conn.config.base}")
    }

    fun subscribeToTvSetup(uuid: String) {
        currentTvSetupUuid = uuid
        sendToAll(tvSetupMessage(uuid))
        Log.d(TAG, "Requested subscription to tv-setup: $uuid")
    }

    fun unsubscribeFromTvSetup(uuid: String) {
        sendToAll("""{"event":"pusher:unsubscribe","data":{"channel":"tv-setup-$uuid"}}""")
        if (currentTvSetupUuid == uuid) currentTvSetupUuid = null
    }

    fun unsubscribeFromOnlyChat() {
        currentChatroomId?.let { id ->
            sendToAll("""{"event":"pusher:unsubscribe","data":{"channel":"chatrooms.$id.v2"}}""")
        }
    }

    fun subscribeToOnlyChat() {
        currentChatroomId?.let { id ->
            sendToAll("""{"event":"pusher:subscribe","data":{"auth":"","channel":"chatrooms.$id.v2"}}""")
        }
    }

    fun unsubscribeFromChat() {
        currentChatroomId?.let { id ->
            sendToAll("""{"event":"pusher:unsubscribe","data":{"channel":"chatrooms.$id.v2"}}""")
            sendToAll("""{"event":"pusher:unsubscribe","data":{"channel":"chatrooms.$id"}}""")
            sendToAll("""{"event":"pusher:unsubscribe","data":{"channel":"chatroom_$id"}}""")
            sendToAll("""{"event":"pusher:unsubscribe","data":{"channel":"private-chatroom_$id"}}""")
            currentChatroomId = null
        }
    }

    fun unsubscribeFromChannelEvents() {
        currentChannelId?.let { id ->
            sendToAll("""{"event":"pusher:unsubscribe","data":{"channel":"channel.$id"}}""")
            sendToAll("""{"event":"pusher:unsubscribe","data":{"channel":"channel_$id"}}""")
            sendToAll("""{"event":"pusher:unsubscribe","data":{"channel":"predictions-channel-$id"}}""")
            currentChannelId = null
        }
    }

    /**
     * Fully disconnect and close every WebSocket. Disables auto-reconnect.
     */
    fun disconnect() {
        shouldReconnect.set(false)

        unsubscribeFromChat()
        unsubscribeFromChannelEvents()
        currentLivestreamId?.let { id ->
            sendToAll("""{"event":"pusher:unsubscribe","data":{"channel":"private-livestream.$id"}}""")
            currentLivestreamId = null
        }

        connections.forEach { it.close() }
        aggregateConnected.set(false)
        seenPayloads.clear()
    }

    private fun handleMessage(conn: Connection, text: String) {
        // Drop any late in-flight frames after a full disconnect().
        if (!shouldReconnect.get()) return

        try {
            val event = gson.fromJson(text, PusherEvent::class.java)
            val eventName = event.event

            // Control frames are per-connection (each socket has its own connection_established),
            // so never de-dup them; de-dup only data frames mirrored across clusters.
            val isControl = eventName != null &&
                (eventName.startsWith("pusher:") || eventName.startsWith("pusher_internal:"))

            if (!isControl && seenPayloads.put(text, true) != null) {
                return
            }

            if (eventName?.contains("ChatMessageEvent") != true && !isControl) {
                 Log.d(TAG, "WS Msg: $eventName Data: ${event.data}")
            }

            when (eventName) {
                "pusher:connection_established" -> {
                    Log.d(TAG, "[${conn.config.base}] Pusher connection established")
                    try {
                        val data = gson.fromJson(event.data, Map::class.java)
                        val id = data["socket_id"] as? String
                        if (id != null) {
                            conn.socketId = id
                            Log.d(TAG, "[${conn.config.base}] Socket ID received: $id")
                            // Only the auth cluster serves private channels, so only it drives
                            // the auth/subscribe flow.
                            if (conn.config.authCluster) {
                                onSocketIdReceived?.invoke(id)
                            }
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "Error parsing connection_established data", e)
                    }
                }
                "pusher_internal:subscription_succeeded" -> {
                    Log.d(TAG, "Subscription succeeded to channel: ${event.channel}")
                    // Fire one-shot callback when chatroom v2 subscription succeeds
                    if (event.channel?.contains(".v2") == true) {
                        onChatResubscribed?.let { cb ->
                            onChatResubscribed = null
                            cb()
                        }
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
                        onEventReceived(eventName, dataString)
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
                "App\\Events\\StreamHostedEvent", "StreamHostedEvent" -> {
                    // Fired on chatrooms.<id> when another channel hosts the current stream.
                    // We emit a SYSTEM chat message so users see the host announcement inline.
                    Log.d(TAG, "Stream Hosted Event: ${event.data}")
                    event.data?.let { dataString ->
                        try {
                            val obj = com.google.gson.JsonParser.parseString(dataString).asJsonObject
                            val userObj = obj.getAsJsonObject("user")
                            val messageObj = obj.getAsJsonObject("message")
                            val hostUsername = userObj?.get("username")?.asString ?: return@let
                            val viewers = messageObj?.get("numberOfViewers")?.takeIf { !it.isJsonNull }?.asInt ?: 0
                            val msgId = messageObj?.get("id")?.takeIf { !it.isJsonNull }?.asString
                                ?: "host_${System.currentTimeMillis()}"

                            val content = if (viewers > 0) {
                                context.getString(R.string.chat_stream_hosted, hostUsername, viewers)
                            } else {
                                context.getString(R.string.chat_stream_hosted_no_count, hostUsername)
                            }

                            val systemMessage = ChatMessage(
                                id = "host_$msgId",
                                content = content,
                                sender = ChatSender(0, context.getString(R.string.chat_system_username), null, null),
                                type = dev.xacnio.kciktv.shared.data.model.MessageType.SYSTEM,
                                iconResId = R.drawable.ic_tv
                            )
                            onMessageReceived(systemMessage)
                            onEventReceived("App\\Events\\StreamHostedEvent", dataString)
                        } catch (e: Exception) {
                            Log.e(TAG, "Error parsing StreamHostedEvent", e)
                        }
                    }
                }
                "App\\Events\\StreamHostEvent", "StreamHostEvent" -> {
                    // Duplicate of StreamHostedEvent delivered on the chatrooms.<id>.v2 channel.
                    // The .v2 variant has less data (no host user id, no message id) and fires at
                    // the same time, so we ignore it to avoid showing the system message twice.
                    Log.d(TAG, "Ignoring duplicate StreamHostEvent (v2) — handled via StreamHostedEvent")
                }
                "App\\Events\\SubscriptionEvent", "SubscriptionEvent" -> {
                    // Fired on chatrooms.<id>.v2 when a user subscribes (new or renewal).
                    // Has the months count, which lets us distinguish new vs resub.
                    Log.d(TAG, "Subscription Event: ${event.data}")
                    event.data?.let { dataString ->
                        try {
                            val obj = com.google.gson.JsonParser.parseString(dataString).asJsonObject
                            val username = obj.get("username")?.takeIf { !it.isJsonNull }?.asString ?: return@let
                            val months = obj.get("months")?.takeIf { !it.isJsonNull }?.asInt ?: 1

                            val content = if (months <= 1) {
                                context.getString(R.string.chat_subscribed_new, username)
                            } else {
                                context.getString(R.string.chat_subscribed_resub, username, months)
                            }

                            val systemMessage = ChatMessage(
                                id = "sub_${username}_${System.currentTimeMillis()}",
                                content = content,
                                sender = ChatSender(0, context.getString(R.string.chat_system_username), null, null),
                                type = dev.xacnio.kciktv.shared.data.model.MessageType.SYSTEM,
                                iconResId = R.drawable.ic_star
                            )
                            onMessageReceived(systemMessage)
                            onEventReceived("App\\Events\\SubscriptionEvent", dataString)
                        } catch (e: Exception) {
                            Log.e(TAG, "Error parsing SubscriptionEvent", e)
                        }
                    }
                }
                "App\\Events\\ChannelSubscriptionEvent", "ChannelSubscriptionEvent" -> {
                    // Fires on channel.<id> at the same time as SubscriptionEvent but with less
                    // data (no months count, just user_ids). Ignored to avoid a duplicate chat
                    // notification; SubscriptionEvent above is the canonical source.
                    Log.d(TAG, "Ignoring duplicate ChannelSubscriptionEvent — handled via SubscriptionEvent")
                }
                "App\\Events\\LivestreamUpdated" -> {
                    event.data?.let { dataString ->
                        onEventReceived("App\\Events\\LivestreamUpdated", dataString)
                    }
                }
                "App\\Events\\SetupTvEvent", "SetupTvEvent" -> {
                    event.data?.let { dataString ->
                        onEventReceived("App\\Events\\SetupTvEvent", dataString)
                    }
                }
                else -> {
                    // Try flexible matching for events with different backslash escaping
                    val rawEventName = eventName ?: ""
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
                            badgesV2 = s.identity?.badgesV2,
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
                badgesV2 = sender.identity?.badgesV2,
                profilePicture = sender.profilePicture
            ),
            messageRef = messageRef,
            metadata = metadata,
            type = messageType,
            celebrationData = event.metadata?.celebration
        )
    }

    fun isConnected(): Boolean = anyConnected()
}
