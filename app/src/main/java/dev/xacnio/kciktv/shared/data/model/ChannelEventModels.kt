package dev.xacnio.kciktv.shared.data.model

import com.google.gson.annotations.SerializedName

data class ChannelEventsResponse(
    @SerializedName("status") val status: ChannelEventsStatus?,
    @SerializedName("data") val data: ChannelEventsData?
)

data class ChannelEventsStatus(
    @SerializedName("code") val code: Int?,
    @SerializedName("error") val error: Boolean?,
    @SerializedName("message") val message: String?
)

data class ChannelEventsData(
    @SerializedName("events") val events: List<ChannelEvent>?
)

data class ChannelEvent(
    @SerializedName("channel_id") val channelId: Long?,
    @SerializedName("actor_id") val actorId: Long?,
    @SerializedName("event_type") val eventType: String?,
    @SerializedName("event_data") val eventData: ChannelEventData?,
    @SerializedName("created_at") val createdAt: String?
)

data class ChannelEventData(
    @SerializedName("id") val id: String?,
    @SerializedName("user") val user: ChannelEventUser?,
    @SerializedName("channel") val channel: ChannelEventChannel?,
    @SerializedName("subscription") val subscription: ChannelEventSubscription?,
    @SerializedName("followers_count") val followersCount: Long?,
    @SerializedName("reward") val reward: ChannelEventReward?,
    @SerializedName("created_at") val createdAt: String?
)

data class ChannelEventUser(
    @SerializedName("id") val id: Long?,
    @SerializedName("slug") val slug: String?,
    @SerializedName("username") val username: String?
)

data class ChannelEventChannel(
    @SerializedName("id") val id: Long?,
    @SerializedName("slug") val slug: String?
)

data class ChannelEventSubscription(
    @SerializedName("tier") val tier: Int?,
    @SerializedName("interval") val interval: Int?,
    @SerializedName("total") val total: Int?
)

data class ChannelEventReward(
    @SerializedName("id") val id: String?,
    @SerializedName("reward_title") val rewardTitle: String?,
    @SerializedName("user_input") val userInput: String?,
    @SerializedName("should_redemptions_skip_request_queue") val skipQueue: Boolean?
)
