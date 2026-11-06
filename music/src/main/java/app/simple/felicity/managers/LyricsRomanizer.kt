package app.simple.felicity.managers

import android.icu.text.Transliterator
import java.text.Normalizer
import java.util.concurrent.ConcurrentHashMap

/**
 * Optional on-device romanization for lyric scripts that use a non-Latin writing system.
 *
 * A single generic "Any-Latin" pass gets many things wrong: it reads Japanese kanji with their
 * Chinese (pinyin) readings, and it keeps the inherent vowel of Indic scripts, which gives
 * "pyara" for प्यार and "aparadhi" for অপরাধী. So each word is handled by script:
 *
 *  - Kana is romanized as Japanese. Kanji cannot be read without a dictionary, so in Japanese
 *    text it is left untouched rather than guessed (a wrong reading is worse than none).
 *  - Han text with no kana anywhere in the song is treated as Chinese and gets pinyin.
 *  - Devanagari (Hindi) and Bengali get their final inherent vowel dropped, Bengali inherent
 *    vowels pronounced as "o", and the usual Latin spellings (sh, ch, chh, ng) instead of
 *    scholarly diacritics.
 *  - Other scripts (Cyrillic, Greek, Hangul, Arabic, Hebrew, Georgian, ...) use ICU's standard
 *    transliteration with diacritics removed.
 *
 * Original lyric text is never modified.
 */
internal object LyricsRomanizer {

    private val transliterators = ConcurrentHashMap<String, Transliterator>()

    private fun transliterator(id: String): Transliterator? =
        transliterators[id] ?: runCatching { Transliterator.getInstance(id) }.getOrNull()
            ?.also { transliterators[id] = it }

    private fun isKana(cp: Int) = cp in 0x3040..0x30FF || cp in 0x31F0..0x31FF || cp in 0xFF66..0xFF9F
    private fun isHan(cp: Int) = cp in 0x3400..0x4DBF || cp in 0x4E00..0x9FFF || cp in 0xF900..0xFAFF
    private fun isDevanagari(cp: Int) = cp in 0x0900..0x097F
    private fun isBengali(cp: Int) = cp in 0x0980..0x09FF

    private fun needsRomanization(cp: Int): Boolean =
        cp in 0x0370..0x052F || cp in 0x0590..0x08FF || cp in 0x0900..0x0DFF ||
            cp in 0x0E00..0x0EFF || cp in 0x10A0..0x10FF || cp in 0x1C90..0x1CBF ||
            isKana(cp) || isHan(cp) || cp in 0xAC00..0xD7AF || cp in 0x1100..0x11FF

    /** True when any of [texts] contains kana, i.e. the song is Japanese and its kanji are not Chinese. */
    fun isJapanese(texts: Sequence<String>): Boolean =
        texts.any { line -> line.codePoints().anyMatch { isKana(it) } }

    /**
     * Romanizes [text]. [japanese] says the song contains kana, which changes how kanji is treated.
     * Returns null when there is nothing to romanize or the result would be identical.
     */
    fun romanize(text: String, japanese: Boolean = false): String? {
        val source = text.trim()
        if (source.isEmpty() || source.codePoints().noneMatch { needsRomanization(it) }) return null

        val result = source.split(' ', '　').joinToString(" ") { word -> romanizeWord(word, japanese) }
            .replace(Regex("\\s+"), " ")
            .trim()
        return result.takeIf { it.isNotEmpty() && !it.equals(source, ignoreCase = true) }
    }

    private fun romanizeWord(word: String, japanese: Boolean): String {
        if (word.isEmpty()) return word
        val first = word.codePoints().filter { needsRomanization(it) }.findFirst()
        if (!first.isPresent) return word
        val cp = first.asInt
        return runCatching {
            when {
                isDevanagari(cp) -> indic(word, "Devanagari-Latin", bengali = false)
                isBengali(cp) -> indic(word, "Bengali-Latin", bengali = true)
                isKana(cp) || (isHan(cp) && japanese) -> japanese(word)
                else -> generic(word)
            }
        }.getOrDefault(word)
    }

    /** Standard ICU transliteration with diacritics stripped. */
    private fun generic(word: String): String {
        val t = transliterator("Any-Latin") ?: return word
        return stripMarks(t.transliterate(word))
    }

    /**
     * Japanese: kana runs become romaji, kanji runs are kept as they are (no dictionary to read them),
     * and the particle spacing stays readable by separating the runs.
     */
    private fun japanese(word: String): String {
        val t = transliterator("Any-Latin") ?: return word
        val out = StringBuilder()
        var run = StringBuilder()
        var runKana = true

        fun flush() {
            if (run.isEmpty()) return
            if (out.isNotEmpty()) out.append(' ')
            out.append(if (runKana) stripMarks(t.transliterate(run.toString())) else run.toString())
            run = StringBuilder()
        }

        word.codePoints().forEach { c ->
            val kana = isKana(c)
            val han = isHan(c)
            if (!kana && !han) {
                // Punctuation and Latin stay attached to the run they follow.
                run.appendCodePoint(c)
            } else {
                if (run.isNotEmpty() && kana != runKana) flush()
                runKana = kana
                run.appendCodePoint(c)
            }
        }
        flush()
        return out.toString()
    }

    private val consonantEnd = Regex("(?<=[bcdfghjklmnpqrstvwxyzṭḍṇśṣṅñṛḷẏṯ])a(?=[^\\p{L}]*$)")

    /**
     * Hindi / Bengali. ICU writes every inherent vowel as "a"; real speech drops the one at the end
     * of a word, and Bengali pronounces the others as "o".
     */
    private fun indic(word: String, id: String, bengali: Boolean): String {
        val t = transliterator(id) ?: return generic(word)
        var s = Normalizer.normalize(t.transliterate(word).lowercase(), Normalizer.Form.NFC)

        s = s.replace(consonantEnd, "")
        if (bengali) {
            // a -> o (long ā is a single precomposed letter, so it is untouched here).
            s = s.replace('a', 'o')
            s = s.replace("ā", "a")
        } else {
            s = s.replace("ā", "aa")
        }
        s = s.replace("ī", "i").replace("ū", "u").replace("ē", "e").replace("ō", "o")
            .replace("ṝ", "ri").replace("ṛ", "r").replace("ṅ", "ng").replace("ṃ", if (bengali) "ng" else "n")
            .replace("ṁ", if (bengali) "ng" else "n").replace("ḥ", "h")
            .replace("ś", "sh").replace("ṣ", "sh").replace("ṭ", "t").replace("ḍ", "d").replace("ṇ", "n")
            .replace("ñ", "n").replace("ẏ", "y").replace("ṯ", "t").replace("ḷ", "l")

        // ICU writes च as "c" and छ as "ch"; everyday spelling is "ch" and "chh".
        s = s.replace("ch", "\u0001").replace("c", "ch").replace("\u0001", "chh")
        return stripMarks(s)
    }

    private fun stripMarks(value: String): String {
        val decomposed = Normalizer.normalize(value, Normalizer.Form.NFD)
        val sb = StringBuilder(decomposed.length)
        for (ch in decomposed) {
            if (Character.getType(ch) != Character.NON_SPACING_MARK.toInt()) sb.append(ch)
        }
        return Normalizer.normalize(sb, Normalizer.Form.NFC).trim()
    }
}
