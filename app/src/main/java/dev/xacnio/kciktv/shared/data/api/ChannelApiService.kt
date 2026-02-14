/**
 * File: ChannelApiService.kt
 *
 * Description: Background service handling Channel Api tasks.
 *
 * Author: Xacnio
 *
 */
package dev.xacnio.kciktv.shared.data.api

import dev.xacnio.kciktv.shared.data.model.ChannelDetailResponse
import dev.xacnio.kciktv.shared.data.model.LivestreamResponse
import dev.xacnio.kciktv.shared.data.model.FollowedChannelsResponse
import dev.xacnio.kciktv.shared.data.model.LiveStreamsResponse
import dev.xacnio.kciktv.shared.data.model.LiveStreamItem
import retrofit2.Response
import retrofit2.http.GET
import retrofit2.http.Header
import retrofit2.http.Headers
import retrofit2.http.Path
import retrofit2.http.Query
import retrofit2.http.POST
import retrofit2.http.DELETE
import retrofit2.http.PUT
import retrofit2.http.PATCH
import retrofit2.http.Body
import retrofit2.http.Url
import dev.xacnio.kciktv.shared.data.api.ChannelApiService
import dev.xacnio.kciktv.shared.data.model.ChannelLink
import dev.xacnio.kciktv.shared.data.model.ChannelUserMeResponse
import dev.xacnio.kciktv.shared.data.model.ChannelUserResponse
import dev.xacnio.kciktv.shared.data.model.ChannelVideo
import dev.xacnio.kciktv.shared.data.model.ChatIdentityResponse
import dev.xacnio.kciktv.shared.data.model.ChatRulesResponse
import dev.xacnio.kciktv.shared.data.model.ClipPlayResponse
import dev.xacnio.kciktv.shared.data.model.ClipResponse
import dev.xacnio.kciktv.shared.data.model.ListChannelVideo
import dev.xacnio.kciktv.shared.data.model.SubcategoriesResponse
import dev.xacnio.kciktv.shared.data.model.ToggleFollowResponse
import dev.xacnio.kciktv.shared.data.model.TopCategory
import dev.xacnio.kciktv.shared.data.model.ViewerCountItem

/**
 * Channel API service (kick.com)
 */
interface ChannelApiService {
    
    /**
     * Returns information for a specific channel
     */
    @Headers("Accept: application/json")
    @GET("api/v2/channels/{slug}")
    suspend fun getChannel(
        @Path("slug") slug: String,
        @Header("Authorization") token: String? = null
    ): Response<ChannelDetailResponse>
    
    /**
     * Returns live stream information for a specific channel
     */
    @Headers("Accept: application/json")
    @GET("api/v2/channels/{slug}/livestream")
    suspend fun getChannelLivestream(
        @Path("slug") slug: String
    ): Response<dev.xacnio.kciktv.shared.data.model.LivestreamResponseWrapper>

    /**
     * Returns the detailed list of followed channels (paginated)
     */
    @Headers(
        "Accept: application/json",
        "Referer: https://kick.com/following"
    )
    @GET("api/v2/channels/followed-page")
    suspend fun getFollowingLiveStreams(
        @Header("Authorization") token: String,
        @Query("cursor") cursor: Int? = null
    ): Response<FollowedChannelsResponse>

    /**
     * Returns live streams of followed channels from the v1 API (for thumbnail support)
     */
    @Headers(
        "Accept: application/json",
        "Referer: https://kick.com/following"
    )
    @GET("api/v1/user/livestreams")
    suspend fun getFollowingLiveStreamsV1(
        @Header("Authorization") token: String
    ): Response<List<LiveStreamItem>>

    /**
     * Returns emotes for a specific channel (channel emotes + global + emoji)
     */
    @Headers("Accept: application/json")
    @GET("emotes/{slug}")
    suspend fun getEmotes(
        @Path("slug") slug: String,
        @Header("Authorization") token: String?
    ): Response<List<dev.xacnio.kciktv.shared.data.model.EmoteCategory>>
    
    
    @retrofit2.http.GET
    @retrofit2.http.Streaming
    suspend fun checkUrl(@retrofit2.http.Url url: String): Response<okhttp3.ResponseBody>

