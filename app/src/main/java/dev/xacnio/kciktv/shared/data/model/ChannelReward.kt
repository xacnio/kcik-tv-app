/**
 * File: ChannelReward.kt
 *
 * Description: Models for the moderator-facing reward queue feature. The shapes mirror the
 * Kick.com `api/v2/channels/{slug}/rewards` and `api/v2/channels/{slug}/redemptions` responses.
 *
 * Author: Xacnio
 *
 */
package dev.xacnio.kciktv.shared.data.model

import com.google.gson.annotations.SerializedName

/** A reward definition configured for a channel (the "categories" in the queue UI). */
data class ChannelReward(
    @SerializedName("id") val id: String,
    @SerializedName("title") val title: String,
    @SerializedName("cost") val cost: Long = 0,
    @SerializedName("description") val description: String? = null,
    @SerializedName("is_enabled") val isEnabled: Boolean = true,
    @SerializedName("is_paused") val isPaused: Boolean = false,
    @SerializedName("is_user_input_required") val isUserInputRequired: Boolean = false,
    @SerializedName("prompt") val prompt: String? = null,
    @SerializedName("background_color") val backgroundColor: String? = null,
    @SerializedName("should_redemptions_skip_request_queue") val skipsQueue: Boolean = false
)

/** GET api/v2/channels/{slug}/rewards → {"data": [...], "message": "OK"} */
data class ChannelRewardsResponse(
    @SerializedName("data") val data: List<ChannelReward>,
    @SerializedName("message") val message: String?
)

/** A single pending redemption that needs moderator action. */
data class RewardRedemption(
    @SerializedName("id") val id: String,
    @SerializedName("channel_id") val channelId: Long? = null,
    @SerializedName("reward_id") val rewardId: String,
    @SerializedName("reward_title") val rewardTitle: String? = null,
    @SerializedName("user_id") val userId: Long? = null,
    @SerializedName("username") val username: String,
    @SerializedName("username_color") val userColor: String? = null,
    @SerializedName("user_input") val userInput: String? = null,
    @SerializedName("transaction_id") val transactionId: String? = null,
    @SerializedName("status") val status: String? = null,
    @SerializedName("created_at") val createdAt: String? = null
)

/** Inner data for GET api/v2/channels/{slug}/redemptions */
data class RedemptionsListData(
    @SerializedName("redemptions") val redemptions: List<RewardRedemption>,
    @SerializedName("next_page_token") val nextPageToken: String?
)

/** GET api/v2/channels/{slug}/redemptions → {"data": {...}, "message": "OK"} */
data class RedemptionsListResponse(
    @SerializedName("data") val data: RedemptionsListData?,
    @SerializedName("message") val message: String?
)

/** Per-reward count entry inside redemption-metadata response. */
data class RedemptionCountItem(
    @SerializedName("reward_id") val rewardId: String,
    @SerializedName("count") val count: Int,
    @SerializedName("title") val title: String
)

/** Inner data for GET api/v2/channels/{slug}/redemption-metadata */
data class RedemptionMetadataData(
    @SerializedName("channel_id") val channelId: Long,
    @SerializedName("total_redemptions") val totalRedemptions: Int,
    @SerializedName("total_rewards") val totalRewards: Int,
    @SerializedName("max_rewards_limit") val maxRewardsLimit: Int? = null,
    @SerializedName("redemptions") val redemptions: List<RedemptionCountItem>
)

/** GET api/v2/channels/{slug}/redemption-metadata → {"data": {...}, "message": "OK"} */
data class RedemptionMetadataResponse(
    @SerializedName("data") val data: RedemptionMetadataData?,
    @SerializedName("message") val message: String?
)

/** POST api/v2/channels/{slug}/redemptions/accept|reject */
data class RedemptionBatchRequest(
    @SerializedName("redemption_ids") val redemptionIds: List<String>
)

/** PATCH api/v2/channels/{slug}/rewards/{rewardId} */
data class RewardPatchRequest(
    @SerializedName("is_paused") val isPaused: Boolean
)
