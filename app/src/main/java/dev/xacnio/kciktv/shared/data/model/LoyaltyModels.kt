/**
 * File: LoyaltyModels.kt
 *
 * Description: Implementation of Loyalty Models functionality.
 *
 * Author: Xacnio
 *
 */
package dev.xacnio.kciktv.shared.data.model

import com.google.gson.annotations.SerializedName

data class LoyaltyRewardsResponse(
    @SerializedName("data") val data: List<LoyaltyRewardItem>,
    @SerializedName("message") val message: String?
)

data class LoyaltyRewardItem(
    @SerializedName("id") val id: String,
    @SerializedName("title") val title: String,
    @SerializedName("cost") val cost: Int,
    @SerializedName("description") val description: String?,
    @SerializedName("background_color") val backgroundColor: String?,
    @SerializedName("is_user_input_required") val isUserInputRequired: Boolean
)

data class RedeemRewardResponse(
    @SerializedName("data") val data: RedemptionData?,
    @SerializedName("message") val message: String?
)

data class RedemptionData(
    @SerializedName("id") val id: String,
    @SerializedName("reward_id") val rewardId: String,
    @SerializedName("reward_title") val rewardTitle: String
)

data class ChannelPointsResponse(
    @SerializedName("data") val data: PointsData,
    @SerializedName("message") val message: String?
)

data class PointsData(
    @SerializedName("points") val points: Int
)

data class RewardRedeemedEventData(
    @SerializedName("reward_title") val rewardTitle: String?,
    @SerializedName("user_id") val userId: Long?,
    @SerializedName("channel_id") val channelId: Long?,
    @SerializedName("username") val username: String?,
    @SerializedName("user_input") val userInput: String?,
    @SerializedName("reward_background_color") val rewardBackgroundColor: String?,
    @SerializedName("reward_cost") val rewardCost: Int?
)

data class PointsUpdatedEventData(
    @SerializedName("reason") val reason: String,
    @SerializedName("points") val points: Int,
    @SerializedName("balance") val balance: Int,
    @SerializedName("user_id") val userId: Long,
    @SerializedName("channel_id") val channelId: Long
)
