/**
 * File: MentionsManager.kt
 *
 * Description: Tracks and displays user mentions within the active chat session.
 * It manages the "Mentions" badge notification and the bottom sheet dialog that lists
 * recent messages where the user was tagged, allowing for quick replies.
 *
 * Author: Xacnio
 *
 */
package dev.xacnio.kciktv.mobile.ui.chat

import android.content.Context
import android.os.Build
import android.view.View
import android.widget.TextView
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.bottomsheet.BottomSheetDialog
import dev.xacnio.kciktv.mobile.MobilePlayerActivity
import dev.xacnio.kciktv.R
import dev.xacnio.kciktv.shared.data.model.ChatMessage
import dev.xacnio.kciktv.shared.ui.adapter.MentionsAdapter
import dev.xacnio.kciktv.shared.data.prefs.AppPreferences
import dev.xacnio.kciktv.databinding.ActivityMobilePlayerBinding

class MentionsManager(
    private val activity: MobilePlayerActivity,
    private val binding: dev.xacnio.kciktv.databinding.ActivityMobilePlayerBinding,
    private val prefs: dev.xacnio.kciktv.shared.data.prefs.AppPreferences
) {
    internal var onReplyClick: ((ChatMessage) -> Unit)? = null
    internal var onGoToMessageClick: ((ChatMessage) -> Unit)? = null

    // Mentions state
    internal val mentionMessages = mutableListOf<ChatMessage>()
    internal var lastSeenMentionCount = 0

    internal fun updateMentionsBadge() {
        activity.runOnUiThread {
            val count = mentionMessages.size - lastSeenMentionCount
            if (count > 0) {
                binding.mentionsBadge.text = count.toString()
                binding.mentionsBadge.visibility = View.VISIBLE
                triggerMentionVibration()
            } else {
                binding.mentionsBadge.visibility = View.GONE
            }
        }
    }

    internal fun showMentionsBottomSheet() {
        val dialog = BottomSheetDialog(activity, R.style.Theme_KcikTV_Dialog)
        val view = activity.layoutInflater.inflate(R.layout.bottom_sheet_mentions, null)
        dialog.setContentView(view)

        val mentionsRecycler = view.findViewById<RecyclerView>(R.id.mentionsRecycler)
        val mentionsCountText = view.findViewById<TextView>(R.id.mentionsCount)
        val clearButton = view.findViewById<View>(R.id.clearMentionsButton)
        val emptyState = view.findViewById<View>(R.id.emptyStateContainer)

        mentionsCountText.text = activity.getString(R.string.mentions_count_format, mentionMessages.size)
        emptyState.visibility = if (mentionMessages.isEmpty()) View.VISIBLE else View.GONE

        val mentionsAdapter = MentionsAdapter(
            onReplyClick = { message ->
                onReplyClick?.invoke(message)
                dialog.dismiss()
            },
            onGoToMessageClick = { message ->
                onGoToMessageClick?.invoke(message)
                dialog.dismiss()
            }
        )

        mentionsRecycler.layoutManager = LinearLayoutManager(activity)
        mentionsRecycler.adapter = mentionsAdapter
        mentionsAdapter.submitList(mentionMessages)

        clearButton.setOnClickListener {
            mentionMessages.clear()
            lastSeenMentionCount = 0
            updateMentionsBadge()
            dialog.dismiss()
        }

        dialog.setOnDismissListener {
            lastSeenMentionCount = mentionMessages.size
            updateMentionsBadge()
        }

        dialog.show()
    }

    private fun triggerMentionVibration() {
        if (!prefs.vibrateOnMentions) return
        
        try {
            val vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val vibratorManager = activity.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as android.os.VibratorManager
                vibratorManager.defaultVibrator
            } else {
                @Suppress("DEPRECATION")
                activity.getSystemService(Context.VIBRATOR_SERVICE) as android.os.Vibrator
            }

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                // Simple short vibration for mentions
                val effect = android.os.VibrationEffect.createOneShot(50, android.os.VibrationEffect.DEFAULT_AMPLITUDE)
                vibrator.vibrate(effect)
            } else {
                @Suppress("DEPRECATION")
                vibrator.vibrate(50)
            }
        } catch (e: Exception) {
            android.util.Log.e("MentionsManager", "Failed to vibrate", e)
        }
    }
}
