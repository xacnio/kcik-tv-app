package dev.xacnio.kciktv.data.model

import com.google.gson.annotations.SerializedName

/**
 * Kick chat mesaj modeli
 */
data class ChatMessage(
    val id: String,
    val content: String,
    val sender: ChatSender,
    val createdAt: Long = System.currentTimeMillis()
)

data class ChatSender(
    val id: Long,
    val username: String,
    val color: String?,
    val badges: List<ChatBadge>?
)

data class ChatBadge(
    val type: String,
    val text: String?,
    val count: Int? = null
)

/**
 * Pusher WebSocket chat event data
 */
data class PusherChatEvent(
    @SerializedName("id")
    val id: String?,
    
    @SerializedName("chatroom_id")
    val chatroomId: Long?,
    
    @SerializedName("content")
    val content: String?,
    
    @SerializedName("type")
    val type: String?,
    
    @SerializedName("created_at")
    val createdAt: String?,
    
    @SerializedName("sender")
    val sender: PusherSender?
)

data class PusherSender(
    @SerializedName("id")
    val id: Long?,
    
    @SerializedName("username")
    val username: String?,
    
    @SerializedName("slug")
    val slug: String?,
    
    @SerializedName("identity")
    val identity: PusherIdentity?
)

data class PusherIdentity(
    @SerializedName("color")
    val color: String?,
    
    @SerializedName("badges")
    val badges: List<PusherBadge>?
)

data class PusherBadge(
    @SerializedName("type")
    val type: String?,
    
    @SerializedName("text")
    val text: String?,
    
    @SerializedName("count")
    val count: Int?
)

/**
 * Pusher wrapper event
 */
data class PusherEvent(
    @SerializedName("event")
    val event: String?,
    
    @SerializedName("data")
    val data: String?, // JSON string, needs secondary parsing
    
    @SerializedName("channel")
    val channel: String?
)

/**
 * Chat history API response modeli
 */
data class ChatHistoryResponse(
    @SerializedName("data")
    val data: ChatHistoryData?
)

data class ChatHistoryData(
    @SerializedName("cursor")
    val cursor: String?,
    
    @SerializedName("messages")
    val messages: List<ChatHistoryMessage>?
)

data class ChatHistoryMessage(
    @SerializedName("id")
    val id: String?,
    
    @SerializedName("chat_id")
    val chatId: Long?,
    
    @SerializedName("user_id")
    val userId: Long?,
    
    @SerializedName("content")
    val content: String?,
    
    @SerializedName("type")
    val type: String?,
    
    @SerializedName("metadata")
    val metadata: String?,
    
    @SerializedName("created_at")
    val createdAt: String?,
    
    @SerializedName("sender")
    val sender: ChatHistorySender?
)

data class ChatHistorySender(
    @SerializedName("id")
    val id: Long?,
    
    @SerializedName("slug")
    val slug: String?,
    
    @SerializedName("username")
    val username: String?,
    
    @SerializedName("identity")
    val identity: ChatHistoryIdentity?
)

data class ChatHistoryIdentity(
    @SerializedName("color")
    val color: String?,
    
    @SerializedName("badges")
    val badges: List<ChatHistoryBadge>?
)

data class ChatHistoryBadge(
    @SerializedName("type")
    val type: String?,
    
    @SerializedName("text")
    val text: String?,
    
    @SerializedName("count")
    val count: Int?
)

