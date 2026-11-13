package community.auroville.voiceengine

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * v16, 13.11.2026. Marko: "for transcribing anything now, its default engine is Groq, large model of Whisper ... over 3
 * accounts, not a paid one, by rotating the API keys." The ring is the Speechify ring's twin (keyring.md): keys picked
 * from a file (Download/Api/groq*.txt), each tested when picked, kept encrypted as one block, forward-only cursor,
 * 401/403/429 -> the next key and the job restarts from the beginning on it (one key per job, keyring.md 2b).
 * The sound goes to Groq only (the transcription service he chose); between his machines only the TEXT travels.
 */
object GroqKeys {
    private const val BASE = "https://api.groq.com/openai/v1"
    private const val PREFS = "groq"
    private const val ALIAS = "voiceengine_groq"
    const val MODEL = "whisper-large-v3"

    private fun p(c: Context) = c.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private fun secret(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getKey(ALIAS, null) as? SecretKey)?.let { return it }
        val g = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        g.init(KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build())
        return g.generateKey()
    }

    private fun enc(t: String): String {
        val ci = Cipher.getInstance("AES/GCM/NoPadding"); ci.init(Cipher.ENCRYPT_MODE, secret())
        return Base64.encodeToString(ci.iv + ci.doFinal(t.toByteArray()), Base64.NO_WRAP)
    }

    private fun dec(b: String): String? = runCatching {
        val raw = Base64.decode(b, Base64.NO_WRAP)
        val ci = Cipher.getInstance("AES/GCM/NoPadding")
        ci.init(Cipher.DECRYPT_MODE, secret(), GCMParameterSpec(128, raw.copyOfRange(0, 12)))
        String(ci.doFinal(raw.copyOfRange(12, raw.size)))
    }.getOrNull()

    fun saveKeys(c: Context, keys: List<String>): Boolean = runCatching {
        p(c).edit().putString("ks", enc(keys.joinToString("\n"))).putInt("cur", 0).apply(); true
    }.getOrDefault(false)

    fun keys(c: Context): List<String> =
        p(c).getString("ks", null)?.let { dec(it) }?.lines()?.filter { it.isNotBlank() }.orEmpty()

    fun hasKey(c: Context) = keys(c).isNotEmpty()
    fun deleteKeys(c: Context) = p(c).edit().remove("ks").remove("cur").apply()
    fun ringLabel(c: Context): String { val n = keys(c).size; return if (n == 0) "none" else "${(cursor(c) % n) + 1}/$n" }
    private fun cursor(c: Context) = p(c).getInt("cur", 0)
    private fun setCursor(c: Context, i: Int) = p(c).edit().putInt("cur", i).apply()

    /** Groq keys start gsk_ . */
    fun candidates(text: String): List<String> = Regex("gsk_[A-Za-z0-9]{20,}").findAll(text).map { it.value }.distinct().toList()

    /** Blocking. "works" or why not; never the key. */
    fun test(key: String): String = runCatching {
        val conn = (URL("$BASE/models").openConnection() as HttpURLConnection).apply {
            connectTimeout = 10000; readTimeout = 10000; setRequestProperty("Authorization", "Bearer $key")
        }
        when (val code = conn.responseCode) {
            200 -> "works"
            401, 403 -> "does not work (refused)"
            429 -> "does not work (throttled)"
            else -> "does not work (HTTP $code)"
        }
    }.getOrElse { "not reached (no internet?)" }

    /** Blocking. Whisper large v3 on [wav]; the text, or throws with a short reason. The job restarts on the next key. */
    fun transcribe(c: Context, wav: ByteArray): String {
        val all = keys(c)
        if (all.isEmpty()) throw IllegalStateException("no Groq key: pick the key file in Voice Engine")
        val start = cursor(c) % all.size
        var last = 0
        for (step in all.indices) {
            val idx = (start + step) % all.size
            val (code, text) = one(all[idx], wav)
            if (code == 200) { setCursor(c, idx); return text }
            last = code
            if (code !in setOf(401, 403, 429)) break
        }
        throw IllegalStateException(if (last in setOf(401, 403, 429)) "every Groq key is out ($last)" else "Groq answered $last")
    }

    private fun one(key: String, wav: ByteArray): Pair<Int, String> {
        val boundary = "----ve" + System.nanoTime()
        val out = ByteArrayOutputStream()
        fun field(n: String, v: String) { out.write("--$boundary\r\nContent-Disposition: form-data; name=\"$n\"\r\n\r\n$v\r\n".toByteArray()) }
        field("model", MODEL); field("response_format", "json"); field("temperature", "0")
        out.write("--$boundary\r\nContent-Disposition: form-data; name=\"file\"; filename=\"reply.wav\"\r\nContent-Type: audio/wav\r\n\r\n".toByteArray())
        out.write(wav); out.write("\r\n--$boundary--\r\n".toByteArray())
        val conn = (URL("$BASE/audio/transcriptions").openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"; doOutput = true; connectTimeout = 15000; readTimeout = 90000
            setRequestProperty("Authorization", "Bearer $key")
            setRequestProperty("Content-Type", "multipart/form-data; boundary=$boundary")
        }
        conn.outputStream.use { it.write(out.toByteArray()) }
        val code = conn.responseCode
        if (code != 200) return code to ""
        return 200 to JSONObject(conn.inputStream.bufferedReader().use { it.readText() }).optString("text", "").trim()
    }
}
