/**
 * File: MarkdownUtils.kt
 *
 * Description: Utility helper class providing static methods for Markdown.
 *
 * Author: Xacnio
 *
 */
package dev.xacnio.kciktv.mobile.util

import dev.xacnio.kciktv.mobile.util.DialogUtils

import android.text.Html
import android.text.SpannableStringBuilder
import android.text.method.LinkMovementMethod
import android.text.style.ClickableSpan
import android.text.style.URLSpan
import android.view.View
import android.widget.TextView

object MarkdownUtils {
    fun parseAndSetMarkdown(textView: TextView, rawText: String?) {
        if (rawText.isNullOrEmpty()) {
            textView.text = ""
            return
        }

        var processed = rawText
            .replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
            .replace(Regex("`([^`]*)`"), "<tt>$1</tt>")
            .replace(Regex("(?m)^#\\s+(.+)$"), "<h3>$1</h3>")
            .replace(Regex("\\*\\*(.*?)\\*\\*"), "<b>$1</b>")
            .replace(Regex("\\*(.*?)\\*"), "<i>$1</i>")
            .replace(Regex("_(.*?)_"), "<i>$1</i>")
            .replace(Regex("!\\[(.*?)\\]\\((.*?)\\)"), "<a href=\"$2\">📷 $1</a>")
            .replace(Regex("\\[(.*?)\\]\\((.*?)\\)"), "<a href=\"$2\">$1</a>")
            .replace("\n", "<br>")

        val spanned = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.N) {
             Html.fromHtml(processed, Html.FROM_HTML_MODE_COMPACT)
        } else {
             @Suppress("DEPRECATION")
             Html.fromHtml(processed)
        }

        val spannable = SpannableStringBuilder(spanned)
        android.text.util.Linkify.addLinks(spannable, android.text.util.Linkify.EMAIL_ADDRESSES)
        val urls = spannable.getSpans(0, spannable.length, URLSpan::class.java)
        
        for (span in urls) {
            val start = spannable.getSpanStart(span)
            val end = spannable.getSpanEnd(span)
            val flags = spannable.getSpanFlags(span)
            val url = span.url
            
            spannable.removeSpan(span)
            spannable.setSpan(object : ClickableSpan() {
                override fun onClick(widget: View) {
                    DialogUtils.showLinkConfirmationDialog(widget.context, url)
                }
            }, start, end, flags)
        }
        
        textView.text = spannable
        textView.movementMethod = LinkMovementMethod.getInstance()
    }
}
