/**
 * File: AuthApiService.kt
 *
 * Description: Background service handling Auth Api tasks.
 *
 * Author: Xacnio
 *
 */
package dev.xacnio.kciktv.shared.data.api

import com.google.gson.annotations.SerializedName
import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.POST
import retrofit2.http.Header
import dev.xacnio.kciktv.shared.data.api.AuthApiService

/**
 * Request body for login
 */
data class LoginRequest(
    @SerializedName("email") val email: String,
    @SerializedName("password") val password: String,
    @SerializedName("one_time_password") val oneTimePassword: String? = null
)

/**
 * Request body for mobile login
 */
data class MobileLoginRequest(
    @SerializedName("email") val email: String,
    @SerializedName("password") val password: String,
    @SerializedName("one_time_password") val oneTimePassword: String? = null,
    @SerializedName("isMobileRequest") val isMobileRequest: Boolean = true
)

/**
 * Login response
 */
data class LoginResponse(
    @SerializedName("token") val token: String?,
    @SerializedName("2fa_required") val twoFaRequired: Boolean?,
    @SerializedName("message") val message: String?,
    @SerializedName("errors") val errors: Map<String, List<String>>?
)

/**
 * Mobile login response
 */
data class MobileLoginResponse(
    @SerializedName("token") val token: String?,
    @SerializedName("2fa_required") val twoFaRequired: Boolean?,
    @SerializedName("message") val message: String?,
    @SerializedName("data") val data: MobileLoginData?
)

data class MobileLoginData(
    @SerializedName("token") val token: String?
)

/**
 * User info response (Updated to match latest Kick API)
 */
data class UserResponse(
    @SerializedName("id") val id: Long?,
    @SerializedName("username") val username: String?,
    @SerializedName("email") val email: String?,
    @SerializedName("bio") val bio: String?,
    @SerializedName(value = "profilepic", alternate = ["profile_pic", "profile_picture"]) val profilePic: String?,
    @SerializedName("verified") val verified: Boolean? = null,
    @SerializedName("filtered_categories") val filteredCategories: List<String>? = null,
    @SerializedName("is_2fa_setup") val is2faSetup: Boolean? = null,
    @SerializedName("is_over_18") val isOver18: Boolean? = null,
    @SerializedName("streamer_channel") val streamerChannel: StreamerChannelData?
)

data class StreamerChannelData(
    @SerializedName("id") val id: Long?,
    @SerializedName("user_id") val userId: Long?,
    @SerializedName("slug") val slug: String?,
    @SerializedName("playback_url") val playbackUrl: String?,
    @SerializedName("is_banned") val isBanned: Boolean?,
    @SerializedName("vod_enabled") val vodEnabled: Boolean?,
    @SerializedName("subscription_enabled") val subscriptionEnabled: Boolean? = null
)

/**
 * Kick Authentication API Service
 */
interface AuthApiService {
    
    /**
     * Mobile login endpoint - mimics mobile app behavior
     */
    @retrofit2.http.Headers("Accept: application/json", "X-Requested-With: com.kick.mobile")
    @POST("mobile/token")
    suspend fun mobileLogin(
        @retrofit2.http.Header("X-KPSDK-CD") kpsdkCd: String? = null,
        @retrofit2.http.Header("X-KPSDK-CT") kpsdkCt: String? = null,
        @Body request: MobileLoginRequest
    ): Response<okhttp3.ResponseBody>
    
    /**
     * Fetches user information
     */
    @retrofit2.http.Headers("Accept: application/json")
    @GET("api/v1/user")
    suspend fun getUser(
        @Header("Authorization") token: String
    ): Response<UserResponse>

    /**
     * Hits base URL for Cloudflare bypass
     */
    @GET("/")
    suspend fun getBaseUrl(): Response<okhttp3.ResponseBody>

    /**
     * Sends a chat message to a chatroom
     */
    @retrofit2.http.Headers("Accept: application/json", "Content-Type: application/json")
    @POST("api/v2/messages/send/{chatroom_id}")
    suspend fun sendChatMessage(
        @Header("Authorization") token: String,
        @retrofit2.http.Path("chatroom_id") chatroomId: Long,
        @Body request: SendMessageRequest
    ): Response<okhttp3.ResponseBody>

    /**
     * Resolves which realtime server (Centrifugo) the client should connect to
     */
    @retrofit2.http.Headers("Accept: application/json", "Content-Type: application/json")
    @POST("https://web.kick.com/api/v1/realtime/connection")
    suspend fun getRealtimeConnection(
        @Header("Authorization") token: String?,
        @Body request: RealtimeConnectionRequest
    ): Response<RealtimeConnectionResponse>

    /**
     * Gets a realtime connection token. Works for guests too (client_id = random UUID).
     */
    @retrofit2.http.Headers("Accept: application/json", "Content-Type: application/json")
    @POST("https://web.kick.com/api/v1/realtime/auth/connection")
    suspend fun getRealtimeConnectionToken(
        @Header("Authorization") token: String?,
        @Body request: RealtimeConnectionAuthRequest
    ): Response<RealtimeTokenResponse>

