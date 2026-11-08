package community.auroville.voiceengine

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.provider.Settings
import android.text.SpannableString
import android.text.Spanned
import android.text.style.BackgroundColorSpan
import android.text.style.ForegroundColorSpan
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView
import kotlin.math.abs

/**
 * The subtitle on the screen, above every app (the Mac's ScreenSub, one screen here): a band across the whole width,
 * the words being spoken centred and wrapped like a film's subtitle, never cut short, the word being said lit in its
 * own colour. On the right HIDE (the subtitle goes, the voice reads on) and STOP (the reading ends), as on the Mac since
 * v29. A drag moves the band up or down only, kept as `ypos` (the Mac's v18); a tap never hides it.
 */
class SubtitleOverlay(
    private val ctx: Context,
    private val onPause: () -> Unit,
    private val onHide: () -> Unit,
    private val onStop: () -> Unit,
    private val onDragged: (Double) -> Unit,
) {
    private val wm = ctx.getSystemService(WindowManager::class.java)
    private val density = ctx.resources.displayMetrics.density
    private var band: LinearLayout? = null
    private var tv: TextView? = null
    private var params: WindowManager.LayoutParams? = null
    private var conf = SubSettings()
    private var dragging = false
    private var pauseKey: KeyIcon? = null

    /** PAUSE reads CONTINUE (a play triangle) while the voice waits (SPEC.md section 3). */
    fun setPaused(p: Boolean) { pauseKey?.setPaused(p) }

    val shown: Boolean get() = band != null

    fun canDraw(): Boolean = Settings.canDrawOverlays(ctx)

    private fun dp(v: Float) = (v * density).toInt()

    /** Opens the band (if allowed); false when Android does not let this app draw over others. */
    @SuppressLint("ClickableViewAccessibility")
    fun open(c: SubSettings): Boolean {
        conf = c
        if (band != null) { restyle(c); return true }
        if (!canDraw()) return false
        val text = TextView(ctx).apply {
            gravity = Gravity.CENTER
            setLineSpacing(0f, 1.06f)
            setPadding(dp(14f), dp(8f), dp(6f), dp(8f))
            includeFontPadding = true
        }
        val keys = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER }
        val k = dp(46f)
        pauseKey = KeyIcon(ctx, KeyIcon.PAUSE) { onPause() }
        keys.addView(pauseKey, LinearLayout.LayoutParams(k, k))
        keys.addView(KeyIcon(ctx, KeyIcon.HIDE) { onHide() }, LinearLayout.LayoutParams(k, k))
        keys.addView(KeyIcon(ctx, KeyIcon.STOP) { onStop() }, LinearLayout.LayoutParams(k, k))
        val b = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = dp(96f)
        }
        b.addView(text, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        b.addView(keys, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { marginEnd = dp(8f) })

        val p = WindowManager.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT,
        )
        p.gravity = Gravity.TOP or Gravity.START
        p.y = screen().top
        p.title = "Voice Engine subtitle"

        // a drag moves it up or down; the place is kept when the finger lifts
        var grabY = 0f
        var startY = 0
        var moved = false
        val drag = View.OnTouchListener { _, e ->
            val lp = params ?: return@OnTouchListener false
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> { grabY = e.rawY; startY = lp.y; moved = false; dragging = true }
                MotionEvent.ACTION_MOVE -> {
                    val dy = e.rawY - grabY
                    if (abs(dy) > dp(6f)) moved = true
                    if (moved) {
                        lp.y = clampY(startY + dy.toInt(), band?.height ?: 0)
                        band?.let { wm.updateViewLayout(it, lp) }
                    }
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    dragging = false
                    if (moved) {
                        val s = screen()
                        val h = band?.height ?: 0
                        onDragged(((s.height - (lp.y + h / 2.0)) / s.height).coerceIn(0.0, 1.0))
                    }
                }
            }
            true
        }
        text.setOnTouchListener(drag)
        b.setOnTouchListener(drag)
        b.addOnLayoutChangeListener { _, _, top, _, bottom, _, oldTop, _, oldBottom ->
            if (bottom - top != oldBottom - oldTop) place()
        }
        band = b; tv = text; params = p
        restyle(c)
        try {
            wm.addView(b, p)
        } catch (e: RuntimeException) {          // the permission went away between the check and now
            band = null; tv = null; params = null
            return false
        }
        return true
    }

    fun close() {
        val b = band ?: return
        band = null; tv = null; params = null; dragging = false
        try { wm.removeView(b) } catch (e: IllegalArgumentException) { }
    }

    fun restyle(c: SubSettings) {
        conf = c
        val b = band ?: return
        val t = tv ?: return
        val g = (c.grey / 100.0 * 255).toInt().coerceIn(0, 255)
        val a = (c.band / 100.0 * 255).toInt().coerceIn(0, 255)
        b.setBackgroundColor(Color.argb(a, g, g, g))
        t.setTextSize(TypedValue.COMPLEX_UNIT_PX, (screen().height * c.size / 100.0).toFloat().coerceAtLeast(dp(12f).toFloat()))
        t.typeface = if (c.bold) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
        t.setTextColor(SubSettings.colourOr(c.colour, Color.parseColor("#E8A33D")))
        if (!dragging) place()
    }

    /** These words, the word [hs, he) lit (hs < 0: none lit). */
    fun show(text: String, hs: Int, he: Int) {
        val t = tv ?: return
        val sp = SpannableString(text)
        if (hs in 0 until he && he <= text.length) {
            sp.setSpan(ForegroundColorSpan(SubSettings.colourOr(conf.word, Color.parseColor("#FF3B30"))), hs, he, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            if (conf.wordbg.isNotBlank()) {
                val bg = SubSettings.colourOr(conf.wordbg, Color.TRANSPARENT)
                sp.setSpan(BackgroundColorSpan(Color.argb(230, Color.red(bg), Color.green(bg), Color.blue(bg))), hs, he, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            }
        }
        t.text = sp
    }

    data class Screen(val height: Int, val top: Int, val bottom: Int)

    private fun screen(): Screen {
        val m = wm.currentWindowMetrics
        val ins = m.windowInsets.getInsetsIgnoringVisibility(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
        return Screen(m.bounds.height(), ins.top, ins.bottom)
    }

    private fun clampY(y: Int, h: Int): Int {
        val s = screen()
        val lo = s.top
        val hi = (s.height - s.bottom - h).coerceAtLeast(lo)
        return y.coerceIn(lo, hi)
    }

    /** Where the band stands: where it was dragged, else TOP / MIDDLE / BOTTOM. */
    private fun place() {
        val b = band ?: return
        val lp = params ?: return
        val s = screen()
        val h = b.height
        val y = when {
            conf.ypos >= 0 -> (s.height - conf.ypos * s.height - h / 2.0).toInt()
            conf.position == "top" -> s.top
            conf.position == "bottom" -> s.height - s.bottom - h
            else -> (s.height - h) / 2
        }
        val c = clampY(y, h)
        if (c != lp.y) {
            lp.y = c
            try { wm.updateViewLayout(b, lp) } catch (e: IllegalArgumentException) { }
        }
    }
}

/** PAUSE (two bars, a triangle while paused), HIDE (a caption bubble crossed out) and STOP (a square), drawn, as the Mac's SF Symbols. */
class KeyIcon(ctx: Context, private val kind: Int, private val action: () -> Unit) : View(ctx) {
    companion object {
        const val HIDE = 0
        const val STOP = 1
        const val PAUSE = 2
    }

    private var paused = false
    fun setPaused(p: Boolean) { if (p == paused) return; paused = p; contentDescription = if (p) "Continue" else "Pause"; invalidate() }

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(235, 235, 235, 235); strokeCap = Paint.Cap.ROUND }

    init {
        isClickable = true
        isFocusable = false
        contentDescription = when (kind) { HIDE -> "Hide"; PAUSE -> "Pause"; else -> "Stop" }
        setOnClickListener { action() }
    }

    override fun onDraw(c: Canvas) {
        super.onDraw(c)
        val s = minOf(width, height) * 0.5f
        val cx = width / 2f
        val cy = height / 2f
        if (kind == PAUSE) {
            paint.style = Paint.Style.FILL
            if (paused) {
                val p = android.graphics.Path().apply { moveTo(cx - s * 0.32f, cy - s * 0.46f); lineTo(cx + s * 0.5f, cy); lineTo(cx - s * 0.32f, cy + s * 0.46f); close() }
                c.drawPath(p, paint)
            } else {
                c.drawRoundRect(cx - s * 0.42f, cy - s * 0.42f, cx - s * 0.1f, cy + s * 0.42f, s * 0.06f, s * 0.06f, paint)
                c.drawRoundRect(cx + s * 0.1f, cy - s * 0.42f, cx + s * 0.42f, cy + s * 0.42f, s * 0.06f, s * 0.06f, paint)
            }
            return
        }
        if (kind == STOP) {
            paint.style = Paint.Style.FILL
            c.drawRoundRect(cx - s * 0.42f, cy - s * 0.42f, cx + s * 0.42f, cy + s * 0.42f, s * 0.08f, s * 0.08f, paint)
            return
        }
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = s * 0.11f
        c.drawRoundRect(cx - s * 0.62f, cy - s * 0.44f, cx + s * 0.62f, cy + s * 0.44f, s * 0.16f, s * 0.16f, paint)
        c.drawLine(cx - s * 0.36f, cy - s * 0.08f, cx + s * 0.08f, cy - s * 0.08f, paint)
        c.drawLine(cx - s * 0.36f, cy + s * 0.18f, cx + s * 0.36f, cy + s * 0.18f, paint)
        paint.strokeWidth = s * 0.13f
        c.drawLine(cx - s * 0.72f, cy + s * 0.66f, cx + s * 0.72f, cy - s * 0.66f, paint)
    }
}
