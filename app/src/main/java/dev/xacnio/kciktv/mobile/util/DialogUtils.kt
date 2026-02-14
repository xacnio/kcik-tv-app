/**
 * File: DialogUtils.kt
 *
 * Description: Utility helper class providing static methods for Dialog.
 *
 * Author: Xacnio
 *
 */
package dev.xacnio.kciktv.mobile.util

import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.net.Uri
import android.text.Spannable
import android.text.SpannableStringBuilder
import android.text.style.AbsoluteSizeSpan
import android.text.style.ForegroundColorSpan
import android.text.style.StyleSpan
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import dev.xacnio.kciktv.mobile.MobilePlayerActivity
import dev.xacnio.kciktv.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import com.bumptech.glide.Glide
import org.json.JSONObject

object DialogUtils {
    
    /**
     * Unwraps ContextWrapper to find the base MobilePlayerActivity.
     */
    private fun findMobilePlayerActivity(context: Context): MobilePlayerActivity? {
        var ctx: Context? = context
        while (ctx != null) {
            if (ctx is MobilePlayerActivity) {
                return ctx
            }
            ctx = if (ctx is ContextWrapper) ctx.baseContext else null
        }
        return null
    }

    private fun getKickSlug(url: String): String? {
        // Kick link detection logic
        val kickRegex = Regex("https?://(?:www\\.)?kick\\.com/([a-zA-Z0-9_.-]+)(?:/.*)?", RegexOption.IGNORE_CASE)
        val matchResult = kickRegex.find(url)
        val slug = matchResult?.groupValues?.get(1)
        
        // Exclude common non-channel paths
        val excludedPaths = listOf("categories", "video", "clip", "search", "about", "privacy", "terms", "mobile", "help", "api")
        return if (slug != null && !excludedPaths.contains(slug.lowercase())) slug else null
    }

    fun getExternalAppIntent(context: Context, url: String): Triple<Intent, Any?, String>? {
        val uri = Uri.parse(url)
        val viewIntent = Intent(Intent.ACTION_VIEW, uri)
        viewIntent.addCategory(Intent.CATEGORY_BROWSABLE)
        
        val packageManager = context.packageManager
        
        // 1. Identify "Global Browsers"
        val genericUri = Uri.parse("https://random-link-that-no-app-owns.xyz")
        val genericIntent = Intent(Intent.ACTION_VIEW, genericUri)
        genericIntent.addCategory(Intent.CATEGORY_BROWSABLE)
        
        val browserPackages = packageManager.queryIntentActivities(genericIntent, PackageManager.MATCH_ALL)
            .map { it.activityInfo.packageName }
            .toSet()
        
        // 2. Ask the system: "What is the DEFAULT app for this link?"
        val bestResolve = packageManager.resolveActivity(viewIntent, PackageManager.MATCH_DEFAULT_ONLY)
        val bestPkg = bestResolve?.activityInfo?.packageName
        
        if (bestPkg != null && bestPkg != context.packageName && bestPkg != "android" && !bestPkg.contains("resolver") && !browserPackages.contains(bestPkg)) {
            // We have a clear winner (the system's preferred app)
            val targetIntent = Intent(Intent.ACTION_VIEW, uri)
            targetIntent.setPackage(bestPkg)
            targetIntent.addCategory(Intent.CATEGORY_BROWSABLE)
            targetIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            
            val appIcon = try {
                bestResolve?.loadIcon(packageManager) ?: packageManager.getApplicationIcon(bestPkg)
            } catch (e: Exception) {
                null
            }

            val appLabel = try {
                bestResolve?.loadLabel(packageManager)?.toString() ?: packageManager.getApplicationInfo(bestPkg, 0).loadLabel(packageManager).toString()
            } catch (e: Exception) {
                ""
            }
            
            return Triple(targetIntent, appIcon ?: "", appLabel) 
        }
        
        // 3. No clear default set. Check all possible specialized handlers.
        val allPossibleHandlers = packageManager.queryIntentActivities(viewIntent, PackageManager.MATCH_ALL)
        val specializedHandlers = allPossibleHandlers.filter { resolveInfo ->
            val pkg = resolveInfo.activityInfo.packageName
            pkg != context.packageName && pkg != "android" && !pkg.contains("resolver") && !pkg.contains("fallback") && !browserPackages.contains(pkg)
        }
        
        if (specializedHandlers.isEmpty()) {
            return null
        }
        
        if (specializedHandlers.size == 1) {
            val resolveInfo = specializedHandlers[0]
            val pkg = resolveInfo.activityInfo.packageName
            val targetIntent = Intent(Intent.ACTION_VIEW, uri)
            targetIntent.setPackage(pkg)
            targetIntent.addCategory(Intent.CATEGORY_BROWSABLE)
            targetIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            
            val appIcon = try {
                resolveInfo.loadIcon(packageManager)
            } catch (e: Exception) {
                null
            }

            val appLabel = try {
                resolveInfo.loadLabel(packageManager).toString()
            } catch (e: Exception) {
                ""
            }
            
            return Triple(targetIntent, appIcon ?: "", appLabel)
        } else {
            // Multiple apps, return generic to trigger system chooser
            val genericAppIntent = Intent(Intent.ACTION_VIEW, uri)
            genericAppIntent.addCategory(Intent.CATEGORY_BROWSABLE)
            genericAppIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            
            return Triple(genericAppIntent, "", "") 
        }
    }

