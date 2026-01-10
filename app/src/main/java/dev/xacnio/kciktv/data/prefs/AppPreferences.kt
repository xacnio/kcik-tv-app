package dev.xacnio.kciktv.data.prefs

import android.content.Context
import android.content.SharedPreferences

class AppPreferences(private val context: Context) {
    private val prefs: SharedPreferences = context.getSharedPreferences("kick_tv_prefs", Context.MODE_PRIVATE)

    var infoDelay: Int
        get() = prefs.getInt("info_delay", 5)
        set(value) = prefs.edit().putInt("info_delay", value).apply()

    var infoTransparency: Int
        get() = prefs.getInt("info_transparency", 80)
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
        get() {
            val saved = languageRaw
            if (saved == "system") {
                val systemLocale = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.N) {
                    android.content.res.Resources.getSystem().configuration.locales[0]
                } else {
                    @Suppress("DEPRECATION")
                    android.content.res.Resources.getSystem().configuration.locale
                }
                val localeCode = systemLocale.language
                return if (localeCode == "tr") "tr" else "en"
            }
            return saved
        }
        set(value) = prefs.edit().putString("language", value).apply()

    val languageRaw: String
        get() = prefs.getString("language", "system") ?: "system"

    var themeColor: Int
        get() = prefs.getInt("theme_color", 0xFF00F2FF.toInt()) // Default KcikTV Cyan
        set(value) = prefs.edit().putInt("theme_color", value).apply()

    var blockedCategories: Set<String>
        get() = prefs.getStringSet("blocked_categories", setOf("Pools, Hot Tubs & Beaches")) ?: setOf("Pools, Hot Tubs & Beaches")
        set(value) = prefs.edit().putStringSet("blocked_categories", value).apply()

    var streamLanguages: Set<String>
        get() = prefs.getStringSet("stream_languages", emptySet()) ?: emptySet()
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

    var globalSortMode: String
        get() = prefs.getString("global_sort_mode", "viewer_count_desc") ?: "viewer_count_desc"
        set(value) = prefs.edit().putString("global_sort_mode", value).apply()
    
    var playerEngine: String
        get() = prefs.getString("player_engine", "amazon_ivs") ?: "amazon_ivs"
        set(value) = prefs.edit().putString("player_engine", value).apply()

    var mobileQualityLimit: String
        get() = prefs.getString("mobile_quality_limit", "none") ?: "none"
        set(value) = prefs.edit().putString("mobile_quality_limit", value).apply()

    var catchUpMode: String
        get() = prefs.getString("catch_up_mode", "low") ?: "low"
        set(value) = prefs.edit().putString("catch_up_mode", value).apply()

    var backgroundAudioEnabled: Boolean
        get() = prefs.getBoolean("background_audio_enabled", true)
        set(value) = prefs.edit().putBoolean("background_audio_enabled", value).apply()

    var autoPipEnabled: Boolean
        get() = prefs.getBoolean("auto_pip_enabled", true)
        set(value) = prefs.edit().putBoolean("auto_pip_enabled", value).apply()

    var autoUpdateEnabled: Boolean
        get() = prefs.getBoolean("auto_update_enabled", true)
        set(value) = prefs.edit().putBoolean("auto_update_enabled", value).apply()
    
    // ==================== Auth ====================
    
    var authToken: String?
        get() = prefs.getString("auth_token", null)
        set(value) = prefs.edit().putString("auth_token", value).apply()
    
    var username: String?
        get() = prefs.getString("logged_in_username", null)
        set(value) = prefs.edit().putString("logged_in_username", value).apply()
    
    var profilePic: String?
        get() = prefs.getString("logged_in_profile_pic", null)
        set(value) = prefs.edit().putString("logged_in_profile_pic", value).apply()
    
    val isLoggedIn: Boolean
        get() = authToken != null
    
    fun saveAuth(token: String, user: String, pic: String?) {
        prefs.edit()
            .putString("auth_token", token)
            .putString("logged_in_username", user)
            .putString("logged_in_profile_pic", pic)
            .apply()
    }
    
    fun clearAuth() {
        prefs.edit()
            .remove("auth_token")
            .remove("logged_in_username")
            .remove("logged_in_profile_pic")
            .apply()
    }
}
