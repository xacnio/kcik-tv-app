/**
 * File: ChatUiManager.kt
 *
 * Description: Orchestrates the overall Chat UI within the MobilePlayerActivity.
 * It coordinates the RecyclerView, input area, interactions with other chat managers,
 * and handles the sending of messages and execution of chat commands.
 *
 * Author: Xacnio
 *
 */
package dev.xacnio.kciktv.mobile.ui.chat

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.Toast
import androidx.core.view.isVisible
import androidx.lifecycle.LifecycleCoroutineScope
import androidx.recyclerview.widget.LinearLayoutManager
import dev.xacnio.kciktv.mobile.LoginActivity
import dev.xacnio.kciktv.mobile.MobilePlayerActivity
import dev.xacnio.kciktv.R
import dev.xacnio.kciktv.shared.data.api.RetrofitClient
import dev.xacnio.kciktv.shared.data.model.*
import dev.xacnio.kciktv.shared.data.prefs.AppPreferences
import dev.xacnio.kciktv.shared.data.repository.ChannelRepository
import dev.xacnio.kciktv.databinding.ActivityMobilePlayerBinding
import dev.xacnio.kciktv.shared.ui.adapter.ChatAdapter
import dev.xacnio.kciktv.shared.ui.adapter.CommandSuggestionsAdapter
import dev.xacnio.kciktv.shared.ui.adapter.UserSuggestionAdapter
import dev.xacnio.kciktv.mobile.ui.dialog.LoyaltyPointsBottomSheet
import dev.xacnio.kciktv.shared.util.getLocalizedChatError
import kotlinx.coroutines.launch
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import dev.xacnio.kciktv.shared.data.model.ChatCommands
import dev.xacnio.kciktv.shared.data.model.ChatMessage
import dev.xacnio.kciktv.shared.data.model.PinnedMessage
import dev.xacnio.kciktv.shared.ui.dialog.GiftShopBottomSheet
import dev.xacnio.kciktv.mobile.ui.chat.ChatEventHandler

