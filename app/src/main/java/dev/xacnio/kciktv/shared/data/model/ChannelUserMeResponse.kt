/**
 * File: ChannelUserMeResponse.kt
 *
 * Description: Implementation of Channel User Me Response functionality.
 *
 * Author: Xacnio
 *
 */
package dev.xacnio.kciktv.shared.data.model

import com.google.gson.annotations.SerializedName
import dev.xacnio.kciktv.shared.data.model.ChannelUserMeResponse

/**
 * Response for /api/v2/channels/{slug}/me
 * Detailed relationship between current user and channel
 */
data class ChannelUserMeResponse(
    @SerializedName("subscription")
    val subscription: Any?,
    
    @SerializedName("is_super_admin")
    val isSuperAdmin: Boolean?,
    
    @SerializedName("is_following")
    val isFollowing: Boolean?,
    
    @SerializedName("following_since")
    val followingSince: String?,
    
    @SerializedName("is_broadcaster")
    val isBroadcaster: Boolean?,
    
    @SerializedName("is_moderator")
    val isModerator: Boolean?,
    
    @SerializedName("banned")
    val banned: ChannelUserMeBanned?,

    @SerializedName("celebrations")
    val celebrations: List<ChannelCelebration>?
)

data class ChannelUserMeBanned(
    @SerializedName("banned_at")
    val bannedAt: String?,
    
    @SerializedName("reason")
    val reason: String?,
    
    @SerializedName("permanent")
    val permanent: Boolean?,
    
    @SerializedName("expires_at")
    val expiresAt: String?
)

data class ChannelCelebration(
    @SerializedName("id")
    val id: String,
    
    @SerializedName("type")
    val type: String,
    
    @SerializedName("created_at")
    val createdAt: String?,
    
    @SerializedName("metadata")
    val metadata: ChannelCelebrationMetadata?
)

data class ChannelCelebrationMetadata(
    @SerializedName("total_months")
    val months: Int?
)
