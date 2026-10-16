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
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import dev.xacnio.kciktv.R
import dev.xacnio.kciktv.shared.data.api.RealtimeCapabilities
import dev.xacnio.kciktv.shared.data.api.RealtimeChannelAuthRequest
import dev.xacnio.kciktv.shared.data.api.RealtimeClient
import dev.xacnio.kciktv.shared.data.api.RealtimeConnectionAuthRequest
import dev.xacnio.kciktv.shared.data.api.RealtimeConnectionRequest
import dev.xacnio.kciktv.shared.data.api.RealtimeProvider
import dev.xacnio.kciktv.shared.data.api.RetrofitClient
import dev.xacnio.kciktv.shared.data.model.ChatBadge
import dev.xacnio.kciktv.shared.data.model.ChatMessage
import dev.xacnio.kciktv.shared.data.model.ChatSender
import dev.xacnio.kciktv.shared.data.model.PusherChatEvent
import dev.xacnio.kciktv.shared.data.model.PusherEvent
import dev.xacnio.kciktv.shared.data.prefs.AppPreferences
import dev.xacnio.kciktv.shared.util.TimeUtils
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.launch
import okhttp3.*
import java.util.concurrent.TimeUnit

/**
 * Kick Chat WebSocket client for Kick's realtime service (Centrifugo JSON protocol).
 *
 * Connection and private channel tokens come from web.kick.com; publications still carry
 * Kick's Pusher-style `{event, data}` payload.
 */
