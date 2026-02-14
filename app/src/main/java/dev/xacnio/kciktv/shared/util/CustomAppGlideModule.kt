/**
 * File: CustomAppGlideModule.kt
 *
 * Description: Implementation of Custom App Glide Module functionality.
 *
 * Author: Xacnio
 *
 */
package dev.xacnio.kciktv.shared.util

import android.content.Context
import com.bumptech.glide.Glide
import com.bumptech.glide.GlideBuilder
import com.bumptech.glide.Registry
import com.bumptech.glide.annotation.GlideModule
import com.bumptech.glide.integration.okhttp3.OkHttpUrlLoader
import com.bumptech.glide.load.engine.DiskCacheStrategy
import com.bumptech.glide.load.engine.cache.InternalCacheDiskCacheFactory
import com.bumptech.glide.load.engine.cache.LruResourceCache
import com.bumptech.glide.load.model.GlideUrl
import com.bumptech.glide.module.AppGlideModule
import com.bumptech.glide.request.RequestOptions
import com.bumptech.glide.signature.ObjectKey
import okhttp3.OkHttpClient
import java.io.InputStream
import java.util.concurrent.TimeUnit

/**
 * Thumbnail cache signature helper.
 * Creates a 5-minute time block signature for cache invalidation.
 */
object ThumbnailCacheHelper {
    private const val CACHE_DURATION_MS = 5 * 60 * 1000L // 5 minutes
    
    /**
     * Returns an ObjectKey that changes every 5 minutes.
     * Use this in Glide's .signature() to cache thumbnails for 5 minutes.
     */
    fun getCacheSignature(): ObjectKey {
        val timeBlock = System.currentTimeMillis() / CACHE_DURATION_MS
        return ObjectKey(timeBlock)
    }
}

/**
 * Custom Glide module to improve image loading reliability
 * - Faster connection timeouts for quicker retry on failure
 * - Larger memory cache for smoother scrolling
 * - Disk cache for offline support
 */
@GlideModule
class CustomAppGlideModule : AppGlideModule() {
    
    override fun applyOptions(context: Context, builder: GlideBuilder) {
        // Memory cache: 1/8 of available memory (default is 1/8, but we ensure it's set)
        val memorySize = Runtime.getRuntime().maxMemory() / 8
        builder.setMemoryCache(LruResourceCache(memorySize))
        
        // Disk cache: 250MB
        val diskCacheSize = 250L * 1024 * 1024
        builder.setDiskCache(InternalCacheDiskCacheFactory(context, "glide_cache", diskCacheSize))
        
        // Default request options
        builder.setDefaultRequestOptions(
            RequestOptions()
                .diskCacheStrategy(DiskCacheStrategy.AUTOMATIC)
                .timeout(15000) // 15 second timeout
        )
    }
    
    override fun registerComponents(context: Context, glide: Glide, registry: Registry) {
        // Use custom OkHttpClient with better timeout and retry settings
        val client = OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .writeTimeout(15, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            // Add connection pool for better reuse
            .connectionPool(okhttp3.ConnectionPool(5, 30, TimeUnit.SECONDS))
            // Add retry interceptor
            .addInterceptor { chain ->
                var request = chain.request()
                var response: okhttp3.Response? = null
                var exception: Exception? = null
                
                // Retry up to 3 times
                for (i in 0..2) {
                    try {
                        response = chain.proceed(request)
                        if (response.isSuccessful) {
                            break
                        }
                        response.close()
                    } catch (e: Exception) {
                        exception = e
                        if (i == 2) throw e
                        // Wait before retry
                        Thread.sleep((i + 1) * 500L)
                    }
                }
                
                response ?: throw exception ?: java.io.IOException("Failed to load image")
            }
            .build()
        
        registry.replace(
            GlideUrl::class.java,
            InputStream::class.java,
            OkHttpUrlLoader.Factory(client)
        )
    }
    
    override fun isManifestParsingEnabled(): Boolean = false
}
