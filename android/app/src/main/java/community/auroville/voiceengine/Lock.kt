package community.auroville.voiceengine

import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView

/**
 * v7, 13.11.2026. THE PHONE LOCK. Marko: "When you need phone make a lock ... When my Mantra macro recorder is using the
 * keyboard I cannot use my computer at all. It blocks everything. Now you can do the same block here. You just put one
 * badge over the middle of the screen, say Claude is using the phone, and then if I press some button on that badge, I
 * can override it ... And when it disappears, I can use it. So we can be in exchange."
 *
 * A full-screen overlay that swallows every touch (dimmed), one badge in the middle: CLAUDE IS USING THE PHONE, what it
 * is doing, and LET ME USE IT, which removes the lock at once and tells the caller (status lock = "overridden").
 * The caller's own taps and screenshots go through [through]: the lock hides for a moment and comes back.
 * Never focusable, so it does not take the keyboard from the app underneath.
 */
class Lock(private val ctx: Context) {
    private val wm = ctx.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private val main = Handler(Looper.getMainLooper())
    private var root: FrameLayout? = null
    private var detail: TextView? = null
    @Volatile var state = "off"          // off | on | overridden | requested
        private set
    private var lastText = ""
    private var blob: View? = null
    private var back: Runnable? = null

    // v27 (Marko, 14.11.2026: "it's persistent on the screen. I cannot get rid of it."): nothing here outlives its owner.
    /** True while the HTTP server that /unlock reaches is up. Set by the service. */
    @Volatile var serverAlive: () -> Boolean = { true }
    private var deadAt = 0L          // the lock or blob closes itself at this time
    private var downChecks = 0
    private val guard = object : Runnable {
        override fun run() {
            if (state == "off") return
            downChecks = if (serverAlive()) 0 else downChecks + 1
            if (downChecks >= 2 || System.currentTimeMillis() > deadAt) { off(); return }
            main.postDelayed(this, 2000)
        }
    }
    private fun arm(ms: Long) {
        deadAt = System.currentTimeMillis() + ms; downChecks = 0
        main.removeCallbacks(guard); main.postDelayed(guard, 2000)
    }
    companion object { const val LOCK_MAX_MS = 15 * 60_000L; const val BLOB_MAX_MS = 10 * 60_000L }

    /** The height of the clock-and-icons strip, until the insets say it exactly. */
    private fun statusBar(): Int {
        val id = ctx.resources.getIdentifier("status_bar_height", "dimen", "android")
        return if (id > 0) ctx.resources.getDimensionPixelSize(id) else dp(28)
    }

    private fun dp(v: Int) = (v * ctx.resources.displayMetrics.density).toInt()

    private var bar: LinearLayout? = null
    // v13: the countdown (Marko, 13.11.2026: "estimated time to finish, how much I need to wait, counting down")
    private var endAt = 0L
    private var label = ""
    private val tick = object : Runnable {
        override fun run() {
            val d = detail ?: return
            d.text = barText()
            main.postDelayed(this, 1000)
        }
    }

    private fun barText(): String {
        if (endAt <= 0L) return "CLAUDE · $label"
        val left = ((endAt - System.currentTimeMillis()) / 1000).toInt()
        val t = if (left > 0) String.format("%d:%02d left", left / 60, left % 60) else "almost done"
        // v15: the time first, so a long label is cut, never the time (13.11.2026: "· 3:…").
        return "CLAUDE · $t · $label"
    }

    /** /lock with seconds: the time left starts again from there. */
    fun on(text: String, seconds: Int) {
        endAt = if (seconds > 0) System.currentTimeMillis() + seconds * 1000L else 0L
        on(text)
    }

