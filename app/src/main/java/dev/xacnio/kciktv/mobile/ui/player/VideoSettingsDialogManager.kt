/**
 * File: VideoSettingsDialogManager.kt
 *
 * Description: Manages the Video Settings panel, providing UI for selecting video quality,
 * changing playback speed, and accessing audio settings.
 *
 * Author: Xacnio
 *
 */
package dev.xacnio.kciktv.mobile.ui.player

import android.annotation.SuppressLint
import android.view.View
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.TextView
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.amazonaws.ivs.player.Quality
import dev.xacnio.kciktv.mobile.MobilePlayerActivity
import dev.xacnio.kciktv.R
import dev.xacnio.kciktv.shared.data.prefs.AppPreferences

class VideoSettingsDialogManager(
    private val activity: MobilePlayerActivity,
    private val prefs: AppPreferences
) {

    class QualityViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val badge: TextView = view.findViewById(R.id.qualityBadge)
        val label: TextView = view.findViewById(R.id.qualityLabel)
        val bitrate: TextView = view.findViewById(R.id.qualityBitrate)
        val checkmark: ImageView = view.findViewById(R.id.selectedCheck)
    }

    /** Maps a stream's vertical resolution to a short tier badge (FHD/HD/SD/LD). */
    private fun qualityTierLabel(height: Int): String = when {
        height >= 2160 -> "4K"
        height >= 1080 -> "FHD"
        height >= 720 -> "HD"
        height >= 480 -> "SD"
        else -> "LD"
    }

    private var currentPanelView: View? = null
    private var scrimView: View? = null

    private companion object {
        const val PAGE_ROOT = 0
        const val PAGE_QUALITY = 1
        const val PAGE_SPEED = 2
    }

    fun isPanelShowing(): Boolean = currentPanelView != null

    fun dismissPanel() {
        val view = currentPanelView ?: return
        scrimView?.let { (it.parent as? android.view.ViewGroup)?.removeView(it) }
        scrimView = null
        view.animate().alpha(0f).setDuration(150).withEndAction {
            (view.parent as? android.view.ViewGroup)?.removeView(view)
            currentPanelView = null
        }.start()
    }

    private fun dpToPx(dp: Int): Int = (dp * activity.resources.displayMetrics.density).toInt()

    private fun computeRootHeight(visibleItemCount: Int): Int {
        val overhead = 73 // header(52) + sep(1) + handle(20)
        return dpToPx(overhead + visibleItemCount * 56)
    }

    private fun computeSubPageHeight(itemCount: Int): Int {
        val overhead = 73 // header(52) + sep(1) + handle(20)
        return dpToPx(overhead + itemCount * 52).coerceAtMost(dpToPx(420))
    }

    private fun animatePanelToHeight(targetH: Int) {
        val view = currentPanelView ?: return
        val startH = view.layoutParams.height
        if (startH == targetH) return
        android.animation.ValueAnimator.ofInt(startH, targetH).apply {
            duration = 200
            interpolator = android.view.animation.DecelerateInterpolator()
            addUpdateListener { anim ->
                val lp = view.layoutParams
                lp.height = anim.animatedValue as Int
                view.layoutParams = lp
            }
            start()
        }
    }

    @SuppressLint("InflateParams")
    fun showVideoSettingsDialog() {
        if (currentPanelView != null) return

        val view = activity.layoutInflater.inflate(R.layout.bottom_sheet_video_settings, null)
        currentPanelView = view

        val backButton = view.findViewById<ImageButton>(R.id.videoPanelBackButton)
        val backSpacer = view.findViewById<View>(R.id.videoPanelBackSpacer)
        val titleText = view.findViewById<TextView>(R.id.videoPanelTitleText)
        val btnDismiss = view.findViewById<View>(R.id.btnDismissVideoSettings)

        val mainView = view.findViewById<View>(R.id.videoSettingsMainView)
        val qualityView = view.findViewById<View>(R.id.qualitySettingsView)
        val speedView = view.findViewById<View>(R.id.speedSettingsView)

        val playMode = activity.vodManager.currentPlaybackMode
        val isVodOrClip = playMode != VodManager.PlaybackMode.LIVE
        val hasAudio = android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P

        var currentPage = PAGE_ROOT

        fun updateHeader(titleRes: Int, showBack: Boolean) {
            titleText.text = activity.getString(titleRes)
            backButton.visibility = if (showBack) View.VISIBLE else View.GONE
            backSpacer.visibility = if (showBack) View.GONE else View.VISIBLE
        }

        fun navigate(page: Int) {
            currentPage = page
            when (page) {
                PAGE_ROOT -> {
                    var count = 3 // Quality, Refresh, Stats always visible
                    if (isVodOrClip) count++
                    if (hasAudio) count++
                    animatePanelToHeight(computeRootHeight(count))
                    mainView.visibility = View.VISIBLE
                    qualityView.visibility = View.GONE
                    speedView.visibility = View.GONE
                    updateHeader(R.string.settings, false)
                }
                PAGE_QUALITY -> {
                    val qualities = activity.ivsPlayer?.qualities?.sortedByDescending { it.height } ?: emptyList()
                    animatePanelToHeight(computeSubPageHeight(qualities.size + 1))
                    mainView.visibility = View.GONE
                    qualityView.visibility = View.VISIBLE
                    speedView.visibility = View.GONE
                    updateHeader(R.string.quality_selection, true)
                }
                PAGE_SPEED -> {
                    animatePanelToHeight(computeSubPageHeight(6))
                    mainView.visibility = View.GONE
                    qualityView.visibility = View.GONE
                    speedView.visibility = View.VISIBLE
                    updateHeader(R.string.playback_speed_title, true)
                }
            }
        }

        backButton.setOnClickListener {
            if (currentPage == PAGE_QUALITY || currentPage == PAGE_SPEED) navigate(PAGE_ROOT)
        }
        btnDismiss.setOnClickListener { dismissPanel() }

        // Quality value text
        val qualityValueText = view.findViewById<TextView>(R.id.videoSettingQualityValue)
        fun updateQualityValue() {
            val currentQuality = activity.ivsPlayer?.quality
            val isAuto = activity.ivsPlayer?.isAutoQualityMode == true
            val userLimit = activity.userSelectedQualityLimit
            qualityValueText.text = when {
                // Audio-only (genuine or synthesized from the lowest real quality) is always
                // pinned directly rather than used as an ABR cap — reflect that regardless of
                // the dynamic quality setting or which underlying quality it resolved to.
                activity.playerManager.isAudioOnlyActive -> activity.getString(R.string.quality_audio_only)
                prefs.dynamicQualityEnabled -> {
                    if (userLimit != null)
                        activity.getString(R.string.auto_quality_with_limit, userLimit.height)
                    else
                        activity.getString(R.string.auto_quality)
                }
                isAuto -> activity.getString(R.string.auto_quality)
                (currentQuality?.height ?: 0) <= 0 -> activity.getString(R.string.quality_audio_only)
                else -> "${currentQuality?.height}p"
            }
        }
        updateQualityValue()

        view.findViewById<View>(R.id.videoSettingQuality).setOnClickListener { navigate(PAGE_QUALITY) }

        view.findViewById<View>(R.id.videoSettingRefresh).setOnClickListener {
            dismissPanel()
            activity.playCurrentChannel()
        }

        view.findViewById<View>(R.id.videoSettingStats).setOnClickListener {
            dismissPanel()
            activity.showPlayerStatsSheet()
        }

        // Speed option
        val speedOption = view.findViewById<View>(R.id.videoSettingSpeed)
        val speedValueText = view.findViewById<TextView>(R.id.videoSettingSpeedValue)
        if (isVodOrClip) {
            speedOption.visibility = View.VISIBLE
            val currentSpeed = activity.ivsPlayer?.playbackRate ?: 1.0f
            speedValueText.text = if (currentSpeed == 1.0f) activity.getString(R.string.speed_normal_1x)
                else activity.getString(R.string.speed_x_format, currentSpeed)
            speedOption.setOnClickListener { navigate(PAGE_SPEED) }
        }

        // Audio EQ option
        val audioOption = view.findViewById<View>(R.id.videoSettingAudio)
        if (hasAudio) {
            audioOption.visibility = View.VISIBLE
            audioOption.setOnClickListener {
                dismissPanel()
                activity.customEqDialogManager.showCustomEqDialog()
            }
        }

        // Quality RecyclerView
        val qualityRecycler = view.findViewById<RecyclerView>(R.id.qualityRecyclerView)
        qualityRecycler.layoutManager = LinearLayoutManager(activity)
        val qualities = activity.ivsPlayer?.qualities?.sortedByDescending { it.height } ?: emptyList()
        val realQualities = qualities.filter { it.height > 0 }
        // Not every channel's manifest has a genuine no-video "audio_only" rendition — when it
        // doesn't, synthesize the option (see PlayerManager.resolveAudioOnlyQuality). isAudioOnly
        // is a separate tag rather than inferred from height, since a synthesized entry shares
        // its Quality with a real row above.
        val audioOnlyTarget = activity.playerManager.resolveAudioOnlyQuality()
        val qualityItems = mutableListOf<Triple<Quality?, String, Boolean>>()
        qualityItems.add(Triple(null, activity.getString(R.string.auto_quality), false))
        realQualities.forEach { q -> qualityItems.add(Triple(q, "${q.height}p${q.framerate.toInt()}", false)) }
        audioOnlyTarget?.let { qualityItems.add(Triple(it, activity.getString(R.string.quality_audio_only), true)) }

        qualityRecycler.adapter = object : RecyclerView.Adapter<QualityViewHolder>() {
            override fun onCreateViewHolder(parent: android.view.ViewGroup, viewType: Int) =
                QualityViewHolder(activity.layoutInflater.inflate(R.layout.item_quality_option, parent, false))

            override fun onBindViewHolder(holder: QualityViewHolder, position: Int) {
                val (q, label, isAudioOnlyItem) = qualityItems[position]
                holder.label.text = label
                val currentQuality = activity.ivsPlayer?.quality
                val isAutoMode = activity.ivsPlayer?.isAutoQualityMode == true
                val isAudioOnlyActive = activity.playerManager.isAudioOnlyActive
                holder.badge.visibility = View.VISIBLE
                if (isAudioOnlyItem) {
                    // A genuine audio_only track (q.height <= 0) has no video, so its bitrate is
                    // meaningful on its own. A synthetic one is really a real video quality with
                    // the picture hidden — make that explicit rather than showing its full bitrate,
                    // which would misleadingly read like a lean audio-only number.
                    holder.bitrate.text = if (q != null && q.height <= 0) {
                        String.format("%.1f Mbps", q.bitrate / 1_000_000.0)
                    } else {
                        activity.getString(R.string.quality_audio_only_synthetic, q?.height ?: 0)
                    }
                    holder.bitrate.visibility = View.VISIBLE
                    holder.badge.text = "AUD"
                } else if (q != null) {
                    holder.bitrate.text = String.format("%.1f Mbps", q.bitrate / 1_000_000.0)
                    holder.bitrate.visibility = View.VISIBLE
                    holder.badge.text = qualityTierLabel(q.height)
                } else {
                    val liveHeight = currentQuality?.height ?: 0
                    holder.bitrate.text = when {
                        currentQuality == null -> ""
                        isAudioOnlyActive -> activity.getString(R.string.quality_audio_only)
                        else -> "${liveHeight}p"
                    }
                    holder.bitrate.visibility = if (isAutoMode) View.VISIBLE else View.GONE
                    holder.badge.text = "AUTO"
                }
                val userLimit = activity.userSelectedQualityLimit
                val isSelected = when {
                    isAudioOnlyItem -> isAudioOnlyActive
                    q == null -> !isAudioOnlyActive && (if (prefs.dynamicQualityEnabled) userLimit == null else isAutoMode)
                    else -> !isAudioOnlyActive && (
                        if (prefs.dynamicQualityEnabled) q.bitrate == userLimit?.bitrate && q.height == userLimit.height
                        else q.bitrate == currentQuality?.bitrate && q.height == currentQuality.height && !isAutoMode
                    )
                }
                holder.checkmark.visibility = if (isSelected) View.VISIBLE else View.GONE
                holder.checkmark.imageTintList = android.content.res.ColorStateList.valueOf(prefs.themeColor)
                holder.itemView.setOnClickListener {
                    when {
                        isAudioOnlyItem -> {
                            activity.playerManager.updateAudioOnlyVisual(true)
                            val resolved = activity.playerManager.resolveAudioOnlyQuality()
                            if (resolved != null) {
                                activity.userSelectedQualityLimit = resolved
                                activity.ivsPlayer?.isAutoQualityMode = false
                                activity.ivsPlayer?.quality = resolved
                            }
                            activity.analytics.logFeatureUsed("quality_audio_only")
                        }
                        q == null -> {
                            activity.userSelectedQualityLimit = null
                            activity.ivsPlayer?.isAutoQualityMode = true
                            activity.checkAndApplyQualityLimit()
                            activity.analytics.logFeatureUsed("quality_auto")
                            activity.playerManager.updateAudioOnlyVisual(false)
                        }
                        else -> {
                            activity.userSelectedQualityLimit = q
                            activity.analytics.logFeatureUsed("quality_${q.height}p")
                            if (prefs.dynamicQualityEnabled) {
                                activity.ivsPlayer?.isAutoQualityMode = true
                                activity.ivsPlayer?.setAutoMaxQuality(q)
                                activity.checkAndApplyQualityLimit()
                            } else {
                                activity.ivsPlayer?.isAutoQualityMode = false
                                activity.ivsPlayer?.quality = q
                                activity.checkAndApplyQualityLimit()
                            }
                            activity.playerManager.updateAudioOnlyVisual(false)
                        }
                    }
                    updateQualityValue()
                    when {
                        isAudioOnlyItem -> activity.binding.videoQualityBadge.text = activity.getString(R.string.quality_audio_only)
                        q != null -> activity.binding.videoQualityBadge.text = "${q.height}p${q.framerate.toInt()}"
                    }
                    dismissPanel()
                }
            }

            override fun getItemCount() = qualityItems.size
        }

        // Speed RecyclerView
        val speedRecycler = view.findViewById<RecyclerView>(R.id.speedRecyclerView)
        speedRecycler.layoutManager = LinearLayoutManager(activity)
        val speeds = listOf(0.5f, 0.75f, 1.0f, 1.25f, 1.5f, 2.0f)
        speedRecycler.adapter = object : RecyclerView.Adapter<QualityViewHolder>() {
            override fun onCreateViewHolder(parent: android.view.ViewGroup, viewType: Int) =
                QualityViewHolder(activity.layoutInflater.inflate(R.layout.item_quality_option, parent, false))

            override fun onBindViewHolder(holder: QualityViewHolder, position: Int) {
                val s = speeds[position]
                holder.label.text = if (s == 1.0f) activity.getString(R.string.speed_normal_1x)
                    else activity.getString(R.string.speed_x_format, s)
                holder.bitrate.visibility = View.GONE
                holder.badge.visibility = View.INVISIBLE
                val currentSpeed = activity.ivsPlayer?.playbackRate ?: 1.0f
                holder.checkmark.visibility = if (s == currentSpeed) View.VISIBLE else View.GONE
                holder.checkmark.imageTintList = android.content.res.ColorStateList.valueOf(prefs.themeColor)
                holder.itemView.setOnClickListener {
                    activity.ivsPlayer?.playbackRate = s
                    activity.analytics.logFeatureUsed("playback_speed_${s}x")
                    dismissPanel()
                }
            }

            override fun getItemCount() = speeds.size
        }

        // Inject into chatContainer so it appears at the top of the chat area (same approach as chat settings panel)
        val container = activity.binding.chatContainer

        // Full-container click-catcher behind the panel: a tap outside the panel dismisses it.
        val scrim = View(activity).apply {
            layoutParams = ConstraintLayout.LayoutParams(0, 0).apply {
                topToTop = ConstraintLayout.LayoutParams.PARENT_ID
                bottomToBottom = ConstraintLayout.LayoutParams.PARENT_ID
                startToStart = ConstraintLayout.LayoutParams.PARENT_ID
                endToEnd = ConstraintLayout.LayoutParams.PARENT_ID
            }
            isClickable = true
            isFocusable = true
            elevation = dpToPx(15).toFloat()
            setOnClickListener { dismissPanel() }
        }
        container.addView(scrim)
        scrimView = scrim

        var rootItemCount = 3
        if (isVodOrClip) rootItemCount++
        if (hasAudio) rootItemCount++
        val initialHeight = computeRootHeight(rootItemCount)
        val lp = ConstraintLayout.LayoutParams(ConstraintLayout.LayoutParams.MATCH_PARENT, initialHeight)
        lp.topToTop = ConstraintLayout.LayoutParams.PARENT_ID
        lp.startToStart = ConstraintLayout.LayoutParams.PARENT_ID
        lp.endToEnd = ConstraintLayout.LayoutParams.PARENT_ID
        view.layoutParams = lp
        // Consume stray taps on the panel itself so they don't fall through to the scrim.
        view.isClickable = true
        container.addView(view)
        view.elevation = dpToPx(16).toFloat()

        view.alpha = 0f
        view.animate().alpha(1f).setDuration(180).start()

        navigate(PAGE_ROOT)
    }
}
