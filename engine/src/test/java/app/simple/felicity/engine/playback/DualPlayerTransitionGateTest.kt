package app.simple.felicity.engine.playback

import org.junit.Assert.*
import org.junit.Test

class DualPlayerTransitionGateTest {
    @Test fun validTransitionMustFollowOrderedStates() {
        val gate = DualPlayerTransitionGate()
        val token = gate.begin()
        assertFalse(gate.fading(token))
        assertTrue(gate.ready(token))
        assertTrue(gate.fading(token))
        assertTrue(gate.handingOff(token))
        assertTrue(gate.complete(token))
        assertEquals(DualPlayerTransitionGate.State.IDLE, gate.state)
    }

    @Test fun newerTransitionInvalidatesOlderCoroutineToken() {
        val gate = DualPlayerTransitionGate()
        val old = gate.begin()
        val current = gate.begin()
        assertFalse(gate.ready(old))
        assertTrue(gate.ready(current))
        assertTrue(gate.cancel(current))
    }

    @Test fun cancellationAndInvalidationReturnToIdle() {
        val gate = DualPlayerTransitionGate()
        val token = gate.begin()
        assertTrue(gate.cancel(token))
        assertFalse(gate.cancel(token))
        gate.begin()
        gate.invalidate()
        assertEquals(DualPlayerTransitionGate.State.IDLE, gate.state)
    }
}
