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
import com.bumptech.glide.load.engine.cache.MemorySizeCalculator
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
        // Read low_battery_mode_enabled directly from SharedPreferences to avoid
        // instantiating AppPreferences (and its CryptoManager) during Glide init.
        // Toggle changes take effect after app restart.
        val lowBattery = context.getSharedPreferences("kick_tv_prefs", Context.MODE_PRIVATE)
            .getBoolean("low_battery_mode_enabled", false)

        // Memory cache via MemorySizeCalculator (respects device class & low_ram).
        // Default Glide uses ~2 screens of bitmap memory; we shrink to 1.5 screens
        // normally, 1.0 screen in battery saver mode (~25-50% reduction).
        val screens = if (lowBattery) 1.0f else 1.5f
        val calculator = MemorySizeCalculator.Builder(context)
            .setMemoryCacheScreens(screens)
            .setBitmapPoolScreens(screens)
            .build()
        builder.setMemoryCache(LruResourceCache(calculator.memoryCacheSize.toLong()))

        // Disk cache: 250 MB was excessive for stream thumbnails that rotate every
        // 5 minutes (see ThumbnailCacheHelper). 100 MB default; 30 MB in battery
        // saver to minimize storage write amplification on long sessions.
        val diskCacheSize = if (lowBattery) 30L * 1024 * 1024 else 100L * 1024 * 1024
        builder.setDiskCache(InternalCacheDiskCacheFactory(context, "glide_cache", diskCacheSize))

        builder.setDefaultRequestOptions(
            RequestOptions()
                .diskCacheStrategy(DiskCacheStrategy.AUTOMATIC)
                .timeout(15000) // 15 second timeout
        )
    }
    
    override fun registerComponents(context: Context, glide: Glide, registry: Registry) {
        // OkHttp's built-in retryOnConnectionFailure already covers transient I/O errors.
        // The previous custom interceptor added Thread.sleep((i+1) * 500L) blocking retries
        // (up to 1.5s) that kept worker threads warm and burned CPU on bad networks; Glide
        // itself also retries failed requests at a higher level, producing duplicate attempts.
        val client = OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .writeTimeout(15, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            .connectionPool(okhttp3.ConnectionPool(5, 30, TimeUnit.SECONDS))
            .build()

        registry.replace(
            GlideUrl::class.java,
            InputStream::class.java,
            OkHttpUrlLoader.Factory(client)
        )
    }
    
    override fun isManifestParsingEnabled(): Boolean = false
}
