/**
 * File: ChannelItem.kt
 *
 * Description: Implementation of Channel Item functionality.
 *
 * Author: Xacnio
 *
 */
package dev.xacnio.kciktv.shared.data.model

import android.os.Parcelable
import kotlinx.parcelize.Parcelize
import java.text.SimpleDateFormat
import java.util.TimeZone
import java.util.Locale
import dev.xacnio.kciktv.shared.data.model.ChannelDetailResponse
import dev.xacnio.kciktv.shared.data.model.ChannelItem

/**
 * Simplified channel model used within the app
 */
@Parcelize
data class ChannelItem(
    val id: String,
    val slug: String,
    val username: String,
    val title: String?,
    var viewerCount: Int,
    val thumbnailUrl: String?,
    val profilePicUrl: String?,
    val playbackUrl: String?,
    val categoryName: String?,
    val categorySlug: String?,
    val language: String?,
    val isLive: Boolean = true,
    val isMature: Boolean = false,
    val verified: Boolean = false,
    val offlineBannerUrl: String? = null,
    val startTimeMillis: Long? = null,
    val tags: List<String>? = null,
    val livestreamId: Long? = null,
    val categoryId: Long? = null,
    val livestreamSlug: String? = null,
    val chatroomId: Long? = null  // Chatroom ID for chat history API
) : Parcelable {
    companion object {
        fun fromLiveStreamItem(item: LiveStreamItem): ChannelItem {
            val username = item.channel?.username ?: item.channel?.user?.username ?: ""
            val profilePicUrl = item.channel?.profilePic ?: item.channel?.user?.profilePic
            val title = item.title ?: item.sessionTitle
            val categoryName = item.category?.name ?: item.categories?.firstOrNull()?.name
            val categorySlug = item.category?.slug ?: item.categories?.firstOrNull()?.slug
            val viewerCount = if (item.viewerCount > 0) item.viewerCount else (item.viewers ?: 0)
            
            // Try to parse startTime if available
            val startTime = item.startTime?.let { dateStr ->
                try {
                    val cleaned = if (dateStr.contains(".")) dateStr.substringBefore(".") + "Z" else dateStr
                    val sdf = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US)
                    sdf.timeZone = TimeZone.getTimeZone("UTC")
                    sdf.parse(cleaned)?.time
                } catch (e: Exception) {
                    try {
                        val sdf = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US)
                        sdf.timeZone = TimeZone.getTimeZone("UTC")
                        sdf.parse(dateStr)?.time
                    } catch (e2: Exception) { null }
                }
            }

            val livestreamIdParsed = item.id.toLongOrNull()
            return ChannelItem(
                id = item.channel?.id?.toString() ?: item.id,
                slug = item.channel?.slug ?: "",
                username = username,
                title = title,
                viewerCount = viewerCount,
                thumbnailUrl = getBestImageUrl(item.thumbnail?.src, item.thumbnail?.srcset),
                profilePicUrl = profilePicUrl,
                playbackUrl = null, // Will be fetched separately from API
                categoryName = categoryName,
                categorySlug = categorySlug,
                language = item.language,
                isLive = true,
                isMature = item.isMature ?: false,
                verified = item.channel?.verified ?: false,
                offlineBannerUrl = null,
                startTimeMillis = startTime,
                tags = item.tags,
                livestreamId = livestreamIdParsed,
                categoryId = item.category?.id ?: item.categories?.firstOrNull()?.id
            )
        }

        fun fromFollowedChannelItem(item: FollowedChannelItem): ChannelItem {
            return ChannelItem(
                id = item.channelSlug, // Use slug if there is no string id in API
                slug = item.channelSlug,
                username = item.userUsername,
                title = item.sessionTitle,
                viewerCount = item.viewerCount,
                thumbnailUrl = item.bannerPicture,
                profilePicUrl = item.profilePicture,
                playbackUrl = null,
                categoryName = item.categoryName,
                categorySlug = null, // Not available in followed API directly yet
                language = null, // V2 API doesn't provide language info
                isLive = item.isLive,
                isMature = false, // Not available in followed-page API at the moment
                verified = false, // Not available in this API
                offlineBannerUrl = null,
                tags = null,
                categoryId = null
            )
        }

        fun fromChannelDetailResponse(response: ChannelDetailResponse): ChannelItem {
            val ls = response.livestream
            val cat = ls?.categories?.firstOrNull() ?: response.recentCategories?.firstOrNull()
            return ChannelItem(
                id = response.id?.toString() ?: "",
                slug = response.slug ?: "",
                username = response.user?.username ?: "",
                title = ls?.sessionTitle,
                viewerCount = ls?.viewerCount ?: 0,
                thumbnailUrl = getBestImageUrl(ls?.thumbnail?.src, ls?.thumbnail?.srcset) ?: getBestImageUrl(response.bannerImage?.src, response.bannerImage?.srcset),
                profilePicUrl = response.user?.profilePic,
                playbackUrl = response.playbackUrl,
                categoryName = cat?.name,
                categorySlug = cat?.slug,
                language = ls?.langIso,
                isLive = ls?.isLive ?: false,
                isMature = false,
                verified = response.verified ?: false,
                offlineBannerUrl = getBestImageUrl(response.offlineBannerImage?.src, response.offlineBannerImage?.srcset)
                    ?: "https://kick.com/img/default-channel-banners/offline-banner.webp",
                startTimeMillis = ls?.startTime?.let { dateStr ->
                    try {
                        // Format: "2026-01-12 13:51:16"
                        val sdf = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)
                        sdf.timeZone = TimeZone.getTimeZone("UTC")
                        sdf.parse(dateStr)?.time
                    } catch (e: Exception) { null }
                },
                tags = ls?.tags,
                livestreamId = ls?.id,
                categoryId = cat?.id,
                livestreamSlug = ls?.slug,
                chatroomId = response.chatroom?.id
            )
        }

        /**
         * Parses Kick's srcset and returns the last (usually smallest/optimized) URL
         */
        fun getBestImageUrl(src: String?, srcset: String?): String? {
            if (srcset.isNullOrEmpty()) return src
            return try {
                val sources = srcset.split(",")
                if (sources.isNotEmpty()) {
                    val lastSource = sources.last().trim()
                    val url = lastSource.split(" ").firstOrNull()
                    if (!url.isNullOrEmpty()) url else src
                } else src
            } catch (e: Exception) {
                src
            }
        }
    }
    
     /**
     * Returns the HLS stream URL
     */
    fun getStreamUrl(): String? {
        return playbackUrl
    }

    /**
     * Returns the profile picture URL or a consistent default one if null
     */
    fun getEffectiveProfilePicUrl(): String {
        if (!profilePicUrl.isNullOrEmpty()) return profilePicUrl
        // Use a consistent default avatar based on username string hash (1-6)
        val hash = username.hashCode()
        val index = (if (hash < 0) -hash else hash) % 6 + 1
        return "https://kick.com/img/default-profile-pictures/default-avatar-$index.webp"
    }

    /**
     * Returns the offline banner URL or a default Kick banner if null
     */
    fun getEffectiveOfflineBannerUrl(): String {
        if (!offlineBannerUrl.isNullOrEmpty()) return offlineBannerUrl
        return "https://kick.com/img/default-channel-banners/offline-banner.webp"
    }
}
