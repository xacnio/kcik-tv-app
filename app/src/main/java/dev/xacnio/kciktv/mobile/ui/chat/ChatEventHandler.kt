/**
 * File: ChatEventHandler.kt
 *
 * Description: Processes incoming real-time events from the chat WebSocket.
 * This class interprets various event types such as mode changes, message deletions, user bans,
 * and polls, updating the local chat state and UI to reflect these server-side changes.
 *
 * Author: Xacnio
 *
 */
package dev.xacnio.kciktv.mobile.ui.chat

import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.View
import com.google.gson.Gson
import com.google.gson.JsonParser
import dev.xacnio.kciktv.mobile.MobilePlayerActivity
import dev.xacnio.kciktv.R
import dev.xacnio.kciktv.shared.data.model.*
import dev.xacnio.kciktv.databinding.ActivityMobilePlayerBinding
import dev.xacnio.kciktv.shared.ui.adapter.ChatAdapter
import dev.xacnio.kciktv.shared.util.TimeUtils
import dev.xacnio.kciktv.shared.util.dpToPx
import dev.xacnio.kciktv.shared.data.model.ChatMessage
import dev.xacnio.kciktv.mobile.ui.chat.ChatEventHandler

class ChatEventHandler(
    private val activity: MobilePlayerActivity,
    private val binding: ActivityMobilePlayerBinding,
    private val chatAdapter: ChatAdapter,
    private val stateManager: ChatStateManager
) {
    private val TAG = "ChatEventHandler"
    private val gson = Gson()
    private val runOnUiThread = { action: () -> Unit -> activity.runOnUiThread(action) }
    private fun getString(resId: Int, vararg formatArgs: Any): String = activity.getString(resId, *formatArgs)

    // Poll & Prediction State - Delegated to StateManager for sharing
    // Note: Local properties for convenience if needed, but easier to use stateManager directly or activity logic

    // Overlay Stack - logic is in Activity for now (primaryOverlayItem), 
    // but handleEvent updates it. Activity maintains primaryOverlayItem. 
    // If we want to move stack logic, we need to move primaryOverlayItem to StateManager.
    // For now, Activity handles the UI stacking in updateChatOverlayState, so we just call it.

    fun handleEvent(event: String, data: String? = null) {
        if (event == "App\\Events\\StreamerIsLive") {
            activity.onStreamerIsLive()
            return
        }
        
        val current = stateManager.currentChatroom ?: return
        var updated = current
        
        when (event) {
            "App\\Events\\KicksGifted", "KicksGifted" -> handleGiftedEvent(data)
            "App\\Events\\ChatroomUpdatedEvent" -> handleChatroomUpdated(data, current)
            
            // Mode Events
            "SubscribersModeActivated" -> {
                updated = current.copy(subscribersMode = true)
                announceModeChange(data, R.string.chat_status_subscribers_mode_on_by, R.string.chat_status_subscribers_mode_on, R.drawable.ic_star)
                activity.addInfoMessage(getString(R.string.chat_status_subscribers_mode_on))
            }
            "SubscribersModeDeactivated" -> {
                updated = current.copy(subscribersMode = false)
                announceModeChange(data, R.string.chat_status_subscribers_mode_off_by, R.string.chat_status_subscribers_mode_off, R.drawable.ic_star)
                activity.addInfoMessage(getString(R.string.chat_status_subscribers_mode_off))
            }
            "EmotesModeActivated" -> {
                updated = current.copy(emotesMode = true)
                announceModeChange(data, R.string.chat_status_emotes_mode_on_by, R.string.chat_status_emotes_mode_on, R.drawable.ic_emoji)
                activity.addInfoMessage(getString(R.string.chat_status_emotes_mode_on))
            }
            "EmotesModeDeactivated" -> {
                updated = current.copy(emotesMode = false)
                announceModeChange(data, R.string.chat_status_emotes_mode_off_by, R.string.chat_status_emotes_mode_off, R.drawable.ic_emoji)
                activity.addInfoMessage(getString(R.string.chat_status_emotes_mode_off))
            }
            "FollowersModeActivated" -> {
                updated = current.copy(followersMode = true)
                announceModeChange(data, R.string.chat_status_followers_mode_on_by, R.string.chat_status_followers_mode_on, R.drawable.ic_heart)
                activity.addInfoMessage(getString(R.string.chat_status_followers_mode_on))
            }
            "FollowersModeDeactivated" -> {
                updated = current.copy(followersMode = false)
                announceModeChange(data, R.string.chat_status_followers_mode_off_by, R.string.chat_status_followers_mode_off, R.drawable.ic_heart)
                activity.addInfoMessage(getString(R.string.chat_status_followers_mode_off))
            }
            "SlowModeActivated" -> {
                updated = current.copy(slowMode = true)
                val modName = tryParseMod(data)
                var interval = tryParseSlowModeInterval(data)
                
                if (interval == null || interval == 0) {
                    interval = current.slowModeInterval
                }
                
                updated = updated.copy(slowModeInterval = interval)
                                
                if (modName != null) {
                    activity.addSystemMessage(getString(R.string.chat_status_slow_mode_on_by, modName), R.drawable.ic_timer)
                } else {
                    activity.addSystemMessage(getString(R.string.chat_status_slow_mode_on), R.drawable.ic_timer)
                }
            }
            "SlowModeDeactivated" -> {
                updated = current.copy(slowMode = false)
                announceModeChange(data, R.string.chat_status_slow_mode_off_by, R.string.chat_status_slow_mode_off, R.drawable.ic_timer)
                activity.addInfoMessage(getString(R.string.chat_status_slow_mode_off))
            }
            
            "App\\Events\\PinnedMessageCreatedEvent" -> handlePinnedMessageCreated(data)
            "App\\Events\\PinnedMessageDeletedEvent" -> handlePinnedMessageDeleted()
            "App\\Events\\MessageDeletedEvent" -> handleMessageDeleted(data)
            "App\\Events\\UserBannedEvent" -> handleUserBanned(data)
            "App\\Events\\UserUnbannedEvent" -> handleUserUnbanned(data)
            "App\\Events\\ChatroomClearEvent" -> handleChatClear()
            "App\\Events\\PollUpdateEvent" -> handlePollUpdate(data)
            "App\\Events\\PollDeleteEvent" -> handlePollDelete()
            "PredictionCreated", "App\\Events\\PredictionCreated" -> handlePredictionCreated(data)
            "PredictionUpdated", "App\\Events\\PredictionUpdated" -> handlePredictionCreated(data) 
        }
        
        if (updated != current) {
            stateManager.updateChatroom(updated)
            runOnUiThread {
                activity.updateChatroomHint(updated)
            }
        }
    }

    private fun handleGiftedEvent(data: String?) {
        data?.let {
             try {
                 val giftEvent = gson.fromJson(it, KicksGiftedEventData::class.java)
                 
                 val sender = PinnedGiftSender(
                     id = giftEvent.sender?.id,
                     profilePicture = giftEvent.sender?.profilePicture, 
                     username = giftEvent.sender?.username,
                     usernameColor = giftEvent.sender?.usernameColor ?: giftEvent.sender?.identity?.color
                 )
                 
                 val pinnedSeconds = (giftEvent.gift?.pinnedTime ?: 0L) / 1000000000L
                 
                 val giftInfo = GiftInfo(
                     amount = giftEvent.gift?.amount,
                     giftId = giftEvent.gift?.giftId,
                     name = giftEvent.gift?.name,
                     tier = giftEvent.gift?.tier,
                     pinnedTime = pinnedSeconds
                 )
                 
                 val cleanedMessage = giftEvent.message?.replace(Regex("[\\r\\n]+"), " ")
                 val pinnedGift = PinnedGift(
                     createdAt = giftEvent.createdAt,
                     expiresAt = giftEvent.expiresAt,
                     gift = giftInfo,
                     giftTransactionId = giftEvent.giftTransactionId,
                     message = cleanedMessage,
                     sender = sender,
                     pinnedTime = pinnedSeconds
                 )
                 
                 stateManager.pinnedGifts.add(0, pinnedGift)
                 
                 runOnUiThread {
                     // Also add to chat as a message
                     val chatSender = ChatSender(
                         id = giftEvent.sender?.id ?: 0,
                         username = giftEvent.sender?.username ?: "Unknown",
                         color = giftEvent.sender?.usernameColor ?: giftEvent.sender?.identity?.color,
                         badges = giftEvent.sender?.identity?.badges?.map { b ->
                             ChatBadge(b.type ?: "", b.text, b.count)
                         } ?: emptyList(),
                         profilePicture = giftEvent.sender?.profilePicture
                     )
                     val chatMessage = ChatMessage(
                         id = giftEvent.giftTransactionId ?: "gift_${System.currentTimeMillis()}",
                         content = cleanedMessage ?: "",
                         sender = chatSender,
                         type = MessageType.GIFT,
                         giftData = giftEvent
                     )
                     chatAdapter.addMessages(listOf(chatMessage))

                     activity.overlayManager.updatePinnedGiftsUI()
                     activity.overlayManager.restartPinnedGiftsTimer()
                 }
             } catch (e: Exception) {
                 Log.e(TAG, "Error handling KicksGifted for pinned list", e)
             }
         }
    }
    
    private fun handleChatroomUpdated(data: String?, current: ChatroomInfo) {
        data?.let {
            try {
                val eventData = gson.fromJson(it, ChatroomUpdatedEventData::class.java)
                val next = current.copy(
                    slowMode = eventData.slowMode?.enabled ?: current.slowMode,
                    subscribersMode = eventData.subscribersMode?.enabled ?: current.subscribersMode,
                    followersMode = eventData.followersMode?.enabled ?: current.followersMode,
                    emotesMode = eventData.emotesMode?.enabled ?: current.emotesMode,
                    slowModeInterval = eventData.slowMode?.messageInterval ?: current.slowModeInterval,
                    followersMinDuration = eventData.followersMode?.minDuration ?: current.followersMinDuration
                )
                
                if (next.slowMode != current.slowMode || (next.slowMode == true && next.slowModeInterval != current.slowModeInterval)) {
                    if (next.slowMode == true) {
                        val interval = next.slowModeInterval ?: 0
                        val durationStr = if (interval > 0) TimeUtils.formatSlowModeDuration(activity, interval) else ""
                        val msg = if (durationStr.isNotEmpty()) getString(R.string.chat_status_slow_mode_on_duration, durationStr) else getString(R.string.chat_status_slow_mode_on)
                        activity.addInfoMessage(msg)
                    } else {
                        activity.addInfoMessage(getString(R.string.chat_status_slow_mode_off))
                    }
                }
                
                if(!stateManager.isModeratorOrOwner) {
                    if (next.subscribersMode != current.subscribersMode) {
                        activity.addInfoMessage(getString(if (next.subscribersMode == true) R.string.chat_status_subscribers_mode_on else R.string.chat_status_subscribers_mode_off))
                    }
                    if (next.followersMode != current.followersMode) {
                        activity.addInfoMessage(getString(if (next.followersMode == true) R.string.chat_status_followers_mode_on else R.string.chat_status_followers_mode_off))
                    }
                    if (next.emotesMode != current.emotesMode) {
                        activity.addInfoMessage(getString(if (next.emotesMode == true) R.string.chat_status_emotes_mode_on else R.string.chat_status_emotes_mode_off))
                    }                          
                }
                
                if (next != current) {
                    stateManager.updateChatroom(next)
                    runOnUiThread {
                        activity.updateChatroomHint(next)
                    }
                }
                Unit
            } catch (e: Exception) {
                Log.e(TAG, "Error parsing ChatroomUpdatedEvent", e)
            }
        }
    }

    private fun handlePinnedMessageCreated(data: String?) {
        data?.let {
            try {
                val eventData = gson.fromJson(it, PinnedMessageCreatedEventData::class.java)
                val messageDoc = eventData.message
                val senderDoc = messageDoc?.sender
                
                if (messageDoc != null && senderDoc != null) {
                    runOnUiThread {
                        android.transition.TransitionManager.beginDelayedTransition(binding.chatContainer)
                        activity.chatStateManager.isPinnedMessageActive = true
                        activity.chatStateManager.primaryOverlayItem = "pinned"
                        
                        activity.chatStateManager.isPinnedMessageHiddenByManual = false 
                        binding.restorePinnedMessage.visibility = View.GONE
                        activity.updateChatOverlayState()
                        
                        val content = messageDoc.content?.replace(Regex("[\\r\\n]+"), " ") ?: ""
                        activity.overlayManager.processPinnedMessageEmotes(content)
                        
                        val chatSender = ChatSender(
                            id = senderDoc.id ?: 0,
                            username = senderDoc.username ?: "Unknown",
                            color = senderDoc.identity?.color,
                            badges = senderDoc.identity?.badges?.map { 
                                ChatBadge(it.type ?: "", it.text, it.count)
                            } ?: emptyList(),
                            profilePicture = senderDoc.profilePicture
                        )
                        activity.overlayManager.renderPinnedSender(chatSender)
                        
                        binding.pinnedByText.text = binding.root.context.getString(R.string.pinned_by_format, eventData.pinnedBy?.username ?: senderDoc.username)
                        val dateStr = java.text.SimpleDateFormat("dd.MM.yyyy HH:mm", java.util.Locale.getDefault()).format(java.util.Date())
                        binding.pinnedDateText.text = binding.root.context.getString(R.string.date_format_label, dateStr)
                        
                        activity.overlayManager.updatePinnedMessageUIState()
                    }
                }
                Unit
            } catch (e: Exception) {
                Log.e(TAG, "Error parsing PinnedMessageCreatedEvent", e)
            }
        }
    }

    private fun handlePinnedMessageDeleted() {
        runOnUiThread {
            stateManager.isPinnedMessageActive = false
            activity.overlayManager.clearPinnedEmotes()
            
            activity.overlayManager.hideOverlayView(binding.pinnedMessageContainer)
            
            stateManager.isPinnedMessageHiddenByManual = false
            binding.restorePinnedMessage.visibility = View.GONE
        }
    }
    
    // ... MessageDeleted, UserBanned ...
    // Keep implementation from previous step but usage corrections
    
    private fun handleMessageDeleted(data: String?) {
         data?.let {
             try {
                 val eventData = gson.fromJson(it, MessageDeletedEventData::class.java)
                 val messageId = eventData.message?.id
                 if (messageId != null) {
                     val aiModerated = eventData.aiModerated == true
                     val violatedRules = eventData.violatedRules
                     runOnUiThread {
                         chatAdapter.markMessageAsDeleted(messageId, aiModerated, violatedRules)
                     }
                 }
                 Unit
             } catch (e: Exception) {
                 Log.e(TAG, "Error parsing MessageDeletedEvent", e)
             }
         }
    }
    
    private fun handleUserBanned(data: String?) {
        data?.let {
             try {
                 val eventData = gson.fromJson(it, UserBannedEventData::class.java)
                 val username = eventData.user?.username
                  if (username != null) {
                      runOnUiThread {
                          chatAdapter.markUserMessagesAsDeleted(username)
                          if (username.equals(stateManager.prefs.username, ignoreCase = true)) {
                              stateManager.setBanStatus(true, eventData.permanent == true, activity.parseIsoDate(eventData.expiresAt))
                              activity.updateChatLoginState()
                          }
                      }
                  }
                  Unit
             } catch (e: Exception) {
                 Log.e(TAG, "Error parsing UserBannedEvent", e)
             }
         }
    }
    
    private fun handleUserUnbanned(data: String?) {
        data?.let {
             try {
                 val eventData = gson.fromJson(it, UserUnbannedEventData::class.java)
                 val username = eventData.user?.username
                 if (username != null) {
                     runOnUiThread {
                         if (username.equals(stateManager.prefs.username, ignoreCase = true)) {
                             stateManager.setBanStatus(false, false, 0)
                             activity.updateChatLoginState()
                         }
                     }
                 }
                 Unit
             } catch (e: Exception) {
                 Log.e(TAG, "Error parsing UserUnbannedEvent", e)
             }
         }
    }
    
    private fun handleChatClear() {
        runOnUiThread {
            // Save messages before clearing so they can be restored
            val currentMessages = chatAdapter.currentList.toList()
            activity.chatUiManager.clearChatMessages()
            
            val restoreButtonMessage = ChatMessage(
                id = "restore_${System.currentTimeMillis()}",
                content = getString(R.string.chat_restore_messages, currentMessages.size),
                sender = ChatSender(-1, "", null, null),
                type = MessageType.RESTORE_BUTTON,
                createdAt = System.currentTimeMillis()
            )
            val systemMessage = ChatMessage(
                id = "clear_${System.currentTimeMillis()}",
                content = getString(R.string.chat_cleared),
                sender = ChatSender(0, getString(R.string.chat_system_username), null, null),
                type = MessageType.SYSTEM,
                createdAt = System.currentTimeMillis(),
                iconResId = R.drawable.ic_delete
            )
            chatAdapter.addMessages(listOf(restoreButtonMessage, systemMessage))
        }
    }
    
    private fun handlePollUpdate(data: String?) {
        data?.let {
             try {
                 val eventData = gson.fromJson(it, PollUpdateEventData::class.java)
                 eventData.poll?.let { poll ->
                     runOnUiThread {
                         activity.overlayManager.updatePollUI(poll)
                     }
                 }
             } catch (e: Exception) {
                 Log.e(TAG, "Error parsing PollUpdateEvent", e)
             }
         }
    }
    
    private fun handlePollDelete() {
        runOnUiThread {
            // If we are showing completion results (countdown), ignore the delete event
            // The completion logic will clear the poll when finished.
            if (stateManager.isPollCompleting) return@runOnUiThread
            
            stateManager.currentPoll = null
            activity.overlayManager.stopPollTimer()
            activity.updateChatOverlayState()
        }
    }
    
    private fun handlePredictionCreated(data: String?) {
        data?.let {
             try {
                 val eventData = gson.fromJson(it, PredictionEventData::class.java)
                 eventData.prediction?.let { prediction ->
                     runOnUiThread {
                         activity.overlayManager.updatePredictionUI(prediction)
                         stateManager.currentPrediction = prediction
                         activity.updateChatOverlayState()
                     }
                 }
             } catch (e: Exception) {
                 Log.e(TAG, "Error parsing PredictionCreated", e)
             }
         }
    }

    private fun announceModeChange(data: String?, byRes: Int, defRes: Int, iconRes: Int) {
        val modName = tryParseMod(data)
        if (modName != null) {
            activity.addSystemMessage(getString(byRes, modName), iconRes)
        } else {
            activity.addSystemMessage(getString(defRes), iconRes)
        }
    }

    private fun tryParseMod(data: String?): String? {
        if (data == null) return null
        return try {
            val eventData = gson.fromJson(data, ChatModeEventData::class.java)
            eventData.user?.username
        } catch (_: Exception) {
            null
        }
    }
    
    private fun tryParseSlowModeInterval(data: String?): Int? {
        if (data == null) return null
        return try {
            val json = JsonParser.parseString(data).asJsonObject
            if (json.has("message_interval")) {
                json.get("message_interval").asInt
            } else if (json.has("slow_mode")) {
                val sm = json.getAsJsonObject("slow_mode")
                if (sm.has("message_interval")) {
                     sm.get("message_interval").asInt
                } else null
            } else null
        } catch (_: Exception) {
            null
        }
    }
}
