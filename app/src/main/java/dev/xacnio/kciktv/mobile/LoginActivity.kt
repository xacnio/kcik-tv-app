/**
 * File: LoginActivity.kt
 *
 * Description: Handles the user login process using a hybrid WebView and Retrofit approach.
 * It intercepts specific network requests to capture KPSDK headers, manages cookie synchronization,
 * and performs secure authentication against the backend API.
 *
 * Author: Xacnio
 *
 */
package dev.xacnio.kciktv.mobile

import android.annotation.SuppressLint
import android.os.Bundle
import android.util.Log
import android.view.View
import android.webkit.CookieManager
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.lifecycle.lifecycleScope
import androidx.webkit.WebSettingsCompat
import androidx.webkit.WebViewFeature
import dev.xacnio.kciktv.shared.data.prefs.AppPreferences
import dev.xacnio.kciktv.databinding.ActivityLoginBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import dev.xacnio.kciktv.R

/**
 * LoginActivity - Hybrid WebView + Retrofit login
 * 
 * Strategy:
 * 1. Let WebView load kick.com normally - KPSDK will initialize
 * 2. User fills login form, KPSDK generates headers
 * 3. When POST to /mobile/login is detected, intercept and extract KPSDK headers
 * 4. Block WebView request, make the same request via Retrofit/OkHttp (no WebView headers)
 * 5. Handle response and complete login
 */
class LoginActivity : ComponentActivity() {

    private lateinit var binding: ActivityLoginBinding
    private lateinit var prefs: AppPreferences
    
    companion object {
        private const val TAG = "LoginActivity"
        private const val KICK_URL = "https://kick.com"
        private const val LOGIN_ENDPOINT = "/mobile/login"
    }
    
    // Captured login body from JavaScript interceptor
    @Volatile
    private var capturedLoginBody: String? = null
    private var is2FaMode = false
    private var isLoginCompleted = false
    
