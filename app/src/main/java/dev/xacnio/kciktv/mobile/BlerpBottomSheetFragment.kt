/**
 * File: BlerpBottomSheetFragment.kt
 *
 * Description: Implements a BottomSheetDialogFragment that displays Blerp content within a WebView. This fragment handles WebView configuration, cookie synchronization for authenticated sessions, file upload support via WebChromeClient, and manages the bottom sheet's expansion behavior and layout.
 *
 * Author: Xacnio
 *
 */
package dev.xacnio.kciktv.mobile

import android.annotation.SuppressLint
import android.app.Dialog
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import dev.xacnio.kciktv.databinding.BottomSheetBlerpBinding
import dev.xacnio.kciktv.shared.data.prefs.AppPreferences
import dev.xacnio.kciktv.R

class BlerpBottomSheetFragment : BottomSheetDialogFragment() {

    private var _binding: BottomSheetBlerpBinding? = null
    private val binding get() = _binding!!

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        val dialog = super.onCreateDialog(savedInstanceState) as BottomSheetDialog
        dialog.setOnShowListener {
            val bottomSheet = dialog.findViewById<View>(com.google.android.material.R.id.design_bottom_sheet) as FrameLayout?
            bottomSheet?.let {
                val behavior = BottomSheetBehavior.from(it)
                val displayMetrics = resources.displayMetrics
                val height = (displayMetrics.heightPixels * 0.90).toInt()
                
                // Set fixed height and state for 90% coverage
                it.layoutParams.height = height
                behavior.peekHeight = height
                behavior.state = BottomSheetBehavior.STATE_EXPANDED
                
                // Disable global dragging by default
                behavior.isDraggable = false
                behavior.isHideable = true

                // Setup touch listeners to enable dragging ONLY from the header/handle
                val dragToggleListener = View.OnTouchListener { _, event ->
                    when (event.action) {
                        android.view.MotionEvent.ACTION_DOWN -> {
                            behavior.isDraggable = true
                        }
                        android.view.MotionEvent.ACTION_UP, android.view.MotionEvent.ACTION_CANCEL -> {
                            // We don't reset immediately to allow the gesture to complete
                        }
                    }
                    false // Return false so the touch continues to propagate if needed
                }

                binding.blerpHeader.setOnTouchListener(dragToggleListener)
                binding.blerpHandle.setOnTouchListener(dragToggleListener)

                // Reset draggable state when the sheet is settled to prevent sticking
                behavior.addBottomSheetCallback(object : BottomSheetBehavior.BottomSheetCallback() {
                    override fun onStateChanged(bottomSheet: View, newState: Int) {
                        if (newState == BottomSheetBehavior.STATE_EXPANDED || newState == BottomSheetBehavior.STATE_COLLAPSED) {
                            behavior.isDraggable = false
                        }
                    }
                    override fun onSlide(bottomSheet: View, slideOffset: Float) {}
                })
            }
        }
        return dialog
    }

    private var persistentWebView: WebView? = null
    private var isUrlLoaded = false

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        val url = arguments?.getString("url") ?: ""
        
        if (_binding == null) {
            _binding = BottomSheetBlerpBinding.inflate(inflater, container, false)
            persistentWebView = binding.blerpWebView
            setupWebView()
        } else {
            // Re-use existing binding/view if possible
            val root = binding.root
            (root.parent as? ViewGroup)?.removeView(root)
        }

        // Always re-inject cookies when the view is created/re-shown to catch session changes
        injectCookies()

        if (!isUrlLoaded && url.isNotEmpty()) {
            persistentWebView?.loadUrl(url)
            isUrlLoaded = true
        }

        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        
        // Fix Title
        binding.sheetTitle.text = "Blerp"
        binding.sheetSubtitle.visibility = View.GONE
        
        // Fix Close Button
        binding.btnClose.setOnClickListener { dismiss() }
        
        // Fix Menu Button
        binding.btnMenu.setOnClickListener { v ->
            val popup = android.widget.PopupMenu(requireContext(), v)
            val currentUrl = persistentWebView?.url ?: arguments?.getString("url") ?: "https://blerp.com"
            val pm = requireContext().packageManager
            
            // 1. Refresh
            popup.menu.add("Yenile").setOnMenuItemClickListener {
                persistentWebView?.reload()
                true
            }
            
            // 2. Open in External Browser
            // Resolve generic URL (Google) to find the default browser app
            val browserCheckIntent = android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse("https://google.com"))
            val browserResolve = pm.resolveActivity(browserCheckIntent, android.content.pm.PackageManager.MATCH_DEFAULT_ONLY)
            var browserLabel = "Tarayıcı"
            var browserPkg: String? = null
            
            if (browserResolve != null) {
                val pkg = browserResolve.activityInfo.packageName
                if (pkg != "android" && !pkg.contains("resolver") && !pkg.contains("start")) {
                     browserLabel = browserResolve.loadLabel(pm).toString()
                     browserPkg = pkg
                }
            }
            
            popup.menu.add("$browserLabel'da Aç").setOnMenuItemClickListener {
                try {
                    val intent = android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(currentUrl))
                    if (browserPkg != null) {
                        intent.setPackage(browserPkg)
                    }
                    startActivity(intent)
                    dismiss()
                } catch (e: Exception) {
                     try {
                        startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(currentUrl)))
                         dismiss()
                    } catch (e2: Exception) {}
                }
                true
            }
            
            // 3. Copy Link
            popup.menu.add("Bağlantıyı kopyala").setOnMenuItemClickListener {
                val clipboard = requireContext().getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                val clip = android.content.ClipData.newPlainText("URL", currentUrl)
                clipboard.setPrimaryClip(clip)
                true
            }
            
            popup.show()
        }
    }

    override fun onDismiss(dialog: android.content.DialogInterface) {
        super.onDismiss(dialog)
        (activity as? MobilePlayerActivity)?.startBlerpCleanupTimer()
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun setupWebView() {
        val webView = persistentWebView ?: return
        with(webView) {
            isVerticalScrollBarEnabled = false
            isHorizontalScrollBarEnabled = false
            scrollBarStyle = View.SCROLLBARS_OUTSIDE_OVERLAY

            val chromeUserAgent = "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/143.0.0.0 Mobile Safari/537.36 OPR/94.0.0.0"
            settings.userAgentString = chromeUserAgent
            
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.databaseEnabled = true
            
            val cookieManager = CookieManager.getInstance()
            cookieManager.setAcceptCookie(true)
            cookieManager.setAcceptThirdPartyCookies(this, true)

            webViewClient = object : WebViewClient() {
                override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                    return false
                }
                override fun onPageStarted(view: WebView?, url: String?, favicon: android.graphics.Bitmap?) {
                    if (_binding != null) {
                        binding.blerpProgress.visibility = View.VISIBLE
                        // Reinforce cookies on navigation start
                        injectCookies()
                    }
                }
                override fun onPageFinished(view: WebView?, url: String?) {
                    if (_binding != null) {
                        binding.blerpProgress.visibility = View.GONE
                        CookieManager.getInstance().flush()

                        // Save Blerp Cookies (Only from Blerp domains)
                        if (url != null && url.contains("blerp.com", ignoreCase = true)) {
                            try {
                                val cookies = CookieManager.getInstance().getCookie(url)
                                if (cookies != null) {
                                    val context = context ?: return@onPageFinished
                                    val prefs = dev.xacnio.kciktv.shared.data.prefs.AppPreferences(context)
                                    prefs.savedBlerpCookies = cookies
                                }
                            } catch (e: Exception) {}
                        }
                        
                        // [disabled] No need to fix layout for now
                        // Inject JavaScript to hide specific elements using structural traversal and optimize layout
                        // Monitors URL changes and applies cleanup when path starts with /x/
                        // view?.evaluateJavascript("""
                        //     (function() {
                        //         function doCleanup() {
                        //             var isExtensionView = window.location.pathname.startsWith('/x/');
                                    
                        //             if (!isExtensionView) {
                        //                 var existingStyle = document.getElementById('blerp-cleanup-style');
                        //                 if (existingStyle) existingStyle.remove();
                        //                 document.body.style.overflow = '';
                        //                 document.documentElement.style.overflow = '';
                        //                 return;
                        //             }

                        //             if (!document.getElementById('blerp-cleanup-style')) {
                        //                 var style = document.createElement('style');
                        //                 style.id = 'blerp-cleanup-style';
                        //                 style.innerHTML = '#nav-bar { display: none !important; }';
                        //                 document.head.appendChild(style);
                        //             }

                        //             var container = document.getElementById('blerp-main-container');
                        //             var navBar = document.getElementById('nav-bar');
                        //             if (navBar) {
                        //                 var nextDiv = navBar.nextElementSibling;
                        //                 if (nextDiv) {
                        //                     // 1. Path: nav-bar -> next div -> child().child().child()
                        //                     var target = nextDiv;
                        //                     for(var i=0; i<3; i++) { if(target && target.children) target = target.children[0]; }
                                            
                        //                     if (target && target.children) {
                        //                         if (target.children[0]) target.children[0].style.display = 'none';
                                                
                        //                         if (target.children[1] && target.children[1].children && target.children[1].children[0]) {
                        //                             target.children[1].children[0].style.display = 'none';
                        //                         }
                        //                     }

                        //                     var nextDiv2 = nextDiv.nextElementSibling;
                        //                     if (nextDiv2 && nextDiv2.children) {
                        //                         if (nextDiv2.children[0]) nextDiv2.children[0].style.display = 'none';
                                                
                        //                         if (nextDiv2.children[0] && nextDiv2.children[0].children) {
                        //                             if (nextDiv2.children[0].children[0]) nextDiv2.children[0].children[0].style.display = 'none';
                        //                         }

                        //                         if (nextDiv2.children[1] && nextDiv2.children[1].children) {
                        //                             if (nextDiv2.children[1].children[1]) nextDiv2.children[1].children[1].style.display = 'none';
                        //                         }
                        //                     }
                        //                 }
                        //             }

                        //             if (container) {
                        //                  container.style.width = '100vw';
                        //                  container.style.height = '100vh';
                        //                  container.style.maxWidth = '100vw';
                        //                  container.style.maxHeight = '100vh';
                        //                  container.style.position = 'fixed';
                        //                  container.style.top = '0';
                        //                  container.style.left = '0';
                        //                  container.style.margin = '0';
                        //                  container.style.padding = '0';
                        //                  container.style.overflow = 'auto';
                                         
                        //                  document.body.style.margin = '0';
                        //                  document.body.style.padding = '0';
                        //                  document.body.style.overflow = 'hidden';
                        //                  document.documentElement.style.margin = '0';
                        //                  document.documentElement.style.padding = '0';
                        //                  document.documentElement.style.overflow = 'hidden';
                        //             }
                        //         }

                        //         // Run immediately
                        //         doCleanup();

                        //         // Monitor for URL changes (important for SPA navigation)
                        //         if (!window.blerpObserverAttached) {
                        //             window.blerpObserverAttached = true;
                        //             var lastHref = window.location.href;
                        //             var observer = new MutationObserver(function() {
                        //                 if (lastHref !== window.location.href) {
                        //                     lastHref = window.location.href;
                        //                     doCleanup();
                        //                     // Extra retry as DOM might still be loading
                        //                     setTimeout(doCleanup, 500);
                        //                 }
                        //             });
                        //             observer.observe(document, {subtree: true, childList: true});
                        //         }
                                
                        //         // Reliable periodic check
                        //         setInterval(doCleanup, 2000);
                        //     })();
                        // """.trimIndent(), null)
                    }
                }
            }

            webChromeClient = object : WebChromeClient() {
                override fun onProgressChanged(view: WebView?, newProgress: Int) {
                    if (newProgress == 100 && _binding != null) {
                        binding.blerpProgress.visibility = View.GONE
                    }
                }
            }
        }
    }

    private fun injectCookies() {
        val webView = persistentWebView ?: return
        try {
            val context = context ?: return
            val prefs = dev.xacnio.kciktv.shared.data.prefs.AppPreferences(context)
            val cookieManager = CookieManager.getInstance()
            
            cookieManager.setAcceptCookie(true)
            cookieManager.setAcceptThirdPartyCookies(webView, true)

            // 1. Kick Cookies pool injection
            val savedCookies = prefs.savedCookies
            
            fun injectKickCookie(cookie: String) {
                val trimmed = cookie.trim()
                if (trimmed.isEmpty()) return
                
                // Extract only the descriptor part (name=value) to avoid flag duplication or errors
                val nameValue = trimmed.split(";")[0].trim()
                if (nameValue.isEmpty() || !nameValue.contains("=")) return
                
                val key = nameValue.split("=")[0].trim().lowercase()
                // Skip if it's just a protocol flag
                if (key == "path" || key == "domain" || key == "expires" || key == "samesite" || key == "secure" || key == "httponly") return

                // Force SameSite=None; Secure for cross-origin iframe usage (Blerp needs this to read Kick cookies)
                val baseFlags = "; Domain=.kick.com; Path=/; Secure; SameSite=None"
                
                // Set for multiple common Kick subdomains to ensure coverage
                cookieManager.setCookie("https://kick.com", nameValue + baseFlags)
                cookieManager.setCookie("https://www.kick.com", nameValue + baseFlags)
                cookieManager.setCookie("https://account.kick.com", nameValue + baseFlags)
            }

            if (savedCookies != null) {
                val groups = savedCookies.split("|:|")
                for (group in groups) {
                    if (group.isNotBlank()) {
                        val cookies = group.split(";")
                        for (c in cookies) {
                            injectKickCookie(c)
                        }
                    }
                }
            }
            
            // 2. Extra Identity Reinforcement (Critical for recognition)
            val authToken = prefs.authToken
            if (!authToken.isNullOrEmpty()) {
                val baseFlags = "; Domain=.kick.com; Path=/; Secure; SameSite=None"
                val tokens = listOf("kick_session", "session_token")
                val domains = listOf("https://kick.com", "https://www.kick.com", "https://account.kick.com")
                
                for (tokenKey in tokens) {
                    for (domain in domains) {
                        cookieManager.setCookie(domain, "$tokenKey=$authToken$baseFlags")
                    }
                }
            }
            
            // 3. Restore Blerp Specific Cookies
            val savedBlerpCookies = prefs.savedBlerpCookies
            if (!savedBlerpCookies.isNullOrEmpty()) {
                val bCookies = savedBlerpCookies.split(";")
                for (c in bCookies) {
                    val trimmed = c.trim()
                    if (trimmed.isNotBlank() && trimmed.contains("=")) {
                        val nameValue = trimmed.split(";")[0].trim()
                        
                        // Key fix: Explicitly set for .blerp.com domain so it persists across subdomains
                        val baseBlerpFlags = "; Domain=.blerp.com; Path=/; Secure; SameSite=None"
                        
                        // Set for base domains to ensure coverage
                        cookieManager.setCookie("https://blerp.com", "$nameValue$baseBlerpFlags")
                        cookieManager.setCookie("https://www.blerp.com", "$nameValue$baseBlerpFlags")
                        
                        // Also set for the current target URL specifically
                        val targetUrl = arguments?.getString("url") ?: "https://blerp.com"
                        cookieManager.setCookie(targetUrl, "$nameValue; Path=/; Secure; SameSite=None")
                    }
                }
            }

            cookieManager.flush()
        } catch (e: Exception) {
            android.util.Log.e("BlerpSheet", "Error in injectCookies", e)
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
    }

    companion object {
        fun newInstance(url: String): BlerpBottomSheetFragment {
            return BlerpBottomSheetFragment().apply {
                arguments = Bundle().apply {
                    putString("url", url)
                }
            }
        }
    }
}
