package dev.xacnio.kciktv.mobile.ui.chat

import android.content.Context
import android.graphics.Color
import android.text.SpannableStringBuilder
import android.text.style.ForegroundColorSpan
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.RecyclerView
import dev.xacnio.kciktv.R
import dev.xacnio.kciktv.shared.data.model.ChannelEvent
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

enum class ActivityEventCategory(val eventTypes: Set<String>) {
    FOLLOWERS(setOf("FollowerAdded")),
    UNFOLLOWERS(setOf("FollowerDeleted")),
    SUBSCRIBERS(setOf("SubscriptionNew", "SubscriptionRenewed", "CelebrationConsumed")),
    GIFTED_SUBS(setOf("GiftedSubscriptions")),
    HOSTS(setOf("HostStart", "HostEnd", "HostBegin", "HostStop")),
    REWARDS(setOf("RedeemedReward")),
    FOLLOWER_GOALS(setOf("FollowerGoal", "FollowerGoalProgress", "FollowerGoalStart", "FollowerGoalEnd")),
    SUBSCRIBER_GOALS(setOf("SubscriberGoal", "SubscriberGoalProgress", "SubscriberGoalStart", "SubscriberGoalEnd")),
    CHANNEL_ENGAGEMENT(setOf("ChannelEngagement", "ChannelEngagementUpdate"));

    companion object {
        fun categoryFor(eventType: String): ActivityEventCategory? =
            values().firstOrNull { eventType in it.eventTypes }
    }
}

