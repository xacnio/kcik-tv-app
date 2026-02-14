/**
 * File: ChannelVideoAdapter.kt
 *
 * Description: RecyclerView Adapter for displaying Channel Video lists.
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
import dev.xacnio.kciktv.shared.data.model.ListChannelVideo
import dev.xacnio.kciktv.shared.data.prefs.AppPreferences
import dev.xacnio.kciktv.mobile.ui.player.VodManager
import dev.xacnio.kciktv.shared.util.Constants

class ChannelVideoAdapter(
    private var videos: List<ListChannelVideo>,
    private val prefs: dev.xacnio.kciktv.shared.data.prefs.AppPreferences,
    private val onItemClick: (ListChannelVideo) -> Unit
) : RecyclerView.Adapter<ChannelVideoAdapter.VideoViewHolder>() {

    fun updateData(newVideos: List<ListChannelVideo>) {
        videos = newVideos
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VideoViewHolder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_channel_video, parent, false)
        return VideoViewHolder(view)
    }

    override fun onBindViewHolder(holder: VideoViewHolder, position: Int) {
        holder.bind(videos[position])
    }

    override fun getItemCount(): Int = videos.size

    inner class VideoViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val thumbnail: ImageView = itemView.findViewById(R.id.videoThumbnail)
        private val durationBadge: TextView = itemView.findViewById(R.id.durationBadge)
        private val liveBadge: TextView = itemView.findViewById(R.id.liveBadge)
        private val title: TextView = itemView.findViewById(R.id.videoTitle)
        private val category: TextView = itemView.findViewById(R.id.videoCategory)
        private val stats: TextView = itemView.findViewById(R.id.videoStats)
        private val progressContainer: android.widget.LinearLayout = itemView.findViewById(R.id.progressContainer)
        private val progressBar: android.widget.ProgressBar = itemView.findViewById(R.id.watchProgressBar)
        private val continueLabel: TextView = itemView.findViewById(R.id.continueWatchingLabel)

        fun bind(video: ListChannelVideo) {
            title.text = video.sessionTitle ?: itemView.context.getString(R.string.untitled_video)
            
            // Check watch progress
            // Prefer UUID just like VodManager saves it
            val videoId = video.uuid ?: video.id.toString()
            val progress = prefs.getVodProgress(videoId)
            
            if (progress != null && progress.duration > 0 && progress.watchedDuration > 0) {
                 progressContainer.visibility = View.VISIBLE
                 val percentage = ((progress.watchedDuration.toFloat() / progress.duration) * 1000).toInt()
                 progressBar.progress = percentage
                 progressBar.max = 1000
                 durationBadge.visibility = View.GONE // Hide duration if showing progress? Or keep it? Usually hide or move.
                 // Actually keeping duration is fine, but maybe redundant if bar shows remaining.
                 // User said "seek bar?" so they want the bar.
            } else {
                 progressContainer.visibility = View.GONE
            }
            
            // Category
            val catName = video.categories?.firstOrNull()?.name
            if (catName != null) {
                category.text = catName
                category.visibility = View.VISIBLE
            } else {
                category.visibility = View.GONE
            }

            // Thumbnail
            val thumbUrl = video.thumbnail?.src
            val defaultThumbnailBuilder = Glide.with(itemView.context)
                .load(Constants.Urls.DEFAULT_LIVESTREAM_THUMBNAIL)
                .transform(CenterCrop())

            if (!thumbUrl.isNullOrEmpty()) {
                Glide.with(itemView.context)
                    .load(thumbUrl)
                    .transform(CenterCrop())
                    .error(defaultThumbnailBuilder)
                    .into(thumbnail)
            } else {
                defaultThumbnailBuilder.into(thumbnail)
            }

            // Live / Duration
            if (video.isLive == true) {
                liveBadge.visibility = View.VISIBLE
                durationBadge.visibility = View.GONE
            } else {
                liveBadge.visibility = View.GONE
                // Always show duration
                durationBadge.visibility = View.VISIBLE
                val ms = video.duration ?: 0L
                durationBadge.text = formatDuration(ms)
            }
            
            // Stats
            val views = video.views ?: video.viewerCount ?: 0
            val suffix = itemView.context.getString(R.string.view_count_suffix)
            val viewsText = if (views >= 1000) String.format("%.1fK %s", views / 1000.0, suffix) else "$views $suffix"
            
            // Date formatting
            val dateText = formatDate(video.createdAt)
            
            stats.text = "$viewsText • $dateText"
            
            itemView.setOnClickListener { onItemClick(video) }
        }
        
        private fun formatDuration(ms: Long): String {
            val seconds = ms / 1000
            val h = seconds / 3600
            val m = (seconds % 3600) / 60
            val s = seconds % 60
            return if (h > 0) {
                String.format("%d:%02d:%02d", h, m, s)
            } else {
                String.format("%d:%02d", m, s)
            }
        }

        private fun formatDate(dateString: String?): String {
            if (dateString == null) return ""
            try {
                // Try format from sample: "2026-01-22 14:18:29"
                val sdf = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.US)
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
                return ""
            }
        }
    }
}
