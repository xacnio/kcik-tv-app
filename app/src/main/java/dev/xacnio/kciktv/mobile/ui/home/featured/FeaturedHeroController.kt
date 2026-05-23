package dev.xacnio.kciktv.mobile.ui.home.featured

import android.os.Handler
import android.os.Looper
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import androidx.recyclerview.widget.RecyclerView
import androidx.viewpager2.widget.ViewPager2
import dev.xacnio.kciktv.mobile.MobilePlayerActivity
import dev.xacnio.kciktv.shared.data.model.ChannelItem

class FeaturedHeroController(private val activity: MobilePlayerActivity) {

    private val mainHandler = Handler(Looper.getMainLooper())

    private var pager: ViewPager2? = null
    private var indicators: LinearLayout? = null
    private var prevBtn: ImageView? = null
    private var nextBtn: ImageView? = null
    private var chatContainer: FrameLayout? = null

    private var adapter: FeaturedHeroAdapter? = null
    val previewPlayer = HeroPreviewPlayer(activity)
    var chatPreview: HeroChatPreview? = null

    private var currentPosition = 0
    private var isBound = false
    private var isMuted = true

    private val autoAdvanceRunnable = Runnable {
        val p = pager ?: return@Runnable
        val count = adapter?.itemCount ?: 0
        if (count > 1) {
            val next = (currentPosition + 1) % count
            p.setCurrentItem(next, true)
        }
        scheduleAutoAdvance()
    }

    private val pageChangeCallback = object : ViewPager2.OnPageChangeCallback() {
        override fun onPageSelected(position: Int) {
            currentPosition = position
            updateIndicators(position)
            resetAutoAdvanceTimer()
            pager?.post { attachPlayerToPage(position) }
            scheduleChatSwitch(position)
        }

        override fun onPageScrollStateChanged(state: Int) {
            if (state == ViewPager2.SCROLL_STATE_DRAGGING) {
                previewPlayer.pause()
                mainHandler.removeCallbacks(autoAdvanceRunnable)
            } else if (state == ViewPager2.SCROLL_STATE_IDLE) {
                val channel = adapter?.getChannel(currentPosition) ?: return
                if (previewPlayer.isWifi()) {
                    previewPlayer.loadAndPlay(channel.playbackUrl)
                }
                scheduleAutoAdvance()
            }
        }
    }

    // Debounce chat connection on fast swipe
    private var pendingChatPosition = -1
    private val chatSwitchRunnable = Runnable {
        val pos = pendingChatPosition
        if (pos < 0) return@Runnable
        val channel = adapter?.getChannel(pos) ?: return@Runnable
        chatPreview?.switchTo(channel.chatroomId, channel.id.toLongOrNull())
    }

    fun bind(rootView: View, channels: List<ChannelItem>) {
        pager = rootView.findViewById(dev.xacnio.kciktv.R.id.heroPager)
        indicators = rootView.findViewById(dev.xacnio.kciktv.R.id.heroIndicators)
        prevBtn = rootView.findViewById(dev.xacnio.kciktv.R.id.heroPrevBtn)
        nextBtn = rootView.findViewById(dev.xacnio.kciktv.R.id.heroNextBtn)
        chatContainer = rootView.findViewById(dev.xacnio.kciktv.R.id.heroChatContainer)

        adapter = FeaturedHeroAdapter(
            activity = activity,
            channels = channels,
            onChannelClick = { channel ->
                activity.openChannel(channel)
            },
            onProfileClick = { channel ->
                activity.channelProfileManager.openChannelProfile(channel.slug)
            },
            onFirstBind = { _ ->
                // Post so we run after the current layout pass finishes.
                // Attaching a View inside onBindViewHolder (which runs during layout) is unsafe.
                pager?.post {
                    retriggerCurrentPage()
                }
            },
            onMuteToggle = { _ ->
                toggleMute()
            }
        )

        val isTablet = activity.resources.getBoolean(dev.xacnio.kciktv.R.bool.is_tablet)

        pager?.apply {
            adapter = this@FeaturedHeroController.adapter
            offscreenPageLimit = 1
            registerOnPageChangeCallback(pageChangeCallback)
        }

        // Show arrow buttons on tablet
        if (isTablet) {
            prevBtn?.visibility = View.VISIBLE
            nextBtn?.visibility = View.VISIBLE
        }
        prevBtn?.setOnClickListener {
            val count = adapter?.itemCount ?: 0
            if (count > 1) pager?.setCurrentItem((currentPosition - 1 + count) % count, true)
            resetAutoAdvanceTimer()
        }
        nextBtn?.setOnClickListener {
            val count = adapter?.itemCount ?: 0
            if (count > 1) pager?.setCurrentItem((currentPosition + 1) % count, true)
            resetAutoAdvanceTimer()
        }

        // Init dot indicators
        buildIndicators(channels.size)

        // Init chat
        chatContainer?.let { container ->
            chatPreview = HeroChatPreview(
                context = activity,
                container = container,
                isCompact = !isTablet,
                fetchHistory = { channelId ->
                    // getChatHistory takes channelId (numeric channel ID), not chatroomId
                    activity.repository.getChatHistory(channelId).getOrNull()?.messages ?: emptyList()
                }
            )
        }
        chatContainer?.visibility = View.VISIBLE

        isBound = true

        scheduleAutoAdvance()
    }