    /**
     * Returns the message history of a specific chatroom (v2)
     */
    @Headers("Accept: application/json")
    @GET("api/v2/chatrooms/{chatroomId}/messages")
    suspend fun getChatMessages(
        @Path("chatroomId") chatroomId: Long,
        @Query("nextCursor") cursor: String? = null
    ): Response<dev.xacnio.kciktv.shared.data.model.ChatHistoryResponse>

    /**
     * Returns user info for a specific channel (subscription status, badges, etc.)
     */
    @Headers("Accept: application/json")
    @GET("api/v2/channels/{channelSlug}/users/{userSlug}")
    suspend fun getChannelUserInfo(
        @Path("channelSlug") channelSlug: String,
        @Path("userSlug") userSlug: String,
        @Header("Authorization") token: String? = null
    ): Response<dev.xacnio.kciktv.shared.data.model.ChannelUserResponse>

    /**
     * Returns personal relationship with a channel for the current authenticated user
     */
    @Headers("Accept: application/json")
    @GET("api/v2/channels/{slug}/me")
    suspend fun getChannelUserMe(
        @Path("slug") slug: String,
        @Header("Authorization") token: String
    ): Response<dev.xacnio.kciktv.shared.data.model.ChannelUserMeResponse>

    @Headers("Content-Type: application/json", "Accept: application/json")
    @POST("api/v2/channels/{slug}/celebrations/{celebrationId}/action")
    suspend fun postChannelCelebrationAction(
        @Path("slug") slug: String,
        @Path("celebrationId") celebrationId: String,
        @Header("Authorization") token: String,
        @Body body: okhttp3.RequestBody
    ): Response<okhttp3.ResponseBody>

    /**
     * Follows a channel
     */
    @Headers("Accept: application/json")
    @POST("api/v2/channels/{slug}/follow")
    suspend fun followChannel(
        @Path("slug") slug: String,
        @Header("Authorization") token: String
    ): Response<Void>

    /**
     * Unfollows a channel
     */
    @Headers("Accept: application/json")
    @DELETE("api/v2/channels/{slug}/follow")
    suspend fun unfollowChannel(
        @Path("slug") slug: String,
        @Header("Authorization") token: String
    ): Response<Void>


    /**
     * Returns chat identity for a specific user in a specific channel
     */
    @Headers("Accept: application/json")
    @GET("api/v2/channels/{channelId}/users/{userId}/identity")
    suspend fun getChatIdentity(
        @Path("channelId") channelId: Long,
        @Path("userId") userId: Long,
        @Header("Authorization") token: String? = null
    ): Response<dev.xacnio.kciktv.shared.data.model.ChatIdentityResponse>

    /**
     * Updates chat identity for a specific user in a specific channel
     */
    @Headers(
        "Accept: application/json",
        "Content-Type: application/json",
        "Referer: https://kick.com/",
        "Origin: https://kick.com"
    )
    @PUT("api/v2/channels/{channelId}/users/{userId}/identity")
    suspend fun updateChatIdentity(
        @Path("channelId") channelId: Long,
        @Path("userId") userId: Long,
        @Header("Authorization") token: String,
        @retrofit2.http.Body body: okhttp3.RequestBody
    ): Response<Void>

    /**
     * Returns current viewer counts for given livestream IDs
     * Example: current-viewers?ids[]=91649180
     */
    @Headers("Accept: application/json")
    @GET("current-viewers")
    suspend fun getCurrentViewers(
        @Query("ids[]") livestreamId: Long
    ): Response<List<dev.xacnio.kciktv.shared.data.model.ViewerCountItem>>

    /**
     * Removes the pinned message from a channel
     * DELETE https://kick.com/api/v2/channels/{slug}/pinned-message
     */
    @Headers(
        "Accept: application/json",
        "Referer: https://kick.com/",
        "Origin: https://kick.com"
    )
    @DELETE("api/v2/channels/{slug}/pinned-message")
    suspend fun unpinMessage(
        @Path("slug") slug: String,
        @Header("Authorization") token: String
    ): Response<Void>

    /**
     * Pins a message to a channel
     * POST https://kick.com/api/v2/channels/{slug}/pinned-message
     * Body: {"message": {...}, "duration": 1200}
     */
    @Headers(
        "Accept: application/json",
        "Content-Type: application/json",
        "Referer: https://kick.com/",
        "Origin: https://kick.com"
    )
    @POST("api/v2/channels/{slug}/pinned-message")
    suspend fun pinMessage(
        @Path("slug") slug: String,
        @Header("Authorization") token: String,
        @retrofit2.http.Body body: okhttp3.RequestBody
    ): Response<Void>

