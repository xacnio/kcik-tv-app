package dev.xacnio.kciktv.mobile.ui.home.featured

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.text.Spannable
import android.text.SpannableStringBuilder
import android.text.style.ForegroundColorSpan
import android.text.style.ImageSpan
import android.text.style.StyleSpan
import android.view.Gravity
import android.view.ViewGroup
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.RecyclerView
import dev.xacnio.kciktv.R
import dev.xacnio.kciktv.shared.data.model.ChatMessage
import dev.xacnio.kciktv.shared.ui.utils.EmoteManager
import java.util.regex.Pattern

class MiniChatAdapter(private val isCompact: Boolean = true) : RecyclerView.Adapter<MiniChatAdapter.MsgVH>() {

    private val items = mutableListOf<ChatMessage>()

    private val emotePattern = Pattern.compile("\\[emote:(\\d+):([^\\]]+)\\]")

    inner class MsgVH(val text: TextView) : RecyclerView.ViewHolder(text)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): MsgVH {
        val tv = TextView(parent.context).apply {
            textSize = if (isCompact) 11f else 13f
            setTextColor(Color.WHITE)
            if (isCompact) {
                maxLines = 3
            }
            setPadding(if (isCompact) 24 else 32, if (isCompact) 5 else 12, if (isCompact) 24 else 32, if (isCompact) 5 else 12)
            gravity = Gravity.START
            layoutParams = RecyclerView.LayoutParams(
                RecyclerView.LayoutParams.MATCH_PARENT,
                RecyclerView.LayoutParams.WRAP_CONTENT
            )
        }
        return MsgVH(tv)
    }

    override fun getItemCount() = items.size

    override fun onBindViewHolder(holder: MsgVH, position: Int) {
        val msg = items[position]
        val sender = msg.sender ?: return
        bindMessage(holder.text, msg)
    }

    private fun bindMessage(tv: TextView, msg: ChatMessage) {
        val sender = msg.sender ?: return
        tv.tag = msg.id  // guard against stale async callbacks

        val badgeSize = (tv.textSize * 1.25f).toInt()
        val emoteSize = (tv.textSize * 1.4f).toInt()
        val builder = SpannableStringBuilder()


        sender.badges?.forEach { badge ->
            val resId: Int? = when (badge.type) {
                "broadcaster"  -> R.drawable.ic_badge_broadcaster
                "moderator"    -> R.drawable.ic_badge_moderator
                "vip"          -> R.drawable.ic_badge_vip
                "og"           -> R.drawable.ic_badge_og
                "verified"     -> R.drawable.ic_badge_verified
                "founder"      -> R.drawable.ic_badge_founder
                "staff"        -> R.drawable.ic_badge_staff
                "subscriber"   -> R.drawable.ic_badge_subscriber_default
                "sub_gifter"   -> R.drawable.ic_badge_sub_gifter
                "sidekick"     -> R.drawable.ic_badge_sidekick
                "bot"          -> R.drawable.ic_badge_bot
                else           -> null
            }
            if (resId != null) {
                val d = ContextCompat.getDrawable(tv.context, resId)?.mutate() ?: return@forEach
                d.setBounds(0, 0, badgeSize, badgeSize)
                val s = builder.length
                builder.append(" ")
                builder.setSpan(CenterImageSpan(d), s, s + 1, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
                builder.append(" ")
            }
        }


        val usernameStart = builder.length
        builder.append(sender.username ?: "")
        val usernameEnd = builder.length
        val nameColor = try {
            sender.color?.let { Color.parseColor(it) } ?: Color.parseColor("#53FC18")
        } catch (_: Exception) {
            Color.parseColor("#53FC18")
        }
        builder.setSpan(ForegroundColorSpan(nameColor), usernameStart, usernameEnd, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
        builder.setSpan(StyleSpan(Typeface.BOLD), usernameStart, usernameEnd, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
        builder.append(": ")


        val msgStartIndex = builder.length
        val content = msg.content ?: ""
        val emotePlaceholders = mutableListOf<Triple<Int, Int, String>>() // start, end, emoteId

        val sb = StringBuilder()
        val matcher = emotePattern.matcher(content)
        var lastEnd = 0
        while (matcher.find()) {
            sb.append(content, lastEnd, matcher.start())
            val emoteId = matcher.group(1) ?: continue
            val emoteStart = msgStartIndex + sb.length
            sb.append(" ")  // one-char placeholder
            emotePlaceholders.add(Triple(emoteStart, emoteStart + 1, emoteId))
            lastEnd = matcher.end()
        }
        sb.append(content, lastEnd, content.length)
        builder.append(sb)


        emotePlaceholders.forEach { (s, e, _) ->
            val placeholder = ColorDrawable(Color.TRANSPARENT)
            placeholder.setBounds(0, 0, emoteSize, emoteSize)
            builder.setSpan(CenterImageSpan(placeholder), s, e, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
        }

        tv.text = builder


        val msgIdStr = msg.id
        if (!animatedItems.contains(msgIdStr)) {
            animatedItems.add(msgIdStr)
            tv.alpha = 0f
            tv.translationY = 20f
            tv.animate().alpha(1f).translationY(0f).setDuration(250).start()
        }


        val msgId = msg.id
        emotePlaceholders.forEach { (s, e, emoteId) ->
            EmoteManager.loadSynchronizedEmote(tv.context, emoteId, emoteSize, tv) { drawable ->
                if (tv.tag != msgId) return@loadSynchronizedEmote

                try {
                    val current = SpannableStringBuilder(tv.text)
                    current.getSpans(s, e, CenterImageSpan::class.java).forEach { current.removeSpan(it) }
                    current.setSpan(CenterImageSpan(drawable), s, e, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
                    tv.text = current
                } catch (_: Exception) {}
            }
        }
    }

    private val animatedItems = mutableSetOf<String>()

    fun append(msg: ChatMessage, maxSize: Int = 20) {
        if (msg.sender == null || msg.content == null) return
        items.add(msg)
        if (items.size > maxSize) {
            items.removeAt(0)
            notifyDataSetChanged()
        } else {
            notifyItemInserted(items.size - 1)
        }
    }

    fun clear() {
        items.clear()
        animatedItems.clear()
        notifyDataSetChanged()
    }


    private class CenterImageSpan(drawable: Drawable) : ImageSpan(drawable) {
        override fun getSize(paint: Paint, text: CharSequence, start: Int, end: Int, fm: Paint.FontMetricsInt?): Int {
            val rect = drawable.bounds
            if (fm != null) {
                val pfm = paint.fontMetricsInt
                val mid = (pfm.ascent + pfm.descent) / 2
                fm.ascent = mid - rect.height() / 2
                fm.descent = mid + rect.height() / 2
                fm.top = fm.ascent
                fm.bottom = fm.descent
            }
            return rect.right
        }

        override fun draw(canvas: Canvas, text: CharSequence, start: Int, end: Int, x: Float, top: Int, y: Int, bottom: Int, paint: Paint) {
            val b = drawable
            canvas.save()
            val fm = paint.fontMetricsInt
            val mid = (fm.ascent + fm.descent) / 2
            val transY = y + mid - b.bounds.height() / 2
            canvas.translate(x, transY.toFloat())
            b.draw(canvas)
            canvas.restore()
        }
    }
}
