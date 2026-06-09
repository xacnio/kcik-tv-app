package dev.xacnio.kciktv.shared.ui.font

import android.content.Context
import android.graphics.Typeface
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File

object FontManager {

    val systemFonts = listOf(
        FontOption("default", "Default", "sans-serif", true),
        FontOption("serif", "Serif", "serif", true),
        FontOption("monospace", "Monospace", "monospace", true),
        FontOption("condensed", "Condensed", "sans-serif-condensed", true)
    )

    val googleFonts = listOf(
        // Sans-serif
        FontOption("roboto",            "Roboto",            "Roboto",            false, "sans"),
        FontOption("inter",             "Inter",             "Inter",             false, "sans"),
        FontOption("poppins",           "Poppins",           "Poppins",           false, "sans"),
        FontOption("lato",              "Lato",              "Lato",              false, "sans"),
        FontOption("open_sans",         "Open Sans",         "Open Sans",         false, "sans"),
        FontOption("montserrat",        "Montserrat",        "Montserrat",        false, "sans"),
        FontOption("nunito",            "Nunito",            "Nunito",            false, "sans"),
        FontOption("raleway",           "Raleway",           "Raleway",           false, "sans"),
        FontOption("ubuntu",            "Ubuntu",            "Ubuntu",            false, "sans"),
        FontOption("rubik",             "Rubik",             "Rubik",             false, "sans"),
        FontOption("dm_sans",           "DM Sans",           "DM Sans",           false, "sans"),
        FontOption("work_sans",         "Work Sans",         "Work Sans",         false, "sans"),
        FontOption("outfit",            "Outfit",            "Outfit",            false, "sans"),
        FontOption("manrope",           "Manrope",           "Manrope",           false, "sans"),
        FontOption("plus_jakarta_sans", "Plus Jakarta Sans", "Plus Jakarta Sans", false, "sans"),
        FontOption("figtree",           "Figtree",           "Figtree",           false, "sans"),
        FontOption("urbanist",          "Urbanist",          "Urbanist",          false, "sans"),
        FontOption("mulish",            "Mulish",            "Mulish",            false, "sans"),
        FontOption("barlow",            "Barlow",            "Barlow",            false, "sans"),
        FontOption("fira_sans",         "Fira Sans",         "Fira Sans",         false, "sans"),
        FontOption("karla",             "Karla",             "Karla",             false, "sans"),
        FontOption("cabin",             "Cabin",             "Cabin",             false, "sans"),
        FontOption("lexend",            "Lexend",            "Lexend",            false, "sans"),
        FontOption("syne",              "Syne",              "Syne",              false, "sans"),
        FontOption("be_vietnam_pro",    "Be Vietnam Pro",    "Be Vietnam Pro",    false, "sans"),
        FontOption("josefin_sans",      "Josefin Sans",      "Josefin Sans",      false, "sans"),
        FontOption("space_grotesk",     "Space Grotesk",     "Space Grotesk",     false, "sans"),
        FontOption("quicksand",         "Quicksand",         "Quicksand",         false, "sans"),
        FontOption("comfortaa",         "Comfortaa",         "Comfortaa",         false, "sans"),
        FontOption("oswald",            "Oswald",            "Oswald",            false, "sans"),
        FontOption("exo_2",             "Exo 2",             "Exo 2",             false, "sans"),
        FontOption("titillium_web",     "Titillium Web",     "Titillium Web",     false, "sans"),
        FontOption("noto_sans",         "Noto Sans",         "Noto Sans",         false, "sans"),
        // Serif
        FontOption("merriweather",      "Merriweather",      "Merriweather",      false, "serif"),
        FontOption("playfair_display",  "Playfair Display",  "Playfair Display",  false, "serif"),
        FontOption("roboto_slab",       "Roboto Slab",       "Roboto Slab",       false, "serif"),
        FontOption("lora",              "Lora",              "Lora",              false, "serif"),
        FontOption("libre_baskerville", "Libre Baskerville", "Libre Baskerville", false, "serif"),
        FontOption("eb_garamond",       "EB Garamond",       "EB Garamond",       false, "serif"),
        FontOption("crimson_text",      "Crimson Text",      "Crimson Text",       false, "serif"),
        FontOption("cormorant",         "Cormorant",         "Cormorant",         false, "serif"),
        // Display & Handwriting
        FontOption("lobster",           "Lobster",           "Lobster",           false, "display"),
        FontOption("pacifico",          "Pacifico",          "Pacifico",          false, "display"),
        FontOption("dancing_script",    "Dancing Script",    "Dancing Script",    false, "display"),
        FontOption("caveat",            "Caveat",            "Caveat",            false, "display"),
        FontOption("abril_fatface",     "Abril Fatface",     "Abril Fatface",     false, "display"),
        FontOption("permanent_marker",  "Permanent Marker",  "Permanent Marker",  false, "display"),
        FontOption("indie_flower",      "Indie Flower",      "Indie Flower",      false, "display"),
        // Monospace
        FontOption("fira_code",         "Fira Code",         "Fira Code",         false, "mono"),
        FontOption("jetbrains_mono",    "JetBrains Mono",    "JetBrains Mono",    false, "mono"),
        FontOption("source_code_pro",   "Source Code Pro",   "Source Code Pro",   false, "mono")
    )

