/**
 * File: AppPreferences.kt
 *
 * Description: Implementation of App Preferences functionality.
 *
 * Author: Xacnio
 *
 */
package dev.xacnio.kciktv.shared.data.prefs

import android.content.Context
import android.content.SharedPreferences
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import dev.xacnio.kciktv.shared.util.CryptoManager
import dev.xacnio.kciktv.shared.data.prefs.AppPreferences
import dev.xacnio.kciktv.R

class AppPreferences(private val context: Context) {
    private val prefs: SharedPreferences = context.getSharedPreferences("kick_tv_prefs", Context.MODE_PRIVATE)
    private val cryptoManager = CryptoManager()

    // Helper to encrypt data transparently
    private fun putStringSafe(key: String, value: String?) {
        if (value == null) {
            prefs.edit().remove(key).apply()
            return
        }
        val encrypted = cryptoManager.encrypt(value)
        prefs.edit().putString(key, encrypted).apply()
    }

    // Helper to decrypt data transparently (with auto-migration)
    private fun getStringSafe(key: String, defValue: String?): String? {
        val storedValue = prefs.getString(key, null) ?: return defValue
        
        // Check if value looks encrypted (contains our separator)
        return if (storedValue.contains("|||")) {
             cryptoManager.decrypt(storedValue) ?: defValue // Return default if decryption fails
        } else {
             // It's plain text (old version), migrate it to encrypted
             putStringSafe(key, storedValue)
             storedValue
        }
    }

    var infoDelay: Int
        get() = prefs.getInt("info_delay", 5)
        set(value) = prefs.edit().putInt("info_delay", value).apply()

    var isFeedContentFullscreen: Boolean
        get() = prefs.getBoolean("feed_content_fullscreen", false)
        set(value) = prefs.edit().putBoolean("feed_content_fullscreen", value).apply()

    var isClipContentFullscreen: Boolean
        get() = prefs.getBoolean("clip_content_fullscreen", false)
        set(value) = prefs.edit().putBoolean("clip_content_fullscreen", value).apply()

    var infoTransparency: Int
        get() = prefs.getInt("info_transparency", 60)
        set(value) = prefs.edit().putInt("info_transparency", value).apply()

    var updateChannel: String
        get() {
            val saved = prefs.getString("update_channel", null)
            if (saved != null) return saved
            
            // Auto-detect based on version name
            return try {
                val pInfo = context.packageManager.getPackageInfo(context.packageName, 0)
                if (pInfo.versionName.contains("beta", ignoreCase = true)) "beta" else "stable"
            } catch (e: Exception) {
                "stable"
            }
        }
        set(value) = prefs.edit().putString("update_channel", value).apply()

    var language: String
        get() = prefs.getString("language", "system") ?: "system"
        set(value) = prefs.edit().putString("language", value).apply()

    val languageRaw: String
        get() = language

    var themeColor: Int
        get() = androidx.core.content.ContextCompat.getColor(context, dev.xacnio.kciktv.R.color.theme_primary)
        set(@Suppress("UNUSED_PARAMETER") value) { /* No-op, theme is locked */ }

    var blockedCategories: Set<String>
        get() = prefs.getStringSet("blocked_categories", setOf("Pools, Hot Tubs & Beaches")) ?: setOf("Pools, Hot Tubs & Beaches")
        set(value) = prefs.edit().putStringSet("blocked_categories", value).apply()

    var streamLanguages: Set<String>
        get() {
            if (!prefs.contains("stream_languages")) {
                val systemLang = java.util.Locale.getDefault().language
                return setOf(systemLang)
            }
            return prefs.getStringSet("stream_languages", emptySet()) ?: emptySet()
        }
        set(value) = prefs.edit().putStringSet("stream_languages", value).apply()

    fun isCategoryBlocked(categoryName: String?): Boolean {
        if (categoryName == null) return false
        return blockedCategories.contains(categoryName)
    }

