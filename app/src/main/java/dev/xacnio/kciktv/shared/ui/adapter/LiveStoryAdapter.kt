package dev.xacnio.kciktv.shared.ui.adapter

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.bumptech.glide.load.resource.bitmap.CircleCrop
import dev.xacnio.kciktv.R
import dev.xacnio.kciktv.shared.data.model.ChannelItem

class LiveStoryAdapter(
    private val onStoryClick: (ChannelItem) -> Unit
) : RecyclerView.Adapter<LiveStoryAdapter.ViewHolder>() {

    private val items = mutableListOf<ChannelItem>()

    fun submitList(list: List<ChannelItem>) {
        items.clear()
        items.addAll(list)
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_live_story, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        holder.bind(items[position])
    }

    override fun getItemCount(): Int = items.size

    inner class ViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val profilePic: ImageView = itemView.findViewById(R.id.liveStoryProfilePic)
        private val username: TextView = itemView.findViewById(R.id.liveStoryUsername)

        fun bind(channel: ChannelItem) {
            username.text = channel.username
            Glide.with(profilePic)
                .load(channel.getEffectiveProfilePicUrl())
                .transform(CircleCrop())
                .placeholder(R.drawable.bg_rounded_circle)
                .into(profilePic)
            itemView.setOnClickListener { onStoryClick(channel) }
        }
    }
}
