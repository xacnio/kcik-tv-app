/**
 * File: FloatingEmoteManager.kt
 *
 * Description: Manages business logic, state, and UI interactions for Floating Emote.
 *
 * Author: Xacnio
 *
 */
package dev.xacnio.kciktv.shared.ui.chat

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Path
import android.graphics.PathMeasure
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.view.animation.LinearInterpolator
import android.widget.FrameLayout
import android.widget.ImageView
import dev.xacnio.kciktv.shared.ui.utils.EmoteManager
import dev.xacnio.kciktv.shared.data.prefs.AppPreferences
import java.util.Random
import java.util.regex.Pattern

/**
 * Manages floating emotes animation (like hearts in live streams).
 * Emotes float up from the bottom right side of the screen.
 */
class FloatingEmoteManager(
    private val context: Context,
    private val container: ViewGroup,
    private val prefs: AppPreferences
) {
    private val emotePattern = Pattern.compile("\\[emote:(\\d+):([^\\]]+)\\]")
    private val random = Random()
    private val mainHandler = Handler(Looper.getMainLooper())
    
    // Config
    private val MAX_SIMULTANEOUS_EMOTES = 15 // Limit views for performance and battery
    private val SPAWN_RATE_LIMIT_MS = 100L // Min time between spawns per emote
    private var lastSpawnTime = 0L
    
    fun processMessage(messageContent: String) {
        if (!prefs.floatingEmotesEnabled) return

        // Simple rate limiting globally to prevent explosion
        val now = System.currentTimeMillis()
        if (now - lastSpawnTime < 50) return // Max 20 emotes per second globally from messages
        
        val matcher = emotePattern.matcher(messageContent)
        var emotesInMessage = 0
        
        while (matcher.find()) {
            if (emotesInMessage >= 3) break // Max 3 emotes per message to float
            
            val emoteId = matcher.group(1) ?: continue
            
            // Random chance to skip some emotes if chat is super fast
            if (container.childCount > 20 && random.nextFloat() > 0.5f) continue
            
            spawnFloatingEmote(emoteId)
            emotesInMessage++
            lastSpawnTime = System.currentTimeMillis()
        }
    }
    
    private fun spawnFloatingEmote(emoteId: String) {
        if (container.childCount >= MAX_SIMULTANEOUS_EMOTES) return
        
        val emoteView = ImageView(context)
        val size = (20 + random.nextInt(10)).dpToPx() // Tiny size: 20-30dp
        val params = FrameLayout.LayoutParams(size, size)
        
        // Start position: Will be set in animation, just add to view
        params.leftMargin = 0
        params.topMargin = 0
        emoteView.layoutParams = params
        
        // Alpha mostly transparent
        emoteView.alpha = 0.7f
        
        // Load Emote
        EmoteManager.loadSynchronizedEmote(context, emoteId, size, emoteView) { drawable ->
            emoteView.setImageDrawable(drawable)
        }
        
        container.addView(emoteView)
        
        // Animation
        animateEmote(emoteView, size)
    }
    
    private fun animateEmote(view: View, size: Int) {
        val parentHeight = container.height.toFloat()
        val parentWidth = container.width.toFloat()
        
        if (parentHeight == 0f || parentWidth == 0f) {
            container.removeView(view)
            return
        }
        
        // Ensure within bounds
        val safeWidth = parentWidth - size
        val startX = (random.nextFloat() * safeWidth * 0.8f) + (safeWidth * 0.1f) // Center-ish spread
        val startY = parentHeight // Start from very bottom
        
        // Control points for Bezier path (S-curve)
        val path = Path()
        path.moveTo(startX, startY)
        
        // Gentle S-curve, keeping within width bounds
        val controlX1 = (startX + (random.nextInt(100) - 50).dpToPx()).coerceIn(0f, safeWidth)
        val controlY1 = parentHeight * 0.6f
        
        val controlX2 = (startX + (random.nextInt(100) - 50).dpToPx()).coerceIn(0f, safeWidth)
        val controlY2 = parentHeight * 0.3f
        
        val endX = (startX + (random.nextInt(60) - 30).dpToPx()).coerceIn(0f, safeWidth)
        val endY = -size.toFloat() // Just above top
        
        path.cubicTo(controlX1, controlY1, controlX2, controlY2, endX, endY)
        
        // Rotation Animation
        val rotDuration = 2000L + random.nextInt(2000)
        val startRot = random.nextFloat() * 30f - 15f
        val endRot = random.nextFloat() * 60f - 30f
        
        view.rotation = startRot
        view.animate()
            .rotation(endRot)
            .setDuration(rotDuration)
            .start()
            
        // Path Animation
        val animator = ObjectAnimator.ofFloat(view, View.X, View.Y, path)
        animator.duration = 3000L + random.nextInt(2000) // 3-5 seconds duration
        animator.interpolator = LinearInterpolator() // Constant speed upward looks more like floating
        
        // Fade out at end
        // Fade out at end - start earlier to disappear before hitting top edge strictly
        view.animate()
            .alpha(0f)
            .setStartDelay(animator.duration - 1200) // Start fading earlier
            .setDuration(1000)
            .start()
            
        // Ensure on top
        view.elevation = 20f
            
        animator.addListener(object : AnimatorListenerAdapter() {
            override fun onAnimationEnd(animation: Animator) {
                container.removeView(view)
            }
        })
        
        animator.start()
    }
    
    private fun Int.dpToPx(): Int {
        return (this * context.resources.displayMetrics.density).toInt()
    }
    
    fun clear() {
        mainHandler.removeCallbacksAndMessages(null)
        container.removeAllViews()
    }
}
