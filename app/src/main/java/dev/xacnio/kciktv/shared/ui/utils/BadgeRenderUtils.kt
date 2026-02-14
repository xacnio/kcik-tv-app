/**
 * File: BadgeRenderUtils.kt
 *
 * Description: Utility helper class providing static methods for Badge Render.
 *
 * Author: Xacnio
 *
 */
package dev.xacnio.kciktv.shared.ui.utils

import android.content.Context
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.widget.ImageView
import android.widget.LinearLayout
import com.bumptech.glide.Glide
import dev.xacnio.kciktv.R
import dev.xacnio.kciktv.shared.data.model.ChannelUserBadge

/**
 * Utility object for rendering chat badges into views
 */
object BadgeRenderUtils {
    
    /**
     * Renders a single badge into the container with optional inactive styling
     * 
     * @param context Android context for creating views and loading resources
     * @param container The LinearLayout container to add the badge to
     * @param badge The badge data to render
     * @param size Size in pixels for the badge icon
     * @param margin Right margin in pixels between badges
     * @param subscriberBadges Map of months to subscriber badge URLs for the current channel
     */
    fun renderSingleBadgeIntoSheet(
        context: Context,
        container: LinearLayout?,
        badge: ChannelUserBadge,
        size: Int,
        margin: Int,
        subscriberBadges: Map<Int, String> = emptyMap()
    ) {
        if (container == null) return
        
        val type = badge.type ?: return
        val isActive = badge.active == true
        
        val imageView = ImageView(context)
        val params = LinearLayout.LayoutParams(size, size)
        params.setMargins(0, 0, margin, 0)
        imageView.layoutParams = params
        
        // Apply gray filter if inactive
        if (!isActive) {
            val matrix = ColorMatrix()
            matrix.setSaturation(0f) // Grayscale
            imageView.colorFilter = ColorMatrixColorFilter(matrix)
            imageView.alpha = 0.5f
        }

        container.addView(imageView)

        when (type) {
            "moderator" -> imageView.setImageResource(R.drawable.ic_badge_moderator)
            "broadcaster" -> imageView.setImageResource(R.drawable.ic_badge_broadcaster)
            "verified" -> imageView.setImageResource(R.drawable.ic_badge_verified)
            "staff" -> imageView.setImageResource(R.drawable.ic_badge_staff)
            "og" -> imageView.setImageResource(R.drawable.ic_badge_og)
            "founder" -> imageView.setImageResource(R.drawable.ic_badge_founder)
            "sub_gifter" -> imageView.setImageResource(R.drawable.ic_badge_sub_gifter)
            "vip" -> imageView.setImageResource(R.drawable.ic_badge_vip)
            "sidekick" -> imageView.setImageResource(R.drawable.ic_badge_sidekick)
            "bot" -> imageView.setImageResource(R.drawable.ic_badge_bot)
            "subscriber" -> {
                val months = badge.count ?: 0
                val badgeUrl = subscriberBadges.keys.filter { it <= months }
                    .maxOrNull()?.let { subscriberBadges[it] }
                
                if (badgeUrl != null) {
                    // Use ApngBadgeManager for animated APNG badges
                    ApngBadgeManager.loadBadge(
                        badgeUrl,
                        size,
                        imageView
                    ) { drawable ->
                        if (drawable != null) {
                            imageView.setImageDrawable(drawable)
                        } else {
                            // Fallback to Glide if APNG fails
                            Glide.with(context).load(badgeUrl).into(imageView)
                        }
                    }
                } else {
                    imageView.setImageResource(R.drawable.ic_badge_subscriber_default)
                }
            }
            else -> {
                container.removeView(imageView)
            }
        }
    }
}