class KcikChatWebSocket(
    private val context: Context,
    private val onMessageReceived: (ChatMessage) -> Unit,
    private val onEventReceived: (String, String) -> Unit = { _, _ -> },
    private val onConnectionStateChanged: (Boolean) -> Unit,
    private val onReconnecting: ((current: Int, max: Int) -> Unit)? = null,
    private val onMaxRetriesReached: (() -> Unit)? = null
) {
    companion object {
        private const val TAG = "KcikChatWebSocket"

        private const val DEFAULT_WS_URL = "wss://realtime.us-east-1.platform.kick.com/connection/websocket"
        private const val PROVIDER_CENTRIFUGO = "centrifugo"
        // Subscribe flag the Kick web client sends with every subscription.
        private const val SUBSCRIBE_FLAG = 1
        // Centrifugo "already subscribed" error code.
        private const val ERROR_ALREADY_SUBSCRIBED = 105
        // Server unsubscribe codes >= 2500 mean the client should resubscribe.
        private const val UNSUBSCRIBE_RESUBSCRIBE_MIN = 2500

        private const val MAX_RECONNECT_ATTEMPTS = 10
        private const val BASE_RECONNECT_DELAY_MS = 1000L
        private const val MAX_RECONNECT_DELAY_MS = 8000L

        // No frame within the server ping interval + grace means the socket is dead
        private const val DEFAULT_SERVER_PING_MS = 25_000L
        private const val SERVER_PING_GRACE_MS = 15_000L
        private const val WATCHDOG_INTERVAL_MS = 10_000L
    }

    private val gson = Gson()
    private val prefs = AppPreferences(context)
    private val authService = RetrofitClient.authService
    // Shares the app-wide OkHttpClient; no read timeout for the long-lived socket
    private val client = RetrofitClient.okHttpClient.newBuilder()
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .build()

    private val handler = Handler(Looper.getMainLooper())
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    // Guests need a stable client id for the lifetime of this socket.
    private val guestClientId = UUID.randomUUID().toString()
    @Volatile private var wsUrl: String? = null

    // Desired subscription state, replayed onto the connection whenever it (re)opens.
    private val publicChannels = linkedSetOf<String>()
    private val privateChannels = linkedSetOf<String>()
    private var currentChatroomId: Long? = null
    private var currentChannelId: Long? = null
    private var currentTvSetupUuid: String? = null
    private var currentLivestreamId: Long? = null

    // Cleared by disconnect() to stop all reconnect loops and drop late in-flight frames.
    private val shouldReconnect = AtomicBoolean(true)
    // Bumped whenever a socket is opened or torn down; callbacks from older sockets are ignored.
    private val generation = AtomicInteger(0)
    @Volatile private var webSocket: WebSocket? = null
    @Volatile private var connecting = false
    @Volatile private var connected = false

    private var reconnectAttempt = 0
    private var reconnectRunnable: Runnable? = null

    private val commandId = AtomicInteger(0)
    @Volatile private var connectCommandId = -1
    // Command id -> channel for in-flight subscribe / sub_refresh commands.
    private val pendingSubscribes = ConcurrentHashMap<Int, String>()
    private val pendingSubRefreshes = ConcurrentHashMap<Int, String>()
    private val pendingConnectionRefreshes = java.util.Collections.newSetFromMap(ConcurrentHashMap<Int, Boolean>())

    @Volatile private var serverPingMs = DEFAULT_SERVER_PING_MS
    @Volatile private var lastFrameAt = 0L

    private var connectionRefreshRunnable: Runnable? = null
    private val subRefreshRunnables = ConcurrentHashMap<String, Runnable>()

    // One-shot callback for when chat re-subscription succeeds (used by low battery resume).
    var onChatResubscribed: (() -> Unit)? = null

    private val watchdogRunnable = object : Runnable {
        override fun run() {
            if (!connected) return
            if (System.currentTimeMillis() - lastFrameAt > serverPingMs + SERVER_PING_GRACE_MS) {
                Log.w(TAG, "No server ping within ${serverPingMs + SERVER_PING_GRACE_MS}ms, reconnecting")
                val ws = webSocket
                handleDown(generation.get(), "ping timeout")
                ws?.cancel()
                return
            }
            handler.postDelayed(this, WATCHDOG_INTERVAL_MS)
        }
    }

    private fun authHeader(): String? =
        prefs.authToken?.takeIf { it.isNotBlank() }?.let { "Bearer $it" }

    private fun clientId(): String =
        if (authHeader() != null && prefs.userId > 0) prefs.userId.toString() else guestClientId

    /** Ask Kick which realtime server to use; falls back to the known default. */
    private suspend fun resolveUrl(): String {
        wsUrl?.let { return it }
        val url = try {
            val res = authService.getRealtimeConnection(
                authHeader(),
                RealtimeConnectionRequest(
                    client = RealtimeClient(clientId()),
                    capabilities = RealtimeCapabilities(listOf(RealtimeProvider(PROVIDER_CENTRIFUGO)))
                )
            )
            res.body()?.data?.connections
                ?.firstOrNull { it.provider == PROVIDER_CENTRIFUGO }
                ?.credentials?.url
        } catch (e: Exception) {
            Log.w(TAG, "Realtime connection lookup failed: ${e.message}")
            null
        }
        return (url ?: DEFAULT_WS_URL).also { wsUrl = it }
    }

    private suspend fun fetchConnectionToken(): String? {
        val auth = authHeader()
        val token = requestConnectionToken(auth, clientId())
        // Fall back to a guest token if the user token is rejected
        if (token == null && auth != null) return requestConnectionToken(null, guestClientId)
        return token
    }

    private suspend fun requestConnectionToken(auth: String?, clientId: String): String? = try {
        val res = authService.getRealtimeConnectionToken(auth, RealtimeConnectionAuthRequest(clientId))
        res.body()?.data?.token.also {
            if (it == null) Log.w(TAG, "Connection token request failed: HTTP ${res.code()}")
        }
    } catch (e: Exception) {
        Log.w(TAG, "Connection token request failed: ${e.message}")
        null
    }

    private suspend fun fetchChannelToken(channel: String): String? {
        val auth = authHeader() ?: return null
        return try {
            val res = authService.getRealtimeChannelToken(auth, RealtimeChannelAuthRequest(channel))
            res.body()?.data?.token.also {
                if (it == null) Log.w(TAG, "Channel token for $channel failed: HTTP ${res.code()}")
            }
        } catch (e: Exception) {
            Log.w(TAG, "Channel token for $channel failed: ${e.message}")
            null
        }
    }

    private fun openConnection() {
        if (connected || connecting || webSocket != null) return
        cancelReconnect()
        connecting = true
        val gen = generation.incrementAndGet()

        scope.launch {
            val url = resolveUrl()
            val token = fetchConnectionToken()
            if (gen != generation.get() || !shouldReconnect.get()) return@launch
            if (token == null) {
                handleDown(gen, "no connection token")
                return@launch
            }

            val request = Request.Builder()
                .url(url)
                .header("Origin", "https://kick.com")
                .build()
            webSocket = client.newWebSocket(request, Listener(gen, token))
        }
    }

    private inner class Listener(private val gen: Int, private val token: String) : WebSocketListener() {
        override fun onOpen(webSocket: WebSocket, response: Response) {
            if (gen != generation.get()) return
            Log.d(TAG, "WebSocket opened, sending connect")
            lastFrameAt = System.currentTimeMillis()
            val params = JsonObject().apply {
                addProperty("token", token)
                addProperty("name", "js")
            }
            val id = commandId.incrementAndGet()
            connectCommandId = id
            webSocket.send(command(id, "connect", params))
        }

        override fun onMessage(webSocket: WebSocket, text: String) {
            if (gen != generation.get() || !shouldReconnect.get()) return
            lastFrameAt = System.currentTimeMillis()
            // Centrifugo batches several replies/pushes into one frame, newline-separated.
            text.split('\n').forEach { line ->
                if (line.isNotBlank()) handleFrame(webSocket, gen, line)
            }
        }

        override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
            Log.d(TAG, "WebSocket closing: $code $reason")
            webSocket.close(code, null)
        }

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
            Log.d(TAG, "WebSocket closed: $code $reason")
            handleDown(gen, "closed $code $reason")
        }

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            Log.e(TAG, "WebSocket error: ${t.message}")
            handleDown(gen, "failure ${t.message}")
        }
    }

    private fun command(id: Int, method: String, params: JsonObject): String =
        JsonObject().apply {
            add(method, params)
            addProperty("id", id)
        }.toString()

    /** Tear down the socket identified by [gen] and schedule a reconnect. */
    private fun handleDown(gen: Int, reason: String) {
        if (!generation.compareAndSet(gen, gen + 1)) return
        Log.d(TAG, "Connection down: $reason")
        webSocket = null
        connecting = false
        val wasConnected = connected
        connected = false
        clearSessionState()
        handler.post {
            if (wasConnected) onConnectionStateChanged(false)
            scheduleReconnect()
        }
    }

    private fun clearSessionState() {
        handler.removeCallbacks(watchdogRunnable)
        connectionRefreshRunnable?.let { handler.removeCallbacks(it) }
        connectionRefreshRunnable = null
        subRefreshRunnables.values.forEach { handler.removeCallbacks(it) }
        subRefreshRunnables.clear()
        pendingSubscribes.clear()
        pendingSubRefreshes.clear()
        pendingConnectionRefreshes.clear()
        connectCommandId = -1
    }

    private fun scheduleReconnect() {
        if (!shouldReconnect.get()) {
            Log.d(TAG, "Reconnect disabled, not scheduling")
            return
        }

        if (reconnectAttempt >= MAX_RECONNECT_ATTEMPTS) {
            Log.w(TAG, "Max reconnect attempts reached, giving up")
            onMaxRetriesReached?.invoke()
            return
        }

        val delay = minOf(BASE_RECONNECT_DELAY_MS * (1 shl reconnectAttempt), MAX_RECONNECT_DELAY_MS)
        reconnectAttempt++

        Log.d(TAG, "Scheduling reconnect attempt $reconnectAttempt in ${delay}ms")
        onReconnecting?.invoke(reconnectAttempt, MAX_RECONNECT_ATTEMPTS)

        reconnectRunnable = Runnable {
            if (shouldReconnect.get() && !connected) {
                Log.d(TAG, "Attempting reconnect ($reconnectAttempt/$MAX_RECONNECT_ATTEMPTS)")
                openConnection()
            }
        }
        handler.postDelayed(reconnectRunnable!!, delay)
    }

    private fun cancelReconnect() {
        reconnectRunnable?.let { handler.removeCallbacks(it) }
        reconnectRunnable = null
    }

    /**
     * Connect to Kick's realtime service.
     */
    fun connect() {
        shouldReconnect.set(true)
        openConnection()
    }

    /**
     * Manual reconnect triggered by user (e.g., retry button). Resets retry counters and
     * attempts an immediate connection.
     */
    fun manualReconnect() {
        shouldReconnect.set(true)
        reconnectAttempt = 0
        cancelReconnect()
        openConnection()
    }

    private fun handleFrame(ws: WebSocket, gen: Int, line: String) {
        val frame = try {
            JsonParser.parseString(line).asJsonObject
        } catch (e: Exception) {
            Log.e(TAG, "Error parsing frame: ${e.message}")
            return
        }

        // Empty object is a server ping; answer with an empty pong.
        if (frame.size() == 0) {
            ws.send("{}")
            return
        }

        frame.getAsJsonObject("push")?.let {
            handlePush(it)
            return
        }

        val id = frame.get("id")?.takeIf { it.isJsonPrimitive }?.asInt ?: return
        val error = frame.getAsJsonObject("error")

        when {
            id == connectCommandId -> handleConnectReply(ws, gen, frame.getAsJsonObject("connect"), error)
            pendingSubscribes.containsKey(id) -> {
                val channel = pendingSubscribes.remove(id) ?: return
                handleSubscribeReply(channel, frame.getAsJsonObject("subscribe"), error)
            }
            pendingSubRefreshes.containsKey(id) -> {
                val channel = pendingSubRefreshes.remove(id) ?: return
                if (error != null) {
                    Log.w(TAG, "sub_refresh failed for $channel: $error")
                } else {
                    scheduleSubRefresh(channel, frame.getAsJsonObject("sub_refresh"))
                }
            }
            pendingConnectionRefreshes.remove(id) -> {
                if (error != null) {
                    Log.w(TAG, "Connection refresh failed: $error")
                } else {
                    scheduleConnectionRefresh(frame.getAsJsonObject("refresh"))
                }
            }
            error != null -> Log.w(TAG, "Command $id failed: $error")
        }
    }

    private fun handleConnectReply(ws: WebSocket, gen: Int, result: JsonObject?, error: JsonObject?) {
        if (error != null || result == null) {
            Log.e(TAG, "Connect rejected: $error")
            handleDown(gen, "connect rejected")
            ws.cancel()
            return
        }

        Log.d(TAG, "Realtime connected (client ${result.get("client")?.asString})")
        serverPingMs = result.get("ping")?.asLong?.times(1000) ?: DEFAULT_SERVER_PING_MS
        connected = true
        connecting = false
        scheduleConnectionRefresh(result)

        // Replay desired subscriptions in one batched frame.
        val frames = synchronized(publicChannels) { publicChannels.toList() }
            .map { subscribeCommand(it, null) }
        if (frames.isNotEmpty()) ws.send(frames.joinToString("\n"))
        synchronized(privateChannels) { privateChannels.toList() }.forEach { subscribePrivate(it) }

        handler.post {
            reconnectAttempt = 0
            handler.removeCallbacks(watchdogRunnable)
            handler.postDelayed(watchdogRunnable, WATCHDOG_INTERVAL_MS)
            onConnectionStateChanged(true)
        }
    }

    private fun handleSubscribeReply(channel: String, result: JsonObject?, error: JsonObject?) {
        if (error != null) {
            if (error.get("code")?.asInt != ERROR_ALREADY_SUBSCRIBED) {
                Log.w(TAG, "Subscribe to $channel failed: $error")
            }
            return
        }
        Log.d(TAG, "Subscription succeeded to channel: $channel")
        // Fire one-shot callback when chatroom v2 subscription succeeds
        if (channel.endsWith(".v2")) {
            onChatResubscribed?.let { cb ->
                onChatResubscribed = null
                cb()
            }
        }
        scheduleSubRefresh(channel, result)
    }

    private fun handlePush(push: JsonObject) {
        val channel = push.get("channel")?.takeIf { it.isJsonPrimitive }?.asString

        push.getAsJsonObject("pub")?.let { pub ->
            val payload = pub.get("data")?.takeIf { it.isJsonObject }?.asJsonObject ?: return
            val eventName = payload.get("event")?.takeIf { it.isJsonPrimitive }?.asString
            dispatchEvent(PusherEvent(eventName, payload.get("data").asDataString(), channel))
            return
        }

        push.getAsJsonObject("unsubscribe")?.let { unsub ->
            val code = unsub.get("code")?.asInt ?: 0
            Log.w(TAG, "Server unsubscribed $channel (code $code)")
            if (channel != null && code >= UNSUBSCRIBE_RESUBSCRIBE_MIN) {
                handler.postDelayed({ resubscribe(channel) }, BASE_RECONNECT_DELAY_MS)
            }
            return
        }

        push.getAsJsonObject("disconnect")?.let {
            // The close frame follows and drives the reconnect.
            Log.w(TAG, "Server disconnect: $it")
        }
    }

    /** Event data is usually a JSON string; raw objects are accepted too. */
    private fun JsonElement?.asDataString(): String? = when {
        this == null || isJsonNull -> null
        isJsonPrimitive -> asString
        else -> toString()
    }

    private fun resubscribe(channel: String) {
        if (!connected) return
        when {
            synchronized(publicChannels) { channel in publicChannels } -> sendSubscribe(channel)
            synchronized(privateChannels) { channel in privateChannels } -> subscribePrivate(channel)
        }
    }

    private fun subscribeCommand(channel: String, token: String?): String {
        val id = commandId.incrementAndGet()
        pendingSubscribes[id] = channel
        val params = JsonObject().apply {
            addProperty("channel", channel)
            token?.let { addProperty("token", it) }
            addProperty("flag", SUBSCRIBE_FLAG)
        }
        return command(id, "subscribe", params)
    }

    private fun sendSubscribe(channel: String, token: String? = null) {
        val ws = webSocket ?: return
        if (!connected) return
        ws.send(subscribeCommand(channel, token))
    }

    private fun sendUnsubscribe(channel: String) {
        subRefreshRunnables.remove(channel)?.let { handler.removeCallbacks(it) }
        val ws = webSocket ?: return
        if (!connected) return
        val params = JsonObject().apply { addProperty("channel", channel) }
        ws.send(command(commandId.incrementAndGet(), "unsubscribe", params))
    }

    /** Fetch a channel token and subscribe, unless the socket changed in the meantime. */
    private fun subscribePrivate(channel: String) {
        val gen = generation.get()
        scope.launch {
            val token = fetchChannelToken(channel) ?: return@launch
            if (gen != generation.get() || !connected) return@launch
            if (synchronized(privateChannels) { channel !in privateChannels }) return@launch
            sendSubscribe(channel, token)
        }
    }

    /** Refresh tokens a bit before they expire (ttl is in seconds). */
    private fun refreshDelayMs(ttlSeconds: Long): Long =
        maxOf(ttlSeconds - 60, ttlSeconds * 4 / 5, 5L) * 1000L

    private fun scheduleConnectionRefresh(result: JsonObject?) {
        if (result?.get("expires")?.asBoolean != true) return
        val ttl = result.get("ttl")?.asLong ?: return
        val gen = generation.get()
        val runnable = Runnable {
            scope.launch {
                val token = fetchConnectionToken() ?: return@launch
                if (gen != generation.get() || !connected) return@launch
                val ws = webSocket ?: return@launch
                val id = commandId.incrementAndGet()
                pendingConnectionRefreshes.add(id)
                ws.send(command(id, "refresh", JsonObject().apply { addProperty("token", token) }))
            }
        }
        handler.post {
            connectionRefreshRunnable?.let { handler.removeCallbacks(it) }
            connectionRefreshRunnable = runnable
            handler.postDelayed(runnable, refreshDelayMs(ttl))
        }
    }

    private fun scheduleSubRefresh(channel: String, result: JsonObject?) {
        if (result?.get("expires")?.asBoolean != true) return
        val ttl = result.get("ttl")?.asLong ?: return
        val gen = generation.get()
        val runnable = Runnable {
            scope.launch {
                val token = fetchChannelToken(channel) ?: return@launch
                if (gen != generation.get() || !connected) return@launch
                val ws = webSocket ?: return@launch
                val id = commandId.incrementAndGet()
                pendingSubRefreshes[id] = channel
                val params = JsonObject().apply {
                    addProperty("channel", channel)
                    addProperty("token", token)
                }
                ws.send(command(id, "sub_refresh", params))
            }
        }
        subRefreshRunnables.put(channel, runnable)?.let { handler.removeCallbacks(it) }
        handler.postDelayed(runnable, refreshDelayMs(ttl))
    }

    private fun addPublic(channels: List<String>) {
        channels.forEach { channel ->
            val added = synchronized(publicChannels) { publicChannels.add(channel) }
            if (added) sendSubscribe(channel)
        }
    }

    private fun removePublic(channels: List<String>) {
        channels.forEach { channel ->
            val removed = synchronized(publicChannels) { publicChannels.remove(channel) }
            if (removed) sendUnsubscribe(channel)
        }
    }

    private fun addPrivate(channel: String) {
        if (authHeader() == null) return
        val added = synchronized(privateChannels) { privateChannels.add(channel) }
        if (added && connected) subscribePrivate(channel)
    }

    private fun removePrivate(channel: String) {
        val removed = synchronized(privateChannels) { privateChannels.remove(channel) }
        if (removed) sendUnsubscribe(channel)
    }

    private fun chatChannels(chatroomId: Long) = listOf(
        "chatrooms.$chatroomId.v2",
        "chatrooms.$chatroomId",
        "chatroom_$chatroomId"
    )

    private fun channelEventChannels(channelId: Long) = listOf(
        "channel.$channelId",
        "channel_$channelId"
    )

    private fun predictionChannels(channelId: Long) = listOf(
        "predictions-channel-$channelId"
    )

    fun subscribeToChat(chatroomId: Long) {
        currentChatroomId = chatroomId
        addPublic(chatChannels(chatroomId))
        Log.d(TAG, "Requested subscription to chatroom: $chatroomId (v2, meta, and reward)")
    }

    fun subscribeToChannelEvents(channelId: Long) {
        currentChannelId = channelId
        addPublic(channelEventChannels(channelId))
        Log.d(TAG, "Requested subscription to channel events: $channelId (dot and underscore)")
    }

    fun subscribeToPredictions(channelId: Long) {
        currentChannelId = channelId
        addPublic(predictionChannels(channelId))
        Log.d(TAG, "Requested subscription to predictions: $channelId")
    }

    /** Subscribe to the private chatroom channel (requires login; token is fetched internally). */
    fun subscribeToPrivateChatroom(chatroomId: Long) {
        addPrivate("private-chatroom_$chatroomId")
        Log.d(TAG, "Requested subscription to private-chatroom: $chatroomId")
    }

    /** Subscribe to the private channel-points channel (requires login). */
    fun subscribeToChannelPoints(userId: Long) {
        addPrivate("private-channelpoints-$userId")
        Log.d(TAG, "Requested subscription to channel points: $userId")
    }

    /** Subscribe to the private livestream channel to receive LivestreamUpdated events (requires login). */
    fun subscribeToLivestream(livestreamId: Long) {
        currentLivestreamId?.takeIf { it != livestreamId }?.let { removePrivate("private-livestream.$it") }
        currentLivestreamId = livestreamId
        addPrivate("private-livestream.$livestreamId")
        Log.d(TAG, "Requested subscription to private-livestream: $livestreamId")
    }

    fun subscribeToTvSetup(uuid: String) {
        currentTvSetupUuid = uuid
        addPublic(listOf("tv-setup-$uuid"))
        Log.d(TAG, "Requested subscription to tv-setup: $uuid")
    }

    fun unsubscribeFromTvSetup(uuid: String) {
        removePublic(listOf("tv-setup-$uuid"))
        if (currentTvSetupUuid == uuid) currentTvSetupUuid = null
    }

    fun unsubscribeFromOnlyChat() {
        currentChatroomId?.let { id -> removePublic(listOf("chatrooms.$id.v2")) }
    }

    fun subscribeToOnlyChat() {
        currentChatroomId?.let { id -> addPublic(listOf("chatrooms.$id.v2")) }
    }

    fun unsubscribeFromChat() {
        currentChatroomId?.let { id ->
            removePublic(chatChannels(id))
            removePrivate("private-chatroom_$id")
            currentChatroomId = null
        }
    }

    fun unsubscribeFromChannelEvents() {
        currentChannelId?.let { id ->
            removePublic(channelEventChannels(id) + predictionChannels(id))
            currentChannelId = null
        }
    }

    /**
     * Fully disconnect and close the WebSocket. Disables auto-reconnect.
     */
    fun disconnect() {
        shouldReconnect.set(false)

        unsubscribeFromChat()
        unsubscribeFromChannelEvents()
        currentLivestreamId?.let { id ->
            removePrivate("private-livestream.$id")
            currentLivestreamId = null
        }
        synchronized(privateChannels) { privateChannels.clear() }

        generation.incrementAndGet()
        scope.coroutineContext.cancelChildren()
        cancelReconnect()
        clearSessionState()
        val ws = webSocket
        webSocket = null
        connecting = false
        connected = false
        try {
            ws?.cancel() // Immediate termination to prevent late messages
        } catch (e: Exception) {
            // Ignore
        }
    }

    /** Handles one Kick event. */
    private fun dispatchEvent(event: PusherEvent) {
        val eventName = event.event
        if (eventName?.contains("ChatMessageEvent") != true) {
            Log.d(TAG, "WS Msg: $eventName Data: ${event.data}")
        }

        try {
            when (eventName) {
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
            Log.e(TAG, "Error handling event $eventName: ${e.message}")
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

    fun isConnected(): Boolean = connected
}