    /**
     * Deletes a chat message
     * DELETE https://kick.com/api/v2/chatrooms/{chatroomId}/messages/{messageId}
     */
    @Headers(
        "Accept: application/json",
        "Referer: https://kick.com/",
        "Origin: https://kick.com"
    )
    @DELETE("api/v2/chatrooms/{chatroomId}/messages/{messageId}")
    suspend fun deleteMessage(
        @Path("chatroomId") chatroomId: Long,
        @Path("messageId") messageId: String,
        @Header("Authorization") token: String
    ): Response<Void>

    /**
     * Timeout a user (temporary ban)
     * POST https://kick.com/api/v2/channels/{channelId}/bans
     * Body: {"banned_username": "username", "duration": 60, "permanent": false}
     */
    @Headers(
        "Accept: application/json",
        "Content-Type: application/json",
        "Referer: https://kick.com/",
        "Origin: https://kick.com"
    )
    @POST("api/v2/channels/{slug}/bans")
    suspend fun timeoutUser(
        @Path("slug") slug: String,
        @Header("Authorization") token: String,
        @retrofit2.http.Body body: okhttp3.RequestBody
    ): Response<Void>

    /**
     * Permanently ban a user
     * POST https://kick.com/api/v2/channels/{channelId}/bans
     * Body: {"banned_username": "username", "permanent": true}
     */
    @Headers(
        "Accept: application/json",
        "Content-Type: application/json",
        "Referer: https://kick.com/",
        "Origin: https://kick.com"
    )
    @POST("api/v2/channels/{slug}/bans")
    suspend fun banUser(
        @Path("slug") slug: String,
        @Header("Authorization") token: String,
        @retrofit2.http.Body body: okhttp3.RequestBody
    ): Response<Void>

    @Headers(
        "Accept: application/json",
        "Referer: https://kick.com/",
        "Origin: https://kick.com"
    )
    @DELETE("api/v2/channels/{slug}/bans/{username}")
    suspend fun unbanUser(
        @Path("slug") slug: String,
        @Path("username") username: String,
        @Header("Authorization") token: String
    ): Response<Void>

    /**
     * Returns the list of banned users for a specific channel
     */
    @Headers("Accept: application/json")
    @GET("api/v2/channels/{slug}/bans")
    suspend fun getBans(
        @Path("slug") slug: String,
        @Header("Authorization") token: String
    ): Response<List<dev.xacnio.kciktv.shared.data.model.BanItem>>

    /**
     * Updates chatroom settings (slow mode, followers only, subscribers only, emotes only)
     * PUT https://kick.com/api/v2/channels/{slug}/chatroom
     * Body examples: 
     *   {"slow_mode": true, "message_interval": 15}
     *   {"subscribers_mode": true}
     *   {"followers_mode": true, "min_duration": 10}
     *   {"emotes_mode": true}
     */
    @Headers(
        "Accept: application/json",
        "Content-Type: application/json",
        "Referer: https://kick.com/",
        "Origin: https://kick.com"
    )
    @PUT("api/v2/channels/{slug}/chatroom")
    suspend fun updateChatroomSettings(
        @Path("slug") slug: String,
        @Header("Authorization") token: String,
        @retrofit2.http.Body body: okhttp3.RequestBody
    ): Response<Void>

    @Headers(
        "Accept: application/json",
        "Content-Type: application/json",
        "Referer: https://kick.com/",
        "Origin: https://kick.com"
    )
    @retrofit2.http.PATCH("api/v2/channels/{slug}/stream-info")
    suspend fun updateChannelInfo(
        @Path("slug") slug: String,
        @Header("Authorization") token: String,
        @retrofit2.http.Body body: okhttp3.RequestBody
    ): Response<Void>

    @Headers(
        "Accept: application/json",
        "Referer: https://kick.com/",
        "Origin: https://kick.com"
    )
    @GET("api/v2/channels/{slug}/stream-info")
    suspend fun getStreamInfo(
        @Path("slug") slug: String,
        @Header("Authorization") token: String? = null
    ): Response<dev.xacnio.kciktv.shared.data.model.StreamInfoResponse>

