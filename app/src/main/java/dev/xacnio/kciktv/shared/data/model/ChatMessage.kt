/**
 * File: ChatMessage.kt
 *
 * Description: Implementation of Chat Message functionality.
 *
 * Author: Xacnio
 *
 */
package dev.xacnio.kciktv.shared.data.model

import com.google.gson.annotations.SerializedName
import dev.xacnio.kciktv.shared.data.model.ChatMessage

/**
 * Kick chat mesaj modeli
 */
data class ChatMessage(
    val id: String,
    val content: String,
    val sender: ChatSender,
    val createdAt: Long = System.currentTimeMillis(),
    val messageRef: String? = null,
    val status: MessageStatus = MessageStatus.SENT,
    val metadata: ChatMetadata? = null,
    val type: MessageType = MessageType.CHAT,
    val iconResId: Int? = null,
    val isAiModerated: Boolean = false,
    val violatedRules: List<String>? = null,
    val rewardData: RewardRedeemedEventData? = null,
    val celebrationData: CelebrationData? = null,
    val giftData: KicksGiftedEventData? = null,
    val targetUsername: String? = null, // For ban/unban messages - clickable username (target user)
    val moderatorUsername: String? = null // For ban/unban messages - clickable moderator name
)
enum class MessageType {
    CHAT,
    SYSTEM,
    INFO,
    RESTORE_BUTTON,
    REWARD,
    CELEBRATION,
    GIFT
}

data class ChatMetadata(
    val originalSender: ChatSender?,
    val originalMessageContent: String?,
    val originalMessageId: String?
)

enum class MessageStatus {
    SENDING,
    SENT,
    FAILED,
    DELETED
}

data class MessageDeletedEventData(
    @SerializedName("id") val id: String?,
    @SerializedName("message") val message: DeletedMessageInfo?,
    @SerializedName("aiModerated") val aiModerated: Boolean?,
    @SerializedName("violatedRules") val violatedRules: List<String>?
)

data class DeletedMessageInfo(
    @SerializedName("id") val id: String?
)

data class ChatErrorResponse(
    @SerializedName("status") val status: ChatStatus?
)

data class ChatStatus(
    @SerializedName("error") val error: Boolean,
    @SerializedName("code") val code: Int,
    @SerializedName("message") val message: String?
)

data class ChatSender(
    val id: Long,
    val username: String,
    val color: String?,
    val badges: List<ChatBadge>?,
    val profilePicture: String? = null
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
    val sender: PusherSender?,

    @SerializedName("metadata")
    val metadata: PusherMetadata? 
)

data class PusherMetadata(
    @SerializedName("message_ref")
    val messageRef: String?,
    @SerializedName("original_sender")
    val originalSender: PusherSender?,
    @SerializedName("original_message")
    val originalMessage: PusherOriginalMessage?,
    @SerializedName("celebration")
    val celebration: CelebrationData?
)

data class CelebrationMetadata(
    @SerializedName("celebration") val celebration: CelebrationData?
)

data class CelebrationData(
    @SerializedName("id") val id: String?,
    @SerializedName("type") val type: String?,
    @SerializedName("total_months") val totalMonths: Int?,
    @SerializedName("created_at") val createdAt: String?
)

data class PusherOriginalMessage(
    @SerializedName("id") val id: String?,
    @SerializedName("content") val content: String?
)

data class PusherSender(
    @SerializedName("id")
    val id: Long?,
    
    @SerializedName("username")
    val username: String?,
    
    @SerializedName("slug")
    val slug: String?,
    
    @SerializedName("identity")
    val identity: PusherIdentity?,
    
    @SerializedName("username_color")
    val usernameColor: String? = null,
    
    @SerializedName(value = "profile_picture", alternate = ["profile_pic"])
    val profilePicture: String? = null
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

data class ChatHistoryResponse(
    @SerializedName("data")
    val data: ChatHistoryData?,
    
    @SerializedName("messages")
    val messages: List<ChatHistoryMessage>?,
    
    @SerializedName("pinned_message")
    val pinnedMessageWrapper: PinnedMessageWrapper?,
    
    @SerializedName(value = "cursor", alternate = ["nextCursor"])
    val cursor: String?
)

data class ChatHistoryData(
    @SerializedName("cursor")
    val cursor: String?,
    
    @SerializedName("messages")
    val messages: List<ChatHistoryMessage>?,

    @SerializedName("pinned_message")
    val pinnedMessageWrapper: PinnedMessageWrapper?
)

data class ChatroomUpdatedEventData(
    @SerializedName("id") val id: Long?,
    @SerializedName("slow_mode") val slowMode: ChatModeStatus?,
    @SerializedName("subscribers_mode") val subscribersMode: ChatModeStatus?,
    @SerializedName("followers_mode") val followersMode: ChatModeStatus?,
    @SerializedName("emotes_mode") val emotesMode: ChatModeStatus?
)

data class ChatModeStatus(
    @SerializedName("enabled") val enabled: Boolean,
    @SerializedName("message_interval") val messageInterval: Int? = null,
    @SerializedName("min_duration") val minDuration: Int? = null  // For followers mode
)

data class ChatModeEventData(
    @SerializedName("id") val id: String?,
    @SerializedName("user") val user: PusherSender?,
    @SerializedName("created_at") val createdAt: String?,
    @SerializedName("slow_mode") val slowMode: ChatModeStatus? = null,
    @SerializedName("message_interval") val messageInterval: Int? = null
)

data class PinnedMessageCreatedEventData(
    @SerializedName("message") val message: PusherChatEvent?,
    @SerializedName("duration") val duration: String?,
    @SerializedName("pinnedBy") val pinnedBy: PusherSender?
)

data class PinnedMessageDeletedEventData(
    @SerializedName("id") val id: String?
)

data class PinnedMessageWrapper(
    @SerializedName("message")
    val message: ChatHistoryMessage?
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
    val identity: ChatHistoryIdentity?,

    @SerializedName(value = "profile_picture", alternate = ["profile_pic"])
    val profilePicture: String? = null
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

data class UserBannedEventData(
    @SerializedName("id") val id: String?,
    @SerializedName("user") val user: PusherSender?,
    @SerializedName("banned_by") val bannedBy: PusherSender?,
    @SerializedName("permanent") val permanent: Boolean?,
    @SerializedName("duration") val duration: Int?,
    @SerializedName("expires_at") val expiresAt: String?
)

data class UserUnbannedEventData(
    @SerializedName("id") val id: String?,
    @SerializedName("user") val user: PusherSender?,
    @SerializedName("unbanned_by") val unbannedBy: PusherSender?
)


data class GiftedSubscriptionsEventData(
    @SerializedName("chatroom_id") val chatroomId: Long?,
    @SerializedName("gifted_usernames") val giftedUsernames: List<String>?,
    @SerializedName("gifter_username") val gifterUsername: String?,
    @SerializedName("gifter_total") val gifterTotal: Int?
)

data class KicksGiftedEventData(
    @SerializedName("gift_transaction_id") val giftTransactionId: String?,
    @SerializedName("message") val message: String?,
    @SerializedName("sender") val sender: PusherSender?,
    @SerializedName("gift") val gift: GiftData?,
    @SerializedName("created_at") val createdAt: String?,
    @SerializedName("expires_at") val expiresAt: String?
)

data class GiftData(
    @SerializedName("gift_id") val giftId: String?,
    @SerializedName("name") val name: String?,
    @SerializedName("amount") val amount: Int?,
    @SerializedName("type") val type: String?,
    @SerializedName("tier") val tier: String?,
    @SerializedName("pinned_time") val pinnedTime: Long? = null
)




