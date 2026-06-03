package dev.xacnio.kciktv.shared.data.model

import com.google.gson.annotations.SerializedName

data class UserLevelResponse(
    @SerializedName("data") val data: UserLevelData?,
    @SerializedName("message") val message: String?
)

data class UserLevelData(
    @SerializedName("badge") val badge: UserLevelBadge?,
    @SerializedName("level") val level: Int,
    @SerializedName("progress_xp") val progressXp: Int,
    @SerializedName("total_xp") val totalXp: Int,
    @SerializedName("user_id") val userId: Long,
    @SerializedName("xp_to_next_level") val xpToNextLevel: Int
) {
    val progressPercent: Int
        get() {
            val total = progressXp + xpToNextLevel
            return if (total > 0) (progressXp * 100 / total).coerceIn(0, 100) else 0
        }
}

data class UserLevelBadge(
    @SerializedName("badge_type") val badgeType: String?,
    @SerializedName("image_url") val imageUrl: String?,
    @SerializedName("name") val name: String?
)