    private fun openInBrowser(context: Context, url: String) {
        try {
            val uri = Uri.parse(url)
            val intent = Intent(Intent.ACTION_VIEW, uri)
            intent.addCategory(Intent.CATEGORY_BROWSABLE)
            
            val packageManager = context.packageManager

            // 1. Try to find the system default browser
            val browserTestIntent = Intent(Intent.ACTION_VIEW, Uri.parse("https://www.google.com"))
            browserTestIntent.addCategory(Intent.CATEGORY_BROWSABLE)
            val resolveInfo = packageManager.resolveActivity(browserTestIntent, PackageManager.MATCH_DEFAULT_ONLY)
            
            var targetPackage: String? = null
            
            if (resolveInfo != null && resolveInfo.activityInfo.packageName != "android" && !resolveInfo.activityInfo.packageName.contains("resolver")) {
                targetPackage = resolveInfo.activityInfo.packageName
                android.util.Log.d("LinkDetection", "Default browser found: $targetPackage")
            }

            // 2. If no clear default, try common browser packages
            if (targetPackage == null) {
                val commonBrowsers = listOf("com.android.chrome", "org.mozilla.firefox", "com.android.browser", "com.microsoft.emmx", "com.opera.browser")
                for (pkg in commonBrowsers) {
                    try {
                        packageManager.getPackageInfo(pkg, 0)
                        targetPackage = pkg
                        android.util.Log.d("LinkDetection", "Fallback browser found: $pkg")
                        break
                    } catch (_: Exception) {}
                }
            }

            // 3. Fallback: Request only browsers that handle generic https
            if (targetPackage == null) {
                val handlers = packageManager.queryIntentActivities(browserTestIntent, PackageManager.MATCH_ALL)
                val browserPkg = handlers.firstOrNull { 
                    it.activityInfo.packageName != context.packageName && 
                    it.activityInfo.packageName != "android" && 
                    !it.activityInfo.packageName.contains("resolver") 
                }?.activityInfo?.packageName
                
                if (browserPkg != null) {
                    targetPackage = browserPkg
                    android.util.Log.d("LinkDetection", "Query-based browser found: $targetPackage")
                }
            }

            if (targetPackage != null) {
                intent.setPackage(targetPackage)
            }
            
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
        } catch (e: Exception) {
            android.util.Log.e("LinkDetection", "Failed to force browser, using generic view", e)
            try {
                val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                context.startActivity(intent)
            } catch (_: Exception) {}
        }
    }

