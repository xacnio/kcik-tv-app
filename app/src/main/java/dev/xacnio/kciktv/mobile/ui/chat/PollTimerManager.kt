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
    // The poll countdown displays mm:ss; visually 5 Hz is indistinguishable from 20 Hz,
    // so use 200 ms ticks instead of 50 ms. In Low Battery mode we drop to 500 ms —
    // still imperceptible on a mm:ss display, but halves Choreographer wakeups.
    // Backed by the main looper Handler (no extra thread + no per-tick runOnUiThread
    // post compared to java.util.Timer).
    private val TICK_MS: Long get() = if (prefs.lowBatteryModeEnabled) 500L else 200L
    private var activeTickRunnable: Runnable? = null
    private var pollAutoHideRunnable: Runnable? = null

    private fun cancelActiveTick() {
        activeTickRunnable?.let { mainHandler.removeCallbacks(it) }
        activeTickRunnable = null
    }

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

        val tick = object : Runnable {
            override fun run() {
                val remMillis = (endTime - System.currentTimeMillis()).toInt()
                if (remMillis <= 0) {
                    handlePollCompletion(poll)
                    return
                }
                val mins = (remMillis / 1000) / 60
                val secs = (remMillis / 1000) % 60
                binding.pollTimerText.text = String.format(Locale.US, "%02d:%02d", mins, secs)
                binding.pollDurationProgress.progress = remMillis
                mainHandler.postDelayed(this, TICK_MS)
            }
        }
        activeTickRunnable = tick
        mainHandler.post(tick)
    }

    /**
     * Handle poll completion - show results and schedule auto-hide
     */
    fun handlePollCompletion(poll: PollData) {
        if (chatStateManager.isPollCompleting) return
        chatStateManager.isPollCompleting = true
        cancelActiveTick()

        binding.pollTimerText.text = activity.getString(R.string.poll_result)
        binding.pollStatusText.text = activity.getString(R.string.poll_result_title)
        binding.pollDurationProgress.visibility = View.VISIBLE

        // Re-render UI to hide non-winners (Winners only)
        val resultPoll = poll.copy(remaining = 0)
        activity.updatePollUI(resultPoll)

        val displayDuration = poll.resultDisplayDuration ?: 10
        binding.pollDurationProgress.max = displayDuration * 1000
        val hideTime = System.currentTimeMillis() + (displayDuration * 1000L)

        val tick = object : Runnable {
            override fun run() {
                val remMillis = (hideTime - System.currentTimeMillis()).toInt()
                if (remMillis <= 0) {
                    chatStateManager.currentPoll = null
                    chatStateManager.isPollCompleting = false
                    activity.updateChatOverlayState()
                    cancelActiveTick()
                    return
                }
                val mins = (remMillis / 1000) / 60
                val secs = (remMillis / 1000) % 60
                binding.pollTimerText.text = String.format(Locale.US, "%02d:%02d", mins, secs)
                binding.pollDurationProgress.progress = remMillis
                mainHandler.postDelayed(this, TICK_MS)
            }
        }
        activeTickRunnable = tick
        mainHandler.post(tick)
    }

    /**
     * Stop the poll timer
     */
    fun stopPollTimer() {
        cancelActiveTick()
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
