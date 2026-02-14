/**
 * File: ModActionsManager.kt
 *
 * Description: Provides the interface for Moderator Actions.
 * It allows authorized users to toggle chat modes (Slow, Followers-only, Sub-only, etc.)
 * and update stream metadata such as title, category, and content rating.
 *
 * Author: Xacnio
 *
 */
package dev.xacnio.kciktv.mobile.ui.chat

import android.content.Context
import android.util.DisplayMetrics
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.lifecycle.lifecycleScope
import com.google.android.material.bottomsheet.BottomSheetDialog
import dev.xacnio.kciktv.mobile.MobilePlayerActivity
import dev.xacnio.kciktv.R
import dev.xacnio.kciktv.shared.data.api.RetrofitClient
import dev.xacnio.kciktv.shared.data.model.CategoryInfo
import dev.xacnio.kciktv.shared.data.model.TopCategory
import dev.xacnio.kciktv.shared.data.model.BannerInfo
import dev.xacnio.kciktv.shared.data.prefs.AppPreferences
import dev.xacnio.kciktv.shared.data.repository.ChannelRepository
import dev.xacnio.kciktv.mobile.ui.sheet.CategoryPickerSheetHelper
import kotlinx.coroutines.launch
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import dev.xacnio.kciktv.mobile.ui.chat.OverlayManager