    /**
     * Returns the message history of a specific user in a specific channel
     */
    @Headers(
        "Accept: application/json",
        "Origin: https://dashboard.kick.com",
    )
    @GET("api/v2/channels/{channelId}/users/{userId}/messages")
    suspend fun getUserChatHistory(
        @Path("channelId") channelId: Long,
        @Path("userId") userId: Long,
        @Header("Authorization") token: String,
        @Header("Referer") referer: String,
        @Query("cursor") cursor: String? = null
    ): Response<dev.xacnio.kciktv.shared.data.model.ChatHistoryResponse>

    /**
     * Executes a chat command (e.g., /clear)
     * POST https://kick.com/api/v2/channels/{slug}/chat-commands
     * Body: {"command": "clear"}
     */
    @Headers(
        "Accept: application/json",
        "Content-Type: application/json",
        "Referer: https://kick.com/",
        "Origin: https://kick.com"
    )
    @POST("api/v2/channels/{slug}/chat-commands")
    suspend fun executeChatCommand(
        @Path("slug") slug: String,
        @Header("Authorization") token: String,
        @retrofit2.http.Body body: okhttp3.RequestBody
    ): Response<Void>
    
    /**
     * Returns loyalty rewards for the channel
     */
    @Headers("Accept: application/json")
    @GET("api/v2/channels/{slug}/rewards")
    suspend fun getChannelRewards(
        @Path("slug") slug: String,
        @Query("is_enabled") isEnabled: Boolean = true
    ): Response<dev.xacnio.kciktv.shared.data.model.LoyaltyRewardsResponse>

    /**
     * Redeems a loyalty reward
     */
    @Headers(
        "Accept: application/json",
        "Content-Type: application/json"
    )
    @POST("api/v2/channels/{slug}/rewards/{rewardId}/redeem")
    suspend fun redeemReward(
        @Path("slug") slug: String,
        @Path("rewardId") rewardId: String,
        @Header("Authorization") token: String,
        @Body body: Map<String, String>
    ): Response<dev.xacnio.kciktv.shared.data.model.RedeemRewardResponse>

    /**
     * Gets user's loyalty points for the channel
     */
    @Headers("Accept: application/json")
    @GET("api/v2/channels/{slug}/points")
    suspend fun getChannelPoints(
        @Path("slug") slug: String,
        @Header("Authorization") token: String
    ): Response<dev.xacnio.kciktv.shared.data.model.ChannelPointsResponse>

    /**
     * Votes in a channel poll
     * POST https://kick.com/api/v2/channels/{slug}/polls/vote
     * Body: {"id": option_id}
     */
    @Headers(
        "Accept: application/json",
        "Content-Type: application/json",
        "Referer: https://kick.com/",
        "Origin: https://kick.com"
    )
    @POST("api/v2/channels/{slug}/polls/vote")
    suspend fun voteInPoll(
        @Path("slug") slug: String,
        @Header("Authorization") token: String,
        @Body body: Map<String, Int>
    ): Response<Void>

    /**
     * Votes in a channel prediction
     * POST https://kick.com/api/v2/channels/{slug}/predictions/vote
     * Body: {"amount": amount, "outcome_id": "id"}
     */
    @Headers(
        "Accept: application/json",
        "Content-Type: application/json",
        "Referer: https://kick.com/",
        "Origin: https://kick.com"
    )
    @POST("api/v2/channels/{slug}/predictions/vote")
    suspend fun voteInPrediction(
        @Path("slug") slug: String,
        @Header("Authorization") token: String,
        @Body body: dev.xacnio.kciktv.shared.data.model.PredictionVoteRequest
    ): Response<dev.xacnio.kciktv.shared.data.model.PredictionVoteResponse>

    /**
     * Updates current prediction state (LOCKED, RESOLVED, CANCELLED)
     * PATCH https://kick.com/api/v2/channels/{slug}/predictions/{id}
     */
    @Headers(
        "Accept: application/json",
        "Content-Type: application/json",
        "Referer: https://dashboard.kick.com/",
        "Origin: https://dashboard.kick.com"
    )
    @PATCH("api/v2/channels/{slug}/predictions/{id}")
    suspend fun updatePrediction(
        @Path("slug") slug: String,
        @Path("id") predictionId: String,
        @Header("Authorization") token: String,
        @Body body: dev.xacnio.kciktv.shared.data.model.UpdatePredictionRequest
    ): Response<Void>


