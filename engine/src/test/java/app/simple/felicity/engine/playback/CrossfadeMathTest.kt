package app.simple.felicity.engine.playback

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CrossfadeMathTest {
    @Test fun endpointsAreSilentOnOppositeSides() {
        val start = CrossfadeMath.equalPowerGains(0f)
        val end = CrossfadeMath.equalPowerGains(1f)
        assertEquals(1f, start.outgoing, 0.0001f)
        assertEquals(0f, start.incoming, 0.0001f)
        assertEquals(0f, end.outgoing, 0.0001f)
        assertEquals(1f, end.incoming, 0.0001f)
    }

    @Test fun incomingRisesGentlyAndOutgoingFallsMonotonically() {
        var prev = CrossfadeMath.equalPowerGains(0f)
        for (step in 1..100) {
            val gains = CrossfadeMath.equalPowerGains(step / 100f)
            assertTrue(gains.incoming >= prev.incoming)
            assertTrue(gains.outgoing <= prev.outgoing)
            prev = gains
        }
        // The new song is still quiet a quarter of the way in, the old one has already dipped.
        val quarter = CrossfadeMath.equalPowerGains(0.25f)
        assertTrue(quarter.incoming < 0.2f)
        assertTrue(quarter.outgoing < 0.95f)
        // Summed power never collapses mid-fade.
        for (step in 0..100) {
            val g = CrossfadeMath.equalPowerGains(step / 100f)
            assertTrue(g.outgoing * g.outgoing + g.incoming * g.incoming > 0.6f)
        }
    }

    @Test fun delayScalesWithPlaybackSpeedAndRejectsInvalidSpeed() {
        assertEquals(500L, CrossfadeMath.wallClockStepMs(1000L, 2f))
        assertEquals(1000L, CrossfadeMath.wallClockStepMs(1000L, 1f))
        assertEquals(1000L, CrossfadeMath.wallClockStepMs(1000L, Float.NaN))
        assertTrue(CrossfadeMath.wallClockStepMs(1L, 1f) >= 10L)
    }

    @Test fun progressFollowsElapsedMediaTimeAndClamps() {
        assertEquals(0f, CrossfadeMath.fadeProgress(-50L, 4000L), 0.0001f)
        assertEquals(0.5f, CrossfadeMath.fadeProgress(2000L, 4000L), 0.0001f)
        assertEquals(1f, CrossfadeMath.fadeProgress(9000L, 4000L), 0.0001f)
        assertEquals(1f, CrossfadeMath.fadeProgress(10L, 0L), 0.0001f)
    }

    @Test fun adaptiveFadeNeverExceedsTheChosenMaximum() {
        assertEquals(6000L, CrossfadeMath.adaptiveFadeMs(6000L, 240_000L, 200_000L, sameAlbum = false))
        assertEquals(3000L, CrossfadeMath.adaptiveFadeMs(6000L, 80_000L, 200_000L, sameAlbum = false))
        assertEquals(1500L, CrossfadeMath.adaptiveFadeMs(6000L, 20_000L, 200_000L, sameAlbum = false))
        assertTrue(CrossfadeMath.adaptiveFadeMs(1000L, 300_000L, 300_000L, sameAlbum = true) >= 1000L)
        assertTrue(CrossfadeMath.adaptiveFadeMs(12000L, 300_000L, 300_000L, sameAlbum = true) <= 12000L)
    }

    @Test fun sameAlbumNeighboursGetAShorterBlend() {
        val other = CrossfadeMath.adaptiveFadeMs(8000L, 240_000L, 240_000L, sameAlbum = false)
        val same = CrossfadeMath.adaptiveFadeMs(8000L, 240_000L, 240_000L, sameAlbum = true)
        assertTrue(same < other)
    }
}