    fun toggleCategoryBlock(categoryName: String) {
        val current = blockedCategories.toMutableSet()
        if (current.contains(categoryName)) {
            current.remove(categoryName)
        } else {
            current.add(categoryName)
        }
        blockedCategories = current
    }

    fun addBlockedCategory(categoryName: String) {
        val current = blockedCategories.toMutableSet()
        current.add(categoryName)
        blockedCategories = current
    }

    fun removeBlockedCategory(categoryName: String) {
        val current = blockedCategories.toMutableSet()
        current.remove(categoryName)
        blockedCategories = current
    }

    var animationsEnabled: Boolean
        get() = prefs.getBoolean("animations_enabled", true)
        set(value) = prefs.edit().putBoolean("animations_enabled", value).apply()

    var autoRefreshInterval: Int
        get() = prefs.getInt("auto_refresh_interval", 5)
        set(value) = prefs.edit().putInt("auto_refresh_interval", value).apply()

    var lastListMode: String
        get() = prefs.getString("last_list_mode", "GLOBAL") ?: "GLOBAL"
        set(value) = prefs.edit().putString("last_list_mode", value).apply()

    var zapDelay: Int
        get() = prefs.getInt("zap_delay", 700)
        set(value) = prefs.edit().putInt("zap_delay", value).apply()

    var blerpEnabled: Boolean
        get() = prefs.getBoolean("blerp_enabled", false)
        set(value) = prefs.edit().putBoolean("blerp_enabled", value).apply()

    var globalSortMode: String
        get() = prefs.getString("global_sort_mode", "viewer_count_desc") ?: "viewer_count_desc"
        set(value) = prefs.edit().putString("global_sort_mode", value).apply()
    
    var playerEngine: String
        get() = prefs.getString("player_engine", "amazon_ivs") ?: "amazon_ivs"
        set(value) = prefs.edit().putString("player_engine", value).apply()

    var mobileQualityLimit: String
        get() = prefs.getString("mobile_quality_limit", "none") ?: "none"
        set(value) = prefs.edit().putString("mobile_quality_limit", value).apply()

    var qualityLimit: Int
        get() = prefs.getInt("quality_limit", -1)
        set(value) = prefs.edit().putInt("quality_limit", value).apply()

    var dynamicQualityEnabled: Boolean
        get() = prefs.getBoolean("dynamic_quality_enabled", true)
        set(value) = prefs.edit().putBoolean("dynamic_quality_enabled", value).apply()

    var mobileLayoutMode: String
        get() = prefs.getString("mobile_layout_mode", "list") ?: "list"
        set(value) = prefs.edit().putString("mobile_layout_mode", value).apply()

    var uiMode: String
        get() = prefs.getString("ui_mode", "auto") ?: "auto"
        set(value) = prefs.edit().putString("ui_mode", value).apply()

    var catchUpMode: String
        get() = prefs.getString("catch_up_mode", "low") ?: "low"
        set(value) = prefs.edit().putString("catch_up_mode", value).apply()

    var backgroundAudioEnabled: Boolean
        get() = prefs.getBoolean("background_audio_enabled", true)
        set(value) = prefs.edit().putBoolean("background_audio_enabled", value).apply()

    var noiseReductionEnabled: Boolean
        get() = prefs.getBoolean("noise_reduction_enabled", false)
        set(value) = prefs.edit().putBoolean("noise_reduction_enabled", value).apply()
        
    // Custom EQ Settings (Gain in dB)
    var eqBassGain: Float
        get() = prefs.getFloat("eq_bass_gain", 0f)
        set(value) = prefs.edit().putFloat("eq_bass_gain", value).apply()
        
    var eqLowMidGain: Float
        get() = prefs.getFloat("eq_low_mid_gain", 0f)
        set(value) = prefs.edit().putFloat("eq_low_mid_gain", value).apply()
        
    var eqMidGain: Float
        get() = prefs.getFloat("eq_mid_gain", 0f)
        set(value) = prefs.edit().putFloat("eq_mid_gain", value).apply()
        