    @Headers(
        "Accept: application/json",
        "Content-Type: application/json",
        "Referer: https://kick.com/",
        "Origin: https://kick.com"
    )
    @POST("api/v2/channels/{slug}/predictions")
    suspend fun createPrediction(
        @Path("slug") slug: String,
        @Header("Authorization") token: String,
        @Body body: dev.xacnio.kciktv.shared.data.model.CreatePredictionRequest
    ): Response<Void>



    @Headers("Accept: application/json")
    @GET("api/v2/channels/{slug}/predictions/latest")
    suspend fun getLatestPrediction(
        @Path("slug") slug: String,
        @Header("Authorization") token: String? = null
    ): Response<dev.xacnio.kciktv.shared.data.model.LatestPredictionResponse>

    @Headers("Accept: application/json")
    @GET("api/v2/channels/{slug}/predictions/recent")
    suspend fun getRecentPredictions(
        @Path("slug") slug: String,
        @Header("Authorization") token: String
    ): Response<dev.xacnio.kciktv.shared.data.model.RecentPredictionsResponse>

    @GET
    suspend fun getViewerToken(@Url url: String = "https://websockets.kick.com/viewer/v1/token"): Response<okhttp3.ResponseBody>

    /**
     * Creates a poll in the channel
     * POST https://kick.com/api/v2/channels/{slug}/polls
     * Body: {"duration":30,"result_display_duration":15,"options":["option1","option2"],"title":"question"}
     */
    @Headers(
        "Accept: application/json",
        "Content-Type: application/json",
        "Referer: https://kick.com/",
        "Origin: https://kick.com"
    )
    @POST("api/v2/channels/{slug}/polls")
    suspend fun createPoll(
        @Path("slug") slug: String,
        @Header("Authorization") token: String,
        @Body body: dev.xacnio.kciktv.shared.data.model.CreatePollRequest
    ): Response<Void>

    /**
     * Deletes/ends the current poll in the channel
     * DELETE https://kick.com/api/v2/channels/{slug}/polls
     */
    @Headers(
        "Accept: application/json",
        "Referer: https://kick.com/",
        "Origin: https://kick.com"
    )
    @DELETE("api/v2/channels/{slug}/polls")
    suspend fun deletePoll(
        @Path("slug") slug: String,
        @Header("Authorization") token: String
    ): Response<Void>

    @Headers("Accept: application/json")
    @GET("api/v1/categories/top")
    suspend fun getTopCategories(): Response<List<dev.xacnio.kciktv.shared.data.model.TopCategory>>

    @Headers("Accept: application/json")
    @GET("api/v1/subcategories")
    suspend fun getSubcategories(
        @Query("limit") limit: Int = 32,
        @Query("page") page: Int = 1
    ): Response<dev.xacnio.kciktv.shared.data.model.SubcategoriesResponse>

    @Headers("Accept: application/json")
    @GET("api/v1/subcategories/{slug}")
    suspend fun getSubcategoryDetails(@Path("slug") slug: String): Response<dev.xacnio.kciktv.shared.data.model.TopCategory>

    @Headers("Accept: application/json")
    @GET("api/v2/channels/{slug}/videos")
    suspend fun getChannelVideos(
        @Path("slug") slug: String
    ): Response<List<dev.xacnio.kciktv.shared.data.model.ListChannelVideo>>
    @Headers(
        "Accept: application/json",
        "Content-Type: application/json",
        "Referer: https://kick.com/",
        "Origin: https://kick.com",
        "X-Kick-App-Platform: android"
    )
    @POST("api/internal/v1/livestreams/{slug}/clips")
    suspend fun createClipDraft(
        @Path("slug") slug: String,
        @Header("Authorization") token: String,
        @Body body: Map<String, String> = emptyMap() // Genellikle boş body gider
    ): Response<dev.xacnio.kciktv.shared.data.model.ClipResponse>
    
    @Headers(
        "Accept: application/json",
        "Content-Type: application/json",
        "Referer: https://kick.com/",
        "Origin: https://kick.com"
    )
    @POST("api/internal/v1/videos/{slug}/clips/{clipId}/finalize")
    suspend fun finalizeClip(
        @Path("slug") slug: String,
        @Path("clipId") clipId: String,
        @Header("Authorization") token: String,
        @Body body: dev.xacnio.kciktv.shared.data.model.FinalizeClipRequest
    ): Response<Void>

