/**
 * File: BottomSheetManager.kt
 *
 * Description: Manages business logic, state, and UI interactions for Bottom Sheet.
 *
 * Author: Xacnio
 *
 */
package dev.xacnio.kciktv.mobile.ui.sheet

import android.content.res.Configuration
import android.os.Build
import android.util.Log
import android.view.View
import android.view.WindowManager
import androidx.fragment.app.DialogFragment
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog
import dev.xacnio.kciktv.mobile.MobilePlayerActivity
import dev.xacnio.kciktv.mobile.ui.dialog.LoyaltyPointsBottomSheet
import dev.xacnio.kciktv.R

/**
 * Manages bottom sheet tracking and dismissal.
 */
class BottomSheetManager(private val activity: MobilePlayerActivity) {

    companion object {
        private const val TAG = "BottomSheetManager"
    }

    /**
     * Dismisses all tracked bottom sheets.
     */
    fun dismissAllBottomSheets() {
        // Dismiss all tracked bottom sheets
        activity.activeBottomSheets.forEach { sheet ->
            try {
                if (sheet.isShowing) {
                    sheet.dismiss()
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error dismissing bottom sheet: ${e.message}")
            }
        }
        activity.activeBottomSheets.clear()

        // Also dismiss cached Blerp fragment
        activity.cachedBlerpFragment?.let {
            if (it.isVisible) {
                it.dismissAllowingStateLoss()
            }
        }

        // Dismiss Fragment-based bottom sheets
        val sheetTags = listOf(
            "LoyaltyPointsBottomSheet",
            "create_poll_sheet",
            "create_prediction_sheet"
        )

        sheetTags.forEach { tag ->
            val fragment = activity.supportFragmentManager.findFragmentByTag(tag)
            if (fragment is DialogFragment) {
                try {
                    fragment.dismissAllowingStateLoss()
                } catch (e: Exception) {
                    Log.e(TAG, "Error dismissing fragment sheet $tag: ${e.message}")
                }
            }
        }
    }

    /**
     * Tracks a bottom sheet for later dismissal.
     */
    fun trackBottomSheet(sheet: BottomSheetDialog) {
        activity.activeBottomSheets.add(sheet)
        sheet.setOnDismissListener {
            activity.activeBottomSheets.remove(sheet)
        }
        // Configure for fullscreen/landscape mode if needed
        configureForFullscreen(sheet)
    }

    /**
     * Configures a BottomSheetDialog to display properly in fullscreen/landscape mode.
     * This ensures the dialog appears above the video player and expands fully.
     */
    fun configureForFullscreen(sheet: BottomSheetDialog) {
        val isLandscape = activity.resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
        
        if (activity.isFullscreen || isLandscape) {
            sheet.window?.let { window ->
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    window.setDecorFitsSystemWindows(false)
                    window.insetsController?.let { controller ->
                        controller.hide(android.view.WindowInsets.Type.statusBars() or android.view.WindowInsets.Type.navigationBars())
                        controller.systemBarsBehavior = android.view.WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
                    }
                } else {
                    @Suppress("DEPRECATION")
                    window.addFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN)
                    @Suppress("DEPRECATION")
                    window.decorView.systemUiVisibility = activity.window.decorView.systemUiVisibility
                }
                
                // Ensure the dialog is drawn above system bars
                window.setFlags(
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                )
            }
            
            // Configure the bottom sheet to expand fully in landscape
            sheet.setOnShowListener { dialog ->
                val bottomSheet = (dialog as BottomSheetDialog).findViewById<View>(
                    com.google.android.material.R.id.design_bottom_sheet
                )
                bottomSheet?.let { view ->
                    val behavior = BottomSheetBehavior.from(view)
                    
                    // Set to expanded state immediately
                    behavior.state = BottomSheetBehavior.STATE_EXPANDED
                    behavior.skipCollapsed = true
                    
                    // In landscape, make it take more height
                    val displayMetrics = activity.resources.displayMetrics
                    val screenHeight = displayMetrics.heightPixels
                    view.layoutParams.height = (screenHeight * 0.9).toInt()
                    view.requestLayout()
                }
                
                // Clear the not focusable flag after showing
                sheet.window?.clearFlags(WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE)
            }
        }
    }
}
