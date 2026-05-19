/**
 * File: RewardQueueManager.kt
 *
 * Description: Moderator-facing reward queue sheet.
 *   Panel 0: vertical reward list with counts from /redemption-metadata.
 *   Panel 1: redemptions for the selected reward, with cursor-based pagination and item selection.
 *
 * Author: Xacnio
 *
 */
package dev.xacnio.kciktv.mobile.ui.chat

import android.util.Log
import android.view.View
import android.widget.Button
import android.widget.CheckBox
import android.widget.ImageButton
import android.widget.TextView
import android.widget.ViewFlipper
import androidx.appcompat.widget.SwitchCompat
import android.view.animation.AnimationUtils
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog
import dev.xacnio.kciktv.R
import dev.xacnio.kciktv.databinding.ActivityMobilePlayerBinding
import dev.xacnio.kciktv.mobile.MobilePlayerActivity
import dev.xacnio.kciktv.shared.data.model.ChannelReward
import dev.xacnio.kciktv.shared.data.model.RedemptionMetadataData
import dev.xacnio.kciktv.shared.data.model.RedemptionsListData
import dev.xacnio.kciktv.shared.data.model.RewardRedemption
import dev.xacnio.kciktv.shared.data.prefs.AppPreferences
import dev.xacnio.kciktv.shared.ui.adapter.RewardChipAdapter
import dev.xacnio.kciktv.shared.ui.adapter.RewardRedemptionAdapter
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class RewardQueueManager(
    private val activity: MobilePlayerActivity,
    private val binding: ActivityMobilePlayerBinding,
    private val prefs: AppPreferences
) {
    private companion object {
        const val TAG = "RewardQueueManager"
    }

    // --- Persistent state (survives sheet close/reopen within same channel) ------------------

    private val rewards = mutableListOf<ChannelReward>()
    /** rewardId -> pending count from metadata; null key = total */
    private val rewardCounts = mutableMapOf<String?, Int>()

    private var channelSlug: String = ""
    /** null = "Tümü" */
    private var activeFilter: String? = null

    // --- Pagination state (reset on each navigateToRedemptions call) -------------------------

    /** Token history: tokenHistory[i] is the token used to fetch page (i+1). Index 0 = null (first page). */
    private val tokenHistory = mutableListOf<String?>()
    /** Next-page token from the last fetched page; null = no more pages. */
    private var nextToken: String? = null
    private var currentPage: Int = 0  // 0-based index into tokenHistory
    /** Items returned on the first page; used to estimate total page count. */
    private var pageSize: Int = 0

    // --- Sheet-scoped views (null when sheet is closed) ---------------------------------------

    private var dialog: BottomSheetDialog? = null
    private var chipAdapter: RewardChipAdapter? = null
    private var redemptionAdapter: RewardRedemptionAdapter? = null
    private var emptyState: View? = null
    private var loadingIndicator: View? = null
    private var flipper: ViewFlipper? = null
    private var paginationText: TextView? = null
    private var prevButton: ImageButton? = null
    private var nextButton: ImageButton? = null
    private var selectAllCheckbox: CheckBox? = null
    private var completeAllButton: Button? = null
    private var rejectAllButton: Button? = null
    private var pauseSwitch: SwitchCompat? = null
    private var pauseRow: View? = null
    private var pauseDivider: View? = null
    private var pauseSwitchUpdating = false

    // --- Public API --------------------------------------------------------------------------

    fun resetForChannel(slug: String = "") {
        channelSlug = slug
        rewards.clear()
        rewardCounts.clear()
        activeFilter = null
        resetPagination()
        updateBadge()
    }

    fun pendingCount(): Int = rewardCounts[null] ?: 0

    fun onRedemptionCreated(redemption: RewardRedemption) {
        rewardCounts[null] = (rewardCounts[null] ?: 0) + 1
        rewardCounts[redemption.rewardId] = (rewardCounts[redemption.rewardId] ?: 0) + 1
        updateBadge()
        rebuildChips()
    }

    fun onRedemptionResolved(redemptionId: String) {
        rewardCounts[null] = ((rewardCounts[null] ?: 1) - 1).coerceAtLeast(0)
        updateBadge()
        rebuildChips()
    }

    // --- Sheet -------------------------------------------------------------------------------

    fun showSheet() {
        val dialog = BottomSheetDialog(activity, R.style.Theme_KcikTV_Dialog)
        val view = activity.layoutInflater.inflate(R.layout.bottom_sheet_reward_queue, null)
        dialog.setContentView(view)

        dialog.setCancelable(false)
        dialog.setOnShowListener {
            val sheet = (it as BottomSheetDialog)
                .findViewById<View>(com.google.android.material.R.id.design_bottom_sheet)
            sheet?.let { bs -> BottomSheetBehavior.from(bs).isHideable = false }
        }

        flipper = view.findViewById(R.id.rewardQueueFlipper)
        emptyState = view.findViewById(R.id.rewardQueueEmptyState)
        loadingIndicator = view.findViewById(R.id.rewardQueueLoading)
        paginationText = view.findViewById(R.id.redemptionsPagination)
        prevButton = view.findViewById(R.id.redemptionsPrevButton)
        nextButton = view.findViewById(R.id.redemptionsNextButton)
        selectAllCheckbox = view.findViewById(R.id.redemptionsSelectAll)
        completeAllButton = view.findViewById(R.id.rewardQueueCompleteAllButton)
        rejectAllButton = view.findViewById(R.id.rewardQueueRejectAllButton)
        pauseSwitch = view.findViewById(R.id.rewardPauseSwitch)
        pauseRow = view.findViewById(R.id.rewardPauseRow)
        pauseDivider = view.findViewById(R.id.rewardPauseDivider)

        pauseSwitch?.setOnCheckedChangeListener { _, checked ->
            if (!pauseSwitchUpdating) {
                activeFilter?.let { handlePauseToggle(it, checked) }
            }
        }

        // Panel 0 — reward list
        val chipsRecycler = view.findViewById<RecyclerView>(R.id.rewardChipsRecycler)
        chipAdapter = RewardChipAdapter { selectedId -> navigateToRedemptions(selectedId, view) }
        chipsRecycler.layoutManager = LinearLayoutManager(activity)
        chipsRecycler.adapter = chipAdapter
        rebuildChips()

        // Panel 1 — redemptions
        val redemptionsRecycler = view.findViewById<RecyclerView>(R.id.rewardRedemptionsRecycler)
        redemptionAdapter = RewardRedemptionAdapter(
            timeAgoFormatter = ::formatTimeAgo,
            onAccept = { handleAccept(it) },
            onReject = { handleReject(it) },
            onSelectionChanged = { selected, total -> onSelectionChanged(selected, total) }
        )
        redemptionsRecycler.layoutManager = LinearLayoutManager(activity)
        redemptionsRecycler.adapter = redemptionAdapter

        // Pagination buttons
        prevButton?.setOnClickListener { navigatePage(forward = false) }
        nextButton?.setOnClickListener { navigatePage(forward = true) }

        // Select all checkbox
        selectAllCheckbox?.setOnCheckedChangeListener { _, checked ->
            if (checked) redemptionAdapter?.selectAll() else redemptionAdapter?.clearSelection()
        }

        // Action buttons — work on selection (or all if none selected)
        completeAllButton?.setOnClickListener { handleCompleteAction() }
        rejectAllButton?.setOnClickListener { handleRejectAction() }

        view.findViewById<View>(R.id.redemptionsBackButton)?.setOnClickListener { navigateBack() }
        view.findViewById<View>(R.id.rewardQueueCloseButton)?.setOnClickListener { dialog.dismiss() }
        view.findViewById<View>(R.id.rewardQueueRefreshButton)?.setOnClickListener { refresh() }

        dialog.setOnDismissListener {
            chipAdapter = null
            redemptionAdapter = null
            emptyState = null
            loadingIndicator = null
            flipper = null
            paginationText = null
            prevButton = null
            nextButton = null
            selectAllCheckbox = null
            completeAllButton = null
            rejectAllButton = null
            pauseSwitch = null
            pauseRow = null
            pauseDivider = null
            this.dialog = null
            activeFilter = null
            resetPagination()
        }
        this.dialog = dialog
        dialog.show()

        showLoading(true)
        activity.lifecycleScope.launch {
            runCatching {
                val rewardsResult = fetchRewards()
                val metaResult = fetchMetadata()
                withContext(Dispatchers.Main) {
                    if (rewardsResult != null) { rewards.clear(); rewards.addAll(rewardsResult) }
                    if (metaResult != null) applyMetadata(metaResult)
                    rebuildChips()
                }
            }.onFailure { Log.w(TAG, "Initial fetch failed", it) }
            withContext(Dispatchers.Main) { showLoading(false) }
        }
    }

    // --- Navigation --------------------------------------------------------------------------

    private fun navigateToRedemptions(rewardId: String?, view: View) {
        activeFilter = rewardId
        resetPagination()
        redemptionAdapter?.clearSelection()

        val reward = rewardId?.let { id -> rewards.find { it.id == id } }
        val title = reward?.title ?: rewardId ?: activity.getString(R.string.reward_queue_all)
        view.findViewById<TextView>(R.id.redemptionsRewardTitle)?.text = title

        val showPause = reward != null
        val pauseVis = if (showPause) View.VISIBLE else View.GONE
        pauseRow?.visibility = pauseVis
        pauseDivider?.visibility = pauseVis
        if (showPause) {
            pauseSwitchUpdating = true
            pauseSwitch?.isChecked = reward!!.isPaused
            pauseSwitchUpdating = false
        }

        flipper?.apply {
            inAnimation = AnimationUtils.loadAnimation(activity, R.anim.slide_in_right)
            outAnimation = AnimationUtils.loadAnimation(activity, R.anim.slide_out_left)
            showNext()
        }

        loadPage(null)
    }

    private fun navigateBack() {
        activeFilter = null
        resetPagination()
        redemptionAdapter?.clearSelection()
        flipper?.apply {
            inAnimation = AnimationUtils.loadAnimation(activity, R.anim.slide_in_left)
            outAnimation = AnimationUtils.loadAnimation(activity, R.anim.slide_out_right)
            showPrevious()
        }
    }

    private fun navigatePage(forward: Boolean) {
        if (forward) {
            val token = nextToken ?: return
            if (currentPage == tokenHistory.size - 1) tokenHistory.add(token)
            currentPage++
            loadPage(tokenHistory[currentPage])
        } else {
            if (currentPage <= 0) return
            currentPage--
            loadPage(tokenHistory[currentPage])
        }
    }

    private fun loadPage(token: String?) {
        showLoading(true)
        updatePagination(loading = true)
        activity.lifecycleScope.launch {
            val result = runCatching { fetchRedemptions(activeFilter, token) }.getOrNull()
            withContext(Dispatchers.Main) {
                if (result != null) {
                    redemptionAdapter?.submit(result.redemptions)
                    emptyState?.visibility = if (result.redemptions.isEmpty()) View.VISIBLE else View.GONE
                    nextToken = result.nextPageToken?.takeIf { it.isNotEmpty() }
                    if (tokenHistory.isEmpty()) tokenHistory.add(token)
                    if (pageSize == 0 && result.redemptions.isNotEmpty()) pageSize = result.redemptions.size
                } else {
                    emptyState?.visibility = View.VISIBLE
                }
                selectAllCheckbox?.isChecked = false
                updatePagination(loading = false)
                showLoading(false)
            }
        }
    }

    // --- Selection ---------------------------------------------------------------------------

    private fun onSelectionChanged(selectedCount: Int, totalCount: Int) {
        val allChecked = selectedCount > 0 && selectedCount == totalCount
        selectAllCheckbox?.setOnCheckedChangeListener(null)
        selectAllCheckbox?.isChecked = allChecked
        selectAllCheckbox?.setOnCheckedChangeListener { _, checked ->
            if (checked) redemptionAdapter?.selectAll() else redemptionAdapter?.clearSelection()
        }
        updateActionButtons(selectedCount)
    }

    private fun updateActionButtons(selectedCount: Int) {
        if (selectedCount > 0) {
            completeAllButton?.text = activity.getString(R.string.reward_queue_complete_selected)
            rejectAllButton?.text = activity.getString(R.string.reward_queue_reject_selected)
        } else {
            completeAllButton?.text = activity.getString(R.string.reward_queue_complete_all)
            rejectAllButton?.text = activity.getString(R.string.reward_queue_reject_all)
        }
    }

    // --- Internals ---------------------------------------------------------------------------

    private fun refresh() {
        showLoading(true)
        activity.lifecycleScope.launch {
            val rewardsResult = runCatching { fetchRewards() }.getOrNull()
            val metaResult = runCatching { fetchMetadata() }.getOrNull()
            withContext(Dispatchers.Main) {
                if (rewardsResult != null) { rewards.clear(); rewards.addAll(rewardsResult) }
                if (metaResult != null) applyMetadata(metaResult)
                rebuildChips()
                if (flipper?.displayedChild == 1) {
                    loadPage(tokenHistory.getOrNull(currentPage))
                } else {
                    showLoading(false)
                }
            }
        }
    }

    private fun resetPagination() {
        tokenHistory.clear()
        nextToken = null
        currentPage = 0
        pageSize = 0
    }

    private fun updatePagination(loading: Boolean) {
        val totalItems = if (activeFilter == null) rewardCounts[null] ?: 0
                         else rewardCounts[activeFilter] ?: 0
        val totalPages = when {
            totalItems == 0 -> 0
            pageSize > 0 -> kotlin.math.ceil(totalItems.toDouble() / pageSize).toInt()
            else -> currentPage + 1
        }
        val pageNum = if (totalItems == 0) 0 else currentPage + 1
        paginationText?.text = "$pageNum / $totalPages"
        prevButton?.isEnabled = !loading && currentPage > 0
        prevButton?.alpha = if (!loading && currentPage > 0) 1f else 0.35f
        nextButton?.isEnabled = !loading && nextToken != null
        nextButton?.alpha = if (!loading && nextToken != null) 1f else 0.35f
    }

    private fun applyMetadata(meta: RedemptionMetadataData) {
        rewardCounts.clear()
        meta.redemptions.forEach { item -> rewardCounts[item.rewardId] = item.count }
        rewardCounts[null] = rewardCounts.values.sum()
        updateBadge()
        if (flipper?.displayedChild == 1) updatePagination(loading = false)
    }

    private fun rebuildChips() {
        val adapter = chipAdapter ?: return
        val chips = mutableListOf<RewardChipAdapter.Chip>().apply {
            add(RewardChipAdapter.Chip(null, activity.getString(R.string.reward_queue_all), rewardCounts[null] ?: 0))
            rewards.filter { it.isEnabled }.forEach { r ->
                add(RewardChipAdapter.Chip(r.id, r.title, rewardCounts[r.id] ?: 0, r.isPaused))
            }
        }
        adapter.submit(chips)
        adapter.setSelected(activeFilter)
    }

    private fun updateBadge() {
        val badge = binding.rewardQueueBadge
        val container = binding.rewardQueueButton
        if (container.visibility != View.VISIBLE) { badge.visibility = View.GONE; return }
        val count = rewardCounts[null] ?: 0
        if (count <= 0) {
            badge.visibility = View.GONE
        } else {
            badge.text = if (count > 99) "99+" else count.toString()
            badge.visibility = View.VISIBLE
        }
    }

    private fun showLoading(show: Boolean) {
        loadingIndicator?.visibility = if (show) View.VISIBLE else View.GONE
    }

    private fun handleAccept(r: RewardRedemption) {
        removeItemOptimistic(r)
        activity.lifecycleScope.launch {
            val ok = runCatching { acceptRedemption(r) }.getOrDefault(false)
            val meta = if (ok) runCatching { fetchMetadata() }.getOrNull() else null
            withContext(Dispatchers.Main) {
                if (ok) {
                    if (meta != null) applyMetadata(meta)
                } else {
                    loadPage(tokenHistory.getOrNull(currentPage))
                    showToast(R.string.reward_queue_action_failed)
                }
            }
        }
    }

    private fun handleReject(r: RewardRedemption) {
        removeItemOptimistic(r)
        activity.lifecycleScope.launch {
            val ok = runCatching { rejectRedemption(r) }.getOrDefault(false)
            val meta = if (ok) runCatching { fetchMetadata() }.getOrNull() else null
            withContext(Dispatchers.Main) {
                if (ok) {
                    if (meta != null) applyMetadata(meta)
                } else {
                    loadPage(tokenHistory.getOrNull(currentPage))
                    showToast(R.string.reward_queue_action_failed)
                }
            }
        }
    }

    private fun removeItemOptimistic(r: RewardRedemption) {
        redemptionAdapter?.removeItem(r.id)
        emptyState?.visibility = if ((redemptionAdapter?.itemCount ?: 0) == 0) View.VISIBLE else View.GONE
        rewardCounts[null] = ((rewardCounts[null] ?: 1) - 1).coerceAtLeast(0)
        rewardCounts[r.rewardId] = ((rewardCounts[r.rewardId] ?: 1) - 1).coerceAtLeast(0)
        updateBadge(); rebuildChips(); updatePagination(loading = false)
    }

    private fun handleCompleteAction() {
        val adapter = redemptionAdapter ?: return
        if (adapter.getSelectedCount() == 0) adapter.selectAll()
        val targets = adapter.getSelectedItems()
        if (targets.isEmpty()) return
        val delta = targets.size
        rewardCounts[null] = ((rewardCounts[null] ?: delta) - delta).coerceAtLeast(0)
        activeFilter?.let { id -> rewardCounts[id] = ((rewardCounts[id] ?: delta) - delta).coerceAtLeast(0) }
        adapter.clearSelection()
        updateBadge(); rebuildChips(); updatePagination(loading = false)
        activity.lifecycleScope.launch {
            runCatching { completeAll(targets) }
            val meta = runCatching { fetchMetadata() }.getOrNull()
            withContext(Dispatchers.Main) {
                if (meta != null) applyMetadata(meta)
                loadPage(tokenHistory.getOrNull(currentPage))
            }
        }
    }

    private fun handleRejectAction() {
        val adapter = redemptionAdapter ?: return
        if (adapter.getSelectedCount() == 0) adapter.selectAll()
        val targets = adapter.getSelectedItems()
        if (targets.isEmpty()) return
        val delta = targets.size
        rewardCounts[null] = ((rewardCounts[null] ?: delta) - delta).coerceAtLeast(0)
        activeFilter?.let { id -> rewardCounts[id] = ((rewardCounts[id] ?: delta) - delta).coerceAtLeast(0) }
        adapter.clearSelection()
        updateBadge(); rebuildChips(); updatePagination(loading = false)
        activity.lifecycleScope.launch {
            runCatching { rejectAll(targets) }
            val meta = runCatching { fetchMetadata() }.getOrNull()
            withContext(Dispatchers.Main) {
                if (meta != null) applyMetadata(meta)
                loadPage(tokenHistory.getOrNull(currentPage))
            }
        }
    }

    private fun handlePauseToggle(rewardId: String, newPaused: Boolean) {
        val index = rewards.indexOfFirst { it.id == rewardId }
        if (index < 0) return
        val oldPaused = rewards[index].isPaused
        rewards[index] = rewards[index].copy(isPaused = newPaused)
        rebuildChips()
        activity.lifecycleScope.launch {
            val ok = runCatching { setRewardPaused(rewardId, newPaused) }.getOrDefault(false)
            if (!ok) withContext(Dispatchers.Main) {
                rewards[index] = rewards[index].copy(isPaused = oldPaused)
                rebuildChips()
                pauseSwitchUpdating = true
                pauseSwitch?.isChecked = oldPaused
                pauseSwitchUpdating = false
                showToast(R.string.reward_queue_action_failed)
            }
        }
    }

    private fun showToast(resId: Int) {
        android.widget.Toast.makeText(activity, activity.getString(resId), android.widget.Toast.LENGTH_SHORT).show()
    }

    private fun formatTimeAgo(createdAt: String?): String {
        if (createdAt.isNullOrBlank()) return ""
        val ts = try { java.time.Instant.parse(createdAt).toEpochMilli() } catch (_: Exception) { return "" }
        val diffMs = (System.currentTimeMillis() - ts).coerceAtLeast(0)
        val minutes = diffMs / 60_000; val hours = minutes / 60; val days = hours / 24
        val res = activity.resources
        return when {
            minutes < 1 -> res.getString(R.string.reward_queue_just_now)
            minutes < 60 -> res.getQuantityString(R.plurals.reward_queue_minutes_ago, minutes.toInt(), minutes.toInt())
            hours < 24 -> res.getQuantityString(R.plurals.reward_queue_hours_ago, hours.toInt(), hours.toInt())
            else -> res.getQuantityString(R.plurals.reward_queue_days_ago, days.toInt(), days.toInt())
        }
    }

    // --- API calls ---------------------------------------------------------------------------

    private suspend fun fetchRewards(): List<ChannelReward>? {
        val slug = channelSlug.takeIf { it.isNotEmpty() } ?: return null
        val token = prefs.authToken ?: return null
        return activity.repository.getCustomRewards(slug, token).getOrNull()
    }

    private suspend fun fetchMetadata(): RedemptionMetadataData? {
        val slug = channelSlug.takeIf { it.isNotEmpty() } ?: return null
        val token = prefs.authToken ?: return null
        return activity.repository.getRedemptionMetadata(slug, token).getOrNull()
    }

    private suspend fun fetchRedemptions(rewardId: String?, pageToken: String?): RedemptionsListData? {
        val slug = channelSlug.takeIf { it.isNotEmpty() } ?: return null
        val token = prefs.authToken ?: return null
        return activity.repository.getPendingRedemptions(slug, token, rewardId, pageToken).getOrNull()
    }

    private suspend fun acceptRedemption(r: RewardRedemption): Boolean {
        val slug = channelSlug.takeIf { it.isNotEmpty() } ?: return false
        val token = prefs.authToken ?: return false
        return activity.repository.acceptRedemptions(slug, listOf(r.id), token).isSuccess
    }

    private suspend fun rejectRedemption(r: RewardRedemption): Boolean {
        val slug = channelSlug.takeIf { it.isNotEmpty() } ?: return false
        val token = prefs.authToken ?: return false
        return activity.repository.rejectRedemptions(slug, listOf(r.id), token).isSuccess
    }

    private suspend fun completeAll(redemptions: List<RewardRedemption>): Boolean {
        val slug = channelSlug.takeIf { it.isNotEmpty() } ?: return false
        val token = prefs.authToken ?: return false
        return activity.repository.acceptRedemptions(slug, redemptions.map { it.id }, token).isSuccess
    }

    private suspend fun rejectAll(redemptions: List<RewardRedemption>): Boolean {
        val slug = channelSlug.takeIf { it.isNotEmpty() } ?: return false
        val token = prefs.authToken ?: return false
        return activity.repository.rejectRedemptions(slug, redemptions.map { it.id }, token).isSuccess
    }

    private suspend fun setRewardPaused(rewardId: String, paused: Boolean): Boolean {
        val slug = channelSlug.takeIf { it.isNotEmpty() } ?: return false
        val token = prefs.authToken ?: return false
        return activity.repository.setRewardPaused(slug, rewardId, paused, token).isSuccess
    }
}