    var eqHighMidGain: Float
        get() = prefs.getFloat("eq_high_mid_gain", 0f)
        set(value) = prefs.edit().putFloat("eq_high_mid_gain", value).apply()
        
    var eqTrebleGain: Float
        get() = prefs.getFloat("eq_treble_gain", 0f)
        set(value) = prefs.edit().putFloat("eq_treble_gain", value).apply()
        
    var eqPreAmpGain: Float
        get() = prefs.getFloat("eq_pre_amp_gain", 0f)
        set(value) = prefs.edit().putFloat("eq_pre_amp_gain", value).apply()

    var isVirtualizerEnabled: Boolean
        get() = prefs.getBoolean("is_virtualizer_enabled", false)
        set(value) = prefs.edit().putBoolean("is_virtualizer_enabled", value).apply()

    var virtualizerStrength: Int
        get() = prefs.getInt("virtualizer_strength", 1000)
        set(value) = prefs.edit().putInt("virtualizer_strength", value).apply()

    var reverbPreset: Int
        get() = prefs.getInt("reverb_preset", 0) // 0 = None
        set(value) = prefs.edit().putInt("reverb_preset", value).apply()

    var autoPipEnabled: Boolean
        get() = prefs.getBoolean("auto_pip_enabled", true)
        set(value) = prefs.edit().putBoolean("auto_pip_enabled", value).apply()

    var autoUpdateEnabled: Boolean
        get() = prefs.getBoolean("auto_update_enabled", true)
        set(value) = prefs.edit().putBoolean("auto_update_enabled", value).apply()

    var chatShowTimestamps: Boolean
        get() = prefs.getBoolean("chat_show_timestamps", false)
        set(value) = prefs.edit().putBoolean("chat_show_timestamps", value).apply()

    var chatShowSeconds: Boolean
        get() = prefs.getBoolean("chat_show_seconds", false)
        set(value) = prefs.edit().putBoolean("chat_show_seconds", value).apply()

    var chatTextSize: Float
        get() = prefs.getFloat("chat_text_size", 14f)
        set(value) = prefs.edit().putFloat("chat_text_size", value).apply()

    var chatEmoteSize: Float
        get() = prefs.getFloat("chat_emote_size", 1.8f)
        set(value) = prefs.edit().putFloat("chat_emote_size", value).apply()

    var chatMessageAnimation: String
        get() = prefs.getString("chat_message_animation", "none") ?: "none"
        set(value) = prefs.edit().putString("chat_message_animation", value).apply()
    
    // ==================== Auth ====================
    
    var authToken: String?
        get() = getStringSafe("auth_token", null)
        set(value) = putStringSafe("auth_token", value)
    
    var username: String?
        get() = getStringSafe("logged_in_username", null)
        set(value) = putStringSafe("logged_in_username", value)
    
    var profilePic: String?
        get() = getStringSafe("logged_in_profile_pic", null)
        set(value) = putStringSafe("logged_in_profile_pic", value)

    var userId: Long
        get() = prefs.getLong("logged_in_user_id", 0L)
        set(value) = prefs.edit().putLong("logged_in_user_id", value).apply()
    
    val isLoggedIn: Boolean
        get() = authToken != null
    
    var userSlug: String?
        get() = getStringSafe("logged_in_slug", null)
        set(value) = putStringSafe("logged_in_slug", value)

    var chatColor: String?
        get() = getStringSafe("logged_in_chat_color", null)
        set(value) = putStringSafe("logged_in_chat_color", value)

    fun saveAuth(token: String, user: String, pic: String?, id: Long? = null, slug: String? = null, color: String? = null) {
        putStringSafe("auth_token", token)
        putStringSafe("logged_in_username", user)
        putStringSafe("logged_in_profile_pic", pic)
        prefs.edit().putLong("logged_in_user_id", id ?: 0L).apply()
        putStringSafe("logged_in_slug", slug)
        putStringSafe("logged_in_chat_color", color)
    }
    
