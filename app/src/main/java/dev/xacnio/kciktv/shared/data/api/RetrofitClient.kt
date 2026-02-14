/**
 * File: RetrofitClient.kt
 *
 * Description: Implementation of Retrofit Client functionality.
 *
 * Author: Xacnio
 *
 */
package dev.xacnio.kciktv.shared.data.api

import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import java.util.concurrent.TimeUnit
import dev.xacnio.kciktv.shared.data.api.AuthApiService
import dev.xacnio.kciktv.shared.data.api.BlerpApiService
import dev.xacnio.kciktv.shared.data.api.ChannelApiService
import dev.xacnio.kciktv.shared.data.api.GithubApiService
import dev.xacnio.kciktv.shared.data.api.LiveStreamsApiService
import dev.xacnio.kciktv.shared.data.api.RetrofitClient
import dev.xacnio.kciktv.shared.data.api.SearchApiService

/**
 * Retrofit client singleton - Separate instances for two different base URLs
 */
object RetrofitClient {
    
    // For Livestreams API
    private const val WEB_BASE_URL = "https://web.kick.com/"
    
    // For other APIs
    private const val BASE_URL = "https://kick.com/"
    
    // For GitHub
    private const val GITHUB_BASE_URL = "https://api.github.com/"
    
    // For Search API
    private const val SEARCH_BASE_URL = "https://search.kick.com/"
    
    // For Blerp API
    private const val BLERP_BASE_URL = "https://api.blerp.com/"
    
    private val loggingInterceptor = HttpLoggingInterceptor().apply {
        level = HttpLoggingInterceptor.Level.BODY
    }
    
    private fun getUnsafeOkHttpClientBuilder(): OkHttpClient.Builder {
        try {
            val trustAllCerts = arrayOf<javax.net.ssl.TrustManager>(object : javax.net.ssl.X509TrustManager {
                override fun checkClientTrusted(chain: Array<java.security.cert.X509Certificate>, authType: String) {}
                override fun checkServerTrusted(chain: Array<java.security.cert.X509Certificate>, authType: String) {}
                override fun getAcceptedIssuers(): Array<java.security.cert.X509Certificate> = arrayOf()
            })

            val sslContext = javax.net.ssl.SSLContext.getInstance("SSL")
            sslContext.init(null, trustAllCerts, java.security.SecureRandom())
            val sslSocketFactory = sslContext.socketFactory

            return OkHttpClient.Builder()
                .sslSocketFactory(sslSocketFactory, trustAllCerts[0] as javax.net.ssl.X509TrustManager)
                .hostnameVerifier { _, _ -> true }
        } catch (e: Exception) {
            return OkHttpClient.Builder()
        }
    }

    val okHttpClient = getUnsafeOkHttpClientBuilder()
        .addInterceptor { chain ->
            val originalRequest = chain.request()
            
            val request = originalRequest.newBuilder()
                .header("User-Agent", "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/143.0.0.0 Mobile Safari/537.36 OPR/94.0.0.0")
                .header("Accept", "application/json")
                .build()
            chain.proceed(request)
        }
        .addInterceptor(loggingInterceptor)
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .build()
    
    // Retrofit instance for web.kick.com (livestreams)
    private val webRetrofit: Retrofit = Retrofit.Builder()
        .baseUrl(WEB_BASE_URL)
        .client(okHttpClient)
        .addConverterFactory(GsonConverterFactory.create())
        .build()
    
    // Retrofit instance for kick.com (channel, user etc.)
    private val mainRetrofit: Retrofit = Retrofit.Builder()
        .baseUrl(BASE_URL)
        .client(okHttpClient)
        .addConverterFactory(GsonConverterFactory.create())
        .build()

    // Retrofit instance for GitHub API
    private val githubRetrofit: Retrofit = Retrofit.Builder()
        .baseUrl(GITHUB_BASE_URL)
        .client(okHttpClient)
        .addConverterFactory(GsonConverterFactory.create())
        .build()
    
    // Retrofit instance for Search API
    private val searchRetrofit: Retrofit = Retrofit.Builder()
        .baseUrl(SEARCH_BASE_URL)
        .client(okHttpClient)
        .addConverterFactory(GsonConverterFactory.create())
        .build()
    
    // Retrofit instance for Blerp API
    private val blerpRetrofit: Retrofit = Retrofit.Builder()
        .baseUrl(BLERP_BASE_URL)
        .client(okHttpClient)
        .addConverterFactory(GsonConverterFactory.create())
        .build()
    
    // Livestreams API service (web.kick.com)
    val liveStreamsService: LiveStreamsApiService = webRetrofit.create(LiveStreamsApiService::class.java)
    
    // Channel and other API services (kick.com)
    val channelService: ChannelApiService = mainRetrofit.create(ChannelApiService::class.java)
    
    // Auth API service (kick.com)
    val authService: AuthApiService = mainRetrofit.create(AuthApiService::class.java)

    // GitHub API Service
    val githubService: GithubApiService = githubRetrofit.create(GithubApiService::class.java)
    
    // Search API Service (search.kick.com)
    val searchService: SearchApiService = searchRetrofit.create(SearchApiService::class.java)
    
    // Blerp API Service (api.blerp.com)
    val blerpService: BlerpApiService = blerpRetrofit.create(BlerpApiService::class.java)
}
