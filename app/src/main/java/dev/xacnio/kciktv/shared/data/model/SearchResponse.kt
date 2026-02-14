/**
 * File: SearchResponse.kt
 *
 * Description: Implementation of Search Response functionality.
 *
 * Author: Xacnio
 *
 */
package dev.xacnio.kciktv.shared.data.model

import com.google.gson.annotations.SerializedName

/**
 * Multi-search response wrapper
 */
data class MultiSearchResponse(
    @SerializedName("results") val results: List<SearchResult>
)

data class SearchResult(
    @SerializedName("hits") val hits: List<SearchHit>,
    @SerializedName("found") val found: Int,
    @SerializedName("request_params") val requestParams: RequestParams
)

data class RequestParams(
    @SerializedName("collection_name") val collectionName: String,
    @SerializedName("per_page") val perPage: Int,
    @SerializedName("q") val query: String
)

data class SearchHit(
    @SerializedName("document") val document: SearchDocument
)

/**
 * Universal search document that can represent channel, category, or tag
 */
data class SearchDocument(
    // Common
    @SerializedName("id") val id: String,
    @SerializedName("slug") val slug: String? = null,
    
    // Channel specific
    @SerializedName("username") val username: String? = null,
    @SerializedName("is_live") val isLive: Boolean = false,
    @SerializedName("is_banned") val isBanned: Boolean = false,
    @SerializedName("verified") val verified: Boolean = false,
    @SerializedName("followers_count") val followersCount: Int = 0,
    @SerializedName(value = "profile_pic", alternate = ["profile_picture", "profilepic"]) val profilePic: String? = null,
    
    // Category specific
    @SerializedName("name") val name: String? = null,
    @SerializedName("description") val description: String? = null,
    @SerializedName("parent") val parent: String? = null,
    @SerializedName("src") val src: String? = null,
    @SerializedName("srcset") val srcset: String? = null,
    @SerializedName("is_mature") val isMature: Boolean = false,
    
    // Tag specific
    @SerializedName("label") val label: String? = null
)

/**
 * Simplified search result item for UI
 */
sealed class SearchResultItem {
    data class ChannelResult(
        val id: String,
        val slug: String,
        val username: String,
        val isLive: Boolean,
        val verified: Boolean,
        val followersCount: Int,
        val profilePic: String? = null
    ) : SearchResultItem()
    
    data class CategoryResult(
        val id: String,
        val slug: String,
        val name: String,
        val imageUrl: String?,
        val parent: String?
    ) : SearchResultItem()
    
    data class TagResult(
        val id: String,
        val label: String
    ) : SearchResultItem()
}
