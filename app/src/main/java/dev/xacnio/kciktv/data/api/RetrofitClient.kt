package dev.xacnio.kciktv.data.api

import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import java.util.concurrent.TimeUnit

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

    private val okHttpClient = getUnsafeOkHttpClientBuilder()
        .addInterceptor { chain ->
            val request = chain.request().newBuilder()
                .header("User-Agent", "KcikTV-Android/1.0")
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
    
    // Livestreams API service (web.kick.com)
    val liveStreamsService: LiveStreamsApiService = webRetrofit.create(LiveStreamsApiService::class.java)
    
    // Channel and other API services (kick.com)
    val channelService: ChannelApiService = mainRetrofit.create(ChannelApiService::class.java)
    
    // Auth API service (kick.com)
    val authService: AuthApiService = mainRetrofit.create(AuthApiService::class.java)

    // GitHub API Service
    val githubService: GithubApiService = githubRetrofit.create(GithubApiService::class.java)
}
