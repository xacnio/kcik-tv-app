/**
 * File: EmoteManager.kt
 *
 * Description: Manages business logic, state, and UI interactions for Emote.
 *
 * Author: Xacnio
 *
 */
package dev.xacnio.kciktv.shared.ui.utils

import android.content.Context
import android.graphics.*
import android.graphics.drawable.Animatable
import android.graphics.drawable.Drawable
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.View
import com.bumptech.glide.Glide
import com.bumptech.glide.load.engine.DiskCacheStrategy
import com.bumptech.glide.load.resource.gif.GifDrawable
import com.bumptech.glide.request.target.CustomTarget
import com.bumptech.glide.request.target.Target
import com.bumptech.glide.request.transition.Transition
import com.bumptech.glide.signature.ObjectKey
import java.lang.ref.WeakReference
import java.util.Collections
import java.util.WeakHashMap
import dev.xacnio.kciktv.shared.ui.adapter.ChatAdapter
import dev.xacnio.kciktv.shared.util.Constants

/**
 * Ultimate emote synchronization manager (Buffer Guard V6).
 * Fixes frozen emotes in TextViews by forcing view-level invalidation.
 */
object EmoteManager {
    private const val EMOTE_BASE_URL = "https://files.kick.com/emotes/"
    private val EMOTE_PATTERN = java.util.regex.Pattern.compile("\\[emote:(\\d+):([^\\]]+)\\]")
    
    // Minimum interval between frame captures (16ms ≈ 60fps cap)
    private const val MIN_FRAME_INTERVAL_MS = 16L
    // Batch invalidation interval
    private const val INVALIDATION_BATCH_MS = 16L
    
    private val emoteEntries = mutableMapOf<String, EmoteEntry>()
    private val activeTargets = mutableMapOf<String, Target<*>>()
    
    // Pending callbacks for emotes that are currently loading (original implementation for loadSynchronizedImage)
    private val pendingCallbacks = mutableMapOf<String, MutableList<Pair<WeakReference<View>, (Drawable) -> Unit>>>()
    
    // Shared pending callbacks for synchronized emotes (receives EmoteEntry instead of Drawable)
    private val sharedPendingCallbacks = mutableMapOf<String, MutableList<Pair<WeakReference<View>, (EmoteEntry) -> Unit>>>()

    // Batched view invalidation — collects views and flushes every INVALIDATION_BATCH_MS
    private val viewsToInvalidate = Collections.synchronizedSet(mutableSetOf<View>())
    private val invalidationHandler = Handler(Looper.getMainLooper())
    @Volatile private var invalidationScheduled = false
    private val invalidationRunnable = Runnable {
        invalidationScheduled = false
        synchronized(viewsToInvalidate) {
            val iterator = viewsToInvalidate.iterator()
            while (iterator.hasNext()) {
                iterator.next().invalidate()
                iterator.remove()
            }
        }
    }

    private fun requestViewInvalidation(view: View) {
        viewsToInvalidate.add(view)
        if (!invalidationScheduled) {
            invalidationScheduled = true
            invalidationHandler.postDelayed(invalidationRunnable, INVALIDATION_BATCH_MS)
        }
    }

    internal class EmoteEntry(val id: String, val master: Drawable, val size: Int) {
        val buffer: Bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        private val bufferCanvas = Canvas(buffer)
        // Changed to Drawable to support both ProxyDrawable and ScalingProxyDrawable
        val viewers = Collections.synchronizedSet(Collections.newSetFromMap(WeakHashMap<Drawable, Boolean>()))
        private val mainHandler = Handler(Looper.getMainLooper())
        @Volatile private var lastCaptureTime: Long = -1
        // Track last invalidation dispatch time for throttling
        @Volatile private var lastInvalidationTime: Long = 0

