/**
 * File: ChannelUserResponse.kt
 *
 * Description: Implementation of Channel User Response functionality.
 *
 * Author: Xacnio
 *
 */
package dev.xacnio.kciktv.shared.data.model

import com.google.gson.annotations.SerializedName
import dev.xacnio.kciktv.shared.data.model.ChannelUserResponse

/**
 * Channel user info response (api/v2/channels/{channel}/users/{user})
 * Used to check if user is subscribed to a channel
 */
data class ChannelUserResponse(
    @SerializedName("id")
    val id: Long?,
    
    @SerializedName("username")
    val username: String?,
    
    @SerializedName("slug")
    val slug: String?,
    
    @SerializedName("profile_pic")
    val profilePic: String?,
    
    @SerializedName("is_staff")
    val isStaff: Boolean?,
    
    @SerializedName("is_channel_owner")
    val isChannelOwner: Boolean?,
    
    @SerializedName("is_moderator")
    val isModerator: Boolean?,
    
    @SerializedName("badges")
    val badges: List<ChannelUserBadge>?,
    
    @SerializedName("following_since")
    val followingSince: String?,
    
    @SerializedName("subscribed_for")
    val subscribedFor: Int?,
    
    @SerializedName("banned")
    val banned: BannedInfo?
) {
    /**
     * Check if user is subscribed to this channel
     */
    val isSubscribed: Boolean
        get() = (subscribedFor ?: 0) > 0 || 
                badges?.any { it.type == "subscriber"} == true ||
                isChannelOwner == true || 
                isStaff == true
    
    /**
     * Check if user is founder
     */
    val isFounder: Boolean
        get() = badges?.any { it.type == "founder" } == true
    
    /**
     * Check if user is currently banned
     */
    val isBanned: Boolean
        get() = banned != null
    
    /**
     * Check if ban is permanent
     */
    val isPermanentBan: Boolean
        get() = banned?.permanent == true
    
    /**
     * Get ban expiration time as ISO string (for timeout)
     */
    val banExpiresAt: String?
        get() = banned?.expiresAt
}

data class BannedInfo(
    @SerializedName("id")
    val id: String?,
    
    @SerializedName("permanent")
    val permanent: Boolean?,
    
    @SerializedName("reason")
    val reason: String?,
    
    @SerializedName("type")
    val type: String?,
    
    @SerializedName("banner_id")
    val bannerId: Long?,
    
    @SerializedName("expires_at")
    val expiresAt: String?,
    
    @SerializedName("created_at")
    val createdAt: String?,
    
    @SerializedName("banned_by") // Some endpoints might still use this
    val bannedBy: BannedByInfo?,
    
    @SerializedName("banner") // Some endpoints use this for expanded info
    val banner: BannedByInfo?
)

data class BannedByInfo(
    @SerializedName("id")
    val id: Long?,
    
    @SerializedName("username")
    val username: String?,
    
    @SerializedName("slug")
    val slug: String?
)

data class ChannelUserBadge(
    @SerializedName("type")
    val type: String?,
    
    @SerializedName("text")
    val text: String?,
    
    @SerializedName("count")
    val count: Int?,
    
    @SerializedName("active")
    val active: Boolean?
)
