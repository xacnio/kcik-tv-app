package dev.xacnio.kciktv.data.model

import android.os.Parcelable
import kotlinx.parcelize.Parcelize
import java.text.SimpleDateFormat
import java.util.TimeZone
import java.util.Locale

/**
 * Simplified channel model used within the app
 */
@Parcelize
data class ChannelItem(
    val id: String,
    val slug: String,
    val username: String,
    val title: String,
    val viewerCount: Int,
    val thumbnailUrl: String?,
    val profilePicUrl: String?,
    val playbackUrl: String?,
    val categoryName: String?,
    val language: String?,
    val isLive: Boolean = true,
    val offlineBannerUrl: String? = null,
    val startTimeMillis: Long? = null
) : Parcelable {
    companion object {
        fun fromLiveStreamItem(item: LiveStreamItem): ChannelItem {
            val username = item.channel?.username ?: item.channel?.user?.username ?: ""
            val profilePicUrl = item.channel?.profilePic ?: item.channel?.user?.profilePic
            val title = item.title ?: item.sessionTitle ?: "Live Stream"
            val categoryName = item.category?.name ?: item.categories?.firstOrNull()?.name
            val viewerCount = if (item.viewerCount > 0) item.viewerCount else (item.viewers ?: 0)
            
            // Try to parse startTime if available
            val startTime = item.startTime?.let { dateStr ->
                try {
                    val sdf = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US)
                    sdf.timeZone = TimeZone.getTimeZone("UTC")
                    sdf.parse(dateStr)?.time
                } catch (e: Exception) { null }
            }

            return ChannelItem(
                id = item.id,
                slug = item.channel?.slug ?: "",
                username = username,
                title = title,
                viewerCount = viewerCount,
                thumbnailUrl = item.thumbnail?.src,
                profilePicUrl = profilePicUrl,
                playbackUrl = null, // Will be fetched separately from API
                categoryName = categoryName,
                language = item.language,
                isLive = true,
                offlineBannerUrl = null,
                startTimeMillis = startTime
            )
        }

        fun fromFollowedChannelItem(item: FollowedChannelItem): ChannelItem {
            return ChannelItem(
                id = item.channelSlug, // Use slug if there is no string id in API
                slug = item.channelSlug,
                username = item.userUsername,
                title = item.sessionTitle ?: "Live Stream",
                viewerCount = item.viewerCount,
                thumbnailUrl = null,
                profilePicUrl = item.profilePicture,
                playbackUrl = null,
                categoryName = item.categoryName,
                language = "tr",
                isLive = item.isLive,
                offlineBannerUrl = item.bannerPicture
            )
        }
    }
    
     /**
     * Returns the HLS stream URL
     */
    fun getStreamUrl(): String {
        return playbackUrl ?: "https://fa723fc1b171.us-west-2.playback.live-video.net/api/video/v1/us-west-2.196233775518.channel.$slug.m3u8"
    }
}
