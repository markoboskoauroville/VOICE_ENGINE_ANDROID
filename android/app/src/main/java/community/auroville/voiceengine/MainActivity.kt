package community.auroville.voiceengine

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView

/**
 * The settings of the subtitle, the rows of the Mac's Voice Engine window in the same words and order (SUBTITLES ...
 * WHERE ON THE SCREEN). Every change is saved at once and shown on the screen (the Mac's four-second preview).
 */
class MainActivity : Activity() {

    companion object { const val PICK_KEY = 41; const val PICK_GROQ = 42; const val MIC = 43 }

    private val bg = Color.parseColor("#111111")
    private val fg = Color.parseColor("#E6E6E6")
    private val dim = Color.parseColor("#8A8A8A")
    private val orange = Color.parseColor("#E8A33D")
    private val key = Color.parseColor("#2A2A2A")
    private val palette = listOf("#E8A33D", "#FFFFFF", "#FFD60A", "#FF3B30", "#FF9F0A", "#34C759", "#0A84FF", "#BF5AF2")

    private lateinit var s: SubSettings
    private lateinit var scroll: ScrollView
    private lateinit var box: LinearLayout

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        VoiceService.start(this)
        if (intent?.getBooleanExtra("mic", false) == true && checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), MIC)
        }
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
        }
        scroll = ScrollView(this).apply { setBackgroundColor(bg); isFillViewport = true }
        box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        scroll.addView(box, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        scroll.setOnApplyWindowInsetsListener { v, ins ->
            val i = ins.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
            v.setPadding(i.left + dp(16), i.top + dp(12), i.right + dp(16), i.bottom + dp(24))
            ins
        }
        setContentView(scroll)
        build()
    }

    private var keyReport = ""

    @Deprecated("plain Activity result")
    override fun onActivityResult(req: Int, res: Int, data: Intent?) {
        super.onActivityResult(req, res, data)
        if ((req != PICK_KEY && req != PICK_GROQ) || res != RESULT_OK) return
        val uri = data?.data ?: return
        val groq = req == PICK_GROQ
        keyReport = "Testing…"; build()
        Thread {
            val text = runCatching { contentResolver.openInputStream(uri)?.use { String(it.readBytes().take(200_000).toByteArray()) } }.getOrNull().orEmpty()
            val keys = if (groq) GroqKeys.candidates(text) else Speechify.candidates(text)
            val lines = ArrayList<String>()
            val good = ArrayList<String>()
            for ((i, k) in keys.withIndex()) {
                val r = if (groq) GroqKeys.test(k) else Speechify.test(k)
                lines += "Key ${i + 1}: $r"
                if (r.startsWith("works")) good += k
            }
            val kept = good.isNotEmpty() && (if (groq) GroqKeys.saveKeys(this, good) else Speechify.saveKeys(this, good))
            val report = if (keys.isEmpty()) "No key found in that file." else lines.joinToString("\n") +
                if (kept) "\nKept ${good.size} working key(s); they rotate when one is out." else "\nNone kept."
            runOnUiThread { keyReport = report; build() }
        }.start()
    }

    override fun onRequestPermissionsResult(code: Int, perms: Array<out String>, res: IntArray) {
        super.onRequestPermissionsResult(code, perms, res)
        build()
    }

    override fun onResume() {
        super.onResume()
        build()
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun version(): String = try { packageManager.getPackageInfo(packageName, 0).versionName ?: "?" } catch (e: PackageManager.NameNotFoundException) { "?" }

    private fun rebuild() {
        val y = scroll.scrollY
        build()
        scroll.post { scroll.scrollTo(0, y) }
    }

    private fun change(f: (SubSettings) -> SubSettings) {
        s = f(SubSettings.load(this))
        s.save(this)
        val svc = VoiceService.instance
        if (svc != null) svc.preview() else VoiceService.start(this)
        rebuild()
    }

    // ------------------------------------------------------------ the pieces of a row

    private fun label(t: String, size: Float = 13f, colour: Int = dim, top: Int = 18): TextView = TextView(this).apply {
        text = t; textSize = size; setTextColor(colour); letterSpacing = 0.06f
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(top) }
    }

    private fun word(t: String, on: Boolean, f: () -> Unit): TextView = TextView(this).apply {
        text = t; textSize = 15f; gravity = Gravity.CENTER
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        setTextColor(if (on) Color.BLACK else fg)
        background = GradientDrawable().apply { cornerRadius = dp(6).toFloat(); setColor(if (on) orange else key) }
        setPadding(dp(14), dp(11), dp(14), dp(11))
        setOnClickListener { f() }
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { marginEnd = dp(8) }
    }

    private fun row(vararg v: View): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(8) }
        v.forEach { addView(it) }
    }

    private fun swatches(current: String, none: Boolean, f: (String) -> Unit): LinearLayout {
        val r = row()
        if (none) r.addView(word("NONE", current.isBlank()) { f("") })
        for (h in palette) {
            val sel = h.equals(current, true)
            r.addView(View(this).apply {
                background = GradientDrawable().apply {
                    cornerRadius = dp(6).toFloat(); setColor(Color.parseColor(h))
                    setStroke(dp(if (sel) 3 else 1), if (sel) Color.WHITE else Color.parseColor("#444444"))
                }
                contentDescription = h
                setOnClickListener { f(h) }
                layoutParams = LinearLayout.LayoutParams(0, dp(38), 1f).apply { marginEnd = dp(5) }
            })
        }
        return r
    }

    private fun stepper(value: String, minus: () -> Unit, plus: () -> Unit): LinearLayout =
        row(word("−", false, minus), TextView(this).apply {
            text = value; textSize = 17f; setTextColor(fg); gravity = Gravity.CENTER; minWidth = dp(110)
        }, word("+", false, plus))

    private fun slider(left: String, right: String, value: Double, f: (Double) -> Unit): LinearLayout {
        val bar = SeekBar(this).apply {
            max = 100; progress = value.toInt()
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(sb: SeekBar, p: Int, user: Boolean) {}
                override fun onStartTrackingTouch(sb: SeekBar) {}
                override fun onStopTrackingTouch(sb: SeekBar) { f(sb.progress.toDouble()) }
            })
        }
        return row(TextView(this).apply { text = left; setTextColor(dim); textSize = 13f }, bar,
            TextView(this).apply { text = right; setTextColor(dim); textSize = 13f })
    }

    // ------------------------------------------------------------ the window

    private fun build() {
        s = SubSettings.load(this)
        box.removeAllViews()
        val svc = VoiceService.instance

        box.addView(label("VOICE ENGINE (MANTRA)   v${version()}", 20f, orange, 4))
        box.addView(label(if (svc != null && svc.port > 0) "LISTENING ON 127.0.0.1:${svc.port}" else "STARTING …", 12f, dim, 6))

        if (!Settings.canDrawOverlays(this)) {
            box.addView(label("THE SUBTITLE MAY NOT YET BE DRAWN OVER OTHER APPS", 13f, Color.parseColor("#FF6B5E")))
            box.addView(row(word("ALLOW", true) {
                startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
            }))
        }

        // v8: Auto-rotate stays off; the engine needs "Modify system settings" to put it back.
        if (!Settings.System.canWrite(this)) {
            box.addView(label("AUTO-ROTATE GUARD NEEDS \"MODIFY SYSTEM SETTINGS\"", 13f, Color.parseColor("#FF6B5E")))
            box.addView(row(word("ALLOW", true) {
                startActivity(Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS, Uri.parse("package:$packageName")))
            }))
        } else box.addView(label("AUTO-ROTATE: KEPT OFF (caught ${VoiceService.instance?.rotation?.caught ?: 0} times)", 12f, dim, 6))

        // v4 (13.11.2026): the voice is Speechify, spoken here; other computers send text only.
        box.addView(label("VOICE (SPEECHIFY)"))
        box.addView(label(if (Speechify.hasKey(this)) "KEYS: ${Speechify.keys(this).size} · SPEAKING ON ${Speechify.ringLabel(this)} · VOICE ${Speechify.voice(this).uppercase()}" else "KEY: NONE YET · PICK THE FILE WITH YOUR SPEECHIFY KEY", 12f, dim, 4))
        box.addView(row(word("PICK KEY FILE", false) {
            startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("*/*"), PICK_KEY)
        }, word("RE-TEST", false) {
            val ks = Speechify.keys(this)
            if (ks.isEmpty()) return@word
            Thread {
                val res = ks.mapIndexed { i, k -> "Key ${i + 1}: " + Speechify.test(k) }
                runOnUiThread { keyReport = res.joinToString("\n"); build() }
            }.start()
        }, word("DELETE", false) { Speechify.deleteKey(this); keyReport = "Key deleted."; build() }))
        if (keyReport.isNotEmpty()) box.addView(label(keyReport, 14f, fg, 6))
        box.addView(row(word("SAY A TEST SENTENCE", false) {
            VoiceService.instance?.let { svc -> Thread { runCatching { svc.sayLocal("This is your voice engine speaking through Speechify.") } }.start() }
        }))

        // v16 (13.11.2026): talking back. REPLY records, Groq whisper-large-v3 writes it down, the TEXT goes to the session that spoke.
        box.addView(label("REPLY BY VOICE (GROQ WHISPER LARGE V3)"))
        box.addView(label(if (GroqKeys.hasKey(this)) "GROQ KEYS: ${GroqKeys.keys(this).size} · ON ${GroqKeys.ringLabel(this)}" else "GROQ KEY: NONE YET · PICK THE FILE WITH YOUR GROQ KEYS", 12f, dim, 4))
        box.addView(row(word("PICK GROQ KEY FILE", false) {
            startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("*/*"), PICK_GROQ)
        }, word("RE-TEST", false) {
            val ks = GroqKeys.keys(this)
            if (ks.isEmpty()) return@word
            Thread {
                val res = ks.mapIndexed { i, k -> "Groq key ${i + 1}: " + GroqKeys.test(k) }
                runOnUiThread { keyReport = res.joinToString("\n"); build() }
            }.start()
        }, word("DELETE", false) { GroqKeys.deleteKeys(this); keyReport = "Groq keys deleted."; build() }))
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            box.addView(label("THE MICROPHONE IS NOT ALLOWED YET", 13f, Color.parseColor("#FF6B5E")))
            box.addView(row(word("ALLOW MICROPHONE", true) { requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), MIC) }))
        } else box.addView(label("MICROPHONE: ALLOWED · ANSWERS WAITING TO BE SENT: ${Outbox.size(this)}", 12f, dim, 6))
        box.addView(row(word("SHOW A TEST REPLY BUTTON", false) {
            VoiceService.instance?.let { v -> v.reply.offer(Target("test", "", "", "")); v.test("This is a test message. Press REPLY.", 20.0) }
        }))

        box.addView(label("SUBTITLES"))
        box.addView(row(word("ON", s.subtitles) { change { it.copy(subtitles = true) } },
            word("OFF", !s.subtitles) { change { it.copy(subtitles = false) } }))
        box.addView(row(word("SHOW A TEST SUBTITLE", false) {
            VoiceService.instance?.test("This is how the subtitle looks while I speak to you.", 6.0)
        }))

        box.addView(label("LETTER SIZE"))
        box.addView(stepper(String.format("%.1f %%", s.size),
            { change { it.copy(size = (it.size - 0.2).coerceAtLeast(1.0)) } },
            { change { it.copy(size = (it.size + 0.2).coerceAtMost(12.0)) } }))
        box.addView(label("BOLD LETTERS"))
        box.addView(row(word("ON", s.bold) { change { it.copy(bold = true) } }, word("OFF", !s.bold) { change { it.copy(bold = false) } }))

        box.addView(label("LETTER COLOUR"))
        box.addView(swatches(s.colour, false) { h -> change { it.copy(colour = h) } })
        box.addView(label("COLOUR OF THE WORD BEING SPOKEN"))
        box.addView(swatches(s.word, false) { h -> change { it.copy(word = h) } })
        box.addView(label("MARKER BEHIND THE SPOKEN WORD"))
        box.addView(swatches(s.wordbg, true) { h -> change { it.copy(wordbg = h) } })

        box.addView(label("SPOKEN WORD LIT EARLIER / LATER"))
        box.addView(stepper(String.format("%+d ms", s.offset.toInt()),
            { change { it.copy(offset = (it.offset - 50).coerceAtLeast(-2000.0)) } },
            { change { it.copy(offset = (it.offset + 50).coerceAtMost(2000.0)) } }))

        box.addView(label("SUBTITLE STAYS AFTER THE VOICE STOPS"))
        box.addView(stepper(if (s.linger <= 0) "UNTIL HIDE" else "${s.linger.toInt()} s",
            { change { it.copy(linger = (it.linger - 1).coerceAtLeast(0.0)) } },
            { change { it.copy(linger = (it.linger + 1).coerceAtMost(600.0)) } }))

        box.addView(label("BACKGROUND BEHIND THE SUBTITLE"))
        box.addView(slider("clear", "solid", s.band) { v -> change { it.copy(band = v) } })
        box.addView(label("BACKGROUND SHADE"))
        box.addView(slider("dark", "light", s.grey) { v -> change { it.copy(grey = v) } })

        box.addView(label("WHERE ON THE SCREEN"))
        val dragged = s.ypos >= 0
        box.addView(row(
            word("TOP", !dragged && s.position == "top") { change { it.copy(position = "top", ypos = -1.0) } },
            word("MIDDLE", !dragged && s.position == "middle") { change { it.copy(position = "middle", ypos = -1.0) } },
            word("BOTTOM", !dragged && s.position == "bottom") { change { it.copy(position = "bottom", ypos = -1.0) } }))
        if (dragged) box.addView(row(word("WHERE YOU DRAGGED IT", true) {}))

        box.addView(label("ON THE SUBTITLE: HIDE (the voice reads on) · STOP (the reading ends). " +
            "DRAG IT UP OR DOWN. IN THE NOTIFICATION: PAUSE · STOP · SUB.", 12f, dim, 28))
        box.addView(label("FROM CLAUDE CODE: talk sub on | off | test | status | set key=value", 12f, dim, 10))
    }
}
