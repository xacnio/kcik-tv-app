/**
 * File: ChatSettingsSheetManager.kt
 *
 * Description: Controls the Chat Settings bottom sheet, allowing users to customize their chat experience.
 * It manages preferences for message sizing, timestamp display, and animation styles,
 * and provides an interface for users to customize their profile identity and badges for the channel.
 *
 * Author: Xacnio
 *
 */
package dev.xacnio.kciktv.mobile.ui.chat

import dev.xacnio.kciktv.shared.ui.chat.ChatRulesManager
import dev.xacnio.kciktv.shared.data.model.ChannelItem

import android.annotation.SuppressLint
import android.graphics.Color
import android.util.Log
import android.view.View
import android.widget.GridLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.widget.SwitchCompat
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.google.android.material.bottomsheet.BottomSheetDialog
import dev.xacnio.kciktv.mobile.MobilePlayerActivity
import dev.xacnio.kciktv.R
import dev.xacnio.kciktv.shared.data.model.ChatIdentityBadge
import dev.xacnio.kciktv.shared.data.prefs.AppPreferences

import kotlinx.coroutines.CoroutineScope
import dev.xacnio.kciktv.shared.data.repository.ChannelRepository
import kotlinx.coroutines.launch

class ChatSettingsSheetManager(
    private val activity: MobilePlayerActivity,
    private val prefs: AppPreferences,
    private val chatRulesManager: ChatRulesManager,
    private val chatUiManager: ChatUiManager,
    private val chatStateManager: ChatStateManager,
    private val repository: ChannelRepository,
    private val lifecycleScope: CoroutineScope
) {
    // Profile Identity State
    private var profileSelectedBadges = mutableListOf<String>()
    private var profileSelectedColor: String = "#53fc18"
    internal var hasProfileChanges: Boolean = false
    private var currentIdentityChannelId: Long = 0L
    private var availableProfileBadges = mutableListOf<ChatIdentityBadge>()
    private var isProfileIdentityLoaded = false

    private val profileColors = listOf(
        "#FFD899", "#FFC466", "#FF9D00", "#FBCFD8", "#F2708A", "#E9113C", "#DEB2FF", "#BC66FF",
        "#B9D6F6", "#72ACED", "#1475E1", "#BAFEA3", "#75FD46", "#93EBE0", "#31D6C2", "#00CCB3"
    )

    // Helper state for sliders usually kept in sync with prefs
    private var currentTextSize: Float = 14f
    private var currentEmoteScale: Float = 1.0f
    private var showTimestamps = false
    private var showSeconds = false

    @SuppressLint("InflateParams")
    internal fun showChatSettingsSheet(startOnProfile: Boolean = false) {
        val dialog = BottomSheetDialog(activity)
        val view = activity.layoutInflater.inflate(R.layout.bottom_sheet_chat_settings, null)
        
        val rootMenu = view.findViewById<View>(R.id.chatSettingsRootMenu)
        val profileView = view.findViewById<View>(R.id.profileViewContainer)
        val chatSettingsView = view.findViewById<View>(R.id.chatSettingsViewContainer)
        val mainScrollView = view.findViewById<View>(R.id.mainSettingsScrollView)
        
        // Sync local state with prefs/adapter
        currentTextSize = prefs.chatTextSize
        currentEmoteScale = prefs.chatEmoteSize
        showTimestamps = prefs.chatShowTimestamps
        showSeconds = prefs.chatShowSeconds
        
        if (startOnProfile) {
            rootMenu.visibility = View.GONE
            profileView.visibility = View.VISIBLE
        }
        
        // Main Menu Clicks
        val btnProfile = view.findViewById<View>(R.id.btnMenuProfile)
        if (!prefs.isLoggedIn) {
            btnProfile.isEnabled = false
            btnProfile.alpha = 0.5f
        } else {
            btnProfile.isEnabled = true
            btnProfile.alpha = 1.0f
            btnProfile.setOnClickListener {
                rootMenu.visibility = View.GONE
                profileView.visibility = View.VISIBLE
            }
        }
        
        view.findViewById<View>(R.id.btnMenuChatSettings).setOnClickListener {
            rootMenu.visibility = View.GONE
            chatSettingsView.visibility = View.VISIBLE
        }

        var pendingRulesChannel: ChannelItem? = null
        
        view.findViewById<View>(R.id.btnMenuChatRules).setOnClickListener {
            pendingRulesChannel = activity.currentChannel
            dialog.dismiss()
        }
        
        hasProfileChanges = false
        isProfileIdentityLoaded = false
        availableProfileBadges.clear()
        setupProfileSettings(view)
        
        // Back Clicks
        view.findViewById<View>(R.id.btnBackFromProfile).setOnClickListener {
            if (hasProfileChanges) saveProfileIdentity()
            profileView.visibility = View.GONE
            rootMenu.visibility = View.VISIBLE
        }
        
        view.findViewById<View>(R.id.btnBackToRootFromChat).setOnClickListener {
            chatSettingsView.visibility = View.GONE
            rootMenu.visibility = View.VISIBLE
        }
        
        // Dismiss button
        view.findViewById<View>(R.id.btnDismissSettings).setOnClickListener {
            dialog.dismiss()
        }
        
        // --- Tab Switching ---
        val tabSizes = view.findViewById<TextView>(R.id.tabSizes)
        val tabFeatures = view.findViewById<TextView>(R.id.tabFeatures)
        val tabAdvanced = view.findViewById<TextView>(R.id.tabAdvanced)
        val sectionSizes = view.findViewById<View>(R.id.sectionSizes)
        val sectionFeatures = view.findViewById<View>(R.id.sectionFeatures)
        val sectionAdvanced = view.findViewById<View>(R.id.sectionAdvanced)
        
        val themeColor = prefs.themeColor
        
        fun selectTab(selectedTab: TextView) {
            val tabs = listOf(tabSizes, tabFeatures, tabAdvanced)
            val sections = listOf(sectionSizes, sectionFeatures, sectionAdvanced)
            tabs.forEachIndexed { index, tab ->
                val isSelected = tab == selectedTab
                tab.backgroundTintList = android.content.res.ColorStateList.valueOf(
                    if (isSelected) themeColor else 0xFF2A2A2A.toInt()
                )
                tab.setTextColor(if (isSelected) 0xFFFFFFFF.toInt() else 0xFFAAAAAA.toInt())
                tab.setTypeface(null, if (isSelected) android.graphics.Typeface.BOLD else android.graphics.Typeface.NORMAL)
                sections[index].visibility = if (isSelected) View.VISIBLE else View.GONE
            }
        }
        
        tabSizes.setOnClickListener { selectTab(tabSizes) }
        tabFeatures.setOnClickListener { selectTab(tabFeatures) }
        tabAdvanced.setOnClickListener { selectTab(tabAdvanced) }
        
        // Initialize with Sizes tab selected and apply theme color
        selectTab(tabSizes)
        
        // Views
        val switchTimestamps = view.findViewById<SwitchCompat>(R.id.switchTimestamps)
        val switchSeconds = view.findViewById<SwitchCompat>(R.id.switchSeconds)
        val containerSeconds = view.findViewById<View>(R.id.containerSeconds)
        val textSeconds = view.findViewById<TextView>(R.id.textSeconds)
        
        val seekBarMessageSize = view.findViewById<SeekBar>(R.id.seekBarMessageSize)
        val textValueMessageSize = view.findViewById<TextView>(R.id.textValueMessageSize)
        
        val seekBarEmoteSize = view.findViewById<SeekBar>(R.id.seekBarEmoteSize)
        val textValueEmoteSize = view.findViewById<TextView>(R.id.textValueEmoteSize)
        
        val switchPinnedGifts = view.findViewById<SwitchCompat>(R.id.switchPinnedGifts)
        val switchEmoteCombo = view.findViewById<SwitchCompat>(R.id.switchEmoteCombo)
        val switchFloatingEmotes = view.findViewById<SwitchCompat>(R.id.switchFloatingEmotes)
        val switchLowBatteryMode = view.findViewById<SwitchCompat>(R.id.switchLowBatteryMode)
        
        // Apply theme color to switches and seekbars
        val themeColorStateList = android.content.res.ColorStateList(
            arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf()),
            intArrayOf(themeColor, 0xFF333333.toInt())
        )
        val thumbColorStateList = android.content.res.ColorStateList(
            arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf()),
            intArrayOf(themeColor, 0xFFFFFFFF.toInt())
        )
        
        listOf(switchTimestamps, switchSeconds, switchPinnedGifts, switchEmoteCombo, switchFloatingEmotes, switchLowBatteryMode).forEach { switch ->
            switch.trackTintList = themeColorStateList
            switch.thumbTintList = thumbColorStateList
        }
        
        seekBarMessageSize.progressTintList = android.content.res.ColorStateList.valueOf(themeColor)
        seekBarMessageSize.thumbTintList = android.content.res.ColorStateList.valueOf(themeColor)
        seekBarEmoteSize.progressTintList = android.content.res.ColorStateList.valueOf(themeColor)
        seekBarEmoteSize.thumbTintList = android.content.res.ColorStateList.valueOf(themeColor)

        // --- Setup Message Size Slider (10sp to 30sp) ---
        val minSize = 10
        seekBarMessageSize.max = 20
        seekBarMessageSize.progress = (currentTextSize - minSize).toInt().coerceIn(0, 20)
        textValueMessageSize.text = "${currentTextSize.toInt()}sp"
        
        seekBarMessageSize.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                val size = (minSize + progress).toFloat()
                textValueMessageSize.text = "${size.toInt()}sp"
                currentTextSize = size
                if (fromUser) chatUiManager.chatAdapter.setChatTextSize(size)
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {
                prefs.chatTextSize = currentTextSize
                chatUiManager.chatAdapter.setChatTextSize(currentTextSize)
            }
        })
        
        // --- Setup Emote Size Slider (1.0x to 3.0x) ---
        seekBarEmoteSize.max = 20
        seekBarEmoteSize.progress = ((currentEmoteScale - 1.0f) * 10).toInt().coerceIn(0, 20)
        textValueEmoteSize.text = String.format("%.1fx", currentEmoteScale)
        
        seekBarEmoteSize.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                val scale = 1.0f + (progress / 10.0f)
                textValueEmoteSize.text = String.format("%.1fx", scale)
                currentEmoteScale = scale
                if (fromUser) chatUiManager.chatAdapter.setChatEmoteSize(scale)
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {
                prefs.chatEmoteSize = currentEmoteScale
                chatUiManager.chatAdapter.setChatEmoteSize(currentEmoteScale)
            }
        })

        // --- Chat Animation ---
        val textChatAnimationValue = view.findViewById<TextView>(R.id.textChatAnimationValue)
        textChatAnimationValue.text = getChatAnimationValue()
        
        view.findViewById<View>(R.id.btnChatAnimation).setOnClickListener {
            mainScrollView.visibility = View.GONE
            view.findViewById<View>(R.id.animationSettingsContainer).visibility = View.VISIBLE
            setupAnimationSettings(view)
        }

        // --- Timestamps ---
        switchTimestamps.isChecked = showTimestamps
        switchSeconds.isChecked = showSeconds
        
        fun updateSecondsEnabledState() {
            val isEnabled = switchTimestamps.isChecked
            switchSeconds.isEnabled = isEnabled
            containerSeconds.alpha = if (isEnabled) 1.0f else 0.5f
            textSeconds.alpha = if (isEnabled) 1.0f else 0.5f
        }
        updateSecondsEnabledState()

        switchTimestamps.setOnCheckedChangeListener { _, isChecked ->
            showTimestamps = isChecked
            prefs.chatShowTimestamps = isChecked
            chatUiManager.chatAdapter.setShowTimestamps(isChecked)
            updateSecondsEnabledState()
        }

        switchSeconds.setOnCheckedChangeListener { _, isChecked ->
            showSeconds = isChecked
            prefs.chatShowSeconds = isChecked
            chatUiManager.chatAdapter.setShowSeconds(isChecked)
        }

        // --- Pinned Gifts Toggle ---
        switchPinnedGifts.isChecked = prefs.showPinnedGifts
        switchPinnedGifts.setOnCheckedChangeListener { _, isChecked ->
            prefs.showPinnedGifts = isChecked
            if (isChecked) {
                activity.overlayManager.updatePinnedGiftsUI()
            } else {
                activity.binding.pinnedGiftsScroll.visibility = View.GONE
            }
        }

        // --- Emote Combo Toggle ---
        switchEmoteCombo.isChecked = prefs.emoteComboEnabled
        switchEmoteCombo.setOnCheckedChangeListener { _, isChecked ->
            prefs.emoteComboEnabled = isChecked
            if (!isChecked) activity.emoteComboManager.clear()
        }

        // --- Floating Emotes Toggle ---
        switchFloatingEmotes.isChecked = prefs.floatingEmotesEnabled
        switchFloatingEmotes.setOnCheckedChangeListener { _, isChecked ->
            prefs.floatingEmotesEnabled = isChecked
            if (!isChecked) activity.floatingEmoteManager.clear()
        }

        // --- Low Battery Mode Toggle ---
        switchLowBatteryMode.isChecked = prefs.lowBatteryModeEnabled
        switchLowBatteryMode.setOnCheckedChangeListener { _, isChecked ->
            prefs.lowBatteryModeEnabled = isChecked
            chatUiManager.stopFlushing()
            chatUiManager.startFlushing()
        }

        // --- Highlight Settings ---
        val highlightContainer = view.findViewById<View>(R.id.highlightSettingsScrollView)
        val btnHighlightedMessages = view.findViewById<View>(R.id.btnHighlightedMessages)
        val btnBackFromHighlights = view.findViewById<View>(R.id.btnBackFromHighlights)
        
        val switchHighlightOwn = view.findViewById<SwitchCompat>(R.id.switchHighlightOwn)
        val switchHighlightMentions = view.findViewById<SwitchCompat>(R.id.switchHighlightMentions)
        val switchHighlightMods = view.findViewById<SwitchCompat>(R.id.switchHighlightMods)
        val switchHighlightVips = view.findViewById<SwitchCompat>(R.id.switchHighlightVips)
        val switchHighlightUseNameColor = view.findViewById<SwitchCompat>(R.id.switchHighlightUseNameColor)
        val switchVibrateOnMentions = view.findViewById<SwitchCompat>(R.id.switchVibrateOnMentions)
        
        switchHighlightOwn.isChecked = prefs.highlightOwnMessages
        switchHighlightMentions.isChecked = prefs.highlightMentions
        switchHighlightMods.isChecked = prefs.highlightMods
        switchHighlightVips.isChecked = prefs.highlightVips
        switchHighlightUseNameColor.isChecked = prefs.chatUseNameColorForHighlight
        switchVibrateOnMentions.isChecked = prefs.vibrateOnMentions
        
        listOf(switchHighlightOwn, switchHighlightMentions, switchHighlightMods, switchHighlightVips, switchHighlightUseNameColor, switchVibrateOnMentions).forEach { switch ->
            switch.trackTintList = themeColorStateList
            switch.thumbTintList = thumbColorStateList
        }

        switchVibrateOnMentions.setOnCheckedChangeListener { _, isChecked ->
            prefs.vibrateOnMentions = isChecked
        }
        
        // Theme color for highlight sub-page headers and icons
        view.findViewById<ImageView>(R.id.chatHighlightBackIcon)?.imageTintList = android.content.res.ColorStateList.valueOf(prefs.themeColor)
        view.findViewById<TextView>(R.id.chatHighlightBackText)?.setTextColor(prefs.themeColor)
        view.findViewById<View>(R.id.chatHighlightSelfBar)?.setBackgroundColor(prefs.themeColor)
        
        btnHighlightedMessages.setOnClickListener {
            mainScrollView.visibility = View.GONE
            highlightContainer.visibility = View.VISIBLE
        }
        
        btnBackFromHighlights.setOnClickListener {
            highlightContainer.visibility = View.GONE
            mainScrollView.visibility = View.VISIBLE
        }
        
        val onHighlightUpdate = {
            prefs.highlightOwnMessages = switchHighlightOwn.isChecked
            prefs.highlightMentions = switchHighlightMentions.isChecked
            prefs.highlightMods = switchHighlightMods.isChecked
            prefs.highlightVips = switchHighlightVips.isChecked
            prefs.chatUseNameColorForHighlight = switchHighlightUseNameColor.isChecked
            
            chatUiManager.chatAdapter.setHighlightSettings(
                prefs.highlightOwnMessages,
                prefs.highlightMentions,
                prefs.highlightMods,
                prefs.highlightVips,
                prefs.chatUseNameColorForHighlight
            )
        }
        
        switchHighlightOwn.setOnCheckedChangeListener { _, _ -> onHighlightUpdate() }
        switchHighlightMentions.setOnCheckedChangeListener { _, _ -> onHighlightUpdate() }
        switchHighlightMods.setOnCheckedChangeListener { _, _ -> onHighlightUpdate() }
        switchHighlightVips.setOnCheckedChangeListener { _, _ -> onHighlightUpdate() }
        switchHighlightUseNameColor.setOnCheckedChangeListener { _, _ -> onHighlightUpdate() }
        
        dialog.setContentView(view)
        dialog.setOnDismissListener {
            if (hasProfileChanges) saveProfileIdentity()
            pendingRulesChannel?.let { channel ->
                chatRulesManager.forceShowRules(channel)
            }
        }
        dialog.setOnShowListener {
             (it as? BottomSheetDialog)?.window?.setDimAmount(0f)
        }
        dialog.show()
        
        // Limit max sheet height so chat stays visible as live preview
        val bottomSheet = dialog.findViewById<View>(com.google.android.material.R.id.design_bottom_sheet)
        bottomSheet?.let {
            val screenHeight = activity.resources.displayMetrics.heightPixels
            val maxHeight = (screenHeight * 0.55).toInt()
            val behavior = com.google.android.material.bottomsheet.BottomSheetBehavior.from(it)
            behavior.maxHeight = maxHeight
            behavior.skipCollapsed = true
            behavior.state = com.google.android.material.bottomsheet.BottomSheetBehavior.STATE_EXPANDED
        }
    }

    private fun setupProfileSettings(view: View) {
        val rvBadges = view.findViewById<RecyclerView>(R.id.rvProfileBadges)
        val gridColors = view.findViewById<GridLayout>(R.id.gridNameColors)
        val previewUsername = view.findViewById<TextView>(R.id.previewUsername)
        val previewBadgeLayout = view.findViewById<LinearLayout>(R.id.previewBadgeLayout)

        val channelId = activity.currentChannel?.id?.toLongOrNull() ?: 0L
        val userId = prefs.userId
        val token = prefs.authToken
        
        if (channelId == 0L || userId == 0L || token == null) {
            return
        }
        currentIdentityChannelId = channelId
        
        val viewerUsername = prefs.username ?: "User"
        previewUsername.text = viewerUsername
        
        fun updatePreview() {
            previewUsername.setTextColor(Color.parseColor(profileSelectedColor))
            updatePreviewBadges(previewBadgeLayout, profileSelectedBadges, availableProfileBadges)
        }
        
        // Colors
        gridColors.removeAllViews()
        val density = activity.resources.displayMetrics.density
        val size = (36 * density).toInt()
        val margin = (4 * density).toInt()
        
        profileColors.forEach { color ->
            val frame = android.widget.FrameLayout(activity)
            val params = GridLayout.LayoutParams()
            params.width = size
            params.height = size
            params.setMargins(margin, margin, margin, margin)
            frame.layoutParams = params
            
            val bg = View(activity)
            bg.layoutParams = android.widget.FrameLayout.LayoutParams(android.view.ViewGroup.LayoutParams.MATCH_PARENT, android.view.ViewGroup.LayoutParams.MATCH_PARENT)
            bg.setBackgroundResource(R.drawable.bg_rounded_circle)
            bg.backgroundTintList = android.content.res.ColorStateList.valueOf(Color.parseColor(color))
            frame.addView(bg)
            
            val check = ImageView(activity)
            check.layoutParams = android.widget.FrameLayout.LayoutParams(android.view.ViewGroup.LayoutParams.MATCH_PARENT, android.view.ViewGroup.LayoutParams.MATCH_PARENT)
            check.setImageResource(R.drawable.ic_check)
            check.imageTintList = android.content.res.ColorStateList.valueOf(Color.WHITE)
            check.setPadding(8, 8, 8, 8)
            check.visibility = View.GONE
            frame.addView(check)
            
            frame.setOnClickListener {
                profileSelectedColor = color
                hasProfileChanges = true
                updatePreview()
                
                // Update selection UI
                for (i in 0 until gridColors.childCount) {
                    val child = gridColors.getChildAt(i) as android.widget.FrameLayout
                    val childCheck = child.getChildAt(1)
                    childCheck.visibility = View.GONE
                }
                check.visibility = View.VISIBLE
            }
            
            gridColors.addView(frame)
        }

        // Badges Adapter
        val adapter = object : RecyclerView.Adapter<ProfileBadgeViewHolder>() {
            override fun onCreateViewHolder(parent: android.view.ViewGroup, viewType: Int): ProfileBadgeViewHolder {
                val v = activity.layoutInflater.inflate(R.layout.item_profile_badge_selection, parent, false)
                return ProfileBadgeViewHolder(v)
            }
            
            override fun onBindViewHolder(holder: ProfileBadgeViewHolder, position: Int) {
                val badge = availableProfileBadges[position]
                val iv = holder.itemView.findViewById<ImageView>(R.id.ivBadgeIcon)
                val check = holder.itemView.findViewById<View>(R.id.badgeSelectionOverlay)
                
                // Load badge
                val badgeIcon = getBadgeUrl(badge.type, badge.count)
                if (badgeIcon != null) {
                    Glide.with(iv).load(badgeIcon).into(iv)
                }
                
                val isSelected = profileSelectedBadges.contains(badge.type)
                check.visibility = if (isSelected) View.VISIBLE else View.GONE
                
                holder.itemView.setOnClickListener {
                    if (isSelected) {
                        profileSelectedBadges.remove(badge.type)
                    } else {
                        // Max 4 badges
                        if (profileSelectedBadges.size < 4) {
                            badge.type?.let { it1 -> profileSelectedBadges.add(it1) }
                        } else {
                            android.widget.Toast.makeText(activity, activity.getString(R.string.max_badges_reached, 4), android.widget.Toast.LENGTH_SHORT).show()
                        }
                    }
                    hasProfileChanges = true
                    notifyDataSetChanged()
                    updatePreview()
                }
            }
            
            override fun getItemCount() = availableProfileBadges.size
        }
        
        rvBadges.layoutManager = androidx.recyclerview.widget.LinearLayoutManager(activity, RecyclerView.HORIZONTAL, false)
        rvBadges.adapter = adapter
        
        // Fetch Identity
        if (!isProfileIdentityLoaded) {
            lifecycleScope.launch {
                try {
                    repository.getChatIdentity(channelId, userId, token).onSuccess { response ->
                        activity.runOnUiThread {
                            val identity = response.data?.identity
                            profileSelectedColor = identity?.color ?: "#53fc18"
                            profileSelectedBadges.clear()
                            identity?.badges?.forEach { badge: ChatIdentityBadge ->
                                if (badge.active == true) badge.type?.let { profileSelectedBadges.add(it) }
                            }
                            
                            availableProfileBadges.clear()
                            identity?.badges?.let { availableProfileBadges.addAll(it) }
                            
                            isProfileIdentityLoaded = true
                            adapter.notifyDataSetChanged()
                            updatePreview()
                            
                            // Update Color Selection UI
                            for (i in 0 until gridColors.childCount) {
                                val child = gridColors.getChildAt(i) as android.widget.FrameLayout
                                val childBg = child.getChildAt(0)
                                val childCheck = child.getChildAt(1)
                                
                                val bgColor = childBg.backgroundTintList?.defaultColor
                                val targetColor = Color.parseColor(profileSelectedColor)
                                
                                if (bgColor == targetColor) {
                                    childCheck.visibility = View.VISIBLE
                                } else {
                                    childCheck.visibility = View.GONE
                                }
                            }
                        }
                    }
                } catch (e: Exception) {
                    Log.e("ChatSettings", "Profile identity error: ${e.message}")
                }
            }
        } else {
            // Check UI selection state for color if already loaded
            // But we recreate views so we need to set them checked based on current vars
             for (i in 0 until gridColors.childCount) {
                // val child = gridColors.getChildAt(i) as android.widget.FrameLayout
                // val childBg = child.getChildAt(0)

                // This is hard to check exact color from background here without tag
                 // For now, let's just trigger updatePreview to ensure text color is right
                 // Re-selection visual logic might be skipped if we don't have color index
             }
             updatePreview()
        }
    }
    
    private fun updatePreviewBadges(layout: LinearLayout, selectedTypes: List<String>, allBadges: List<ChatIdentityBadge>) {
        layout.removeAllViews()
        val size = (20 * activity.resources.displayMetrics.density).toInt()
        val margin = (2 * activity.resources.displayMetrics.density).toInt()
        
        selectedTypes.forEach { type ->
            val badge = allBadges.find { it.type == type }
            val iv = ImageView(activity)
            val params = LinearLayout.LayoutParams(size, size)
            params.setMargins(0, 0, margin, 0)
            iv.layoutParams = params
            val badgeIcon = getBadgeUrl(type, badge?.count)
            Glide.with(iv).load(badgeIcon).into(iv)
            layout.addView(iv)
        }
    }

    private fun getBadgeUrl(type: String?, count: Int?): Any? {
        if (type == null) return null
        return when (type) {
            "broadcaster" -> R.drawable.ic_badge_broadcaster
            "moderator" -> R.drawable.ic_badge_moderator
            "vip" -> R.drawable.ic_badge_vip
            "og" -> R.drawable.ic_badge_og
            "verified" -> R.drawable.ic_badge_verified
            "founder" -> R.drawable.ic_badge_founder
            "staff" -> R.drawable.ic_badge_staff
            "subscriber" -> {
                val months = count ?: 1
                chatStateManager.subscriberBadges.keys.filter { it <= months }
                    .maxOrNull()?.let { chatStateManager.subscriberBadges[it] }
                    ?: R.drawable.ic_badge_subscriber_default
            }
            else -> "https://kick.com/badges/$type/default.png"
        }
    }

    private fun saveProfileIdentity() {
        if (!hasProfileChanges) return
        val channelId = currentIdentityChannelId
        val userId = prefs.userId
        val token = prefs.authToken ?: return
        if (channelId == 0L || userId == 0L) return
        
        val badges = profileSelectedBadges.toList()
        val color = profileSelectedColor
        
        lifecycleScope.launch {
            try {
                repository.updateChatIdentity(channelId, userId, token, badges, color)
                hasProfileChanges = false
                Log.d("ChatSettings", "Profile identity saved successfully")
            } catch (e: Exception) {
                Log.e("ChatSettings", "Failed to save profile identity: ${e.message}")
            }
        }
    }

    private fun setupAnimationSettings(view: View) {
        val container = view.findViewById<View>(R.id.animationSettingsContainer)
        val optionsContainer = view.findViewById<LinearLayout>(R.id.animationOptionsContainer)
        val previewBox = view.findViewById<View>(R.id.previewAnimationBox)
        val btnReplay = view.findViewById<View>(R.id.btnReplayAnimation)
        val textChatAnimationValue = view.findViewById<TextView>(R.id.textChatAnimationValue)
        val mainScrollView = view.findViewById<View>(R.id.mainSettingsScrollView)

        // Back action
        view.findViewById<View>(R.id.btnBackFromAnimations).setOnClickListener {
            container.visibility = View.GONE
            mainScrollView.visibility = View.VISIBLE
            textChatAnimationValue.text = getChatAnimationValue()
        }
        
        // Theme Colors
        view.findViewById<ImageView>(R.id.chatAnimationBackIcon)?.imageTintList = android.content.res.ColorStateList.valueOf(prefs.themeColor)
        view.findViewById<TextView>(R.id.chatAnimationBackText)?.setTextColor(prefs.themeColor)
        view.findViewById<View>(R.id.chatAnimationSelfBar)?.setBackgroundColor(prefs.themeColor)
        
        // Populate List
        optionsContainer.removeAllViews()
        val animations = listOf(
            "none" to R.string.animation_none,
            "fade_in" to R.string.animation_fade_in,
            "slide_left" to R.string.animation_slide_left,
            "slide_right" to R.string.animation_slide_right,
            "slide_bottom" to R.string.animation_slide_bottom,
            "scale" to R.string.animation_scale,
            "typewriter" to R.string.animation_typewriter,
            "curtain" to R.string.animation_curtain,
            "flip" to R.string.animation_flip,
            "rotate" to R.string.animation_rotate,
            "zoom_out" to R.string.animation_zoom_out,
            "swing" to R.string.animation_swing
        )
        
        val density = activity.resources.displayMetrics.density
        val paddingHorizontal = (16 * density).toInt()
        val paddingVertical = (12 * density).toInt()

        animations.forEach { (key, nameRes) ->
            // Creating a simple text view item programmatically
            val itemLayout = LinearLayout(activity).apply {
                orientation = LinearLayout.HORIZONTAL
                layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
                gravity = android.view.Gravity.CENTER_VERTICAL
                setPadding(paddingHorizontal, paddingVertical, paddingHorizontal, paddingVertical)
                background = activity.getDrawable(R.drawable.bg_ripple_rounded)
            }

            val textView = TextView(activity).apply {
                text = activity.getString(nameRes)
                textSize = 14f
                setTextColor(Color.WHITE)
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            }
            
            val checkIcon = ImageView(activity).apply {
                setImageResource(R.drawable.ic_check)
                imageTintList = android.content.res.ColorStateList.valueOf(prefs.themeColor)
                layoutParams = LinearLayout.LayoutParams(48, 48)
                visibility = if (prefs.chatMessageAnimation == key) View.VISIBLE else View.GONE
            }
            
            itemLayout.addView(textView)
            itemLayout.addView(checkIcon)
            
            itemLayout.setOnClickListener {
                prefs.chatMessageAnimation = key
                chatUiManager.chatAdapter.setAnimationType(key)
                
                // Update checks
                for (i in 0 until optionsContainer.childCount) {
                    val child = optionsContainer.getChildAt(i) as LinearLayout
                    child.getChildAt(1).visibility = View.GONE
                    (child.getChildAt(0) as TextView).setTextColor(Color.parseColor("#AAAAAA"))
                }
                checkIcon.visibility = View.VISIBLE
                textView.setTextColor(Color.WHITE)
                
                runPreviewAnimation(previewBox, key)
            }
            
            // Set initial state
            if (prefs.chatMessageAnimation == key) {
                textView.setTextColor(Color.WHITE)
            } else {
                textView.setTextColor(Color.parseColor("#AAAAAA"))
            }

            optionsContainer.addView(itemLayout)
        }
        
        btnReplay.setOnClickListener {
            runPreviewAnimation(previewBox, prefs.chatMessageAnimation)
        }

        // Initial run
        runPreviewAnimation(previewBox, prefs.chatMessageAnimation)
    }

    private fun runPreviewAnimation(view: View, type: String) {
        view.clearAnimation()
        view.alpha = 1f
        view.scaleX = 1f
        view.scaleY = 1f
        view.translationX = 0f
        view.translationY = 0f
        view.rotation = 0f
        view.rotationX = 0f
        view.rotationY = 0f

        when (type) {
            "fade_in" -> {
                view.alpha = 0f
                view.animate().alpha(1f).setDuration(300).start()
            }
            "slide_left" -> {
                val width = view.width.toFloat().takeIf { it != 0f } ?: 500f
                view.translationX = -width
                view.animate().translationX(0f).setDuration(300).start()
            }
            "slide_right" -> {
                val width = view.width.toFloat().takeIf { it != 0f } ?: 500f
                view.translationX = width
                view.animate().translationX(0f).setDuration(300).start()
            }
            "slide_bottom" -> {
                view.translationY = 100f
                view.animate().translationY(0f).setDuration(300).start()
            }
            "scale" -> {
                view.scaleX = 0.5f
                view.scaleY = 0.5f
                view.alpha = 0f
                view.animate().scaleX(1f).scaleY(1f).alpha(1f).setDuration(300).start()
            }
             "typewriter" -> {
                 // Simple mock for typewriter: just fade in quickly
                 view.alpha = 0f
                 view.animate().alpha(1f).setDuration(150).start()
             }
             "curtain" -> {
                 view.scaleY = 0f
                 view.pivotY = 0f
                 view.animate().scaleY(1f).setDuration(300).start()
             }
             "flip" -> {
                 view.rotationX = 90f
                 view.animate().rotationX(0f).setDuration(400).start()
             }
             "rotate" -> {
                 view.rotation = -90f
                 view.scaleX = 0.5f
                 view.scaleY = 0.5f
                 view.alpha = 0f
                 view.animate().rotation(0f).scaleX(1f).scaleY(1f).alpha(1f).setDuration(400).start()
             }
             "zoom_out" -> {
                 view.scaleX = 1.5f
                 view.scaleY = 1.5f
                 view.alpha = 0f
                 view.animate().scaleX(1f).scaleY(1f).alpha(1f).setDuration(300).start()
             }
             "swing" -> {
                 view.rotationX = -90f
                 view.pivotX = 0.5f
                 view.pivotY = 0f
                 view.animate().rotationX(0f).setDuration(600).setInterpolator(android.view.animation.OvershootInterpolator()).start()
             }
        }
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
            "rotate" -> activity.getString(R.string.animation_rotate)
            "zoom_out" -> activity.getString(R.string.animation_zoom_out)
            "swing" -> activity.getString(R.string.animation_swing)
            else -> activity.getString(R.string.animation_none)
        }
    }

    // Internal ViewHolder
    private class ProfileBadgeViewHolder(view: View) : RecyclerView.ViewHolder(view)
}
