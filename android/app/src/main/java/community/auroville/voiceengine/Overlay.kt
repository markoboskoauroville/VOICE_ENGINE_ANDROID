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

/** What the keys under the subtitle do (the Mac's v42 rows: PREVIOUS PAUSE NEXT SKIP / SLOWER SPEED FASTER / HIDE STOP). */
class SubActions(
    val pause: () -> Unit, val hide: () -> Unit, val stop: () -> Unit,
    val prev: () -> Unit, val next: () -> Unit, val skip: () -> Unit,
    val slower: () -> Unit, val faster: () -> Unit,
    val dragged: (Double) -> Unit,
)

/**
 * The subtitle on the screen, above every app (the Mac's ScreenSub, v43, adapted to a portrait phone): at most four
 * words at a time, centred in a column of about 76 % of the width (the phone is narrow), the word being said lit, and
 * three centred rows of keys under the text: previous / pause / next sentence / skip the reading, then slower / speed /
 * faster, then HIDE / STOP. Text and keys have the same hard black shadow. Everything stands inside the system bars and
 * the camera cutout with 16 dp of margin on every side. A drag moves it up or down only, kept as `ypos`.
 */
class SubtitleOverlay(private val ctx: Context, private val act: SubActions) {
    private val wm = ctx.getSystemService(WindowManager::class.java)
    private val density = ctx.resources.displayMetrics.density
    private var band: LinearLayout? = null
    private var tv: TextView? = null
    private var params: WindowManager.LayoutParams? = null
    private var conf = SubSettings()
    private var dragging = false
    private var pauseKey: KeyIcon? = null
    private var speedLabel: TextView? = null
    private var rate = 1.0

    /** PAUSE reads CONTINUE (a play triangle) while the voice waits (SPEC.md section 3). */
    fun setPaused(p: Boolean) { pauseKey?.setPaused(p) }

    fun setRate(r: Double) { rate = r; speedLabel?.text = String.format(java.util.Locale.US, "%.1fx", r) }

    val shown: Boolean get() = band != null

    fun canDraw(): Boolean = Settings.canDrawOverlays(ctx)

    private fun dp(v: Float) = (v * density).toInt()

    private fun shadowed(t: TextView, px: Float) {
        val off = maxOf(2f, Math.round(px * 0.07f).toFloat())
        t.setShadowLayer(0.01f, off, off, Color.BLACK)
    }

