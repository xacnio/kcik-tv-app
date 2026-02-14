/**
 * File: OfflineChannelAdapter.kt
 *
 * Description: RecyclerView Adapter for displaying Offline Channel lists.
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
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.bumptech.glide.load.resource.bitmap.CircleCrop
import dev.xacnio.kciktv.R
import dev.xacnio.kciktv.shared.data.model.ChannelItem

class OfflineChannelAdapter(
    private val onChannelClick: (ChannelItem) -> Unit,
    private val onProfileClick: (ChannelItem) -> Unit,
    private val onUnfollowClick: ((ChannelItem) -> Unit)? = null,
    private var themeColor: Int
) : RecyclerView.Adapter<OfflineChannelAdapter.ViewHolder>() {

    private var items: List<ChannelItem> = ArrayList()

    fun updateThemeColor(color: Int) {
        themeColor = color
        notifyDataSetChanged()
    }
    
    fun submitList(newItems: List<ChannelItem>, commitCallback: (() -> Unit)? = null) {
        items = newItems
        notifyDataSetChanged()
        commitCallback?.invoke()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_offline_channel, parent, false)
        
        val isTablet = try {
            val resId = parent.context.resources.getIdentifier("is_tablet", "bool", parent.context.packageName)
            if (resId != 0) parent.context.resources.getBoolean(resId) else false
        } catch (e: Exception) {
            parent.context.resources.configuration.smallestScreenWidthDp >= 600
        }

        if (isTablet) {
            val margin = (12 * parent.context.resources.displayMetrics.density).toInt()
            val lp = RecyclerView.LayoutParams(
                RecyclerView.LayoutParams.MATCH_PARENT,
                RecyclerView.LayoutParams.WRAP_CONTENT
            )
            lp.setMargins(margin, margin, margin, margin)
            view.layoutParams = lp
        }
        
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val channel = items[position]
        holder.bind(channel, themeColor)
        holder.itemView.setOnClickListener {
            onChannelClick(channel)
        }
        holder.profilePic.setOnClickListener {
            onProfileClick(channel)
        }
        holder.heartIcon.setOnClickListener {
            onUnfollowClick?.invoke(channel)
        }
        
        holder.itemView.setOnFocusChangeListener { _, hasFocus ->
            holder.updateFocusState(hasFocus)
        }
    }
    
    override fun getItemCount(): Int = items.size

    class ViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        val username: TextView = itemView.findViewById(R.id.offlineChannelUsername)
        val profilePic: ImageView = itemView.findViewById(R.id.offlineChannelProfilePic)
        val heartIcon: ImageView = itemView.findViewById(R.id.offlineChannelHeartIcon)

        fun bind(channel: ChannelItem, themeColor: Int) {
            username.text = channel.username

            // Load Profile Picture
            Glide.with(itemView.context)
                .load(channel.getEffectiveProfilePicUrl())
                .transform(CircleCrop())
                .placeholder(R.drawable.default_avatar)
                .into(profilePic)
                
            heartIcon.imageTintList = android.content.res.ColorStateList.valueOf(themeColor)
        }
        
        fun updateFocusState(hasFocus: Boolean) {
            val scale = if (hasFocus) 1.05f else 1.0f
            itemView.animate()
                .scaleX(scale)
                .scaleY(scale)
                .setDuration(200)
                .start()
                
            itemView.isActivated = hasFocus
        }
    }
}
