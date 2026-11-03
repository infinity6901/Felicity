package app.simple.felicity.managers

/**
 * Optional on-device romanization for lyric scripts that use a non-Latin writing system.
 * Android's ICU transliterator is used reflectively so this remains safe on devices where
 * the ICU API is unavailable. Original lyric text is never modified.
 */
internal object LyricsRomanizer {
    private val supportedScripts = Regex(
        "[\\u0370-\\u03ff\\u0400-\\u052f\\u0590-\\u05ff\\u0600-\\u06ff" +
            "\\u0750-\\u077f\\u08a0-\\u08ff\\u0900-\\u097f\\u0980-\\u09ff" +
            "\\u0a00-\\u0a7f\\u10a0-\\u10ff\\u1c90-\\u1cbf\\u3040-\\u30ff" +
            "\\u3400-\\u9fff\\uac00-\\ud7af]"
    )

    fun romanize(text: String): String? {
        val source = text.trim()
        if (source.isEmpty() || !supportedScripts.containsMatchIn(source)) return null
        return runCatching {
            val type = Class.forName("android.icu.text.Transliterator")
            val transliterator = type.getMethod("getInstance", String::class.java)
                .invoke(null, "Any-Latin; NFD; [:Nonspacing Mark:] Remove; NFC")
            val converted = type.getMethod("transliterate", String::class.java)
                .invoke(transliterator, source) as? String
            converted?.trim()?.takeIf { it.isNotEmpty() && !it.equals(source, ignoreCase = true) }
        }.getOrNull()
    }
}