    @Headers("Accept: application/json")
    @GET("api/v2/channels/{slug}/clips")
    suspend fun getChannelClips(
        @Path("slug") slug: String,
        @Query("cursor") cursor: String? = null,
        @Query("sort") sort: String = "date", // date, view
        @Query("time") time: String = "all" // all, day, week, month
    ): Response<dev.xacnio.kciktv.shared.data.model.ChannelClipsResponse>

    @Headers("Accept: application/json")
    @GET("api/v1/channels/{slug}/links")
    suspend fun getChannelLinks(
        @Path("slug") slug: String
    ): Response<List<dev.xacnio.kciktv.shared.data.model.ChannelLink>>

    /**
     * Returns clip play details including started_at for chat sync
     */
    @Headers("Accept: application/json")
    @GET("api/v2/clips/{clipId}/play")
    suspend fun getClipPlayDetails(
        @Path("clipId") clipId: String
    ): Response<dev.xacnio.kciktv.shared.data.model.ClipPlayResponse>

    /**
     * Returns chat history from a specific time for VOD/Clip replay
     * https://web.kick.com/api/v1/chat/{channelId}/history?start_time=2026-01-16T15:06:29.688Z
     */
    @Headers("Accept: application/json")
    @GET("api/v1/chat/{channelId}/history")
    suspend fun getChatHistoryFromTime(
        @Path("channelId") channelId: Long,
        @Query("start_time") startTime: String,
        @Query("cursor") cursor: String? = null
    ): Response<dev.xacnio.kciktv.shared.data.model.ChatHistoryResponse>

    @Headers("Accept: application/json")
    @GET("api/v1/video/{id}")
    suspend fun getVideo(
        @Path("id") id: String
    ): Response<dev.xacnio.kciktv.shared.data.model.ChannelVideo>

    /**
     * Returns chat rules for a specific channel
     */
    @Headers("Accept: application/json")
    @GET("api/v2/channels/{slug}/chatroom/rules")
    suspend fun getChatRules(
        @Path("slug") slug: String
    ): Response<dev.xacnio.kciktv.shared.data.model.ChatRulesResponse>

    /**
     * Returns browse clips list (global clips feed)
     * GET https://kick.com/api/v2/clips
     * Params: sort (date|view), time (day|week|month|all), cursor
     */
    @Headers("Accept: application/json")
    @GET("api/v2/clips")
    suspend fun getBrowseClips(
        @Query("sort") sort: String = "date",
        @Query("time") time: String = "all",
        @Query("cursor") cursor: String? = null
    ): Response<dev.xacnio.kciktv.shared.data.model.BrowseClipsResponse>

    /**
     * Returns clips for a specific category
     * GET https://kick.com/api/v2/categories/{slug}/clips
     */
    @Headers("Accept: application/json")
    @GET("api/v2/categories/{slug}/clips")
    suspend fun getCategoryClips(
        @Path("slug") slug: String,
        @Query("sort") sort: String = "view",
        @Query("time") time: String = "day",
        @Query("cursor") cursor: String? = null
    ): Response<dev.xacnio.kciktv.shared.data.model.BrowseClipsResponse>
    @Headers("Accept: application/json")
    @GET("api/v2/clips/{clipId}/download")
    suspend fun getClipDownload(
        @Path("clipId") clipId: String,
        @Header("Authorization") token: String? = null
    ): Response<dev.xacnio.kciktv.shared.data.model.ClipDownloadResponse>

    /**
     * Toggles follow status for a category
     */
    @Headers("Accept: application/json")
    @POST("api/v1/subcategories/{slug}/toggle-follow")
    suspend fun toggleCategoryFollow(
        @Path("slug") slug: String,
        @Header("Authorization") token: String
    ): Response<dev.xacnio.kciktv.shared.data.model.ToggleFollowResponse>

    /**
     * Returns top followed categories for the user
     */
    @Headers("Accept: application/json")
    @GET("api/v1/user/categories/top")
    suspend fun getFollowedCategories(
        @Header("Authorization") token: String
    ): Response<List<dev.xacnio.kciktv.shared.data.model.TopCategory>>
}
