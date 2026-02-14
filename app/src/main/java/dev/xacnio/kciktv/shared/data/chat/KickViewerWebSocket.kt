/**
 * File: KickViewerWebSocket.kt
 *
 * Description: Implementation of Kick Viewer Web Socket functionality.
 *
 * Author: Xacnio
 *
 */
package dev.xacnio.kciktv.shared.data.chat

import android.os.Handler
import android.os.Looper
import android.util.Log
import okhttp3.*
import org.json.JSONObject
import java.util.Timer
import java.util.TimerTask
import java.util.concurrent.atomic.AtomicBoolean
import dev.xacnio.kciktv.shared.data.chat.KickViewerWebSocket

class KickViewerWebSocket(private val client: OkHttpClient) {
    private var webSocket: WebSocket? = null
    private val TAG = "KickViewerWS"
    private var pingTimer: Timer? = null
    private var handshakeTimer: Timer? = null
    private var watchEventTimer: Timer? = null
    
    // Store connection parameters for reconnection
    private var currentToken: String? = null
    private var currentChannelId: String? = null
    private var currentLivestreamId: String? = null
    
    // Auto-reconnect state
    private val shouldReconnect = AtomicBoolean(true)
    private var reconnectAttempt = 0
    // No max limit for viewer websocket - keep retrying forever
    private val baseReconnectDelayMs = 1000L
    private val maxReconnectDelayMs = 8000L
    private val reconnectHandler = Handler(Looper.getMainLooper())
    private var reconnectRunnable: Runnable? = null
    private var isConnected = false
    private var hasEverConnected = false  // Track if we've ever successfully connected
    private var wasDisconnectedAfterConnect = false  // Track if we disconnected after initial connection
    
    // Connection state callback - (isConnected, isFirstConnection)
    var onConnectionStateChanged: ((Boolean, Boolean) -> Unit)? = null
    
    // Token refresh callback - called before each reconnect to get fresh token
    var onTokenRequest: ((channelId: String, livestreamId: String?, callback: (String?) -> Unit) -> Unit)? = null

    interface ViewerWebSocketListener {
        fun onEvent(event: String, data: String)
    }

    private var listener: ViewerWebSocketListener? = null

    fun setListener(listener: ViewerWebSocketListener) {
        this.listener = listener
    }

