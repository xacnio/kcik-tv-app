/**
 * File: RouletteOverlay.kt
 *
 * Description: Full-screen daily-reward roulette. Spins a reel of candidate cards over a dark
 * scrim, scales whichever card sits under the pointer, then flies the winner into the daily
 * reward modal as the scrim clears.
 *
 * Author: Xacnio
 *
 */
package dev.xacnio.kciktv.mobile.ui.reward

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.graphics.drawable.Drawable
import android.os.Handler
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.view.animation.DecelerateInterpolator
import android.view.animation.LinearInterpolator
import android.view.animation.PathInterpolator
import android.widget.ImageView
import android.widget.LinearLayout
import com.bumptech.glide.Glide
import com.bumptech.glide.load.DataSource
import com.bumptech.glide.load.engine.GlideException
import com.bumptech.glide.request.RequestListener
import com.bumptech.glide.request.target.Target
import dev.xacnio.kciktv.R
import dev.xacnio.kciktv.mobile.MobilePlayerActivity
import dev.xacnio.kciktv.shared.data.model.ChallengeWinner
import dev.xacnio.kciktv.shared.data.model.RouletteItem
import dev.xacnio.kciktv.shared.util.CardImageUrl
import kotlin.math.abs

class RouletteOverlay(
    private val activity: MobilePlayerActivity,
    private val handler: Handler
) {
    private companion object {
        const val TAG = "RouletteOverlay"

        const val ITEM_HEIGHT_DP = 168
        const val ITEM_MARGIN_DP = 6

        // Must match rouletteShine's rotation in the layout; sweep distance is derived from it.
        const val SHINE_ROTATION_DEG = 35f

        // Width asked of the CDN for reel cards; they're large on screen, so the grid's 128 default
        // would be visibly upscaled.
        const val REEL_CDN_WIDTH = 256

        // Don't hold the spin hostage to a slow image forever.
        const val PRELOAD_TIMEOUT_MS = 6000L

        // Card art is 128x169, so items keep that shape.
        const val CARD_ASPECT = 128f / 169f

        // The card under the pointer swells to MAX; its neighbours sit at MIN. Everything in
        // between is interpolated, so cards grow and shrink as they pass the line.
        const val MIN_SCALE = 0.76f
        const val MAX_SCALE = 1.18f

        const val SPIN_DURATION_MS = 5200L
        const val REPEATS = 4
        const val LANDING_REPEAT = 2

        // How far off the winner's centre the pointer comes to rest, as a fraction of card width.
        // A minimum is enforced — plain symmetric random landed near-centre about half the time.
        const val LANDING_OFFSET_MIN = 0.18f
        const val LANDING_OFFSET_MAX = 0.40f

        // How much the winner grows when it's pulled to centre and shown off.
        const val SHOWCASE_SCALE = 1.4f
    }

    private var dialog: android.app.Dialog? = null
    private var shineRunnable: Runnable? = null
    private var spinAnimator: ValueAnimator? = null

    val isShowing: Boolean get() = dialog?.isShowing == true

    fun dismiss() {
        spinAnimator?.cancel()
        spinAnimator = null
        shineRunnable?.let { handler.removeCallbacks(it) }
        shineRunnable = null
        dialog?.dismiss()
        dialog = null
    }

    private fun dpToPx(dp: Int): Int = (dp * activity.resources.displayMetrics.density).toInt()

    private fun itemHeightPx() = dpToPx(ITEM_HEIGHT_DP)
    private fun itemWidthPx() = (itemHeightPx() * CARD_ASPECT).toInt()

    /**
     * Warms Glide's cache for every image the spin needs, then calls [onReady] — otherwise cards
     * pop in mid-spin as they scroll into view. Requests here must match [show]'s url *and*
     * target size exactly, since Glide's cache key includes the dimensions.
     */
    fun preloadReel(
        roulette: List<RouletteItem>,
        winner: ChallengeWinner,
        onReady: () -> Unit
    ) {
        val itemWidth = itemWidthPx()
        val itemHeight = itemHeightPx()
        val showcaseWidth = (itemWidth * MAX_SCALE * SHOWCASE_SCALE).toInt()
        val showcaseHeight = (itemHeight * MAX_SCALE * SHOWCASE_SCALE).toInt()

        // Distinct: the reel repeats, and duplicate urls would just collapse onto one request.
        val requests = roulette
            .map { CardImageUrl.sized(it.itemUrl, width = REEL_CDN_WIDTH) }
            .distinct()
            .map { Triple(it, itemWidth, itemHeight) }
            .toMutableList()
        winner.cardUrl?.let { requests.add(Triple(it, showcaseWidth, showcaseHeight)) }

        if (requests.isEmpty()) {
            onReady()
            return
        }

        var remaining = requests.size
        var settled = false
        fun finish() {
            if (settled) return
            settled = true
            onReady()
        }

        // Failsafe: one dead image shouldn't strand the user on a spinner forever.
        val timeout = Runnable {
            if (!settled) {
                Log.w(TAG, "Reel preload timed out with $remaining image(s) outstanding — spinning anyway")
                finish()
            }
        }
        handler.postDelayed(timeout, PRELOAD_TIMEOUT_MS)

        // Glide's listener fires on the main thread, so this counter needs no synchronisation.
        val counter = object : RequestListener<Drawable> {
            private fun done(): Boolean {
                remaining--
                if (remaining <= 0 && !settled) {
                    handler.removeCallbacks(timeout)
                    finish()
                }
                return false
            }

            override fun onLoadFailed(
                e: GlideException?,
                model: Any?,
                target: Target<Drawable>,
                isFirstResource: Boolean
            ): Boolean = done()

            override fun onResourceReady(
                resource: Drawable,
                model: Any,
                target: Target<Drawable>?,
                dataSource: DataSource,
                isFirstResource: Boolean
            ): Boolean = done()
        }

        requests.forEach { (url, width, height) ->
            Glide.with(activity).load(url).listener(counter).preload(width, height)
        }
    }

    /**
     * @param targetView the modal's card ImageView — where the winner flies to once it lands.
     * @param onFinished called after the winner has been delivered, for the modal to show its
     *        resolved state. The overlay is already gone by then.
     */
    fun show(
        roulette: List<RouletteItem>,
        winner: ChallengeWinner,
        targetView: View,
        onFinished: () -> Unit
    ) {
        if (isShowing || roulette.isEmpty()) return

        val view = LayoutInflater.from(activity).inflate(R.layout.dialog_roulette_overlay, null)
        // Plain Dialog rather than an AlertDialog: its content wrapper won't reliably fill the
        // screen, which a full-bleed scrim needs.
        val d = android.app.Dialog(activity, R.style.Theme_KcikTV_Dialog)
        d.requestWindowFeature(Window.FEATURE_NO_TITLE)
        d.setContentView(view)
        d.setCancelable(false)
        d.window?.apply {
            setBackgroundDrawableResource(android.R.color.transparent)
            setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
            // The theme dims behind by default, which would keep the modal greyed out even after
            // our own scrim fades — this view's scrim is the only darkening we want.
            clearFlags(android.view.WindowManager.LayoutParams.FLAG_DIM_BEHIND)
        }
        dialog = d
        d.show()

        val viewport = view.findViewById<View>(R.id.rouletteViewport)
        val strip = view.findViewById<LinearLayout>(R.id.rouletteStrip)

        val itemHeight = itemHeightPx()
        val itemWidth = itemWidthPx()
        val itemMargin = dpToPx(ITEM_MARGIN_DP)
        val pitch = itemWidth + itemMargin * 2

        // Repeat the reel so there's runway for a long spin, and land on a copy of the winner
        // partway in rather than one right at the start.
        val extended = (0 until REPEATS).flatMap { roulette }
        val winnerOffset = roulette.indexOfFirst { it.id == winner.id }.coerceAtLeast(0)
        val targetIndex = roulette.size * LANDING_REPEAT + winnerOffset

        strip.removeAllViews()
        extended.forEach { item ->
            val itemView = ImageView(activity).apply {
                layoutParams = LinearLayout.LayoutParams(itemWidth, itemHeight).apply {
                    marginStart = itemMargin
                    marginEnd = itemMargin
                }
                scaleType = ImageView.ScaleType.FIT_CENTER
            }
            // Must match preloadReel's url and target size, or this misses the warmed cache.
            Glide.with(activity)
                .load(CardImageUrl.sized(item.itemUrl, width = REEL_CDN_WIDTH))
                .override(itemWidth, itemHeight)
                .into(itemView)
            strip.addView(itemView)
        }

        // A wrap_content child of a FrameLayout is measured with an AT_MOST spec, which would
        // clamp the strip to the viewport and clip away everything past the first few cards.
        strip.layoutParams = strip.layoutParams.apply { width = extended.size * pitch }
        strip.requestLayout()

        viewport.post {
            if (viewport.width <= 0) return@post
            val viewportCenter = viewport.width / 2f
            // Come to rest somewhere across the winner, never dead on its centre. Sign and
            // magnitude are drawn separately so the offset is always clearly visible.
            val offsetSign = if (Math.random() < 0.5) -1f else 1f
            val offsetFraction = LANDING_OFFSET_MIN +
                Math.random().toFloat() * (LANDING_OFFSET_MAX - LANDING_OFFSET_MIN)
            val jitter = offsetSign * itemWidth * offsetFraction
            val endX = viewportCenter - (targetIndex * pitch + pitch / 2f) + jitter
            val startX = viewportCenter - (pitch + pitch / 2f)

            strip.translationX = startX
            applyProximityScales(strip, viewport, pitch)

            val animator = ValueAnimator.ofFloat(startX, endX).apply {
                duration = SPIN_DURATION_MS
                // Eases up off the line, then a long quint-ish tail into the stop.
                interpolator = PathInterpolator(0.12f, 0.62f, 0.08f, 1f)
                addUpdateListener { anim ->
                    strip.translationX = anim.animatedValue as Float
                    applyProximityScales(strip, viewport, pitch)
                }
                addListener(object : AnimatorListenerAdapter() {
                    override fun onAnimationEnd(animation: Animator) {
                        spinAnimator = null
                        onLanded(view, strip, jitter, itemWidth, itemHeight, winner, targetView, onFinished)
                    }
                })
            }
            spinAnimator = animator
            animator.start()
        }
    }

    /** Scales each card by distance from the pointer; smoothstep keeps the growth/shrink smooth. */
    private fun applyProximityScales(strip: LinearLayout, viewport: View, pitch: Int) {
        // Pointer position expressed in the strip's own coordinates.
        val pointerInStrip = viewport.width / 2f - strip.translationX
        for (i in 0 until strip.childCount) {
            val child = strip.getChildAt(i)
            val childCenter = child.left + child.width / 2f
            val distance = abs(childCenter - pointerInStrip)
            val t = (1f - distance / pitch).coerceIn(0f, 1f)
            val eased = t * t * (3f - 2f * t) // smoothstep
            val scale = MIN_SCALE + (MAX_SCALE - MIN_SCALE) * eased
            child.scaleX = scale
            child.scaleY = scale
        }
    }

    private fun onLanded(
        root: View,
        @Suppress("UNUSED_PARAMETER") strip: LinearLayout,
        jitter: Float,
        itemWidth: Int,
        itemHeight: Int,
        winner: ChallengeWinner,
        targetView: View,
        onFinished: () -> Unit
    ) {
        val flyer = root.findViewById<ImageView>(R.id.rouletteFlyer)
        val shineClip = root.findViewById<View>(R.id.rouletteShineClip)
        val shine = root.findViewById<View>(R.id.rouletteShine)
        val reelGroup = root.findViewById<View>(R.id.rouletteReelGroup)

        val winnerWidth = (itemWidth * MAX_SCALE).toInt()
        val winnerHeight = (itemHeight * MAX_SCALE).toInt()

        // Size before loading so Glide can't measure at the 10dp placeholder. The flyer is
        // centred in the root, so nudge it by `jitter` to where the winner actually landed.
        flyer.layoutParams = flyer.layoutParams.apply {
            width = winnerWidth
            height = winnerHeight
        }
        flyer.requestLayout()
        flyer.translationX = jitter
        flyer.alpha = 0f

        // Explicit override(): otherwise Glide decodes to the ImageView's placeholder size at
        // request time, which is what made the card arrive blurry. Decode at showcase size,
        // the largest this card ever gets drawn.
        winner.cardUrl?.let {
            Glide.with(activity)
                .load(it)
                .override(
                    (winnerWidth * SHOWCASE_SCALE).toInt(),
                    (winnerHeight * SHOWCASE_SCALE).toInt()
                )
                .into(flyer)
        }
        flyer.visibility = View.VISIBLE

        // Swap the reel out for the winner's own card art.
        flyer.animate().alpha(1f).setDuration(220).start()
        reelGroup.animate().alpha(0f).setStartDelay(120).setDuration(260).start()

        // Showcase: pull the winner from where it landed to dead centre, grow it, and spin it
        // through a full turn. Only once it settles does the light start running over it.
        flyer.cameraDistance = 12000f * activity.resources.displayMetrics.density
        flyer.animate()
            .translationX(0f)
            .scaleX(SHOWCASE_SCALE)
            .scaleY(SHOWCASE_SCALE)
            .rotationY(360f)
            .setStartDelay(180)
            .setDuration(760)
            .setInterpolator(DecelerateInterpolator(1.4f))
            .withEndAction {
                if (!isShowing) return@withEndAction
                // Normalise so the fly-out animates from a clean transform.
                flyer.rotationY = 0f
                startShowcaseShine(shineClip, shine, winnerWidth, winnerHeight)
            }
            .start()

        // Let the win register before handing it off to the modal.
        handler.postDelayed({
            if (!isShowing) return@postDelayed
            flyToModal(root, flyer, shineClip, targetView, onFinished)
        }, 2700L)
    }

    /** Runs the light sweep over the winner at its showcase size, centred. */
    private fun startShowcaseShine(shineClip: View, shine: View, winnerWidth: Int, winnerHeight: Int) {
        val showcaseWidth = (winnerWidth * SHOWCASE_SCALE).toInt()
        val showcaseHeight = (winnerHeight * SHOWCASE_SCALE).toInt()
        shineClip.layoutParams = shineClip.layoutParams.apply {
            width = showcaseWidth
            height = showcaseHeight
        }
        shineClip.requestLayout()
        shineClip.translationX = 0f
        shineClip.visibility = View.VISIBLE
        sweepShine(shine, showcaseWidth, showcaseHeight)
    }

    /**
     * Travels exactly corner to corner (half-width + half-height·tan of the rotation). The bar's
     * own width isn't added — its edges are the transparent ends of the gradient.
     */
    private fun sweepShine(shine: View, clipWidth: Int, clipHeight: Int) {
        val radians = Math.toRadians(SHINE_ROTATION_DEG.toDouble())
        val travel = clipWidth / 2f + clipHeight / 2f * kotlin.math.tan(radians).toFloat()
        if (travel <= 0f) return

        shine.translationX = -travel
        shine.visibility = View.VISIBLE
        shine.animate()
            .translationX(travel)
            .setDuration(850)
            .setInterpolator(LinearInterpolator())
            .withEndAction {
                shine.visibility = View.INVISIBLE
                val runnable = Runnable { sweepShine(shine, clipWidth, clipHeight) }
                shineRunnable = runnable
                handler.postDelayed(runnable, 900L)
            }
            .start()
    }

    /** Flies the winner from where it landed down into the modal's card slot. */
    private fun flyToModal(
        root: View,
        flyer: ImageView,
        shineClip: View,
        targetView: View,
        onFinished: () -> Unit
    ) {
        shineRunnable?.let { handler.removeCallbacks(it) }
        shineRunnable = null
        shineClip.visibility = View.INVISIBLE

        val rootLoc = IntArray(2)
        root.getLocationOnScreen(rootLoc)
        val targetLoc = IntArray(2)
        targetView.getLocationOnScreen(targetLoc)

        // Measure the real art rather than assuming the usual card size, or a card off that
        // aspect lands the flyer at the wrong scale and jumps the instant the modal takes over.
        val artWidth = flyer.drawable?.intrinsicWidth?.takeIf { it > 0 } ?: CardImageUrl.NATIVE_WIDTH
        val artHeight = flyer.drawable?.intrinsicHeight?.takeIf { it > 0 } ?: CardImageUrl.NATIVE_HEIGHT

        // Both views paint the art fitCenter, so compare how large each draws it and scale by the
        // ratio — that holds whatever the card's aspect turns out to be.
        val availWidth = targetView.width - targetView.paddingLeft - targetView.paddingRight
        val availHeight = targetView.height - targetView.paddingTop - targetView.paddingBottom
        val targetFit = minOf(availWidth.toFloat() / artWidth, availHeight.toFloat() / artHeight)
        val flyerFit = minOf(flyer.width.toFloat() / artWidth, flyer.height.toFloat() / artHeight)

        // The flyer is centred in the root, so its translation is simply the offset between the
        // root's centre and the modal card's centre, both in screen coordinates.
        val rootCenterX = rootLoc[0] + root.width / 2f
        val rootCenterY = rootLoc[1] + root.height / 2f
        val targetCenterX = targetLoc[0] + targetView.width / 2f
        val targetCenterY = targetLoc[1] + targetView.height / 2f

        val scaleTo = if (flyerFit > 0f) targetFit / flyerFit else 1f

        // Only the backdrop fades — the flyer is a sibling, so it stays fully opaque in flight.
        root.findViewById<View>(R.id.rouletteScrim)
            .animate().alpha(0f).setStartDelay(140).setDuration(420).start()

        flyer.animate()
            .translationX(targetCenterX - rootCenterX)
            .translationY(targetCenterY - rootCenterY)
            .scaleX(scaleTo)
            .scaleY(scaleTo)
            .setDuration(560)
            .setInterpolator(DecelerateInterpolator(1.6f))
            .withEndAction {
                onFinished()
                dismiss()
            }
            .start()
    }
}
