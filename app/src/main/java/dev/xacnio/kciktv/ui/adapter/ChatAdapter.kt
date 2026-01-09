package dev.xacnio.kciktv.ui.adapter

import android.graphics.Color
import android.graphics.drawable.Animatable
import android.graphics.drawable.Drawable
import android.text.Spannable
import android.text.SpannableStringBuilder
import android.text.style.DynamicDrawableSpan
import android.text.style.ForegroundColorSpan
import android.text.style.ImageSpan
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.bumptech.glide.load.resource.gif.GifDrawable
import com.bumptech.glide.request.target.CustomTarget
import com.bumptech.glide.request.transition.Transition
import dev.xacnio.kciktv.R
import dev.xacnio.kciktv.data.model.ChatMessage
import java.util.regex.Pattern

/**
 * Optimized Chat adapter using ListAdapter with DiffUtil
 * Supports inline emotes with [emote:id:name] format
 */
class ChatAdapter : ListAdapter<ChatMessage, ChatAdapter.ChatViewHolder>(ChatDiffCallback()) {

    private var themeColor: Int = 0xFF53FC18.toInt()
    private var subscriberBadges: Map<Int, String> = emptyMap() // months -> badge URL
    
    companion object {
        // Pattern to match [emote:id:name] format
        private val EMOTE_PATTERN = Pattern.compile("\\[emote:(\\d+):([^\\]]+)\\]")
        private const val EMOTE_BASE_URL = "https://files.kick.com/emotes/"
    }
    
    fun setSubscriberBadges(badges: Map<Int, String>) {
        this.subscriberBadges = badges
    }