    fun clearAuth() {
        prefs.edit()
            .remove("auth_token")
            .remove("logged_in_username")
            .remove("logged_in_profile_pic")
            .remove("logged_in_user_id")
            .remove("logged_in_chat_color")
            .remove("saved_cookies") 
            .remove("xsrf_token")
            .remove("user_json")
            .apply()
    }
    
    var xsrfToken: String?
        get() = getStringSafe("xsrf_token", null)
        set(value) = putStringSafe("xsrf_token", value)

    var userJson: String?
        get() = getStringSafe("user_json", null)
        set(value) = putStringSafe("user_json", value)

    // Alias for savedCookies to match helper usage
    var cookies: String?
        get() = savedCookies
        set(value) { savedCookies = value }
    
    // ==================== Cookie Storage for WebView ====================
    
    /**
     * Saves all cookies from WebView as a single string (cookies separated by "|||")
     */
    var savedCookies: String?
        get() = getStringSafe("saved_cookies", null)
        set(value) = putStringSafe("saved_cookies", value)
        
    /**
     * Saves cookies specifically for Blerp domains
     */
    var savedBlerpCookies: String?
        get() = getStringSafe("saved_blerp_cookies", null)
        set(value) = putStringSafe("saved_blerp_cookies", value)
    
    /**
     * Extracts session_token from saved cookies and returns it
     */
    fun getSessionTokenFromCookies(): String? {
        val cookies = savedCookies ?: return null
        // Cookies are stored as "name=value; ..." format, separated by "|:|"
        val cookieList = cookies.split("|:|")
        for (cookie in cookieList) {
            if (cookie.contains("session_token=")) {
                // Extract the value
                val parts = cookie.split(";")
                for (part in parts) {
                    val trimmed = part.trim()
                    if (trimmed.startsWith("session_token=")) {
                        return trimmed.substring("session_token=".length)
                    }
                }
            }
        }
        return null
    }
    
    /**
     * Saves cookies for a specific domain
     * @param cookieString The full cookie string from CookieManager.getCookie()
     */
    fun appendCookies(@Suppress("UNUSED_PARAMETER") domain: String, cookieString: String?) {
        if (cookieString.isNullOrEmpty()) return
        val current = savedCookies ?: ""
        val newCookies = if (current.isEmpty()) cookieString else "$current|:|$cookieString"
        savedCookies = newCookies
    }

    var lastKnownLiveChannelIds: Set<String>
        get() = prefs.getStringSet("last_known_live_channels", emptySet()) ?: emptySet()
        set(value) = prefs.edit().putStringSet("last_known_live_channels", value).apply()

    var recentEmoteIds: String?
        get() = prefs.getString("recent_emote_ids", null)
        set(value) = prefs.edit().putString("recent_emote_ids", value).apply()

    var lastWatchedChannelSlug: String?
        get() = prefs.getString("last_watched_channel_slug", null)
        set(value) = prefs.edit().putString("last_watched_channel_slug", value).apply()

    // ==================== Chat Highlight Settings ====================
    
    var highlightOwnMessages: Boolean
        get() = prefs.getBoolean("highlight_own_messages", true)
        set(value) = prefs.edit().putBoolean("highlight_own_messages", value).apply()

    var highlightMentions: Boolean
        get() = prefs.getBoolean("highlight_mentions", true)
        set(value) = prefs.edit().putBoolean("highlight_mentions", value).apply()

    var highlightMods: Boolean
        get() = prefs.getBoolean("highlight_mods", true)
        set(value) = prefs.edit().putBoolean("highlight_mods", value).apply()

    var highlightVips: Boolean
        get() = prefs.getBoolean("highlight_vips", true)
        set(value) = prefs.edit().putBoolean("highlight_vips", value).apply()

    var chatUseNameColorForHighlight: Boolean
        get() = prefs.getBoolean("chat_use_name_color_for_highlight", false)
        set(value) = prefs.edit().putBoolean("chat_use_name_color_for_highlight", value).apply()

