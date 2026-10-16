/**
 * File: DropsModels.kt
 *
 * Description: Models for Kick Drops campaigns (web.kick.com/api/v1/drops/campaigns).
 *
 * Author: Xacnio
 *
 */
package dev.xacnio.kciktv.shared.data.model

import com.google.gson.annotations.SerializedName

data class DropCampaignsResponse(
    @SerializedName("data") val data: List<DropCampaign>?,
    @SerializedName("message") val message: String?
)

/** User progress (api/v1/drops/progress). */
data class DropProgressResponse(
    @SerializedName("data") val data: List<DropCampaignProgress>?,
    @SerializedName("message") val message: String?
)

data class DropCampaignProgress(
    @SerializedName("id") val id: String,
    // "in progress", "claimed" (every reward claimed) or "expired"
    @SerializedName("status") val status: String?,
    // Minutes watched toward this campaign
    @SerializedName("progress_units") val progressUnits: Int?,
    @SerializedName("rewards") val rewards: List<DropRewardProgress>?
)

data class DropRewardProgress(
    @SerializedName("id") val id: String,
    @SerializedName("claimed") val claimed: Boolean?,
    // 0..1
    @SerializedName("progress") val progress: Float?,
    @SerializedName("required_units") val requiredUnits: Int?
)

data class DropClaimRequest(
    @SerializedName("reward_id") val rewardId: String,
    @SerializedName("campaign_id") val campaignId: String
)

data class DropLivestreamsResponse(
    @SerializedName("data") val data: List<LiveStreamItem>?,
    @SerializedName("message") val message: String?
)

data class DropCampaign(
    @SerializedName("id") val id: String,
    @SerializedName("name") val name: String?,
    // "active" or "expired"
    @SerializedName("status") val status: String?,
    @SerializedName("starts_at") val startsAt: String?,
    @SerializedName("ends_at") val endsAt: String?,
    // Non-empty when the reward needs a linked game account (Riot, Facepunch, ...)
    @SerializedName("connect_url") val connectUrl: String?,
    // Organizer's page about the campaign
    @SerializedName("url") val url: String?,
    // How rewards are earned, e.g. {id: 1, name: "Watch to redeem"}
    @SerializedName("rule") val rule: DropRule?,
    @SerializedName("category") val category: DropCategory?,
    @SerializedName("organization") val organization: DropOrganization?,
    @SerializedName("rewards") val rewards: List<DropReward>?,
    // Empty means any channel streaming the category counts
    @SerializedName("channels") val channels: List<DropChannel>?
)

data class DropRule(
    @SerializedName("id") val id: Int?,
    @SerializedName("name") val name: String?
)

data class DropCategory(
    @SerializedName("id") val id: Long?,
    @SerializedName("name") val name: String?,
    @SerializedName("slug") val slug: String?,
    @SerializedName("image_url") val imageUrl: String?
)

data class DropOrganization(
    @SerializedName("id") val id: String?,
    @SerializedName("name") val name: String?,
    @SerializedName("logo_url") val logoUrl: String?
)

data class DropReward(
    @SerializedName("id") val id: String,
    @SerializedName("name") val name: String?,
    // Relative to the CDN, e.g. "drops/reward-image/....png"
    @SerializedName("image_url") val imageUrl: String?,
    // Minutes of watch time needed
    @SerializedName("required_units") val requiredUnits: Int?
)

data class DropChannel(
    @SerializedName("id") val id: Long?,
    @SerializedName("slug") val slug: String?,
    @SerializedName("user") val user: DropChannelUser?
)

data class DropChannelUser(
    @SerializedName("id") val id: Long?,
    @SerializedName("username") val username: String?,
    @SerializedName("profile_picture") val profilePicture: String?
)
