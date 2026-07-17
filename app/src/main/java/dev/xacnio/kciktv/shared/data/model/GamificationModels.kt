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
    @SerializedName("name") val name: String?,
    @SerializedName("metadata") val metadata: BadgeMetadata? = null
)

data class BadgeMetadata(
    @SerializedName("level") val level: Int?
)

data class ChallengesResponse(
    @SerializedName("data") val data: List<DailyChallenge>?,
    @SerializedName("message") val message: String?
)

data class DailyChallenge(
    @SerializedName("claimed_at") val claimedAt: String?,
    @SerializedName("condition") val condition: ChallengeCondition,
    @SerializedName("consolation") val consolation: ChallengeConsolation?,
    @SerializedName("drop_table") val dropTable: List<ChallengeDropTableEntry> = emptyList(),
    @SerializedName("id") val id: String,
    @SerializedName("recurrence") val recurrence: String?,
    @SerializedName("status") val status: String?,
    @SerializedName("window") val window: ChallengeWindow,
    @SerializedName("winner") val winner: ChallengeWinner?
) {
    val isClaimed: Boolean
        get() = winner != null

    val isUnlocked: Boolean
        get() = condition.progress >= condition.threshold
}

data class ChallengeCondition(
    @SerializedName("progress") val progress: Long,
    @SerializedName("threshold") val threshold: Long,
    @SerializedName("type") val type: String?
)

data class ChallengeConsolation(
    @SerializedName("next_level_badge") val nextLevelBadge: UserLevelBadge?,
    @SerializedName("watch_time_minutes") val watchTimeMinutes: Long
)

data class ChallengeDropTableEntry(
    @SerializedName("rarity") val rarity: String,
    @SerializedName("weighting") val weighting: Long
) {
    val percent: Double
        get() = weighting / 10_000.0
}

data class ChallengeWindow(
    @SerializedName("ends_at") val endsAt: String?,
    @SerializedName("starts_at") val startsAt: String?
)

data class ChallengeWinner(
    @SerializedName("card_url") val cardUrl: String?,
    @SerializedName("id") val id: String?,
    @SerializedName("rarity") val rarity: String?
)

data class ClaimChallengeResponse(
    @SerializedName("data") val data: ClaimChallengeData?,
    @SerializedName("message") val message: String?
)

data class ClaimChallengeData(
    @SerializedName("challenge_id") val challengeId: String,
    @SerializedName("consolation") val consolation: ChallengeConsolation?,
    @SerializedName("roulette") val roulette: List<RouletteItem> = emptyList(),
    @SerializedName("winner") val winner: ChallengeWinner
)

data class RouletteItem(
    @SerializedName("id") val id: String,
    @SerializedName("item_url") val itemUrl: String
)

data class CollectiblesResponse(
    @SerializedName("data") val data: List<CollectibleCard>?,
    @SerializedName("message") val message: String?
)

data class CollectibleCard(
    @SerializedName("card_url") val cardUrl: String?,
    @SerializedName("id") val id: String,
    @SerializedName("owned") val owned: Boolean,
    @SerializedName("rarity") val rarity: String?,
    @SerializedName("type") val type: String?
)
