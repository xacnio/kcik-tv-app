/**
 * File: PinnedGiftsModels.kt
 *
 * Description: Implementation of Pinned Gifts Models functionality.
 *
 * Author: Xacnio
 *
 */
package dev.xacnio.kciktv.shared.data.model

import com.google.gson.annotations.SerializedName

data class PinnedGiftsResponse(
    @SerializedName("data") val data: PinnedGiftsData?,
    @SerializedName("message") val message: String?
)

data class PinnedGiftsData(
    @SerializedName("pinned_gifts") val pinnedGifts: List<PinnedGift>?
)

data class PinnedGift(
    @SerializedName("created_at") val createdAt: String?,
    @SerializedName("expires_at") val expiresAt: String?,
    @SerializedName("gift") val gift: GiftInfo?,
    @SerializedName("gift_transaction_id") val giftTransactionId: String?,
    @SerializedName("message") val message: String?,
    @SerializedName("sender") val sender: PinnedGiftSender?,
    @SerializedName("pinned_time") val pinnedTime: Long? = null
)

data class GiftInfo(
    @SerializedName("amount") val amount: Int?,
    @SerializedName("gift_id") val giftId: String?,
    @SerializedName("name") val name: String?,
    @SerializedName("tier") val tier: String?,
    @SerializedName("pinned_time") val pinnedTime: Long? = null
)

data class PinnedGiftSender(
    @SerializedName("id") val id: Long?,
    @SerializedName("profile_picture") val profilePicture: String?,
    @SerializedName("username") val username: String?,
    @SerializedName("username_color") val usernameColor: String?
)

data class GiftsListResponse(
    @SerializedName("data") val data: List<GiftMetadata>?,
    @SerializedName("message") val message: String?
)

data class GiftMetadata(
    @SerializedName("amount") val amount: Int?,
    @SerializedName("character_limit") val characterLimit: Int?,
    @SerializedName("gift_id") val giftId: String?,
    @SerializedName("name") val name: String?,
    @SerializedName("pinned_time") val pinnedTime: Long?,
    @SerializedName("tier") val tier: String?,
    @SerializedName("type") val type: String?
)
