/**
 * File: SearchHistoryAdapter.kt
 *
 * Description: RecyclerView Adapter for displaying Search History lists.
 *
 * Author: Xacnio
 *
 */
package dev.xacnio.kciktv.shared.ui.adapter

import android.content.res.ColorStateList
import android.graphics.Color
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import dev.xacnio.kciktv.R
import dev.xacnio.kciktv.shared.data.prefs.AppPreferences
import dev.xacnio.kciktv.shared.util.CategoryUtils

class SearchHistoryAdapter(
    private val items: List<AppPreferences.HistoryEntry>,
    private val onClick: (AppPreferences.HistoryEntry) -> Unit
) : RecyclerView.Adapter<SearchHistoryAdapter.SearchHistoryViewHolder>() {

    class SearchHistoryViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val text: TextView = view.findViewById(R.id.historyText)
        val icon: ImageView = view.findViewById(R.id.historyIcon)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): SearchHistoryViewHolder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_search_history, parent, false)
        return SearchHistoryViewHolder(view)
    }

    override fun onBindViewHolder(holder: SearchHistoryViewHolder, position: Int) {
        val entry = items[position]
        
        when (entry.type) {
            "query" -> {
                holder.text.text = entry.query
                holder.icon.setImageResource(R.drawable.ic_update)
                holder.icon.imageTintList = ColorStateList.valueOf(Color.parseColor("#555555"))
            }
            "channel" -> {
                val channel = entry.channelItem
                holder.text.text = channel?.username
                holder.icon.imageTintList = null
                holder.icon.setImageResource(R.drawable.ic_person)
                holder.icon.imageTintList = ColorStateList.valueOf(Color.parseColor("#53FC18"))
            }
            "category" -> {
                val category = entry.categoryItem
                holder.text.text = category?.name
                holder.icon.imageTintList = null
                
                if (!category?.imageUrl.isNullOrEmpty()) {
                     Glide.with(holder.itemView.context)
                        .load(category?.imageUrl)
                        .circleCrop()
                        .placeholder(dev.xacnio.kciktv.shared.util.CategoryUtils.getCategoryIcon(category?.slug))
                        .into(holder.icon)
                } else {
                    holder.icon.setImageResource(dev.xacnio.kciktv.shared.util.CategoryUtils.getCategoryIcon(category?.slug))
                    holder.icon.imageTintList = ColorStateList.valueOf(Color.parseColor("#888888"))
                }
            }
        }
        
        holder.itemView.setOnClickListener { onClick(entry) }
    }

    override fun getItemCount() = items.size
}
