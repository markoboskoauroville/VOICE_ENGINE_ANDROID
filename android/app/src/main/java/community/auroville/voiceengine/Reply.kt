package community.auroville.voiceengine

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Handler
import android.os.Looper
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.sqrt

/** Who spoke, and where the answer goes. */
data class Target(val from: String, val fromId: String, val relay: String, val token: String)

/**
 * v16, 13.11.2026: talking AND listening. Marko: "I get the thing you are talking about with subtitles, and there will
 * be a reply button ... I can transcribe again with my API keys from Groq ... and send you a message. Back-and-forth."
 *
 * A message with reply=true raises a big REPLY button. REPLY: record until 1.5 s of silence (60 s at most) with a level
 * line and STOP; Groq whisper-large-v3 (GroqKeys); the text shown 3 s with SEND / AGAIN (SEND by itself after 3 s);
 * then the text goes to the session that spoke (Outbox). Sound never leaves the phone except to Groq. Main thread only,
 * except the recorder thread, which reports through [main].
 */
class Reply(private val svc: VoiceService) {
    enum class S { IDLE, OFFER, RECORDING, TRANSCRIBING, CONFIRM, DONE, ERROR }

    @Volatile var state = S.IDLE
        private set
    var target: Target? = null
        private set
    @Volatile var lastText = ""
    @Volatile var note = ""
    private val main = Handler(Looper.getMainLooper())
    private val wm = svc.getSystemService(WindowManager::class.java)
    private val density = svc.resources.displayMetrics.density
    private var panel: LinearLayout? = null
    private var lp: WindowManager.LayoutParams? = null
    private var levelView: LevelView? = null
    private var ringView: RingView? = null
        @Volatile private var stopFlag = false
    @Volatile private var cancelFlag = false
    @Volatile private var lastWav: ByteArray? = null
    @Volatile private var retryable = false
    @Volatile private var resend = false
    private var listenLabel: TextView? = null
    private var countdown: Runnable? = null
    private var hideJob: Runnable? = null
    private var sendLabel: TextView? = null

    private fun dp(v: Float) = (v * density).toInt()
    private val orange = Color.parseColor("#E8A33D")

    // ------------------------------------------------------------ the states

    /** A message with reply=true was spoken (or shown): the big REPLY button. */
    fun offer(t: Target) {
        if (state == S.RECORDING || state == S.TRANSCRIBING) return
        target = t; note = ""
        setState(S.OFFER)
        autoHide(180_000)
    }

    /** REPLY pressed (the button, or POST /reply). */
    fun press() {
        if (state == S.RECORDING || state == S.TRANSCRIBING) return
        if (target == null) target = Target("", "", "", "")
        svc.silence()                                   // the mic must not hear the voice
        if (svc.checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            failed("Allow the microphone: open Voice Engine and tap ALLOW MICROPHONE.")
            svc.startActivity(Intent(svc, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK).putExtra("mic", true))
            return
        }
        stopFlag = false; cancelFlag = false; retryable = false
        setState(S.RECORDING)
        svc.startForegroundService(Intent(svc, ReplyService::class.java))
    }

    fun stopRecording() { stopFlag = true }     // SEND
    fun cancelRecording() { cancelFlag = true }

    fun again() { cancelTimers(); press() }

    fun dismiss() { cancelTimers(); cancelFlag = true; close(); state = S.IDLE }

    fun failed(why: String) { main.post { note = why; setState(S.ERROR); autoHide(8000) } }

    private fun setState(s: S) { cancelCountdownOnly(); state = s; render() }

    private fun cancelCountdownOnly() { countdown?.let { main.removeCallbacks(it) }; countdown = null }
    private fun cancelTimers() { cancelCountdownOnly(); hideJob?.let { main.removeCallbacks(it) }; hideJob = null }
    private fun autoHide(ms: Long) {
        hideJob?.let { main.removeCallbacks(it) }
        val j = Runnable { hideJob = null; if (state == S.OFFER || state == S.DONE || state == S.ERROR) { close(); state = S.IDLE } }
        hideJob = j; main.postDelayed(j, ms)
    }

    // ------------------------------------------------------------ recording (on ReplyService's thread)

