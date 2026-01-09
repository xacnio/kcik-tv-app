package dev.xacnio.kciktv.data.api

import com.google.gson.annotations.SerializedName
import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.POST
import retrofit2.http.Header

/**
 * Request body for login
 */
data class LoginRequest(
    @SerializedName("email") val email: String,
    @SerializedName("password") val password: String,
    @SerializedName("one_time_password") val oneTimePassword: String? = null
)

/**
 * Request body for mobile token
 */
data class MobileTokenRequest(
    @SerializedName("email") val email: String,
    @SerializedName("password") val password: String,
    @SerializedName("one_time_password") val oneTimePassword: String? = null,
    @SerializedName("device_name") val deviceName: String = "KickTV-Android"
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
 * Mobile token response
 */
data class MobileTokenResponse(
    @SerializedName("token") val token: String?,
    @SerializedName("2fa_required") val twoFaRequired: Boolean?,
    @SerializedName("message") val message: String?,
    @SerializedName("data") val data: MobileTokenData?
)

data class MobileTokenData(
    @SerializedName("token") val token: String?
)

/**
 * User info response
 */
data class UserResponse(
    @SerializedName("id") val id: Long?,
    @SerializedName("username") val username: String?,
    @SerializedName("email") val email: String?,
    @SerializedName("profile_pic") val profilePic: String?,
    @SerializedName("profile_picture") val profilePicture: String?,
    @SerializedName("bio") val bio: String?,
    @SerializedName("verified") val verified: Boolean?
)

/**
 * Kick Authentication API Service
 */
interface AuthApiService {
    
    /**
     * Mobile token endpoint - for Mobile apps
     */
    @POST("mobile/token")
    suspend fun mobileToken(
        @Body request: MobileTokenRequest
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
}
