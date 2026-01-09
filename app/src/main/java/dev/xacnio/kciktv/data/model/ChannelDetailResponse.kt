package dev.xacnio.kciktv.data.model

import com.google.gson.annotations.SerializedName

/**
 * Channel details API response (kick.com/api/v2/channels/{slug})
 */
data class ChannelDetailResponse(
    @SerializedName("id")
    val id: Long?,
    
    @SerializedName("user_id")
    val userId: Long?,
    
    @SerializedName("slug")
    val slug: String?,
    
    @SerializedName("playback_url")
    val playbackUrl: String?,
    
    @SerializedName("vod_enabled")
    val vodEnabled: Boolean?,
    
    @SerializedName("subscription_enabled")
    val subscriptionEnabled: Boolean?,
    
    @SerializedName("followers_count")
    val followersCount: Int?,
    
    @SerializedName("banner_image")
    val bannerImage: ImageInfo?,
    
    @SerializedName("verified")
    val verified: Boolean?,
    
    @SerializedName("user")
    val user: UserDetail?,
    
    @SerializedName("livestream")
    val livestream: LivestreamDetail?,
    
    @SerializedName("chatroom")
    val chatroom: ChatroomInfo?,
    
    @SerializedName("subscriber_badges")
    val subscriberBadges: List<SubscriberBadge>?,

    @SerializedName("offline_banner_image")
    val offlineBannerImage: ImageInfo?
)

data class SubscriberBadge(
    @SerializedName("id")
    val id: Long?,
    
    @SerializedName("channel_id")
    val channelId: Long?,
    
    @SerializedName("months")
    val months: Int?,
    
    @SerializedName("badge_image")
    val badgeImage: BadgeImage?
)

data class BadgeImage(
    @SerializedName("srcset")
    val srcset: String?,
    
    @SerializedName("src")
    val src: String?
)

data class ChatroomInfo(
    @SerializedName("id")
    val id: Long?,
    
    @SerializedName("chatable_type")
    val chatableType: String?,
    
    @SerializedName("slow_mode")
    val slowMode: Boolean?,
    
    @SerializedName("followers_mode")
    val followersMode: Boolean?,
    
    @SerializedName("subscribers_mode")
    val subscribersMode: Boolean?,
    
    @SerializedName("emotes_mode")
    val emotesMode: Boolean?
)

data class ImageInfo(
    @SerializedName("url")
    val url: String?,
    
    @SerializedName("src")
    val src: String?,

    @SerializedName("srcset")
    val srcset: String?
)

data class UserDetail(
    @SerializedName("id")
    val id: Long?,
    
    @SerializedName("username")
    val username: String?,
    
    @SerializedName("bio")
    val bio: String?,
    
    @SerializedName("profile_pic")
    val profilePic: String?
)

data class LivestreamDetail(
    @SerializedName("id")
    val id: Long?,
    
    @SerializedName("slug")
    val slug: String?,
    
    @SerializedName("session_title")
    val sessionTitle: String?,
    
    @SerializedName("viewer_count")
    val viewerCount: Int?,
    
    @SerializedName("is_live")
    val isLive: Boolean?,
    
    @SerializedName("thumbnail")
    val thumbnail: ImageInfo?,
    
    @SerializedName("categories")
    val categories: List<CategoryInfo>?,

    @SerializedName("created_at")
    val createdAt: String?
)

/**
 * Livestream API response (kick.com/api/v2/channels/{slug}/livestream)
 */
data class LivestreamResponse(
    @SerializedName("id")
    val id: Long?,
    
    @SerializedName("slug")
    val slug: String?,
    
    @SerializedName("session_title")
    val sessionTitle: String?,
    
    @SerializedName("playback_url")
    val playbackUrl: String?,
    
    @SerializedName(value = "viewers", alternate = ["viewer_count"])
    val viewers: Int?,
    
    @SerializedName("is_live")
    val isLive: Boolean?,

    @SerializedName("created_at")
    val createdAt: String?
)
