package dev.xacnio.kciktv.mobile.ui.chat

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import dev.xacnio.kciktv.R

data class ModEmoteChannelItem(
    val slug: String,
    val username: String,
    val profilePic: String?,
    val isAdded: Boolean
)

class ModEmoteChannelAdapter(
    private val onAdd: (ModEmoteChannelItem) -> Unit,
    private val onRemove: (ModEmoteChannelItem) -> Unit
) : RecyclerView.Adapter<ModEmoteChannelAdapter.VH>() {

    private val items = mutableListOf<ModEmoteChannelItem>()

    fun setItems(list: List<ModEmoteChannelItem>) {
        items.clear()
        items.addAll(list)
        notifyDataSetChanged()
    }

    inner class VH(view: View) : RecyclerView.ViewHolder(view) {
        val avatar: ImageView = view.findViewById(R.id.modEmoteChannelAvatar)
        val name: TextView = view.findViewById(R.id.modEmoteChannelName)
        val action: ImageButton = view.findViewById(R.id.modEmoteChannelActionButton)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_mod_emote_channel, parent, false)
        return VH(view)
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        val item = items[position]
        holder.name.text = item.username

        Glide.with(holder.avatar.context)
            .load(item.profilePic)
            .circleCrop()
            .placeholder(R.drawable.ic_user)
            .into(holder.avatar)

        if (item.isAdded) {
            holder.action.setImageResource(R.drawable.ic_delete)
            holder.action.setColorFilter(
                androidx.core.content.ContextCompat.getColor(holder.action.context, android.R.color.holo_red_light)
            )
            holder.action.setOnClickListener { onRemove(item) }
        } else {
            holder.action.setImageResource(R.drawable.ic_person_add)
            holder.action.setColorFilter(
                android.graphics.Color.parseColor("#53FC18")
            )
            holder.action.setOnClickListener { onAdd(item) }
        }
    }

    override fun getItemCount() = items.size
}
