/**
 * File: PollTimerManager.kt
 *
 * Description: Handles the countdown timer logic for active polls.
 * It manages the visual progress bar and timer text updates, ensuring the UI reflects
 * the remaining time and triggers completion states when the poll ends.
 *
 * Author: Xacnio
 *
 */
package dev.xacnio.kciktv.mobile.ui.chat

import android.os.Handler
import android.os.Looper
import android.view.View
import android.widget.Toast
import dev.xacnio.kciktv.mobile.MobilePlayerActivity
import dev.xacnio.kciktv.R
import dev.xacnio.kciktv.shared.data.model.PollData
import dev.xacnio.kciktv.shared.data.prefs.AppPreferences
import dev.xacnio.kciktv.shared.data.repository.ChannelRepository
import dev.xacnio.kciktv.databinding.ActivityMobilePlayerBinding
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import java.util.Locale
import java.util.Timer
import java.util.TimerTask

/**
 * Manages poll timer logic including starting, stopping, and handling poll completion.
 * Extracted from MobilePlayerActivity for better code organization.
 */
class PollTimerManager(
    private val activity: MobilePlayerActivity,
    private val binding: ActivityMobilePlayerBinding,
    private val prefs: AppPreferences,
    private val repository: ChannelRepository,
    private val lifecycleScope: CoroutineScope,
    private val chatStateManager: ChatStateManager,
    private val mainHandler: Handler
) {
    private var pollTimer: Timer? = null
    private var pollAutoHideRunnable: Runnable? = null

    /**
     * Start the poll countdown timer
     */
    fun startPollTimer() {
        stopPollTimer()
        chatStateManager.isPollCompleting = false
        val poll = chatStateManager.currentPoll ?: return
        val remaining = poll.remaining ?: 0
        val totalDuration = poll.duration ?: remaining

        if (remaining <= 0) {
            handlePollCompletion(poll)
            return
        }

        binding.pollDurationProgress.max = totalDuration * 1000
        val endTime = System.currentTimeMillis() + (remaining * 1000L)

        pollTimer = Timer()
        pollTimer?.scheduleAtFixedRate(object : TimerTask() {
            override fun run() {
                val now = System.currentTimeMillis()
                val remMillis = (endTime - now).toInt()

                activity.runOnUiThread {
                    if (remMillis <= 0) {
                        handlePollCompletion(poll)
                        return@runOnUiThread
                    }

                    val mins = (remMillis / 1000) / 60
                    val secs = (remMillis / 1000) % 60
                    binding.pollTimerText.text = String.format(Locale.US, "%02d:%02d", mins, secs)
                    binding.pollDurationProgress.progress = remMillis
                }
            }
        }, 0, 50)
    }

    /**
     * Handle poll completion - show results and schedule auto-hide
     */
    fun handlePollCompletion(poll: PollData) {
        if (chatStateManager.isPollCompleting) return
        chatStateManager.isPollCompleting = true
        stopPollTimer()

        activity.runOnUiThread {
            binding.pollTimerText.text = activity.getString(R.string.poll_result)
            binding.pollStatusText.text = activity.getString(R.string.poll_result_title)
            binding.pollDurationProgress.visibility = View.VISIBLE

            // Re-render UI to hide non-winners (Winners only)
            val resultPoll = poll.copy(remaining = 0)
            activity.updatePollUI(resultPoll)

            val displayDuration = poll.resultDisplayDuration ?: 10
            binding.pollDurationProgress.max = displayDuration * 1000
            val hideTime = System.currentTimeMillis() + (displayDuration * 1000L)

            pollTimer = Timer()
            pollTimer?.scheduleAtFixedRate(object : TimerTask() {
                override fun run() {
                    val now = System.currentTimeMillis()
                    val remMillis = (hideTime - now).toInt()

                    if (remMillis <= 0) {
                        activity.runOnUiThread {
                            chatStateManager.currentPoll = null
                            chatStateManager.isPollCompleting = false
                            activity.updateChatOverlayState()
                            stopPollTimer()
                        }
                        return
                    }

                    activity.runOnUiThread {
                        val mins = (remMillis / 1000) / 60
                        val secs = (remMillis / 1000) % 60
                        binding.pollTimerText.text = String.format(Locale.US, "%02d:%02d", mins, secs)
                        binding.pollDurationProgress.progress = remMillis
                    }
                }
            }, 0, 50)
        }
    }

    /**
     * Stop the poll timer
     */
    fun stopPollTimer() {
        pollTimer?.cancel()
        pollTimer = null
        pollAutoHideRunnable?.let { mainHandler.removeCallbacks(it) }
    }

    /**
     * Vote in the current poll
     */
    fun voteInPoll(optionId: Int) {
        val slug = activity.currentChannel?.slug ?: return
        val token = prefs.authToken ?: return

        lifecycleScope.launch {
            val result = repository.voteInPoll(slug, token, optionId)
            if (result.isFailure) {
                activity.runOnUiThread {
                    Toast.makeText(
                        activity,
                        activity.getString(R.string.vote_failed, result.exceptionOrNull()?.message),
                        Toast.LENGTH_SHORT
                    ).show()
                }
            }
        }
    }
}
