/**
 * File: BrowseClipResponse.kt
 *
 * Description: Implementation of Browse Clip Response functionality.
 *
 * Author: Xacnio
 *
 */
package dev.xacnio.kciktv.shared.data.model

import com.google.gson.annotations.SerializedName

/**
 * Response model for the Browse Clips API endpoint
 * GET https://kick.com/api/v2/clips
 */
data class BrowseClipsResponse(
    @SerializedName("clips")
    val clips: List<BrowseClip>,
    
    @SerializedName("nextCursor")
    val nextCursor: String?
)

data class BrowseClip(
    @SerializedName("id")
    val id: String,
    
    @SerializedName("livestream_id")
    val livestreamId: String?,
    
    @SerializedName("category_id")
    val categoryId: String?,
    
    @SerializedName("channel_id")
    val channelId: Long,
    
    @SerializedName("user_id")
    val userId: Long,
    
    @SerializedName("title")
    val title: String?,
    
    @SerializedName("clip_url")
    val clipUrl: String?,
    
    @SerializedName("thumbnail_url")
    val thumbnailUrl: String?,
    
    @SerializedName("privacy")
    val privacy: String?,
    
    @SerializedName("likes")
    val likes: Int?,
    
    @SerializedName("liked")
    val liked: Boolean?,
    
    @SerializedName("views")
    val views: Int?,
    
    @SerializedName("duration")
    val duration: Int?,
    
    @SerializedName("started_at")
    val startedAt: String?,
    
    @SerializedName("created_at")
    val createdAt: String?,
    
    @SerializedName("vod_starts_at")
    val vodStartsAt: Int?,
    
    @SerializedName("is_mature")
    val isMature: Boolean?,
    
    @SerializedName("video_url")
    val videoUrl: String?,
    
    @SerializedName("view_count")
    val viewCount: Int?,
    
    @SerializedName("likes_count")
    val likesCount: Int?,
    
    @SerializedName("category")
    val category: BrowseClipCategory?,
    
    @SerializedName("creator")
    val creator: BrowseClipUser?,
    
    @SerializedName("channel")
    val channel: BrowseClipChannel?
)

data class BrowseClipCategory(
    @SerializedName("id")
    val id: Int,
    
    @SerializedName("name")
    val name: String?,
    
    @SerializedName("slug")
    val slug: String?,
    
    @SerializedName("parent_category")
    val parentCategory: String?
)

data class BrowseClipUser(
    @SerializedName("id")
    val id: Long,
    
    @SerializedName("username")
    val username: String?,
    
    @SerializedName("slug")
    val slug: String?
)

data class BrowseClipChannel(
    @SerializedName("id")
    val id: Long,
    
    @SerializedName("username")
    val username: String?,
    
    @SerializedName("slug")
    val slug: String?,
    
    @SerializedName("profile_picture")
    val profilePicture: String?
)