    fun connect(token: String, channelId: String, livestreamId: String?) {
        // Store parameters for reconnection
        currentToken = token
        currentChannelId = channelId
        currentLivestreamId = livestreamId
        
        shouldReconnect.set(true)
        cancelReconnect()
        
        disconnect(intentional = false) // Close existing if any, but allow reconnects
        
        val request = Request.Builder()
             // Note: Added pusher protocol query param which is standard for Kick
            .url("wss://websockets.kick.com/viewer/v1/connect?token=$token")
            .build()
            
        webSocket = client.newWebSocket(request, object : WebSocketListener() {

    override fun onOpen(webSocket: WebSocket, response: Response) {
        Log.d(TAG, "Viewer WS Connected")
        val isFirstConnect = !hasEverConnected
        isConnected = true
        reconnectAttempt = 0 // Reset on successful connect
        
        onConnectionStateChanged?.invoke(true, isFirstConnect)
        hasEverConnected = true
        
        startHandshakeLoop(channelId)
        if (livestreamId != null) {
            startWatchEventLoop(channelId, livestreamId)
        }
        startPing()
    }

    override fun onMessage(webSocket: WebSocket, text: String) {
        try {
            val json = JSONObject(text)
            val event = json.optString("event")
            val data = json.optString("data")
            if (event.isNotEmpty()) {
                listener?.onEvent(event, data)
            }
        } catch (e: Exception) {
            // Log.e(TAG, "Error parsing WS message", e)
        }
    }
    
    override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
        Log.e(TAG, "Viewer WS Failed", t)
        isConnected = false
        stopPing()
        stopHandshakeLoop()
        stopWatchEventLoop()
        
        // Notify disconnection
        onConnectionStateChanged?.invoke(false, false)
        
        scheduleReconnect()
    }
    
    override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
         stopPing()
         stopHandshakeLoop()
         stopWatchEventLoop()
    }
    
    override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
        Log.d(TAG, "Viewer WS Closed: $reason")
        isConnected = false
        stopPing()
        stopHandshakeLoop()
        stopWatchEventLoop()
        
        // Notify disconnection
        onConnectionStateChanged?.invoke(false, false)
        
        scheduleReconnect()
    }
        })
    }
    
    /**
     * Schedule a reconnection attempt with exponential backoff
     * Unlike chat websocket, viewer websocket has no max retry limit
     */
    private fun scheduleReconnect() {
        if (!shouldReconnect.get()) {
            Log.d(TAG, "Reconnect disabled, not scheduling")
            return
        }
        
        // Can't reconnect without stored parameters
        val channelId = currentChannelId
        if (channelId == null) {
            Log.w(TAG, "Cannot reconnect: missing channelId")
            return
        }
        
        // Cap the attempt counter to prevent overflow, but don't limit retries
        val effectiveAttempt = minOf(reconnectAttempt, 10)
        val delay = minOf(baseReconnectDelayMs * (1 shl effectiveAttempt), maxReconnectDelayMs)
        reconnectAttempt++
        
        Log.d(TAG, "Scheduling reconnect attempt $reconnectAttempt in ${delay}ms")
        
        reconnectRunnable = Runnable {
            if (shouldReconnect.get() && !isConnected) {
                Log.d(TAG, "Attempting viewer WS reconnect (attempt $reconnectAttempt)")
                
                // If token request callback is set, get fresh token before reconnecting
                val tokenCallback = onTokenRequest
                if (tokenCallback != null) {
                    tokenCallback(channelId, currentLivestreamId) { newToken ->
                        if (newToken != null && shouldReconnect.get() && !isConnected) {
                            currentToken = newToken
                            connect(newToken, channelId, currentLivestreamId)
                        } else if (newToken == null) {
                            Log.w(TAG, "Token refresh failed, scheduling another retry")
                            scheduleReconnect()
                        }
                    }
                } else {
                    // Fallback: use stored token (not recommended)
                    val token = currentToken
                    if (token != null) {
                        connect(token, channelId, currentLivestreamId)
                    } else {
                        Log.w(TAG, "No token available for reconnect")
                    }
                }
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
    
    private fun startHandshakeLoop(channelId: String) {
        stopHandshakeLoop()
        handshakeTimer = Timer()
        handshakeTimer?.schedule(object : TimerTask() {
            override fun run() {
                sendHandshake(channelId)
            }
        }, 0, 15000) // 15 seconds interval
    }
    
    private fun stopHandshakeLoop() {
        handshakeTimer?.cancel()
        handshakeTimer = null
    }
    
    private fun sendHandshake(channelId: String) {
        try {
            val json = JSONObject()
            json.put("type", "channel_handshake")
            val data = JSONObject()
            val message = JSONObject()
            message.put("channelId", channelId)
            data.put("message", message)
            json.put("data", data)
            
            webSocket?.send(json.toString())
            Log.d(TAG, "Handshake sent for channel: $channelId")
        } catch (e: Exception) {
            Log.e(TAG, "Error creating handshake", e)
        }
    }
    
    private fun startWatchEventLoop(channelId: String, livestreamId: String) {
        stopWatchEventLoop()
        watchEventTimer = Timer()
        watchEventTimer?.schedule(object : TimerTask() {
            override fun run() {
                sendWatchEvent(channelId, livestreamId)
            }
        }, 0, 120000) // 2 minutes interval
    }
    
    private fun stopWatchEventLoop() {
        watchEventTimer?.cancel()
        watchEventTimer = null
    }
    
    private fun sendWatchEvent(channelId: String, livestreamId: String) {
        try {
            val json = JSONObject()
            json.put("type", "user_event")
            val data = JSONObject()
            val message = JSONObject()
            message.put("name", "tracking.user.watch.livestream")
            // Try to parse as Long, otherwise use String
            val cId = channelId.toLongOrNull()
            if (cId != null) message.put("channel_id", cId) else message.put("channel_id", channelId)
            
            val lsId = livestreamId.toLongOrNull()
            if (lsId != null) message.put("livestream_id", lsId) else message.put("livestream_id", livestreamId)
            
            data.put("message", message)
            json.put("data", data)
            
            webSocket?.send(json.toString())
            Log.d(TAG, "Watch event sent for livestream: $livestreamId")
        } catch (e: Exception) {
            Log.e(TAG, "Error sending watch event", e)
        }
    }
    
    private fun startPing() {
        stopPing()
        pingTimer = Timer()
        pingTimer?.schedule(object : TimerTask() {
            override fun run() {
                try {
                    val json = JSONObject()
                    json.put("type", "ping")
                    webSocket?.send(json.toString())
                } catch (e: Exception) {
                    // Ignore
                }
            }
        }, 30000, 30000) // 30 seconds interval (Standard fallback)
    }
    
    private fun stopPing() {
        pingTimer?.cancel()
        pingTimer = null
    }

    /**
     * Disconnect with intentional flag to control auto-reconnect behavior
     */
    private fun disconnect(intentional: Boolean) {
        if (intentional) {
            shouldReconnect.set(false)
            cancelReconnect()
            currentToken = null
            currentChannelId = null
            currentLivestreamId = null
        }
        
        stopPing()
        stopHandshakeLoop()
        stopWatchEventLoop()
        webSocket?.close(1000, "Disconnecting")
        webSocket = null
        isConnected = false
    }

    /**
     * Public disconnect - intentional, disables auto-reconnect
     */
    fun disconnect() {
        disconnect(intentional = true)
    }
}
