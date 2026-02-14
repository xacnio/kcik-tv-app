/**
 * File: InternalBrowserSheet.kt
 *
 * Description: Implements a full-screen BottomSheetDialogFragment serving as an internal browser.
 * It configures the WebView, manages layout dimensions to ensure a full-screen experience,
 * and handles user interactions without leaving the application context.
 *
 * Author: Xacnio
 *
 */
package dev.xacnio.kciktv.mobile

import android.annotation.SuppressLint
import android.app.Dialog
import android.content.Context
import android.content.DialogInterface
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import dev.xacnio.kciktv.databinding.BottomSheetBlerpBinding // Using existing layout
import dev.xacnio.kciktv.shared.data.prefs.AppPreferences
import dev.xacnio.kciktv.mobile.ui.player.WebViewManager
import dev.xacnio.kciktv.R

class InternalBrowserSheet : BottomSheetDialogFragment() {

    private var _binding: BottomSheetBlerpBinding? = null
    private val binding get() = _binding!!

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        val dialog = super.onCreateDialog(savedInstanceState) as BottomSheetDialog
        dialog.setOnShowListener {
            val bottomSheet = dialog.findViewById<View>(com.google.android.material.R.id.design_bottom_sheet) as FrameLayout?
            bottomSheet?.let {
                val behavior = BottomSheetBehavior.from(it)
                val displayMetrics = dialog.context.resources.displayMetrics
                val height = displayMetrics.heightPixels // Full Screen
                
                it.layoutParams.height = height
                behavior.peekHeight = height
                behavior.state = BottomSheetBehavior.STATE_EXPANDED
                behavior.skipCollapsed = true
                behavior.isDraggable = false // Prevent dragging to verify full screen feel
            }
        }
        return dialog
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = BottomSheetBlerpBinding.inflate(inflater, container, false)
        
        // Initial Title
        val url = arguments?.getString("url") ?: "https://kick.com"
        binding.sheetTitle.text = android.net.Uri.parse(url).host ?: "In App Browser"
        
        binding.btnClose.setOnClickListener { dismiss() }
        
        binding.btnMenu.setOnClickListener { view ->
            val popup = android.widget.PopupMenu(requireContext(), view)
            
            val currentUrl = binding.blerpWebView.url ?: url
            val pm = requireContext().packageManager
            
            // 1. Refresh
            popup.menu.add("Yenile").setOnMenuItemClickListener {
                binding.blerpWebView.reload()
                true
            }

            // Resolve Default Browser First (to exclude from App search)
            val browserCheckIntent = android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse("https://google.com"))
            val browserResolve = pm.resolveActivity(browserCheckIntent, android.content.pm.PackageManager.MATCH_DEFAULT_ONLY)
            
            var browserPkg: String? = null
            var browserLabel = "Tarayıcı"
            
            if (browserResolve != null) {
                val pkg = browserResolve.activityInfo.packageName
                if (pkg != "android" && !pkg.contains("resolver") && !pkg.contains("start")) {
                     browserPkg = pkg
                     browserLabel = browserResolve.loadLabel(pm).toString()
                }
            }

            // 2. Open in Native App (Use DialogUtils Logic)
            val externalAppData = dev.xacnio.kciktv.mobile.util.DialogUtils.getExternalAppIntent(requireContext(), currentUrl)
            if (externalAppData != null) {
                val (appIntent, _, appLabel) = externalAppData
                popup.menu.add("$appLabel'da Aç").setOnMenuItemClickListener {
                     try {
                         startActivity(appIntent)
                         dismiss()
                     } catch (e: Exception) {}
                     true
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
            
            // 4. Copy Link
            popup.menu.add("Bağlantıyı kopyala").setOnMenuItemClickListener {
                val clipboard = requireContext().getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                val clip = android.content.ClipData.newPlainText("URL", currentUrl)
                clipboard.setPrimaryClip(clip)
                true
            }
            
            // 5. Share
            popup.menu.add("Şununla paylaş...").setOnMenuItemClickListener {
                 try {
                     val shareIntent = android.content.Intent(android.content.Intent.ACTION_SEND)
                     shareIntent.type = "text/plain"
                     shareIntent.putExtra(android.content.Intent.EXTRA_TEXT, currentUrl)
                     startActivity(android.content.Intent.createChooser(shareIntent, "Share via"))
                 } catch (e: Exception) {}
                 true
            }
            
            popup.show()
        }
        
        setupWebView()
        return binding.root
    }

