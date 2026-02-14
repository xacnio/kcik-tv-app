/**
 * File: ApngBadgeManager.kt
 *
 * Description: Manages business logic, state, and UI interactions for Apng Badge.
 *
 * Author: Xacnio
 *
 */
package dev.xacnio.kciktv.shared.ui.utils

import android.content.Context
import android.graphics.drawable.Drawable
import android.os.Handler
import android.os.Looper
import android.view.View
import java.io.File
import java.lang.ref.WeakReference
import java.util.concurrent.ConcurrentHashMap
import dev.xacnio.kciktv.shared.util.Constants

/**
 * Manager for loading and caching APNG badges.
 * Uses the same ProxyDrawable pattern as EmoteManager to share single APNGDrawable instance across all views.
 * This prevents OutOfMemoryError by ensuring only ONE instance per badge URL.
 */
object ApngBadgeManager {
    private val badgeCache = ConcurrentHashMap<String, EmoteManager.EmoteEntry>()
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
        val key = "apng_${url.hashCode()}_$size"
        
        // 1. Check cache - return existing instance
        badgeCache[key]?.let { entry ->
            val proxy = EmoteManager.ProxyDrawable(entry, targetView)
            entry.viewers.add(proxy)
            proxy.setBounds(0, 0, size, size)
            onReady(proxy)
            return
        }
        
        // 2. Check if loading - add to queue
        synchronized(pendingLoads) {
            val pending = pendingLoads[key]
            if (pending != null) {
                pending.add(Pair(WeakReference(targetView), onReady))
                return
            }
            pendingLoads[key] = mutableListOf(Pair(WeakReference(targetView), onReady))
        }
        
        // 3. Load APNG in background
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
                        // Use ByteBufferLoader subclass to properly implement the Loader interface from bytes
                        val loader: com.github.penfeizhou.animation.loader.Loader = object : com.github.penfeizhou.animation.loader.ByteBufferLoader() {
                            override fun getByteBuffer(): java.nio.ByteBuffer {
                                return java.nio.ByteBuffer.wrap(bytes)
                            }
                        }
                        val apngDrawable = com.github.penfeizhou.animation.apng.APNGDrawable(loader)
                        
                        android.util.Log.d("ApngBadgeManager", "APNG loaded from bytes: $url")
                        
                        // Create shared entry (same pattern as EmoteManager)
                        mainHandler.post {
                            // Verify bytes are actually an APNG or at least a valid image
                            // (BinaryLoader with APNGDrawable will try to parse it)
                            val entry = EmoteManager.EmoteEntry(key, apngDrawable, size)
                            badgeCache[key] = entry
                            
                            // Animation will be started by EmoteEntry.init
                            
                            // Notify all pending
                            val callbacks = synchronized(pendingLoads) {
                                pendingLoads.remove(key)
                            } ?: return@post
                            
                            for ((viewRef, callback) in callbacks) {
                                viewRef.get()?.let { view ->
                                    val proxy = EmoteManager.ProxyDrawable(entry, view)
                                    entry.viewers.add(proxy)
                                    proxy.setBounds(0, 0, size, size)
                                    callback(proxy)
                                }
                            }
                        }
                        return@Thread
                    }
                }
                android.util.Log.e("ApngBadgeManager", "Response unsuccessful for $url: ${response.code}")
            } catch (e: Exception) {
                android.util.Log.e("ApngBadgeManager", "Load failed: ${e.message}", e)
            }
            
            // Failed - notify callbacks with null
            mainHandler.post {
                val callbacks = synchronized(pendingLoads) {
                    pendingLoads.remove(key)
                }
                
                android.util.Log.d("ApngBadgeManager", "Load failed for $url, notifying ${callbacks?.size ?: 0} callbacks with null")
                
                callbacks?.forEach { (_, callback) ->
                    callback(null)
                }
            }
        }.start()
    }
    
    fun clearCache() {
        badgeCache.clear()
    }
}
