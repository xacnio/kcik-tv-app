/**
 * File: QuickEmoteAdapter.kt
 *
 * Description: RecyclerView Adapter for displaying Quick Emote lists.
 *
 *  Loading strategy:
 *    - onFirstImageReady fires as soon as the first bitmap/drawable in the current
 *      batch arrives on the main thread.  QuickEmoteBarManager uses this to keep
 *      the shimmer visible until real content is ready (INVISIBLE RecyclerView trick).
 *    - Per-slot: emotePlaceholder View is separate from ImageView so Glide's internal
 *      ImageView.clear() cannot make it disappear.  Generation counter prevents stale
 *      callbacks from a recycled holder from hiding a fresh placeholder.
 *    - Glide listener returns true (handled) so we set the bitmap ourselves before
 *      making the ImageView visible — zero gap between hide-placeholder and show-image.
 *
 * Author: Xacnio
 *
 */
package dev.xacnio.kciktv.shared.ui.adapter

import android.graphics.Bitmap
import android.graphics.drawable.Drawable
import android.graphics.drawable.Animatable
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.bumptech.glide.load.DataSource
import com.bumptech.glide.load.engine.GlideException
import com.bumptech.glide.request.RequestListener
import com.bumptech.glide.request.target.Target
import dev.xacnio.kciktv.R
import dev.xacnio.kciktv.shared.data.model.Emote
import dev.xacnio.kciktv.shared.ui.utils.EmoteManager

class QuickEmoteAdapter(
    private val onEmoteClick: (Emote) -> Unit
) : RecyclerView.Adapter<QuickEmoteAdapter.ViewHolder>() {

    private val emotes = mutableListOf<Emote>()
    private var isSubscribedToCurrentChannel: Boolean = false
    private var currentChannelId: Long? = null

    /**
     * Fires on the main thread when the reveal threshold of the current emote batch
     * finishes loading.  Used by QuickEmoteBarManager to hide the shimmer and
     * reveal the RecyclerView exactly when there is something to show.
     */
    var onFirstImageReady: (() -> Unit)? = null
    private var firstImageFired = false
    private var imagesLoadedCount = 0
    private var revealThreshold = 6

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
        firstImageFired = false          // reset for the new batch
        imagesLoadedCount = 0
        revealThreshold = Math.min(newEmotes.size, 6)
        emotes.clear()
        emotes.addAll(newEmotes)
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_quick_emote, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        holder.bind(emotes[position])
    }

    override fun getItemCount(): Int = emotes.size

    override fun onViewRecycled(holder: ViewHolder) {
        holder.invalidate()
        EmoteManager.unregisterViewer(holder.emoteImage)
        Glide.with(holder.emoteImage).clear(holder.emoteImage)
        holder.showPlaceholder()
        super.onViewRecycled(holder)
    }

    private fun canUseEmote(emote: Emote): Boolean {
        if (!emote.subscribersOnly) return true
        val emoteChannelId = emote.channelId
        if (emoteChannelId != null && currentChannelId != null && emoteChannelId == currentChannelId) {
            return isSubscribedToCurrentChannel
        }
        return true
    }

    inner class ViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        val emoteImage: ImageView = itemView.findViewById(R.id.emoteImage)
        private val emotePlaceholder: View = itemView.findViewById(R.id.emotePlaceholder)

        private var generation = 0
        fun invalidate() { generation++ }

        fun showPlaceholder() {
            emotePlaceholder.visibility = View.VISIBLE
            emoteImage.visibility = View.INVISIBLE
        }

        private fun revealImage(gen: Int) {
            if (gen != generation) return
            emotePlaceholder.visibility = View.GONE
            emoteImage.visibility = View.VISIBLE
            
            imagesLoadedCount++
            // Notify the manager that the threshold number of images is loaded.
            if (imagesLoadedCount >= revealThreshold) {
                if (!firstImageFired) {
                    firstImageFired = true
                    onFirstImageReady?.invoke()
                }
            }
        }

        fun bind(emote: Emote) {
            generation++
            val myGen = generation
            showPlaceholder()

            val size = (24 * itemView.context.resources.displayMetrics.density).toInt()
            val canUse = canUseEmote(emote)
            emoteImage.alpha = if (canUse) 1.0f else 0.4f

            if (EmoteManager.quickPanelEmotesAnimated) {
                EmoteManager.loadSynchronizedEmote(
                    itemView.context,
                    emote.id.toString(),
                    size,
                    emoteImage
                ) { sharedDrawable ->
                    if (myGen == generation) {
                        emoteImage.setImageDrawable(sharedDrawable)
                    }
                    revealImage(myGen)
                }
            } else {
                EmoteManager.unregisterViewer(emoteImage)
                val url = EmoteManager.emoteUrl(emote.id.toString())
                val glideUrl = com.bumptech.glide.load.model.GlideUrl(
                    url,
                    com.bumptech.glide.load.model.LazyHeaders.Builder()
                        .addHeader("User-Agent", dev.xacnio.kciktv.shared.util.Constants.USER_AGENT)
                        .build()
                )
                Glide.with(emoteImage)
                    .asDrawable()
                    .load(glideUrl)
                    .dontAnimate()
                    .signature(com.bumptech.glide.signature.ObjectKey("v12_shared_sync_${emote.id}"))
                    .diskCacheStrategy(com.bumptech.glide.load.engine.DiskCacheStrategy.DATA)
                    .listener(object : RequestListener<Drawable> {
                        override fun onResourceReady(
                            resource: Drawable,
                            model: Any,
                            target: Target<Drawable>?,
                            dataSource: DataSource,
                            isFirstResource: Boolean
                        ): Boolean {
                            if (resource is Animatable) {
                                resource.stop()
                            }
                            emoteImage.setImageDrawable(resource)
                            revealImage(myGen)
                            return true
                        }

                        override fun onLoadFailed(
                            e: GlideException?,
                            model: Any?,
                            target: Target<Drawable>,
                            isFirstResource: Boolean
                        ): Boolean {
                            revealImage(myGen)
                            return false
                        }
                    })
                    .into(emoteImage)
            }

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
