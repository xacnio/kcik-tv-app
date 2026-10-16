/**
 * File: DropsManager.kt
 *
 * Description: Manages the Drops page: active and expired campaigns with the user's progress.
 *
 * Author: Xacnio
 *
 */
package dev.xacnio.kciktv.mobile.ui.drops

import android.util.Log
import android.view.View
import android.widget.ImageButton
import android.widget.TextView
import androidx.lifecycle.LifecycleCoroutineScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout
import dev.xacnio.kciktv.R
import dev.xacnio.kciktv.databinding.ActivityMobilePlayerBinding
import dev.xacnio.kciktv.mobile.InternalBrowserSheet
import dev.xacnio.kciktv.mobile.MobilePlayerActivity
import dev.xacnio.kciktv.shared.data.model.DropCampaign
import dev.xacnio.kciktv.shared.data.prefs.AppPreferences
import dev.xacnio.kciktv.shared.data.repository.ChannelRepository
import dev.xacnio.kciktv.shared.util.TimeUtils
import kotlinx.coroutines.async
import kotlinx.coroutines.launch

class DropsManager(
    private val activity: MobilePlayerActivity,
    private val binding: ActivityMobilePlayerBinding,
    private val prefs: AppPreferences,
    private val repository: ChannelRepository,
    private val lifecycleScope: LifecycleCoroutineScope
) {
    private companion object {
        const val TAG = "DropsManager"
        const val STATUS_EXPIRED = "expired"
        const val TAB_ACTIVE = 0xFF53FC18.toInt()
        const val TAB_INACTIVE = 0xFF888888.toInt()
    }

    var isVisible = false
        private set

    private var isSetup = false
    private var campaigns: List<DropCampaign>? = null
    // User progress by campaign id, refreshed on every open
    private var progress: Map<String, dev.xacnio.kciktv.shared.data.model.DropCampaignProgress> = emptyMap()
    private var showEnded = false
    private var adapter: DropsAdapter? = null
    private var screenBefore: MobilePlayerActivity.AppScreen = MobilePlayerActivity.AppScreen.HOME
    private var headerVisibilityBefore = View.VISIBLE

    private val container get() = binding.dropsContainer

    fun show() {
        if (!isSetup) setup()

        // Remember the previous screen to restore on close
        if (!isVisible) {
            screenBefore = activity.currentScreen
            headerVisibilityBefore = binding.mobileHeader.visibility
        }

        binding.mobileHeader.visibility = View.GONE
        container.root.visibility = View.VISIBLE
        isVisible = true
        activity.setCurrentScreen(MobilePlayerActivity.AppScreen.DROPS)

        if (campaigns == null) {
            load()
        } else {
            applyData()
            refreshProgress()
        }
    }

    fun close() {
        if (!isVisible) return
        container.root.visibility = View.GONE
        isVisible = false
        binding.mobileHeader.visibility = headerVisibilityBefore
        activity.setCurrentScreen(screenBefore)
    }

    /** Hides the page without restoring the previous screen (caller is navigating elsewhere). */
    fun hide() {
        if (!isVisible) return
        container.root.visibility = View.GONE
        isVisible = false
    }

    private fun setup() {
        val recyclerView = container.root.findViewById<RecyclerView>(R.id.dropsRecyclerView)
        val dropsAdapter = DropsAdapter(
            onJoinClick = { openCategory(it) },
            onConnectClick = { openConnect(it) },
            onDetailsClick = { openDetails(it) },
            onClaimClick = { campaign, rewardId -> claimReward(campaign, rewardId) }
        )
        adapter = dropsAdapter
        recyclerView.adapter = dropsAdapter
        recyclerView.layoutManager = LinearLayoutManager(activity)

        container.root.findViewById<ImageButton>(R.id.btnDropsBack).setOnClickListener { close() }
        container.root.findViewById<ImageButton>(R.id.btnDropsClose).setOnClickListener { close() }
        container.root.findViewById<View>(R.id.dropsTabActive).setOnClickListener { selectTab(ended = false) }
        container.root.findViewById<View>(R.id.dropsTabEnded).setOnClickListener { selectTab(ended = true) }

        container.root.findViewById<SwipeRefreshLayout>(R.id.dropsSwipeRefresh).apply {
            setColorSchemeColors(TAB_ACTIVE)
            setOnRefreshListener { load(pullToRefresh = true) }
        }

        isSetup = true
    }

    /** Loads campaigns and progress; pull-to-refresh keeps the list and skips the cache. */
    private fun load(pullToRefresh: Boolean = false) {
        val shimmer = container.dropsShimmer.root
        val message = container.root.findViewById<TextView>(R.id.dropsMessage)
        val recyclerView = container.root.findViewById<View>(R.id.dropsRecyclerView)
        val swipeRefresh = container.root.findViewById<SwipeRefreshLayout>(R.id.dropsSwipeRefresh)

        if (!pullToRefresh) {
            shimmer.visibility = View.VISIBLE
            message.visibility = View.GONE
            recyclerView.visibility = View.GONE
        }

        lifecycleScope.launch {
            val token = prefs.authToken
            val progressDeferred = token?.let { async { repository.getDropProgress(it) } }
            val result = repository.getDropCampaigns(token, forceRefresh = pullToRefresh)
            val progressResult = progressDeferred?.await()
            swipeRefresh.isRefreshing = false
            if (!isVisible) return@launch
            shimmer.visibility = View.GONE

            result.onSuccess { loaded ->
                campaigns = loaded
                progressResult?.getOrNull()?.let { progress = it }
                applyData()
            }.onFailure {
                Log.e(TAG, "Failed to load drop campaigns", it)
                if (pullToRefresh && campaigns != null) {
                    // Keep what's already on screen
                    android.widget.Toast.makeText(activity, R.string.drops_load_error, android.widget.Toast.LENGTH_SHORT).show()
                    return@onFailure
                }
                recyclerView.visibility = View.GONE
                message.text = activity.getString(R.string.drops_load_error)
                message.visibility = View.VISIBLE
                message.setOnClickListener { load() }
            }
        }
    }

    private fun refreshProgress() {
        val token = prefs.authToken ?: return
        lifecycleScope.launch {
            val latest = repository.getDropProgress(token).getOrNull() ?: return@launch
            progress = latest
            if (isVisible) applyData()
        }
    }

    private fun selectTab(ended: Boolean) {
        if (showEnded == ended) return
        showEnded = ended
        applyData()
        container.root.findViewById<RecyclerView>(R.id.dropsRecyclerView).scrollToPosition(0)
    }

    private fun applyData() {
        val message = container.root.findViewById<TextView>(R.id.dropsMessage)
        val recyclerView = container.root.findViewById<View>(R.id.dropsRecyclerView)
        val tabActive = container.root.findViewById<TextView>(R.id.dropsTabActive)
        val tabEnded = container.root.findViewById<TextView>(R.id.dropsTabEnded)

        val (ended, active) = campaigns.orEmpty().partition { it.status == STATUS_EXPIRED }

        tabActive.text = "${activity.getString(R.string.drops_tab_active)} (${active.size})"
        tabEnded.text = "${activity.getString(R.string.drops_tab_ended)} (${ended.size})"
        tabActive.setTextColor(if (showEnded) TAB_INACTIVE else TAB_ACTIVE)
        tabEnded.setTextColor(if (showEnded) TAB_ACTIVE else TAB_INACTIVE)

        val shown = if (showEnded) {
            // Most recently ended first
            ended.sortedByDescending { TimeUtils.parseIso8601ToMillis(it.endsAt) ?: 0L }
        } else {
            // Soonest ending first
            active.sortedBy { TimeUtils.parseIso8601ToMillis(it.endsAt) ?: Long.MAX_VALUE }
        }

        if (shown.isEmpty()) {
            recyclerView.visibility = View.GONE
            message.text = activity.getString(if (showEnded) R.string.drops_empty_ended else R.string.drops_empty)
            message.setOnClickListener(null)
            message.visibility = View.VISIBLE
            return
        }
        message.visibility = View.GONE
        recyclerView.visibility = View.VISIBLE

        val items = mutableListOf<DropsAdapter.Item>()
        if (!showEnded) items.add(DropsAdapter.Item.Intro)
        shown.forEach { items.add(DropsAdapter.Item.Campaign(it, progress[it.id])) }
        adapter?.submit(items)
    }

    /** Opens the campaign's category on its Drops tab. */
    private fun openCategory(campaign: DropCampaign) {
        val slug = campaign.category?.slug ?: return
        activity.browseManager.openCategoryBySlug(slug, dropsOnly = true, returnToDrops = true) { hide() }
    }

    /** Opens the organizer's account linking page. */
    private fun openConnect(campaign: DropCampaign) {
        openInBrowser(campaign.connectUrl)
    }

    /** Claims a fully watched reward and reloads progress. */
    private fun claimReward(campaign: DropCampaign, rewardId: String) {
        val token = prefs.authToken ?: return
        lifecycleScope.launch {
            repository.claimDropReward(token, campaign.id, rewardId)
                .onSuccess {
                    android.widget.Toast.makeText(activity, R.string.drops_claim_success, android.widget.Toast.LENGTH_SHORT).show()
                    refreshProgress()
                }
                .onFailure {
                    Log.e(TAG, "Failed to claim drop reward $rewardId", it)
                    android.widget.Toast.makeText(activity, R.string.drops_claim_error, android.widget.Toast.LENGTH_SHORT).show()
                    // Re-enable the button
                    if (isVisible) applyData()
                }
        }
    }

    /** The organizer's page about the campaign (Kick's "More details"). */
    private fun openDetails(campaign: DropCampaign) {
        openInBrowser(campaign.url)
    }

    private fun openInBrowser(url: String?) {
        if (url.isNullOrBlank()) return
        InternalBrowserSheet.newInstance(url)
            .show(activity.supportFragmentManager, "InternalBrowserSheet")
    }
}
