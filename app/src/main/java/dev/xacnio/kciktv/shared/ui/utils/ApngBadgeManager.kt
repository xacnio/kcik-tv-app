/**
 * File: ApngBadgeManager.kt
 *
 * Description: Manages business logic, state, and UI interactions for Apng Badge.
 *
 * Author: Xacnio
 *
 */
package dev.xacnio.kciktv.shared.ui.utils

import android.graphics.drawable.Drawable
import android.os.Handler
import android.os.Looper
import android.view.View
import java.lang.ref.WeakReference
import java.nio.ByteBuffer
import java.util.concurrent.ConcurrentHashMap

/**
 * Manager for loading and caching APNG badges.
 *
 * Caches raw bytes per URL. Each ImageView gets its own APNGDrawable instance created
 * from the cached bytes — no ProxyDrawable needed because ImageView implements
 * Drawable.Callback and handles animation scheduling directly.
 */
object ApngBadgeManager {
    private const val MAX_CACHED_ENTRIES = 64

    private val bytesCache = object : java.util.LinkedHashMap<String, ByteArray>(
        MAX_CACHED_ENTRIES, 0.75f, true
    ) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, ByteArray>?): Boolean =
            size > MAX_CACHED_ENTRIES
    }

    private val pendingLoads = ConcurrentHashMap<String, MutableList<Pair<WeakReference<View>, (Drawable?) -> Unit>>>()
    private val mainHandler = Handler(Looper.getMainLooper())

    private val client by lazy {
        okhttp3.OkHttpClient.Builder()
            .connectTimeout(15, java.util.concurrent.TimeUnit.SECONDS)
            .readTimeout(15, java.util.concurrent.TimeUnit.SECONDS)
            .build()
    }

    fun loadBadge(
        url: String,
        size: Int,
        targetView: View,
        onReady: (Drawable?) -> Unit
    ) {
        // 1. Cache hit — build a fresh drawable from cached bytes (no re-download)
        val cachedBytes = synchronized(bytesCache) { bytesCache[url] }
        if (cachedBytes != null) {
            onReady(buildDrawable(cachedBytes, size))
            return
        }

        // 2. Already loading — queue up
        synchronized(pendingLoads) {
            val pending = pendingLoads[url]
            if (pending != null) {
                pending.add(Pair(WeakReference(targetView), onReady))
                return
            }
            pendingLoads[url] = mutableListOf(Pair(WeakReference(targetView), onReady))
        }

        // 3. Download in background
        Thread {
            try {
                val request = okhttp3.Request.Builder()
                    .url(url)
                    .header("User-Agent", dev.xacnio.kciktv.shared.util.Constants.USER_AGENT)
                    .build()
                val response = client.newCall(request).execute()
                if (response.isSuccessful) {
                    val bytes = response.body?.bytes()
                    if (bytes != null) {
                        synchronized(bytesCache) { bytesCache[url] = bytes }
                        mainHandler.post {
                            val callbacks = synchronized(pendingLoads) { pendingLoads.remove(url) } ?: return@post
                            for ((viewRef, callback) in callbacks) {
                                if (viewRef.get() != null) callback(buildDrawable(bytes, size))
                            }
                        }
                        return@Thread
                    }
                }
                android.util.Log.e("ApngBadgeManager", "Response failed for $url: ${response.code}")
            } catch (e: Exception) {
                android.util.Log.e("ApngBadgeManager", "Load failed: ${e.message}", e)
            }
            mainHandler.post {
                val callbacks = synchronized(pendingLoads) { pendingLoads.remove(url) }
                callbacks?.forEach { (_, callback) -> callback(null) }
            }
        }.start()
    }

    /**
     * Creates an APNGDrawable from bytes without starting it.
     * The caller must call (drawable as Animatable).start() AFTER setImageDrawable()
     * so the ImageView is already the drawable's callback when animation begins.
     */
    private fun buildDrawable(bytes: ByteArray, size: Int): Drawable? {
        return try {
            val loader = object : com.github.penfeizhou.animation.loader.ByteBufferLoader() {
                override fun getByteBuffer(): ByteBuffer = ByteBuffer.wrap(bytes)
            }
            com.github.penfeizhou.animation.apng.APNGDrawable(loader).also { apng ->
                apng.setBounds(0, 0, size, size)
            }
        } catch (e: Exception) {
            android.util.Log.e("ApngBadgeManager", "Failed to build APNGDrawable: ${e.message}")
            null
        }
    }

    fun clearCache() {
        synchronized(bytesCache) { bytesCache.clear() }
    }
}
