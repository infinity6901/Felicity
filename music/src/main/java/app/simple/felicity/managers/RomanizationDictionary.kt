package app.simple.felicity.managers

import java.io.File
import java.util.zip.GZIPInputStream

/**
 * A loaded romanization word list and the word segmenter built on it.
 *
 * Pack format (gzip, UTF-8, tab separated, one word per line, first line is [HEADER]):
 * `surface  reading  pronunciation  cost  class`
 *
 *  - `reading`: kana (Japanese) or already-romanized syllables (Chinese pinyin).
 *  - `pronunciation`: only for particles whose sound differs from their spelling (は→ワ).
 *  - `cost`: lower is more likely; the segmenter picks the cheapest way to cover a line.
 *  - `class`: N = ordinary word (written with a space before it), P = particle (own word),
 *    p = particle that joins the previous word (て, から), a = auxiliary/suffix that joins it.
 *
 * Only the entries that can actually occur in the lyrics at hand are kept in memory, so a
 * multi-megabyte pack costs a few hundred kilobytes while a song is processed.
 */
internal class RomanizationDictionary private constructor(
        private val entries: HashMap<String, Entry>,
        private val maxLength: Int
) {

    class Entry(val reading: String, val pron: String, val cost: Int, val cls: Char)

    /** A stretch of the input; [entry] is null when no dictionary word covers it. */
    class Token(val text: String, val entry: Entry?)

    /** The cheapest segmentation of [line] into dictionary words (single unknown characters otherwise). */
    fun segment(line: String): List<Token> {
        val n = line.length
        if (n == 0) return emptyList()
        val unreachable = Int.MAX_VALUE
        val best = IntArray(n + 1) { unreachable }
        val previous = IntArray(n + 1) { -1 }
        val used = arrayOfNulls<Entry>(n + 1)
        best[0] = 0

        for (i in 0 until n) {
            val here = best[i]
            if (here == unreachable) continue

            val skip = here + UNKNOWN_COST
            if (skip < best[i + 1]) {
                best[i + 1] = skip
                previous[i + 1] = i
                used[i + 1] = null
            }
            val limit = minOf(maxLength, n - i)
            for (length in 1..limit) {
                val entry = entries[line.substring(i, i + length)] ?: continue
                val cost = here + entry.cost
                if (cost < best[i + length]) {
                    best[i + length] = cost
                    previous[i + length] = i
                    used[i + length] = entry
                }
            }
        }

        val tokens = ArrayList<Token>()
        var end = n
        while (end > 0) {
            val start = previous[end]
            if (start < 0) break
            tokens.add(Token(line.substring(start, end), used[end]))
            end = start
        }
        tokens.reverse()
        return tokens
    }

    companion object {
        const val HEADER = "#felicity-romanization-dictionary"
        private const val UNKNOWN_COST = 11_000

        /** Reads [file] keeping only the words that appear somewhere in [text]. */
        fun load(file: File, text: String): RomanizationDictionary? = runCatching {
            val present = HashSet<Char>()
            text.forEach { present.add(it) }
            val map = HashMap<String, Entry>()
            var longest = 1

            GZIPInputStream(file.inputStream().buffered(64 * 1024)).bufferedReader().use { reader ->
                var line = reader.readLine()
                if (line == null || !line.startsWith(HEADER)) return null
                while (true) {
                    line = reader.readLine() ?: break
                    val t1 = line.indexOf('\t')
                    if (t1 <= 0 || line[0] !in present) continue
                    val surface = line.substring(0, t1)
                    if (!text.contains(surface)) continue

                    val t2 = line.indexOf('\t', t1 + 1)
                    val t3 = if (t2 < 0) -1 else line.indexOf('\t', t2 + 1)
                    val t4 = if (t3 < 0) -1 else line.indexOf('\t', t3 + 1)
                    if (t4 < 0) continue
                    val cost = line.substring(t3 + 1, t4).toIntOrNull() ?: continue
                    val cls = line.getOrNull(t4 + 1) ?: 'N'

                    val existing = map[surface]
                    if (existing == null || cost < existing.cost) {
                        map[surface] = Entry(line.substring(t1 + 1, t2), line.substring(t2 + 1, t3), cost, cls)
                        if (surface.length > longest) longest = surface.length
                    }
                }
            }
            RomanizationDictionary(map, longest)
        }.getOrNull()
    }
}
