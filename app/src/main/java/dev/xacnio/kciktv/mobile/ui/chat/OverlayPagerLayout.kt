/**
 * File: OverlayPagerLayout.kt
 *
 * Description: Swipeable container for the chat overlay cards (pinned message, poll, prediction).
 *
 * Author: Xacnio
 *
 */
package dev.xacnio.kciktv.mobile.ui.chat

import android.content.Context
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.VelocityTracker
import android.view.View
import android.view.ViewConfiguration
import android.view.animation.DecelerateInterpolator
import android.widget.FrameLayout
import kotlin.math.abs

/**
 * Drag-to-page container for the overlay cards. Both gestures are claimed in
 * [onInterceptTouchEvent] because the cards are clickable and would otherwise swallow them:
 * drag sideways to change card, drag up to dismiss. Taps still reach the children.
 */
class OverlayPagerLayout @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : FrameLayout(context, attrs, defStyleAttr) {

    /** How many cards are currently swipeable; paging is off while this is 1 or less. */
    var pageCount: () -> Int = { 0 }

    /** The card on screen — the one that follows the finger. */
    var activePage: () -> View? = { null }

    var onNext: (() -> Unit)? = null
    var onPrev: (() -> Unit)? = null
    var onDismiss: (() -> Unit)? = null

    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop
    private val minFlingVelocity = ViewConfiguration.get(context).scaledMinimumFlingVelocity

    private enum class Drag { NONE, HORIZONTAL, UP }

    private var downX = 0f
    private var downY = 0f
    private var drag = Drag.NONE
    private var velocityTracker: VelocityTracker? = null

    override fun onInterceptTouchEvent(ev: MotionEvent): Boolean {
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> startTracking(ev)
            MotionEvent.ACTION_MOVE -> {
                velocityTracker?.addMovement(ev)
                if (claimGesture(ev)) {
                    parent?.requestDisallowInterceptTouchEvent(true)
                    return true
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> stopTracking()
        }
        return false
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                startTracking(event)
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                velocityTracker?.addMovement(event)
                if (drag == Drag.NONE) claimGesture(event) else followFinger(event)
                return true
            }
            MotionEvent.ACTION_UP -> {
                velocityTracker?.addMovement(event)
                settle(event.x - downX, event.y - downY)
                stopTracking()
                return true
            }
            MotionEvent.ACTION_CANCEL -> {
                springBack()
                stopTracking()
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    private fun startTracking(ev: MotionEvent) {
        downX = ev.x
        downY = ev.y
        drag = Drag.NONE
        velocityTracker?.recycle()
        velocityTracker = VelocityTracker.obtain().also { it.addMovement(ev) }
    }

    private fun stopTracking() {
        drag = Drag.NONE
        velocityTracker?.recycle()
        velocityTracker = null
    }

    /** Decides which gesture this is once it clears the slop, and takes it from the children. */
    private fun claimGesture(ev: MotionEvent): Boolean {
        if (drag != Drag.NONE) return false
        val dx = ev.x - downX
        val dy = ev.y - downY
        drag = when {
            abs(dx) > touchSlop && abs(dx) > abs(dy) && pageCount() > 1 -> Drag.HORIZONTAL
            dy < -touchSlop && abs(dy) > abs(dx) -> Drag.UP
            else -> Drag.NONE
        }
        return drag != Drag.NONE
    }

    private fun followFinger(ev: MotionEvent) {
        val page = activePage() ?: return
        when (drag) {
            Drag.HORIZONTAL -> page.translationX = ev.x - downX
            // Downward is not a gesture here, so never let the card travel below its resting spot
            Drag.UP -> page.translationY = (ev.y - downY).coerceAtMost(0f)
            Drag.NONE -> Unit
        }
    }

    private fun settle(dx: Float, dy: Float) {
        val tracker = velocityTracker
        tracker?.computeCurrentVelocity(1000)

        when (drag) {
            Drag.HORIZONTAL -> {
                val flung = abs(tracker?.xVelocity ?: 0f) > minFlingVelocity && abs(dx) > touchSlop
                if (abs(dx) > width / 4f || flung) {
                    // The card stays where the finger left it; navigation slides it the rest of the way
                    if (dx < 0) onNext?.invoke() else onPrev?.invoke()
                } else {
                    springBack()
                }
            }
            Drag.UP -> {
                val page = activePage()
                val flungUp = (tracker?.yVelocity ?: 0f) < -minFlingVelocity
                val travelled = abs(dy) > (page?.height ?: height) / 2f
                if (flungUp || travelled) onDismiss?.invoke() else springBack()
            }
            Drag.NONE -> Unit
        }
    }

    private fun springBack() {
        val page = activePage() ?: return
        page.animate()
            .translationX(0f)
            .translationY(0f)
            .setDuration(180)
            .setInterpolator(DecelerateInterpolator(1.5f))
            .start()
    }
}
