package community.auroville.voiceengine

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * v16. The reply travels as TEXT only: POST {"to","text","at"} to the Mac's relay (phonereply.py, port 8875).
 * Where: the address the sender gave in its message (its Tailscale address), then 127.0.0.1:8875 (adb reverse), then
 * 10.0.2.2:8875 (the emulator's name for the Mac). Unreached: the reply stays in this queue (kept on the phone) and
 * is tried again every 20 s until it is delivered.
 */
object Outbox {
    private const val PREFS = "outbox"
    private val lock = Any()
    private val FALLBACK = listOf("http://127.0.0.1:8875", "http://10.0.2.2:8875")

    private fun p(c: Context) = c.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private fun load(c: Context) = runCatching { JSONArray(p(c).getString("q", "[]")) }.getOrDefault(JSONArray())
    private fun save(c: Context, a: JSONArray) = p(c).edit().putString("q", a.toString()).apply()

    fun size(c: Context): Int = synchronized(lock) { load(c).length() }
    fun lastResult(c: Context): String = p(c).getString("last", "") ?: ""

    fun add(c: Context, to: String, toId: String, text: String, relay: String, token: String, at: String): String = synchronized(lock) {
        val id = java.util.UUID.randomUUID().toString()
        val a = load(c)
        a.put(JSONObject().put("to", to).put("to_id", toId).put("text", text).put("at", at).put("relay", relay).put("token", token).put("id", id))
        save(c, a)
        id
    }

    /** Blocking. Tries every waiting reply; returns how many are still waiting. */
    fun flush(c: Context): Int = synchronized(lock) {
        val a = load(c)
        val keep = JSONArray()
        for (i in 0 until a.length()) {
            val o = a.getJSONObject(i)
            if (!deliver(o)) keep.put(o)
        }
        save(c, keep)
        p(c).edit().putString("last", if (keep.length() == 0 && a.length() > 0) "delivered" else if (keep.length() > 0) "waiting ${keep.length()}" else p(c).getString("last", "")).apply()
        keep.length()
    }

    private fun deliver(o: JSONObject): Boolean {
        val urls = listOfNotNull(o.optString("relay").ifBlank { null }) + FALLBACK
        val body = JSONObject().put("to", o.optString("to")).put("to_id", o.optString("to_id")).put("text", o.optString("text")).put("at", o.optString("at")).put("id", o.optString("id")).toString().toByteArray()
        for (u in urls.distinct()) {
            try {
                val conn = (URL(u.trimEnd('/') + "/reply").openConnection() as HttpURLConnection).apply {
                    requestMethod = "POST"; doOutput = true; connectTimeout = 4000; readTimeout = 25000
                    setRequestProperty("Content-Type", "application/json")
                    if (o.optString("token").isNotBlank()) setRequestProperty("X-Mantra-Token", o.optString("token"))
                }
                conn.outputStream.use { it.write(body) }
                if (conn.responseCode == 200) return true
            } catch (e: Exception) { }
        }
        return false
    }
}
