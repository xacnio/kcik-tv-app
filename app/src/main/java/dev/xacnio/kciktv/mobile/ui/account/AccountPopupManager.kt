/**
 * File: AccountPopupManager.kt
 *
 * Description: Manages the display and interaction of the account popup menu. It handles the
 * inflation of the popup layout, manages its dimensions and positioning relative to the anchor view,
 * and facilitates navigation to profile and settings screens.
 *
 * Author: Xacnio
 *
 */
package dev.xacnio.kciktv.mobile.ui.account

import android.content.Intent
import android.os.Build
import android.view.View
import android.widget.ImageView
import android.widget.TextView
import com.bumptech.glide.Glide
import dev.xacnio.kciktv.mobile.LoginActivity
import dev.xacnio.kciktv.mobile.MobilePlayerActivity
import dev.xacnio.kciktv.R

class AccountPopupManager(private val activity: MobilePlayerActivity) {

    private val prefs get() = activity.prefs
    private val channelProfileManager get() = activity.channelProfileManager

    /**
     * Shows the account popup menu anchored to the given view.
     */
    fun showAccountPopupMenu(anchor: View) {
        try {
            val view = activity.layoutInflater.inflate(R.layout.layout_account_popup, null)

            // Calculate width in pixels (280dp)
            val density = activity.resources.displayMetrics.density
            val widthPx = (280 * density).toInt()

            val popupWindow = android.widget.PopupWindow(
                view,
                widthPx,
                android.view.ViewGroup.LayoutParams.WRAP_CONTENT,
                true
            )

            // Allow background clicks to dismiss
            popupWindow.setBackgroundDrawable(android.graphics.drawable.ColorDrawable(android.graphics.Color.TRANSPARENT))
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                popupWindow.elevation = 16f
            }

            val loggedInGroup = view.findViewById<View>(R.id.popupLoggedInGroup)
            val loggedOutGroup = view.findViewById<View>(R.id.popupLoggedOutGroup)

            if (prefs.isLoggedIn) {
                loggedInGroup.visibility = View.VISIBLE
                loggedOutGroup.visibility = View.GONE

                val profilePic = view.findViewById<ImageView>(R.id.popupProfilePic)
                val username = view.findViewById<TextView>(R.id.popupUsername)

                username.text = prefs.username ?: activity.getString(R.string.user_default_name)
                val effectivePic = if (prefs.profilePic.isNullOrEmpty()) {
                    val hash = (prefs.username ?: "Guest").hashCode()
                    val index = (if (hash < 0) -hash else hash) % 6 + 1
                    "https://kick.com/img/default-profile-pictures/default-avatar-$index.webp"
                } else {
                    prefs.profilePic
                }

                Glide.with(activity)
                    .load(effectivePic)
                    .circleCrop()
                    .placeholder(R.drawable.default_avatar)
                    .into(profilePic)

                view.findViewById<View>(R.id.btnPopupMyChannel).setOnClickListener {
                    popupWindow.dismiss()
                    val slug = prefs.userSlug
                    if (!slug.isNullOrEmpty()) {
                        channelProfileManager.openChannelProfile(slug)
                    }
                }

                view.findViewById<View>(R.id.btnPopupSettings).setOnClickListener {
                    popupWindow.dismiss()
                    activity.showSettingsPanel()
                }

                view.findViewById<View>(R.id.btnPopupQrScanner).setOnClickListener {
                    popupWindow.dismiss()
                    val intent = Intent(activity, dev.xacnio.kciktv.mobile.ui.settings.qr.QRScannerActivity::class.java)
                    activity.startActivity(intent)
                }

                view.findViewById<View>(R.id.btnPopupLogout).setOnClickListener {
                    popupWindow.dismiss()
                    activity.authManager.showLogoutConfirmDialog()
                }
            } else {
                loggedInGroup.visibility = View.GONE
                loggedOutGroup.visibility = View.VISIBLE

                view.findViewById<View>(R.id.btnPopupLogin).setOnClickListener {
                    popupWindow.dismiss()
                    val intent = Intent(activity, LoginActivity::class.java)
                    activity.startActivity(intent)
                }
            }

            // Show popup below anchor
            popupWindow.showAsDropDown(anchor, 0, (12 * density).toInt())

        } catch (e: Exception) {
            e.printStackTrace()
        }
    }
}
