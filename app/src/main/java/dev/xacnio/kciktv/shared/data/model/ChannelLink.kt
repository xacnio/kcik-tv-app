/**
 * File: ChannelLink.kt
 *
 * Description: Implementation of Channel Link functionality.
 *
 * Author: Xacnio
 *
 */
package dev.xacnio.kciktv.shared.data.model

import com.google.gson.annotations.SerializedName
import dev.xacnio.kciktv.shared.data.model.ChannelLink

data class ChannelLink(
    @SerializedName("id") val id: Long? = null,
    @SerializedName("channel_id") val channelId: Long? = null,
    @SerializedName("description") val description: String? = null,
    @SerializedName("link") val link: String? = null,
    @SerializedName("created_at") val createdAt: String? = null,
    @SerializedName("updated_at") val updatedAt: String? = null,
    @SerializedName("order") val order: Int? = null,
    @SerializedName("title") val title: String? = null,
    @SerializedName("image") val image: ChannelLinkImage? = null
)

data class ChannelLinkImage(
    @SerializedName("url") val url: String? = null
)
