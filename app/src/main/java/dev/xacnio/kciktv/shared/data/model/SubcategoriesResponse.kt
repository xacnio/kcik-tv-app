/**
 * File: SubcategoriesResponse.kt
 *
 * Description: Implementation of Subcategories Response functionality.
 *
 * Author: Xacnio
 *
 */
package dev.xacnio.kciktv.shared.data.model

import com.google.gson.annotations.SerializedName
import dev.xacnio.kciktv.shared.data.model.SubcategoriesResponse
import dev.xacnio.kciktv.shared.data.model.TopCategory

data class SubcategoriesResponse(
    @SerializedName("current_page") val currentPage: Int,
    @SerializedName("data") val data: List<TopCategory>,
    @SerializedName("next_page_url") val nextPageUrl: String?,
    @SerializedName("prev_page_url") val prevPageUrl: String?
)
