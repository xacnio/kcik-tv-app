/**
 * File: LoyaltyPointsManager.kt
 *
 * Description: Manages the display and real-time updates of Channel Points (Loyalty).
 * It handles fetching the user's point balance, processing update events, formatting values,
 * and triggering floating text animations for point gains or expenditures.
 *
 * Author: Xacnio
 *
 */
package dev.xacnio.kciktv.mobile.ui.player

import android.util.Log
import android.view.View
import androidx.lifecycle.lifecycleScope
import dev.xacnio.kciktv.mobile.MobilePlayerActivity
import dev.xacnio.kciktv.shared.data.prefs.AppPreferences
import kotlinx.coroutines.launch

import dev.xacnio.kciktv.R

class LoyaltyPointsManager(
    private val activity: MobilePlayerActivity,
    private val prefs: AppPreferences
) {

    private companion object {
        const val TAG = "LoyaltyPointsManager"
    }

    var currentLoyaltyPoints: Int = 0

    private fun formatCompactNumber(number: Int): String {
        return when {
            number >= 1_000_000 -> {
                val suffix = activity.getString(R.string.number_suffix_million)
                String.format(java.util.Locale.US, "%.1f%s", number / 1_000_000.0, suffix).replace(".0$suffix", suffix)
            }
            number >= 1_000 -> {
                val suffix = activity.getString(R.string.number_suffix_thousand)
                String.format(java.util.Locale.US, "%.1f%s", number / 1_000.0, suffix).replace(".0$suffix", suffix)
            }
            else -> number.toString()
        }
    }

    fun fetchLoyaltyPoints() {
        val slug = activity.currentChannel?.slug ?: return

        val token = prefs.authToken
        if (token.isNullOrEmpty()) {
            activity.binding.loyaltyPointsButton.visibility = View.GONE
            return
        }

        activity.binding.loyaltyPointsButton.visibility = View.VISIBLE

        activity.lifecycleScope.launch {
            try {
                val result = activity.repository.getChannelPoints(slug, token)
                if (result.isSuccess) {
                    val points = result.getOrNull() ?: 0
                    currentLoyaltyPoints = points
                    if (points >= 1_000_000) {
                        activity.binding.loyaltyPointsText.visibility = View.GONE
                        activity.binding.loyaltyInfinityIcon.visibility = View.VISIBLE
                    } else {
                        activity.binding.loyaltyPointsText.visibility = View.VISIBLE
                        activity.binding.loyaltyInfinityIcon.visibility = View.GONE
                        val formatted = formatCompactNumber(points)
                        activity.binding.loyaltyPointsText.text = formatted
                    }
                }
            } catch (e: Exception) {
                // Ignore
            }
        }
    }

    fun handleChannelPointsEvent(data: String) {
        Log.d(TAG, "Received channel points event: $data")
        try {
            val eventData = com.google.gson.Gson().fromJson(data, dev.xacnio.kciktv.shared.data.model.PointsUpdatedEventData::class.java)

            // Check if user matches (should match as channel is user specific)
            if (prefs.userId != eventData.userId) return

            // We should only update if we are watching the channel where points update happened
            // Or maybe update regardless if we are just showing 'points' in general?
            // Kick points are per channel.
            // eventData has channel_id. compare with current channel
            val channel = activity.currentChannel ?: return
            val currentChannelId = channel.id
            if (eventData.channelId.toString() != currentChannelId) return

            val newBalance = eventData.balance
            val diff = newBalance - currentLoyaltyPoints
            currentLoyaltyPoints = newBalance

            activity.runOnUiThread {
                if (newBalance >= 1_000_000) {
                    activity.binding.loyaltyPointsText.visibility = View.GONE
                    activity.binding.loyaltyInfinityIcon.visibility = View.VISIBLE
                } else {
                    activity.binding.loyaltyPointsText.visibility = View.VISIBLE
                    activity.binding.loyaltyInfinityIcon.visibility = View.GONE
                    activity.binding.loyaltyPointsText.text = formatCompactNumber(newBalance)
                }
                if (diff != 0) {
                    showFloatingPointsAnimation(diff)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error handling points update", e)
        }
    }

    private fun showFloatingPointsAnimation(pointsChange: Int) {
        val text = if (pointsChange > 0) {
            activity.getString(R.string.points_gain_format, pointsChange)
        } else {
            pointsChange.toString()
        }
        activity.binding.floatingPointsText.text = text
        activity.binding.floatingPointsText.visibility = View.VISIBLE
        activity.binding.floatingPointsText.translationY = 0f
        activity.binding.floatingPointsText.alpha = 1f

        activity.binding.floatingPointsText.animate()
            .translationY(-50f)
            .alpha(0f)
            .setDuration(2000)
            .withEndAction {
                activity.binding.floatingPointsText.visibility = View.GONE
            }
            .start()
    }
}