    private fun setButtonMultiLineText(button: com.google.android.material.button.MaterialButton, mainText: String, subText: String) {
        val builder = SpannableStringBuilder()
        
        // First line: MAIN LABEL (Bold, Normal Size, Black)
        val mainStart = 0
        builder.append(mainText.uppercase())
        builder.setSpan(StyleSpan(Typeface.BOLD), mainStart, builder.length, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
        builder.setSpan(ForegroundColorSpan(Color.BLACK), mainStart, builder.length, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
        
        // Second line: app name (Thin/Small, Semi-transparent black)
        if (subText.isNotEmpty()) {
            builder.append("\n")
            val subStart = builder.length
            builder.append(subText)
            
            // Small Size (12sp approximate in pixels)
            val density = button.context.resources.displayMetrics.density
            builder.setSpan(AbsoluteSizeSpan((12 * density).toInt()), subStart, builder.length, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
            
            // Semi-transparent black color for subtitle
            builder.setSpan(ForegroundColorSpan(Color.parseColor("#CC000000")), subStart, builder.length, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
            
            // Normal weight (not bold)
            builder.setSpan(StyleSpan(Typeface.NORMAL), subStart, builder.length, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
        
        button.isAllCaps = false // Required for multi-styling and casing
        button.setTextColor(Color.BLACK) // Ensure base text color is black
        button.text = builder
    }
    
    fun showLinkConfirmationDialog(context: Context, url: String) {
        // Intercept Blerp Logic
        if (url.contains("blerp.com/x/")) {
             val activity = findMobilePlayerActivity(context)
             if (activity != null) {
                 val prefs = dev.xacnio.kciktv.shared.data.prefs.AppPreferences(context)
                 if (prefs.blerpEnabled) {
                     activity.cachedBlerpFragment = dev.xacnio.kciktv.mobile.BlerpBottomSheetFragment.newInstance(url)
                     activity.cachedBlerpFragment?.show(activity.supportFragmentManager, "blerp_sheet")
                     return
                 } else {
                     // Show Dialog
                      try {
                         androidx.appcompat.app.AlertDialog.Builder(context)
                             .setTitle("Blerp Overlay")
                             .setMessage("Blerp Overlay kapalı. Bu içeriği (ses/efekt) düzgün görüntülemek için açmak ister misiniz?")
                             .setPositiveButton("Evet") { _, _ ->
                                 prefs.blerpEnabled = true
                                 activity.cachedBlerpFragment = dev.xacnio.kciktv.mobile.BlerpBottomSheetFragment.newInstance(url)
                                 activity.cachedBlerpFragment?.show(activity.supportFragmentManager, "blerp_sheet")
                             }
                             .setNegativeButton("Hayır") { _, _ ->
                                 // Fallback to normal flow (show link confirmation)
                                 showLinkConfirmationDialogInternal(context, url)
                             }
                             .setCancelable(true)
                             .show()
                         return
                      } catch (e: Exception) {}
                 }
             }
        }
        showLinkConfirmationDialogInternal(context, url)
    }

    private fun showLinkConfirmationDialogInternal(context: Context, url: String) {
        try {
            val dialogView = android.view.LayoutInflater.from(context).inflate(dev.xacnio.kciktv.R.layout.dialog_link_confirmation, null)
            val dialog = com.google.android.material.dialog.MaterialAlertDialogBuilder(context, dev.xacnio.kciktv.R.style.Theme_KcikTV_Dialog)
                .setView(dialogView)
                .create()
                
            // Fix rounded corners by making the window background transparent
            dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)

            val urlText = dialogView.findViewById<android.widget.TextView>(dev.xacnio.kciktv.R.id.urlText)
            val btnVisit = dialogView.findViewById<com.google.android.material.button.MaterialButton>(dev.xacnio.kciktv.R.id.btnVisit)
            val btnOpenInApp = dialogView.findViewById<com.google.android.material.button.MaterialButton>(dev.xacnio.kciktv.R.id.btnOpenInApp)
            val btnCancel = dialogView.findViewById<android.view.View>(dev.xacnio.kciktv.R.id.btnCancel)
            val dialogIcon = dialogView.findViewById<android.widget.ImageView>(dev.xacnio.kciktv.R.id.dialogIcon)
            
            // Preview Views
            val previewImage = dialogView.findViewById<android.widget.ImageView>(dev.xacnio.kciktv.R.id.previewImage)
            val previewFavicon = dialogView.findViewById<android.widget.ImageView>(dev.xacnio.kciktv.R.id.previewFavicon)
            val titleContainer = dialogView.findViewById<android.view.View>(dev.xacnio.kciktv.R.id.titleContainer)
            val previewTitle = dialogView.findViewById<android.widget.TextView>(dev.xacnio.kciktv.R.id.previewTitle)
            val previewDesc = dialogView.findViewById<android.widget.TextView>(dev.xacnio.kciktv.R.id.previewDesc)

            // Setup top icon with real app icon to match home screen exactly
            try {
                val appIcon = context.packageManager.getApplicationIcon(context.packageName)
                dialogIcon.setImageDrawable(appIcon)
            } catch (e: Exception) {
                dialogIcon.setImageResource(dev.xacnio.kciktv.R.mipmap.ic_launcher_green)
            }
            dialogIcon.background = null
            dialogIcon.setPadding(0, 0, 0, 0)
            dialogIcon.clearColorFilter()

            urlText.text = url
            
            // --- Link Preview Logic ---
            val prefs = dev.xacnio.kciktv.shared.data.prefs.AppPreferences(context)
            val isTrusted = prefs.trustedDomains.any { url.contains(it, ignoreCase = true) }

            // Loading shimmer views
            val previewLoading = dialogView.findViewById<android.view.View>(dev.xacnio.kciktv.R.id.previewLoading)
            val shimmerImage = dialogView.findViewById<android.view.View>(dev.xacnio.kciktv.R.id.shimmerImage)
            val shimmerTitle = dialogView.findViewById<android.view.View>(dev.xacnio.kciktv.R.id.shimmerTitle)
            val shimmerDesc = dialogView.findViewById<android.view.View>(dev.xacnio.kciktv.R.id.shimmerDesc)
            val untrustedContainer = dialogView.findViewById<android.view.View>(dev.xacnio.kciktv.R.id.untrustedContainer)
            val btnTrustAndPreview = dialogView.findViewById<android.view.View>(dev.xacnio.kciktv.R.id.btnTrustAndPreview)

            // Shimmer pulse animation helper
            fun startShimmerAnimation() {
                val shimmerViews = listOfNotNull(shimmerImage, shimmerTitle, shimmerDesc)
                for ((index, view) in shimmerViews.withIndex()) {
                    val animator = android.animation.ObjectAnimator.ofFloat(view, "alpha", 0.3f, 0.7f).apply {
                        duration = 800
                        repeatMode = android.animation.ValueAnimator.REVERSE
                        repeatCount = android.animation.ValueAnimator.INFINITE
                        startDelay = (index * 120).toLong()
                        interpolator = android.view.animation.AccelerateDecelerateInterpolator()
                    }
                    animator.start()
                    // Tag the animator so we can cancel it later
                    view.setTag(dev.xacnio.kciktv.R.id.settingIcon, animator)
                }
            }

            fun stopShimmerAnimation() {
                val shimmerViews = listOfNotNull(shimmerImage, shimmerTitle, shimmerDesc)
                for (view in shimmerViews) {
                    val animator = view.getTag(dev.xacnio.kciktv.R.id.settingIcon) as? android.animation.ObjectAnimator
                    animator?.cancel()
                    view.alpha = 1f
                }
            }
            
            suspend fun applyMetadataToUi(title: String?, desc: String?, image: String?, favicon: String?) {
                fun resolveUrl(baseUrl: String, extractUrl: String?): String? {
                    if (extractUrl == null || extractUrl.isBlank()) return null
                    if (extractUrl.startsWith("http")) return extractUrl
                    return try {
                        val base = baseUrl.toHttpUrlOrNull()
                        base?.resolve(extractUrl)?.toString()
                    } catch (e: Exception) { null }
                }

                fun decodeHtml(text: String?): String? {
                    if (text == null) return null
                    return if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.N) {
                        android.text.Html.fromHtml(text, android.text.Html.FROM_HTML_MODE_COMPACT).toString()
                    } else {
                        @Suppress("DEPRECATION")
                        android.text.Html.fromHtml(text).toString()
                    }
                }

                val finalTitle = decodeHtml(title)
                val finalDesc = decodeHtml(desc)
                val finalFavicon = favicon ?: resolveUrl(url, "/favicon.ico")

                kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                    stopShimmerAnimation()
                    previewLoading?.animate()?.alpha(0f)?.setDuration(200)?.withEndAction { previewLoading.visibility = android.view.View.GONE }?.start()

                    if (!finalTitle.isNullOrBlank()) {
                        titleContainer?.alpha = 0f
                        previewTitle.text = finalTitle
                        titleContainer?.visibility = android.view.View.VISIBLE
                        
                        if (!finalFavicon.isNullOrBlank()) {
                            previewFavicon.visibility = android.view.View.VISIBLE
                            com.bumptech.glide.Glide.with(context)
                                .load(finalFavicon as String)
                                .into(previewFavicon)
                        } else {
                            previewFavicon.visibility = android.view.View.GONE
                        }
                        
                        titleContainer?.animate()?.alpha(1f)?.setDuration(300)?.setStartDelay(150)?.start()
                    }
                    
                    if (!finalDesc.isNullOrBlank()) {
                        previewDesc.alpha = 0f
                        previewDesc.text = finalDesc
                        previewDesc.visibility = android.view.View.VISIBLE
                        previewDesc.animate().alpha(1f).setDuration(300).setStartDelay(200).start()
                    }
                    
                    // Show thumbnail if it's available
                    if (!image.isNullOrBlank()) {
                        previewImage.alpha = 0f
                        previewImage.visibility = android.view.View.VISIBLE
                        com.bumptech.glide.Glide.with(context)
                            .load(image)
                            .placeholder(dev.xacnio.kciktv.R.drawable.placeholder_thumbnail)
                            .centerCrop()
                            .into(previewImage)
                        previewImage.animate().alpha(1f).setDuration(300).setStartDelay(100).start()
                    }
                }
            }

            // Helper to resolve URL outside of applyMetadataToUi if needed (though JS handles it mostly)
            fun resolveUrl(baseUrl: String, extractUrl: String?): String? {
                if (extractUrl == null || extractUrl.isBlank()) return null
                if (extractUrl.startsWith("http")) return extractUrl
                return try {
                    val base = baseUrl.toHttpUrlOrNull()
                    base?.resolve(extractUrl)?.toString()
                } catch (e: Exception) { null }
            }
            
            fun fetchPreview() {
                previewLoading?.visibility = android.view.View.VISIBLE
                untrustedContainer?.visibility = android.view.View.GONE
                startShimmerAnimation()

                kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.IO).launch {
                    try {
                        // Use the "Magic" User-Agent for Social Media Previews
                        val document = org.jsoup.Jsoup.connect(url)
                            .userAgent("facebookexternalhit/1.1;line-poker/1.0") 
                            .timeout(10000)
                            .get()

                        val baseUri = document.baseUri() ?: url
                        
                        // Helper to resolve relative URLs from meta tags properties
                        fun resolve(path: String): String {
                            if (path.isEmpty()) return ""
                            return try {
                                if (path.startsWith("http")) path else java.net.URL(java.net.URL(baseUri), path).toString()
                            } catch (e: Exception) { path }
                        }

                        // Extract Title
                        var title = document.select("meta[property=og:title]").attr("content")
                        if (title.isEmpty()) title = document.select("meta[name=twitter:title]").attr("content")
                        if (title.isEmpty()) title = document.title()

                        // Extract Description
                        var desc = document.select("meta[property=og:description]").attr("content")
                        if (desc.isEmpty()) desc = document.select("meta[name=twitter:description]").attr("content")
                        if (desc.isEmpty()) desc = document.select("meta[name=description]").attr("content")

                        // Extract Image (prioritize OG/Twitter) ensures absolute URL
                        var image = resolve(document.select("meta[property=og:image]").attr("content"))
                        if (image.isEmpty()) image = resolve(document.select("meta[name=twitter:image]").attr("content"))
                        if (image.isEmpty()) image = resolve(document.select("meta[property=twitter:image]").attr("content"))
                        if (image.isEmpty()) image = resolve(document.select("meta[name=twitter:image:src]").attr("content"))
                        if (image.isEmpty()) image = resolve(document.select("meta[property=og:image:secure_url]").attr("content"))
                        if (image.isEmpty()) image = document.select("link[rel=image_src]").attr("abs:href")

                        // Extract Favicon (More robust support + Absolute URLs)
                        // Priority: Apple Touch > Precomposed > Fluid > Shortcut > Icon > MS Tiles
                        var favicon = document.select("link[rel='apple-touch-icon']").attr("abs:href")
                        if (favicon.isEmpty()) favicon = document.select("link[rel='apple-touch-icon-precomposed']").attr("abs:href")
                        if (favicon.isEmpty()) favicon = document.select("link[rel='fluid-icon']").attr("abs:href")
                        if (favicon.isEmpty()) favicon = document.select("link[rel='shortcut icon']").attr("abs:href")
                        if (favicon.isEmpty()) favicon = document.select("link[rel='icon']").attr("abs:href")
                        if (favicon.isEmpty()) favicon = document.select("link[rel='mask-icon']").attr("abs:href")
                        
                        // Microsoft Tile Fallbacks (resolve manually as they use 'content')
                        if (favicon.isEmpty()) favicon = resolve(document.select("meta[name='msapplication-TileImage']").attr("content"))
                        if (favicon.isEmpty()) favicon = resolve(document.select("meta[name='msapplication-square310x310logo']").attr("content"))
                        if (favicon.isEmpty()) favicon = resolve(document.select("meta[name='msapplication-square150x150logo']").attr("content"))
                        if (favicon.isEmpty()) favicon = resolve(document.select("meta[name='msapplication-square70x70logo']").attr("content"))
                        
                        // Fallback to default /favicon.ico if nothing else found
                        if (favicon.isEmpty()) {
                            try {
                                favicon = java.net.URL(java.net.URL(baseUri), "/favicon.ico").toString()
                            } catch (e: Exception) {}
                        }

                        // Last resort: use the main image as favicon if nothing else found (and no favicon.ico guessed)
                        if (favicon.isEmpty() && image.isNotEmpty()) favicon = image

                        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                            applyMetadataToUi(title, desc, image, favicon)
                        }

                    } catch (e: Exception) {
                        e.printStackTrace()
                        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                            stopShimmerAnimation()
                            previewLoading?.visibility = android.view.View.GONE
                        }
                    }
                }
            }

            if (isTrusted) {
                fetchPreview()
            } else {
                untrustedContainer?.visibility = android.view.View.VISIBLE
                btnTrustAndPreview?.setOnClickListener {
                    try {
                        val uri = Uri.parse(url)
                        val host = uri.host
                        if (host != null) {
                            val currentDomains = prefs.trustedDomains.toMutableSet()
                            currentDomains.add(host)
                            prefs.trustedDomains = currentDomains
                            fetchPreview()
                        }
                    } catch (e: Exception) {
                        fetchPreview()
                    }
                }
            }

            val slug = getKickSlug(url)
            val externalAppData = getExternalAppIntent(context, url)

            if (slug != null) {
                btnOpenInApp.visibility = android.view.View.VISIBLE
                
                // Set multiline text with app name inside
                setButtonMultiLineText(btnOpenInApp, context.getString(R.string.open_in_app), context.getString(R.string.app_name))

                // Use system-loaded app icon for internal Kick links
                try {
                    btnOpenInApp.icon = context.packageManager.getApplicationIcon(context.packageName)
                } catch (e: Exception) {
                    btnOpenInApp.icon = context.getDrawable(dev.xacnio.kciktv.R.mipmap.ic_launcher_green)
                }
                btnOpenInApp.iconTint = null
                btnOpenInApp.iconSize = (24 * context.resources.displayMetrics.density).toInt()
                btnOpenInApp.iconPadding = (8 * context.resources.displayMetrics.density).toInt()
                btnOpenInApp.iconGravity = com.google.android.material.button.MaterialButton.ICON_GRAVITY_TOP
                
                btnOpenInApp.setOnClickListener {
                    findMobilePlayerActivity(context)?.let { activity ->
                        activity.channelLoadManager.loadChannelBySlug(slug)
                    }
                    dialog.dismiss()
                }
            } else if (externalAppData != null) {
                btnOpenInApp.visibility = android.view.View.VISIBLE
                
                // Set multiline text with the specific app name
                val appLabel = externalAppData.third
                setButtonMultiLineText(btnOpenInApp, context.getString(R.string.open_in_app), if (appLabel.isNotEmpty()) appLabel else "")
                
                // Show App Icon if available
                val icon = externalAppData.second
                if (icon is android.graphics.drawable.Drawable) {
                    // Disable tinting to show the original colored app icon
                    btnOpenInApp.iconTint = null 
                    btnOpenInApp.icon = icon
                    btnOpenInApp.iconSize = (24 * context.resources.displayMetrics.density).toInt()
                    btnOpenInApp.iconPadding = (8 * context.resources.displayMetrics.density).toInt()
                    btnOpenInApp.iconGravity = com.google.android.material.button.MaterialButton.ICON_GRAVITY_TOP
                } else {
                    btnOpenInApp.icon = null
                }

                btnOpenInApp.setOnClickListener {
                    try {
                        findMobilePlayerActivity(context)?.enterPiPNow()
                        context.startActivity(externalAppData.first)
                    } catch (e: Exception) {
                        android.util.Log.e("LinkDetection", "Failed to launch app", e)
                        Toast.makeText(context, context.getString(dev.xacnio.kciktv.R.string.link_open_error, e.message), Toast.LENGTH_SHORT).show()
                    }
                    dialog.dismiss()
                }
            } else {
                btnOpenInApp.visibility = android.view.View.GONE
            }

            val btnInternal = dialogView.findViewById<android.view.View>(dev.xacnio.kciktv.R.id.btnOpenInInAppBrowser)
            btnInternal?.setOnClickListener {
                val activity = findMobilePlayerActivity(context)
                if (activity != null) {
                     if (url.contains("blerp.com/x/")) {
                         val prefs = dev.xacnio.kciktv.shared.data.prefs.AppPreferences(context)
                         if (prefs.blerpEnabled) {
                             activity.cachedBlerpFragment = dev.xacnio.kciktv.mobile.BlerpBottomSheetFragment.newInstance(url)
                             activity.cachedBlerpFragment?.show(activity.supportFragmentManager, "blerp_sheet")
                             dialog.dismiss()
                             return@setOnClickListener
                         }
                     }
                     dev.xacnio.kciktv.mobile.InternalBrowserSheet.newInstance(url).show(activity.supportFragmentManager, "InternalBrowserSheet")
                }
                dialog.dismiss()
            }

            btnVisit.setOnClickListener {
                try {
                    findMobilePlayerActivity(context)?.enterPiPNow()
                    openInBrowser(context, url)
                } catch (e: Exception) {
                    Toast.makeText(context, context.getString(dev.xacnio.kciktv.R.string.link_open_error, e.message), Toast.LENGTH_SHORT).show()
                }
                dialog.dismiss()
            }

            btnCancel.setOnClickListener {
                dialog.dismiss()
            }

            dialog.show()
        } catch (e: Exception) {
            // Fallback
            try {
                findMobilePlayerActivity(context)?.updatePiPUi()
                val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                context.startActivity(intent)
            } catch (_: Exception) {}
        }
    }
    fun showLinkOptionsBottomSheet(context: Context, url: String) {
        val bottomSheetDialog = com.google.android.material.bottomsheet.BottomSheetDialog(context)
        val view = android.view.LayoutInflater.from(context).inflate(dev.xacnio.kciktv.R.layout.bottom_sheet_link_options, null)
        bottomSheetDialog.setContentView(view)

        // Title
        val titleView = view.findViewById<android.widget.TextView>(dev.xacnio.kciktv.R.id.sheetTitle)
        titleView?.text = url

        // 0. Preview Views
        // Kick Channel (Avatar + Text)
        val kickPreviewContainer = view.findViewById<android.view.View>(dev.xacnio.kciktv.R.id.kickPreviewContainer)
        val previewTitle = view.findViewById<android.widget.TextView>(dev.xacnio.kciktv.R.id.previewTitle)
        val previewDesc = view.findViewById<android.widget.TextView>(dev.xacnio.kciktv.R.id.previewDescription)
        val previewImage = view.findViewById<android.widget.ImageView>(dev.xacnio.kciktv.R.id.previewImage)

        // Trusted Link (Vertical Card)
        val trustedPreviewCard = view.findViewById<android.view.View>(dev.xacnio.kciktv.R.id.trustedPreviewCard)
        val trustedImage = view.findViewById<android.widget.ImageView>(dev.xacnio.kciktv.R.id.trustedImage)
        val trustedTitle = view.findViewById<android.widget.TextView>(dev.xacnio.kciktv.R.id.trustedTitle)
        val trustedDesc = view.findViewById<android.widget.TextView>(dev.xacnio.kciktv.R.id.trustedDesc)
        val trustedFavicon = view.findViewById<android.widget.ImageView>(dev.xacnio.kciktv.R.id.trustedFavicon)

        val prefs = dev.xacnio.kciktv.shared.data.prefs.AppPreferences(context)
        val isTrusted = prefs.trustedDomains.any { url.contains(it, ignoreCase = true) }
        val slug = getKickSlug(url)
        val btnTrustDomain = view.findViewById<android.view.View>(dev.xacnio.kciktv.R.id.btnTrustDomain)

        fun startPreviewFetch() {
             // Hide both initially to reset state
             kickPreviewContainer?.visibility = View.GONE
             trustedPreviewCard?.visibility = View.GONE
             trustedFavicon?.visibility = View.GONE
             
             if (slug != null) {
                 // KICK CHANNEL PREVIEW -> Horizontal Avatar Layout
                 if (kickPreviewContainer == null) return
                 kickPreviewContainer.visibility = View.VISIBLE
                 
                 previewTitle?.text = slug.replaceFirstChar { it.uppercase() }
                 previewDesc?.text = "Kick Channel"
                 
                 CoroutineScope(Dispatchers.Main).launch {
                     try {
                         withContext(Dispatchers.IO) {
                             val request = Request.Builder().url("https://kick.com/api/v1/channels/$slug").build()
                             val response = OkHttpClient().newCall(request).execute()
                             val json = response.body?.string()
                             if (json != null) {
                                 val jsonObj = JSONObject(json)
                                 val user = jsonObj.optJSONObject("user")
                                 val realName = user?.optString("username") ?: jsonObj.optString("slug")
                                 val profilePic = user?.optString("profile_pic")
                                 
                                 if (realName.isNotEmpty() || profilePic != null) {
                                     withContext(Dispatchers.Main) {
                                         if (realName.isNotEmpty()) previewTitle?.text = realName
                                         if (profilePic != null) {
                                             Glide.with(context).load(profilePic).circleCrop().into(previewImage!!)
                                             previewImage?.setColorFilter(null)
                                         }
                                     }
                                 }
                             }
                         }
                     } catch (e: Exception) {}
                 }
             } else {
                 // GENERIC TRUSTED PREVIEW -> Vertical Card Layout
                 if (trustedPreviewCard == null) return
                 trustedPreviewCard.visibility = View.VISIBLE
                 
                 trustedTitle?.text = "Loading preview..." 
                 trustedDesc?.text = url
                 trustedImage?.setImageResource(dev.xacnio.kciktv.R.drawable.placeholder_thumbnail)
                 trustedFavicon?.visibility = View.GONE
                 
                 CoroutineScope(Dispatchers.IO).launch {
                     try {
                        val document = org.jsoup.Jsoup.connect(url)
                            .userAgent("facebookexternalhit/1.1;line-poker/1.0") 
                            .timeout(10000)
                            .get()
                        
                        val baseUri = document.baseUri() ?: url
                        fun resolve(path: String): String {
                            if (path.isEmpty()) return ""
                            return try {
                                if (path.startsWith("http")) path else java.net.URL(java.net.URL(baseUri), path).toString()
                            } catch (e: Exception) { path }
                        }

                        var title = document.select("meta[property=og:title]").attr("content")
                        if (title.isEmpty()) title = document.title()

                        var desc = document.select("meta[property=og:description]").attr("content")
                        
                        var image = resolve(document.select("meta[property=og:image]").attr("content"))
                        if (image.isEmpty()) image = document.select("link[rel=image_src]").attr("abs:href")
                        if (image.isEmpty()) image = resolve(document.select("meta[name=twitter:image]").attr("content"))

                        // Favicon Extraction
                        var favicon = document.select("link[rel='apple-touch-icon']").attr("abs:href")
                        if (favicon.isEmpty()) favicon = document.select("link[rel='shortcut icon']").attr("abs:href")
                        if (favicon.isEmpty()) favicon = document.select("link[rel='icon']").attr("abs:href")
                        if (favicon.isEmpty()) {
                            try { favicon = java.net.URL(java.net.URL(baseUri), "/favicon.ico").toString() } catch (e: Exception) {}
                        }

                        withContext(Dispatchers.Main) {
                            if (title.isEmpty() && desc.isEmpty() && image.isEmpty()) {
                                trustedPreviewCard.visibility = android.view.View.GONE
                            } else {
                                if (title.isNotEmpty()) trustedTitle?.text = title
                                if (desc.isNotEmpty()) trustedDesc?.text = desc
                                
                                if (image.isNotEmpty()) {
                                    Glide.with(context)
                                         .load(image)
                                         .centerCrop()
                                         .placeholder(dev.xacnio.kciktv.R.drawable.placeholder_thumbnail)
                                         .into(trustedImage!!)
                                }
                                
                                if (favicon.isNotEmpty()) {
                                    trustedFavicon?.visibility = View.VISIBLE
                                    Glide.with(context).load(favicon).circleCrop().into(trustedFavicon!!)
                                }
                            }
                        }
                     } catch (e: Exception) {
                         withContext(Dispatchers.Main) {
                             trustedPreviewCard.visibility = android.view.View.GONE
                         }
                     }
                 }
             }
        }

        if ((slug != null || isTrusted)) {
             startPreviewFetch()
        } else {
             // Untrusted
             btnTrustDomain?.visibility = android.view.View.VISIBLE
             btnTrustDomain?.setOnClickListener {
                 try {
                     val uri = Uri.parse(url)
                     val host = uri.host
                     if (host != null) {
                         val current = prefs.trustedDomains.toMutableSet()
                         current.add(host)
                         prefs.trustedDomains = current
                         btnTrustDomain.visibility = android.view.View.GONE
                         startPreviewFetch()
                     }
                 } catch (e: Exception) {}
             }
        }

        // 1. Copy URL
        view.findViewById<android.view.View>(dev.xacnio.kciktv.R.id.btnCopyUrl)?.setOnClickListener {
            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
            val clip = android.content.ClipData.newPlainText("URL", url)
            clipboard.setPrimaryClip(clip)
            if (android.os.Build.VERSION.SDK_INT <= android.os.Build.VERSION_CODES.S_V2) {
                Toast.makeText(context, "Copied to clipboard", Toast.LENGTH_SHORT).show()
            }
            bottomSheetDialog.dismiss()
        }

        // 2. Open In In-App Browser (WebView/InternalBrowserSheet)
        view.findViewById<android.view.View>(dev.xacnio.kciktv.R.id.btnInAppBrowser)?.setOnClickListener {
            bottomSheetDialog.dismiss()
            val activity = findMobilePlayerActivity(context)
            
            if (activity != null) {
                // Intercept Blerp Logic Here to avoid double-opening
                if (url.contains("blerp.com/x/")) {
                     val prefs = dev.xacnio.kciktv.shared.data.prefs.AppPreferences(context)
                     if (prefs.blerpEnabled) {
                         activity.cachedBlerpFragment = dev.xacnio.kciktv.mobile.BlerpBottomSheetFragment.newInstance(url)
                         activity.cachedBlerpFragment?.show(activity.supportFragmentManager, "blerp_sheet")
                         return@setOnClickListener
                     } else {
                         // Show Dialog
                          try {
                             androidx.appcompat.app.AlertDialog.Builder(context)
                                 .setTitle("Blerp Overlay")
                                 .setMessage("Blerp Overlay kapalı. Bu içeriği (ses/efekt) düzgün görüntülemek için açmak ister misiniz?")
                                 .setPositiveButton("Evet") { _, _ ->
                                     prefs.blerpEnabled = true
                                     activity.cachedBlerpFragment = dev.xacnio.kciktv.mobile.BlerpBottomSheetFragment.newInstance(url)
                                     activity.cachedBlerpFragment?.show(activity.supportFragmentManager, "blerp_sheet")
                                 }
                                 .setNegativeButton("Hayır") { _, _ ->
                                     // Open Internal Browser as fallback
                                     dev.xacnio.kciktv.mobile.InternalBrowserSheet.newInstance(url, true).show(activity.supportFragmentManager, "InternalBrowserSheet")
                                 }
                                 .show()
                             return@setOnClickListener
                         } catch (e: Exception) {}
                     }
                }
                
                dev.xacnio.kciktv.mobile.InternalBrowserSheet.newInstance(url).show(activity.supportFragmentManager, "InternalBrowserSheet")
            } else {
                openInBrowser(context, url) // Fallback if activity context issues
            }
        }

        // 3. Open In App (e.g. Instagram)
        val btnOpenInApp = view.findViewById<android.view.View>(dev.xacnio.kciktv.R.id.btnOpenInApp)
        val iconOpenInApp = view.findViewById<android.widget.ImageView>(dev.xacnio.kciktv.R.id.iconOpenInApp)
        val textOpenInApp = view.findViewById<android.widget.TextView>(dev.xacnio.kciktv.R.id.textOpenInApp)
        
        val externalAppData = getExternalAppIntent(context, url)
        if (externalAppData != null && btnOpenInApp != null && iconOpenInApp != null && textOpenInApp != null) {
            val (intent, icon, label) = externalAppData
            btnOpenInApp.visibility = android.view.View.VISIBLE
            // Use hardcoded string to ensure safety if resource missing
            textOpenInApp.text = "Open in $label"
            
            if (icon is android.graphics.drawable.Drawable) {
                iconOpenInApp.setImageDrawable(icon)
            } else {
                // Fallback icon
                 try {
                    iconOpenInApp.setImageResource(dev.xacnio.kciktv.R.mipmap.ic_launcher_green)
                } catch (e: Exception) { /* Ignore if missing */ }
            }
            
            btnOpenInApp.setOnClickListener {
                try {
                    findMobilePlayerActivity(context)?.enterPiPNow()
                    context.startActivity(intent)
                } catch (e: Exception) {
                    Toast.makeText(context, "Failed to open app: ${e.message}", Toast.LENGTH_SHORT).show()
                }
                bottomSheetDialog.dismiss()
            }
        } else {
            btnOpenInApp?.visibility = android.view.View.GONE
        }

        // 4. Open In External Browser (Default Browser)
        val btnExternal = view.findViewById<android.view.View>(dev.xacnio.kciktv.R.id.btnOpenInExternalBrowser)
        val iconExternal = view.findViewById<android.widget.ImageView>(dev.xacnio.kciktv.R.id.iconExternalBrowser)
        val textExternal = view.findViewById<android.widget.TextView>(dev.xacnio.kciktv.R.id.textOpenInExternalBrowser)
        
        // Find default browser name
        val browserIntent = Intent(Intent.ACTION_VIEW, Uri.parse("https://google.com"))
        val resolveInfo = context.packageManager.resolveActivity(browserIntent, PackageManager.MATCH_DEFAULT_ONLY)
        val browserLabel = resolveInfo?.loadLabel(context.packageManager)?.toString() ?: "Browser"
        
        textExternal?.text = "Open in $browserLabel"
        try {
            val icon = resolveInfo?.loadIcon(context.packageManager)
            if (icon != null) iconExternal?.setImageDrawable(icon)
        } catch (e: Exception) {}

        btnExternal?.setOnClickListener {
            try {
                findMobilePlayerActivity(context)?.enterPiPNow()
                val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
                intent.addCategory(Intent.CATEGORY_BROWSABLE)
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                // Force open in the detected browser package to avoid app interception loops
                if (resolveInfo != null) {
                    intent.setPackage(resolveInfo.activityInfo.packageName)
                }
                context.startActivity(intent)
            } catch (e: Exception) {
                // If specific package fails, try generic intent
                try {
                    val fallbackIntent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
                    fallbackIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    context.startActivity(fallbackIntent)
                } catch (e2: Exception) {
                    Toast.makeText(context, "Failed to open browser", Toast.LENGTH_SHORT).show()
                }
            }
            bottomSheetDialog.dismiss()
        }

        bottomSheetDialog.show()
    }