    // JavaScript Interface to receive login body
    inner class LoginJsInterface {
        @android.webkit.JavascriptInterface
        fun onLoginBody(body: String) {
            Log.d(TAG, "📦 Login body captured from JS: ${body.take(100)}...")
            capturedLoginBody = body
        }

        @android.webkit.JavascriptInterface
        fun onKpsdkReady() {
            Log.d(TAG, "🟢 JS reports KPSDK is Ready!")
            runOnUiThread {
                if (!is2FaMode) {
                    binding.loginSubmitButton.isEnabled = true
                    binding.loginSubmitText.text = getString(R.string.login_button_label)
                    binding.loginLoadingSpinner.visibility = View.GONE
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        binding = ActivityLoginBinding.inflate(layoutInflater)
        setContentView(binding.root)
        
        // Force LTR layout regardless of language
        window.decorView.layoutDirection = View.LAYOUT_DIRECTION_LTR
        
        prefs = AppPreferences(this)
        
        // Apply Theme Color
        val themeColor = prefs.themeColor
        binding.loginSubmitButton.backgroundTintList = android.content.res.ColorStateList.valueOf(themeColor)
        binding.btnSwitchToWebView.setTextColor(themeColor)
        try {
            binding.otpLogoP1.setTextColor(themeColor)
            binding.otpLogoP2.setTextColor(themeColor)
        } catch (e: Exception) {
            // Layout mismatch
        }
        
        // Apply cursor color to OTP fields
        val fields = listOf(binding.otp1, binding.otp2, binding.otp3, binding.otp4, binding.otp5, binding.otp6)
        fields.forEach { setCursorColor(it, themeColor) }
        
        setupWebViewLogin()
    }
    
    // Helper to set cursor color programmatically
    private fun setCursorColor(view: android.widget.EditText, color: Int) {
        try {
            // Get the cursor resource id
            var field = android.widget.TextView::class.java.getDeclaredField("mCursorDrawableRes")
            field.isAccessible = true
            val drawableResId = field.getInt(view)

            // Get the editor
            field = android.widget.TextView::class.java.getDeclaredField("mEditor")
            field.isAccessible = true
            val editor = field.get(view)

            // Get the drawable and set a tint
            val drawable = androidx.core.content.ContextCompat.getDrawable(view.context, drawableResId)
            drawable?.setTint(color)
            val drawables = arrayOf(drawable, drawable)

            // Set the drawables
            field = editor.javaClass.getDeclaredField("mCursorDrawable")
            field.isAccessible = true
            field.set(editor, drawables)
        } catch (e: Exception) {
            // Fallback for API 29+ or failure
             if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
                 val drawable = view.textCursorDrawable
                 drawable?.setTint(color)
                 view.textCursorDrawable = drawable
             }
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun setupWebViewLogin() {
        binding.loginBackButton.setOnClickListener {
            finish()
        }
        
        // Definitions must be here
        val webView = binding.loginWebView
        
        onBackPressedDispatcher.addCallback(this, object : androidx.activity.OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (webView.canGoBack()) {
                    webView.goBack()
                } else {
                    isEnabled = false
                    onBackPressedDispatcher.onBackPressed()
                }
            }
        })
        
        val cookieManager = CookieManager.getInstance()
        
        // Always start with a clean slate
        cookieManager.removeAllCookies(null)
        cookieManager.flush()
        
        // Enable WebView debugging
        WebView.setWebContentsDebuggingEnabled(true)
        
        // Enable cookies
        cookieManager.setAcceptCookie(true)
        cookieManager.setAcceptThirdPartyCookies(webView, true)

        // Configure WebView Settings
        with(webView.settings) {
            javaScriptEnabled = true
            domStorageEnabled = true
            databaseEnabled = true
            userAgentString = "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/132.0.6834.163 Mobile Safari/537.36"
            javaScriptCanOpenWindowsAutomatically = true
            setSupportMultipleWindows(false)
            loadWithOverviewMode = true
            useWideViewPort = true
            allowContentAccess = true
            mixedContentMode = android.webkit.WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
            cacheMode = android.webkit.WebSettings.LOAD_DEFAULT
            mediaPlaybackRequiresUserGesture = true
            setSupportZoom(true)
            builtInZoomControls = true
            displayZoomControls = false
        }

        // Show Form-based Login, Hide WebView (but keep it active for KPSDK)
        binding.loginEmailInput.visibility = View.VISIBLE
        binding.loginPasswordInput.visibility = View.VISIBLE
        binding.loginOtpContainer.visibility = View.GONE
        binding.loginSubmitButton.visibility = View.VISIBLE
        binding.loginErrorText.visibility = View.GONE
        binding.webViewContainer.visibility = View.INVISIBLE // Invisible (not GONE) to keep JS running
        
        // Disable header list for now (handled by proxy)
        try {
            if (WebViewFeature.isFeatureSupported(WebViewFeature.REQUESTED_WITH_HEADER_ALLOW_LIST)) {
                WebSettingsCompat.setRequestedWithHeaderOriginAllowList(webView.settings, emptySet())
            }
        } catch (e: Exception) {
            // Ignore
        }
        
        // Add JavaScript Interface to receive login body from JS
        webView.addJavascriptInterface(LoginJsInterface(), "KCIKTVLogin")
        
        // Setup Native Login TextWatchers and Button
        setupNativeLoginFlow(webView)
        
        webView.webViewClient = object : WebViewClient() {
            
            // INTERCEPT LOGIN REQUEST - Extract KPSDK headers and make request via OkHttp
            override fun shouldInterceptRequest(view: WebView?, request: WebResourceRequest?): android.webkit.WebResourceResponse? {
                if (request == null) return null
                
                val url = request.url.toString()
                val method = request.method.uppercase()
                
                // Only intercept kick.com requests (ALL of them - including assets)
                if (!url.contains("kick.com")) {
                    return null 
                }
                
                // CRITICAL FIX: Only intercept POST if it is the login request (to use our captured body)
                if (method == "POST" && !url.contains(LOGIN_ENDPOINT)) {
                    return null
                }
                
                // Only intercept POST to /mobile/login (we handle this specially with body from JS)
                if (method == "POST" && url.contains(LOGIN_ENDPOINT)) {
                    Log.d(TAG, "🎯 LOGIN REQUEST INTERCEPTED!")
                    Log.d(TAG, "URL: $url")
                    
                    // Extract KPSDK and auth headers
                    val headers = request.requestHeaders ?: emptyMap()
                    
                    val kpsdkCd = headers["x-kpsdk-cd"] ?: headers["X-Kpsdk-Cd"]
                    val kpsdkCt = headers["x-kpsdk-ct"] ?: headers["X-Kpsdk-Ct"]
                    val kpsdkH = headers["x-kpsdk-h"] ?: headers["X-Kpsdk-H"]
                    val kpsdkV = headers["x-kpsdk-v"] ?: headers["X-Kpsdk-V"]
                    val xsrfToken = headers["x-xsrf-token"] ?: headers["X-Xsrf-Token"]
                    val referer = headers["referer"] ?: headers["Referer"]
                    val authorization = headers["authorization"] ?: headers["Authorization"]
                    val contentType = headers["content-type"] ?: headers["Content-Type"] ?: "application/json"
                    
                    Log.d(TAG, "KPSDK Headers captured:")
                    Log.d(TAG, "  x-kpsdk-cd: ${kpsdkCd?.take(50)}...")
                    Log.d(TAG, "  x-kpsdk-ct: $kpsdkCt")
                    Log.d(TAG, "  x-kpsdk-h: ${kpsdkH?.take(50)}...")
                    Log.d(TAG, "  x-kpsdk-v: $kpsdkV")
                    Log.d(TAG, "  x-xsrf-token: ${xsrfToken?.take(30)}...")
                    Log.d(TAG, "  referer: $referer")
                    Log.d(TAG, "  authorization: ${authorization?.take(30)}...")
                    
                    // Use body directly (set by Native Flow before triggering fetch)
                    // No need to wait/sleep, avoiding thread blocking/freezing.
                    val body = capturedLoginBody ?: "{}" 
                    
                    Log.d(TAG, "📦 Using login body: ${body.take(100)}...")
                    
                    try {
                        val client = okhttp3.OkHttpClient.Builder()
                            .connectionSpecs(listOf(okhttp3.ConnectionSpec.RESTRICTED_TLS, okhttp3.ConnectionSpec.CLEARTEXT))
                            .protocols(listOf(okhttp3.Protocol.HTTP_2, okhttp3.Protocol.HTTP_1_1))
                            .followRedirects(true)
                            .followSslRedirects(true)
                            .cookieJar(okhttp3.CookieJar.NO_COOKIES)
                            .connectTimeout(30, java.util.concurrent.TimeUnit.SECONDS)
                            .readTimeout(30, java.util.concurrent.TimeUnit.SECONDS)
                            .build()
                        
                        val requestBuilder = Request.Builder()
                            .url(url)
                            .post(body.toRequestBody(contentType.toMediaType()))
                        
                        // 1. Browser Identifiers / Fingerprint (Updated to Real Chrome 132)
                        requestBuilder.header("sec-ch-ua", "\"Not A(Brand\";v=\"99\", \"Android WebView\";v=\"132\", \"Chromium\";v=\"132\"")
                        requestBuilder.header("sec-ch-ua-mobile", "?1")
                        requestBuilder.header("sec-ch-ua-platform", "\"Android\"")
                        requestBuilder.header("Upgrade-Insecure-Requests", "1")
                        
                        // 2. Standard Headers
                        requestBuilder.header("Origin", "https://kick.com")
                        requestBuilder.header("Content-Type", contentType)
                        requestBuilder.header("User-Agent", "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/132.0.6834.163 Mobile Safari/537.36")
                        requestBuilder.header("Accept", "application/json, text/plain, */*")
                        
                        // 3. Sec-Fetch Headers
                        requestBuilder.header("Sec-Fetch-Site", "same-origin")
                        requestBuilder.header("Sec-Fetch-Mode", "cors")
                        requestBuilder.header("Sec-Fetch-Dest", "empty")
                        
                        // 4. Context Headers
                        referer?.let { requestBuilder.header("Referer", it) }
                        requestBuilder.header("Accept-Encoding", "gzip, deflate") 
                        requestBuilder.header("Accept-Language", "en-US,en;q=0.9")
                        
                        // 5. Auth & Custom KPSDK Headers
                        // Important: Server might expect these at specific positions, but usually standard headers first is safer
                        kpsdkCd?.let { requestBuilder.header("x-kpsdk-cd", it) }
                        kpsdkCt?.let { requestBuilder.header("x-kpsdk-ct", it) }
                        kpsdkH?.let { requestBuilder.header("x-kpsdk-h", it) }
                        kpsdkV?.let { requestBuilder.header("x-kpsdk-v", it) }

                        // Parse Cookies to extract Manual Valid Tokens
                        val cookieStr = cookieManager.getCookie("https://kick.com")
                        var extractedXsrf: String? = null
                        var extractedSession: String? = null
                        
                        if (cookieStr != null) {
                            val pairs = cookieStr.split(";")
                            for (pair in pairs) {
                                val parts = pair.trim().split("=")
                                if (parts.size >= 2) {
                                    val key = parts[0].trim()
                                    val value = parts.drop(1).joinToString("=")
                                    if (key == "XSRF-TOKEN") {
                                        extractedXsrf = java.net.URLDecoder.decode(value, "UTF-8")
                                    } else if (key == "kick_session") {
                                        extractedSession = java.net.URLDecoder.decode(value, "UTF-8")
                                    }
                                }
                            }
                        }

                        // XSRF-TOKEN (Manual from Cookie > Intercepted)
                        val finalXsrf = extractedXsrf ?: xsrfToken
                        if (!finalXsrf.isNullOrEmpty()) {
                            requestBuilder.header("X-XSRF-TOKEN", finalXsrf)
                        }

                        // Authorization (Manual Session Bearer > Intercepted)
                        if (!extractedSession.isNullOrEmpty()) {
                            requestBuilder.header("Authorization", "Bearer $extractedSession")
                        } else {
                            authorization?.let { requestBuilder.header("Authorization", it) }
                        }
                        
                        // 6. Cookies (Last)
                        cookieStr?.let { requestBuilder.header("Cookie", it) }

                        val builtRequest = requestBuilder.build()
                        
                        Log.d(TAG, "--- OkHttp Login Request Headers ---")
                        builtRequest.headers.forEach {
                            Log.d(TAG, "${it.first}: ${it.second}")
                        }
                        
                        val response = client.newCall(builtRequest).execute()
                        
                        Log.d(TAG, "✅ OkHttp Login Response: ${response.code}")
                        
                        var responseBodyBytes = response.body?.bytes() ?: ByteArray(0)
                        
                        // Manually decompress GZIP if Header exists (because we disabled OkHttp's transparent GZIP by setting Accept-Encoding)
                        val contentEncoding = response.header("Content-Encoding")
                        if (contentEncoding?.equals("gzip", ignoreCase = true) == true) {
                            try {
                                val gzipStream = java.util.zip.GZIPInputStream(java.io.ByteArrayInputStream(responseBodyBytes))
                                responseBodyBytes = gzipStream.readBytes()
                                gzipStream.close()
                                Log.d(TAG, "📂 Decompressed GZIP response")
                            } catch (e: Exception) {
                                Log.e(TAG, "Failed to decompress GZIP", e)
                            }
                        }
                        
                        val responseBodyString = String(responseBodyBytes)
                        Log.d(TAG, "Login Response Body: ${if (responseBodyString.length > 1000) responseBodyString.take(1000) + "..." else responseBodyString}")

                        // If successful login, save token immediately
                        if (response.code in 200..299) {
                            
                            // 2FA Check
                            if (responseBodyString.contains("\"2fa_required\":true") || responseBodyString.contains("\"otp_required\":true")) {
                                Log.d(TAG, "🚨 2FA/OTP Required!")
                                val isOtp = responseBodyString.contains("\"otp_required\":true")
                                runOnUiThread {
                                    show2FAInput(isOtp)
                                }
                            } else {
                                // Successful Login (No 2FA or 2FA Passed)
                                try {
                                    val json = org.json.JSONObject(responseBodyString)
                                    
                                    if (json.has("token")) {
                                        val token = json.getString("token")
                                        
                                        // Extract User Info from Login Response (Fallback)
                                        var username = "Kick User"
                                        var profilePic: String? = null
                                        var userId = 0L
                                        var slug: String? = null
                                        
                                            if (json.has("user")) {
                                            val userObj = json.getJSONObject("user")
                                            username = userObj.optString("username", username)
                                            profilePic = userObj.optString("profile_pic").takeIf { it.isNotEmpty() } 
                                                ?: userObj.optString("profile_picture").takeIf { it.isNotEmpty() }
                                            userId = userObj.optLong("id", 0L)
                                        }
                                        
                                        // --- FETCH FULL USER DETAILS (api/v1/user) ---
                                        try {
                                            Log.d(TAG, "🔍 Fetching Full User Details...")
                                            val userReq = Request.Builder()
                                                .url("https://kick.com/api/v1/user")
                                                .header("Authorization", "Bearer $token")
                                                .header("Accept", "application/json")
                                                .header("User-Agent", "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/132.0.6834.163 Mobile Safari/537.36")
                                                .header("Cookie", cookieStr ?: "") 
                                                .build()
                                                
                                            val userRes = client.newCall(userReq).execute()
                                            if (userRes.isSuccessful) {
                                                val userBody = userRes.body?.string() ?: "{}"
                                                val userJson = org.json.JSONObject(userBody)
                                                
                                                username = userJson.optString("username", username)
                                                userId = userJson.optLong("id", userId)
                                                profilePic = if (userJson.has("profilepic") && !userJson.isNull("profilepic")) {
                                                    userJson.getString("profilepic")
                                                } else {
                                                    profilePic
                                                }
                                                val chatColor = userJson.optString("chat_color").takeIf { it.isNotEmpty() }
                                                
                                                val streamerChannel = userJson.optJSONObject("streamer_channel")
                                                slug = streamerChannel?.optString("slug")
                                                if (slug.isNullOrEmpty()) slug = username
                                                
                                                Log.d(TAG, "✅ User Details Fetched: $username (ID: $userId, Slug: $slug, Color: $chatColor)")
                                                
                                                // Save Auth with color
                                                prefs.saveAuth(token, username, profilePic, userId, slug, chatColor)
                                            } else {
                                                Log.w(TAG, "⚠️ Failed to fetch user details: ${userRes.code}")
                                                prefs.saveAuth(token, username, profilePic, userId, slug)
                                            }
                                        } catch (e: Exception) {
                                            Log.e(TAG, "Error fetching user details", e)
                                        }

                                        // Wait until userRes is finished and check it
                                        // This check is already handled inside the try block for better safety.
                                        // I'll remove the redundant check here which was causing 'Unresolved reference: userRes'.
                                        
                                        // Sync cookies (Critical for subsequent requests)
                                        val responseCookies = response.headers("Set-Cookie")
                                        for (c in responseCookies) {
                                            cookieManager.setCookie("https://kick.com", c)
                                            // Persist to encrypted prefs so other WebViews can use them
                                            prefs.appendCookies("https://kick.com", c)
                                        }
                                        cookieManager.flush()
                                        
                                        // Finish Activity
                                        runOnUiThread {
                                            // SECURITY: Clear WebView cookies now that they are saved to encrypted prefs.
                                            // This prevents plain-text storage in app_webview/Cookies database.
                                            cookieManager.removeAllCookies(null)
                                            cookieManager.flush()
                                            Log.d(TAG, "🧹 WebView Cookies cleared for security after login")

                                            Toast.makeText(this@LoginActivity, getString(R.string.login_success_welcome, username), Toast.LENGTH_LONG).show()
                                            setResult(android.app.Activity.RESULT_OK)
                                            finish()
                                        }
                                    }
                                } catch (e: Exception) {
                                    Log.e(TAG, "Failed to parse successful login json", e)
                                }
                            }
                        } else {
                             // LOGIN FAILED (422, 401, etc)
                            Log.e(TAG, "❌ Login Failed (${response.code}) Body: $responseBodyString")
                            
                            runOnUiThread {
                                binding.loginSubmitButton.isEnabled = true
                                binding.loginSubmitText.text = getString(R.string.login_button_label)
                                binding.loginLoadingSpinner.visibility = View.GONE
                                
                                var errorMsg = getString(R.string.login_failed_code, response.code)
                                try {
                                    val json = org.json.JSONObject(responseBodyString)
                                    if (json.has("message")) errorMsg = json.getString("message")
                                    else if (json.has("error")) errorMsg = json.getString("error")
                                    else if (json.has("errors")) {
                                        val errors = json.getJSONObject("errors")
                                        val keys = errors.keys()
                                        if (keys.hasNext()) errorMsg = errors.getJSONArray(keys.next()).getString(0)
                                    }
                                } catch (e: Exception) {}
                                
                                binding.loginErrorText.text = errorMsg
                                binding.loginErrorText.visibility = View.VISIBLE
                                
                                if (is2FaMode) {
                                     Toast.makeText(this@LoginActivity, errorMsg, Toast.LENGTH_LONG).show()
                                }
                            }
                        }
                        
                        // Return the actual response to WebView
                        return android.webkit.WebResourceResponse(
                            "application/json",
                            "UTF-8",
                            response.code,
                            response.message.ifEmpty { "OK" },
                            mapOf(
                                "Content-Type" to "application/json",
                                "Access-Control-Allow-Origin" to "*",
                                "Access-Control-Allow-Credentials" to "true"
                            ),
                            java.io.ByteArrayInputStream(responseBodyBytes)
                        )
                        
                    } catch (e: Exception) {
                        Log.e(TAG, "Login proxy failed: ${e.message}")
                        return null // Fallback to normal WebView request (which will fail due to checks)
                    }
                }
                
                // Let all other requests go through normally
                return null
            }
            
            override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                return false
            }
            
            override fun onPageStarted(view: WebView?, url: String?, favicon: android.graphics.Bitmap?) {
                super.onPageStarted(view, url, favicon)
                binding.webViewLoading.visibility = View.VISIBLE
            }
            
            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)
                binding.webViewLoading.visibility = View.GONE
                cookieManager.flush()
                checkCookieForLogin()
                
                Log.d(TAG, "Page loaded: $url")
                
                // Inject basic monitoring, but rely on Kick's own KPSDK loading
                view?.evaluateJavascript("""
                    (function() {
                        // MUTE ALL MEDIA
                        document.querySelectorAll('video, audio').forEach(el => { el.muted = true; el.pause(); });

                        // Auto-click Login Button
                        setTimeout(function() {
                            try {
                                var loginBtn = document.querySelector('[data-testid="login"]');
                                if(loginBtn) {
                                    console.log('[KCIKTV] Auto-clicking login button');
                                    loginBtn.click();
                                }
                            } catch(e) {}
                        }, 1000);

                        if (window._KCIKTVIntercepted) return;
                        window._KCIKTVIntercepted = true;
                        
                        console.log('[KCIKTV] Page loaded, waiting for KPSDK...');
                        
                        // Just check periodically if KPSDK is ready
                        var checkInterval = setInterval(function() {
                            if (window.KPSDK || document.querySelector('script[src*="fp"]')) {
                                console.log('[KCIKTV] KPSDK Found!');
                                clearInterval(checkInterval);
                                try { window.AndroidLogin.onKpsdkReady(); } catch(e) {}
                            }
                        }, 500);

                    })();
                """.trimIndent(), null)
            
            // REMOVE WAIT LOOP in shouldInterceptRequest
            // (Wait logic removed in this replacement block via context matching if possible, otherwise next step)
    
                // Check for session_token removed
            }
            
            override fun onReceivedSslError(view: WebView?, handler: android.webkit.SslErrorHandler?, error: android.net.http.SslError?) {
                Log.w(TAG, "SSL Error: ${error?.toString()}")
                handler?.proceed()
            }
            
            override fun onReceivedHttpError(view: WebView?, request: WebResourceRequest?, errorResponse: android.webkit.WebResourceResponse?) {
                val url = request?.url?.toString() ?: "unknown"
                val statusCode = errorResponse?.statusCode ?: 0
                
                // Ignore 429 for KPSDK fingerprint endpoints - this is expected behavior
                if (statusCode == 429 && (url.contains("/fp?") || url.contains("kpsdk"))) {
                    Log.d(TAG, "KPSDK fingerprint response (429 is normal): $url")
                    return
                }
                
                Log.e(TAG, "HTTP Error: $statusCode for $url")
            }
            
            override fun onReceivedError(view: WebView?, request: WebResourceRequest?, error: android.webkit.WebResourceError?) {
                val url = request?.url?.toString() ?: "unknown"
                val errorCode = error?.errorCode ?: 0
                val description = error?.description?.toString() ?: "unknown"
                Log.e(TAG, "Load Error: $errorCode - $description for $url")
            }
        }
        
