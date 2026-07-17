/**
 * File: CollectiblesManager.kt
 *
 * Description: Manages the Collectibles page — the user's full card inventory, grouped and
 * sorted, showing which cards have been unlocked.
 *
 * Author: Xacnio
 *
 */
package dev.xacnio.kciktv.mobile.ui.collectibles

import android.util.Log
import android.view.View
import android.widget.ImageButton
import androidx.lifecycle.LifecycleCoroutineScope
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import dev.xacnio.kciktv.R
import dev.xacnio.kciktv.databinding.ActivityMobilePlayerBinding
import dev.xacnio.kciktv.mobile.MobilePlayerActivity
import dev.xacnio.kciktv.shared.data.model.CollectibleCard
import dev.xacnio.kciktv.shared.data.prefs.AppPreferences
import dev.xacnio.kciktv.shared.data.repository.ChannelRepository
import kotlinx.coroutines.launch
import java.util.Locale

class CollectiblesManager(
    private val activity: MobilePlayerActivity,
    private val binding: ActivityMobilePlayerBinding,
    private val prefs: AppPreferences,
    private val repository: ChannelRepository,
    private val lifecycleScope: LifecycleCoroutineScope
) {
    private companion object {
        const val TAG = "CollectiblesManager"

        // Rarest first, matching how Kick orders its own inventory.
        val RARITY_ORDER = listOf("mythic", "legendary", "epic", "rare", "uncommon", "common")
    }

    enum class SortMode { RARITY, TYPE, UNLOCKED }

    var isVisible = false
        private set

    private var isSetup = false
    private var sortMode = SortMode.RARITY
    private var cards: List<CollectibleCard> = emptyList()
    private var adapter: CollectiblesAdapter? = null
    private var screenBefore: MobilePlayerActivity.AppScreen = MobilePlayerActivity.AppScreen.HOME
    private var headerVisibilityBefore = View.VISIBLE

    private val container get() = binding.collectiblesContainer

    fun show() {
        if (!isSetup) setup()

        // Remember where to hand control back to — this page overlays whatever was showing
        // rather than replacing it, so closing just restores the previous screen's state.
        if (!isVisible) {
            screenBefore = activity.currentScreen
            headerVisibilityBefore = binding.mobileHeader.visibility
        }

        // The global header sits at elevation 30dp — above this container — and this page has
        // its own header anyway, so take it out while we're up.
        binding.mobileHeader.visibility = View.GONE
        container.root.visibility = View.VISIBLE
        isVisible = true
        activity.setCurrentScreen(MobilePlayerActivity.AppScreen.COLLECTIBLES)

        if (cards.isEmpty()) load() else applyData()
    }

    fun close() {
        if (!isVisible) return
        container.root.visibility = View.GONE
        isVisible = false
        binding.mobileHeader.visibility = headerVisibilityBefore
        activity.setCurrentScreen(screenBefore)
    }

    /**
     * Hides the page without restoring the previous screen — for callers that are navigating
     * somewhere themselves and just need this overlay out of the way. They set header
     * visibility themselves, so it isn't restored here.
     */
    fun hide() {
        if (!isVisible) return
        container.root.visibility = View.GONE
        isVisible = false
    }

    private fun setup() {
        val recyclerView = container.root.findViewById<RecyclerView>(R.id.collectiblesRecyclerView)

        val collectiblesAdapter = CollectiblesAdapter(
            onSortClick = { anchor -> showSortMenu(anchor) },
            onCardClick = { card -> showCardZoom(card) }
        )
        adapter = collectiblesAdapter
        recyclerView.adapter = collectiblesAdapter

        val spanCount = activity.resources.getInteger(R.integer.collectibles_span_count)
        val layoutManager = GridLayoutManager(activity, spanCount)
        layoutManager.spanSizeLookup = object : GridLayoutManager.SpanSizeLookup() {
            override fun getSpanSize(position: Int): Int =
                if (collectiblesAdapter.isFullSpan(position)) spanCount else 1
        }
        recyclerView.layoutManager = layoutManager

        container.root.findViewById<ImageButton>(R.id.btnCollectiblesBack).setOnClickListener { close() }
        container.root.findViewById<ImageButton>(R.id.btnCollectiblesClose).setOnClickListener { close() }

        isSetup = true
    }

    private fun load() {
        val token = prefs.authToken ?: return
        val shimmer = container.collectiblesShimmer.root
        val error = container.root.findViewById<View>(R.id.collectiblesError)
        val recyclerView = container.root.findViewById<View>(R.id.collectiblesRecyclerView)

        shimmer.visibility = View.VISIBLE
        error.visibility = View.GONE
        recyclerView.visibility = View.GONE

        lifecycleScope.launch {
            val result = repository.getCollectibles(token)
            if (!isVisible) return@launch
            shimmer.visibility = View.GONE

            result.onSuccess { loaded ->
                cards = loaded
                recyclerView.visibility = View.VISIBLE
                applyData()
            }.onFailure {
                Log.e(TAG, "Failed to load collectibles", it)
                error.visibility = View.VISIBLE
            }
        }
    }

    private fun applyData() {
        val items = mutableListOf<CollectiblesAdapter.Item>()
        items.add(
            CollectiblesAdapter.Item.Inventory(
                unlocked = cards.count { it.owned },
                total = cards.size,
                sortLabel = activity.getString(sortLabelRes(sortMode))
            )
        )

        groupCards().forEach { (title, groupCards) ->
            items.add(
                CollectiblesAdapter.Item.Section(
                    title = title,
                    unlocked = groupCards.count { it.owned },
                    total = groupCards.size
                )
            )
            groupCards.forEach { items.add(CollectiblesAdapter.Item.Card(it)) }
        }

        adapter?.submit(items)
    }

    /** Rarest first. Unknown rarities rank last rather than jumping to the top. */
    private fun rarityRank(card: CollectibleCard): Int {
        val index = RARITY_ORDER.indexOf(card.rarity?.lowercase(Locale.US))
        return if (index >= 0) index else RARITY_ORDER.size
    }

    /** Owned cards lead, rarest first within each half. Shared by all sort modes. */
    private fun sortedForDisplay(group: List<CollectibleCard>): List<CollectibleCard> =
        group.sortedWith(
            compareByDescending<CollectibleCard> { it.owned }.thenBy { rarityRank(it) }
        )

    /** Groups the cards into titled sections according to the active sort mode. */
    private fun groupCards(): List<Pair<String, List<CollectibleCard>>> = when (sortMode) {
        SortMode.RARITY -> RARITY_ORDER.mapNotNull { rarity ->
            val group = cards.filter { it.rarity?.lowercase(Locale.US) == rarity }
            if (group.isEmpty()) null else activity.getString(rarityNameRes(rarity)) to sortedForDisplay(group)
        }
        SortMode.TYPE -> listOf("emote", "badge").mapNotNull { type ->
            val group = cards.filter { it.type?.lowercase(Locale.US) == type }
            if (group.isEmpty()) null else activity.getString(typeNameRes(type)) to sortedForDisplay(group)
        }
        SortMode.UNLOCKED -> listOf(true, false).mapNotNull { owned ->
            val group = cards.filter { it.owned == owned }
            if (group.isEmpty()) null else {
                val title = activity.getString(
                    if (owned) R.string.collectibles_group_unlocked else R.string.collectibles_group_locked
                )
                title to sortedForDisplay(group)
            }
        }
    }

    private fun showSortMenu(anchor: View) {
        val popup = android.widget.PopupMenu(activity, anchor)
        SortMode.entries.forEachIndexed { index, mode ->
            popup.menu.add(0, index, index, activity.getString(sortLabelRes(mode)))
        }
        popup.setOnMenuItemClickListener { menuItem ->
            val selected = SortMode.entries[menuItem.itemId]
            if (selected != sortMode) {
                sortMode = selected
                applyData()
            }
            true
        }
        popup.show()
    }

    private fun showCardZoom(card: CollectibleCard) {
        val url = card.cardUrl ?: return
        activity.dailyRewardManager.showCardZoom(url)
    }

    private fun sortLabelRes(mode: SortMode): Int = when (mode) {
        SortMode.RARITY -> R.string.collectibles_sort_rarity
        SortMode.TYPE -> R.string.collectibles_sort_type
        SortMode.UNLOCKED -> R.string.collectibles_sort_unlocked
    }

    private fun typeNameRes(type: String): Int = when (type) {
        "badge" -> R.string.collectibles_type_badge
        else -> R.string.collectibles_type_emote
    }

    private fun rarityNameRes(rarity: String): Int = when (rarity) {
        "common" -> R.string.rarity_common
        "uncommon" -> R.string.rarity_uncommon
        "rare" -> R.string.rarity_rare
        "epic" -> R.string.rarity_epic
        "legendary" -> R.string.rarity_legendary
        "mythic" -> R.string.rarity_mythic
        else -> R.string.rarity_common
    }
}
