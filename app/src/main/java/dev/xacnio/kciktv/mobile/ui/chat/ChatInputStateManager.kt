/**
 * File: ChatInputStateManager.kt
 *
 * Description: Controls the state and availability of the chat input interface.
 * It manages restrictions such as slow mode timers, follower-only duration requirements,
 * and ban status, creating a valid context for when a user can send messages.
 *
 * Author: Xacnio
 *
 */
package dev.xacnio.kciktv.mobile.ui.chat

import android.view.View
import dev.xacnio.kciktv.mobile.MobilePlayerActivity
import dev.xacnio.kciktv.R

class ChatInputStateManager(private val activity: MobilePlayerActivity) {

    private val mainHandler = android.os.Handler(android.os.Looper.getMainLooper())

    private val slowModeCountdownRunnable = object : Runnable {
        override fun run() {
            val chatroom = activity.currentChatroom
            val slowModeEnabled = chatroom?.slowMode == true
            // If slow mode is enabled but interval is null/0, use default 5 seconds
            val rawInterval = chatroom?.slowModeInterval ?: 0
            val interval = if (slowModeEnabled && rawInterval <= 0) 5000L else rawInterval * 1000L

            if (slowModeEnabled && !activity.isModeratorOrOwner) {
                val elapsed = System.currentTimeMillis() - activity.lastMessageSentMillis
                if (elapsed < interval) {
                    val remainingSeconds = (((interval - elapsed) / 1000) + 1).toInt()
                    activity.binding.chatSlowModeCountdown.text = activity.getString(R.string.slow_mode_countdown_format, remainingSeconds)
                    activity.binding.chatSlowModeCountdown.visibility = View.VISIBLE
                    activity.binding.chatSendButton.isEnabled = false
                    startSlowModeCountdown()
                    return
                }
            }
            activity.binding.chatSlowModeCountdown.visibility = View.GONE
            // Re-sync input state immediately when countdown finished
            updateInputState()
        }
    }

    private val timeoutCountdownRunnable = object : Runnable {
        override fun run() {
            if (activity.isBannedFromCurrentChannel && !activity.isPermanentBan && activity.timeoutExpirationMillis > System.currentTimeMillis()) {
                activity.updateTimeoutOverlayContent()
                mainHandler.postDelayed(this, 1000)
            } else if (activity.isBannedFromCurrentChannel && !activity.isPermanentBan && activity.timeoutExpirationMillis <= System.currentTimeMillis() && activity.timeoutExpirationMillis > 0) {
                activity.isBannedFromCurrentChannel = false
                activity.runOnUiThread { activity.updateChatLoginState() }
            }
        }
    }

    private val followersCountdownRunnable = object : Runnable {
        override fun run() {
            val chatroom = activity.currentChatroom ?: return
            if (chatroom.followersMode != true) return
            
            val isFollowing = activity.chatStateManager.isFollowingCurrentChannel
            val followingSince = activity.chatStateManager.followingSince
            val followersMinDuration = chatroom.followersMinDuration ?: 0
            
            if (!isFollowing || followingSince == null || followersMinDuration == 0) return
            
            val followDurationMinutes: Long = try {
                val followDate = java.time.ZonedDateTime.parse(followingSince)
                val now = java.time.ZonedDateTime.now()
                java.time.Duration.between(followDate, now).toMinutes()
            } catch (e: Exception) {
                0L
            }
            
            if (followDurationMinutes < followersMinDuration) {
                // Still waiting - update hint and schedule next check
                val remainingMinutes = (followersMinDuration - followDurationMinutes).coerceAtLeast(1)
                activity.runOnUiThread {
                    activity.binding.chatInput.hint = activity.getString(R.string.chat_hint_follower_remaining, remainingMinutes.toInt())
                }
                // Check every 30 seconds for more accurate updates
                mainHandler.postDelayed(this, 30_000)
            } else {
                // Requirement met - update hint to default
                activity.runOnUiThread {
                    activity.updateChatroomHint(activity.currentChatroom)
                }
            }
        }
    }

    fun startSlowModeCountdown() {
        mainHandler.removeCallbacks(slowModeCountdownRunnable)
        mainHandler.post(slowModeCountdownRunnable)
    }

    fun startTimeoutCountdown() {
        mainHandler.removeCallbacks(timeoutCountdownRunnable)
        mainHandler.post(timeoutCountdownRunnable)
    }

    fun startFollowersCountdown() {
        mainHandler.removeCallbacks(followersCountdownRunnable)
        mainHandler.post(followersCountdownRunnable)
    }

    fun stopSlowModeCountdown() {
        mainHandler.removeCallbacks(slowModeCountdownRunnable)
    }

    fun stopTimeoutCountdown() {
        mainHandler.removeCallbacks(timeoutCountdownRunnable)
    }

    fun stopFollowersCountdown() {
        mainHandler.removeCallbacks(followersCountdownRunnable)
    }

    fun stopAllCountdowns() {
        stopSlowModeCountdown()
        stopTimeoutCountdown()
        stopFollowersCountdown()
    }

    fun updateInputState() {
        // Check celebration mode status first to ensure it's not overridden by other states
        var isCelebration = false
        try {
             isCelebration = activity.chatUiManager.isCelebrationMode
        } catch (e: Exception) {}

        if (!activity.prefs.isLoggedIn) {
            activity.binding.chatInput.isEnabled = isCelebration // Allow input in celebration even if logged out? Probably not possible to be in celeb mode if not logged in, but safe.
            activity.binding.chatSendButton.isEnabled = isCelebration
            if (!isCelebration) {
                activity.binding.chatInput.hint = activity.getString(R.string.chat_hint_login_required)
                return
            }
        }

        val isLocked = activity.isUserLockedByChatMode()
        val hasText = !activity.binding.chatInput.text.isNullOrEmpty()
        
        // Handle Slow Mode overlap
        var isSlowModeActive = false
        val chatroom = activity.currentChatroom
        val slowModeEnabled = chatroom?.slowMode == true
        if (slowModeEnabled && !activity.isModeratorOrOwner) {
            val rawInterval = chatroom?.slowModeInterval ?: 0
            val interval = if (rawInterval <= 0) 5000L else rawInterval * 1000L
            val elapsed = System.currentTimeMillis() - activity.lastMessageSentMillis
            if (elapsed < interval) {
                isSlowModeActive = true
            }
        }

        if ((activity.isBannedFromCurrentChannel || activity.isCheckingBanStatus) && !isCelebration) {
            activity.binding.chatInput.isEnabled = false
            activity.binding.chatSendButton.isEnabled = false
            // But we still want to ensure button visibility is consistent
            activity.binding.chatSendContainer.visibility = if (hasText) View.VISIBLE else View.GONE
            activity.binding.chatSendButton.visibility = if (hasText) View.VISIBLE else View.GONE
            activity.binding.chatSettingsButton.visibility = if (hasText) View.GONE else View.VISIBLE
            return
        }

        activity.binding.chatInput.isEnabled = !isLocked || isCelebration
        activity.binding.chatSendButton.isEnabled = (!isLocked && !isSlowModeActive && hasText) || isCelebration
        
        if (hasText || isCelebration) {
            activity.binding.chatSendContainer.visibility = View.VISIBLE
            activity.binding.chatSendButton.visibility = View.VISIBLE
            activity.binding.chatSettingsButton.visibility = View.GONE
        } else {
            activity.binding.chatSendContainer.visibility = View.GONE
            activity.binding.chatSendButton.visibility = View.GONE
            activity.binding.chatSettingsButton.visibility = View.VISIBLE
        }
    }
}
