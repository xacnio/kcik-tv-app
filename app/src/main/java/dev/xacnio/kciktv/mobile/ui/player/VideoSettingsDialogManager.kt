/**
 * File: VideoSettingsDialogManager.kt
 *
 * Description: Manages the Video Settings bottom sheet dialog.
 * It provides the UI for selecting video quality (source/transcodes/auto), changing playback speed,
 * and accessing advanced audio settings (Equalizer).
 *
 * Author: Xacnio
 *
 */
package dev.xacnio.kciktv.mobile.ui.player

import android.annotation.SuppressLint
import android.view.View
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.amazonaws.ivs.player.Quality
import com.google.android.material.bottomsheet.BottomSheetDialog
import dev.xacnio.kciktv.mobile.MobilePlayerActivity
import dev.xacnio.kciktv.R
import dev.xacnio.kciktv.shared.data.prefs.AppPreferences

/**
 * Manages the video settings bottom sheet dialog.
 * Handles quality selection, playback speed, and audio mode settings.
 */
class VideoSettingsDialogManager(
    private val activity: MobilePlayerActivity,
    private val prefs: AppPreferences
) {

    class QualityViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val label: TextView = view.findViewById(R.id.qualityLabel)
        val bitrate: TextView = view.findViewById(R.id.qualityBitrate)
        val checkmark: ImageView = view.findViewById(R.id.selectedCheck)
    }

    @SuppressLint("InflateParams")
    fun showVideoSettingsDialog() {
        val bottomSheet = BottomSheetDialog(activity, R.style.BottomSheetDialogTheme)
        val view = activity.layoutInflater.inflate(R.layout.bottom_sheet_video_settings, null)
        bottomSheet.setContentView(view)
        bottomSheet.behavior.state = com.google.android.material.bottomsheet.BottomSheetBehavior.STATE_EXPANDED
        bottomSheet.behavior.skipCollapsed = true

        val mainView = view.findViewById<View>(R.id.mainSettingsView)
        val qualityView = view.findViewById<View>(R.id.qualitySettingsView)
        val speedView = view.findViewById<View>(R.id.speedSettingsView)
        val backBtn = view.findViewById<View>(R.id.backToMainSettings)
        val backFromSpeedBtn = view.findViewById<View>(R.id.backFromSpeedToMain)
        val recyclerView = view.findViewById<RecyclerView>(R.id.qualityRecyclerView)
        val speedRecyclerView = view.findViewById<RecyclerView>(R.id.speedRecyclerView)
        
        // Quality value in main menu
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

        // --- Main View Setup ---
        view.findViewById<View>(R.id.videoSettingQuality).setOnClickListener {
            mainView.visibility = View.GONE
            qualityView.visibility = View.VISIBLE
        }

        view.findViewById<View>(R.id.videoSettingRefresh).setOnClickListener {
            bottomSheet.dismiss()
            activity.playCurrentChannel()
        }

        view.findViewById<View>(R.id.videoSettingStats).setOnClickListener {
            bottomSheet.dismiss()
            activity.showPlayerStatsSheet()
        }

        // --- Speed View Selection ---
        val playMode = activity.vodManager.currentPlaybackMode
        val isVodOrClip = playMode != VodManager.PlaybackMode.LIVE
        
        val speedOption = view.findViewById<View>(R.id.videoSettingSpeed)
        val speedValueText = view.findViewById<TextView>(R.id.videoSettingSpeedValue)
        
        if (isVodOrClip) {
            speedOption.visibility = View.VISIBLE
            val currentSpeed = activity.ivsPlayer?.playbackRate ?: 1.0f
            speedValueText.text = if (currentSpeed == 1.0f) activity.getString(R.string.speed_normal_1x) else activity.getString(R.string.speed_x_format, currentSpeed)
            
            speedOption.setOnClickListener {
                mainView.visibility = View.GONE
                speedView.visibility = View.VISIBLE
            }
        } else {
            speedOption.visibility = View.GONE
        }

        // --- Audio Mode Setup (Custom EQ) ---
        val audioOption = view.findViewById<View>(R.id.videoSettingAudio)

        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P) {
            audioOption.visibility = View.VISIBLE
            // Click directly opens Custom EQ settings
            audioOption.setOnClickListener {
                // Enable Custom EQ mode
                bottomSheet.dismiss()
                activity.customEqDialogManager.showCustomEqDialog()
            }
        } else {
            audioOption.visibility = View.GONE
        }

        // --- Quality View Setup ---
        backBtn.setOnClickListener {
            qualityView.visibility = View.GONE
            mainView.visibility = View.VISIBLE
        }

        // --- Speed View Setup ---
        backFromSpeedBtn.setOnClickListener {
            speedView.visibility = View.GONE
            mainView.visibility = View.VISIBLE
        }

        speedRecyclerView.layoutManager = LinearLayoutManager(activity)
        val speeds = listOf(0.5f, 0.75f, 1.0f, 1.25f, 1.5f, 2.0f)
        speedRecyclerView.adapter = object : RecyclerView.Adapter<QualityViewHolder>() {
            override fun onCreateViewHolder(parent: android.view.ViewGroup, viewType: Int): QualityViewHolder {
                val itemView = activity.layoutInflater.inflate(R.layout.item_quality_option, parent, false)
                return QualityViewHolder(itemView)
            }

            override fun onBindViewHolder(holder: QualityViewHolder, position: Int) {
                val s = speeds[position]
                holder.label.text = if (s == 1.0f) activity.getString(R.string.speed_normal_1x) else activity.getString(R.string.speed_x_format, s)
                holder.bitrate.visibility = View.GONE
                
                val currentSpeed = activity.ivsPlayer?.playbackRate ?: 1.0f
                holder.checkmark.visibility = if (s == currentSpeed) View.VISIBLE else View.GONE
                
                holder.itemView.setOnClickListener {
                    activity.ivsPlayer?.playbackRate = s
                    activity.analytics.logFeatureUsed("playback_speed_${s}x")
                    bottomSheet.dismiss()
                }
            }
            override fun getItemCount() = speeds.size
        }

        recyclerView.layoutManager = LinearLayoutManager(activity)
        val qualities = activity.ivsPlayer?.qualities?.sortedByDescending { it.height } ?: emptyList()
        val qualityItems = mutableListOf<Pair<Quality?, String>>()
        qualityItems.add(Pair(null, activity.getString(R.string.auto_quality)))
        qualities.forEach { q -> qualityItems.add(Pair(q, "${q.height}p${q.framerate.toInt()}")) }

        recyclerView.adapter = object : RecyclerView.Adapter<QualityViewHolder>() {
            override fun onCreateViewHolder(parent: android.view.ViewGroup, viewType: Int): QualityViewHolder {
                val itemView = activity.layoutInflater.inflate(R.layout.item_quality_option, parent, false)
                return QualityViewHolder(itemView)
            }

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

                val userSelectedLimit = activity.userSelectedQualityLimit
                val isSelected = if (q == null) {
                    if (prefs.dynamicQualityEnabled) userSelectedLimit == null else isAutoMode
                } else {
                    if (prefs.dynamicQualityEnabled) {
                        q.bitrate == userSelectedLimit?.bitrate && q.height == userSelectedLimit.height
                    } else {
                        q.bitrate == currentQuality?.bitrate && q.height == currentQuality.height && !isAutoMode
                    }
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
                    
                    bottomSheet.dismiss()
                }
            }
            override fun getItemCount() = qualityItems.size
        }

        activity.trackBottomSheet(bottomSheet)
        bottomSheet.show()
    }
}
