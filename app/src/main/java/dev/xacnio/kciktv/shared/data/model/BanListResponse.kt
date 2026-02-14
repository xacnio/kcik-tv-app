/**
 * File: BanListResponse.kt
 *
 * Description: Implementation of Ban List Response functionality.
 *
 * Author: Xacnio
 *
 */
package dev.xacnio.kciktv.shared.data.model

import com.google.gson.annotations.SerializedName

/**
 * API response for GET /api/v2/channels/{slug}/bans
 * This endpoint returns a direct list of [BanItem]
 */

data class BanItem(
    @SerializedName("banned_user")
    val bannedUser: BanUserInfo?,
    
    @SerializedName("banned_by")
    val bannedBy: BanUserInfo?,
    
    @SerializedName("ban")
    val banInfo: BanDetails?
)

data class BanUserInfo(
    @SerializedName("id")
    val id: Long?,
    
    @SerializedName("username")
    val username: String?,
    
    @SerializedName("profile_pic")
    val profilePic: String?
)

data class BanDetails(
    @SerializedName("reason")
    val reason: String?,
    
    @SerializedName("banned_at")
    val bannedAt: String?,
    
    @SerializedName("permanent")
    val permanent: Boolean?,
    
    @SerializedName("expires_at")
    val expiresAt: String?
)