    private fun handleBlerpUrl(context: Context, url: String): Boolean {
        if (url.contains("blerp.com/x/")) {
             val prefs = dev.xacnio.kciktv.shared.data.prefs.AppPreferences(context)
             
             if (prefs.blerpEnabled) {
                 // Close browser and open Blerp Overlay
                 val activity = (context as? android.app.Activity) 
                     ?: (context as? android.content.ContextWrapper)?.baseContext as? android.app.Activity
                 
                 if (activity is dev.xacnio.kciktv.mobile.MobilePlayerActivity) {
                     dismiss()
                     activity.cachedBlerpFragment = dev.xacnio.kciktv.mobile.BlerpBottomSheetFragment.newInstance(url)
                     activity.cachedBlerpFragment?.show(activity.supportFragmentManager, "blerp_sheet")
                     return true
                 }
             } else {
                 // Ask to enable
                 try {
                     androidx.appcompat.app.AlertDialog.Builder(context)
                         .setTitle("Blerp Overlay")
                         .setMessage("Blerp Overlay kapalı. Bu içeriği (ses/efekt) düzgün görüntülemek için açmak ister misiniz?")
                         .setPositiveButton("Evet") { _, _ ->
                             prefs.blerpEnabled = true
                             val activity = (context as? android.app.Activity) 
                                 ?: (context as? android.content.ContextWrapper)?.baseContext as? android.app.Activity
                                 
                             if (activity is dev.xacnio.kciktv.mobile.MobilePlayerActivity) {
                                 dismiss()
                                 activity.cachedBlerpFragment = dev.xacnio.kciktv.mobile.BlerpBottomSheetFragment.newInstance(url)
                                 activity.cachedBlerpFragment?.show(activity.supportFragmentManager, "blerp_sheet")
                             }
                         }
                         .setNegativeButton("Hayır") { _, _ ->
                             binding.blerpWebView.loadUrl(url)
                         }
                         .show()
                     return true // We handled the decision flow (intercepted)
                 } catch (e: Exception) { }
             }
        }
        return false
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun setupWebView() {
        val webView = binding.blerpWebView
        val url = arguments?.getString("url") ?: "https://kick.com"
        val ignoreBlerpLogic = arguments?.getBoolean("ignoreBlerpLogic") ?: false
        
        // Initial Blerp Check - Skip if explicitly ignored
        if (!ignoreBlerpLogic && handleBlerpUrl(requireContext(), url)) {
            return // Stop setup if Blerp handled it
        }

        binding.blerpProgress.visibility = View.VISIBLE
        // ... (rest of setupWebView)

        val cookieManager = CookieManager.getInstance()
        cookieManager.setAcceptCookie(true)
        cookieManager.setAcceptThirdPartyCookies(webView, true)

        // Inject Cookies from Encrypted Prefs
        try {
            val prefs = AppPreferences(requireContext())
            
            // 1. Saved Cookie Groups
            val savedCookies = prefs.savedCookies
            if (savedCookies != null) {
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
            }
            
            // 2. Force Inject Auth Token (Critical for immediate login state)
            val authToken = prefs.authToken
            if (!authToken.isNullOrEmpty()) {
                val sessionCookie = "kick_session=$authToken; Domain=.kick.com; Path=/; Secure; HttpOnly"
                cookieManager.setCookie("https://kick.com", sessionCookie)
                // Also redundant keys just in case
                cookieManager.setCookie("https://kick.com", "session_token=$authToken; Domain=.kick.com; Path=/; Secure")
            }
            
            cookieManager.flush()
            
        } catch (e: Exception) { }

        webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            databaseEnabled = true
            mediaPlaybackRequiresUserGesture = true
            userAgentString = "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/143.0.0.0 Mobile Safari/537.36 OPR/94.0.0.0"
        }

        webView.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView?, url: String?) {
                if (_binding != null) {
                    binding.blerpProgress.visibility = View.GONE
                    
                    // Update Title/Subtitle
                    val title = view?.title
                    val pageUrl = view?.url ?: url
                    
                    if (!title.isNullOrEmpty() && pageUrl != null) {
                         binding.sheetTitle.text = title
                         binding.sheetSubtitle.text = android.net.Uri.parse(pageUrl).host
                         
                         // SSL Lock Icon
                         if (pageUrl.startsWith("https://")) {
                             binding.sslIcon.visibility = View.VISIBLE
                             binding.sslIcon.setColorFilter(android.graphics.Color.parseColor("#4CAF50"))
                         } else {
                             binding.sslIcon.visibility = View.GONE
                         }
                         
                         binding.subtitleContainer.visibility = View.VISIBLE
                    }
                }
                CookieManager.getInstance().flush() 
            }
            
