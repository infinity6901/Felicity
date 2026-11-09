package app.simple.felicity.managers

import java.text.Normalizer

/**
 * Rule-based Urdu (Arabic script) to Latin romanization.
 *
 * Urdu leaves short vowels unwritten, so a reading is reconstructed: consonant letters, long-vowel
 * letters (ا و ی ے), aspirates (بھ → bh), shadda doubling, explicit diacritics when present, and an
 * inherent "a" inserted into consonant runs so they can be pronounced. The same rules, plus a
 * hand-checked list of frequent words, generate the downloadable `ur` pack
 * (dictionaries/romanization/tools/build_urdu.py); this class is the fallback for words the pack lacks.
 */
internal object UrduLatin {

    private val CONS = mapOf(
            'ب' to "b", 'پ' to "p", 'ت' to "t", 'ٹ' to "t", 'ث' to "s", 'ج' to "j", 'چ' to "ch", 'ح' to "h",
            'خ' to "kh", 'د' to "d", 'ڈ' to "d", 'ذ' to "z", 'ر' to "r", 'ڑ' to "r", 'ز' to "z", 'ژ' to "zh",
            'س' to "s", 'ش' to "sh", 'ص' to "s", 'ض' to "z", 'ط' to "t", 'ظ' to "z", 'غ' to "gh", 'ف' to "f",
            'ق' to "q", 'ک' to "k", 'ك' to "k", 'گ' to "g", 'ل' to "l", 'م' to "m", 'ن' to "n", 'ں' to "n",
            'ہ' to "h", 'ه' to "h", 'ۃ' to "t"
    )

    private const val ZABAR = 'َ'
    private const val ZER = 'ِ'
    private const val PESH = 'ُ'
    private const val SHADDA = 'ّ'
    private val DIACRITICS = setOf('ً', 'ٌ', 'ٍ', ZABAR, ZER, PESH, SHADDA, 'ْ', 'ٰ', 'ٖ', 'ٗ', 'ٔ')

    private val COMMON = mapOf(
            "کے" to "ke", "میں" to "mein", "کی" to "ki", "ہے" to "hai", "اور" to "aur", "سے" to "se", "کا" to "ka",
            "کو" to "ko", "اس" to "is", "پر" to "par", "ہیں" to "hain", "نے" to "ne", "بھی" to "bhi", "ایک" to "ek",
            "یہ" to "yeh", "نہیں" to "nahin", "کہ" to "ke", "تو" to "to", "کیا" to "kya", "وہ" to "woh", "ہو" to "ho",
            "تھا" to "tha", "تھی" to "thi", "تھے" to "the", "جو" to "jo", "گیا" to "gaya", "گئے" to "gaye",
            "میرا" to "mera", "میری" to "meri", "تیرا" to "tera", "تم" to "tum", "ہم" to "hum", "دل" to "dil",
            "پیار" to "pyaar", "عشق" to "ishq", "محبت" to "mohabbat", "زندگی" to "zindagi", "بہت" to "bohot"
    )

    private fun inSet(ch: Char?, chars: String) = ch != null && chars.indexOf(ch) >= 0

    fun isUrduLetter(cp: Int) = cp in 0x0600..0x06FF || cp in 0x0750..0x077F || cp in 0xFB50..0xFDFF || cp in 0xFE70..0xFEFF

    /** Letters only Urdu uses (not Arabic or Persian): retroflexes, nun ghunna, choti ye, do-chashmi he. */
    fun isUrduSpecific(cp: Int) = cp == 0x0679 || cp == 0x0688 || cp == 0x0691 || cp == 0x06BA || cp == 0x06D2 ||
            cp == 0x06BE || cp == 0x06C1

    fun of(word: String): String {
        COMMON[word]?.let { return it }
        val w = Normalizer.normalize(word, Normalizer.Form.NFC)
        val letters = w.filter { it != '‌' && it != '‍' && it != 'ـ' }
        val n = letters.length
        // kind: 'C' consonant, 'V' vowel, 'X' other
        val kinds = ArrayList<Char>()
        val texts = ArrayList<String>()
        fun add(kind: Char, text: String) { kinds.add(kind); texts.add(text) }
        fun first() = kinds.none { it != 'X' }

        for (i in 0 until n) {
            val c = letters[i]
            val nxt = letters.getOrNull(i + 1)
            val last = (i + 1 until n).all { letters[it] in DIACRITICS }
            when {
                c in DIACRITICS -> when {
                    c == SHADDA && kinds.lastOrNull() == 'C' -> add('C', texts.last())
                    c == ZABAR -> add('V', "a")
                    c == ZER -> add('V', "i")
                    c == PESH -> add('V', "u")
                }
                c == 'آ' -> add('V', "aa")
                c == 'ا' -> add('V', if (first() || last) "a" else "aa")
                c == 'ع' -> if (first()) add('V', "a")
                c == 'ء' -> Unit
                c == 'ی' || c == 'ي' || c == 'ې' -> when {
                    inSet(nxt, "اوے") || (first() && nxt != null) -> add('C', "y")
                    last -> add('V', "i")
                    else -> add('V', "ee")
                }
                c == 'ے' -> add('V', "e")
                c == 'ؤ' -> add('V', "o")
                c == 'ئ' -> if (nxt == 'ی') add('V', "") else add('C', "y")
                c == 'و' -> if (first() && nxt != null) add('C', "w") else add('V', "o")
                c == 'ہ' && last && kinds.lastOrNull() == 'C' && n > 2 -> add('V', "a")
                c == 'ھ' && kinds.lastOrNull() == 'C' -> texts[texts.lastIndex] = texts.last() + "h"
                CONS.containsKey(c) -> add('C', CONS.getValue(c))
                else -> add('X', c.toString())
            }
        }

        val sb = StringBuilder()
        var i = 0
        val m = kinds.size
        while (i < m) {
            if (kinds[i] != 'C') { sb.append(texts[i]); i++; continue }
            var j = i
            while (j < m && kinds[j] == 'C') j++
            val run = texts.subList(i, j).toMutableList()
            if (run.size >= 2 && (run.last() == "y" || run.last() == "w") && j < m) {
                val glide = run.removeAt(run.lastIndex)
                run[run.lastIndex] = run.last() + glide
            }
            val atStart = (0 until i).none { kinds[it] != 'X' }
            val atEnd = j == m
            when {
                run.size >= 2 && atStart && !atEnd -> sb.append(run[0]).append('a').append(run.drop(1).joinToString(""))
                run.size >= 3 && !atStart && !atEnd -> sb.append(run[0]).append('i').append(run.drop(1).joinToString(""))
                run.size >= 3 && atEnd -> sb.append(run[0]).append('a').append(run.drop(1).joinToString(""))
                else -> sb.append(run.joinToString(""))
            }
            i = j
        }
        return sb.toString()
    }
}