    fun recordNow() {
        val rate = 16000
        val min = AudioRecord.getMinBufferSize(rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val rec = try {
            AudioRecord(MediaRecorder.AudioSource.MIC, rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, maxOf(min, rate))
        } catch (e: Exception) { failed("The microphone could not be opened."); return }
        if (rec.state != AudioRecord.STATE_INITIALIZED) { rec.release(); failed("The microphone could not be opened."); return }
        val pcm = ByteArrayOutputStream()
        val chunk = ShortArray(rate / 10)                 // 100 ms
        var floor = 0.0; var calib = 0
        var thr = 600.0
        var spoke = false
        var steps = 0
        rec.startRecording()
        try {
            // Marko, 13.11.2026: "It doesn't stop ... Don't cut automatically." No silence cut, no countdown: the reply
            // ends only with SEND (or CANCEL); the safety limit is 10 minutes.
            while (!stopFlag && !cancelFlag && steps < 6000) {
                val n = rec.read(chunk, 0, chunk.size)
                if (n <= 0) break
                steps++
                var sum = 0.0
                for (i in 0 until n) { sum += chunk[i].toDouble() * chunk[i] }
                val rms = sqrt(sum / n)
                val bb = ByteBuffer.allocate(n * 2).order(ByteOrder.LITTLE_ENDIAN)
                for (i in 0 until n) bb.putShort(chunk[i])
                pcm.write(bb.array())
                if (calib < 3) { floor = maxOf(floor, rms); calib++; if (calib == 3) thr = maxOf(600.0, floor * 2.5) }
                else if (rms > thr) spoke = true
                val lv = (rms / 6000.0).coerceIn(0.0, 1.0).toFloat()
                val sec = steps / 10
                main.post { levelView?.set(lv); listenLabel?.text = "LISTENING %d:%02d  %s".format(sec / 60, sec % 60, target?.from?.takeIf { it.isNotBlank() }?.let { "to $it" } ?: "") }
            }
        } finally { rec.stop(); rec.release() }
        if (cancelFlag) { main.post { close(); state = S.IDLE }; return }
        if (!spoke) { main.post { note = "I heard nothing."; setState(S.ERROR) }; return }
        lastWav = wav(pcm.toByteArray(), rate)
        main.post { setState(S.TRANSCRIBING) }
        transcribeAndSend()
    }

    /** Groq on the kept recording, then straight to the session that spoke (SEND was his last word). */
    private fun transcribeAndSend() {
        val w = lastWav ?: return
        try {
            val text = try { if (MacServer.enabled(svc)) MacServer.hear(svc, w) else GroqKeys.transcribe(svc, w) } catch (e: Exception) { if (MacServer.enabled(svc) && GroqKeys.hasKey(svc)) GroqKeys.transcribe(svc, w) else throw e }   // v24: the Mac's Whisper first (free), Groq when it does not answer
            if (text.isBlank()) { main.post { note = "I heard nothing."; setState(S.ERROR) }; return }
            lastText = text
            main.post { send() }
        } catch (e: Exception) {
            val why = e.message ?: "transcription failed"
            main.post { note = why; retryable = true; setState(S.ERROR) }
        }
    }

    fun retry() { retryable = false; resend = false; setState(S.TRANSCRIBING); Thread { transcribeAndSend() }.start() }

    private fun wav(pcm: ByteArray, rate: Int): ByteArray {
        val h = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN)
        h.put("RIFF".toByteArray()).putInt(36 + pcm.size).put("WAVE".toByteArray()).put("fmt ".toByteArray())
        h.putInt(16).putShort(1).putShort(1).putInt(rate).putInt(rate * 2).putShort(2).putShort(16)
        h.put("data".toByteArray()).putInt(pcm.size)
        return h.array() + pcm
    }

    // ------------------------------------------------------------ SEND, the queue

    private fun sendQueued() {
        note = "Sending …"; setState(S.DONE)
        Thread {
            val left = Outbox.flush(svc)
            main.post {
                if (left == 0) { note = "Sent: $lastText"; render(); autoHide(4000) }
                else { note = "Still not reached. Your words are kept: $lastText"; resend = true; setState(S.ERROR) }
            }
        }.start()
    }

    fun send() {
        val t = target ?: Target("", "", "", "")
        val text = lastText
        if (text.isBlank()) { close(); state = S.IDLE; return }
        val at = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssZ", Locale.US).format(Date())
        Outbox.add(svc, t.from, t.fromId, text, t.relay, t.token, at)
        note = "Sending: $text"; setState(S.DONE)
        Thread {
            val left = Outbox.flush(svc)
            main.post {
                if (left == 0) { note = "Sent: $text"; render(); autoHide(4000) }
                else { note = "Not sent (the Mac is not reached). Your words are kept: $text"; resend = true; setState(S.ERROR) }   // goes out only when SEND is pressed again
            }
        }.start()
    }

    // ------------------------------------------------------------ the panel

    private fun screenInsets(): IntArray {
        val m = wm.currentWindowMetrics
        val i = m.windowInsets.getInsetsIgnoringVisibility(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
        return intArrayOf(m.bounds.height(), i.top, i.bottom)
    }

    private fun btn(t: String, big: Boolean, on: Boolean = true, f: () -> Unit) = TextView(svc).apply {
        text = t; gravity = Gravity.CENTER; setTextSize(TypedValue.COMPLEX_UNIT_SP, if (big) 30f else 20f)
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD); letterSpacing = 0.06f
        setTextColor(if (on) Color.BLACK else Color.WHITE)
        background = GradientDrawable().apply { cornerRadius = dp(14f).toFloat(); setColor(if (on) orange else Color.parseColor("#3A3A3A")) }
        setPadding(dp(18f), dp(if (big) 18f else 12f), dp(18f), dp(if (big) 18f else 12f))
        contentDescription = t
        setOnClickListener { f() }
    }

