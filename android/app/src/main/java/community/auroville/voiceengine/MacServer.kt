package community.auroville.voiceengine

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * v24, 13.11.2026. Marko: "Since phone is not strong enough to run this kind of models, then you need to build API
 * interface for this models so my Mac itself becomes server for talking and server for everything. So my phones can use
 * the services from my Mac. Over Tailscale, we are all connected."
 *
 * "Mac (free)" is an engine choice: the text goes to the Mac's MANTRA_VOICE (POST /render on the tailnet), the rendered
 * mp3 and its word timings come back and play through the same player and subtitle. Only if the Mac does not answer
 * within [TIMEOUT_MS] does the engine fall back to its own Speechify, and that is a PAID call: it is logged here
 * ([fallbacks], /status) and, once the Cost Agent has a gate API, asked there first (modules/mac-as-server.md).
 * The Mac's /hear does the same for the speech to text (Reply.kt).
 */
object MacServer {
    private const val PREFS = "macserver"
    const val DEFAULT_HOST = "100.79.251.19"       // the Mac on the tailnet (tailscale ip -4), 13.11.2026
    const val PORT = 8837
    const val TIMEOUT_MS = 3000                    // the Mac must answer a render within this, else Speechify (cached clips answer at once)
    const val HEAR_TIMEOUT_MS = 60000

    @Volatile var lastError = ""
    @Volatile var lastEngine = ""
    @Volatile var fallbacks = 0

    private fun p(c: Context) = c.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun enabled(c: Context) = p(c).getString("engine", "mac") == "mac"
    fun setEngine(c: Context, e: String) = p(c).edit().putString("engine", if (e == "speechify") "speechify" else "mac").apply()
    fun host(c: Context) = p(c).getString("host", DEFAULT_HOST) ?: DEFAULT_HOST
    fun setHost(c: Context, h: String) = p(c).edit().putString("host", h.trim()).apply()
    private fun base(c: Context) = "http://${host(c)}:$PORT"

    /** Blocking. The Mac's rendering of [text], or throws (timeout, refused, error). [timeoutMs] bounds the whole answer. */
    fun render(c: Context, text: String, voice: String? = null, timeoutMs: Int = TIMEOUT_MS): Speechify.Spoken {
        val conn = (URL(base(c) + "/render").openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"; doOutput = true; connectTimeout = 2000; readTimeout = timeoutMs
            setRequestProperty("Content-Type", "application/json")
        }
        val body = JSONObject().put("text", text)
        if (!voice.isNullOrBlank()) body.put("voice", voice)
        conn.outputStream.use { it.write(body.toString().toByteArray()) }
        val code = conn.responseCode
        if (code != 200) throw IllegalStateException("Mac answered $code")
        val j = JSONObject(conn.inputStream.bufferedReader().use { it.readText() })
        val audio = j.optString("audio")
        if (audio.isBlank()) throw IllegalStateException("Mac sent no audio")
        val src = j.optJSONArray("words") ?: JSONArray()
        val words = JSONArray()
        for (i in 0 until src.length()) {          // the engine's words: t = start, d = END (Speechify.words, and the Mac's)
            val o = src.optJSONObject(i) ?: continue
            val t = o.optDouble("t")
            // the Mac's word "d" is the END time of the word, as the engine's own (Beatrice marks); never earlier than t
            words.put(JSONObject().put("s", o.optInt("s")).put("e", o.optInt("e")).put("t", t).put("d", maxOf(o.optDouble("d"), t + 0.05)))
        }
        lastEngine = j.optString("engine") + (if (j.optBoolean("cached")) " (cached)" else "")
        return Speechify.Spoken(audio, words)
    }

    /**
     * The one door for speaking: Mac first when chosen, Speechify when the Mac fails or when it is not chosen. A fallback
     * is a paid Speechify call: counted and logged ("fallback to Speechify: <why>"); the Cost Agent gate is not built yet,
     * so nothing is asked there (TODO in modules/mac-as-server.md).
     */
    fun speak(c: Context, text: String, voice: String): Speechify.Spoken {
        if (enabled(c)) {
            try {
                val r = render(c, text)
                lastError = ""
                return r
            } catch (e: Exception) {
                lastError = e.message ?: e.javaClass.simpleName
                fallbacks++
                android.util.Log.w("VoiceEngine", "fallback to Speechify (paid): Mac failed: $lastError")
            }
        }
        return Speechify.speak(c, text, voice)
    }

    /** Blocking. Speech to text on the Mac's /hear (Whisper); [wav] is a 16-bit mono wav. Throws on failure. */
    fun hear(c: Context, wav: ByteArray): String {
        val conn = (URL(base(c) + "/hear").openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"; doOutput = true; connectTimeout = 2000; readTimeout = HEAR_TIMEOUT_MS
            setRequestProperty("Content-Type", "audio/wav")
        }
        conn.outputStream.use { it.write(wav) }
        if (conn.responseCode != 200) throw IllegalStateException("Mac /hear answered ${conn.responseCode}")
        return JSONObject(conn.inputStream.bufferedReader().use { it.readText() }).optString("text", "").trim()
    }

    /** One line for the window and /status. */
    fun label(c: Context): String =
        (if (enabled(c)) "MAC (FREE) ${host(c)}:$PORT" else "SPEECHIFY (OWN KEY)") + " · fallbacks $fallbacks" + (if (lastError.isNotEmpty()) " · last: $lastError" else "")
}
