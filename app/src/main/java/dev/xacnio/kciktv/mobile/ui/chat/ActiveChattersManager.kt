/**
 * File: ActiveChattersManager.kt
 *
 * Description: Builds the Active Chatters bottom sheet — everyone seen this session with their
 * message counts, searchable, badge-filterable, grouped and sorted. A row opens user actions.
 *
 * Author: Xacnio
 *
 */
package dev.xacnio.kciktv.mobile.ui.chat

import android.os.Handler
import android.os.Looper
import android.view.View
import android.widget.EditText
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.PopupMenu
import android.widget.TextView
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog
import dev.xacnio.kciktv.R
import dev.xacnio.kciktv.mobile.MobilePlayerActivity
import dev.xacnio.kciktv.shared.data.model.ActiveChatter
import dev.xacnio.kciktv.shared.data.model.ChatBadge
import dev.xacnio.kciktv.shared.data.model.ChatBadgeV2
import dev.xacnio.kciktv.shared.data.model.ChatterClassifier
import dev.xacnio.kciktv.shared.data.model.ChatterGrouping
import dev.xacnio.kciktv.shared.data.model.ChatterRole
import dev.xacnio.kciktv.shared.data.model.ChatterSort
import dev.xacnio.kciktv.shared.ui.adapter.ActiveChattersAdapter
import dev.xacnio.kciktv.shared.ui.utils.BadgeRenderUtils

class ActiveChattersManager(private val activity: MobilePlayerActivity) {

    /** One entry of the badge filter strip, carrying the art to draw for it. */
    private class BadgeFilterOption(
        val key: String,
        val v1: ChatBadge?,
        val v2: ChatBadgeV2?
    )

    /** A group of the list. [key] is stable across refreshes so collapse state sticks. */
    private class Section(
        val key: String,
        val title: String,
        val option: BadgeFilterOption?,
        val chatters: List<ActiveChatter>
    )

    private val prefs get() = activity.prefs
    private val store get() = activity.activeChattersStore

    /** null = no badge filter, show everyone. Survives reopening the sheet. */
    private var selectedBadgeKey: String? = null

    /** Section keys the user folded shut, namespaced per grouping so modes don't collide. */
    private val collapsedSections = mutableSetOf<String>()

    private val refreshHandler = Handler(Looper.getMainLooper())

    private var groupingOrNull: ChatterGrouping? = null
    private var grouping: ChatterGrouping
        get() = groupingOrNull
            ?: (runCatching { ChatterGrouping.valueOf(prefs.activeChattersGrouping) }
                .getOrNull() ?: ChatterGrouping.ROLE).also { groupingOrNull = it }
        set(value) {
            groupingOrNull = value
            prefs.activeChattersGrouping = value.name
        }

    private var sortOrNull: ChatterSort? = null
    private var sort: ChatterSort
        get() = sortOrNull
            ?: (runCatching { ChatterSort.valueOf(prefs.activeChattersSort) }
                .getOrNull() ?: ChatterSort.MESSAGES).also { sortOrNull = it }
        set(value) {
            sortOrNull = value
            prefs.activeChattersSort = value.name
        }

    private var sortDescendingOrNull: Boolean? = null
    private var sortDescending: Boolean
        get() = sortDescendingOrNull ?: prefs.activeChattersSortDescending.also {
            sortDescendingOrNull = it
        }
        set(value) {
            sortDescendingOrNull = value
            prefs.activeChattersSortDescending = value
        }

