/**
 * File: LiveStreamsResponse.kt
 *
 * Description: Implementation of Live Streams Response functionality.
 *
 * Author: Xacnio
 *
 */
package dev.xacnio.kciktv.shared.data.model

import com.google.gson.annotations.SerializedName
import dev.xacnio.kciktv.shared.data.model.LiveStreamsResponse

/**
 * API response for the live streams list
 * API format: {"data":{"livestreams":[...],"pagination":{...}},"message":"Success"}
 */
data class LiveStreamsResponse(
    @SerializedName("data")
    val data: LiveStreamsData?,
    
    @SerializedName("pagination")
    val pagination: Pagination?,
    
    @SerializedName("message")
    val message: String?
)

data class LiveStreamsData(
    @SerializedName("livestreams")
    val livestreams: List<LiveStreamItem>,
    
    @SerializedName("pagination")
    val pagination: Pagination?
)

data class Pagination(
    @SerializedName("next_cursor")
    val nextCursor: String?
)

data class LiveStreamItem(
    @SerializedName("id")
    val id: String,
    
    @SerializedName("title")
    val title: String?,
    
    @SerializedName("session_title")
    val sessionTitle: String?,
    
    @SerializedName("viewer_count")
    val viewerCount: Int = 0,
    
    @SerializedName("viewers")
    val viewers: Int? = null,
    
    @SerializedName("thumbnail")
    val thumbnail: ThumbnailInfo?,
    
    @SerializedName("start_time")
    val startTime: String?,
    
    @SerializedName("channel")
    val channel: ChannelInfo?,
    
    @SerializedName("category")
    val category: CategoryInfo?,
    
    @SerializedName("categories")
    val categories: List<CategoryInfo>?,
    
    @SerializedName("language")
    val language: String?,
    
    @SerializedName("is_mature")
    val isMature: Boolean?,
    
    @SerializedName("tags")
    val tags: List<String>?
)

data class ThumbnailInfo(
    @SerializedName("src")
    val src: String?,
    
    @SerializedName("srcset")
    val srcset: String?
)

data class ChannelInfo(
    @SerializedName("id")
    val id: Long,
    
    @SerializedName("slug")
    val slug: String,
    
    @SerializedName("username")
    val username: String?,
    
    @SerializedName(value = "profile_pic", alternate = ["profile_picture", "profilepic"])
    val profilePic: String?,

    @SerializedName("verified")
    val verified: Boolean? = false,
    
    @SerializedName("user")
    val user: FollowingUserInfo?
)

data class FollowingUserInfo(
    @SerializedName("username")
    val username: String?,
    
    @SerializedName(value = "profilepic", alternate = ["profile_pic", "profile_picture"])
    val profilePic: String?
)

data class CategoryInfo(
    @SerializedName("id")
    val id: Long,
    
    @SerializedName("name")
    val name: String,
    
    @SerializedName("slug")
    val slug: String,
    
    @SerializedName("banner")
    val banner: BannerInfo? = null,

    @SerializedName("tags")
    val tags: List<String>? = null
)

data class BannerInfo(
    @SerializedName("url")
    val url: String?
)