        private val callback = object : Drawable.Callback {
            override fun invalidateDrawable(who: Drawable) {
                // Throttle invalidation dispatches to avoid spamming views every GIF frame
                val now = SystemClock.uptimeMillis()
                if (now - lastInvalidationTime < MIN_FRAME_INTERVAL_MS) return
                lastInvalidationTime = now
                
                // Iterate with pruning: remove dead/detached viewers in the same pass
                // Avoids toTypedArray() copy and keeps viewer set small
                synchronized(viewers) {
                    val iterator = viewers.iterator()
                    while (iterator.hasNext()) {
                        val proxy = iterator.next()
                        if (proxy == null) {
                            iterator.remove()
                        } else {
                            val v = when (proxy) {
                                is ScalingProxyDrawable -> proxy.viewRef.get()
                                is ProxyDrawable -> proxy.viewRef.get()
                                else -> null
                            }
                            if (v == null || !v.isAttachedToWindow) {
                                iterator.remove()
                            } else {
                                when (proxy) {
                                    is ScalingProxyDrawable -> proxy.triggerInvalidation()
                                    is ProxyDrawable -> proxy.triggerInvalidation()
                                }
                            }
                        }
                    }
                }
            }

            override fun scheduleDrawable(who: Drawable, what: Runnable, `when`: Long) {
                mainHandler.removeCallbacks(what)
                val delay = Math.max(0L, `when` - SystemClock.uptimeMillis())
                // Enforce minimum frame delay for smooth pacing
                val clampedDelay = Math.max(delay, MIN_FRAME_INTERVAL_MS)
                mainHandler.postDelayed(what, clampedDelay)
            }

            override fun unscheduleDrawable(who: Drawable, what: Runnable) {
                mainHandler.removeCallbacks(what)
            }
        }

        init {
            master.callback = callback
            master.setBounds(0, 0, size, size)
            if (master is Animatable) {
                if (master is GifDrawable) {
                    master.setLoopCount(GifDrawable.LOOP_FOREVER)
                }
                master.start()
            }
        }

        fun captureFrame() {
            val currentTime = SystemClock.uptimeMillis()
            // Only re-capture if at least MIN_FRAME_INTERVAL_MS has passed
            if (currentTime - lastCaptureTime < MIN_FRAME_INTERVAL_MS) return
            lastCaptureTime = currentTime
            try {
                buffer.eraseColor(Color.TRANSPARENT)
                master.draw(bufferCanvas)
            } catch (e: Exception) {
                // This handles cases like "Buffer not large enough for pixels" if it throws
                // or other rendering artifacts on specific Android versions
                android.util.Log.e("EmoteManager", "Error capturing frame for $id: ${e.message}")
            }
        }
    }

    internal class ProxyDrawable(private val entry: EmoteEntry, view: View) : Drawable() {
        private val paint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)
        internal val viewRef = WeakReference(view)

        fun triggerInvalidation() {
            val view = viewRef.get() ?: return
            if (!view.isAttachedToWindow) return
            requestViewInvalidation(view)
            invalidateSelf()
        }

        override fun draw(canvas: Canvas) {
            entry.captureFrame()
            // We draw the pre-captured buffer which is already at entry.size
            canvas.drawBitmap(entry.buffer, 0f, 0f, paint)
        }

        override fun getIntrinsicWidth(): Int = entry.size
        override fun getIntrinsicHeight(): Int = entry.size

        override fun setAlpha(alpha: Int) {
            paint.alpha = alpha
            invalidateSelf()
        }

        override fun setColorFilter(colorFilter: ColorFilter?) {
            paint.colorFilter = colorFilter
            invalidateSelf()
        }

