/**
 * File: OverlayManager.kt
 *
 * Description: Controls the interactive overlays displayed on top of the chat.
 * This includes managing the lifecycle and UI states for Pinned Messages, Polls, and Predictions,
 * ensuring they render correctly and handle user interactions like voting or betting.
 *
 * Author: Xacnio
 *
 */
package dev.xacnio.kciktv.mobile.ui.chat

import android.annotation.SuppressLint
import android.app.AlertDialog
import android.app.Dialog
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.ClipDrawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.LayerDrawable
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.text.Spannable
import android.text.SpannableStringBuilder
import android.text.style.ForegroundColorSpan
import android.text.style.StyleSpan
import android.transition.AutoTransition
import android.transition.TransitionManager
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.*
import androidx.lifecycle.lifecycleScope
import com.bumptech.glide.Glide
import com.google.android.material.bottomsheet.BottomSheetDialog
import dev.xacnio.kciktv.mobile.MobilePlayerActivity
import dev.xacnio.kciktv.R
import dev.xacnio.kciktv.shared.data.model.*
import dev.xacnio.kciktv.shared.data.model.PinnedMessage
import dev.xacnio.kciktv.shared.data.repository.ChannelRepository
import dev.xacnio.kciktv.shared.ui.dialog.CreatePollBottomSheet
import dev.xacnio.kciktv.shared.ui.dialog.CreatePredictionBottomSheet
import dev.xacnio.kciktv.mobile.ui.player.VodManager
import kotlinx.coroutines.launch
import java.text.NumberFormat
import java.text.SimpleDateFormat
import dev.xacnio.kciktv.shared.ui.adapter.ChatAdapter
import dev.xacnio.kciktv.shared.ui.utils.EmoteManager
import java.util.*
import kotlin.math.roundToInt
import dev.xacnio.kciktv.shared.data.chat.KickViewerWebSocket
import dev.xacnio.kciktv.shared.ui.view.BallVisualizerView

