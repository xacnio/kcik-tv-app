/**
 * File: WebViewManager.kt
 *
 * Description: Manages a hidden WebView to perform authenticated actions.
 * It injects a JavaScript bridge ("AppBridge") to proxy API requests (Follow/Unfollow, Token Fetching, History)
 * through the WebView context, bypassing complex API protection mechanisms (KPSDK).
 *
 * Author: Xacnio
 *
 */
package dev.xacnio.kciktv.mobile.ui.player

import android.annotation.SuppressLint
import android.util.Log
import android.view.View
import android.widget.Toast
import androidx.lifecycle.lifecycleScope
import dev.xacnio.kciktv.mobile.MobilePlayerActivity
import dev.xacnio.kciktv.R
import dev.xacnio.kciktv.shared.data.prefs.AppPreferences
import kotlinx.coroutines.launch

/**
 * Manages all WebView-based operations including:
 * - Initial WebView setup with pre-injected JS bridge
 * - Follow/Unfollow via bridge (KPSDK headers added automatically)
 * - Chat history fetching via bridge
 * - Viewer token fetching via bridge
 *
 * Architecture: A single authWebView loads kick.com/mobile/token once at startup.
 * Once KPSDK initializes, a JS bridge (window.AppBridge) is injected into the page.
 * All subsequent operations call bridge functions directly — no page reloads,
 * no KPSDK polling, no request interception needed.
 */
