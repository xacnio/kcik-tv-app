/**
 * File: ActiveChattersStore.kt
 *
 * Description: Tracks everyone who has spoken this session (including the /history batch) and
 * how many messages each sent. Backs the "@" auto-fill and the Active Chatters panel.
 * In-memory only, cleared when the chat resets for a new channel.
 *
 * Author: Xacnio
 *
 */
package dev.xacnio.kciktv.mobile.ui.chat

import dev.xacnio.kciktv.shared.data.model.ActiveChatter
import dev.xacnio.kciktv.shared.data.model.ChatMessage
import dev.xacnio.kciktv.shared.data.model.ChatSender
import dev.xacnio.kciktv.shared.data.model.ChatterClassifier
import dev.xacnio.kciktv.shared.data.model.MessageType

class ActiveChattersStore {

    private class Entry(
        var sender: ChatSender,
        var messageCount: Int,
        var lastMessageAt: Long
    )

    /** Keyed by lowercase username, insertion ordered so the most recent speaker sits last. */
    private val entries = LinkedHashMap<String, Entry>()

    /** Already-counted ids: replayed history (reconnect, load-missed, paging) must not double count. */
    private val countedMessageIds = object : LinkedHashMap<String, Boolean>(512, 0.75f, false) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Boolean>?): Boolean =
            size > MAX_COUNTED_IDS
    }

    @Synchronized
    fun clear() {
        entries.clear()
        countedMessageIds.clear()
    }

    @Synchronized
    fun record(message: ChatMessage) {
        recordInternal(message)
    }

    @Synchronized
    fun recordAll(messages: List<ChatMessage>) {
        messages.forEach { recordInternal(it) }
    }

    /** Usernames starting with [prefix], most recent speaker first, capped at [limit]. */
    @Synchronized
    fun mentionCandidates(prefix: String, limit: Int): List<String> {
        if (limit <= 0) return emptyList()
        val matches = ArrayList<String>()
        for ((key, entry) in entries) {
            if (entry.messageCount > 0 && key.startsWith(prefix)) matches.add(entry.sender.username)
        }
        return matches.asReversed().take(limit)
    }

    @Synchronized
    fun snapshot(): List<ActiveChatter> = entries.values.map { entry ->
        ActiveChatter(
            sender = entry.sender,
            messageCount = entry.messageCount,
            lastMessageAt = entry.lastMessageAt,
            role = ChatterClassifier.roleOf(entry.sender),
            level = ChatterClassifier.levelOf(entry.sender),
            badgeKeys = ChatterClassifier.badgeKeysOf(entry.sender)
        )
    }

    private fun recordInternal(message: ChatMessage) {
        if (message.type !in COUNTED_TYPES) return
        val sender = message.sender
        // System/placeholder rows carry a synthetic sender with no real user id.
        if (sender.id <= 0L || sender.username.isBlank()) return

        val counted = countedMessageIds.put(message.id, true) == null
        val key = sender.username.lowercase()
        val existing = entries[key]

        if (existing == null) {
            entries[key] = Entry(
                sender = sender,
                messageCount = if (counted) 1 else 0,
                lastMessageAt = message.createdAt
            )
            return
        }

        if (counted) existing.messageCount++
        // Paging in older history must not promote a chatter back to the top
        if (message.createdAt >= existing.lastMessageAt) {
            existing.sender = sender
            existing.lastMessageAt = message.createdAt
            entries.remove(key)
            entries[key] = existing
        }
    }

    companion object {
        private const val MAX_COUNTED_IDS = 4000

        private val COUNTED_TYPES = setOf(
            MessageType.CHAT,
            MessageType.REWARD,
            MessageType.CELEBRATION,
            MessageType.GIFT
        )
    }
}
