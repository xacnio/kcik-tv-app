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
    @SerializedName("color") val color: String?
)

data class ChatIdentityBadge(
    @SerializedName("type") val type: String?,
    @SerializedName("text") val text: String?,
    @SerializedName("count") val count: Int? = null,
    @SerializedName("active") val active: Boolean? = null
)
