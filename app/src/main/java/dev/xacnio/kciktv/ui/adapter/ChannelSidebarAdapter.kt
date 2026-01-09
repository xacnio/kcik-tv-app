package dev.xacnio.kciktv.ui.adapter

import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.InsetDrawable
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import dev.xacnio.kciktv.R
import dev.xacnio.kciktv.PlayerActivity
import dev.xacnio.kciktv.data.model.ChannelItem

class ChannelSidebarAdapter(
    private var channels: MutableList<ChannelItem>,
    private var currentIndex: Int,
    private var themeColor: Int = 0xFF53FC18.toInt(),
    private val onChannelSelected: (Int) -> Unit,
    private val onLoadMore: () -> Unit
) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

    companion object {
        private const val TYPE_CHANNEL = 0
        private const val TYPE_LOAD_MORE = 1
    }

    class ChannelViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val channelNumber: TextView = view.findViewById(R.id.channelNumber)
        val thumbnailImage: ImageView = view.findViewById(R.id.thumbnailImage)
        val profileImage: ImageView = view.findViewById(R.id.profileImage)
        val channelName: TextView = view.findViewById(R.id.channelName)
        val streamTitle: TextView = view.findViewById(R.id.streamTitle)
        val viewerCount: TextView = view.findViewById(R.id.viewerCount)
        val categoryName: TextView = view.findViewById(R.id.categoryName)
        val liveBadge: View = view.findViewById(R.id.liveBadge)
        val selectionIndicator: View = view.findViewById(R.id.selectionIndicator)
    }

    class LoadMoreViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val txtLoadMore: TextView = view.findViewById(R.id.txtSettingItem)
    }

    override fun getItemViewType(position: Int): Int {
        return if (position < channels.size) TYPE_CHANNEL else TYPE_LOAD_MORE
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        return if (viewType == TYPE_CHANNEL) {
            val view = LayoutInflater.from(parent.context).inflate(R.layout.item_channel, parent, false)
            ChannelViewHolder(view)
        } else {
            val view = LayoutInflater.from(parent.context).inflate(R.layout.item_setting, parent, false)
            LoadMoreViewHolder(view)
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        if (holder is ChannelViewHolder) {
            val channel = channels[position]
            
            holder.channelNumber.text = String.format("%02d", position + 1)
            holder.channelName.text = channel.username
            
            // Check offline status
            if (!channel.isLive) {
                // Offline channel view
                holder.streamTitle.text = holder.itemView.context.getString(R.string.stream_ended)
                holder.viewerCount.visibility = View.GONE
                holder.categoryName.visibility = View.GONE
                holder.liveBadge.visibility = View.GONE
                holder.itemView.alpha = 0.5f
            } else {
                // Normal live channel view
                holder.streamTitle.text = channel.title
                holder.viewerCount.text = formatViewerCount(channel.viewerCount)
                holder.viewerCount.visibility = View.VISIBLE
                holder.categoryName.text = channel.categoryName ?: holder.itemView.context.getString(R.string.live_stream)
                holder.categoryName.visibility = View.VISIBLE
                holder.liveBadge.visibility = View.VISIBLE
                holder.itemView.alpha = 1f
            }
            
            holder.channelNumber.setTextColor(themeColor)
            holder.selectionIndicator.setBackgroundColor(themeColor)
            
            // Viewer icon in theme color, text in white
            holder.viewerCount.setTextColor(Color.WHITE)
            val viewerIcon = androidx.core.content.ContextCompat.getDrawable(holder.itemView.context, R.drawable.ic_viewers_small)?.mutate()
            viewerIcon?.let {
                it.setTint(themeColor)
                val size = (14 * holder.itemView.resources.displayMetrics.density).toInt()
                it.setBounds(0, 0, size, size)
                holder.viewerCount.setCompoundDrawables(it, null, null, null)
            }

            Glide.with(holder.itemView.context)
                .load(channel.thumbnailUrl)
                .placeholder(R.color.surface_dark)
                .centerCrop()
                .into(holder.thumbnailImage)

            Glide.with(holder.itemView.context)
                .load(channel.profilePicUrl)
                .circleCrop()
                .into(holder.profileImage)

            val isPlaying = position == currentIndex
            holder.selectionIndicator.visibility = if (isPlaying) View.VISIBLE else View.GONE
            holder.channelName.setTextColor(if (isPlaying) themeColor else Color.WHITE)
            
            // Apply Focus Theme & Marquee
            holder.itemView.setOnFocusChangeListener { view, hasFocus ->
                val bg = GradientDrawable()
                val density = view.resources.displayMetrics.density
                bg.cornerRadius = 16f * density 

                if (hasFocus) {
                    val alphaColor = (themeColor and 0x00FFFFFF) or 0x4D000000 // 30% alpha
                    bg.setColor(alphaColor)
                    bg.setStroke((2.5f * density).toInt(), themeColor)
                } else {
                    bg.setColor(Color.parseColor("#141A26")) // background_card color
                    bg.setStroke(0, Color.TRANSPARENT)
                }
                view.background = bg
                
                // Marquee only when focused
                holder.channelName.isSelected = hasFocus
                holder.streamTitle.isSelected = hasFocus
                holder.categoryName.isSelected = hasFocus
            }

            holder.itemView.setOnClickListener { 
                if (position == currentIndex) {
                    // Close sidebar if same channel is selected
                    (holder.itemView.context as? PlayerActivity)?.hideAllOverlays()
                } else {
                    // Update selection immediately for visual feedback
                    setCurrentIndex(position)
                    // Then change channel
                    onChannelSelected(position)
                }
            }
        } else if (holder is LoadMoreViewHolder) {
            holder.txtLoadMore.text = holder.itemView.context.getString(R.string.load_more)
            holder.txtLoadMore.setTextColor(themeColor)
            
            holder.itemView.setOnFocusChangeListener { view, hasFocus ->
                val bg = GradientDrawable()
                val density = view.resources.displayMetrics.density
                bg.cornerRadius = 14f * density // Consistent with drawer buttons
                
                if (hasFocus) {
                    val alphaColor = (themeColor and 0x00FFFFFF) or 0x4D000000 // 30% alpha
                    bg.setColor(alphaColor)
                    bg.setStroke((2.5f * density).toInt(), themeColor)
                    holder.txtLoadMore.setTextColor(Color.WHITE)
                    holder.txtLoadMore.typeface = android.graphics.Typeface.DEFAULT_BOLD
                } else {
                    bg.setColor(Color.TRANSPARENT)
                    bg.setStroke(0, Color.TRANSPARENT)
                    holder.txtLoadMore.setTextColor(themeColor)
                    holder.txtLoadMore.typeface = android.graphics.Typeface.DEFAULT
                }
                
                view.background = bg
            }

            holder.itemView.setOnClickListener { onLoadMore() }
        }
        
        holder.itemView.isFocusable = true
        holder.itemView.isFocusableInTouchMode = true
    }

    override fun getItemCount(): Int = channels.size + 1

    fun addChannels(newChannels: List<ChannelItem>) {
        val startPos = channels.size
        channels.addAll(newChannels)
        notifyItemRangeInserted(startPos, newChannels.size)
    }

    fun replaceChannels(newList: List<ChannelItem>, newIndex: Int) {
        channels.clear()
        channels.addAll(newList)
        currentIndex = newIndex
        notifyDataSetChanged()
    }

    fun setCurrentIndex(index: Int) {
        val oldIndex = currentIndex
        currentIndex = index
        if (oldIndex in channels.indices) notifyItemChanged(oldIndex)
        if (currentIndex in channels.indices) notifyItemChanged(currentIndex)
    }

    fun updateThemeColor(color: Int) {
        themeColor = color
        notifyDataSetChanged()
    }

    private fun formatViewerCount(count: Int): String = when {
        count >= 1000000 -> String.format("%.1fM", count / 1000000.0)
        count >= 1000 -> String.format("%.1fK", count / 1000.0)
        else -> count.toString()
    }
}
