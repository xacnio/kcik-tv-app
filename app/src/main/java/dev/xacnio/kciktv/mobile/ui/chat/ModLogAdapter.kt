package dev.xacnio.kciktv.mobile.ui.chat

import android.content.Context
import android.graphics.Color
import android.text.SpannableString
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.text.style.StyleSpan
import android.graphics.Typeface
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.RecyclerView
import dev.xacnio.kciktv.R
import dev.xacnio.kciktv.shared.data.model.ChatroomEvent
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

enum class ModLogCategory(val eventTypes: Set<String>) {
    BANNED_WORDS(setOf("BannedWordAdded", "BannedWordRemoved")),
    BANS_AND_UNBANS(setOf("BannedUserAdded", "BannedUserDeleted")),
    CATEGORY_CHANGE(setOf("CategoryChanged", "CategoryUpdated")),
    CHAT_MODE_CHANGES(setOf(
        "SlowModeEnabled", "SlowModeDisabled", "SlowModeUpdated",
        "FollowersOnlyEnabled", "FollowersOnlyDisabled",
        "SubscribersOnlyEnabled", "SubscribersOnlyDisabled",
        "EmotesOnlyEnabled", "EmotesOnlyDisabled",
        "ChatModeChanged", "ChatModeUpdated"
    )),
    LANGUAGE_CHANGE(setOf("LanguageChanged", "LanguageUpdated")),
    MATURE_MODE_CHANGE(setOf("MatureContentEnabled", "MatureContentDisabled", "MatureContentUpdated", "MatureModeUpdated")),
    DELETED_MESSAGES(setOf("MessageDeleted")),
    PIN_AND_UNPIN(setOf("MessagePinned", "MessageUnpinned")),
    POLLS(setOf("PollCreated", "PollUpdated", "PollCompleted", "PollCancelled", "PollDeleted")),
    TITLE_CHANGE(setOf("TitleUpdated", "TitleChanged", "StreamTitleUpdated")),
    USER_TIMEOUTS(setOf("UserTimeouted", "UserTimeoutRemoved"));

    companion object {
        fun categoryFor(eventType: String): ModLogCategory? =
            values().firstOrNull { eventType in it.eventTypes }
    }
}

