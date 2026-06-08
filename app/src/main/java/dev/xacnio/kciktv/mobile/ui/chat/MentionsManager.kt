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
import dev.xacnio.kciktv.shared.util.MentionStore

class MentionsManager(
    private val activity: MobilePlayerActivity,
    private val binding: dev.xacnio.kciktv.databinding.ActivityMobilePlayerBinding,
    private val prefs: dev.xacnio.kciktv.shared.data.prefs.AppPreferences
) {
    internal var onReplyClick: ((ChatMessage) -> Unit)? = null
    internal var onGoToMessageClick: ((ChatMessage) -> Unit)? = null

    // Mentions for the current channel only; cleared on channel switch and reloaded from disk
    internal val mentionMessages = mutableListOf<ChatMessage>()
    internal var lastSeenMentionCount = 0

    // The slug of the channel whose mentions are currently loaded in memory
    private var currentChannelSlug: String? = null

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

    /**
     * Called when a new channel is opened. Saves the previous channel's unsaved state,
     * clears in-memory mentions, and loads this channel's persisted mentions from disk.
     */
    fun switchToChannel(slug: String) {
        if (slug == currentChannelSlug) return

        val username = prefs.username
        // Load persisted mentions for the new channel
        currentChannelSlug = slug
        mentionMessages.clear()
        lastSeenMentionCount = 0
        updateMentionsBadge()

        if (!username.isNullOrEmpty()) {
            Thread {
                val saved = MentionStore.load(activity, username, slug)
                activity.runOnUiThread {
                    // Guard: slug may have changed again while the thread ran
                    if (currentChannelSlug != slug) return@runOnUiThread
                    mentionMessages.addAll(saved)
                    // Don't badge already-seen mentions on channel load
                    lastSeenMentionCount = mentionMessages.size
                    updateMentionsBadge()
                }
            }.start()
        }
    }

    /** Called after a mention is added to mentionMessages. Persists and fires notification. */
    fun onMentionAdded(msg: ChatMessage) {
        saveMentions()
        maybeSendMentionNotification(msg)
    }

    private fun saveMentions() {
        val username = prefs.username ?: return
        val slug = currentChannelSlug ?: return
        val snapshot = mentionMessages.toList()
        Thread { MentionStore.save(activity, username, slug, snapshot) }.start()
    }

    /** Cancels all active mention notifications (call when app comes to foreground). */
    fun clearMentionNotifications() {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                val nm = activity.getSystemService(NotificationManager::class.java)
                nm.activeNotifications
                    .filter { it.notification.channelId == MENTION_CHANNEL_ID }
                    .forEach { nm.cancel(it.id) }
            }
        } catch (_: Exception) {}
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
        val deleteAllButton = view.findViewById<View>(R.id.deleteAllMentionsButton)
        val closeButton = view.findViewById<View>(R.id.closeMentionsButton)
        val emptyState = view.findViewById<View>(R.id.emptyStateContainer)

        fun refreshDialog(adapter: MentionsAdapter) {
            mentionsCountText.text = activity.getString(R.string.mentions_count_format, mentionMessages.size)
            emptyState.visibility = if (mentionMessages.isEmpty()) View.VISIBLE else View.GONE
            adapter.submitList(mentionMessages.toList())
        }

        lateinit var mentionsAdapter: MentionsAdapter
        mentionsAdapter = MentionsAdapter(
            onReplyClick = { message ->
                onReplyClick?.invoke(message)
                dialog.dismiss()
            },
            onGoToMessageClick = { message ->
                onGoToMessageClick?.invoke(message)
                dialog.dismiss()
            },
            onDeleteClick = { message ->
                mentionMessages.remove(message)
                saveMentions()
                updateMentionsBadge()
                refreshDialog(mentionsAdapter)
            }
        )

        mentionsRecycler.layoutManager = LinearLayoutManager(activity)
        mentionsRecycler.adapter = mentionsAdapter
        refreshDialog(mentionsAdapter)

        closeButton.setOnClickListener {
            dialog.dismiss()
        }

        deleteAllButton.setOnClickListener {
            com.google.android.material.dialog.MaterialAlertDialogBuilder(activity, R.style.Theme_KcikTV_Dialog)
                .setTitle(activity.getString(R.string.mentions_clear_confirm_title))
                .setMessage(activity.getString(R.string.mentions_clear_confirm_msg))
                .setPositiveButton(activity.getString(R.string.delete)) { _, _ ->
                    mentionMessages.clear()
                    lastSeenMentionCount = 0
                    val slug = currentChannelSlug
                    val username = prefs.username
                    if (!username.isNullOrEmpty() && !slug.isNullOrEmpty()) {
                        Thread { MentionStore.clear(activity, username, slug) }.start()
                    }
                    updateMentionsBadge()
                    dialog.dismiss()
                }
                .setNegativeButton(activity.getString(R.string.cancel), null)
                .show()
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

        val isInPip = Build.VERSION.SDK_INT >= Build.VERSION_CODES.N && activity.isInPictureInPictureMode
        val isResumed = activity.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)
        if (isResumed && !isInPip) return

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
                putExtra("open_mentions", true)
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
                .setGroup("kciktv.mentions")
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
