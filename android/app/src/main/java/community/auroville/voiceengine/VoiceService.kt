package community.auroville.voiceengine

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.drawable.Icon
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.util.Base64
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * THE ENGINE ON THE PHONE. The Mac's Voice Engine asks its voice server GET /speaking five times a second; here the
 * speaking program is `talk` in Termux (termux-tools talker.py, mpv), which cannot be asked, so it tells: it POSTs the
 * reading to this app on 127.0.0.1 and then its position five times a second, and every answer carries back what the
 * buttons asked for (stop, toggle). Nothing here plays a sound; it draws and it relays.
 *
 *   GET  /status                  {"app":"voiceengine","version","model","port","reading","paused","subtitles","overlay"}
 *   POST /start {id,text,words}   a reading begins; words [{s,e,t,d}] as MA Reader's (code points, seconds), may be []
 *   POST /pos {id,pos,paused,dur} where the voice is -> {"cmd": "" | "stop" | "toggle"}
 *   POST /end {id}                the voice stopped; the last words stay `linger` seconds
 *   POST /say {text, from, from_id, reply:true, relay, token}  v16: with a REPLY button; POST /offer (button, no voice); POST /reply (= the button)
 *   POST /lock {text} | /unlock | /through {ms} | /request {text}  (v11: the blob that asks for / gives back the phone)   v7: "Claude is using the phone" (blocks touches, LET ME USE IT overrides)
 *   POST /say {text, voice?, id?}  v4: text only; the engine asks Speechify (its own key, Beatrice by default) and speaks
 *   POST /play {id,text,audio,words,dur}   v3: the engine plays the sound itself (audio = base64 mp3), with the subtitle
 *   POST /test {text,seconds}     a subtitle without a voice, to see how it looks
 *   POST /hide, /stop, /toggle    the subtitle's HIDE, its STOP, the notification's pause/play
 *   POST /subtitles {on}          SUB
 *   GET  /settings, POST /settings {any keys}   the settings, as the Mac's subtitle.json
 */
class VoiceService : Service() {

    companion object {
        @Volatile var instance: VoiceService? = null
        const val PORT0 = 8720                      // 8720..8735, the first free (ports.md: "picks free")
        private const val CHANNEL = "voice"
        private const val NID = 1
        const val A_TOGGLE = "community.auroville.voiceengine.TOGGLE"
        const val A_STOP = "community.auroville.voiceengine.STOP"
        const val A_SUB = "community.auroville.voiceengine.SUB"

        fun start(ctx: Context) {
            ctx.startForegroundService(Intent(ctx, VoiceService::class.java))
        }
    }

    private val main = Handler(Looper.getMainLooper())
    private lateinit var overlay: SubtitleOverlay
    private var server: ServerSocket? = null
    private val pool = Executors.newCachedThreadPool()
    @Volatile var port = -1
        private set
    private val pendingCmd = AtomicReference("")

    // the reading, touched on the main thread only
    private var conf = SubSettings()
    private var rid = ""
    private var text = ""
    private var words: List<Word> = emptyList()
    private var pieces: List<Piece> = emptyList()
    /** v22: what is on the screen one at a time: chunks of at most four words (Chunks), or sentences while no timings exist. */
    private class Seg(val start: Int, val end: Int, val a: Int, val b: Int)
    private var segs: List<Seg> = emptyList()
    private var segOfWord = IntArray(0)
    private fun rebuildSegs() {
        if (words.isNotEmpty()) {
            val ch = Chunks.make(text, words)
            segs = ch.map { Seg(words[it[0]].s, words[it[1]].e, it[0], it[1]) }
            segOfWord = IntArray(words.size)
            for ((i, c) in ch.withIndex()) for (w in c[0]..c[1]) segOfWord[w] = i
        } else {
            segs = pieces.map { Seg(it.start, it.end, -1, -1) }
            segOfWord = IntArray(0)
        }
    }
    @Volatile var rate = 1.0
        private set
    @Volatile private var reading = false
    @Volatile private var paused = false
    private val clock = Clock()
    private var lastPos = 0L
    private var dismissed = false
    private var shownPiece = -2
    private var lit = -2
    private var nowText = ""
    private var lingerJob: Runnable? = null
    private var testJob: Runnable? = null

