/**
 * File: ChatIdentityResponse.kt
 *
 * Description: Implementation of Chat Identity Response functionality.
 *
 * Author: Xacnio
 *
 */
package dev.xacnio.kciktv.shared.data.model

import com.google.gson.annotations.SerializedName
import dev.xacnio.kciktv.shared.data.model.ChatIdentityResponse

/**
 * Response for chat identity API
 * https://kick.com/api/v2/channels/{channel_id}/users/{user_id}/identity
 */
data class ChatIdentityResponse(
    @SerializedName("status") val status: ChatIdentityStatus?,
    @SerializedName("data") val data: ChatIdentityData?
)

data class ChatIdentityStatus(
    @SerializedName("error") val error: Boolean,
    @SerializedName("code") val code: Int,
    @SerializedName("message") val message: String?
)

data class ChatIdentityData(
    @SerializedName("identity") val identity: UserChatIdentity?
)

data class UserChatIdentity(
    @SerializedName("badges") val badges: List<ChatIdentityBadge>?,
    @SerializedName("badges_v2") val badgesV2: List<ChatIdentityBadgeV2>?,
    @SerializedName("color") val color: String?
)

data class ChatIdentityBadge(
    @SerializedName("type") val type: String?,
    @SerializedName("text") val text: String?,
    @SerializedName("count") val count: Int? = null,
    @SerializedName("active") val active: Boolean? = null,
    @SerializedName("sort_order") val sortOrder: Int? = null
)

data class ChatIdentityBadgeV2(
    @SerializedName("name") val name: String?,
    @SerializedName("badge_type") val badgeType: String?,
    @SerializedName("image_url") val imageUrl: String?,
    @SerializedName("metadata") val metadata: BadgeV2Metadata?,
    @SerializedName("selected") val selected: Boolean?,
    @SerializedName("sort_order") val sortOrder: Int?
)

data class BadgeV2Metadata(
    @SerializedName("level") val level: Int?
)
