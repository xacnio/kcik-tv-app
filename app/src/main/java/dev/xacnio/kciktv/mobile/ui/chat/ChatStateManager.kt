/**
 * File: ChatStateManager.kt
 *
 * Description: A central repository for the runtime state of the chat system.
 * It maintains critical data such as the current chatroom configuration, user moderation status,
 * active polls, and message timers, serving as a source of truth for UI components.
 *
 * Author: Xacnio
 *
 */
package dev.xacnio.kciktv.mobile.ui.chat

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.widget.EditText
import androidx.lifecycle.LifecycleCoroutineScope
import dev.xacnio.kciktv.shared.data.model.ChatMessage
import dev.xacnio.kciktv.shared.data.model.ChatSender
import dev.xacnio.kciktv.shared.data.model.ChatroomInfo
import dev.xacnio.kciktv.shared.data.model.PollData
import dev.xacnio.kciktv.shared.data.model.PinnedGift
import dev.xacnio.kciktv.shared.data.prefs.AppPreferences
import dev.xacnio.kciktv.shared.ui.adapter.ChatAdapter
import kotlinx.coroutines.Job
import java.util.regex.Pattern
import dev.xacnio.kciktv.mobile.MobilePlayerActivity

/**
 * Manages chat state and slow mode functionality.
 * This class encapsulates the chat-related state that was previously in MobilePlayerActivity.
 */
