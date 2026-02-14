/**
 * File: ViewerCountManager.kt
 *
 * Description: Manages the fetching and display of the live viewer count.
 * It periodically polls the repository for current viewer numbers, formats the count
 * (e.g., 1.2K, 10M), and updates the UI, respecting "Return to Live" states.
 *
 * Author: Xacnio
 *
 */
package dev.xacnio.kciktv.mobile.ui.player

import android.util.Log
import androidx.lifecycle.lifecycleScope
import dev.xacnio.kciktv.mobile.MobilePlayerActivity
import kotlinx.coroutines.launch
import java.util.Locale
import dev.xacnio.kciktv.shared.util.FormatUtils

class ViewerCountManager(private val activity: MobilePlayerActivity) {

    private val binding get() = activity.binding
    private val repository get() = activity.repository
    private val ivsPlayer get() = activity.ivsPlayer

    companion object {
        private const val TAG = "ViewerCountManager"
    }

    /**
     * Fetches the current viewer count for the active livestream.
     */
    fun fetchCurrentViewerCount() {
        val livestreamId = activity.currentLivestreamId

        if (livestreamId == null) {
            Log.w(TAG, "fetchCurrentViewerCount: livestreamId is null, skipping")
            return
        }

        activity.lifecycleScope.launch {
            repository.getCurrentViewers(livestreamId)
                .onSuccess { viewers ->
                    activity.runOnUiThread {
                        // Only update if we are NOT in "Return to Live" mode
                        val player = ivsPlayer
                        val duration = player?.duration ?: 0L
                        val position = player?.position ?: 0L
                        val isBehindLive = duration > 0 && (duration - position) > 15000
                        activity.currentChannel?.viewerCount = viewers

                        // Skip UI update if in mini player mode
                        if (!isBehindLive && !activity.miniPlayerManager.isMiniPlayerMode) {
                            binding.viewerCount.text = dev.xacnio.kciktv.shared.util.FormatUtils.formatViewerCount(
                                viewers.toLong(),
                                activity.showFullViewerCount
                            )
                        }
                    }
                }
                .onFailure { error ->
                    Log.e(TAG, "Failed to fetch viewer count: ${error.message}")
                    activity.currentChannel?.viewerCount?.let { count ->
                        binding.viewerCount.text = dev.xacnio.kciktv.shared.util.FormatUtils.formatViewerCount(
                            count.toLong(),
                            activity.showFullViewerCount
                        )
                    }
                }
        }
    }

    /**
     * Formats seconds into a time string (HH:MM:SS or MM:SS).
     */
    fun formatStreamTime(seconds: Long): String {
        val h = seconds / 3600
        val m = (seconds % 3600) / 60
        val s = seconds % 60
        return if (h > 0) {
            String.format(Locale.US, "%02d:%02d:%02d", h, m, s)
        } else {
            String.format(Locale.US, "%02d:%02d", m, s)
        }
    }

    /**
     * Formats a viewer count into a human-readable string (e.g., 1.5K, 2M).
     */
    fun formatViewerCount(count: Long): String {
        val ltrMark = "\u200E"
        return when {
            count >= 1_000_000 -> ltrMark + String.format(Locale.US, "%.1fM", count / 1_000_000.0).replace(".0M", "M")
            count >= 1_000 -> ltrMark + String.format(Locale.US, "%.1fK", count / 1_000.0).replace(".0K", "K")
            else -> ltrMark + count.toString()
        }
    }
}
