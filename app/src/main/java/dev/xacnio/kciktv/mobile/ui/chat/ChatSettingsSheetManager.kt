/**
 * File: ChatSettingsSheetManager.kt
 *
 * Description: Controls the Chat Settings panel, allowing users to customize their chat experience.
 * It manages preferences for message sizing, timestamp display, and animation styles,
 * and provides an interface for users to customize their profile identity and badges for the channel.
 *
 * Author: Xacnio
 *
 */
package dev.xacnio.kciktv.mobile.ui.chat

import dev.xacnio.kciktv.shared.ui.chat.ChatRulesManager
import dev.xacnio.kciktv.shared.data.model.ChannelItem

import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.graphics.Color
import android.util.Log
import android.view.View
import android.view.animation.DecelerateInterpolator
import android.widget.FrameLayout
import android.widget.GridLayout
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.SeekBar
import android.widget.TextView
import androidx.appcompat.widget.SwitchCompat
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import dev.xacnio.kciktv.mobile.MobilePlayerActivity
import dev.xacnio.kciktv.R
import dev.xacnio.kciktv.shared.data.model.ChatIdentityBadge
import dev.xacnio.kciktv.shared.data.model.UserLevelData
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
    private var profileSelectedBadgesV2 = mutableListOf<String>()
    private var availableBadgesV2 = mutableListOf<dev.xacnio.kciktv.shared.data.model.ChatIdentityBadgeV2>()
    private var profileSelectedColor: String = "#53fc18"
    internal var hasProfileChanges: Boolean = false
    private var currentIdentityChannelId: Long = 0L
    private var availableProfileBadges = mutableListOf<ChatIdentityBadge>()
    private var isProfileIdentityLoaded = false

    private val profileColors = listOf(
        "#FFD899", "#FFC466", "#FF9D00", "#FBCFD8", "#F2708A", "#E9113C", "#DEB2FF", "#BC66FF",
        "#B9D6F6", "#72ACED", "#1475E1", "#BAFEA3", "#75FD46", "#93EBE0", "#31D6C2", "#00CCB3"
    )

    private var currentTextSize: Float = 14f
    private var currentEmoteScale: Float = 1.0f
    private var showTimestamps = false
    private var showSeconds = false

    private var currentPanelView: View? = null
    private var pendingRulesChannel: ChannelItem? = null

    private companion object {
        const val PAGE_ROOT = 0
        const val PAGE_PROFILE = 1
        const val PAGE_CHAT_SETTINGS = 2
        const val PAGE_HIGHLIGHTS = 3
        const val PAGE_ANIMATIONS = 4
    }

    fun isPanelShowing(): Boolean = currentPanelView != null

    fun dismissPanel() {
        val view = currentPanelView ?: return
        if (hasProfileChanges) saveProfileIdentity()
        view.animate().alpha(0f).setDuration(150).withEndAction {
            (view.parent as? android.view.ViewGroup)?.removeView(view)
            currentPanelView = null
        }.start()
        pendingRulesChannel?.let { channel ->
            chatRulesManager.forceShowRules(channel)
            pendingRulesChannel = null
        }
    }

    @SuppressLint("InflateParams")
    internal fun showChatSettingsSheet(startOnProfile: Boolean = false) {
        if (currentPanelView != null) return

        val view = activity.layoutInflater.inflate(R.layout.bottom_sheet_chat_settings, null)
        currentPanelView = view

        // Header views
        val panelBackButton = view.findViewById<ImageButton>(R.id.panelBackButton)
        val panelBackSpacer = view.findViewById<View>(R.id.panelBackSpacer)
        val panelTitleText = view.findViewById<TextView>(R.id.panelTitleText)
        val btnDismiss = view.findViewById<View>(R.id.btnDismissSettings)

        // Sub-page containers
        val rootMenu = view.findViewById<View>(R.id.chatSettingsRootMenu)
        val profileView = view.findViewById<View>(R.id.profileViewContainer)
        val chatSettingsView = view.findViewById<View>(R.id.chatSettingsViewContainer)
        val mainScrollView = view.findViewById<View>(R.id.mainSettingsScrollView)
        val highlightContainer = view.findViewById<View>(R.id.highlightSettingsScrollView)
        val animContainer = view.findViewById<View>(R.id.animationSettingsContainer)

        // Sync prefs
        currentTextSize = prefs.chatTextSize
        currentEmoteScale = prefs.chatEmoteSize
        showTimestamps = prefs.chatShowTimestamps
        showSeconds = prefs.chatShowSeconds

        hasProfileChanges = false
        isProfileIdentityLoaded = false
        availableProfileBadges.clear()
        availableBadgesV2.clear()
        profileSelectedBadgesV2.clear()

        var currentPage = PAGE_ROOT

        fun updateHeader(titleRes: Int, showBack: Boolean) {
            panelTitleText.text = activity.getString(titleRes)
            panelBackButton.visibility = if (showBack) View.VISIBLE else View.GONE
            panelBackSpacer.visibility = if (showBack) View.GONE else View.VISIBLE
        }

        fun navigate(page: Int) {
            currentPage = page
            val targetHeight = when (page) {
                PAGE_ROOT    -> dpToPx(264)
                PAGE_PROFILE -> getSubPageHeight(0.88f)
                else         -> getSubPageHeight()
            }
            animatePanelToHeight(targetHeight)
            when (page) {
                PAGE_ROOT -> {
                    rootMenu.visibility = View.VISIBLE
                    profileView.visibility = View.GONE
                    chatSettingsView.visibility = View.GONE
                    updateHeader(R.string.chat_settings_title, false)
                }
                PAGE_PROFILE -> {
                    rootMenu.visibility = View.GONE
                    profileView.visibility = View.VISIBLE
                    chatSettingsView.visibility = View.GONE
                    updateHeader(R.string.chat_identity_title, true)
                }
                PAGE_CHAT_SETTINGS -> {
                    rootMenu.visibility = View.GONE
                    profileView.visibility = View.GONE
                    chatSettingsView.visibility = View.VISIBLE
                    mainScrollView.visibility = View.VISIBLE
                    highlightContainer.visibility = View.GONE
                    animContainer.visibility = View.GONE
                    updateHeader(R.string.chat_appearance_title, true)
                }
                PAGE_HIGHLIGHTS -> {
                    mainScrollView.visibility = View.GONE
                    highlightContainer.visibility = View.VISIBLE
                    animContainer.visibility = View.GONE
                    updateHeader(R.string.chat_setting_highlights, true)
                }
                PAGE_ANIMATIONS -> {
                    mainScrollView.visibility = View.GONE
                    highlightContainer.visibility = View.GONE
                    animContainer.visibility = View.VISIBLE
                    updateHeader(R.string.setting_chat_animation, true)
                    setupAnimationSettings(view)
                }
            }
        }

        panelBackButton.setOnClickListener {
            when (currentPage) {
                PAGE_PROFILE -> {
                    if (hasProfileChanges) saveProfileIdentity()
                    navigate(PAGE_ROOT)
                }
                PAGE_CHAT_SETTINGS -> navigate(PAGE_ROOT)
                PAGE_HIGHLIGHTS -> navigate(PAGE_CHAT_SETTINGS)
                PAGE_ANIMATIONS -> navigate(PAGE_CHAT_SETTINGS)
            }
        }

        btnDismiss.setOnClickListener { dismissPanel() }

        // Root menu navigation
        val btnProfile = view.findViewById<View>(R.id.btnMenuProfile)
        if (!prefs.isLoggedIn) {
            btnProfile.isEnabled = false
            btnProfile.alpha = 0.4f
        } else {
            btnProfile.isEnabled = true
            btnProfile.alpha = 1.0f
            btnProfile.setOnClickListener { navigate(PAGE_PROFILE) }
        }

        view.findViewById<View>(R.id.btnMenuChatSettings).setOnClickListener {
            navigate(PAGE_CHAT_SETTINGS)
        }

        view.findViewById<View>(R.id.btnMenuChatRules).setOnClickListener {
            pendingRulesChannel = activity.currentChannel
            dismissPanel()
        }

        setupProfileSettings(view)

        // --- Theme color ---
        val themeColor = prefs.themeColor
        val themeColorStateList = android.content.res.ColorStateList(
            arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf()),
            intArrayOf(themeColor, 0xFF333333.toInt())
        )
        val thumbColorStateList = android.content.res.ColorStateList(
            arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf()),
            intArrayOf(themeColor, 0xFFFFFFFF.toInt())
        )

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

        listOf(switchTimestamps, switchSeconds, switchPinnedGifts, switchEmoteCombo, switchFloatingEmotes, switchLowBatteryMode).forEach { switch ->
            switch.trackTintList = themeColorStateList
            switch.thumbTintList = thumbColorStateList
        }

        seekBarMessageSize.progressTintList = android.content.res.ColorStateList.valueOf(themeColor)
        seekBarMessageSize.thumbTintList = android.content.res.ColorStateList.valueOf(themeColor)
        seekBarEmoteSize.progressTintList = android.content.res.ColorStateList.valueOf(themeColor)
        seekBarEmoteSize.thumbTintList = android.content.res.ColorStateList.valueOf(themeColor)

        // Message Size Slider (10sp to 30sp)
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

        // Emote Size Slider (1.0x to 3.0x)
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

        // Chat Animation button
        val textChatAnimationValue = view.findViewById<TextView>(R.id.textChatAnimationValue)
        textChatAnimationValue.text = getChatAnimationValue()
        view.findViewById<View>(R.id.btnChatAnimation).setOnClickListener {
            navigate(PAGE_ANIMATIONS)
        }

        // Timestamps
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

        // Pinned Gifts
        switchPinnedGifts.isChecked = prefs.showPinnedGifts
        switchPinnedGifts.setOnCheckedChangeListener { _, isChecked ->
            prefs.showPinnedGifts = isChecked
            if (isChecked) activity.overlayManager.updatePinnedGiftsUI()
            else activity.binding.pinnedGiftsScroll.visibility = View.GONE
        }

        // Emote Combo
        switchEmoteCombo.isChecked = prefs.emoteComboEnabled
        switchEmoteCombo.setOnCheckedChangeListener { _, isChecked ->
            prefs.emoteComboEnabled = isChecked
            if (!isChecked) activity.emoteComboManager.clear()
        }

        // Floating Emotes
        switchFloatingEmotes.isChecked = prefs.floatingEmotesEnabled
        switchFloatingEmotes.setOnCheckedChangeListener { _, isChecked ->
            prefs.floatingEmotesEnabled = isChecked
            if (!isChecked) activity.floatingEmoteManager.clear()
        }

        // Low Battery Mode
        switchLowBatteryMode.isChecked = prefs.lowBatteryModeEnabled
        switchLowBatteryMode.setOnCheckedChangeListener { _, isChecked ->
            prefs.lowBatteryModeEnabled = isChecked
            chatUiManager.stopFlushing()
            chatUiManager.startFlushing()
            activity.chatConnectionManager.applyLowBatteryPingInterval()
        }

        // Highlight Settings
        view.findViewById<View>(R.id.btnHighlightedMessages).setOnClickListener {
            navigate(PAGE_HIGHLIGHTS)
        }

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

        // Add panel to chatContainer (parent of chatListContainer) so it renders above
        // the pinned message overlay which is a sibling declared later in XML.
        val container = activity.binding.chatContainer
        val panelHeight = computePanelHeight()
        val lp = androidx.constraintlayout.widget.ConstraintLayout.LayoutParams(
            androidx.constraintlayout.widget.ConstraintLayout.LayoutParams.MATCH_PARENT,
            panelHeight
        )
        lp.topToTop = androidx.constraintlayout.widget.ConstraintLayout.LayoutParams.PARENT_ID
        lp.startToStart = androidx.constraintlayout.widget.ConstraintLayout.LayoutParams.PARENT_ID
        lp.endToEnd = androidx.constraintlayout.widget.ConstraintLayout.LayoutParams.PARENT_ID
        view.layoutParams = lp
        container.addView(view)
        view.elevation = dpToPx(16).toFloat()

        // Fade in
        view.alpha = 0f
        view.animate().alpha(1f).setDuration(180).start()

        if (startOnProfile) navigate(PAGE_PROFILE)
        else navigate(PAGE_ROOT)
    }

    private fun computePanelHeight(): Int = dpToPx(264)

    private fun getSubPageHeight(ratio: Float = 0.70f): Int {
        val container = activity.binding.chatListContainer
        val containerHeight = container.height.takeIf { it > 0 }
            ?: (activity.resources.displayMetrics.heightPixels * 0.55).toInt()
        return (containerHeight * ratio).toInt()
    }

    private fun animatePanelToHeight(targetH: Int) {
        val view = currentPanelView ?: return
        val startH = view.layoutParams.height
        if (startH == targetH) return
        android.animation.ValueAnimator.ofInt(startH, targetH).apply {
            duration = 220
            interpolator = android.view.animation.DecelerateInterpolator()
            addUpdateListener { anim ->
                val lp = view.layoutParams
                lp.height = anim.animatedValue as Int
                view.layoutParams = lp
            }
            start()
        }
    }

    private fun dpToPx(dp: Int): Int = (dp * activity.resources.displayMetrics.density).toInt()

    private fun setupProfileSettings(view: View) {
        val rvBadges = view.findViewById<RecyclerView>(R.id.rvProfileBadges)
        val gridColors = view.findViewById<GridLayout>(R.id.gridNameColors)
        val previewUsername = view.findViewById<TextView>(R.id.previewUsername)
        val previewBadgeLayout = view.findViewById<LinearLayout>(R.id.previewBadgeLayout)
        val labelGlobalBadges = view.findViewById<TextView>(R.id.labelGlobalBadges)
        val globalBadgesContainer = view.findViewById<LinearLayout>(R.id.globalBadgesContainer)
        val emptyGlobalBadges = view.findViewById<TextView>(R.id.emptyGlobalBadges)
        val emptyChannelBadges = view.findViewById<TextView>(R.id.emptyChannelBadges)
        val profileLevelCard = view.findViewById<View>(R.id.profileLevelCard)
        val profileLevelBadge = view.findViewById<android.widget.ImageView>(R.id.profileLevelBadge)
        val profileLevelText = view.findViewById<TextView>(R.id.profileLevelText)
        val profileLevelProgress = view.findViewById<android.widget.ProgressBar>(R.id.profileLevelProgress)
        val profileLevelProgressText = view.findViewById<TextView>(R.id.profileLevelProgressText)

        val channelId = activity.currentChannel?.id?.toLongOrNull() ?: 0L
        val userId = prefs.userId
        val token = prefs.authToken

        if (channelId == 0L || userId == 0L || token == null) return
        currentIdentityChannelId = channelId

        val viewerUsername = prefs.username ?: "User"
        previewUsername.text = viewerUsername

        fun updatePreview() {
            previewUsername.setTextColor(Color.parseColor(profileSelectedColor))
            updatePreviewBadges(previewBadgeLayout, profileSelectedBadges, availableProfileBadges, profileSelectedBadgesV2, availableBadgesV2)
        }

        // Color grid
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
            bg.layoutParams = android.widget.FrameLayout.LayoutParams(
                android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                android.view.ViewGroup.LayoutParams.MATCH_PARENT
            )
            bg.setBackgroundResource(R.drawable.bg_rounded_circle)
            bg.backgroundTintList = android.content.res.ColorStateList.valueOf(Color.parseColor(color))
            frame.addView(bg)

            val check = ImageView(activity)
            check.layoutParams = android.widget.FrameLayout.LayoutParams(
                android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                android.view.ViewGroup.LayoutParams.MATCH_PARENT
            )
            check.setImageResource(R.drawable.ic_check)
            check.imageTintList = android.content.res.ColorStateList.valueOf(Color.WHITE)
            check.setPadding(8, 8, 8, 8)
            check.visibility = View.GONE
            frame.addView(check)

            frame.setOnClickListener {
                profileSelectedColor = color
                hasProfileChanges = true
                updatePreview()
                for (i in 0 until gridColors.childCount) {
                    val child = gridColors.getChildAt(i) as android.widget.FrameLayout
                    child.getChildAt(1).visibility = View.GONE
                }
                check.visibility = View.VISIBLE
            }

            gridColors.addView(frame)
        }

        // Badges adapter
        val adapter = object : RecyclerView.Adapter<ProfileBadgeViewHolder>() {
            override fun onCreateViewHolder(parent: android.view.ViewGroup, viewType: Int): ProfileBadgeViewHolder {
                val v = activity.layoutInflater.inflate(R.layout.item_profile_badge_selection, parent, false)
                return ProfileBadgeViewHolder(v)
            }

            override fun onBindViewHolder(holder: ProfileBadgeViewHolder, position: Int) {
                val badge = availableProfileBadges[position]
                val iv = holder.itemView.findViewById<ImageView>(R.id.ivBadgeIcon)
                val check = holder.itemView.findViewById<View>(R.id.badgeSelectionOverlay)

                val badgeIcon = getBadgeUrl(badge.type, badge.count)
                if (badgeIcon != null) Glide.with(iv).load(badgeIcon).into(iv)

                val isSelected = profileSelectedBadges.contains(badge.type)
                check.visibility = if (isSelected) View.VISIBLE else View.GONE

                val isLocked = badge.type == "broadcaster" || badge.type == "moderator"
                holder.itemView.alpha = if (isLocked) 0.5f else 1.0f

                holder.itemView.setOnClickListener {
                    if (isLocked) return@setOnClickListener
                    if (isSelected) {
                        profileSelectedBadges.remove(badge.type)
                    } else {
                        val total = profileSelectedBadges.size + profileSelectedBadgesV2.size
                        if (total < 4) {
                            badge.type?.let { t -> profileSelectedBadges.add(t) }
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

                            // Populate selectable global badges from badges_v2
                            val allBadgesV2 = identity?.badgesV2
                                ?.sortedBy { it.sortOrder ?: Int.MAX_VALUE }
                                ?: emptyList()

                            if (allBadgesV2.isNotEmpty()) {
                                availableBadgesV2.clear()
                                availableBadgesV2.addAll(allBadgesV2)
                                profileSelectedBadgesV2.clear()
                                allBadgesV2.filter { it.selected == true }
                                    .mapNotNull { it.name }
                                    .forEach { profileSelectedBadgesV2.add(it) }

                                globalBadgesContainer.removeAllViews()

                                fun refreshGlobalBadgeOverlays() {
                                    for (i in 0 until globalBadgesContainer.childCount) {
                                        val frame = globalBadgesContainer.getChildAt(i) as? FrameLayout ?: continue
                                        val name = frame.tag as? String ?: continue
                                        frame.findViewById<View>(R.id.badgeSelectionOverlay)?.visibility =
                                            if (profileSelectedBadgesV2.contains(name)) View.VISIBLE else View.GONE
                                    }
                                }

                                allBadgesV2.forEach { badge ->
                                    val name = badge.name ?: return@forEach

                                    val item = activity.layoutInflater.inflate(
                                        R.layout.item_profile_badge_selection,
                                        globalBadgesContainer,
                                        false
                                    ) as FrameLayout
                                    item.tag = name

                                    val iv = item.findViewById<ImageView>(R.id.ivBadgeIcon)
                                    val overlay = item.findViewById<View>(R.id.badgeSelectionOverlay)
                                    overlay.visibility = if (profileSelectedBadgesV2.contains(name)) View.VISIBLE else View.GONE

                                    when (name) {
                                        "verified"   -> iv.setImageResource(R.drawable.ic_badge_verified)
                                        "staff"      -> iv.setImageResource(R.drawable.ic_badge_staff)
                                        "og"         -> iv.setImageResource(R.drawable.ic_badge_og)
                                        "sub_gifter" -> iv.setImageResource(R.drawable.ic_badge_sub_gifter)
                                        "sidekick"   -> iv.setImageResource(R.drawable.ic_badge_sidekick)
                                        "bot"        -> iv.setImageResource(R.drawable.ic_badge_bot)
                                        else         -> {
                                            if (!badge.imageUrl.isNullOrEmpty()) {
                                                Glide.with(activity).load(badge.imageUrl).into(iv)
                                            }
                                        }
                                    }

                                    item.setOnClickListener {
                                        if (profileSelectedBadgesV2.contains(name)) {
                                            profileSelectedBadgesV2.remove(name)
                                        } else {
                                            val total = profileSelectedBadges.size + profileSelectedBadgesV2.size
                                            if (total < 4) {
                                                profileSelectedBadgesV2.add(name)
                                            } else {
                                                android.widget.Toast.makeText(activity, activity.getString(R.string.max_badges_reached, 4), android.widget.Toast.LENGTH_SHORT).show()
                                            }
                                        }
                                        hasProfileChanges = true
                                        refreshGlobalBadgeOverlays()
                                        updatePreview()
                                    }

                                    globalBadgesContainer.addView(item)
                                }

                                labelGlobalBadges.visibility = View.VISIBLE
                                globalBadgesContainer.visibility = View.VISIBLE
                                emptyGlobalBadges.visibility = View.GONE
                            } else {
                                labelGlobalBadges.visibility = View.VISIBLE
                                emptyGlobalBadges.visibility = View.VISIBLE
                            }

                            isProfileIdentityLoaded = true
                            adapter.notifyDataSetChanged()
                            emptyChannelBadges.visibility =
                                if (availableProfileBadges.isEmpty()) View.VISIBLE else View.GONE
                            updatePreview()

                            for (i in 0 until gridColors.childCount) {
                                val child = gridColors.getChildAt(i) as android.widget.FrameLayout
                                val childBg = child.getChildAt(0)
                                val childCheck = child.getChildAt(1)
                                val bgColor = childBg.backgroundTintList?.defaultColor
                                val targetColor = Color.parseColor(profileSelectedColor)
                                childCheck.visibility = if (bgColor == targetColor) View.VISIBLE else View.GONE
                            }
                        }
                    }
                } catch (e: Exception) {
                    Log.e("ChatSettings", "Profile identity error: ${e.message}")
                }
            }
        } else {
            updatePreview()
        }

        // Load level progress
        if (!token.isNullOrEmpty()) {
            val cached = activity.cachedUserLevel
            if (cached != null) {
                applyLevelData(cached, profileLevelCard, profileLevelBadge, profileLevelText, profileLevelProgress, profileLevelProgressText)
            }

            lifecycleScope.launch {
                repository.getUserLevel(token).onSuccess { newData ->
                    activity.runOnUiThread {
                        val oldData = activity.cachedUserLevel
                        activity.cachedUserLevel = newData
                        if (oldData == null) {
                            applyLevelData(newData, profileLevelCard, profileLevelBadge, profileLevelText, profileLevelProgress, profileLevelProgressText)
                        } else {
                            animateLevelUpdate(oldData, newData, profileLevelCard, profileLevelBadge, profileLevelText, profileLevelProgress, profileLevelProgressText)
                        }
                    }
                }
            }
        }
    }

    private fun updatePreviewBadges(
        layout: LinearLayout,
        selectedTypes: List<String>,
        allBadges: List<ChatIdentityBadge>,
        selectedV2Names: List<String> = emptyList(),
        allBadgesV2: List<dev.xacnio.kciktv.shared.data.model.ChatIdentityBadgeV2> = emptyList()
    ) {
        layout.removeAllViews()
        val size = (20 * activity.resources.displayMetrics.density).toInt()
        val margin = (2 * activity.resources.displayMetrics.density).toInt()

        // Build a unified list of (sortOrder, render lambda) and sort together
        val entries = mutableListOf<Pair<Int, () -> Unit>>()

        allBadgesV2
            .filter { it.name != null && selectedV2Names.contains(it.name) }
            .forEach { badge ->
                entries.add(Pair(badge.sortOrder ?: Int.MAX_VALUE) {
                    val iv = ImageView(activity)
                    val params = LinearLayout.LayoutParams(size, size)
                    params.setMargins(0, 0, margin, 0)
                    iv.layoutParams = params
                    iv.scaleType = ImageView.ScaleType.FIT_CENTER
                    val drawableRes = when (badge.name) {
                        "verified"   -> R.drawable.ic_badge_verified
                        "staff"      -> R.drawable.ic_badge_staff
                        "og"         -> R.drawable.ic_badge_og
                        "sub_gifter" -> R.drawable.ic_badge_sub_gifter
                        "sidekick"   -> R.drawable.ic_badge_sidekick
                        "bot"        -> R.drawable.ic_badge_bot
                        else         -> null
                    }
                    if (drawableRes != null) {
                        iv.setImageResource(drawableRes)
                        layout.addView(iv)
                    } else if (!badge.imageUrl.isNullOrEmpty()) {
                        Glide.with(iv).load(badge.imageUrl).into(iv)
                        layout.addView(iv)
                    }
                })
            }

        selectedTypes.forEach { type ->
            val badge = allBadges.find { it.type == type }
            entries.add(Pair(badge?.sortOrder ?: Int.MAX_VALUE) {
                val iv = ImageView(activity)
                val params = LinearLayout.LayoutParams(size, size)
                params.setMargins(0, 0, margin, 0)
                iv.layoutParams = params
                val badgeIcon = getBadgeUrl(type, badge?.count)
                Glide.with(iv).load(badgeIcon).into(iv)
                layout.addView(iv)
            })
        }

        entries.sortBy { it.first }
        entries.forEach { it.second() }
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
        val badgesV2 = profileSelectedBadgesV2.toList()
        val color = profileSelectedColor

        lifecycleScope.launch {
            try {
                repository.updateChatIdentity(channelId, userId, token, badges, badgesV2, color)
                hasProfileChanges = false
                Log.d("ChatSettings", "Profile identity saved successfully")
            } catch (e: Exception) {
                Log.e("ChatSettings", "Failed to save profile identity: ${e.message}")
            }
        }
    }

    private fun setupAnimationSettings(view: View) {
        val optionsContainer = view.findViewById<LinearLayout>(R.id.animationOptionsContainer)
        val textChatAnimationValue = view.findViewById<TextView>(R.id.textChatAnimationValue)

        val density = activity.resources.displayMetrics.density
        val paddingH = (16 * density).toInt()
        val paddingV = (12 * density).toInt()

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

        animations.forEach { (key, nameRes) ->
            val itemLayout = LinearLayout(activity).apply {
                orientation = LinearLayout.HORIZONTAL
                layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
                gravity = android.view.Gravity.CENTER_VERTICAL
                setPadding(paddingH, paddingV, paddingH, paddingV)
                background = activity.getDrawable(R.drawable.bg_ripple_rounded)
            }

            val textView = TextView(activity).apply {
                text = activity.getString(nameRes)
                textSize = 14f
                setTextColor(if (prefs.chatMessageAnimation == key) Color.WHITE else Color.parseColor("#AAAAAA"))
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
                textChatAnimationValue.text = getChatAnimationValue()

                for (i in 0 until optionsContainer.childCount) {
                    val child = optionsContainer.getChildAt(i) as LinearLayout
                    child.getChildAt(1).visibility = View.GONE
                    (child.getChildAt(0) as TextView).setTextColor(Color.parseColor("#AAAAAA"))
                }
                checkIcon.visibility = View.VISIBLE
                textView.setTextColor(Color.WHITE)
            }

            optionsContainer.addView(itemLayout)
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

    private fun applyLevelData(
        data: UserLevelData,
        levelCard: View,
        badgeView: ImageView,
        levelText: TextView,
        progressBar: ProgressBar,
        progressText: TextView
    ) {
        levelText.text = activity.getString(R.string.level_format, data.level)
        progressBar.progress = data.progressPercent
        progressText.text = activity.getString(
            R.string.level_towards_next_format,
            data.progressPercent,
            data.level + 1
        )
        val badgeUrl = data.badge?.imageUrl
        if (!badgeUrl.isNullOrEmpty()) {
            Glide.with(activity).load(badgeUrl).into(badgeView)
        }
        levelCard.visibility = View.VISIBLE
    }

    private fun animateLevelUpdate(
        oldData: UserLevelData,
        newData: UserLevelData,
        levelCard: View,
        badgeView: ImageView,
        levelText: TextView,
        progressBar: ProgressBar,
        progressText: TextView
    ) {
        levelCard.visibility = View.VISIBLE
        val leveledUp = newData.level > oldData.level

        val badgeUrl = newData.badge?.imageUrl
        if (!badgeUrl.isNullOrEmpty() && badgeUrl != oldData.badge?.imageUrl) {
            Glide.with(activity).load(badgeUrl).into(badgeView)
        }

        progressText.text = activity.getString(
            R.string.level_towards_next_format,
            newData.progressPercent,
            newData.level + 1
        )

        if (leveledUp) {
            levelText.text = activity.getString(R.string.level_format, newData.level)

            ValueAnimator.ofInt(oldData.progressPercent, 100).apply {
                duration = 400
                interpolator = DecelerateInterpolator()
                addUpdateListener { progressBar.progress = it.animatedValue as Int }
                addListener(object : AnimatorListenerAdapter() {
                    override fun onAnimationEnd(animation: android.animation.Animator) {
                        progressBar.progress = 0
                        ValueAnimator.ofInt(0, newData.progressPercent).apply {
                            duration = 600
                            interpolator = DecelerateInterpolator()
                            addUpdateListener { progressBar.progress = it.animatedValue as Int }
                            start()
                        }
                    }
                })
                start()
            }

            levelText.animate().scaleX(1.35f).scaleY(1.35f).setDuration(200).withEndAction {
                levelText.animate().scaleX(1f).scaleY(1f).setDuration(200).start()
            }.start()
            badgeView.animate().scaleX(1.25f).scaleY(1.25f).setDuration(200).withEndAction {
                badgeView.animate().scaleX(1f).scaleY(1f).setDuration(200).start()
            }.start()
        } else {
            levelText.text = activity.getString(R.string.level_format, newData.level)

            ValueAnimator.ofInt(oldData.progressPercent, newData.progressPercent).apply {
                duration = 600
                interpolator = DecelerateInterpolator()
                addUpdateListener { progressBar.progress = it.animatedValue as Int }
                start()
            }
        }
    }

    private class ProfileBadgeViewHolder(view: View) : RecyclerView.ViewHolder(view)
}