    var vibrateOnMentions: Boolean
        get() = prefs.getBoolean("vibrate_on_mentions", true)
        set(value) = prefs.edit().putBoolean("vibrate_on_mentions", value).apply()

    var showPinnedGifts: Boolean
        get() = prefs.getBoolean("show_pinned_gifts", true)
        set(value) = prefs.edit().putBoolean("show_pinned_gifts", value).apply()

    var emoteComboEnabled: Boolean
        get() = prefs.getBoolean("emote_combo_enabled", false)
        set(value) = prefs.edit().putBoolean("emote_combo_enabled", value).apply()

    var floatingEmotesEnabled: Boolean
        get() = prefs.getBoolean("floating_emotes_enabled", false)
        set(value) = prefs.edit().putBoolean("floating_emotes_enabled", value).apply()

    var lowBatteryModeEnabled: Boolean
        get() = prefs.getBoolean("low_battery_mode_enabled", false)
        set(value) = prefs.edit().putBoolean("low_battery_mode_enabled", value).apply()

    var lastSeenWhatsNewVersion: String
        get() = prefs.getString("last_seen_whats_new_version", "") ?: ""
        set(value) = prefs.edit().putString("last_seen_whats_new_version", value).apply()

    // Analytics (Privacy-focused - users can opt-out)
    var analyticsEnabled: Boolean
        get() = prefs.getBoolean("analytics_enabled", true)
        set(value) = prefs.edit().putBoolean("analytics_enabled", value).apply()

    var chatRefreshRate: Long
        get() = prefs.getLong("chat_refresh_rate", 200L)
        set(value) = prefs.edit().putLong("chat_refresh_rate", value).apply()

    var recentCategoriesRaw: String?
        get() = prefs.getString("recent_categories", null)
        set(value) = prefs.edit().putString("recent_categories", value).apply()

    fun getRecentCategories(): List<String> {
        val raw = recentCategoriesRaw ?: return emptyList()
        return raw.split(",").filter { it.isNotBlank() }
    }

    fun addRecentCategory(categoryName: String) {
        val current = getRecentCategories().toMutableList()
        current.remove(categoryName) // Remove if exists to move to top
        current.add(0, categoryName)
        
        // Keep only top 10
        val limited = if (current.size > 10) current.take(10) else current
        recentCategoriesRaw = limited.joinToString(",")
    }

    var searchHistoryRaw: String?
        get() = prefs.getString("search_history", null)
        set(value) = prefs.edit().putString("search_history", value).apply()

    fun getSearchHistory(): List<String> {
        val raw = searchHistoryRaw ?: return emptyList()
        return raw.split("|||").filter { it.isNotBlank() }
    }

    fun addSearchHistory(query: String) {
        if (query.isBlank()) return
        val current = getSearchHistory().toMutableList()
        current.remove(query)
        current.add(0, query)
        val limited = if (current.size > 15) current.take(15) else current
        searchHistoryRaw = limited.joinToString("|||")
    }

    fun clearSearchHistory() {
        searchHistoryRaw = null
    }

    private val gson = Gson()

    data class HistoryEntry(
        val type: String, // "query", "channel", "category"
        val query: String? = null,
        val channelItem: dev.xacnio.kciktv.shared.data.model.SearchResultItem.ChannelResult? = null,
        val categoryItem: dev.xacnio.kciktv.shared.data.model.SearchResultItem.CategoryResult? = null,
        val timestamp: Long = System.currentTimeMillis()
    )

    fun getSearchHistoryItems(): List<HistoryEntry> {
        val raw = searchHistoryRaw ?: return emptyList()
        return try {
            val type = object : TypeToken<List<HistoryEntry>>() {}.type
            gson.fromJson(raw, type)
        } catch (e: Exception) {
            emptyList()
        }
    }

