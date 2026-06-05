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

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.SoundPool
import android.os.Build
import android.view.View
import android.widget.TextView
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.lifecycle.Lifecycle
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

    // Short soft "mention" sound, played via SoundPool (low-latency, mixes with stream audio).
    private var soundPool: SoundPool? = null
    private var mentionSoundId: Int = 0
    private var mentionSoundLoaded = false

    init {
        try {
            val attrs = AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .build()
            soundPool = SoundPool.Builder().setMaxStreams(1).setAudioAttributes(attrs).build().also { sp ->
                sp.setOnLoadCompleteListener { _, sampleId, status ->
                    if (status == 0 && sampleId == mentionSoundId) mentionSoundLoaded = true
                }
                mentionSoundId = sp.load(activity, R.raw.mention_pop, 1)
            }
        } catch (e: Exception) {
            android.util.Log.e("MentionsManager", "Failed to init mention sound", e)
        }
    }

    /** Releases the SoundPool. Call from the activity's onDestroy. */
    fun release() {
        try { soundPool?.release() } catch (e: Exception) { /* ignore */ }
        soundPool = null
        mentionSoundLoaded = false
    }

    internal fun updateMentionsBadge() {
        activity.runOnUiThread {
            val count = mentionMessages.size - lastSeenMentionCount
            if (count > 0) {
                binding.mentionsBadge.text = count.toString()
                binding.mentionsBadge.visibility = View.VISIBLE
                triggerMentionVibration()
                triggerMentionSound()
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

    private fun triggerMentionSound() {
        if (!prefs.soundOnMentions) return
        val sp = soundPool ?: return
        if (!mentionSoundLoaded) return
        try {
            // The asset is already mastered soft; play at full SoundPool volume.
            sp.play(mentionSoundId, 1.0f, 1.0f, 1, 0, 1.0f)
        } catch (e: Exception) {
            android.util.Log.e("MentionsManager", "Failed to play mention sound", e)
        }
    }

    private val emoteToken = Regex("\\[emote:\\d+:([^\\]]+)\\]")
    private fun cleanContent(s: String): String = emoteToken.replace(s) { it.groupValues[1] }.trim()

    private var mentionChannelCreated = false
    private fun ensureMentionChannel() {
        if (mentionChannelCreated) return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                MENTION_CHANNEL_ID,
                activity.getString(R.string.notif_mentions_channel),
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = activity.getString(R.string.notif_mentions_channel_desc)
                // The in-app "sound on mention" / "vibrate on mention" toggles own the audio
                // and haptics, so keep the channel itself silent to avoid doubling them.
                setSound(null, null)
                enableVibration(false)
                setShowBadge(true)
            }
            activity.getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
        mentionChannelCreated = true
    }

    /**
     * Posts a system notification for a mention/reply, but ONLY when the user is not
     * actively watching in the foreground (i.e. screen locked, PIP, or background audio).
     * Gated by [AppPreferences.notifyOnMentions] and the POST_NOTIFICATIONS permission.
     */
    fun maybeSendMentionNotification(msg: ChatMessage) {
        if (!prefs.notifyOnMentions) return

        // Skip while the app is in the foreground and the user is already watching.
        val isInPip = Build.VERSION.SDK_INT >= Build.VERSION_CODES.N && activity.isInPictureInPictureMode
        val isResumed = activity.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)
        if (isResumed && !isInPip) return

        // Notifications need runtime permission on Android 13+.
        if (Build.VERSION.SDK_INT >= 33 &&
            activity.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            return
        }

        try {
            ensureMentionChannel()

            val currentUser = prefs.username
            val who = msg.sender.username
            val isReply = msg.metadata?.originalSender?.username.equals(currentUser, ignoreCase = true) &&
                !msg.metadata?.originalMessageContent.isNullOrBlank()

            val title: String
            val text: String
            val bigText: String
            if (isReply) {
                title = activity.getString(R.string.notif_reply_title, who)
                val reply = cleanContent(msg.content)
                val mine = cleanContent(msg.metadata?.originalMessageContent ?: "")
                text = reply
                bigText = reply + "\n" + activity.getString(R.string.notif_reply_original, mine)
            } else {
                title = activity.getString(R.string.notif_mention_title, who)
                text = cleanContent(msg.content)
                bigText = text
            }

            val intent = Intent(activity, MobilePlayerActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP
            }
            val pendingIntent = PendingIntent.getActivity(
                activity, 0, intent, PendingIntent.FLAG_IMMUTABLE
            )

            val notification = NotificationCompat.Builder(activity, MENTION_CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_notification)
                .setContentTitle(title)
                .setContentText(text)
                .setStyle(NotificationCompat.BigTextStyle().bigText(bigText))
                .setContentIntent(pendingIntent)
                .setAutoCancel(true)
                .setCategory(NotificationCompat.CATEGORY_MESSAGE)
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .build()

            NotificationManagerCompat.from(activity).notify(msg.id.hashCode(), notification)
        } catch (e: Exception) {
            android.util.Log.e("MentionsManager", "Failed to post mention notification", e)
        }
    }

    companion object {
        private const val MENTION_CHANNEL_ID = "kciktv_mentions"
    }
}