class WebViewManager(
    private val activity: MobilePlayerActivity,
    private val prefs: AppPreferences
) {

    private companion object {
        const val TAG = "WebViewManager"
        const val USER_AGENT = "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/143.0.0.0 Mobile Safari/537.36 OPR/94.0.0.0"
        const val CLIENT_TOKEN = "e1393935a959b4020a4491574f6490129f678acdaa92760471263db43487f823"
        const val KICK_TOKEN_URL = "https://kick.com/mobile/token"
        // Note: Session cookies are persisted between operations to maintain XSRF tokens
        // and session continuity. Only clear cookies on explicit logout or deep resets.
        const val KPSDK_POLL_INTERVAL_MS = 100L
        const val KPSDK_MAX_WAIT_MS = 5000L
        const val KPSDK_MIN_WAIT_MS = 300L
        const val DEEP_SUSPEND_DELAY_MS = 2 * 60 * 1000L // 2 minutes
    }

    // Callback for history fetching via WebView
    private var historyCallback: ((Result<String>) -> Unit)? = null

    // Mutable callback for viewer token fetching via authWebView
    private var viewerTokenCallback: ((String) -> Unit)? = null

    // Mutable callback for follow result via bridge
    private var followResultCallback: ((String) -> Unit)? = null

    // Mutable callback for clip creation result via bridge
    private var clipCreateCallback: ((Result<String>) -> Unit)? = null

    // Mutable callback for clip finalization result via bridge
    private var clipFinalizeCallback: ((Result<String>) -> Unit)? = null

    // Cached CookieManager instance
    private val cookieManager: android.webkit.CookieManager by lazy {
        android.webkit.CookieManager.getInstance()
    }

    // Track whether the main authWebView has been initialized with KPSDK + bridge
    @Volatile
    private var isAuthWebViewReady = false

    // Track whether the JS bridge has been injected into the page
    private var isBridgeInjected = false

    // Track whether the authWebView is currently suspended (paused)
    private var isAuthWebViewSuspended = false

    // Track whether the authWebView has been deep suspended (about:blank loaded)
    private var isDeepSuspended = false

    // Handler for scheduling deep suspend after idle timeout
    private val deepSuspendHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private val deepSuspendRunnable = Runnable { deepSuspendAuthWebView() }

    /**
     * Suspends the authWebView to conserve RAM when not in use.
     * Phase 1 (immediate): onPause() - stops JS/layout/rendering
     * Phase 2 (after 2min): loads about:blank to free DOM/JS heap/GPU (~50MB saved)
     */
    private fun suspendAuthWebView() {
        if (isAuthWebViewSuspended && !isDeepSuspended) {
            // Already in light suspend, just reschedule deep suspend timer
            return
        }
        if (isDeepSuspended) return
        try {
            activity.binding.authWebView.onPause()
            isAuthWebViewSuspended = true
            Log.d(TAG, "authWebView light-suspended (onPause)")
            // Schedule deep suspend after idle timeout
            deepSuspendHandler.removeCallbacks(deepSuspendRunnable)
            deepSuspendHandler.postDelayed(deepSuspendRunnable, DEEP_SUSPEND_DELAY_MS)
        } catch (e: Exception) {
            Log.w(TAG, "Failed to suspend authWebView", e)
        }
    }

    /**
     * Deep suspends the authWebView by loading about:blank.
     * This frees the DOM tree, JavaScript heap, and GPU textures (~50MB).
     * The bridge and KPSDK state are lost and will be re-initialized on next use.
     */
    private fun deepSuspendAuthWebView() {
        if (isDeepSuspended) return
        try {
            val webView = activity.binding.authWebView
            webView.loadUrl("about:blank")
            isDeepSuspended = true
            isBridgeInjected = false
            isAuthWebViewReady = false
            Log.d(TAG, "authWebView deep-suspended (about:blank) — ~50MB freed")
        } catch (e: Exception) {
            Log.w(TAG, "Failed to deep suspend authWebView", e)
        }
    }

    /**
     * Ensures the authWebView is fully ready (resumed + KPSDK + bridge) before executing an operation.
     * Handles all 3 states transparently:
     *   - Active: calls onReady immediately
     *   - Light suspended (onPause): resumes instantly, calls onReady
     *   - Deep suspended (about:blank): reloads page, re-injects bridge, then calls onReady
     */
    private fun ensureAuthWebViewReady(onReady: () -> Unit) {
        // Cancel any pending deep suspend — we're about to use the WebView
        deepSuspendHandler.removeCallbacks(deepSuspendRunnable)

        if (isDeepSuspended) {
            // Deep suspended — need full reload
            Log.d(TAG, "Waking from deep suspend — reloading WebView")
            isDeepSuspended = false
            isAuthWebViewSuspended = false
            isAuthWebViewReady = false
            isBridgeInjected = false

            val webView = activity.binding.authWebView
            webView.onResume()
            restoreSavedCookies()

            var pageFinishedHandled = false
            webView.webViewClient = object : android.webkit.WebViewClient() {
                override fun onPageFinished(view: android.webkit.WebView?, url: String?) {
                    super.onPageFinished(view, url)
                    if (pageFinishedHandled) return
                    pageFinishedHandled = true
                    Log.d(TAG, "WebView reloaded after deep suspend: $url")

                    pollKpsdkAndExecute(view) {
                        try {
                            view?.evaluateJavascript(bridgeScript, null)
                            isBridgeInjected = true
                            isAuthWebViewReady = true
                            Log.d(TAG, "AppBridge re-injected after deep suspend")
                        } catch (e: Exception) {
                            Log.e(TAG, "Failed to re-inject bridge", e)
                            isAuthWebViewReady = true
                        }
                        cookieManager.flush()
                        onReady()
                    }
                }
            }
            webView.loadUrl(KICK_TOKEN_URL)
            return
        }

        // Ensure WebView is visible and active
        val webView = activity.binding.authWebView
        if (webView.visibility != View.VISIBLE) {
            webView.visibility = View.VISIBLE
            webView.alpha = 0.01f
        }

        if (isAuthWebViewSuspended) {
            // Light suspended — instant resume
            try {
                webView.onResume()
                // Even though onPause doesn't clear cookies, restoring ensures we have the login state
                // if it was somehow lost or cleared elsewhere.
                restoreSavedCookies()
                isAuthWebViewSuspended = false
                Log.d(TAG, "authWebView resumed from light suspend")
                
                // Add a small delay to ensure the JS engine is fully awake and ready to process evaluateJavascript
                webView.postDelayed({ onReady() }, 200)
                return
            } catch (e: Exception) {
                Log.w(TAG, "Failed to resume authWebView", e)
            }
        }

        onReady()
    }

    /**
     * The JS bridge code that gets injected into the WebView page after KPSDK loads.
     * All functions use the page's fetch() which KPSDK automatically intercepts
     * to add anti-bot headers (x-kpsdk-cd, x-kpsdk-ct, etc.)
     */
    private val bridgeScript = """
        window.AppBridge = {
            followChannel: function(slug, method, token) {
                var headers = {
                    'Content-Type': 'application/json',
                    'Authorization': 'Bearer ' + token,
                    'Accept': 'application/json'
                };
                var cookies = document.cookie.split(';');
                for (var i = 0; i < cookies.length; i++) {
                    var c = cookies[i].trim();
                    if (c.startsWith('XSRF-TOKEN=')) {
                        headers['X-XSRF-TOKEN'] = decodeURIComponent(c.substring(11));
                        break;
                    }
                }
                fetch('https://kick.com/api/v2/channels/' + slug + '/follow', {
                    method: method,
                    headers: headers,
                    credentials: 'include',
                    body: method === 'POST' ? '{}' : null
                })
                .then(function(r) {
                    return r.text().then(function(t) {
                        return { ok: r.ok, code: r.status, body: t };
                    });
                })
                .then(function(res) { AndroidBridge.onFollowResult(JSON.stringify(res)); })
                .catch(function(e) { AndroidBridge.onFollowResult(JSON.stringify({ok: false, code: 0, body: e.toString()})); });
            },

            fetchToken: function(authToken, clientToken, requestId) {
                fetch('https://websockets.kick.com/viewer/v1/token', {
                    method: 'GET',
                    headers: {
                        'Authorization': 'Bearer ' + authToken,
                        'Accept': 'application/json',
                        'Origin': 'https://kick.com',
                        'Referer': 'https://kick.com/',
                        'x-client-token': clientToken,
                        'x-request-id': requestId
                    }
                })
                .then(function(r) { return r.json(); })
                .then(function(d) { AndroidToken.processToken(JSON.stringify(d)); })
                .catch(function(e) { AndroidToken.processToken(JSON.stringify({error: e.toString()})); });
            },

            fetchHistory: function(url, token) {
                fetch(url, {
                    method: 'GET',
                    headers: {
                        'Authorization': 'Bearer ' + token,
                        'Accept': 'application/json',
                        'X-Requested-With': 'XMLHttpRequest',
                        'Cache-Control': 'no-cache'
                    }
                })
                .then(function(r) {
                    if (!r.ok) throw new Error(r.status);
                    return r.text();
                })
                .then(function(t) { Android.onHistorySuccess(t); })
                .catch(function(e) { Android.onHistoryError(e.toString()); });
            },

            createClip: function(url, body, token) {
                var headers = {
                    'Content-Type': 'application/json',
                    'Authorization': 'Bearer ' + token,
                    'Accept': 'application/json'
                };
                var cookies = document.cookie.split(';');
                for (var i = 0; i < cookies.length; i++) {
                    var c = cookies[i].trim();
                    if (c.startsWith('XSRF-TOKEN=')) {
                        headers['X-XSRF-TOKEN'] = decodeURIComponent(c.substring(11));
                        break;
                    }
                }
                fetch(url, {
                    method: 'POST',
                    headers: headers,
                    body: body
                })
                .then(function(r) {
                    return r.text().then(function(t) {
                        return { ok: r.ok, code: r.status, body: t };
                    });
                })
                .then(function(res) { AndroidBridge.onClipCreateResult(JSON.stringify(res)); })
                .catch(function(e) { AndroidBridge.onClipCreateResult(JSON.stringify({ok: false, code: 0, body: e.toString()})); });
            },

            finalizeClip: function(url, body, token) {
                var headers = {
                    'Content-Type': 'application/json',
                    'Authorization': 'Bearer ' + token,
                    'Accept': 'application/json'
                };
                var cookies = document.cookie.split(';');
                for (var i = 0; i < cookies.length; i++) {
                    var c = cookies[i].trim();
                    if (c.startsWith('XSRF-TOKEN=')) {
                        headers['X-XSRF-TOKEN'] = decodeURIComponent(c.substring(11));
                        break;
                    }
                }
                fetch(url, {
                    method: 'POST',
                    headers: headers,
                    body: body
                })
                .then(function(r) {
                    return r.text().then(function(t) {
                        return { ok: r.ok, code: r.status, body: t };
                    });
                })
                .then(function(res) { AndroidBridge.onClipFinalizeResult(JSON.stringify(res)); })
                .catch(function(e) { AndroidBridge.onClipFinalizeResult(JSON.stringify({ok: false, code: 0, body: e.toString()})); });
            }
        };
        console.log('[AppBridge] Bridge injected successfully');
    """.trimIndent()

    @SuppressLint("SetJavaScriptEnabled")
    fun setupWebView() {
        val webView = activity.binding.authWebView
        webView.settings.javaScriptEnabled = true
        webView.settings.domStorageEnabled = true
        webView.settings.databaseEnabled = true
        webView.settings.userAgentString = USER_AGENT

        cookieManager.setAcceptCookie(true)
        cookieManager.setAcceptThirdPartyCookies(webView, true)

        // Restore saved cookies from prefs (from WebView login)
        restoreSavedCookies()

        // Register all JS interfaces BEFORE loadUrl (they only take effect after page load)
        webView.addJavascriptInterface(WebAppInterface(), "Android")
        webView.addJavascriptInterface(object {
            @android.webkit.JavascriptInterface
            fun processToken(json: String) {
                viewerTokenCallback?.invoke(json)
            }
        }, "AndroidToken")
        webView.addJavascriptInterface(object {
            @android.webkit.JavascriptInterface
            fun onFollowResult(json: String) {
                followResultCallback?.invoke(json)
            }
            @android.webkit.JavascriptInterface
            fun onClipCreateResult(json: String) {
                clipCreateCallback?.invoke(Result.success(json))
            }
            @android.webkit.JavascriptInterface
            fun onClipFinalizeResult(json: String) {
                clipFinalizeCallback?.invoke(Result.success(json))
            }
        }, "AndroidBridge")

        var pageFinishedHandled = false
        webView.webViewClient = object : android.webkit.WebViewClient() {
            override fun onPageFinished(view: android.webkit.WebView?, url: String?) {
                super.onPageFinished(view, url)
                if (pageFinishedHandled) return
                pageFinishedHandled = true
                Log.d(TAG, "WebView loaded: $url")

                // Wait for KPSDK to initialize, then inject the bridge
                pollKpsdkAndExecute(view) {
                    try {
                        view?.evaluateJavascript(bridgeScript, null)
                        isBridgeInjected = true
                        isAuthWebViewReady = true
                        Log.d(TAG, "AppBridge injected - WebView fully ready")
                    } catch (e: Exception) {
                        Log.e(TAG, "Failed to inject bridge", e)
                        // Still mark as ready even without bridge for fallback paths
                        isAuthWebViewReady = true
                    }
                    cookieManager.flush()
                    suspendAuthWebView()
                }
            }
        }
        // Load a minimal page to initialize KPSDK environment
        webView.loadUrl(KICK_TOKEN_URL)
    }

    /**
     * Restores saved cookies from AppPreferences to CookieManager
     * This makes WebView "logged in" using cookies from the login flow
     */
    fun restoreSavedCookies() {
        val savedCookies = prefs.savedCookies ?: return

        // Cookies are stored separated by "|:|"
        val cookieGroups = savedCookies.split("|:|")
        for (cookieGroup in cookieGroups) {
            if (cookieGroup.isNotBlank()) {
                val individualCookies = cookieGroup.split(";")
                for (cookie in individualCookies) {
                    val trimmed = cookie.trim()
                    if (trimmed.isNotEmpty()) {
                        cookieManager.setCookie("https://kick.com", "$trimmed; Domain=.kick.com; Path=/; Secure")
                    }
                }
            }
        }
        cookieManager.flush()
        Log.d(TAG, "Restored ${cookieGroups.size} cookie groups from preferences")
    }

    /**
     * Security: Clears all cookies from WebView storage.
     * Should be called after operations are complete to prevent unencrypted storage.
     */
    fun clearWebViewCookies() {
        cookieManager.removeAllCookies(null)
        cookieManager.flush()
        Log.d(TAG, "🧹 WebView Cookies Cleared for Security")
    }

    inner class WebAppInterface {
        @android.webkit.JavascriptInterface
        fun onFollowSuccess(isFollow: Boolean) {
            activity.runOnUiThread {
                activity.currentIsFollowing = isFollow
                activity.updateFollowButtonState()
                activity.binding.infoFollowButton.isEnabled = true
                val msg = if (isFollow) activity.getString(R.string.followed) else activity.getString(R.string.unfollowed)
                Toast.makeText(activity, msg, Toast.LENGTH_SHORT).show()
            }
        }

        @android.webkit.JavascriptInterface
        fun onFollowError(error: String) {
            Log.e(TAG, "WebView Follow Error: $error")
            activity.runOnUiThread {
                activity.binding.infoFollowButton.isEnabled = true
                Toast.makeText(activity, activity.getString(R.string.error_format, error), Toast.LENGTH_SHORT).show()
            }
        }

        @android.webkit.JavascriptInterface
        fun onHistorySuccess(json: String) {
            historyCallback?.invoke(Result.success(json))
            historyCallback = null
            activity.runOnUiThread {
                suspendAuthWebView()
            }
        }

        @android.webkit.JavascriptInterface
        fun onHistoryError(error: String) {
            historyCallback?.invoke(Result.failure(Exception(error)))
            historyCallback = null
            activity.runOnUiThread {
                suspendAuthWebView()
            }
        }
    }

    // ==================== HISTORY FETCHING ====================

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    suspend fun fetchHistoryViaWebView(channelId: Long, userId: Long, cursor: String?): Result<dev.xacnio.kciktv.shared.data.model.ChatHistoryResponse> = kotlinx.coroutines.suspendCancellableCoroutine { continuation ->
        val safeCursor = cursor ?: ""
        val url = "https://kick.com/api/v2/channels/$channelId/users/$userId/messages?cursor=$safeCursor"
        val token = prefs.authToken ?: ""

        Log.d(TAG, "Starting WebView fetch for history: $url")

        // Set callback
        historyCallback = { result ->
            if (continuation.isActive) {
                result.onSuccess { json ->
                    try {
                        val response = com.google.gson.Gson().fromJson(json, dev.xacnio.kciktv.shared.data.model.ChatHistoryResponse::class.java)
                        continuation.resume(Result.success(response)) { }
                    } catch (e: Exception) {
                        Log.e(TAG, "JSON Parse Error on WebView history", e)
                        continuation.resume(Result.failure(e)) { }
                    }
                }.onFailure { err ->
                    continuation.resume(Result.failure(err)) { }
                }
            }
        }

        activity.runOnUiThread {
            if (activity.isFinishing || activity.isDestroyed) return@runOnUiThread
            ensureAuthWebViewReady {
                try {
                    // Use bridge if available, otherwise fallback to inline script
                    if (isBridgeInjected) {
                        val escapedUrl = url.replace("'", "\\'")
                        activity.binding.authWebView.evaluateJavascript(
                            "window.AppBridge.fetchHistory('$escapedUrl', '$token');", null
                        )
                    } else {
                        val script = """
                            fetch('$url', {
                                method: 'GET',
                                headers: {
                                    'Authorization': 'Bearer $token',
                                    'Accept': 'application/json',
                                    'X-Requested-With': 'XMLHttpRequest',
                                    'Cache-Control': 'no-cache'
                                }
                            }).then(response => {
                                if (!response.ok) throw new Error(response.status);
                                return response.text();
                            }).then(text => {
                                Android.onHistorySuccess(text);
                            }).catch(err => {
                                Android.onHistoryError(err.toString());
                            });
                        """
                        activity.binding.authWebView.evaluateJavascript(script, null)
                    }
                } catch (t: Throwable) {
                    Log.e(TAG, "Failed to execute history fetch script", t)
                    suspendAuthWebView()
                }
            }
        }
    }

    // ==================== FOLLOW / UNFOLLOW ====================

    @SuppressLint("SetJavaScriptEnabled")
    fun performFollowViaWebView(
        slug: String, 
        token: String, 
        isFollow: Boolean,
        onSuccess: ((Boolean) -> Unit)? = null, 
        onError: ((String) -> Unit)? = null
    ) {
        activity.runOnUiThread {
            ensureAuthWebViewReady {
                // Inject session cookie so the fetch request includes it
                val cookieStr = "session_token=$token; Domain=.kick.com; Path=/; Secure"
                cookieManager.setCookie("https://kick.com", cookieStr)
                cookieManager.setCookie(".kick.com", cookieStr)
                cookieManager.flush()

                val method = if (isFollow) "POST" else "DELETE"

                // Set the callback that AndroidBridge.onFollowResult will invoke
                followResultCallback = { json ->
                    activity.runOnUiThread {
                        followResultCallback = null
                        handleFollowResult(json, isFollow, slug, token, onSuccess, onError)
                    }
                }

                try {
                    if (isBridgeInjected) {
                        // Fast path: call pre-injected bridge function directly
                        Log.d(TAG, "Follow via AppBridge: $method /channels/$slug/follow")
                        activity.binding.authWebView.evaluateJavascript(
                            "window.AppBridge.followChannel('$slug', '$method', '$token');", null
                        )
                    } else {
                        // Fallback: inline script (bridge not ready yet, extremely rare)
                        Log.d(TAG, "Follow via inline script (bridge not ready): $method")
                        val script = """
                            (function() {
                                var headers = {
                                    'Content-Type': 'application/json',
                                    'Authorization': 'Bearer $token',
                                    'Accept': 'application/json'
                                };
                                var cookies = document.cookie.split(';');
                                for (var i = 0; i < cookies.length; i++) {
                                    var c = cookies[i].trim();
                                    if (c.startsWith('XSRF-TOKEN=')) {
                                        headers['X-XSRF-TOKEN'] = decodeURIComponent(c.substring(11));
                                        break;
                                    }
                                }
                                fetch('https://kick.com/api/v2/channels/$slug/follow', {
                                    method: '$method',
                                    headers: headers,
                                    credentials: 'include',
                                    body: '$method' === 'POST' ? '{}' : null
                                })
                                .then(function(r) { return r.text().then(function(t) { return {ok: r.ok, code: r.status, body: t}; }); })
                                .then(function(res) { AndroidBridge.onFollowResult(JSON.stringify(res)); })
                                .catch(function(e) { AndroidBridge.onFollowResult(JSON.stringify({ok: false, code: 0, body: e.toString()})); });
                            })();
                        """.trimIndent()
                        activity.binding.authWebView.evaluateJavascript(script, null)
                    }
                } catch (t: Throwable) {
                    Log.e(TAG, "Failed to execute follow via bridge", t)
                    val isCustomCallback = onSuccess != null || onError != null
                    if (isCustomCallback) {
                        onError?.invoke(t.message ?: "Unknown Error")
                    } else {
                        activity.binding.infoFollowLoader.visibility = View.GONE
                        activity.binding.infoFollowButton.visibility = View.VISIBLE
                        activity.binding.infoFollowButton.isEnabled = true
                        Toast.makeText(activity, activity.getString(R.string.error_format, t.message), Toast.LENGTH_SHORT).show()
                    }
                    suspendAuthWebView()
                }
            }
        }
    }

    /**
     * Handles the follow/unfollow result from the bridge callback.
     * Preserves the exact same behavior as the previous shouldInterceptRequest approach.
     */
    private fun handleFollowResult(
        json: String, isFollow: Boolean, slug: String, token: String,
        onSuccess: ((Boolean) -> Unit)?, onError: ((String) -> Unit)?
    ) {
        try {
            val result = org.json.JSONObject(json)
            val success = result.optBoolean("ok", false)
            val code = result.optInt("code", 0)
            val body = result.optString("body", "")

            Log.d(TAG, "Follow result: ok=$success, code=$code, body=$body")

            val isCustomCallback = onSuccess != null || onError != null

            // Hide Loader & Show Button only if default behavior
            if (!isCustomCallback) {
                activity.binding.infoFollowLoader.visibility = View.GONE
                activity.binding.infoFollowButton.visibility = View.VISIBLE
                activity.binding.infoFollowButton.isEnabled = true
            }

            if (success) {
                activity.currentIsFollowing = isFollow
                activity.chatStateManager.isFollowingCurrentChannel = isFollow

                if (isCustomCallback) {
                    onSuccess?.invoke(isFollow)
                    activity.channelProfileManager.updateChannelProfileFollowButton(activity.currentIsFollowing)
                } else {
                    activity.updateFollowButtonState()
                    Toast.makeText(activity, if (isFollow) activity.getString(R.string.followed) else activity.getString(R.string.unfollowed), Toast.LENGTH_SHORT).show()
                    activity.channelProfileManager.updateChannelProfileFollowButton(activity.currentIsFollowing)
                }

                suspendAuthWebView()

                // Fetch updated user info from API to get real followingSince
                activity.lifecycleScope.launch {
                    activity.repository.getChannelUserMe(slug, token).onSuccess { me ->
                        activity.chatStateManager.isFollowingCurrentChannel = me.isFollowing == true
                        activity.chatStateManager.followingSince = me.followingSince
                        if (!isCustomCallback) {
                            activity.runOnUiThread {
                                activity.updateChatroomHint(activity.currentChatroom)
                            }
                        }
                    }.onFailure {
                        if (!isFollow) activity.chatStateManager.followingSince = null
                        if (!isCustomCallback) {
                            activity.runOnUiThread {
                                activity.updateChatroomHint(activity.currentChatroom)
                            }
                        }
                    }
                }
            } else {
                if (isCustomCallback) {
                    onError?.invoke("API Error: $code")
                } else {
                    Toast.makeText(activity, activity.getString(R.string.operation_failed_code, code), Toast.LENGTH_SHORT).show()
                    activity.channelProfileManager.updateChannelProfileFollowButton(activity.currentIsFollowing)
                }
                suspendAuthWebView()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error handling follow result", e)
            val isCustomCallback = onSuccess != null || onError != null
            if (!isCustomCallback) {
                activity.binding.infoFollowLoader.visibility = View.GONE
                activity.binding.infoFollowButton.visibility = View.VISIBLE
                activity.binding.infoFollowButton.isEnabled = true
                Toast.makeText(activity, activity.getString(R.string.error_format, e.message), Toast.LENGTH_SHORT).show()
                activity.channelProfileManager.updateChannelProfileFollowButton(activity.currentIsFollowing)
            } else {
                onError?.invoke(e.message ?: "Unknown Error")
            }
            suspendAuthWebView()
        }
    }

    // ==================== KPSDK POLLING (only used during initial setup) ====================

    /**
     * Polls for KPSDK readiness by checking if the _kpsdk object exists in the WebView.
     * Only used during initial setupWebView() to wait for KPSDK before injecting the bridge.
     * After setup, all operations use the bridge directly — no polling needed.
     */
    private fun pollKpsdkAndExecute(view: android.webkit.WebView?, onReady: () -> Unit) {
        val startTime = System.currentTimeMillis()
        val handler = android.os.Handler(android.os.Looper.getMainLooper())
        
        fun isWebViewValid(): Boolean {
            if (view == null) return false
            if (view.parent == null) return false
            return true
        }
        
        fun poll() {
            if (activity.isFinishing || activity.isDestroyed) return
            
            if (!isWebViewValid()) {
                Log.d(TAG, "KPSDK poll stopped: WebView no longer valid")
                return
            }
            
            val elapsed = System.currentTimeMillis() - startTime
            
            if (elapsed >= KPSDK_MAX_WAIT_MS) {
                Log.d(TAG, "KPSDK poll timeout after ${elapsed}ms, executing anyway")
                onReady()
                return
            }
            
            try {
                view?.evaluateJavascript(
                    "(typeof window._kpsdk !== 'undefined' || typeof window.KPSDK !== 'undefined' || document.querySelector('script[src*=\"kpsdk\"]') !== null).toString()"
                ) { result ->
                    if (!isWebViewValid() || activity.isFinishing || activity.isDestroyed) return@evaluateJavascript
                    
                    val isReady = result?.trim('"') == "true"
                    if (isReady && elapsed >= KPSDK_MIN_WAIT_MS) {
                        Log.d(TAG, "KPSDK detected ready after ${elapsed}ms")
                        onReady()
                    } else {
                        handler.postDelayed({ poll() }, KPSDK_POLL_INTERVAL_MS)
                    }
                }
            } catch (t: Throwable) {
                Log.w(TAG, "KPSDK poll error: WebView likely destroyed", t)
            }
        }
        
        handler.postDelayed({ poll() }, KPSDK_MIN_WAIT_MS)
    }

    // ==================== VIEWER TOKEN FETCHING ====================

    @SuppressLint("SetJavaScriptEnabled")
    fun startViewerWebSocket(channelId: String, channelSlug: String, livestreamId: String?) {
        activity.binding.viewerConnectionContainer.visibility = View.VISIBLE
        activity.binding.viewerConnectionProgress.visibility = View.VISIBLE

        fetchViewerTokenInternal { token ->
            if (token != null) {
                activity.overlayManager.connectViewerWebSocket(token, channelId, livestreamId)
            } else {
                Log.e(TAG, "Failed to fetch viewer token for WebSocket")
            }
        }
    }

    /**
     * Fetch a new viewer token for websocket reconnection
     * This is called by OverlayManager when viewer websocket needs to reconnect
     */
    @SuppressLint("SetJavaScriptEnabled")
    fun fetchViewerToken(channelId: String, callback: (String?) -> Unit) {
        fetchViewerTokenInternal(callback)
    }

    /**
     * Shared implementation for viewer token fetching.
     * Uses ensureAuthWebViewReady to handle all states (active, light suspend, deep suspend).
     * The WebView is guaranteed to be ready with KPSDK + bridge before the token fetch executes.
     */
    @SuppressLint("SetJavaScriptEnabled")
    private fun fetchViewerTokenInternal(callback: (String?) -> Unit) {
        activity.runOnUiThread {
            try {
                if (activity.isFinishing || activity.isDestroyed) {
                    callback(null)
                    return@runOnUiThread
                }

                ensureAuthWebViewReady {
                    Log.d(TAG, "Fetching viewer token via bridge")
                    
                    // Set callback
                    viewerTokenCallback = { json ->
                        activity.runOnUiThread {
                            viewerTokenCallback = null
                            val token = parseTokenFromJson(json)
                            callback(token)
                            suspendAuthWebView()
                        }
                    }

                    // Generate request params
                    val authToken = prefs.authToken ?: ""
                    val chars = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz"
                    val randomStr = (1..6).map { chars.random() }.joinToString("")
                    val requestId = "${System.currentTimeMillis()}.$randomStr"

                    if (isBridgeInjected) {
                        activity.binding.authWebView.evaluateJavascript(
                            "window.AppBridge.fetchToken('$authToken', '$CLIENT_TOKEN', '$requestId');", null
                        )
                    } else {
                        executeViewerTokenScript(activity.binding.authWebView)
                    }
                }

            } catch (e: Exception) {
                Log.e(TAG, "Error fetching viewer token", e)
                callback(null)
            }
        }
    }

    /**
     * Injects and executes the inline JS fetch script for viewer token.
     * Used as fallback when bridge is not available.
     */
    private fun executeViewerTokenScript(view: android.webkit.WebView?) {
        val authToken = prefs.authToken ?: ""
        val chars = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz"
        val randomStr = (1..6).map { chars.random() }.joinToString("")
        val requestId = "${System.currentTimeMillis()}.$randomStr"

        val script = """
            fetch('https://websockets.kick.com/viewer/v1/token', {
                method: 'GET',
                headers: {
                    'Authorization': 'Bearer $authToken',
                    'Accept': 'application/json',
                    'Origin': 'https://kick.com',
                    'Referer': 'https://kick.com/',
                    'x-client-token': '$CLIENT_TOKEN',
                    'x-request-id': '$requestId'
                }
            })
              .then(response => response.json())
              .then(data => {
                  AndroidToken.processToken(JSON.stringify(data));
              })
              .catch(err => {
                  AndroidToken.processToken(JSON.stringify({error: err.toString()}));
              });
        """.trimIndent()

        try {
            view?.evaluateJavascript(script, null)
        } catch (t: Throwable) {
            Log.e(TAG, "Failed to inject token script: WebView destroyed or invalid", t)
        }
    }

    /**
     * Parses the viewer token from a JSON response string.
     * Returns the token string or null if parsing fails.
     */
    private fun parseTokenFromJson(json: String): String? {
        Log.d(TAG, "Token received via JS: $json")
        return try {
            val obj = org.json.JSONObject(json)
            val token = if (obj.has("data") && !obj.isNull("data") && obj.getJSONObject("data").has("token")) {
                obj.getJSONObject("data").getString("token")
            } else {
                obj.optString("token")
            }
            if (token.isNotEmpty()) {
                Log.d(TAG, "Viewer token obtained successfully")
                token
            } else {
                Log.e(TAG, "No token in JS response: $json")
                null
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error parsing token from JS", e)
            null
        }
    }

    // ==================== CLIP CREATION ====================

    @SuppressLint("SetJavaScriptEnabled")
    fun createClip(url: String, body: String, token: String, callback: (Result<String>) -> Unit) {
        activity.runOnUiThread {
            ensureAuthWebViewReady {
                // Set callbacks
                clipCreateCallback = { result ->
                    activity.runOnUiThread {
                        clipCreateCallback = null
                        callback(result)
                        suspendAuthWebView()
                    }
                }

                try {
                    val escapedBody = body.replace("\\", "\\\\").replace("'", "\\'").replace("\n", "\\n")
                    if (isBridgeInjected) {
                        Log.d(TAG, "createClip via AppBridge: $url")
                        activity.binding.authWebView.evaluateJavascript(
                            "window.AppBridge.createClip('$url', '$escapedBody', '$token');", null
                        )
                    } else {
                        // Inline fallback
                        Log.d(TAG, "createClip via inline script")
                        val script = """
                            (function() {
                                var headers = {
                                    'Content-Type': 'application/json',
                                    'Authorization': 'Bearer $token',
                                    'Accept': 'application/json'
                                };
                                var cookies = document.cookie.split(';');
                                for (var i = 0; i < cookies.length; i++) {
                                    var c = cookies[i].trim();
                                    if (c.startsWith('XSRF-TOKEN=')) {
                                        headers['X-XSRF-TOKEN'] = decodeURIComponent(c.substring(11));
                                        break;
                                    }
                                }
                                fetch('$url', {
                                    method: 'POST',
                                    headers: headers,
                                    body: '$escapedBody'
                                })
                                .then(function(r) { return r.text().then(function(t) { return {ok: r.ok, code: r.status, body: t}; }); })
                                .then(function(res) { AndroidBridge.onClipCreateResult(JSON.stringify(res)); })
                                .catch(function(e) { AndroidBridge.onClipCreateResult(JSON.stringify({ok: false, code: 0, body: e.toString()})); });
                            })();
                        """.trimIndent()
                        activity.binding.authWebView.evaluateJavascript(script, null)
                    }
                } catch (t: Throwable) {
                    Log.e(TAG, "Failed to execute createClip via bridge", t)
                    callback(Result.failure(t))
                    suspendAuthWebView()
                }
            }
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    fun finalizeClip(url: String, body: String, token: String, callback: (Result<String>) -> Unit) {
        activity.runOnUiThread {
            ensureAuthWebViewReady {
                // Set callbacks
                clipFinalizeCallback = { result ->
                    activity.runOnUiThread {
                        clipFinalizeCallback = null
                        callback(result)
                        suspendAuthWebView()
                    }
                }

                try {
                    val escapedBody = body.replace("\\", "\\\\").replace("'", "\\'").replace("\n", "\\n")
                    if (isBridgeInjected) {
                        Log.d(TAG, "finalizeClip via AppBridge: $url")
                        activity.binding.authWebView.evaluateJavascript(
                            "window.AppBridge.finalizeClip('$url', '$escapedBody', '$token');", null
                        )
                    } else {
                        // Inline fallback
                        Log.d(TAG, "finalizeClip via inline script")
                        val script = """
                            (function() {
                                var headers = {
                                    'Content-Type': 'application/json',
                                    'Authorization': 'Bearer $token',
                                    'Accept': 'application/json'
                                };
                                var cookies = document.cookie.split(';');
                                for (var i = 0; i < cookies.length; i++) {
                                    var c = cookies[i].trim();
                                    if (c.startsWith('XSRF-TOKEN=')) {
                                        headers['X-XSRF-TOKEN'] = decodeURIComponent(c.substring(11));
                                        break;
                                    }
                                }
                                fetch('$url', {
                                    method: 'POST',
                                    headers: headers,
                                    body: '$escapedBody'
                                })
                                .then(function(r) { return r.text().then(function(t) { return {ok: r.ok, code: r.status, body: t}; }); })
                                .then(function(res) { AndroidBridge.onClipFinalizeResult(JSON.stringify(res)); })
                                .catch(function(e) { AndroidBridge.onClipFinalizeResult(JSON.stringify({ok: false, code: 0, body: e.toString()})); });
                            })();
                        """.trimIndent()
                        activity.binding.authWebView.evaluateJavascript(script, null)
                    }
                } catch (t: Throwable) {
                    Log.e(TAG, "Failed to execute finalizeClip via bridge", t)
                    callback(Result.failure(t))
                    suspendAuthWebView()
                }
            }
        }
    }
}
