/**
 * File: MessageActionsSheetManager.kt
 *
 * Description: Manages business logic, state, and UI interactions for Message Actions Sheet.
 *
 * Author: Xacnio
 *
 */
package dev.xacnio.kciktv.mobile.ui.sheet

import android.util.Log
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.lifecycle.lifecycleScope
import com.bumptech.glide.Glide
import com.bumptech.glide.load.DataSource
import com.bumptech.glide.load.engine.GlideException
import com.bumptech.glide.request.RequestListener
import com.bumptech.glide.request.target.Target
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.button.MaterialButton
import dev.xacnio.kciktv.mobile.MobilePlayerActivity
import dev.xacnio.kciktv.R
import dev.xacnio.kciktv.shared.data.model.ChannelUserBadge
import dev.xacnio.kciktv.shared.data.model.ChatMessage
import dev.xacnio.kciktv.shared.data.model.ChatSender
import dev.xacnio.kciktv.shared.ui.utils.BadgeRenderUtils
import dev.xacnio.kciktv.shared.ui.utils.EmoteManager
import dev.xacnio.kciktv.shared.util.ShimmerDrawable
import dev.xacnio.kciktv.shared.util.TimeUtils
import kotlinx.coroutines.launch

/**
 * Manager class for handling Message Actions Bottom Sheet UI
 * Extracted from MobilePlayerActivity for better code organization
 */
class MessageActionsSheetManager(private val activity: MobilePlayerActivity) {
    
    private val TAG = "MessageActionsSheetManager"
    
    private val prefs get() = activity.prefs
    private val repository get() = activity.repository
    private val chatStateManager get() = activity.chatStateManager
    
    var messageActionsSheet: BottomSheetDialog? = null
        private set
    
    var selectedMessage: ChatMessage? = null
        private set
    
    /**
     * Shows the message actions bottom sheet for a given message
     */
    fun showMessageActionsSheet(message: ChatMessage) {
        Log.d(TAG, "Showing message actions for: ${message.sender.username}")
        selectedMessage = message
        
        val sheetView = activity.layoutInflater.inflate(R.layout.bottom_sheet_message_actions, null)
        Log.d(TAG, "Inflated action sheet view")
        messageActionsSheet = BottomSheetDialog(activity).apply {
            setContentView(sheetView)
            
            // Configure background
            window?.findViewById<View>(com.google.android.material.R.id.design_bottom_sheet)
                ?.setBackgroundColor(android.graphics.Color.TRANSPARENT)
        }
        
        // Populate message preview
        sheetView.findViewById<TextView>(R.id.previewUsername)?.apply {
            text = message.sender.username
            try {
                val color = message.sender.color?.let { android.graphics.Color.parseColor(it) } ?: 0xFF53FC18.toInt()
                setTextColor(color)
            } catch (_: Exception) {
                setTextColor(0xFF53FC18.toInt())
            }
        }
        // Render message with emotes
        sheetView.findViewById<TextView>(R.id.previewMessage)?.let { previewText ->
            val emoteSize = (previewText.textSize * 1.5f).toInt()
            EmoteManager.renderEmoteText(previewText, message.content, emoteSize)
        }
        
        // Setup Copy Button in the sheet
        sheetView.findViewById<View>(R.id.btnCopyMessageInSheet)?.setOnClickListener {
            try {
                val clipboard = activity.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                val clip = android.content.ClipData.newPlainText("Kick Message", message.content)
                clipboard.setPrimaryClip(clip)
                Toast.makeText(activity, activity.getString(R.string.message_copied), Toast.LENGTH_SHORT).show()
                
                // Optional: Provide visual feedback on the button
                it.animate().scaleX(1.2f).scaleY(1.2f).setDuration(100).withEndAction {
                    it.animate().scaleX(1.0f).scaleY(1.0f).setDuration(100).start()
                }.start()
            } catch (e: Exception) {
                Log.e(TAG, "Failed to copy message from sheet", e)
            }
        }
        
        // Setup action buttons
        setupMessageActionButtons(sheetView, message)
        
        // Load detailed user info (Avatar, follow date, etc.)
        loadDetailedUserInfoForSheet(sheetView, message.sender)
        
        Log.d(TAG, "Showing bottom sheet...")
        messageActionsSheet?.show()
    }
    
