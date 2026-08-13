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

    /**
     * Invoked (off the main thread) when an authenticated kick.com request returns 401
     * Unauthenticated. The argument is the request's Authorization header so the handler can
     * confirm the 401 belongs to the currently active token before signing the user out.
     * Debounced to fire at most once every few seconds, so a burst of parallel 401s (home,
     * following, /me, ...) does not trigger repeated logouts.
     */
    @Volatile
    var onUnauthorized: ((authHeader: String?) -> Unit)? = null

    @Volatile
    private var lastUnauthorizedNotifyMs = 0L

    // Uses the platform's default TLS trust manager (previously trust-all, an MITM risk now that
    // requests can be routed through a user-configured proxy).
    private fun newOkHttpClientBuilder(): OkHttpClient.Builder = OkHttpClient.Builder()

    val okHttpClient = newOkHttpClientBuilder()
        .addInterceptor(dev.xacnio.kciktv.shared.data.mock.MockInterceptor())
        .addInterceptor { chain ->
            val originalRequest = chain.request()

            val request = originalRequest.newBuilder()
                .header("User-Agent", "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/143.0.0.0 Mobile Safari/537.36 OPR/94.0.0.0")
                .header("Accept", "application/json")
                .build()
            chain.proceed(request)
        }
        .addInterceptor { chain ->
            val request = chain.request()
            val response = chain.proceed(request)
            if (response.code == 401 && !dev.xacnio.kciktv.shared.data.mock.MockConfig.enabled) {
                val authHeader = request.header("Authorization")
                val host = request.url.host
                if (!authHeader.isNullOrBlank() && (host == "kick.com" || host == "web.kick.com")) {
                    val now = System.currentTimeMillis()
                    if (now - lastUnauthorizedNotifyMs > 3000L) {
                        lastUnauthorizedNotifyMs = now
                        onUnauthorized?.invoke(authHeader)
                    }
                }
            }
            response
        }
        .addInterceptor(loggingInterceptor)
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .writeTimeout(15, TimeUnit.SECONDS)
        .build()

    // checkUrl (link previews) can hit arbitrary external hosts via absolute @Url — never proxy those.
    private val PROXY_ELIGIBLE_HOSTS = setOf("kick.com", "web.kick.com", "search.kick.com")

    // Default (SCOPE_CRITICAL) proxy scope: paths Kick is known to geo-block per channel/video.
    private val CRITICAL_PROXY_PATH_PREFIXES = listOf(
        "/api/v2/channels/",
        "/api/v1/channels/",
        "/api/v1/video/"
    )

    // Resolves ProxyConfig live per connection so toggling the proxy applies without a restart.
    private val proxySelector = object : java.net.ProxySelector() {
        override fun select(uri: java.net.URI): List<java.net.Proxy> {
            if (!ProxyConfig.isConfigured) return listOf(java.net.Proxy.NO_PROXY)
            val type = if (ProxyConfig.type == ProxyConfig.TYPE_SOCKS5) {
                java.net.Proxy.Type.SOCKS
            } else {
                java.net.Proxy.Type.HTTP
            }
            val address = java.net.InetSocketAddress.createUnresolved(ProxyConfig.host, ProxyConfig.port)
            return listOf(java.net.Proxy(type, address))
        }

        override fun connectFailed(uri: java.net.URI, sa: java.net.SocketAddress, ioe: java.io.IOException) {
            // No-op: OkHttp already surfaces the failure to the caller.
        }
    }

    init {
        // JDK SOCKS5 asks the default Authenticator for creds; scoped to the configured proxy's host/port.
        java.net.Authenticator.setDefault(object : java.net.Authenticator() {
            override fun getPasswordAuthentication(): java.net.PasswordAuthentication? {
                val user = ProxyConfig.username
                if (requestorType != RequestorType.PROXY || user.isNullOrBlank()) return null
                if (!requestingHost.equals(ProxyConfig.host, ignoreCase = true) || requestingPort != ProxyConfig.port) return null
                return java.net.PasswordAuthentication(user, (ProxyConfig.password ?: "").toCharArray())
            }
        })
    }

    // Same pipeline as okHttpClient, but always dialed through ProxyConfig. Only reached via
    // proxiedOkHttpClient's routing interceptor below — never used directly by Retrofit.
    private val proxyBackendClient: OkHttpClient = okHttpClient.newBuilder()
        .proxySelector(proxySelector)
        .proxyAuthenticator { _, response ->
            val user = ProxyConfig.username
            if (ProxyConfig.type != ProxyConfig.TYPE_HTTP || user.isNullOrBlank()) return@proxyAuthenticator null
            if (response.request.header("Proxy-Authorization") != null) return@proxyAuthenticator null
            response.request.newBuilder()
                .header("Proxy-Authorization", okhttp3.Credentials.basic(user, ProxyConfig.password ?: ""))
                .build()
        }
        .build()

    // Per-request client switch: OkHttp selects a proxy per host (Address ignores path), so a
    // single client can't proxy some paths and not others. This picks proxyBackendClient vs.
    // okHttpClient per request instead, which is what makes SCOPE_CRITICAL possible.
    val proxiedOkHttpClient: OkHttpClient = OkHttpClient.Builder()
        .addInterceptor { chain ->
            val request = chain.request()
            val backend = if (shouldProxy(request.url)) proxyBackendClient else okHttpClient
            backend.newCall(request).execute()
        }
        .build()

    private fun shouldProxy(url: okhttp3.HttpUrl): Boolean {
        if (!ProxyConfig.isConfigured) return false
        if (url.host !in PROXY_ELIGIBLE_HOSTS) return false
        if (ProxyConfig.scope == ProxyConfig.SCOPE_ALL) return true
        val path = url.encodedPath
        return CRITICAL_PROXY_PATH_PREFIXES.any { path.startsWith(it) }
    }

    // Re-reads ProxyConfig and evicts pooled connections so the change applies immediately
    // (stale pooled sockets would otherwise keep reusing the old route). Eviction runs off the
    // main thread since closing a socket is a blocking network write (NetworkOnMainThreadException).
    fun refreshProxy(prefs: dev.xacnio.kciktv.shared.data.prefs.AppPreferences) {
        ProxyConfig.syncFrom(prefs)
        proxyBackendClient.dispatcher.executorService.execute {
            proxyBackendClient.connectionPool.evictAll()
        }
    }

    // Short-lived client for connectivity testing, independent of ProxyConfig.enabled so a
    // proxy can be verified before turning it on.
    fun buildProxyTestClient(proxy: java.net.Proxy): OkHttpClient {
        return newOkHttpClientBuilder()
            .proxy(proxy)
            .connectTimeout(8, TimeUnit.SECONDS)
            .readTimeout(8, TimeUnit.SECONDS)
            .build()
    }

    // Retrofit instance for web.kick.com (livestreams)
    private val webRetrofit: Retrofit = Retrofit.Builder()
        .baseUrl(WEB_BASE_URL)
        .client(proxiedOkHttpClient)
        .addConverterFactory(GsonConverterFactory.create())
        .build()

    // Retrofit instance for kick.com (channel, user etc.)
    private val mainRetrofit: Retrofit = Retrofit.Builder()
        .baseUrl(BASE_URL)
        .client(proxiedOkHttpClient)
        .addConverterFactory(GsonConverterFactory.create())
        .build()

    // Retrofit instance for GitHub API
    private val githubRetrofit: Retrofit = Retrofit.Builder()
        .baseUrl(GITHUB_BASE_URL)
        .client(okHttpClient)
        .addConverterFactory(GsonConverterFactory.create())
        .build()

    // Retrofit instance for Search API (proxied — search.kick.com is one of the three allowed hosts)
    private val searchRetrofit: Retrofit = Retrofit.Builder()
        .baseUrl(SEARCH_BASE_URL)
        .client(proxiedOkHttpClient)
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
