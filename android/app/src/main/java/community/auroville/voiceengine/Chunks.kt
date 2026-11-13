package community.auroville.voiceengine

/**
 * v22: the Mac's Subtitle.swift makeChunks (v43), ported. Marko, 13.11.2026: "maximum 4 words at one time on the screen.
 * That is it." The words are cut into chunks of at most four, broken at punctuation first; inside a clause the cheapest
 * cut wins: balanced chunks, a break after an article / preposition / conjunction costs a lot ("the | drum" never), a
 * single stranded word joins its neighbour when that stays within four. Imports nothing from Android.
 */
object Chunks {
    const val MAX = 4
    private const val BREAKS = ",;:.!?—–"
    private val BINDERS = setOf(
        "a", "an", "the", "of", "to", "in", "on", "at", "for", "with", "by", "from", "as", "into", "onto",
        "and", "or", "but", "that", "than", "if", "my", "your", "his", "her", "its", "our", "their", "this",
        "these", "those", "is", "are", "was", "be", "not", "no", "so", "about", "over", "under", "through",
    )

    /** Chunks as inclusive word-index ranges [a, b], in order, covering every word. */
    fun make(text: String, words: List<Word>): List<IntArray> {
        if (words.isEmpty()) return emptyList()
        val clauses = ArrayList<ArrayList<Int>>()
        clauses.add(ArrayList())
        for ((i, w) in words.withIndex()) {
            clauses.last().add(i)
            if (w.e in 1..text.length && BREAKS.indexOf(text[w.e - 1]) >= 0) clauses.add(ArrayList())
        }
        fun binder(i: Int): Boolean {
            val w = words[i]
            if (w.s < 0 || w.e > text.length || w.s >= w.e) return false
            return text.substring(w.s, w.e).lowercase().trim { !it.isLetterOrDigit() } in BINDERS
        }
        val out = ArrayList<IntArray>()
        for (cl in clauses) {
            val n = cl.size
            if (n == 0) continue
            val best = DoubleArray(n + 1) { 1e9 }
            val from = IntArray(n + 1)
            best[0] = 0.0
            for (e in 1..n) {
                for (len in 1..minOf(MAX, e)) {
                    val st = e - len
                    if (best[st] >= 1e9) continue
                    var c = 10.0 + (MAX - len) * 0.6 + (if (len == 1 && n > 1) 12 else 0)
                    if (e < n && binder(cl[e - 1])) c += 30
                    if (best[st] + c < best[e]) { best[e] = best[st] + c; from[e] = st }
                }
            }
            val cuts = ArrayList<IntArray>()
            var e = n
            while (e > 0) { val st = from[e]; cuts.add(intArrayOf(cl[st], cl[e - 1])); e = st }
            out.addAll(cuts.reversed())
        }
        var j = 0
        while (j < out.size) {
            val r = out[j]
            if (r[0] == r[1] && out.size > 1) {
                if (j > 0 && out[j - 1][1] - out[j - 1][0] + 2 <= MAX) { out[j - 1][1] = r[1]; out.removeAt(j); continue }
                if (j + 1 < out.size && out[j + 1][1] - out[j + 1][0] + 2 <= MAX) { out[j + 1][0] = r[0]; out.removeAt(j); continue }
            }
            j++
        }
        return out
    }
}
