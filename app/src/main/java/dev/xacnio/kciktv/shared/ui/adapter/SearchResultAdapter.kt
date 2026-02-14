/**
 * File: SearchResultAdapter.kt
 *
 * Description: RecyclerView Adapter for displaying Search Result lists.
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
import dev.xacnio.kciktv.shared.data.model.SearchResultItem
import dev.xacnio.kciktv.shared.util.Constants

class SearchResultAdapter(
    private val items: List<SearchResultItem>,
    private val themeColor: Int = 0xFF53FC18.toInt(), // Default green
    private val onItemClick: (SearchResultItem) -> Unit
) : RecyclerView.Adapter<SearchResultAdapter.ViewHolder>() {

    inner class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val icon: ImageView = view.findViewById(R.id.resultIcon)
        val title: TextView = view.findViewById(R.id.resultTitle)
        val subtitle: TextView = view.findViewById(R.id.resultSubtitle)
        val typeTag: TextView = view.findViewById(R.id.resultType)
        val verifiedBadge: ImageView = view.findViewById(R.id.verifiedBadge)
        val liveIndicator: View = view.findViewById(R.id.liveIndicator)
        val backgroundImage: ImageView = view.findViewById(R.id.resultBackgroundImage)
        val backgroundOverlay: View = view.findViewById(R.id.resultBackgroundOverlay)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_search_result, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val item = items[position]
        
        // Reset background
        holder.backgroundImage.visibility = View.GONE
        holder.backgroundOverlay.visibility = View.GONE
        
        when (item) {
            is SearchResultItem.ChannelResult -> {
                holder.title.text = item.username
                holder.subtitle.text = formatFollowerCount(item.followersCount) + 
                    if (item.isLive) " • LIVE" else ""
                holder.typeTag.text = holder.itemView.context.getString(R.string.type_channel)
                holder.typeTag.setTextColor(themeColor)
                holder.verifiedBadge.visibility = if (item.verified) View.VISIBLE else View.GONE
                holder.liveIndicator.visibility = if (item.isLive) View.VISIBLE else View.GONE
            }
            is SearchResultItem.CategoryResult -> {
                holder.title.text = item.name
                holder.subtitle.text = item.parent?.replaceFirstChar { it.uppercase() } ?: "Category"
                holder.typeTag.text = holder.itemView.context.getString(R.string.type_category)
                holder.typeTag.setTextColor(0xFFFFAA00.toInt())
                holder.verifiedBadge.visibility = View.GONE
                holder.liveIndicator.visibility = View.GONE
                
                // Load background image for category
                val imageUrl = item.imageUrl
                val defaultThumb = Constants.Urls.DEFAULT_LIVESTREAM_THUMBNAIL
                
                holder.backgroundImage.visibility = View.VISIBLE
                holder.backgroundOverlay.visibility = View.VISIBLE
                
                Glide.with(holder.backgroundImage.context)
                    .load(imageUrl ?: defaultThumb)
                    .centerCrop()
                    .error(Glide.with(holder.backgroundImage.context).load(defaultThumb).centerCrop())
                    .into(holder.backgroundImage)
            }
            is SearchResultItem.TagResult -> {
                holder.title.text = "#${item.label}"
                holder.subtitle.text = holder.itemView.context.getString(R.string.type_tag)
                holder.typeTag.text = holder.itemView.context.getString(R.string.type_tag)
                holder.typeTag.setTextColor(0xFF9966FF.toInt())
                holder.verifiedBadge.visibility = View.GONE
                holder.liveIndicator.visibility = View.GONE
            }
        }
        
        holder.itemView.setOnClickListener {
            onItemClick(item)
        }
    }

    override fun getItemCount() = items.size
    
    private fun formatFollowerCount(count: Int): String {
        return when {
            count >= 1_000_000 -> String.format("%.1fM followers", count / 1_000_000.0)
            count >= 1_000 -> String.format("%.1fK followers", count / 1_000.0)
            else -> "$count followers"
        }
    }
}
