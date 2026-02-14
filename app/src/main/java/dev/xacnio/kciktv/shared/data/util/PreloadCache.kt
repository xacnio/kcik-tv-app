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
     * Clear cache after consumption to ensure fresh data on next refresh.
     */
    fun clear() {
        categories = null
        featuredStreams = null
        followingStreams = null
        isAuthValid = null
    }
}
