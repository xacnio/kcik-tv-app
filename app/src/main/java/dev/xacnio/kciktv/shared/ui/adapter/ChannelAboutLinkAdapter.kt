/**
 * File: ChannelAboutLinkAdapter.kt
 *
 * Description: RecyclerView Adapter for displaying Channel About Link lists.
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
import dev.xacnio.kciktv.R
import dev.xacnio.kciktv.shared.data.model.ChannelLink
import dev.xacnio.kciktv.mobile.util.DialogUtils
import dev.xacnio.kciktv.mobile.util.MarkdownUtils

class ChannelAboutLinkAdapter(
    private var links: List<ChannelLink>
) : RecyclerView.Adapter<ChannelAboutLinkAdapter.LinkViewHolder>() {

    fun updateData(newLinks: List<ChannelLink>) {
        links = newLinks
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): LinkViewHolder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_channel_about_link, parent, false)
        return LinkViewHolder(view)
    }

    override fun onBindViewHolder(holder: LinkViewHolder, position: Int) {
        holder.bind(links[position])
    }

    override fun getItemCount(): Int = links.size

    inner class LinkViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val title: TextView = itemView.findViewById(R.id.linkTitle)
        private val image: ImageView = itemView.findViewById(R.id.linkImage)
        private val description: TextView = itemView.findViewById(R.id.linkDescription)

        fun bind(link: ChannelLink) {
            if (link.title.isNullOrEmpty()) {
                title.visibility = View.GONE
            } else {
                title.text = link.title
            }

            // Image
            val imageUrl = link.image?.url
            if (!imageUrl.isNullOrEmpty()) {
                image.visibility = View.VISIBLE
                Glide.with(itemView.context)
                    .load(imageUrl)
                    .into(image)
            } else {
                image.visibility = View.GONE
            }

            // Description
            if (!link.description.isNullOrEmpty()) {
                dev.xacnio.kciktv.mobile.util.MarkdownUtils.parseAndSetMarkdown(description, link.description)
                description.visibility = View.VISIBLE
            } else {
                description.visibility = View.GONE
            }

            if(link.link.isNullOrEmpty()) {
                itemView.setOnClickListener(null)
            } else {
                itemView.setOnClickListener {
                    dev.xacnio.kciktv.mobile.util.DialogUtils.showLinkConfirmationDialog(itemView.context, link.link)
                }
            }
        }
    }
}
