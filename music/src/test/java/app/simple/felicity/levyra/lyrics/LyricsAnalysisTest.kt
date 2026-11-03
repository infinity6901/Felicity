package app.simple.felicity.levyra.lyrics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

class LyricsAnalysisTest {
    @Test fun explicitSectionMarkersAreRemovedAndMapped() {
        val result = LyricsSectionDetector.detect(listOf(
            LyricLine(0, 1000, "[Verse 1]"),
            LyricLine(1000, 2000, "First line"),
            LyricLine(2000, 3000, "[Chorus]"),
            LyricLine(3000, 4000, "Sing it")
        ))
        assertEquals(listOf("First line", "Sing it"), result.lines.map { it.text })
        assertEquals(listOf(LyricSectionType.VERSE, LyricSectionType.CHORUS), result.sections.map { it.type })
    }

    @Test fun phraseSearchFindsLyricLine() {
        val match = LyricsPhraseMatcher.match("we will find our way home", listOf("A quiet night", "We will find our way home", "Before the morning"))
        assertNotNull(match)
        assertEquals(1, match!!.lineIndex)
    }
}
