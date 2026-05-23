package dev.xacnio.kciktv.mobile.ui.home.featured

import android.graphics.Color
import android.view.LayoutInflater
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.google.android.material.button.MaterialButton
import dev.xacnio.kciktv.R
import dev.xacnio.kciktv.mobile.MobilePlayerActivity
import dev.xacnio.kciktv.shared.data.model.ChannelItem
import dev.xacnio.kciktv.shared.util.CategoryUtils
import dev.xacnio.kciktv.shared.util.FormatUtils
import dev.xacnio.kciktv.shared.util.ShimmerDrawable
import dev.xacnio.kciktv.shared.util.ThumbnailCacheHelper
import dev.xacnio.kciktv.shared.util.Constants

class FeaturedHeroAdapter(
    private val activity: MobilePlayerActivity,
    private var channels: List<ChannelItem>,
    private val onChannelClick: (ChannelItem) -> Unit,
    private val onProfileClick: (ChannelItem) -> Unit,
    var onFirstBind: ((PageViewHolder) -> Unit)? = null,
    var onMuteToggle: ((Boolean) -> Unit)? = null,
) : RecyclerView.Adapter<FeaturedHeroAdapter.PageViewHolder>() {

    private var firstBindFired = false

    inner class PageViewHolder(parent: ViewGroup) : RecyclerView.ViewHolder(
        LayoutInflater.from(parent.context).inflate(R.layout.item_featured_hero_page, parent, false)
    ) {
        val thumbnail: ImageView = itemView.findViewById(R.id.heroThumbnail)
        val playerContainer: FrameLayout = itemView.findViewById(R.id.heroPlayerContainer)
        val viewerCount: TextView = itemView.findViewById(R.id.heroViewerCount)
        val profilePic: ImageView = itemView.findViewById(R.id.heroProfilePic)
        val username: TextView = itemView.findViewById(R.id.heroUsername)
        val category: TextView = itemView.findViewById(R.id.heroCategory)
        val title: TextView = itemView.findViewById(R.id.heroTitle)
        val watchCta: MaterialButton = itemView.findViewById(R.id.heroWatchCta)
        val tagsRow: LinearLayout = itemView.findViewById(R.id.heroTagsRow)
        val muteBtn: ImageButton = itemView.findViewById(R.id.heroMuteBtn)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) = PageViewHolder(parent)

    override fun getItemCount() = channels.size

    override fun onBindViewHolder(holder: PageViewHolder, position: Int) {
        val channel = channels[position]

        // Thumbnail
        var thumbnailUrl = channel.thumbnailUrl
        if (thumbnailUrl.isNullOrEmpty()) {
            thumbnailUrl = "https://images.kick.com/video_thumbnails/${channel.slug}/uuid/fullsize"
        }
        if (holder.thumbnail.tag != thumbnailUrl) {
            holder.thumbnail.tag = thumbnailUrl
            Glide.with(activity)
                .load(thumbnailUrl)
                .signature(ThumbnailCacheHelper.getCacheSignature())
                .placeholder(ShimmerDrawable(isCircle = false))
                .thumbnail(Glide.with(activity).load(thumbnailUrl).override(100))
                .error(
                    Glide.with(activity)
                        .load(Constants.Urls.DEFAULT_LIVESTREAM_THUMBNAIL)
                        .transform(jp.wasabeef.glide.transformations.BlurTransformation(25, 3))
                )
                .into(holder.thumbnail)
        }

        // Profile pic
        val profileUrl = channel.getEffectiveProfilePicUrl()
        if (holder.profilePic.tag != profileUrl) {
            holder.profilePic.tag = profileUrl
            Glide.with(activity)
                .load(profileUrl)
                .circleCrop()
                .placeholder(R.drawable.default_avatar)
                .into(holder.profilePic)
        }

        // Text fields
        holder.username.text = channel.username
        holder.category.text = CategoryUtils.getLocalizedCategoryName(
            activity, channel.categoryName, channel.categorySlug
        )
        holder.title.text = channel.title?.takeIf { it.isNotBlank() } ?: channel.username
        holder.viewerCount.text = FormatUtils.formatViewerCount(channel.viewerCount)

        // Tags (language + up to 2 custom tags)
        holder.tagsRow.removeAllViews()
        val tagsToShow = buildList {
            if (!channel.language.isNullOrEmpty()) {
                add(activity.getLanguageName(channel.language))
            }
            channel.tags?.take(2)?.forEach { add(it) }
        }
        tagsToShow.forEach { tag ->
            val tagView = TextView(activity).apply {
                text = tag
                setTextColor(Color.WHITE)
                textSize = 10f
                setPadding(16, 6, 16, 6)
                setBackgroundResource(R.drawable.bg_tag)
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { marginEnd = 8 }
            }
            holder.tagsRow.addView(tagView)
        }

        // Click handlers
        holder.watchCta.setOnClickListener { onChannelClick(channel) }
        holder.itemView.setOnClickListener { onChannelClick(channel) }
        holder.profilePic.setOnClickListener { onProfileClick(channel) }
        holder.muteBtn.setOnClickListener { onMuteToggle?.invoke(true) }

        if (position == 0 && !firstBindFired) {
            firstBindFired = true
            onFirstBind?.invoke(holder)
        }
    }

    fun updateChannels(newChannels: List<ChannelItem>) {
        channels = newChannels
        notifyDataSetChanged()
    }

    fun getChannel(position: Int): ChannelItem? = channels.getOrNull(position)
}
