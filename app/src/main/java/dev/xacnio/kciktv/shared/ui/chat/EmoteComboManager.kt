/**
 * File: EmoteComboManager.kt
 *
 * Description: Manages business logic, state, and UI interactions for Emote Combo.
 *
 * Author: Xacnio
 *
 */
package dev.xacnio.kciktv.shared.ui.chat

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.AnimatorSet
import android.animation.ObjectAnimator
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.animation.OvershootInterpolator
import android.widget.ImageView
import android.widget.TextView
import dev.xacnio.kciktv.R
import dev.xacnio.kciktv.shared.ui.utils.EmoteManager
import dev.xacnio.kciktv.shared.data.prefs.AppPreferences
import java.util.concurrent.ConcurrentHashMap
import java.util.regex.Pattern

/**
 * Manages emote combos (streaks) in the chat.
 * Shows a visual counter when an emote is spammed.
 */
class EmoteComboManager(
    private val context: Context,
    private val container: ViewGroup,
    private val prefs: AppPreferences
) {
    
    // Config
    private val COMBO_THRESHOLD = 5 // Minimum count to show combo
    private val COMBO_TIMEOUT_MS = 4000L // Time without new emotes before combo fades out
    private val MAX_VISIBLE_COMBOS = 3 // Max concurrent combos shown
    
    // State
    private val activeCombos = ConcurrentHashMap<String, ComboState>()
    private val emotePattern = Pattern.compile("\\[emote:(\\d+):([^\\]]+)\\]")
    private val mainHandler = Handler(Looper.getMainLooper())
    
    data class ComboState(
        val emoteId: String,
        val emoteName: String,
        var count: Int = 0,
        var lastUpdateTime: Long = 0L,
        var view: View? = null,
        var timeoutRunnable: Runnable? = null
    )
    
    fun processMessage(messageContent: String) {
        if (!prefs.emoteComboEnabled) return

        // Clean up message
        val trimmed = messageContent.trim()
        
        // Find all emotes
        val matcher = emotePattern.matcher(trimmed)
        val emotesFound = mutableListOf<Pair<String, String>>()
        
        while (matcher.find()) {
            val emoteId = matcher.group(1) ?: continue
            val emoteName = matcher.group(2) ?: "emote"
            emotesFound.add(emoteId to emoteName)
        }
        
        // If no emotes found, ignore
        if (emotesFound.isEmpty()) return
        
        // Check if message consists ONLY of emotes (plus spaces)
        val textWithoutEmotes = emotePattern.matcher(trimmed).replaceAll("").trim()
        if (textWithoutEmotes.isNotEmpty()) return // Contains other text, ignore for combo
        
        // Check if all emotes are the SAME emote
        val firstEmoteId = emotesFound[0].first
        val allSame = emotesFound.all { it.first == firstEmoteId }
        
        if (allSame) {
            // Valid combo hit! Count as +1 regardless of how many emotes are in the message (anti-spam)
            // Or count as +1 per message (standard combo behavior)
            handleEmoteOccurrence(firstEmoteId, emotesFound[0].second)
        }
    }
    
    private fun handleEmoteOccurrence(emoteId: String, emoteName: String) {
        mainHandler.post {
            val combo = activeCombos.getOrPut(emoteId) {
                ComboState(emoteId, emoteName)
            }
            
            combo.count++
            combo.lastUpdateTime = System.currentTimeMillis()
            
            // Reset timeout
            combo.timeoutRunnable?.let { mainHandler.removeCallbacks(it) }
            combo.timeoutRunnable = Runnable {
                removeCombo(emoteId)
            }
            mainHandler.postDelayed(combo.timeoutRunnable!!, COMBO_TIMEOUT_MS)
            
            // Show or Update UI
            if (combo.count >= COMBO_THRESHOLD) {
                updateComboUI(combo)
            }
        }
    }
    
    private fun updateComboUI(combo: ComboState) {
        if (combo.view == null) {
            // Check limits first
            if (container.childCount >= MAX_VISIBLE_COMBOS) {
                // If we have too many, don't show new ones unless they have higher count than existing?
                // For simplicity, just ignore new ones for now or remove oldest
                return 
            }
            createComboView(combo)
        } else {
            // Update existing view
            val view = combo.view!!
            val countText = view.findViewById<TextView>(R.id.comboCount)
            countText.text = "x${combo.count}" // Add 'x' prefix to count for style
            
            // Bump animation
            playBumpAnimation(view)
        }
    }
    
    private fun createComboView(combo: ComboState) {
        val view = LayoutInflater.from(context).inflate(R.layout.item_emote_combo, container, false)
        combo.view = view
        
        val emoteImage = view.findViewById<ImageView>(R.id.emoteImage)
        val countText = view.findViewById<TextView>(R.id.comboCount)
        
        countText.text = "x${combo.count}"
        
        // Load Emote
        val size = (32 * context.resources.displayMetrics.density).toInt() // Reduced size
        EmoteManager.loadSynchronizedEmote(context, combo.emoteId, size, emoteImage) { drawable ->
            emoteImage.setImageDrawable(drawable)
        }
        
        // Add to container with entry animation
        container.addView(view)
        
        // Entry Animation (Slide in from right + Scale up)
        view.translationX = 200f
        view.scaleX = 0.5f
        view.scaleY = 0.5f
        view.alpha = 0f
        
        view.animate()
            .translationX(0f)
            .scaleX(1f)
            .scaleY(1f)
            .alpha(1f)
            .setDuration(300)
            .setInterpolator(OvershootInterpolator(1.2f))
            .start()
    }
    
    private fun playBumpAnimation(view: View) {
        // Cancel any pending animations on scale to avoid conflict? 
        // ViewPropertyAnimator handles this automatically for the same properties
        
        view.animate()
            .scaleX(1.2f)
            .scaleY(1.2f)
            .setDuration(80)
            .withEndAction {
                view.animate()
                    .scaleX(1f)
                    .scaleY(1f)
                    .setDuration(120)
                    .setInterpolator(OvershootInterpolator())
                    .start()
            }
            .start()
            
        // Flash count color?
        val countText = view.findViewById<TextView>(R.id.comboCount)
        countText.animate()
            .scaleX(1.3f)
            .scaleY(1.3f)
            .setDuration(100)
            .withEndAction {
                countText.animate()
                    .scaleX(1f)
                    .scaleY(1f)
                    .setDuration(100)
                    .start()
            }
            .start()
    }
    
    private fun removeCombo(emoteId: String) {
        val combo = activeCombos[emoteId] ?: return
        
        combo.view?.let { view ->
            // Exit animation - just fade out smoothly
            view.animate()
                .alpha(0f)
                .setDuration(250)
                .setListener(object : AnimatorListenerAdapter() {
                    override fun onAnimationEnd(animation: Animator) {
                        container.removeView(view)
                    }
                })
                .start()
        }
        
        activeCombos.remove(emoteId)
    }
    
    fun clear() {
        mainHandler.removeCallbacksAndMessages(null)
        activeCombos.clear()
        container.removeAllViews()
    }
}
