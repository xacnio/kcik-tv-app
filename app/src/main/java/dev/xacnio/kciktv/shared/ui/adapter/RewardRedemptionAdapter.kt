/**
 * File: RewardRedemptionAdapter.kt
 *
 * Description: Renders pending reward redemptions with checkboxes for bulk selection,
 * per-row accept/reject actions, and full-text display of user input.
 *
 * Author: Xacnio
 *
 */
package dev.xacnio.kciktv.shared.ui.adapter

import android.graphics.Color
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.CheckBox
import android.widget.ImageButton
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import dev.xacnio.kciktv.R
import dev.xacnio.kciktv.shared.data.model.RewardRedemption

class RewardRedemptionAdapter(
    private val timeAgoFormatter: (createdAt: String?) -> String,
    private val onAccept: (RewardRedemption) -> Unit,
    private val onReject: (RewardRedemption) -> Unit,
    private val onSelectionChanged: (selectedCount: Int, totalCount: Int) -> Unit = { _, _ -> }
) : RecyclerView.Adapter<RewardRedemptionAdapter.VH>() {

    private val items = mutableListOf<RewardRedemption>()
    private val selectedIds = mutableSetOf<String>()

    fun submit(list: List<RewardRedemption>) {
        items.clear()
        items.addAll(list)
        selectedIds.retainAll(items.map { it.id }.toSet())
        notifyDataSetChanged()
        onSelectionChanged(selectedIds.size, items.size)
    }

    fun selectAll() {
        selectedIds.addAll(items.map { it.id })
        notifyDataSetChanged()
        onSelectionChanged(selectedIds.size, items.size)
    }

    fun clearSelection() {
        selectedIds.clear()
        notifyDataSetChanged()
        onSelectionChanged(0, items.size)
    }

    fun isAllSelected(): Boolean = items.isNotEmpty() && selectedIds.containsAll(items.map { it.id })

    fun getSelectedItems(): List<RewardRedemption> = items.filter { it.id in selectedIds }

    fun getSelectedCount(): Int = selectedIds.size

    fun removeItem(id: String) {
        val index = items.indexOfFirst { it.id == id }
        if (index < 0) return
        items.removeAt(index)
        selectedIds.remove(id)
        notifyItemRemoved(index)
        onSelectionChanged(selectedIds.size, items.size)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_reward_redemption, parent, false)
        return VH(view)
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        val r = items[position]
        val selected = r.id in selectedIds

        holder.rewardTitle.text = r.rewardTitle ?: ""
        holder.username.text = r.username
        try {
            r.userColor?.let { holder.username.setTextColor(Color.parseColor(it)) }
        } catch (_: IllegalArgumentException) {}

        if (r.userInput.isNullOrBlank()) {
            holder.userInput.visibility = View.GONE
        } else {
            holder.userInput.visibility = View.VISIBLE
            holder.userInput.text = r.userInput
        }

        holder.timeAgo.text = timeAgoFormatter(r.createdAt)
        holder.checkbox.isChecked = selected

        holder.itemView.setOnClickListener {
            if (r.id in selectedIds) selectedIds.remove(r.id) else selectedIds.add(r.id)
            notifyItemChanged(position)
            onSelectionChanged(selectedIds.size, items.size)
        }
        holder.acceptButton.setOnClickListener { onAccept(r) }
        holder.rejectButton.setOnClickListener { onReject(r) }
    }

    override fun getItemCount(): Int = items.size

    class VH(itemView: View) : RecyclerView.ViewHolder(itemView) {
        val checkbox: CheckBox = itemView.findViewById(R.id.redemptionCheckbox)
        val rewardTitle: TextView = itemView.findViewById(R.id.redemptionRewardTitle)
        val timeAgo: TextView = itemView.findViewById(R.id.redemptionTimeAgo)
        val username: TextView = itemView.findViewById(R.id.redemptionUsername)
        val userInput: TextView = itemView.findViewById(R.id.redemptionUserInput)
        val acceptButton: ImageButton = itemView.findViewById(R.id.redemptionAcceptButton)
        val rejectButton: ImageButton = itemView.findViewById(R.id.redemptionRejectButton)
    }
}
