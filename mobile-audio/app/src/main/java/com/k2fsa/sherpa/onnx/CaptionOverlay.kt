package com.k2fsa.sherpa.onnx

import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.os.Handler
import android.os.Looper
import android.text.SpannableString
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.TextPaint
import android.text.method.LinkMovementMethod
import android.text.style.ClickableSpan
import android.text.style.ForegroundColorSpan
import android.text.style.StyleSpan
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView

/**
 * Floating, draggable caption window. Shows the last 2 finalized lines (dim) plus a live,
 * streaming current line (bright). Each Chinese line's words are tappable for a
 * tone-coloured reading + definition popup. English fills in asynchronously per line.
 */
class CaptionOverlay(private val ctx: Context) {
    private val wm = ctx.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private val main = Handler(Looper.getMainLooper())

    private lateinit var root: LinearLayout
    private lateinit var handle: TextView
    private lateinit var lines: LinearLayout
    private lateinit var popup: TextView
    private lateinit var params: WindowManager.LayoutParams
    private var shown = false

    private data class Line(val id: Int, var zh: String, var en: String?)
    private val history = ArrayDeque<Line>()   // up to 2 finalized lines
    private var current: Line? = null          // the streaming (partial) line
    private var reading = "jyut"
    private var seq = 0

    fun setReading(r: String) { reading = r }

    fun show() {
        if (shown) return
        root = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#CC000000"))
            setPadding(28, 10, 28, 18)
        }
        handle = TextView(ctx).apply {
            text = "⠿  Subtitle Everything  ·  drag"
            textSize = 11f
            setTextColor(Color.parseColor("#9AA0A6"))
            setPadding(0, 0, 0, 6)
        }
        lines = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        popup = TextView(ctx).apply {
            textSize = 15f
            setTextColor(Color.WHITE)
            setBackgroundColor(Color.parseColor("#F21A1C22"))
            setPadding(22, 14, 22, 14)
            visibility = View.GONE
        }
        root.addView(handle)
        root.addView(lines)
        root.addView(popup)

        params = WindowManager.LayoutParams(
            (ctx.resources.displayMetrics.widthPixels * 0.94).toInt(),
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
            y = 160
        }
        enableDrag()
        wm.addView(root, params)
        shown = true
    }

    private fun enableDrag() {
        var ix = 0; var iy = 0; var dx = 0f; var dy = 0f
        handle.setOnTouchListener { _, e ->
            when (e.action) {
                MotionEvent.ACTION_DOWN -> { ix = params.x; iy = params.y; dx = e.rawX; dy = e.rawY; true }
                MotionEvent.ACTION_MOVE -> {
                    params.x = ix + (e.rawX - dx).toInt()
                    params.y = iy - (e.rawY - dy).toInt()
                    if (shown) wm.updateViewLayout(root, params)
                    true
                }
                else -> false
            }
        }
    }

    /** Live update of the in-progress line (already HK-traditional). */
    fun partial(zh: String) = main.post { if (shown) { current = Line(-1, zh, null); render() } }

    /** Finalize the current line into history; returns an id to attach English later. */
    fun commit(zh: String): Int {
        val id = ++seq
        main.post {
            if (!shown) return@post
            history.addLast(Line(id, zh, null))
            while (history.size > 2) history.removeFirst()
            current = null
            render()
        }
        return id
    }

    fun setEnglish(id: Int, en: String) = main.post {
        if (!shown) return@post
        history.firstOrNull { it.id == id }?.let { it.en = en; render() }
    }

    fun message(msg: String) = main.post {
        if (!shown) return@post
        history.clear(); current = Line(-1, msg, null); render()
    }

    fun hide() {
        if (shown) { try { wm.removeView(root) } catch (e: Exception) {}; shown = false }
    }

    // ---- rendering ----
    private fun render() {
        popup.visibility = View.GONE
        lines.removeAllViews()
        for (l in history) addLine(l, dim = true)
        current?.let { addLine(it, dim = false) }
    }

    private fun addLine(l: Line, dim: Boolean) {
        val zhView = TextView(ctx).apply {
            textSize = if (dim) 17f else 23f
            setTextColor(Color.parseColor(if (dim) "#6FA8BF" else "#7FD7FF"))
            movementMethod = LinkMovementMethod.getInstance()
            text = buildSpannable(l.zh)
            setPadding(0, 3, 0, 0)
        }
        lines.addView(zhView)
        val en = l.en
        if (!en.isNullOrBlank()) {
            lines.addView(TextView(ctx).apply {
                textSize = if (dim) 13f else 16f
                setTextColor(Color.parseColor(if (dim) "#B39A5C" else "#FFD479"))
                text = en
            })
        }
    }

    private fun buildSpannable(text: String): CharSequence {
        val sb = SpannableString(text)
        var i = 0
        while (i < text.length) {
            val n = Dict.matchLen(text, i)
            if (n > 0) {
                val start = i; val end = i + n
                val word = text.substring(start, end)
                sb.setSpan(object : ClickableSpan() {
                    override fun onClick(w: View) = showPopup(word)
                    override fun updateDrawState(ds: TextPaint) { ds.isUnderlineText = false }
                }, start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                i = end
            } else i++
        }
        return sb
    }

    private fun toneColor(syllable: String): Int {
        val hex = when (syllable.trim().lastOrNull()) {
            '1' -> "#E15A5A"; '2' -> "#E6A13A"; '3' -> "#3FAE4F"
            '4' -> "#5A8FE1"; '5' -> "#B06FE0"; '6' -> "#9AA0A6"
            else -> "#C9CCD1"
        }
        return Color.parseColor(hex)
    }

    private fun showPopup(word: String) {
        val entries = Dict.lookup(word)
        if (entries.isEmpty()) { popup.visibility = View.GONE; return }
        val sb = SpannableStringBuilder()
        val hs = sb.length
        sb.append(word)
        sb.setSpan(StyleSpan(Typeface.BOLD), hs, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        for (e in entries.take(4)) {
            sb.append("\n")
            val r = if (reading == "pinyin") e.py else e.jy.ifBlank { e.py }
            for (syl in r.split(" ")) {
                if (syl.isBlank()) continue
                val s = sb.length
                sb.append(syl)
                sb.setSpan(ForegroundColorSpan(toneColor(syl)), s, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                sb.append(" ")
            }
            sb.append("  ").append(e.defs.take(3).joinToString("; "))
        }
        popup.text = sb
        popup.visibility = View.VISIBLE
    }
}
