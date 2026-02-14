/**
 * File: ViewerCountItem.kt
 *
 * Description: Implementation of Viewer Count Item functionality.
 *
 * Author: Xacnio
 *
 */
package dev.xacnio.kciktv.shared.data.model

import com.google.gson.annotations.SerializedName
import dev.xacnio.kciktv.shared.data.model.ViewerCountItem

/**
 * Response item from current-viewers API
 */
data class ViewerCountItem(
    @SerializedName("id")
    val id: Long,
    
    @SerializedName("viewers")
    val viewers: Int
)
