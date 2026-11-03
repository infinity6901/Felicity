package app.simple.felicity.levyra.lyrics

import app.simple.felicity.decorations.lrc.model.LrcData

/** Bridges Felicity's existing LRC representation to Levyra's lyric analysis primitives. */
object FelicityLyricsAdapter {
    fun toLines(data: LrcData): List<LyricLine> {
        val entries = data.entries
        return entries.mapIndexed { index, entry ->
            val next = entries.getOrNull(index + 1)?.timeInMillis
            val end = maxOf(entry.timeInMillis + 1L, next ?: (entry.timeInMillis + 4_000L))
            LyricLine(
                startMs = entry.timeInMillis.coerceAtLeast(0L),
                endMs = end.coerceAtLeast(entry.timeInMillis + 1L),
                text = entry.text,
                romanized = entry.romanization.orEmpty(),
                isInstrumental = entry.text.isBlank() || entry.text.trim().matches(Regex("(?i)\\[(instrumental|music|intro|outro)\\]"))
            )
        }
    }

    fun sections(data: LrcData): LyricsSectionDetection = LyricsSectionDetector.detect(toLines(data))
}
