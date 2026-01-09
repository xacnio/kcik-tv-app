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
    val showCheckbox: Boolean = false
)

class GenericSelectionAdapter(
    private var items: List<SelectionItem>,
    private var themeColor: Int = 0xFF53FC18.toInt(),
    private val onItemFocused: ((SelectionItem) -> Unit)? = null,
    private val onItemSelected: (SelectionItem) -> Unit
) : RecyclerView.Adapter<GenericSelectionAdapter.ViewHolder>() {

    private val activeHolders = mutableSetOf<ViewHolder>()

    class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val txtName: TextView = view.findViewById(R.id.txtSettingItem)
        val imgIndicator: ImageView = view.findViewById(R.id.imgIndicator)
        var currentItem: SelectionItem? = null

        fun updateVisuals(item: SelectionItem, hasFocus: Boolean, themeColor: Int) {
            val bg = GradientDrawable()
            val density = itemView.resources.displayMetrics.density
            bg.cornerRadius = 12f * density 
            
            txtName.isSelected = hasFocus

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

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_setting, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val item = items[position]
        holder.currentItem = item
        holder.txtName.text = item.title
        
        holder.itemView.setOnFocusChangeListener { _, hasFocus -> 
            holder.updateVisuals(item, hasFocus, themeColor)
            if (hasFocus) onItemFocused?.invoke(item)
        }
        holder.updateVisuals(item, holder.itemView.hasFocus(), themeColor)

        holder.itemView.setOnClickListener { onItemSelected(item) }
        holder.itemView.isFocusable = true
        holder.itemView.isFocusableInTouchMode = true
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
        items = items.map { it.copy(isSelected = it.id == selectedId) }
        refreshHolders()
    }

    fun toggleItemSelection(id: String) {
        items = items.map { 
            if (it.id == id) it.copy(isSelected = !it.isSelected) else it 
        }
        refreshHolders()
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
}
