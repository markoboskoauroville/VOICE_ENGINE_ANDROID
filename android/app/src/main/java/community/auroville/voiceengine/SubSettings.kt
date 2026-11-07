package community.auroville.voiceengine

import android.content.Context
import android.graphics.Color
import org.json.JSONObject

/**
 * The subtitle's settings, the same keys as the Mac's ~/.config/subtitle.json (VOICE_ENGINE app/App.swift
 * SubtitleSettings), so one word means one thing on both. `bandmain` and `voice` are the Mac's own and are not kept here.
 * Kept in SharedPreferences as that same JSON; GET/POST /settings on the app's server reads and changes it.
 */
data class SubSettings(
    val size: Double = 3.0,            // letter height, % of the screen's height (the Mac's 6 is for a landscape screen)
    val colour: String = "#E8A33D",    // the letters
    val band: Double = 62.0,           // the background's opacity, %: clear 0 ... solid 100
    val grey: Double = 18.0,           // the background's shade, % white: dark 0 ... light 100
    val position: String = "middle",   // top, middle, bottom
    val bold: Boolean = false,
    val word: String = "#FF3B30",      // the word being said
    val wordbg: String = "",           // a marker behind that word, "" for none
    val linger: Double = 2.0,          // seconds the last words stay after the voice stops; 0 = until HIDE
    val offset: Double = 0.0,          // ms, the lit word earlier (-) or later (+) than the voice
    val subtitles: Boolean = true,     // SUB: the subtitle on or off
    val ypos: Double = -1.0,           // where it was dragged: its centre as a fraction of the screen height from the
                                       // bottom (0..1), as on the Mac; -1 = `position` decides
) {
    fun toJson(): JSONObject = JSONObject()
        .put("size", size).put("colour", colour).put("band", band).put("grey", grey)
        .put("position", position).put("bold", bold).put("word", word).put("wordbg", wordbg)
        .put("linger", linger).put("offset", offset).put("subtitles", subtitles).put("ypos", ypos)

    fun save(ctx: Context) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY, toJson().toString()).apply()
    }

    companion object {
        private const val PREFS = "subtitle"
        private const val KEY = "json"
        val POSITIONS = listOf("top", "middle", "bottom")

        fun colourOr(hex: String, fallback: Int): Int =
            try { if (hex.isBlank()) fallback else Color.parseColor(if (hex.startsWith("#")) hex else "#$hex") } catch (e: IllegalArgumentException) { fallback }

        private fun hexOk(h: String) = h.isEmpty() || Regex("^#?[0-9A-Fa-f]{6}$").matches(h)

        /** [j] over [base]: a key missing or wrong keeps the base's value (a file from an older version, a typo in a curl). */
        fun from(j: JSONObject, base: SubSettings = SubSettings()): SubSettings {
            fun str(k: String, b: String, ok: (String) -> Boolean): String {
                if (!j.has(k)) return b
                val v = j.optString(k, b).trim()
                return if (ok(v)) v else b
            }
            fun num(k: String, b: Double, lo: Double, hi: Double): Double {
                if (!j.has(k)) return b
                val v = j.optDouble(k, b)
                return if (v.isNaN()) b else v.coerceIn(lo, hi)
            }
            val ypos = if (j.has("ypos")) { val v = j.optDouble("ypos", base.ypos); if (v.isNaN() || v < 0) -1.0 else v.coerceIn(0.0, 1.0) } else base.ypos
            return SubSettings(
                size = num("size", base.size, 1.0, 12.0),
                colour = str("colour", base.colour) { it.isNotEmpty() && hexOk(it) },
                band = num("band", base.band, 0.0, 100.0),
                grey = num("grey", base.grey, 0.0, 100.0),
                position = str("position", base.position) { it in POSITIONS },
                bold = if (j.has("bold")) j.optBoolean("bold", base.bold) else base.bold,
                word = str("word", base.word) { it.isNotEmpty() && hexOk(it) },
                wordbg = str("wordbg", base.wordbg) { hexOk(it) },
                linger = num("linger", base.linger, 0.0, 600.0),
                offset = num("offset", base.offset, -2000.0, 2000.0),
                subtitles = if (j.has("subtitles")) j.optBoolean("subtitles", base.subtitles) else base.subtitles,
                ypos = ypos,
            )
        }

        fun load(ctx: Context): SubSettings {
            val s = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, null) ?: return SubSettings()
            return try { from(JSONObject(s)) } catch (e: org.json.JSONException) { SubSettings() }
        }
    }
}
