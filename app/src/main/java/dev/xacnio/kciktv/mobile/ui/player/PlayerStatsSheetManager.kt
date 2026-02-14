/**
 * File: PlayerStatsSheetManager.kt
 *
 * Description: Manages the "Stats for Nerds" bottom sheet.
 * It displays real-time technical statistics from the player, including video resolution,
 * framerate, bitrate, buffer health, and latency, updating them periodically while open.
 *
 * Author: Xacnio
 *
 */
package dev.xacnio.kciktv.mobile.ui.player

import android.annotation.SuppressLint
import android.os.Handler
import android.os.Looper
import android.view.View
import android.widget.TextView
import com.amazonaws.ivs.player.Player
import com.google.android.material.bottomsheet.BottomSheetDialog
import dev.xacnio.kciktv.mobile.MobilePlayerActivity
import dev.xacnio.kciktv.R

/**
 * Manages the player statistics bottom sheet dialog.
 * Shows real-time playback statistics like resolution, FPS, bitrate, latency, etc.
 */
class PlayerStatsSheetManager(private val activity: MobilePlayerActivity) {

    @SuppressLint("InflateParams")
    fun showPlayerStatsSheet() {
        val bottomSheet = BottomSheetDialog(activity, R.style.BottomSheetDialogTheme)
        val view = activity.layoutInflater.inflate(R.layout.bottom_sheet_player_stats, null)
        bottomSheet.setContentView(view)

        val statResolution = view.findViewById<TextView>(R.id.statResolution)
        val statFps = view.findViewById<TextView>(R.id.statFps)
        val statBitrate = view.findViewById<TextView>(R.id.statBitrate)
        val statLatency = view.findViewById<TextView>(R.id.statLatency)
        val statLatencyRow = view.findViewById<View>(R.id.statLatencyRow)
        val statBuffer = view.findViewById<TextView>(R.id.statBuffer)
        val statDroppedFrames = view.findViewById<TextView>(R.id.statDroppedFrames)
        val statPlaybackRate = view.findViewById<TextView>(R.id.statPlaybackRate)
        val statPlayerState = view.findViewById<TextView>(R.id.statPlayerState)
        val statVolume = view.findViewById<TextView>(R.id.statVolume)
        val btnClose = view.findViewById<View>(R.id.btnCloseStats)
        
        // Hide latency row for VOD, Clip, and DVR modes (latency is only meaningful for live streams)
        val currentPlaybackMode = activity.vodManager.currentPlaybackMode
        val isDvrMode = activity.currentStreamUrl == activity.dvrPlaybackUrl && activity.dvrPlaybackUrl != null
        val isLiveMode = currentPlaybackMode == VodManager.PlaybackMode.LIVE && !isDvrMode
        statLatencyRow.visibility = if (isLiveMode) View.VISIBLE else View.GONE

        val statsHandler = Handler(Looper.getMainLooper())
        var isStatsSheetOpen = true

        fun updateStats() {
            val player = activity.ivsPlayer ?: return
            val quality = player.quality
            val stats = player.statistics
            val statsStr = stats.toString()

            // Resolution
            val resolution = "${quality.width}x${quality.height}"
            statResolution.text = resolution

            // FPS
            fun parseField(key: String): String {
                return Regex("$key=([\\d.]+)").find(statsStr)?.groupValues?.get(1) ?: "0"
            }
            val frameRate = parseField("frameRate")
            statFps.text = "$frameRate fps"

            // Bitrate
            val rawBitrate = parseField("bitRate").toDoubleOrNull() ?: 0.0
            val bitrate = String.format("%.2f Mbps", rawBitrate / 1000000.0)
            statBitrate.text = bitrate

            // Latency
            val latency = String.format("%.2fs", player.liveLatency / 1000.0)
            statLatency.text = latency

            // Buffer
            val bufferMs = player.bufferedPosition - player.position
            val buffer = String.format("%.2fs", (if (bufferMs > 0) bufferMs else 0L) / 1000.0)
            statBuffer.text = buffer

            // Dropped Frames
            val decoded = parseField("decodedFrames").toLongOrNull() ?: 0L
            val dropped = parseField("droppedFrames").toLongOrNull() ?: 0L
            val dropPercent = if (decoded > 0) String.format("%.2f%%", (dropped.toDouble() / (decoded + dropped).toDouble()) * 100.0) else "0.00%"
            statDroppedFrames.text = "$dropped / $decoded ($dropPercent)"

            // Playback Rate
            statPlaybackRate.text = "${player.playbackRate}x"

            // Player State
            statPlayerState.text = player.state.name

            // Volume
            statVolume.text = "${(player.volume * 100).toInt()}%"
        }

        val statsRunnable = object : Runnable {
            override fun run() {
                if (isStatsSheetOpen) {
                    updateStats()
                    statsHandler.postDelayed(this, 1000L)
                }
            }
        }

        // Start updating
        statsHandler.post(statsRunnable)

        btnClose.setOnClickListener {
            bottomSheet.dismiss()
        }

        bottomSheet.setOnDismissListener {
            isStatsSheetOpen = false
            statsHandler.removeCallbacks(statsRunnable)
            activity.activeBottomSheets.remove(bottomSheet)
        }

        activity.activeBottomSheets.add(bottomSheet)
        bottomSheet.show()
    }
}
