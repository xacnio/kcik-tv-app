/**
 * File: LanguageFilterManager.kt
 *
 * Description: Manages business logic, state, and UI interactions for Language Filter.
 *
 * Author: Xacnio
 *
 */
package dev.xacnio.kciktv.mobile.ui.filter

import android.os.Build
import androidx.appcompat.app.AlertDialog
import dev.xacnio.kciktv.mobile.MobilePlayerActivity
import dev.xacnio.kciktv.R

/**
 * Manages language filter functionality for stream browsing.
 */
class LanguageFilterManager(private val activity: MobilePlayerActivity) {

    private val prefs get() = activity.prefs
    private val binding get() = activity.binding

    /**
     * Shows the language filter dialog for selecting stream languages.
     */
    fun showLanguageFilterDialog() {
        val codes = activity.resources.getStringArray(R.array.stream_language_codes)
        val names = activity.resources.getStringArray(R.array.stream_language_names)

        val currentApiLangs = prefs.streamLanguages
        val tempSelected = currentApiLangs.toMutableSet()
        val checkedItems = codes.map { currentApiLangs.contains(it) }.toBooleanArray()

        AlertDialog.Builder(activity)
            .setTitle(R.string.stream_language)
            .setMultiChoiceItems(names, checkedItems) { _, which, isChecked ->
                if (isChecked) {
                    tempSelected.add(codes[which])
                } else {
                    tempSelected.remove(codes[which])
                }
            }
            .setPositiveButton(R.string.yes) { _, _ ->
                prefs.streamLanguages = tempSelected
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    /**
     * Updates the language filter button text based on selected languages.
     */
    /**
     * Converts ISO language code OR English language name to localized language name.
     * The API may return the language as ISO code (e.g., "tr") or English name (e.g., "Turkish").
     * Uses the localized string arrays from strings.xml for proper translations.
     */
    fun getLocalizedLanguageName(languageInput: String?): String {
        if (languageInput.isNullOrBlank()) return ""
        
        return try {
            val codes = activity.resources.getStringArray(R.array.stream_language_codes)
            val localizedNames = activity.resources.getStringArray(R.array.stream_language_names)
            
            // First, try to find by ISO code (e.g., "tr", "en")
            val lowerInput = languageInput.lowercase()
            var index = codes.indexOf(lowerInput)
            
            // If not found as ISO code, the input might be an English language name (e.g., "Turkish")
            // We need to get English names to compare against
            if (index < 0) {
                // Get English language names for comparison
                val englishNames = getEnglishLanguageNames()
                
                // Find the index by comparing with English names (case-insensitive)
                index = englishNames.indexOfFirst { it.equals(languageInput, ignoreCase = true) }
            }
            
            if (index >= 0 && index < localizedNames.size) {
                localizedNames[index]
            } else {
                // Fallback to Locale if code not in array
                val locale = java.util.Locale(languageInput)
                val appLang = prefs.languageRaw
                val currentLocale = if (!appLang.isNullOrEmpty() && appLang != "system") {
                    java.util.Locale(appLang)
                } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                    @Suppress("NewApi")
                    activity.resources.configuration.locales[0]
                } else {
                    @Suppress("DEPRECATION")
                    activity.resources.configuration.locale
                }
                locale.getDisplayLanguage(currentLocale).replaceFirstChar {
                    if (it.isLowerCase()) it.titlecase(currentLocale) else it.toString()
                }
            }
        } catch (_: Exception) {
            languageInput.uppercase()
        }
    }
    
    /**
     * Returns the English language names array.
     * This is needed to convert English names from API to ISO codes.
     */
    private fun getEnglishLanguageNames(): Array<String> {
        // These are the English names matching stream_language_codes order
        return arrayOf(
            "Turkish", "English", "Afrikaans", "German", "Arabic", "Albanian", "Azerbaijani", "Belarusian",
            "Bengali", "Bosnian", "Bulgarian", "Czech", "Chinese", "Danish", "Indonesian", "Esperanto",
            "Estonian", "Faroese", "Persian", "Dutch", "Filipino", "Finnish", "French", "Georgian",
            "Hawaiian", "Croatian", "Hindi", "Hebrew", "Interlingua", "Spanish", "Swedish", "Italian",
            "Japanese", "Catalan", "Korean", "Latin", "Polish", "Latvian", "Lithuanian", "Hungarian",
            "Macedonian", "Malayalam", "Malay", "Mongolian", "Norwegian", "Norwegian Bokmål", "Portuguese", "Romanian",
            "Moldavian", "Russian", "Serbian", "Slovak", "Slovenian", "Somali", "Sundanese", "Tatar",
            "Thai", "Ukrainian", "Urdu", "Vietnamese", "Greek", "Zulu"
        )
    }

    /**
     * Gets the display name for a language code.
     * Uses the system locale to show language names in the user's app language.
     */
    fun getLanguageName(code: String?): String {
        if (code.isNullOrEmpty()) return ""
        return getLocalizedLanguageName(code)
    }
}
