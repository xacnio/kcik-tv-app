package dev.xacnio.kciktv.shared.util

import android.content.Context
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import dev.xacnio.kciktv.shared.data.model.ChatMessage
import java.io.File

object MentionStore {
    private val gson = Gson()
    private val listType = object : TypeToken<List<ChatMessage>>() {}.type
    private const val MAX_MENTIONS = 200

    fun save(context: Context, username: String, channelSlug: String, messages: List<ChatMessage>) {
        try {
            val limited = messages.takeLast(MAX_MENTIONS)
            cacheFile(context, username, channelSlug).writeText(gson.toJson(limited))
        } catch (_: Exception) {}
    }

    fun load(context: Context, username: String, channelSlug: String): MutableList<ChatMessage> {
        return try {
            val file = cacheFile(context, username, channelSlug)
            if (!file.exists()) mutableListOf()
            else gson.fromJson<List<ChatMessage>>(file.readText(), listType)?.toMutableList()
                ?: mutableListOf()
        } catch (_: Exception) {
            mutableListOf()
        }
    }

    fun clear(context: Context, username: String, channelSlug: String) {
        try { cacheFile(context, username, channelSlug).delete() } catch (_: Exception) {}
    }

    private fun cacheFile(context: Context, username: String, channelSlug: String) =
        File(context.filesDir, "mentions_${username.lowercase()}_${channelSlug.lowercase()}.json")
}