    fun setupClickableLinks(textView: android.widget.TextView) {
        val context = textView.context
        val text = textView.text
        if (text == null || text.isEmpty()) return

        val spannable = android.text.SpannableString(text)
        android.text.util.Linkify.addLinks(spannable, android.text.util.Linkify.WEB_URLS)
        
        val spans = spannable.getSpans(0, spannable.length, android.text.style.URLSpan::class.java)
        if (spans.isEmpty()) return

        for (span in spans) {
            val start = spannable.getSpanStart(span)
            val end = spannable.getSpanEnd(span)
            val url = span.url
            
            spannable.removeSpan(span)
            // Use custom span that exposes the URL
            spannable.setSpan(UrlClickableSpan(context, url), start, end, android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
        
        textView.text = spannable
        textView.movementMethod = android.text.method.LinkMovementMethod.getInstance()
        textView.highlightColor = android.graphics.Color.TRANSPARENT

        // Capture touch coordinates for finding the link on Long Click
        textView.setOnTouchListener { v, event ->
             if (event.action == android.view.MotionEvent.ACTION_DOWN) {
                 v.tag = android.graphics.PointF(event.x, event.y)
             }
             false // Let LinkMovementMethod handle clicks
        }
        
        // Aggressive Custom Long Press Handler
        // We use a shorter timeout (400ms) to beat the system/adapter's LongClickListener (500ms).
        // This ensures we catch the gesture, show the sheet, and CANCEL the system behavior before it triggers 'Message Copied'.
        textView.setOnTouchListener(object : android.view.View.OnTouchListener {
            private val handler = android.os.Handler(android.os.Looper.getMainLooper())
            private val longPressTimeout = 400L // System is 500ms. We win race.
            private var isLongPressTriggered = false
            private var downX = 0f
            private var downY = 0f
            
            private val longPressRunnable = Runnable {
                isLongPressTriggered = true
                val tv = textView
                var targetUrl: String? = null
                
                // 1. Precise Touch Match (at touch-down location)
                val x = downX.toInt() - tv.totalPaddingLeft + tv.scrollX
                val y = downY.toInt() - tv.totalPaddingTop + tv.scrollY
                val layout = tv.layout
                
                if (layout != null && tv.text is android.text.Spanned) {
                    val line = layout.getLineForVertical(y)
                    val offset = layout.getOffsetForHorizontal(line, x.toFloat())
                    val spannedText = tv.text as android.text.Spanned
                    val linkSpans = spannedText.getSpans(offset, offset, UrlClickableSpan::class.java)
                    
                    if (linkSpans.isNotEmpty()) {
                         val span = linkSpans[0]
                         val start = spannedText.getSpanStart(span)
                         val end = spannedText.getSpanEnd(span)
                         if (offset >= start && offset <= end) {
                             targetUrl = span.url
                         }
                    }
                }

                // 2. Fallback: Any Link Span
                if (targetUrl == null && tv.text is android.text.Spanned) {
                    val spannedText = tv.text as android.text.Spanned
                    val allSpans = spannedText.getSpans(0, spannedText.length, UrlClickableSpan::class.java)
                    if (allSpans.isNotEmpty()) {
                        targetUrl = allSpans[0].url
                    }
                }

                // 3. Regex Fallback
                if (targetUrl == null) {
                    val matcher = android.util.Patterns.WEB_URL.matcher(tv.text)
                    if (matcher.find()) {
                        targetUrl = matcher.group()
                    }
                }

                if (targetUrl != null) {
                    showLinkOptionsBottomSheet(context, targetUrl)
                    tv.performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS)
                    tv.cancelLongPress() // KEY: Stop system from firing 'Message Copied'
                }
            }

            override fun onTouch(v: View, event: android.view.MotionEvent): Boolean {
                 when (event.action) {
                     android.view.MotionEvent.ACTION_DOWN -> {
                         isLongPressTriggered = false
                         downX = event.x
                         downY = event.y
                         handler.postDelayed(longPressRunnable, longPressTimeout)
                     }
                     android.view.MotionEvent.ACTION_UP, android.view.MotionEvent.ACTION_CANCEL -> {
                         handler.removeCallbacks(longPressRunnable)
                         if (isLongPressTriggered) return true // Consume UP if we handled long press (prevent Click)
                     }
                     android.view.MotionEvent.ACTION_MOVE -> {
                         if (!isLongPressTriggered) {
                             val dx = Math.abs(event.x - downX)
                             val dy = Math.abs(event.y - downY)
                             val slop = android.view.ViewConfiguration.get(context).scaledTouchSlop
                             if (dx > slop || dy > slop) {
                                 handler.removeCallbacks(longPressRunnable)
                                 isLongPressTriggered = false // Cancel detection
                             }
                         }
                     }
                 }
                 
                 if (isLongPressTriggered) {
                     return true // Consume events if we are in 'handled' state
                 }
                 
                 // Return false so LinkMovementMethod can handle clicks (ACTION_UP)
                 return false 
            }
        })
    }

    // Public so ChatAdapter can set it when a long press is handled
    var lastLinkLongPressTime: Long = 0
    var lastLinkClickTime: Long = 0

    // Custom Span class to hold URL
    class UrlClickableSpan(val context: Context, val url: String) : android.text.style.ClickableSpan() {
        override fun onClick(widget: android.view.View) {
            // Prevent click if a long press just happened
            if (System.currentTimeMillis() - lastLinkLongPressTime < 500) return
            
            DialogUtils.lastLinkClickTime = System.currentTimeMillis()
            DialogUtils.showLinkOptionsBottomSheet(context, url)
        }
        
        override fun updateDrawState(ds: android.text.TextPaint) {
            super.updateDrawState(ds)
            ds.isUnderlineText = false
            ds.color = android.graphics.Color.parseColor("#53FC18") 
        }
    }
}

