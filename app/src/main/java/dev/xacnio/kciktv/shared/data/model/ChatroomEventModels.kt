package dev.xacnio.kciktv.shared.data.model

import com.google.gson.annotations.SerializedName

data class ChatroomEventsResponse(
    @SerializedName("status") val status: ChatroomEventsStatus?,
    @SerializedName("data") val data: ChatroomEventsData?
)

data class ChatroomEventsStatus(
    @SerializedName("error") val error: Boolean?,
    @SerializedName("message") val message: String?,
    @SerializedName("code") val code: Int?
)

data class ChatroomEventsData(
    @SerializedName("events") val events: List<ChatroomEvent>?
)

data class ChatroomEvent(
    @SerializedName("chat_id") val chatId: Long?,
    @SerializedName("actor_id") val actorId: Long?,
    @SerializedName("event_type") val eventType: String?,
    @SerializedName("event_data") val eventData: ChatroomEventData?,
    @SerializedName("created_at") val createdAt: String?
)

data class ChatroomEventData(
    @SerializedName("id") val id: String?,
    @SerializedName("user") val user: ChatroomEventUser?,
    @SerializedName("banned") val banned: ChatroomEventUser?,
    @SerializedName("ban") val ban: ChatroomEventBan?,
    @SerializedName("pinned_message") val pinnedMessage: ChatroomEventPinnedMessage?,
    @SerializedName("category") val category: ChatroomEventCategory?,
    @SerializedName("created_at") val createdAt: String?
)

data class ChatroomEventCategory(
    @SerializedName("id") val id: Long?,
    @SerializedName("name") val name: String?,
    @SerializedName("slug") val slug: String?
)

data class ChatroomEventUser(
    @SerializedName("id") val id: Long?,
    @SerializedName("slug") val slug: String?,
    @SerializedName("username") val username: String?
)

data class ChatroomEventBan(
    @SerializedName("reason") val reason: String?,
    @SerializedName("type") val type: String?,
    @SerializedName("expires_at") val expiresAt: String?,
    @SerializedName("created_at") val createdAt: String?,
    @SerializedName("duration") val duration: Int?
)

data class ChatroomEventPinnedMessage(
    @SerializedName("id") val id: String?,
    @SerializedName("content") val content: String?,
    @SerializedName("sender") val sender: ChatroomEventUser?
)