class ChatUiManager(
    private val activity: MobilePlayerActivity,
    private val binding: ActivityMobilePlayerBinding,
    private val prefs: AppPreferences,
    private val repository: ChannelRepository,
    private val chatStateManager: ChatStateManager,
    private val lifecycleScope: LifecycleCoroutineScope
) {
    private val TAG = "ChatUiManager"
    private val mainHandler = Handler(Looper.getMainLooper())
    
    val chatAdapter: ChatAdapter
    private lateinit var commandSuggestionsAdapter: CommandSuggestionsAdapter
    private lateinit var userSuggestionAdapter: UserSuggestionAdapter
    private val recentChatUsers = mutableSetOf<String>()
    
    internal var isChatAutoScrollEnabled = true
    private var clearedMessages = listOf<ChatMessage>()
    private var emoteNameToId = mapOf<String, String>() // This should be populated if needed
    private var newMessageCount = 0 // Count of new messages while scrolled up
    private var bufferCount = 0     // Count of messages currently in buffer
    private var lastItemCount = 0   // Track last item count to calculate batch sizes
    
    private val incomingMessageBuffer = java.util.Collections.synchronizedList(mutableListOf<ChatMessage>())
    var isChatUiPaused = false

    init {
        chatAdapter = ChatAdapter(
            onReplyClick = { originalId -> scrollToRepliedMessage(originalId) },
            onSwipeToReply = { message -> prepareReply(message) },
            onEmoteClick = null,
            onMessageLongClick = { message -> copyMessageToClipboard(message) },
            onMessageClick = { message -> handleMessageClick(message) },
            onRestoreClick = { restoreClearedMessages() },
            onRestoreDismiss = { dismissRestoreButton() },
            onMessageAdded = { handleMessageAdded() },
            onEmptySpaceClick = { activity.handleScreenTap() },
            onClipUrlFound = { clipId -> fetchClipDetails(clipId) },
            onClipClick = { clipId -> handleClipClick(clipId) }
        ).apply {
            setChatTextSize(prefs.chatTextSize)
            setChatEmoteSize(prefs.chatEmoteSize)
            setShowTimestamps(prefs.chatShowTimestamps)
            setShowSeconds(prefs.chatShowSeconds)
            setCurrentUser(prefs.username)
            setHighlightSettings(
                prefs.highlightOwnMessages,
                prefs.highlightMentions,
                prefs.highlightMods,
                prefs.highlightVips,
                prefs.chatUseNameColorForHighlight
            )
            // Note: chatAdapter is passed to ChatEventHandler in Activity logic, 
            // so we expose it here.
        }
    }

    fun setup() {
        setupRecyclerView()
        setupInputLogic()
        setupCommandSuggestions()
        setupUserSuggestions()
        setupClickListeners()
        setupCelebrationListeners()
    }

    private fun setupRecyclerView() {
        binding.chatRecyclerView.apply {
            layoutManager = LinearLayoutManager(activity)
            adapter = chatAdapter
            applyChatMessageAnimator()
            setHasFixedSize(false)
            setItemViewCacheSize(4)
            // Allow animations to draw outside bounds (fixes slide_left visibility)
            clipChildren = false
            clipToPadding = false
        }
        
        val swipeCallback = ChatAdapter.SwipeToReplyCallback(chatAdapter, activity)
        val itemTouchHelper = androidx.recyclerview.widget.ItemTouchHelper(swipeCallback)
        itemTouchHelper.attachToRecyclerView(binding.chatRecyclerView)

        binding.chatRecyclerView.addOnScrollListener(object : androidx.recyclerview.widget.RecyclerView.OnScrollListener() {
            override fun onScrollStateChanged(recyclerView: androidx.recyclerview.widget.RecyclerView, newState: Int) {
                super.onScrollStateChanged(recyclerView, newState)
                if (newState == androidx.recyclerview.widget.RecyclerView.SCROLL_STATE_DRAGGING) {
                    if (isChatAutoScrollEnabled) {
                        // Just started scrolling up - reset counter
                        newMessageCount = 0
                    }
                    isChatAutoScrollEnabled = false
                    chatAdapter.isAutoScrollEnabled = false
                    updateJumpToBottomButton()
                }
            }

            override fun onScrolled(recyclerView: androidx.recyclerview.widget.RecyclerView, dx: Int, dy: Int) {
                super.onScrolled(recyclerView, dx, dy)

                if (dy < 0) {
                    isChatAutoScrollEnabled = false
                    chatAdapter.isAutoScrollEnabled = false
                }

                if (!recyclerView.canScrollVertically(1)) {
                    isChatAutoScrollEnabled = true
                    chatAdapter.isAutoScrollEnabled = true
                    newMessageCount = 0
                    binding.chatJumpToBottom.visibility = View.GONE
                } else if (!isChatAutoScrollEnabled) {
                    updateJumpToBottomButton()
                }
            }
        })
        
        binding.chatJumpToBottom.setOnClickListener {
            isChatAutoScrollEnabled = true
            chatAdapter.isAutoScrollEnabled = true
            newMessageCount = 0
            scrollToBottom()
            binding.chatJumpToBottom.visibility = View.GONE
            
            // Fix theatre mode auto-collapse
            activity.fullscreenToggleManager.onChatJumpToBottomClicked()
        }
        
        binding.chatRecyclerView.addOnLayoutChangeListener { _, _, _, _, bottom, _, _, _, oldBottom ->
            if (bottom < oldBottom && isChatAutoScrollEnabled) {
                scrollToBottom()
            }
        }
    }

    private fun applyChatMessageAnimator() {
        // We now handle animations directly in the adapter to ensure they only apply to truly new messages
        // and not every time the list refreshes or scrolls.
        val animKey = prefs.chatMessageAnimation
        chatAdapter.setAnimationType(animKey)
        
        // Disable default item animator to prevent conflicts and double animations
        binding.chatRecyclerView.itemAnimator = null
    }

    /**
     * Called from settings when the user changes the chat message animation type.
     */
    fun updateChatMessageAnimation() {
        applyChatMessageAnimator()
    }

    private fun setupInputLogic() {
        binding.chatInput.isEnabled = prefs.isLoggedIn
        binding.chatSendButton.isEnabled = prefs.isLoggedIn
        if (!prefs.isLoggedIn) {
            binding.chatInput.hint = activity.getString(R.string.login_to_chat)
        }

        binding.chatInput.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                activity.chatInputStateManager.updateInputState()
                // For now, let's call Activity method if it exists and exposes logic, otherwise replicate.
                // Activity's updateChatInputState is public/internal? It is currently internal from previous edit step?
                // Let's assume we can call it or replicate it. It depends on `isUserLockedByChatMode` which is in StateManager now.
                // The Activity likely has one.
                // Removed redundant call

                updateCommandSuggestions(s?.toString() ?: "")
                updateMentionSuggestions(s?.toString() ?: "", binding.chatInput.selectionStart)
                if (binding.chatErrorContainer.isVisible) {
                    binding.chatErrorContainer.visibility = View.GONE
                }
            }
            override fun afterTextChanged(s: android.text.Editable?) {
                updateSendButtonState()
                updateChatModeIndicators()
            }
        })

        binding.chatInput.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEND) {
                sendChatMessage()
                true
            } else false
        }
    }

    private fun updateSendButtonState() {
        activity.chatInputStateManager.updateInputState()
    }

    private fun setupCommandSuggestions() {
        commandSuggestionsAdapter = CommandSuggestionsAdapter { command ->
            val text = if (command.requiresArg) "/${command.name} " else "/${command.name}"
            binding.chatInput.setText(text)
            binding.chatInput.setSelection(text.length)
            binding.commandSuggestionsRecycler.visibility = View.GONE
        }
        // Initially set command adapter, but it will be swapped dynamically
        binding.commandSuggestionsRecycler.layoutManager = LinearLayoutManager(activity)
        binding.commandSuggestionsRecycler.adapter = commandSuggestionsAdapter
    }

    private fun setupUserSuggestions() {
        userSuggestionAdapter = UserSuggestionAdapter { username ->
            completeMention(username)
        }
    }

    private fun showChatError(message: String) {
        binding.chatErrorText.text = message
        binding.chatErrorContainer.visibility = View.VISIBLE
        chatStateManager.currentChatErrorMessage = message
    }

    private fun setupClickListeners() {
        binding.chatLoginButton.setOnClickListener {
            activity.startActivity(Intent(activity, LoginActivity::class.java))
        }

        binding.chatSendButton.setOnClickListener {
            sendChatMessage()
        }

        binding.chatSettingsButton.setOnClickListener {
            activity.showChatSettingsSheet()
        }
        
        binding.chatErrorClose.setOnClickListener {
            chatStateManager.currentChatErrorMessage = null
            binding.chatErrorContainer.visibility = View.GONE
        }

        binding.chatEmoteButton.setOnClickListener {
            toggleEmotePanel()
        }

        binding.chatInput.setOnClickListener {
            if (binding.emotePanelContainer.isVisible) activity.emotePanelManager.toggleEmotePanel(showKeyboardOnClose = false)
        }
        
        binding.chatInput.setOnFocusChangeListener { _, hasFocus ->
            if (hasFocus && binding.emotePanelContainer.isVisible) activity.emotePanelManager.toggleEmotePanel(showKeyboardOnClose = false)
        }

        binding.chatRecyclerView.setOnTouchListener { view, event ->
            if (event.action == android.view.MotionEvent.ACTION_DOWN) {
                if (binding.emotePanelContainer.isVisible) binding.emotePanelContainer.visibility = View.GONE
                val imm = activity.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
                if (imm.isActive) {
                    imm.hideSoftInputFromWindow(activity.currentFocus?.windowToken ?: binding.chatInput.windowToken, 0)
                    binding.chatInput.clearFocus()
                }
            } else if (event.action == android.view.MotionEvent.ACTION_UP) {
                view.performClick()
            }
            false
        }
        
        // Loyalty Points & Store
        binding.loyaltyPointsText.text = "0"
        
        binding.loyaltyPointsButton.setOnClickListener {
              try {
                  val channel = activity.currentChannel
                  val slug = channel?.slug ?: return@setOnClickListener
                  val sheet = LoyaltyPointsBottomSheet.newInstance(slug)
                  sheet.show(activity.supportFragmentManager, "LoyaltyPointsBottomSheet")
              } catch (e: Exception) {
                  e.printStackTrace()
              }
        }
        
        binding.loyaltyStoreButton.setOnClickListener {
            try {
                val channelId = activity.currentChannel?.id?.toLongOrNull() ?: 0L
                if (channelId == 0L) return@setOnClickListener
                
                val sheet = dev.xacnio.kciktv.shared.ui.dialog.GiftShopBottomSheet.newInstance(channelId)
                sheet.show(activity.supportFragmentManager, "GiftShopBottomSheet")
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
        
        binding.chatModeIndicator.setOnClickListener { view ->
            showChatModeTooltip(view)
        }
    }
    
    private fun showChatModeTooltip(anchorView: View) {
        val room = chatStateManager.currentChatroom
        val sb = StringBuilder()
        
        // Slow Mode
        if (room?.slowMode == true) {
             val interval = room.slowModeInterval ?: 0
             if (chatStateManager.isModeratorOrOwner) {
                 sb.append(activity.getString(R.string.slow_mode_exempt, interval) + "\n")
             } else {
                 sb.append(activity.getString(R.string.slow_mode_active, interval) + "\n")
             }
        }
        
        // Sub Only
        if (room?.subscribersMode == true) {
             sb.append(activity.getString(R.string.subscribers_only_mode) + "\n")
        }
        
        // Follow Only
        if (room?.followersMode == true) {
             val minDur = room.followersMinDuration ?: 0
             if (minDur > 0) {
                 sb.append(activity.getString(R.string.followers_only_mode_min, minDur) + "\n")
             } else {
                 sb.append(activity.getString(R.string.followers_only_mode) + "\n")
             }
        }
        
        // Emote Only
        if (room?.emotesMode == true) {
             sb.append(activity.getString(R.string.emotes_only_mode) + "\n")
        }
        
        val message = sb.toString().trim()
        if (message.isEmpty()) return

        val context = activity
        val popupView = android.widget.TextView(context).apply {
            text = message
            setTextColor(android.graphics.Color.WHITE)
            setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 13f)
            setBackgroundResource(R.drawable.bg_tooltip)
            setPadding(24, 16, 24, 16) // Padding in pixels
            elevation = 10f
        }
        
        val popupWindow = android.widget.PopupWindow(
            popupView, 
            android.view.ViewGroup.LayoutParams.WRAP_CONTENT,
            android.view.ViewGroup.LayoutParams.WRAP_CONTENT,
            true
        )
        popupWindow.isOutsideTouchable = true
        popupWindow.setBackgroundDrawable(android.graphics.drawable.ColorDrawable(android.graphics.Color.TRANSPARENT))
        
        popupView.measure(View.MeasureSpec.UNSPECIFIED, View.MeasureSpec.UNSPECIFIED)
        val popupWidth = popupView.measuredWidth
        val popupHeight = popupView.measuredHeight
        
        val location = IntArray(2)
        anchorView.getLocationOnScreen(location)
        
        val x = location[0] + (anchorView.width / 2) - (popupWidth / 2)
        val y = location[1] - popupHeight - 20 // 20px above icon
        
        popupWindow.showAtLocation(anchorView, android.view.Gravity.NO_GRAVITY, x, y)
    }
    
    fun updateChatModeIndicators() {
        val text = binding.chatInput.text?.toString() ?: ""
        
        if (text.isNotEmpty()) {
            binding.chatModeIndicator.visibility = View.GONE
            return
        }

        val room = chatStateManager.currentChatroom
        val isSlow = room?.slowMode == true
        val isSub = room?.subscribersMode == true
        val isFollow = room?.followersMode == true
        val isEmote = room?.emotesMode == true

        // Determine the appropriate icon
        // Priority: Sub > Emote > Follow for mode type
        val iconRes: Int = when {
            // Combined icons (Slow + Mode) - priority order
            isSlow && isSub -> R.drawable.ic_slow_mode_indicator_subsonly
            isSlow && isEmote -> R.drawable.ic_slow_mode_indicator_emotesonly
            isSlow && isFollow -> R.drawable.ic_slow_mode_indicator_followersonly
            // Slow Mode only
            isSlow -> R.drawable.ic_slow_mode_indicator
            // Single mode icons - priority order
            isSub -> R.drawable.ic_subsonly
            isEmote -> R.drawable.ic_emotesonly
            isFollow -> R.drawable.ic_followersonly
            // Nothing active
            else -> 0
        }
        
        if (iconRes != 0) {
            binding.chatModeIndicator.setImageResource(iconRes)
            binding.chatModeIndicator.visibility = View.VISIBLE
        } else {
            binding.chatModeIndicator.visibility = View.GONE
        }
    }

    fun sendChatMessage(overrideMessage: String? = null) {
        val isOverride = overrideMessage != null
        var message = overrideMessage ?: binding.chatInput.text?.toString() ?: ""
        
        // Celebration: If empty, send default emote
        if (isCelebrationMode && message.trim().isEmpty()) {
            message = "🎉"
        }
        
        if (message.trim().isEmpty()) return

        if (isInputACommand(message)) {
            executeCommand(message)
            return
        }

        // Client-side slow mode check
        if (chatStateManager.isSlowModeActive()) return

        // Check if user is mod/owner (exempt from restrictions)
        val isModOrOwner = chatStateManager.isModeratorOrOwner
        
        // Check follower requirement
        val room = chatStateManager.currentChatroom
        if (!isModOrOwner && room?.followersMode == true) {
            val isFollowing = chatStateManager.isFollowingCurrentChannel
            val followingSince = chatStateManager.followingSince
            val followersMinDuration = room.followersMinDuration ?: 0
            
            val followDurationMinutes: Long = if (isFollowing && followingSince != null) {
                try {
                    val followDate = java.time.ZonedDateTime.parse(followingSince)
                    val now = java.time.ZonedDateTime.now()
                    java.time.Duration.between(followDate, now).toMinutes()
                } catch (e: Exception) {
                    0L
                }
            } else {
                0L
            }
            
            val meetsFollowRequirement = isFollowing && (followersMinDuration == 0 || followDurationMinutes >= followersMinDuration)
            
            if (!meetsFollowRequirement) {
                Toast.makeText(activity, activity.getString(R.string.chat_error_followers_only), Toast.LENGTH_SHORT).show()
                return
            }
        }
        
        // Check subscriber requirement
        if (!isModOrOwner && room?.subscribersMode == true) {
            if (!chatStateManager.isSubscribedToCurrentChannel) {
                Toast.makeText(activity, activity.getString(R.string.chat_error_subscribers_only), Toast.LENGTH_SHORT).show()
                return
            }
        }

        // Emote processing would go here if needed (Activity had simple mapping code)
        // Ignoring complicated emote map construction for now as it relies on emoteNameToId which is seemingly empty in Activity snippet
        // or populated elsewhere. Activity snippet showed "emoteNameToId[word]" usage.
        // I'll keep the logic simple or assume it's passed.
        // For line reduction, we can assume bare text sending or implement later.
        // Let's implement basic emoji tagging if map was available, but since I can't see where it's populated,
        // I will copy the logic but emoteNameToId will be empty.

        // Emote processing and Recent Emote Tracking
        val usedEmotes = mutableListOf<dev.xacnio.kciktv.shared.data.model.Emote>()
        
        // Use a simpler regex or manual parsing for reliability
        val words = message.split(" ")
        val processedWords = words.map { word ->
            if (word.startsWith("[emote:") && word.endsWith("]")) {
                // Case 1: Tagged emote (e.g. from clipboard or suggestion) -> [emote:123:Name]
                try {
                    val parts = word.removePrefix("[emote:").removeSuffix("]").split(":")
                    if (parts.size >= 2) {
                        val id = parts[0].toLongOrNull()
                        val name = parts[1]
                        if (id != null) {
                             usedEmotes.add(dev.xacnio.kciktv.shared.data.model.Emote(id, null, name))
                        }
                    }
                } catch(e: Exception) {
                    // Ignore parse errors
                }
                word
            } else {
                // Case 2: Typed emote name (e.g. KEKW) -> Check map
                val emoteId = emoteNameToId[word]
                if (emoteId != null) {
                     val idLong = emoteId.toLongOrNull()
                     if (idLong != null) {
                         usedEmotes.add(dev.xacnio.kciktv.shared.data.model.Emote(idLong, null, word))
                     }
                     "[emote:$emoteId:$word]"
                } else word
            }
        }
        
        // Add collected emotes to Quick Emote Bar history
        usedEmotes.distinctBy { it.id }.forEach { 
             try {
                 activity.quickEmoteBarManager.addRecentEmote(it)
             } catch(e: Exception) {
                 e.printStackTrace()
             }
        }
        
        message = processedWords.joinToString(" ")
        message = message.trim()
        
        val chatroomId = chatStateManager.currentChatroomId ?: return
        val token = prefs.authToken ?: return
        val messageRef = System.currentTimeMillis().toString()
        
        // Celebration Interception
        if (isCelebrationMode && currentCelebration != null && currentCelebrationSlug != null) {
             val celebrationId = currentCelebration!!.id
             val slug = currentCelebrationSlug!!
             
             // Consume immediately with message
             lifecycleScope.launch {
                 val originalText = message
                 
                 // Optimistic or waiting? Let's clear input first
                 if (!isOverride) {
                    binding.chatInput.text?.clear()
                 }
                 exitCelebrationMode()
                 
                 // Perform generic action with message
                 val result = repository.performCelebrationAction(slug, celebrationId, "consume", message, token)
                 
                 result.onFailure { _ ->
                     // If failed, restore the message to input so user doesn't lose it
                     if (!isOverride) {
                         binding.chatInput.setText(originalText)
                         try {
                             binding.chatInput.setSelection(originalText.length)
                         } catch (e: Exception) {}
                     }
                     // Show error
                     activity.addSystemMessage(activity.getString(R.string.error_generic), R.drawable.ic_error)
                     
                     // Improve: Re-enter celebration mode so they can try again immediately?
                     // For now, allow them to initiate again via the restored button.
                 }
             }
             return
        }

        if (!isOverride) {
            binding.chatInput.text?.clear()
            binding.chatInput.clearFocus()
            // Hide keyboard
            val imm = activity.getSystemService(android.content.Context.INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager
            imm.hideSoftInputFromWindow(binding.chatInput.windowToken, 0)
        }

        // === Synchronous: Show local message IMMEDIATELY on main thread ===
        val sender = chatStateManager.currentUserSender ?: ChatSender(0, prefs.username ?: "Me", "#53fc18", emptyList())
        
        val replyId = chatStateManager.currentReplyMessageId
        val replyMessage = if (replyId != null) chatAdapter.currentList.find { it.id == replyId } else null

        val localMessage = ChatMessage(
            id = "local_$messageRef",
            content = message,
            sender = sender,
            messageRef = messageRef,
            status = MessageStatus.SENDING,
            createdAt = System.currentTimeMillis(),
            metadata = if (replyId != null) ChatMetadata(
                originalSender = replyMessage?.sender,
                originalMessageContent = replyMessage?.content,
                originalMessageId = replyId
            ) else null
        )
        // Add directly to chat adapter for instant display (bypass buffer/flush path)
        chatAdapter.addMessages(listOf(localMessage), onCommit = {
            handleMessageAdded()
            if (binding.chatRecyclerView.visibility != View.VISIBLE) {
                binding.chatRecyclerView.visibility = View.VISIBLE
            }
            binding.emptyChatText.visibility = View.GONE
        })
        
        binding.emptyChatText.visibility = View.GONE
        if (binding.chatRecyclerView.visibility != View.VISIBLE) {
            binding.chatRecyclerView.visibility = View.VISIBLE
        }
        
        chatStateManager.onMessageSent(messageRef)

        if (!isOverride) {
            binding.chatSendButton.visibility = View.GONE
            binding.chatSettingsButton.visibility = View.VISIBLE
        }

        if (replyId != null) cancelReply()

        // === Async: Send API request in background without blocking ===
        lifecycleScope.launch {
            val result = repository.sendChatMessage(token, chatroomId, message, messageRef, replyMessage)
            
            if (result.isSuccess) {
                // Log analytics event (anonymous - just counts, no content)
                activity.analytics.logChatMessageSent()
                
                // Update timestamp from server response if available for accurate slow mode
                result.getOrNull()?.let { serverMessage ->
                    if (serverMessage.createdAt > 0) {
                        chatStateManager.updateLastMessageSent(serverMessage.createdAt)
                    }
                }

                chatAdapter.updateMessageStatus(messageRef, MessageStatus.SENT)
            } else {
                chatAdapter.updateMessageStatus(messageRef, MessageStatus.FAILED)
                result.onFailure { e ->
                    val errorCode = e.message ?: "Unknown"
                    val errorMessage = activity.getLocalizedChatError(errorCode)
                    
                    if (errorCode == "BANNED_USER_ERROR") {
                        chatStateManager.setBanStatus(true, false, 0)
                        activity.updateChatLoginState()
                    }
                    
                    chatStateManager.currentChatErrorMessage = errorMessage
                    binding.chatErrorText.text = errorMessage
                    binding.chatErrorContainer.visibility = View.VISIBLE
                }
            }
        }
    }

    // --- Private Helpers ---
    fun forceScrollToBottomIfEnabled() {
        if (isChatAutoScrollEnabled) {
            // Use post to allow layout to settle if called during layout change
            binding.chatRecyclerView.postDelayed({
                scrollToBottom()
            }, 100)
        }
    }

    private fun fetchClipDetails(clipId: String) {
        lifecycleScope.launch {
            try {
                val result = repository.getClipPlayDetails(clipId)
                result.getOrNull()?.clip?.let { clip ->
                    val channelName = clip.channel?.username ?: clip.creator?.username ?: "Unknown"
                    val channelAvatar = clip.channel?.profilePicture ?: clip.creator?.profilePicture
                    
                    // Run on main thread to update UI
                    mainHandler.post {
                        chatAdapter.updateClipInfo(
                            clipId = clipId, 
                            title = clip.title ?: "", 
                            thumbnail = clip.thumbnailUrl ?: "",
                            channelName = channelName,
                            channelAvatar = channelAvatar,
                            views = clip.viewCount ?: 0,
                            duration = clip.duration ?: 0
                        )
                    }
                }
            } catch (e: Exception) {
                // Ignore errors
            }
        }
    }

    private fun scrollToBottom() {
        if (chatAdapter.itemCount > 0) {
            val lm = binding.chatRecyclerView.layoutManager as? LinearLayoutManager
            lm?.scrollToPositionWithOffset(chatAdapter.itemCount - 1, 0)
        }
    }

    private fun handleClipClick(clipId: String) {
        activity.vodManager.playClipById(clipId)
    }

    private fun scrollToRepliedMessage(originalId: String) {
        val position = chatAdapter.currentList.indexOfFirst { it.id == originalId }
        if (position != -1) {
            binding.chatRecyclerView.smoothScrollToPosition(position)
            chatAdapter.highlightMessage(originalId)
        }
    }

    private fun prepareReply(message: ChatMessage) {
        if (!prefs.isLoggedIn) return
        chatStateManager.currentReplyMessageId = message.id
        chatStateManager.currentReplyMessage = message
        
        binding.replyContainer.visibility = View.VISIBLE
        binding.replyUsername.text = activity.getString(R.string.replying_to, message.sender.username)
        // binding.replyMessage.text = message.content
        binding.replyCloseButton.setOnClickListener { cancelReply() }
        
        binding.chatInput.requestFocus()
        val imm = activity.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        imm.showSoftInput(binding.chatInput, InputMethodManager.SHOW_IMPLICIT)
    }

    private fun cancelReply() {
        chatStateManager.currentReplyMessageId = null
        chatStateManager.currentReplyMessage = null
        binding.replyContainer.visibility = View.GONE
    }
    
    private fun copyMessageToClipboard(message: ChatMessage) {
        try {
            val clipboard = activity.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            val clip = ClipData.newPlainText("Kick Message", message.content)
            clipboard.setPrimaryClip(clip)
            Toast.makeText(activity, activity.getString(R.string.message_copied), Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to copy message", e)
        }
    }
    
    private fun showCommandLoading(show: Boolean) {
        if (show) {
            binding.chatSendContainer.visibility = View.VISIBLE
            binding.chatSendButton.visibility = View.GONE
            binding.chatSendLoading.visibility = View.VISIBLE
            binding.chatInput.isEnabled = false
        } else {
            binding.chatSendLoading.visibility = View.GONE
            binding.chatInput.isEnabled = true
            
            // Restore visibility based on input text
            if (binding.chatInput.text.isNullOrEmpty()) {
                binding.chatSendContainer.visibility = View.GONE
                binding.chatSendButton.visibility = View.GONE
                binding.chatSettingsButton.visibility = View.VISIBLE
            } else {
                binding.chatSendContainer.visibility = View.VISIBLE
                binding.chatSendButton.visibility = View.VISIBLE
                binding.chatSettingsButton.visibility = View.GONE
            }
        }
    }

    private fun handleMessageClick(message: ChatMessage) {
        val imm = activity.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        val isEmotePanelOpen = binding.emotePanelContainer.visibility == View.VISIBLE
        val isKeyboardOpen = binding.chatInput.hasFocus()

        if (isEmotePanelOpen || isKeyboardOpen) {
            binding.emotePanelContainer.visibility = View.GONE
            imm.hideSoftInputFromWindow(binding.chatInput.windowToken, 0)
            binding.chatInput.clearFocus()
        } else {
            activity.showMessageActionsSheet(message)
        }
    }
    
    private fun restoreClearedMessages() {
        if (clearedMessages.isNotEmpty()) {
            chatAdapter.clearMessages()
            chatAdapter.addMessages(clearedMessages)
            clearedMessages = emptyList()
            if (isChatAutoScrollEnabled) {
                binding.chatRecyclerView.postDelayed({
                    scrollToBottom()
                }, 50)
            }
        }
    }
    
    private fun dismissRestoreButton() {
        // Discard cleared messages and remove restore button from chat
        clearedMessages = emptyList()
        val currentList = chatAdapter.currentList.toMutableList()
        val restoreButtonIndex = currentList.indexOfFirst { it.type == MessageType.RESTORE_BUTTON }
        if (restoreButtonIndex >= 0) {
            currentList.removeAt(restoreButtonIndex)
            chatAdapter.submitList(currentList)
        }
    }
    
    private fun handleMessageAdded() {
        val layoutManager = binding.chatRecyclerView.layoutManager as? LinearLayoutManager
        layoutManager?.findLastVisibleItemPosition() ?: -1
        val totalItems = chatAdapter.itemCount
        
        // Ensure empty state is hidden when any message (including system msg) is added
        if (totalItems > 0) {
            binding.emptyChatText.visibility = View.GONE
            if (binding.chatRecyclerView.visibility != View.VISIBLE) {
                binding.chatRecyclerView.visibility = View.VISIBLE
            }
        }
        
        // Calculate how many messages were added (diff)
        var added = 1
        if (lastItemCount > 0 && totalItems > lastItemCount) {
             added = totalItems - lastItemCount
        }
        lastItemCount = totalItems
        
        val lastMessage = chatAdapter.currentList.lastOrNull()
        
        // Store sender for mentions - exclude system messages
        if (lastMessage?.type == MessageType.CHAT || lastMessage?.type == MessageType.REWARD || lastMessage?.type == MessageType.CELEBRATION) {
            lastMessage.sender.username.let { 
                 recentChatUsers.remove(it)
                 recentChatUsers.add(it)
            }
        }
        
        val isOwnMessage = lastMessage?.sender?.username?.equals(prefs.username, ignoreCase = true) == true
        
        val shouldScroll = isOwnMessage || isChatAutoScrollEnabled
        
        if (shouldScroll) {
            scrollToBottom()
        } else {
            // User is scrolled up - increment new message counter
            newMessageCount += added
            updateJumpToBottomButton()
        }
    }

    fun updateBufferCount(count: Int) {
        bufferCount = count
        if (!isChatAutoScrollEnabled) {
            updateJumpToBottomButton()
        }
    }
    
    private fun updateJumpToBottomButton() {
        val totalCount = newMessageCount + bufferCount
        if (totalCount > 0) {
            val countText = if (totalCount > 99) "99+" else totalCount.toString()
            binding.chatJumpToBottom.text = activity.getString(R.string.chat_new_messages_count, countText)
        } else {
            binding.chatJumpToBottom.text = activity.getString(R.string.chat_jump_to_bottom)
        }
        binding.chatJumpToBottom.visibility = View.VISIBLE
    }

    private fun updateCommandSuggestions(input: String) {
        if (input.startsWith("/")) {
            val matchingCommands = dev.xacnio.kciktv.shared.data.model.ChatCommands.findMatchingCommands(input, chatStateManager.isModeratorOrOwner)
            if (matchingCommands.isNotEmpty() && input.length <= 20) {
                commandSuggestionsAdapter.updateCommands(matchingCommands)
                binding.commandSuggestionsRecycler.adapter = commandSuggestionsAdapter
                binding.commandSuggestionsRecycler.visibility = View.VISIBLE
            } else {
                binding.commandSuggestionsRecycler.visibility = View.GONE
            }
        } else {
             // If not a command, we might be showing mentions, so don't hide blindly unless mentions logic handles it
             // Current logic calls updateMentionSuggestions right after this, so we rely on that for hiding if needed
             // But to avoid flicker:
             if (binding.commandSuggestionsRecycler.adapter == commandSuggestionsAdapter) {
                 binding.commandSuggestionsRecycler.visibility = View.GONE
             }
        }
    }
    
    private fun updateMentionSuggestions(input: String, cursor: Int) {
        val wordRange = getWordRangeAtCursor(input, cursor)
        val word = if (wordRange != null) input.substring(wordRange.first, wordRange.second) else ""
        
        if (word.startsWith("@")) {
            val query = word.substring(1).lowercase()
            
            // Build candidates list: Recent users (newest first) + Channel Owner at the bottom
            val recents = recentChatUsers.toList().reversed()
            val owner = activity.currentChannel?.username ?: activity.currentChannel?.slug
            
            val candidates = if (!owner.isNullOrEmpty()) {
                (recents + owner).distinct()
            } else {
                recents
            }
            
            val matches = candidates.filter { it.lowercase().startsWith(query) }
                .take(5) // Limit suggestions
                
            if (matches.isNotEmpty()) {
                userSuggestionAdapter.updateUsers(matches)
                binding.commandSuggestionsRecycler.adapter = userSuggestionAdapter
                binding.commandSuggestionsRecycler.visibility = View.VISIBLE
            } else {
                binding.commandSuggestionsRecycler.visibility = View.GONE
            }
        } else {
            // Only hide if we are currently showing mentions (to avoid hiding command suggestions if logic overlaps)
            // But since commands start with / and mentions with @, they shouldn't overlap.
             if (binding.commandSuggestionsRecycler.adapter == userSuggestionAdapter) {
                 binding.commandSuggestionsRecycler.visibility = View.GONE
             }
        }
    }
    
    private fun completeMention(username: String) {
        val fullText = binding.chatInput.text?.toString() ?: ""
        val cursor = binding.chatInput.selectionStart
        val range = getWordRangeAtCursor(fullText, cursor) ?: return
        
        val newText = fullText.replaceRange(range.first, range.second, "@$username ")
        binding.chatInput.setText(newText)
        binding.chatInput.setSelection(range.first + username.length + 2) // @ + name + space
        
        binding.commandSuggestionsRecycler.visibility = View.GONE
    }

    fun appendMention(username: String) {
        val currentText = binding.chatInput.text?.toString() ?: ""
        val newText = if (currentText.isEmpty() || currentText.endsWith(" ")) {
            "$currentText@$username "
        } else {
            "$currentText @$username "
        }
        binding.chatInput.setText(newText)
        binding.chatInput.setSelection(newText.length)
        
        binding.chatInput.requestFocus()
        binding.chatInput.postDelayed({
            val imm = binding.chatInput.context.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
            imm?.showSoftInput(binding.chatInput, InputMethodManager.SHOW_IMPLICIT)
        }, 200)
    }
    
    private fun getWordRangeAtCursor(text: String, cursor: Int): Pair<Int, Int>? {
        if (cursor < 0 || cursor > text.length) return null
        
        // Find start
        var start = cursor
        while (start > 0 && !text[start - 1].isWhitespace()) {
            start--
        }
        
        // Find end
        var end = cursor
        while (end < text.length && !text[end].isWhitespace()) {
            end++
        }
        
        if (start < end) return Pair(start, end)
        return null
    }

    private fun isInputACommand(input: String): Boolean {
        if (!input.startsWith("/")) return false
        val commandName = input.trim().split(" ", limit = 2)[0].drop(1).lowercase()
        val allCommands = dev.xacnio.kciktv.shared.data.model.ChatCommands.getCommandsForUser(chatStateManager.isModeratorOrOwner)
        return allCommands.any { it.name == commandName }
    }

    private fun executeCommand(input: String) {
        val parts = input.trim().split(" ", limit = 2)
        val commandName = parts[0].drop(1).lowercase()
        val argument = if (parts.size > 1) parts[1] else null
        
        val channelSlug = activity.currentChannel?.slug ?: return
        val token = prefs.authToken ?: return
        
        if (commandName == "user" && !argument.isNullOrEmpty()) {
            val username = argument.trim().removePrefix("@")
            val dummySender = ChatSender(0, username, null, null)
            activity.showUserActionsSheet(dummySender)
            binding.chatInput.text?.clear()
            binding.commandSuggestionsRecycler.visibility = View.GONE
            return
        }

        if (commandName == "poll") {
            activity.overlayManager.openCreatePollSheet()
            binding.chatInput.text?.clear()
            binding.commandSuggestionsRecycler.visibility = View.GONE
            return
        }

        if (commandName == "prediction") {
            activity.overlayManager.openPredictionSheet()
            binding.chatInput.text?.clear()
            binding.commandSuggestionsRecycler.visibility = View.GONE
            return
        }

        if (commandName == "category") {
            activity.modActionsManager.openCategoryPicker()
            binding.chatInput.text?.clear()
            binding.commandSuggestionsRecycler.visibility = View.GONE
            return
        }

        if (commandName == "ban" || commandName == "unban" || commandName == "timeout") {
            if (argument.isNullOrBlank()) {
                showChatError("Input a username")
                return
            }

            lifecycleScope.launch {
                val argParts = argument.trim().split(" ", limit = 2)
                val targetUsername = argParts[0].removePrefix("@")
                val extraArg = if (argParts.size > 1) argParts[1] else null

                showCommandLoading(true)
                try {
                    val response = when (commandName) {
                        "ban" -> {
                            val json = JSONObject().put("banned_username", targetUsername).put("permanent", true)
                            extraArg?.let { json.put("reason", it) }
                            RetrofitClient.channelService.banUser(channelSlug, "Bearer $token", json.toString().toRequestBody("application/json".toMediaType()))
                        }
                        "unban" -> {
                            RetrofitClient.channelService.unbanUser(channelSlug, targetUsername, "Bearer $token")
                        }
                        "timeout" -> {
                            // Default 10 mins if not specified, otherwise parse
                            val duration = extraArg?.toIntOrNull() ?: 10
                            val json = JSONObject().put("banned_username", targetUsername).put("duration", duration).put("permanent", false)
                            RetrofitClient.channelService.timeoutUser(channelSlug, "Bearer $token", json.toString().toRequestBody("application/json".toMediaType()))
                        }
                        else -> null
                    }

                    if (response != null && response.isSuccessful) {
                        binding.chatInput.text?.clear()
                        binding.commandSuggestionsRecycler.visibility = View.GONE
                    } else {
                        val errorBody = response?.errorBody()?.string()
                        if (response?.code() == 400 && !errorBody.isNullOrBlank()) {
                            try {
                                val json = JSONObject(errorBody)
                                val errorMessage = json.optString("message")
                                if (errorMessage == "User does not exists") {
                                    showChatError(activity.getString(R.string.error_user_not_exists))
                                    return@launch
                                } else if (errorMessage == "You can not ban a moderator") {
                                    showChatError(activity.getString(R.string.error_cannot_ban_moderator))
                                    return@launch
                                } else if (errorMessage == "You can not ban the channel owner") {
                                    showChatError(activity.getString(R.string.error_cannot_ban_owner))
                                    return@launch
                                }
                            } catch (e: Exception) {
                                Log.e(TAG, "Error parsing mod action error", e)
                            }
                        }
                        showChatError("Error: ${response?.code() ?: "Unknown"}")
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Command error", e)
                    showChatError("Error: ${e.message}")
                } finally {
                    showCommandLoading(false)
                }
            }
            return
        }

        lifecycleScope.launch {
            showCommandLoading(true)
            try {
                val body = buildCommandBody(commandName, argument)
                if (body == null) {
                    Toast.makeText(activity, activity.getString(R.string.unknown_command, commandName), Toast.LENGTH_SHORT).show()
                    return@launch
                }
                
                val response = if (commandName == "clear") {
                    RetrofitClient.channelService.executeChatCommand(channelSlug, "Bearer $token", body)
                } else {
                    RetrofitClient.channelService.updateChatroomSettings(channelSlug, "Bearer $token", body)
                }
                
                if (response.isSuccessful) {
                    binding.chatInput.text?.clear()
                    binding.commandSuggestionsRecycler.visibility = View.GONE
                } else {
                    Toast.makeText(activity, activity.getString(R.string.command_failed), Toast.LENGTH_SHORT).show()
                }
            } catch (e: Exception) {
                Log.e(TAG, "Command execution error", e)
            } finally {
                showCommandLoading(false)
            }
        }
    }
    
    private fun buildCommandBody(commandName: String, argument: String?): okhttp3.RequestBody? {
         val json = JSONObject()
         return when (commandName) {
            "clear" -> json.put("command", "clear")
            "slow" -> json.put("slow_mode", true).put("message_interval", argument?.toIntOrNull() ?: 5)
            "slowoff" -> json.put("slow_mode", false)
            "subscribers" -> json.put("subscribers_mode", true)
            "subscribersoff" -> json.put("subscribers_mode", false)
            "followers" -> json.put("followers_mode", true).put("min_duration", argument?.toIntOrNull() ?: 0)
            "followersoff" -> json.put("followers_mode", false)
            "emoteonly" -> json.put("emotes_mode", true)
            "emoteonlyoff" -> json.put("emotes_mode", false)
            else -> return null
        }.toString().toRequestBody("application/json".toMediaType())
    }

    private fun toggleEmotePanel() {
        activity.toggleEmotePanel()
    }
    
    fun clearChatMessages() {
        val current = chatAdapter.currentList
        if (current.isNotEmpty()) {
            clearedMessages = current.toList()
            chatAdapter.clearMessages()
        }
    }
    
    fun setEmoteMap(map: Map<String, String>) {
         emoteNameToId = map
    }
    
    fun clearIncomingBuffer() {
        incomingMessageBuffer.clear()
        updateBufferCount(0)
    }
    
    fun reset() {
        // Reset Chat List
        chatAdapter.clearMessages()
        clearedMessages = emptyList()
        incomingMessageBuffer.clear()
        
        // Reset Input
        binding.chatInput.text?.clear()
        binding.chatSendContainer.visibility = View.GONE
        binding.chatSendButton.visibility = View.GONE
        binding.chatSettingsButton.visibility = View.VISIBLE
        
        // Reset Celebration
        exitCelebrationMode()
        currentCelebration = null
        currentCelebrationSlug = null
        hideCelebration(defer = false)
        
        // Reset Errors
        binding.chatErrorContainer.visibility = View.GONE
        
        // Reset Overlays
        binding.replyContainer.visibility = View.GONE
        chatStateManager.currentReplyMessageId = null
    }
    
    fun setVodMode(enabled: Boolean) {
        chatAdapter.isVodMode = enabled
        
        if (enabled) {
             val padding = (50 * activity.resources.displayMetrics.density).toInt()
             binding.chatRecyclerView.setPadding(0, 0, 0, padding)
             binding.chatRecyclerView.clipToPadding = false
             
             // Ensure interactions are hidden
             binding.chatInputContainer.visibility = View.GONE
        } else {
             binding.chatRecyclerView.setPadding(0, 0, 0, 0)
        }
    }

    fun setCurrentUserSender(sender: ChatSender?) {
        chatStateManager.currentUserSender = sender
        sender?.let {
            chatAdapter.setCurrentUser(it.username)
        }
    }
    
    fun setModeratorStatus(isMod: Boolean) {
        chatStateManager.setModeratorStatus(isMod)
    }

    fun handleIncomingMessage(message: ChatMessage) {
        incomingMessageBuffer.add(message)
        updateBufferCount(incomingMessageBuffer.size)
    }

    fun startFlushing() {
        mainHandler.removeCallbacks(flushMessagesRunnable)
        mainHandler.post(flushMessagesRunnable)
    }

    fun stopFlushing() {
        mainHandler.removeCallbacks(flushMessagesRunnable)
    }

    private val flushMessagesRunnable = object : Runnable {
        override fun run() {
            // If in mini player mode, don't reschedule - flushing will be restarted when needed
            if (activity.miniPlayerManager.isMiniPlayerMode) {
                return
            }
            
            if (!isChatUiPaused) {
                flushPendingMessages()
            }
            updateBufferCount(incomingMessageBuffer.size)

            val isPip = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.N) activity.isInPictureInPictureMode else false
            val isBackground = isChatUiPaused

            val useSlowInterval = isPip || isBackground
            val baseInterval = if (useSlowInterval) 1000L else prefs.chatRefreshRate

            val interval = if (prefs.lowBatteryModeEnabled) 1500L else baseInterval
            mainHandler.postDelayed(this, interval)
        }
    }

    fun flushPendingMessages() {
        val messages = synchronized(incomingMessageBuffer) {
            val list = ArrayList(incomingMessageBuffer)
            incomingMessageBuffer.clear()
            list
        }
        
        if (messages.isEmpty()) return
        
        val toAdd = ArrayList<ChatMessage>()
        val currentUser = prefs.username
        var anyUpdated = false
        var hasMention = false
        
        messages.forEach { msg ->
            // Process recurring emote logic 
            activity.emoteComboManager.processMessage(msg.content)
            activity.floatingEmoteManager.processMessage(msg.content)
            
            var handled = false
            // Check if this message is from current user and might be a duplicate/echo
            if (currentUser != null && msg.sender.username.equals(currentUser, ignoreCase = true)) {
                // Update slow mode timestamp only for own messages
                if (msg.createdAt > 0) {
                    chatStateManager.updateLastMessageSent(msg.createdAt)
                }
                // Try to confirm an existing pending message by content if ID matching failed earlier
                if (chatAdapter.confirmSentMessageByContent(msg)) {
                    handled = true
                    anyUpdated = true
                }
            }
            
            // Check for mentions or replies (from other users only)
            if (currentUser != null && !msg.sender.username.equals(currentUser, ignoreCase = true)) {
                val isMention = msg.content.contains("@$currentUser", ignoreCase = true)
                val isReplyToMe = msg.metadata?.originalSender?.username == currentUser
                if (isMention || isReplyToMe) {
                    activity.mentionMessages.add(msg)
                    hasMention = true
                }
            }
            
            if (!handled) {
                toAdd.add(msg)
            }
        }
        
        // Update mentions badge (triggers vibration internally if needed)
        if (hasMention) {
            activity.updateMentionsBadge()
        }
        
        if (toAdd.isNotEmpty()) {
            chatAdapter.addMessages(toAdd, onCommit = {
                handleMessageAdded()
                if (binding.chatRecyclerView.visibility != View.VISIBLE) {
                    binding.chatRecyclerView.visibility = View.VISIBLE
                }
                binding.emptyChatText.visibility = View.GONE
            })
        } else if (anyUpdated) {
            // If we updated messages but didn't add new ones, still update scroll state if needed
            handleMessageAdded()
        }
    }

    fun loadChatHistory(channelId: Long) {
        lifecycleScope.launch {
            try {
                activity.runOnUiThread {
                    // Stay INVISIBLE so we can layout in background without glitching
                    chatAdapter.clearMessages()
                    binding.chatShimmer.root.visibility = View.VISIBLE
                    binding.chatRecyclerView.visibility = View.INVISIBLE
                    binding.emptyChatText.visibility = View.GONE
                }

                val result = repository.getChatHistory(channelId, null)
                result.onSuccess { history ->
                    val messages = history.messages

                    if (messages.isNotEmpty()) {
                        activity.runOnUiThread {
                            if (chatStateManager.currentUserSender == null) {
                                messages.findLast { it.sender.username == prefs.username }?.let {
                                    chatStateManager.currentUserSender = it.sender
                                }
                            }

                            // Submit messages while RV is still INVISIBLE to avoid flickering
                            chatAdapter.addMessages(messages, deduplicate = true, animate = false, onCommit = {
                                activity.runOnUiThread {
                                    // Give it a tiny bit of time to settle layout
                                    binding.chatRecyclerView.post {
                                        scrollToBottom()
                                        binding.chatRecyclerView.postDelayed({
                                            binding.chatShimmer.root.visibility = View.GONE
                                            binding.chatRecyclerView.visibility = View.VISIBLE
                                            scrollToBottom()
                                        }, 50)
                                    }
                                }
                            })

                            // Populate recent users for mention suggestions
                            messages.forEach { msg ->
                                recentChatUsers.add(msg.sender.username)
                            }
                            
                            binding.chatJumpToBottom.visibility = View.GONE
                            binding.emptyChatText.visibility = View.GONE
                        }
                    } else {
                        activity.runOnUiThread {
                            binding.chatShimmer.root.visibility = View.GONE
                            binding.emptyChatText.text = activity.getString(R.string.no_messages)
                            binding.emptyChatText.visibility = View.VISIBLE
                            binding.chatRecyclerView.visibility = View.VISIBLE
                        }
                    }

                    val pinnedMsg = history.pinnedMessage
                    if (pinnedMsg != null) {
                         val pinnedMessage = PinnedMessage(
                             id = pinnedMsg.id,
                             content = pinnedMsg.content,
                             sender = pinnedMsg.sender,
                             createdAt = pinnedMsg.createdAt
                         )
                         activity.overlayManager.handlePinnedMessageFromHistory(pinnedMessage)
                    } else {
                         activity.overlayManager.handlePinnedMessageFromHistory(null)
                    }
                }.onFailure { e ->
                    Log.e(TAG, "Failed loading history", e)
                    activity.runOnUiThread {
                        binding.chatShimmer.root.visibility = View.GONE
                        binding.emptyChatText.text = activity.getString(R.string.history_load_failed)
                        binding.emptyChatText.visibility = View.VISIBLE
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error in loadChatHistory", e)
                activity.runOnUiThread {
                     binding.chatShimmer.root.visibility = View.GONE
                     binding.emptyChatText.text = activity.getString(R.string.history_load_failed)
                     binding.emptyChatText.visibility = View.VISIBLE
                }
            }
        }
    }
    // Celebration Logic
    private var pendingCelebrations: MutableList<ChannelCelebration> = mutableListOf()
    private var currentCelebration: ChannelCelebration? = null
    private var currentCelebrationSlug: String? = null

    fun handleCelebrations(celebrations: List<ChannelCelebration>, slug: String) {
        pendingCelebrations.clear()
        pendingCelebrations.addAll(celebrations)
        currentCelebrationSlug = slug
        
        if (pendingCelebrations.isNotEmpty()) {
            showCelebration(pendingCelebrations[0])
        }
    }

    private fun showCelebration(celebration: ChannelCelebration) {
        currentCelebration = celebration
        val container = binding.celebrationContainer
        
        container.root.visibility = View.VISIBLE
        binding.chatInputCelebrationButton.visibility = View.GONE

        container.celebrationBannerUser.text = prefs.username
        
        if (celebration.type == "subscription_renewed") {
             container.celebrationBannerTitle.text = activity.getString(R.string.celebration_banner_sub_renew_title)
             val months = celebration.metadata?.months ?: 1
             container.celebrationBannerSubtitle.text = activity.getString(R.string.celebration_banner_sub_renew_duration, months)
        } else {
             container.celebrationBannerTitle.text = activity.getString(R.string.chat_celebration_generic_title)
             container.celebrationBannerSubtitle.text = ""
        }
        
        // Continuous Loop Animation (Swing + Scale)
        // Reset state
        container.celebrationBannerIcon.rotation = 0f
        container.celebrationBannerIcon.scaleX = 0.9f
        container.celebrationBannerIcon.scaleY = 0.9f
        
        // Cancel any existing animation on this view
        container.celebrationBannerIcon.clearAnimation()

        val rotationAnim = android.animation.ObjectAnimator.ofFloat(container.celebrationBannerIcon, "rotation", -15f, 15f).apply {
            duration = 800
            repeatCount = android.animation.ObjectAnimator.INFINITE
            repeatMode = android.animation.ObjectAnimator.REVERSE
            interpolator = android.view.animation.AccelerateDecelerateInterpolator()
        }
        
        val scaleXAnim = android.animation.ObjectAnimator.ofFloat(container.celebrationBannerIcon, "scaleX", 0.9f, 1.1f).apply {
            duration = 600
            repeatCount = android.animation.ObjectAnimator.INFINITE
            repeatMode = android.animation.ObjectAnimator.REVERSE
            interpolator = android.view.animation.AccelerateDecelerateInterpolator()
        }

        val scaleYAnim = android.animation.ObjectAnimator.ofFloat(container.celebrationBannerIcon, "scaleY", 0.9f, 1.1f).apply {
            duration = 600
            repeatCount = android.animation.ObjectAnimator.INFINITE
            repeatMode = android.animation.ObjectAnimator.REVERSE
             interpolator = android.view.animation.AccelerateDecelerateInterpolator()
        }
        
        val animatorSet = android.animation.AnimatorSet()
        animatorSet.playTogether(rotationAnim, scaleXAnim, scaleYAnim)
        animatorSet.start()
        
        // Store animator to cancel later if needed (e.g. in hideCelebration)
        // For now, view.clearAnimation() or standard lifecycle handling is usually enough for simple UI.

        container.celebrationBannerShare.setOnClickListener {
             // Instead of direct consume, enter context mode
             enterCelebrationMode(celebration)
        }
        
        container.celebrationBannerClose.setOnClickListener {
            // Treat X as Defer (Share Later)
            hideCelebration(defer = true)
        }
    }
    
    fun hideCelebration(defer: Boolean = false) {
        binding.celebrationContainer.root.visibility = View.GONE
        if (defer && currentCelebration != null) {
            binding.chatInputCelebrationButton.visibility = View.VISIBLE
        } else {
            binding.chatInputCelebrationButton.visibility = View.GONE
            currentCelebration = null
        }
    }

    private fun performCelebrationAction(id: String, action: String) {
        val slug = currentCelebrationSlug ?: return
        val token = prefs.authToken ?: return
        
        lifecycleScope.launch {
            repository.performCelebrationAction(slug, id, action, null, token)
            
            if (action == "consume" || action == "dismiss") {
                hideCelebration(defer = false)
            }
        }
    }

    private fun setupCelebrationListeners() {
        binding.chatInputCelebrationButton.setOnClickListener {
            currentCelebration?.let { showCelebration(it) }
        }
    }

    // Celebration Context Logic
    var isCelebrationMode = false

    private fun enterCelebrationMode(celebration: ChannelCelebration) {
        // Hide banner
        hideCelebration(defer = false)
        
        isCelebrationMode = true
        currentCelebration = celebration
        
        // Show Context UI
        val ctxContainer = binding.celebrationContextContainer
        ctxContainer.root.visibility = View.VISIBLE
        
        // Force Send Button Visible immediately
        updateSendButtonState()
        
        // Update context text
        val channelName = currentCelebrationSlug ?: ""
        val months = celebration.metadata?.months ?: 1
        val formatted = activity.getString(R.string.celebration_context_info_sub, channelName, months)
        ctxContainer.celebrationContextInfo.text = formatted
        
        // Setup Close Button
        ctxContainer.celebrationContextClose.setOnClickListener {
            exitCelebrationMode()
        }
        
        // Focus input
        binding.chatInput.requestFocus()
        try {
            val imm = activity.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
            imm.showSoftInput(binding.chatInput, InputMethodManager.SHOW_IMPLICIT)
        } catch(e:Exception){}
    }

    private fun exitCelebrationMode() {
        isCelebrationMode = false
        binding.celebrationContextContainer.root.visibility = View.GONE
        
        // Restore correct button visibility
        updateSendButtonState()
        
        if (currentCelebration != null) {
            hideCelebration(defer = true)
        }
    }
}
