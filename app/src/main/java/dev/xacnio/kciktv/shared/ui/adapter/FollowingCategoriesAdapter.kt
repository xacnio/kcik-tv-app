/**
 * File: FollowingCategoriesAdapter.kt
 *
 * Description: RecyclerView Adapter for displaying Following Categories lists.
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
import com.bumptech.glide.load.engine.DiskCacheStrategy
import com.bumptech.glide.load.resource.drawable.DrawableTransitionOptions
import dev.xacnio.kciktv.R
import dev.xacnio.kciktv.shared.data.model.TopCategory
import dev.xacnio.kciktv.shared.util.CategoryUtils
import dev.xacnio.kciktv.shared.util.Constants
import dev.xacnio.kciktv.shared.util.FormatUtils
import dev.xacnio.kciktv.shared.util.ShimmerDrawable
import jp.wasabeef.glide.transformations.BlurTransformation
import java.util.Locale

class FollowingCategoriesAdapter(
    private var categories: List<TopCategory>,
    private val onCategoryClick: (TopCategory) -> Unit
) : RecyclerView.Adapter<FollowingCategoriesAdapter.ViewHolder>() {

    class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val image: ImageView = view.findViewById(R.id.categoryImage)
        val blurredBg: ImageView = view.findViewById(R.id.categoryBlurredBg)
        val name: TextView = view.findViewById(R.id.categoryName)
        val viewers: TextView = view.findViewById(R.id.categoryViewers)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_home_category, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val category = categories[position]
        val context = holder.itemView.context
        
        // Localized Name
        holder.name.text = CategoryUtils.getLocalizedCategoryName(
            context, 
            category.name, 
            category.slug
        )
        
        // Localized Viewers
        holder.viewers.text = context.getString(
            R.string.viewer_count_label, 
            FormatUtils.formatViewerCount(category.viewers)
        )
        
        val url = category.banner?.src
        val defaultThumb = Constants.Urls.DEFAULT_LIVESTREAM_THUMBNAIL
        
        // Main Image
        Glide.with(context)
            .load(url ?: defaultThumb)
            .placeholder(ShimmerDrawable(isCircle = false))
            .thumbnail(Glide.with(context).load(url ?: defaultThumb).override(50))
            .error(Glide.with(context).load(defaultThumb))
            .transition(DrawableTransitionOptions.withCrossFade())
            .into(holder.image)

        // Blurred Background
        Glide.with(context)
            .load(url ?: defaultThumb)
            .transform(BlurTransformation(25, 3))
            .error(Glide.with(context).load(defaultThumb).transform(BlurTransformation(25, 3)))
            .transition(DrawableTransitionOptions.withCrossFade())
            .into(holder.blurredBg)
            
        holder.itemView.setOnClickListener { onCategoryClick(category) }
    }

    override fun getItemCount() = categories.size
    
    fun updateData(newCategories: List<TopCategory>) {
        categories = newCategories
        notifyDataSetChanged()
    }
    
}
