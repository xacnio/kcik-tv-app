/**
 * File: QuickEmoteAdapter.kt
 *
 * Description: RecyclerView Adapter for displaying Quick Emote lists.
 *
 * Author: Xacnio
 *
 */
package dev.xacnio.kciktv.shared.ui.adapter

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import dev.xacnio.kciktv.R
import dev.xacnio.kciktv.shared.data.model.Emote
import dev.xacnio.kciktv.shared.ui.utils.EmoteManager

class QuickEmoteAdapter(
    private val onEmoteClick: (Emote) -> Unit
) : RecyclerView.Adapter<QuickEmoteAdapter.ViewHolder>() {

    private val emotes = mutableListOf<Emote>()
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
        emotes.clear()
        emotes.addAll(newEmotes)
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_emote, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        holder.bind(emotes[position])
    }

    override fun getItemCount(): Int = emotes.size

    override fun onViewRecycled(holder: ViewHolder) {
        dev.xacnio.kciktv.shared.ui.utils.EmoteManager.unregisterViewer(holder.itemView.findViewById(R.id.emoteImage))
        super.onViewRecycled(holder)
    }

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

    inner class ViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val emoteImage: ImageView = itemView.findViewById(R.id.emoteImage)

        fun bind(emote: Emote) {
            val size = (24 * itemView.context.resources.displayMetrics.density).toInt()
            dev.xacnio.kciktv.shared.ui.utils.EmoteManager.loadSynchronizedEmote(
                itemView.context,
                emote.id.toString(),
                size,
                emoteImage
            ) { sharedDrawable ->
                emoteImage.setImageDrawable(sharedDrawable)
            }

            val canUse = canUseEmote(emote)
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
        }
    }
}
