/**
 * File: CardImageUrl.kt
 *
 * Description: Builds resized CDN urls for collectible card art.
 *
 * Author: Xacnio
 *
 */
package dev.xacnio.kciktv.shared.util

object CardImageUrl {

    /** Card art is served at 128x169; anything wider than that is upscaling for nothing. */
    const val NATIVE_WIDTH = 128
    const val NATIVE_HEIGHT = 169

    /**
     * Appends the CDN's inline resize params. Cards are fetched hundreds at a time on the
     * collectibles grid, so serving them as small webp rather than full-size png matters.
     * Urls that already carry a query string are left alone.
     */
    fun sized(url: String, width: Int = NATIVE_WIDTH, quality: Int = 75): String =
        if (url.isEmpty() || url.contains('?')) url
        else "$url?width=$width,format=webp,quality=$quality"
}