    private val memoryCache = mutableMapOf<String, Typeface>()
    private val previewMemoryCache = mutableMapOf<String, Typeface>()

    fun getOption(fontId: String): FontOption? =
        systemFonts.find { it.id == fontId } ?: googleFonts.find { it.id == fontId }

    fun getSystemTypeface(fontId: String): Typeface? {
        memoryCache[fontId]?.let { return it }
        val opt = systemFonts.find { it.id == fontId } ?: return null
        return Typeface.create(opt.family, Typeface.NORMAL).also { memoryCache[fontId] = it }
    }

    fun getCachedGoogleFont(context: Context, fontId: String): Typeface? {
        memoryCache[fontId]?.let { return it }
        return try {
            val file = getFontFile(context, fontId)
            if (file.exists()) Typeface.createFromFile(file).also { memoryCache[fontId] = it }
            else null
        } catch (_: Exception) { null }
    }

    fun isGoogleFontCached(context: Context, fontId: String): Boolean =
        memoryCache.containsKey(fontId) || getFontFile(context, fontId).exists()

    private fun getFontFile(context: Context, fontId: String) =
        File(context.filesDir, "fonts/$fontId.ttf")

    private fun getPreviewFontFile(context: Context, fontId: String) =
        File(context.filesDir, "fonts/${fontId}_preview.ttf")

    fun getCachedPreviewTypeface(context: Context, fontId: String): Typeface? {
        previewMemoryCache[fontId]?.let { return it }
        return try {
            val file = getPreviewFontFile(context, fontId)
            if (file.exists()) Typeface.createFromFile(file).also { previewMemoryCache[fontId] = it }
            else null
        } catch (_: Exception) { null }
    }

