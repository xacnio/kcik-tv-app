/**
 * File: ClipPlayResponse.kt
 *
 * Description: Implementation of Clip Play Response functionality.
 *
 * Author: Xacnio
 *
 */
package dev.xacnio.kciktv.shared.data.model

import com.google.gson.annotations.SerializedName
import dev.xacnio.kciktv.shared.data.model.ClipPlayResponse

data class ClipPlayResponse(
    @SerializedName("clip")
    val clip: ClipPlayDetails?
)

data class ClipPlayDetails(
    @SerializedName("id")
    val id: String?,
    
    @SerializedName("livestream_id")
    val livestreamId: String?,
    
    @SerializedName("category_id")
    val categoryId: String?,
    
    @SerializedName("channel_id")
    val channelId: Long?,
    
    @SerializedName("user_id")
    val userId: Long?,
    
    @SerializedName("title")
    val title: String?,
    
    @SerializedName("clip_url")
    val clipUrl: String?,
    
    @SerializedName("thumbnail_url")
    val thumbnailUrl: String?,
    
    @SerializedName("video_url")
    val videoUrl: String?,
    
    @SerializedName("views")
    val views: Int?,
    
    @SerializedName("view_count")
    val viewCount: Int?,
    
    @SerializedName("duration")
    val duration: Int?,
    
    @SerializedName("started_at")
    val startedAt: String?,
    
    @SerializedName("created_at")
    val createdAt: String?,
    
    @SerializedName("vod_starts_at")
    val vodStartsAt: Long?,
    
    @SerializedName("is_mature")
    val isMature: Boolean?,
    
    @SerializedName("vod")
    val vod: ClipVod?,
    
    @SerializedName("category")
    val category: ClipCategory?,
    
    @SerializedName("creator")
    val creator: ClipCreator?,
    
    @SerializedName("channel")
    val channel: ClipChannel?
)

data class ClipVod(
    @SerializedName("id")
    val id: String?
)

data class ClipCreator(
    @SerializedName("id")
    val id: Long?,
    
    @SerializedName("username")
    val username: String?,
    
    @SerializedName("slug")
    val slug: String?,
    
    @SerializedName("profile_picture")
    val profilePicture: String?
)

data class ClipChannel(
    @SerializedName("id")
    val id: Long?,
    
    @SerializedName("username")
    val username: String?,
    
    @SerializedName("slug")
    val slug: String?,
    
    @SerializedName("profile_picture")
    val profilePicture: String?
) {
    fun getEffectiveProfilePicUrl(): String {
        if (!profilePicture.isNullOrEmpty()) return profilePicture
        // Use a consistent default avatar based on username string hash (1-6)
        val name = username ?: slug ?: ""
        val hash = name.hashCode()
        val index = (if (hash < 0) -hash else hash) % 6 + 1
        return "https://kick.com/img/default-profile-pictures/default-avatar-$index.webp"
    }
}


/**
 * Simplified category for clip responses - banner field is ignored to avoid type conflicts
 */
data class ClipCategory(
    @SerializedName("id")
    val id: Long?,
    
    @SerializedName("name")
    val name: String?,
    
    @SerializedName("slug")
    val slug: String?
    // banner is intentionally omitted as it can be String or Object
)

data class ClipDownloadResponse(
    @SerializedName("url")
    val url: String?
)
