/**
 * File: FullscreenManager.kt
 *
 * Description: Manages the standard Fullscreen mode state and transitions.
 * It handles screen orientation changes, layout parameter adjustments for the video player,
 * and immersive mode toggles (hiding system bars) when entering or exiting fullscreen.
 *
 * Author: Xacnio
 *
 */
package dev.xacnio.kciktv.mobile.ui.player

import android.content.pm.ActivityInfo
import android.os.Build
import android.view.View
import android.view.WindowManager
import dev.xacnio.kciktv.mobile.MobilePlayerActivity

/**
 * Manages fullscreen mode transitions and immersive mode for the player.
 */
class FullscreenManager(private val activity: MobilePlayerActivity) {

    var isFullscreen = false
        internal set

    fun enterFullscreen() {
        isFullscreen = true
        // Use sensor landscape to allow 180 degree rotation in fullscreen
        activity.requestedOrientation = if (activity.isRotationAllowed()) {
            ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        } else {
            ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
        }
        
        // Hide non-video elements
        activity.binding.actionBar.visibility = View.GONE
        activity.binding.chatContainer.visibility = View.GONE
        
        // Expand video to full screen
        val params = activity.binding.videoContainer.layoutParams as androidx.constraintlayout.widget.ConstraintLayout.LayoutParams
        params.dimensionRatio = null
        params.height = androidx.constraintlayout.widget.ConstraintLayout.LayoutParams.MATCH_PARENT
        activity.binding.videoContainer.layoutParams = params
        
        enableImmersiveMode()
    }

    fun exitFullscreen() {
        isFullscreen = false
        val isTablet = activity.resources.getBoolean(dev.xacnio.kciktv.R.bool.is_tablet)
        // Respect system rotation setting when exiting fullscreen
        activity.requestedOrientation = when {
            activity.isRotationAllowed() -> ActivityInfo.SCREEN_ORIENTATION_SENSOR
            isTablet -> ActivityInfo.SCREEN_ORIENTATION_USER
            else -> ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
        }
        
        // Show elements
        activity.binding.actionBar.visibility = View.VISIBLE
        activity.binding.chatContainer.visibility = View.VISIBLE
        
        // Restore video ratio
        val params = activity.binding.videoContainer.layoutParams as androidx.constraintlayout.widget.ConstraintLayout.LayoutParams
        params.dimensionRatio = "16:9"
        params.height = 0
        activity.binding.videoContainer.layoutParams = params

        disableImmersiveMode()
    }

    fun toggleFullscreen() {
        if (isFullscreen) exitFullscreen() else enterFullscreen()
    }

    private fun enableImmersiveMode() {
        @Suppress("DEPRECATION")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            activity.window.setDecorFitsSystemWindows(false)
            activity.window.insetsController?.let {
                it.hide(android.view.WindowInsets.Type.statusBars() or android.view.WindowInsets.Type.navigationBars())
                it.systemBarsBehavior = android.view.WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            }
        } else {
            activity.window.decorView.systemUiVisibility = (
                View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                or View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                or View.SYSTEM_UI_FLAG_FULLSCREEN
            )
            activity.window.addFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN)
        }
    }

    private fun disableImmersiveMode() {
        @Suppress("DEPRECATION")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            activity.window.setDecorFitsSystemWindows(true)
            activity.window.insetsController?.show(android.view.WindowInsets.Type.statusBars() or android.view.WindowInsets.Type.navigationBars())
        } else {
            activity.window.decorView.systemUiVisibility = (
                View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
            )
            activity.window.clearFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN)
        }
    }
}