        @Suppress("DEPRECATION")
        override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
    }

    /**
     * ScalingProxyDrawable - Scales the shared master buffer to the target display size.
     * This allows all emote instances to share the same animation frame regardless of display size.
     */
    internal class ScalingProxyDrawable(
        private val entry: EmoteEntry, 
        view: View,
        private val targetSize: Int
    ) : Drawable() {
        private val paint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)
        internal val viewRef = WeakReference(view)
        private val srcRect = Rect(0, 0, entry.size, entry.size)
        private val dstRect = RectF(0f, 0f, targetSize.toFloat(), targetSize.toFloat())

        fun triggerInvalidation() {
            val view = viewRef.get() ?: return
            if (!view.isAttachedToWindow) return
            requestViewInvalidation(view)
            invalidateSelf()
        }

        override fun draw(canvas: Canvas) {
            entry.captureFrame()
            // Scale the master buffer to target size
            canvas.drawBitmap(entry.buffer, srcRect, dstRect, paint)
        }

        override fun getIntrinsicWidth(): Int = targetSize
        override fun getIntrinsicHeight(): Int = targetSize

        override fun setAlpha(alpha: Int) {
            paint.alpha = alpha
            invalidateSelf()
        }

        override fun setColorFilter(colorFilter: ColorFilter?) {
            paint.colorFilter = colorFilter
            invalidateSelf()
        }

        @Suppress("DEPRECATION")
        override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
    }

    /**
     * Loads an emote and returns a ProxyDrawable that shares animation state with all other instances.
     * All emotes with the same ID share the same animation frame, regardless of display size.
     */
    fun loadSynchronizedEmote(
        context: Context,
        emoteId: String,
        size: Int,
        targetView: View,
        onReady: (Drawable) -> Unit
    ) {
        // Use ONLY emoteId as key - this ensures all sizes share the same animation
        val key = emoteId
        
        // Master size for shared buffer (use largest common size for quality)
        val masterSize = 48
        
        // 1. Check if already loaded
        val entry = emoteEntries[key]
        if (entry != null) {
            val proxy = ScalingProxyDrawable(entry, targetView, size)
            entry.viewers.add(proxy)
            proxy.setBounds(0, 0, size, size)
            onReady(proxy)
            return
        }
        
        // 2. Check if currently loading - add to shared pending queue
        val pending = sharedPendingCallbacks[key]
        if (pending != null) {
            pending.add(Pair(WeakReference(targetView), { loadedEntry: EmoteEntry ->
                val proxy = ScalingProxyDrawable(loadedEntry, targetView, size)
                loadedEntry.viewers.add(proxy)
                proxy.setBounds(0, 0, size, size)
                onReady(proxy)
            }))
            return
        }
        
        // 3. Start new load
        sharedPendingCallbacks[key] = mutableListOf(Pair(WeakReference(targetView), { loadedEntry: EmoteEntry ->
            val proxy = ScalingProxyDrawable(loadedEntry, targetView, size)
            loadedEntry.viewers.add(proxy)
            proxy.setBounds(0, 0, size, size)
            onReady(proxy)
        }))

        val url = "$EMOTE_BASE_URL$emoteId/fullsize"
        loadInternalShared(context, url, key, masterSize)
    }

    fun loadSynchronizedImage(
        context: Context,
        url: String,
        size: Int,
        targetView: View,
        onReady: (Drawable) -> Unit
    ) {
        val key = "url_${url.hashCode()}_$size"
        
        // 1. Check if already loaded
        val entry = emoteEntries[key]
        if (entry != null) {
            val proxy = ProxyDrawable(entry, targetView)
            entry.viewers.add(proxy)
            proxy.setBounds(0, 0, size, size)
            onReady(proxy)
            return
        }
        
        // 2. Check if currently loading - add to pending queue
        val pending = pendingCallbacks[key]
        if (pending != null) {
            pending.add(Pair(WeakReference(targetView), onReady))
            return
        }
        
        // 3. Start new load
        pendingCallbacks[key] = mutableListOf(Pair(WeakReference(targetView), onReady))
        loadInternal(context, url, key, size, onReady)
    }

    private fun loadInternal(
        context: Context,
        url: String,
        key: String,
        size: Int,
        @Suppress("UNUSED_PARAMETER") onReady: (Drawable) -> Unit
    ) {
        val target = object : CustomTarget<Drawable>(size, size) {
            override fun onResourceReady(resource: Drawable, transition: Transition<in Drawable>?) {
                android.util.Log.d("EmoteManager", "✅ Loaded emote: $url")
                val newEntry = EmoteEntry(key, resource, size)
                emoteEntries[key] = newEntry
                
                // Notify all pending callbacks
                val callbacks = pendingCallbacks.remove(key) ?: return
                for ((viewRef, callback) in callbacks) {
                    val view = viewRef.get() ?: continue
                    val proxy = ProxyDrawable(newEntry, view)
                    newEntry.viewers.add(proxy)
                    proxy.setBounds(0, 0, size, size)
                    callback(proxy)
                }
                
                activeTargets.remove(key)
            }

            override fun onLoadCleared(placeholder: Drawable?) {}
            
            override fun onLoadFailed(errorDrawable: Drawable?) {
                android.util.Log.e("EmoteManager", "❌ Failed to load emote: $url")
                pendingCallbacks.remove(key)
                activeTargets.remove(key)
            }
        }

        activeTargets[key] = target

        val glideUrl = com.bumptech.glide.load.model.GlideUrl(
            url,
            com.bumptech.glide.load.model.LazyHeaders.Builder()
                .addHeader("User-Agent", dev.xacnio.kciktv.shared.util.Constants.USER_AGENT)
                .build()
        )

        Glide.with(context.applicationContext)
            .asDrawable()
            .load(glideUrl)
            .signature(ObjectKey("v10_sync_render_$key")) // Bumped version to v10
            .diskCacheStrategy(DiskCacheStrategy.DATA)
            .disallowHardwareConfig() // Important for BitmapShader and Span operations
            .into(target)
    }

    /**
     * Shared loading function that uses sharedPendingCallbacks.
     * Callbacks receive EmoteEntry so they can create their own sized ProxyDrawable.
     */
    private fun loadInternalShared(
        context: Context,
        url: String,
        key: String,
        masterSize: Int
    ) {
        val target = object : CustomTarget<Drawable>(masterSize, masterSize) {
            override fun onResourceReady(resource: Drawable, transition: Transition<in Drawable>?) {
                android.util.Log.d("EmoteManager", "✅ Loaded shared emote: $url")
                val newEntry = EmoteEntry(key, resource, masterSize)
                emoteEntries[key] = newEntry
                
                // Notify all shared pending callbacks with the EmoteEntry
                val callbacks = sharedPendingCallbacks.remove(key) ?: return
                for ((viewRef, callback) in callbacks) {
                    if (viewRef.get() == null) continue
                    callback(newEntry)
                }
                
                activeTargets.remove(key)
            }

            override fun onLoadCleared(placeholder: Drawable?) {}
            
            override fun onLoadFailed(errorDrawable: Drawable?) {
                android.util.Log.e("EmoteManager", "❌ Failed to load shared emote: $url")
                sharedPendingCallbacks.remove(key)
                activeTargets.remove(key)
            }
        }

        activeTargets[key] = target

        val glideUrl = com.bumptech.glide.load.model.GlideUrl(
            url,
            com.bumptech.glide.load.model.LazyHeaders.Builder()
                .addHeader("User-Agent", dev.xacnio.kciktv.shared.util.Constants.USER_AGENT)
                .build()
        )

        Glide.with(context.applicationContext)
            .asDrawable()
            .load(glideUrl)
            .signature(ObjectKey("v12_shared_sync_$key")) // New version for shared system
            .diskCacheStrategy(DiskCacheStrategy.DATA)
            .disallowHardwareConfig()
            .into(target)
    }

    fun unregisterViewer(view: View) {
        // Actively remove all proxy drawables associated with this view
        // This prevents "ghost viewers" from accumulating when RecyclerView recycles
        for (entry in emoteEntries.values) {
            synchronized(entry.viewers) {
                entry.viewers.removeAll { proxy ->
                    when (proxy) {
                        is ScalingProxyDrawable -> proxy.viewRef.get().let { it == null || it == view }
                        is ProxyDrawable -> proxy.viewRef.get().let { it == null || it == view }
                        else -> false
                    }
                }
            }
        }
    }

    /**
     * Renders text with [emote:id:name] tags into a TextView using animated spans.
     */
    /**
     * Tracks pending emote loads per TextView to batch layout updates.
     * Key = view identity hash, Value = remaining load count
     */
    private val pendingEmoteLoads = java.util.WeakHashMap<View, java.util.concurrent.atomic.AtomicInteger>()

    fun renderEmoteText(textView: android.widget.TextView, content: String, emoteSize: Int) {
        val matcher = EMOTE_PATTERN.matcher(content)
        val resultText = StringBuilder()
        val emotePlaceholders = mutableListOf<Triple<Int, String, String>>()
        var lastEnd = 0

        while (matcher.find()) {
            resultText.append(content.substring(lastEnd, matcher.start()))
            val emoteId = matcher.group(1) ?: continue
            val emoteName = matcher.group(2) ?: continue
            val actualPosition = resultText.length
            // Object Replacement Character (U+FFFC) is the standard for inline objects like spans
            resultText.append("\uFFFC")
            emotePlaceholders.add(Triple(actualPosition, emoteId, emoteName))
            lastEnd = matcher.end()
        }
        resultText.append(content.substring(lastEnd))

        val spannable = android.text.SpannableStringBuilder(resultText.toString())
        textView.setText(spannable, android.widget.TextView.BufferType.SPANNABLE)

        if (emotePlaceholders.isEmpty()) return

        // Track how many emotes are still loading so we do ONE layout refresh at the end
        val remaining = java.util.concurrent.atomic.AtomicInteger(emotePlaceholders.size)
        pendingEmoteLoads[textView] = remaining

        for ((pos, emoteId, _) in emotePlaceholders) {
            loadSynchronizedEmote(textView.context, emoteId, emoteSize, textView) { sharedDrawable ->
                try {
                    val currentText = textView.text
                    if (currentText is android.text.Spannable && pos < currentText.length) {
                        // Use the custom CenterImageSpan with margin
                        val span = try {
                           dev.xacnio.kciktv.shared.ui.adapter.ChatAdapter.CenterImageSpan(sharedDrawable, 4)
                         } catch (e: Exception) {
                           android.text.style.ImageSpan(sharedDrawable, android.text.style.ImageSpan.ALIGN_BOTTOM)
                         }
                         
                         // Remove any existing span at this position first
                         currentText.getSpans(pos, pos + 1, android.text.style.ImageSpan::class.java).forEach {
                             currentText.removeSpan(it)
                         }
                         currentText.setSpan(span, pos, pos + 1, android.text.Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
                         
                         // Only do the expensive layout refresh once when ALL emotes are loaded
                         if (remaining.decrementAndGet() <= 0) {
                             // Force layout update to solve the "top line invisible" bug
                             val fresh = android.text.SpannableStringBuilder()
                             fresh.append(currentText.toString())
                             val allSpans = currentText.getSpans(0, currentText.length, Any::class.java)
                             for (s in allSpans) {
                                 val start = currentText.getSpanStart(s)
                                 val end = currentText.getSpanEnd(s)
                                 val flags = currentText.getSpanFlags(s)
                                 
                                 // Clone CenterImageSpan to avoid caching issues
                                 val newSpan = if (s is dev.xacnio.kciktv.shared.ui.adapter.ChatAdapter.CenterImageSpan) {
                                     dev.xacnio.kciktv.shared.ui.adapter.ChatAdapter.CenterImageSpan(s.drawable, s.rightMargin)
                                 } else {
                                     s
                                 }
                                 fresh.setSpan(newSpan, start, end, flags)
                             }
                             textView.text = ""
                             textView.setText(fresh, android.widget.TextView.BufferType.SPANNABLE)
                         } else {
                             // Lightweight invalidate for intermediate loads
                             textView.invalidate()
                         }
                    } else {
                        remaining.decrementAndGet()
                    }
                } catch (e: Exception) {
                    remaining.decrementAndGet()
                    android.util.Log.e("EmoteManager", "Error applying span: ${e.message}")
                }
            }
        }
    }
}
