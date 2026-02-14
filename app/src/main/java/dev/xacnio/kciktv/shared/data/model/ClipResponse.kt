/**
 * File: ClipResponse.kt
 *
 * Description: Implementation of Clip Response functionality.
 *
 * Author: Xacnio
 *
 */
package dev.xacnio.kciktv.shared.data.model

import com.google.gson.annotations.SerializedName
import dev.xacnio.kciktv.shared.data.model.ClipResponse

data class ClipResponse(
    @SerializedName("id")
    val id: String,
    
    @SerializedName("url")
    val url: String,
    
    @SerializedName("thumbnails")
    val thumbnails: List<String>,
    
    @SerializedName("source_duration")
    val sourceDuration: Int,

    @SerializedName("clip_editor")
    val clip_editor: ClipEditor? = null
)

data class ClipEditor(
    @SerializedName("video_sources")
    val video_sources: List<VideoSource>,
    
    @SerializedName("duration")
    val duration: Int
)

data class VideoSource(
    @SerializedName("format")
    val format: String,
    
    @SerializedName("source")
    val source: String
)

data class CreateClipRequest(
    @SerializedName("title")
    val title: String,
    
    @SerializedName("start_time")
    val startTime: Int,
    
    @SerializedName("duration")
    val duration: Int
)

data class FinalizeClipRequest(
    @SerializedName("duration")
    val duration: Int,
    
    @SerializedName("start_time")
    val startTime: Int,
    
    @SerializedName("title")
    val title: String
)
