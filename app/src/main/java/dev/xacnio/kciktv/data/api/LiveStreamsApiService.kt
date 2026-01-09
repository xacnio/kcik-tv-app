package dev.xacnio.kciktv.data.api

import dev.xacnio.kciktv.data.model.ChatHistoryResponse
import dev.xacnio.kciktv.data.model.LiveStreamsResponse
import retrofit2.Response
import retrofit2.http.GET
import retrofit2.http.Path
import retrofit2.http.Query

/**
 * Livestreams API service (web.kick.com)
 */
interface LiveStreamsApiService {
    
    /**
     * Returns live streams
     * @param language Language filter (e.g.: "tr", "en")
     * @param sort Sort type (e.g.: "viewer_count_desc")
     * @param after Cursor (for pagination)
     * @param limit Results per page
     */
    @retrofit2.http.Headers("Accept: application/json")
    @GET("api/v1/livestreams")
    suspend fun getLiveStreams(
        @Query("language") languages: List<String>? = null,
        @Query("sort") sort: String = "viewer_count_desc",
        @Query("after") after: String? = null,
        @Query("limit") limit: Int = 50
    ): Response<LiveStreamsResponse>
    
    @retrofit2.http.Headers("Accept: application/json")
    @GET("api/v1/livestreams")
    suspend fun getAllLiveStreams(
        @Query("sort") sort: String = "viewer_count_desc",
        @Query("after") after: String? = null,
        @Query("limit") limit: Int = 100
    ): Response<LiveStreamsResponse>

    /**
     * Returns recommended/featured streams
     */
    @retrofit2.http.Headers("Accept: application/json")
    @GET("api/v1/livestreams/featured")
    suspend fun getFeaturedStreams(
        @Query("language") languages: List<String>? = null
    ): Response<LiveStreamsResponse>
    
    /**
     * Returns the message history of a specific chatroom
     */
    @retrofit2.http.Headers("Accept: application/json")
    @GET("api/v1/chat/{chatroomId}/history")
    suspend fun getChatHistory(
        @Path("chatroomId") chatroomId: Long
    ): Response<ChatHistoryResponse>

}