            override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                val url = request?.url?.toString() ?: return false
                
                if (url.startsWith("http://") || url.startsWith("https://")) {
                     // Blerp Handling
                     if (handleBlerpUrl(view?.context ?: requireContext(), url)) {
                         return true
                     }
                     return false // Stay in WebView for standard pages
                }
                
                // Handle Custom Schemes (e.g. snssdk1233://, intent://)
                try {
                    val intent = if (url.startsWith("intent://")) {
                        android.content.Intent.parseUri(url, android.content.Intent.URI_INTENT_SCHEME)
                    } else {
                        android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(url))
                    }
                    
                    intent.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                    
                    val fallbackToMarket = {
                        val pkg = intent.`package`
                        if (!pkg.isNullOrEmpty()) {
                             try {
                                 val marketIntent = android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse("market://details?id=$pkg"))
                                 marketIntent.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                                 startActivity(marketIntent)
                             } catch (e2: Exception) {}
                        }
                    }

                    // Check if this is a user click or automatic redirect
                    val hitTestResult = view?.hitTestResult
                    val isUserClick = hitTestResult != null && hitTestResult.type != WebView.HitTestResult.UNKNOWN_TYPE
                    
                    if (isUserClick) {
                        try {
                            startActivity(intent)
                        } catch (e: Exception) {
                            fallbackToMarket()
                        }
                    } else {
                        // Automatic redirect -> Ask for confirmation
                        androidx.appcompat.app.AlertDialog.Builder(requireContext())
                            .setTitle("Dış Bağlantı Yönlendirmesi")
                            .setMessage("Bu sayfa harici bir uygulama açmak istiyor. İzin veriyor musunuz?")
                            .setPositiveButton("Aç") { _, _ ->
                                try { 
                                    startActivity(intent) 
                                } catch (e: Exception) {
                                    fallbackToMarket()
                                }
                            }
                            .setNegativeButton("İptal", null)
                            .show()
                    }
                    return true 
                } catch (e: Exception) {
                    return true 
                }
            }
        }
        
        val headers = HashMap<String, String>()
        headers["Accept-Language"] = java.util.Locale.getDefault().toLanguageTag()
        webView.loadUrl(url, headers)
    }

    override fun onDismiss(dialog: DialogInterface) {
        super.onDismiss(dialog)
        // Note: We do NOT clear cookies here because CookieManager is global.
        // Clearing it here would break other app features (like WebViewManager background tasks).
        // The app handles security via WebViewManager's specific cleanup tasks.
    }

    companion object {
        fun newInstance(url: String = "https://kick.com", ignoreBlerpLogic: Boolean = false): InternalBrowserSheet {
            return InternalBrowserSheet().apply {
                arguments = Bundle().apply {
                    putString("url", url)
                    putBoolean("ignoreBlerpLogic", ignoreBlerpLogic)
                }
            }
        }
    }
}
