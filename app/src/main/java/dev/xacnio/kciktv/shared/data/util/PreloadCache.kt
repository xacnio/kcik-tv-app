/**
 * File: PreloadCache.kt
 *
 * Description: Implementation of Preload Cache functionality.
 *
 * Author: Xacnio
 *
 */
package dev.xacnio.kciktv.shared.data.util

import dev.xacnio.kciktv.shared.data.model.TopCategory
import dev.xacnio.kciktv.shared.data.model.ChannelItem
import dev.xacnio.kciktv.shared.data.repository.ChannelListData

/**
 * Static cache for preloading data during splash screen.
 * This helps eliminate shimmer/loading delay when entering the main activity.
 */
object PreloadCache {
    var categories: List<TopCategory>? = null
    var featuredStreams: ChannelListData? = null
    var followingStreams: List<ChannelItem>? = null
    var isAuthValid: Boolean? = null

    /**
     * Cross-tab cache: whenever the home page fetches the followed live streams it writes
     * the full channel list here. The following tab reads it on first open to show data
     * instantly, then refreshes in the background. Unlike [followingStreams] this is NOT
     * cleared after consumption — it stays until a newer fetch overwrites it, giving the
     * following tab a warm start on every visit during the session.
     */
    @Volatile var followingStreamsForTab: List<ChannelItem>? = null

    /**
     * Clear cache after consumption to ensure fresh data on next refresh.
     */
    fun clear() {
        categories = null
        featuredStreams = null
        followingStreams = null
        isAuthValid = null
        // followingStreamsForTab is intentionally NOT cleared here — it persists for the
        // lifetime of the session so the following tab always has a warm start.
    }
}
