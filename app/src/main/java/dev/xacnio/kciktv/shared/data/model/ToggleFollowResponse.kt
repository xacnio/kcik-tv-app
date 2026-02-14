/**
 * File: ToggleFollowResponse.kt
 *
 * Description: Implementation of Toggle Follow Response functionality.
 *
 * Author: Xacnio
 *
 */
package dev.xacnio.kciktv.shared.data.model

import com.google.gson.annotations.SerializedName
import dev.xacnio.kciktv.shared.data.model.ToggleFollowResponse

data class ToggleFollowResponse(
    @SerializedName("id") val id: Long,
    @SerializedName("subcategory_id") val subcategoryId: Long,
    @SerializedName("user_id") val userId: Long,
    @SerializedName("deleted_at") val deletedAt: String?
)
