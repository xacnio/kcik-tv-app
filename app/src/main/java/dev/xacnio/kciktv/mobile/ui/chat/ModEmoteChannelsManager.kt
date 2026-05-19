package dev.xacnio.kciktv.mobile.ui.chat

import android.text.Editable
import android.text.TextWatcher
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.widget.EditText
import android.widget.ImageButton
import androidx.lifecycle.LifecycleCoroutineScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.bottomsheet.BottomSheetDialog
import dev.xacnio.kciktv.R
import dev.xacnio.kciktv.mobile.MobilePlayerActivity
import dev.xacnio.kciktv.shared.data.model.SearchResultItem
import dev.xacnio.kciktv.shared.data.repository.ChannelRepository
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class ModEmoteChannelsManager(
    private val activity: MobilePlayerActivity,
    private val repository: ChannelRepository,
    private val lifecycleScope: LifecycleCoroutineScope,
    private val onChannelsChanged: () -> Unit
) {
    private companion object {
        const val TAG = "ModEmoteChannelsManager"
    }

    private var dialog: BottomSheetDialog? = null
    private var adapter: ModEmoteChannelAdapter? = null
    private var searchJob: Job? = null
    private var currentQuery = ""

    fun show(existingCategorySlugs: Set<String>) {
        val ctx = activity
        val view = LayoutInflater.from(ctx).inflate(R.layout.bottom_sheet_mod_emote_channels, null)
        val dlg = BottomSheetDialog(ctx, R.style.BottomSheetDialogTheme)
        dlg.setContentView(view)
        dialog = dlg

        val recyclerView = view.findViewById<RecyclerView>(R.id.modEmoteChannelList)
        val searchInput = view.findViewById<EditText>(R.id.modEmoteSearchInput)
        val closeButton = view.findViewById<ImageButton>(R.id.modEmoteCloseButton)

        val channelAdapter = ModEmoteChannelAdapter(
            onAdd = { item ->
                addChannel(item.slug)
                refreshList(searchInput.text.toString(), existingCategorySlugs)
            },
            onRemove = { item ->
                removeChannel(item.slug)
                refreshList(searchInput.text.toString(), existingCategorySlugs)
            }
        )
        adapter = channelAdapter

        recyclerView.layoutManager = LinearLayoutManager(ctx)
        recyclerView.adapter = channelAdapter

        closeButton.setOnClickListener { dlg.dismiss() }

        // Show added channels on open
        refreshList("", existingCategorySlugs)

        searchInput.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                val q = s?.toString()?.trim() ?: ""
                currentQuery = q
                searchJob?.cancel()
                if (q.isEmpty()) {
                    refreshList("", existingCategorySlugs)
                } else {
                    searchJob = lifecycleScope.launch {
                        delay(350)
                        performSearch(q, existingCategorySlugs)
                    }
                }
            }
        })

        dlg.setOnDismissListener {
            dialog = null
            adapter = null
        }

        dlg.show()
    }

    private fun refreshList(query: String, existingCategorySlugs: Set<String>) {
        if (query.isEmpty()) {
            val addedSlugs = activity.prefs.modEmoteChannelSlugs
            if (addedSlugs.isEmpty()) {
                adapter?.setItems(emptyList())
                return
            }
            // Show added channels — build items from slugs (no profile pic info here, just slug)
            val items = addedSlugs.map { slug ->
                ModEmoteChannelItem(slug = slug, username = slug, profilePic = null, isAdded = true)
            }
            adapter?.setItems(items)
        } else {
            searchJob?.cancel()
            searchJob = lifecycleScope.launch {
                performSearch(query, existingCategorySlugs)
            }
        }
    }

    private suspend fun performSearch(query: String, existingCategorySlugs: Set<String>) {
        try {
            repository.searchChannels(query).onSuccess { results ->
                val addedSlugs = activity.prefs.modEmoteChannelSlugs
                val items = results
                    .filterIsInstance<SearchResultItem.ChannelResult>()
                    .filter { it.slug !in existingCategorySlugs }
                    .map { ch ->
                        ModEmoteChannelItem(
                            slug = ch.slug,
                            username = ch.username,
                            profilePic = ch.profilePic,
                            isAdded = ch.slug in addedSlugs
                        )
                    }
                activity.runOnUiThread {
                    adapter?.setItems(items)
                }
            }.onFailure {
                Log.e(TAG, "Search failed", it)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Search error", e)
        }
    }

    private fun addChannel(slug: String) {
        val current = activity.prefs.modEmoteChannelSlugs.toMutableSet()
        current.add(slug)
        activity.prefs.modEmoteChannelSlugs = current
        onChannelsChanged()
    }

    private fun removeChannel(slug: String) {
        val current = activity.prefs.modEmoteChannelSlugs.toMutableSet()
        current.remove(slug)
        activity.prefs.modEmoteChannelSlugs = current
        onChannelsChanged()
    }
}
