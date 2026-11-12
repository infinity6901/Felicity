package app.simple.felicity.server

import org.junit.Assert.assertEquals
import org.junit.Test

class MusicHttpServerRangeTest {
    @Test fun parsesStandardOpenEndedAndSuffixRanges() {
        assertEquals(
            MusicHttpServer.ByteRangeResult.Valid(0L, 99L),
            MusicHttpServer.parseByteRange("bytes=0-99", 1000L)
        )
        assertEquals(
            MusicHttpServer.ByteRangeResult.Valid(500L, 999L),
            MusicHttpServer.parseByteRange("bytes=500-", 1000L)
        )
        assertEquals(
            MusicHttpServer.ByteRangeResult.Valid(800L, 999L),
            MusicHttpServer.parseByteRange("bytes=-200", 1000L)
        )
        assertEquals(
            MusicHttpServer.ByteRangeResult.Valid(0L, 999L),
            MusicHttpServer.parseByteRange("bytes=-2000", 1000L)
        )
    }

    @Test fun rejectsMalformedReversedMultiAndOutOfBoundsRanges() {
        assertEquals(
            MusicHttpServer.ByteRangeResult.Unsatisfiable,
            MusicHttpServer.parseByteRange("bytes=1000-", 1000L)
        )
        assertEquals(
            MusicHttpServer.ByteRangeResult.Unsatisfiable,
            MusicHttpServer.parseByteRange("bytes=500-200", 1000L)
        )
        assertEquals(
            MusicHttpServer.ByteRangeResult.Unsatisfiable,
            MusicHttpServer.parseByteRange("bytes=0-10,20-30", 1000L)
        )
        assertEquals(
            MusicHttpServer.ByteRangeResult.Unsatisfiable,
            MusicHttpServer.parseByteRange("items=0-10", 1000L)
        )
        assertEquals(
            MusicHttpServer.ByteRangeResult.Unsatisfiable,
            MusicHttpServer.parseByteRange("bytes=-0", 1000L)
        )
    }
}
