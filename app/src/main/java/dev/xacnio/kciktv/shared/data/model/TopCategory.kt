/**
 * File: TopCategory.kt
 *
 * Description: Implementation of Top Category functionality.
 *
 * Author: Xacnio
 *
 */
package dev.xacnio.kciktv.shared.data.model

import com.google.gson.annotations.SerializedName
import dev.xacnio.kciktv.shared.data.model.TopCategory

data class TopCategory(
    @SerializedName("id") val id: Long,
    @SerializedName("category_id") val categoryId: Long,
    @SerializedName("name") val name: String,
    @SerializedName("slug") val slug: String,
    @SerializedName("tags") val tags: List<String>?,
    @SerializedName("description") val description: String?,
    @SerializedName("viewers") val viewers: Int,
    @SerializedName("followers_count") val followersCount: Int?,
    @SerializedName("banner") val banner: CategoryBanner?,
    @SerializedName("category") val parentCategory: ParentCategory?
)

data class CategoryBanner(
    @SerializedName("src", alternate = ["url"]) val src: String,
    @SerializedName("srcset", alternate = ["responsive"]) val srcSet: String?
)

data class ParentCategory(
    @SerializedName("id") val id: Long,
    @SerializedName("name") val name: String,
    @SerializedName("slug") val slug: String,
    @SerializedName("icon") val icon: String?
)
