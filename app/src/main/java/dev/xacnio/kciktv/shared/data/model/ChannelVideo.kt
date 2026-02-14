/**
 * File: ChannelVideo.kt
 *
 * Description: Implementation of Channel Video functionality.
 *
 * Author: Xacnio
 *
 */
package dev.xacnio.kciktv.shared.data.model

import com.google.gson.annotations.SerializedName
import dev.xacnio.kciktv.shared.data.model.ChannelVideo
import dev.xacnio.kciktv.shared.data.model.TopCategory

/**
 * Channel video/VOD model matching Kick API structure
 */
data class ChannelVideo(
    @SerializedName("id")
    internal val topId: Long?,
    
    @SerializedName("live_stream_id")
    internal val topLiveStreamId: Long?,
    
    @SerializedName("uuid")
    val uuid: String?,
    
    @SerializedName("created_at")
    val createdAt: String?,
    
    @SerializedName("updated_at")
    val updatedAt: String?,
    
    @SerializedName("session_title")
    private val topSessionTitle: String? = null,
    
    @SerializedName("duration")
    private val topDuration: Long? = null,
    
    @SerializedName("viewer_count")
    private val topViewerCount: Int? = null,
    
    @SerializedName("views")
    val views: Int?,
    
    @SerializedName("language")
    private val topLanguage: String? = null,
    
    @SerializedName("is_mature")
    private val topIsMature: Boolean? = null,
    
    @SerializedName("categories")
    private val topCategories: List<TopCategory>? = null,

    @SerializedName("thumbnail")
    private val topThumbnail: VideoThumbnail? = null,

    @SerializedName("thumb")
    private val topThumb: String? = null,

    @SerializedName("is_live")
    private val topIsLive: Boolean? = null,
    
    @SerializedName("source")
    val source: String?,

    @SerializedName("livestream")
    val livestream: ChannelVideoLivestream?
) {
    // Correct IDs mapping
    val id: Long? get() = topId ?: livestream?.id
    val liveStreamId: Long? get() = topLiveStreamId ?: livestream?.id

    // Helper fields for backward compatibility with UI code (Adapter & Manager)
    val sessionTitle: String? get() = livestream?.sessionTitle ?: topSessionTitle
    val duration: Long? get() = livestream?.duration ?: topDuration
    val isLive: Boolean? get() = livestream?.isLive ?: topIsLive ?: false
    val viewerCount: Int? get() = livestream?.viewerCount ?: topViewerCount
    val language: String? get() = livestream?.language ?: topLanguage
    val isMature: Boolean? get() = livestream?.isMature ?: topIsMature
    val categories: List<TopCategory>? get() = livestream?.categories ?: topCategories
    
    val thumbnail: VideoThumbnail? get() {
        val url = livestream?.thumbnailUrl
        if (!url.isNullOrEmpty()) return VideoThumbnail(url, null)
        if (topThumbnail != null) return topThumbnail
        if (!topThumb.isNullOrEmpty()) return VideoThumbnail(topThumb, null)
        return null
    }
    
    // Video detail property used in some internal logic
    val video: ChannelVideoDetail? get() = ChannelVideoDetail(id, liveStreamId, uuid, createdAt)

    /**
     * Converts nested livestream channel data to ClipChannel for reuse
     */
    val channel: ClipChannel? get() {
        val vChannel = livestream?.channel
        return if (vChannel != null) {
            ClipChannel(
                id = vChannel.id,
                username = vChannel.user?.username,
                slug = vChannel.slug,
                profilePicture = vChannel.user?.profilePic
            )
        } else null
    }
}

data class ChannelVideoLivestream(
    @SerializedName("id") val id: Long?,
    @SerializedName("slug") val slug: String?,
    @SerializedName("session_title") val sessionTitle: String?,
    @SerializedName("created_at") val createdAt: String?,
    @SerializedName("is_live") val isLive: Boolean?,
    @SerializedName("start_time") val startTime: String?,
    @SerializedName("duration") val duration: Long?,
    @SerializedName("language") val language: String?,
    @SerializedName("is_mature") val isMature: Boolean?,
    @SerializedName("viewer_count") val viewerCount: Int?,
    @SerializedName("thumbnail") val thumbnailUrl: String?,
    @SerializedName("channel") val channel: ChannelVideoChannel?,
    @SerializedName("categories") val categories: List<TopCategory>?
)

data class ChannelVideoChannel(
    @SerializedName("id") val id: Long?,
    @SerializedName("user_id") val userId: Long?,
    @SerializedName("slug") val slug: String?,
    @SerializedName("playback_url") val playbackUrl: String?,
    @SerializedName("vod_enabled") val vodEnabled: Boolean?,
    @SerializedName("subscription_enabled") val subscriptionEnabled: Boolean?,
    @SerializedName("followersCount") val followersCount: Int?,
    @SerializedName("user") val user: ChannelVideoUser?
)

data class ChannelVideoUser(
    @SerializedName("username") val username: String?,
    @SerializedName("profilepic") val profilePic: String?,
    @SerializedName("bio") val bio: String?
)

data class VideoThumbnail(
    @SerializedName("src")
    val src: String?,
    @SerializedName("srcset")
    val srcset: String?
)

data class ChannelVideoDetail(
    @SerializedName("id")
    val id: Long?,
    @SerializedName("live_stream_id")
    val liveStreamId: Long?,
    @SerializedName("uuid") val uuid: String?,
    @SerializedName("created_at") val createdAt: String?
)
