/**
 * File: CollectiblesAdapter.kt
 *
 * Description: RecyclerView adapter for the Collectibles page — renders the intro/inventory
 * header, per-group section headers, and the card grid itself as one flat, recycling list.
 *
 * Author: Xacnio
 *
 */
package dev.xacnio.kciktv.mobile.ui.collectibles

import android.graphics.drawable.Drawable
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.bumptech.glide.load.DataSource
import com.bumptech.glide.load.engine.GlideException
import com.bumptech.glide.request.RequestListener
import com.bumptech.glide.request.target.Target
import dev.xacnio.kciktv.R
import dev.xacnio.kciktv.shared.data.model.CollectibleCard
import dev.xacnio.kciktv.shared.util.CardImageUrl

class CollectiblesAdapter(
    private val onSortClick: (View) -> Unit,
    private val onCardClick: (CollectibleCard) -> Unit
) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

    companion object {
        const val TYPE_INVENTORY = 0
        const val TYPE_SECTION = 1
        const val TYPE_CARD = 2
    }

    sealed class Item {
        /** Intro blurb + "x of y Unlocked" summary + the sort control. */
        data class Inventory(val unlocked: Int, val total: Int, val sortLabel: String) : Item()
        data class Section(val title: String, val unlocked: Int, val total: Int) : Item()
        data class Card(val card: CollectibleCard) : Item()
    }

    private val items = mutableListOf<Item>()

    fun submit(newItems: List<Item>) {
        items.clear()
        items.addAll(newItems)
        notifyDataSetChanged()
    }

    /** Full-span rows (everything except the cards themselves). */
    fun isFullSpan(position: Int): Boolean = getItemViewType(position) != TYPE_CARD

    override fun getItemCount(): Int = items.size

    override fun getItemViewType(position: Int): Int = when (items[position]) {
        is Item.Inventory -> TYPE_INVENTORY
        is Item.Section -> TYPE_SECTION
        is Item.Card -> TYPE_CARD
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val inflater = LayoutInflater.from(parent.context)
        return when (viewType) {
            TYPE_INVENTORY -> InventoryViewHolder(inflater.inflate(R.layout.item_collectible_inventory, parent, false))
            TYPE_SECTION -> SectionViewHolder(inflater.inflate(R.layout.item_collectible_section, parent, false))
            else -> CardViewHolder(inflater.inflate(R.layout.item_collectible_card, parent, false))
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        when (val item = items[position]) {
            is Item.Inventory -> (holder as InventoryViewHolder).bind(item)
            is Item.Section -> (holder as SectionViewHolder).bind(item)
            is Item.Card -> (holder as CardViewHolder).bind(item.card)
        }
    }

    inner class InventoryViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val count: TextView = itemView.findViewById(R.id.collectiblesInventoryCount)
        private val sortButton: View = itemView.findViewById(R.id.collectiblesSortButton)
        private val sortLabel: TextView = itemView.findViewById(R.id.collectiblesSortLabel)

        fun bind(item: Item.Inventory) {
            count.text = itemView.context.getString(
                R.string.collectibles_unlocked_count, item.unlocked, item.total
            )
            sortLabel.text = item.sortLabel
            sortButton.setOnClickListener { onSortClick(sortButton) }
        }
    }

    inner class SectionViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val title: TextView = itemView.findViewById(R.id.collectibleSectionTitle)
        private val subtitle: TextView = itemView.findViewById(R.id.collectibleSectionSubtitle)

        fun bind(item: Item.Section) {
            title.text = item.title
            subtitle.text = itemView.context.getString(
                R.string.collectibles_unlocked_count, item.unlocked, item.total
            )
        }
    }

    inner class CardViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val image: ImageView = itemView.findViewById(R.id.collectibleCardImage)
        private val lockBadge: View = itemView.findViewById(R.id.collectibleLockBadge)
        private val shimmer: View = itemView.findViewById(R.id.collectibleShimmer)

        fun bind(card: CollectibleCard) {
            // Cards you don't own are dimmed and get a lock badge, matching Kick's own grid.
            image.alpha = if (card.owned) 1.0f else 0.35f
            lockBadge.visibility = if (card.owned) View.GONE else View.VISIBLE

            // Reset per-bind: this holder may be recycled from an already-loaded card.
            shimmer.visibility = View.VISIBLE
            image.setImageDrawable(null)

            Glide.with(itemView.context)
                .load(CardImageUrl.sized(card.cardUrl.orEmpty()))
                .listener(object : RequestListener<Drawable> {
                    override fun onLoadFailed(
                        e: GlideException?,
                        model: Any?,
                        target: Target<Drawable>,
                        isFirstResource: Boolean
                    ): Boolean {
                        shimmer.visibility = View.GONE
                        return false
                    }

                    override fun onResourceReady(
                        resource: Drawable,
                        model: Any,
                        target: Target<Drawable>?,
                        dataSource: DataSource,
                        isFirstResource: Boolean
                    ): Boolean {
                        shimmer.visibility = View.GONE
                        return false
                    }
                })
                .into(image)

            itemView.setOnClickListener { onCardClick(card) }
        }
    }
}
