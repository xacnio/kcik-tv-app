/**
 * File: EmoteModels.kt
 *
 * Description: Implementation of Emote Models functionality.
 *
 * Author: Xacnio
 *
 */
package dev.xacnio.kciktv.shared.data.model

import com.google.gson.annotations.SerializedName

/**
 * Emote category response - can be channel, global or emoji
 */
data class EmoteCategory(
    @SerializedName("id") val id: Any?, // Can be Long for channels or String for Global/Emoji
    @SerializedName("slug") val slug: String? = null,
    @SerializedName("name") val name: String? = null,
    @SerializedName("emotes") val emotes: List<Emote> = emptyList(),
    @SerializedName("user") val user: EmoteUser? = null
) {
    val displayName: String
        get() = when {
            slug != null -> user?.username ?: slug
            name != null -> name
            else -> "Unknown"
        }
    
    val isChannelEmotes: Boolean
        get() = slug != null
    
    val isGlobal: Boolean
        get() = name == "Global"
    
    val isEmoji: Boolean
        get() = name == "Emojis"
    
    val profilePic: String?
        get() = user?.profilePic
}

data class Emote(
    @SerializedName("id") val id: Long,
    @SerializedName("channel_id") val channelId: Long? = null,
    @SerializedName("name") val name: String,
    @SerializedName("subscribers_only") val subscribersOnly: Boolean = false
) {
    val imageUrl: String
        get() = "https://files.kick.com/emotes/$id/fullsize"
}

data class EmoteUser(
    @SerializedName("id") val id: Long,
    @SerializedName("username") val username: String,
    @SerializedName("profile_pic") val profilePic: String? = null
)
