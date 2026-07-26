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
import dev.xacnio.kciktv.shared.data.model.ChatBadgeV2
import dev.xacnio.kciktv.shared.ui.utils.ApngBadgeManager

/**
 * Utility object for rendering chat badges into views
 */
object BadgeRenderUtils {

    /** Gift-count thresholds, descending, so the first match is the highest tier reached. */
    private val SUB_GIFTER_TIERS = listOf(
        500 to R.drawable.ic_badge_sub_gifter_500,
        200 to R.drawable.ic_badge_sub_gifter_200,
        100 to R.drawable.ic_badge_sub_gifter_100,
        50 to R.drawable.ic_badge_sub_gifter_50,
        25 to R.drawable.ic_badge_sub_gifter_25,
        10 to R.drawable.ic_badge_sub_gifter_10,
        5 to R.drawable.ic_badge_sub_gifter_5,
        1 to R.drawable.ic_badge_sub_gifter_1
    )

    /** Highest tier at or below [count]; falls back to the lowest tier when count is missing. */
    fun subGifterBadge(count: Int?): Int {
        val gifts = count ?: 0
        return SUB_GIFTER_TIERS.firstOrNull { (threshold, _) -> gifts >= threshold }?.second
            ?: R.drawable.ic_badge_sub_gifter_1
    }


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
        
        if (!isActive) {
            val matrix = ColorMatrix()
            matrix.setSaturation(0f)
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
            "sub_gifter" -> imageView.setImageResource(subGifterBadge(badge.count))
            "vip" -> imageView.setImageResource(R.drawable.ic_badge_vip)
            "sidekick" -> imageView.setImageResource(R.drawable.ic_badge_sidekick)
            "bot" -> imageView.setImageResource(R.drawable.ic_badge_bot)
            "subscriber" -> {
                val months = badge.count ?: 0
                val badgeUrl = subscriberBadges.keys.filter { it <= months }
                    .maxOrNull()?.let { subscriberBadges[it] }
                
                if (badgeUrl != null) {
                    ApngBadgeManager.loadBadge(badgeUrl, size, imageView) { drawable ->
                        if (drawable != null) {
                            imageView.setImageDrawable(drawable)
                            (drawable as? android.graphics.drawable.Animatable)?.start()
                        } else {
                            // APNG failed, fall back to Glide static load
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

    /** Renders one badges_v2 entry. Split out so badges can be merged with the v1 list and sorted together. */
    fun renderSingleBadgeV2IntoSheet(
        context: Context,
        container: LinearLayout?,
        badge: ChatBadgeV2,
        size: Int,
        margin: Int
    ) {
        if (container == null) return

        val iv = ImageView(context)
        val params = LinearLayout.LayoutParams(size, size)
        params.setMargins(0, 0, margin, 0)
        iv.layoutParams = params
        iv.scaleType = ImageView.ScaleType.FIT_CENTER
        container.addView(iv)

        val drawableRes = when (badge.name) {
            "verified"   -> R.drawable.ic_badge_verified
            "staff"      -> R.drawable.ic_badge_staff
            "og"         -> R.drawable.ic_badge_og
            // badges_v2 has no gift count; prefer its own image_url, fall back to lowest tier
            "sub_gifter" -> if (badge.imageUrl.isNullOrEmpty()) subGifterBadge(null) else null
            "sidekick"   -> R.drawable.ic_badge_sidekick
            "bot"        -> R.drawable.ic_badge_bot
            else         -> null
        }
        if (drawableRes != null) {
            iv.setImageResource(drawableRes)
        } else if (!badge.imageUrl.isNullOrEmpty()) {
            Glide.with(context).load(badge.imageUrl).into(iv)
        } else {
            container.removeView(iv)
        }
    }

    /**
     * Renders a chat sender's badges into [container], clearing it first so it is safe to
     * call from a recycled list row. Same v2-then-v1 ordering the chat lines use.
     */
    fun renderChatSenderBadges(
        context: Context,
        container: LinearLayout?,
        badges: List<dev.xacnio.kciktv.shared.data.model.ChatBadge>?,
        badgesV2: List<ChatBadgeV2>?,
        size: Int,
        margin: Int,
        subscriberBadges: Map<Int, String> = emptyMap()
    ) {
        if (container == null) return
        container.removeAllViews()

        badgesV2?.filter { it.selected == true }
            ?.sortedBy { it.sortOrder ?: Int.MAX_VALUE }
            ?.forEach { renderSingleBadgeV2IntoSheet(context, container, it, size, margin) }

        badges?.forEach { badge ->
            renderSingleBadgeIntoSheet(
                context,
                container,
                ChannelUserBadge(
                    type = badge.type,
                    text = badge.text,
                    count = badge.count ?: badge.text?.toIntOrNull(),
                    active = true
                ),
                size,
                margin,
                subscriberBadges
            )
        }
        container.visibility = if (container.childCount == 0) android.view.View.GONE
            else android.view.View.VISIBLE
    }

    /** Renders v1 and v2 badges as one row, interleaved and ordered by sort_order across both lists. */
    fun renderAllBadgesIntoSheet(
        context: Context,
        container: LinearLayout?,
        badges: List<ChannelUserBadge>?,
        badgesV2: List<ChatBadgeV2>?,
        size: Int,
        margin: Int,
        subscriberBadges: Map<Int, String> = emptyMap()
    ) {
        if (container == null) return

        // Deferred so both lists can be interleaved before anything is added to the container.
        val entries = mutableListOf<Pair<Int, () -> Unit>>()

        badges?.forEach { badge ->
            entries.add((badge.sortOrder ?: Int.MAX_VALUE) to {
                renderSingleBadgeIntoSheet(context, container, badge, size, margin, subscriberBadges)
            })
        }
        badgesV2?.filter { it.selected == true }?.forEach { badge ->
            entries.add((badge.sortOrder ?: Int.MAX_VALUE) to {
                renderSingleBadgeV2IntoSheet(context, container, badge, size, margin)
            })
        }

        entries.sortBy { it.first }
        entries.forEach { it.second() }
    }
}
