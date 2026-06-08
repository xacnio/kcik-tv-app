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
import dev.xacnio.kciktv.shared.data.model.ChatroomEvent
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

class ModLogManager(private val activity: MobilePlayerActivity) {

    private val prefs get() = activity.prefs
    private val TAG = "ModLogManager"

    private val allCategories = setOf(*ModLogCategory.values())

    // --- Cache ---

    private var cachedEvents: List<ChatroomEvent>? = null
    private var prefetchJob: Job? = null
    private var isPrefetching = false

    fun resetCache() {
        prefetchJob?.cancel()
        prefetchJob = null
        cachedEvents = null
        isPrefetching = false
    }

    fun prefetch() {
        val chatroomId = activity.chatStateManager.currentChatroomId ?: return
        val token = prefs.authToken ?: return
        if (cachedEvents != null || isPrefetching) return
        isPrefetching = true
        prefetchJob = activity.lifecycleScope.launch {
            try {
                val response = RetrofitClient.channelService.getChatroomEvents(chatroomId, "Bearer $token")
                if (response.isSuccessful) {
                    cachedEvents = response.body()?.data?.events ?: emptyList()
                    Log.d(TAG, "Prefetched ${cachedEvents?.size} events for chatroom $chatroomId")
                }
            } catch (e: Exception) {
                Log.w(TAG, "Prefetch failed", e)
            } finally {
                isPrefetching = false
            }
        }
    }

    // --- Sheet ---

    fun showModLog() {
        val chatroomId = activity.chatStateManager.currentChatroomId ?: return
        val token = prefs.authToken ?: return

        val dialog = BottomSheetDialog(activity, R.style.Theme_KcikTV_Dialog)
        val view = activity.layoutInflater.inflate(R.layout.bottom_sheet_mod_log, null)
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

        val loading = view.findViewById<View>(R.id.modLogLoading)
        val recycler = view.findViewById<androidx.recyclerview.widget.RecyclerView>(R.id.modLogRecycler)
        val emptyView = view.findViewById<View>(R.id.modLogEmpty)
        val errorView = view.findViewById<View>(R.id.modLogError)
        val closeButton = view.findViewById<View>(R.id.modLogClose)

        val chipAll = view.findViewById<Chip>(R.id.modLogChipAll)
        val categoryChips: Map<ModLogCategory, Chip> = mapOf(
            ModLogCategory.BANNED_WORDS to view.findViewById(R.id.modLogChipBannedWords),
            ModLogCategory.BANS_AND_UNBANS to view.findViewById(R.id.modLogChipBansUnbans),
            ModLogCategory.CATEGORY_CHANGE to view.findViewById(R.id.modLogChipCategory),
            ModLogCategory.CHAT_MODE_CHANGES to view.findViewById(R.id.modLogChipChatMode),
            ModLogCategory.LANGUAGE_CHANGE to view.findViewById(R.id.modLogChipLanguage),
            ModLogCategory.MATURE_MODE_CHANGE to view.findViewById(R.id.modLogChipMature),
            ModLogCategory.DELETED_MESSAGES to view.findViewById(R.id.modLogChipDeleted),
            ModLogCategory.PIN_AND_UNPIN to view.findViewById(R.id.modLogChipPin),
            ModLogCategory.POLLS to view.findViewById(R.id.modLogChipPolls),
            ModLogCategory.TITLE_CHANGE to view.findViewById(R.id.modLogChipTitle),
            ModLogCategory.USER_TIMEOUTS to view.findViewById(R.id.modLogChipTimeouts)
        )

        val adapter = ModLogAdapter(activity)
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
            val allEnabled = allCategories.all { it in adapter.enabledCategories }
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

        fun showData(events: List<ChatroomEvent>) {
            if (events.isEmpty()) {
                emptyView.visibility = View.VISIBLE
            } else {
                adapter.setEvents(events)
                refreshList()
            }
        }

        val cached = cachedEvents
        if (cached != null) {
            showData(cached)
            dialog.show()
            activity.lifecycleScope.launch {
                try {
                    val response = RetrofitClient.channelService.getChatroomEvents(chatroomId, "Bearer $token")
                    if (response.isSuccessful) {
                        val events = response.body()?.data?.events ?: emptyList()
                        cachedEvents = events
                        if (dialog.isShowing) {
                            activity.runOnUiThread { showData(events) }
                        }
                    }
                } catch (_: Exception) {}
            }
            return
        }

        if (isPrefetching) {
            loading.visibility = View.VISIBLE
            dialog.show()
            activity.lifecycleScope.launch {
                try {
                    prefetchJob?.join()
                    activity.runOnUiThread {
                        loading.visibility = View.GONE
                        val events = cachedEvents
                        if (events == null || events.isEmpty()) emptyView.visibility = View.VISIBLE
                        else showData(events)
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

        loading.visibility = View.VISIBLE
        dialog.show()
        activity.lifecycleScope.launch {
            try {
                val response = RetrofitClient.channelService.getChatroomEvents(chatroomId, "Bearer $token")
                activity.runOnUiThread {
                    loading.visibility = View.GONE
                    if (response.isSuccessful) {
                        val events = response.body()?.data?.events ?: emptyList()
                        cachedEvents = events
                        showData(events)
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