    fun updateChannels(channels: List<ChannelItem>) {
        adapter?.updateChannels(channels)
        buildIndicators(channels.size)
    }

    fun currentChannel(): ChannelItem? = adapter?.getChannel(currentPosition)

    fun retriggerCurrentPage(forceChat: Boolean = false) {
        // Post so we run after RecyclerView processes the notifyDataSetChanged layout pass.
        // findViewHolderForLayoutPosition is used because findViewHolderForAdapterPosition
        // returns null after notifyDataSetChanged until the next layout traversal.
        pager?.post {
            val rv = (pager?.getChildAt(0) as? RecyclerView) ?: return@post
            val vh = rv.findViewHolderForLayoutPosition(currentPosition) as? FeaturedHeroAdapter.PageViewHolder
                ?: return@post
            previewPlayer.detach()
            previewPlayer.attachTo(vh.playerContainer)
            updateMuteIcon(vh.muteBtn)
            val channel = adapter?.getChannel(currentPosition) ?: return@post
            if (previewPlayer.isWifi()) previewPlayer.loadAndPlay(channel.playbackUrl)
            chatPreview?.switchTo(channel.chatroomId, channel.id.toLongOrNull(), force = forceChat)
        }
    }

    // Called when home screen is hidden (e.g., user opens a channel)
    fun onPause() {
        mainHandler.removeCallbacks(autoAdvanceRunnable)
        mainHandler.removeCallbacks(chatSwitchRunnable)
        previewPlayer.pause()
        chatPreview?.pause()
    }

    // Called when home screen becomes visible again
    fun onResume() {
        if (!isBound) return
        val channel = adapter?.getChannel(currentPosition)
        // Skip video while mini player is active — two IVS decoders simultaneously can fail
        if (channel != null && previewPlayer.isWifi() && !activity.miniPlayerManager.isMiniPlayerMode) {
            previewPlayer.loadAndPlay(channel.playbackUrl)
        }
        chatPreview?.reconnect()
        scheduleAutoAdvance()
    }

    // Called when hero scrolls back into view — reconnects WebSocket but doesn't reload history
    fun onScrollResume() {
        if (!isBound) return
        val channel = adapter?.getChannel(currentPosition)
        if (channel != null && previewPlayer.isWifi() && !activity.miniPlayerManager.isMiniPlayerMode) {
            previewPlayer.loadAndPlay(channel.playbackUrl)
        }
        chatPreview?.resume()
        scheduleAutoAdvance()
    }

    fun onDestroy() {
        mainHandler.removeCallbacks(autoAdvanceRunnable)
        mainHandler.removeCallbacks(chatSwitchRunnable)
        pager?.unregisterOnPageChangeCallback(pageChangeCallback)
        previewPlayer.release()
        chatPreview?.release()
        isBound = false
    }

