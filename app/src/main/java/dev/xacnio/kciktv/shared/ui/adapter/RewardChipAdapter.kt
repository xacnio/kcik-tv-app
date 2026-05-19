/**
 * File: RewardChipAdapter.kt
 *
 * Description: Vertical reward list shown on the left panel of the reward queue sheet.
 * The first item is a synthetic "All rewards" entry; the rest map 1:1 to ChannelReward.
 *
 * Author: Xacnio
 *
 */
package dev.xacnio.kciktv.shared.ui.adapter

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import dev.xacnio.kciktv.R

class RewardChipAdapter(
    private val onSelected: (rewardId: String?) -> Unit
) : RecyclerView.Adapter<RewardChipAdapter.VH>() {

    /** id == null is the synthetic "all rewards" entry. */
    data class Chip(val id: String?, val title: String, val count: Int, val isPaused: Boolean = false)

    private val items = mutableListOf<Chip>()
    private var selectedId: String? = null  // null = "all rewards"

    fun submit(chips: List<Chip>) {
        items.clear()
        items.addAll(chips)
        notifyDataSetChanged()
    }

    fun setSelected(rewardId: String?) {
        if (selectedId == rewardId) return
        selectedId = rewardId
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_reward_chip, parent, false)
        return VH(view)
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        val chip = items[position]
        holder.title.text = chip.title
        holder.badge.text = chip.count.toString()
        holder.badge.visibility = if (chip.count > 0) View.VISIBLE else View.GONE
        val selected = chip.id == selectedId
        holder.root.isSelected = selected
        holder.selectionBar.visibility = if (selected) View.VISIBLE else View.INVISIBLE
        holder.title.alpha = if (selected) 1f else 0.75f

        holder.pausedBadge.visibility = if (chip.id != null && chip.isPaused) View.VISIBLE else View.GONE
        if (chip.isPaused) holder.title.alpha = if (selected) 0.55f else 0.4f

        holder.root.setOnClickListener {
            if (selectedId != chip.id) {
                selectedId = chip.id
                notifyDataSetChanged()
            }
            onSelected(chip.id)
        }
    }

    override fun getItemCount(): Int = items.size

    class VH(itemView: View) : RecyclerView.ViewHolder(itemView) {
        val root: LinearLayout = itemView.findViewById(R.id.rewardChipRoot)
        val title: TextView = itemView.findViewById(R.id.rewardChipTitle)
        val badge: TextView = itemView.findViewById(R.id.rewardChipBadge)
        val selectionBar: View = itemView.findViewById(R.id.rewardChipSelectionBar)
        val pausedBadge: TextView = itemView.findViewById(R.id.rewardChipPausedBadge)
    }
}
