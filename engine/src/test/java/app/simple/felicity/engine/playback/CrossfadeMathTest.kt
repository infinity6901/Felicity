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

    @Test fun equalPowerKeepsSquaredGainConstant() {
        for (step in 0..100) {
            val gains = CrossfadeMath.equalPowerGains(step / 100f)
            assertEquals(1f, gains.outgoing * gains.outgoing + gains.incoming * gains.incoming, 0.0002f)
        }
    }

    @Test fun delayScalesWithPlaybackSpeedAndRejectsInvalidSpeed() {
        assertEquals(500L, CrossfadeMath.wallClockStepMs(1000L, 2f))
        assertEquals(1000L, CrossfadeMath.wallClockStepMs(1000L, 1f))
        assertEquals(1000L, CrossfadeMath.wallClockStepMs(1000L, Float.NaN))
        assertTrue(CrossfadeMath.wallClockStepMs(1L, 1f) >= 10L)
    }
}
