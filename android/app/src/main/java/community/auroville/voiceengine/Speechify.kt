package community.auroville.voiceengine

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * v4, 13.11.2026. Marko: "update my speaking engine so the other computer sends only text and the API from Speechify is
 * talking." The engine speaks itself: POST /say {text, voice?} -> Speechify (his voice, Beatrice by default) -> the mp3
 * plays here through the v3 player, the subtitle uses Speechify's own word timings. No audio crosses the network.
 *
 * The key comes from a file only (his rule: keys are picked from a file, never pasted), tested at once ("works" /
 * "does not work"), kept encrypted with an Android Keystore key, never shown or logged.
 */
object Speechify {
    private const val BASE = "https://api.sws.speechify.com/v1"
    private const val PREFS = "speechify"
    private const val ALIAS = "voiceengine_speechify"
    const val DEFAULT_VOICE = "beatrice_32"

    private fun p(c: Context) = c.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private fun secret(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getKey(ALIAS, null) as? SecretKey)?.let { return it }
        val g = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        g.init(KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build())
        return g.generateKey()
    }

    fun saveKey(c: Context, key: String): Boolean = runCatching {
        val ci = Cipher.getInstance("AES/GCM/NoPadding"); ci.init(Cipher.ENCRYPT_MODE, secret())
        val enc = ci.doFinal(key.trim().toByteArray())
        p(c).edit().putString("k", Base64.encodeToString(ci.iv + enc, Base64.NO_WRAP)).apply(); true
    }.getOrDefault(false)

    fun key(c: Context): String? = runCatching {
        val raw = Base64.decode(p(c).getString("k", null) ?: return null, Base64.NO_WRAP)
        val ci = Cipher.getInstance("AES/GCM/NoPadding")
        ci.init(Cipher.DECRYPT_MODE, secret(), GCMParameterSpec(128, raw.copyOfRange(0, 12)))
        String(ci.doFinal(raw.copyOfRange(12, raw.size)))
    }.getOrNull()

    fun hasKey(c: Context) = p(c).getString("k", null) != null
    fun deleteKey(c: Context) = p(c).edit().remove("k").apply()

    fun voice(c: Context): String = p(c).getString("voice", DEFAULT_VOICE) ?: DEFAULT_VOICE
    fun setVoice(c: Context, v: String) = p(c).edit().putString("voice", v).apply()

    /** Candidate Speechify keys in a text: long tokens (Speechify keys are sk_... or 40+ characters). */
    fun candidates(text: String): List<String> =
        Regex("[A-Za-z0-9_\\-]{32,}").findAll(text).map { it.value }.filter { it.any(Char::isDigit) && it.any(Char::isLetter) }.distinct().toList()

    /** Blocking. "works" or why not; never the key. */
    fun test(key: String): String = runCatching {
        val conn = (URL("$BASE/voices?limit=1").openConnection() as HttpURLConnection).apply {
            connectTimeout = 10000; readTimeout = 10000; setRequestProperty("Authorization", "Bearer $key")
        }
        when (val code = conn.responseCode) {
            200 -> "works"
            401, 403 -> "does not work (refused)"
            402 -> "does not work (no balance)"
            else -> "does not work (HTTP $code)"
        }
    }.getOrElse { "not reached (no internet?)" }

    class Spoken(val audioB64: String, val words: JSONArray)

    /**
     * Blocking. One request: the mp3 (base64) and the word timings as the engine reads them ({s, e, t, d}: character
     * offsets in [text], start and end in seconds). Throws with a short reason on failure.
     */
    fun speak(c: Context, text: String, voice: String = voice(c)): Spoken {
        val key = key(c) ?: throw IllegalStateException("no Speechify key: pick the key file in Voice Engine")
        var lastCode = 0
        for (model in listOf("simba-multilingual", "simba-english")) {
            val conn = (URL("$BASE/audio/speech").openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"; doOutput = true; connectTimeout = 15000; readTimeout = 60000
                setRequestProperty("Authorization", "Bearer $key"); setRequestProperty("Content-Type", "application/json")
            }
            val body = JSONObject().put("input", text).put("voice_id", voice).put("audio_format", "mp3").put("model", model)
            conn.outputStream.use { it.write(body.toString().toByteArray()) }
            lastCode = conn.responseCode
            if (lastCode != 200) continue
            val json = JSONObject(conn.inputStream.bufferedReader().use { it.readText() })
            val audio = json.optString("audio_data")
            if (audio.isBlank()) throw IllegalStateException("Speechify sent no audio")
            return Spoken(audio, words(text, json.optJSONObject("speech_marks")))
        }
        throw IllegalStateException("Speechify answered $lastCode")
    }

    /** Speechify's marks (chunks of type word, start/end characters, start_time/end_time ms) as the engine's words. */
    fun words(text: String, marks: JSONObject?): JSONArray {
        val out = JSONArray()
        val chunks = marks?.optJSONArray("chunks") ?: return out
        var from = 0
        for (i in 0 until chunks.length()) {
            val ch = chunks.optJSONObject(i) ?: continue
            if (ch.optString("type") != "word") continue
            val value = ch.optString("value").trim()
            val t0 = ch.optInt("start_time", -1); val t1 = ch.optInt("end_time", -1)
            if (value.isEmpty() || t0 < 0 || t1 < t0) continue
            // Character offsets: Speechify's own when they fit the text, else the next occurrence of the word.
            var s = ch.optInt("start", -1); var e = ch.optInt("end", -1)
            if (s < 0 || e <= s || e > text.length || text.substring(s, e) != value) {
                val at = text.indexOf(value, from)
                if (at < 0) continue
                s = at; e = at + value.length
            }
            from = e
            out.put(JSONObject().put("s", s).put("e", e).put("t", t0 / 1000.0).put("d", t1 / 1000.0))
        }
        return out
    }
}
