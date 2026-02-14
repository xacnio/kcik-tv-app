/**
 * File: WhatsNewSheetManager.kt
 *
 * Description: Manages business logic, state, and UI interactions for Whats New Sheet.
 *
 * Author: Xacnio
 *
 */
package dev.xacnio.kciktv.mobile.ui.settings

import android.annotation.SuppressLint
import android.app.Dialog
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import android.widget.ProgressBar
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog
import dev.xacnio.kciktv.mobile.MobilePlayerActivity
import dev.xacnio.kciktv.R
import dev.xacnio.kciktv.shared.data.model.GithubRelease
import dev.xacnio.kciktv.shared.data.repository.UpdateRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import dev.xacnio.kciktv.BuildConfig

class WhatsNewSheetManager(
    private val activity: MobilePlayerActivity,
    private val repository: UpdateRepository
) {
    private var activeDialog: Dialog? = null

    fun show(isAutoPopup: Boolean = false) {
        if (activeDialog?.isShowing == true) return

        if (isAutoPopup) {
            // Check content before showing dialog
            CoroutineScope(Dispatchers.IO).launch {
                val result = repository.getAllReleases()
                withContext(Dispatchers.Main) {
                    if (result.isSuccess) {
                        val releases = result.getOrNull() ?: emptyList()
                        val currentVer = dev.xacnio.kciktv.BuildConfig.VERSION_NAME
                        
                        val match = releases.firstOrNull { 
                            it.tagName.equals("v$currentVer", ignoreCase = true) || 
                            it.tagName.equals(currentVer, ignoreCase = true) 
                        }
                        
                        if (match != null) {
                            val body = match.body.trim()
                            // Skip if content is just an auto-generated changelog link
                            if (body.startsWith("**Full Changelog**", ignoreCase = true) || body.isEmpty()) {
                                // Mark as seen silently
                                activity.prefs.lastSeenWhatsNewVersion = currentVer
                                return@withContext
                            }
                            
                            createAndShowDialog(listOf(match), true)
                        }
                        // If no match found, don't show anything for auto-popup
                    }
                }
            }
        } else {
            // Manual open: show immediately and fetch
            createAndShowDialog(null, false)
        }
    }

    private fun createAndShowDialog(preloadedReleases: List<GithubRelease>?, isAutoPopup: Boolean) {
        val view = LayoutInflater.from(activity).inflate(R.layout.bottom_sheet_webview, null)
        
        if (isAutoPopup) {
            // Center Dialog Mode
            val dialog = Dialog(activity)
            dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)
            dialog.setContentView(view)
            dialog.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            
            val width = (activity.resources.displayMetrics.widthPixels * 0.9).toInt()
            val height = (activity.resources.displayMetrics.heightPixels * 0.7).toInt()
            dialog.window?.setLayout(width, height)
            
            val bg = GradientDrawable()
            bg.setColor(Color.parseColor("#121212"))
            bg.cornerRadius = 16f * activity.resources.displayMetrics.density
            view.background = bg
            
            activeDialog = dialog
        } else {
            // Bottom Sheet Mode
            val bsDialog = BottomSheetDialog(activity, R.style.BottomSheetDialogTheme)
            bsDialog.setContentView(view)

            val parentLayout = bsDialog.findViewById<View>(com.google.android.material.R.id.design_bottom_sheet) as? FrameLayout
            parentLayout?.let {
                val behavior = BottomSheetBehavior.from(it)
                behavior.state = BottomSheetBehavior.STATE_EXPANDED
                behavior.skipCollapsed = true
                it.layoutParams.height = ViewGroup.LayoutParams.MATCH_PARENT
            }
            activeDialog = bsDialog
        }

        val webView = view.findViewById<WebView>(R.id.webView)
        val progressBar = view.findViewById<ProgressBar>(R.id.progressBar)
        val closeBtn = view.findViewById<View>(R.id.closeButton)

        closeBtn.setOnClickListener { activeDialog?.dismiss() }

        if (isAutoPopup) {
            activeDialog?.setOnDismissListener {
                activity.prefs.lastSeenWhatsNewVersion = dev.xacnio.kciktv.BuildConfig.VERSION_NAME
            }
        }

        setupWebView(webView, isAutoPopup)

        if (preloadedReleases != null) {
            // Data is already ready
            progressBar.visibility = View.GONE
            val html = generateHtml(preloadedReleases)
            webView.loadDataWithBaseURL(null, html, "text/html", "UTF-8", null)
        } else {
            // Need to fetch data (Manual mode)
            progressBar.visibility = View.VISIBLE
            CoroutineScope(Dispatchers.IO).launch {
                val result = repository.getAllReleases()
                withContext(Dispatchers.Main) {
                    if (result.isSuccess) {
                        var releases = result.getOrNull() ?: emptyList()
                        val currentVer = dev.xacnio.kciktv.BuildConfig.VERSION_NAME
                        
                        // User request: Start showing from "My Version" downwards (History)
                        // This hides newer versions if the user is on an older version
                        val startIndex = releases.indexOfFirst { 
                            it.tagName.equals("v$currentVer", ignoreCase = true) || 
                            it.tagName.equals(currentVer, ignoreCase = true) 
                        }
                        
                        if (startIndex != -1) {
                            releases = releases.drop(startIndex)
                        }
                        
                        val html = generateHtml(releases)
                        webView.loadDataWithBaseURL(null, html, "text/html", "UTF-8", null)
                    } else {
                        val errorHtml = "<html><body style='background:#0f0f0f;color:white;display:flex;justify-content:center;align-items:center;height:100%'>Failed to load releases.</body></html>"
                        webView.loadData(errorHtml, "text/html", "UTF-8")
                    }
                    progressBar.visibility = View.GONE
                }
            }
        }

        activeDialog?.show()
    }

    @SuppressLint("SetJavaScriptEnabled", "ClickableViewAccessibility")
    private fun setupWebView(webView: WebView, isAutoPopup: Boolean) {
        webView.settings.javaScriptEnabled = true
        webView.settings.domStorageEnabled = true
        webView.setBackgroundColor(0) // Transparent
        
        if (!isAutoPopup) {
            webView.setOnTouchListener { v, event ->
                v.parent.requestDisallowInterceptTouchEvent(true)
                if ((event.action and android.view.MotionEvent.ACTION_MASK) == android.view.MotionEvent.ACTION_UP) {
                    v.parent.requestDisallowInterceptTouchEvent(false)
                }
                false
            }
        }
        
        webView.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest): Boolean {
                request.url?.let { uri ->
                    dev.xacnio.kciktv.mobile.util.DialogUtils.showLinkConfirmationDialog(activity, uri.toString())
                }
                return true
            }
        }
    }

    private fun generateHtml(releases: List<GithubRelease>): String {
        val jsonData = com.google.gson.Gson().toJson(releases)
        
        return """
            <!DOCTYPE html>
            <html>
            <head>
                <meta name="viewport" content="width=device-width, initial-scale=1.0">
                <style>
                    body { background-color: transparent; color: #e0e0e0; font-family: -apple-system, BlinkMacSystemFont, "Segoe UI", Roboto, Helvetica, Arial, sans-serif; padding: 16px; margin: 0; padding-bottom: 50px; }
                    h2 { color: #53fc18; margin-top: 24px; margin-bottom: 4px; font-size: 20px; }
                    .date { color: #888; font-size: 13px; margin-bottom: 16px; display: block; }
                    a { color: #53fc18; text-decoration: none; }
                    hr { border: 0; border-top: 1px solid #333; margin: 24px 0; }
                    ul { padding-left: 20px; }
                    li { margin-bottom: 6px; line-height: 1.5; }
                    p { line-height: 1.5; }
                    code { background: #222; padding: 2px 5px; border-radius: 4px; font-family: monospace; font-size: 13px; }
                    pre { background: #1a1a1a; padding: 12px; border-radius: 8px; overflow-x: auto; border: 1px solid #333; }
                    pre code { background: transparent; padding: 0; }
                    blockquote { border-left: 3px solid #53fc18; margin: 0; padding-left: 12px; color: #aaa; }
                    img { max-width: 100%; border-radius: 8px; }
                    .badge { display: inline-block; padding: 2px 8px; border-radius: 12px; font-size: 11px; font-weight: bold; margin-left: 8px; vertical-align: middle; }
                    .badge-pre { background: #ff9800; color: #000; }
                    .badge-stable { background: #53fc18; color: #000; }
                </style>
                <script src="https://cdn.jsdelivr.net/npm/marked/marked.min.js"></script>
            </head>
            <body>
                <div id="content"></div>
                
                <script>
                    const releases = $jsonData;
                    let html = "";
                    
                    releases.forEach((r, index) => {
                        let badge = r.prerelease ? '<span class="badge badge-pre">BETA</span>' : '<span class="badge badge-stable">STABLE</span>';
                        html += "<h2>" + r.tag_name + badge + "</h2>";
                        
                        try {
                            const date = new Date(r.published_at);
                            html += '<span class="date">' + date.toLocaleDateString() + ' ' + date.toLocaleTimeString() + '</span>';
                        } catch(e) {}
                        
                        html += marked.parse(r.body || "No description.");
                        
                        if (index < releases.length - 1) {
                            html += "<hr/>";
                        }
                    });
                    
                    document.getElementById('content').innerHTML = html;
                </script>
            </body>
            </html>
        """.trimIndent()
    }
}
