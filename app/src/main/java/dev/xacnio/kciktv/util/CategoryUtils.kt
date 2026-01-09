package dev.xacnio.kciktv.util

import android.content.Context
import dev.xacnio.kciktv.R

object CategoryUtils {
    fun getLocalizedCategoryName(context: Context, inputName: String?, inputSlug: String?, languageCode: String): String {
        if (inputName == null) return ""
        
        // Kullanıcı isteği: Sadece Türkçe ise çevir, yoksa API'den geleni kullan.
        if (languageCode != "tr") {
            return inputName
        }
        
        // Use slug if available, otherwise normalize the name
        val lookupKey = inputSlug ?: inputName.lowercase().replace(" ", "-").replace("&", "").replace(",", "").replace("--", "-")
        
        val resId = when (lookupKey) {
            "art" -> R.string.cat_art
            "chess" -> R.string.cat_chess
            "slots", "slots-casino" -> R.string.cat_slots
            "food-drink" -> R.string.cat_food_drink
            "games-demos" -> R.string.cat_games_demos
            "just-chatting" -> R.string.cat_just_chatting
            "just-sleeping" -> R.string.cat_just_sleeping
            "music" -> R.string.cat_music
            "music-production" -> R.string.cat_music_production
            "pools-hot-tubs", "pools-hot-tubs-bikinis" -> R.string.cat_pools_hot_tubs
            "special-events" -> R.string.cat_special_events
            "sports" -> R.string.cat_sports
            "video-production" -> R.string.cat_video_production
            "irl" -> R.string.cat_irl
            "gaming" -> R.string.cat_gaming
            "creative" -> R.string.cat_creative
            else -> 0
        }
        
        return if (resId != 0) context.getString(resId) else inputName
    }
}
