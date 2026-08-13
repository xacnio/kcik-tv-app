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

    /**
     * Hosts eligible for proxying at all. [checkUrl] (link-preview fetches) can hit arbitrary
     * external domains through this same client via an absolute @Url override — those must never
     * be routed through the user's proxy, so the host is checked in addition to the path.
     */
    private val PROXY_ELIGIBLE_HOSTS = setOf("kick.com", "web.kick.com", "search.kick.com")

    /**
     * Path prefixes Kick is known to geo-block per channel/video. This is the default
     * ([ProxyConfig.SCOPE_CRITICAL]) proxy scope — everything else (categories, clips feed,
     * chatroom messages, etc.) goes direct even when the proxy is enabled.
     */
    private val CRITICAL_PROXY_PATH_PREFIXES = listOf(
        "/api/v2/channels/",
        "/api/v1/channels/",
        "/api/v1/video/"
    )

    /**
     * Resolves the live [ProxyConfig] on every connection attempt (rather than baking a proxy
     * into the client at build time) so toggling the proxy in Settings takes effect immediately,
     * without rebuilding Retrofit or restarting the app.
     */
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
        // The JDK's built-in SOCKS5 client asks the process-wide default Authenticator for
        // credentials when the proxy demands username/password auth. Scoped to requests whose
        // host/port match the configured proxy, so it never affects unrelated connections.
        java.net.Authenticator.setDefault(object : java.net.Authenticator() {
            override fun getPasswordAuthentication(): java.net.PasswordAuthentication? {
                val user = ProxyConfig.username
                if (requestorType != RequestorType.PROXY || user.isNullOrBlank()) return null
                if (!requestingHost.equals(ProxyConfig.host, ignoreCase = true) || requestingPort != ProxyConfig.port) return null
                return java.net.PasswordAuthentication(user, (ProxyConfig.password ?: "").toCharArray())
            }
        })
    }

    /**
     * Same pipeline as [okHttpClient], but every connection it opens is dialed through
     * [ProxyConfig] (when configured). Never used directly by Retrofit — only reached through
     * [proxiedOkHttpClient]'s routing interceptor for requests that should actually be proxied.
     */
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

    /**
     * Per-request client switch used by web/main/search Retrofit instances.
     *
     * OkHttp picks a proxy once per *host*, not per request: [okhttp3.Address] (the connection
     * pool/route-selection key) only encodes scheme+host+port, and the [java.net.ProxySelector]
     * callback is invoked with that host-only URL — it never sees the request path. A single
     * OkHttpClient therefore cannot proxy some paths on a host and not others. Two fully separate
     * clients ([okHttpClient] direct, [proxyBackendClient] always-proxied) with their own
     * connection pools, plus this outer interceptor deciding per request which one actually
     * performs the call, is what makes path-scoped proxying ([ProxyConfig.SCOPE_CRITICAL])
     * possible at all.
     */
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

    /**
     * Re-reads [ProxyConfig] from prefs and evicts pooled connections so the change takes
     * effect on the very next request. Without this, [proxyBackendClient]'s connection pool can
     * keep reusing an already-open socket established under the previous proxy config — OkHttp's
     * pool matches by [okhttp3.Address], which does not encode which proxy a pooled connection
     * was actually established through, so a stale connection stays eligible for reuse until it's
     * naturally evicted.
     *
     * Callers include Activity.onCreate() and UI click listeners, i.e. the main thread.
     * [okhttp3.ConnectionPool.evictAll] closes sockets synchronously (SSL close_notify is a
     * network write), which throws [android.os.NetworkOnMainThreadException] there — so the
     * actual eviction is dispatched onto OkHttp's own background executor.
     */
    fun refreshProxy(prefs: dev.xacnio.kciktv.shared.data.prefs.AppPreferences) {
        ProxyConfig.syncFrom(prefs)
        proxyBackendClient.dispatcher.executorService.execute {
            proxyBackendClient.connectionPool.evictAll()
        }
    }

    /**
     * Builds a short-lived client for connectivity testing through an explicit proxy,
     * independent of [ProxyConfig.enabled] so a proxy can be verified before turning it on.
     */
    fun buildProxyTestClient(proxy: java.net.Proxy): OkHttpClient {
        return getUnsafeOkHttpClientBuilder()
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
