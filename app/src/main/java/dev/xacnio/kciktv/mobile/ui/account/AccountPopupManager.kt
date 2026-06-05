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

import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.content.Intent
import android.os.Build
import android.view.View
import android.view.animation.DecelerateInterpolator
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.TextView
import androidx.lifecycle.lifecycleScope
import com.bumptech.glide.Glide
import dev.xacnio.kciktv.mobile.LoginActivity
import dev.xacnio.kciktv.mobile.MobilePlayerActivity
import dev.xacnio.kciktv.R
import dev.xacnio.kciktv.shared.data.model.UserLevelData
import kotlinx.coroutines.launch

class AccountPopupManager(private val activity: MobilePlayerActivity) {

    private val prefs get() = activity.prefs
    private val channelProfileManager get() = activity.channelProfileManager

    fun showAccountPopupMenu(anchor: View) {
        try {
            val view = activity.layoutInflater.inflate(R.layout.layout_account_popup, null)

            val density = activity.resources.displayMetrics.density
            val widthPx = (280 * density).toInt()

            val popupWindow = android.widget.PopupWindow(
                view,
                widthPx,
                android.view.ViewGroup.LayoutParams.WRAP_CONTENT,
                true
            )

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

                val token = prefs.authToken
                if (!token.isNullOrEmpty()) {
                    val shimmer = view.findViewById<dev.xacnio.kciktv.shared.ui.widget.ShimmerLayout>(R.id.popupLevelShimmer)
                    val levelCard = view.findViewById<View>(R.id.popupLevelCard)
                    val badgeView = view.findViewById<ImageView>(R.id.popupLevelBadge)
                    val levelText = view.findViewById<TextView>(R.id.popupLevelText)
                    val progressBar = view.findViewById<ProgressBar>(R.id.popupLevelProgress)
                    val progressText = view.findViewById<TextView>(R.id.popupLevelProgressText)

                    val cached = activity.cachedUserLevel
                    if (cached != null) {
                        shimmer.visibility = View.GONE
                        applyLevelData(cached, levelCard, badgeView, levelText, progressBar, progressText)
                    } else {
                        shimmer.visibility = View.VISIBLE
                    }

                    activity.lifecycleScope.launch {
                        val result = activity.repository.getUserLevel(token)
                        activity.runOnUiThread {
                            shimmer.visibility = View.GONE
                            result.onSuccess { newData ->
                                val oldData = activity.cachedUserLevel
                                activity.cachedUserLevel = newData
                                if (oldData == null) {
                                    applyLevelData(newData, levelCard, badgeView, levelText, progressBar, progressText)
                                } else {
                                    animateLevelUpdate(oldData, newData, levelCard, badgeView, levelText, progressBar, progressText)
                                }
                            }
                        }
                    }
                }

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

                view.findViewById<View>(R.id.btnPopupSwitchAccount).setOnClickListener {
                    popupWindow.dismiss()
                    dev.xacnio.kciktv.mobile.ui.sheet.AccountSwitcherSheetManager(activity).show()
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

            popupWindow.showAsDropDown(anchor, 0, (12 * density).toInt())

        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun applyLevelData(
        data: UserLevelData,
        levelCard: View,
        badgeView: ImageView,
        levelText: TextView,
        progressBar: ProgressBar,
        progressText: TextView
    ) {
        levelText.text = activity.getString(R.string.level_format, data.level)
        progressBar.progress = data.progressPercent
        progressText.text = activity.getString(
            R.string.level_towards_next_format,
            data.progressPercent,
            data.level + 1
        )
        val badgeUrl = data.badge?.imageUrl
        if (!badgeUrl.isNullOrEmpty()) {
            Glide.with(activity).load(badgeUrl).into(badgeView)
        }
        levelCard.visibility = View.VISIBLE
    }

    private fun animateLevelUpdate(
        oldData: UserLevelData,
        newData: UserLevelData,
        levelCard: View,
        badgeView: ImageView,
        levelText: TextView,
        progressBar: ProgressBar,
        progressText: TextView
    ) {
        levelCard.visibility = View.VISIBLE
        val leveledUp = newData.level > oldData.level

        val badgeUrl = newData.badge?.imageUrl
        if (!badgeUrl.isNullOrEmpty() && badgeUrl != oldData.badge?.imageUrl) {
            Glide.with(activity).load(badgeUrl).into(badgeView)
        }

        progressText.text = activity.getString(
            R.string.level_towards_next_format,
            newData.progressPercent,
            newData.level + 1
        )

        if (leveledUp) {
            levelText.text = activity.getString(R.string.level_format, newData.level)

            // Fill to 100%, then reset and fill to new level's progress
            ValueAnimator.ofInt(oldData.progressPercent, 100).apply {
                duration = 400
                interpolator = DecelerateInterpolator()
                addUpdateListener { progressBar.progress = it.animatedValue as Int }
                addListener(object : AnimatorListenerAdapter() {
                    override fun onAnimationEnd(animation: android.animation.Animator) {
                        progressBar.progress = 0
                        ValueAnimator.ofInt(0, newData.progressPercent).apply {
                            duration = 600
                            interpolator = DecelerateInterpolator()
                            addUpdateListener { progressBar.progress = it.animatedValue as Int }
                            start()
                        }
                    }
                })
                start()
            }

            levelText.animate().scaleX(1.35f).scaleY(1.35f).setDuration(200).withEndAction {
                levelText.animate().scaleX(1f).scaleY(1f).setDuration(200).start()
            }.start()
            badgeView.animate().scaleX(1.25f).scaleY(1.25f).setDuration(200).withEndAction {
                badgeView.animate().scaleX(1f).scaleY(1f).setDuration(200).start()
            }.start()
        } else {
            levelText.text = activity.getString(R.string.level_format, newData.level)

            ValueAnimator.ofInt(oldData.progressPercent, newData.progressPercent).apply {
                duration = 600
                interpolator = DecelerateInterpolator()
                addUpdateListener { progressBar.progress = it.animatedValue as Int }
                start()
            }
        }
    }
}