    /**
     * Gets a subscription token for a private realtime channel
     */
    @retrofit2.http.Headers("Accept: application/json", "Content-Type: application/json")
    @POST("https://web.kick.com/api/v1/realtime/auth/channel")
    suspend fun getRealtimeChannelToken(
        @Header("Authorization") token: String,
        @Body request: RealtimeChannelAuthRequest
    ): Response<RealtimeTokenResponse>

    /**
     * Gets user kicks balance
     */
    @retrofit2.http.Headers("Accept: application/json")
    @GET("https://web.kick.com/api/v1/kicks/balance")
    suspend fun getKicksBalance(
        @retrofit2.http.Header("Authorization") token: String
    ): Response<KicksBalanceResponse>
    /**
     * Sends a gift to a channel
     */
    @retrofit2.http.Headers("Accept: application/json")
    @POST("https://web.kick.com/api/v1/kicks/{channelId}/gift")
    suspend fun sendGift(
        @Header("Authorization") token: String,
        @retrofit2.http.Path("channelId") channelId: Long,
        @Body request: SendGiftRequest
    ): Response<SendGiftResponse>

    /**
     * Link TV Device
     */
    @retrofit2.http.Headers("Accept: application/json", "Content-Type: application/json")
    @POST("api/tv/link/setup")
    suspend fun linkTvDevice(
        @Header("Authorization") token: String,
        @Body request: TvLinkSetupRequest
    ): Response<okhttp3.ResponseBody>
}

/**
 * Realtime (Centrifugo) connection discovery models
 */
data class RealtimeConnectionRequest(
    @SerializedName("client") val client: RealtimeClient,
    @SerializedName("capabilities") val capabilities: RealtimeCapabilities
)

data class RealtimeClient(
    @SerializedName("id") val id: String,
    @SerializedName("type") val type: String = "web"
)

data class RealtimeCapabilities(
    @SerializedName("accepted_providers") val acceptedProviders: List<RealtimeProvider>
)

data class RealtimeProvider(
    @SerializedName("provider") val provider: String
)

data class RealtimeConnectionResponse(
    @SerializedName("data") val data: RealtimeConnectionData?,
    @SerializedName("message") val message: String?
)

data class RealtimeConnectionData(
    @SerializedName("connections") val connections: List<RealtimeConnectionOption>?,
    @SerializedName("mode") val mode: String?
)

data class RealtimeConnectionOption(
    @SerializedName("provider") val provider: String?,
    @SerializedName("credentials") val credentials: RealtimeCredentials?
)

data class RealtimeCredentials(
    @SerializedName("url") val url: String?
)

/**
 * Realtime (Centrifugo) token models
 */
data class RealtimeConnectionAuthRequest(
    @SerializedName("client_id") val clientId: String
)

data class RealtimeChannelAuthRequest(
    @SerializedName("channel") val channel: String
)

data class RealtimeTokenResponse(
    @SerializedName("data") val data: RealtimeTokenData?,
    @SerializedName("message") val message: String?
)

data class RealtimeTokenData(
    @SerializedName("token") val token: String?
)

/**
 * TV Link Setup Request
 */
data class TvLinkSetupRequest(
    @SerializedName("uuid") val uuid: String,
    @SerializedName("key") val key: String
)

/**
 * Request body for sending chat message
 */
data class SendMessageRequest(
    @SerializedName("content") val content: String,
    @SerializedName("type") val type: String = "message",
    @SerializedName("message_ref") val messageRef: String = System.currentTimeMillis().toString(),
    @SerializedName("metadata") val metadata: SendMessageMetadata? = null
)

data class SendMessageMetadata(
    @SerializedName("original_message") val originalMessage: OriginalMessageData?,
    @SerializedName("original_sender") val originalSender: OriginalSenderData?
)

data class OriginalMessageData(
    @SerializedName("id") val id: String,
    @SerializedName("content") val content: String
)

data class OriginalSenderData(
    @SerializedName("id") val id: Int,
    @SerializedName("username") val username: String
)

/**
 * Response for Kicks Balance
 */
data class KicksBalanceResponse(
    @SerializedName("data") val data: KicksBalanceData?,
    @SerializedName("message") val message: String?
)

data class KicksBalanceData(
    @SerializedName("balance") val balance: KicksBalanceDetails?
)

data class KicksBalanceDetails(
    @SerializedName("available") val available: Long?,
    @SerializedName("last_updated") val lastUpdated: String?,
    @SerializedName("user_id") val userId: Long?
)

/**
 * Gift Sending models
 */
data class SendGiftRequest(
    @SerializedName("gift_id") val giftId: String,
    @SerializedName("message") val message: String,
    @SerializedName("idempotency_key") val idempotencyKey: String
)

data class SendGiftResponse(
    @SerializedName("data") val data: SendGiftData?,
    @SerializedName("message") val message: String?
)

data class SendGiftData(
    @SerializedName("balance") val balance: KicksBalanceDetails?,
    @SerializedName("transaction_id") val transactionId: String?,
    @SerializedName("details") val details: String?,
    @SerializedName("type") val type: String?
)