    /** Opens the band (if allowed); false when Android does not let this app draw over others. */
    @SuppressLint("ClickableViewAccessibility")
    fun open(c: SubSettings): Boolean {
        conf = c
        if (band != null) { restyle(c); return true }
        if (!canDraw()) return false
        val text = TextView(ctx).apply {
            gravity = Gravity.CENTER
            setLineSpacing(0f, 1.06f)
            setPadding(dp(12f), dp(10f), dp(12f), dp(4f))
            includeFontPadding = true
        }
        val k = dp(44f)
        fun row(vararg v: View): LinearLayout = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER
            for (x in v) addView(x, LinearLayout.LayoutParams(if (x is KeyIcon) k else dp(64f), k))
        }
        pauseKey = KeyIcon(ctx, KeyIcon.PAUSE) { act.pause() }
        val sp = TextView(ctx).apply {
            gravity = Gravity.CENTER; setTextColor(Color.argb(240, 240, 240, 240)); contentDescription = "Speed"
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f); typeface = Typeface.DEFAULT_BOLD
            this.text = String.format(java.util.Locale.US, "%.1fx", rate)
        }
        shadowed(sp, dp(15f).toFloat())
        speedLabel = sp
        val col = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL }
        col.addView(text, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        col.addView(row(KeyIcon(ctx, KeyIcon.PREV) { act.prev() }, pauseKey!!, KeyIcon(ctx, KeyIcon.NEXT) { act.next() }, KeyIcon(ctx, KeyIcon.SKIP) { act.skip() }))
        col.addView(row(KeyIcon(ctx, KeyIcon.MINUS) { act.slower() }, sp, KeyIcon(ctx, KeyIcon.PLUS) { act.faster() }))
        col.addView(row(KeyIcon(ctx, KeyIcon.HIDE) { act.hide() }, KeyIcon(ctx, KeyIcon.STOP) { act.stop() }))
        col.setPadding(dp(8f), dp(4f), dp(8f), dp(10f))

        val s0 = screen()
        val p = WindowManager.LayoutParams(
            columnWidth(s0), ViewGroup.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT,
        )
        p.gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
        p.y = s0.top + dp(MARGIN)
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
                        act.dragged(((s.height - (lp.y + h / 2.0)) / s.height).coerceIn(0.0, 1.0))
                    }
                }
            }
            true
        }
        text.setOnTouchListener(drag)
        col.setOnTouchListener(drag)
        col.addOnLayoutChangeListener { _, _, top, _, bottom, _, oldTop, _, oldBottom ->
            if (bottom - top != oldBottom - oldTop) place()
        }
        band = col; tv = text; params = p
        restyle(c)
        try {
            wm.addView(col, p)
        } catch (e: RuntimeException) {          // the permission went away between the check and now
            band = null; tv = null; params = null; speedLabel = null; pauseKey = null
            return false
        }
        return true
    }

    fun close() {
        val b = band ?: return
        band = null; tv = null; params = null; dragging = false; speedLabel = null; pauseKey = null
        try { wm.removeView(b) } catch (e: IllegalArgumentException) { }
    }

    fun restyle(c: SubSettings) {
        conf = c
        val b = band ?: return
        val t = tv ?: return
        val g = (c.grey / 100.0 * 255).toInt().coerceIn(0, 255)
        val a = (c.band / 100.0 * 255).toInt().coerceIn(0, 255)
        b.setBackgroundColor(Color.argb(a, g, g, g))
        val px = (screen().height * c.size / 100.0).toFloat().coerceAtLeast(dp(12f).toFloat())
        t.setTextSize(TypedValue.COMPLEX_UNIT_PX, px)
        // The Mac's v37 hard shadow (Marko, 8.11.2026; on the phone 13.11.2026): solid black, no blur, down and right by
        // 7 % of the letter size, at least 2 px. The keys carry the same (v43: "exactly the same for the controls").
        shadowed(t, px)
        t.typeface = if (c.bold) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
        t.setTextColor(SubSettings.colourOr(c.colour, Color.parseColor("#E8A33D")))
        if (!dragging) place()
    }

    /** These words, the word [hs, he) lit (hs < 0: none lit). The spoken word is lit by a plain colour change. */
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

    companion object { const val MARGIN = 16f; const val COLUMN = 0.76 }

    data class Screen(val height: Int, val width: Int, val top: Int, val bottom: Int, val left: Int, val right: Int)

    private fun screen(): Screen {
        val m = wm.currentWindowMetrics
        val ins = m.windowInsets.getInsetsIgnoringVisibility(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
        return Screen(m.bounds.height(), m.bounds.width(), ins.top, ins.bottom, ins.left, ins.right)
    }

    /** About 76 % of the width, never wider than what is left inside the bars and the cutout less the margin each side. */
    private fun columnWidth(s: Screen): Int =
        minOf((s.width * COLUMN).toInt(), s.width - maxOf(s.left, s.right) * 2 - dp(MARGIN) * 2)

    private fun clampY(y: Int, h: Int): Int {
        val s = screen()
        val lo = s.top + dp(MARGIN)
        val hi = (s.height - s.bottom - dp(MARGIN) - h).coerceAtLeast(lo)
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
        val w = columnWidth(s)
        var changed = false
        if (c != lp.y) { lp.y = c; changed = true }
        if (w != lp.width) { lp.width = w; changed = true }
        if (changed) try { wm.updateViewLayout(b, lp) } catch (e: IllegalArgumentException) { }
    }
}

/** The keys, drawn (the Mac's SF Symbols): each shape twice, first in hard black shifted down-right, then in light. */
class KeyIcon(ctx: Context, private val kind: Int, private val action: () -> Unit) : View(ctx) {
    companion object {
        const val HIDE = 0
        const val STOP = 1
        const val PAUSE = 2
        const val PREV = 3
        const val NEXT = 4
        const val SKIP = 5
        const val MINUS = 6
        const val PLUS = 7
    }

    private var paused = false
    fun setPaused(p: Boolean) { if (p == paused) return; paused = p; contentDescription = if (p) "Continue" else "Pause"; invalidate() }

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { strokeCap = Paint.Cap.ROUND }

