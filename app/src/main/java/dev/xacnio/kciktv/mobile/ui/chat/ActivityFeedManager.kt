package dev.xacnio.kciktv.mobile.ui.chat

import android.util.Log
import android.view.View
import android.widget.Toast
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.chip.Chip
import dev.xacnio.kciktv.R
import dev.xacnio.kciktv.mobile.MobilePlayerActivity
import dev.xacnio.kciktv.shared.data.api.RetrofitClient
import dev.xacnio.kciktv.shared.data.model.ChannelEvent
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

class ActivityFeedManager(private val activity: MobilePlayerActivity) {

    private val prefs get() = activity.prefs
    private val TAG = "ActivityFeedManager"

    // Categories controlled by "All" — Unfollowers is always independent
    private val allControlledCategories = setOf(
        ActivityEventCategory.FOLLOWERS,
        ActivityEventCategory.SUBSCRIBERS,
        ActivityEventCategory.GIFTED_SUBS,
        ActivityEventCategory.HOSTS,
        ActivityEventCategory.REWARDS,
        ActivityEventCategory.FOLLOWER_GOALS,
        ActivityEventCategory.SUBSCRIBER_GOALS,
        ActivityEventCategory.CHANNEL_ENGAGEMENT
    )

    // --- Cache state (persists across sheet open/close for same channel) ----------------------

    private var cachedEvents: List<ChannelEvent>? = null
    private var prefetchJob: Job? = null
    private var isPrefetching = false

    fun resetCache() {
        prefetchJob?.cancel()
        prefetchJob = null
        cachedEvents = null
        isPrefetching = false
    }

    fun prefetch(slug: String) {
        if (cachedEvents != null || isPrefetching) return
        val token = prefs.authToken ?: return
        isPrefetching = true
        prefetchJob = activity.lifecycleScope.launch {
            try {
                val response = RetrofitClient.channelService.getChannelEvents(slug, "Bearer $token")
                if (response.isSuccessful) {
                    cachedEvents = response.body()?.data?.events ?: emptyList()
                    Log.d(TAG, "Prefetched ${cachedEvents?.size} events for $slug")
                }
            } catch (e: Exception) {
                Log.w(TAG, "Prefetch failed", e)
            } finally {
                isPrefetching = false
            }
        }
    }

    // --- Sheet --------------------------------------------------------------------------------

