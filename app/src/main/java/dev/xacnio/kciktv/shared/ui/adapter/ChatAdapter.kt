/**
 * File: ChatAdapter.kt
 *
 * Description: RecyclerView Adapter for displaying Chat lists.
 *
 * Author: Xacnio
 *
 */
package dev.xacnio.kciktv.shared.ui.adapter


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
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.bumptech.glide.load.resource.gif.GifDrawable
import com.bumptech.glide.request.target.CustomTarget
import com.bumptech.glide.request.transition.Transition
import dev.xacnio.kciktv.R
import dev.xacnio.kciktv.shared.data.model.ChatMessage
import java.util.regex.Pattern
import dev.xacnio.kciktv.shared.util.Constants
import dev.xacnio.kciktv.shared.ui.widget.ShimmerLayout
import dev.xacnio.kciktv.shared.ui.utils.ApngBadgeManager
import dev.xacnio.kciktv.shared.ui.utils.EmoteManager
import dev.xacnio.kciktv.mobile.util.DialogUtils
import dev.xacnio.kciktv.shared.util.FormatUtils

data class CachedClipInfo(
    val title: String,
    val thumbnail: String,
    val channelName: String,
    val channelAvatar: String?,
    val views: Int,
    val duration: Int
)

/**
 * Optimized Chat adapter using ListAdapter with DiffUtil
 * Supports inline emotes with [emote:id:name] format
 */
