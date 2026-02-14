/**
 * File: ChannelClipAdapter.kt
 *
 * Description: RecyclerView Adapter for displaying Channel Clip lists.
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
import com.bumptech.glide.load.resource.bitmap.CenterCrop
import dev.xacnio.kciktv.R
import dev.xacnio.kciktv.shared.data.model.ChannelClip
import dev.xacnio.kciktv.shared.util.ShimmerDrawable
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import dev.xacnio.kciktv.shared.util.Constants

class ChannelClipAdapter(
    private var clips: List<ChannelClip>,
    private var isGrid: Boolean = true,
    private val onItemClick: (ChannelClip) -> Unit,
    private val onItemLongClick: ((ChannelClip) -> Unit)? = null
) : RecyclerView.Adapter<ChannelClipAdapter.ClipViewHolder>() {

    fun updateData(newClips: List<ChannelClip>, newIsGrid: Boolean = isGrid) {
        clips = newClips
        isGrid = newIsGrid
        notifyDataSetChanged()
    }

    override fun getItemViewType(position: Int): Int {
        return if (isGrid) VIEW_TYPE_GRID else VIEW_TYPE_LIST
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ClipViewHolder {
        val layoutRes = if (viewType == VIEW_TYPE_GRID) R.layout.item_channel_clip else R.layout.item_browse_clip_list
        val view = LayoutInflater.from(parent.context).inflate(layoutRes, parent, false)
        
        // Add margins for grid view to match Live Streams (6dp all sides)
        if (viewType == VIEW_TYPE_GRID) {
            val params = view.layoutParams as? androidx.recyclerview.widget.RecyclerView.LayoutParams
                ?: androidx.recyclerview.widget.RecyclerView.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, 
                    ViewGroup.LayoutParams.WRAP_CONTENT
                )
            val density = parent.context.resources.displayMetrics.density
            val margin = (6 * density).toInt()
            params.marginStart = margin
            params.marginEnd = margin
            params.topMargin = margin
            params.bottomMargin = margin
            view.layoutParams = params
        }
        
        return ClipViewHolder(view)
    }

    companion object {
        private const val VIEW_TYPE_GRID = 0
        private const val VIEW_TYPE_LIST = 1
    }

    override fun onBindViewHolder(holder: ClipViewHolder, position: Int) {
        holder.bind(clips[position])
    }

    override fun getItemCount(): Int = clips.size

    inner class ClipViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val thumbnail: ImageView = itemView.findViewById(R.id.clipThumbnail)
        private val durationBadge: TextView = itemView.findViewById(R.id.durationBadge)
        private val title: TextView = itemView.findViewById(R.id.clipTitle)
        private val stats: TextView = itemView.findViewById(R.id.clipStats)
        
        // Grid unique
        private val date: TextView? = itemView.findViewById(R.id.clipDate) 

        // List unique
        private val channelAvatar: ImageView? = itemView.findViewById(R.id.channelAvatar)
        private val channelName: TextView? = itemView.findViewById(R.id.channelName)
        private val matureBadge: TextView? = itemView.findViewById(R.id.matureBadge)

        fun bind(clip: ChannelClip) {
            title.text = clip.title ?: itemView.context.getString(R.string.untitled_clip)
            
            // Thumbnail with shimmer placeholder
            val thumbUrl = clip.thumbnailUrl
            val shimmerPlaceholder = ShimmerDrawable(isCircle = false)
            val defaultThumbnailBuilder = Glide.with(itemView.context)
                .load(Constants.Urls.DEFAULT_LIVESTREAM_THUMBNAIL)
                .transform(CenterCrop())

            Glide.with(itemView.context)
                .load(thumbUrl)
                .transform(CenterCrop())
                .placeholder(shimmerPlaceholder)
                .error(defaultThumbnailBuilder)
                .transition(com.bumptech.glide.load.resource.drawable.DrawableTransitionOptions.withCrossFade(150))
                .into(thumbnail)


            // Duration
            val seconds = clip.duration ?: 0
            if (seconds > 0) {
                 durationBadge.text = if (seconds >= 60) String.format("%d:%02d", seconds / 60, seconds % 60) else "${seconds}s"
                 durationBadge.visibility = View.VISIBLE
            } else {
                 durationBadge.visibility = View.GONE
            }
            
            // Stats & Date
            val views = clip.views ?: 0
            val viewsText = if (views >= 1000) String.format("%.1fK", views / 1000.0) else "$views"
            val timeAgo = formatDate(clip.createdAt)
            val creatorName = clip.creator?.username ?: ""

            // Both grid and list now show similar stats format
            val suffix = itemView.context.getString(R.string.view_count_suffix)
            val statsText = if (creatorName.isNotEmpty()) {
                "$viewsText $suffix • $timeAgo • $creatorName"
            } else {
                "$viewsText $suffix • $timeAgo"
            }
            stats.text = statsText
            date?.visibility = View.GONE
            
            // Channel Info (List mode)
            channelName?.text = clip.channel?.username ?: itemView.context.getString(R.string.unknown_channel)
            if (channelAvatar != null && clip.channel != null) {
                val avatarUrl = clip.channel.getEffectiveProfilePicUrl()
                Glide.with(itemView.context)
                    .load(avatarUrl)
                    .circleCrop()
                    .placeholder(R.drawable.default_avatar)
                    .error(R.drawable.default_avatar)
                    .into(channelAvatar)
            }


            // Mature badge if available
            matureBadge?.visibility = View.GONE // Hidden by default
            
            itemView.setOnClickListener { onItemClick(clip) }
            itemView.setOnLongClickListener { 
                onItemLongClick?.invoke(clip)
                true
            }
        }

        private fun formatDate(dateString: String?): String {
            if (dateString == null) return ""
            try {
                val sdf = java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", java.util.Locale.US)
                sdf.timeZone = java.util.TimeZone.getTimeZone("UTC")
                val date = sdf.parse(dateString) ?: return ""
                val now = System.currentTimeMillis()
                val diff = now - date.time
                
                val seconds = diff / 1000
                val minutes = seconds / 60
                val hours = minutes / 60
                val days = hours / 24
                
                return when {
                    days > 365 -> itemView.context.getString(R.string.time_ago_years, days / 365)
                    days > 30 -> itemView.context.getString(R.string.time_ago_months, days / 30)
                    days > 0 -> itemView.context.getString(R.string.time_ago_days, days)
                    hours > 0 -> itemView.context.getString(R.string.time_ago_hours, hours)
                    minutes > 0 -> itemView.context.getString(R.string.time_ago_minutes, minutes)
                    else -> itemView.context.getString(R.string.time_ago_just_now)
                }
            } catch (e: Exception) {
                return itemView.context.getString(R.string.no_date)
            }
        }
    }
}
