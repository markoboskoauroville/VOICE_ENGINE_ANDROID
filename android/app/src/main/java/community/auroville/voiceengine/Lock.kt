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
    private var blob: TextView? = null
    private var back: Runnable? = null

    private fun dp(v: Int) = (v * ctx.resources.displayMetrics.density).toInt()

    fun on(text: String) {
        lastText = text
        closeBlob()
        detail?.let { it.text = text; if (state == "on") { root?.visibility = View.VISIBLE; return } }
        if (!Settings.canDrawOverlays(ctx)) return
        close()
        val orange = Color.parseColor("#E8A33D")
        val card = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL
            background = GradientDrawable().apply { cornerRadius = dp(18).toFloat(); setColor(Color.parseColor("#F2161A20")); setStroke(dp(2), orange) }
            setPadding(dp(24), dp(22), dp(24), dp(20))
        }
        card.addView(TextView(ctx).apply {
            this.text = "CLAUDE IS USING THE PHONE"; textSize = 20f; setTextColor(orange); gravity = Gravity.CENTER
            typeface = Typeface.DEFAULT_BOLD; setShadowLayer(0.01f, 3f, 3f, Color.BLACK)
        })
        val d = TextView(ctx).apply {
            this.text = text; textSize = 15f; setTextColor(Color.parseColor("#E6EDF3")); gravity = Gravity.CENTER
            setPadding(0, dp(10), 0, dp(16))
        }
        card.addView(d)
        card.addView(TextView(ctx).apply {
            this.text = "LET ME USE IT"; textSize = 16f; setTextColor(Color.BLACK); gravity = Gravity.CENTER
            typeface = Typeface.DEFAULT_BOLD
            background = GradientDrawable().apply { cornerRadius = dp(10).toFloat(); setColor(orange) }
            setPadding(dp(22), dp(12), dp(22), dp(12))
            setOnClickListener { override() }
        })
        val r = FrameLayout(ctx).apply {
            setBackgroundColor(Color.argb(110, 0, 0, 0))
            isClickable = true                        // every touch outside the button stops here
            setOnTouchListener { _, _ -> true }
            // In the upper third (13.11.2026: centred, the spoken subtitle sat right on top of it).
            addView(card, FrameLayout.LayoutParams(FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.CENTER_HORIZONTAL or Gravity.TOP).apply {
                leftMargin = dp(24); rightMargin = dp(24); topMargin = (ctx.resources.displayMetrics.heightPixels * 0.18f).toInt()
            })
        }
        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT,
        )
        try { wm.addView(r, lp) } catch (e: Exception) { return }
        root = r; detail = d; state = "on"
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

    fun off() { close(); closeBlob(); state = "off" }

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
        val b = TextView(ctx).apply {
            this.text = label; textSize = 13f; setTextColor(Color.BLACK); gravity = Gravity.CENTER
            typeface = Typeface.DEFAULT_BOLD
            background = GradientDrawable().apply { cornerRadius = dp(22).toFloat(); setColor(orange); setStroke(dp(2), Color.BLACK) }
            setPadding(dp(14), dp(10), dp(14), dp(10))
            elevation = dp(6).toFloat()
        }
        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT,
        ).apply { gravity = Gravity.END or Gravity.TOP; x = dp(8); y = (ctx.resources.displayMetrics.heightPixels * 0.35f).toInt() }
        var downY = 0f; var startY = 0; var moved = false
        b.setOnTouchListener { v, e ->
            when (e.actionMasked) {
                android.view.MotionEvent.ACTION_DOWN -> { downY = e.rawY; startY = lp.y; moved = false; true }
                android.view.MotionEvent.ACTION_MOVE -> {
                    val dy = (e.rawY - downY).toInt()
                    if (kotlin.math.abs(dy) > dp(6)) { moved = true; lp.y = (startY + dy).coerceAtLeast(0); runCatching { wm.updateViewLayout(v, lp) } }
                    true
                }
                android.view.MotionEvent.ACTION_UP -> { if (!moved) { closeBlob(); tap() }; true }
                else -> false
            }
        }
        try { wm.addView(b, lp) } catch (e: Exception) { return }
        blob = b
    }

    private fun closeBlob() {
        blob?.let { try { wm.removeView(it) } catch (e: Exception) { } }
        blob = null
    }

    private fun close() {
        back?.let { main.removeCallbacks(it) }; back = null
        root?.let { try { wm.removeView(it) } catch (e: Exception) { } }
        root = null; detail = null
    }
}