    // Downloads a character subset containing "Aa" + the font's display name — used purely for list previews.
    fun downloadGoogleFontPreview(
        context: Context,
        fontId: String,
        callback: (Typeface?) -> Unit
    ) {
        previewMemoryCache[fontId]?.let { callback(it); return }
        val previewFile = getPreviewFontFile(context, fontId)
        if (previewFile.exists()) {
            try {
                callback(Typeface.createFromFile(previewFile).also { previewMemoryCache[fontId] = it })
            } catch (_: Exception) { callback(null) }
            return
        }
        val opt = googleFonts.find { it.id == fontId } ?: run { callback(null); return }
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val client = OkHttpClient()
                // Include all unique characters needed to display "Aa" + the font display name
                val previewChars = ("Aa" + opt.displayName).toSet().sorted().joinToString("")
                val textParam = java.net.URLEncoder.encode(previewChars, "UTF-8")
                val cssUrl = "https://fonts.googleapis.com/css?family=${opt.family.replace(" ", "+")}&text=$textParam"
                val css = client.newCall(
                    Request.Builder().url(cssUrl).header("User-Agent", "Android").build()
                ).execute().body?.string() ?: throw Exception("empty CSS")
                val ttfUrl = Regex("""url\((https://fonts\.gstatic\.com/[^)]+\.(?:ttf|otf))\)""")
                    .find(css)?.groupValues?.get(1) ?: throw Exception("no TTF URL")
                val bytes = client.newCall(Request.Builder().url(ttfUrl).build()).execute()
                    .body?.bytes() ?: throw Exception("empty font")
                val dir = File(context.filesDir, "fonts").also { it.mkdirs() }
                File(dir, "${fontId}_preview.ttf").also { it.writeBytes(bytes) }
                val typeface = Typeface.createFromFile(previewFile).also { previewMemoryCache[fontId] = it }
                withContext(Dispatchers.Main) { callback(typeface) }
            } catch (_: Exception) {
                withContext(Dispatchers.Main) { callback(null) }
            }
        }
    }

    fun downloadGoogleFont(
        context: Context,
        fontId: String,
        onStart: () -> Unit = {},
        callback: (Typeface?) -> Unit
    ) {
        memoryCache[fontId]?.let { callback(it); return }
        val opt = googleFonts.find { it.id == fontId } ?: run { callback(null); return }

        onStart()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val client = OkHttpClient()

                // Fetch CSS — User-Agent without woff2 support forces TTF responses
                val cssUrl = "https://fonts.googleapis.com/css?family=${opt.family.replace(" ", "+")}"
                val cssResp = client.newCall(
                    Request.Builder().url(cssUrl).header("User-Agent", "Android").build()
                ).execute()
                val css = cssResp.body?.string() ?: throw Exception("Empty CSS response")

                // Parse first TTF URL from the CSS src
                val ttfUrl = Regex("""url\((https://fonts\.gstatic\.com/[^)]+\.(?:ttf|otf))\)""")
                    .find(css)?.groupValues?.get(1)
                    ?: throw Exception("No TTF URL found in CSS")

                // Download the font binary
                val fontResp = client.newCall(Request.Builder().url(ttfUrl).build()).execute()
                val bytes = fontResp.body?.bytes() ?: throw Exception("Empty font response")

                // Save to internal storage
                val fontDir = File(context.filesDir, "fonts").also { it.mkdirs() }
                val fontFile = File(fontDir, "$fontId.ttf").also { it.writeBytes(bytes) }

                val typeface = Typeface.createFromFile(fontFile).also {
                    memoryCache[fontId] = it
                    previewMemoryCache[fontId] = it  // full font supersedes any preview
                }
                withContext(Dispatchers.Main) { callback(typeface) }
            } catch (_: Exception) {
                withContext(Dispatchers.Main) { callback(null) }
            }
        }
    }

    fun deleteGoogleFont(context: Context, fontId: String) {
        memoryCache.remove(fontId)
        previewMemoryCache.remove(fontId)
        getFontFile(context, fontId).delete()
        getPreviewFontFile(context, fontId).delete()
    }

    fun applyToView(root: View, typeface: Typeface) {
        if (root.tag == "brand_font") return
        if (root is TextView) {
            val style = root.typeface?.style ?: Typeface.NORMAL
            root.typeface = Typeface.create(typeface, style)
        }
        if (root is ViewGroup) {
            for (i in 0 until root.childCount) applyToView(root.getChildAt(i), typeface)
        }
    }

    fun applyCurrentFont(context: Context, root: View, fontId: String) {
        if (fontId == "default") return
        val typeface = getSystemTypeface(fontId) ?: getCachedGoogleFont(context, fontId) ?: return
        applyToView(root, typeface)
    }
}