    fun showSheet() {
        val dialog = BottomSheetDialog(activity, R.style.Theme_KcikTV_Dialog)
        val view = activity.layoutInflater.inflate(R.layout.bottom_sheet_active_chatters, null)
        dialog.setContentView(view)
        activity.trackBottomSheet(dialog)

        // Fixed height so filtering down to one row can't shrink the sheet, and no drag or
        // scrim dismissal so scrolling past the top never closes it — the X button does that.
        dialog.setCanceledOnTouchOutside(false)
        dialog.findViewById<View>(com.google.android.material.R.id.design_bottom_sheet)?.let { sheet ->
            val behavior = BottomSheetBehavior.from(sheet)
            val sheetHeight = (activity.resources.displayMetrics.heightPixels * SHEET_HEIGHT_RATIO).toInt()
            sheet.layoutParams = sheet.layoutParams.apply { height = sheetHeight }
            behavior.peekHeight = sheetHeight
            behavior.maxHeight = sheetHeight
            behavior.isFitToContents = true
            behavior.skipCollapsed = true
            behavior.isHideable = false
            behavior.isDraggable = false
            behavior.state = BottomSheetBehavior.STATE_EXPANDED
        }

        val recycler = view.findViewById<RecyclerView>(R.id.activeChattersRecycler)
        val emptyView = view.findViewById<TextView>(R.id.activeChattersEmpty)
        val summary = view.findViewById<TextView>(R.id.activeChattersSummary)
        val searchInput = view.findViewById<EditText>(R.id.activeChattersSearch)
        val searchClear = view.findViewById<ImageButton>(R.id.activeChattersSearchClear)
        val filterStrip = view.findViewById<LinearLayout>(R.id.activeChattersBadgeFilters)
        val groupingButton = view.findViewById<View>(R.id.activeChattersGroupingButton)
        val groupingLabel = view.findViewById<TextView>(R.id.activeChattersGroupingLabel)
        val sortButton = view.findViewById<View>(R.id.activeChattersSortButton)
        val sortLabel = view.findViewById<TextView>(R.id.activeChattersSortLabel)
        val sortDirection = view.findViewById<ImageButton>(R.id.activeChattersSortDirection)

        // Declared up front so the adapter and the badge taps can call back into it
        var refresh: () -> Unit = {}

        val subscriberBadges = activity.chatStateManager.subscriberBadges
        val adapter = ActiveChattersAdapter(
            onChatterClick = { chatter ->
                dialog.dismiss()
                activity.showUserActionsSheet(chatter.sender)
            },
            onSectionToggle = { sectionKey ->
                if (!collapsedSections.remove(sectionKey)) collapsedSections.add(sectionKey)
                refresh()
            }
        )
        adapter.subscriberBadges = subscriberBadges
        recycler.layoutManager = LinearLayoutManager(activity)
        recycler.adapter = adapter

        // Rebuilt only when the badges present change, so a refresh can't steal a tap
        var builtFilterKeys: List<String> = emptyList()
        val filterViews = HashMap<String?, View>()

        refresh = {
            val snapshot = store.snapshot()

            val options = buildFilterOptions(snapshot)
            val keys = options.map { it.key }
            if (keys != builtFilterKeys) {
                builtFilterKeys = keys
                filterViews.clear()
                filterStrip.removeAllViews()

                filterViews[null] = addFilterOption(
                    filterStrip, null, activity.getString(R.string.activity_filter_all), subscriberBadges
                ) {
                    selectedBadgeKey = null
                    refresh()
                }
                options.forEach { option ->
                    filterViews[option.key] = addFilterOption(
                        filterStrip, option, badgeLabel(option.key, option), subscriberBadges
                    ) {
                        // Tapping the active badge clears the filter
                        selectedBadgeKey = if (selectedBadgeKey == option.key) null else option.key
                        refresh()
                    }
                }
            }
            // A badge can vanish from the strip while it is the active filter
            if (selectedBadgeKey != null && selectedBadgeKey !in keys) selectedBadgeKey = null
            filterViews.forEach { (key, itemView) -> itemView.isSelected = key == selectedBadgeKey }

            val query = searchInput.text?.toString()?.trim()?.lowercase().orEmpty()
            val visible = snapshot.filter { chatter ->
                (selectedBadgeKey == null || selectedBadgeKey in chatter.badgeKeys) &&
                    (query.isEmpty() || chatter.username.lowercase().contains(query))
            }

            summary.text = activity.getString(
                R.string.active_chatters_summary, snapshot.size, snapshot.sumOf { it.messageCount }
            )
            groupingLabel.text = activity.getString(groupingLabelRes(grouping))
            sortLabel.text = activity.getString(sortLabelRes(sort))
            sortDirection.setImageResource(
                if (sortDescending) R.drawable.ic_arrow_downward else R.drawable.ic_arrow_upward
            )

            adapter.submitList(buildItems(visible, options.associateBy { it.key }))

            emptyView.visibility = if (visible.isEmpty()) View.VISIBLE else View.GONE
            emptyView.setText(
                if (snapshot.isEmpty()) R.string.active_chatters_empty
                else R.string.active_chatters_no_results
            )
        }

        groupingButton.setOnClickListener { anchor ->
            val popup = PopupMenu(activity, anchor)
            ChatterGrouping.entries.forEachIndexed { index, mode ->
                popup.menu.add(0, index, index, activity.getString(groupingLabelRes(mode)))
            }
            popup.setOnMenuItemClickListener { menuItem ->
                val selected = ChatterGrouping.entries[menuItem.itemId]
                if (selected != grouping) {
                    grouping = selected
                    refresh()
                }
                true
            }
            popup.show()
        }

        sortButton.setOnClickListener { anchor ->
            val popup = PopupMenu(activity, anchor)
            ChatterSort.entries.forEachIndexed { index, mode ->
                popup.menu.add(0, index, index, activity.getString(sortLabelRes(mode)))
            }
            popup.setOnMenuItemClickListener { menuItem ->
                val selected = ChatterSort.entries[menuItem.itemId]
                if (selected != sort) {
                    sort = selected
                    refresh()
                }
                true
            }
            popup.show()
        }

        sortDirection.setOnClickListener {
            sortDescending = !sortDescending
            refresh()
        }

        searchInput.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: android.text.Editable?) {
                searchClear.visibility = if (s.isNullOrEmpty()) View.GONE else View.VISIBLE
                refresh()
            }
        })
        searchClear.setOnClickListener { searchInput.setText("") }
        view.findViewById<View>(R.id.activeChattersClose).setOnClickListener { dialog.dismiss() }

        // Chat keeps flowing, so pull in new counts periodically — never mid-scroll
        val refreshTick = object : Runnable {
            override fun run() {
                if (recycler.scrollState == RecyclerView.SCROLL_STATE_IDLE) refresh()
                refreshHandler.postDelayed(this, REFRESH_INTERVAL_MS)
            }
        }

        // Replaces the listener trackBottomSheet installed, so untrack here too
        dialog.setOnDismissListener {
            activity.activeBottomSheets.remove(dialog)
            refreshHandler.removeCallbacks(refreshTick)
        }

        refresh()
        refreshHandler.postDelayed(refreshTick, REFRESH_INTERVAL_MS)
        dialog.show()
    }

    // --- Badge filter strip ---

    /** One entry per badge worn by someone. Ignores the search so the strip stays put while typing. */
    private fun buildFilterOptions(chatters: List<ActiveChatter>): List<BadgeFilterOption> {
        val options = LinkedHashMap<String, BadgeFilterOption>()

        chatters.forEach { chatter ->
            chatter.sender.badges?.forEach { badge ->
                if (badge.type.isNotEmpty()) {
                    options.getOrPut(badge.type) { BadgeFilterOption(badge.type, badge, null) }
                }
            }
            chatter.sender.badgesV2?.forEach { badge ->
                // Level badges left out: one entry per level would be noise
                if (badge.selected == true && !ChatterClassifier.isLevelBadge(badge)) {
                    val name = badge.name
                    if (!name.isNullOrEmpty()) {
                        val key = ChatterClassifier.V2_PREFIX + name
                        options.getOrPut(key) { BadgeFilterOption(key, null, badge) }
                    }
                }
            }
            if (chatter.badgeKeys.contains(ChatterClassifier.NO_BADGE)) {
                options.getOrPut(ChatterClassifier.NO_BADGE) {
                    BadgeFilterOption(ChatterClassifier.NO_BADGE, null, null)
                }
            }
        }

        return options.values.sortedBy { ChatterClassifier.keyRank(it.key) }
    }

    /** Falls back to a text label when there is no art to draw ("All", "no badge", unknown types). */
    private fun addFilterOption(
        strip: LinearLayout,
        option: BadgeFilterOption?,
        label: String,
        subscriberBadges: Map<Int, String>,
        onClick: () -> Unit
    ): View {
        val itemView = activity.layoutInflater
            .inflate(R.layout.item_active_chatter_badge_filter, strip, false)
        val icons = itemView.findViewById<LinearLayout>(R.id.badgeFilterIcons)
        val text = itemView.findViewById<TextView>(R.id.badgeFilterLabel)

        if (option != null) {
            BadgeRenderUtils.renderChatSenderBadges(
                activity,
                icons,
                listOfNotNull(option.v1),
                listOfNotNull(option.v2),
                (18 * activity.resources.displayMetrics.density).toInt(),
                0,
                subscriberBadges
            )
        }

        if (option == null || icons.childCount == 0) {
            icons.visibility = View.GONE
            text.visibility = View.VISIBLE
            text.text = label
        }

        itemView.contentDescription = label
        itemView.setOnClickListener { onClick() }
        strip.addView(itemView)
        return itemView
    }

    // --- Grouping & sorting ---

    /** Flattens chatters into headers + rows. [badgeOptions] supplies badge-header art. */
    private fun buildItems(
        chatters: List<ActiveChatter>,
        badgeOptions: Map<String, BadgeFilterOption>
    ): List<ActiveChattersAdapter.Item> {
        if (chatters.isEmpty()) return emptyList()

        if (grouping == ChatterGrouping.NONE) {
            return sortedForDisplay(chatters).map { ActiveChattersAdapter.Item.Chatter(it) }
        }

        val sections: List<Section> = when (grouping) {
            ChatterGrouping.ROLE -> ChatterRole.entries.map { role ->
                Section(
                    key = "ROLE:${role.name}",
                    title = activity.getString(roleLabelRes(role)),
                    option = null,
                    chatters = chatters.filter { it.role == role }
                )
            }

            ChatterGrouping.BADGE -> {
                // A chatter shows up once, under their highest-ranked badge
                val byKey = LinkedHashMap<String, MutableList<ActiveChatter>>()
                chatters.forEach { chatter ->
                    val key = chatter.badgeKeys.minByOrNull { ChatterClassifier.keyRank(it) }
                        ?: ChatterClassifier.NO_BADGE
                    byKey.getOrPut(key) { mutableListOf() }.add(chatter)
                }
                byKey.entries
                    .sortedBy { ChatterClassifier.keyRank(it.key) }
                    .map { (key, group) ->
                        val option = badgeOptions[key]
                        Section("BADGE:$key", badgeLabel(key, option), option, group.toList())
                    }
            }

            else -> chatters
                .groupBy { initialOf(it.username) }
                .toSortedMap()
                .map { (letter, group) -> Section("ALPHA:$letter", letter, null, group) }
        }

        val items = mutableListOf<ActiveChattersAdapter.Item>()
        sections.forEach { section ->
            if (section.chatters.isEmpty()) return@forEach
            val collapsed = section.key in collapsedSections
            items.add(
                ActiveChattersAdapter.Item.Header(
                    sectionKey = section.key,
                    title = section.title,
                    chatters = section.chatters.size,
                    messages = section.chatters.sumOf { it.messageCount },
                    collapsed = collapsed,
                    badgeV1 = section.option?.v1,
                    badgeV2 = section.option?.v2
                )
            )
            if (!collapsed) {
                sortedForDisplay(section.chatters).forEach {
                    items.add(ActiveChattersAdapter.Item.Chatter(it))
                }
            }
        }
        return items
    }

    /** Non-letter names (digits, symbols) all land under "#". */
    private fun initialOf(username: String): String {
        val first = username.firstOrNull() ?: return "#"
        return if (first.isLetter()) first.uppercase() else "#"
    }

    private fun sortedForDisplay(chatters: List<ActiveChatter>): List<ActiveChatter> {
        val base: Comparator<ActiveChatter> = when (sort) {
            ChatterSort.MESSAGES -> compareBy<ActiveChatter> { it.messageCount }
                .thenBy { it.lastMessageAt }
            ChatterSort.NAME -> compareBy { it.username.lowercase() }
            ChatterSort.LEVEL -> compareBy<ActiveChatter> { it.level }
                .thenBy { it.messageCount }
            ChatterSort.RECENT -> compareBy { it.lastMessageAt }
        }
        val stable = base.thenBy { it.username.lowercase() }
        return chatters.sortedWith(if (sortDescending) stable.reversed() else stable)
    }

    // --- Labels ---

    private fun roleLabelRes(role: ChatterRole): Int = when (role) {
        ChatterRole.MODERATOR -> R.string.active_chatters_group_moderators
        ChatterRole.VIP -> R.string.active_chatters_group_vips
        ChatterRole.OG -> R.string.active_chatters_group_ogs
        ChatterRole.VIEWER -> R.string.active_chatters_group_viewers
    }

    private fun groupingLabelRes(mode: ChatterGrouping): Int = when (mode) {
        ChatterGrouping.ROLE -> R.string.active_chatters_grouping_role
        ChatterGrouping.BADGE -> R.string.active_chatters_grouping_badge
        ChatterGrouping.ALPHABET -> R.string.active_chatters_grouping_alphabet
        ChatterGrouping.NONE -> R.string.active_chatters_grouping_none
    }

    private fun sortLabelRes(mode: ChatterSort): Int = when (mode) {
        ChatterSort.MESSAGES -> R.string.active_chatters_sort_messages
        ChatterSort.NAME -> R.string.active_chatters_sort_name
        ChatterSort.LEVEL -> R.string.active_chatters_sort_level
        ChatterSort.RECENT -> R.string.active_chatters_sort_recent
    }

    /**
     * The badge's own wording instead of a table to keep in step with Kick: the API's `text`,
     * else the name. Numeric text is a subscriber month count, not a label, so it falls back too.
     */
    private fun badgeLabel(key: String, option: BadgeFilterOption?): String {
        if (key == ChatterClassifier.NO_BADGE) return activity.getString(R.string.badge_name_none)

        option?.v1?.let { badge ->
            val text = badge.text?.trim()
            if (!text.isNullOrEmpty() && text.toIntOrNull() == null) return text
            return prettifyBadgeName(badge.type)
        }
        option?.v2?.let { badge -> return prettifyBadgeName(badge.name.orEmpty()) }

        return prettifyBadgeName(key.removePrefix(ChatterClassifier.V2_PREFIX))
    }

    /** "sub_gifter" -> "Sub Gifter". */
    private fun prettifyBadgeName(raw: String): String = raw
        .split('_', '-')
        .filter { it.isNotEmpty() }
        .joinToString(" ") { part -> part.replaceFirstChar { it.uppercase() } }

    companion object {
        private const val REFRESH_INTERVAL_MS = 2500L

        /** Fraction of the screen the sheet always occupies. */
        private const val SHEET_HEIGHT_RATIO = 0.75
    }
}