    /**
     * v12 (Marko, 13.11.2026: "very rude ... in the middle of the screen ... I cannot see what you are doing. Please put
     * this at the top ... be aware of the notch of the camera ... on the left and right side just say what you are doing
     * and give me the button to take over"). Nothing is dimmed and nothing sits in the middle: one thin dark bar at the
     * very top of the app area (Android draws its own status bar above every app overlay, so the bar sits right under
     * the clock and icons, the full width): left CLAUDE · what it does, a gap where the camera hole is, right TAKE OVER.
     * The rest of the screen stays fully visible; touches there are swallowed by an invisible layer.
     */
    fun on(text: String) {
        lastText = text
        closeBlob()
        label = text
        if (state == "on" && detail != null) { detail?.text = barText(); root?.visibility = View.VISIBLE; arm(LOCK_MAX_MS); return }
        if (!Settings.canDrawOverlays(ctx)) return
        close()
        val orange = Color.parseColor("#E8A33D")
        // The invisible layer: every touch outside the bar stops here.
        val r = FrameLayout(ctx).apply { isClickable = true; setOnTouchListener { _, _ -> true } }
        val full = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT,
        )
        try { wm.addView(r, full) } catch (e: Exception) { return }
        root = r
        // The bar: text left, a gap for the camera hole in the middle, the button right.
        val d = TextView(ctx).apply {
            this.text = "CLAUDE · $text"; textSize = 13f; setTextColor(orange); typeface = Typeface.DEFAULT_BOLD
            fontFeatureSettings = "tnum"
            maxLines = 2; ellipsize = android.text.TextUtils.TruncateAt.END; gravity = Gravity.CENTER_VERTICAL or Gravity.START
        }
        val take = TextView(ctx).apply {
            this.text = "TAKE OVER"; textSize = 13f; setTextColor(Color.BLACK); typeface = Typeface.DEFAULT_BOLD; gravity = Gravity.CENTER
            background = GradientDrawable().apply { cornerRadius = dp(8).toFloat(); setColor(orange) }
            setPadding(dp(12), dp(6), dp(12), dp(6))
            setOnClickListener { override() }
        }
        val hole = View(ctx)
        val b = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(Color.parseColor("#F2101114"))
            setPadding(dp(10), statusBar() + dp(4), dp(10), dp(6))
            addView(FrameLayout(ctx).apply { addView(d, FrameLayout.LayoutParams(-1, -2, Gravity.CENTER_VERTICAL or Gravity.START)) },
                LinearLayout.LayoutParams(0, -2, 1f))
            addView(hole, LinearLayout.LayoutParams(dp(56), 1))
            addView(FrameLayout(ctx).apply { addView(take, FrameLayout.LayoutParams(-2, -2, Gravity.CENTER_VERTICAL or Gravity.END)) },
                LinearLayout.LayoutParams(0, -2, 1f))
        }
        val top = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP
            if (android.os.Build.VERSION.SDK_INT >= 28) layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
        }
        try { wm.addView(b, top) } catch (e: Exception) { }
        // The camera hole: its real width once the bar knows its insets.
        b.setOnApplyWindowInsetsListener { v, ins ->
            val cut = ins.displayCutout?.boundingRects?.firstOrNull { it.top == 0 }
            if (cut != null) hole.layoutParams = LinearLayout.LayoutParams(cut.width() + dp(12), 1)
            // v14: the clock and icons are drawn over every overlay; the bar's words start under them, its dark band
            // runs up behind them (screenshot 13.11.2026: "CLAUDE · Testing" was written across the clock).
            @Suppress("DEPRECATION") val sb = ins.systemWindowInsetTop
            v.setPadding(dp(10), sb + dp(4), dp(10), dp(6))
            v.onApplyWindowInsets(ins)
        }
        bar = b; detail = d; state = "on"
        arm(LOCK_MAX_MS)
        d.text = barText()
        main.removeCallbacks(tick); main.post(tick)
    }

    /** The caller's own tap or screenshot: the lock steps aside for [ms] and comes back (unless it was lifted). */
    fun through(ms: Long) {
        val r = root ?: return
        back?.let { main.removeCallbacks(it) }
        r.visibility = View.GONE
        val b = Runnable { if (state == "on") root?.visibility = View.VISIBLE }
        back = b
        main.postDelayed(b, ms.coerceIn(100, 15000))
    }

    fun off() { main.removeCallbacks(guard); close(); closeBlob(); state = "off" }

    /**
     * v11 (Marko, 13.11.2026: "if you are using the phone and I take over, I need to have ability give you back the
     * phone ... some floating blob"). After LET ME USE IT a small blob stays at the side: a tap gives the phone back and
     * the lock returns. Never focusable, only the blob itself takes touches; drag it up or down.
     */
    private fun override() { close(); state = "overridden"; showBlob("GIVE THE PHONE\nBACK TO CLAUDE") { on(lastText.ifBlank { "Back to work. It is yours again when this disappears." }) } }

    /** Claude needs the phone while Marko uses it: the blob asks; a tap hands it over (the lock comes on). */
    fun request(text: String) {
        if (state == "on") return
        lastText = text
        state = "requested"
        showBlob("CLAUDE ASKS\nFOR THE PHONE") { on(lastText) }
    }

    private fun showBlob(label: String, tap: () -> Unit) {
        closeBlob()
        if (!Settings.canDrawOverlays(ctx)) return
        val orange = Color.parseColor("#E8A33D")
        val t = TextView(ctx).apply {
            this.text = label; textSize = 13f; setTextColor(Color.BLACK); gravity = Gravity.CENTER
            typeface = Typeface.DEFAULT_BOLD
        }
        // v27: a small X removes the blob for good; so does a long press on it.
        val closeX = TextView(ctx).apply {
            this.text = "\u2715"; textSize = 16f; setTextColor(Color.BLACK); gravity = Gravity.CENTER
            typeface = Typeface.DEFAULT_BOLD
            contentDescription = "Dismiss"
            setPadding(dp(10), dp(4), dp(2), dp(4))
            setOnClickListener { dismiss() }
        }
        val b = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            background = GradientDrawable().apply { cornerRadius = dp(22).toFloat(); setColor(orange); setStroke(dp(2), Color.BLACK) }
            setPadding(dp(14), dp(8), dp(8), dp(8))
            elevation = dp(6).toFloat()
            addView(t); addView(closeX)
        }
        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT,
        ).apply { gravity = Gravity.END or Gravity.TOP; x = dp(8); y = (ctx.resources.displayMetrics.heightPixels * 0.35f).toInt() }
        var downY = 0f; var startY = 0; var moved = false; var longDone = false
        val longPress = Runnable { longDone = true; dismiss() }
        t.setOnTouchListener { _, e ->
            when (e.actionMasked) {
                android.view.MotionEvent.ACTION_DOWN -> { downY = e.rawY; startY = lp.y; moved = false; longDone = false; main.postDelayed(longPress, 700); true }
                android.view.MotionEvent.ACTION_MOVE -> {
                    val dy = (e.rawY - downY).toInt()
                    if (kotlin.math.abs(dy) > dp(6)) { moved = true; main.removeCallbacks(longPress); lp.y = (startY + dy).coerceAtLeast(0); runCatching { wm.updateViewLayout(b, lp) } }
                    true
                }
                android.view.MotionEvent.ACTION_UP -> { main.removeCallbacks(longPress); if (!moved && !longDone) { closeBlob(); tap() }; true }
                android.view.MotionEvent.ACTION_CANCEL -> { main.removeCallbacks(longPress); true }
                else -> false
            }
        }
        try { wm.addView(b, lp) } catch (e: Exception) { return }
        blob = b
        arm(BLOB_MAX_MS)
    }

    /** Marko removes the blob for good: nothing is asked, nothing comes back until the next /lock or /request. */
    fun dismiss() { off() }

    private fun closeBlob() {
        blob?.let { try { wm.removeView(it) } catch (e: Exception) { } }
        blob = null
    }

    private fun close() {
        main.removeCallbacks(tick)
        back?.let { main.removeCallbacks(it) }; back = null
        root?.let { try { wm.removeView(it) } catch (e: Exception) { } }
        bar?.let { try { wm.removeView(it) } catch (e: Exception) { } }
        root = null; bar = null; detail = null
    }
}