class ChatAdapter(
    private val onReplyClick: (String) -> Unit = {},
    private val onSwipeToReply: (ChatMessage) -> Unit = {},
    private val onEmoteClick: ((String, String) -> Unit)? = null, // (emoteId, emoteName)
    private val onMessageLongClick: ((ChatMessage) -> Unit)? = null, // Long press on message
    private val onMessageClick: ((ChatMessage) -> Unit)? = null, // Click on message
    private val onRestoreClick: (() -> Unit)? = null, // Restore button click
    private val onRestoreDismiss: (() -> Unit)? = null, // Dismiss restore button
    private val onMessageAdded: (() -> Unit)? = null, // Called when new message is added
    private val onEmptySpaceClick: (() -> Unit)? = null, // Click on empty space
    private val onClipUrlFound: ((String) -> Unit)? = null,
    private val onClipClick: ((String) -> Unit)? = null
) : ListAdapter<ChatMessage, RecyclerView.ViewHolder>(ChatDiffCallback()) {

    // Animation variables
    private val pendingAnimationIds = java.util.Collections.synchronizedSet(mutableSetOf<String>())
    private val animatingViews = mutableSetOf<View>() // Views currently running enter animation
    private var animationType: String = "none"

    fun setAnimationType(type: String) {
        this.animationType = type
    }
    
    // Removed resetAnimationDelay as it's no longer needed without staggered batching


    private fun runEnterAnimation(view: View, position: Int) {
        if (animationType == "none") return

        // Track this view as actively animating
        animatingViews.add(view)

        // Safety check to ensure we have valid metrics
        val metrics = view.resources.displayMetrics
        val screenWidth = metrics.widthPixels.toFloat()
        val density = metrics.density
        
        val endAction = Runnable {
            animatingViews.remove(view)
            // Now that animation completed, remove the ID from pending set
            val msgId = view.getTag(R.id.chat_animation_pending) as? String
            if (msgId != null) pendingAnimationIds.remove(msgId)
            view.setTag(R.id.chat_animation_pending, null)
        }
        
        when (animationType) {
            "fade_in" -> {
                view.animate()
                    .alpha(1f)
                    .setDuration(250)
                    .setInterpolator(android.view.animation.DecelerateInterpolator())
                    .withLayer()
                    .withEndAction(endAction)
                    .start()
            }
            "slide_left" -> {
                view.animate()
                    .translationX(0f)
                    .alpha(1f)
                    .setDuration(300)
                    .setInterpolator(android.view.animation.DecelerateInterpolator())
                    .withLayer()
                    .withEndAction(endAction)
                    .start()
            }
            "slide_right" -> {
                view.animate()
                    .translationX(0f)
                    .alpha(1f)
                    .setDuration(300)
                    .setInterpolator(android.view.animation.DecelerateInterpolator())
                    .withLayer()
                    .withEndAction(endAction)
                    .start()
            }
            "slide_bottom" -> {
                view.animate()
                    .translationY(0f)
                    .alpha(1f)
                    .setDuration(300)
                    .setInterpolator(android.view.animation.DecelerateInterpolator())
                    .withLayer()
                    .withEndAction(endAction)
                    .start()
            }
            "scale" -> {
                view.animate()
                    .scaleX(1f)
                    .scaleY(1f)
                    .alpha(1f)
                    .setDuration(300)
                    .setInterpolator(android.view.animation.OvershootInterpolator())
                    .withLayer()
                    .withEndAction(endAction)
                    .start()
            }
            "typewriter" -> {
                view.pivotX = 0f
                view.animate()
                    .scaleX(1f)
                    .alpha(1f)
                    .setDuration(350)
                    .setInterpolator(android.view.animation.DecelerateInterpolator(1.5f))
                    .withLayer()
                    .withEndAction(endAction)
                    .start()
            }
            "curtain" -> {
                view.pivotY = 0f
                view.animate()
                    .scaleY(1f)
                    .alpha(1f)
                    .setDuration(350)
                    .setInterpolator(android.view.animation.DecelerateInterpolator(1.5f))
                    .withLayer()
                    .withEndAction(endAction)
                    .start()
            }
            "flip" -> {
                view.cameraDistance = view.resources.displayMetrics.density * 8000
                view.animate()
                    .rotationX(0f)
                    .alpha(1f)
                    .setDuration(400)
                    .setInterpolator(android.view.animation.DecelerateInterpolator(1.5f))
                    .withLayer()
                    .withEndAction(endAction)
                    .start()
            }
            "rotate" -> {
                view.animate()
                    .rotation(0f)
                    .alpha(1f)
                    .setDuration(350)
                    .setInterpolator(android.view.animation.DecelerateInterpolator(2f))
                    .withLayer()
                    .withEndAction(endAction)
                    .start()
            }
            "zoom_out" -> {
                view.animate()
                    .scaleX(1f)
                    .scaleY(1f)
                    .alpha(1f)
                    .setDuration(300)
                    .setInterpolator(android.view.animation.DecelerateInterpolator(1.5f))
                    .withLayer()
                    .withEndAction(endAction)
                    .start()
            }
            "swing" -> {
                view.cameraDistance = view.resources.displayMetrics.density * 8000
                view.pivotX = 0f
                view.animate()
                    .rotationY(0f)
                    .alpha(1f)
                    .setDuration(400)
                    .setInterpolator(android.view.animation.DecelerateInterpolator(1.5f))
                    .withLayer()
                    .withEndAction(endAction)
                    .start()
            }
        }
    }
    var isLoggedIn: Boolean = false
    var isVodMode: Boolean = false // New flag for VOD
    var isAutoScrollEnabled: Boolean = true // When false, don't trim old messages
    private var themeColor: Int = 0xFF53FC18.toInt()
    private var subscriberBadges: Map<Int, String> = emptyMap() // months -> badge URL
    private var showTimestamps: Boolean = true
    private var showSeconds: Boolean = true
    private var textSize: Float = 14f
    private var emoteScale: Float = 1.8f
    private var lastLinkClickTime: Long = 0
    
    private var currentUsername: String? = null
    private var highlightOwn: Boolean = true
    private var highlightMentions: Boolean = true
    private var highlightMods: Boolean = true
    private var highlightVips: Boolean = true
    private var useNameColorForHighlight: Boolean = false
    
    // Internal cache to maintain state reliability
    private val messageCache = java.util.Collections.synchronizedList(java.util.ArrayList<ChatMessage>())
    
    private val clipCache = java.util.Collections.synchronizedMap(mutableMapOf<String, CachedClipInfo>())
    private val activeClipFetches = java.util.Collections.synchronizedSet(mutableSetOf<String>())
    
    companion object {
        fun showLinkConfirmationDialog(context: android.content.Context, url: String) {
            dev.xacnio.kciktv.mobile.util.DialogUtils.showLinkConfirmationDialog(context, url)
        }

        private const val VIEW_TYPE_MESSAGE = 0
        private const val VIEW_TYPE_SYSTEM = 1
        private const val VIEW_TYPE_INFO = 2
        private const val VIEW_TYPE_RESTORE_BUTTON = 3
        private const val VIEW_TYPE_REWARD = 4
        private const val VIEW_TYPE_CELEBRATION = 5
        private const val VIEW_TYPE_GIFT = 6
        
        // Pattern to match [emote:id:name] format
        private val EMOTE_PATTERN = java.util.regex.Pattern.compile("\\[emote:([^:]+):([^\\]]+)\\]")
        private val CLIP_URL_PATTERN = java.util.regex.Pattern.compile("kick\\.com/[^/]+/clips/(clip_[a-zA-Z0-9_]+)")
        // Pattern for HTTPS links - captures everything non-whitespace to handle trailing slashes correctly
        internal val URL_PATTERN = java.util.regex.Pattern.compile("https?://\\S+")
    }

    override fun getItemViewType(position: Int): Int {
        return when(getItem(position).type) {
            dev.xacnio.kciktv.shared.data.model.MessageType.SYSTEM -> VIEW_TYPE_SYSTEM
            dev.xacnio.kciktv.shared.data.model.MessageType.INFO -> VIEW_TYPE_INFO
            dev.xacnio.kciktv.shared.data.model.MessageType.RESTORE_BUTTON -> VIEW_TYPE_RESTORE_BUTTON
            dev.xacnio.kciktv.shared.data.model.MessageType.REWARD -> VIEW_TYPE_REWARD
            dev.xacnio.kciktv.shared.data.model.MessageType.CELEBRATION -> VIEW_TYPE_CELEBRATION
            dev.xacnio.kciktv.shared.data.model.MessageType.GIFT -> VIEW_TYPE_GIFT
            else -> VIEW_TYPE_MESSAGE
        }
    }

    private var highlightedMessageId: String? = null

    fun setSubscriberBadges(badges: Map<Int, String>) {
        this.subscriberBadges = badges
    }

    fun highlightMessage(id: String?) {
        val oldId = highlightedMessageId
        highlightedMessageId = id
        
        oldId?.let { oid ->
            val pos = currentList.indexOfFirst { it.id == oid }
            if (pos != -1) notifyItemChanged(pos)
        }
        id?.let { nid ->
            val pos = currentList.indexOfFirst { it.id == nid }
            if (pos != -1) notifyItemChanged(pos)
        }
    }

    fun setShowTimestamps(show: Boolean) {
        val old = this.showTimestamps
        this.showTimestamps = show
        if (old != show) {
            notifyDataSetChanged()
        }
    }

    fun setShowSeconds(show: Boolean) {
        val old = this.showSeconds
        this.showSeconds = show
        if (old != show && showTimestamps) {
            notifyDataSetChanged()
        }
    }

    fun setChatTextSize(sizeSp: Float) {
        val old = this.textSize
        this.textSize = sizeSp
        if (old != sizeSp) {
            notifyDataSetChanged()
        }
    }

    fun setChatEmoteSize(scale: Float) {
        val old = this.emoteScale
        this.emoteScale = scale
        if (old != scale) {
            notifyDataSetChanged()
        }
    }

    fun setCurrentUser(username: String?) {
        this.currentUsername = username
        notifyDataSetChanged()
    }

    fun setHighlightSettings(own: Boolean, mentions: Boolean, mods: Boolean, vips: Boolean, useNameColor: Boolean) {
        this.highlightOwn = own
        this.highlightMentions = mentions
        this.highlightMods = mods
        this.highlightVips = vips
        this.useNameColorForHighlight = useNameColor
        notifyDataSetChanged()
    }

    class ChatViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val messageText: TextView = view.findViewById(R.id.messageText)
        val clipPreviewContainer: android.widget.FrameLayout = view.findViewById(R.id.clipPreviewContainer)
        val clipShimmerLayout: dev.xacnio.kciktv.shared.ui.widget.ShimmerLayout = view.findViewById(R.id.clipShimmerLayout)
        val clipContentLayout: androidx.constraintlayout.widget.ConstraintLayout = view.findViewById(R.id.clipContentLayout)
        val clipThumbnail: ImageView = view.findViewById(R.id.clipThumbnail)
        val clipTitle: TextView = view.findViewById(R.id.clipTitle)
        val clipChannel: TextView = view.findViewById(R.id.clipChannel)
        val clipViews: TextView = view.findViewById(R.id.clipViews)
        val clipDuration: TextView = view.findViewById(R.id.clipDuration)
        val targets = mutableListOf<com.bumptech.glide.request.target.Target<*>>()
        
        // Debounced setText to batch layout updates
        private var pendingLayoutUpdate: Runnable? = null
        private val layoutHandler = android.os.Handler(android.os.Looper.getMainLooper())
        
        fun scheduleLayoutUpdate() {
            pendingLayoutUpdate?.let { layoutHandler.removeCallbacks(it) }
            pendingLayoutUpdate = Runnable {
                val currentText = messageText.text
                if (currentText is android.text.Spannable) {
                    // Create a completely new SpannableStringBuilder with NEW span objects
                    // to avoid any caching issues with span references
                    val fresh = android.text.SpannableStringBuilder()
                    fresh.append(currentText.toString())
                    
                    // Copy all spans BUT create new CenterImageSpan instances
                    val allSpans = currentText.getSpans(0, currentText.length, Any::class.java)
                    for (span in allSpans) {
                        val start = currentText.getSpanStart(span)
                        val end = currentText.getSpanEnd(span)
                        val flags = currentText.getSpanFlags(span)
                        
                        // Create new span object for CenterImageSpan to avoid caching
                        // Preserve the rightMargin when copying
                        val newSpan = if (span is CenterImageSpan) {
                            CenterImageSpan(span.drawable, span.rightMargin)
                        } else {
                            span
                        }
                        fresh.setSpan(newSpan, start, end, flags)
                    }
                    
                    // Reset text completely
                    messageText.text = ""
                    messageText.setText(fresh, TextView.BufferType.SPANNABLE)
                }
            }
            // Delay slightly to allow batch span applications
            layoutHandler.postDelayed(pendingLayoutUpdate!!, 50)
        }
        
        fun cancelPendingLayout() {
            pendingLayoutUpdate?.let { layoutHandler.removeCallbacks(it) }
            pendingLayoutUpdate = null
        }

        fun clear() {
            cancelPendingLayout()
            if (targets.isEmpty()) return
            val toClear = ArrayList(targets)
            targets.clear()
            toClear.forEach { Glide.with(itemView.context.applicationContext).clear(it) }
        }
    }

    class SystemViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val systemMessageText: TextView = view.findViewById(R.id.systemMessageText)
        val systemMessageIcon: ImageView = view.findViewById(R.id.systemMessageIcon)
    }

    class InfoViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val infoMessageText: TextView = view.findViewById(R.id.infoMessageText)
    }

    class RestoreButtonViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val restoreButton: android.widget.Button = view.findViewById(R.id.restoreButton)
        val dismissButton: android.widget.ImageButton = view.findViewById(R.id.dismissButton)
    }

    class RewardViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val rewardHeader: TextView = view.findViewById(R.id.rewardHeader)
        val rewardTitle: TextView = view.findViewById(R.id.rewardTitle)
        val rewardInput: TextView = view.findViewById(R.id.rewardInput)
        val rewardCost: TextView = view.findViewById(R.id.rewardCost)
        val rewardIcon: ImageView = view.findViewById(R.id.rewardIcon)
        val accentStripe: View = view.findViewById(R.id.accentStripe)
        val cardContainer: androidx.cardview.widget.CardView = view.findViewById(R.id.cardContainer)
    }

    class CelebrationViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val accentStripe: View = view.findViewById(R.id.accentStripe)
        val celebrationIcon: ImageView = view.findViewById(R.id.celebrationIcon)
        val celebrationUser: TextView = view.findViewById(R.id.celebrationUser)
        val celebrationTitle: TextView = view.findViewById(R.id.celebrationTitle)
        val celebrationSubTitle: TextView = view.findViewById(R.id.celebrationSubTitle)
        val messageText: TextView = view.findViewById(R.id.messageText)
        val targets = mutableListOf<com.bumptech.glide.request.target.Target<*>>()

        private var pendingLayoutUpdate: Runnable? = null
        private val layoutHandler = android.os.Handler(android.os.Looper.getMainLooper())

        fun scheduleLayoutUpdate() {
            pendingLayoutUpdate?.let { layoutHandler.removeCallbacks(it) }
            pendingLayoutUpdate = Runnable {
                val currentText = messageText.text
                if (currentText is android.text.Spannable) {
                    val fresh = android.text.SpannableStringBuilder()
                    fresh.append(currentText.toString())
                    val allSpans = currentText.getSpans(0, currentText.length, Any::class.java)
                    for (span in allSpans) {
                        val start = currentText.getSpanStart(span)
                        val end = currentText.getSpanEnd(span)
                        val flags = currentText.getSpanFlags(span)
                        val newSpan = if (span is CenterImageSpan) {
                            CenterImageSpan(span.drawable, span.rightMargin)
                        } else {
                            span
                        }
                        fresh.setSpan(newSpan, start, end, flags)
                    }
                    messageText.text = ""
                    messageText.setText(fresh, TextView.BufferType.SPANNABLE)
                }
            }
            layoutHandler.postDelayed(pendingLayoutUpdate!!, 50)
        }

        fun cancelPendingLayout() {
            pendingLayoutUpdate?.let { layoutHandler.removeCallbacks(it) }
            pendingLayoutUpdate = null
        }

        fun clear() {
            cancelPendingLayout()
            if (targets.isEmpty()) return
            val toClear = ArrayList(targets)
            targets.clear()
            toClear.forEach { Glide.with(itemView.context.applicationContext).clear(it) }
        }
    }

    class GiftViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val giftHeaderText: TextView = view.findViewById(R.id.giftHeaderText)
        val giftMessageText: TextView = view.findViewById(R.id.giftMessageText)
        val amountText: TextView = view.findViewById(R.id.amountText)
        val amountLayout: View = view.findViewById(R.id.amountLayout)
        val giftImage: ImageView = view.findViewById(R.id.giftImage)
        val gradientBackground: View = view.findViewById(R.id.gradientBackground)
        val giftContentLayout: androidx.constraintlayout.widget.ConstraintLayout = view.findViewById(R.id.giftContentLayout)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val inflater = LayoutInflater.from(parent.context)
        return when (viewType) {
            VIEW_TYPE_SYSTEM -> {
                SystemViewHolder(inflater.inflate(R.layout.item_chat_system, parent, false))
            }
            VIEW_TYPE_INFO -> {
                InfoViewHolder(inflater.inflate(R.layout.item_chat_info, parent, false))
            }
            VIEW_TYPE_RESTORE_BUTTON -> {
                RestoreButtonViewHolder(inflater.inflate(R.layout.item_chat_restore_button, parent, false))
            }
            VIEW_TYPE_REWARD -> {
                RewardViewHolder(inflater.inflate(R.layout.item_chat_reward, parent, false))
            }
            VIEW_TYPE_CELEBRATION -> {
                CelebrationViewHolder(inflater.inflate(R.layout.item_chat_celebration, parent, false))
            }
            VIEW_TYPE_GIFT -> {
                GiftViewHolder(inflater.inflate(R.layout.item_chat_gift, parent, false))
            }
            else -> {
                ChatViewHolder(inflater.inflate(R.layout.item_chat_message, parent, false))
            }
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        val message = getItem(position)
        
        // Check if this message needs enter animation
        // Use contains (not remove) — ID is only cleared when animation completes or view is recycled
        val needsAnimation = pendingAnimationIds.contains(message.id)
        if (needsAnimation && animationType != "none") {
            // Set initial hidden state — animation will fire in onViewAttachedToWindow
            holder.itemView.animate().cancel()
            holder.itemView.alpha = 0f
            holder.itemView.translationX = 0f
            holder.itemView.translationY = 0f
            holder.itemView.scaleX = 1f
            holder.itemView.scaleY = 1f
            val metrics = holder.itemView.resources.displayMetrics
            val screenWidth = metrics.widthPixels.toFloat()
            val density = metrics.density
            
            when (animationType) {
                "slide_left" -> holder.itemView.translationX = -screenWidth * 0.5f
                "slide_right" -> holder.itemView.translationX = screenWidth * 0.5f
                "slide_bottom" -> holder.itemView.translationY = 50f * density
                "scale" -> {
                    holder.itemView.scaleX = 0.5f
                    holder.itemView.scaleY = 0.5f
                }
                "typewriter" -> {
                    holder.itemView.pivotX = 0f
                    holder.itemView.scaleX = 0f
                }
                "curtain" -> {
                    holder.itemView.pivotY = 0f
                    holder.itemView.scaleY = 0f
                }
                "flip" -> {
                    holder.itemView.cameraDistance = density * 8000
                    holder.itemView.rotationX = 90f
                }
                "rotate" -> {
                    holder.itemView.pivotX = 0f
                    holder.itemView.pivotY = holder.itemView.height.toFloat().coerceAtLeast(40f * density)
                    holder.itemView.rotation = -90f
                }
                "zoom_out" -> {
                    holder.itemView.scaleX = 2f
                    holder.itemView.scaleY = 2f
                }
                "swing" -> {
                    holder.itemView.cameraDistance = density * 8000
                    holder.itemView.pivotX = 0f
                    holder.itemView.rotationY = -90f
                }
            }
            // Store message ID as tag for rebind protection
            holder.itemView.setTag(R.id.chat_animation_pending, message.id)
            // Start animation immediately (needed for scrap-reused views that won't trigger onViewAttachedToWindow)
            runEnterAnimation(holder.itemView, position)
        } else if (holder.itemView.getTag(R.id.chat_animation_pending) == message.id) {
            // Same message rebound while animation is pending/running — don't touch
        } else {
            // Different message or no pending animation — full reset
            holder.itemView.setTag(R.id.chat_animation_pending, null)
            animatingViews.remove(holder.itemView)
            holder.itemView.animate().cancel()
            holder.itemView.alpha = 1f
            holder.itemView.translationX = 0f
            holder.itemView.translationY = 0f
            holder.itemView.scaleX = 1f
            holder.itemView.scaleY = 1f
            holder.itemView.rotationX = 0f
            holder.itemView.rotationY = 0f
            holder.itemView.rotation = 0f
        }

        when (holder) {
            is ChatViewHolder -> bindChatMessage(holder, message)
            is SystemViewHolder -> bindSystemMessage(holder, message)
            is InfoViewHolder -> bindInfoMessage(holder, message)
            is RestoreButtonViewHolder -> bindRestoreButton(holder, message)
            is RewardViewHolder -> bindRewardMessage(holder, message)
            is CelebrationViewHolder -> bindCelebrationMessage(holder, message)
            is GiftViewHolder -> bindGiftMessage(holder, message)
        }
    }



    private fun bindGiftMessage(holder: GiftViewHolder, message: ChatMessage) {
        val data = message.giftData ?: return
        
        // Parse user color
        val usernameColor = try {
            message.sender.color?.let { Color.parseColor(it) } ?: themeColor
        } catch (e: Exception) {
            themeColor
        }
        
        val amount = data.gift?.amount ?: 0
        val isSmallGift = amount < 500
        
        // Background (Gradient for 500+, Border for <500)
        holder.gradientBackground.visibility = View.VISIBLE
        if (isSmallGift) {
            holder.gradientBackground.setBackgroundResource(R.drawable.bg_gift_input)
        } else {
            val gradient = android.graphics.drawable.GradientDrawable(
                android.graphics.drawable.GradientDrawable.Orientation.LEFT_RIGHT,
                intArrayOf(
                    Color.TRANSPARENT,
                    Color.argb(40, Color.red(usernameColor), Color.green(usernameColor), Color.blue(usernameColor)),
                    Color.argb(100, Color.red(usernameColor), Color.green(usernameColor), Color.blue(usernameColor))
                )
            )
            holder.gradientBackground.background = gradient
        }

        // Adjust Layout based on gift size
        val density = holder.itemView.resources.displayMetrics.density
        val paddingHorizontal = ((if (isSmallGift) 12 else 10) * density).toInt()
        val paddingVertical = ((if (isSmallGift) 6 else 10) * density).toInt()
        holder.giftContentLayout.setPadding(paddingHorizontal, paddingVertical, paddingHorizontal, paddingVertical)
        
        val imageSize = ((if (isSmallGift) 40 else 56) * density).toInt()
        holder.giftImage.layoutParams.width = imageSize
        holder.giftImage.layoutParams.height = imageSize
        holder.giftImage.requestLayout()
        
        val amountMarginTop = ((if (isSmallGift) 4 else 8) * density).toInt()
        (holder.amountLayout.layoutParams as? ViewGroup.MarginLayoutParams)?.topMargin = amountMarginTop

        // Header: User Color + White Text
        val builder = SpannableStringBuilder()
        builder.append(message.sender.username)
        val usernameEnd = builder.length
        
        builder.setSpan(ForegroundColorSpan(usernameColor), 0, usernameEnd, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
        builder.setSpan(android.text.style.StyleSpan(android.graphics.Typeface.BOLD), 0, usernameEnd, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
        
        val giftName = data.gift?.name ?: holder.itemView.context.getString(R.string.gift)
        val suffix = " " + holder.itemView.context.getString(R.string.gift_sent_label, giftName)
        
        builder.append(suffix)
        builder.setSpan(ForegroundColorSpan(Color.WHITE), usernameEnd, builder.length, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
        builder.setSpan(android.text.style.StyleSpan(android.graphics.Typeface.BOLD), usernameEnd, builder.length, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
        
        holder.giftHeaderText.text = builder
        holder.giftHeaderText.textSize = textSize
        
        // Message Content
        if (isSmallGift) {
            holder.giftMessageText.visibility = View.GONE
        } else {
            holder.giftMessageText.text = message.content
            holder.giftMessageText.textSize = textSize
            holder.giftMessageText.visibility = if (message.content.isNullOrEmpty()) View.GONE else View.VISIBLE
        }
        
        // Amount (formatted with thousands separator)
        holder.amountText.text = java.text.NumberFormat.getIntegerInstance().format(amount).replace(",", ".")
        holder.amountText.textSize = textSize * 0.9f
        
        // Gift Image
        val giftId = data.gift?.giftId
        if (!giftId.isNullOrEmpty()) {
            val formattedGiftId = giftId.replace("_", "-")
            val url = "https://files.kick.com/kicks/gifts/$formattedGiftId.webp"
            Glide.with(holder.itemView.context)
                .load(url)
                .into(holder.giftImage)
            holder.giftImage.visibility = View.VISIBLE
        } else {
            holder.giftImage.visibility = View.GONE
        }
    }

    private fun bindRewardMessage(holder: RewardViewHolder, message: ChatMessage) {
        val data = message.rewardData ?: return
        
        val username = data.username ?: "User"
        val builder = SpannableStringBuilder()
        
        builder.append(username)
        val usernameEnd = builder.length
        
        val clickableSpan = object : android.text.style.ClickableSpan() {
            override fun onClick(widget: View) {
                onMessageClick?.invoke(message)
            }
            override fun updateDrawState(ds: android.text.TextPaint) {
                ds.isUnderlineText = false
                ds.isFakeBoldText = true
            }
        }
        builder.setSpan(clickableSpan, 0, usernameEnd, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
        builder.setSpan(android.text.style.StyleSpan(android.graphics.Typeface.BOLD), 0, usernameEnd, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
        
        builder.append(" " + holder.itemView.context.getString(R.string.chat_reward_used))
        
        holder.rewardHeader.text = builder
        holder.rewardHeader.textSize = textSize
        holder.rewardHeader.movementMethod = android.text.method.LinkMovementMethod.getInstance()

        holder.rewardTitle.text = data.rewardTitle ?: ""
        holder.rewardTitle.textSize = textSize
        
        if (!data.userInput.isNullOrBlank()) {
            val input = data.userInput
            val spannable = android.text.SpannableString(input)
            
            // Regex for https links
            val matcher = URL_PATTERN.matcher(input)
            while (matcher.find()) {
                val fullUrl = matcher.group()
                var start = matcher.start()
                var end = matcher.end()
                
                // Trim trailing punctuation (.,!?) but KEEP trailing slash
                while (end > start) {
                    val c = input[end - 1]
                    if (c == '.' || c == ',' || c == '!' || c == '?' || c == ')' || c == ']' || c == ';') {
                        end--
                    } else {
                        break
                    }
                }
                
                val url = input.substring(start, end)
                if (url.startsWith("https://")) {
                    val linkSpan = object : android.text.style.ClickableSpan() {
                        override fun onClick(widget: View) {
                            lastLinkClickTime = System.currentTimeMillis()
                            showLinkConfirmationDialog(widget.context, url)
                        }
                        override fun updateDrawState(ds: android.text.TextPaint) {
                            super.updateDrawState(ds)
                            ds.isUnderlineText = false
                            ds.color = 0xFF53FC18.toInt()
                        }
                    }
                    spannable.setSpan(linkSpan, start, end, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
                }
            }
            
            holder.rewardInput.text = spannable
            holder.rewardInput.textSize = textSize
            holder.rewardInput.movementMethod = android.text.method.LinkMovementMethod.getInstance()
            holder.rewardInput.visibility = View.VISIBLE
        } else {
            holder.rewardInput.visibility = View.GONE
        }
        
        val cost = data.rewardCost ?: 0
        if (cost > 0) {
            holder.rewardCost.text = "-${cost}"
            holder.rewardCost.textSize = textSize * 0.85f
            holder.rewardCost.visibility = View.VISIBLE
            (holder.rewardCost.parent as? View)?.visibility = View.VISIBLE
        } else {
            (holder.rewardCost.parent as? View)?.visibility = View.GONE
        }
        
        val colorHex = data.rewardBackgroundColor ?: "#53FC18"
        try {
            val color = Color.parseColor(colorHex)
            holder.accentStripe.setBackgroundColor(color)
            holder.rewardIcon.setColorFilter(color)
        } catch (e: Exception) {
            holder.accentStripe.setBackgroundColor(0xFF53FC18.toInt())
        }
    }

    private fun bindCelebrationMessage(holder: CelebrationViewHolder, message: ChatMessage) {
        val data = message.celebrationData ?: return
        val context = holder.itemView.context
        
        holder.celebrationUser.text = message.sender.username
        
        // Identity color
        val identityColor = try {
            message.sender.color?.let { Color.parseColor(it) } ?: 0xFFDEB2FF.toInt()
        } catch (e: Exception) {
            0xFFDEB2FF.toInt()
        }
        
        holder.celebrationUser.setTextColor(identityColor)
        holder.celebrationIcon.setColorFilter(identityColor)
        holder.accentStripe.setBackgroundColor(identityColor)

        // Title and Subtitle based on type
        if (data.type == "subscription_renewed") {
            holder.celebrationTitle.text = context.getString(R.string.chat_celebration_sub_renew_title)
            holder.celebrationSubTitle.text = context.getString(R.string.chat_celebration_sub_renew_duration, data.totalMonths ?: 1)
            holder.celebrationSubTitle.visibility = View.VISIBLE
        } else {
            // Generic celebration
            holder.celebrationTitle.text = context.getString(R.string.chat_celebration_generic_title)
            holder.celebrationSubTitle.visibility = View.GONE
        }

        var lastTouchX = 0f
        var lastTouchY = 0f
        holder.messageText.setOnTouchListener { _, event ->
            if (event.action == android.view.MotionEvent.ACTION_DOWN) {
                lastTouchX = event.x
                lastTouchY = event.y
            }
            false
        }

        val clickListener = View.OnClickListener {
            if (System.currentTimeMillis() - dev.xacnio.kciktv.mobile.util.DialogUtils.lastLinkClickTime > 300) {
                onMessageClick?.invoke(message)
            }
        }
        val longClickListener = View.OnLongClickListener { view ->
            var urlFound: String? = null
            if (view is TextView && view.text is Spannable) {
                 val x = lastTouchX.toInt() - view.totalPaddingLeft + view.scrollX
                 val y = lastTouchY.toInt() - view.totalPaddingTop + view.scrollY
                 val layout = view.layout
                 if (layout != null) {
                     val line = layout.getLineForVertical(y)
                     val offset = layout.getOffsetForHorizontal(line, x.toFloat())
                     val spans = (view.text as Spannable).getSpans(offset, offset, dev.xacnio.kciktv.mobile.util.DialogUtils.UrlClickableSpan::class.java)
                     if (spans.isNotEmpty()) {
                         urlFound = spans[0].url
                     }
                 }
            }
            
            if (urlFound != null) {
                 view.performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS)
                 dev.xacnio.kciktv.mobile.util.DialogUtils.showLinkOptionsBottomSheet(view.context, urlFound)
                 dev.xacnio.kciktv.mobile.util.DialogUtils.lastLinkLongPressTime = System.currentTimeMillis()
                 // Cancel touch to prevent LinkMovementMethod from firing onClick
                 val cancelEvent = android.view.MotionEvent.obtain(
                     android.os.SystemClock.uptimeMillis(),
                     android.os.SystemClock.uptimeMillis(),
                     android.view.MotionEvent.ACTION_CANCEL,
                     0f, 0f, 0
                 )
                 view.dispatchTouchEvent(cancelEvent)
                 cancelEvent.recycle()
            } else {
                 onMessageLongClick?.invoke(message)
            }
            true
        }

        holder.itemView.setOnClickListener(clickListener)
        holder.itemView.setOnLongClickListener(longClickListener)
        // Ensure detection works when clicking directly on the text
        holder.messageText.setOnClickListener(clickListener)
        holder.messageText.setOnLongClickListener(longClickListener)

        // Now bind the message text part reusing logic from bindChatMessage
        // We will pass CelebrationViewHolder as if it was a ChatViewHolder (they have same messageText/targets structure)
        // I need a wrapper or just use holder.messageText manually
        
        // I'll manually implement a cut-down version of bindChatMessage for the internal text
        bindMessageTextOnly(holder.messageText, holder::scheduleLayoutUpdate, message)
    }

    private fun bindMessageTextOnly(
        messageText: TextView, 
        scheduleLayoutUpdate: () -> Unit,
        message: ChatMessage
    ) {
        messageText.text = null
        messageText.textSize = textSize
        
        val context = messageText.context
        val badgeSize = (messageText.textSize * 1.2).toInt()
        val builder = SpannableStringBuilder()
        
        // In celebration, we don't show reply header or timestamps usually to keep it clean inside the card
        // But let's follow standard look for the user: badges + name + content
        
        val badgePlaceholders = mutableListOf<Pair<Int, String>>()
        
        message.sender.badges?.forEach { badge ->
            val startPos = builder.length
            when (badge.type) {
                "broadcaster" -> {
                    val d = androidx.core.content.ContextCompat.getDrawable(context, R.drawable.ic_badge_broadcaster)?.mutate()
                    if (d != null) {
                        d.setBounds(0, 0, badgeSize, badgeSize)
                        builder.append("  ")
                        builder.setSpan(CenterImageSpan(d), startPos, startPos + 1, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
                    } else builder.append("🎬 ")
                }
                "moderator" -> {
                    val d = androidx.core.content.ContextCompat.getDrawable(context, R.drawable.ic_badge_moderator)?.mutate()
                    if (d != null) {
                        d.setBounds(0, 0, badgeSize, badgeSize)
                        builder.append("  ")
                        builder.setSpan(CenterImageSpan(d), startPos, startPos + 1, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
                    } else builder.append("🛡️ ")
                }
                "vip" -> {
                    val d = androidx.core.content.ContextCompat.getDrawable(context, R.drawable.ic_badge_vip)?.mutate()
                    if (d != null) {
                        d.setBounds(0, 0, badgeSize, badgeSize)
                        builder.append("  ")
                        builder.setSpan(CenterImageSpan(d), startPos, startPos + 1, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
                    } else builder.append("💎 ")
                }
                "og" -> {
                    val d = androidx.core.content.ContextCompat.getDrawable(context, R.drawable.ic_badge_og)?.mutate()
                    if (d != null) {
                        d.setBounds(0, 0, badgeSize, badgeSize)
                        builder.append("  ")
                        builder.setSpan(CenterImageSpan(d), startPos, startPos + 1, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
                    } else builder.append("👑 ")
                }
                "verified" -> {
                    val d = androidx.core.content.ContextCompat.getDrawable(context, R.drawable.ic_badge_verified)?.mutate()
                    if (d != null) {
                        d.setBounds(0, 0, badgeSize, badgeSize)
                        builder.append("  ")
                        builder.setSpan(CenterImageSpan(d), startPos, startPos + 1, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
                    } else builder.append("✓ ")
                }
                "founder" -> {
                    val d = androidx.core.content.ContextCompat.getDrawable(context, R.drawable.ic_badge_founder)?.mutate()
                    if (d != null) {
                        d.setBounds(0, 0, badgeSize, badgeSize)
                        builder.append("  ")
                        builder.setSpan(CenterImageSpan(d), startPos, startPos + 1, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
                    } else builder.append("🏆 ")
                }
                "staff" -> {
                    val d = androidx.core.content.ContextCompat.getDrawable(context, R.drawable.ic_badge_staff)?.mutate()
                    if (d != null) {
                        d.setBounds(0, 0, badgeSize, badgeSize)
                        builder.append("  ")
                        builder.setSpan(CenterImageSpan(d), startPos, startPos + 1, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
                    } else builder.append("🔧 ")
                }
                "subscriber" -> {
                    val months = badge.count ?: badge.text?.toIntOrNull() ?: 1
                    val badgeUrl = subscriberBadges.keys.filter { it <= months }.maxOrNull()?.let { subscriberBadges[it] }
                    if (badgeUrl != null) {
                        builder.append("  ")
                        badgePlaceholders.add(startPos to badgeUrl)
                    } else {
                        val d = androidx.core.content.ContextCompat.getDrawable(context, R.drawable.ic_badge_subscriber_default)?.mutate()
                        if (d != null) {
                            d.setBounds(0, 0, badgeSize, badgeSize)
                            builder.append("  ")
                            builder.setSpan(CenterImageSpan(d), startPos, startPos + 1, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
                        } else builder.append("⭐ ")
                    }
                }
                "sub_gifter" -> {
                    val d = androidx.core.content.ContextCompat.getDrawable(context, R.drawable.ic_badge_sub_gifter)?.mutate()
                    if (d != null) {
                        d.setBounds(0, 0, badgeSize, badgeSize)
                        builder.append("  ")
                        builder.setSpan(CenterImageSpan(d), startPos, startPos + 1, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
                    } else builder.append("🎁 ")
                }
                "sidekick" -> {
                    val d = androidx.core.content.ContextCompat.getDrawable(context, R.drawable.ic_badge_sidekick)?.mutate()
                    if (d != null) {
                        d.setBounds(0, 0, badgeSize, badgeSize)
                        builder.append("  ")
                        builder.setSpan(CenterImageSpan(d), startPos, startPos + 1, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
                    } else builder.append("🤝 ")
                }
                "bot" -> {
                    val d = androidx.core.content.ContextCompat.getDrawable(context, R.drawable.ic_badge_bot)?.mutate()
                    if (d != null) {
                        d.setBounds(0, 0, badgeSize, badgeSize)
                        builder.append("  ")
                        builder.setSpan(CenterImageSpan(d), startPos, startPos + 1, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
                    } else builder.append("🤖 ")
                }
                // Add more badges as needed...
            }
        }
        
        val usernameStart = builder.length
        builder.append(message.sender.username)
        val usernameEnd = builder.length
        
        val usernameColor = try {
            message.sender.color?.let { Color.parseColor(it) } ?: themeColor
        } catch (e: Exception) {
            themeColor
        }
        builder.setSpan(ForegroundColorSpan(usernameColor), usernameStart, usernameEnd, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
        builder.setSpan(android.text.style.StyleSpan(android.graphics.Typeface.BOLD), usernameStart, usernameEnd, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
        
        builder.append(": ")
        val messageStartIndex = builder.length
        
        val emoteSize = (messageText.textSize * emoteScale).toInt()
        val emotePlaceholders = mutableListOf<EmotePlaceholder>()
        val processedContent = processEmotesInContent(message.content, messageStartIndex, emotePlaceholders)
        builder.append(processedContent)
        
        applyEmoteAnnotations(builder, emotePlaceholders, emoteSize)
        
        // Detect hyperlinks and make them clickable (without underline)
        val matcher = URL_PATTERN.matcher(builder)
        while (matcher.find()) {
            val fullUrl = matcher.group()
            var start = matcher.start()
            var end = matcher.end()
            
            // Trim trailing punctuation (.,!?) but KEEP trailing slash
            while (end > start) {
                val c = builder[end - 1]
                if (c == '.' || c == ',' || c == '!' || c == '?' || c == ')' || c == ']' || c == ';') {
                    end--
                } else {
                    break
                }
            }
            
            val url = builder.substring(start, end)
            if (url.startsWith("https://")) {
                val clickableSpan = dev.xacnio.kciktv.mobile.util.DialogUtils.UrlClickableSpan(messageText.context, url)
                builder.setSpan(clickableSpan, start, end, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
            }
        }

        messageText.setText(builder, TextView.BufferType.SPANNABLE)

        // Load badges
        for ((pos, url) in badgePlaceholders) {
            loadBadgeImageInternal(messageText, scheduleLayoutUpdate, pos, url, badgeSize)
        }
        
        // Load emotes
        for (p in emotePlaceholders) {
            loadEmoteImageInternal(messageText, scheduleLayoutUpdate, p.id, p.name, emoteSize)
        }
    }

    private fun loadBadgeImageInternal(textView: TextView, scheduleLayoutUpdate: () -> Unit, position: Int, url: String, size: Int) {
        dev.xacnio.kciktv.shared.ui.utils.ApngBadgeManager.loadBadge(url, size, textView) { drawable ->
            if (drawable != null) {
                try {
                    val currentText = textView.text
                    if (currentText is Spannable && position < currentText.length) {
                        currentText.getSpans(position, position + 1, CenterImageSpan::class.java).forEach { currentText.removeSpan(it) }
                        currentText.setSpan(CenterImageSpan(drawable), position, position + 1, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
                        scheduleLayoutUpdate()
                    }
                } catch (e: Exception) {}
            }
        }
    }

    private fun loadEmoteImageInternal(textView: TextView, scheduleLayoutUpdate: () -> Unit, emoteId: String, emoteName: String, size: Int) {
        val tag = "[emote:$emoteId:$emoteName]"
        dev.xacnio.kciktv.shared.ui.utils.EmoteManager.loadSynchronizedEmote(textView.context, emoteId, size, textView) { sharedDrawable ->
            val currentSpannable = textView.text as? Spannable
            if (currentSpannable != null) {
                applyEmoteSpanToTagInternal(textView, scheduleLayoutUpdate, currentSpannable, tag, sharedDrawable, emoteId, emoteName)
            }
        }
    }

    private fun applyEmoteSpanToTagInternal(textView: TextView, scheduleLayoutUpdate: () -> Unit, spannable: Spannable, tag: String, drawable: Drawable, emoteId: String, emoteName: String) {
        try {
            val annotations = spannable.getSpans(0, spannable.length, android.text.Annotation::class.java)
            val target = annotations.find { ann ->
                if (ann.key != "emote_tag" || ann.value != tag) return@find false
                val start = spannable.getSpanStart(ann)
                val end = spannable.getSpanEnd(ann)
                if (start == -1) return@find false
                val existing = spannable.getSpans(start, end, CenterImageSpan::class.java)
                existing.isEmpty() || existing.any { it.drawable is android.graphics.drawable.ColorDrawable }
            }
            if (target != null) {
                val start = spannable.getSpanStart(target)
                val end = spannable.getSpanEnd(target)
                if (start == -1) return
                applyEmoteSpanInternal(textView, scheduleLayoutUpdate, spannable, start, end, drawable, emoteId, emoteName, 4)
            }
        } catch (e: Exception) {}
    }

    private fun applyEmoteSpanInternal(textView: TextView, scheduleLayoutUpdate: () -> Unit, spannable: Spannable?, start: Int, end: Int, drawable: Drawable, emoteId: String, emoteName: String, rightMargin: Int = 0) {
        if (spannable == null) return
        try {
            if (start >= spannable.length) return
            spannable.getSpans(start, end, CenterImageSpan::class.java).forEach { spannable.removeSpan(it) }
            spannable.setSpan(CenterImageSpan(drawable, rightMargin), start, end, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
            
            onEmoteClick?.let { listener ->
                val clickableSpan = object : android.text.style.ClickableSpan() {
                    override fun onClick(widget: View) { listener(emoteId, emoteName) }
                    override fun updateDrawState(ds: android.text.TextPaint) { ds.isUnderlineText = false }
                }
                spannable.setSpan(clickableSpan, start, end, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
            }
            scheduleLayoutUpdate()
            textView.invalidate()
        } catch (e: Exception) {}
    }

    private fun bindRestoreButton(holder: RestoreButtonViewHolder, message: ChatMessage) {
        holder.restoreButton.text = message.content
        holder.restoreButton.setOnClickListener {
            onRestoreClick?.invoke()
        }
        holder.dismissButton.setOnClickListener {
            onRestoreDismiss?.invoke()
        }
    }

    private fun bindInfoMessage(holder: InfoViewHolder, message: ChatMessage) {
        val content = message.content
        val htmlRes = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.N) {
            android.text.Html.fromHtml(content, android.text.Html.FROM_HTML_MODE_COMPACT)
        } else {
            @Suppress("DEPRECATION")
            android.text.Html.fromHtml(content)
        }
        
        // Remove underlines from HTML links
        val spannable = SpannableStringBuilder(htmlRes)
        spannable.getSpans(0, spannable.length, android.text.style.URLSpan::class.java).forEach { span ->
            val start = spannable.getSpanStart(span)
            val end = spannable.getSpanEnd(span)
            val url = span.url
            
            if (url.startsWith("https://")) {
                spannable.removeSpan(span)
                val newSpan = object : android.text.style.ClickableSpan() {
                    override fun onClick(widget: View) {
                        showLinkConfirmationDialog(widget.context, url)
                    }
                    override fun updateDrawState(ds: android.text.TextPaint) {
                        ds.isUnderlineText = false
                        ds.color = 0xFF53FC18.toInt()
                    }
                }
                spannable.setSpan(newSpan, start, end, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
            } else {
                // If it doesn't start with https://, remove the span so it's not clickable
                spannable.removeSpan(span)
            }
        }
        
        holder.infoMessageText.text = spannable
        holder.infoMessageText.textSize = textSize
        holder.infoMessageText.movementMethod = android.text.method.LinkMovementMethod.getInstance()
    }

    private fun bindSystemMessage(holder: SystemViewHolder, message: ChatMessage) {
        val content = message.content
        val htmlRes = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.N) {
            android.text.Html.fromHtml(content, android.text.Html.FROM_HTML_MODE_COMPACT)
        } else {
            @Suppress("DEPRECATION")
            android.text.Html.fromHtml(content)
        }

        // Remove underlines from HTML links
        val spannable = SpannableStringBuilder(htmlRes)
        spannable.getSpans(0, spannable.length, android.text.style.URLSpan::class.java).forEach { span ->
            val start = spannable.getSpanStart(span)
            val end = spannable.getSpanEnd(span)
            val url = span.url
            
            if (url.startsWith("https://")) {
                spannable.removeSpan(span)
                val newSpan = object : android.text.style.ClickableSpan() {
                    override fun onClick(widget: View) {
                        showLinkConfirmationDialog(widget.context, url)
                    }
                    override fun updateDrawState(ds: android.text.TextPaint) {
                        ds.isUnderlineText = false
                        ds.color = 0xFF53FC18.toInt()
                    }
                }
                spannable.setSpan(newSpan, start, end, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
            } else {
                spannable.removeSpan(span)
            }
        }
        
        // Make targetUsername clickable if present (for ban/unban messages)
        val targetUsername = message.targetUsername
        if (!targetUsername.isNullOrEmpty()) {
            val usernameIndex = spannable.toString().indexOf(targetUsername)
            if (usernameIndex >= 0) {
                val usernameEnd = usernameIndex + targetUsername.length
                val clickableSpan = object : android.text.style.ClickableSpan() {
                    override fun onClick(widget: View) {
                        // Create a minimal ChatMessage to trigger user actions
                        val dummySender = dev.xacnio.kciktv.shared.data.model.ChatSender(
                            id = 0,
                            username = targetUsername,
                            color = null,
                            badges = null
                        )
                        val dummyMessage = dev.xacnio.kciktv.shared.data.model.ChatMessage(
                            id = "target_${System.currentTimeMillis()}",
                            content = "",
                            sender = dummySender
                        )
                        onMessageClick?.invoke(dummyMessage)
                    }
                    override fun updateDrawState(ds: android.text.TextPaint) {
                        ds.isUnderlineText = false
                        ds.isFakeBoldText = true
                        ds.color = 0xFFFFFFFF.toInt()
                    }
                }
                spannable.setSpan(clickableSpan, usernameIndex, usernameEnd, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
            }
        }
        
        // Make moderatorUsername clickable if present (for ban/unban messages)
        val moderatorUsername = message.moderatorUsername
        if (!moderatorUsername.isNullOrEmpty() && moderatorUsername != "Moderator") {
            val modIndex = spannable.toString().indexOf(moderatorUsername)
            if (modIndex >= 0) {
                val modEnd = modIndex + moderatorUsername.length
                val modClickableSpan = object : android.text.style.ClickableSpan() {
                    override fun onClick(widget: View) {
                        val dummySender = dev.xacnio.kciktv.shared.data.model.ChatSender(
                            id = 0,
                            username = moderatorUsername,
                            color = null,
                            badges = null
                        )
                        val dummyMessage = dev.xacnio.kciktv.shared.data.model.ChatMessage(
                            id = "mod_${System.currentTimeMillis()}",
                            content = "",
                            sender = dummySender
                        )
                        onMessageClick?.invoke(dummyMessage)
                    }
                    override fun updateDrawState(ds: android.text.TextPaint) {
                        ds.isUnderlineText = false
                        ds.isFakeBoldText = true
                        ds.color = 0xFFFFFFFF.toInt()
                    }
                }
                spannable.setSpan(modClickableSpan, modIndex, modEnd, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
            }
        }
        
        holder.systemMessageText.text = spannable
        holder.systemMessageText.textSize = textSize
        holder.systemMessageText.movementMethod = android.text.method.LinkMovementMethod.getInstance()
        
        // Scale icon based on text size
        val density = holder.itemView.resources.displayMetrics.density
        val iconSize = (textSize * density * 1.2f).toInt()
        holder.systemMessageIcon.layoutParams.width = iconSize
        holder.systemMessageIcon.layoutParams.height = iconSize
        
        if (message.iconResId != null) {
            holder.systemMessageIcon.setImageResource(message.iconResId)
            holder.systemMessageIcon.visibility = View.VISIBLE
        } else {
            holder.systemMessageIcon.visibility = View.GONE
        }
    }

    fun updateClipInfo(
        clipId: String, 
        title: String, 
        thumbnail: String,
        channelName: String,
        channelAvatar: String?,
        views: Int,
        duration: Int
    ) {
        if (clipCache.containsKey(clipId)) return
        clipCache[clipId] = CachedClipInfo(title, thumbnail, channelName, channelAvatar, views, duration)
        activeClipFetches.remove(clipId)
        notifyDataSetChanged()
    }

    private fun bindClipPreview(holder: ChatViewHolder, message: ChatMessage) {
        try {
            val matcher = CLIP_URL_PATTERN.matcher(message.content)
            if (matcher.find()) {
                val clipId = matcher.group(1) ?: return
                
                holder.clipPreviewContainer.visibility = View.VISIBLE
                
                val info = clipCache[clipId]
                if (info != null) {
                    holder.clipShimmerLayout.stopShimmer()
                    holder.clipShimmerLayout.visibility = View.GONE
                    holder.clipContentLayout.visibility = View.VISIBLE
                    
                    holder.clipTitle.text = info.title
                    holder.clipChannel.text = info.channelName
                    holder.clipViews.text = "${dev.xacnio.kciktv.shared.util.FormatUtils.formatViewerCount(info.views.toLong())} views"
                    
                    if (info.duration > 0) {
                        holder.clipDuration.visibility = View.VISIBLE
                        holder.clipDuration.text = dev.xacnio.kciktv.shared.util.FormatUtils.formatDurationShort(info.duration)
                    } else {
                        holder.clipDuration.visibility = View.GONE
                    }
                    
                    Glide.with(holder.itemView.context)
                        .load(info.thumbnail)
                        .placeholder(R.drawable.placeholder_thumbnail)
                        .into(holder.clipThumbnail)
                        
                    holder.clipPreviewContainer.setOnClickListener {
                        onClipClick?.invoke(clipId)
                    }
                } else {
                    holder.clipContentLayout.visibility = View.GONE
                    holder.clipShimmerLayout.visibility = View.VISIBLE
                    holder.clipShimmerLayout.startShimmer()
                    
                    if (!activeClipFetches.contains(clipId)) {
                        activeClipFetches.add(clipId)
                        onClipUrlFound?.invoke(clipId)
                    }
                }
            } else {
                holder.clipPreviewContainer.visibility = View.GONE
            }
        } catch (e: Exception) {
            holder.clipPreviewContainer.visibility = View.GONE
        }
    }

    private fun bindChatMessage(holder: ChatViewHolder, message: ChatMessage) {
        
        // ABSOLUTELY CRITICAL: Clear text and spans immediately to stop any 
        // lingering drawing operations before we clear the Glide targets.
        holder.messageText.text = null
        holder.messageText.textSize = textSize 
        
        // Ensure TextView can receive clicks and long clicks
        holder.messageText.isClickable = true
        holder.messageText.isLongClickable = true
        holder.messageText.autoLinkMask = 0 // Disable autoLink to prevent overwriting our custom spans


        bindClipPreview(holder, message)

        // Highlight logic
        val isHighlightedByNav = message.id == highlightedMessageId
        var borderColor: Int? = null
        
        // Check highlight conditions
        val isOwn = highlightOwn && currentUsername != null && message.sender.username == currentUsername
        val isMention = highlightMentions && currentUsername != null && 
                  (message.content.contains("@$currentUsername", ignoreCase = true) || 
                   message.metadata?.originalSender?.username == currentUsername)
        val isMod = highlightMods && message.sender.badges?.any { it.type == "moderator" || it.type == "broadcaster" || it.type == "staff" } == true
        val isVip = highlightVips && message.sender.badges?.any { it.type == "vip" } == true

        if (isOwn || isMention || isMod || isVip) {
            if (useNameColorForHighlight) {
                // Priority: Use name color if enabled
                try {
                    borderColor = message.sender.color?.let { Color.parseColor(it) }
                } catch (e: Exception) {
                    // Fallback to default logic if parsing fails
                }
            }

            if (borderColor == null) {
                // Default Highlight Colors
                borderColor = when {
                    isOwn -> 0xFF53FC18.toInt() // Own: Kick Green
                    isMention -> 0xFF00F2FF.toInt() // Mentions: Cyan
                    isMod -> 0xFFFFD700.toInt() // Mods: Gold
                    isVip -> 0xFFFF00FF.toInt() // VIPs: Pink
                    else -> null
                }
            }
        }

        val context = holder.itemView.context
        val baseBg = if (isHighlightedByNav) {
            android.graphics.drawable.ColorDrawable(0x4453FC18)
        } else {
            androidx.core.content.ContextCompat.getDrawable(context, R.drawable.chat_message_background)
        }

        if (borderColor != null) {
            val stroke = android.graphics.drawable.GradientDrawable().apply {
                setColor(Color.TRANSPARENT)
                setStroke(3, borderColor)
                cornerRadius = 8f
            }
            val layers = if (baseBg != null) arrayOf(baseBg, stroke) else arrayOf(stroke)
            holder.itemView.background = android.graphics.drawable.LayerDrawable(layers)
        } else {
            holder.itemView.background = baseBg
        }

        // Custom movement method to handle link clicks separately from message clicks
        // Custom movement method to handle link clicks separately from message clicks
        // Custom movement method to handle link clicks separately from message clicks
        holder.messageText.movementMethod = object : android.text.method.LinkMovementMethod() {
            override fun onTouchEvent(widget: TextView, buffer: Spannable, event: android.view.MotionEvent): Boolean {
                val action = event.action
                if (action == android.view.MotionEvent.ACTION_UP || action == android.view.MotionEvent.ACTION_DOWN) {
                    var x = event.x.toInt()
                    var y = event.y.toInt()

                    x -= widget.totalPaddingLeft
                    y -= widget.totalPaddingTop

                    x += widget.scrollX
                    y += widget.scrollY

                    val layout = widget.layout
                    val line = layout.getLineForVertical(y)
                    val off = layout.getOffsetForHorizontal(line, x.toFloat())

                    // Find ClickableSpan at this strict position
                    val links = buffer.getSpans(off, off, android.text.style.ClickableSpan::class.java)

                    if (links.isNotEmpty()) {
                        // Check if it is a URLSpan (default linkify) or our reply link
                        val link = links[0]
                        if (link is android.text.style.URLSpan) {
                            if (action == android.view.MotionEvent.ACTION_UP) {
                                val url = link.url ?: ""
                                if (url.startsWith("http")) {
                                    lastLinkClickTime = System.currentTimeMillis()
                                    dev.xacnio.kciktv.shared.ui.adapter.ChatAdapter.showLinkConfirmationDialog(widget.context, url)
                                } else {
                                    lastLinkClickTime = System.currentTimeMillis()
                                    link.onClick(widget)
                                }
                            } else if (action == android.view.MotionEvent.ACTION_DOWN) {
                                android.text.Selection.setSelection(buffer, buffer.getSpanStart(link), buffer.getSpanEnd(link))
                            }
                            return true // CONSUMED
                        } else if (link.toString().contains("ClickableSpan")) {
                             if (action == android.view.MotionEvent.ACTION_UP) {
                                link.onClick(widget)
                            } else if (action == android.view.MotionEvent.ACTION_DOWN) {
                                android.text.Selection.setSelection(buffer, buffer.getSpanStart(link), buffer.getSpanEnd(link))
                            }
                            return true // CONSUMED
                        }
                    } else {
                        android.text.Selection.removeSelection(buffer)
                    }
                }
                // Return false for normal text touches so the TextView's OnClickListener can fire
                return false 
            }
        }
        holder.clear()
        
        // Apply alpha for SENDING state - DISABLED as requested
        // val isSending = message.status == dev.xacnio.kciktv.shared.data.model.MessageStatus.SENDING
        // holder.itemView.alpha = if (isSending) 0.5f else 1.0f
        holder.itemView.alpha = 1.0f
        
        val emotePlaceholdersReply = mutableListOf<EmotePlaceholder>() // Reply emotes
        val badgeSize = (holder.messageText.textSize * 1.2).toInt()
        
        // Build styled message: [badges] username: message
        val builder = SpannableStringBuilder()
        
        // Add Reply Header if present
        // Add Reply Header if present
        message.metadata?.originalSender?.let { originalSender ->
            val start = builder.length
            
            val originalContent = message.metadata.originalMessageContent ?: ""
            val hasContent = originalContent.isNotEmpty()
            
            // Custom Reply Icon (Drawable) instead of text arrow

            val replyIcon = androidx.core.content.ContextCompat.getDrawable(context, R.drawable.ic_reply)?.mutate()
            if (replyIcon != null) {
                val iconSize = (holder.messageText.textSize * 0.8f).toInt()
                replyIcon.setBounds(0, 0, iconSize, iconSize)
                replyIcon.setTint(0xFF999999.toInt())
                builder.append("  ") // Placeholder space
                builder.setSpan(CenterImageSpan(replyIcon), start, start + 1, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
                builder.append(" @${originalSender.username}")
            } else {
                builder.append("↪ @${originalSender.username}")
            }

            // Add content if available
            if (hasContent) {
                builder.append(": ")
                val replyContentStart = builder.length
                
                // Truncate limit removed as per request.
                // We will display the full content. If it's too long, it will wrap.
                val displayContent = originalContent
                
                // Process emotes in the reply content
                val processedReplyContent = processEmotesInContent(displayContent, replyContentStart, emotePlaceholdersReply)
                
                builder.append(processedReplyContent)
                
                // Add Annotation spans and transparent placeholders for reliable async tracking in replies
                val replyEmoteSize = (holder.messageText.textSize * 0.8f).toInt()
                applyEmoteAnnotations(builder, emotePlaceholdersReply, replyEmoteSize)
            }
            
            builder.append("\n")
            
            val spanEnd = builder.length 
            
            // Common styles
            builder.setSpan(ForegroundColorSpan(0xFF999999.toInt()), start, spanEnd, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
            builder.setSpan(android.text.style.RelativeSizeSpan(0.8f), start, spanEnd, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
            // Italic removed as requested
            // builder.setSpan(android.text.style.StyleSpan(android.graphics.Typeface.ITALIC), start, spanEnd, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
            
            // Clickable Span for navigation
            message.metadata.originalMessageId?.let { originalId ->
                val clickableSpan = object : android.text.style.ClickableSpan() {
                    override fun onClick(widget: View) {
                        dev.xacnio.kciktv.mobile.util.DialogUtils.lastLinkClickTime = System.currentTimeMillis()
                        onReplyClick(originalId)
                    }
                    override fun updateDrawState(ds: android.text.TextPaint) {
                        super.updateDrawState(ds)
                        ds.isUnderlineText = false 
                        ds.color = 0xFF999999.toInt() 
                    }
                }
                builder.setSpan(clickableSpan, start, spanEnd, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
            }
        }
        

        
        // Add timestamp if enabled
        if (showTimestamps) {
            val pattern = if (showSeconds) "HH:mm:ss" else "HH:mm"
            val sdf = java.text.SimpleDateFormat(pattern, java.util.Locale.getDefault())
            val timeStr = "${sdf.format(java.util.Date(message.createdAt))} "
            val start = builder.length
            builder.append(timeStr)
            
            // Apply multiple spans for look and feel
            builder.setSpan(
                ForegroundColorSpan(0xFF999999.toInt()), // Slightly lighter gray
                start,
                builder.length,
                Spannable.SPAN_EXCLUSIVE_EXCLUSIVE
            )
            builder.setSpan(
                android.text.style.RelativeSizeSpan(0.8f), // Smaller size
                start,
                builder.length,
                Spannable.SPAN_EXCLUSIVE_EXCLUSIVE
            )
            builder.setSpan(
                android.text.style.TypefaceSpan("sans-serif-light"), // Light font weight
                start,
                builder.length,
                Spannable.SPAN_EXCLUSIVE_EXCLUSIVE
            )
        }
        
        // Add badges - first add placeholders, then load images
        val badgePlaceholders = mutableListOf<Pair<Int, String>>() // position -> badge URL or emoji
        
        message.sender.badges?.forEach { badge ->
            val startPos = builder.length
            when (badge.type) {
                "broadcaster" -> {

                    val d = androidx.core.content.ContextCompat.getDrawable(context, R.drawable.ic_badge_broadcaster)?.mutate()
                    if (d != null) {
                        d.setBounds(0, 0, badgeSize, badgeSize)
                        builder.append("  ")
                        builder.setSpan(CenterImageSpan(d), startPos, startPos + 1, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
                    } else {
                        builder.append("🎬 ")
                    }
                }
                "moderator" -> {

                    val d = androidx.core.content.ContextCompat.getDrawable(context, R.drawable.ic_badge_moderator)?.mutate()
                    if (d != null) {
                        d.setBounds(0, 0, badgeSize, badgeSize)
                        builder.append("  ")
                        builder.setSpan(CenterImageSpan(d), startPos, startPos + 1, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
                    } else {
                        builder.append("🛡️ ")
                    }
                }
                "vip" -> {

                    val d = androidx.core.content.ContextCompat.getDrawable(context, R.drawable.ic_badge_vip)?.mutate()
                    if (d != null) {
                        d.setBounds(0, 0, badgeSize, badgeSize)
                        builder.append("  ")
                        builder.setSpan(CenterImageSpan(d), startPos, startPos + 1, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
                    } else {
                        builder.append("💎 ")
                    }
                }
                "og" -> {

                    val d = androidx.core.content.ContextCompat.getDrawable(context, R.drawable.ic_badge_og)?.mutate()
                    if (d != null) {
                        d.setBounds(0, 0, badgeSize, badgeSize)
                        builder.append("  ")
                        builder.setSpan(CenterImageSpan(d), startPos, startPos + 1, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
                    } else {
                        builder.append("👑 ")
                    }
                }
                "verified" -> {

                    val d = androidx.core.content.ContextCompat.getDrawable(context, R.drawable.ic_badge_verified)?.mutate()
                    if (d != null) {
                        d.setBounds(0, 0, badgeSize, badgeSize)
                        builder.append("  ")
                        builder.setSpan(CenterImageSpan(d), startPos, startPos + 1, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
                    } else {
                        builder.append("✓ ")
                    }
                }
                "founder" -> {

                    val d = androidx.core.content.ContextCompat.getDrawable(context, R.drawable.ic_badge_founder)?.mutate()
                    if (d != null) {
                        d.setBounds(0, 0, badgeSize, badgeSize)
                        builder.append("  ")
                        builder.setSpan(CenterImageSpan(d), startPos, startPos + 1, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
                    } else {
                        builder.append("🏆 ")
                    }
                }
                "staff" -> {

                    val d = androidx.core.content.ContextCompat.getDrawable(context, R.drawable.ic_badge_staff)?.mutate()
                    if (d != null) {
                        d.setBounds(0, 0, badgeSize, badgeSize)
                        builder.append("  ")
                        builder.setSpan(CenterImageSpan(d), startPos, startPos + 1, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
                    } else {
                        builder.append("🔧 ")
                    }
                }
                "subscriber" -> {
                    // Subscriber badge - load image based on count (get months from count or text)
                    val months = badge.count ?: badge.text?.toIntOrNull() ?: 1
                    // Find the most suitable badge (closest equal or smaller)
                    val badgeUrl = subscriberBadges.keys
                        .filter { it <= months }
                        .maxOrNull()
                        ?.let { subscriberBadges[it] }
                    
                    if (badgeUrl != null) {
                        builder.append("  ") // Placeholder for image
                        // Add transparent placeholder to prevent jumping as images load
                        val placeholder = android.graphics.drawable.ColorDrawable(android.graphics.Color.TRANSPARENT)
                        placeholder.setBounds(0, 0, badgeSize, badgeSize)
                        builder.setSpan(CenterImageSpan(placeholder, 4), startPos, startPos + 1, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
                        badgePlaceholders.add(startPos to badgeUrl)
                    } else {
    
                        val d = androidx.core.content.ContextCompat.getDrawable(context, R.drawable.ic_badge_subscriber_default)?.mutate()
                        if (d != null) {
                            d.setBounds(0, 0, badgeSize, badgeSize)
                            builder.append("  ")
                            builder.setSpan(CenterImageSpan(d), startPos, startPos + 1, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
                        } else {
                            builder.append("⭐ ")
                        }
                    }
                }
                "sub_gifter" -> {

                    val d = androidx.core.content.ContextCompat.getDrawable(context, R.drawable.ic_badge_sub_gifter)?.mutate()
                    if (d != null) {
                        d.setBounds(0, 0, badgeSize, badgeSize)
                        builder.append("  ")
                        builder.setSpan(CenterImageSpan(d), startPos, startPos + 1, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
                    } else {
                        builder.append("🎁 ")
                    }
                }
                "sidekick" -> {

                    val d = androidx.core.content.ContextCompat.getDrawable(context, R.drawable.ic_badge_sidekick)?.mutate()
                    if (d != null) {
                        d.setBounds(0, 0, badgeSize, badgeSize)
                        builder.append("  ")
                        builder.setSpan(CenterImageSpan(d), startPos, startPos + 1, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
                    } else {
                        builder.append("🤝 ")
                    }
                }
                "bot" -> {

                    val d = androidx.core.content.ContextCompat.getDrawable(context, R.drawable.ic_badge_bot)?.mutate()
                    if (d != null) {
                        d.setBounds(0, 0, badgeSize, badgeSize)
                        builder.append("  ")
                        builder.setSpan(CenterImageSpan(d), startPos, startPos + 1, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
                    } else {
                        builder.append("🤖 ")
                    }
                }
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
        builder.setSpan(
            android.text.style.StyleSpan(android.graphics.Typeface.BOLD),
            usernameStart,
            usernameEnd,
            Spannable.SPAN_EXCLUSIVE_EXCLUSIVE
        )
        
        // Add message content - process emotes BEFORE adding to builder
        builder.append(": ")
        val messageStartIndex = builder.length
        
        // Parse emotes and process with placeholders
        val emoteSize = (holder.messageText.textSize * emoteScale).toInt()
        val emotePlaceholders = mutableListOf<EmotePlaceholder>() 
        
        val processedContent = processEmotesInContent(message.content, messageStartIndex, emotePlaceholders)
        builder.append(processedContent)
        
        // Add Annotation spans and transparent placeholders for reliable async tracking
        applyEmoteAnnotations(builder, emotePlaceholders, emoteSize)


        // Add reply emotes placeholders
        val replyEmoteSize = (holder.messageText.textSize * 0.8f).toInt()
        // Note: processEmotesInContent for reply was handled during reply header construction
        // but we need to load them here.
        
        // Load emotes BEFORE setting text to ensure cached emotes are shown immediately
        // IMPORTANT: Process reply emotes first because they appear earlier in the builder string
        for (p in emotePlaceholdersReply) {
            loadEmoteImage(holder, builder, p.start, p.end, p.id, p.name, replyEmoteSize)
        }
        for (p in emotePlaceholders) {
            loadEmoteImage(holder, builder, p.start, p.end, p.id, p.name, emoteSize)
        }

        // Apply message status color to content
        val contentColor = when (message.status) {
            dev.xacnio.kciktv.shared.data.model.MessageStatus.SENDING -> 0xFFFFFFFF.toInt()
            dev.xacnio.kciktv.shared.data.model.MessageStatus.FAILED -> 0xFFFF4444.toInt()
            dev.xacnio.kciktv.shared.data.model.MessageStatus.DELETED -> 0x66FFFFFF.toInt()
            else -> 0xFFFFFFFF.toInt()
        }
        builder.setSpan(
            ForegroundColorSpan(contentColor),
            messageStartIndex,
            builder.length,
            Spannable.SPAN_EXCLUSIVE_EXCLUSIVE
        )

        // Apply strike-through for deleted messages
        if (message.status == dev.xacnio.kciktv.shared.data.model.MessageStatus.DELETED) {
            builder.setSpan(
                android.text.style.StrikethroughSpan(),
                messageStartIndex,
                builder.length,
                Spannable.SPAN_EXCLUSIVE_EXCLUSIVE
            )
        }
        
        // Check for DELETED status and append (Deleted) text
        if (message.status == dev.xacnio.kciktv.shared.data.model.MessageStatus.DELETED) {
            val deletedStart = builder.length
            val deletedText = " (Deleted)"
            builder.append(deletedText)
            builder.setSpan(
                ForegroundColorSpan(Color.GRAY),
                deletedStart,
                deletedStart + deletedText.length,
                Spannable.SPAN_EXCLUSIVE_EXCLUSIVE
            )

            builder.setSpan(
                android.text.style.StyleSpan(android.graphics.Typeface.ITALIC),
                deletedStart,
                deletedStart + deletedText.length,
                Spannable.SPAN_EXCLUSIVE_EXCLUSIVE
            )

            if (message.isAiModerated) {
                builder.append(" ") // Spacer

                // "AI Mod" Badge
                val aiStart = builder.length
                val aiText = " AI Mod "
                builder.append(aiText)

                // Background Span
                val badgeColor = 0xFFFFC107.toInt() // Amber 500
                builder.setSpan(
                     android.text.style.BackgroundColorSpan(badgeColor),
                     aiStart,
                     aiStart + aiText.length,
                     Spannable.SPAN_EXCLUSIVE_EXCLUSIVE
                )
                
                builder.setSpan(
                    ForegroundColorSpan(Color.BLACK),
                    aiStart,
                    aiStart + aiText.length,
                    Spannable.SPAN_EXCLUSIVE_EXCLUSIVE
                )
                
                builder.setSpan(
                    android.text.style.StyleSpan(android.graphics.Typeface.BOLD),
                    aiStart,
                    aiStart + aiText.length,
                    Spannable.SPAN_EXCLUSIVE_EXCLUSIVE
                )
                
                // Violated Rules
                message.violatedRules?.forEach { rule ->
                    val ruleStart = builder.length
                    val ruleText = " [$rule]"
                    builder.append(ruleText)
                    
                    builder.setSpan(
                        ForegroundColorSpan(Color.WHITE),
                        ruleStart,
                        ruleStart + ruleText.length,
                        Spannable.SPAN_EXCLUSIVE_EXCLUSIVE
                    )
                     builder.setSpan(
                        android.text.style.StyleSpan(android.graphics.Typeface.BOLD),
                        ruleStart,
                        ruleStart + ruleText.length,
                        Spannable.SPAN_EXCLUSIVE_EXCLUSIVE
                    )
                }
            }
        }
        
        // Detect hyperlinks in normal messages and make them clickable (without underline)
        // Use robust pattern that captures trailing slashes correctly
        val robustUrlPattern = java.util.regex.Pattern.compile("https?://[^\\s]+", java.util.regex.Pattern.CASE_INSENSITIVE)
        val urlMatcher = robustUrlPattern.matcher(builder)
        while (urlMatcher.find()) {
            var start = urlMatcher.start()
            var end = urlMatcher.end()
            
            // Trim trailing punctuation (.,!?) but KEEP trailing slash
            while (end > start) {
                val c = builder[end - 1]
                if (c == '/' ) break
                if (c == '.' || c == ',' || c == '!' || c == '?' || c == ')' || c == ']' || c == ';') {
                    end--
                } else {
                    break
                }
            }
            
            val url = builder.substring(start, end)
            if (url.startsWith("https://") || url.startsWith("http://")) {
                val clickableSpan = dev.xacnio.kciktv.mobile.util.DialogUtils.UrlClickableSpan(holder.itemView.context, url)
                builder.setSpan(clickableSpan, start, end, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
            }
        }

        // Load and set text
        holder.messageText.setText(builder, TextView.BufferType.SPANNABLE)
        
        // Load subscriber badge images
        for ((pos, url) in badgePlaceholders) {
            loadBadgeImage(holder, pos, url, badgeSize)
        }
        
        // Click and Long click listeners for message actions
        val clickListener = View.OnClickListener {
            // ONLY trigger onMessageClick if it wasn't a link click (with a small safety buffer)
            if (System.currentTimeMillis() - dev.xacnio.kciktv.mobile.util.DialogUtils.lastLinkClickTime > 300) {
                onMessageClick?.invoke(message)
            }
        }
        // Capture touch coordinates for precise long-press detection
        var lastTouchX = 0f
        var lastTouchY = 0f
        holder.messageText.setOnTouchListener { _, event ->
            if (event.action == android.view.MotionEvent.ACTION_DOWN) {
                lastTouchX = event.x
                lastTouchY = event.y
            }
            false
        }

        val longClickListener = View.OnLongClickListener { view ->
            var urlFound: String? = null
            if (view is TextView && view.text is Spannable) {
                 val x = lastTouchX.toInt() - view.totalPaddingLeft + view.scrollX
                 val y = lastTouchY.toInt() - view.totalPaddingTop + view.scrollY
                 val layout = view.layout
                 if (layout != null) {
                     val line = layout.getLineForVertical(y)
                     val offset = layout.getOffsetForHorizontal(line, x.toFloat())
                     val spans = (view.text as Spannable).getSpans(offset, offset, dev.xacnio.kciktv.mobile.util.DialogUtils.UrlClickableSpan::class.java)
                     if (spans.isNotEmpty()) {
                         urlFound = spans[0].url
                     }
                 }
            }
            
            if (urlFound != null) {
                 view.performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS)
                 dev.xacnio.kciktv.mobile.util.DialogUtils.showLinkOptionsBottomSheet(view.context, urlFound)
                 dev.xacnio.kciktv.mobile.util.DialogUtils.lastLinkLongPressTime = System.currentTimeMillis()
                 // Cancel touch to prevent LinkMovementMethod from firing onClick
                 val cancelEvent = android.view.MotionEvent.obtain(
                     android.os.SystemClock.uptimeMillis(),
                     android.os.SystemClock.uptimeMillis(),
                     android.view.MotionEvent.ACTION_CANCEL,
                     0f, 0f, 0
                 )
                 view.dispatchTouchEvent(cancelEvent)
                 cancelEvent.recycle()
            } else {
                 onMessageLongClick?.invoke(message)
            }
            true
        }

        // Listeners are applied directly to messageText below
        // holder.itemView.setOnClickListener(clickListener)
        // holder.itemView.setOnLongClickListener(longClickListener)

        // Set listener for empty space tap
        holder.itemView.setOnClickListener {
            onEmptySpaceClick?.invoke()
        }
        
        // Also set listeners on TextView to ensure it catches clicks/long clicks correctly
        holder.messageText.setOnClickListener(clickListener)
        holder.messageText.setOnLongClickListener(longClickListener)
    }

    override fun onViewAttachedToWindow(holder: RecyclerView.ViewHolder) {
        super.onViewAttachedToWindow(holder)
        // Check tag set by onBindViewHolder — no need for adapterPosition/getItem
        val pendingId = holder.itemView.getTag(R.id.chat_animation_pending) as? String
        if (pendingId != null && !animatingViews.contains(holder.itemView)) {
            runEnterAnimation(holder.itemView, 0)
        }
    }

    override fun onViewRecycled(holder: RecyclerView.ViewHolder) {
        super.onViewRecycled(holder)
        
        // Clear animation state
        val pendingId = holder.itemView.getTag(R.id.chat_animation_pending) as? String
        if (pendingId != null) pendingAnimationIds.remove(pendingId)
        holder.itemView.setTag(R.id.chat_animation_pending, null)
        animatingViews.remove(holder.itemView)
        
        // Ensure no stray animations persist
        holder.itemView.animate().cancel()
        holder.itemView.alpha = 1f
        holder.itemView.translationX = 0f
        holder.itemView.translationY = 0f
        holder.itemView.scaleX = 1f
        holder.itemView.scaleY = 1f
        holder.itemView.rotationX = 0f
        holder.itemView.rotationY = 0f
        holder.itemView.rotation = 0f

        when (holder) {
            is ChatViewHolder -> {
                dev.xacnio.kciktv.shared.ui.utils.EmoteManager.unregisterViewer(holder.messageText)
                holder.messageText.text = null
                holder.clear()
            }
            is CelebrationViewHolder -> {
                dev.xacnio.kciktv.shared.ui.utils.EmoteManager.unregisterViewer(holder.messageText)
                holder.messageText.text = null
                holder.clear()
            }
            is GiftViewHolder -> {
                com.bumptech.glide.Glide.with(holder.itemView.context.applicationContext).clear(holder.giftImage)
            }
        }
    }
    
    private fun loadBadgeImage(holder: ChatViewHolder, position: Int, url: String, size: Int) {
        val textView = holder.messageText
        
        // Always try APNG first (badge URLs don't have extensions)
        // ApngBadgeManager will fallback if it's not an APNG
        dev.xacnio.kciktv.shared.ui.utils.ApngBadgeManager.loadBadge(
            url,
            size,
            textView
        ) { drawable ->
            if (drawable != null) {
                // Successfully loaded (APNG or fallback)
                try {
                    val currentText = textView.text
                    if (currentText is Spannable && position < currentText.length) {
                        currentText.getSpans(position, position + 1, CenterImageSpan::class.java).forEach {
                            currentText.removeSpan(it)
                        }
                        
                        currentText.setSpan(
                            CenterImageSpan(drawable),
                            position,
                            position + 1,
                            Spannable.SPAN_EXCLUSIVE_EXCLUSIVE
                        )
                        
                        holder.scheduleLayoutUpdate()
                    }
                    Unit
                } catch (e: Exception) {
                    android.util.Log.e("ChatAdapter", "Badge error: ${e.message}")
                }
            } else {
                // ApngBadgeManager failed, use EmoteManager as final fallback
                dev.xacnio.kciktv.shared.ui.utils.EmoteManager.loadSynchronizedImage(
                    textView.context,
                    url,
                    size,
                    textView
                ) { sharedDrawable ->
                    try {
                        val currentText = textView.text
                        if (currentText is Spannable && position < currentText.length) {
                            currentText.getSpans(position, position + 1, CenterImageSpan::class.java).forEach {
                                currentText.removeSpan(it)
                            }
                            
                            currentText.setSpan(
                                CenterImageSpan(sharedDrawable),
                                position,
                                position + 1,
                                Spannable.SPAN_EXCLUSIVE_EXCLUSIVE
                            )
                            
                            holder.scheduleLayoutUpdate()
                        }
                        Unit
                    } catch (e: Exception) {
                        android.util.Log.e("ChatAdapter", "Badge fallback error: ${e.message}")
                    }
                }
            }
        }
    }
    
    private data class EmotePlaceholder(val start: Int, val end: Int, val id: String, val name: String)

    /**
     * Process emotes in content - keeps [emote:id:name] tags as underlying text
     * Returns the processed content string
     */
    private fun processEmotesInContent(
        content: String, 
        basePosition: Int,
        emotePlaceholders: MutableList<EmotePlaceholder>
    ): String {
        val matcher = EMOTE_PATTERN.matcher(content)
        val result = StringBuilder()
        var lastEnd = 0
        
        while (matcher.find()) {

            val emoteId = matcher.group(1) ?: continue
            val emoteName = matcher.group(2) ?: continue
            
            // Add text before this emote
            result.append(content.substring(lastEnd, matcher.start()))
            
            // Calculate actual position (Object Replacement Character is 1 char)
            val tagStart = basePosition + result.length
            
            // Use Object Replacement Character (\uFFFC) for visual rendering
            result.append("\uFFFC")
            
            // Record target metadata
            emotePlaceholders.add(EmotePlaceholder(tagStart, tagStart + 1, emoteId, emoteName))
            
            lastEnd = matcher.end()
        }
        
        // Add remaining text after last emote
        result.append(content.substring(lastEnd))
        
        return result.toString()
    }

    /**
     * Helper to apply Annotation spans and transparent placeholders to a builder
     */
    private fun applyEmoteAnnotations(builder: SpannableStringBuilder, placeholders: List<EmotePlaceholder>, size: Int) {
        for (p in placeholders) {
            val tag = "[emote:${p.id}:${p.name}]"
            
            // Apply Annotation for tracking
            builder.setSpan(
                android.text.Annotation("emote_tag", tag),
                p.start,
                p.end,
                Spannable.SPAN_EXCLUSIVE_EXCLUSIVE
            )
            
            // Apply a transparent placeholder span immediately to hide the "OBJ" text
            val placeholder = android.graphics.drawable.ColorDrawable(android.graphics.Color.TRANSPARENT)
            placeholder.setBounds(0, 0, size, size)
            builder.setSpan(
                CenterImageSpan(placeholder, 4),
                p.start,
                p.end,
                Spannable.SPAN_EXCLUSIVE_EXCLUSIVE
            )
        }
    }
    
    /**
     * Load emote image at specific position (supports animated GIF/WebP)
     */
    private fun loadEmoteImage(holder: ChatViewHolder, targetSpannable: Spannable?, start: Int, end: Int, emoteId: String, emoteName: String, size: Int) {
        val textView = holder.messageText
        val tag = "[emote:$emoteId:$emoteName]"
        
        dev.xacnio.kciktv.shared.ui.utils.EmoteManager.loadSynchronizedEmote(
            textView.context,
            emoteId,
            size,
            textView
        ) { sharedDrawable ->
            // Update live view text
            val currentSpannable = textView.text as? Spannable
            if (currentSpannable != null) {
                // Verify that the emote tag is still there at the intended position
                val annotations = currentSpannable.getSpans(start, end, android.text.Annotation::class.java)
                val isStillSame = annotations.any { it.key == "emote_tag" && it.value == tag }
                
                if (isStillSame) {
                    // Apply to exact position
                    applyEmoteSpan(holder, currentSpannable, start, end, sharedDrawable, emoteId, emoteName, 4)
                } else {
                    // Fallback to tag search if text content shifted (rare in our adapter)
                    applyEmoteSpanToTag(holder, currentSpannable, tag, sharedDrawable, emoteId, emoteName)
                }
            }
            
            // Also update the provided builder if it's different (initial load optimization)
            if (targetSpannable != null && targetSpannable !== currentSpannable) {
                applyEmoteSpan(holder, targetSpannable, start, end, sharedDrawable, emoteId, emoteName, 4)
            }
        }
    }
    
    /**
     * Find the target range using Annotation spans and apply the emote span
     */
    private fun applyEmoteSpanToTag(holder: ChatViewHolder, spannable: Spannable, tag: String, drawable: Drawable, emoteId: String, emoteName: String) {
        try {
            val annotations = spannable.getSpans(0, spannable.length, android.text.Annotation::class.java)
            val target = annotations.find { ann ->
                if (ann.key != "emote_tag" || ann.value != tag) return@find false
                val start = spannable.getSpanStart(ann)
                val end = spannable.getSpanEnd(ann)
                if (start == -1) return@find false
                
                // We check if there's no "real" emote span yet. 
                // A "real" one is a CenterImageSpan where drawable is NOT a ColorDrawable.
                val existing = spannable.getSpans(start, end, CenterImageSpan::class.java)
                existing.isEmpty() || existing.any { it.drawable is android.graphics.drawable.ColorDrawable }
            }
            
            if (target != null) {
                val start = spannable.getSpanStart(target)
                val end = spannable.getSpanEnd(target)
                if (start == -1) return
                
                // Final application
                applyEmoteSpan(holder, spannable, start, end, drawable, emoteId, emoteName, 4)
            }
        } catch (e: Exception) {
            android.util.Log.e("ChatAdapter", "Tag span error: ${e.message}")
        }
    }
    
    /**
     * Apply the emote span to a target Spannable at the given range
     */
    private fun applyEmoteSpan(holder: ChatViewHolder, spannable: Spannable?, start: Int, end: Int, drawable: Drawable, emoteId: String, emoteName: String, rightMargin: Int = 0) {
        if (spannable == null) return
        
        try {
            val textLength = spannable.length
            if (start >= textLength) return
            
            // Update in-place to avoid flickering
            spannable.getSpans(start, end, CenterImageSpan::class.java).forEach {
                spannable.removeSpan(it)
            }
            
            // Add ImageSpan
            val span = CenterImageSpan(drawable, rightMargin)
            spannable.setSpan(
                span,
                start,
                end,
                Spannable.SPAN_EXCLUSIVE_EXCLUSIVE
            )
            
            // Add ClickableSpan for emote click action
            onEmoteClick?.let { listener ->
                val clickableSpan = object : android.text.style.ClickableSpan() {
                    override fun onClick(widget: View) {
                        listener(emoteId, emoteName)
                    }
                    override fun updateDrawState(ds: android.text.TextPaint) {
                        ds.color = ds.color
                        ds.isUnderlineText = false
                    }
                }
                spannable.setSpan(
                    clickableSpan,
                    start,
                    end,
                    Spannable.SPAN_EXCLUSIVE_EXCLUSIVE
                )
            }
            
            // Trigger redraw
            holder.scheduleLayoutUpdate()
            holder.messageText.invalidate()
        } catch (e: Exception) {
            android.util.Log.e("ChatAdapter", "Emote span error: ${e.message}", e)
        }
    }

    fun updateThemeColor(color: Int) {
        themeColor = color
        notifyDataSetChanged()
    }

    /**
     * Add new messages efficiently with merging and sorting
     * @param deduplicate Set to true when loading history to remove duplicate messages
     */
    fun addMessages(newMessages: List<ChatMessage>, deduplicate: Boolean = false, animate: Boolean = true, onCommit: (() -> Unit)? = null) {
        if (newMessages.isEmpty()) return
        
        // Mark new messages for animation (only non-deduplicated, i.e. truly new messages)
        if (!deduplicate && animate && animationType != "none") {
            for (msg in newMessages) {
                pendingAnimationIds.add(msg.id)
            }
        }
        
        val finalList: List<ChatMessage>
        
        synchronized(messageCache) {
            // Fast path for single message append (most common case)
            if (newMessages.size == 1 && !deduplicate) {
                val newMsg = newMessages[0]
                // Insert maintaining sort order
                val insertIndex = messageCache.indexOfLast { it.createdAt <= newMsg.createdAt } + 1
                messageCache.add(insertIndex, newMsg)
                
                // Trim only if auto scroll is enabled (user is at bottom)
                if (isAutoScrollEnabled) {
                    while (messageCache.size > Constants.Chat.MESSAGE_LIMIT) {
                        messageCache.removeAt(0)
                    }
                }
                
                finalList = ArrayList(messageCache)
            } else {
                // Batch path for multiple messages or history loading
                messageCache.addAll(newMessages)
                
                // Only deduplicate when loading history
                val workingList = if (deduplicate) {
                    val seen = HashSet<String>(messageCache.size)
                    messageCache.filter { seen.add(it.id) }
                } else {
                    messageCache.toList()
                }
                
                // Sort by createdAt
                //val sortedList = workingList.sortedBy { it.createdAt }
                
                // Trim to limit only if auto scroll is enabled
                val trimmedList = if (isAutoScrollEnabled && workingList.size > Constants.Chat.MESSAGE_LIMIT) {
                    workingList.subList(workingList.size - Constants.Chat.MESSAGE_LIMIT, workingList.size)
                } else {
                    workingList
                }
                
                messageCache.clear()
                messageCache.addAll(trimmedList)
                finalList = ArrayList(messageCache)
            }
        }
        
        // Submit outside synchronized block to avoid blocking
        submitList(finalList) {
            onCommit?.invoke()
            onMessageAdded?.invoke()
        }
    }

    fun clearMessages() {
        pendingAnimationIds.clear()
        synchronized(messageCache) {
            messageCache.clear()
            submitList(emptyList())
        }
    }
    
    fun clearAll() {
        clearMessages()
    }
    
    fun removeMessage(messageId: String) {
        synchronized(messageCache) {
            val removed = messageCache.removeIf { it.id == messageId }
            if (removed) {
                submitList(ArrayList(messageCache))
            }
        }
    }


    fun updateMessageStatus(messageRef: String, newStatus: dev.xacnio.kciktv.shared.data.model.MessageStatus, updatedSender: dev.xacnio.kciktv.shared.data.model.ChatSender? = null) {
        val currentList = currentList.toMutableList()
        val index = currentList.indexOfFirst { it.messageRef == messageRef }
        if (index != -1) {
            val oldMsg = currentList[index]
            val updatedMsg = oldMsg.copy(
                status = newStatus,
                sender = updatedSender ?: oldMsg.sender
            )
            currentList[index] = updatedMsg
            submitList(currentList)
        }
    }

    fun markMessageAsDeleted(messageId: String, isAiModerated: Boolean = false, violatedRules: List<String>? = null) {
        synchronized(messageCache) {
            val index = messageCache.indexOfFirst { it.id == messageId }
            if (index != -1) {
                val oldMsg = messageCache[index]
                if (oldMsg.status != dev.xacnio.kciktv.shared.data.model.MessageStatus.DELETED) {
                    val updatedMsg = oldMsg.copy(
                        status = dev.xacnio.kciktv.shared.data.model.MessageStatus.DELETED,
                        isAiModerated = isAiModerated,
                        violatedRules = violatedRules
                    )
                    messageCache[index] = updatedMsg
                    submitList(ArrayList(messageCache))
                }
            }
        }
    }

    fun markUserMessagesAsDeleted(username: String) {
        synchronized(messageCache) {
            var changed = false
            for (i in messageCache.indices) {
                val msg = messageCache[i]
                if (msg.sender.username.equals(username, ignoreCase = true) && 
                    msg.status != dev.xacnio.kciktv.shared.data.model.MessageStatus.DELETED) {
                    messageCache[i] = msg.copy(status = dev.xacnio.kciktv.shared.data.model.MessageStatus.DELETED)
                    changed = true
                }
            }
            if (changed) {
                submitList(ArrayList(messageCache))
            }
        }
    }

    fun confirmSentMessage(messageRef: String, serverMessage: ChatMessage) {
        synchronized(messageCache) {
            val index = messageCache.indexOfFirst { it.messageRef == messageRef && it.status == dev.xacnio.kciktv.shared.data.model.MessageStatus.SENDING }
            if (index != -1) {
                val oldMsg = messageCache[index]
                // Replace local draft with server message but PRESERVE original timestamp
                // to prevent the message from jumping down in the list.
                messageCache[index] = serverMessage.copy(createdAt = oldMsg.createdAt)
                submitList(ArrayList(messageCache))
            } else {
                // If not found (maybe cleared or already updated), just add it
                addMessages(listOf(serverMessage))
            }
        }
    }

    /**
     * Attempts to confirm a sent message by matching content and sender.
     * Returns true if a pending message was found and updated, false otherwise.
     */
    fun confirmSentMessageByContent(serverMessage: ChatMessage): Boolean {
        synchronized(messageCache) {
            // Find a pending message from same user with same content
            // We search from end (most recent) to start as the most recent one is likely the one being confirmed
            val index = messageCache.indexOfLast { 
                it.status == dev.xacnio.kciktv.shared.data.model.MessageStatus.SENDING &&
                it.sender.username.equals(serverMessage.sender.username, ignoreCase = true) &&
                it.content == serverMessage.content
            }
            
            if (index != -1) {
                val oldMsg = messageCache[index]
                // Replace local draft with server message but PRESERVE original timestamp
                // to prevent the message from jumping down in the list.
                messageCache[index] = serverMessage.copy(createdAt = oldMsg.createdAt)
                submitList(ArrayList(messageCache))
                return true
            }
            return false
        }
    }

    class ChatDiffCallback : DiffUtil.ItemCallback<ChatMessage>() {
        override fun areItemsTheSame(oldItem: ChatMessage, newItem: ChatMessage): Boolean {
            return oldItem.id == newItem.id
        }

        override fun areContentsTheSame(oldItem: ChatMessage, newItem: ChatMessage): Boolean {
            // Only compare fields that can change after message creation
            // This is faster than full object comparison
            return oldItem.content == newItem.content &&
                   oldItem.status == newItem.status &&
                   oldItem.isAiModerated == newItem.isAiModerated
        }
    }

    /**
     * Custom ImageSpan to vertically center drawables with text
     */
    class CenterImageSpan(drawable: Drawable, val rightMargin: Int = 0) : ImageSpan(drawable) {
        
        override fun getSize(
            paint: android.graphics.Paint,
            text: CharSequence?,
            start: Int,
            end: Int,
            fm: android.graphics.Paint.FontMetricsInt?
        ): Int {
            val d = drawable
            var rect = d.bounds
            
            // If bounds are empty, try to use intrinsic size
            if (rect.isEmpty) {
                val w = d.intrinsicWidth.takeIf { it > 0 } ?: 66
                val h = d.intrinsicHeight.takeIf { it > 0 } ?: 66
                d.setBounds(0, 0, w, h)
                rect = d.bounds
            }
            
            if (fm != null) {
                val fmInt = paint.fontMetricsInt
                val fontHeight = fmInt.descent - fmInt.ascent
                val drawableHeight = rect.height()
                
                // Adjust line height if drawable is taller than text
                if (drawableHeight > fontHeight) {
                    val centerY = (fmInt.ascent + fmInt.descent) / 2
                    fm.ascent = centerY - drawableHeight / 2
                    fm.descent = centerY + drawableHeight / 2
                    fm.top = fm.ascent
                    fm.bottom = fm.descent
                } else {
                    fm.ascent = fmInt.ascent
                    fm.descent = fmInt.descent
                    fm.top = fmInt.top
                    fm.bottom = fmInt.bottom
                }
            }
            // Return width + margin for spacing between emotes
            return rect.right + rightMargin
        }

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
            val d = drawable
            if (d.bounds.isEmpty) return
            
            canvas.save()
            // Center the drawable between 'top' and 'bottom' of the allocated space
            val transY = top + (bottom - top) / 2 - d.bounds.height() / 2
            canvas.translate(x, transY.toFloat())
            d.draw(canvas)
            canvas.restore()
        }
    }

    class SwipeToReplyCallback(
        private val adapter: ChatAdapter,
        private val context: android.content.Context
    ) : androidx.recyclerview.widget.ItemTouchHelper.SimpleCallback(0, androidx.recyclerview.widget.ItemTouchHelper.RIGHT) {

        private val icon = androidx.core.content.ContextCompat.getDrawable(context, R.drawable.ic_reply)
        @Suppress("DEPRECATION")
        private val vibrator = context.getSystemService(android.content.Context.VIBRATOR_SERVICE) as? android.os.Vibrator

        override fun getMovementFlags(recyclerView: RecyclerView, viewHolder: RecyclerView.ViewHolder): Int {
            if (!adapter.isLoggedIn || adapter.isVodMode) return 0
            return super.getMovementFlags(recyclerView, viewHolder)
        }

        override fun onMove(
            recyclerView: RecyclerView,
            viewHolder: RecyclerView.ViewHolder,
            target: RecyclerView.ViewHolder
        ): Boolean {
            return false
        }

        override fun onSwiped(viewHolder: RecyclerView.ViewHolder, direction: Int) {
            val position = viewHolder.adapterPosition
            if (position != RecyclerView.NO_POSITION) {
                val message = adapter.currentList[position]
                adapter.onSwipeToReply(message)
                
                if (android.os.Build.VERSION.SDK_INT >= 26) {
                    vibrator?.vibrate(android.os.VibrationEffect.createOneShot(50, android.os.VibrationEffect.DEFAULT_AMPLITUDE))
                } else {
                    @Suppress("DEPRECATION")
                    vibrator?.vibrate(50)
                }

                // Restore item
                adapter.notifyItemChanged(position)
            }
        }
        
        override fun getSwipeThreshold(viewHolder: RecyclerView.ViewHolder): Float {
            return 0.15f // Minimal swipe to trigger
        }
        
        override fun getSwipeEscapeVelocity(defaultValue: Float): Float {
            return defaultValue * 5f // Prevent fling
        }

        override fun onChildDraw(
            c: android.graphics.Canvas,
            recyclerView: RecyclerView,
            viewHolder: RecyclerView.ViewHolder,
            dX: Float,
            dY: Float,
            actionState: Int,
            isCurrentlyActive: Boolean
        ) {
            val itemView = viewHolder.itemView
            val density = context.resources.displayMetrics.density
            val maxSwipeDist = 50f * density // Reduced distance (was 80f)
            
            // Limit visual movement
            val newDx = if (dX > 0) dX.coerceAtMost(maxSwipeDist) else dX
            
            if (dX > 0) {
                icon?.let {
                    // Only draw if we have some movement
                    if (newDx > 5f * density) {
                        val progress = (newDx / maxSwipeDist).coerceIn(0f, 1f)
                        
                        // Smaller Fixed Icon Size (20dp)
                        val iconSize = (20f * density).toInt()
                        
                        // Position icon on the left, vertically centered
                        val iconLeft = itemView.left + (16f * density).toInt() 
                        val iconTop = itemView.top + (itemView.height - iconSize) / 2
                        val iconRight = iconLeft + iconSize
                        val iconBottom = iconTop + iconSize
                        
                        it.setBounds(iconLeft, iconTop, iconRight, iconBottom)
                        it.setTint(android.graphics.Color.WHITE)
                        
                        // Dynamic alpha and scale
                        it.alpha = (255 * progress).toInt()
                        
                        c.save()
                        // Scale from 0.5 to 1.0
                        val scale = 0.5f + (0.5f * progress)
                        c.scale(scale, scale, (iconLeft + iconRight) / 2f, (iconTop + iconBottom) / 2f)
                        it.draw(c)
                        c.restore()
                    }
                }
            }
            
            super.onChildDraw(c, recyclerView, viewHolder, newDx, dY, actionState, isCurrentlyActive)
        }
    }
    
    override fun submitList(list: List<ChatMessage>?, commitCallback: Runnable?) {
        val oldSize = itemCount
        super.submitList(list) {
            commitCallback?.run()
            // If new messages were added, trigger callback
            if (list != null && list.size > oldSize) {
                onMessageAdded?.invoke()
            }
        }
    }
}