class ChatStateManager(
    private val context: Context,
    val prefs: AppPreferences
) {
    private val TAG = "ChatStateManager"
    
    // Chat State
    var currentChatroomId: Long? = null
        private set
    var currentChatroom: ChatroomInfo? = null
        private set
    var currentUserSender: ChatSender? = null
    var currentChatErrorMessage: String? = null
    
    // Slow Mode State
    var lastMessageSentMillis: Long = 0
    private val mainHandler = Handler(Looper.getMainLooper())
    
    // Moderation State
    var isModeratorOrOwner = false
    var isChannelOwner = false
    var isBannedFromCurrentChannel = false
    var isCheckingBanStatus = false
    var isPermanentBan = false
    var timeoutExpirationMillis: Long = 0
    
    // Subscription State
    var isSubscribedToCurrentChannel = false
    var subscriberBadges: Map<Int, String> = emptyMap()
    
    // Following State
    var isFollowingCurrentChannel = false
    var followingSince: String? = null  // ISO date string
    
    // Reply State
    var currentReplyMessageId: String? = null
    var currentReplyMessage: ChatMessage? = null
    
    // Sent message tracking
    private val sentMessageRefs = java.util.Collections.synchronizedList(mutableListOf<String>())
    
    // Emote conversion
    var isEmoteConverting = false
    val emoteTagPattern: Pattern = Pattern.compile("\\[emote:(\\d+):([^\\]]+)\\]")

    // Poll & Prediction State
    var currentPoll: PollData? = null
    var currentPrediction: dev.xacnio.kciktv.shared.data.model.PredictionData? = null
    var pinnedGifts = mutableListOf<PinnedGift>()
    
    // Overlay UI State
    var isPinnedMessageActive = false
    var isPinnedMessageExpanded = true
    var isPinnedMessageHiddenByManual = false
    var isPollExpanded = true
    var isPollHiddenManually = false
    var isPollCompleting = false
    var isPredictionExpanded = true
    var isPredictionHiddenManually = false
    var isPredictionCompleting = false
    var primaryOverlayItem: String = "pinned"
    var shownBallCount = 0
    var currentPredictionEndTime: Long = 0L
    var selectedOutcomeId: String? = null
    
    // Callbacks
    var onSlowModeCountdownUpdate: ((Int) -> Unit)? = null
    var onSlowModeFinished: (() -> Unit)? = null
    var onBanStatusChanged: ((Boolean, Boolean, Long) -> Unit)? = null
    
    // Slow mode countdown runnable
    private var slowModeCountdownRunnable: Runnable? = null
    private var timeoutCountdownRunnable: Runnable? = null
    
    /**
     * Update chatroom info
     */
    fun updateChatroom(chatroom: ChatroomInfo) {
        currentChatroom = chatroom
        currentChatroomId = chatroom.id
    }
    
    /**
     * Reset chat state for a new channel
     */
    fun resetForNewChannel() {
        currentChatroomId = null
        currentChatroom = null
        currentUserSender = null
        isModeratorOrOwner = false
        isChannelOwner = false
        isBannedFromCurrentChannel = false
        isCheckingBanStatus = prefs.isLoggedIn
        isPermanentBan = false
        timeoutExpirationMillis = 0
        isSubscribedToCurrentChannel = false
        currentReplyMessageId = null
        currentReplyMessage = null
        sentMessageRefs.clear()
        stopSlowModeCountdown()
        stopTimeoutCountdown()
        currentPoll = null
        currentPrediction = null
        pinnedGifts.clear()
        isPinnedMessageActive = false
        isPinnedMessageExpanded = true
        isPinnedMessageHiddenByManual = false
        isPollExpanded = true
        isPollHiddenManually = false
        isPollCompleting = false
        isPredictionExpanded = true
        isPredictionHiddenManually = false
        isPredictionCompleting = false
        primaryOverlayItem = "pinned"
        shownBallCount = 0
        currentPredictionEndTime = 0L
        selectedOutcomeId = null
    }
    
    /**
     * Called when a message is sent - tracks the message ref and starts slow mode countdown
     */
    fun onMessageSent(messageRef: String?) {
        lastMessageSentMillis = System.currentTimeMillis()
        messageRef?.let { sentMessageRefs.add(it) }
        startSlowModeCountdown()
    }

    fun updateLastMessageSent(timestamp: Long) {
        lastMessageSentMillis = timestamp
        startSlowModeCountdown()
    }
    
    /**
     * Check if a sent message should be confirmed (from this device)
     */
    fun shouldConfirmSentMessage(messageRef: String?): Boolean {
        if (messageRef == null) return false
        return sentMessageRefs.contains(messageRef)
    }
    
    /**
     * Confirm a sent message was received
     */
    fun confirmSentMessage(messageRef: String) {
        sentMessageRefs.remove(messageRef)
    }
    
    /**
     * Start slow mode countdown
     */
    fun startSlowModeCountdown() {
        stopSlowModeCountdown()
        
        slowModeCountdownRunnable = object : Runnable {
            override fun run() {
                val slowModeEnabled = currentChatroom?.slowMode == true
                val rawInterval = currentChatroom?.slowModeInterval ?: 0
                val interval = if (slowModeEnabled && rawInterval <= 0) 5000L else rawInterval * 1000L
                
                if (slowModeEnabled && !isModeratorOrOwner) {
                    val elapsed = System.currentTimeMillis() - lastMessageSentMillis
                    if (elapsed < interval) {
                        val remainingSeconds = (((interval - elapsed) / 1000) + 1).toInt()
                        onSlowModeCountdownUpdate?.invoke(remainingSeconds)
                        mainHandler.postDelayed(this, 1000)
                        return
                    }
                }
                onSlowModeFinished?.invoke()
            }
        }
        
        mainHandler.post(slowModeCountdownRunnable!!)
    }
    
    /**
     * Stop slow mode countdown
     */
    fun stopSlowModeCountdown() {
        slowModeCountdownRunnable?.let { mainHandler.removeCallbacks(it) }
        slowModeCountdownRunnable = null
    }
    
    /**
     * Start timeout countdown
     */
    fun startTimeoutCountdown() {
        stopTimeoutCountdown()
        
        timeoutCountdownRunnable = object : Runnable {
            override fun run() {
                if (isBannedFromCurrentChannel && !isPermanentBan && timeoutExpirationMillis > System.currentTimeMillis()) {
                    // Still timed out - continue countdown
                    mainHandler.postDelayed(this, 1000)
                } else if (isBannedFromCurrentChannel && !isPermanentBan && timeoutExpirationMillis <= System.currentTimeMillis() && timeoutExpirationMillis > 0) {
                    // Timeout expired
                    isBannedFromCurrentChannel = false
                    onBanStatusChanged?.invoke(false, false, 0)
                }
            }
        }
        
        mainHandler.post(timeoutCountdownRunnable!!)
    }
    
    /**
     * Stop timeout countdown
     */
    fun stopTimeoutCountdown() {
        timeoutCountdownRunnable?.let { mainHandler.removeCallbacks(it) }
        timeoutCountdownRunnable = null
    }
    
    /**
     * Calculate remaining timeout time formatted as HH:MM:SS
     */
    fun getFormattedTimeoutRemaining(): String? {
        if (!isBannedFromCurrentChannel || isPermanentBan) return null
        
        val remaining = timeoutExpirationMillis - System.currentTimeMillis()
        if (remaining <= 0) return null
        
        val seconds = (remaining / 1000) % 60
        val minutes = (remaining / (1000 * 60)) % 60
        val hours = (remaining / (1000 * 60 * 60))
        
        return String.format(java.util.Locale.US, "%02d:%02d:%02d", hours, minutes, seconds)
    }
    
    /**
     * Check if user is locked from sending messages due to chat mode
     */
    fun isUserLockedByChatMode(): Boolean {
        if (!prefs.isLoggedIn) return true
        if (isBannedFromCurrentChannel) return true
        if (isCheckingBanStatus) return true
        
        val chatroom = currentChatroom ?: return false
        
        // Subscriber only mode
        if (chatroom.subscribersMode == true && !isSubscribedToCurrentChannel && !isModeratorOrOwner) {
            return true
        }
        
        // Followers only mode
        if (chatroom.followersMode == true) {
            // Would need to check follow status and duration
            // For now, assume not locked if we're following
        }
        
        return false
    }
    
    /**
     * Check if slow mode is currently active for the user
     */
    fun isSlowModeActive(): Boolean {
        val slowModeEnabled = currentChatroom?.slowMode == true
        if (!slowModeEnabled || isModeratorOrOwner) return false
        
        val rawInterval = currentChatroom?.slowModeInterval ?: 0
        val interval = if (rawInterval <= 0) 5000L else rawInterval * 1000L
        val elapsed = System.currentTimeMillis() - lastMessageSentMillis
        
        return elapsed < interval
    }
    
    /**
     * Set ban status
     */
    fun setBanStatus(isBanned: Boolean, isPermanent: Boolean, expiresAtMillis: Long) {
        isBannedFromCurrentChannel = isBanned
        isPermanentBan = isPermanent
        timeoutExpirationMillis = expiresAtMillis
        
        if (isBanned && !isPermanent && expiresAtMillis > System.currentTimeMillis()) {
            startTimeoutCountdown()
        }
        
        onBanStatusChanged?.invoke(isBanned, isPermanent, expiresAtMillis)
    }

    fun setModeratorStatus(isMod: Boolean) {
        isModeratorOrOwner = isMod
    }
    
    /**
     * Cleanup resources
     */
    fun cleanup() {
        stopSlowModeCountdown()
        stopTimeoutCountdown()
        mainHandler.removeCallbacksAndMessages(null)
    }
}