    private fun line(t: String, sp: Float, colour: Int = Color.WHITE) = TextView(svc).apply {
        text = t; setTextSize(TypedValue.COMPLEX_UNIT_SP, sp); setTextColor(colour); gravity = Gravity.CENTER
        setPadding(dp(8f), dp(4f), dp(8f), dp(4f))
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun render() {
        if (state == S.IDLE) { close(); return }
        val p = panel ?: open() ?: return
        p.removeAllViews(); levelView = null; ringView = null; sendLabel = null; listenLabel = null
        val w = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(8f) }
        val who = target?.from?.takeIf { it.isNotBlank() }?.let { "to $it" } ?: ""
        when (state) {
            S.OFFER -> {
                p.addView(btn("REPLY", true) { press() }, w)
                p.addView(btn("STOP SPEAKING", false, false) { svc.silence() }, w)
                p.addView(line(if (who.isBlank()) "answer by voice" else "answer by voice $who", 13f, Color.parseColor("#BBBBBB")))
                p.addView(btn("✕", false, false) { dismiss() }, LinearLayout.LayoutParams(dp(80f), ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(6f) })
            }
            S.RECORDING -> {
                val ll = line("LISTENING", 14f, orange); listenLabel = ll
                p.addView(ll)
                val lv = LevelView(svc); levelView = lv
                p.addView(lv, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(18f)).apply { topMargin = dp(6f) })
                p.addView(btn("SEND", true) { stopRecording() }, w)
                p.addView(btn("CANCEL", false, false) { cancelRecording() }, w)
            }
            S.TRANSCRIBING -> p.addView(line("WRITING DOWN WHAT YOU SAID …", 16f, orange))
            S.DONE -> p.addView(line(note, 18f, orange))
            S.ERROR -> {
                p.addView(line(note, 16f, Color.parseColor("#FF6B5E")))
                if (resend) p.addView(btn("SEND", true) { resend = false; setState(S.TRANSCRIBING); main.post { sendQueued() } }, w)
                else if (retryable) p.addView(btn("TRY AGAIN", true) { retry() }, w) else p.addView(btn("AGAIN", false) { again() }, w)
                p.addView(btn("✕", false, false) { dismiss() }, LinearLayout.LayoutParams(dp(80f), ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(6f) })
            }
            else -> {}
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun open(): LinearLayout? {
        if (!android.provider.Settings.canDrawOverlays(svc)) return null
        val p = LinearLayout(svc).apply {
            orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(14f), dp(12f), dp(14f), dp(14f))
            background = GradientDrawable().apply { cornerRadius = dp(20f).toFloat(); setColor(Color.argb(238, 20, 20, 20)) }
        }
        val s = screenInsets()
        val sub = SubSettings.load(svc)
        val atTop = sub.position == "bottom" && sub.ypos < 0
        val params = WindowManager.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT,
        )
        params.gravity = (if (atTop) Gravity.TOP else Gravity.BOTTOM) or Gravity.CENTER_HORIZONTAL
        params.x = 0; params.y = if (atTop) s[1] + dp(8f) else s[2] + dp(16f)
        params.horizontalMargin = 0.04f
        params.title = "Voice Engine reply"
        return try { wm.addView(p, params); panel = p; lp = params; p } catch (e: RuntimeException) { null }
    }

    fun close() {
        val p = panel ?: return
        panel = null; lp = null; levelView = null; ringView = null; sendLabel = null
        try { wm.removeView(p) } catch (e: IllegalArgumentException) { }
    }

    val shown: Boolean get() = panel != null
}

/** The level line while recording. */
class LevelView(ctx: Context) : View(ctx) {
    private var level = 0f
    private val bg = Paint().apply { color = Color.parseColor("#333333") }
    private val fg = Paint().apply { color = Color.parseColor("#E8A33D") }
    fun set(v: Float) { level = level * 0.4f + v * 0.6f; invalidate() }
    override fun onDraw(c: Canvas) {
        c.drawRoundRect(0f, 0f, width.toFloat(), height.toFloat(), height / 2f, height / 2f, bg)
        c.drawRoundRect(0f, 0f, maxOf(height.toFloat(), width * level), height.toFloat(), height / 2f, height / 2f, fg)
    }
}

/** A ring that fills during the last 2 s of silence before the recording ends by itself (STOP finishes at once). */
class RingView(ctx: Context) : View(ctx) {
    private var v = 0f
    private val back = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#333333"); style = Paint.Style.STROKE }
    private val front = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#E8A33D"); style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND }
    fun set(x: Float) { if (x != v) { v = x; invalidate() } }
    override fun onDraw(c: Canvas) {
        val w = width * 0.14f; back.strokeWidth = w; front.strokeWidth = w
        val r = android.graphics.RectF(w, w, width - w, height - w)
        c.drawOval(r, back)
        if (v > 0f) c.drawArc(r, -90f, 360f * v.coerceAtMost(1f), false, front)
    }
}
