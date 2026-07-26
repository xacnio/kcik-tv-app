/**
 * File: ActiveChatter.kt
 *
 * Description: Model for one participant of the current chat session, plus the role/badge/level
 * classification the Active Chatters panel groups, filters and sorts by.
 *
 * Author: Xacnio
 *
 */
package dev.xacnio.kciktv.shared.data.model

/** Section a chatter falls into when the list is grouped by role. Highest badge wins. */
enum class ChatterRole {
    MODERATOR,
    VIP,
    OG,
    VIEWER
}

/** How the Active Chatters list is split into sections. */
enum class ChatterGrouping {
    /** Moderators / VIPs / OGs / viewers. */
    ROLE,

    /** One section per badge — a chatter lands under their top-ranked one. */
    BADGE,

    /** A-Z sections on the first letter of the name. */
    ALPHABET,

    NONE
}

/** Ordering within each section. Every mode can run in either direction. */
enum class ChatterSort {
    MESSAGES,
    NAME,
    LEVEL,
    RECENT
}

/** [sender] is the most recently seen identity, so badges and colour stay current. */
data class ActiveChatter(
    val sender: ChatSender,
    val messageCount: Int,
    val lastMessageAt: Long,
    val role: ChatterRole,
    /** Gamification level, 0 when the chatter wears no level badge. */
    val level: Int,
    /** Filter keys for every badge worn — see [ChatterClassifier.badgeKeysOf]. */
    val badgeKeys: Set<String>
) {
    val username: String get() = sender.username
}

object ChatterClassifier {

    /** Filter key for chatters wearing nothing at all. */
    const val NO_BADGE = "none"

    /** Marks a badges_v2 (global) key so a global "og" can't collide with a channel "og". */
    const val V2_PREFIX = "v2:"

    /** Display order for the filter strip and the badge grouping; unlisted keys go after. */
    private val KEY_ORDER = listOf(
        "broadcaster",
        "moderator",
        "vip",
        "og",
        "founder",
        "${V2_PREFIX}verified",
        "verified",
        "${V2_PREFIX}staff",
        "staff",
        "subscriber",
        "sub_gifter",
        "${V2_PREFIX}sub_gifter",
        "${V2_PREFIX}sidekick",
        "sidekick",
        "${V2_PREFIX}bot",
        "bot"
    )

    /** [KEY_ORDER] first, then unrecognised badges, "no badge" last. */
    fun keyRank(key: String): Int = when {
        key == NO_BADGE -> Int.MAX_VALUE
        else -> KEY_ORDER.indexOf(key).let { if (it >= 0) it else KEY_ORDER.size }
    }

    /** Excluded from grouping and filtering — one section per level would be noise. */
    fun isLevelBadge(badge: ChatBadgeV2): Boolean =
        badge.metadata?.level != null || badge.badgeType?.contains("level", ignoreCase = true) == true

    fun roleOf(sender: ChatSender): ChatterRole {
        val types = sender.badges?.mapTo(HashSet()) { it.type } ?: emptySet<String>()
        return when {
            "moderator" in types -> ChatterRole.MODERATOR
            "vip" in types -> ChatterRole.VIP
            "og" in types -> ChatterRole.OG
            else -> ChatterRole.VIEWER
        }
    }

    /** Every badge worn, across both schemas. Bare chatters get [NO_BADGE] so they stay filterable. */
    fun badgeKeysOf(sender: ChatSender): Set<String> {
        val out = LinkedHashSet<String>()
        sender.badges?.forEach { if (it.type.isNotEmpty()) out.add(it.type) }
        sender.badgesV2?.forEach { badge ->
            if (badge.selected == true && !isLevelBadge(badge)) {
                badge.name?.takeIf { it.isNotEmpty() }?.let { out.add(V2_PREFIX + it) }
            }
        }
        if (out.isEmpty()) out.add(NO_BADGE)
        return out
    }

    /** Read from metadata rather than a badge name, so renamed badges keep working. */
    fun levelOf(sender: ChatSender): Int =
        sender.badgesV2?.mapNotNull { it.metadata?.level }?.maxOrNull() ?: 0
}
