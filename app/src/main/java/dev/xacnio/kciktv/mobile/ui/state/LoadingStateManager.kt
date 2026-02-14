/**
 * File: LoadingStateManager.kt
 *
 * Description: Manages business logic, state, and UI interactions for Loading State.
 *
 * Author: Xacnio
 *
 */
package dev.xacnio.kciktv.mobile.ui.state

import android.view.View
import dev.xacnio.kciktv.mobile.MobilePlayerActivity

/**
 * Manages loading and error state UI.
 */
class LoadingStateManager(private val activity: MobilePlayerActivity) {

    private val binding get() = activity.binding

    /**
     * Shows an error message and activates error state.
     */
    fun showError(message: String) {
        hideLoading()
        activity.isErrorStateActive = true
        binding.errorText.text = message
        activity.showOverlay()
    }

    /**
     * Shows the loading indicator and overlay.
     */
    fun showLoading() {
        binding.loadingIndicator.visibility = View.VISIBLE
        binding.loadingOverlay.visibility = View.VISIBLE
    }

    /**
     * Hides the loading indicator and overlay.
     */
    fun hideLoading() {
        binding.loadingIndicator.visibility = View.GONE
        binding.loadingOverlay.visibility = View.GONE
    }
}
