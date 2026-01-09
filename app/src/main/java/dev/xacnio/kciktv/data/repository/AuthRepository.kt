package dev.xacnio.kciktv.data.repository

import android.util.Log
import dev.xacnio.kciktv.data.api.LoginRequest
import dev.xacnio.kciktv.data.api.MobileTokenRequest
import dev.xacnio.kciktv.data.api.RetrofitClient
import dev.xacnio.kciktv.data.api.UserResponse
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Login result
 */
sealed class LoginResult {
    data class Success(val token: String, val user: UserResponse?) : LoginResult()
    data class TwoFARequired(val message: String) : LoginResult()
    data class Error(val message: String) : LoginResult()
}

/**
 * Kick Authentication Repository
 */
class AuthRepository {
    
    private val authService = RetrofitClient.authService
    private val channelService = RetrofitClient.channelService
    
    companion object {
        private const val TAG = "AuthRepository"
    }
    
    /**
     * Logs in to Kick
     * @param email Username or email
     * @param password Password
     * @param otp OTP code (for 2FA, optional)
     */
    suspend fun login(email: String, password: String, otp: String? = null): LoginResult = withContext(Dispatchers.IO) {
        try {
            val request = MobileTokenRequest(
                email = email,
                password = password,
                oneTimePassword = otp
            )
            
            Log.d(TAG, "Attempting login for: $email")
            val response = authService.mobileToken(request)
            
            if (response.isSuccessful) {
                val token = response.body()?.string()?.filter { it.code in 33..126 } // Only printable non-space ASCII
                if (!token.isNullOrEmpty()) {
                    Log.d(TAG, "Login successful, token received: $token")
                    
                    // Token comes in userid|token format, use it directly
                    // Get user info
                    var userResponse = try {
                        val authHeader = "Bearer $token"
                        val userResult = authService.getUser(authHeader)
                        if (userResult.isSuccessful) userResult.body() else null
                    } catch (e: Exception) {
                        Log.e(TAG, "Failed to get user info: ${e.message}")
                        null
                    }

                    // Fallback: Use channel API for profile pic if needed
                    val username = userResponse?.username ?: email
                    if (userResponse?.profilePic.isNullOrEmpty() && userResponse?.profilePicture.isNullOrEmpty()) {
                        try {
                            val channelResult = channelService.getChannel(username)
                            if (channelResult.isSuccessful) {
                                val channelPic = channelResult.body()?.user?.profilePic
                                if (!channelPic.isNullOrEmpty()) {
                                    Log.d(TAG, "Fetched profile pic from channel API: $channelPic")
                                    userResponse = userResponse?.copy(profilePic = channelPic) ?: UserResponse(
                                        id = null,
                                        username = username,
                                        email = email,
                                        profilePic = channelPic,
                                        profilePicture = null,
                                        bio = null,
                                        verified = null
                                    )
                                }
                            }
                        } catch (e: Exception) {
                            Log.e(TAG, "Channel API fallback failed: ${e.message}")
                        }
                    }
                    
                    return@withContext LoginResult.Success(token, userResponse)
                } else {
                    return@withContext LoginResult.Error("Could not get token (empty response)")
                }
            } else {
                val errorBody = response.errorBody()?.string() ?: ""
                Log.e(TAG, "Login failed: ${response.code()}, $errorBody")
                
                // 401 Usually 2FA or wrong password
                if (response.code() == 401) {
                    if (errorBody.contains("2fa", ignoreCase = true) || errorBody.contains("one_time_password", ignoreCase = true)) {
                        return@withContext LoginResult.TwoFARequired("OTP verification required")
                    } else {
                        return@withContext LoginResult.Error("Invalid username or password")
                    }
                }
                
                // 422 - Validation error
                if (response.code() == 422) {
                    return@withContext LoginResult.Error("Invalid data input")
                }
                
                return@withContext LoginResult.Error("Login failed: ${response.code()}")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Login exception: ${e.message}", e)
            return@withContext LoginResult.Error("Connection error: ${e.message}")
        }
    }
}
