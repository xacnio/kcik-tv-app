package dev.xacnio.kciktv.ui.adapter

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.bumptech.glide.load.resource.bitmap.RoundedCorners
import dev.xacnio.kciktv.R
import dev.xacnio.kciktv.data.model.ChannelItem
import com.bumptech.glide.request.RequestOptions
import jp.wasabeef.glide.transformations.BlurTransformation
import com.bumptech.glide.load.MultiTransformation

/**
 * RecyclerView adapter for the channel list
 */
class ChannelAdapter(
    private val onChannelClick: (ChannelItem, Int) -> Unit
) : ListAdapter<ChannelItem, ChannelAdapter.ChannelViewHolder>(ChannelDiffCallback()) {
    
    private var selectedPosition = 0
    
    fun setSelectedPosition(position: Int) {
        val oldPosition = selectedPosition
        selectedPosition = position
        notifyItemChanged(oldPosition)
        notifyItemChanged(selectedPosition)
    }
    
    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ChannelViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_channel, parent, false)
        return ChannelViewHolder(view)
    }
    
    override fun onBindViewHolder(holder: ChannelViewHolder, position: Int) {
        val channel = getItem(position)
        holder.bind(channel, position == selectedPosition)
        
        holder.itemView.setOnClickListener {
            onChannelClick(channel, position)
        }
        
        holder.itemView.setOnFocusChangeListener { _, hasFocus ->
            holder.updateFocusState(hasFocus)
        }
    }
    
    inner class ChannelViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val thumbnailImage: ImageView = itemView.findViewById(R.id.thumbnailImage)
        private val profileImage: ImageView = itemView.findViewById(R.id.profileImage)
        private val channelNumber: TextView = itemView.findViewById(R.id.channelNumber)
        private val channelName: TextView = itemView.findViewById(R.id.channelName)
        private val streamTitle: TextView = itemView.findViewById(R.id.streamTitle)
        private val viewerCount: TextView = itemView.findViewById(R.id.viewerCount)
        private val categoryName: TextView = itemView.findViewById(R.id.categoryName)
        private val liveBadge: View = itemView.findViewById(R.id.liveBadge)
        private val matureBadge: View = itemView.findViewById(R.id.matureBadge)
        private val selectionIndicator: View = itemView.findViewById(R.id.selectionIndicator)
        
        fun bind(channel: ChannelItem, isSelected: Boolean) {
            channelNumber.text = String.format("%02d", adapterPosition + 1)
            channelName.text = channel.username
            streamTitle.text = channel.title
            viewerCount.text = formatViewerCount(channel.viewerCount)
            categoryName.text = channel.categoryName ?: itemView.context.getString(R.string.live_stream)
            
            liveBadge.visibility = if (channel.isLive) View.VISIBLE else View.GONE
            matureBadge.visibility = if (channel.isMature) View.VISIBLE else View.GONE
            selectionIndicator.visibility = if (isSelected) View.VISIBLE else View.GONE
            
            // Load Thumbnail
            channel.thumbnailUrl?.let { url ->
                if (channel.isMature) {
                    Glide.with(itemView.context)
                        .load(url)
                        .transform(com.bumptech.glide.load.resource.bitmap.CenterCrop(), BlurTransformation(25, 4), RoundedCorners(8))
                        .placeholder(R.drawable.placeholder_thumbnail)
                        .into(thumbnailImage)
                } else {
                    Glide.with(itemView.context)
                        .load(url)
                        .transform(com.bumptech.glide.load.resource.bitmap.CenterCrop(), RoundedCorners(8))
                        .placeholder(R.drawable.placeholder_thumbnail)
                        .into(thumbnailImage)
                }
            } ?: run {
                thumbnailImage.setImageResource(R.drawable.placeholder_thumbnail)
            }
            
            // Load Profile Picture
            channel.profilePicUrl?.let { url ->
                Glide.with(itemView.context)
                    .load(url)
                    .circleCrop()
                    .placeholder(R.drawable.placeholder_profile)
                    .into(profileImage)
            } ?: run {
                profileImage.setImageResource(R.drawable.placeholder_profile)
            }
            
            updateSelectionState(isSelected)
        }
        
        fun updateFocusState(hasFocus: Boolean) {
            val scale = if (hasFocus) 1.05f else 1.0f
            itemView.animate()
                .scaleX(scale)
                .scaleY(scale)
                .setDuration(200)
                .start()
            
            itemView.isActivated = hasFocus // To trigger state-list drawables
        }
        
        private fun updateSelectionState(isSelected: Boolean) {
            selectionIndicator.visibility = if (isSelected) View.VISIBLE else View.GONE
        }
        
        private fun formatViewerCount(count: Int): String {
            return when {
                count >= 1000000 -> String.format("%.1fM", count / 1000000.0)
                count >= 1000 -> String.format("%.1fK", count / 1000.0)
                else -> count.toString()
            }
        }
    }
    
    class ChannelDiffCallback : DiffUtil.ItemCallback<ChannelItem>() {
        override fun areItemsTheSame(oldItem: ChannelItem, newItem: ChannelItem): Boolean {
            return oldItem.id == newItem.id
        }
        
        override fun areContentsTheSame(oldItem: ChannelItem, newItem: ChannelItem): Boolean {
            return oldItem == newItem
        }
    }
}