    class ChatViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val messageText: TextView = view.findViewById(R.id.messageText)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ChatViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_chat_message, parent, false)
        return ChatViewHolder(view)
    }

    override fun onBindViewHolder(holder: ChatViewHolder, position: Int) {
        val message = getItem(position)
        val badgeSize = (holder.messageText.textSize * 1.2).toInt()
        
        // Build styled message: [badges] username: message
        val builder = SpannableStringBuilder()
        
        // Add badges - first add placeholders, then load images
        val badgePlaceholders = mutableListOf<Pair<Int, String>>() // position -> badge URL or emoji
        
        message.sender.badges?.forEach { badge ->
            val startPos = builder.length
            when (badge.type) {
                "broadcaster" -> builder.append("🎬 ")
                "moderator" -> builder.append("🛡️ ")
                "vip" -> builder.append("💎 ")
                "og" -> builder.append("👑 ")
                "verified" -> builder.append("✓ ")
                "founder" -> builder.append("🏆 ")
                "subscriber" -> {
                    // Subscriber badge - load image based on count (get months from count or text)
                    val months = badge.count ?: badge.text?.toIntOrNull() ?: 1
                    // Find the most suitable badge (closest equal or smaller)
                    val badgeUrl = subscriberBadges.keys
                        .filter { it <= months }
                        .maxOrNull()
                        ?.let { subscriberBadges[it] }
                    
                    android.util.Log.d("ChatAdapter", "Subscriber badge: months=$months, foundBadgeUrl=${badgeUrl != null}, availableBadges=${subscriberBadges.keys}")
                    
                    if (badgeUrl != null) {
                        builder.append("  ") // Placeholder for image
                        badgePlaceholders.add(startPos to badgeUrl)
                    } else {
                        builder.append("⭐ ")
                    }
                }
                "sub_gifter" -> builder.append("🎁 ")
                else -> {} // Unknown badge, skip
            }
        }
        
        // Add username with color
        val usernameStart = builder.length
        builder.append(message.sender.username)
        val usernameEnd = builder.length
        
        // Apply username color
        val usernameColor = try {
            message.sender.color?.let { Color.parseColor(it) } ?: themeColor
        } catch (e: Exception) {
            themeColor
        }
        builder.setSpan(
            ForegroundColorSpan(usernameColor),
            usernameStart,
            usernameEnd,
            Spannable.SPAN_EXCLUSIVE_EXCLUSIVE
        )
        
        // Add message content - process emotes BEFORE adding to builder
        builder.append(": ")
        val messageStartIndex = builder.length
        
        // Parse emotes and replace with placeholders immediately
        val emoteSize = (holder.messageText.textSize * 1.4).toInt()
        val emotePlaceholders = mutableListOf<Triple<Int, String, String>>() // position, emoteId, emoteName
        
        val processedContent = processEmotesInContent(message.content, messageStartIndex, emotePlaceholders)
        builder.append(processedContent)
        
        // Apply placeholder spans for emotes (transparent placeholders while images load)
        for ((pos, _, _) in emotePlaceholders) {
            // Create a small placeholder drawable (transparent or loading indicator)
            val placeholder = android.graphics.drawable.ColorDrawable(android.graphics.Color.TRANSPARENT)
            placeholder.setBounds(0, 0, emoteSize, emoteSize)
            builder.setSpan(
                CenterImageSpan(placeholder),
                pos,
                pos + 1,
                Spannable.SPAN_EXCLUSIVE_EXCLUSIVE
            )
        }
        
        // Set initial text (emotes are invisible placeholders, not raw text)
        holder.messageText.text = builder
        
        // Load subscriber badge images
        for ((pos, url) in badgePlaceholders) {
            loadBadgeImage(holder.messageText, pos, url, badgeSize)
        }
        
        // Load emote images asynchronously (replacing placeholders)
        for ((pos, emoteId, _) in emotePlaceholders) {
            loadEmoteImage(holder.messageText, pos, emoteId, emoteSize)
        }
    }
    
    private fun loadBadgeImage(textView: TextView, position: Int, url: String, size: Int) {
        try {
            Glide.with(textView.context.applicationContext)
                .asDrawable()
                .load(url)
                .override(size, size)
                .into(object : CustomTarget<Drawable>(size, size) {
                    override fun onResourceReady(resource: Drawable, transition: Transition<in Drawable>?) {
                        resource.setBounds(0, 0, size, size)
                        try {
                            val currentText = textView.text.toString()
                            if (position < currentText.length) {
                                val spannable = SpannableStringBuilder(textView.text)
                                spannable.setSpan(
                                    CenterImageSpan(resource),
                                    position,
                                    position + 1,
                                    Spannable.SPAN_EXCLUSIVE_EXCLUSIVE
                                )
                                textView.text = spannable
                            }
                        } catch (e: Exception) {
                            android.util.Log.e("ChatAdapter", "Badge image error: ${e.message}")
                        }
                    }
                    
                    override fun onLoadCleared(placeholder: Drawable?) {}
                    override fun onLoadFailed(errorDrawable: Drawable?) {
                        android.util.Log.e("ChatAdapter", "Failed to load badge: $url")
                    }
                })
        } catch (e: Exception) {
            android.util.Log.e("ChatAdapter", "Badge Glide error: ${e.message}")
        }
    }
    
    /**
     * Process emotes in content - replaces [emote:id:name] tags with single placeholder characters
     * Returns the processed content string
     */
    private fun processEmotesInContent(
        content: String, 
        basePosition: Int,
        emotePlaceholders: MutableList<Triple<Int, String, String>>
    ): String {
        val matcher = EMOTE_PATTERN.matcher(content)
        val result = StringBuilder()
        var lastEnd = 0
        
        while (matcher.find()) {
            // Add text before this emote
            result.append(content.substring(lastEnd, matcher.start()))
            
            val emoteId = matcher.group(1) ?: continue
            val emoteName = matcher.group(2) ?: continue
            
            // Calculate actual position in final spannable
            val actualPosition = basePosition + result.length
            
            // Add placeholder character for emote
            result.append(" ") // Single space as placeholder
            
            // Record position for later image loading
            emotePlaceholders.add(Triple(actualPosition, emoteId, emoteName))
            
            lastEnd = matcher.end()
        }
        
        // Add remaining text after last emote
        result.append(content.substring(lastEnd))
        
        return result.toString()
    }
    
    /**
     * Load emote image at specific position (supports animated GIF/WebP)
     */
    private fun loadEmoteImage(textView: TextView, position: Int, emoteId: String, size: Int) {
        val emoteUrl = "${EMOTE_BASE_URL}${emoteId}/fullsize"
        
        try {
            // Use asDrawable() to handle both animated WebP and static images
            Glide.with(textView.context.applicationContext)
                .asDrawable()
                .load(emoteUrl)
                .override(size, size)
                .into(object : CustomTarget<Drawable>(size, size) {
                    override fun onResourceReady(resource: Drawable, transition: Transition<in Drawable>?) {
                        resource.setBounds(0, 0, size, size)
                        
                        // Check if drawable is animated (GIF or animated WebP)
                        if (resource is Animatable) {
                            android.util.Log.d("ChatAdapter", "Animated emote detected: $emoteId")
                            
                            // Set up animation callback to invalidate TextView on each frame
                            resource.callback = object : Drawable.Callback {
                                override fun invalidateDrawable(who: Drawable) {
                                    textView.invalidate()
                                }
                                override fun scheduleDrawable(who: Drawable, what: Runnable, `when`: Long) {
                                    textView.postDelayed(what, `when` - android.os.SystemClock.uptimeMillis())
                                }
                                override fun unscheduleDrawable(who: Drawable, what: Runnable) {
                                    textView.removeCallbacks(what)
                                }
                            }
                            
                            // For GifDrawable, set loop count
                            if (resource is GifDrawable) {
                                resource.setLoopCount(GifDrawable.LOOP_FOREVER)
                            }
                            
                            // Start the animation
                            resource.start()
                        }
                        
                        applyEmoteSpan(textView, position, resource)
                    }
                    
                    override fun onLoadCleared(placeholder: Drawable?) {}
                    override fun onLoadFailed(errorDrawable: Drawable?) {
                        android.util.Log.e("ChatAdapter", "Failed to load emote: $emoteUrl")
                    }
                })
        } catch (e: Exception) {
            android.util.Log.e("ChatAdapter", "Emote Glide error: ${e.message}")
        }
    }
    
    /**
     * Apply the emote span to the TextView at the given position
     */
    private fun applyEmoteSpan(textView: TextView, position: Int, drawable: Drawable) {
        try {
            val currentText = textView.text
            if (position < currentText.length) {
                val spannable = SpannableStringBuilder(currentText)
                // Remove any existing spans at this position
                spannable.getSpans(position, position + 1, CenterImageSpan::class.java).forEach {
                    spannable.removeSpan(it)
                }
                spannable.setSpan(
                    CenterImageSpan(drawable),
                    position,
                    position + 1,
                    Spannable.SPAN_EXCLUSIVE_EXCLUSIVE
                )
                textView.text = spannable
            }
        } catch (e: Exception) {
            android.util.Log.e("ChatAdapter", "Emote span error: ${e.message}")
        }
    }

    fun updateThemeColor(color: Int) {
        themeColor = color
        notifyDataSetChanged()
    }

    /**
     * Add new messages efficiently
     */
    fun addMessages(newMessages: List<ChatMessage>) {
        val currentList = currentList.toMutableList()
        currentList.addAll(newMessages)
        
        // Keep only last 100 messages for performance
        val trimmedList = if (currentList.size > 100) {
            currentList.takeLast(100)
        } else {
            currentList
        }
        
        submitList(trimmedList)
    }

    fun clearMessages() {
        submitList(emptyList())
    }

    class ChatDiffCallback : DiffUtil.ItemCallback<ChatMessage>() {
        override fun areItemsTheSame(oldItem: ChatMessage, newItem: ChatMessage): Boolean {
            return oldItem.id == newItem.id
        }

        override fun areContentsTheSame(oldItem: ChatMessage, newItem: ChatMessage): Boolean {
            return oldItem == newItem
        }
    }

    /**
     * Custom ImageSpan to vertically center drawables with text
     */
    class CenterImageSpan(drawable: Drawable) : ImageSpan(drawable) {
        override fun draw(
            canvas: android.graphics.Canvas,
            text: CharSequence?,
            start: Int,
            end: Int,
            x: Float,
            top: Int,
            y: Int,
            bottom: Int,
            paint: android.graphics.Paint
        ) {
            val drawable = drawable
            canvas.save()
            val fm = paint.fontMetricsInt
            val transY = (y + fm.descent + y + fm.ascent) / 2 - drawable.bounds.bottom / 2
            canvas.translate(x, transY.toFloat())
            drawable.draw(canvas)
            canvas.restore()
        }
    }
}
