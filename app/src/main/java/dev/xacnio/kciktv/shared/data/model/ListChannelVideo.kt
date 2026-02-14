/**
 * File: ListChannelVideo.kt
 *
 * Description: Implementation of List Channel Video functionality.
 *
 * Author: Xacnio
 *
 */
package dev.xacnio.kciktv.shared.data.model

import com.google.gson.annotations.SerializedName
import dev.xacnio.kciktv.shared.data.model.ChannelVideo
import dev.xacnio.kciktv.shared.data.model.ListChannelVideo
import dev.xacnio.kciktv.shared.data.model.TopCategory

/**
 * Model representing a video item in the channel video list (e.g. Previous Broadcasts).
 * Based on the user provided JSON structure.
 */
data class ListChannelVideo(
    @SerializedName("id") val id: Long, // Matches livestream ID
    @SerializedName("slug") val slug: String?,
    @SerializedName("channel_id") val channelId: Long?,
    @SerializedName("created_at") val createdAt: String?,
    @SerializedName("session_title") val sessionTitle: String?,
    @SerializedName("is_live") val isLive: Boolean?,
    @SerializedName("start_time") val startTime: String?,
    @SerializedName("source") val source: String?,
    @SerializedName("duration") val duration: Long?,
    @SerializedName("language") val language: String?,
    @SerializedName("viewer_count") val viewerCount: Int?,
    @SerializedName("thumbnail") val thumbnail: VideoThumbnail?,
    @SerializedName("views") val views: Int?,
    @SerializedName("video") val video: ListChannelVideoDetail?,
    @SerializedName("channel") val channel: ChannelVideoChannel?,
    @SerializedName("categories") val categories: List<TopCategory>?
) {
    val uuid: String? get() = video?.uuid
    val liveStreamId: Long? get() = video?.liveStreamId
    
    fun toChannelVideo(): ChannelVideo {
        return ChannelVideo(
            topId = id,
            topLiveStreamId = video?.liveStreamId,
            uuid = video?.uuid,
            createdAt = createdAt,
            updatedAt = video?.updatedAt,
            topSessionTitle = sessionTitle,
            topDuration = duration,
            topViewerCount = viewerCount,
            views = views,
            topLanguage = language,
            topIsMature = null, // Not explicitly in JSON root but safe to ignore or defaulting
            topCategories = categories,
            topThumbnail = thumbnail, // Compatible type
            topThumb = null,
            topIsLive = isLive,
            source = source,
            livestream = null
        )
    }
}

data class ListChannelVideoDetail(
    @SerializedName("id") val id: Long,
    @SerializedName("live_stream_id") val liveStreamId: Long?,
    @SerializedName("slug") val slug: String?,
    @SerializedName("uuid") val uuid: String?,
    @SerializedName("created_at") val createdAt: String?,
    @SerializedName("updated_at") val updatedAt: String?
)
