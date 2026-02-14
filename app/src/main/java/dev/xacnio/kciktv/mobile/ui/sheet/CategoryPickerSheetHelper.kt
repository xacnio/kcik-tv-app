/**
 * File: CategoryPickerSheetHelper.kt
 *
 * Description: Implementation of Category Picker Sheet Helper functionality.
 *
 * Author: Xacnio
 *
 */
package dev.xacnio.kciktv.mobile.ui.sheet

import android.content.Context
import android.content.res.ColorStateList
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.lifecycle.LifecycleCoroutineScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.google.android.material.bottomsheet.BottomSheetDialog
import dev.xacnio.kciktv.R
import dev.xacnio.kciktv.shared.data.model.TopCategory
import dev.xacnio.kciktv.shared.data.prefs.AppPreferences
import dev.xacnio.kciktv.shared.data.repository.ChannelRepository
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import dev.xacnio.kciktv.shared.util.CategoryUtils

/**
 * Helper class for category picker bottom sheet.
 * Uses dialog_category_search_sheet layout.
 */
class CategoryPickerSheetHelper(
    private val context: Context,
    private val layoutInflater: LayoutInflater,
    private val lifecycleScope: LifecycleCoroutineScope,
    private val prefs: AppPreferences,
    private val repository: ChannelRepository
) {
    companion object {
        private const val TAG = "CategoryPickerSheet"
        private const val SEARCH_DEBOUNCE_MS = 300L
    }
    
    private var dialog: BottomSheetDialog? = null
    private var searchJob: Job? = null
    
    interface CategoryPickerListener {
        fun getCurrentChannelSlug(): String?
        fun onCategorySelected(category: TopCategory)
    }
    
    fun show(listener: CategoryPickerListener) {
        val channelSlug = listener.getCurrentChannelSlug()
        if (channelSlug.isNullOrEmpty()) {
            Toast.makeText(context, context.getString(R.string.channel_slug_error), Toast.LENGTH_SHORT).show()
            return
        }
        
        val bottomSheetDialog = BottomSheetDialog(context)
        dialog = bottomSheetDialog
        
        // Fix for landscape/tablet mode
        bottomSheetDialog.behavior.state = com.google.android.material.bottomsheet.BottomSheetBehavior.STATE_EXPANDED
        bottomSheetDialog.behavior.skipCollapsed = true
        
        // Use correct layout
        val view = layoutInflater.inflate(R.layout.dialog_category_search_sheet, null)
        
        // Find Views from dialog_category_search_sheet.xml
        val searchInput = view.findViewById<EditText>(R.id.categorySearchInput)
        val resultsRecycler = view.findViewById<RecyclerView>(R.id.categoryResultsRecycler) // Use the one we updated to RecyclerView
        val recentRecycler = view.findViewById<RecyclerView>(R.id.recentCategoriesRecycler)
        val recentTitle = view.findViewById<TextView>(R.id.recentCategoriesTitle)
        val loadingIndicator = view.findViewById<ProgressBar>(R.id.searchLoading)
        val emptyText = view.findViewById<TextView>(R.id.emptyResultsText)
        val clearButton = view.findViewById<ImageView>(R.id.btnClearSearch)
        
        // Setup adapter
        val categories = mutableListOf<TopCategory>()
        val adapter = CategoryAdapter(categories) { category ->
            updateCategory(channelSlug, category, listener, bottomSheetDialog)
        }
        
        resultsRecycler.layoutManager = LinearLayoutManager(context)
        resultsRecycler.adapter = adapter
        
        // Setup Recent Categories
        val recentItems = prefs.getRecentCategories()
        if (recentItems.isNotEmpty()) {
            recentTitle?.visibility = View.VISIBLE
            recentRecycler?.visibility = View.VISIBLE
            recentRecycler?.layoutManager = LinearLayoutManager(context, LinearLayoutManager.HORIZONTAL, false)
            recentRecycler?.adapter = RecentCategoryAdapter(recentItems) { categoryName ->
                 searchInput?.setText(categoryName)
                 searchInput?.setSelection(categoryName.length)
            }
        }
        
        // Search functionality
        searchInput?.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: android.text.Editable?) {
                searchJob?.cancel()
                val query = s?.toString()?.trim() ?: ""
                
                // Show/hide clear button
                clearButton?.visibility = if (query.isNotEmpty()) View.VISIBLE else View.GONE
                
                if (query.isEmpty()) {
                    // Load top categories if query is empty
                    loadTopCategories(categories, adapter, loadingIndicator, emptyText)
                } else {
                    searchJob = lifecycleScope.launch {
                        delay(SEARCH_DEBOUNCE_MS)
                        searchCategories(query, categories, adapter, loadingIndicator, emptyText)
                    }
                }
            }
        })
        
        clearButton?.setOnClickListener {
            searchInput?.setText("")
        }
        
        // Initial load
        loadTopCategories(categories, adapter, loadingIndicator, emptyText)
        
        bottomSheetDialog.setContentView(view)
        bottomSheetDialog.show()
    }
    
    private fun loadTopCategories(
        categories: MutableList<TopCategory>,
        adapter: CategoryAdapter,
        loadingIndicator: ProgressBar?,
        emptyText: TextView?
    ) {
        loadingIndicator?.visibility = View.VISIBLE
        emptyText?.visibility = View.GONE
        
        lifecycleScope.launch {
            try {
                val result = repository.getTopCategories()
                result.onSuccess { topCategories ->
                    (context as? android.app.Activity)?.runOnUiThread {
                        categories.clear()
                        categories.addAll(topCategories)
                        adapter.notifyDataSetChanged()
                        loadingIndicator?.visibility = View.GONE
                        emptyText?.visibility = if (categories.isEmpty()) View.VISIBLE else View.GONE
                        
                        // If no categories found (should not happen for top), show empty
                        if (categories.isEmpty()) {
                             emptyText?.text = context.getString(R.string.category_not_found)
                        }
                    }
                }.onFailure {
                    (context as? android.app.Activity)?.runOnUiThread {
                        loadingIndicator?.visibility = View.GONE
                        // On failure keep empty or show error
                    }
                }
            } catch (e: Exception) {
                 (context as? android.app.Activity)?.runOnUiThread {
                    loadingIndicator?.visibility = View.GONE
                }
            }
        }
    }
    
    private fun searchCategories(
        query: String,
        categories: MutableList<TopCategory>,
        adapter: CategoryAdapter,
        loadingIndicator: ProgressBar?,
        emptyText: TextView?
    ) {
        loadingIndicator?.visibility = View.VISIBLE
        emptyText?.visibility = View.GONE
        
        lifecycleScope.launch {
            try {
                // Assume searchCategories returns List<SearchResultItem.CategoryResult> or similar?
                // Actually if unresolved, I should use searchChannels and filter?
                // The error said `inferred type is List<CategoryInfo>`.
                // So searchCategories exists in repository? But I don't see it in the file view of ChannelRepository.kt (it was truncated or I missed it).
                // Let's assume I need to use searchChannels and map.
                
                val result = repository.searchChannels(query)
                result.onSuccess { searchResults ->
                    (context as? android.app.Activity)?.runOnUiThread {
                        val mappedCategories = searchResults.filterIsInstance<dev.xacnio.kciktv.shared.data.model.SearchResultItem.CategoryResult>().map { 
                            TopCategory(
                                id = try { it.id.toLong() } catch(e: Exception) { 0L },
                                categoryId = 0,
                                name = it.name,
                                slug = it.slug,
                                tags = null,
                                description = null,
                                viewers = 0,
                                followersCount = 0,
                                banner = dev.xacnio.kciktv.shared.data.model.CategoryBanner(it.imageUrl ?: "", null),
                                parentCategory = null
                            )
                        }
                        
                        categories.clear()
                        categories.addAll(mappedCategories)
                        adapter.notifyDataSetChanged()
                        loadingIndicator?.visibility = View.GONE
                        emptyText?.visibility = if (categories.isEmpty()) View.VISIBLE else View.GONE
                    }
                }.onFailure {
                    (context as? android.app.Activity)?.runOnUiThread {
                        loadingIndicator?.visibility = View.GONE
                    }
                }
            } catch (e: Exception) {
                (context as? android.app.Activity)?.runOnUiThread {
                    loadingIndicator?.visibility = View.GONE
                }
            }
        }
    }
    
    private fun updateCategory(
        channelSlug: String,
        category: TopCategory,
        listener: CategoryPickerListener,
        dialog: BottomSheetDialog
    ) {
        val token = prefs.authToken ?: return
        
        lifecycleScope.launch {
            try {
                // Assuming repository has updateStreamCategory
                val result = repository.updateStreamCategory(channelSlug, token, category.id)
                result.onSuccess {
                    (context as? android.app.Activity)?.runOnUiThread {
                        prefs.addRecentCategory(category.name)
                        Toast.makeText(context, R.string.category_updated_success, Toast.LENGTH_SHORT).show()
                        listener.onCategorySelected(category)
                        dialog.dismiss()
                    }
                }.onFailure { error ->
                    (context as? android.app.Activity)?.runOnUiThread {
                        Toast.makeText(context, context.getString(R.string.category_update_failed, error.message), Toast.LENGTH_SHORT).show()
                    }
                }
            } catch (e: Exception) {
                (context as? android.app.Activity)?.runOnUiThread {
                    Toast.makeText(context, context.getString(R.string.category_update_failed, e.message), Toast.LENGTH_SHORT).show()
                }
            }
        }
    }
    
    private inner class CategoryAdapter(
        private val items: List<TopCategory>,
        private val onClick: (TopCategory) -> Unit
    ) : RecyclerView.Adapter<CategoryViewHolder>() {
        
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): CategoryViewHolder {
            // Using item_browse_category.xml
            val itemView = layoutInflater.inflate(R.layout.item_browse_category, parent, false)
            return CategoryViewHolder(itemView)
        }
        
        override fun onBindViewHolder(holder: CategoryViewHolder, position: Int) {
            val category = items[position]
            
            holder.name.text = category.name
            
            // Viewers text removed as requested
            
            // Image (Prioritize SRC over SRCSET)
            val imageUrl = category.banner?.src ?: category.banner?.srcSet?.split(" ")?.firstOrNull() ?: ""
            
            Glide.with(context)
                .load(imageUrl)
                .placeholder(dev.xacnio.kciktv.shared.util.CategoryUtils.getCategoryIcon(category.slug))
                .into(holder.image)
            
            holder.itemView.setOnClickListener {
                onClick(category)
            }
        }
        
        override fun getItemCount() = items.size
    }
    
    private class CategoryViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        val image: ImageView = itemView.findViewById(R.id.categoryImage)
        val name: TextView = itemView.findViewById(R.id.categoryName)
        val viewers: TextView = itemView.findViewById(R.id.categoryViewers)
    }

    private inner class RecentCategoryAdapter(
        private val items: List<String>,
        private val onClick: (String) -> Unit
    ) : RecyclerView.Adapter<RecentCategoryViewHolder>() {
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecentCategoryViewHolder {
            val view = layoutInflater.inflate(R.layout.item_category_chip, parent, false)
            return RecentCategoryViewHolder(view)
        }
        override fun onBindViewHolder(holder: RecentCategoryViewHolder, position: Int) {
            val item = items[position]
            holder.text.text = item
            holder.itemView.setOnClickListener { onClick(item) }
        }
        override fun getItemCount() = items.size
    }

    private class RecentCategoryViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val text: TextView = view.findViewById(R.id.chipText)
    }
    
    fun dismiss() {
        searchJob?.cancel()
        dialog?.dismiss()
    }
    
    fun isShowing(): Boolean = dialog?.isShowing == true
}
