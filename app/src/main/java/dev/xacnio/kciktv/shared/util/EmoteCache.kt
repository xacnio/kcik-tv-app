package dev.xacnio.kciktv.shared.util

import android.content.Context
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import dev.xacnio.kciktv.shared.data.model.EmoteCategory
import java.io.File

object EmoteCache {
    private val gson = Gson()
    private val listType = object : TypeToken<List<EmoteCategory>>() {}.type

    fun save(context: Context, slug: String, categories: List<EmoteCategory>) {
        try {
            cacheFile(context, slug).writeText(gson.toJson(categories))
        } catch (_: Exception) {}
    }

    fun load(context: Context, slug: String): List<EmoteCategory>? {
        return try {
            val file = cacheFile(context, slug)
            if (!file.exists()) null
            else gson.fromJson(file.readText(), listType)
        } catch (_: Exception) {
            null
        }
    }

    private fun cacheFile(context: Context, slug: String) =
        File(context.filesDir, "emotes_$slug.json")
}
