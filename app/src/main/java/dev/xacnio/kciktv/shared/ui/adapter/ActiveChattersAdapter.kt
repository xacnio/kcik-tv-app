/**
 * File: ActiveChattersAdapter.kt
 *
 * Description: RecyclerView adapter for the Active Chatters panel — collapsible group headers
 * and compact chatter rows (badges, name, message count) as one flat, recycling list.
 *
 * Author: Xacnio
 *
 */
package dev.xacnio.kciktv.shared.ui.adapter

import android.graphics.Color
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import dev.xacnio.kciktv.R
import dev.xacnio.kciktv.shared.data.model.ActiveChatter
import dev.xacnio.kciktv.shared.data.model.ChatBadge
import dev.xacnio.kciktv.shared.data.model.ChatBadgeV2
import dev.xacnio.kciktv.shared.ui.utils.BadgeRenderUtils

class ActiveChattersAdapter(
    private val onChatterClick: (ActiveChatter) -> Unit,
    private val onSectionToggle: (String) -> Unit
) : ListAdapter<ActiveChattersAdapter.Item, RecyclerView.ViewHolder>(DIFF) {

    sealed class Item {
        /** [badgeV1]/[badgeV2] are set by the badge grouping only, which draws the real art. */
        data class Header(
            val sectionKey: String,
            val title: String,
            val chatters: Int,
            val messages: Int,
            val collapsed: Boolean,
            val badgeV1: ChatBadge? = null,
            val badgeV2: ChatBadgeV2? = null
        ) : Item()

        data class Chatter(val chatter: ActiveChatter) : Item()
    }

    /** Channel subscriber badge art (months -> URL); set before submitting a list. */
    var subscriberBadges: Map<Int, String> = emptyMap()

    override fun getItemViewType(position: Int): Int = when (getItem(position)) {
        is Item.Header -> TYPE_HEADER
        is Item.Chatter -> TYPE_CHATTER
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val inflater = LayoutInflater.from(parent.context)
        return if (viewType == TYPE_HEADER) {
            HeaderViewHolder(inflater.inflate(R.layout.item_active_chatter_header, parent, false))
        } else {
            ChatterViewHolder(inflater.inflate(R.layout.item_active_chatter, parent, false))
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        when (val item = getItem(position)) {
            is Item.Header -> (holder as HeaderViewHolder).bind(item)
            is Item.Chatter -> (holder as ChatterViewHolder).bind(item.chatter)
        }
    }

    inner class HeaderViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val chevron: ImageView = itemView.findViewById(R.id.activeChatterHeaderChevron)
        private val badge: LinearLayout = itemView.findViewById(R.id.activeChatterHeaderBadge)
        private val title: TextView = itemView.findViewById(R.id.activeChatterHeaderTitle)
        private val summary: TextView = itemView.findViewById(R.id.activeChatterHeaderSummary)

        fun bind(item: Item.Header) {
            title.text = item.title
            chevron.setImageResource(
                if (item.collapsed) R.drawable.ic_chevron_right else R.drawable.ic_chevron_down
            )
            itemView.setOnClickListener { onSectionToggle(item.sectionKey) }
            summary.text = itemView.context.getString(
                R.string.active_chatters_summary, item.chatters, item.messages
            )

            if (item.badgeV1 == null && item.badgeV2 == null) {
                badge.removeAllViews()
                badge.visibility = View.GONE
            } else {
                val density = itemView.context.resources.displayMetrics.density
                BadgeRenderUtils.renderChatSenderBadges(
                    itemView.context,
                    badge,
                    listOfNotNull(item.badgeV1),
                    listOfNotNull(item.badgeV2),
                    (14 * density).toInt(),
                    0,
                    subscriberBadges
                )
            }
        }
    }

    inner class ChatterViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val badges: LinearLayout = itemView.findViewById(R.id.activeChatterBadges)
        private val username: TextView = itemView.findViewById(R.id.activeChatterName)
        private val messageCount: TextView = itemView.findViewById(R.id.activeChatterMessageCount)

        fun bind(chatter: ActiveChatter) {
            val context = itemView.context

            username.text = chatter.username
            username.setTextColor(
                try {
                    chatter.sender.color?.let { Color.parseColor(it) }
                        ?: ContextCompat.getColor(context, R.color.brand_green)
                } catch (_: Exception) {
                    ContextCompat.getColor(context, R.color.brand_green)
                }
            )

            messageCount.text = chatter.messageCount.toString()
            messageCount.contentDescription = context.getString(
                R.string.active_chatters_message_count, chatter.messageCount
            )

            val density = context.resources.displayMetrics.density
            BadgeRenderUtils.renderChatSenderBadges(
                context,
                badges,
                chatter.sender.badges,
                chatter.sender.badgesV2,
                (13 * density).toInt(),
                (3 * density).toInt(),
                subscriberBadges
            )

            itemView.setOnClickListener { onChatterClick(chatter) }
        }
    }

    companion object {
        private const val TYPE_HEADER = 0
        private const val TYPE_CHATTER = 1

        private val DIFF = object : DiffUtil.ItemCallback<Item>() {
            override fun areItemsTheSame(oldItem: Item, newItem: Item): Boolean = when {
                oldItem is Item.Header && newItem is Item.Header ->
                    oldItem.sectionKey == newItem.sectionKey
                oldItem is Item.Chatter && newItem is Item.Chatter ->
                    oldItem.chatter.username.equals(newItem.chatter.username, ignoreCase = true)
                else -> false
            }

            override fun areContentsTheSame(oldItem: Item, newItem: Item): Boolean = oldItem == newItem
        }
    }
}
