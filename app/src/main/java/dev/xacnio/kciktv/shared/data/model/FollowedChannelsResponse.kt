/**
 * File: FollowedChannelsResponse.kt
 *
 * Description: Implementation of Followed Channels Response functionality.
 *
 * Author: Xacnio
 *
 */
package dev.xacnio.kciktv.shared.data.model

import com.google.gson.annotations.SerializedName
import dev.xacnio.kciktv.shared.data.model.FollowedChannelsResponse

/**
 * Followed channels API response (api/v2/channels/followed-page)
 */
data class FollowedChannelsResponse(
    @SerializedName("nextCursor")
    val nextCursor: Int?,
    
    @SerializedName("channels")
    val channels: List<FollowedChannelItem>
)

data class FollowedChannelItem(
    @SerializedName("is_live")
    val isLive: Boolean,
    
    @SerializedName("profile_picture")
    val profilePicture: String?,
    
    @SerializedName("banner_picture")
    val bannerPicture: String?,
    
    @SerializedName("channel_slug")
    val channelSlug: String,
    
    @SerializedName("viewer_count")
    val viewerCount: Int = 0,
    
    @SerializedName("category_name")
    val categoryName: String?,
    
    @SerializedName("user_username")
    val userUsername: String,
    
    @SerializedName("session_title")
    val sessionTitle: String?
)
