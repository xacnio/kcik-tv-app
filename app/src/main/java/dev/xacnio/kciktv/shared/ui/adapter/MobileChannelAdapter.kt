/**
 * File: MobileChannelAdapter.kt
 *
 * Description: RecyclerView Adapter for displaying Mobile Channel lists.
 *
 * Author: Xacnio
 *
 */
package dev.xacnio.kciktv.shared.ui.adapter

import android.content.Context
import android.graphics.Color
import android.text.TextUtils
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.bumptech.glide.load.engine.DiskCacheStrategy
import com.google.android.material.card.MaterialCardView
import dev.xacnio.kciktv.R
import dev.xacnio.kciktv.shared.data.model.ChannelItem
import dev.xacnio.kciktv.shared.util.CategoryUtils
import dev.xacnio.kciktv.shared.util.Constants
import dev.xacnio.kciktv.shared.util.ShimmerDrawable
import dev.xacnio.kciktv.shared.util.ThumbnailCacheHelper
import java.util.Locale

/**
 * Adapter for channel list in bottom sheet with shimmer loading states.
 */
class MobileChannelAdapter(
    private val context: Context,
    private val channels: List<ChannelItem>,
    private var selectedSlug: String?,
    private val isGrid: Boolean,
    private val themeColor: Int,
    private val formatViewerCount: (Long) -> String,
    private val getLanguageName: (String?) -> String,
    private var isLoading: Boolean = false,
    private var isLoadingMore: Boolean = false,
    private val onItemClick: (Int) -> Unit,
    private val onProfileClick: ((ChannelItem) -> Unit)? = null
) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

    private val VIEW_TYPE_ITEM = 0
    private val VIEW_TYPE_SHIMMER = 1

    fun setIsLoading(loading: Boolean) {
        if (this.isLoading != loading) {
            this.isLoading = loading
            notifyDataSetChanged()
        }
    }

    fun setIsLoadingMore(loadingMore: Boolean) {
        if (this.isLoadingMore != loadingMore) {
            this.isLoadingMore = loadingMore
            notifyDataSetChanged()
        }
    }

    override fun getItemViewType(position: Int): Int {
        return if (isLoading || (isLoadingMore && position >= channels.size)) VIEW_TYPE_SHIMMER else VIEW_TYPE_ITEM
    }

    inner class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val thumbnail: ImageView? = view.findViewById(R.id.channelThumbnail)
        val avatar: ImageView = view.findViewById(R.id.channelProfilePic)
        val blurredBackground: ImageView? = view.findViewById(R.id.blurredBackground)
        val name: TextView = view.findViewById(R.id.channelUsername)
        val verifiedBadge: ImageView = view.findViewById(R.id.verifiedBadge)
        val title: TextView = view.findViewById(R.id.channelStreamerName)
        val viewers: TextView = view.findViewById(R.id.viewerCount)
        val category: TextView = view.findViewById(R.id.channelCategory)
        val uptime: TextView? = view.findViewById(R.id.uptime)
        val matureBadge: TextView? = view.findViewById(R.id.matureBadge)
        val languageTag: TextView? = view.findViewById(R.id.languageTag)
        val tagsContainer: LinearLayout? = view.findViewById(R.id.tagsContainer)
        val container: View = view
    }

    inner class ShimmerViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val thumbnail: View? = view.findViewById(R.id.shimmerThumbnail)
        val avatar: View? = view.findViewById(R.id.shimmerAvatar)
        val name: View? = view.findViewById(R.id.shimmerName)
        val title: View? = view.findViewById(R.id.shimmerTitle)
        val category: View? = view.findViewById(R.id.shimmerCategory)

        init {
            thumbnail?.background = ShimmerDrawable(false)
            avatar?.background = ShimmerDrawable(true)
            name?.background = ShimmerDrawable(false)
            title?.background = ShimmerDrawable(false)
            category?.background = ShimmerDrawable(false)
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        return if (viewType == VIEW_TYPE_SHIMMER) {
            val layoutRes = if (isGrid) R.layout.item_shimmer_mobile_channel_grid else R.layout.item_shimmer_mobile_channel
            val view = LayoutInflater.from(parent.context)
                .inflate(layoutRes, parent, false)
            ShimmerViewHolder(view)
        } else {
            val layoutRes = if (isGrid) R.layout.item_mobile_channel_grid else R.layout.item_mobile_channel
            val view = LayoutInflater.from(parent.context)
                .inflate(layoutRes, parent, false)
            ViewHolder(view)
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        if (holder is ShimmerViewHolder) return

        val vh = holder as ViewHolder
        val channel = channels[position]

        vh.name.text = channel.username
        vh.verifiedBadge.visibility = if (channel.verified) View.VISIBLE else View.GONE
        vh.title.text = channel.title ?: channel.username
        vh.viewers.text = formatViewerCount(channel.viewerCount.toLong())

        // Use localized category name
        val localizedCategory = CategoryUtils.getLocalizedCategoryName(
            vh.category.context,
            channel.categoryName,
            channel.categorySlug
        )
        vh.category.text = localizedCategory
        vh.category.setTextColor(themeColor)

        // Load avatar
        val avatarUrl = channel.getEffectiveProfilePicUrl()
        if (vh.avatar.tag != avatarUrl) {
            vh.avatar.tag = avatarUrl
            Glide.with(vh.avatar.context)
                .load(avatarUrl)
                .circleCrop()
                .placeholder(ShimmerDrawable())
                .thumbnail(Glide.with(vh.avatar.context).load(avatarUrl).override(100))
                .into(vh.avatar)
        }

        // Load blurred background avatar
        vh.blurredBackground?.let { bgView ->
            if (bgView.tag != avatarUrl) {
                bgView.tag = avatarUrl
                Glide.with(bgView.context)
                    .load(avatarUrl)
                    .centerCrop()
                    .into(bgView)
            }
        }

        // Load thumbnail
        vh.thumbnail?.let { imageView ->
            val thumbnailUrl = channel.thumbnailUrl
            val thumbToLoad = thumbnailUrl ?: Constants.Urls.DEFAULT_LIVESTREAM_THUMBNAIL
            
            if (imageView.tag != thumbToLoad) {
                imageView.tag = thumbToLoad
                val defaultThumbnailBuilder = Glide.with(imageView.context)
                    .load(Constants.Urls.DEFAULT_LIVESTREAM_THUMBNAIL)
                    .fitCenter()

                if (!thumbnailUrl.isNullOrEmpty()) {
                    val requestBuilder = Glide.with(imageView.context)
                        .load(thumbnailUrl)
                        .signature(ThumbnailCacheHelper.getCacheSignature())
                        .placeholder(ShimmerDrawable(isCircle = false))
                        .diskCacheStrategy(DiskCacheStrategy.AUTOMATIC)
                        .error(defaultThumbnailBuilder)

                    // Always use fitCenter as requested universally
                    requestBuilder.fitCenter()
                    imageView.setBackgroundColor(Color.BLACK)

                    requestBuilder
                        .thumbnail(Glide.with(imageView.context).load(thumbnailUrl).override(50))
                        .into(imageView)
                } else {
                    defaultThumbnailBuilder.into(imageView)
                }
            }
        }

        // Show mature badge
        vh.matureBadge?.visibility = if (channel.isMature) View.VISIBLE else View.GONE

        // Show language tag
        vh.languageTag?.let { langView ->
            val langCode = channel.language
            if (!langCode.isNullOrEmpty()) {
                langView.visibility = View.VISIBLE
                langView.text = getLanguageName(langCode)
            } else {
                langView.visibility = View.GONE
            }
        }

        // Show tags
        vh.tagsContainer?.let { container ->
            container.removeAllViews()
            channel.tags?.take(2)?.forEach { tag: String ->
                val tagView = TextView(container.context).apply {
                    text = tag
                    setTextColor(0xFFAAAAAA.toInt())
                    textSize = 10f
                    setPadding(12, 6, 12, 6)
                    setBackgroundResource(R.drawable.bg_language_tag)
                    layoutParams = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.WRAP_CONTENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT
                    ).apply {
                        marginStart = 8
                    }
                }
                container.addView(tagView)
            }
        }

        // Hide uptime for now (startedAt not available in ChannelItem)
        vh.uptime?.visibility = View.GONE

        // Highlight selected channel with border
        val cardView = vh.container as? MaterialCardView
        cardView?.let { cv ->
            if (channel.slug == selectedSlug) {
                cv.strokeWidth = (2 * cv.context.resources.displayMetrics.density).toInt()
                cv.strokeColor = themeColor
                // Derive a subtle background tint from theme color
                val bgColor = Color.argb(
                    40,
                    Color.red(themeColor),
                    Color.green(themeColor),
                    Color.blue(themeColor)
                )
                cv.setCardBackgroundColor(bgColor)
            } else {
                cv.strokeWidth = 0
                cv.setCardBackgroundColor(0xFF1a1a1a.toInt())
            }
        }

        // Setup marquee on hold
        vh.title.ellipsize = TextUtils.TruncateAt.MARQUEE
        vh.title.isSingleLine = true
        vh.title.marqueeRepeatLimit = -1
        vh.title.isSelected = false // Reset state

        vh.container.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    vh.title.isSelected = true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    vh.title.isSelected = false
                }
            }
            false
        }

        vh.container.setOnClickListener {
            onItemClick(position)
        }
        
        // Avatar click opens channel profile instead of stream
        vh.avatar.setOnClickListener {
            onProfileClick?.invoke(channel) ?: onItemClick(position)
        }
    }

    override fun getItemCount(): Int {
        return if (isLoading) 12 // Show 12 shimmer items while loading
        else if (isLoadingMore) channels.size + 4 // Show 4 more shimmers while loading more
        else channels.size
    }

    fun updateSelection(newSlug: String?) {
        val oldSlug = selectedSlug
        selectedSlug = newSlug

        // Find positions and update
        val oldPos = channels.indexOfFirst { it.slug == oldSlug }
        val newPos = channels.indexOfFirst { it.slug == newSlug }

        if (oldPos != -1) notifyItemChanged(oldPos)
        if (newPos != -1) notifyItemChanged(newPos)
    }

}
