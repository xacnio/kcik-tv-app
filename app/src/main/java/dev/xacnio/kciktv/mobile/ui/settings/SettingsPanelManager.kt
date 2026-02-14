/**
 * File: SettingsPanelManager.kt
 *
 * Description: Manages business logic, state, and UI interactions for Settings Panel.
 *
 * Author: Xacnio
 *
 */
package dev.xacnio.kciktv.mobile.ui.settings

import android.content.Intent
import android.os.Build
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.core.content.FileProvider
import androidx.core.net.toUri
import androidx.lifecycle.lifecycleScope
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog
import dev.xacnio.kciktv.BuildConfig
import dev.xacnio.kciktv.mobile.MobilePlayerActivity
import dev.xacnio.kciktv.tv.PlayerActivity
import dev.xacnio.kciktv.R
import dev.xacnio.kciktv.shared.data.prefs.AppPreferences
import dev.xacnio.kciktv.shared.data.repository.UpdateRepository
import dev.xacnio.kciktv.databinding.ActivityMobilePlayerBinding
import dev.xacnio.kciktv.shared.util.SupportedLanguages
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import dev.xacnio.kciktv.mobile.InternalBrowserSheet
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream

/**
 * Manages the settings panel UI as a bottom sheet with category navigation.
 */
class SettingsPanelManager(
    private val activity: MobilePlayerActivity,
    private val binding: ActivityMobilePlayerBinding,
    private val prefs: AppPreferences,
    private val updateRepository: UpdateRepository
) {
    var isSettingsVisible = false
        private set
    
    private var currentDialog: BottomSheetDialog? = null
    private var detailDialog: BottomSheetDialog? = null

    /**
     * Shows the settings bottom sheet with categories.
     */
    fun showSettingsPanel() {
        val dialog = BottomSheetDialog(activity, R.style.BottomSheetDialogTheme)
        currentDialog = dialog
        
        val view = LayoutInflater.from(activity).inflate(R.layout.bottom_sheet_settings_categories, null)
        dialog.setContentView(view)
        
        // Configure bottom sheet behavior
        val bottomSheet = dialog.findViewById<View>(com.google.android.material.R.id.design_bottom_sheet)
        bottomSheet?.let {
            val behavior = BottomSheetBehavior.from(it)
            behavior.state = BottomSheetBehavior.STATE_EXPANDED
            behavior.skipCollapsed = true
            it.setBackgroundResource(R.drawable.bg_bottom_sheet)
        }
        
        // Setup category click listeners
        view.findViewById<View>(R.id.categoryPlayer)?.setOnClickListener {
            showPlayerSettings()
        }
        
        view.findViewById<View>(R.id.categoryAppearance)?.setOnClickListener {
            showAppearanceSettings()
        }
        
        view.findViewById<View>(R.id.categoryThirdParty)?.setOnClickListener {
            showThirdPartySettings()
        }
        
        view.findViewById<View>(R.id.categoryApp)?.setOnClickListener {
            showAppSettings()
        }

        view.findViewById<View>(R.id.categoryAdvanced)?.setOnClickListener {
            showAdvancedSettings()
        }
        
        dialog.setOnDismissListener {
            isSettingsVisible = false
            currentDialog = null
        }
        
        dialog.show()
        isSettingsVisible = true
    }
    
    /**
     * Hides the settings panel.
     */
    fun hideSettingsPanel() {
        currentDialog?.dismiss()
        detailDialog?.dismiss()
        isSettingsVisible = false
    }
    
    // === Category Detail Screens ===
    
    private fun showPlayerSettings() {
        showDetailSheet(activity.getString(R.string.settings_category_player)) { container ->
            // Quality Setting
            addSettingItem(container, R.drawable.ic_hd, 
                activity.getString(R.string.setting_mobile_quality),
                getQualityLimitValue()) {
                showQualityLimitDialog()
            }
            
            // Dynamic Quality Toggle
            addToggleSetting(container, R.drawable.ic_settings,
                activity.getString(R.string.setting_dynamic_quality),
                activity.getString(R.string.dynamic_quality_desc),
                prefs.dynamicQualityEnabled) { isEnabled ->
                prefs.dynamicQualityEnabled = isEnabled
            }
            
            // Background Audio Toggle
            addToggleSetting(container, R.drawable.ic_headphones,
                activity.getString(R.string.setting_background_audio),
                if (prefs.backgroundAudioEnabled) activity.getString(R.string.on) else activity.getString(R.string.off),
                prefs.backgroundAudioEnabled) { isEnabled ->
                prefs.backgroundAudioEnabled = isEnabled
            }
        }
    }
    
    private fun showAppearanceSettings() {
        showDetailSheet(activity.getString(R.string.settings_category_appearance)) { container ->
            // App Language
            addSettingItem(container, R.drawable.ic_language,
                activity.getString(R.string.setting_app_language),
                getAppLanguageValue()) {
                showAppLanguageDialog()
            }
        }
    }
    
    private fun showThirdPartySettings() {
        showDetailSheet(activity.getString(R.string.settings_category_third_party)) { container ->
            // Blerp Toggle
            addToggleSetting(container, R.drawable.ic_blerp,
                activity.getString(R.string.blerp_title),
                activity.getString(R.string.blerp_desc),
                prefs.blerpEnabled) { isEnabled ->
                prefs.blerpEnabled = isEnabled
                // Update button visibility immediately
                if (isEnabled && !activity.currentBlerpUrl.isNullOrEmpty()) {
                    binding.blerpButton.visibility = View.VISIBLE
                } else {
                    binding.blerpButton.visibility = View.GONE
                }
            }
        }
    }
    
    private fun showAppSettings() {
        showDetailSheet(activity.getString(R.string.settings_category_app)) { container ->
            // Switch to TV UI
            addSettingItem(container, R.drawable.ic_tv,
                activity.getString(R.string.switch_to_tv_ui), null) {
                prefs.uiMode = "tv"
                val intent = Intent(activity, PlayerActivity::class.java)
                activity.startActivity(intent)
                activity.finish()
            }
      
            // Chat Refresh Rate
            addSettingItem(container, R.drawable.ic_timer,
                activity.getString(R.string.setting_chat_refresh_title),
                "${prefs.chatRefreshRate}ms") {
                showChatRefreshDialog()
            }
            
            // Auto-update Toggle
            addToggleSetting(container, R.drawable.ic_update,
                activity.getString(R.string.setting_auto_update),
                activity.getString(R.string.setting_auto_update_desc),
                prefs.autoUpdateEnabled) { isEnabled ->
                prefs.autoUpdateEnabled = isEnabled
            }

            // Update Channel
            val channelSubtitle = if (prefs.updateChannel == "beta") 
                activity.getString(R.string.beta) 
            else 
                activity.getString(R.string.stable)
                
            addSettingItem(container, R.drawable.ic_settings,
                activity.getString(R.string.setting_update_channel),
                channelSubtitle) {
                showUpdateChannelDialog()
            }

            // Check for Updates
            addSettingItem(container, R.drawable.ic_update,
                activity.getString(R.string.check_update), null) {
                checkForUpdates()
            }
            
            // What's New
            addSettingItem(container, null,
                activity.getString(R.string.setting_whats_new), null,
                emojiIcon = "🎉") {
                WhatsNewSheetManager(activity, updateRepository).show()
            }
            
            // Privacy Policy
            addSettingItem(container, R.drawable.ic_security,
                activity.getString(R.string.privacy_policy), null) {
                val policyUrl = "https://github.com/xacnio/kcik-tv-app/blob/main/docs/privacy_policy.md"
                dev.xacnio.kciktv.mobile.InternalBrowserSheet.newInstance(policyUrl).show(activity.supportFragmentManager, "PrivacyPolicy")
                hideSettingsPanel()
            }
            
            // About
            addSettingItem(container, R.drawable.ic_info,
                activity.getString(R.string.setting_about),
                "v${getVersionName()}") {
                activity.showAboutDialog()
            }
        }
    }

    private fun showAdvancedSettings() {
        showDetailSheet(activity.getString(R.string.settings_category_advanced)) { container ->
            // Kick WebView
            addSettingItem(container, null,
                activity.getString(R.string.settings_kick_webview),
                activity.getString(R.string.settings_kick_webview_desc),
                emojiIcon = "🌐") {
                
                val sheet = dev.xacnio.kciktv.mobile.InternalBrowserSheet.newInstance()
                sheet.show(activity.supportFragmentManager, "InternalBrowserSheet")
                hideSettingsPanel()
            }

            // Trusted Domains
            addSettingItem(container, null,
                activity.getString(R.string.settings_trusted_domains),
                activity.getString(R.string.trusted_domains_count, prefs.trustedDomains.size),
                emojiIcon = "🔗") {
                showTrustedDomainsSheet()
            }
            
            // Clear Cache
            addSettingItem(container, null,
                activity.getString(R.string.settings_clear_cache),
                activity.getString(R.string.settings_clear_cache_desc),
                emojiIcon = "🗑️") {
                clearAppCache()
            }
            
            // Clear Blerp Auth
            addSettingItem(container, null,
                activity.getString(R.string.settings_clear_blerp_auth),
                activity.getString(R.string.settings_clear_blerp_auth_desc),
                emojiIcon = "🔐") {
                clearBlerpAuth()
            }
        }
    }

    private fun showTrustedDomainsSheet() {
        showDetailSheet(activity.getString(R.string.settings_trusted_domains)) { container ->
            val domains = prefs.trustedDomains.toSortedSet()

            // Add Domain button
            addSettingItem(container, null,
                activity.getString(R.string.trusted_domain_add_title),
                activity.getString(R.string.settings_trusted_domains_desc),
                emojiIcon = "➕") {
                showAddDomainDialog()
            }

            // Reset to Defaults button
            addSettingItem(container, null,
                activity.getString(R.string.trusted_domains_reset),
                null,
                emojiIcon = "🔄") {
                AlertDialog.Builder(activity)
                    .setTitle(R.string.trusted_domains_reset)
                    .setMessage(R.string.trusted_domains_reset_confirm)
                    .setPositiveButton(R.string.confirm) { dialog, _ ->
                        prefs.resetTrustedDomains()
                        Toast.makeText(activity, R.string.trusted_domains_reset_done, Toast.LENGTH_SHORT).show()
                        dialog.dismiss()
                        // Refresh the sheet
                        detailDialog?.dismiss()
                        showTrustedDomainsSheet()
                    }
                    .setNegativeButton(R.string.cancel, null)
                    .show()
            }

            // Separator-like spacing via a divider view
            val divider = android.view.View(activity).apply {
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    (1 * activity.resources.displayMetrics.density).toInt()
                ).apply {
                    topMargin = (8 * activity.resources.displayMetrics.density).toInt()
                    bottomMargin = (8 * activity.resources.displayMetrics.density).toInt()
                    marginStart = (16 * activity.resources.displayMetrics.density).toInt()
                    marginEnd = (16 * activity.resources.displayMetrics.density).toInt()
                }
                setBackgroundColor(0x33FFFFFF)
            }
            container.addView(divider)

            // List each domain
            for (domain in domains) {
                addSettingItem(container, null,
                    domain,
                    null,
                    emojiIcon = "🌐") {
                    AlertDialog.Builder(activity)
                        .setTitle(R.string.remove)
                        .setMessage(activity.getString(R.string.trusted_domain_remove_confirm, domain))
                        .setPositiveButton(R.string.remove) { dialog, _ ->
                            prefs.removeTrustedDomain(domain)
                            Toast.makeText(
                                activity,
                                activity.getString(R.string.trusted_domain_removed, domain),
                                Toast.LENGTH_SHORT
                            ).show()
                            dialog.dismiss()
                            // Refresh the sheet
                            detailDialog?.dismiss()
                            showTrustedDomainsSheet()
                        }
                        .setNegativeButton(R.string.cancel, null)
                        .show()
                }
            }
        }
    }

    private fun showAddDomainDialog() {
        val inputLayout = android.widget.FrameLayout(activity).apply {
            setPadding(
                (24 * activity.resources.displayMetrics.density).toInt(),
                (16 * activity.resources.displayMetrics.density).toInt(),
                (24 * activity.resources.displayMetrics.density).toInt(),
                0
            )
        }
        val editText = android.widget.EditText(activity).apply {
            hint = activity.getString(R.string.trusted_domain_add_hint)
            inputType = android.text.InputType.TYPE_TEXT_VARIATION_URI
            setSingleLine(true)
        }
        inputLayout.addView(editText)

        AlertDialog.Builder(activity)
            .setTitle(R.string.trusted_domain_add_title)
            .setView(inputLayout)
            .setPositiveButton(R.string.add) { dialog, _ ->
                val domain = editText.text.toString().lowercase().trim()
                    .removePrefix("http://")
                    .removePrefix("https://")
                    .removePrefix("www.")
                    .trimEnd('/')

                if (domain.isBlank() || !domain.contains('.')) {
                    Toast.makeText(activity, R.string.trusted_domain_invalid, Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }

                if (prefs.trustedDomains.contains(domain)) {
                    Toast.makeText(activity, R.string.trusted_domain_exists, Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }

                prefs.addTrustedDomain(domain)
                Toast.makeText(
                    activity,
                    activity.getString(R.string.trusted_domain_added, domain),
                    Toast.LENGTH_SHORT
                ).show()
                dialog.dismiss()
                // Refresh the sheet
                detailDialog?.dismiss()
                showTrustedDomainsSheet()
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }
    
    private fun clearAppCache() {
        try {
            // Clear app cache directory
            activity.cacheDir.deleteRecursively()
            
            // Clear WebView cache
            android.webkit.WebView(activity).apply {
                clearCache(true)
                destroy()
            }
            
            android.widget.Toast.makeText(
                activity,
                activity.getString(R.string.cache_cleared),
                android.widget.Toast.LENGTH_SHORT
            ).show()
        } catch (e: Exception) {
            android.util.Log.e("SettingsPanelManager", "Error clearing cache", e)
        }
    }
    
    private fun clearBlerpAuth() {
        try {
            // Clear Blerp cookies
            val cookieManager = android.webkit.CookieManager.getInstance()
            cookieManager.removeAllCookies { success ->
                android.util.Log.d("SettingsPanelManager", "Cookies cleared: $success")
            }
            cookieManager.flush()
            
            // Dismiss cached Blerp fragment
            activity.cachedBlerpFragment?.let {
                if (it.isAdded) it.dismissAllowingStateLoss()
            }
            activity.cachedBlerpFragment = null
            
            android.widget.Toast.makeText(
                activity,
                activity.getString(R.string.blerp_auth_cleared),
                android.widget.Toast.LENGTH_SHORT
            ).show()
        } catch (e: Exception) {
            android.util.Log.e("SettingsPanelManager", "Error clearing Blerp auth", e)
        }
    }
    
    private fun showDetailSheet(title: String, setupContent: (LinearLayout) -> Unit) {
        val dialog = BottomSheetDialog(activity, R.style.BottomSheetDialogTheme)
        detailDialog = dialog
        
        val view = LayoutInflater.from(activity).inflate(R.layout.bottom_sheet_settings_detail, null)
        dialog.setContentView(view)
        
        // Configure bottom sheet behavior
        val bottomSheet = dialog.findViewById<View>(com.google.android.material.R.id.design_bottom_sheet)
        bottomSheet?.let {
            val behavior = BottomSheetBehavior.from(it)
            behavior.state = BottomSheetBehavior.STATE_EXPANDED
            behavior.skipCollapsed = true
            it.setBackgroundResource(R.drawable.bg_bottom_sheet)
        }
        
        // Set title
        view.findViewById<TextView>(R.id.settingsDetailTitle)?.text = title
        
        // Back button
        view.findViewById<View>(R.id.settingsDetailBackButton)?.setOnClickListener {
            dialog.dismiss()
        }
        
        // Setup content
        val container = view.findViewById<LinearLayout>(R.id.settingsDetailContainer)
        setupContent(container)
        
        dialog.setOnDismissListener {
            detailDialog = null
        }
        
        dialog.show()
    }
    
    // === Helper Methods for Building Settings Items ===
    
    private fun addSettingItem(
        container: LinearLayout,
        iconRes: Int?,
        title: String,
        subtitle: String?,
        emojiIcon: String? = null,
        onClick: () -> Unit
    ) {
        val itemView = LayoutInflater.from(activity).inflate(R.layout.item_settings_row, container, false)
        
        val iconView = itemView.findViewById<android.widget.ImageView>(R.id.settingIcon)
        val emojiView = itemView.findViewById<TextView>(R.id.settingEmojiIcon)
        val titleView = itemView.findViewById<TextView>(R.id.settingTitle)
        val subtitleView = itemView.findViewById<TextView>(R.id.settingSubtitle)
        val chevron = itemView.findViewById<android.widget.ImageView>(R.id.settingChevron)
        
        if (emojiIcon != null) {
            iconView?.visibility = View.GONE
            emojiView?.visibility = View.VISIBLE
            emojiView?.text = emojiIcon
        } else if (iconRes != null) {
            iconView?.setImageResource(iconRes)
            iconView?.visibility = View.VISIBLE
            emojiView?.visibility = View.GONE
        } else {
            iconView?.visibility = View.GONE
            emojiView?.visibility = View.GONE
        }
        
        titleView?.text = title
        
        if (subtitle != null) {
            subtitleView?.text = subtitle
            subtitleView?.visibility = View.VISIBLE
        } else {
            subtitleView?.visibility = View.GONE
        }
        
        chevron?.visibility = View.VISIBLE
        
        itemView.setOnClickListener { onClick() }
        container.addView(itemView)
    }
    
    private fun addToggleSetting(
        container: LinearLayout,
        iconRes: Int,
        title: String,
        subtitle: String,
        initialState: Boolean,
        onToggle: (Boolean) -> Unit
    ) {
        val itemView = LayoutInflater.from(activity).inflate(R.layout.item_settings_toggle, container, false)
        
        val iconView = itemView.findViewById<android.widget.ImageView>(R.id.settingIcon)
        val titleView = itemView.findViewById<TextView>(R.id.settingTitle)
        val subtitleView = itemView.findViewById<TextView>(R.id.settingSubtitle)
        val toggleBg = itemView.findViewById<FrameLayout>(R.id.toggleBackground)
        val toggleThumb = itemView.findViewById<View>(R.id.toggleThumb)
        
        iconView?.setImageResource(iconRes)
        titleView?.text = title
        subtitleView?.text = subtitle
        
        var isEnabled = initialState
        
        fun updateToggleState(animate: Boolean = true) {
            val endX = if (isEnabled) 24f * activity.resources.displayMetrics.density else 0f
            
            if (animate) {
                toggleThumb?.animate()?.translationX(endX)?.setDuration(150)?.start()
            } else {
                toggleThumb?.translationX = endX
            }
            
            if (isEnabled) {
                val onDrawable = android.graphics.drawable.GradientDrawable().apply {
                    shape = android.graphics.drawable.GradientDrawable.RECTANGLE
                    cornerRadius = 14 * activity.resources.displayMetrics.density
                    setColor(prefs.themeColor)
                }
                toggleBg?.background = onDrawable
            } else {
                toggleBg?.setBackgroundResource(R.drawable.bg_toggle_off)
            }
        }
        
        updateToggleState(false)
        
        itemView.setOnClickListener {
            isEnabled = !isEnabled
            updateToggleState()
            onToggle(isEnabled)
        }
        
        container.addView(itemView)
    }
    
    // === Value Getters ===
    
    private fun getQualityLimitValue(): String {
        return when (prefs.mobileQualityLimit) {
            "none" -> activity.getString(R.string.quality_unlimited)
            "1080p" -> "1080p"
            "720p" -> "720p"
            "480p" -> "480p"
            "360p" -> "360p"
            else -> activity.getString(R.string.quality_unlimited)
        }
    }
    
    private fun getAppLanguageValue(): String {
        val savedLangRaw = prefs.languageRaw
        return when (savedLangRaw) {
            "system" -> activity.getString(R.string.system_default)
            else -> SupportedLanguages.getDisplayName(savedLangRaw)
        }
    }
    
    private fun getVersionName(): String {
        return try {
            activity.packageManager.getPackageInfo(activity.packageName, 0).versionName ?: "?"
        } catch (_: Exception) { "?" }
    }
    
    private fun getChatAnimationValue(): String {
        return when (prefs.chatMessageAnimation) {
            "none" -> activity.getString(R.string.animation_none)
            "fade_in" -> activity.getString(R.string.animation_fade_in)
            "slide_left" -> activity.getString(R.string.animation_slide_left)
            "slide_right" -> activity.getString(R.string.animation_slide_right)
            "slide_bottom" -> activity.getString(R.string.animation_slide_bottom)
            "scale" -> activity.getString(R.string.animation_scale)
            "typewriter" -> activity.getString(R.string.animation_typewriter)
            "curtain" -> activity.getString(R.string.animation_curtain)
            "flip" -> activity.getString(R.string.animation_flip)
            else -> activity.getString(R.string.animation_none)
        }
    }
    
    // === Dialogs ===
    
    private fun showQualityLimitDialog() {
        val qualities = arrayOf(activity.getString(R.string.quality_unlimited), "1080p", "720p", "480p", "360p")
        val qualityValues = arrayOf("none", "1080p", "720p", "480p", "360p")
        val currentIndex = qualityValues.indexOf(prefs.mobileQualityLimit).takeIf { it >= 0 } ?: 0
        
        AlertDialog.Builder(activity)
            .setTitle(R.string.setting_mobile_quality)
            .setSingleChoiceItems(qualities, currentIndex) { dialog, which ->
                prefs.mobileQualityLimit = qualityValues[which]
                activity.checkAndApplyQualityLimit()
                dialog.dismiss()
                // Refresh detail sheet
                detailDialog?.dismiss()
                showPlayerSettings()
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }
    
    private fun showAppLanguageDialog() {
        val languages = SupportedLanguages.getDisplayNamesForDialog(
            activity, 
            activity.getString(R.string.system_default)
        )
        val locales = SupportedLanguages.getCodesForDialog()
        val currentIndex = SupportedLanguages.getDialogIndex(prefs.languageRaw)
        
        AlertDialog.Builder(activity)
            .setTitle(R.string.setting_app_language)
            .setSingleChoiceItems(languages, currentIndex) { dialog, which ->
                val selectedLocale = locales[which]
                
                // Save to SharedPreferences
                prefs.language = selectedLocale
                
                val locale = if (selectedLocale == "system") {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                        android.content.res.Resources.getSystem().configuration.locales.get(0)
                    } else {
                        @Suppress("DEPRECATION")
                        android.content.res.Resources.getSystem().configuration.locale
                    }
                } else {
                    java.util.Locale(selectedLocale)
                }
                
                java.util.Locale.setDefault(locale)
                val config = activity.resources.configuration
                config.setLocale(locale)
                @Suppress("DEPRECATION")
                activity.resources.updateConfiguration(config, activity.resources.displayMetrics)
                
                dialog.dismiss()
                
                // Restart activity to apply language change
                activity.recreate()
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }
    
    private fun showUpdateChannelDialog() {
        val options = arrayOf(
            activity.getString(R.string.stable),
            activity.getString(R.string.beta)
        )
        val values = arrayOf("stable", "beta")
        val currentIndex = values.indexOf(prefs.updateChannel).takeIf { it >= 0 } ?: 0

        AlertDialog.Builder(activity)
            .setTitle(R.string.setting_update_channel)
            .setSingleChoiceItems(options, currentIndex) { dialog, which ->
                prefs.updateChannel = values[which]
                dialog.dismiss()
                // Refresh detail sheet
                detailDialog?.dismiss()
                showAppSettings()
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }
    
    private fun showChatRefreshDialog() {
        val options = arrayOf(
            activity.getString(R.string.speed_ultra_fast),
            activity.getString(R.string.speed_fast),
            activity.getString(R.string.speed_normal),
            activity.getString(R.string.speed_slow)
        )
        val values = arrayOf(100L, 200L, 500L, 1000L)
        val currentIndex = values.indexOf(prefs.chatRefreshRate).takeIf { it >= 0 } ?: 1

        AlertDialog.Builder(activity)
            .setTitle(R.string.setting_chat_refresh_title)
            .setSingleChoiceItems(options, currentIndex) { dialog, which ->
                prefs.chatRefreshRate = values[which]
                dialog.dismiss()
                // Refresh detail sheet
                detailDialog?.dismiss()
                showAppSettings()
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }
    
    private fun showChatAnimationDialog() {
        val options = arrayOf(
            activity.getString(R.string.animation_none),
            activity.getString(R.string.animation_fade_in),
            activity.getString(R.string.animation_slide_left),
            activity.getString(R.string.animation_slide_right),
            activity.getString(R.string.animation_slide_bottom),
            activity.getString(R.string.animation_scale),
            activity.getString(R.string.animation_typewriter),
            activity.getString(R.string.animation_curtain),
            activity.getString(R.string.animation_flip)
        )
        val values = arrayOf("none", "fade_in", "slide_left", "slide_right", "slide_bottom", "scale", "typewriter", "curtain", "flip")
        val currentIndex = values.indexOf(prefs.chatMessageAnimation).takeIf { it >= 0 } ?: 0

        AlertDialog.Builder(activity)
            .setTitle(R.string.setting_chat_animation)
            .setSingleChoiceItems(options, currentIndex) { dialog, which ->
                prefs.chatMessageAnimation = values[which]
                // Live-update the chat animator
                try {
                    activity.chatUiManager.updateChatMessageAnimation()
                } catch (_: Exception) { }
                dialog.dismiss()
                // Refresh detail sheet
                detailDialog?.dismiss()
                showAppearanceSettings()
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }
    
    internal fun checkForUpdates(isSilent: Boolean = false) {
        if (!isSilent) {
            Toast.makeText(activity, R.string.checking_updates, Toast.LENGTH_SHORT).show()
        }
        
        activity.lifecycleScope.launch {
            try {
                updateRepository.getLatestRelease(prefs.updateChannel).onSuccess { release ->
                    activity.runOnUiThread {
                        if (release != null) {
                            val currentVersion = BuildConfig.VERSION_NAME
                            val newVersion = release.tagName.replace("v", "")
                            
                            if (newVersion != currentVersion) {
                                showModernUpdateDialog(release, currentVersion, newVersion)
                            } else if (!isSilent) {
                                Toast.makeText(activity, R.string.no_updates, Toast.LENGTH_SHORT).show()
                            }
                        } else if (!isSilent) {
                            Toast.makeText(activity, R.string.no_updates, Toast.LENGTH_SHORT).show()
                        }
                    }
                }.onFailure {
                    activity.runOnUiThread {
                        if (!isSilent) {
                            Toast.makeText(activity, R.string.update_check_failed, Toast.LENGTH_SHORT).show()
                        }
                    }
                }
            } catch (e: Exception) {
                activity.runOnUiThread {
                    if (!isSilent) {
                        Toast.makeText(activity, R.string.update_check_failed, Toast.LENGTH_SHORT).show()
                    }
                }
            }
        }
    }
    
    private fun showModernUpdateDialog(release: dev.xacnio.kciktv.shared.data.model.GithubRelease, currentVersion: String, newVersion: String) {
        val dialogView = LayoutInflater.from(activity).inflate(R.layout.dialog_update, null)
        
        val dialog = AlertDialog.Builder(activity, R.style.TransparentAlertDialog)
            .setView(dialogView)
            .setCancelable(true)
            .create()
        
        // Setup views
        val titleView = dialogView.findViewById<TextView>(R.id.updateTitle)
        val versionView = dialogView.findViewById<TextView>(R.id.updateVersion)
        val changelogView = dialogView.findViewById<TextView>(R.id.updateChangelog)
        val btnLater = dialogView.findViewById<com.google.android.material.button.MaterialButton>(R.id.btnUpdateLater)
        val btnUpdateNow = dialogView.findViewById<com.google.android.material.button.MaterialButton>(R.id.btnUpdateNow)
        val progressSection = dialogView.findViewById<LinearLayout>(R.id.progressSection)
        val progressBar = dialogView.findViewById<ProgressBar>(R.id.updateProgressBar)
        val progressPercent = dialogView.findViewById<TextView>(R.id.progressPercent)
        val downloadSize = dialogView.findViewById<TextView>(R.id.downloadSize)
        val downloadSpeed = dialogView.findViewById<TextView>(R.id.downloadSpeed)
        
        // Set content
        titleView.text = activity.getString(R.string.update_available_title)
        versionView.text = activity.getString(R.string.update_version_format, currentVersion, newVersion)
        
        // Parse and format changelog (markdown body from GitHub)
        val changelog = release.body.trim()
        if (changelog.isNotEmpty()) {
            // Simple markdown parsing for bullet points
            val formattedChangelog = changelog
                .replace(Regex("^#+\\s*(.+)$", RegexOption.MULTILINE), "$1")  // Remove markdown headers
                .replace(Regex("^[-*]\\s*", RegexOption.MULTILINE), "• ")     // Convert bullets
                .replace(Regex("\\*\\*(.+?)\\*\\*"), "$1")                     // Remove bold
                .replace(Regex("\\*(.+?)\\*"), "$1")                           // Remove italic
                .trim()
            changelogView.text = formattedChangelog
        } else {
            changelogView.text = activity.getString(R.string.update_available_msg, newVersion)
        }
        dev.xacnio.kciktv.mobile.util.DialogUtils.setupClickableLinks(changelogView)
        
        // Button actions
        btnLater.setOnClickListener {
            dialog.dismiss()
        }
        
        var isDownloading = false
        
        btnUpdateNow.setOnClickListener {
            if (isDownloading) return@setOnClickListener
            isDownloading = true
            btnUpdateNow.isEnabled = false
            btnLater.isEnabled = false
            btnUpdateNow.alpha = 0.5f
            btnLater.alpha = 0.5f
            
            progressSection.visibility = View.VISIBLE
            progressBar.progress = 0
            progressPercent.text = "0%"
            downloadSize.text = ""
            downloadSpeed.text = ""
            
            // Start download
            val asset = release.assets.firstOrNull { it.name.endsWith(".apk") }
            if (asset != null) {
                downloadAndInstallApk(asset.downloadUrl, asset.size) { progress, downloadedBytes, totalBytes, speed ->
                    activity.runOnUiThread {
                        if (progress >= 0) {
                            progressBar.progress = progress
                            progressPercent.text = "$progress%"
                            downloadSize.text = "${formatFileSize(downloadedBytes)} / ${formatFileSize(totalBytes)}"
                            if (speed > 0) {
                                downloadSpeed.text = "${formatFileSize(speed.toLong())}/s"
                            }
                        } else {
                            // Download failed or completed with install
                            if (progress == -1) {
                                dialog.dismiss()
                            }
                        }
                    }
                }
            } else {
                Toast.makeText(activity, activity.getString(R.string.apk_not_found), Toast.LENGTH_SHORT).show()
                dialog.dismiss()
            }
        }
        
        dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)
        dialog.show()
    }
    
    private fun downloadAndInstallApk(
        url: String, 
        totalSize: Long,
        onProgress: (progress: Int, downloadedBytes: Long, totalBytes: Long, speed: Float) -> Unit
    ) {
        activity.lifecycleScope.launch(Dispatchers.IO) {
            try {
                val client = OkHttpClient()
                val request = Request.Builder().url(url).build()
                val response = client.newCall(request).execute()
                
                if (response.isSuccessful && response.body != null) {
                    val body = response.body!!
                    val contentLength = if (body.contentLength() > 0) body.contentLength() else totalSize
                    val inputStream = body.byteStream()
                    val apkFile = File(activity.cacheDir, "update.apk")
                    val outputStream = FileOutputStream(apkFile)
                    
                    val buffer = ByteArray(8 * 1024)
                    var bytesRead: Int
                    var totalBytesRead: Long = 0
                    var lastProgressTime = System.currentTimeMillis()
                    var lastBytesRead: Long = 0
                    var currentSpeed: Float = 0f
                    
                    while (inputStream.read(buffer).also { bytesRead = it } != -1) {
                        outputStream.write(buffer, 0, bytesRead)
                        totalBytesRead += bytesRead
                        
                        val currentTime = System.currentTimeMillis()
                        val elapsedTime = currentTime - lastProgressTime
                        
                        // Calculate speed every 500ms
                        if (elapsedTime >= 500) {
                            val bytesInInterval = totalBytesRead - lastBytesRead
                            currentSpeed = (bytesInInterval * 1000f) / elapsedTime
                            lastProgressTime = currentTime
                            lastBytesRead = totalBytesRead
                        }
                        
                        if (contentLength > 0) {
                            val progress = (totalBytesRead * 100 / contentLength).toInt()
                            onProgress(progress, totalBytesRead, contentLength, currentSpeed)
                        }
                    }
                    
                    outputStream.flush()
                    outputStream.close()
                    inputStream.close()
                    
                    withContext(Dispatchers.Main) {
                        onProgress(100, totalBytesRead, contentLength, currentSpeed)
                        installApk(apkFile)
                    }
                } else {
                    withContext(Dispatchers.Main) {
                        Toast.makeText(activity, R.string.download_failed, Toast.LENGTH_SHORT).show()
                        onProgress(-1, 0, 0, 0f)
                    }
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    Toast.makeText(activity, activity.getString(R.string.download_failed) + ": ${e.message}", Toast.LENGTH_SHORT).show()
                    onProgress(-1, 0, 0, 0f)
                }
            }
        }
    }
    
    private fun installApk(file: File) {
        val uri = FileProvider.getUriForFile(activity, "${activity.packageName}.fileprovider", file)
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        activity.startActivity(intent)
    }
    
    private fun formatFileSize(bytes: Long): String {
        return when {
            bytes < 1024 -> "$bytes B"
            bytes < 1024 * 1024 -> String.format("%.1f KB", bytes / 1024f)
            bytes < 1024 * 1024 * 1024 -> String.format("%.1f MB", bytes / (1024f * 1024f))
            else -> String.format("%.2f GB", bytes / (1024f * 1024f * 1024f))
        }
    }
}