    init {
        isClickable = true
        isFocusable = false
        contentDescription = when (kind) {
            HIDE -> "Hide"; PAUSE -> "Pause"; PREV -> "Previous sentence"; NEXT -> "Next sentence"
            SKIP -> "Skip to the next reading"; MINUS -> "Slower"; PLUS -> "Faster"; else -> "Stop"
        }
        setOnClickListener { action() }
    }

    override fun onDraw(c: Canvas) {
        super.onDraw(c)
        val s = minOf(width, height) * 0.5f
        val off = maxOf(2f, Math.round(s * 0.14f).toFloat())
        c.save(); c.translate(off, off); paint.color = Color.BLACK; shape(c, s); c.restore()
        paint.color = Color.argb(240, 240, 240, 240); shape(c, s)
    }

    private fun tri(c: Canvas, x0: Float, x1: Float, cy: Float, h: Float) {
        val p = android.graphics.Path().apply { moveTo(x0, cy - h); lineTo(x1, cy); lineTo(x0, cy + h); close() }
        c.drawPath(p, paint)
    }

    private fun shape(c: Canvas, s: Float) {
        val cx = width / 2f
        val cy = height / 2f
        paint.style = Paint.Style.FILL
        when (kind) {
            PAUSE -> if (paused) tri(c, cx - s * 0.32f, cx + s * 0.5f, cy, s * 0.46f) else {
                c.drawRoundRect(cx - s * 0.42f, cy - s * 0.42f, cx - s * 0.1f, cy + s * 0.42f, s * 0.06f, s * 0.06f, paint)
                c.drawRoundRect(cx + s * 0.1f, cy - s * 0.42f, cx + s * 0.42f, cy + s * 0.42f, s * 0.06f, s * 0.06f, paint)
            }
            STOP -> c.drawRoundRect(cx - s * 0.42f, cy - s * 0.42f, cx + s * 0.42f, cy + s * 0.42f, s * 0.08f, s * 0.08f, paint)
            NEXT -> {   // forward.end.fill: a triangle, then a bar
                tri(c, cx - s * 0.5f, cx + s * 0.28f, cy, s * 0.42f)
                c.drawRoundRect(cx + s * 0.3f, cy - s * 0.42f, cx + s * 0.5f, cy + s * 0.42f, s * 0.05f, s * 0.05f, paint)
            }
            PREV -> {   // backward.end.fill
                tri(c, cx + s * 0.5f, cx - s * 0.28f, cy, s * 0.42f)
                c.drawRoundRect(cx - s * 0.5f, cy - s * 0.42f, cx - s * 0.3f, cy + s * 0.42f, s * 0.05f, s * 0.05f, paint)
            }
            SKIP -> {   // forward.fill: two triangles
                tri(c, cx - s * 0.55f, cx, cy, s * 0.42f)
                tri(c, cx, cx + s * 0.55f, cy, s * 0.42f)
            }
            MINUS, PLUS -> {
                paint.style = Paint.Style.STROKE; paint.strokeWidth = s * 0.14f
                c.drawCircle(cx, cy, s * 0.62f, paint)
                c.drawLine(cx - s * 0.3f, cy, cx + s * 0.3f, cy, paint)
                if (kind == PLUS) c.drawLine(cx, cy - s * 0.3f, cx, cy + s * 0.3f, paint)
            }
            else -> {   // HIDE: a caption bubble crossed out
                paint.style = Paint.Style.STROKE
                paint.strokeWidth = s * 0.11f
                c.drawRoundRect(cx - s * 0.62f, cy - s * 0.44f, cx + s * 0.62f, cy + s * 0.44f, s * 0.16f, s * 0.16f, paint)
                c.drawLine(cx - s * 0.36f, cy - s * 0.08f, cx + s * 0.08f, cy - s * 0.08f, paint)
                c.drawLine(cx - s * 0.36f, cy + s * 0.18f, cx + s * 0.36f, cy + s * 0.18f, paint)
                paint.strokeWidth = s * 0.13f
                c.drawLine(cx - s * 0.72f, cy + s * 0.66f, cx + s * 0.72f, cy - s * 0.66f, paint)
            }
        }
    }
}