class ModActionsManager(
    private val activity: MobilePlayerActivity,
    private val repository: ChannelRepository,
    private val prefs: AppPreferences,
    private val overlayManager: OverlayManager
) {
    private var modActionsDialog: BottomSheetDialog? = null
    private var modActionsUpdateCallback: (() -> Unit)? = null
    private val TAG = "ModActionsManager"

    fun showModActionsSheet() {
        val dialog = BottomSheetDialog(activity)
        modActionsDialog = dialog
        val view = activity.layoutInflater.inflate(R.layout.bottom_sheet_mod_actions, null)
        var isUpdating = false
        
        // Get current channel slug
        val channelSlug = activity.currentChannel?.slug ?: return
        
        // Views
        val switchSlowMode = view.findViewById<androidx.appcompat.widget.SwitchCompat>(R.id.switchSlowMode)
        val switchFollowersMode = view.findViewById<androidx.appcompat.widget.SwitchCompat>(R.id.switchFollowersMode)
        val switchSubscribersMode = view.findViewById<androidx.appcompat.widget.SwitchCompat>(R.id.switchSubscribersMode)
        val switchEmotesMode = view.findViewById<androidx.appcompat.widget.SwitchCompat>(R.id.switchEmotesMode)
        val switchMatureContent = view.findViewById<androidx.appcompat.widget.SwitchCompat>(R.id.switchMatureContent)
        
        // Apply theme color to switches
        val themeColor = prefs.themeColor
        val themeColorStateList = android.content.res.ColorStateList(
            arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf()),
            intArrayOf(themeColor, 0xFF333333.toInt())
        )
        val thumbColorStateList = android.content.res.ColorStateList(
            arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf()),
            intArrayOf(themeColor, 0xFFFFFFFF.toInt())
        )
        listOf(switchSlowMode, switchFollowersMode, switchSubscribersMode, switchEmotesMode, switchMatureContent).forEach { switch ->
            switch.trackTintList = themeColorStateList
            switch.thumbTintList = thumbColorStateList
        }
        
        // Apply theme color to loading indicator and icon
        val colorStateList = android.content.res.ColorStateList.valueOf(themeColor)
        view.findViewById<android.widget.ProgressBar>(R.id.modActionsLoading)?.indeterminateTintList = colorStateList
        view.findViewById<android.widget.ImageView>(R.id.modActionsHeaderIcon)?.imageTintList = colorStateList
        
        val slowModeValueText = view.findViewById<TextView>(R.id.slowModeValueText)
        val followersModeValueText = view.findViewById<TextView>(R.id.followersModeValueText)
        val slowModeRow = view.findViewById<LinearLayout>(R.id.slowModeRow)
        val followersModeRow = view.findViewById<LinearLayout>(R.id.followersModeRow)
        
        val streamTitleValueText = view.findViewById<TextView>(R.id.streamTitleValueText)
        val categoryValueText = view.findViewById<TextView>(R.id.categoryValueText)
        val rowStreamTitle = view.findViewById<LinearLayout>(R.id.rowStreamTitle)
        val rowCategory = view.findViewById<LinearLayout>(R.id.rowCategory)
        
        // Helper to format duration
        fun formatSlowModeDuration(seconds: Int): String {
            return if (seconds >= 60) {
                activity.getString(R.string.mod_duration_format_minutes, seconds / 60)
            } else {
                activity.getString(R.string.mod_duration_format_seconds, seconds)
            }
        }
        
        fun formatFollowersDuration(minutes: Int): String {
            return when {
                minutes == 0 -> activity.getString(R.string.mod_followers_any)
                minutes >= 1440 -> activity.getString(R.string.mod_duration_format_days, minutes / 1440)
                minutes >= 60 -> activity.getString(R.string.mod_duration_format_hours, minutes / 60)
                else -> activity.getString(R.string.mod_duration_format_minutes, minutes)
            }
        }
        
        // Update UI from current state
        fun updateUI() {
            val wasUpdating = isUpdating
            isUpdating = true
            val chatroom = activity.currentChatroom
            val isSlowModeOn = chatroom?.slowMode == true
            val isFollowersOn = chatroom?.followersMode == true
            
            switchSlowMode.isChecked = isSlowModeOn
            switchFollowersMode.isChecked = isFollowersOn
            switchSubscribersMode.isChecked = chatroom?.subscribersMode == true
            switchEmotesMode.isChecked = chatroom?.emotesMode == true
            
            val slowModeInterval = chatroom?.slowModeInterval ?: 5
            val followersMinDuration = chatroom?.followersMinDuration ?: 0
            
            slowModeValueText.text = if (isSlowModeOn) formatSlowModeDuration(slowModeInterval) else activity.getString(R.string.off)
            followersModeValueText.text = if (isFollowersOn) formatFollowersDuration(followersMinDuration) else activity.getString(R.string.off)
            
            // Channel Info (Title & Category)
            val channel = activity.currentChannel
            
            channel?.let {
                streamTitleValueText.text = it.title
                categoryValueText.text = it.categoryName ?: activity.getString(R.string.off)
                switchMatureContent.isChecked = it.isMature
            }
            
            isUpdating = wasUpdating
        }
        
        // Initial UI update
        updateUI()

        // Fetch current stream info (title, category) to ensure metadata is fresh
        activity.lifecycleScope.launch {
            repository.getStreamInfo(channelSlug, prefs.authToken).onSuccess { info: dev.xacnio.kciktv.shared.data.model.StreamInfoResponse ->
                activity.runOnUiThread {
                    // Update currentChannel object and optionally in allChannels list
                    val index = activity.currentChannelIndex
                    if (index >= 0 && index < activity.allChannels.size && activity.allChannels[index].slug == channelSlug) {
                        val old = activity.allChannels[index]
                        activity.allChannels[index] = old.copy(
                            title = info.streamTitle ?: old.title,
                            categoryName = info.category?.name ?: old.categoryName,
                            categorySlug = info.category?.slug ?: old.categorySlug,
                            categoryId = info.category?.id ?: old.categoryId,
                            isMature = info.isMature ?: old.isMature
                        )
                    }
                    
                    val cur = activity.currentChannel
                    if (cur != null && cur.slug == channelSlug) {
                        activity.currentChannel = cur.copy(
                            title = info.streamTitle ?: cur.title,
                            categoryName = info.category?.name ?: cur.categoryName,
                            categorySlug = info.category?.slug ?: cur.categorySlug,
                            categoryId = info.category?.id ?: cur.categoryId,
                            isMature = info.isMature ?: cur.isMature
                        )
                    }
                    updateUI()
                }
            }.onFailure { e ->
                Log.e(TAG, "Failed to fetch stream info for moderation", e)
            }
        }
        
        // Store callback for external updates
        modActionsUpdateCallback = { 
            activity.runOnUiThread { updateUI() }
        }
        
        // API helper - sends only the changed field
        fun updateSetting(field: String, value: Any) {
            val token = prefs.authToken ?: return
            
            isUpdating = true
            activity.lifecycleScope.launch {
                try {
                    val jsonBody = when (value) {
                        is Boolean -> "{\"$field\": $value}"
                        is Int -> "{\"$field\": $value}"
                        else -> {
                            isUpdating = false
                            return@launch
                        }
                    }
                    val requestBody = jsonBody.toRequestBody("application/json".toMediaType())
                    
                    Log.d(TAG, "Updating chatroom for $channelSlug: $jsonBody")
                    
                    val response = RetrofitClient.channelService
                        .updateChatroomSettings(channelSlug, "Bearer $token", requestBody)
                    
                    activity.runOnUiThread {
                        if (response.isSuccessful) {
                            Toast.makeText(activity, R.string.mod_settings_updated, Toast.LENGTH_SHORT).show()
                            // Update local state
                            activity.currentChatroom = when (field) {
                                "slow_mode" -> activity.currentChatroom?.copy(slowMode = value as Boolean)
                                "message_interval" -> activity.currentChatroom?.copy(slowModeInterval = value as Int)
                                "followers_mode" -> activity.currentChatroom?.copy(followersMode = value as Boolean)
                                "following_min_duration" -> activity.currentChatroom?.copy(followersMinDuration = value as Int)
                                "subscribers_mode" -> activity.currentChatroom?.copy(subscribersMode = value as Boolean)
                                "emotes_mode" -> activity.currentChatroom?.copy(emotesMode = value as Boolean)
                                else -> activity.currentChatroom
                            }
                            activity.updateChatroomHint(activity.currentChatroom)
                        } else {
                            if (response.code() == 422) {
                                val errorBody = response.errorBody()?.string()
                                Log.e(TAG, "Validation error (422): $errorBody")
                                Toast.makeText(activity, R.string.mod_settings_update_failed, Toast.LENGTH_SHORT).show()
                            } else {
                                Toast.makeText(activity, R.string.mod_settings_update_failed, Toast.LENGTH_SHORT).show()
                            }
                        }
                        // Re-sync UI (either sets new success state or reverts to previous)
                        updateUI()
                        isUpdating = false
                    }
                } catch (e: Exception) {
                    activity.runOnUiThread {
                        Toast.makeText(activity, R.string.mod_settings_update_failed, Toast.LENGTH_SHORT).show()
                        updateUI()
                        isUpdating = false
                    }
                    Log.e(TAG, "Failed to update chatroom settings", e)
                }
            }
        }
        
        // Stream Title Click
        rowStreamTitle.setOnClickListener {
            val isLive = activity.currentChannel?.isLive == true
            if (!activity.isChannelOwner && !isLive) {
                Toast.makeText(activity, R.string.mod_error_metadata_offline, Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            
            val currentTitle = activity.currentChannel?.title ?: ""
            val sheetDialog = BottomSheetDialog(activity, R.style.BottomSheetDialogTheme)
            val sheetView = activity.layoutInflater.inflate(R.layout.dialog_edit_title_sheet, null)
            sheetDialog.setContentView(sheetView)
            
            val editTitle = sheetView.findViewById<EditText>(R.id.editTitle)
            val btnConfirm = sheetView.findViewById<Button>(R.id.btnConfirm)
            val btnCancel = sheetView.findViewById<Button>(R.id.btnCancel)
            
            editTitle.setText(currentTitle)
            editTitle.setSelection(currentTitle.length)
            
            btnConfirm.setOnClickListener {
                val newTitle = editTitle.text.toString()
                if (newTitle.isNotEmpty()) {
                    updateChannelMetadata(newTitle = newTitle)
                    sheetDialog.dismiss()
                }
            }
            
            btnCancel.setOnClickListener {
                sheetDialog.dismiss()
            }
            
            // trackBottomSheet(sheetDialog) // Assuming not critical or handled by internal logic of activity if needed, but here it's manager class
            sheetDialog.show()
            
            // Auto open keyboard
            editTitle.requestFocus()
            val imm = activity.getSystemService(Context.INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager
            imm.showSoftInput(editTitle, android.view.inputmethod.InputMethodManager.SHOW_IMPLICIT)
        }
        
        // Category Click
        rowCategory.setOnClickListener {
            val isLive = activity.currentChannel?.isLive == true
            if (!activity.isChannelOwner && !isLive) {
                Toast.makeText(activity, R.string.mod_error_metadata_offline, Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            showCategorySearchDialog { category ->
                updateChannelMetadata(newCategoryId = category.id)
                // Temporarily update UI name if possible
                val old = activity.currentChannel
                if (old != null) activity.currentChannel = old.copy(
                    categoryName = category.name, 
                    categorySlug = category.slug,
                    categoryId = category.id
                )
                updateUI()
            }
        }
        
        switchMatureContent.setOnCheckedChangeListener { _, isChecked ->
            if (isUpdating) return@setOnCheckedChangeListener
            val isLive = activity.currentChannel?.isLive == true
            if (!activity.isChannelOwner && !isLive) {
                Toast.makeText(activity, R.string.mod_error_metadata_offline, Toast.LENGTH_SHORT).show()
                updateUI()
                return@setOnCheckedChangeListener
            }
            updateChannelMetadata(isMature = isChecked)
        }

        // Slow mode row click - open duration picker
        slowModeRow.setOnClickListener {
            if (switchSlowMode.isChecked) {
                showDurationPickerDialog(
                    title = activity.getString(R.string.mod_slow_mode_title),
                    currentValue = activity.currentChatroom?.slowModeInterval ?: 5,
                    isSlowMode = true,
                    onConfirm = { seconds ->
                        updateSetting("message_interval", seconds)
                    }
                )
            }
        }
        
        // Followers mode row click - open duration picker
        followersModeRow.setOnClickListener {
            if (switchFollowersMode.isChecked) {
                showDurationPickerDialog(
                    title = activity.getString(R.string.mod_followers_mode_title),
                    currentValue = activity.currentChatroom?.followersMinDuration ?: 0,
                    isSlowMode = false,
                    onConfirm = { minutes ->
                        updateSetting("following_min_duration", minutes)
                    }
                )
            }
        }
        
        switchSlowMode.setOnCheckedChangeListener { _, isChecked ->
            if (isUpdating) return@setOnCheckedChangeListener
            
            if (isChecked) {
                // Show duration picker when enabling
                showDurationPickerDialog(
                    title = activity.getString(R.string.mod_slow_mode_title),
                    currentValue = activity.currentChatroom?.slowModeInterval ?: 5,
                    isSlowMode = true,
                    onConfirm = { seconds ->
                        // Enable with selected duration
                        val token = prefs.authToken ?: return@showDurationPickerDialog
                        isUpdating = true
                        activity.lifecycleScope.launch {
                            try {
                                val jsonBody = "{\"slow_mode\": true, \"message_interval\": $seconds}"
                                val requestBody = jsonBody.toRequestBody("application/json".toMediaType())
                                val response = RetrofitClient.channelService
                                    .updateChatroomSettings(channelSlug, "Bearer $token", requestBody)
                                activity.runOnUiThread {
                                    if (response.isSuccessful) {
                                        Toast.makeText(activity, R.string.mod_settings_updated, Toast.LENGTH_SHORT).show()
                                        activity.currentChatroom = activity.currentChatroom?.copy(slowMode = true, slowModeInterval = seconds)
                                        activity.updateChatroomHint(activity.currentChatroom)
                                    } else {
                                        Toast.makeText(activity, R.string.mod_settings_update_failed, Toast.LENGTH_SHORT).show()
                                    }
                                    updateUI()
                                    isUpdating = false
                                }
                            } catch (_: Exception) {
                                activity.runOnUiThread { 
                                    Toast.makeText(activity, R.string.mod_settings_update_failed, Toast.LENGTH_SHORT).show()
                                    updateUI()
                                    isUpdating = false
                                }
                            }
                        }
                    },
                    onCancel = {
                        activity.runOnUiThread {
                            updateUI()
                        }
                    }
                )
            } else {
                updateSetting("slow_mode", false)
            }
        }
        
        switchFollowersMode.setOnCheckedChangeListener { _, isChecked ->
            if (isUpdating) return@setOnCheckedChangeListener
            
            if (isChecked) {
                showDurationPickerDialog(
                    title = activity.getString(R.string.mod_followers_mode_title),
                    currentValue = activity.currentChatroom?.followersMinDuration ?: 0,
                    isSlowMode = false,
                    onConfirm = { minutes ->
                        val token = prefs.authToken ?: return@showDurationPickerDialog
                        isUpdating = true
                        activity.lifecycleScope.launch {
                            try {
                                val jsonBody = "{\"followers_mode\": true, \"following_min_duration\": $minutes}"
                                val requestBody = jsonBody.toRequestBody("application/json".toMediaType())
                                val response = RetrofitClient.channelService
                                    .updateChatroomSettings(channelSlug, "Bearer $token", requestBody)
                                activity.runOnUiThread {
                                    if (response.isSuccessful) {
                                        Toast.makeText(activity, R.string.mod_settings_updated, Toast.LENGTH_SHORT).show()
                                        activity.currentChatroom = activity.currentChatroom?.copy(followersMode = true, followersMinDuration = minutes)
                                        activity.updateChatroomHint(activity.currentChatroom)
                                    } else {
                                        Toast.makeText(activity, R.string.mod_settings_update_failed, Toast.LENGTH_SHORT).show()
                                    }
                                    updateUI()
                                    isUpdating = false
                                }
                            } catch (_: Exception) {
                                activity.runOnUiThread { 
                                    Toast.makeText(activity, R.string.mod_settings_update_failed, Toast.LENGTH_SHORT).show()
                                    updateUI()
                                    isUpdating = false
                                }
                            }
                        }
                    },
                    onCancel = {
                        activity.runOnUiThread {
                            updateUI()
                        }
                    }
                )
            } else {
                updateSetting("followers_mode", false)
            }
        }
        
        switchSubscribersMode.setOnCheckedChangeListener { _, isChecked ->
            if (isUpdating) return@setOnCheckedChangeListener
            updateSetting("subscribers_mode", isChecked)
        }
        
        switchEmotesMode.setOnCheckedChangeListener { _, isChecked ->
            if (isUpdating) return@setOnCheckedChangeListener
            updateSetting("emotes_mode", isChecked)
        }
        
        // Create Poll Button
        view.findViewById<View>(R.id.rowCreatePoll)?.setOnClickListener {
            dialog.dismiss()
            overlayManager.openCreatePollSheet()
        }
        
        dialog.setOnDismissListener {
            modActionsDialog = null
            modActionsUpdateCallback = null
        }
        dialog.setContentView(view)
        dialog.show()
    }

    fun openCategoryPicker() {
        // Only allow changing category if live (same logic as in the row click)
        val isLive = activity.currentChannel?.isLive == true
        if (!activity.isChannelOwner && !isLive) {
            Toast.makeText(activity, R.string.mod_error_metadata_offline, Toast.LENGTH_SHORT).show()
            return
        }

        showCategorySearchDialog { category ->
            // Temporarily update UI name if possible to show immediate feedback
            val old = activity.currentChannel
            if (old != null) activity.currentChannel = old.copy(
                categoryName = category.name, 
                categorySlug = category.slug,
                categoryId = category.id
            )
            updateChannelMetadata(newCategoryId = category.id)
        }
    }

    private fun updateChannelMetadata(newTitle: String? = null, newCategoryId: Long? = null, isMature: Boolean? = null) {
        val token = prefs.authToken ?: return
        val channelSlug = activity.currentChannel?.slug ?: return
        
        activity.lifecycleScope.launch {
            try {
                val result = repository.updateChannelInfo(
                    slug = channelSlug,
                    token = token,
                    title = newTitle,
                    categoryId = newCategoryId,
                    isMature = isMature
                )
                
                activity.runOnUiThread {
                    if (result.isSuccess) {
                        Toast.makeText(activity, R.string.mod_settings_updated, Toast.LENGTH_SHORT).show()
                        // Update local currentChannel
                        val old = activity.currentChannel
                        if (old != null) activity.currentChannel = old.copy(
                            title = newTitle ?: old.title,
                            categoryId = newCategoryId ?: old.categoryId,
                            isMature = isMature ?: old.isMature
                        )
                        notifyModActionsUpdate()
                    } else {
                        Toast.makeText(activity, R.string.mod_settings_update_failed, Toast.LENGTH_SHORT).show()
                    }
                }
            } catch (e: Exception) {
                activity.runOnUiThread {
                    Toast.makeText(activity, R.string.mod_settings_update_failed, Toast.LENGTH_SHORT).show()
                }
                Log.e(TAG, "Failed manual metadata update", e)
            }
        }
    }
    
    private fun showDurationPickerDialog(
        title: String,
        currentValue: Int,
        isSlowMode: Boolean,
        onConfirm: (Int) -> Unit,
        onCancel: (() -> Unit)? = null
    ) {
        val dialogView = activity.layoutInflater.inflate(R.layout.dialog_duration_picker, null)
        val dialog = AlertDialog.Builder(activity, R.style.Theme_KcikTV_Dialog)
            .setView(dialogView)
            .create()
        
        dialog.show()
        
        // Expand dialog width
        dialog.window?.let { window ->
            val displayMetrics = activity.resources.displayMetrics
            val width = (displayMetrics.widthPixels * 0.9).toInt()
            window.setLayout(width, ViewGroup.LayoutParams.WRAP_CONTENT)
            window.setGravity(Gravity.CENTER)
            window.setBackgroundDrawableResource(android.R.color.transparent)
        }
        
        val titleView = dialogView.findViewById<TextView>(R.id.dialogTitle)
        val presetContainer = dialogView.findViewById<ViewGroup>(R.id.presetContainer)
        val customInput = dialogView.findViewById<EditText>(R.id.customDurationInput)
        val unitSpinner = dialogView.findViewById<android.widget.Spinner>(R.id.durationUnitSpinner)
        val btnCancel = dialogView.findViewById<TextView>(R.id.btnCancel)
        val btnConfirm = dialogView.findViewById<TextView>(R.id.btnConfirm)
        
        titleView.text = title
        
        // Unit options
        val units = if (isSlowMode) {
            arrayOf(activity.getString(R.string.mod_unit_seconds), activity.getString(R.string.mod_unit_minutes))
        } else {
            arrayOf(activity.getString(R.string.mod_unit_minutes), activity.getString(R.string.mod_unit_hours), activity.getString(R.string.mod_unit_days))
        }
        val unitMultipliers = if (isSlowMode) intArrayOf(1, 60) else intArrayOf(1, 60, 1440)
        
        val spinnerAdapter = ArrayAdapter(activity, android.R.layout.simple_spinner_item, units)
        spinnerAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        unitSpinner.adapter = spinnerAdapter
        
        var selectedUnit: Int
        var selectedPresetValue: Int? = null
        
        // Set initial value - determine unit and value
        val (initialDisplayValue, initialUnitIndex) = if (isSlowMode) {
            // Slow mode: currentValue is in seconds
            if (currentValue >= 60 && currentValue % 60 == 0) {
                (currentValue / 60) to 1  // Minutes
            } else {
                currentValue to 0  // Seconds
            }
        } else {
            // Followers mode: currentValue is in minutes
            when {
                currentValue >= 1440 && currentValue % 1440 == 0 -> (currentValue / 1440) to 2  // Days
                currentValue >= 60 && currentValue % 60 == 0 -> (currentValue / 60) to 1  // Hours
                else -> currentValue to 0  // Minutes
            }
        }
        
        customInput.setText(initialDisplayValue.toString())
        selectedUnit = initialUnitIndex
        
        // Set spinner selection after adapter is set and add listener after initial selection
        unitSpinner.post {
            unitSpinner.setSelection(initialUnitIndex, false)  // false = don't animate
            
            // Add listener AFTER initial selection to avoid triggering on init
            unitSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
                override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                    selectedUnit = position
                    selectedPresetValue = null
                    // Clear preset selection
                    for (i in 0 until presetContainer.childCount) {
                        val c = presetContainer.getChildAt(i) as TextView
                        c.setBackgroundResource(R.drawable.bg_chip)
                        c.setTextColor(0xFFFFFFFF.toInt())
                    }
                }
                override fun onNothingSelected(parent: AdapterView<*>?) {}
            }
        }
        
        // Presets
        val presets = if (isSlowMode) {
            listOf(1 to "1s", 5 to "5s", 10 to "10s", 15 to "15s", 30 to "30s", 60 to "1m", 120 to "2m", 300 to "5m")
        } else {
            listOf(0 to activity.getString(R.string.mod_followers_any), 10 to "10m", 30 to "30m", 60 to "1h", 1440 to "1d", 10080 to "1w")
        }
        
        presetContainer.removeAllViews()
        presets.forEach { (value, label) ->
            val chip = TextView(activity).apply {
                text = label
                setTextColor(if (value == currentValue) 0xFF000000.toInt() else 0xFFFFFFFF.toInt())
                setBackgroundResource(if (value == currentValue) R.drawable.bg_chip_selected else R.drawable.bg_chip)
                setPadding(32, 16, 32, 16)
                
                val params = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                )
                params.marginEnd = 16
                layoutParams = params
                
                setOnClickListener {
                    // Update all chips
                    for (i in 0 until presetContainer.childCount) {
                        val c = presetContainer.getChildAt(i) as TextView
                        c.setBackgroundResource(R.drawable.bg_chip)
                        c.setTextColor(0xFFFFFFFF.toInt())
                    }
                    setBackgroundResource(R.drawable.bg_chip_selected)
                    setTextColor(0xFF000000.toInt())
                    selectedPresetValue = value
                    
                    // Update input field
                    if (isSlowMode) {
                        if (value >= 60) {
                            customInput.setText((value / 60).toString())
                            unitSpinner.setSelection(1)
                        } else {
                            customInput.setText(value.toString())
                            unitSpinner.setSelection(0)
                        }
                    } else {
                        when {
                            value >= 1440 -> {
                                customInput.setText((value / 1440).toString())
                                unitSpinner.setSelection(2)
                            }
                            value >= 60 -> {
                                customInput.setText((value / 60).toString())
                                unitSpinner.setSelection(1)
                            }
                            else -> {
                                customInput.setText(value.toString())
                                unitSpinner.setSelection(0)
                            }
                        }
                    }
                }
            }
            presetContainer.addView(chip)
        }
        
        btnCancel.setOnClickListener {
            dialog.dismiss()
            onCancel?.invoke()
        }
        
        btnConfirm.setOnClickListener {
            val inputValue = customInput.text.toString().toIntOrNull()
            if (inputValue == null) {
                Toast.makeText(activity, R.string.mod_error_invalid_input, Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            val finalValue = if (selectedPresetValue != null) {
                selectedPresetValue!!
            } else {
                val multiplier = unitMultipliers.getOrElse(selectedUnit) { 1 }
                inputValue * multiplier
            }
            
            // Validation
            if (isSlowMode) {
                if (finalValue !in 1..300) {
                    Toast.makeText(activity, R.string.mod_error_slow_mode_limit, Toast.LENGTH_SHORT).show()
                    return@setOnClickListener
                }
            } else {
                if (finalValue !in 0..525960) {
                    Toast.makeText(activity, R.string.mod_error_followers_limit, Toast.LENGTH_SHORT).show()
                    return@setOnClickListener
                }
            }
            
            Log.d(TAG, "Final calculated value: $finalValue")
            dialog.dismiss()
            onConfirm(finalValue)
        }

        dialog.setOnCancelListener {
            onCancel?.invoke()
        }
    }

    /*
     * Category Search Dialog Helper
     * Reuses CategoryPickerSheetHelper logic for consistent category selection UI
     */
    private fun showCategorySearchDialog(onCategorySelected: (CategoryInfo) -> Unit) {
        val helper = CategoryPickerSheetHelper(
            activity, activity.layoutInflater, activity.lifecycleScope, prefs, repository
        )
        
        helper.show(object : CategoryPickerSheetHelper.CategoryPickerListener {
            override fun getCurrentChannelSlug(): String? {
                val current = activity.currentChannel
                if (current != null && !current.slug.isNullOrEmpty()) {
                    return current.slug
                }
                val index = activity.currentChannelIndex
                if (activity.allChannels.isNotEmpty() && index in activity.allChannels.indices) {
                    return activity.allChannels[index].slug
                }
                return null
            }
            
            override fun onCategorySelected(category: TopCategory) {
                 val bannerInfo = category.banner?.let { 
                    BannerInfo(it.src) 
                }
                val info = CategoryInfo(
                    id = category.id,
                    name = category.name,
                    slug = category.slug,
                    banner = bannerInfo
                )
                
                onCategorySelected(info)
            }
        })
    }
    
    fun notifyModActionsUpdate() {
        modActionsUpdateCallback?.invoke()
    }
}
