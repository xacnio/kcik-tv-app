/**
 * File: MentionsAdapter.kt
 *
 * Description: RecyclerView Adapter for displaying Mentions lists.
 *
 * Author: Xacnio
 *
 */
package dev.xacnio.kciktv.shared.ui.adapter

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import dev.xacnio.kciktv.R
import dev.xacnio.kciktv.shared.data.model.ChatMessage
import dev.xacnio.kciktv.shared.util.TimeUtils
import java.util.Date
import dev.xacnio.kciktv.shared.ui.utils.EmoteManager

class MentionsAdapter(
    private val onReplyClick: (ChatMessage) -> Unit,
    private val onGoToMessageClick: (ChatMessage) -> Unit
) : RecyclerView.Adapter<MentionsAdapter.MentionViewHolder>() {

    private var items: List<ChatMessage> = emptyList()

    fun submitList(newList: List<ChatMessage>) {
        items = newList.reversed() // Show newest at top
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): MentionViewHolder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_mention_message, parent, false)
        return MentionViewHolder(view)
    }

    override fun onBindViewHolder(holder: MentionViewHolder, position: Int) {
        holder.bind(items[position])
    }

    override fun getItemCount(): Int = items.size

    inner class MentionViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        private val senderUsername: TextView = view.findViewById(R.id.senderUsername)
        private val messageTime: TextView = view.findViewById(R.id.messageTime)
        private val replyContent: TextView = view.findViewById(R.id.replyContent)
        private val messageContent: TextView = view.findViewById(R.id.messageContent)
        private val replyButton: View = view.findViewById(R.id.replyButton)
        private val goToMessageButton: View = view.findViewById(R.id.goToMessageButton)

        fun bind(message: ChatMessage) {
            senderUsername.text = message.sender.username
            senderUsername.setTextColor(android.graphics.Color.parseColor(message.sender.color ?: "#53fc18"))
            
            messageTime.text = formatTimeAgo(message.createdAt)
            
            // Handle Reply Header
            message.metadata?.originalSender?.let { sender ->
                val replyText = "↪ @${sender.username}: ${message.metadata.originalMessageContent ?: ""}"
                replyContent.visibility = View.VISIBLE
                val replyEmoteSize = (replyContent.textSize * 1.2f).toInt()
                dev.xacnio.kciktv.shared.ui.utils.EmoteManager.renderEmoteText(replyContent, replyText, replyEmoteSize)
            } ?: run {
                replyContent.visibility = View.GONE
            }

            // Handle Main Message Content with Emotes
            val emoteSize = (messageContent.textSize * 1.5f).toInt()
            dev.xacnio.kciktv.shared.ui.utils.EmoteManager.renderEmoteText(messageContent, message.content, emoteSize)
            
            replyButton.setOnClickListener { onReplyClick(message) }
            goToMessageButton.setOnClickListener { onGoToMessageClick(message) }
        }

        private fun formatTimeAgo(timestamp: Long): String {
            val context = itemView.context
            val diff = System.currentTimeMillis() - timestamp
            val minutes = diff / (1000 * 60)
            val hours = minutes / 60
            
            return when {
                minutes < 1 -> context.getString(R.string.time_ago_just_now)
                hours < 1 -> context.getString(R.string.time_ago_minutes, minutes.toInt())
                else -> context.getString(R.string.time_ago_hours, hours.toInt())
            }
        }
    }
}