class ModLogAdapter(private val context: Context) :
    RecyclerView.Adapter<ModLogAdapter.ViewHolder>() {

    private val RED = Color.parseColor("#FF4444")
    private val GREEN = Color.parseColor("#53fc18")
    private val ORANGE = Color.parseColor("#FF8800")
    private val GRAY = Color.parseColor("#888888")
    private val WHITE = Color.WHITE

    private var allEvents = listOf<ChatroomEvent>()
    private val displayed = mutableListOf<ChatroomEvent>()

    val enabledCategories = mutableSetOf(*ModLogCategory.values())

    fun setEvents(list: List<ChatroomEvent>) {
        allEvents = list
        applyFilter()
    }

    fun applyFilter() {
        displayed.clear()
        displayed.addAll(allEvents.filter { event ->
            val type = event.eventType ?: return@filter false
            val category = ModLogCategory.categoryFor(type)
            category == null || category in enabledCategories
        })
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_activity_event, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) =
        holder.bind(displayed[position])

    override fun getItemCount() = displayed.size

    inner class ViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val icon: ImageView = itemView.findViewById(R.id.eventIcon)
        private val title: TextView = itemView.findViewById(R.id.eventTitle)
        private val subtitle: TextView = itemView.findViewById(R.id.eventSubtitle)
        private val emoji: TextView = itemView.findViewById(R.id.eventEmoji)
        private val time: TextView = itemView.findViewById(R.id.eventTime)

        fun bind(event: ChatroomEvent) {
            emoji.visibility = View.GONE
            subtitle.visibility = View.GONE
            icon.clearColorFilter()

            val actor = event.eventData?.user?.username ?: "Unknown"
            val target = event.eventData?.banned?.username
            val eventType = event.eventType ?: ""

            when (eventType) {
                "BannedUserAdded" -> {
                    icon.setImageResource(R.drawable.ic_block)
                    icon.setColorFilter(RED)
                    title.text = target ?: actor
                    subtitle.text = buildSuffixTitle(context.getString(R.string.mod_log_banned_by), actor, RED)
                    subtitle.visibility = View.VISIBLE
                }
                "BannedUserDeleted" -> {
                    icon.setImageResource(R.drawable.ic_block)
                    icon.setColorFilter(GREEN)
                    // title = the unbanned user, subtitle = "unbanned by [green: mod]"
                    title.text = target ?: actor
                    subtitle.text = buildSuffixTitle(context.getString(R.string.mod_log_unbanned_by), actor, GREEN)
                    subtitle.visibility = View.VISIBLE
                }
                "UserTimeouted" -> {
                    icon.setImageResource(R.drawable.ic_hourglass)
                    icon.setColorFilter(ORANGE)
                    val dur = event.eventData?.ban?.duration
                    val durStr = if (dur != null) " (${formatDuration(dur)})" else ""
                    title.text = target ?: actor
                    subtitle.text = buildSuffixTitle("timed out$durStr by", actor, ORANGE)
                    subtitle.visibility = View.VISIBLE
                }
                "UserTimeoutRemoved" -> {
                    icon.setImageResource(R.drawable.ic_hourglass)
                    icon.setColorFilter(GRAY)
                    title.text = target ?: actor
                    subtitle.text = buildSuffixTitle("timeout removed by", actor, WHITE)
                    subtitle.visibility = View.VISIBLE
                }
                "MessagePinned" -> {
                    icon.setImageResource(R.drawable.ic_pin)
                    icon.setColorFilter(WHITE)
                    title.text = "$actor ${context.getString(R.string.mod_log_pinned)}"
                    val content = event.eventData?.pinnedMessage?.content
                    if (!content.isNullOrBlank()) {
                        subtitle.text = if (content.length > 80) content.take(80) + "…" else content
                        subtitle.visibility = View.VISIBLE
                    }
                }
                "MessageUnpinned" -> {
                    icon.setImageResource(R.drawable.ic_pin)
                    icon.setColorFilter(GRAY)
                    title.text = "$actor ${context.getString(R.string.mod_log_unpinned)}"
                }
                "MessageDeleted" -> {
                    icon.setImageResource(R.drawable.ic_delete)
                    icon.setColorFilter(RED)
                    title.text = "$actor ${context.getString(R.string.mod_log_deleted)}"
                }
                "BannedWordAdded" -> {
                    icon.setImageResource(R.drawable.ic_block)
                    icon.setColorFilter(Color.parseColor("#FFBB00"))
                    title.text = "$actor ${context.getString(R.string.mod_log_word_added)}"
                }
                "BannedWordRemoved" -> {
                    icon.setImageResource(R.drawable.ic_block)
                    icon.setColorFilter(GRAY)
                    title.text = "$actor ${context.getString(R.string.mod_log_word_removed)}"
                }
                "PollCreated" -> {
                    icon.setImageResource(R.drawable.ic_poll)
                    icon.setColorFilter(GREEN)
                    title.text = "$actor ${context.getString(R.string.mod_log_created_poll)}"
                }
                "PollCompleted", "PollUpdated" -> {
                    icon.setImageResource(R.drawable.ic_poll)
                    icon.setColorFilter(GRAY)
                    title.text = "$actor ${context.getString(R.string.mod_log_ended_poll)}"
                }
                "PollCancelled", "PollDeleted" -> {
                    icon.setImageResource(R.drawable.ic_poll)
                    icon.setColorFilter(RED)
                    title.text = "$actor ${context.getString(R.string.mod_log_cancelled_poll)}"
                }
                "CategoryChanged", "CategoryUpdated" -> {
                    icon.setImageResource(R.drawable.ic_category)
                    icon.setColorFilter(WHITE)
                    // title = "Category", subtitle = "changed to [green: catName] by [green: actor]"
                    title.text = context.getString(R.string.mod_log_filter_category)
                    val catName = event.eventData?.category?.name ?: ""
                    subtitle.text = buildCategorySubtitle(catName, actor)
                    subtitle.visibility = View.VISIBLE
                }
                "TitleUpdated", "TitleChanged", "StreamTitleUpdated" -> {
                    icon.setImageResource(R.drawable.ic_info)
                    icon.setColorFilter(WHITE)
                    title.text = "$actor ${context.getString(R.string.mod_log_changed_title)}"
                }
                "LanguageChanged", "LanguageUpdated" -> {
                    icon.setImageResource(R.drawable.ic_language)
                    icon.setColorFilter(WHITE)
                    title.text = "$actor ${context.getString(R.string.mod_log_changed_language)}"
                }
                "MatureContentEnabled", "MatureContentDisabled", "MatureContentUpdated", "MatureModeUpdated" -> {
                    icon.setImageResource(R.drawable.ic_globe)
                    icon.setColorFilter(ORANGE)
                    title.text = "$actor ${context.getString(R.string.mod_log_changed_mature)}"
                }
                "SlowModeEnabled", "SlowModeDisabled", "SlowModeUpdated",
                "FollowersOnlyEnabled", "FollowersOnlyDisabled",
                "SubscribersOnlyEnabled", "SubscribersOnlyDisabled",
                "EmotesOnlyEnabled", "EmotesOnlyDisabled",
                "ChatModeChanged", "ChatModeUpdated" -> {
                    icon.setImageResource(R.drawable.ic_chat)
                    icon.setColorFilter(Color.parseColor("#00CCCC"))
                    title.text = "$actor ${context.getString(R.string.mod_log_changed_mode)}"
                }
                else -> {
                    icon.setImageResource(R.drawable.ic_info)
                    icon.setColorFilter(WHITE)
                    title.text = "$actor · $eventType"
                }
            }

            time.text = formatRelativeTime(event.createdAt)
        }

        // "prefix [color: value]" — e.g. "unbanned by [green: Sucre67]"
        private fun buildSuffixTitle(prefix: String, value: String, color: Int): SpannableString {
            val text = "$prefix $value"
            val span = SpannableString(text)
            val start = prefix.length + 1
            span.setSpan(ForegroundColorSpan(color), start, text.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            span.setSpan(StyleSpan(Typeface.BOLD), start, text.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            return span
        }

        // "changed to [green: catName] by [green: actor]"
        private fun buildCategorySubtitle(catName: String, actor: String): SpannableString {
            val prefix = "changed to "
            val middle = " by "
            val text = "$prefix$catName$middle$actor"
            val span = SpannableString(text)
            val catStart = prefix.length
            span.setSpan(ForegroundColorSpan(GREEN), catStart, catStart + catName.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            span.setSpan(StyleSpan(Typeface.BOLD), catStart, catStart + catName.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            val actorStart = catStart + catName.length + middle.length
            span.setSpan(ForegroundColorSpan(GREEN), actorStart, text.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            span.setSpan(StyleSpan(Typeface.BOLD), actorStart, text.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            return span
        }

        private fun buildTitle(actor: String, action: String, target: String?, targetColor: Int): SpannableString {
            val text = if (target != null) "$actor $action $target" else "$actor $action"
            val span = SpannableString(text)
            span.setSpan(StyleSpan(Typeface.BOLD), 0, actor.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            if (target != null) {
                val start = actor.length + 1 + action.length + 1
                span.setSpan(ForegroundColorSpan(targetColor), start, text.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                span.setSpan(StyleSpan(Typeface.BOLD), start, text.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            }
            return span
        }

        private fun formatDuration(seconds: Int): String = when {
            seconds >= 3600 -> "${seconds / 3600}h"
            seconds >= 60 -> "${seconds / 60}m"
            else -> "${seconds}s"
        }

        private fun formatRelativeTime(dateStr: String?): String {
            if (dateStr == null) return ""
            return try {
                val formats = listOf(
                    "yyyy-MM-dd'T'HH:mm:ssXXX",
                    "yyyy-MM-dd'T'HH:mm:ss'Z'",
                    "yyyy-MM-dd HH:mm:ss"
                )
                var date: Date? = null
                for (fmt in formats) {
                    try {
                        val sdf = SimpleDateFormat(fmt, Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }
                        date = sdf.parse(dateStr)
                        if (date != null) break
                    } catch (_: Exception) {}
                }
                val d = date ?: return ""
                val diffSec = (System.currentTimeMillis() - d.time) / 1000
                when {
                    diffSec < 60 -> "just now"
                    diffSec < 3600 -> "${diffSec / 60}m ago"
                    diffSec < 86400 -> "${diffSec / 3600}h ago"
                    else -> "${diffSec / 86400}d ago"
                }
            } catch (_: Exception) { "" }
        }
    }
}
