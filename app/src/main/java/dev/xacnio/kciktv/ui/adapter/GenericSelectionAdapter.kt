package dev.xacnio.kciktv.ui.adapter

import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.InsetDrawable
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import dev.xacnio.kciktv.R

data class SelectionItem(
    val id: String,
    val title: String,
    val isSelected: Boolean = false,
    val payload: Any? = null,
    val showCheckbox: Boolean = false,
    val isHeader: Boolean = false
)

class GenericSelectionAdapter(
    private var items: List<SelectionItem>,
    private var themeColor: Int = 0xFF53FC18.toInt(),
    private var onItemFocused: ((SelectionItem) -> Unit)? = null,
    private var onItemSelected: (SelectionItem) -> Unit
) : RecyclerView.Adapter<GenericSelectionAdapter.ViewHolder>() {

    private val activeHolders = mutableSetOf<ViewHolder>()

    companion object {
        private const val TYPE_ITEM = 0
        private const val TYPE_HEADER = 1
    }

    class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val txtName: TextView = view.findViewById(R.id.txtSettingItem)
        val imgIndicator: ImageView = view.findViewById(R.id.imgIndicator)
        var currentItem: SelectionItem? = null

        fun updateVisuals(item: SelectionItem, hasFocus: Boolean, themeColor: Int) {
            if (item.isHeader) {
                // Header Styling
                itemView.background = null
                itemView.isFocusable = false
                itemView.isClickable = false
                
                txtName.setTextColor(Color.parseColor("#80FFFFFF")) // Semi-transparent white
                txtName.typeface = android.graphics.Typeface.DEFAULT_BOLD
                txtName.textSize = 12f
                txtName.isSelected = false
                
                imgIndicator.visibility = View.GONE
                
                // Adjust padding for header
                itemView.setPadding(
                    itemView.paddingLeft,
                    (24 * itemView.resources.displayMetrics.density).toInt(),
                    itemView.paddingRight,
                    (8 * itemView.resources.displayMetrics.density).toInt()
                )
                return
            }

            // Normal Item Styling
            itemView.isFocusable = true
            itemView.isClickable = true
            itemView.setPadding(
                (32 * itemView.resources.displayMetrics.density).toInt(),
                (18 * itemView.resources.displayMetrics.density).toInt(),
                (32 * itemView.resources.displayMetrics.density).toInt(),
                (18 * itemView.resources.displayMetrics.density).toInt()
            )

            val bg = GradientDrawable()
            val density = itemView.resources.displayMetrics.density
            bg.cornerRadius = 12f * density 
            
            txtName.isSelected = hasFocus
            txtName.textSize = 14f

            if (hasFocus) {
                val alphaColor = (themeColor and 0x00FFFFFF) or 0x4D000000 
                bg.setColor(alphaColor)
                bg.setStroke((2.5f * density).toInt(), themeColor)
                
                txtName.setTextColor(Color.WHITE)
                txtName.typeface = android.graphics.Typeface.DEFAULT_BOLD
                imgIndicator.setColorFilter(Color.WHITE)
                
                if (item.showCheckbox && !item.isSelected) {
                     val checkBg = GradientDrawable()
                     checkBg.cornerRadius = 4f * density
                     checkBg.setStroke((1.5f * density).toInt(), Color.WHITE)
                     imgIndicator.background = checkBg
                }
            } else {
                bg.setColor(Color.TRANSPARENT)
                bg.setStroke(0, Color.TRANSPARENT)
                
                txtName.setTextColor(if (item.isSelected) themeColor else Color.WHITE)
                txtName.typeface = android.graphics.Typeface.DEFAULT
                imgIndicator.setColorFilter(if (item.isSelected) themeColor else Color.parseColor("#44FFFFFF"))
                
                if (item.showCheckbox && !item.isSelected) {
                     val checkBg = GradientDrawable()
                     checkBg.cornerRadius = 4f * density
                     checkBg.setStroke((1.5f * density).toInt(), Color.parseColor("#44FFFFFF"))
                     imgIndicator.background = checkBg
                }
            }
            
            if (item.showCheckbox) {
                if (item.isSelected) {
                    val checkBg = GradientDrawable()
                    checkBg.cornerRadius = 4f * density
                    checkBg.setColor(if (hasFocus) Color.WHITE else themeColor)
                    imgIndicator.background = checkBg
                    imgIndicator.setImageResource(R.drawable.ic_check)
                    imgIndicator.setColorFilter(if (hasFocus) themeColor else Color.WHITE)
                    imgIndicator.setPadding((2 * density).toInt(), (2 * density).toInt(), (2 * density).toInt(), (2 * density).toInt())
                } else {
                    imgIndicator.setImageResource(0)
                    imgIndicator.setPadding(0, 0, 0, 0)
                }
                imgIndicator.visibility = View.VISIBLE
            } else {
                imgIndicator.background = null
                imgIndicator.setPadding(0, 0, 0, 0)
                if (item.isSelected) {
                    imgIndicator.setImageResource(R.drawable.circle_background)
                    imgIndicator.visibility = View.VISIBLE
                } else {
                    imgIndicator.visibility = View.GONE
                }
            }
            itemView.background = bg
        }
    }

    override fun getItemViewType(position: Int): Int {
        return if (items[position].isHeader) TYPE_HEADER else TYPE_ITEM
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_setting, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val item = items[position]
        holder.currentItem = item
        holder.txtName.text = item.title
        
        if (!item.isHeader) {
            holder.itemView.setOnFocusChangeListener { _, hasFocus -> 
                holder.updateVisuals(item, hasFocus, themeColor)
                if (hasFocus) onItemFocused?.invoke(item)
            }
            holder.itemView.setOnClickListener { onItemSelected(item) }
            holder.itemView.isFocusable = true
            holder.itemView.isFocusableInTouchMode = true
        } else {
            holder.itemView.setOnFocusChangeListener(null)
            holder.itemView.setOnClickListener(null)
            holder.itemView.isFocusable = false
            holder.itemView.isFocusableInTouchMode = false
        }
        
        holder.updateVisuals(item, holder.itemView.hasFocus(), themeColor)
    }

    override fun onViewAttachedToWindow(holder: ViewHolder) {
        super.onViewAttachedToWindow(holder)
        activeHolders.add(holder)
    }

    override fun onViewDetachedFromWindow(holder: ViewHolder) {
        super.onViewDetachedFromWindow(holder)
        activeHolders.remove(holder)
    }

    override fun getItemCount(): Int = items.size

    fun updateItems(newItems: List<SelectionItem>) {
        items = newItems
        notifyDataSetChanged()
    }

    fun updateSingleSelection(selectedId: String) {
        val oldSelectedIndex = items.indexOfFirst { it.isSelected }
        val newSelectedIndex = items.indexOfFirst { it.id == selectedId }
        
        items = items.map { it.copy(isSelected = it.id == selectedId) }
        
        if (oldSelectedIndex != -1) notifyItemChanged(oldSelectedIndex, "selection_update")
        if (newSelectedIndex != -1 && newSelectedIndex != oldSelectedIndex) notifyItemChanged(newSelectedIndex, "selection_update")
    }

    fun toggleItemSelection(id: String) {
        val index = items.indexOfFirst { it.id == id }
        if (index != -1) {
            items = items.mapIndexed { i, item -> 
                if (i == index) item.copy(isSelected = !item.isSelected) else item 
            }
            notifyItemChanged(index, "payload_toggle")
        }
    }

    private fun refreshHolders() {
        activeHolders.forEach { holder ->
            holder.currentItem?.let { item ->
                val newItem = items.find { it.id == item.id }
                if (newItem != null) {
                    holder.currentItem = newItem
                    holder.updateVisuals(newItem, holder.itemView.hasFocus(), themeColor)
                }
            }
        }
    }

    fun updateThemeColor(color: Int) {
        themeColor = color
        refreshHolders()
    }

    fun setOnItemSelectListener(listener: (SelectionItem) -> Unit) {
        this.onItemSelected = listener
    }

    fun setOnItemFocusListener(listener: ((SelectionItem) -> Unit)?) {
        this.onItemFocused = listener
    }
}