    fun addSearchHistoryEntry(entry: HistoryEntry) {
        val current = getSearchHistoryItems().toMutableList()
        
        // Remove duplicates of same content
        current.removeAll { 
            when (entry.type) {
                "query" -> it.type == "query" && it.query == entry.query
                "channel" -> it.type == "channel" && it.channelItem?.slug == entry.channelItem?.slug
                "category" -> it.type == "category" && it.categoryItem?.slug == entry.categoryItem?.slug
                else -> false
            }
        }
        
        current.add(0, entry)
        val limited = if (current.size > 20) current.take(20) else current
        searchHistoryRaw = gson.toJson(limited)
    }
    fun getChannelRecentEmoteIds(channelId: Long): String? {
        return prefs.getString("recent_emote_ids_$channelId", null)
    }

    fun setChannelRecentEmoteIds(channelId: Long, value: String) {
        prefs.edit().putString("recent_emote_ids_$channelId", value).apply()
    }

    fun getAcceptedRuleHash(slug: String): String? {
        return prefs.getString("chat_rules_accepted_$slug", null)
    }

    fun setAcceptedRuleHash(slug: String, hash: String) {
        prefs.edit().putString("chat_rules_accepted_$slug", hash).apply()
    }

    // ==================== VOD Watch History ====================

    data class VodWatchState(
        val videoId: String,
        val videoTitle: String?,
        val thumbnailUrl: String?,
        val duration: Long,
        val watchedDuration: Long,
        val sourceUrl: String?,
        val channelName: String?,
        val channelSlug: String?,
        val profilePic: String?,
        val categoryName: String? = null,
        val categorySlug: String? = null,
        val channelId: String? = null,
        val lastWatchedTimestamp: Long = System.currentTimeMillis()
    )

    var vodWatchHistoryRaw: String?
        get() = prefs.getString("vod_watch_history", null)
        set(value) = prefs.edit().putString("vod_watch_history", value).apply()

    fun getVodWatchHistory(): List<VodWatchState> {
        val raw = vodWatchHistoryRaw ?: return emptyList()
        return try {
            val type = object : TypeToken<List<VodWatchState>>() {}.type
            gson.fromJson(raw, type)
        } catch (e: Exception) {
            emptyList()
        }
    }

    fun saveVodProgress(state: VodWatchState) {
        val current = getVodWatchHistory().toMutableList()
        
        // Remove existing entry for same video
        current.removeAll { it.videoId == state.videoId }
        
        // Add new state to top
        current.add(0, state)
        
        // Limit history size (e.g. 50 items)
        val limited = if (current.size > 50) current.take(50) else current
        
        vodWatchHistoryRaw = gson.toJson(limited)
    }

    fun getVodProgress(videoId: String): VodWatchState? {
        return getVodWatchHistory().find { it.videoId == videoId }
    }

    fun clearVodWatchHistory() {
        prefs.edit().remove("vod_watch_history").apply()
    }

    fun removeVodProgress(videoId: String) {
        val current = getVodWatchHistory().toMutableList()
        val removed = current.removeAll { it.videoId == videoId }
        if (removed) {
            vodWatchHistoryRaw = gson.toJson(current)
        }
    }

    // === Trusted Domains for Link Previews ===

    private val defaultTrustedDomains: Set<String>
        get() = dev.xacnio.kciktv.shared.util.Constants.Urls.TRUSTED_DOMAINS.toSet()

    var trustedDomains: Set<String>
        get() {
            val stored = prefs.getStringSet("trusted_domains", null)
            return stored ?: defaultTrustedDomains
        }
        set(value) = prefs.edit().putStringSet("trusted_domains", value).apply()

    fun addTrustedDomain(domain: String) {
        val current = trustedDomains.toMutableSet()
        current.add(domain.lowercase().trim())
        trustedDomains = current
    }

    fun removeTrustedDomain(domain: String) {
        val current = trustedDomains.toMutableSet()
        current.remove(domain.lowercase().trim())
        trustedDomains = current
    }

    fun resetTrustedDomains() {
        prefs.edit().remove("trusted_domains").apply()
    }
}
