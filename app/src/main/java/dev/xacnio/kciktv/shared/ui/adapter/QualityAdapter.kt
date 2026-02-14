/**
 * File: QualityAdapter.kt
 *
 * Description: RecyclerView Adapter for displaying Quality lists.
 *
 * Author: Xacnio
 *
 */
package dev.xacnio.kciktv.shared.ui.adapter

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.amazonaws.ivs.player.Quality
import dev.xacnio.kciktv.R

class QualityAdapter(
    private val qualities: List<Quality>,
    private val currentQuality: Quality?,
    private val isAuto: Boolean,
    private val onQualitySelected: (Quality?) -> Unit
) : RecyclerView.Adapter<QualityAdapter.ViewHolder>() {

    class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val txtName: TextView = view.findViewById(R.id.txtSettingItem)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_setting, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        if (position == 0) {
            holder.txtName.text = holder.itemView.context.getString(R.string.auto)
            holder.txtName.setTextColor(if (isAuto) 0xFF53FC18.toInt() else 0xFFFFFFFF.toInt())
            holder.itemView.setOnClickListener { onQualitySelected(null) }
        } else {
            val quality = qualities[position - 1]
            holder.txtName.text = quality.name.uppercase()
            val isSelected = !isAuto && currentQuality?.name == quality.name
            holder.txtName.setTextColor(if (isSelected) 0xFF53FC18.toInt() else 0xFFFFFFFF.toInt())
            holder.itemView.setOnClickListener { onQualitySelected(quality) }
        }
    }

    override fun getItemCount(): Int = qualities.size + 1
}