class ActivityFeedAdapter(private val context: Context) :
    RecyclerView.Adapter<ActivityFeedAdapter.EventViewHolder>() {

    private val GREEN_COLOR = Color.parseColor("#53fc18")

    private var allEvents = listOf<ChannelEvent>()
    private val displayedEvents = mutableListOf<ChannelEvent>()

    // Default: all enabled except UNFOLLOWERS
    val enabledCategories = mutableSetOf(
        ActivityEventCategory.FOLLOWERS,
        ActivityEventCategory.SUBSCRIBERS,
        ActivityEventCategory.GIFTED_SUBS,
        ActivityEventCategory.HOSTS,
        ActivityEventCategory.REWARDS,
        ActivityEventCategory.FOLLOWER_GOALS,
        ActivityEventCategory.SUBSCRIBER_GOALS,
        ActivityEventCategory.CHANNEL_ENGAGEMENT
    )

    fun setEvents(list: List<ChannelEvent>) {
        allEvents = list
        applyFilter()
    }

    fun applyFilter() {
        displayedEvents.clear()
        displayedEvents.addAll(allEvents.filter { event ->
            val type = event.eventType ?: return@filter false
            val category = ActivityEventCategory.categoryFor(type)
            category == null || category in enabledCategories
        })
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): EventViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_activity_event, parent, false)
        return EventViewHolder(view)
    }

    override fun onBindViewHolder(holder: EventViewHolder, position: Int) {
        holder.bind(displayedEvents[position])
    }

    override fun getItemCount() = displayedEvents.size

    inner class EventViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val icon: ImageView = itemView.findViewById(R.id.eventIcon)
        private val title: TextView = itemView.findViewById(R.id.eventTitle)
        private val subtitle: TextView = itemView.findViewById(R.id.eventSubtitle)
        private val emoji: TextView = itemView.findViewById(R.id.eventEmoji)
        private val time: TextView = itemView.findViewById(R.id.eventTime)

        fun bind(event: ChannelEvent) {
            val username = event.eventData?.user?.username ?: "Unknown"
            val eventType = event.eventType ?: ""

            icon.clearColorFilter()
            emoji.visibility = View.GONE

            when (eventType) {
                "FollowerAdded" -> {
                    icon.setImageResource(R.drawable.ic_heart_filled)
                    icon.setColorFilter(ContextCompat.getColor(context, android.R.color.holo_red_light))
                    title.text = username
                    subtitle.text = context.getString(R.string.activity_feed_followed)
                    subtitle.visibility = View.VISIBLE
                }
                "FollowerDeleted" -> {
                    icon.setImageResource(R.drawable.ic_heart_outline)
                    icon.setColorFilter(Color.GRAY)
                    title.text = username
                    subtitle.text = context.getString(R.string.activity_feed_unfollowed)
                    subtitle.visibility = View.VISIBLE
                }
                "SubscriptionNew" -> {
                    icon.setImageResource(R.drawable.ic_celebration_popper)
                    icon.setColorFilter(GREEN_COLOR)
                    title.text = buildString { append(username); append(" "); append(context.getString(R.string.activity_feed_subscribed)) }
                    subtitle.visibility = View.GONE
                    emoji.text = "🎉"
                    emoji.visibility = View.VISIBLE
                }
                "SubscriptionRenewed" -> {
                    val total = event.eventData?.subscription?.total ?: 1
                    icon.setImageResource(R.drawable.ic_celebration_popper)
                    icon.setColorFilter(GREEN_COLOR)
                    title.text = buildString { append(username); append(" "); append(context.getString(R.string.activity_feed_resubscribed)) }
                    val baseStr = if (total == 1)
                        context.getString(R.string.activity_feed_months, total)
                    else
                        context.getString(R.string.activity_feed_months_plural, total)
                    subtitle.text = colorizeToken(baseStr, total.toString(), GREEN_COLOR)
                    subtitle.visibility = View.VISIBLE
                    emoji.text = "🎊"
                    emoji.visibility = View.VISIBLE
                }
                "CelebrationConsumed" -> {
                    val total = event.eventData?.subscription?.total ?: 1
                    icon.setImageResource(R.drawable.ic_celebration_popper)
                    icon.setColorFilter(GREEN_COLOR)
                    title.text = username
                    val baseStr = if (total == 1)
                        context.getString(R.string.activity_feed_celebrates, total)
                    else
                        context.getString(R.string.activity_feed_celebrates_plural, total)
                    // Colorize "X month(s)" part
                    val monthToken = if (total == 1) "$total month" else "$total months"
                    subtitle.text = colorizeToken(baseStr, monthToken, GREEN_COLOR)
                    subtitle.visibility = View.VISIBLE
                    emoji.text = "🎉"
                    emoji.visibility = View.VISIBLE
                }
                "GiftedSubscriptions" -> {
                    icon.setImageResource(R.drawable.ic_gift)
                    icon.setColorFilter(Color.parseColor("#AA55FF"))
                    title.text = buildString { append(username); append(" "); append(context.getString(R.string.activity_feed_gifted_sub)) }
                    subtitle.visibility = View.GONE
                    emoji.text = "🎁"
                    emoji.visibility = View.VISIBLE
                }
                "RedeemedReward" -> {
                    icon.setImageResource(R.drawable.ic_loyalty)
                    icon.setColorFilter(ContextCompat.getColor(context, android.R.color.holo_orange_light))
                    val rewardTitle = event.eventData?.reward?.rewardTitle ?: ""
                    title.text = buildString {
                        append(username)
                        append(" ")
                        append(context.getString(R.string.activity_feed_redeemed))
                        append(" \"")
                        append(rewardTitle)
                        append("\"")
                    }
                    val userInput = event.eventData?.reward?.userInput
                    if (!userInput.isNullOrBlank()) {
                        subtitle.text = userInput
                        subtitle.visibility = View.VISIBLE
                    } else {
                        subtitle.visibility = View.GONE
                    }
                }
                else -> {
                    icon.setImageResource(R.drawable.ic_info)
                    icon.setColorFilter(Color.WHITE)
                    title.text = username
                    subtitle.text = eventType
                    subtitle.visibility = View.VISIBLE
                }
            }

            time.text = formatRelativeTime(event.createdAt)
        }

        private fun colorizeToken(text: String, token: String, color: Int): SpannableStringBuilder {
            val span = SpannableStringBuilder(text)
            val start = text.indexOf(token)
            if (start >= 0) {
                span.setSpan(ForegroundColorSpan(color), start, start + token.length, 0)
            }
            return span
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
                val diffMs = System.currentTimeMillis() - d.time
                val diffSec = diffMs / 1000
                when {
                    diffSec < 60 -> "just now"
                    diffSec < 3600 -> "${diffSec / 60} minutes ago"
                    diffSec < 86400 -> "${diffSec / 3600} hours ago"
                    else -> "${diffSec / 86400} days ago"
                }
            } catch (_: Exception) {
                ""
            }
        }
    }
}