class OverlayManager(
    private val activity: MobilePlayerActivity,
    private val repository: ChannelRepository
) {
    private val TAG = "OverlayManager"
    private val binding = activity.binding
    private val prefs = activity.prefs
    private val lifecycleScope = activity.lifecycleScope

    private var pollTimer: Timer? = null
    private var predictionTimer: Timer? = null
    private var predictionBottomSheet: BottomSheetDialog? = null
    private var createPredictionDialog: Dialog? = null
    private var resolveDialog: Dialog? = null
    private var deleteConfirmDialog: Dialog? = null
    var viewerWebSocket: dev.xacnio.kciktv.shared.data.chat.KickViewerWebSocket? = null
    
    // Gift Metadata Cache
    private val giftsMetadata = mutableMapOf<String, GiftMetadata>()
    private val pinnedGiftsTimerHandler = Handler(Looper.getMainLooper())
    private val pinnedGiftsTimerRunnable = object : Runnable {
        override fun run() {
            updatePinnedGiftsTimers()
            pinnedGiftsTimerHandler.postDelayed(this, 1000)
        }
    }

    init {
        setupOverlayTouchListeners()
        setupButtonListeners()
    }

    private fun setupButtonListeners() {
        binding.restorePinnedMessage.setOnClickListener { restorePinnedMessageManually() }
        binding.restorePoll.setOnClickListener { restorePollManually() }
        binding.restorePrediction.setOnClickListener { restorePredictionManually() }
        
        binding.pinnedExpand.setOnClickListener { togglePinnedMessageExpansion() }
        binding.pollExpand.setOnClickListener { togglePollExpansion() }
    }

    private val pinnedGifts: MutableList<PinnedGift>
        get() = activity.chatStateManager.pinnedGifts

    fun resetForNewChannel() {
        stopPollTimer()
        stopPredictionTimer()
        predictionBottomSheet?.dismiss()
        predictionBottomSheet = null
        pinnedGifts.clear()
        giftsMetadata.clear()
        binding.pinnedGiftsLayout.removeAllViews()
        binding.pinnedGiftsBlur.visibility = View.GONE
        
        binding.pinnedMessageContainer.visibility = View.GONE
        binding.pollContainer.visibility = View.GONE
        binding.predictionContainer.visibility = View.GONE
        binding.restorePinnedMessage.visibility = View.GONE
        binding.restorePoll.visibility = View.GONE
        updateChatOverlayState()
    }
    
    fun fetchInitialPoll(slug: String) {
        lifecycleScope.launch {
            repository.getPoll(slug).onSuccess { poll ->
                activity.runOnUiThread {
                    if (poll != null) updatePollUI(poll)
                }
            }
        }
    }

    fun fetchInitialPrediction(slug: String) {
        val token = prefs.authToken ?: return
        lifecycleScope.launch {
            repository.getActivePrediction(slug, token).onSuccess { prediction ->
                activity.runOnUiThread {
                    if (prediction != null) updatePredictionUI(prediction)
                }
            }
        }
    }
    
    fun fetchGiftsMetadata() {
        if (giftsMetadata.isNotEmpty()) return
        lifecycleScope.launch {
            repository.getGifts().onSuccess { gifts ->
                gifts.forEach { if (it.giftId != null) giftsMetadata[it.giftId] = it }
            }
        }
    }

    fun fetchPinnedGifts(channelId: Long) {
        fetchGiftsMetadata()
        lifecycleScope.launch {
            repository.getPinnedGifts(channelId).onSuccess { gifts ->
                activity.runOnUiThread {
                    pinnedGifts.clear()
                    pinnedGifts.addAll(gifts)
                    updatePinnedGiftsUI()
                    restartPinnedGiftsTimer()
                }
            }
        }
    }

    var lastOverlayHideTime: Long = 0
    var lastHiddenViewType: String = ""

    fun updateChatOverlayState() {
        updateChatOverlayMargin()
        val hasPinned = activity.chatStateManager.isPinnedMessageActive && !activity.chatStateManager.isPinnedMessageHiddenByManual
        val hasPoll = activity.chatStateManager.currentPoll != null && !activity.chatStateManager.isPollHiddenManually
        val hasPrediction = activity.chatStateManager.currentPrediction != null && !activity.chatStateManager.isPredictionHiddenManually
        
        val shouldShow = hasPinned || hasPoll || hasPrediction
        
        if (!shouldShow && binding.chatOverlayContainer.visibility == View.VISIBLE) {
            lastOverlayHideTime = System.currentTimeMillis()
            lastHiddenViewType = "chat_overlay"
        }
        
        binding.chatOverlayContainer.visibility = if (shouldShow) View.VISIBLE else View.GONE
        
        if (hasPinned) {
            if (binding.pinnedMessageContainer.visibility != View.VISIBLE) showOverlayView(binding.pinnedMessageContainer)
        } else if (binding.pinnedMessageContainer.translationX == 0f) {
            binding.pinnedMessageContainer.visibility = View.GONE
        }
        
        if (hasPoll) {
            if (binding.pollContainer.visibility != View.VISIBLE) showOverlayView(binding.pollContainer)
        } else if (binding.pollContainer.translationX == 0f) {
            binding.pollContainer.visibility = View.GONE
        }
        
        if (hasPrediction) {
            if (binding.predictionContainer.visibility != View.VISIBLE) showOverlayView(binding.predictionContainer)
        } else if (binding.predictionContainer.translationX == 0f) {
            binding.predictionContainer.visibility = View.GONE
        }

        // Restore Buttons Logic
        binding.restorePinnedMessage.visibility = if (activity.chatStateManager.isPinnedMessageActive && activity.chatStateManager.isPinnedMessageHiddenByManual) View.VISIBLE else View.GONE
        binding.restorePoll.visibility = if (activity.chatStateManager.currentPoll != null && activity.chatStateManager.isPollHiddenManually) View.VISIBLE else View.GONE
        binding.restorePrediction.visibility = if (activity.chatStateManager.currentPrediction != null && activity.chatStateManager.isPredictionHiddenManually) View.VISIBLE else View.GONE

        // Z-Ordering logic for 3 elements
        val overlays = mutableListOf<Pair<String, View>>()
        if (hasPinned) overlays.add("pinned" to binding.pinnedMessageContainer)
        if (hasPoll) overlays.add("poll" to binding.pollContainer)
        if (hasPrediction) overlays.add("prediction" to binding.predictionContainer)

        if (overlays.size >= 2) {
            val primary = overlays.find { it.first == activity.chatStateManager.primaryOverlayItem } ?: overlays.first()
            val secondaries = overlays.filter { it.first != primary.first }

            primary.second.bringToFront()
            primary.second.translationZ = 12f * activity.resources.displayMetrics.density
            primary.second.animate()
                .translationY(0f)
                .scaleX(1.0f)
                .scaleY(1.0f)
                .alpha(1.0f)
                .setDuration(300)
                .start()

            secondaries.forEachIndexed { index, pair ->
                pair.second.translationZ = (index * 4f) * activity.resources.displayMetrics.density
                pair.second.animate()
                    .translationY(-(25f * (index + 1)) * activity.resources.displayMetrics.density)
                    .scaleX(0.95f - (index * 0.05f))
                    .scaleY(0.95f - (index * 0.05f))
                    .alpha(0.8f - (index * 0.2f))
                    .setDuration(300)
                    .start()
            }
        } else if (overlays.size == 1) {
            val single = overlays.first().second
            single.bringToFront()
            single.animate()
                .translationY(0f)
                .scaleX(1.0f)
                .scaleY(1.0f)
                .alpha(1.0f)
                .setDuration(300)
                .start()
        }
    }

    fun swapOverlayStack() {
        val hasPinned = activity.chatStateManager.isPinnedMessageActive && !activity.chatStateManager.isPinnedMessageHiddenByManual
        val hasPoll = activity.chatStateManager.currentPoll != null && !activity.chatStateManager.isPollHiddenManually
        val hasPrediction = activity.chatStateManager.currentPrediction != null && !activity.chatStateManager.isPredictionHiddenManually
        
        val activeItems = mutableListOf<String>()
        if (hasPinned) activeItems.add("pinned")
        if (hasPoll) activeItems.add("poll")
        if (hasPrediction) activeItems.add("prediction")
        
        if (activeItems.size > 1) {
            val currentIndex = activeItems.indexOf(activity.chatStateManager.primaryOverlayItem)
            val nextIndex = (currentIndex + 1) % activeItems.size
            activity.chatStateManager.primaryOverlayItem = activeItems[nextIndex]
            
            activity.runOnUiThread {
                updateChatOverlayState()
            }
        }
    }

    fun updateChatOverlayMargin() {
        binding.root.post {
            try {
                val density = activity.resources.displayMetrics.density
                
                val giftsHeight = if (binding.pinnedGiftsBlur.visibility == View.VISIBLE) {
                    binding.pinnedGiftsBlur.height.takeIf { it > 0 } ?: 0
                } else 0
                
                // NOTE: infoPanelHeight + actionBarHeight are NOT included here because
                // chatContainer.paddingTop already pushes all children (including overlay) down.
                val params = binding.chatOverlayContainer.layoutParams as? ViewGroup.MarginLayoutParams
                if (params != null) {
                    val newMargin = giftsHeight + (8 * density).toInt()
                    if (params.topMargin != newMargin) {
                        params.topMargin = newMargin
                        binding.chatOverlayContainer.layoutParams = params
                    }
                }
            } catch (e: Exception) {}
        }
    }

    fun updatePinnedMessageUIState() {
        if (activity.chatStateManager.isPinnedMessageExpanded) {
            binding.pinnedMsgText.maxLines = Int.MAX_VALUE
            binding.pinnedDetailSection.visibility = View.VISIBLE
            binding.pinnedExpand.rotation = 180f
        } else {
            binding.pinnedMsgText.maxLines = 2
            binding.pinnedMsgText.scrollY = 0
            binding.pinnedDetailSection.visibility = View.GONE
            binding.pinnedExpand.rotation = 0f
        }
    }

    fun togglePinnedMessageExpansion() {
        val transition = AutoTransition()
        transition.duration = 300
        TransitionManager.beginDelayedTransition(binding.chatContainer, transition)
        activity.chatStateManager.isPinnedMessageExpanded = !activity.chatStateManager.isPinnedMessageExpanded
        updatePinnedMessageUIState()
    }

    fun hidePinnedMessageManually() {
        activity.chatStateManager.isPinnedMessageHiddenByManual = true
        binding.restorePinnedMessage.visibility = View.VISIBLE
        updateChatOverlayState()
    }

    fun restorePinnedMessageManually() {
        activity.chatStateManager.isPinnedMessageHiddenByManual = false
        binding.restorePinnedMessage.visibility = View.GONE
        updateChatOverlayState()
    }

    fun showUnpinConfirmationDialog() {
        val slug = activity.currentChannel?.slug ?: return
        AlertDialog.Builder(activity)
            .setTitle(activity.getString(R.string.unpin_title))
            .setMessage(activity.getString(R.string.unpin_message))
            .setPositiveButton(R.string.remove) { _, _ ->
                unpinMessageFromChannel(slug)
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun unpinMessageFromChannel(slug: String) {
        val token = prefs.authToken ?: return
        lifecycleScope.launch {
            repository.unpinMessage(slug, token).onSuccess {
                activity.runOnUiThread {
                    Toast.makeText(activity, activity.getString(R.string.message_unpinned), Toast.LENGTH_SHORT).show()
                    activity.chatStateManager.isPinnedMessageActive = false
                    hideOverlayView(binding.pinnedMessageContainer)
                }
            }
        }
    }

    // --- Poll Logic ---

    fun updatePollUIState() {
        if (activity.chatStateManager.isPollExpanded) {
            binding.pollCollapseSection.visibility = View.VISIBLE
            binding.pollExpand.rotation = 180f
        } else {
            binding.pollCollapseSection.visibility = View.GONE
            binding.pollExpand.rotation = 0f
        }
    }

    fun togglePollExpansion() {
        val transition = AutoTransition()
        transition.duration = 300
        TransitionManager.beginDelayedTransition(binding.chatOverlayContainer, transition)
        activity.chatStateManager.isPollExpanded = !activity.chatStateManager.isPollExpanded
        updatePollUIState()
    }

    fun hidePollManually() {
        activity.chatStateManager.isPollHiddenManually = true
        binding.restorePoll.visibility = View.VISIBLE
        updateChatOverlayState()
    }

    fun restorePollManually() {
        activity.chatStateManager.isPollHiddenManually = false
        binding.restorePoll.visibility = View.GONE
        updateChatOverlayState()
    }

    fun deletePoll() {
        val slug = activity.currentChannel?.slug ?: return
        val token = prefs.authToken ?: return

        com.google.android.material.dialog.MaterialAlertDialogBuilder(activity)
            .setTitle(activity.getString(R.string.delete_poll_title))
            .setMessage(activity.getString(R.string.delete_poll_message))
            .setPositiveButton(R.string.delete) { _, _ ->
                lifecycleScope.launch {
                    val result = repository.deletePoll(slug, token)
                    if (result.isSuccess) {
                        activity.runOnUiThread {
                            Toast.makeText(activity, activity.getString(R.string.poll_deleted), Toast.LENGTH_SHORT).show()
                            activity.chatStateManager.currentPoll = null
                            binding.pollContainer.visibility = View.GONE
                            updateChatOverlayState()
                        }
                    } else {
                        activity.runOnUiThread {
                            Toast.makeText(activity, activity.getString(R.string.poll_delete_failed), Toast.LENGTH_SHORT).show()
                        }
                    }
                }
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    fun updatePollUI(poll: PollData) {
        val isNewPoll = activity.chatStateManager.currentPoll == null
        activity.chatStateManager.currentPoll = poll
        
        binding.pollTitle.text = poll.title ?: activity.getString(R.string.poll_label)
        
        if (isNewPoll) {
             activity.chatStateManager.primaryOverlayItem = "poll"
             activity.chatStateManager.isPollExpanded = true
        }
        
        updatePollUIState()
        updateChatOverlayState()
        
        val isFinished = (poll.remaining ?: 0) <= 0
        binding.pollDeleteButton.visibility = if (activity.currentChannel?.slug != null && activity.isModeratorOrOwner && !isFinished) View.VISIBLE else View.GONE
        binding.pollDeleteButton.setOnClickListener { deletePoll() }
        
        val totalVotes = poll.options?.sumOf { it.votes ?: 0 } ?: 0
        binding.pollTotalVotes.text = activity.getString(R.string.total_votes, totalVotes)
        
        val maxVotes = poll.options?.maxOfOrNull { it.votes ?: 0 } ?: 0
        binding.pollStatusText.text = if (isFinished) activity.getString(R.string.poll_result_label) else activity.getString(R.string.poll_label)
        binding.pollDurationProgress.visibility = View.VISIBLE

        binding.pollOptionsContainer.removeAllViews()
        poll.options?.forEach { option ->
            if (isFinished && (totalVotes == 0 || (option.votes ?: 0) < maxVotes)) return@forEach

            val optionView = activity.layoutInflater.inflate(R.layout.item_poll_option, binding.pollOptionsContainer, false)
            val labelText = optionView.findViewById<TextView>(R.id.optionLabel)
            val percentageText = optionView.findViewById<TextView>(R.id.optionPercentage)
            val progressBar = optionView.findViewById<ProgressBar>(R.id.optionProgress)
            val checkedIcon = optionView.findViewById<ImageView>(R.id.checkedIcon)
            
            labelText.text = option.label
            val votes = option.votes ?: 0
            val percent = if (totalVotes > 0) (votes * 100 / totalVotes) else 0
            percentageText.text = "$percent% ($votes)"
            progressBar.progress = percent
            
            val hasVotedThis = poll.hasVoted == true && poll.votedOptionId == option.id
            checkedIcon.visibility = if (hasVotedThis) View.VISIBLE else View.GONE
            
            if (isFinished && maxVotes > 0 && (option.votes ?: 0) == maxVotes) {
                labelText.text = "🏆 ${option.label}"
                labelText.setTypeface(null, Typeface.BOLD)
            }

            if (poll.hasVoted == true || isFinished) {
                optionView.alpha = if (hasVotedThis || (isFinished && (option.votes ?: 0) == maxVotes)) 1.0f else 0.6f
                optionView.isEnabled = false
            } else {
                optionView.alpha = 1.0f
                optionView.isEnabled = true
                optionView.setOnClickListener { voteInPoll(option.id) }
            }
            binding.pollOptionsContainer.addView(optionView)
        }
        
        if (isFinished) {
            handlePollCompletion(poll)
        } else {
            activity.chatStateManager.isPollCompleting = false
            startPollTimer()
        }
    }

    private fun startPollTimer() {
        stopPollTimer()
        activity.chatStateManager.isPollCompleting = false
        val poll = activity.chatStateManager.currentPoll ?: return
        
        val remaining = poll.remaining ?: 0
        if (remaining <= 0) {
            handlePollCompletion(poll)
            return
        }
        
        val totalDuration = poll.duration ?: remaining
        val maxMillis = totalDuration * 1000
        val remainingMillis = remaining * 1000L
        
        activity.runOnUiThread {
            if (activity.isFinishing) return@runOnUiThread
            
            binding.pollDurationProgress.max = maxMillis
            binding.pollDurationProgress.progress = remainingMillis.toInt()
            
            val animator = android.animation.ValueAnimator.ofInt(remainingMillis.toInt(), 0)
            animator.duration = remainingMillis
            animator.interpolator = android.view.animation.LinearInterpolator()
            
            animator.addUpdateListener { animation ->
                 if (activity.chatStateManager.isPollCompleting) {
                     animation.cancel()
                     return@addUpdateListener
                 }
                 val value = animation.animatedValue as Int
                 binding.pollDurationProgress.progress = value
            }
            
            animator.addListener(object : android.animation.AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: android.animation.Animator) {
                     // If animation finished naturally (not cancelled and is the current tag)
                     if (!activity.chatStateManager.isPollCompleting && binding.pollDurationProgress.tag == animation) {
                         handlePollCompletion(poll)
                     }
                }
            })
            
            animator.start()
            binding.pollDurationProgress.tag = animator
        }
    }

    fun stopPollTimer() {
        pollTimer?.cancel()
        pollTimer = null
        
        try {
            // Cancel Active Timer (stored in progress view)
            val activeAnimator = binding.pollDurationProgress.tag as? android.animation.ValueAnimator
            if (activeAnimator != null) {
                binding.pollDurationProgress.tag = null // Clear tag FIRST
                activeAnimator.removeAllListeners() // Remove listeners to be extra safe
                activeAnimator.cancel()
            }
            
            // Also cancel completion runnable if exists
            val runnable = binding.pollContainer.tag as? Runnable
            if (runnable != null) {
                binding.pollContainer.removeCallbacks(runnable)
                binding.pollContainer.tag = null
            }
        } catch (e: Exception) {}
    }

    private fun voteInPoll(optionId: Int) {
        val slug = activity.currentChannel?.slug ?: return
        val token = prefs.authToken ?: return
        lifecycleScope.launch {
            repository.voteInPoll(slug, token, optionId)
        }
    }

    private fun handlePollCompletion(poll: PollData) {
        if (activity.chatStateManager.isPollCompleting) return
        
        // Stop Active Timer first
        (binding.pollDurationProgress.tag as? android.animation.ValueAnimator)?.cancel()
        binding.pollDurationProgress.tag = null
        
        val displayDuration = poll.resultDisplayDuration ?: 15
        Log.d("OverlayManager", "handlePollCompletion: displayDuration = $displayDuration")
        
        if (displayDuration <= 0) {
             activity.runOnUiThread {
                 activity.chatStateManager.currentPoll = null
                 binding.pollContainer.visibility = View.GONE
                 updateChatOverlayState()
             }
             return
        }

        activity.chatStateManager.isPollCompleting = true
        
        activity.runOnUiThread {
            binding.pollContainer.visibility = View.VISIBLE
            binding.pollStatusText.text = activity.getString(R.string.poll_result_label)
            binding.pollDurationProgress.visibility = View.VISIBLE
            
            val maxProgress = displayDuration * 1000
            binding.pollDurationProgress.max = maxProgress
            binding.pollDurationProgress.progress = maxProgress
            
            val animator = android.animation.ValueAnimator.ofInt(maxProgress, 0)
            animator.duration = displayDuration * 1000L
            animator.interpolator = android.view.animation.LinearInterpolator()
            
            animator.addUpdateListener { animation ->
                 if (!activity.chatStateManager.isPollCompleting) {
                     animation.cancel()
                     return@addUpdateListener
                 }
                 val value = animation.animatedValue as Int
                 binding.pollDurationProgress.progress = value
            }
            
            animator.addListener(object : android.animation.AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: android.animation.Animator) {
                    if (activity.chatStateManager.isPollCompleting && !activity.isFinishing) {
                        try {
                            activity.chatStateManager.currentPoll = null
                            activity.chatStateManager.isPollCompleting = false
                            hideOverlayView(binding.pollContainer)
                            binding.pollContainer.tag = null
                        } catch (e: Exception) {}
                    }
                }
            })
            
            animator.start()
            binding.pollContainer.tag = animator
        }
    }

    fun openCreatePollSheet() {
        val slug = activity.currentChannel?.slug ?: return
        CreatePollBottomSheet.newInstance(slug).show(activity.supportFragmentManager, "poll_creation_sheet")
    }

    fun updatePredictionUI(prediction: PredictionData) {
        val stateStr = prediction.state?.lowercase()
        val isNewPrediction = activity.chatStateManager.currentPrediction == null || activity.chatStateManager.currentPrediction?.id != prediction.id
        
        // If opening channel and prediction is already resolved or cancelled, don't show it
        // (unless user is moderator/owner who might want to see cancelled predictions to create new ones)
        if (isNewPrediction && (stateStr == "resolved" || stateStr == "cancelled")) {
            binding.predictionContainer.visibility = View.GONE
            predictionBottomSheet?.dismiss()
            activity.chatStateManager.currentPrediction = null
            // Close creation dialog if a prediction appeared
            createPredictionDialog?.dismiss()
            return
        }

        // Close dialogs based on state changes
        if (isNewPrediction) {
            createPredictionDialog?.dismiss()
        }
        
        if (stateStr == "locked" || stateStr == "resolved" || stateStr == "cancelled") {
            betDialog?.dismiss()
            deleteConfirmDialog?.dismiss()
            
            if (stateStr == "resolved" || stateStr == "cancelled") {
                resolveDialog?.dismiss()
            }
            
            if (stateStr != "locked") {
                // Keep create dialog open only if locked (maybe not needed), actually create dialog should close if ANY prediction exists
                createPredictionDialog?.dismiss()
            }
        }

        // Only reset hidden state for resolved/cancelled, not for locked
        if (stateStr == "resolved" || stateStr == "cancelled") {
            if (activity.chatStateManager.isPredictionHiddenManually) activity.chatStateManager.isPredictionHiddenManually = false
        }
        // Preserve userVote if new one is null and old one exists for same prediction
        val oldPrediction = activity.chatStateManager.currentPrediction
        var mergedPrediction = prediction
        if (prediction.userVote == null && oldPrediction != null && oldPrediction.id == prediction.id && oldPrediction.userVote != null) {
            mergedPrediction = prediction.copy(userVote = oldPrediction.userVote)
        }
        activity.chatStateManager.currentPrediction = mergedPrediction
        
        binding.predictionTitle.text = prediction.title ?: activity.getString(R.string.prediction_label)
        
        // Update status text and state icon based on state
        // Left icon (predictionIcon) stays as ic_loyalty always
        binding.predictionIcon.setImageResource(R.drawable.ic_loyalty)
        
        when (stateStr) {
            "active" -> {
                binding.predictionStatusText.text = activity.getString(R.string.prediction_label)
                binding.predictionStateIcon.visibility = View.GONE
            }
            "locked" -> {
                binding.predictionStatusText.text = activity.getString(R.string.prediction_label)
                binding.predictionStateIcon.visibility = View.VISIBLE
                binding.predictionStateIcon.setImageResource(R.drawable.ic_lock)
                binding.predictionStateIcon.imageTintList = android.content.res.ColorStateList.valueOf(android.graphics.Color.parseColor("#FFC107"))
            }
            "resolved" -> {
                binding.predictionStatusText.text = activity.getString(R.string.prediction_result_label)
                binding.predictionStateIcon.visibility = View.VISIBLE
                binding.predictionStateIcon.setImageResource(R.drawable.ic_check)
                binding.predictionStateIcon.imageTintList = android.content.res.ColorStateList.valueOf(android.graphics.Color.parseColor("#53FC18"))
            }
            "cancelled" -> {
                binding.predictionStatusText.text = activity.getString(R.string.prediction_label)
                binding.predictionStateIcon.visibility = View.VISIBLE
                binding.predictionStateIcon.setImageResource(R.drawable.ic_prediction_refund)
                binding.predictionStateIcon.imageTintList = null
            }
        }
        
        if (isNewPrediction) {
             activity.chatStateManager.primaryOverlayItem = "prediction"
             activity.chatStateManager.isPredictionExpanded = true
             activity.chatStateManager.shownBallCount = 0
             activity.chatStateManager.currentPredictionEndTime = 0L
        } else {
             activity.chatStateManager.primaryOverlayItem = "prediction"
        }
        
        // Only show prediction container if not manually hidden (but always show for resolved/cancelled)
        val shouldShow = when (stateStr) {
            "resolved", "cancelled" -> true // Always show for resolved/cancelled
            else -> !activity.chatStateManager.isPredictionHiddenManually
        }
        
        if (shouldShow && !activity.chatStateManager.isPredictionCompleting) {
             binding.predictionContainer.visibility = View.VISIBLE
        } else if (activity.chatStateManager.isPredictionCompleting) {
            binding.predictionContainer.visibility = if (activity.chatStateManager.isPredictionHiddenManually) View.GONE else View.VISIBLE
        } else {
            binding.predictionContainer.visibility = View.GONE
        }

        updateChatOverlayState()
        
        val outcomes = prediction.outcomes ?: emptyList()
        if (outcomes.size >= 2) {
            val left = outcomes[0]
            val right = outcomes[1]
            val leftPoints = left.totalVoteAmount ?: 0L
            val rightPoints = right.totalVoteAmount ?: 0L
            val miniTotalPoints = leftPoints + rightPoints
            
            val leftPercentRaw = if (miniTotalPoints > 0) (leftPoints * 100.0 / miniTotalPoints) else 50.0
            val leftRounded = Math.round(leftPercentRaw * 10.0) / 10.0
            val rightRounded = if (miniTotalPoints > 0) (Math.round((100.0 - leftRounded) * 10.0) / 10.0) else 50.0
            
            val leftPercentStr = if (leftRounded % 1.0 == 0.0) "%${leftRounded.toInt()}" else "%" + String.format(java.util.Locale.US, "%.1f", leftRounded)
            val rightPercentStr = if (rightRounded % 1.0 == 0.0) "%${rightRounded.toInt()}" else "%" + String.format(java.util.Locale.US, "%.1f", rightRounded)
            
            binding.predictionLeftPercent.text = leftPercentStr
            binding.predictionLeftName.text = left.title
            binding.predictionLeftPoints.text = activity.getString(R.string.points_label, activity.formatViewerCount(leftPoints))
            binding.predictionRightPercent.text = rightPercentStr
            binding.predictionRightName.text = right.title
            binding.predictionRightPoints.text = activity.getString(R.string.points_label, activity.formatViewerCount(rightPoints))
            
            // Update overlay background balls
            if (prediction.state?.lowercase() == "resolved") {
                if (left.id == prediction.winningOutcomeId) {
                    binding.predictionLeftName.text = "🏆 ${left.title}"
                    binding.predictionLeftStats.setBackgroundResource(R.drawable.bg_prediction_card_green_winner)
                } else if (right.id == prediction.winningOutcomeId) {
                    binding.predictionRightName.text = "🏆 ${right.title}"
                    binding.predictionRightStats.setBackgroundResource(R.drawable.bg_prediction_card_red_winner)
                }
                
                // Show top users for winners and losers
                val winningOutcome = if (left.id == prediction.winningOutcomeId) left else right
                val losingOutcome = if (left.id == prediction.winningOutcomeId) right else left
                
                binding.predictionTopUsersContainer.visibility = View.VISIBLE
                
                // Winners
                val winnerTopUsers = winningOutcome.topUsers
                if (!winnerTopUsers.isNullOrEmpty()) {
                    binding.predictionWinnersRow.visibility = View.VISIBLE
                    val winnersText = winnerTopUsers.joinToString(", ") { user ->
                        val formattedAmount = activity.formatViewerCount(user.amount ?: 0)
                        "${user.username} $formattedAmount"
                    }
                    binding.predictionWinnersText.text = winnersText
                } else {
                    binding.predictionWinnersRow.visibility = View.GONE
                }
                
                // Losers
                val loserTopUsers = losingOutcome.topUsers
                if (!loserTopUsers.isNullOrEmpty()) {
                    binding.predictionLosersRow.visibility = View.VISIBLE
                    val losersText = loserTopUsers.joinToString(", ") { user ->
                        val formattedAmount = activity.formatViewerCount(user.amount ?: 0)
                        "${user.username} $formattedAmount"
                    }
                    binding.predictionLosersText.text = losersText
                } else {
                    binding.predictionLosersRow.visibility = View.GONE
                }
                
                // Hide container if both are empty
                if (winnerTopUsers.isNullOrEmpty() && loserTopUsers.isNullOrEmpty()) {
                    binding.predictionTopUsersContainer.visibility = View.GONE
                }
            } else {
                 binding.predictionLeftStats.setBackgroundResource(R.drawable.bg_prediction_card_green)
                 binding.predictionRightStats.setBackgroundResource(R.drawable.bg_prediction_card_red)
                 binding.predictionTopUsersContainer.visibility = View.GONE
            }
        }
        
        binding.predictionContainer.setOnClickListener { showPredictionBottomSheet(mergedPrediction) }
        if (predictionBottomSheet?.isShowing == true) updatePredictionBottomSheetUI(mergedPrediction)
        
        if (mergedPrediction.state?.lowercase() == "resolved" || mergedPrediction.state?.lowercase() == "cancelled") {
            handlePredictionCompletion(mergedPrediction)
        } else {
            activity.chatStateManager.isPredictionCompleting = false
            startPredictionTimer()
        }
    }

    fun hidePredictionManually() {
        activity.chatStateManager.isPredictionHiddenManually = true
        updateChatOverlayState()
    }

    fun restorePredictionManually() {
        activity.chatStateManager.isPredictionHiddenManually = false
        updateChatOverlayState()
    }

    private fun startPredictionTimer() {
        stopPredictionTimer()
        activity.chatStateManager.isPredictionCompleting = false
        val pred = activity.chatStateManager.currentPrediction ?: return
        
        if (pred.state?.lowercase() == "locked") {
              activity.runOnUiThread {
                  binding.predictionTimerText.visibility = View.VISIBLE
                  binding.predictionTimerText.text = activity.getString(R.string.prediction_status_locked)
                  binding.predictionDurationProgress.visibility = View.GONE
              }
              return
        }
        
        if (pred.state?.lowercase() in listOf("resolved", "cancelled")) {
              activity.runOnUiThread {
                  binding.predictionTimerText.visibility = View.GONE
                  binding.predictionDurationProgress.visibility = View.GONE
              }
              return
        }
        
        // Calculate remaining time - API may not provide 'remaining', so calculate from createdAt + duration
        val remaining: Int = if (pred.remaining != null && pred.remaining > 0) {
            pred.remaining
        } else {
            // Calculate from createdAt and duration
            val duration = pred.duration ?: 60
            val createdAt = pred.createdAt
            if (createdAt != null) {
                try {
                    val sdf = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US)
                    sdf.timeZone = TimeZone.getTimeZone("UTC")
                    val createdTime = sdf.parse(createdAt)?.time ?: System.currentTimeMillis()
                    val elapsedSeconds = ((System.currentTimeMillis() - createdTime) / 1000).toInt()
                    (duration - elapsedSeconds).coerceAtLeast(0)
                } catch (e: Exception) {
                    // Try alternative format without milliseconds
                    try {
                        val sdf2 = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US)
                        sdf2.timeZone = TimeZone.getTimeZone("UTC")
                        val createdTime = sdf2.parse(createdAt)?.time ?: System.currentTimeMillis()
                        val elapsedSeconds = ((System.currentTimeMillis() - createdTime) / 1000).toInt()
                        (duration - elapsedSeconds).coerceAtLeast(0)
                    } catch (e2: Exception) {
                        duration
                    }
                }
            } else {
                duration
            }
        }
        
        if (activity.chatStateManager.currentPredictionEndTime == 0L) {
             activity.chatStateManager.currentPredictionEndTime = System.currentTimeMillis() + (remaining * 1000L)
        }
        
        val endTime = activity.chatStateManager.currentPredictionEndTime
        val totalDuration = (pred.duration ?: remaining) * 1000
        binding.predictionDurationProgress.max = totalDuration
        
        // Calculate initial remaining time
        val initialRemMillis = (endTime - System.currentTimeMillis()).coerceAtLeast(0).toInt()
        val initialMins = (initialRemMillis / 1000) / 60
        val initialSecs = (initialRemMillis / 1000) % 60
        
        activity.runOnUiThread {
             binding.predictionTimerText.visibility = View.VISIBLE
             binding.predictionDurationProgress.visibility = View.VISIBLE
             // Set initial values immediately
             binding.predictionTimerText.text = String.format(Locale.US, "%02d:%02d", initialMins, initialSecs)
             binding.predictionDurationProgress.progress = initialRemMillis
        }
        
        predictionTimer = Timer()
        predictionTimer?.scheduleAtFixedRate(object : TimerTask() {
            override fun run() {
                val now = System.currentTimeMillis()
                val remMillis = (endTime - now).toInt()
                activity.runOnUiThread {
                    if (remMillis <= 0) {
                        stopPredictionTimer()
                        return@runOnUiThread
                    }
                    val mins = (remMillis / 1000) / 60
                    val secs = (remMillis / 1000) % 60
                    binding.predictionTimerText.text = String.format(Locale.US, "%02d:%02d", mins, secs)
                    binding.predictionDurationProgress.progress = remMillis
                    
                    if (predictionBottomSheet?.isShowing == true) {
                        val sheetTimerText = predictionBottomSheet?.findViewById<TextView>(R.id.timerText)
                        sheetTimerText?.text = activity.getString(R.string.prediction_status_open_format, String.format(Locale.US, "%02d:%02d", mins, secs))
                    }
                }
            }
        }, 0, 1000)
    }

    private fun stopPredictionTimer() {
        predictionTimer?.cancel()
        predictionTimer = null
        
        try {
            val runnable = binding.predictionContainer.tag as? Runnable
            if (runnable != null) {
                binding.predictionContainer.removeCallbacks(runnable)
                binding.predictionContainer.tag = null
            }
        } catch (e: Exception) {}
    }

    private var ballVisualizer: dev.xacnio.kciktv.shared.ui.view.BallVisualizerView? = null
    
    fun showPredictionBottomSheet(prediction: PredictionData) {
        if (predictionBottomSheet?.isShowing == true) return
        activity.chatStateManager.shownBallCount = 0
        activity.chatStateManager.selectedOutcomeId = null 

        predictionBottomSheet = BottomSheetDialog(activity, R.style.BottomSheetDialogTheme)
        val view = activity.layoutInflater.inflate(R.layout.bottom_sheet_prediction, null)
        predictionBottomSheet?.setContentView(view)
        (view.parent as? View)?.background = null
        
        // Setup ball visualizer
        val visualizerContainer = view.findViewById<android.widget.FrameLayout>(R.id.visualizerContainer)
        ballVisualizer = dev.xacnio.kciktv.shared.ui.view.BallVisualizerView(activity)
        visualizerContainer?.addView(ballVisualizer, android.widget.FrameLayout.LayoutParams(
            android.widget.FrameLayout.LayoutParams.MATCH_PARENT,
            android.widget.FrameLayout.LayoutParams.MATCH_PARENT
        ))
        
        view.findViewById<View>(R.id.closeButton)?.setOnClickListener { predictionBottomSheet?.dismiss() }
        updatePredictionBottomSheetUI(prediction)
        predictionBottomSheet?.show()
    }

    private fun updatePredictionBottomSheetUI(prediction: PredictionData) {
        val sheet = predictionBottomSheet ?: return
        val state = prediction.state?.lowercase()
        
        sheet.findViewById<TextView>(R.id.predictionTitle)?.text = prediction.title ?: activity.getString(R.string.prediction_label)
        val totalPoints = prediction.outcomes?.sumOf { it.totalVoteAmount ?: 0L } ?: 0L
        sheet.findViewById<TextView>(R.id.totalPointsInfo)?.text = activity.getString(R.string.points_contributed_format, NumberFormat.getIntegerInstance().format(totalPoints))
        
        // Update percent display
        val outcomes = prediction.outcomes ?: emptyList()
        if (outcomes.size >= 2) {
            val leftPoints = outcomes[0].totalVoteAmount ?: 0L
            val rightPoints = outcomes[1].totalVoteAmount ?: 0L
            val total = leftPoints + rightPoints
            val leftPercentRaw = if (total > 0) (leftPoints * 100.0 / total) else 50.0
            val leftRounded = Math.round(leftPercentRaw * 10.0) / 10.0
            val rightRounded = if (total > 0) (Math.round((100.0 - leftRounded) * 10.0) / 10.0) else 50.0

            val leftPercentStr = if (leftRounded % 1.0 == 0.0) "%${leftRounded.toInt()}" else "%" + String.format(java.util.Locale.US, "%.1f", leftRounded)
            val rightPercentStr = if (rightRounded % 1.0 == 0.0) "%${rightRounded.toInt()}" else "%" + String.format(java.util.Locale.US, "%.1f", rightRounded)

            sheet.findViewById<TextView>(R.id.percentLeft)?.text = leftPercentStr
            sheet.findViewById<TextView>(R.id.percentRight)?.text = rightPercentStr
            
            // Show resolved winner or vs display
            val percentVsContainer = sheet.findViewById<LinearLayout>(R.id.percentVsContainer)
            val resolvedWinnerContainer = sheet.findViewById<LinearLayout>(R.id.resolvedWinnerContainer)
            val resolvedWinnerPercent = sheet.findViewById<TextView>(R.id.resolvedWinnerPercent)
            val resolvedWinnerLabel = sheet.findViewById<TextView>(R.id.resolvedWinnerLabel)
            
            if (state == "resolved") {
                percentVsContainer?.visibility = View.GONE
                resolvedWinnerContainer?.visibility = View.VISIBLE
                
                val winningOutcome = outcomes.find { it.id == prediction.winningOutcomeId }
                val winnerPercentStr = if (winningOutcome?.id == outcomes[0].id) leftPercentStr else rightPercentStr
                val winnerColor = if (winningOutcome?.id == outcomes[0].id) "#53FC18" else "#FF4D4D"
                
                resolvedWinnerPercent?.text = winnerPercentStr
                resolvedWinnerPercent?.setTextColor(android.graphics.Color.parseColor(winnerColor))
                resolvedWinnerLabel?.text = activity.getString(R.string.winner_label)
                resolvedWinnerLabel?.setTextColor(android.graphics.Color.parseColor(winnerColor))
            } else {
                // For active, locked, and cancelled states - show normal percentage display
                percentVsContainer?.visibility = View.VISIBLE
                resolvedWinnerContainer?.visibility = View.GONE
            }
            
            ballVisualizer?.post {
                 val leftInt = leftRounded.toInt()
                 ballVisualizer?.updatePercentages(leftInt)
            }
        }
        
        // Options container - use item_prediction_option layout
        val container = sheet.findViewById<LinearLayout>(R.id.optionsContainer) ?: return
        container.removeAllViews()
        
        outcomes.forEachIndexed { index, outcome ->
            val itemView = activity.layoutInflater.inflate(R.layout.item_prediction_option, container, false)
            
            // Set layout params for horizontal distribution (weight 1)
            val params = LinearLayout.LayoutParams(
                0, 
                LinearLayout.LayoutParams.WRAP_CONTENT, 
                1f
            )
            // Add margin between items
            if (index == 0) {
                params.marginEnd = (4 * activity.resources.displayMetrics.density).toInt()
            } else {
                params.marginStart = (4 * activity.resources.displayMetrics.density).toInt()
            }
            itemView.layoutParams = params
            
            // Set option title
            val radioButton = itemView.findViewById<android.widget.RadioButton>(R.id.optionRadioButton)
            radioButton?.text = outcome.title
            radioButton?.isChecked = prediction.userVote?.outcomeId == outcome.id
            
            // Set background color based on index
            if (index == 0) {
                radioButton?.setBackgroundResource(R.drawable.bg_prediction_option_green)
            } else {
                radioButton?.setBackgroundResource(R.drawable.bg_prediction_option_red)
            }
            
            // Set total points for this option
            val totalPointsText = itemView.findViewById<TextView>(R.id.totalPointsText)
            totalPointsText?.text = NumberFormat.getIntegerInstance().format(outcome.totalVoteAmount ?: 0)
            
            // Calculate and show ratio/multiplier
            val multiplierText = itemView.findViewById<TextView>(R.id.multiplierText)
            val returnRate = outcome.returnRate ?: 1.0
            if (multiplierText != null) {
                multiplierText.text = activity.getString(R.string.multiplier_format, String.format(Locale.US, "%.2f", returnRate))
                multiplierText.visibility = View.VISIBLE
            }
            
            // User spent amount & potential win
            val userStatsContainer = itemView.findViewById<LinearLayout>(R.id.userStatsContainer)
            val userSpentText = itemView.findViewById<TextView>(R.id.userSpentText)
            val potentialWinText = itemView.findViewById<TextView>(R.id.potentialWinText)

            val userVotedOnThis = prediction.userVote?.outcomeId == outcome.id
            val userVotedOnOther = prediction.userVote != null && prediction.userVote.outcomeId != outcome.id
            
            if (userVotedOnThis) {
                userStatsContainer?.visibility = View.VISIBLE
                
                val spent = prediction.userVote?.totalVoteAmount ?: 0
                val potentialWin = (spent * returnRate).toLong()
                
                userSpentText?.text = NumberFormat.getIntegerInstance().format(spent)
                potentialWinText?.text = NumberFormat.getIntegerInstance().format(potentialWin)
            } else {
                userStatsContainer?.visibility = View.GONE
            }
            
            // Click listener - only allow voting if active
            // If user voted on another option, disable this one
            if (state == "active") {
                if (userVotedOnOther) {
                    // User voted on different option - disable this one
                    itemView.setOnClickListener(null)
                    itemView.isClickable = false
                    itemView.alpha = 0.4f
                } else {
                    // User can vote on this option (either first vote or adding more)
                    itemView.setOnClickListener { showCustomBetDialog(outcome) }
                    itemView.isClickable = true
                    itemView.alpha = 1.0f
                }
            } else {
                itemView.setOnClickListener(null)
                itemView.isClickable = false
                // If locked/resolved, keep user's choice visible
                itemView.alpha = if (userVotedOnThis) 1.0f else 0.5f
            }
            
            // Show winner indicator for resolved
            if (state == "resolved" && outcome.id == prediction.winningOutcomeId) {
                radioButton?.text = "🏆 ${outcome.title}"
                itemView.alpha = 1.0f
            }
            
            // Top Users display
            val topUsersContainer = itemView.findViewById<LinearLayout>(R.id.topUsersContainer)
            val topUsersIcon = itemView.findViewById<ImageView>(R.id.topUsersIcon)
            val topUsersText = itemView.findViewById<TextView>(R.id.topUsersText)
            
            if ((state == "resolved" || state == "locked") && !outcome.topUsers.isNullOrEmpty()) {
                topUsersContainer.visibility = View.VISIBLE
                
                val isWinner = outcome.id == prediction.winningOutcomeId
                
                if (state == "resolved") {
                    if (isWinner) {
                        topUsersIcon.setImageResource(R.drawable.ic_check)
                        topUsersIcon.imageTintList = android.content.res.ColorStateList.valueOf(android.graphics.Color.parseColor("#53FC18"))
                        topUsersText.setTextColor(android.graphics.Color.parseColor("#53FC18"))
                    } else {
                        topUsersIcon.setImageResource(R.drawable.ic_lose)
                        topUsersIcon.imageTintList = android.content.res.ColorStateList.valueOf(android.graphics.Color.parseColor("#FF4D4D"))
                        topUsersText.setTextColor(android.graphics.Color.parseColor("#FF4D4D"))
                    }
                } else {
                    // Locked state - neutral display
                    topUsersIcon.setImageResource(R.drawable.ic_loyalty)
                    topUsersIcon.imageTintList = android.content.res.ColorStateList.valueOf(android.graphics.Color.WHITE)
                    topUsersText.setTextColor(android.graphics.Color.WHITE)
                }
                
                val usersStr = outcome.topUsers.joinToString(", ") { user ->
                    val formattedAmount = activity.formatViewerCount(user.amount ?: 0)
                    "${user.username} $formattedAmount"
                }
                topUsersText.text = usersStr
            } else {
                topUsersContainer.visibility = View.GONE
            }
            
            container.addView(itemView)
        }
        
        // Status text based on state - include timer for active
        val timerText = sheet.findViewById<TextView>(R.id.timerText)
        when (state) {
            "active" -> {
                // Calculate remaining time
                val endTime = activity.chatStateManager.currentPredictionEndTime
                if (endTime > 0) {
                    val remSecs = ((endTime - System.currentTimeMillis()) / 1000).toInt().coerceAtLeast(0)
                    val mins = remSecs / 60
                    val secs = remSecs % 60
                    timerText?.text = activity.getString(R.string.prediction_status_open_format, String.format(Locale.US, "%02d:%02d", mins, secs))
                } else {
                    timerText?.text = activity.getString(R.string.prediction_status_open)
                }
            }
            "locked" -> timerText?.text = activity.getString(R.string.prediction_status_locked)
            "resolved" -> timerText?.text = activity.getString(R.string.prediction_status_resolved)
            "cancelled" -> timerText?.text = activity.getString(R.string.prediction_status_refunded)
            else -> timerText?.text = ""
        }
        
        // Moderator/Owner action buttons
        val modActionsContainer = sheet.findViewById<LinearLayout>(R.id.modActionsContainer)
        val btnDelete = sheet.findViewById<android.widget.Button>(R.id.btnDeletePrediction)
        val btnLock = sheet.findViewById<android.widget.Button>(R.id.btnLockPrediction)
        val btnResolve = sheet.findViewById<android.widget.Button>(R.id.btnResolvePrediction)
        val btnNewPrediction = sheet.findViewById<android.widget.Button>(R.id.btnNewPredictionSecondary)
        
        if (activity.isModeratorOrOwner) {
            when (state) {
                "active" -> {
                    modActionsContainer?.visibility = View.VISIBLE
                    btnDelete?.visibility = View.VISIBLE
                    btnLock?.visibility = View.VISIBLE
                    btnResolve?.visibility = View.GONE
                    btnNewPrediction?.visibility = View.GONE
                    
                    btnDelete?.setOnClickListener { 
                        showDeleteConfirmationDialog() 
                    }
                    btnLock?.setOnClickListener { 
                        lockCurrentPrediction() 
                    }
                }
                "locked" -> {
                    modActionsContainer?.visibility = View.VISIBLE
                    btnDelete?.visibility = View.VISIBLE
                    btnLock?.visibility = View.GONE
                    btnResolve?.visibility = View.VISIBLE
                    btnNewPrediction?.visibility = View.GONE
                    
                    btnDelete?.setOnClickListener { 
                        showDeleteConfirmationDialog() 
                    }
                    btnResolve?.setOnClickListener { 
                        showPredictionResolveDialog() 
                    }
                }
                "cancelled", "resolved" -> {
                    modActionsContainer?.visibility = View.GONE
                    btnNewPrediction?.visibility = View.VISIBLE
                    btnNewPrediction?.setOnClickListener { 
                        predictionBottomSheet?.dismiss()
                        openPredictionSheet() 
                    }
                }
                else -> {
                    modActionsContainer?.visibility = View.GONE
                    btnNewPrediction?.visibility = View.VISIBLE
                    btnNewPrediction?.setOnClickListener { 
                        predictionBottomSheet?.dismiss()
                        openPredictionSheet() 
                    }
                }
            }
        } else {
            modActionsContainer?.visibility = View.GONE
            btnNewPrediction?.visibility = View.GONE
        }
    }

    private var betDialog: BottomSheetDialog? = null
    private var currentBetOutcomeId: String? = null
    
    private fun showCustomBetDialog(outcome: PredictionOutcome) {
        val prediction = activity.chatStateManager.currentPrediction ?: return
        
        betDialog = BottomSheetDialog(activity, R.style.BottomSheetDialogTheme)
        val view = activity.layoutInflater.inflate(R.layout.dialog_prediction_bet, null)
        betDialog?.setContentView(view)
        (view.parent as? View)?.background = null
        currentBetOutcomeId = outcome.id
        
        // Make dialog resize when keyboard opens so button stays visible
        // Deprecated SOFT_INPUT_ADJUST_RESIZE replaced by system handling or WindowInsets
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            @Suppress("DEPRECATION")
            betDialog?.window?.setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        }
        
        val dialogTitle = view.findViewById<TextView>(R.id.dialogTitle)
        val balanceText = view.findViewById<TextView>(R.id.currentBalanceText)
        val betInput = view.findViewById<EditText>(R.id.betAmountInput)
        val quickContainer = view.findViewById<LinearLayout>(R.id.quickAmountContainer)
        val maxButton = view.findViewById<TextView>(R.id.maxBalanceButton)
        val confirmButton = view.findViewById<View>(R.id.confirmBetButton)
        val totalSpentContainer = view.findViewById<LinearLayout>(R.id.totalSpentContainer)
        val totalSpentText = view.findViewById<TextView>(R.id.totalSpentText)
        
        dialogTitle.text = activity.getString(R.string.bet_on_format, outcome.title)
        balanceText.text = activity.getString(R.string.prediction_balance_label, NumberFormat.getIntegerInstance().format(activity.currentLoyaltyPoints))
        
        // Show total spent if user already voted on this option
        val userVote = prediction.userVote
        val totalAmount = userVote?.totalVoteAmount ?: 0L
        if (userVote?.outcomeId == outcome.id && totalAmount > 0) {
            totalSpentContainer?.visibility = View.VISIBLE
            totalSpentText?.text = activity.getString(R.string.bet_amount_total_format, NumberFormat.getIntegerInstance().format(totalAmount))
        } else {
            totalSpentContainer?.visibility = View.GONE
        }
        
        // Quick amount buttons
        val quickAmounts = listOf(100, 1000, 5000, 10000, 25000, 50000)
        quickContainer.removeAllViews()
        quickAmounts.forEach { amount ->
            val btn = android.widget.Button(activity).apply {
                text = if (amount >= 1000) "${amount / 1000}K" else amount.toString()
                setTextColor(android.graphics.Color.WHITE)
                textSize = 12f
                setBackgroundResource(R.drawable.bg_quick_amount_button)
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    (36 * activity.resources.displayMetrics.density).toInt()
                ).apply {
                    marginEnd = (8 * activity.resources.displayMetrics.density).toInt()
                }
                setPadding(
                    (16 * activity.resources.displayMetrics.density).toInt(),
                    0,
                    (16 * activity.resources.displayMetrics.density).toInt(),
                    0
                )
                setOnClickListener {
                    betInput.setText(amount.toString())
                    betInput.setSelection(betInput.text.length)
                    // Hide keyboard
                    val imm = activity.getSystemService(android.content.Context.INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager
                    imm.hideSoftInputFromWindow(betInput.windowToken, 0)
                }
            }
            quickContainer.addView(btn)
        }
        
        // Max button
        maxButton.setOnClickListener {
            betInput.setText(activity.currentLoyaltyPoints.toString())
            betInput.setSelection(betInput.text.length)
            val imm = activity.getSystemService(android.content.Context.INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager
            imm.hideSoftInputFromWindow(betInput.windowToken, 0)
        }
        
        // Confirm button - hide keyboard when clicked
        confirmButton.setOnClickListener {
            // Hide keyboard first
            val imm = activity.getSystemService(android.content.Context.INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager
            imm.hideSoftInputFromWindow(betInput.windowToken, 0)
            
            val amount = betInput.text.toString().toIntOrNull()
            if (amount != null && amount > 0 && amount <= activity.currentLoyaltyPoints) {
                voteInPrediction(outcome.id, amount, view)
            } else {
                Toast.makeText(activity, activity.getString(R.string.invalid_amount), Toast.LENGTH_SHORT).show()
            }
        }
        
        betDialog?.show()
    }

    private fun voteInPrediction(outcomeId: String?, amount: Int, dialogView: View? = null) {
        if (outcomeId == null) return
        val slug = activity.currentChannel?.slug ?: return
        val token = prefs.authToken ?: return
        lifecycleScope.launch {
            repository.voteInPrediction(slug, token, outcomeId, amount).onSuccess { response ->
                activity.runOnUiThread {
                    Toast.makeText(activity, activity.getString(R.string.prediction_saved), Toast.LENGTH_SHORT).show()
                    activity.fetchLoyaltyPoints()
                    
                    // Process API response to get updated userVote
                    val voteData = response.data
                    val updatedPrediction = if (voteData?.prediction != null) {
                         // Merge userVote from response
                         val newPred = voteData.prediction
                         val userVote = voteData.userVote 
                         if (userVote != null) newPred.copy(userVote = userVote) else newPred
                    } else {
                        // Fallback: manually update current prediction
                        val current = activity.chatStateManager.currentPrediction
                        if (current != null) {
                            val newVote = dev.xacnio.kciktv.shared.data.model.PredictionUserVote(
                                outcomeId = outcomeId,
                                totalVoteAmount = (current.userVote?.totalVoteAmount ?: 0) + amount
                            )
                            current.copy(userVote = newVote)
                        } else null
                    }
                    
                    if (updatedPrediction != null) {
                        activity.chatStateManager.currentPrediction = updatedPrediction
                    }

                    // Update the dialog to show new total spent
                    if (dialogView != null) {
                        val totalSpentContainer = dialogView.findViewById<LinearLayout>(R.id.totalSpentContainer)
                        val totalSpentText = dialogView.findViewById<TextView>(R.id.totalSpentText)
                        val balanceText = dialogView.findViewById<TextView>(R.id.currentBalanceText)
                        val betInput = dialogView.findViewById<EditText>(R.id.betAmountInput)
                        
                        val prediction = activity.chatStateManager.currentPrediction
                        val currentSpent = prediction?.userVote?.totalVoteAmount ?: 0
                        
                        totalSpentContainer?.visibility = View.VISIBLE
                        totalSpentText?.text = activity.getString(R.string.bet_amount_total_format, NumberFormat.getIntegerInstance().format(currentSpent))
                        
                        // Update balance
                        balanceText?.text = activity.getString(R.string.prediction_balance_label, NumberFormat.getIntegerInstance().format(activity.currentLoyaltyPoints))
                        
                        // Clear input
                        betInput?.setText("")
                    }
                    
                    // Update prediction bottom sheet and overlay
                    val prediction = activity.chatStateManager.currentPrediction
                    if (prediction != null) {
                        updatePredictionUI(prediction) // Update overlay stats
                        updatePredictionBottomSheetUI(prediction) // Update sheet stats
                    }
                }
            }.onFailure {
                activity.runOnUiThread {
                    Toast.makeText(activity, activity.getString(R.string.vote_failed), Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private fun handlePredictionCompletion(prediction: PredictionData) {
        if (activity.chatStateManager.isPredictionCompleting) return
        activity.chatStateManager.isPredictionCompleting = true
        stopPredictionTimer()
        
        activity.runOnUiThread {
            binding.predictionTimerText.visibility = View.VISIBLE
            binding.predictionDurationProgress.visibility = View.VISIBLE
            binding.predictionTimerText.text = if (prediction.state?.lowercase() == "cancelled") activity.getString(R.string.prediction_status_refunded) else activity.getString(R.string.prediction_status_resolved)
            
            val displayDuration = 15 
            val maxProgress = displayDuration * 1000
            binding.predictionDurationProgress.max = maxProgress
            binding.predictionDurationProgress.progress = maxProgress
            
            val endTime = System.currentTimeMillis() + maxProgress
            
            val runnable = object : Runnable {
                override fun run() {
                    if (!activity.chatStateManager.isPredictionCompleting) return
                    
                    val remaining = endTime - System.currentTimeMillis()
                    
                    if (remaining <= 0) {
                        try {
                            activity.chatStateManager.currentPrediction = null
                            activity.chatStateManager.isPredictionCompleting = false
                            
                            hideOverlayView(binding.predictionContainer)
                            
                            binding.predictionContainer.tag = null
                        } catch (e: Exception) {}
                        return
                    }
                    
                    binding.predictionDurationProgress.progress = remaining.toInt()
                    binding.predictionContainer.postDelayed(this, 50)
                }
            }
            
            binding.predictionContainer.tag = runnable
            binding.predictionContainer.post(runnable)
        }
    }

    private fun lockCurrentPrediction() {
        val slug = activity.currentChannel?.slug ?: return
        val id = activity.chatStateManager.currentPrediction?.id ?: return
        val token = prefs.authToken ?: return
        
        lifecycleScope.launch { 
            repository.lockPrediction(slug, id, token).onFailure { e ->
                activity.runOnUiThread {
                    if (e.message?.contains("403") == true) {
                        Toast.makeText(activity, activity.getString(R.string.error_prediction_conflict), Toast.LENGTH_LONG).show()
                    } else {
                        Toast.makeText(activity, activity.getString(R.string.lock_failed), Toast.LENGTH_SHORT).show()
                    }
                }
            }.onSuccess {
                activity.runOnUiThread { Toast.makeText(activity, activity.getString(R.string.prediction_locked), Toast.LENGTH_SHORT).show() }
            }
        }
    }

    private fun cancelCurrentPrediction() {
        val slug = activity.currentChannel?.slug ?: return
        val id = activity.chatStateManager.currentPrediction?.id ?: return
        val token = prefs.authToken ?: return
        
        lifecycleScope.launch { 
            repository.cancelPrediction(slug, id, token).onFailure { e ->
                activity.runOnUiThread {
                    if (e.message?.contains("403") == true) {
                        Toast.makeText(activity, activity.getString(R.string.error_prediction_conflict), Toast.LENGTH_LONG).show()
                    } else {
                        Toast.makeText(activity, activity.getString(R.string.cancel_failed), Toast.LENGTH_SHORT).show()
                    }
                }
            }.onSuccess {
                activity.runOnUiThread { Toast.makeText(activity, activity.getString(R.string.prediction_cancelled), Toast.LENGTH_SHORT).show() }
            }
        }
    }

    private fun showPredictionResolveDialog() {
        val prediction = activity.chatStateManager.currentPrediction ?: return
        val outcomes = prediction.outcomes ?: return
        
        val dialog = BottomSheetDialog(activity, R.style.BottomSheetDialogTheme)
        resolveDialog = dialog
        val view = activity.layoutInflater.inflate(R.layout.bottom_sheet_resolve_prediction, null)
        dialog.setContentView(view)
        (view.parent as? View)?.background = null
        
        val radioGroup = view.findViewById<android.widget.RadioGroup>(R.id.resolveRadioGroup)
        val btnConfirm = view.findViewById<android.widget.Button>(R.id.btnConfirmResolve)
        
        val density = activity.resources.displayMetrics.density
        
        outcomes.forEach { outcome ->
            val rb = android.widget.RadioButton(activity)
            rb.text = outcome.title
            rb.setTextColor(Color.WHITE)
            rb.textSize = 16f
            rb.tag = outcome.id
            val padding = (12 * density).toInt()
            rb.setPadding(padding, padding, padding, padding)
            rb.buttonTintList = android.content.res.ColorStateList.valueOf(Color.parseColor("#53FC18"))
            
            val params = android.widget.RadioGroup.LayoutParams(
                android.widget.RadioGroup.LayoutParams.MATCH_PARENT,
                android.widget.RadioGroup.LayoutParams.WRAP_CONTENT
            )
            params.bottomMargin = (8 * density).toInt()
            rb.layoutParams = params
            
            radioGroup.addView(rb)
        }
        
        radioGroup.setOnCheckedChangeListener { _, _ ->
             btnConfirm.isEnabled = true
             btnConfirm.alpha = 1.0f
        }
        
        btnConfirm.setOnClickListener {
             val checkedId = radioGroup.checkedRadioButtonId
             if (checkedId != -1) {
                 val rb = radioGroup.findViewById<View>(checkedId)
                 val outcomeId = rb?.tag as? String
                 if (outcomeId != null) {
                    btnConfirm.isEnabled = false
                    btnConfirm.alpha = 0.5f
                     resolvePrediction(outcomeId)
                     radioGroup.clearCheck()
                     dialog.dismiss()
                 }
             }
        }
        
        dialog.show()
    }
    
    private fun resolvePrediction(winningOutcomeId: String) {
        val slug = activity.currentChannel?.slug ?: return
        val token = prefs.authToken ?: return
        val predictionId = activity.chatStateManager.currentPrediction?.id ?: return
        
        lifecycleScope.launch {
            repository.resolvePrediction(slug, predictionId, token, winningOutcomeId).onFailure { e ->
                activity.runOnUiThread {
                    if (e.message?.contains("403") == true) {
                        Toast.makeText(activity, activity.getString(R.string.error_prediction_conflict), Toast.LENGTH_LONG).show()
                    } else {
                        Toast.makeText(activity, activity.getString(R.string.resolve_failed), Toast.LENGTH_SHORT).show()
                    }
                }
            }.onSuccess {
                activity.runOnUiThread { Toast.makeText(activity, activity.getString(R.string.prediction_resolved), Toast.LENGTH_SHORT).show() }
            }
        }
    }

    fun openPredictionSheet() {
        val slug = activity.currentChannel?.slug ?: return
        
        if (!prefs.isLoggedIn) {
             Toast.makeText(activity, activity.getString(R.string.prediction_login_required), Toast.LENGTH_SHORT).show()
             return
        }
        
        // Show create dialog
        val dialog = BottomSheetDialog(activity, R.style.BottomSheetDialogTheme)
        createPredictionDialog = dialog
        val view = activity.layoutInflater.inflate(R.layout.dialog_create_prediction, null)
        dialog.setContentView(view)
        
        // Close button
        view.findViewById<View>(R.id.btnClose)?.setOnClickListener { dialog.dismiss() }
        
        val editTitle = view.findViewById<EditText>(R.id.predictionTitleInput)
        val editOutcome1 = view.findViewById<EditText>(R.id.outcome1Input)
        val editOutcome2 = view.findViewById<EditText>(R.id.outcome2Input)
        val durationSpinner = view.findViewById<Spinner>(R.id.durationSpinner)
        val recentSpinner = view.findViewById<Spinner>(R.id.recentPredictionsSpinner)
        
        // Spinner Setup
        val durationValues = listOf(60, 300, 600, 1800)
        val durationLabels = durationValues.map { activity.getString(R.string.duration_minutes, it / 60) }
        val adapter = ArrayAdapter(activity, android.R.layout.simple_spinner_item, durationLabels)
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        durationSpinner.adapter = adapter
        durationSpinner.setSelection(1) // Default 5 Minutes
        
        // Load recent predictions
        val token = prefs.authToken
        if (token != null) {
            lifecycleScope.launch {
                repository.getRecentPredictions(slug, token).onSuccess { predictions ->
                     if (!predictions.isNullOrEmpty()) {
                         activity.runOnUiThread {
                             val recentList = mutableListOf(activity.getString(R.string.make_selection))
                             // Filter unique titles to avoid clutter users might have same prediction many times
                             val uniquePreds = predictions.distinctBy { it.title + it.outcomes?.map { o -> o.title }?.joinToString() }
                             
                             recentList.addAll(uniquePreds.map { it.title ?: activity.getString(R.string.prediction_label) })
                             
                             val recentAdapter = ArrayAdapter(activity, android.R.layout.simple_spinner_item, recentList)
                             recentAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
                             recentSpinner.adapter = recentAdapter
                             
                             recentSpinner.onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {
                                 override fun onItemSelected(parent: android.widget.AdapterView<*>?, view: View?, position: Int, id: Long) {
                                     if (position > 0) {
                                         val selectedPred = uniquePreds[position - 1]
                                         editTitle.setText(selectedPred.title)
                                         val outlines = selectedPred.outcomes
                                         if (!outlines.isNullOrEmpty()) {
                                             if (outlines.size > 0) editOutcome1.setText(outlines[0].title)
                                             if (outlines.size > 1) editOutcome2.setText(outlines[1].title)
                                         }
                                         
                                         // Set duration if valid
                                         val duration = selectedPred.duration ?: 300
                                         val durationIndex = durationValues.indexOf(duration)
                                         if (durationIndex != -1) {
                                             durationSpinner.setSelection(durationIndex)
                                         }
                                     }
                                 }
                                 override fun onNothingSelected(parent: android.widget.AdapterView<*>?) {}
                             }
                         }
                     }
                }
            }
        }
        
        view.findViewById<View>(R.id.btnCreatePrediction).setOnClickListener {
             val title = editTitle.text.toString()

             val out1 = editOutcome1.text.toString()
             val out2 = editOutcome2.text.toString()
             
             if (title.isBlank() || out1.isBlank() || out2.isBlank()) {
                 Toast.makeText(activity, activity.getString(R.string.fill_all_fields), Toast.LENGTH_SHORT).show()
                 return@setOnClickListener
             }
             
             val duration = durationValues.getOrElse(durationSpinner.selectedItemPosition) { 60 }
             
             createPrediction(title, listOf(out1, out2), duration)
             dialog.dismiss()
        }
        
        dialog.show()
    }
    
    private fun createPrediction(title: String, outcomes: List<String>, duration: Int) {
        val slug = activity.currentChannel?.slug ?: return
        val token = prefs.authToken ?: return
        lifecycleScope.launch {
            repository.createPrediction(slug, token, title, outcomes, duration).onSuccess {
                activity.runOnUiThread {
                    Toast.makeText(activity, activity.getString(R.string.prediction_created), Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    // --- Gift Logic ---

    fun updatePinnedGiftsTimers() {
        if (pinnedGifts.isEmpty()) return
        val now = System.currentTimeMillis()
        var expiredCount = 0
        val iterator = pinnedGifts.iterator()
        while (iterator.hasNext()) {
            val gift = iterator.next()
            if (now >= activity.parseIsoDate(gift.expiresAt)) {
                iterator.remove()
                expiredCount++
            }
        }
        if (expiredCount > 0) {
            updatePinnedGiftsUI()
            updateChatOverlayState()
        } else {
            for (i in 0 until binding.pinnedGiftsLayout.childCount) {
                val view = binding.pinnedGiftsLayout.getChildAt(i) ?: continue
                val tag = view.tag as? String ?: continue
                val gift = pinnedGifts.find { it.giftTransactionId == tag } ?: continue
                val progressBar = view.findViewById<ProgressBar>(R.id.giftTimerProgress)
                val createdAt = activity.parseIsoDate(gift.createdAt)
                val expiresAt = activity.parseIsoDate(gift.expiresAt)
                val totalDuration = (gift.pinnedTime ?: gift.gift?.pinnedTime)?.let { it * 1000L } ?: (expiresAt - createdAt)
                val remaining = expiresAt - now
                if (totalDuration > 0) progressBar.progress = (remaining.toFloat() / totalDuration.toFloat() * 100).toInt().coerceIn(0, 100)
            }
        }
    }

    fun updatePinnedGiftsUI() {
        // Don't show pinned gifts in mini player or PiP mode
        if (activity.miniPlayerManager.isMiniPlayerMode || activity.isInPictureInPictureMode) {
            binding.pinnedGiftsLayout.removeAllViews()
            binding.pinnedGiftsBlur.visibility = View.GONE
            return
        }
        
        if (!prefs.showPinnedGifts || pinnedGifts.isEmpty()) {
            binding.pinnedGiftsLayout.removeAllViews()
            binding.pinnedGiftsBlur.visibility = View.GONE
            updateChatOverlayMargin()
            return
        }
        
        binding.pinnedGiftsBlur.visibility = View.VISIBLE
        val currentIds = pinnedGifts.mapNotNull { it.giftTransactionId }
        
        for (i in binding.pinnedGiftsLayout.childCount - 1 downTo 0) {
            val view = binding.pinnedGiftsLayout.getChildAt(i)
            val tag = view.tag as? String
            if (tag != null && !currentIds.contains(tag)) {
                binding.pinnedGiftsLayout.removeViewAt(i)
            }
        }

        pinnedGifts.forEachIndexed { index, gift ->
            val id = gift.giftTransactionId ?: "gift_${index}"
            var existingViewIndex = -1
            
            for (i in 0 until binding.pinnedGiftsLayout.childCount) {
                if (binding.pinnedGiftsLayout.getChildAt(i).tag == id) {
                    existingViewIndex = i
                    break
                }
            }
            
            val giftView = if (existingViewIndex != -1) {
                val view = binding.pinnedGiftsLayout.getChildAt(existingViewIndex)
                if (existingViewIndex != index) {
                    binding.pinnedGiftsLayout.removeViewAt(existingViewIndex)
                    binding.pinnedGiftsLayout.addView(view, index)
                }
                view
            } else {
                val newView = activity.layoutInflater.inflate(R.layout.item_pinned_gift_header, binding.pinnedGiftsLayout, false)
                newView.tag = id
                binding.pinnedGiftsLayout.addView(newView, index)
                
                val cardContainer = newView.findViewById<FrameLayout>(R.id.cardContainer)
                val progressBar = newView.findViewById<ProgressBar>(R.id.giftTimerProgress)
                val profilePic = newView.findViewById<ImageView>(R.id.senderProfilePic)
                val giftAmount = newView.findViewById<TextView>(R.id.giftAmount)
                
                val colorStr = gift.sender?.usernameColor
                val color = try {
                    if (!colorStr.isNullOrEmpty()) Color.parseColor(colorStr) else Color.parseColor("#53fc18")
                } catch (e: Exception) {
                    Color.parseColor("#53fc18")
                }
                
                val density = activity.resources.displayMetrics.density
                val strokeWidthPx = (1.5f * density).toInt()
                val borderDrawable = GradientDrawable()
                borderDrawable.shape = GradientDrawable.RECTANGLE
                borderDrawable.cornerRadius = 16f * density
                borderDrawable.setStroke(strokeWidthPx, color)
                borderDrawable.setColor(Color.TRANSPARENT)
                cardContainer.background = borderDrawable
                cardContainer.setPadding(strokeWidthPx, strokeWidthPx, strokeWidthPx, strokeWidthPx)

                val progressCornerRadius = 16f * density
                val bgShape = GradientDrawable()
                bgShape.shape = GradientDrawable.RECTANGLE
                bgShape.cornerRadius = progressCornerRadius
                bgShape.setColor(Color.parseColor("#1A1A1A"))
                
                val progressShape = GradientDrawable()
                progressShape.shape = GradientDrawable.RECTANGLE
                progressShape.cornerRadius = progressCornerRadius
                val progressColor = Color.argb((0.7f * 255).toInt(), Color.red(color), Color.green(color), Color.blue(color))
                progressShape.setColor(progressColor)
                
                val progressClip = ClipDrawable(progressShape, Gravity.START, ClipDrawable.HORIZONTAL)
                val layers = LayerDrawable(arrayOf(bgShape, progressClip))
                layers.setId(0, android.R.id.background)
                layers.setId(1, android.R.id.progress)
                progressBar.progressDrawable = layers
                
                val amount = gift.gift?.amount ?: 0
                giftAmount.text = NumberFormat.getIntegerInstance().format(amount).replace(",", ".")
                
                Glide.with(activity)
                    .load(gift.sender?.profilePicture)
                    .circleCrop()
                    .placeholder(R.drawable.ic_user)
                    .into(profilePic)

                newView.setOnClickListener { showPinnedGiftDetails(gift) }
                newView
            }
            
            val progressBar = giftView.findViewById<ProgressBar>(R.id.giftTimerProgress)
            val now = System.currentTimeMillis()
            val createdAt = activity.parseIsoDate(gift.createdAt)
            val expiresAt = activity.parseIsoDate(gift.expiresAt)
            val durationFromModel = (gift.pinnedTime ?: gift.gift?.pinnedTime)?.let { it * 1000L }
            val totalDuration = durationFromModel ?: (expiresAt - createdAt)
            val remaining = expiresAt - now
            if (totalDuration > 0) {
                val progress = (remaining.toFloat() / totalDuration.toFloat() * 100).toInt()
                progressBar.progress = progress.coerceIn(0, 100)
            }
        }
        updateChatOverlayMargin()
    }

    private fun showPinnedGiftDetails(gift: PinnedGift) {
        val dialog = AlertDialog.Builder(activity, R.style.Theme_KcikTV_Dialog).create()
        val view = activity.layoutInflater.inflate(R.layout.dialog_pinned_gift_details, null)
        
        val giftImage = view.findViewById<ImageView>(R.id.giftImageDetail)
        val senderAndGift = view.findViewById<TextView>(R.id.senderAndGiftText)
        val messageText = view.findViewById<TextView>(R.id.giftMessageDetail)
        val amountText = view.findViewById<TextView>(R.id.giftAmountDetail)
        val timeText = view.findViewById<TextView>(R.id.remainingTime)
        
        val senderName = gift.sender?.username ?: activity.getString(R.string.unknown)
        val giftName = gift.gift?.name ?: activity.getString(R.string.gift)
        val colorInt = try { gift.sender?.usernameColor?.let { Color.parseColor(it) } ?: Color.parseColor("#53fc18") } catch(e: Exception) { Color.parseColor("#53fc18") }
        
        val headerBg = view.findViewById<FrameLayout>(R.id.giftHeaderBackground)
        val darkerColor = Color.argb(
            255,
            (Color.red(colorInt) * 0.3f).toInt(),
            (Color.green(colorInt) * 0.3f).toInt(),
            (Color.blue(colorInt) * 0.3f).toInt()
        )
        headerBg.background = GradientDrawable(
            GradientDrawable.Orientation.BOTTOM_TOP,
            intArrayOf(Color.parseColor("#1A1A1A"), darkerColor, colorInt)
        )
        
        val spannable = SpannableStringBuilder()
        spannable.append(senderName)
        spannable.setSpan(ForegroundColorSpan(colorInt), 0, senderName.length, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
        spannable.append(" ")
        val actionText = activity.getString(R.string.gift_sent_label, giftName)
        val giftIndex = actionText.indexOf(giftName)
        spannable.append(actionText)
        
        if (giftIndex >= 0) {
            val start = senderName.length + 1 + giftIndex
            spannable.setSpan(StyleSpan(Typeface.BOLD), start, start + giftName.length, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
        
        senderAndGift.text = spannable
        messageText.text = gift.message
        amountText.text = NumberFormat.getIntegerInstance().format(gift.gift?.amount ?: 0).replace(",", ".")
        
        val giftId = gift.gift?.giftId
        val formattedGiftId = giftId?.replace("_", "-")
        val giftImageUrl = if (!formattedGiftId.isNullOrEmpty()) "https://files.kick.com/kicks/gifts/${formattedGiftId}.webp" else null
        
        Glide.with(activity).load(giftImageUrl).placeholder(R.drawable.ic_app_logo).into(giftImage)
        
        var timerRunnableDelegate: Runnable? = null
        timerRunnableDelegate = Runnable {
            val remainingTime = activity.parseIsoDate(gift.expiresAt) - System.currentTimeMillis()
            if (remainingTime <= 0) {
                timeText.text = "00:00"
                if (dialog.isShowing) dialog.dismiss()
            } else {
                val totalSecs = remainingTime / 1000
                timeText.text = String.format("%02d:%02d", totalSecs / 60, totalSecs % 60)
                timeText.postDelayed(timerRunnableDelegate!!, 1000)
            }
        }
        timeText.post(timerRunnableDelegate)
        dialog.setOnDismissListener { timeText.removeCallbacks(timerRunnableDelegate) }
        
        dialog.setView(view)
        dialog.show()
        dialog.window?.setLayout((280 * activity.resources.displayMetrics.density).toInt(), ViewGroup.LayoutParams.WRAP_CONTENT)
    }

    fun restartPinnedGiftsTimer() {
        pinnedGiftsTimerHandler.removeCallbacks(pinnedGiftsTimerRunnable)
        pinnedGiftsTimerHandler.post(pinnedGiftsTimerRunnable)
    }

    fun connectViewerWebSocket(token: String, channelId: String, livestreamId: String?) {
        viewerWebSocket?.disconnect()
        
        // Show connecting state immediately
        activity.binding.viewerConnectionContainer.visibility = View.VISIBLE
        activity.binding.viewerConnectionProgress.visibility = View.VISIBLE
        
        Log.d(TAG, "Connecting to viewer websocket for channel: $channelId")
        viewerWebSocket = dev.xacnio.kciktv.shared.data.chat.KickViewerWebSocket(
            client = okhttp3.OkHttpClient() 
        ).apply {
            // Connection state callback for system messages
            onConnectionStateChanged = { connected, isFirstConnect ->
                activity.runOnUiThread {
                    if (connected) {
                        activity.binding.viewerConnectionContainer.visibility = View.GONE
                        activity.binding.viewerConnectionProgress.visibility = View.GONE
                    } else {
                        // User requested "automatic spinner", so show progress immediately along with container
                        activity.binding.viewerConnectionContainer.visibility = View.VISIBLE
                        activity.binding.viewerConnectionProgress.visibility = View.VISIBLE
                        // WebSocket auto-reconnects, so just show spinning state
                        activity.binding.viewerConnectionContainer.setOnClickListener {
                            // Optional: Could force immediate reconnect or show detailed error
                        }
                    }
                }
            }
            
            // Token refresh callback - get fresh token before each reconnect
            onTokenRequest = { chId, _, callback ->
                activity.fetchViewerToken(chId) { newToken ->
                    callback(newToken)
                }
            }
            
            setListener(object : dev.xacnio.kciktv.shared.data.chat.KickViewerWebSocket.ViewerWebSocketListener {
                override fun onEvent(event: String, data: String) {
                    when (event) {
                        "App\\Events\\PollUpdateEvent" -> {
                             val poll = com.google.gson.Gson().fromJson(data, PollData::class.java)
                             activity.runOnUiThread { updatePollUI(poll) }
                        }
                        "App\\Events\\PollDeleteEvent" -> {
                             activity.runOnUiThread {
                                 activity.chatStateManager.currentPoll = null
                                 binding.pollContainer.visibility = View.GONE
                                 updateChatOverlayState()
                             }
                        }
                        "App\\Events\\PredictionCreated", "App\\Events\\PredictionUpdated" -> {
                             val pred = com.google.gson.Gson().fromJson(data, PredictionData::class.java)
                             activity.runOnUiThread { updatePredictionUI(pred) }
                        }
                        "App\\Events\\PinnedGiftCreated" -> {
                             fetchPinnedGifts(channelId.toLongOrNull() ?: 0L)
                        }
                        "App\\Events\\PinnedMessageDeleted" -> {
                             activity.runOnUiThread {
                                 activity.chatStateManager.isPinnedMessageActive = false
                                 hideOverlayView(binding.pinnedMessageContainer)
                                 
                                 // Retry hiding in case another handler interferes
                                 binding.pinnedMessageContainer.postDelayed({
                                     if (!activity.chatStateManager.isPinnedMessageActive) {
                                         binding.pinnedMessageContainer.visibility = View.GONE
                                         updateChatOverlayState()
                                     }
                                 }, 400) // Delay > animation duration
                             }
                        }
                    }
                }
            })
            connect(token, channelId, livestreamId)
        }
    }

    fun disconnectViewerWebSocket() {
        viewerWebSocket?.disconnect()
        viewerWebSocket = null
    }

    private fun showDeleteConfirmationDialog() {
        val dialog = android.app.Dialog(activity, R.style.BottomSheetDialogTheme)
        deleteConfirmDialog = dialog
        val view = activity.layoutInflater.inflate(R.layout.dialog_delete_prediction_confirm, null)
        dialog.setContentView(view)
        
        // Update refund text with total points
        val prediction = activity.chatStateManager.currentPrediction
        var totalPoints = 0L
        prediction?.outcomes?.forEach { 
             totalPoints += it.totalVoteAmount ?: 0 
        }
        val formattedPoints = NumberFormat.getIntegerInstance().format(totalPoints).replace(",", ".")
        val refundText = view.findViewById<TextView>(R.id.refundInfoText)
        refundText.text = activity.getString(R.string.prediction_refund_info_format, formattedPoints)
        
        view.findViewById<View>(R.id.btnCancelDelete).setOnClickListener { dialog.dismiss() }
        view.findViewById<View>(R.id.btnConfirmDelete).setOnClickListener {
            dialog.dismiss()
            cancelCurrentPrediction()
        }
        
        val window = dialog.window
        window?.setBackgroundDrawable(android.graphics.drawable.ColorDrawable(android.graphics.Color.TRANSPARENT))
        
        dialog.show()
        
        // Resize dialog to be not full screen
        val width = (activity.resources.displayMetrics.widthPixels * 0.85).toInt()
        window?.setLayout(width, android.view.ViewGroup.LayoutParams.WRAP_CONTENT)
    }

    @android.annotation.SuppressLint("ClickableViewAccessibility")
    private fun setupOverlayTouchListeners() {
        // Setup detector for Pinned Message
        val pinnedDetector = createGestureDetector(binding.pinnedMessageContainer)
        binding.pinnedMessageContainer.setOnTouchListener { _, event -> pinnedDetector.onTouchEvent(event); true }
        
        // Setup detector for Poll
        val pollDetector = createGestureDetector(binding.pollContainer)
        binding.pollContainer.setOnTouchListener { _, event -> pollDetector.onTouchEvent(event); true }
        
        // Setup detector for Prediction
        val predictionDetector = createGestureDetector(binding.predictionContainer)
        binding.predictionContainer.setOnTouchListener { _, event -> predictionDetector.onTouchEvent(event); true }
    }

    fun showOverlayView(view: View) {
        if (view.visibility == View.VISIBLE) return
        
        view.visibility = View.VISIBLE
        view.post {
            val width = view.width.toFloat().takeIf { it > 0 } ?: (activity.resources.displayMetrics.widthPixels.toFloat() / 2)
            view.translationX = width
            view.alpha = 0f
            
            view.animate()
                .translationX(0f)
                .alpha(1f)
                .setDuration(300)
                .setInterpolator(android.view.animation.DecelerateInterpolator())
                .start()
        }
    }

    fun hideOverlayView(view: View) {
        if (view.visibility != View.VISIBLE) {
             view.visibility = View.GONE
             updateChatOverlayState()
             return
        }
        
        view.animate()
            .translationX(view.width.toFloat())
            .alpha(0f)
            .setDuration(300)
            .setInterpolator(android.view.animation.AccelerateInterpolator())
            .withEndAction {
                view.visibility = View.GONE
                view.translationX = 0f
                view.alpha = 1f
                if (view.id == R.id.pinnedMessageContainer) {
                    binding.pinnedMsgText.text = ""
                }
                updateChatOverlayState()
            }
            .start()
    }
    
    private fun createGestureDetector(view: View): android.view.GestureDetector {
        return android.view.GestureDetector(activity, object : android.view.GestureDetector.SimpleOnGestureListener() {
             override fun onDown(e: android.view.MotionEvent): Boolean = true
             
             override fun onLongPress(e: android.view.MotionEvent) {
                if (view == binding.pinnedMessageContainer && activity.isModeratorOrOwner) {
                     showUnpinConfirmationDialog()
                }
             }
             
             override fun onFling(e1: android.view.MotionEvent?, e2: android.view.MotionEvent, velocityX: Float, velocityY: Float): Boolean {
                if (e1 == null) return false
                val deltaY = e2.y - e1.y
                if (kotlin.math.abs(deltaY) > 50 && kotlin.math.abs(velocityY) > 50) {
                    swapOverlayStack()
                    return true
                }
                return false
            }

            override fun onSingleTapConfirmed(e: android.view.MotionEvent): Boolean {
                view.performClick()
                return true
            }
        })
    }
    
    /**
     * Cleanup all resources - call this in Activity onDestroy
     */
    fun cleanup() {
        // Stop all timers
        stopPollTimer()
        stopPredictionTimer()
        pinnedGiftsTimerHandler.removeCallbacks(pinnedGiftsTimerRunnable)
        
        // Disconnect viewer websocket
        disconnectViewerWebSocket()
        
        // Dismiss any open dialogs
        try {
            betDialog?.dismiss()
            resolveDialog?.dismiss()
            deleteConfirmDialog?.dismiss()
            createPredictionDialog?.dismiss()
        } catch (e: Exception) {
            // Ignore
        }
    }

    // Pinned Message Logic
    private var currentPinnedMessageId = 0
    private val pinnedEmoteTargets = mutableListOf<com.bumptech.glide.request.target.Target<*>>()

    fun handlePinnedMessageFromHistory(pinnedMessage: PinnedMessage?) {
        if (pinnedMessage != null) {
            activity.chatStateManager.isPinnedMessageActive = true
            android.transition.TransitionManager.beginDelayedTransition(binding.chatContainer)
            
            if (!activity.chatStateManager.isPinnedMessageHiddenByManual) {
                updateChatOverlayState()
                processPinnedMessageEmotes(pinnedMessage.content)
                renderPinnedSender(pinnedMessage.sender)
                binding.pinnedByText.text = activity.getString(R.string.pinned_by_format, pinnedMessage.sender.username)
                val sdf = java.text.SimpleDateFormat("dd.MM.yyyy HH:mm", java.util.Locale.getDefault())
                val dateStr = sdf.format(java.util.Date(pinnedMessage.createdAt))
                binding.pinnedDateText.text = activity.getString(R.string.date_label_format, dateStr)
                updatePinnedMessageUIState()
            } else {
                binding.restorePinnedMessage.visibility = View.VISIBLE
            }
        } else {
            activity.chatStateManager.isPinnedMessageActive = false
            updateChatOverlayState()
        }
    }

    internal fun clearPinnedEmotes() {
        pinnedEmoteTargets.forEach { Glide.with(activity).clear(it) }
        pinnedEmoteTargets.clear()
        binding.pinnedMsgText.text = ""
    }

    internal fun processPinnedMessageEmotes(content: String) {
        val requestId = ++currentPinnedMessageId
        
        clearPinnedEmotes()

        val textView = binding.pinnedMsgText
        textView.textSize = prefs.chatTextSize
        val emoteSize = (textView.textSize * prefs.chatEmoteSize).toInt()
        val EMOTE_PATTERN = java.util.regex.Pattern.compile("\\[emote:(\\d+):([^\\]]+)\\]")
        
        val builder = android.text.SpannableStringBuilder()
        val matcher = EMOTE_PATTERN.matcher(content)
        val emotePlaceholders = mutableListOf<Pair<Int, String>>()
        var lastEnd = 0
        
        while (matcher.find()) {
            builder.append(content.substring(lastEnd, matcher.start()))
            val emoteId = matcher.group(1) ?: continue
            val pos = builder.length
            builder.append(" ") 
            emotePlaceholders.add(pos to emoteId)
            lastEnd = matcher.end()
        }
        builder.append(content.substring(lastEnd))
        
        textView.setText(builder, android.widget.TextView.BufferType.SPANNABLE)
        
        // Detect and apply non-underlined links
        // Use shared pattern from ChatAdapter to support trailing slashes and consistent behavior
        val urlMatcher = dev.xacnio.kciktv.shared.ui.adapter.ChatAdapter.URL_PATTERN.matcher(builder)
        while (urlMatcher.find()) {
            val fullUrl = urlMatcher.group()
            var start = urlMatcher.start()
            var end = urlMatcher.end()
            
            // Trim trailing punctuation (.,!?) but KEEP trailing slash
            while (end > start) {
                val c = builder[end - 1]
                if (c == '.' || c == ',' || c == '!' || c == '?' || c == ')' || c == ']' || c == ';') {
                    end--
                } else {
                    break
                }
            }
            
            val url = builder.substring(start, end)
            if (url.startsWith("https://")) {
                val clickableSpan = object : android.text.style.ClickableSpan() {
                    override fun onClick(widget: View) {
                        dev.xacnio.kciktv.mobile.util.DialogUtils.showLinkOptionsBottomSheet(widget.context, url)
                    }
                    override fun updateDrawState(ds: android.text.TextPaint) {
                        super.updateDrawState(ds)
                        ds.isUnderlineText = false
                        ds.color = 0xFF53FC18.toInt()
                    }
                }
                builder.setSpan(clickableSpan, start, end, android.text.Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
            }
        }
        
        textView.setText(builder, android.widget.TextView.BufferType.SPANNABLE)
        textView.movementMethod = NonScrollableLinkMovementMethod
        
        emotePlaceholders.forEach { (pos, id) ->
            EmoteManager.loadSynchronizedEmote(
                activity,
                id,
                emoteSize,
                textView
            ) { sharedDrawable ->
                if (requestId != currentPinnedMessageId) return@loadSynchronizedEmote
                
                val currentText = textView.text
                if (currentText is android.text.Spannable && pos < currentText.length) {
                    currentText.setSpan(
                        ChatAdapter.CenterImageSpan(sharedDrawable),
                        pos,
                        pos + 1,
                        android.text.Spannable.SPAN_EXCLUSIVE_EXCLUSIVE
                    )
                } else if (pos < builder.length) {
                    val spannable = android.text.SpannableStringBuilder(textView.text)
                    spannable.setSpan(
                        ChatAdapter.CenterImageSpan(sharedDrawable),
                        pos,
                        pos + 1,
                        android.text.Spannable.SPAN_EXCLUSIVE_EXCLUSIVE
                    )
                    textView.text = spannable
                }
            }
        }
    }

    internal fun renderPinnedSender(sender: ChatSender) {
        binding.pinnedSenderName.text = sender.username
        
        val isVerified = sender.badges?.any { it.type == "verified" } ?: false
        binding.verifiedBadge.visibility = if (isVerified) View.VISIBLE else View.GONE

        try {
            val color = sender.color?.let { android.graphics.Color.parseColor(it) } ?: prefs.themeColor
            binding.pinnedSenderName.setTextColor(color)
        } catch (_: Exception) {
            binding.pinnedSenderName.setTextColor(0xFFFFFFFF.toInt())
        }

        binding.pinnedBadgesContainer.removeAllViews()
        val badgeSize = (14 * activity.resources.displayMetrics.density).toInt()
        val margin = (4 * activity.resources.displayMetrics.density).toInt()

        sender.badges?.forEach { badge ->
            when (badge.type) {
                "broadcaster" -> addStaticBadge(R.drawable.ic_badge_broadcaster, 0, badgeSize, margin)
                "moderator" -> addStaticBadge(R.drawable.ic_badge_moderator, 0, badgeSize, margin)
                "verified" -> {}
                "subscriber" -> {
                    val months = badge.count ?: 0
                    val badgeUrl = activity.chatStateManager.subscriberBadges.keys.filter { it <= months }
                        .maxOrNull()?.let { activity.chatStateManager.subscriberBadges[it] }

                    if (badgeUrl != null) {
                        addRemoteBadge(badgeUrl, badgeSize, margin)
                    } else {
                        addStaticBadge(R.drawable.ic_badge_subscriber_default, prefs.themeColor, badgeSize, margin)
                    }
                }
            }
        }
    }

    private fun addStaticBadge(resId: Int, tint: Int, size: Int, margin: Int, container: LinearLayout = binding.pinnedBadgesContainer) {
        val iv = ImageView(activity)
        iv.setImageResource(resId)
        if (tint != 0) {
            iv.setColorFilter(tint)
        }
        val params = LinearLayout.LayoutParams(size, size)
        params.setMargins(0, 0, margin, 0)
        iv.layoutParams = params
        container.addView(iv)
    }

    private fun addRemoteBadge(url: String, size: Int, margin: Int, container: LinearLayout = binding.pinnedBadgesContainer) {
        val iv = ImageView(activity)
        val params = LinearLayout.LayoutParams(size, size)
        params.setMargins(0, 0, margin, 0)
        iv.layoutParams = params
        container.addView(iv)
        Glide.with(activity).load(url).into(iv)
    }
    private object NonScrollableLinkMovementMethod : android.text.method.LinkMovementMethod() {
        override fun onTouchEvent(widget: android.widget.TextView, buffer: android.text.Spannable, event: android.view.MotionEvent): Boolean {
            val action = event.action

            if (action == android.view.MotionEvent.ACTION_UP || action == android.view.MotionEvent.ACTION_DOWN) {
                var x = event.x.toInt()
                var y = event.y.toInt()

                x -= widget.totalPaddingLeft
                y -= widget.totalPaddingTop

                x += widget.scrollX
                y += widget.scrollY

                val layout = widget.layout ?: return false
                val line = layout.getLineForVertical(y)
                val off = layout.getOffsetForHorizontal(line, x.toFloat())

                val links = buffer.getSpans(off, off, android.text.style.ClickableSpan::class.java)

                if (links.isNotEmpty()) {
                    if (action == android.view.MotionEvent.ACTION_UP) {
                        links[0].onClick(widget)
                    } else if (action == android.view.MotionEvent.ACTION_DOWN) {
                        android.text.Selection.setSelection(buffer, buffer.getSpanStart(links[0]), buffer.getSpanEnd(links[0]))
                    }
                    return true
                } else {
                    android.text.Selection.removeSelection(buffer)
                }
            }
            return false
        }
    }
}