        // WebChromeClient for console logs
        webView.webChromeClient = object : android.webkit.WebChromeClient() {
            override fun onProgressChanged(view: WebView?, newProgress: Int) {
                if (newProgress == 100) {
                    binding.webViewLoading.visibility = View.GONE
                }
            }
            
            override fun onConsoleMessage(consoleMessage: android.webkit.ConsoleMessage?): Boolean {
                Log.d(TAG, "Console: ${consoleMessage?.message()}")
                return true
            }
        }
        
        // Setup close button
        binding.webViewCloseButton.setOnClickListener {
            binding.webViewContainer.visibility = View.GONE
        }
        
        binding.btnSwitchToWebView.setOnClickListener {
            binding.webViewContainer.visibility = View.VISIBLE
            binding.webViewContainer.alpha = 1.0f
            binding.webViewContainer.translationZ = 100f
            
            binding.loginWebView.alpha = 1.0f
            binding.loginWebView.isClickable = true
            binding.loginWebView.isFocusable = true
            binding.loginWebView.isFocusableInTouchMode = true
            binding.webViewContainer.isClickable = true
            
            // Request focus to enable keyboard input
            binding.loginWebView.requestFocus()
            
            val currentUrl = binding.loginWebView.url
            if (currentUrl == null || currentUrl == "about:blank" || !currentUrl.contains("kick.com")) {
                 binding.loginWebView.loadUrl("https://kick.com")
            }
        }
        