    fun showActivityFeed(slug: String) {
        val dialog = BottomSheetDialog(activity, R.style.Theme_KcikTV_Dialog)
        val view = activity.layoutInflater.inflate(R.layout.bottom_sheet_activity_feed, null)
        dialog.setContentView(view)
        activity.trackBottomSheet(dialog)

        val sheet = dialog.findViewById<View>(com.google.android.material.R.id.design_bottom_sheet)
        sheet?.let { bs ->
            val behavior = BottomSheetBehavior.from(bs)
            val screenHeight = activity.resources.displayMetrics.heightPixels
            behavior.expandedOffset = (screenHeight * 0.35).toInt()
            behavior.maxHeight = (screenHeight * 0.65).toInt()
            behavior.isFitToContents = false
            behavior.skipCollapsed = true
            behavior.isHideable = false
            behavior.isDraggable = false
            behavior.state = BottomSheetBehavior.STATE_EXPANDED
            behavior.addBottomSheetCallback(object : BottomSheetBehavior.BottomSheetCallback() {
                override fun onStateChanged(bottomSheet: View, newState: Int) {
                    if (newState != BottomSheetBehavior.STATE_EXPANDED &&
                        newState != BottomSheetBehavior.STATE_DRAGGING &&
                        newState != BottomSheetBehavior.STATE_SETTLING) {
                        behavior.state = BottomSheetBehavior.STATE_EXPANDED
                    }
                }
                override fun onSlide(bottomSheet: View, slideOffset: Float) {}
            })
        }

        val loading = view.findViewById<View>(R.id.activityFeedLoading)
        val recycler = view.findViewById<androidx.recyclerview.widget.RecyclerView>(R.id.activityFeedRecycler)
        val emptyView = view.findViewById<View>(R.id.activityFeedEmpty)
        val errorView = view.findViewById<View>(R.id.activityFeedError)
        val closeButton = view.findViewById<View>(R.id.activityFeedClose)

        val chipAll = view.findViewById<Chip>(R.id.filterChipAll)
        val chipUnfollowers = view.findViewById<Chip>(R.id.filterChipUnfollowers)
        val categoryChips: Map<ActivityEventCategory, Chip> = mapOf(
            ActivityEventCategory.FOLLOWERS to view.findViewById(R.id.filterChipFollowers),
            ActivityEventCategory.SUBSCRIBERS to view.findViewById(R.id.filterChipSubscribers),
            ActivityEventCategory.GIFTED_SUBS to view.findViewById(R.id.filterChipGiftedSubs),
            ActivityEventCategory.HOSTS to view.findViewById(R.id.filterChipHosts),
            ActivityEventCategory.REWARDS to view.findViewById(R.id.filterChipRewards),
            ActivityEventCategory.FOLLOWER_GOALS to view.findViewById(R.id.filterChipFollowerGoals),
            ActivityEventCategory.SUBSCRIBER_GOALS to view.findViewById(R.id.filterChipSubscriberGoals),
            ActivityEventCategory.CHANNEL_ENGAGEMENT to view.findViewById(R.id.filterChipChannelEngagement)
        )

        val adapter = ActivityFeedAdapter(activity)
        recycler.layoutManager = LinearLayoutManager(activity)
        recycler.adapter = adapter

        var isUpdating = false

        fun refreshList() {
            adapter.applyFilter()
            if (adapter.itemCount == 0) {
                recycler.visibility = View.GONE
                emptyView.visibility = View.VISIBLE
            } else {
                recycler.visibility = View.VISIBLE
                emptyView.visibility = View.GONE
            }
        }

        fun syncAllChip() {
            val allEnabled = allControlledCategories.all { it in adapter.enabledCategories }
            isUpdating = true
            chipAll.isChecked = allEnabled
            isUpdating = false
        }

        categoryChips.forEach { (category, chip) ->
            chip.setOnCheckedChangeListener { _, isChecked ->
                if (isUpdating) return@setOnCheckedChangeListener
                if (isChecked) adapter.enabledCategories.add(category)
                else adapter.enabledCategories.remove(category)
                syncAllChip()
                refreshList()
            }
        }

        chipUnfollowers.setOnCheckedChangeListener { _, isChecked ->
            if (isUpdating) return@setOnCheckedChangeListener
            if (isChecked) adapter.enabledCategories.add(ActivityEventCategory.UNFOLLOWERS)
            else adapter.enabledCategories.remove(ActivityEventCategory.UNFOLLOWERS)
            refreshList()
        }

        chipAll.setOnCheckedChangeListener { _, checked ->
            if (isUpdating) return@setOnCheckedChangeListener
            isUpdating = true
            categoryChips.forEach { (category, chip) ->
                chip.isChecked = checked
                if (checked) adapter.enabledCategories.add(category)
                else adapter.enabledCategories.remove(category)
            }
            isUpdating = false
            refreshList()
        }

        closeButton.setOnClickListener { dialog.dismiss() }

        // Show cached data immediately if available
        val cached = cachedEvents
        if (cached != null) {
            if (cached.isEmpty()) {
                emptyView.visibility = View.VISIBLE
            } else {
                adapter.setEvents(cached)
                refreshList()
            }
            dialog.show()
            activity.lifecycleScope.launch {
                try {
                    val bgToken = prefs.authToken ?: return@launch
                    val response = RetrofitClient.channelService.getChannelEvents(slug, "Bearer $bgToken")
                    if (response.isSuccessful) {
                        val events = response.body()?.data?.events ?: emptyList()
                        cachedEvents = events
                        if (dialog.isShowing) {
                            activity.runOnUiThread {
                                if (events.isEmpty()) {
                                    recycler.visibility = View.GONE
                                    emptyView.visibility = View.VISIBLE
                                } else {
                                    adapter.setEvents(events)
                                    refreshList()
                                }
                            }
                        }
                    }
                } catch (_: Exception) {}
            }
            return
        }

        // Still prefetching — wait for it
        if (isPrefetching) {
            loading.visibility = View.VISIBLE
            dialog.show()
            val token = prefs.authToken ?: return
            activity.lifecycleScope.launch {
                try {
                    prefetchJob?.join()
                    activity.runOnUiThread {
                        loading.visibility = View.GONE
                        val events = cachedEvents
                        if (events == null || events.isEmpty()) {
                            emptyView.visibility = View.VISIBLE
                        } else {
                            adapter.setEvents(events)
                            refreshList()
                        }
                    }
                } catch (e: Exception) {
                    activity.runOnUiThread {
                        loading.visibility = View.GONE
                        errorView.visibility = View.VISIBLE
                    }
                }
            }
            return
        }

        // No cache — fetch fresh
        loading.visibility = View.VISIBLE
        dialog.show()
        val token = prefs.authToken ?: return
        activity.lifecycleScope.launch {
            try {
                val response = RetrofitClient.channelService.getChannelEvents(slug, "Bearer $token")
                activity.runOnUiThread {
                    loading.visibility = View.GONE
                    if (response.isSuccessful) {
                        val events = response.body()?.data?.events ?: emptyList()
                        cachedEvents = events
                        if (events.isEmpty()) {
                            emptyView.visibility = View.VISIBLE
                        } else {
                            adapter.setEvents(events)
                            refreshList()
                        }
                    } else {
                        val code = response.code()
                        if (code >= 500) Toast.makeText(activity, activity.getString(R.string.error_server_unavailable, code), Toast.LENGTH_SHORT).show()
                        errorView.visibility = View.VISIBLE
                    }
                }
            } catch (e: Exception) {
                activity.runOnUiThread {
                    loading.visibility = View.GONE
                    Toast.makeText(activity, R.string.error_network_unavailable, Toast.LENGTH_SHORT).show()
                    errorView.visibility = View.VISIBLE
                }
            }
        }
    }
}
