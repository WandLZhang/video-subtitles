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
import android.widget.ScrollView
import android.widget.TextView

/** ScrollView capped at maxPx tall; grows with content, then scrolls. */
private class MaxHeightScrollView(context: Context, private val maxPx: Int) : ScrollView(context) {
    override fun onMeasure(widthSpec: Int, heightSpec: Int) {
        super.onMeasure(widthSpec, MeasureSpec.makeMeasureSpec(maxPx, MeasureSpec.AT_MOST))
    }
}

/**
 * Floating, draggable caption window styled like Live Transcribe: one continuous, uniform
 * stream of the whole session's lines, the live (streaming) line at the bottom, overflow
 * scrolling up (auto-follow unless you scroll up to read back). Every Chinese word is tappable
 * for a reading + definition popup. English lines are the one intentional style break.
 */
class CaptionOverlay(private val ctx: Context) {
    private val wm = ctx.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private val main = Handler(Looper.getMainLooper())

    private lateinit var root: LinearLayout
    private lateinit var handle: TextView
    private lateinit var scroll: MaxHeightScrollView
    private lateinit var container: LinearLayout
    private lateinit var popup: TextView
    private lateinit var params: WindowManager.LayoutParams
    private var shown = false

    private var reading = "jyut"
    private var lang = "yue"
    private var seq = 0
    private var autoScroll = true
    private var currentZh: TextView? = null

    private class CLine(val zh: TextView, var en: TextView?)
    private val committed = HashMap<Int, CLine>()
    private val maxLines = 240

    private val zhColor = Color.parseColor("#DCEBF5")
    private val zhSize = 20f
    private val enColor = Color.parseColor("#FFD479")

    fun setReading(r: String) { reading = r }
    fun setLang(l: String) { lang = l }

    fun show() {
        if (shown) return
        root = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#CC000000"))
            setPadding(28, 10, 28, 16)
        }
        handle = TextView(ctx).apply {
            text = "⠿  Subtitle Everything  ·  drag"
            textSize = 11f
            setTextColor(Color.parseColor("#9AA0A6"))
            setPadding(0, 0, 0, 6)
        }
        val maxPx = (ctx.resources.displayMetrics.heightPixels * 0.42).toInt()
        scroll = MaxHeightScrollView(ctx, maxPx).apply { isVerticalScrollBarEnabled = true }
        container = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        scroll.addView(container)
        popup = TextView(ctx).apply {
            textSize = 15f
            setTextColor(Color.WHITE)
            setBackgroundColor(Color.parseColor("#F21A1C22"))
            setPadding(22, 14, 22, 14)
            visibility = View.GONE
        }
        root.addView(handle)
        root.addView(scroll)
        root.addView(popup)

        scroll.setOnScrollChangeListener { _, _, y, _, _ ->
            autoScroll = y + scroll.height >= container.height - 12
        }

        params = WindowManager.LayoutParams(
            (ctx.resources.displayMetrics.widthPixels * 0.94).toInt(),
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply { gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL; y = 140 }
        enableDrag()
        wm.addView(root, params)
        shown = true
        currentZh = makeZh().also { container.addView(it) }
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

    private fun makeZh(): TextView = TextView(ctx).apply {
        textSize = zhSize
        setTextColor(zhColor)
        movementMethod = LinkMovementMethod.getInstance()
        setPadding(0, 4, 0, 0)
    }

    private fun makeEn(): TextView = TextView(ctx).apply {
        textSize = 15f
        setTextColor(enColor)
    }

    /** Live update of the streaming line. */
    fun partial(zh: String) = main.post {
        if (!shown) return@post
        val c = currentZh ?: makeZh().also { currentZh = it; container.addView(it) }
        c.text = buildSpannable(zh)
        scrollDown()
    }

    /** Finalize the streaming line and start a new one. Returns an id to attach English. */
    fun commit(zh: String): Int {
        val id = ++seq
        main.post {
            if (!shown) return@post
            val zv = currentZh ?: makeZh().also { container.addView(it) }
            zv.text = buildSpannable(zh)
            committed[id] = CLine(zv, null)
            currentZh = makeZh().also { container.addView(it) }
            prune()
            scrollDown()
        }
        return id
    }

    fun setEnglish(id: Int, en: String) = main.post {
        if (!shown) return@post
        val cl = committed[id] ?: return@post
        val idx = container.indexOfChild(cl.zh)
        if (idx < 0) return@post
        var ev = cl.en
        if (ev == null) { ev = makeEn(); container.addView(ev, idx + 1); cl.en = ev }
        ev.text = en
        scrollDown()
    }

    fun message(msg: String) = main.post {
        if (!shown) return@post
        container.removeAllViews(); committed.clear()
        currentZh = makeZh().also { it.text = msg; container.addView(it) }
    }

    fun hide() {
        if (shown) { try { wm.removeView(root) } catch (e: Exception) {}; shown = false }
    }

    private fun prune() {
        while (container.childCount > maxLines) {
            val v = container.getChildAt(0)
            container.removeViewAt(0)
            val it = committed.entries.iterator()
            while (it.hasNext()) { val e = it.next(); if (e.value.zh === v || e.value.en === v) it.remove() }
        }
    }

    private fun scrollDown() {
        if (autoScroll) scroll.post { scroll.fullScroll(View.FOCUS_DOWN) }
    }

    // ---- word spans + popup ----
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
            val r = when (lang) {
                "ja" -> e.r
                "zh" -> e.py
                else -> if (reading == "pinyin") e.py else e.jy.ifBlank { e.py }
            }
            if (lang == "ja") {
                val s = sb.length
                sb.append(r)
                sb.setSpan(ForegroundColorSpan(Color.parseColor("#C9CCD1")), s, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            } else {
                for (syl in r.split(" ")) {
                    if (syl.isBlank()) continue
                    val s = sb.length
                    sb.append(syl)
                    sb.setSpan(ForegroundColorSpan(toneColor(syl)), s, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                    sb.append(" ")
                }
            }
            sb.append("  ").append(e.defs.take(3).joinToString("; "))
        }
        popup.text = sb
        popup.visibility = View.VISIBLE
    }
}
