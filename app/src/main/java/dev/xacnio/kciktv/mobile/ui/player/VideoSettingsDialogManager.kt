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
        val label: TextView = view.findViewById(R.id.qualityLabel)
        val bitrate: TextView = view.findViewById(R.id.qualityBitrate)
        val checkmark: ImageView = view.findViewById(R.id.selectedCheck)
    }

    private var currentPanelView: View? = null

    private companion object {
        const val PAGE_ROOT = 0
        const val PAGE_QUALITY = 1
        const val PAGE_SPEED = 2
    }

    fun isPanelShowing(): Boolean = currentPanelView != null

    fun dismissPanel() {
        val view = currentPanelView ?: return
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
            qualityValueText.text = if (prefs.dynamicQualityEnabled) {
                if (activity.userSelectedQualityLimit != null)
                    activity.getString(R.string.auto_quality_with_limit, activity.userSelectedQualityLimit?.height)
                else
                    activity.getString(R.string.auto_quality)
            } else {
                if (isAuto) activity.getString(R.string.auto_quality) else "${currentQuality?.height}p"
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
        val qualityItems = mutableListOf<Pair<Quality?, String>>()
        qualityItems.add(Pair(null, activity.getString(R.string.auto_quality)))
        qualities.forEach { q -> qualityItems.add(Pair(q, "${q.height}p${q.framerate.toInt()}")) }

        qualityRecycler.adapter = object : RecyclerView.Adapter<QualityViewHolder>() {
            override fun onCreateViewHolder(parent: android.view.ViewGroup, viewType: Int) =
                QualityViewHolder(activity.layoutInflater.inflate(R.layout.item_quality_option, parent, false))

            override fun onBindViewHolder(holder: QualityViewHolder, position: Int) {
                val (q, label) = qualityItems[position]
                holder.label.text = label
                val currentQuality = activity.ivsPlayer?.quality
                val isAutoMode = activity.ivsPlayer?.isAutoQualityMode == true
                if (q != null) {
                    holder.bitrate.text = String.format("%.1f Mbps", q.bitrate / 1_000_000.0)
                    holder.bitrate.visibility = View.VISIBLE
                } else {
                    holder.bitrate.text = if (currentQuality != null) "${currentQuality.height}p" else ""
                    holder.bitrate.visibility = if (isAutoMode) View.VISIBLE else View.GONE
                }
                val userLimit = activity.userSelectedQualityLimit
                val isSelected = if (q == null) {
                    if (prefs.dynamicQualityEnabled) userLimit == null else isAutoMode
                } else {
                    if (prefs.dynamicQualityEnabled) q.bitrate == userLimit?.bitrate && q.height == userLimit.height
                    else q.bitrate == currentQuality?.bitrate && q.height == currentQuality.height && !isAutoMode
                }
                holder.checkmark.visibility = if (isSelected) View.VISIBLE else View.GONE
                holder.checkmark.imageTintList = android.content.res.ColorStateList.valueOf(prefs.themeColor)
                holder.itemView.setOnClickListener {
                    if (q == null) {
                        activity.userSelectedQualityLimit = null
                        activity.ivsPlayer?.isAutoQualityMode = true
                        activity.checkAndApplyQualityLimit()
                        activity.analytics.logFeatureUsed("quality_auto")
                    } else {
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
                    }
                    updateQualityValue()
                    q?.let { activity.binding.videoQualityBadge.text = "${it.height}p${it.framerate.toInt()}" }
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
        var rootItemCount = 3
        if (isVodOrClip) rootItemCount++
        if (hasAudio) rootItemCount++
        val initialHeight = computeRootHeight(rootItemCount)
        val lp = ConstraintLayout.LayoutParams(ConstraintLayout.LayoutParams.MATCH_PARENT, initialHeight)
        lp.topToTop = ConstraintLayout.LayoutParams.PARENT_ID
        lp.startToStart = ConstraintLayout.LayoutParams.PARENT_ID
        lp.endToEnd = ConstraintLayout.LayoutParams.PARENT_ID
        view.layoutParams = lp
        container.addView(view)

        view.alpha = 0f
        view.animate().alpha(1f).setDuration(180).start()

        navigate(PAGE_ROOT)
    }
}
