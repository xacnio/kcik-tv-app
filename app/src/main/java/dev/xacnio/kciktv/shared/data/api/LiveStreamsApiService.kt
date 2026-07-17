/**
 * File: LiveStreamsApiService.kt
 *
 * Description: Background service handling Live Streams Api tasks.
 *
 * Author: Xacnio
 *
 */
package dev.xacnio.kciktv.shared.data.api

import dev.xacnio.kciktv.shared.data.model.ChatHistoryResponse
import dev.xacnio.kciktv.shared.data.model.ChallengesResponse
import dev.xacnio.kciktv.shared.data.model.ClaimChallengeResponse
import dev.xacnio.kciktv.shared.data.model.CollectiblesResponse
import dev.xacnio.kciktv.shared.data.model.LiveStreamsResponse
import dev.xacnio.kciktv.shared.data.model.UserLevelResponse
import retrofit2.Response
import retrofit2.http.GET
import retrofit2.http.Header
import retrofit2.http.POST
import retrofit2.http.Path
import retrofit2.http.Query
import dev.xacnio.kciktv.shared.data.api.LiveStreamsApiService

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
        @Query("limit") limit: Int = 100,
        @Query("category_id") categoryId: Long? = null
    ): Response<LiveStreamsResponse>
    
    /**
     * Returns the message history of a specific chatroom
     * @param startTime Optional ISO8601 time for VOD/Clip chat replay
     */
    @retrofit2.http.Headers("Accept: application/json")
    @GET("api/v1/chat/{chatroomId}/history")
    suspend fun getChatHistory(
        @Path("chatroomId") chatroomId: Long,
        @Query("cursor") cursor: String? = null,
        @Query("start_time") startTime: String? = null
    ): Response<ChatHistoryResponse>

    /**
     * Returns pinned gifts for a specific channel
     */
    @retrofit2.http.Headers("Accept: application/json")
    @GET("api/v1/kicks/{channelId}/pinned-gifts")
    suspend fun getPinnedGifts(
        @Path("channelId") channelId: Long
    ): Response<dev.xacnio.kciktv.shared.data.model.PinnedGiftsResponse>

    /**
     * Returns available gifts metadata
     */
    @retrofit2.http.Headers("Accept: application/json")
    @GET("api/v1/kicks/gifts")
    suspend fun getGifts(): Response<dev.xacnio.kciktv.shared.data.model.GiftsListResponse>

    /**
     * Returns accurate chat settings (slow mode, followers mode, emotes mode, etc.) by channel ID.
     */
    @retrofit2.http.Headers("Accept: application/json")
    @GET("api/v1/channels/{channelId}/chat/settings")
    suspend fun getChatSettings(
        @Path("channelId") channelId: Long
    ): Response<dev.xacnio.kciktv.shared.data.model.ChatSettingsResponse>

    /**
     * Returns the authenticated user's global level and XP progress.
     */
    @retrofit2.http.Headers("Accept: application/json")
    @GET("api/v1/gamification/user/level")
    suspend fun getUserLevel(
        @Header("Authorization") token: String
    ): Response<UserLevelResponse>

    /**
     * Returns the authenticated user's active gamification challenges (e.g. daily reward).
     */
    @retrofit2.http.Headers("Accept: application/json")
    @GET("api/v1/gamification/challenges")
    suspend fun getChallenges(
        @Header("Authorization") token: String
    ): Response<ChallengesResponse>

    /**
     * Claims a gamification challenge and rolls its reward. Returns the roulette reel items
     * plus the winning item, to be animated client-side.
     */
    @retrofit2.http.Headers("Accept: application/json")
    @POST("api/v1/gamification/challenges/{id}/claim")
    suspend fun claimChallenge(
        @Path("id") challengeId: String,
        @Header("Authorization") token: String
    ): Response<ClaimChallengeResponse>

    /**
     * Returns the full collectible card catalog (every emote/badge card), each flagged with
     * whether the authenticated user already owns it.
     */
    @retrofit2.http.Headers("Accept: application/json")
    @GET("api/v1/gamification/collectibles")
    suspend fun getCollectibles(
        @Header("Authorization") token: String
    ): Response<CollectiblesResponse>

}
