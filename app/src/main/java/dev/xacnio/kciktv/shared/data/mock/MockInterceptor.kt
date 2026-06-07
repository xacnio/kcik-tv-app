package dev.xacnio.kciktv.shared.data.mock

import com.google.gson.Gson
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody

/**
 * OkHttp interceptor that returns synthetic responses when MockConfig.enabled is true.
 * Placed at the front of the interceptor chain so no real network call is made.
 * Must be added as the FIRST interceptor in RetrofitClient.okHttpClient.
 */
class MockInterceptor : Interceptor {

    private val gson = Gson()
    private val json = "application/json; charset=utf-8".toMediaType()

    override fun intercept(chain: Interceptor.Chain): Response {
        if (!MockConfig.enabled) return chain.proceed(chain.request())

        val request = chain.request()
        val url = request.url
        val host = url.host          // e.g. "kick.com", "web.kick.com", "search.kick.com"
        val path = url.encodedPath   // e.g. "/api/v1/livestreams"
        val method = request.method

        // Let non-Kick hosts through (pravatar, picsum, etc.)
        if (!host.contains("kick.com") && !host.contains("github.com")) {
            return chain.proceed(request)
        }

        val body: String = when {

            // ---- web.kick.com ----

            isWebKick(host) && path == "/api/v1/livestreams" ->
                gson.toJson(MockDataFactory.buildLiveStreamsResponse())

            isWebKick(host) && path.matches(Regex(".*/api/v1/chat/.*/history")) ->
                chatroomIdFromPath(path)?.let { id ->
                    gson.toJson(MockDataFactory.buildChatHistory(id))
                } ?: emptyOk()

            isWebKick(host) && path.matches(Regex(".*/api/v1/channels/.*/chat/settings")) ->
                chatroomIdFromPath(path)?.let { id ->
                    gson.toJson(MockDataFactory.buildChatSettingsResponse(id))
                } ?: emptyOk()

            isWebKick(host) && path.contains("/api/v1/kicks/") && path.contains("/pinned-gifts") ->
                """{"data":{"pinned_gifts":[]}}"""

            isWebKick(host) && path.contains("/api/v2/kicks/gifts") ->
                """{"data":[]}"""

            // ---- kick.com ----

            isMainKick(host) && path == "/api/v2/channels/followed-page" ->
                gson.toJson(MockDataFactory.buildFollowedChannelsResponse())

            isMainKick(host) && path == "/api/v1/user/livestreams" ->
                gson.toJson(MockDataFactory.buildFollowingV1())

            isMainKick(host) && path.matches(Regex("/api/v2/channels/.+/livestream")) ->
                slugFromPath(path, after = "channels/", stripSuffix = "/livestream").let { slug ->
                    gson.toJson(MockDataFactory.buildLivestreamWrapper(slug))
                }

            isMainKick(host) && path.matches(Regex("/api/v2/channels/.+/me")) ->
                """{"is_subscribed":false,"is_following":false,"is_banned":false}"""

            isMainKick(host) && path.matches(Regex("/api/v2/channels/.+/users/.+")) ->
                """{"is_subscribed":false,"is_banned":false,"is_following":false,"badges":[]}"""

            isMainKick(host) && path.matches(Regex("/api/v2/channels/.+/rewards")) &&
                    method == "GET" ->
                MockDataFactory.buildLoyaltyRewardsJson()

            isMainKick(host) && path.matches(Regex("/api/v2/channels/.+/points")) ->
                MockDataFactory.buildChannelPointsJson()

            isMainKick(host) && path.matches(Regex("/api/v1/channels/.+/links")) ->
                MockDataFactory.buildChannelLinksJson()

            isMainKick(host) && path.matches(Regex("/api/v2/channels/.+/chatroom/rules")) ->
                MockDataFactory.buildChatRulesJson()

            isMainKick(host) && path.matches(Regex("/api/v2/channels/.+/videos")) ->
                MockDataFactory.buildVideosJson()

            isMainKick(host) && path.matches(Regex("/api/v2/channels/.+/clips")) ->
                MockDataFactory.buildClipsJson()

            isMainKick(host) && path.matches(Regex("/api/v2/clips.*")) ->
                MockDataFactory.buildClipsJson()

            isMainKick(host) && path.matches(Regex("/api/v2/chatrooms/.+/messages")) ->
                chatroomIdFromPath(path)?.let { id ->
                    gson.toJson(MockDataFactory.buildChatHistory(id))
                } ?: emptyOk()

            isMainKick(host) && path == "/api/v2/messages/send" ||
            (isMainKick(host) && path.matches(Regex("/api/v2/messages/send/.*"))) ->
                MockDataFactory.buildSentMessageJson("test", 99999L, "MockUser")

            isMainKick(host) && path == "/api/v1/user" ->
                gson.toJson(MockDataFactory.buildUserResponse())

            isMainKick(host) && path.matches(Regex("/current-viewers.*")) -> {
                val id = url.queryParameter("ids[]")?.toLongOrNull() ?: 1L
                MockDataFactory.buildCurrentViewers(id)
            }

            isMainKick(host) && path.matches(Regex("/api/v2/channels/.+/stream-info")) ->
                slugFromPath(path, after = "channels/", stripSuffix = "/stream-info").let { slug ->
                    MockDataFactory.buildStreamInfoJson(slug)
                }

            isMainKick(host) && path.matches(Regex("/api/v1/chat/.+/history")) ->
                chatroomIdFromPath(path)?.let { id ->
                    gson.toJson(MockDataFactory.buildChatHistory(id))
                } ?: emptyOk()

            // Channel detail — must be last among channel/* patterns (catch-all)
            isMainKick(host) && path.matches(Regex("/api/v2/channels/[^/]+")) ->
                slugFromPath(path, after = "channels/").let { slug ->
                    gson.toJson(MockDataFactory.buildChannelDetailResponse(slug))
                }

            // Mock channels have no real emotes, redirect to a fixed channel
            isMainKick(host) && path.startsWith("/emotes/") -> {
                val newUrl = url.newBuilder()
                    .encodedPath("/emotes/eddie")
                    .build()
                return chain.proceed(request.newBuilder().url(newUrl).build())
            }

            // Auth/pusher
            isMainKick(host) && path.contains("/pusher/auth") ->
                """{"auth":"mock:mock"}"""

            isMainKick(host) && path.contains("/toggle-follow") ->
                """{"is_following":true}"""

            isMainKick(host) && path.contains("/api/v2/channels/") ->
                emptyOk() // any other channel sub-resource (bans, polls, predictions…)

            // Websocket viewer token
            host.contains("websockets.kick.com") ->
                """{"token":"mock_viewer_token"}"""

            // ---- api.github.com ----

            host.contains("github.com") ->
                MockDataFactory.buildGithubReleases()

            else -> return chain.proceed(request)
        }

        return Response.Builder()
            .request(request)
            .protocol(Protocol.HTTP_1_1)
            .code(200)
            .message("OK")
            .body(body.toResponseBody(json))
            .build()
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    private fun isWebKick(host: String) = host == "web.kick.com"
    private fun isMainKick(host: String) = host == "kick.com"

    private fun emptyOk() = """{"data":null,"message":"Success"}"""

    /** Extracts a numeric segment from paths like /api/v1/chat/12345/history */
    private fun chatroomIdFromPath(path: String): Long? {
        return Regex("/([0-9]+)/").find(path)?.groupValues?.getOrNull(1)?.toLongOrNull()
    }

    /**
     * Extracts a slug from a path segment.
     * E.g. path="/api/v2/channels/my-channel/livestream", after="channels/", stripSuffix="/livestream"
     * → "my-channel"
     */
    private fun slugFromPath(
        path: String,
        after: String,
        stripSuffix: String? = null
    ): String {
        val idx = path.indexOf(after)
        if (idx < 0) return "unknown"
        var slug = path.substring(idx + after.length)
        if (stripSuffix != null) slug = slug.removeSuffix(stripSuffix)
        // Remove any further path segments
        val slashIdx = slug.indexOf('/')
        if (slashIdx > 0) slug = slug.substring(0, slashIdx)
        return slug.ifBlank { "unknown" }
    }
}
