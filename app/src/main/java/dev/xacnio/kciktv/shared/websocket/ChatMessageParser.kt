/**
 * File: ChatMessageParser.kt
 *
 * Description: Implementation of Chat Message Parser functionality.
 *
 * Author: Xacnio
 *
 */
package dev.xacnio.kciktv.shared.websocket

import android.util.Log
import org.json.JSONArray
import org.json.JSONObject

/**
 * Helper class for parsing Pusher/Kick chat WebSocket messages.
 * Handles subscription, event parsing, and message extraction.
 */
object ChatMessageParser {
    private const val TAG = "ChatMessageParser"
    
    /**
     * Parsed chat event
     */
    sealed class ChatEvent {
        data class ConnectionEstablished(val socketId: String) : ChatEvent()
        data class SubscriptionSucceeded(val channel: String) : ChatEvent()
        data class NewMessage(val messageJson: JSONObject) : ChatEvent()
        data class MessageDeleted(val messageId: String) : ChatEvent()
        data class UserBanned(val userId: Int, val username: String, val permanent: Boolean) : ChatEvent()
        data class UserUnbanned(val userId: Int, val username: String) : ChatEvent()
        data class PollUpdate(val pollJson: JSONObject) : ChatEvent()
        data class PredictionUpdate(val predictionJson: JSONObject) : ChatEvent()
        data class PinnedMessageUpdate(val messageJson: JSONObject?) : ChatEvent()
        data class ChatroomUpdated(val chatroomJson: JSONObject) : ChatEvent()
        data class GiftedSubscription(val giftJson: JSONObject) : ChatEvent()
        data class SubscriberOnly(val enabled: Boolean) : ChatEvent()
        data class SlowMode(val enabled: Boolean, val interval: Int) : ChatEvent()
        data class FollowersMode(val enabled: Boolean, val minDuration: Int) : ChatEvent()
        data class EmotesMode(val enabled: Boolean) : ChatEvent()
        data class StreamHosted(val hostJson: JSONObject) : ChatEvent()
        data class Ping(val data: String? = null) : ChatEvent()
        object Pong : ChatEvent()
        data class Error(val message: String, val code: Int?) : ChatEvent()
        data class Unknown(val eventType: String, val data: String?) : ChatEvent()
    }
    
