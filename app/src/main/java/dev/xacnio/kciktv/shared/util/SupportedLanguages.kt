/**
 * File: SupportedLanguages.kt
 *
 * Description: Implementation of Supported Languages functionality.
 *
 * Author: Xacnio
 *
 */
package dev.xacnio.kciktv.shared.util

import android.content.Context
import java.util.Locale

/**
 * Manages supported languages for the app.
 * Languages are dynamically detected based on available string resources.
 */
object SupportedLanguages {
    
    /**
     * Data class representing a supported language.
     */
    data class Language(
        val code: String,          // e.g., "en", "tr", "de"
        val displayName: String,   // e.g., "English", "Türkçe", "Deutsch"
        val nativeName: String     // Native name in that language
    )
    
    /**
     * List of all supported languages in the app.
     * Add new languages here when creating new values-XX folders.
     * 
     * Format: Language(code, englishName, nativeName)
     */
    private val SUPPORTED_LANGUAGES = listOf(
        Language("en", "English", "English"),
        Language("tr", "Turkish", "Türkçe"),
        Language("es", "Spanish", "Español"),
        Language("fr", "French", "Français"),
        Language("ar", "Arabic", "العربية"),
        Language("de", "German", "Deutsch")
        // Add more languages here as they are added to the project:
        // Language("ru", "Russian", "Русский"),
        // Language("ja", "Japanese", "日本語"),
        // Language("ko", "Korean", "한국어"),
        // Language("zh", "Chinese", "中文"),
        // Language("pt", "Portuguese", "Português"),
    )
    
    /**
     * Get all supported languages.
     */
    fun getAll(): List<Language> = SUPPORTED_LANGUAGES
    
    /**
     * Get a language by its code.
     */
    fun getByCode(code: String): Language? = SUPPORTED_LANGUAGES.find { it.code == code }
    
    /**
     * Get display name for a language code.
     * Returns the native name (e.g., "Türkçe" for "tr").
     */
    fun getDisplayName(code: String): String {
        return getByCode(code)?.nativeName ?: code.uppercase()
    }
    
    /**
     * Get the current device locale's language code.
     */
    fun getDeviceLanguageCode(): String {
        return Locale.getDefault().language
    }
    
    /**
     * Check if a language is supported.
     */
    fun isSupported(code: String): Boolean {
        return SUPPORTED_LANGUAGES.any { it.code == code }
    }
    
    /**
     * Get display names array for dialog (includes "System Default" as first option).
     */
    fun getDisplayNamesForDialog(@Suppress("UNUSED_PARAMETER") context: Context, systemDefaultLabel: String): Array<String> {
        val names = mutableListOf(systemDefaultLabel)
        names.addAll(SUPPORTED_LANGUAGES.map { it.nativeName })
        return names.toTypedArray()
    }
    
    /**
     * Get locale codes array for dialog (includes "system" as first option).
     */
    fun getCodesForDialog(): Array<String> {
        val codes = mutableListOf("system")
        codes.addAll(SUPPORTED_LANGUAGES.map { it.code })
        return codes.toTypedArray()
    }
    
    /**
     * Get the index of a language code in the dialog list.
     * Returns 0 (system default) if not found.
     */
    fun getDialogIndex(code: String?): Int {
        if (code == null || code == "system") return 0
        val index = SUPPORTED_LANGUAGES.indexOfFirst { it.code == code }
        return if (index >= 0) index + 1 else 0 // +1 because "system" is at index 0
    }
}
