/**
 * File: SearchApiService.kt
 *
 * Description: Background service handling Search Api tasks.
 *
 * Author: Xacnio
 *
 */
package dev.xacnio.kciktv.shared.data.api

import dev.xacnio.kciktv.shared.data.model.MultiSearchResponse
import okhttp3.RequestBody
import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.Headers
import retrofit2.http.POST
import dev.xacnio.kciktv.shared.data.api.SearchApiService

/**
// * Search API service (search.kick.com)
 */
interface SearchApiService {
    
    /**
     * Category search endpoint (subcategory_index)
     */
    @Headers(
        "Accept: application/json",
        "x-typesense-api-key: nXIMW0iEN6sMujFYjFuhdrSwVow3pDQu"
    )
    @retrofit2.http.GET("collections/subcategory_index/documents/search")
    suspend fun searchCategories(
        @retrofit2.http.Query("q") query: String,
        @retrofit2.http.Query("query_by") queryBy: String = "name"
    ): Response<dev.xacnio.kciktv.shared.data.model.CategorySearchResponse>

    /**
     * Multi-search endpoint for channels, categories, and tags
     * Uses Kick's public TypeSense API key
     */
    @Headers(
        "Accept: application/json",
        "Content-Type: application/json",
        "x-typesense-api-key: nXIMW0iEN6sMujFYjFuhdrSwVow3pDQu"
    )
    @POST("multi_search")
    suspend fun multiSearch(
        @Body body: RequestBody
    ): Response<MultiSearchResponse>
}