    /**
     * Parse incoming WebSocket message
     */
    fun parse(message: String): ChatEvent? {
        return try {
            val json = JSONObject(message)
            val event = json.optString("event")
            val data = json.optString("data")
            val channel = json.optString("channel")
            
            when (event) {
                "pusher:connection_established" -> {
                    val dataJson = JSONObject(data)
                    ChatEvent.ConnectionEstablished(dataJson.optString("socket_id"))
                }
                
                "pusher_internal:subscription_succeeded" -> {
                    ChatEvent.SubscriptionSucceeded(channel)
                }
                
                "pusher:ping" -> ChatEvent.Ping(data)
                "pusher:pong" -> ChatEvent.Pong
                
                "pusher:error" -> {
                    val dataJson = if (data.isNotEmpty()) JSONObject(data) else null
                    ChatEvent.Error(
                        dataJson?.optString("message") ?: "Unknown error",
                        dataJson?.optInt("code")
                    )
                }
                
                "App\\Events\\ChatMessageEvent" -> {
                    val dataJson = JSONObject(data)
                    ChatEvent.NewMessage(dataJson)
                }
                
                "App\\Events\\MessageDeletedEvent" -> {
                    val dataJson = JSONObject(data)
                    val messageId = dataJson.optJSONObject("message")?.optString("id")
                        ?: dataJson.optString("id")
                    ChatEvent.MessageDeleted(messageId)
                }
                
                "App\\Events\\UserBannedEvent" -> {
                    val dataJson = JSONObject(data)
                    val user = dataJson.optJSONObject("user")
                    ChatEvent.UserBanned(
                        userId = user?.optInt("id") ?: 0,
                        username = user?.optString("username") ?: "",
                        permanent = dataJson.optBoolean("permanent", true)
                    )
                }
                
                "App\\Events\\UserUnbannedEvent" -> {
                    val dataJson = JSONObject(data)
                    val user = dataJson.optJSONObject("user")
                    ChatEvent.UserUnbanned(
                        userId = user?.optInt("id") ?: 0,
                        username = user?.optString("username") ?: ""
                    )
                }
                
                "App\\Events\\PollUpdateEvent" -> {
                    val dataJson = JSONObject(data)
                    ChatEvent.PollUpdate(dataJson.optJSONObject("poll") ?: dataJson)
                }
                
                "App\\Events\\PollDeleteEvent" -> {
                    ChatEvent.PollUpdate(JSONObject()) // Empty poll means deleted
                }
                
                "App\\Events\\PredictionEvent" -> {
                    val dataJson = JSONObject(data)
                    ChatEvent.PredictionUpdate(dataJson.optJSONObject("leaderboard") ?: dataJson)
                }
                
                "App\\Events\\ChatroomUpdatedEvent" -> {
                    val dataJson = JSONObject(data)
                    ChatEvent.ChatroomUpdated(dataJson)
                }
                
                "App\\Events\\PinnedMessageCreatedEvent" -> {
                    val dataJson = JSONObject(data)
                    ChatEvent.PinnedMessageUpdate(dataJson.optJSONObject("message"))
                }
                
                "App\\Events\\PinnedMessageDeletedEvent" -> {
                    ChatEvent.PinnedMessageUpdate(null)
                }
                
                "App\\Events\\GiftedSubscriptionsEvent",
                "App\\Events\\GiftsLeaderboardUpdated" -> {
                    val dataJson = JSONObject(data)
                    ChatEvent.GiftedSubscription(dataJson)
                }
                
                "App\\Events\\SubscribersMode" -> {
                    val dataJson = JSONObject(data)
                    ChatEvent.SubscriberOnly(dataJson.optBoolean("enabled"))
                }
                
                "App\\Events\\SlowModeEvent" -> {
                    val dataJson = JSONObject(data)
                    ChatEvent.SlowMode(
                        enabled = dataJson.optBoolean("enabled"),
                        interval = dataJson.optInt("interval", 5)
                    )
                }
                
                "App\\Events\\FollowersModeEvent" -> {
                    val dataJson = JSONObject(data)
                    ChatEvent.FollowersMode(
                        enabled = dataJson.optBoolean("enabled"),
                        minDuration = dataJson.optInt("min_duration", 0)
                    )
                }
                
                "App\\Events\\EmotesModeEvent" -> {
                    val dataJson = JSONObject(data)
                    ChatEvent.EmotesMode(dataJson.optBoolean("enabled"))
                }
                
                "App\\Events\\StreamHostEvent" -> {
                    val dataJson = JSONObject(data)
                    ChatEvent.StreamHosted(dataJson)
                }
                
                else -> {
                    Log.d(TAG, "Unknown event type: $event")
                    ChatEvent.Unknown(event, data)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to parse message: $message", e)
            null
        }
    }
    
    /**
     * Build subscribe message for a channel
     */
    fun buildSubscribeMessage(channelName: String): String {
        return JSONObject().apply {
            put("event", "pusher:subscribe")
            put("data", JSONObject().apply {
                put("channel", channelName)
            })
        }.toString()
    }
    
    /**
     * Build unsubscribe message for a channel
     */
    fun buildUnsubscribeMessage(channelName: String): String {
        return JSONObject().apply {
            put("event", "pusher:unsubscribe")
            put("data", JSONObject().apply {
                put("channel", channelName)
            })
        }.toString()
    }
    
    /**
     * Build pong response
     */
    fun buildPongMessage(): String {
        return JSONObject().apply {
            put("event", "pusher:pong")
            put("data", JSONObject())
        }.toString()
    }
    
    /**
     * Build chat message for sending
     */
    fun buildChatMessage(
        content: String,
        @Suppress("UNUSED_PARAMETER") chatroomId: Int,
        replyToMessageId: String? = null
    ): JSONObject {
        return JSONObject().apply {
            put("content", content)
            put("type", "message")
            if (replyToMessageId != null) {
                put("metadata", JSONObject().apply {
                    put("original_message", JSONObject().apply {
                        put("id", replyToMessageId)
                    })
                })
            }
        }
    }
}