    private val tick = object : Runnable {
        override fun run() {
            frame()
            if (reading) main.postDelayed(this, 33)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        instance = this
        conf = SubSettings.load(this)
        rotation.start()
        overlay = SubtitleOverlay(this, SubActions(
            pause = { pausePressed() }, hide = { hidePressed() }, stop = { stopPressed() },
            prev = { prevSentence() }, next = { nextSentence() }, skip = { skipReading() },
            slower = { nudgeRate(-0.1) }, faster = { nudgeRate(0.1) }, dragged = { y -> dragged(y) }))
        rate = getSharedPreferences("engine", Context.MODE_PRIVATE).getFloat("rate", 1.0f).toDouble().coerceIn(0.8, 1.6)
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL, "Voice Engine", NotificationManager.IMPORTANCE_LOW).apply {
            setShowBadge(false)
        })
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(NID, notification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(NID, notification())
        }
        Thread({ serve() }, "voiceengine-http").start()
        // v21: a newer Speechify model is found at start and daily, reported in /status as a candidate, never used before Marko approves
        Thread({
            while (true) {
                try { Speechify.detect(this, Speechify.keys(this).firstOrNull()) } catch (e: Exception) { }
                try { Thread.sleep(24 * 3600_000L) } catch (e: Exception) { return@Thread }
            }
        }, "voiceengine-model").start()
        // v18: no automatic retry: an unsent answer is kept and goes out only when Marko presses SEND again
    }
    private var retry: Runnable? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            A_TOGGLE -> togglePressed()
            A_STOP -> stopPressed()
            A_SUB -> setSubtitles(!conf.subtitles)
        }
        return START_STICKY
    }

    override fun onDestroy() {
        rotation.stop()
        reply.close()
        instance = null
        main.removeCallbacksAndMessages(null)
        overlay.close()
        try { server?.close() } catch (e: java.io.IOException) { }
        stopPlayer()
        pool.shutdownNow()
        super.onDestroy()
    }

    private fun nowS() = SystemClock.elapsedRealtime() / 1000.0

    // ---------------------------------------------------------------- the reading

    private fun startReading(j: JSONObject) {
        val t = j.optString("text", "").trim()
        if (t.isEmpty()) return
        lingerJob?.let { main.removeCallbacks(it) }; lingerJob = null
        testJob?.let { main.removeCallbacks(it) }; testJob = null
        conf = SubSettings.load(this)
        rid = j.optString("id", "")
        text = t
        words = Reading.fromServer(t, rawWords(j.optJSONArray("words")))
        val dur = j.optDouble("dur", 0.0)
        if (words.isEmpty() && dur > 0) words = Reading.estimate(t, dur)
        pieces = Reading.pieces(t)
        rebuildSegs()
        overlay.setRate(rate)
        reading = true
        paused = false
        clock.reset()
        lastPos = SystemClock.elapsedRealtime()
        dismissed = false
        shownPiece = -2
        lit = -2
        nowText = segs.firstOrNull()?.let { text.substring(it.start, it.end) } ?: t
        overlay.close()
        notifyNow()
        main.removeCallbacks(tick)
        main.post(tick)
    }

    // v3: the engine's own player (phonesay, 10.11.2026), so no audio file ever opens in another app
    private var player: MediaPlayer? = null
    @Volatile var lastSayError = ""
    @Volatile private var lastEndAt = 0L
    private val GAP_MS = 600L
    /** v22: readings waiting (being made by Speechify or waiting for the voice to be free), in the order they arrived. */
    val queued = java.util.concurrent.atomic.AtomicInteger(0)
    private val ticketSeq = java.util.concurrent.atomic.AtomicLong(0)
    private val nextTicket = java.util.concurrent.atomic.AtomicLong(0)   // whose turn it is to play: Speechify works on all at once, they play in order
    // v7: the phone lock (Claude is using the phone), see Lock.kt
    val lock by lazy { Lock(this) }
    // v8: Auto-rotate stays off (RotationGuard)
    val rotation by lazy { RotationGuard(this) }
    private var playJob: Runnable? = null

    private fun play(j: JSONObject): Boolean {
        val a = j.optString("audio", "")
        if (a.isEmpty() || j.optString("text", "").isBlank()) return false
        stopPlayer()
        if (!takeFocus(j)) return true          // v24: another player (TTT recording) has the floor: held, plays when it lets go
        val f = java.io.File(cacheDir, "play.mp3")
        try { f.writeBytes(Base64.decode(a, Base64.DEFAULT)) } catch (e: Exception) { return false }
        val mp = MediaPlayer()
        try {
            mp.setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
            mp.setDataSource(f.absolutePath)
            mp.prepare()
        } catch (e: Exception) { mp.release(); return false }
        j.put("dur", mp.duration / 1000.0)
        startReading(j)
        player = mp
        mp.setOnCompletionListener { stopPlayer() }
        mp.start()
        mediaSession(true, true)
        if (rate != 1.0) applyRate()
        val job = object : Runnable {
            override fun run() {
                val m = player ?: return
                position(JSONObject().put("id", rid).put("pos", m.currentPosition / 1000.0).put("paused", !m.isPlaying && paused).put("dur", m.duration / 1000.0))
                main.postDelayed(this, 150)
            }
        }
        playJob = job
        main.post(job)
        return true
    }

    /** Blocking: Speechify, then the v3 player and the subtitle. Used by POST /say and the window's test button. */
    @Volatile private var sayGen = 0
    fun sayLocal(text: String, voice: String = Speechify.voice(this), id0: String = "", replyMsg: Boolean = false, ticket: Long = -1) {
        val id = id0.ifBlank { "say-" + System.currentTimeMillis() }
        val gen = sayGen
        try {
            val sp = MacServer.speak(this, text, voice)
            if (gen != sayGen) return          // STOP was pressed while Speechify was still working: nothing plays
            // v22: a reading never cuts another: wait until the one playing is over, then the 0.6 s gap (the Mac's queue)
            while (ticket >= 0 && gen == sayGen && nextTicket.get() != ticket) Thread.sleep(40)      // the order they came in
            while (gen == sayGen && (player != null || pendingPlay != null || reply.state == Reply.S.RECORDING || reply.state == Reply.S.TRANSCRIBING)) Thread.sleep(60)
            val wait = GAP_MS - (SystemClock.elapsedRealtime() - lastEndAt)
            if (wait > 0) Thread.sleep(wait)
            if (gen != sayGen) return
            onMain { if (player == null) play(JSONObject().put("id", id).put("text", text).put("audio", sp.audioB64).put("words", sp.words)) }
            lastSayError = ""
        } catch (e: Exception) {
            lastSayError = e.message ?: e.javaClass.simpleName
            // a message that wants an answer is never lost: its words stay on the screen when the voice cannot speak
            onMain { if (replyMsg) test(text, 20.0) else test("Voice Engine could not speak: " + lastSayError, 5.0) }
        }
    }

    /** v16: the reply (talking back, Reply.kt) and the queue that retries the unsent answers. */
    val reply by lazy { Reply(this) }
    fun silence() { pendingPlay = null; sayGen++; queued.set(0); if (player != null) stopPlayer() else if (reading) pendingCmd.set("stop"); hidePressed() }

    // ---------------------------------------------------------------- v24: the voice is a proper player (Marko, 13.11.2026)
    // TTT asks for AUDIOFOCUS_GAIN_TRANSIENT_EXCLUSIVE when he presses record and abandons it when the dictation is sent.
    // The voice holds focus while it speaks: LOSS_* = pause at this word and hold; GAIN = go on from the same place. A reading
    // that arrives while another player has the floor waits (delayed focus) and starts when it is given back. Mirror of the
    // Mac's MicGuard. A media session lets a system pause/play key reach it too. Nothing in TTT changes.
    @Volatile var yielded = false                  // paused by the focus system (not by his button)
    @Volatile var pendingPlay: JSONObject? = null  // waiting for focus
    @Volatile var focusLosses = 0
    private var focusReq: android.media.AudioFocusRequest? = null
    private var session: android.media.session.MediaSession? = null
    private val audioMgr by lazy { getSystemService(AUDIO_SERVICE) as android.media.AudioManager }

    private val focusListener = android.media.AudioManager.OnAudioFocusChangeListener { change ->
        when (change) {
            android.media.AudioManager.AUDIOFOCUS_LOSS, android.media.AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> {
                focusLosses++
                val m = player
                if (m != null && m.isPlaying) { m.pause(); paused = true; yielded = true; pendingCmd.set(""); notifyNow() }
                else if (m != null) yielded = true
            }
            android.media.AudioManager.AUDIOFOCUS_GAIN -> {
                val pj = pendingPlay
                if (pj != null) { pendingPlay = null; main.post { play(pj) } }
                else {
                    val m = player
                    if (yielded && m != null) { yielded = false; m.start(); applyRate(); paused = false; notifyNow() }
                    yielded = false
                }
            }
        }
    }

    /** True when the voice has the floor now; false when it is held (delayed) and [j] will play on GAIN. */
    private fun takeFocus(j: JSONObject): Boolean {
        val req = android.media.AudioFocusRequest.Builder(android.media.AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
            .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
            .setAcceptsDelayedFocusGain(true).setOnAudioFocusChangeListener(focusListener, main).build()
        focusReq = req
        return when (audioMgr.requestAudioFocus(req)) {
            android.media.AudioManager.AUDIOFOCUS_REQUEST_GRANTED -> true
            android.media.AudioManager.AUDIOFOCUS_REQUEST_DELAYED -> { pendingPlay = j; true.not() }
            else -> { pendingPlay = j; false }
        }
    }

    private fun giveFocus() { focusReq?.let { runCatching { audioMgr.abandonAudioFocusRequest(it) } }; focusReq = null }

    private fun mediaSession(active: Boolean, playing: Boolean) {
        runCatching {
            val ms = session ?: android.media.session.MediaSession(this, "VoiceEngine").also { n ->
                n.setCallback(object : android.media.session.MediaSession.Callback() {
                    override fun onPause() { main.post { player?.let { if (it.isPlaying) toggleLocal() } } }
                    override fun onPlay() { main.post { player?.let { if (!it.isPlaying) toggleLocal() } } }
                    override fun onStop() { main.post { silence() } }
                })
                session = n
            }
            ms.setPlaybackState(android.media.session.PlaybackState.Builder()
                .setActions(android.media.session.PlaybackState.ACTION_PLAY or android.media.session.PlaybackState.ACTION_PAUSE or android.media.session.PlaybackState.ACTION_PLAY_PAUSE or android.media.session.PlaybackState.ACTION_STOP)
                .setState(if (playing) android.media.session.PlaybackState.STATE_PLAYING else android.media.session.PlaybackState.STATE_PAUSED, 0, 1f).build())
            ms.isActive = active
        }
    }

    private fun stopPlayer() {
        playJob?.let { main.removeCallbacks(it) }; playJob = null
        val m = player ?: return
        player = null
        lastEndAt = SystemClock.elapsedRealtime()
        yielded = false; giveFocus(); mediaSession(false, false)
        try { m.stop() } catch (e: Exception) { }
        m.release()
        endReading(rid)
    }

    private fun toggleLocal(): Boolean {
        val m = player ?: return false
        if (m.isPlaying) { m.pause(); paused = true } else { m.start(); applyRate(); paused = false }
        pendingCmd.set("")
        return true
    }

    private fun rawWords(a: JSONArray?): List<DoubleArray> {
        if (a == null) return emptyList()
        val out = ArrayList<DoubleArray>(a.length())
        for (i in 0 until a.length()) {
            val o = a.optJSONObject(i) ?: continue
            if (!o.has("s") || !o.has("e") || !o.has("t")) continue
            out.add(doubleArrayOf(o.optDouble("s"), o.optDouble("e"), o.optDouble("t"), o.optDouble("d", o.optDouble("t") + 0.3)))
        }
        return out
    }

    private fun position(j: JSONObject) {
        val id = j.optString("id", "")
        if (!reading || (id.isNotEmpty() && rid.isNotEmpty() && id != rid)) return
        val p = j.optDouble("pos", -1.0)
        val wasPaused = paused
        paused = j.optBoolean("paused", false)
        val dur = j.optDouble("dur", 0.0)
        if (words.isEmpty() && dur > 0) { words = Reading.estimate(text, dur); rebuildSegs() }
        if (!p.isNaN()) clock.sample(p, nowS(), paused)
        lastPos = SystemClock.elapsedRealtime()
        pauseSync.sync(paused, nowS())
        overlay.setPaused(pauseSync.paused)
        if (wasPaused != paused) notifyNow()
    }

    private fun endReading(id: String) {
        if (!reading) return
        if (id.isNotEmpty() && rid.isNotEmpty() && id != rid) return
        reading = false
        paused = false
        pauseSync.reset(); overlay.setPaused(false)
        main.removeCallbacks(tick)
        if (overlay.shown && shownPiece >= 0) { lit = -1; paintPiece(shownPiece, -1) }
        if (overlay.shown && conf.linger > 0) {
            val job = Runnable { lingerJob = null; if (!reading) overlay.close() }
            lingerJob = job
            main.postDelayed(job, (conf.linger * 1000).toLong())
        }
        notifyNow()
    }

    private fun frame() {
        if (!reading) return
        if (SystemClock.elapsedRealtime() - lastPos > 4000) { endReading(rid); return }   // the talker went away
        if (!conf.subtitles || dismissed) { if (overlay.shown) overlay.close(); return }
        if (!overlay.shown) {
            if (!overlay.open(conf)) return
            shownPiece = -2
        }
        val t = clock.at(nowS(), paused) + Reading.LEAD + conf.offset / 1000.0
        val k = if (clock.running) Reading.litAt(words, t) else -1
        // only the chunk holding the word being said (the first chunk before the first word, the last one stays at the end)
        val pi = if (words.isNotEmpty() && segOfWord.size == words.size && segs.isNotEmpty()) {
            if (k >= 0) segOfWord[k] else if (clock.running && t > words[0].t) segs.lastIndex else 0
        } else Reading.pieceOf(pieces, if (clock.running) Reading.placeAt(words, t, k, text.length) else 0)
        if (pi != shownPiece || k != lit) {
            if (pi != shownPiece && pi in segs.indices) {
                nowText = text.substring(segs[pi].start, segs[pi].end)
                if (shownPiece >= -1) notifyNow()
            }
            shownPiece = pi
            lit = k
            paintPiece(pi, k)
        }
    }

    private fun paintPiece(pi: Int, k: Int) {
        if (pi < 0 || pi >= segs.size) return
        val p = segs[pi]
        val sub = text.substring(p.start, p.end)
        var hs = -1
        var he = -1
        if (k >= 0 && k < words.size) {
            val w = words[k]
            if (w.s >= p.start && w.e <= p.end) { hs = w.s - p.start; he = w.e - p.start }
        }
        overlay.show(sub, hs, he)
    }

    // ---------------------------------------------------------------- the buttons

    /** HIDE: the subtitle goes, the voice reads on; SUB in the notification, or the next reading, brings it back. */
    fun hidePressed() {
        dismissed = true
        lingerJob?.let { main.removeCallbacks(it) }; lingerJob = null
        testJob?.let { main.removeCallbacks(it) }; testJob = null
        overlay.close()
    }

    /** STOP: the reading ends completely (the talker hears it in its next /pos, within a fifth of a second). */
    fun stopPressed() {
        queued.set(0)                 // STOP ends the queue too
        sayGen++                      // a voice still being fetched must not start afterwards ("It doesn't stop", 13.11.2026)
        if (player != null) stopPlayer()
        else if (reading) pendingCmd.set("stop")
        hidePressed()
        main.postDelayed({ if (reading) endReading(rid) }, 2500)
    }

    // ------------------------------------------------ v22: the Mac's controls (previous / next sentence, skip reading, speed)

    private fun playing(): MediaPlayer? = player

    /** Where the sentence [i] begins, in seconds of the clip: the start of its first word. */
    private fun sentenceStart(i: Int): Double? {
        val p = pieces.getOrNull(i) ?: return null
        return words.firstOrNull { it.s >= p.start }?.t
    }

    private fun currentSentence(): Int {
        val k = if (lit >= 0) lit else 0
        val at = words.getOrNull(k)?.s ?: 0
        return Reading.pieceOf(pieces, at)
    }

    private fun seekTo(sec: Double) {
        val m = playing() ?: return
        m.seekTo((sec * 1000).toInt().coerceAtLeast(0))
        clock.sample(sec, nowS(), paused)
    }

    /** NEXT: the next sentence; on the last one, the next reading in the queue (as the Mac's /speak/next). */
    fun nextSentence() {
        if (!reading || player == null) return
        val i = currentSentence()
        val t = sentenceStart(i + 1)
        if (t != null) seekTo(t) else skipReading()
    }

    /** PREVIOUS: the sentence before is said again. */
    fun prevSentence() {
        if (!reading || player == null) return
        val i = currentSentence()
        seekTo(sentenceStart(maxOf(0, i - 1)) ?: 0.0)
    }

    /** SKIP: the rest of this reading is dropped; the next reading in the queue follows after the 0.6 s gap. */
    fun skipReading() {
        if (player != null) stopPlayer()
    }

    /** SLOWER / FASTER and POST /say/rate: 0.8 to 1.6, pitch kept, remembered; the word timings follow because they run on the clip's own time. */
    fun setRate(r: Double) {
        rate = (Math.round(r.coerceIn(0.8, 1.6) * 10) / 10.0)
        getSharedPreferences("engine", Context.MODE_PRIVATE).edit().putFloat("rate", rate.toFloat()).apply()
        overlay.setRate(rate)
        applyRate()
    }
    fun nudgeRate(d: Double) = setRate(rate + d)

    private fun applyRate() {
        val m = player ?: return
        if (Build.VERSION.SDK_INT < 23) return
        try {
            // setPlaybackParams on a paused player can start it: only while it plays; resume applies it too
            if (m.isPlaying) m.playbackParams = android.media.PlaybackParams().setSpeed(rate.toFloat()).setPitch(1f)
        } catch (e: Exception) { }
    }

    private val pauseSync = PauseSync()

    /** PAUSE / CONTINUE on the subtitle: the same toggle as the notification's, the button flips at once (PauseSync). */
    fun pausePressed() {
        if (!reading) return
        pauseSync.press(nowS())
        pendingCmd.set("toggle")
        toggleLocal()
        overlay.setPaused(pauseSync.paused)
    }

    fun togglePressed() {
        if (reading) { pendingCmd.set("toggle"); toggleLocal() }
    }

    fun setSubtitles(on: Boolean) {
        conf = SubSettings.load(this).copy(subtitles = on)
        conf.save(this)
        if (on) {
            dismissed = false
            if (reading) { main.removeCallbacks(tick); main.post(tick) }
        } else {
            overlay.close()
        }
        notifyNow()
    }

    private fun dragged(ypos: Double) {
        conf = SubSettings.load(this).copy(ypos = ypos)
        conf.save(this)
    }

    /** A subtitle with no voice behind it, for [seconds] (the second word lit, so the word's colour shows too). */
    fun test(t: String, seconds: Double) {
        if (reading) return
        overlay.setRate(rate)
        conf = SubSettings.load(this)
        lingerJob?.let { main.removeCallbacks(it) }; lingerJob = null
        testJob?.let { main.removeCallbacks(it) }; testJob = null
        if (!overlay.open(conf)) return
        val m = Regex("\\S+").findAll(t).drop(1).firstOrNull()
        overlay.show(t, m?.range?.first ?: -1, m?.range?.last?.plus(1) ?: -1)
        val job = Runnable { testJob = null; if (!reading) overlay.close() }
        testJob = job
        main.postDelayed(job, (seconds * 1000).toLong())
    }

    /** The settings changed: shown at once on the band that is up, else as a test for four seconds (as on the Mac). */
    fun preview() {
        conf = SubSettings.load(this)
        if (!conf.subtitles) { overlay.close(); notifyNow(); return }
        if (reading) {
            if (overlay.shown) { overlay.restyle(conf); if (shownPiece >= 0) paintPiece(shownPiece, lit) }
        } else {
            test("This is how the subtitle looks while I speak to you.", 4.0)
        }
        notifyNow()
    }

    fun canDraw() = overlay.canDraw()

    // ---------------------------------------------------------------- the notification (the Mac's ▶ ■ SUB)

    private fun pi(action: String, code: Int): PendingIntent = PendingIntent.getService(
        this, code, Intent(this, VoiceService::class.java).setAction(action),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    private fun notification(): Notification {
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val b = Notification.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setShowWhen(false)
            .setContentIntent(open)
        if (reading) {
            b.setContentTitle(if (paused) "Paused" else "Speaking")
            b.setContentText(nowText)
            b.addAction(Notification.Action.Builder(null as Icon?, if (paused) "▶ PLAY" else "❚❚ PAUSE", pi(A_TOGGLE, 1)).build())
            b.addAction(Notification.Action.Builder(null as Icon?, "■ STOP", pi(A_STOP, 2)).build())
        } else {
            b.setContentTitle("Voice Engine")
            b.setContentText(if (port > 0) "Waiting for the voice · 127.0.0.1:$port" else "Starting")
        }
        b.addAction(Notification.Action.Builder(null as Icon?, if (conf.subtitles) "SUB ON" else "SUB OFF", pi(A_SUB, 3)).build())
        return b.build()
    }

    private fun notifyNow() {
        getSystemService(NotificationManager::class.java).notify(NID, notification())
    }

    // ---------------------------------------------------------------- the server, 127.0.0.1 only

    private fun serve() {
        var ss: ServerSocket? = null
        for (p in PORT0 until PORT0 + 16) {
            try {
                ss = ServerSocket(p, 16, InetAddress.getByName("127.0.0.1"))
                break
            } catch (e: java.io.IOException) { }
        }
        val s = ss ?: return
        server = s
        port = s.localPort
        main.post { notifyNow() }
        while (!s.isClosed) {
            val c = try { s.accept() } catch (e: java.io.IOException) { break }
            try { pool.execute { handle(c) } } catch (e: java.util.concurrent.RejectedExecutionException) { c.close() }
        }
    }

    private fun readLine(inp: InputStream): String? {
        val b = ByteArrayOutputStream()
        while (true) {
            val c = inp.read()
            if (c < 0) return if (b.size() == 0) null else b.toString("ISO-8859-1")
            if (c == '\n'.code) return b.toString("ISO-8859-1").trimEnd('\r')
            b.write(c)
            if (b.size() > 16384) return null
        }
    }

    private fun handle(c: Socket) {
        c.use { sock ->
            try {
                sock.soTimeout = 15000
                val inp = BufferedInputStream(sock.getInputStream())
                val first = readLine(inp) ?: return
                val parts = first.split(" ")
                if (parts.size < 2) return
                val method = parts[0]
                val path = parts[1].substringBefore('?')
                var len = 0
                while (true) {
                    val h = readLine(inp) ?: break
                    if (h.isEmpty()) break
                    val i = h.indexOf(':')
                    if (i > 0 && h.substring(0, i).trim().equals("content-length", true)) len = h.substring(i + 1).trim().toIntOrNull() ?: 0
                }
                if (len > 16_000_000) return
                val body = ByteArray(len)
                var got = 0
                while (got < len) {
                    val n = inp.read(body, got, len - got)
                    if (n < 0) break
                    got += n
                }
                val j = try { if (got > 0) JSONObject(String(body, 0, got, Charsets.UTF_8)) else JSONObject() } catch (e: org.json.JSONException) { JSONObject() }
                val (code, out) = route(method, path, j)
                val bytes = out.toString().toByteArray(Charsets.UTF_8)
                val head = "HTTP/1.1 $code ${if (code == 200) "OK" else "Error"}\r\nContent-Type: application/json; charset=utf-8\r\n" +
                    "Content-Length: ${bytes.size}\r\nConnection: close\r\n\r\n"
                val o = sock.getOutputStream()
                o.write(head.toByteArray(Charsets.ISO_8859_1))
                o.write(bytes)
                o.flush()
            } catch (e: java.io.IOException) { }
        }
    }

    private fun <T> onMain(f: () -> T): T? {
        val latch = CountDownLatch(1)
        val box = AtomicReference<T?>(null)
        main.post { try { box.set(f()) } finally { latch.countDown() } }
        latch.await(2, TimeUnit.SECONDS)
        return box.get()
    }

    private fun ok() = JSONObject().put("ok", true)

    private fun route(method: String, path: String, j: JSONObject): Pair<Int, JSONObject> {
        val post = method == "POST"
        return when {
            path == "/status" || path == "/" -> 200 to (onMain { status() } ?: JSONObject().put("app", "voiceengine"))
            post && path == "/start" -> { onMain { startReading(j) }; 200 to ok().put("subtitles", conf.subtitles) }
            // v4: text only; the engine asks Speechify and speaks itself (Marko, 13.11.2026). Answers at once; the voice follows.
            post && path == "/say" -> {
                val text = j.optString("text", "").trim()
                if (text.isEmpty()) 400 to JSONObject().put("ok", false).put("error", "no text")
                else if (!Speechify.hasKey(this) && !MacServer.enabled(this) && !j.optBoolean("reply", false)) 409 to JSONObject().put("ok", false).put("error", "no Speechify key: pick the key file in Voice Engine")
                else {
                    val voice = j.optString("voice", "").ifBlank { Speechify.voice(this) }
                    val wantsReply = j.optBoolean("reply", false)
                    if (wantsReply) onMain { reply.offer(targetOf(j)) }
                    val ticket = ticketSeq.getAndIncrement()
                    queued.incrementAndGet()
                    Thread {
                        try { sayLocal(text, voice, j.optString("id", ""), wantsReply, ticket) }
                        finally {
                            while (nextTicket.get() != ticket) Thread.sleep(40)     // pass the turn on in order, also when this one was stopped
                            nextTicket.incrementAndGet()
                            if (queued.get() > 0) queued.decrementAndGet()
                        }
                    }.start()
                    200 to JSONObject().put("ok", true).put("queued", queued.get()).put("voice", voice).put("subtitles", conf.subtitles).put("reply", wantsReply).put("spoken", Speechify.hasKey(this))
                }
            }
            // v16: a message from a session with the REPLY button but no voice (tests, or when he reads it); POST /reply = the button pressed
            post && path == "/offer" -> { onMain { val t = j.optString("text", "").trim(); if (t.isNotEmpty()) test(t, 20.0); reply.offer(targetOf(j)) }; 200 to ok().put("reply", reply.state.name) }
            post && path == "/reply" -> { onMain { reply.press() }; 200 to ok().put("reply", reply.state.name) }
            // v24: the engine choice "Mac (free)" / "speechify", and the Mac's tailnet address
            path == "/engine" -> {
                if (post) { j.optString("engine", "").takeIf { it.isNotBlank() }?.let { MacServer.setEngine(this, it) }; j.optString("host", "").takeIf { it.isNotBlank() }?.let { MacServer.setHost(this, it) } }
                200 to ok().put("engine", if (MacServer.enabled(this)) "mac" else "speechify").put("host", MacServer.host(this)).put("label", MacServer.label(this))
            }
            // v26: a stand-in for TTT's record button (same focus request: GAIN_TRANSIENT_EXCLUSIVE, speech), for tests: POST /focus-test {seconds}
            post && path == "/focus-test" -> {
                val secs = j.optInt("seconds", 4)
                val rq = android.media.AudioFocusRequest.Builder(android.media.AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_EXCLUSIVE)
                    .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
                    .setOnAudioFocusChangeListener { }.build()
                val r = audioMgr.requestAudioFocus(rq)
                main.postDelayed({ audioMgr.abandonAudioFocusRequest(rq) }, secs * 1000L)
                200 to ok().put("granted", r == android.media.AudioManager.AUDIOFOCUS_REQUEST_GRANTED).put("seconds", secs)
            }
            post && path == "/lock" -> { onMain { lock.on(j.optString("text", "Working on the phone. It will be yours again when this disappears."), j.optInt("seconds", 0)) }; 200 to JSONObject().put("ok", true).put("lock", lock.state) }
            post && path == "/request" -> { onMain { lock.request(j.optString("text", "Claude needs the phone for a moment.")) }; 200 to JSONObject().put("ok", true).put("lock", lock.state) }
            post && path == "/unlock" -> { onMain { lock.off() }; 200 to JSONObject().put("ok", true).put("lock", lock.state) }
            post && path == "/through" -> { onMain { lock.through(j.optLong("ms", 800L)) }; 200 to JSONObject().put("ok", true).put("lock", lock.state) }
            post && path == "/play" -> { val r = onMain { play(j) } ?: false; (if (r) 200 else 400) to JSONObject().put("ok", r).put("subtitles", conf.subtitles) }
            post && path == "/pos" -> { onMain { position(j) }; 200 to JSONObject().put("cmd", pendingCmd.getAndSet("")) }
            post && path == "/end" -> { onMain { endReading(j.optString("id", "")) }; 200 to ok() }
            post && path == "/test" -> {
                val t = j.optString("text", "").ifBlank { "This is how the subtitle looks while I speak to you." }
                onMain { test(t, j.optDouble("seconds", 8.0).coerceIn(1.0, 600.0)) }
                200 to ok().put("overlay", overlay.canDraw())
            }
            post && (path == "/say/next" || path == "/next") -> { onMain { nextSentence() }; 200 to ok() }
            post && (path == "/say/prev" || path == "/say/previous" || path == "/prev") -> { onMain { prevSentence() }; 200 to ok() }
            post && (path == "/say/skip" || path == "/skip") -> { onMain { skipReading() }; 200 to ok().put("queued", maxOf(0, queued.get() - 1)) }
            post && (path == "/say/rate" || path == "/rate") -> {
                onMain { setRate(if (j.has("rate")) j.optDouble("rate", rate) else rate + j.optDouble("delta", 0.0)) }
                200 to ok().put("rate", rate)
            }
            post && path == "/hide" -> { onMain { hidePressed() }; 200 to ok() }
            post && path == "/stop" -> { onMain { stopPressed() }; 200 to ok() }
            post && path == "/toggle" -> { onMain { togglePressed() }; 200 to ok() }
            post && path == "/subtitles" -> { onMain { setSubtitles(j.optBoolean("on", true)) }; 200 to ok().put("subtitles", j.optBoolean("on", true)) }
            path == "/settings" && !post -> 200 to SubSettings.load(this).toJson()
            post && path == "/settings" -> {
                val merged = SubSettings.from(j, SubSettings.load(this))
                merged.save(this)
                onMain { preview() }
                200 to merged.toJson()
            }
            else -> 404 to JSONObject().put("error", "no such path: $method $path")
        }
    }

    private fun targetOf(j: JSONObject) = Target(j.optString("from", ""), j.optString("from_id", ""), j.optString("relay", ""), j.optString("token", ""))

    private fun status(): JSONObject {
        val v = try { packageManager.getPackageInfo(packageName, 0).versionName } catch (e: Exception) { "?" }
        return JSONObject().put("app", "voiceengine").put("version", v).put("port", port)
            .put("queue", queued.get()).put("rate", rate).put("reading", reading).put("paused", paused).put("subtitles", conf.subtitles)
            .put("shown", overlay.shown).put("overlay", overlay.canDraw()).put("text", if (reading) nowText else "")
            .put("lock", lock.state).put("speechify", Speechify.hasKey(this))
            .put("rotation_guard", if (rotation.allowed()) "on" else "needs permission").put("rotation_caught", rotation.caught).put("rotation_last", rotation.lastAt).put("engine", MacServer.label(this)).put("mac_fallbacks", MacServer.fallbacks).put("yielded", yielded).put("waiting_for_focus", pendingPlay != null).put("focus_losses", focusLosses).put("key", Speechify.ringLabel(this)).put("voice", Speechify.voice(this)).put("model", Speechify.modelLabel(this)).put("say_error", lastSayError)
            .put("reply", reply.state.name).put("reply_to", reply.target?.from ?: "").put("reply_text", reply.lastText).put("reply_note", reply.note)
            .put("reply_shown", reply.shown).put("outbox", Outbox.size(this)).put("outbox_last", Outbox.lastResult(this))
            .put("groq", GroqKeys.hasKey(this)).put("groq_key", GroqKeys.ringLabel(this))
            .put("mic", checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) == android.content.pm.PackageManager.PERMISSION_GRANTED)
    }
}