    // Attach the shared IVS PlayerView to the currently-visible page's heroPlayerContainer
    private fun attachPlayerToPage(position: Int) {
        val rv = (pager?.getChildAt(0) as? RecyclerView) ?: return
        val vh = (rv.findViewHolderForAdapterPosition(position)
            ?: rv.findViewHolderForLayoutPosition(position)) as? FeaturedHeroAdapter.PageViewHolder
            ?: return
        previewPlayer.detach()
        previewPlayer.attachTo(vh.playerContainer)
        updateMuteIcon(vh.muteBtn)

        val channel = adapter?.getChannel(position) ?: return
        if (previewPlayer.isWifi()) {
            previewPlayer.loadAndPlay(channel.playbackUrl)
        }
    }

    private fun scheduleChatSwitch(position: Int) {
        pendingChatPosition = position
        mainHandler.removeCallbacks(chatSwitchRunnable)
        mainHandler.postDelayed(chatSwitchRunnable, 600)
    }

    private fun scheduleAutoAdvance() {
        if ((adapter?.itemCount ?: 0) <= 1) return
        if (!isMuted) return
        mainHandler.removeCallbacks(autoAdvanceRunnable)
        mainHandler.postDelayed(autoAdvanceRunnable, AUTO_ADVANCE_DELAY_MS)
    }

    private fun resetAutoAdvanceTimer() {
        mainHandler.removeCallbacks(autoAdvanceRunnable)
        scheduleAutoAdvance()
    }

    private fun buildIndicators(count: Int) {
        val container = indicators ?: return
        container.removeAllViews()
        if (count <= 1) {
            container.visibility = View.GONE
            return
        }
        container.visibility = View.VISIBLE
        val dp4 = (4 * activity.resources.displayMetrics.density).toInt()
        val dp8 = (8 * activity.resources.displayMetrics.density).toInt()
        val dp3 = (3 * activity.resources.displayMetrics.density).toInt()
        repeat(count) { i ->
            val dot = View(activity).apply {
                layoutParams = LinearLayout.LayoutParams(dp4, dp4).apply {
                    marginEnd = dp3
                    marginStart = dp3
                    topMargin = dp8
                    bottomMargin = dp8
                }
                background = activity.getDrawable(
                    if (i == currentPosition) dev.xacnio.kciktv.R.drawable.bg_hero_dot
                    else dev.xacnio.kciktv.R.drawable.bg_hero_dot
                )
                alpha = if (i == currentPosition) 1f else 0.4f
            }
            container.addView(dot)
        }
    }

    private fun updateIndicators(selectedPosition: Int) {
        val container = indicators ?: return
        for (i in 0 until container.childCount) {
            val dot = container.getChildAt(i)
            val isSelected = i == selectedPosition
            dot.background = activity.getDrawable(dev.xacnio.kciktv.R.drawable.bg_hero_dot)
            dot.alpha = if (isSelected) 1f else 0.4f
        }
    }

    private fun toggleMute() {
        isMuted = !isMuted
        previewPlayer.playerView.player.isMuted = isMuted
        if (!isMuted) {
            mainHandler.removeCallbacks(autoAdvanceRunnable)
        } else {
            scheduleAutoAdvance()
        }
        syncMuteIconOnCurrentPage()
    }

    private fun syncMuteIconOnCurrentPage() {
        val rv = (pager?.getChildAt(0) as? RecyclerView) ?: return
        val vh = rv.findViewHolderForAdapterPosition(currentPosition) as? FeaturedHeroAdapter.PageViewHolder
            ?: return
        updateMuteIcon(vh.muteBtn)
    }

    private fun updateMuteIcon(btn: ImageButton) {
        btn.setImageResource(
            if (isMuted) dev.xacnio.kciktv.R.drawable.ic_volume_off
            else dev.xacnio.kciktv.R.drawable.ic_volume_on
        )
    }

    companion object {
        private const val AUTO_ADVANCE_DELAY_MS = 7000L
    }
}
