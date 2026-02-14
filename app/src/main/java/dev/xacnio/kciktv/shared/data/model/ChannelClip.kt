/**
 * File: ChannelClip.kt
 *
 * Description: Implementation of Channel Clip functionality.
 *
 * Author: Xacnio
 *
 */
package dev.xacnio.kciktv.shared.data.model

import com.google.gson.annotations.SerializedName
import dev.xacnio.kciktv.shared.data.model.ChannelClip

data class ChannelClip(
    @SerializedName("id")
    val id: String,
    
    @SerializedName("title")
    val title: String?,
    
    @SerializedName("thumbnail_url")
    val thumbnailUrl: String?,
    
    @SerializedName("duration")
    val duration: Int?,
            
    @SerializedName("view_count")
    val views: Int?,
    
    @SerializedName("created_at")
    val createdAt: String?,
    
    @SerializedName("clip_url")
    val url: String?,
    
    @SerializedName("creator")
    val creator: ClipCreator?,
    
    @SerializedName("channel")
    val channel: ClipChannel?
)

data class ChannelClipsResponse(
    @SerializedName("clips")
    val clips: List<ChannelClip>,
    
    @SerializedName("nextCursor")
    val nextCursor: String? = null
)
