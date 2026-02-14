/**
 * File: UserHistoryAdapter.kt
 *
 * Description: RecyclerView Adapter for displaying User History lists.
 *
 * Author: Xacnio
 *
 */
package dev.xacnio.kciktv.shared.ui.adapter

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.text.Spannable
import android.text.SpannableStringBuilder
import android.text.style.ForegroundColorSpan
import android.text.style.RelativeSizeSpan
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.RecyclerView
import dev.xacnio.kciktv.shared.data.model.ChatMessage
import dev.xacnio.kciktv.shared.ui.utils.EmoteManager
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Enhanced adapter for user history messages with date headers and loading states.
 * Used in the user actions bottom sheet to display chat history.
 */
class UserHistoryAdapter(
    private val context: Context,
    initialHistory: List<ChatMessage>
) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

    // Data Types
    sealed class HistoryItem
    data class MessageItem(val message: ChatMessage) : HistoryItem()
    data class DateHeaderItem(val dateText: String) : HistoryItem()

    // State
    private val rawMessages = initialHistory.toMutableList()
    private val adapterItems = mutableListOf<HistoryItem>()
    private var isLoading = false

    companion object {
        private const val VIEW_TYPE_MESSAGE = 0
        private const val VIEW_TYPE_DATE_HEADER = 1
    }

    init {
        rebuildItems()
    }

    fun setHasMore(@Suppress("UNUSED_PARAMETER") more: Boolean) {
        // Not used anymore - button is in header now
    }

    private fun rebuildItems() {
        val oldItems = ArrayList(adapterItems)
        adapterItems.clear()

        // Sort messages by date: NEWEST FIRST (for reverse layout)
        rawMessages.sortByDescending { it.createdAt }

        val dateFormat = SimpleDateFormat("d MMMM yyyy", Locale.getDefault())

        // Build items from newest to oldest
        // In reverse layout: position N = visually top, position 0 = visually bottom
        // So we add DateHeader AFTER the messages of that date (i.e., visually ABOVE them)
        for (i in rawMessages.indices) {
            val msg = rawMessages[i]
            val msgDateStr = dateFormat.format(Date(msg.createdAt))

            // Add message first
            adapterItems.add(MessageItem(msg))

            // Check if next message is different date or this is the last message
            val nextMsg = rawMessages.getOrNull(i + 1)
            val nextDateStr = nextMsg?.let { dateFormat.format(Date(it.createdAt)) }

            if (nextDateStr != msgDateStr) {
                // Add date header after this message (visually above in reverse layout)
                adapterItems.add(DateHeaderItem(msgDateStr))
            }
        }

        // Calculate Diff
        DiffUtil.calculateDiff(object : DiffUtil.Callback() {
            override fun getOldListSize() = oldItems.size
            override fun getNewListSize() = adapterItems.size

            override fun areItemsTheSame(oldItemPosition: Int, newItemPosition: Int): Boolean {
                val oldItem = oldItems[oldItemPosition]
                val newItem = adapterItems[newItemPosition]

                if (oldItem::class != newItem::class) return false

                return when (oldItem) {
                    is MessageItem -> oldItem.message.id == (newItem as MessageItem).message.id
                    is DateHeaderItem -> oldItem.dateText == (newItem as DateHeaderItem).dateText
                }
            }

            override fun areContentsTheSame(oldItemPosition: Int, newItemPosition: Int): Boolean {
                val oldItem = oldItems[oldItemPosition]
                val newItem = adapterItems[newItemPosition]

                if (oldItem is MessageItem && newItem is MessageItem) {
                    return oldItem.message.content == newItem.message.content &&
                            oldItem.message.status == newItem.message.status
                }
                return true
            }
        }).dispatchUpdatesTo(this)
    }

    /**
     * Add messages to the list. Returns true if new messages were added.
     * Scroll restoration should be handled by the caller.
     */
    fun addMessages(newMessages: List<ChatMessage>): Boolean {
        if (newMessages.isEmpty()) return false

        val newUnique = newMessages.filter { newMsg -> rawMessages.none { it.id == newMsg.id } }
        if (newUnique.isEmpty()) return false

        // Add to raw messages
        rawMessages.addAll(newUnique)

        // Rebuild items list
        adapterItems.clear()

        // Sort messages by date: NEWEST FIRST (for reverse layout)
        rawMessages.sortByDescending { it.createdAt }

        val dateFormat = SimpleDateFormat("d MMMM yyyy", Locale.getDefault())

        // Build items from newest to oldest
        // Add DateHeader AFTER messages of that date (visually ABOVE in reverse layout)
        for (i in rawMessages.indices) {
            val msg = rawMessages[i]
            val msgDateStr = dateFormat.format(Date(msg.createdAt))

            // Add message first
            adapterItems.add(MessageItem(msg))

            // Check if next message is different date
            val nextMsg = rawMessages.getOrNull(i + 1)
            val nextDateStr = nextMsg?.let { dateFormat.format(Date(it.createdAt)) }

            if (nextDateStr != msgDateStr) {
                // Add date header after this message (visually above in reverse layout)
                adapterItems.add(DateHeaderItem(msgDateStr))
            }
        }

        // Use notifyDataSetChanged
        notifyDataSetChanged()

        return true
    }

    fun setLoading(loading: Boolean) {
        this.isLoading = loading
        // No UI update needed - loading state is shown in header button
    }

    override fun getItemViewType(position: Int): Int {
        return when (adapterItems[position]) {
            is MessageItem -> VIEW_TYPE_MESSAGE
            is DateHeaderItem -> VIEW_TYPE_DATE_HEADER
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        return when (viewType) {
            VIEW_TYPE_DATE_HEADER -> { // Date Header
                val tv = TextView(context).apply {
                    layoutParams = ViewGroup.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT
                    )
                    setPadding(16, 24, 16, 8)
                    gravity = Gravity.CENTER
                    setTextColor(0xFFAAAAAA.toInt()) // Gray
                    textSize = 12f
                    typeface = Typeface.DEFAULT_BOLD
                }
                object : RecyclerView.ViewHolder(tv) {}
            }
            else -> { // Message
                val tv = TextView(context).apply {
                    layoutParams = ViewGroup.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT
                    )
                    setPadding(16, 4, 16, 4)
                    setTextColor(Color.WHITE)
                    textSize = 13f
                    // Force LTR for Arabic/RTL characters
                    textDirection = View.TEXT_DIRECTION_LTR
                    textAlignment = View.TEXT_ALIGNMENT_VIEW_START
                }
                object : RecyclerView.ViewHolder(tv) {}
            }
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        when (val item = adapterItems[position]) {
            is DateHeaderItem -> {
                (holder.itemView as TextView).text = item.dateText
            }
            is MessageItem -> {
                val tv = holder.itemView as TextView
                val msg = item.message
                val emoteSize = (tv.textSize * 1.5f).toInt()

                // Build Spannable with Timestamp
                val builder = SpannableStringBuilder()

                // Timestamp (Gray)
                val timeFormat = SimpleDateFormat("HH:mm:ss ", Locale.getDefault())
                val timeStr = timeFormat.format(Date(msg.createdAt))
                val start = builder.length
                builder.append(timeStr)
                builder.setSpan(
                    ForegroundColorSpan(0xFF888888.toInt()),
                    start,
                    builder.length,
                    Spannable.SPAN_EXCLUSIVE_EXCLUSIVE
                )
                builder.setSpan(
                    RelativeSizeSpan(0.85f),
                    start,
                    builder.length,
                    Spannable.SPAN_EXCLUSIVE_EXCLUSIVE
                )

                // Content
                builder.append(msg.content)

                tv.setText(builder, TextView.BufferType.SPANNABLE)

                // Now apply emotes to the content part.
                EmoteManager.renderEmoteText(tv, builder.toString(), emoteSize)

                // Re-apply timestamp span because renderEmoteText might have reset spans
                val finalSpan = tv.text as? Spannable
                finalSpan?.setSpan(
                    ForegroundColorSpan(0xFF888888.toInt()),
                    0,
                    timeStr.length,
                    Spannable.SPAN_EXCLUSIVE_EXCLUSIVE
                )
            }
        }
    }

    override fun getItemCount() = adapterItems.size
}
