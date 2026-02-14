/**
 * File: ChatRulesManager.kt
 *
 * Description: Manages business logic, state, and UI interactions for Chat Rules.
 *
 * Author: Xacnio
 *
 */
package dev.xacnio.kciktv.shared.ui.chat

import android.content.Context
import android.transition.Slide
import android.transition.TransitionManager
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.animation.AccelerateDecelerateInterpolator
import android.widget.TextView
import android.widget.ImageView
import android.widget.Toast
import androidx.lifecycle.LifecycleCoroutineScope
import com.bumptech.glide.Glide
import com.google.android.material.button.MaterialButton
import dev.xacnio.kciktv.R
import dev.xacnio.kciktv.shared.data.prefs.AppPreferences
import dev.xacnio.kciktv.shared.data.repository.ChannelRepository
import dev.xacnio.kciktv.shared.data.model.ChannelItem
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.security.MessageDigest
import android.util.Log

/**
 * Manages the display and acceptance of Chat Rules.
 */
class ChatRulesManager(
    private val context: Context,
    private var rootContainer: ViewGroup,
    private val scope: LifecycleCoroutineScope,
    private val repository: ChannelRepository,
    private val prefs: AppPreferences
) {
    private var rulesView: View? = null
    private var currentSlug: String? = null
    private var currentRulesHash: String? = null
    private val cachedRules = mutableMapOf<String, String>()

    companion object {
        private const val TAG = "ChatRulesManager"
    }
    
    /**
     * Updates the container where rules panel is displayed.
     * Useful for switching between fullscreen and chat-area containers in landscape mode.
     */
    fun updateContainer(newContainer: ViewGroup) {
        // If currently showing, dismiss and re-show in new container
        val wasShowing = isShowing()
        if (wasShowing) {
            rulesView?.let { view ->
                rootContainer.removeView(view)
            }
        }
        rootContainer = newContainer
        if (wasShowing) {
            rulesView?.let { view ->
                rootContainer.addView(view)
            }
        }
    }

    /**
     * Checks if rules need to be shown for the channel.
     * If not accepted or changed, fetches and shows them.
     */
    fun checkAndShowRules(channel: ChannelItem) {
        currentSlug = channel.slug
        
        // Check cache first
        val cached = cachedRules[channel.slug]
        if (cached != null) {
            val rulesHash = hashRules(cached)
            val acceptedHash = prefs.getAcceptedRuleHash(channel.slug)
            
            if (acceptedHash != rulesHash) {
                currentRulesHash = rulesHash
                showRulesPanel(channel, cached, isForced = false)
            }
            return
        }
        
        scope.launch {
            val result = repository.getChatRules(channel.slug)
            result.onSuccess { rulesText ->
                if (!rulesText.isNullOrBlank()) {
                    cachedRules[channel.slug] = rulesText
                    val rulesHash = hashRules(rulesText)
                    val acceptedHash = prefs.getAcceptedRuleHash(channel.slug)
                    
                    if (acceptedHash != rulesHash) {
                        currentRulesHash = rulesHash
                        showRulesPanel(channel, rulesText, isForced = false)
                    }
                } else {
                    Log.d(TAG, "No rules found or empty for ${channel.slug}")
                }
            }.onFailure {
                Log.e(TAG, "Failed to check rules for ${channel.slug}", it)
            }
        }
    }

    /**
     * Force show rules (e.g. from Settings menu).
     * Uses cached rules if available, otherwise fetches from API.
     */
    fun forceShowRules(channel: ChannelItem, isViewOnly: Boolean = true) {
        val cached = cachedRules[channel.slug]
        if (cached != null) {
            showRulesFromText(channel, cached, isViewOnly)
            return
        }
        
        scope.launch {
            val result = repository.getChatRules(channel.slug)
            
            result.onSuccess { rulesText ->
                 if (!rulesText.isNullOrBlank()) {
                    cachedRules[channel.slug] = rulesText
                    showRulesFromText(channel, rulesText, isViewOnly)
                 } else {
                     withContext(Dispatchers.Main) {
                         Toast.makeText(context, context.getString(R.string.chat_rules_not_found), Toast.LENGTH_SHORT).show()
                     }
                 }
            }.onFailure {
                withContext(Dispatchers.Main) {
                    Toast.makeText(context, context.getString(R.string.chat_rules_load_error), Toast.LENGTH_SHORT).show()
                }
            }
        }
    }
    
    private fun showRulesFromText(channel: ChannelItem, rulesText: String, isViewOnly: Boolean) {
        val rulesHash = hashRules(rulesText)
        val acceptedHash = prefs.getAcceptedRuleHash(channel.slug)
        val isNotAccepted = acceptedHash != rulesHash
        val effectiveIsViewOnly = if (isNotAccepted) false else isViewOnly
        
        currentSlug = channel.slug
        currentRulesHash = rulesHash
        
        showRulesPanel(channel, rulesText, isForced = effectiveIsViewOnly)
    }

    private fun showRulesPanel(channel: ChannelItem, rules: String, isForced: Boolean) {
        // Remove existing view if any
        dismiss()

        val inflater = LayoutInflater.from(context)
        val view = inflater.inflate(R.layout.view_chat_rules, rootContainer, false)
        rulesView = view

        // Setup View
        val iconView = view.findViewById<ImageView>(R.id.chatRulesChannelIcon)
        val nameView = view.findViewById<TextView>(R.id.chatRulesChannelName)
        val rulesTextView = view.findViewById<TextView>(R.id.chatRulesText)
        val btnAgree = view.findViewById<MaterialButton>(R.id.btnAgreeRules)
        val btnClose = view.findViewById<ImageView>(R.id.chatRulesCloseBtn)

        nameView.text = channel.username
        
        // Parse only https links and setup clickable spans with warning dialog
        val spannable = android.text.SpannableStringBuilder(rules)
        val urlPattern = java.util.regex.Pattern.compile("https?://\\S+")
        val matcher = urlPattern.matcher(rules)
        
        while (matcher.find()) {
            val fullUrl = matcher.group()
            var start = matcher.start()
            var end = matcher.end()
            
            // Trim trailing punctuation like ChatAdapter
            while (end > start) {
                val c = rules[end - 1]
                if (c == '.' || c == ',' || c == '!' || c == '?' || c == ')' || c == ']' || c == ';') {
                    end--
                } else {
                    break
                }
            }
            
            val url = rules.substring(start, end)
            if (url.startsWith("https://")) {
                val clickableSpan = object : android.text.style.ClickableSpan() {
                    override fun onClick(widget: View) {
                         dev.xacnio.kciktv.mobile.util.DialogUtils.showLinkConfirmationDialog(widget.context, url)
                    }
                    override fun updateDrawState(ds: android.text.TextPaint) {
                        super.updateDrawState(ds)
                        ds.isUnderlineText = false
                        ds.color = 0xFF53FC18.toInt()
                    }
                }
                spannable.setSpan(clickableSpan, start, end, android.text.Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
            }
        }
        
        rulesTextView.text = spannable
        rulesTextView.movementMethod = android.text.method.LinkMovementMethod.getInstance()

        // Load channel icon - use effective picture
        val picUrl = channel.getEffectiveProfilePicUrl()
        Glide.with(context)
            .load(picUrl)
            .placeholder(R.drawable.default_avatar)
            .circleCrop()
            .into(iconView)
        
        if (isForced) {
            btnAgree.text = context.getString(R.string.chat_rules_close)
            btnAgree.setBackgroundColor(0xFF444444.toInt())
            btnAgree.setTextColor(0xFFFFFFFF.toInt())
        } else {
            btnAgree.text = context.getString(R.string.chat_rules_agree)
        }

        btnAgree.setOnClickListener {
            if (!isForced && currentSlug != null && currentRulesHash != null) {
                // Save acceptance
                prefs.setAcceptedRuleHash(currentSlug!!, currentRulesHash!!)
            }
            dismiss()
        }

        btnClose.setOnClickListener {
            dismiss()
        }

        // Add to parent
        // Use auto transition for slide up effect
        val transition = Slide(Gravity.BOTTOM)
        transition.duration = 400
        transition.interpolator = AccelerateDecelerateInterpolator()
        transition.addTarget(view)
        
        TransitionManager.beginDelayedTransition(rootContainer, transition)
        rootContainer.addView(view)
        
        // Ensure it is at the bottom
        val params = view.layoutParams
        if (params is android.widget.FrameLayout.LayoutParams) {
            params.gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
        } else if (params is androidx.coordinatorlayout.widget.CoordinatorLayout.LayoutParams) {
            params.gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
        } else if (params is android.widget.LinearLayout.LayoutParams) {
            params.width = ViewGroup.LayoutParams.MATCH_PARENT
            params.height = ViewGroup.LayoutParams.WRAP_CONTENT
            params.weight = 0f
            // If vertical linear layout, gravity controls horizontal alignment
            params.gravity = Gravity.CENTER_HORIZONTAL
        }
        view.layoutParams = params
    }

    fun dismiss() {
        rulesView?.let { view ->
            val transition = Slide(Gravity.BOTTOM)
            transition.duration = 300
            transition.addTarget(view)
            TransitionManager.beginDelayedTransition(rootContainer, transition)
            rootContainer.removeView(view)
            rulesView = null
        }
    }
    
    fun isShowing(): Boolean = rulesView != null
    
    fun isChatBlocked(): Boolean {
        val slug = currentSlug ?: return false
        val hash = currentRulesHash ?: return false
        val acceptedHash = prefs.getAcceptedRuleHash(slug)
        return hash != acceptedHash
    }

    private fun hashRules(text: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val hash = digest.digest(text.toByteArray(Charsets.UTF_8))
        return hash.joinToString("") { "%02x".format(it) }
    }
}
