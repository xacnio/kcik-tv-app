/**
 * File: AuthManager.kt
 *
 * Description: Centralizes authentication logic, including handling login intent transitions and
 * managing the logout process. It interfaces with the app preferences to maintain session state
 * and coordinates with the UI to reflect authentication status.
 *
 * Author: Xacnio
 *
 */
package dev.xacnio.kciktv.mobile.ui.auth

import android.content.Intent
import android.util.Log
import android.view.View
import android.webkit.CookieManager
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.lifecycle.LifecycleCoroutineScope
import com.bumptech.glide.Glide
import dev.xacnio.kciktv.mobile.LoginActivity
import dev.xacnio.kciktv.mobile.MobilePlayerActivity
import dev.xacnio.kciktv.R
import dev.xacnio.kciktv.shared.data.prefs.AppPreferences
import dev.xacnio.kciktv.databinding.ActivityMobilePlayerBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import java.net.URLDecoder
import dev.xacnio.kciktv.mobile.ui.chat.ChatStateManager

class AuthManager(
    private val activity: MobilePlayerActivity,
    private val binding: ActivityMobilePlayerBinding,
    private val prefs: AppPreferences,
    private val lifecycleScope: LifecycleCoroutineScope
) {
    private companion object {
        const val TAG = "AuthManager"
    }

    fun handleLoginClick() {
        activity.startActivity(Intent(activity, LoginActivity::class.java))
    }

    fun showLogoutConfirmDialog() {
        AlertDialog.Builder(activity)
            .setTitle(R.string.logout_confirm_title)
            .setMessage(R.string.logout_confirm_message)
            .setPositiveButton(R.string.yes) { _, _ ->
                performServerSideLogout()
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun performServerSideLogout() {
        val cookieManager = CookieManager.getInstance()
        val initialCookies = cookieManager.getCookie("https://kick.com") ?: ""
        
        Toast.makeText(activity, activity.getString(R.string.logging_out_secure), Toast.LENGTH_SHORT).show()
        
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val client = OkHttpClient.Builder()
                    .followRedirects(true)
                    .followSslRedirects(true)
                    .build()
                
                val sanctumReq = Request.Builder()
                    .url("https://kick.com/sanctum/csrf-cookie")
                    .header("User-Agent", "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36")
                    .header("Accept", "application/json")
                    .header("Cookie", initialCookies)
                    .build()
                    
                val sanctumRes = client.newCall(sanctumReq).execute()
                val setCookies = sanctumRes.headers("Set-Cookie")
                sanctumRes.close()
                
                var currentCookies = initialCookies
                for (sc in setCookies) {
                    val raw = sc.substringBefore(";")
                    if (currentCookies.isNotEmpty()) currentCookies += "; "
                    currentCookies += raw
                }
                
                var xsrfToken = ""
                val parts = currentCookies.split(";")
                for (part in parts) {
                    val trimmed = part.trim()
                    if (trimmed.startsWith("XSRF-TOKEN=")) {
                        val encoded = trimmed.substring("XSRF-TOKEN=".length)
                        xsrfToken = URLDecoder.decode(encoded, "UTF-8")
                        break
                    }
                }
                
                if (xsrfToken.isNotEmpty()) {
                    val logoutReq = Request.Builder()
                        .url("https://kick.com/logout")
                        .post(ByteArray(0).toRequestBody(null))
                        .header("User-Agent", "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36")
                        .header("Accept", "application/json")
                        .header("Cookie", currentCookies)
                        .header("X-XSRF-TOKEN", xsrfToken)
                        .header("Referer", "https://kick.com/")
                        .build()
                        
                    val logoutRes = client.newCall(logoutReq).execute()
                    logoutRes.close()
                }
                
            } catch (e: Exception) {
                Log.e(TAG, "Logout failed", e)
            } finally {
                withContext(Dispatchers.Main) {
                    prefs.clearAuth()
                    cookieManager.removeAllCookies(null)
                    cookieManager.flush()
                    
                    Toast.makeText(activity, R.string.logged_out, Toast.LENGTH_SHORT).show()
                    activity.updateChatLoginState()
                    activity.updateUserHeaderState()

                }
            }
        }
    }

    fun updateUserHeaderState() {
        if (prefs.isLoggedIn) {
            binding.mobileProfilePic.visibility = View.VISIBLE
            binding.mobileLoginBtn.visibility = View.GONE
            binding.mobileSettingsBtn.visibility = View.GONE
            
            val effectivePic = if (prefs.profilePic.isNullOrEmpty()) {
                val hash = (prefs.username ?: "Guest").hashCode()
                val index = (if (hash < 0) -hash else hash) % 6 + 1
                "https://kick.com/img/default-profile-pictures/default-avatar-$index.webp"
            } else {
                prefs.profilePic
            }

            Glide.with(activity)
                .load(effectivePic)
                .placeholder(R.drawable.default_avatar)
                .error(R.drawable.default_avatar)
                .circleCrop()
                .into(binding.mobileProfilePic)
        } else {
            binding.mobileProfilePic.visibility = View.GONE
            binding.mobileLoginBtn.visibility = View.VISIBLE
            binding.mobileSettingsBtn.visibility = View.VISIBLE
        }
    }

    fun updateChatLoginState() {
        // Sync state from ChatStateManager
        activity.isBannedFromCurrentChannel = activity.chatStateManager.isBannedFromCurrentChannel
        activity.isPermanentBan = activity.chatStateManager.isPermanentBan
        activity.timeoutExpirationMillis = activity.chatStateManager.timeoutExpirationMillis
        
        activity.chatUiManager.chatAdapter.isLoggedIn = prefs.isLoggedIn
        
        if (prefs.isLoggedIn) {
            if (activity.isCheckingBanStatus) {
                binding.chatInputContainer.visibility = View.INVISIBLE
                binding.chatBannedOverlay.visibility = View.GONE
                binding.chatLoginOverlay.visibility = View.GONE
                return
            }
            
            if (activity.isBannedFromCurrentChannel) {
                binding.chatLoginOverlay.visibility = View.GONE
                binding.chatBannedOverlay.visibility = View.VISIBLE
                binding.chatInputContainer.visibility = View.GONE
                
                if (activity.isPermanentBan) {
                    binding.chatBannedText.text = activity.getString(R.string.chat_banned_overlay)
                } else {
                    binding.chatBannedText.text = activity.getString(R.string.chat_timed_out_overlay)
                    activity.chatInputStateManager.startTimeoutCountdown()
                }

                if (binding.emotePanelContainer.visibility == View.VISIBLE) {
                    binding.emotePanelContainer.visibility = View.GONE
                }
            } else {
                activity.chatInputStateManager.stopTimeoutCountdown()
                binding.chatBannedOverlay.visibility = View.GONE
                binding.chatLoginOverlay.visibility = View.GONE
                binding.chatInputContainer.visibility = View.VISIBLE
                binding.quickEmoteRecyclerView.visibility = View.VISIBLE
                binding.loyaltyPointsButton.visibility = View.VISIBLE
                binding.loyaltyStoreButton.visibility = if (activity.isSubscriptionEnabled) View.VISIBLE else View.GONE
                (binding.chatSettingsButton.layoutParams as? android.view.ViewGroup.MarginLayoutParams)?.apply {
                    marginStart = ((if (activity.isSubscriptionEnabled) 4 else 12) * activity.resources.displayMetrics.density).toInt()
                    binding.chatSettingsButton.layoutParams = this
                }
                
                activity.chatInputStateManager.updateInputState()
                activity.chatUiManager.updateChatModeIndicators()
                
                activity.chatInputStateManager.startSlowModeCountdown()
            }
        } else {
            activity.chatInputStateManager.stopAllCountdowns()
            binding.chatLoginOverlay.visibility = View.VISIBLE
            binding.chatBannedOverlay.visibility = View.GONE
            binding.quickEmoteRecyclerView.visibility = View.GONE
            binding.loyaltyPointsButton.visibility = View.GONE
            binding.loyaltyStoreButton.visibility = View.GONE
            (binding.chatSettingsButton.layoutParams as? android.view.ViewGroup.MarginLayoutParams)?.apply {
                marginStart = (12 * activity.resources.displayMetrics.density).toInt()
                binding.chatSettingsButton.layoutParams = this
            }
            // Keep container visible so settings button is shown
            binding.chatInputContainer.visibility = View.VISIBLE
            
            // Hide Quick Emote Menu if not logged in
            if (binding.emotePanelContainer.visibility == View.VISIBLE) {
                binding.emotePanelContainer.visibility = View.GONE
            }
        }
    }
}