    /**
     * Loads detailed user info for the message actions sheet
     */
    private fun loadDetailedUserInfoForSheet(sheetView: View, sender: ChatSender) {
        val currentSlug = activity.currentChannel?.slug ?: return

        val avatarView = sheetView.findViewById<ImageView>(R.id.userAvatar)
        val avatarShimmer = sheetView.findViewById<View>(R.id.avatarShimmer)
        val progress = sheetView.findViewById<View>(R.id.userDetailProgress)
        val content = sheetView.findViewById<View>(R.id.userDetailContent)
        val badgesContainer = sheetView.findViewById<LinearLayout>(R.id.previewBadgesContainer)
        val infoDivider = sheetView.findViewById<View>(R.id.badgeInfoDivider)
        val followText = sheetView.findViewById<TextView>(R.id.followDateText)
        val subText = sheetView.findViewById<TextView>(R.id.subDurationText)
        
        // Ban status views
        val banStatusContainer = sheetView.findViewById<View>(R.id.banStatusContainer)
        val banStatusTitle = sheetView.findViewById<TextView>(R.id.banStatusTitle)
        val banStatusDetail = sheetView.findViewById<TextView>(R.id.banStatusDetail)
        val unbanButton = sheetView.findViewById<MaterialButton>(R.id.unbanButton)
        
        // Moderation action views
        val actionTimeoutContainer = sheetView.findViewById<View>(R.id.actionTimeoutContainer)
        val actionBan = sheetView.findViewById<View>(R.id.actionBan)

        // Start shimmer animation on avatar placeholder
        val shimmerDrawable = ShimmerDrawable()
        avatarShimmer?.background = shimmerDrawable

        // Reset state
        progress?.visibility = View.VISIBLE
        content?.visibility = View.GONE

        activity.lifecycleScope.launch {
            repository.getChannelUserInfo(currentSlug, sender.username, prefs.authToken).onSuccess { u ->
                activity.runOnUiThread {
                    // Profile Picture with fallback logic
                    val profileUrl = if (!u.profilePic.isNullOrEmpty()) {
                        u.profilePic
                    } else {
                        val hash = sender.username.hashCode()
                        val index = (if (hash < 0) -hash else hash) % 6 + 1
                        "https://kick.com/img/default-profile-pictures/default-avatar-$index.webp"
                    }
                    
                    Log.d(TAG, "Loading avatar for ${sender.username}: $profileUrl")
                    
                    Glide.with(activity)
                        .load(profileUrl)
                        .circleCrop()
                        .listener(object : RequestListener<android.graphics.drawable.Drawable> {
                            override fun onLoadFailed(
                                e: GlideException?,
                                model: Any?,
                                target: Target<android.graphics.drawable.Drawable>,
                                isFirstResource: Boolean
                            ): Boolean {
                                shimmerDrawable.stop()
                                avatarShimmer?.visibility = View.GONE
                                avatarView.visibility = View.VISIBLE
                                return false
                            }
                            
                            override fun onResourceReady(
                                resource: android.graphics.drawable.Drawable,
                                model: Any,
                                target: Target<android.graphics.drawable.Drawable>?,
                                dataSource: DataSource,
                                isFirstResource: Boolean
                            ): Boolean {
                                shimmerDrawable.stop()
                                avatarShimmer?.visibility = View.GONE
                                avatarView.visibility = View.VISIBLE
                                return false
                            }
                        })
                        .into(avatarView)

                    // Badges (Active and Inactive)
                    badgesContainer?.removeAllViews()
                    val badgeSize = (18 * activity.resources.displayMetrics.density).toInt()
                    val margin = (4 * activity.resources.displayMetrics.density).toInt()
                    
                    u.badges?.forEach { badge ->
                        BadgeRenderUtils.renderSingleBadgeIntoSheet(
                            activity, badgesContainer, badge, badgeSize, margin, chatStateManager.subscriberBadges
                        )
                    }

                    // Follow Date & Sub Info
                    var hasInfo = false
                    
                    val followDate = u.followingSince
                    if (!followDate.isNullOrEmpty()) {
                        try {
                            val cleanDate = followDate.substringBefore(".")
                            val inputFormat = java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", java.util.Locale.US)
                            val date = inputFormat.parse(cleanDate)
                            if (date != null) {
                                val outputFormat = java.text.SimpleDateFormat("dd.MM.yyyy HH:mm:ss", java.util.Locale.getDefault())
                                followText?.text = activity.getString(R.string.follow_date_format, outputFormat.format(date))
                                followText?.visibility = View.VISIBLE
                                hasInfo = true
                            }
                        } catch (_: Exception) {
                            followText?.text = activity.getString(R.string.follow_date_format, followDate)
                            followText?.visibility = View.VISIBLE
                            hasInfo = true
                        }
                    } else {
                        followText?.visibility = View.GONE
                    }

                    val months = u.subscribedFor ?: 0
                    if (months > 0) {
                        subText?.text = activity.getString(R.string.subscription_months_format, months)
                        subText?.visibility = View.VISIBLE
                        hasInfo = true
                    } else {
                        subText?.visibility = View.GONE
                    }

                    infoDivider?.visibility = if (hasInfo && (u.badges?.isNotEmpty() == true)) View.VISIBLE else View.GONE

                    // Handle ban status
                    val showModerationControls = activity.isModeratorOrOwner && prefs.isLoggedIn
                    if (u.isBanned && showModerationControls) {
                        // User is banned - show ban status and hide timeout/ban buttons
                        banStatusContainer?.visibility = View.VISIBLE
                        actionTimeoutContainer?.visibility = View.GONE
                        actionBan?.visibility = View.GONE
                        
                        if (u.isPermanentBan) {
                            banStatusTitle?.text = activity.getString(R.string.ban_permanent)
                        } else {
                            banStatusTitle?.text = activity.getString(R.string.ban_timeout)
                        }
                        
                        // Set unban button text from strings
                        unbanButton?.text = activity.getString(R.string.action_unban)
                        
                        // Build detail text with ban info
                        val detailParts = mutableListOf<String>()
                        
                        // Remaining time for timeout
                        if (!u.isPermanentBan) {
                            u.banExpiresAt?.let { expiresAt ->
                                try {
                                    val format = java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", java.util.Locale.US)
                                    format.timeZone = java.util.TimeZone.getTimeZone("UTC")
                                    val cleanedDate = expiresAt.substringBefore(".")
                                    val expirationDate = format.parse(cleanedDate)
                                    if (expirationDate != null) {
                                        val remainingMs = expirationDate.time - System.currentTimeMillis()
                                        if (remainingMs > 0) {
                                            val remainingStr = TimeUtils.formatRemainingTime(activity, remainingMs)
                                            detailParts.add(activity.getString(R.string.ban_remaining_format, remainingStr))
                                        }
                                    }
                                } catch (_: Exception) {}
                            }
                        }
                        
                        // Banned by (Try banner object, then bannedBy object, then bannerId fallback)
                        val bannerName = u.banned?.banner?.username 
                            ?: u.banned?.bannedBy?.username 
                            ?: u.banned?.bannerId?.toString()
                        
                        if (bannerName != null) {
                            detailParts.add(activity.getString(R.string.ban_banned_by_format, bannerName))
                        }
                        
                        // Ban Reason
                        u.banned?.reason?.let { reason ->
                            if (reason.isNotBlank() && !reason.equals("No reason provided", ignoreCase = true)) {
                                detailParts.add(activity.getString(R.string.ban_reason_format, reason))
                            }
                        }
                        
                        // Ban date
                        u.banned?.createdAt?.let { createdAt ->
                            try {
                                val format = java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", java.util.Locale.US)
                                format.timeZone = java.util.TimeZone.getTimeZone("UTC")
                                val cleanedDate = createdAt.substringBefore(".")
                                val banDate = format.parse(cleanedDate)
                                if (banDate != null) {
                                    val displayFormat = java.text.SimpleDateFormat("dd.MM.yyyy HH:mm", java.util.Locale.getDefault())
                                    detailParts.add(activity.getString(R.string.ban_date_format, displayFormat.format(banDate)))
                                }
                            } catch (_: Exception) {}
                        }
                        
                        if (detailParts.isNotEmpty()) {
                            banStatusDetail?.text = detailParts.joinToString(" • ")
                            banStatusDetail?.visibility = View.VISIBLE
                        } else {
                            banStatusDetail?.visibility = View.GONE
                        }
                        
                        // Unban button functionality
                        unbanButton?.setOnClickListener {
                            unbanButton.isEnabled = false
                            val username = sender.username
                            activity.lifecycleScope.launch unbanLaunch@{
                                val channel = activity.currentChannel ?: return@unbanLaunch
                                val result = repository.unbanUser(channel.slug, username, prefs.authToken ?: "")
                                activity.runOnUiThread runOnUiThreadUnban@{
                                    unbanButton.isEnabled = true
                                    result.onSuccess {
                                        // Hide ban status and show timeout/ban buttons again
                                        banStatusContainer?.visibility = View.GONE
                                        actionTimeoutContainer?.visibility = View.VISIBLE
                                        actionBan?.visibility = View.VISIBLE
                                        Toast.makeText(activity, R.string.mod_unban_success, Toast.LENGTH_SHORT).show()
                                    }.onFailure {
                                        Toast.makeText(activity, R.string.mod_unban_failed, Toast.LENGTH_SHORT).show()
                                    }
                                }
                            }
                        }
                    } else {
                        banStatusContainer?.visibility = View.GONE
                        // Keep timeout/ban buttons as set in setupMessageActionButtons
                    }

                    // Show content with animation
                    progress?.visibility = View.GONE
                    content?.apply {
                        alpha = 0f
                        visibility = View.VISIBLE
                        animate().alpha(1f).setDuration(300).start()
                    }
                }
            }.onFailure {
                Log.e(TAG, "Failed to load detailed user info for sheet: ${it.message}")
                activity.runOnUiThread {
                    shimmerDrawable.stop()
                    avatarShimmer?.visibility = View.GONE
                    avatarView.visibility = View.VISIBLE
                    progress?.visibility = View.GONE
                }
            }
        }
    }
    
