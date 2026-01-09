package dev.xacnio.kciktv.data.api

import dev.xacnio.kciktv.data.model.ChannelDetailResponse
import dev.xacnio.kciktv.data.model.LivestreamResponse
import dev.xacnio.kciktv.data.model.FollowedChannelsResponse
import dev.xacnio.kciktv.data.model.LiveStreamsResponse
import dev.xacnio.kciktv.data.model.LiveStreamItem
import retrofit2.Response
import retrofit2.http.GET
import retrofit2.http.Header
import retrofit2.http.Headers
import retrofit2.http.Path
import retrofit2.http.Query

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
        @Path("slug") slug: String
    ): Response<ChannelDetailResponse>
    
    /**
     * Returns live stream information for a specific channel
     */
    @Headers("Accept: application/json")
    @GET("api/v2/channels/{slug}/livestream")
    suspend fun getChannelLivestream(
        @Path("slug") slug: String
    ): Response<LivestreamResponse>

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
}
