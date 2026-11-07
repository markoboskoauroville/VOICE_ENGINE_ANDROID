package community.auroville.voiceengine

// THE MECHANISM, imports nothing from Android (android-app.md §1): the words, the pieces of text shown one at a time,
// which word is being said, and the clock. Copied from the Mac's Voice Engine (VOICE_ENGINE app/Subtitle.swift), which
// copied it from MA Reader web (LESSONS.md 1): a predicted clock on wall time, eased halfway to each real position,
// snapped when 0.35 s off; the lit word is the last one started at clock + 50 ms; the last word lets go 0.12 s after its end.

/** One word: s, e = UTF-16 offsets into the whole text; t = when it starts, d = when it ends (seconds into the clip). */
data class Word(val s: Int, val e: Int, val t: Double, val d: Double)

/** A piece of the text shown on its own, like one film subtitle: [start, end) in UTF-16. */
data class Piece(val start: Int, val end: Int)

object Reading {
    const val LEAD = 0.05            // MA Reader's WORD_LEAD: a word lit a breath early reads as on time
    const val LET_GO = 0.12          // the last word stays lit this long after its end
    const val MAX_PIECE = 150        // a phone is narrow: a longer sentence is cut at a comma or a space, never lost

    /** The server counts characters (code points, Python), Kotlin strings count UTF-16 units: index k -> UTF-16. */
    fun cpMap(text: String): IntArray {
        val n = text.codePointCount(0, text.length)
        val m = IntArray(n + 1)
        var i = 0
        var k = 0
        while (i < text.length) {
            m[k++] = i
            i += Character.charCount(text.codePointAt(i))
        }
        m[k] = text.length
        return m
    }

    /** Words from the server's [{s, e, t, d}] (code points), into UTF-16, clamped, in time order. */
    fun fromServer(text: String, raw: List<DoubleArray>): List<Word> {
        val m = cpMap(text)
        val last = m.size - 1
        val out = ArrayList<Word>()
        for (r in raw) {
            if (r.size < 4) continue
            val s = r[0].toInt().coerceIn(0, last)
            val e = r[1].toInt().coerceIn(s, last)
            if (e <= s) continue
            val t = r[2]
            val d = if (r[3] < t) t + r[3] else r[3]      // a duration written where the end belongs (server.py does the same)
            out.add(Word(m[s], m[e], t, d))
        }
        out.sortBy { it.t }
        return out
    }

    /** No timings came with the clip: spread its length over the words by their letters (a guess, but in step). */
    fun estimate(text: String, duration: Double): List<Word> {
        if (duration <= 0) return emptyList()
        val spans = Regex("\\S+").findAll(text).map { it.range.first to it.range.last + 1 }.toList()
        if (spans.isEmpty()) return emptyList()
        val weights = spans.map { (s, e) -> (e - s + 1).toDouble() }
        val total = weights.sum()
        val speech = duration * 0.97
        var t = 0.0
        return spans.mapIndexed { i, (s, e) ->
            val len = speech * weights[i] / total
            val w = Word(s, e, t, t + len)
            t += len
            w
        }
    }

    /** The text cut into pieces: each sentence, and a sentence longer than [max] cut again at a comma or a space. */
    fun pieces(text: String, max: Int = MAX_PIECE): List<Piece> {
        val out = ArrayList<Piece>()
        fun add(a0: Int, b0: Int) {
            var a = a0
            var b = b0
            while (a < b && text[a].isWhitespace()) a++
            while (b > a && text[b - 1].isWhitespace()) b--
            while (b - a > max) {
                val lo = a + max / 2
                val hi = a + max
                var cut = -1
                for (i in hi - 1 downTo lo) if ((text[i] == ',' || text[i] == ';' || text[i] == ':') && i + 1 < b && text[i + 1] == ' ') { cut = i + 1; break }
                if (cut < 0) for (i in hi downTo lo) if (text[i] == ' ') { cut = i; break }
                if (cut < 0) cut = hi
                out.add(Piece(a, cut))
                a = cut
                while (a < b && text[a].isWhitespace()) a++
            }
            if (a < b) out.add(Piece(a, b))
        }
        var st = 0
        for (i in text.indices) {
            val c = text[i]
            if ((c == '.' || c == '!' || c == '?' || c == '…') && (i + 1 == text.length || text[i + 1].isWhitespace())) {
                add(st, i + 1)
                st = i + 1
            }
        }
        add(st, text.length)
        if (out.isEmpty() && text.isNotEmpty()) out.add(Piece(0, text.length))
        return out
    }

    /** The piece holding UTF-16 offset [at]; the last one when [at] is past them all. */
    fun pieceOf(pieces: List<Piece>, at: Int): Int {
        if (pieces.isEmpty()) return -1
        for (i in pieces.indices) if (at < pieces[i].end) return i
        return pieces.size - 1
    }

    /** The word being said at [t]: the last one started; -1 before the first and once the last has let go. */
    fun litAt(words: List<Word>, t: Double): Int {
        var k = -1
        var lo = 0
        var hi = words.size - 1
        while (lo <= hi) {
            val m = (lo + hi) / 2
            if (words[m].t <= t) { k = m; lo = m + 1 } else hi = m - 1
        }
        if (k >= 0 && k == words.size - 1 && t > words[k].d + LET_GO) return -1
        return k
    }

    /** Where in the text the subtitle should stand at [t]: the lit word, else the next word to come, else the end. */
    fun placeAt(words: List<Word>, t: Double, k: Int, textLength: Int): Int {
        if (k >= 0) return words[k].s
        val next = words.firstOrNull { it.t > t }
        if (next != null) return next.s
        return if (words.isNotEmpty() && t > 0.1) textLength else 0
    }
}

/** MA Reader's clock: predicted on wall time between answers, eased toward each answer, snapped when far off. */
class Clock {
    var pred = -1.0
        private set
    private var wall = 0.0
    var running = false
        private set

    fun reset() { pred = -1.0; wall = 0.0; running = false }

    fun sample(observed: Double, now: Double, paused: Boolean) {
        if (pred < 0 || observed < 0) {
            pred = observed; wall = now; running = observed >= 0
            return
        }
        if (!paused) pred += now - wall
        wall = now
        val err = observed - pred
        if (kotlin.math.abs(err) > 0.35) pred = observed else pred += err * 0.5
        running = true
    }

    fun at(now: Double, paused: Boolean): Double {
        if (running && !paused) pred += now - wall
        wall = now
        return pred
    }
}
