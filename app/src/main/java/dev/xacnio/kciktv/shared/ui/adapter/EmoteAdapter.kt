/**
 * File: EmoteAdapter.kt
 *
 * Description: RecyclerView Adapter for displaying Emote lists.
 *
 * Author: Xacnio
 *
 */
package dev.xacnio.kciktv.shared.ui.adapter

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import dev.xacnio.kciktv.R
import dev.xacnio.kciktv.shared.data.model.Emote
import dev.xacnio.kciktv.shared.ui.utils.EmoteManager

class EmoteAdapter(
    private val onEmoteClick: (Emote) -> Unit
) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

    companion object {
        private const val TYPE_EMOTE = 0
        private const val TYPE_SECTION_HEADER = 1
    }

    sealed class EmoteItem {
        data class EmoteEntry(val emote: Emote) : EmoteItem()
        data class SectionHeader(val titleResId: Int?) : EmoteItem()
    }

    private val items = mutableListOf<EmoteItem>()
    private var isSubscribedToCurrentChannel: Boolean = false
    private var currentChannelId: Long? = null

    fun setSubscriptionStatus(subscribed: Boolean) {
        if (isSubscribedToCurrentChannel != subscribed) {
            isSubscribedToCurrentChannel = subscribed
            notifyDataSetChanged()
        }
    }

    fun setCurrentChannelId(channelId: Long?) {
        currentChannelId = channelId
    }

    fun setEmotes(newEmotes: List<Emote>) {
        items.clear()
        
        // Separate normal and subscriber-only emotes
        val normalEmotes = newEmotes.filter { !it.subscribersOnly }
        val subscriberEmotes = newEmotes.filter { it.subscribersOnly }
        
        // Add normal emotes
        normalEmotes.forEach { items.add(EmoteItem.EmoteEntry(it)) }
        
        // Add subscriber section with divider if there are subscriber emotes
        if (subscriberEmotes.isNotEmpty()) {
            items.add(EmoteItem.SectionHeader(null)) // Will display section header from resource
            subscriberEmotes.forEach { items.add(EmoteItem.EmoteEntry(it)) }
        }
        
        notifyDataSetChanged()
    }

    override fun getItemViewType(position: Int): Int {
        return when (items[position]) {
            is EmoteItem.EmoteEntry -> TYPE_EMOTE
            is EmoteItem.SectionHeader -> TYPE_SECTION_HEADER
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        return when (viewType) {
            TYPE_SECTION_HEADER -> {
                val view = LayoutInflater.from(parent.context).inflate(R.layout.item_emote_section, parent, false)
                SectionViewHolder(view)
            }
            else -> {
                val view = LayoutInflater.from(parent.context).inflate(R.layout.item_emote, parent, false)
                EmoteViewHolder(view)
            }
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        when (val item = items[position]) {
            is EmoteItem.EmoteEntry -> (holder as EmoteViewHolder).bind(item.emote)
            is EmoteItem.SectionHeader -> (holder as SectionViewHolder).bind(item.titleResId)
        }
    }

    override fun getItemCount(): Int = items.size

    override fun onViewRecycled(holder: RecyclerView.ViewHolder) {
        if (holder is EmoteViewHolder) {
            dev.xacnio.kciktv.shared.ui.utils.EmoteManager.unregisterViewer(holder.itemView.findViewById(R.id.emoteImage))
        }
        super.onViewRecycled(holder)
    }
    
    /**
     * Check if an emote can be used by the current user
     * - For current channel emotes: check subscription status
     * - For other channels' subscriber emotes: always allowed (user is subscribed to those channels)
     */
    private fun canUseEmote(emote: Emote): Boolean {
        if (!emote.subscribersOnly) return true
        
        // If emote belongs to the current channel, check current channel subscription
        val emoteChannelId = emote.channelId
        if (emoteChannelId != null && currentChannelId != null && emoteChannelId == currentChannelId) {
            return isSubscribedToCurrentChannel
        }
        
        // For other channels' subscriber emotes, always allow (they appear because user is subscribed there)
        return true
    }

    inner class EmoteViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val emoteImage: ImageView = itemView.findViewById(R.id.emoteImage)

        fun bind(emote: Emote) {
            val size = (40 * itemView.context.resources.displayMetrics.density).toInt()
            dev.xacnio.kciktv.shared.ui.utils.EmoteManager.loadSynchronizedEmote(
                itemView.context,
                emote.id.toString(),
                size,
                emoteImage
            ) { sharedDrawable ->
                emoteImage.setImageDrawable(sharedDrawable)
            }

            val canUse = canUseEmote(emote)
            
            // Dim subscriber-only emotes if user cannot use them
            emoteImage.alpha = if (canUse) 1.0f else 0.4f
            
            itemView.setOnClickListener {
                if (canUse) {
                    onEmoteClick(emote)
                } else {
                    android.widget.Toast.makeText(
                        itemView.context,
                        itemView.context.getString(R.string.emote_available_only_for_subscribers),
                        android.widget.Toast.LENGTH_SHORT
                    ).show()
                }
            }
            
            // Long press to show emote name
            itemView.setOnLongClickListener {
                val suffix = if (emote.subscribersOnly) itemView.context.getString(R.string.emote_subscriber_only_suffix) else ""
                android.widget.Toast.makeText(itemView.context, emote.name + suffix, android.widget.Toast.LENGTH_SHORT).show()
                true
            }
        }
    }

    inner class SectionViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val titleText: TextView = itemView.findViewById(R.id.sectionTitle)

        fun bind(titleResId: Int?) {
            titleText.text = itemView.context.getString(titleResId ?: R.string.emote_category_subscribers)
        }
    }
}
