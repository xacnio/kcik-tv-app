/**
 * File: ChatMessageAnimator.kt
 *
 * Description: Implementation of Chat Message Animator functionality.
 *
 * Author: Xacnio
 *
 */
package dev.xacnio.kciktv.shared.ui.adapter

import android.animation.AnimatorSet
import android.animation.ObjectAnimator
import android.view.animation.DecelerateInterpolator
import android.view.animation.OvershootInterpolator
import androidx.recyclerview.widget.DefaultItemAnimator
import androidx.recyclerview.widget.RecyclerView

/**
 * Custom ItemAnimator for chat messages.
 * Supports multiple animation types for new messages entering the RecyclerView.
 */
class ChatMessageAnimator : DefaultItemAnimator() {

    enum class AnimationType(val key: String) {
        NONE("none"),
        FADE_IN("fade_in"),
        SLIDE_LEFT("slide_left"),
        SLIDE_RIGHT("slide_right"),
        SLIDE_BOTTOM("slide_bottom"),
        SCALE("scale");

        companion object {
            fun fromKey(key: String): AnimationType {
                return entries.firstOrNull { it.key == key } ?: NONE
            }
        }
    }

    var animationType: AnimationType = AnimationType.FADE_IN

    private val ANIMATION_DURATION = 250L
    private val pendingAdditions = mutableListOf<RecyclerView.ViewHolder>()

    init {
        addDuration = ANIMATION_DURATION
        removeDuration = 120L
        supportsChangeAnimations = false
    }

    override fun animateAdd(holder: RecyclerView.ViewHolder): Boolean {
        if (animationType == AnimationType.NONE) {
            dispatchAddFinished(holder)
            return false
        }

        holder.itemView.apply {
            // Pre-set initial state based on animation type
            when (animationType) {
                AnimationType.FADE_IN -> {
                    alpha = 0f
                }
                AnimationType.SLIDE_LEFT -> {
                    alpha = 0f
                    translationX = -width.toFloat().coerceAtLeast(200f)
                }
                AnimationType.SLIDE_RIGHT -> {
                    alpha = 0f
                    translationX = width.toFloat().coerceAtLeast(200f)
                }
                AnimationType.SLIDE_BOTTOM -> {
                    alpha = 0f
                    translationY = height.toFloat().coerceAtLeast(80f)
                }
                AnimationType.SCALE -> {
                    alpha = 0f
                    scaleX = 0.8f
                    scaleY = 0.8f
                }
                AnimationType.NONE -> { /* handled above */ }
            }
        }

        pendingAdditions.add(holder)
        return true
    }

    override fun runPendingAnimations() {
        super.runPendingAnimations()

        if (pendingAdditions.isEmpty()) return

        val additions = ArrayList(pendingAdditions)
        pendingAdditions.clear()

        for (holder in additions) {
            animateAddImpl(holder)
        }
    }

    private fun animateAddImpl(holder: RecyclerView.ViewHolder) {
        val view = holder.itemView
        val animSet = AnimatorSet()

        val animators = when (animationType) {
            AnimationType.FADE_IN -> {
                listOf(
                    ObjectAnimator.ofFloat(view, "alpha", 0f, 1f)
                )
            }
            AnimationType.SLIDE_LEFT -> {
                listOf(
                    ObjectAnimator.ofFloat(view, "alpha", 0f, 1f),
                    ObjectAnimator.ofFloat(view, "translationX", view.translationX, 0f)
                )
            }
            AnimationType.SLIDE_RIGHT -> {
                listOf(
                    ObjectAnimator.ofFloat(view, "alpha", 0f, 1f),
                    ObjectAnimator.ofFloat(view, "translationX", view.translationX, 0f)
                )
            }
            AnimationType.SLIDE_BOTTOM -> {
                listOf(
                    ObjectAnimator.ofFloat(view, "alpha", 0f, 1f),
                    ObjectAnimator.ofFloat(view, "translationY", view.translationY, 0f)
                )
            }
            AnimationType.SCALE -> {
                listOf(
                    ObjectAnimator.ofFloat(view, "alpha", 0f, 1f),
                    ObjectAnimator.ofFloat(view, "scaleX", 0.8f, 1f),
                    ObjectAnimator.ofFloat(view, "scaleY", 0.8f, 1f)
                )
            }
            AnimationType.NONE -> {
                dispatchAddFinished(holder)
                return
            }
        }

        animSet.playTogether(animators)
        animSet.duration = ANIMATION_DURATION
        animSet.interpolator = when (animationType) {
            AnimationType.SCALE -> OvershootInterpolator(1.2f)
            else -> DecelerateInterpolator(1.5f)
        }

        animSet.addListener(object : android.animation.AnimatorListenerAdapter() {
            override fun onAnimationEnd(animation: android.animation.Animator) {
                // Reset view state
                view.alpha = 1f
                view.translationX = 0f
                view.translationY = 0f
                view.scaleX = 1f
                view.scaleY = 1f
                dispatchAddFinished(holder)
            }

            override fun onAnimationCancel(animation: android.animation.Animator) {
                // Reset view state
                view.alpha = 1f
                view.translationX = 0f
                view.translationY = 0f
                view.scaleX = 1f
                view.scaleY = 1f
            }
        })

        dispatchAddStarting(holder)
        animSet.start()
    }

    override fun endAnimation(item: RecyclerView.ViewHolder) {
        item.itemView.apply {
            alpha = 1f
            translationX = 0f
            translationY = 0f
            scaleX = 1f
            scaleY = 1f
        }
        super.endAnimation(item)
    }

    override fun endAnimations() {
        for (holder in pendingAdditions) {
            holder.itemView.apply {
                alpha = 1f
                translationX = 0f
                translationY = 0f
                scaleX = 1f
                scaleY = 1f
            }
            dispatchAddFinished(holder)
        }
        pendingAdditions.clear()
        super.endAnimations()
    }
}
