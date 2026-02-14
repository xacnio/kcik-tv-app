/**
 * File: ChannelDetailResponse.kt
 *
 * Description: Implementation of Channel Detail Response functionality.
 *
 * Author: Xacnio
 *
 */
package dev.xacnio.kciktv.shared.data.model

import com.google.gson.annotations.SerializedName
import dev.xacnio.kciktv.shared.data.model.ChannelDetailResponse

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

    @SerializedName(value = "following", alternate = ["is_following"])
    val following: Boolean?,
    
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
    val offlineBannerImage: ImageInfo?,

    @SerializedName("recent_categories")
    val recentCategories: List<CategoryInfo>?,

    @SerializedName("previous_livestreams")
    val previousLivestreams: List<PreviousLivestream>?
) {
    /**
     * Returns the profile picture URL or a consistent default one if null
     */
    fun getEffectiveProfilePicUrl(): String {
        val pfp = user?.profilePic
        if (!pfp.isNullOrEmpty()) return pfp
        // Use a consistent default avatar based on username string hash (1-6)
        val username = user?.username ?: slug ?: ""
        val hash = username.hashCode()
        val index = (if (hash < 0) -hash else hash) % 6 + 1
        return "https://kick.com/img/default-profile-pictures/default-avatar-$index.webp"
    }

    /**
     * Returns the banner image URL or a default Kick banner if null
     */
    fun getEffectiveBannerUrl(): String {
        val banner = bannerImage?.url ?: offlineBannerImage?.src
        if (!banner.isNullOrEmpty()) return banner
        return "https://kick.com/img/default-channel-banners/new-default-banner-1.webp"
    }
}

data class PreviousLivestream(
    @SerializedName("id") val id: Long?,
    @SerializedName("slug") val slug: String?,
    @SerializedName("session_title") val sessionTitle: String?,
    @SerializedName("created_at") val createdAt: String?,
    @SerializedName("duration") val duration: Int?,
    @SerializedName("thumbnail") val thumbnail: ImageInfo?,
    @SerializedName("video") val video: VideoInfo?
)

data class VideoInfo(
    @SerializedName("id") val id: Long?,
    @SerializedName("live_stream_id") val liveStreamId: Long?,
    @SerializedName("slug") val slug: String?,
    @SerializedName("uuid") val uuid: String?,
    @SerializedName("created_at") val createdAt: String?
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
    val emotesMode: Boolean?,

    @SerializedName(value = "message_interval")
    val slowModeInterval: Int?,

    @SerializedName("following_min_duration")
    val followersMinDuration: Int?,

    @SerializedName("pinned_message")
    val pinnedMessage: ChatHistoryMessage?
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
    
    @SerializedName(value = "profile_pic", alternate = ["profile_picture", "profilepic"])
    val profilePic: String?,

    @SerializedName("instagram")
    val instagram: String?,

    @SerializedName("twitter")
    val twitter: String?,

    @SerializedName("youtube")
    val youtube: String?,

    @SerializedName("discord")
    val discord: String?,

    @SerializedName("tiktok")
    val tiktok: String?,

    @SerializedName("facebook")
    val facebook: String?
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
    val createdAt: String?,

    @SerializedName("start_time")
    val startTime: String?,

    @SerializedName("tags")
    val tags: List<String>?,

    @SerializedName("lang_iso")
    val langIso: String?,

    @SerializedName("language")
    val language: String?,

    @SerializedName(value = "is_mature", alternate = ["mature"])
    val isMature: Boolean?
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
    val createdAt: String?,

    @SerializedName("thumbnail")
    val thumbnail: ImageInfo?,

    @SerializedName("language")
    val language: String?,

    @SerializedName("is_mature")
    val isMature: Boolean?,

    @SerializedName("tags")
    val tags: List<String>?,

    @SerializedName("categories")
    val categories: List<CategoryInfo>?
)

data class LivestreamResponseWrapper(
    @SerializedName("data")
    val data: LivestreamResponse?
)
/**
 * Stream info API response (kick.com/api/v2/channels/{slug}/stream-info)
 */
data class StreamInfoResponse(
    @SerializedName("stream_title")
    val streamTitle: String?,
    
    @SerializedName("is_mature")
    val isMature: Boolean?,
    
    @SerializedName("language")
    val language: String?,
    
    @SerializedName("category")
    val category: CategoryInfo?,
    
    @SerializedName("tags")
    val tags: List<String>?
)
/**
 * Category Search API response (search.kick.com)
 */
data class CategorySearchResponse(
    @SerializedName("hits")
    val hits: List<CategorySearchHit>?
)

data class CategorySearchHit(
    @SerializedName("document")
    val document: CategorySearchDocument?
)

data class CategorySearchDocument(
    @SerializedName("category_id")
    val categoryId: Long?,
    @SerializedName("id")
    val id: String?,
    @SerializedName("name")
    val name: String?,
    @SerializedName("slug")
    val slug: String?,
    @SerializedName("src")
    val src: String?
)