    /**
     * Sets up all action buttons in the message actions sheet
     */
    private fun setupMessageActionButtons(sheetView: View, message: ChatMessage) {
        // Reply Action
        val replyAction = sheetView.findViewById<View>(R.id.actionReply)
        if (prefs.isLoggedIn) {
            replyAction?.visibility = View.VISIBLE
            replyAction?.setOnClickListener {
                messageActionsSheet?.dismiss()
                activity.chatReplyManager.prepareReply(message)
            }
        } else {
            replyAction?.visibility = View.GONE
        }
        
        // Mention Action
        val mentionAction = sheetView.findViewById<View>(R.id.actionMention)
        if (prefs.isLoggedIn) {
            mentionAction?.visibility = View.VISIBLE
            mentionAction?.setOnClickListener {
                messageActionsSheet?.dismiss()
                activity.chatUiManager.appendMention(message.sender.username)
            }
        } else {
            mentionAction?.visibility = View.GONE
        }
        
        // User Info Action
        sheetView.findViewById<View>(R.id.actionUserInfo)?.setOnClickListener {
            messageActionsSheet?.dismiss()
            activity.showUserActionsSheet(message.sender)
        }
        
        // Show moderation controls only for moderators/owners
        val showModerationControls = activity.isModeratorOrOwner && prefs.isLoggedIn
        
        sheetView.findViewById<View>(R.id.moderationDivider)?.visibility = if (showModerationControls) View.VISIBLE else View.GONE
        sheetView.findViewById<View>(R.id.actionPin)?.visibility = if (showModerationControls) View.VISIBLE else View.GONE
        sheetView.findViewById<View>(R.id.actionTimeoutContainer)?.visibility = if (showModerationControls) View.VISIBLE else View.GONE
        sheetView.findViewById<View>(R.id.actionBan)?.visibility = if (showModerationControls) View.VISIBLE else View.GONE
        sheetView.findViewById<View>(R.id.actionDelete)?.visibility = if (showModerationControls) View.VISIBLE else View.GONE
        
        if (showModerationControls) {
            // Pin Action
            sheetView.findViewById<View>(R.id.actionPin)?.setOnClickListener {
                messageActionsSheet?.dismiss()
                activity.chatModerationManager.pinMessageToChannel(message)
            }
            
            // Delete Action
            sheetView.findViewById<View>(R.id.actionDelete)?.setOnClickListener {
                messageActionsSheet?.dismiss()
                activity.chatModerationManager.deleteMessageFromChat(message)
            }
            
            // Ban Action
            sheetView.findViewById<View>(R.id.actionBan)?.setOnClickListener {
                messageActionsSheet?.dismiss()
                activity.chatModerationManager.showBanConfirmationDialog(message.sender.username)
            }
            
            // Timeout Actions with duration buttons
            setupTimeoutButtons(sheetView, message.sender.username)
        }
    }
    
    /**
     * Sets up the timeout duration buttons with their click handlers
     */
    private fun setupTimeoutButtons(sheetView: View, username: String) {
        val timeouts = mapOf(
            R.id.timeout1min to 1,
            R.id.timeout5min to 5,
            R.id.timeout1hour to 60,
            R.id.timeout1day to 1440,
            R.id.timeout1week to 10080
        )
        
        timeouts.forEach { (viewId, duration) ->
            sheetView.findViewById<View>(viewId)?.setOnClickListener {
                messageActionsSheet?.dismiss()
                activity.chatModerationManager.timeoutUserFromChat(username, duration)
            }
        }
    }
    
    /**
     * Dismisses the message actions sheet if it's showing
     */
    fun dismiss() {
        messageActionsSheet?.dismiss()
        messageActionsSheet = null
        selectedMessage = null
    }
}