        // Fetch initial tokens via OkHttp/Retrofit (as requested)
        fetchInitialTokens(webView)
    }
    
    private fun fetchInitialTokens(webView: WebView) {
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                Log.d(TAG, "🚀 Fetching initial tokens from provider...")
                
                // Create Chrome-like OkHttp Client
                val client = okhttp3.OkHttpClient.Builder()
                    .connectionSpecs(listOf(okhttp3.ConnectionSpec.RESTRICTED_TLS, okhttp3.ConnectionSpec.CLEARTEXT))
                    .protocols(listOf(okhttp3.Protocol.HTTP_2, okhttp3.Protocol.HTTP_1_1))
                    .followRedirects(true)
                    .followSslRedirects(true)
                    .cookieJar(okhttp3.CookieJar.NO_COOKIES)
                    .connectTimeout(30, java.util.concurrent.TimeUnit.SECONDS)
                    .build()

                val request = Request.Builder()
                    .url("https://kick.com/kick-token-provider")
                    .header("Accept", "application/json")
                    .header("Accept-Language", "en-GB,en;q=0.9")
                    .header("Cache-Control", "no-cache")
                    .header("Pragma", "no-cache")
                    .header("Priority", "u=1, i")
                    .header("Referer", "https://kick.com/")
                    .header("Sec-Ch-Ua", "\"Chromium\";v=\"132\", \"Android WebView\";v=\"132\", \"Not-A.Brand\";v=\"24\"")
                    .header("Sec-Ch-Ua-Mobile", "?1")
                    .header("Sec-Ch-Ua-Platform", "\"Android\"")
                    .header("Sec-Fetch-Dest", "empty")
                    .header("Sec-Fetch-Mode", "cors")
                    .header("Sec-Fetch-Site", "same-origin")
                    .header("User-Agent", "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/132.0.6834.163 Mobile Safari/537.36")
                    .build()

                val response = client.newCall(request).execute()
                Log.d(TAG, "Token Provider Response: ${response.code}")
                
                // Log Headers
                Log.d(TAG, "--- Token Provider Headers ---")
                response.headers.forEach { (name, value) ->
                    Log.d(TAG, "$name: $value")
                }
                
                // Log Body (Use peekBody to not consume stream unexpectedly, though we don't use it elsewhere)
                val responseBodyStr = response.peekBody(Long.MAX_VALUE).string()
                Log.d(TAG, "--- Token Provider Body ---")
                Log.d(TAG, responseBodyStr)

                val cookies = response.headers("Set-Cookie")
                val cookieManager = CookieManager.getInstance()
                
                if (cookies.isNotEmpty()) {
                    withContext(Dispatchers.Main) {
                        // Inject Cookies
                        for (cookie in cookies) {
                            cookieManager.setCookie("https://kick.com", cookie)
                            Log.d(TAG, "🍪 Cookie set: ${cookie.substringBefore(";")}")
                        }
                        cookieManager.flush()
                        
                        // NOW load WebView (Load main page to ensure KPSDK loads)
                        webView.loadUrl("https://kick.com") 
                        
                        // Setup UI visibility (Hidden but active)
                        binding.webViewContainer.visibility = View.VISIBLE
                        binding.webViewContainer.alpha = 0.01f
                        binding.webViewContainer.translationZ = -100f // Send to back!
                        binding.loginWebView.visibility = View.VISIBLE
                        binding.loginWebView.alpha = 0.01f
                        
                        // Disable interaction
                        binding.webViewContainer.isClickable = false
                        binding.webViewContainer.isFocusable = false
                        binding.loginWebView.isClickable = false
                        binding.loginWebView.isFocusable = false
                    }
                } else {
                    Log.w(TAG, "⚠️ No cookies received from token provider")
                     withContext(Dispatchers.Main) {
                        // Fallback load
                        webView.loadUrl("https://kick.com")
                     }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to fetch tokens", e)
                withContext(Dispatchers.Main) {
                    webView.loadUrl("https://kick.com")
                }
            }
        }
    }
    
    private fun setupNativeLoginFlow(webView: WebView) {
        
        binding.loginSubmitButton.setOnClickListener {
            // 2FA SUBMISSION LOGIC
            // ... (Logic below handles submission)
            // 2FA SUBMISSION LOGIC
            if (is2FaMode) {
                val code = getOtpCode()
                if (code.length != 6) {
                    binding.loginErrorText.setText(getString(R.string.enter_6_digit_code))
                    binding.loginErrorText.visibility = View.VISIBLE
                    return@setOnClickListener
                }
                
                binding.loginSubmitButton.isEnabled = false
                binding.loginSubmitText.text = getString(R.string.verifying)
                binding.loginLoadingSpinner.visibility = View.VISIBLE
                binding.loginErrorText.visibility = View.GONE
                
                // Update payload with OTP
                try {
                    val json = org.json.JSONObject(capturedLoginBody ?: "{}")
                    json.put("one_time_password", code)
                    capturedLoginBody = json.toString()
                    
                    Log.d(TAG, "🚀 Resubmitting request with 2FA code...")
                    
                    // Trigger Request Again
                     webView.evaluateJavascript("""
                        var loginPayload = $capturedLoginBody;
                        fetch('https://kick.com/mobile/login', {
                            method: 'POST',
                            headers: { 'Content-Type': 'application/json' },
                            body: JSON.stringify(loginPayload)
                        }).catch(err => console.error(err));
                    """.trimIndent(), null)
                    
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to update 2FA payload", e)
                }
                return@setOnClickListener
            }

            // INITIAL LOGIN LOGIC
            val email = binding.loginEmailInput.text.toString()
            val password = binding.loginPasswordInput.text.toString()
            
            if (email.isBlank() || password.isBlank()) {
                binding.loginErrorText.setText(getString(R.string.fill_all_fields))
                binding.loginErrorText.visibility = View.VISIBLE
                return@setOnClickListener
            }
            
            binding.loginSubmitButton.isEnabled = false
            binding.loginSubmitText.text = getString(R.string.logging_in)
            binding.loginLoadingSpinner.visibility = View.VISIBLE
            
            // Launch background task to hit sanctum/csrf FIRST
            lifecycleScope.launch(Dispatchers.IO) {
                try {
                    Log.d(TAG, "⚡ Requesting Sanctum CSRF...")
                    val cookieManager = CookieManager.getInstance()
                    val currentCookies = cookieManager.getCookie("https://kick.com")
                    
                    val client = okhttp3.OkHttpClient.Builder()
                        .connectionSpecs(listOf(okhttp3.ConnectionSpec.RESTRICTED_TLS, okhttp3.ConnectionSpec.CLEARTEXT))
                        .protocols(listOf(okhttp3.Protocol.HTTP_2, okhttp3.Protocol.HTTP_1_1))
                        .followRedirects(true)
                        .followSslRedirects(true)
                        .cookieJar(okhttp3.CookieJar.NO_COOKIES) 
                        .connectTimeout(30, java.util.concurrent.TimeUnit.SECONDS)
                        .build()

                    val request = Request.Builder()
                        .url("https://kick.com/sanctum/csrf")
                        .header("Accept", "application/json")
                        .header("Accept-Language", "en-GB,en;q=0.9")
                        .header("Cache-Control", "no-cache")
                        .header("Pragma", "no-cache")
                        .header("Priority", "u=1, i")
                        .header("Referer", "https://kick.com/")
                        .header("Sec-Ch-Ua", "\"Chromium\";v=\"132\", \"Android WebView\";v=\"132\", \"Not-A.Brand\";v=\"24\"")
                        .header("Sec-Ch-Ua-Mobile", "?1")
                        .header("Sec-Ch-Ua-Platform", "\"Android\"")
                        .header("Sec-Fetch-Dest", "empty")
                        .header("Sec-Fetch-Mode", "cors")
                        .header("Sec-Fetch-Site", "same-origin")
                        .header("User-Agent", "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/132.0.6834.163 Mobile Safari/537.36")
                        .header("Cookie", currentCookies ?: "")
                        .build()

                    Log.d(TAG, "--- Sanctum Request Headers ---")
                    request.headers.forEach {
                        Log.d(TAG, "${it.first}: ${it.second}")
                    }

                    val response = client.newCall(request).execute()
                    Log.d(TAG, "Sanctum CSRF Response: ${response.code}")
                    
                    Log.d(TAG, "--- Sanctum Response Headers ---")
                    response.headers.forEach {
                        Log.d(TAG, "${it.first}: ${it.second}")
                    }
                    
                    val bodyStr = response.peekBody(Long.MAX_VALUE).string()
                    Log.d(TAG, "--- Sanctum Response Body ---")
                    Log.d(TAG, bodyStr)
                    
                    // Inject Sanctum JS Config if present (Extract from HTML)
                    if (bodyStr.contains("window.KPSDK")) {
                        try {
                            val scriptRegex = Regex("<script[^>]*>(.*?)</script>", setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE))
                            val matches = scriptRegex.findAll(bodyStr)
                            
                            val configScript = matches.map { it.groupValues[1] }.find { it.contains("window.KPSDK") }
                            
                            if (configScript != null) {
                                withContext(Dispatchers.Main) {
                                    if (isFinishing || isDestroyed) return@withContext
                                    Log.d(TAG, "💉 Injecting Extracted Sanctum KPSDK Config...")
                                    try {
                                        webView.evaluateJavascript(configScript, null)
                                    } catch (t: Throwable) {
                                        Log.e(TAG, "Failed to inject Sanctum config script", t)
                                    }
                                }
                            }
                        } catch (e: Exception) {
                            Log.e(TAG, "Failed to extract/inject Sanctum script", e)
                        }
                    }
                    
                    // Update cookies if any
                    val newCookies = response.headers("Set-Cookie")
                    if (newCookies.isNotEmpty()) {
                         withContext(Dispatchers.Main) {
                            Log.d(TAG, "🍪 Setting ${newCookies.size} Sanctum cookies...")
                            for (cookie in newCookies) {
                                cookieManager.setCookie("https://kick.com", cookie)
                                Log.d(TAG, "   Sets-Cookie: ${cookie.take(50)}...")
                            }
                            cookieManager.flush()
                        }
                    }
                    
                } catch (e: Exception) {
                    Log.e(TAG, "Sanctum CSRF Check Failed", e)
                }
                
                // Proceed to Login Flow (Main Thread)
                withContext(Dispatchers.Main) {
                    if (isFinishing || isDestroyed) return@withContext

                    // Prepare body safely using JSONObject
                    val bodyJson = org.json.JSONObject().apply {
                        put("email", email)
                        put("password", password)
                        put("isMobileRequest", true)
                    }.toString()
                    
                    capturedLoginBody = bodyJson // Set directly
                    Log.d(TAG, "🚀 Triggering native login flow...")
                    
                    // Inject a dummy POST request to trigger shouldInterceptRequest
                    // CRITICAL: Send the REAL body so KPSDK calculates the correct headers/checksums!
                    try {
                        webView.evaluateJavascript("""
                            var loginPayload = $capturedLoginBody;
                            fetch('https://kick.com/mobile/login', {
                                method: 'POST',
                                headers: { 'Content-Type': 'application/json' },
                                body: JSON.stringify(loginPayload)
                            }).then(res => {
                                console.log('Dummy fetch completed');
                            }).catch(err => {
                                console.error('Dummy fetch error', err);
                            });
                        """.trimIndent(), null)
                    } catch (t: Throwable) {
                        Log.e(TAG, "Failed to inject login script", t)
                    }
                }
            }
        }
    
        // Simple text watchers to clear error
        val watcher = object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                binding.loginErrorText.visibility = View.GONE
            }
            override fun afterTextChanged(s: android.text.Editable?) {}
        }
        binding.loginEmailInput.addTextChangedListener(watcher)
        binding.loginPasswordInput.addTextChangedListener(watcher)
        
        setupOtpFields()
        
        // 2FA Overlay Listeners
        binding.otpCloseButton.setOnClickListener {
            is2FaMode = false
            binding.loginOtpContainer.visibility = View.GONE
            binding.loginEmailInput.isEnabled = true
            binding.loginPasswordInput.isEnabled = true
            binding.loginSubmitButton.isEnabled = true
            binding.loginSubmitText.text = getString(R.string.login_button_label)
        }
        
        binding.otpTitle.setOnClickListener {
            binding.otpCloseButton.performClick()
        }
    }
    
    private fun checkCookieForLogin() {
        if (isLoginCompleted) return
        
        val cookieManager = CookieManager.getInstance()
        val cookieStr = cookieManager.getCookie("https://kick.com") ?: return
        
        if (cookieStr.contains("session_token")) {
             val pairs = cookieStr.split(";")
             for (pair in pairs) {
                val parts = pair.trim().split("=")
                if (parts.size >= 2) {
                    val key = parts[0].trim()
                    if (key == "session_token") {
                         val value = parts.drop(1).joinToString("=")
                         val token = java.net.URLDecoder.decode(value, "UTF-8")
                         
                         if (!token.isNullOrEmpty() && !isLoginCompleted) {
                             Log.d(TAG, "🍪 Detected session_token cookie! Initiating login check...")
                             performCookieLogin(token, cookieStr)
                             return
                         }
                    }
                }
             }
        }
    }

    private fun performCookieLogin(token: String, allCookies: String) {
        if (isLoginCompleted) return
        isLoginCompleted = true 
        
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                 Log.d(TAG, "🔍 Verifying session_token with api/v1/user...")
                 val client = okhttp3.OkHttpClient.Builder()
                    .connectTimeout(30, java.util.concurrent.TimeUnit.SECONDS)
                    .build()
                    
                 val request = Request.Builder()
                    .url("https://kick.com/api/v1/user")
                    .header("Authorization", "Bearer $token")
                    .header("Cookie", allCookies)
                    .header("Accept", "application/json")
                    .build()
                
                val response = client.newCall(request).execute()
                if (response.isSuccessful) {
                    val body = response.body?.string() ?: "{}"
                    val json = org.json.JSONObject(body)
                    
                    val userId = json.optLong("id")
                    val username = json.optString("username")
                    val profilePic = json.optString("profile_pic") ?: json.optString("profile_picture")
                    var slug = json.optJSONObject("streamer_channel")?.optString("slug")
                    if (slug.isNullOrEmpty()) slug = username
                    
                     withContext(Dispatchers.Main) {
                        prefs.saveAuth(token, username, profilePic, userId, slug)
                        // Save the full cookie set too
                        prefs.cookies = allCookies
                        Toast.makeText(this@LoginActivity, getString(R.string.login_success_cookie), Toast.LENGTH_LONG).show()
                        setResult(android.app.Activity.RESULT_OK)
                        finish()
                     }
                } else {
                    Log.w(TAG, "⚠️ session_token found but API returned ${response.code}")
                    isLoginCompleted = false 
                }
            } catch (e: Exception) {
                Log.e(TAG, "Cookie login check failed", e)
                isLoginCompleted = false
            }
        }
    }

    private fun getOtpCode(): String {
        return binding.otp1.text.toString() +
               binding.otp2.text.toString() +
               binding.otp3.text.toString() +
               binding.otp4.text.toString() +
               binding.otp5.text.toString() +
               binding.otp6.text.toString()
    }

    private fun setupOtpFields() {
        val edits = listOf(binding.otp1, binding.otp2, binding.otp3, binding.otp4, binding.otp5, binding.otp6)
        
        // Apply Theme Color to OTP logic
        val themeColor = prefs.themeColor
        
        for (i in edits.indices) {
            val editText = edits[i]
            
            // Dynamic theme color for focus state
            editText.setOnFocusChangeListener { view, hasFocus ->
                val bg = android.graphics.drawable.GradientDrawable()
                bg.cornerRadius = 12f * resources.displayMetrics.density
                
                if (hasFocus) {
                    bg.setStroke((2f * resources.displayMetrics.density).toInt(), themeColor)
                    bg.setColor(0) // Transparent
                } else {
                    if ((view as android.widget.EditText).text.isNotEmpty()) {
                         bg.setStroke((1f * resources.displayMetrics.density).toInt(), android.graphics.Color.WHITE)
                    } else {
                         bg.setStroke((1f * resources.displayMetrics.density).toInt(), android.graphics.Color.parseColor("#444444"))
                    }
                    bg.setColor(0)
                }
                view.background = bg
            }
            
            editText.addTextChangedListener(object : android.text.TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                    // Paste Detection / Overflow (if length > 1)
                    if (s != null && s.length > 1) {
                         if (i == 0) {
                            val pasted = s.toString()
                            for (j in 0 until minOf(pasted.length, 6)) {
                                if (j < edits.size) edits[j].setText(pasted[j].toString())
                            }
                            val safeIdx = minOf(pasted.length - 1, 5)
                            edits[safeIdx].requestFocus()
                            try { edits[safeIdx].setSelection(edits[safeIdx].text.length) } catch(e:Exception){}
                            
                            if (pasted.length >= 6) handleOtpSubmit()
                        } else {
                            // Keep last char only for other fields
                            try {
                                val lastChar = s.last().toString()
                                editText.setText(lastChar)
                                editText.setSelection(lastChar.length)
                            } catch(e:Exception){}
                        }
                        return
                    }
                    
                    if (s?.length == 1) {
                         binding.loginErrorText.visibility = View.GONE
                        if (i < edits.size - 1) {
                            edits[i + 1].requestFocus()
                        } else {
                            // Last digit filled, hide keyboard or auto-submit
                             val imm = getSystemService(android.content.Context.INPUT_METHOD_SERVICE) as? android.view.inputmethod.InputMethodManager
                             imm?.hideSoftInputFromWindow(editText.windowToken, 0)
                             
                             // Auto Submit
                             handleOtpSubmit()
                        }
                    }
                }
                override fun afterTextChanged(s: android.text.Editable?) {}
            })
            
            // Backspace handling
            editText.setOnKeyListener { _, keyCode, event ->
                if (keyCode == android.view.KeyEvent.KEYCODE_DEL && event.action == android.view.KeyEvent.ACTION_DOWN) {
                    if (editText.text.isEmpty() && i > 0) {
                        edits[i - 1].requestFocus()
                        edits[i - 1].setText("") // Clear previous too
                        return@setOnKeyListener true
                    }
                }
                false
            }
        }
    }
    
    private fun hideFormBasedLogin() {
        try {
            binding.loginEmailInput.visibility = View.GONE
            binding.loginPasswordInput.visibility = View.GONE
            binding.loginOtpContainer.visibility = View.GONE
            binding.loginSubmitButton.visibility = View.GONE
            binding.loginErrorText.visibility = View.GONE
            binding.btnSwitchToWebView.visibility = View.GONE
        } catch (e: Exception) {
            // Some views might not exist
        }
    }
    
    private fun show2FAInput(isOtp: Boolean = false) {
        is2FaMode = true
        binding.loginLoadingSpinner.visibility = View.GONE
        
        // Disable form inputs to prevent interaction behind overlay
        binding.loginEmailInput.isEnabled = false
        binding.loginPasswordInput.isEnabled = false
        
        // Show Overlay
        binding.loginOtpContainer.visibility = View.VISIBLE
        binding.otp1.requestFocus()

        if (isOtp) {
            binding.otpTitle.text = getString(R.string.otp_required_title)
            binding.otpSubtitle.text = getString(R.string.otp_required_desc)
        } else {
            binding.otpTitle.text = getString(R.string.verify_2fa_title)
            binding.otpSubtitle.text = getString(R.string.verify_2fa_desc)
        }
        
        Toast.makeText(this, if(isOtp) getString(R.string.otp_required_title) else getString(R.string.twofa_required), Toast.LENGTH_SHORT).show()
    }
    
    // Check if OTP is full and submit
    private fun handleOtpSubmit() {
        val code = getOtpCode()
        if (code.length == 6) {
            binding.loginSubmitButton.performClick()
        }
    }
}
