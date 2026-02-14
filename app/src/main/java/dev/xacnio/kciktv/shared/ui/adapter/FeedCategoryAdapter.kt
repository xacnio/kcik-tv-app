/**
 * File: FeedCategoryAdapter.kt
 *
 * Description: RecyclerView Adapter for displaying Feed Category lists.
 *
 * Author: Xacnio
 *
 */
package dev.xacnio.kciktv.shared.ui.adapter

import android.graphics.Color
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import dev.xacnio.kciktv.R
import dev.xacnio.kciktv.databinding.ItemFeedCategoryBinding

data class FeedCategory(
    val name: String,
    val slug: String,
    var isSelected: Boolean = false
)

class FeedCategoryAdapter(
    private val onItemClick: (FeedCategory) -> Unit
) : RecyclerView.Adapter<FeedCategoryAdapter.ViewHolder>() {

    private val items = mutableListOf<FeedCategory>()

    fun submitList(newItems: List<FeedCategory>) {
        items.clear()
        items.addAll(newItems)
        notifyDataSetChanged()
    }

    fun selectCategory(slug: String) {
        items.forEachIndexed { index, item ->
            val shouldBeSelected = (item.slug == slug)
            if (item.isSelected != shouldBeSelected) {
                item.isSelected = shouldBeSelected
                notifyItemChanged(index)
            }
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val binding = ItemFeedCategoryBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return ViewHolder(binding)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        holder.bind(items[position])
    }

    override fun getItemCount() = items.size

    inner class ViewHolder(private val binding: ItemFeedCategoryBinding) : RecyclerView.ViewHolder(binding.root) {
        fun bind(item: FeedCategory) {
            binding.categoryName.text = item.name
            
            if (item.isSelected) {
                binding.categoryName.setBackgroundResource(R.drawable.bg_pill_white)
                binding.categoryName.setTextColor(Color.BLACK)
            } else {
                binding.categoryName.setBackgroundResource(R.drawable.bg_pill_dark)
                binding.categoryName.setTextColor(Color.WHITE)
            }
            
            binding.root.setOnClickListener {
                if (!item.isSelected) {
                    onItemClick(item)
                }
            }
        }
    }
}
