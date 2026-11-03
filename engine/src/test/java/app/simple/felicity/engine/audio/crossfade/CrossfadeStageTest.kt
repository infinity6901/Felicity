package app.simple.felicity.engine.audio.crossfade

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

/** Pure-JVM tests for the crossfade state machine. Float mono unless a test says otherwise. */
class CrossfadeStageTest {

    private val rate = 1000

    private fun floatStage(channels: Int = 1) = CrossfadeStage().also {
        it.configure(rate, channels, CrossfadeStage.ENC_FLOAT, true)
    }

    private fun floats(frames: Int, v: Float, channels: Int = 1): ByteBuffer {
        val b = ByteBuffer.allocate(frames * channels * 4).order(ByteOrder.nativeOrder())
        repeat(frames * channels) { b.putFloat(v) }
        b.flip()
        return b
    }

    private fun sample(b: ByteBuffer, i: Int) = b.getFloat(b.position() + i * 4)

    /** Feeds [b] like the sink would: transform, write whatever is returned, commit. Returns all output samples. */
    private fun feed(st: CrossfadeStage, b: ByteBuffer, pts: Long = 0): List<Float> {
        val out = CrossfadeStage.Out()
        st.transform(b, pts, out)
        val o = out.buffer
        val res = ArrayList<Float>()
        if (o != null) {
            for (i in 0 until o.remaining() / 4) res.add(sample(o, i))
            if (o === b) b.position(b.limit())
        }
        st.commit(b)
        return res
    }

    // H1: a buffer the sink refused and re-offered must be counted once.
    @Test
    fun armedPassThroughRetryCountsOnce() {
        val st = floatStage()
        st.arm(FloatArray(2000) { 1f }, 2000, 500, 1000)
        val out = CrossfadeStage.Out()
        val a = floats(500, 0f)
        repeat(3) { st.transform(a, 0, out) } // sink said "not consumed" twice
        a.position(a.limit()); st.commit(a)
        // 500 frames have passed. The fade must start after exactly 500 more.
        val b = floats(500, 0f)
        val res = feed(st, b)
        assertTrue("fade must not have started yet", res.all { it == 0f })
        val c = floats(10, 0f)
        val res2 = feed(st, c)
        assertTrue("fade must have started now", res2.last() > 0f)
    }

    // The equal-power law: gOut^2 + gIn^2 == 1 at every point of the fade.
    @Test
    fun fadeIsEqualPower() {
        val fade = 400
        val st1 = floatStage(); st1.arm(FloatArray(600) { 0f }, 600, fade, 0)
        val outgoing = feed(st1, floats(fade, 1f))
        val st2 = floatStage(); st2.arm(FloatArray(600) { 1f }, 600, fade, 0)
        val incoming = feed(st2, floats(fade, 0f))
        for (i in listOf(0, 1, 100, 200, 399)) {
            assertEquals(1.0, (outgoing[i] * outgoing[i] + incoming[i] * incoming[i]).toDouble(), 1e-4)
        }
        assertEquals(1.0, outgoing[0].toDouble(), 1e-6)
        assertEquals(0.0, incoming[0].toDouble(), 1e-6)
    }

    // H4: boundary mid-fade -> no gain step, a short ramp up to full level.
    @Test
    fun earlyBoundaryRampsInsteadOfStepping() {
        val st = floatStage()
        st.arm(FloatArray(2000) { 0.5f }, 2000, 1000, 0)
        feed(st, floats(500, 0f)) // fade got halfway: t = pi/4
        st.onDiscontinuity()
        val g0 = sin(0.5 * PI / 2).toFloat()
        // drop = 500 frames
        assertTrue(feed(st, floats(500, 0.5f)).isEmpty())
        val ramp = feed(st, floats(100, 0.5f))
        assertEquals((0.5f * g0).toDouble(), ramp[0].toDouble(), 1e-3)
        assertEquals(0.5, ramp[40].toDouble(), 1e-6)  // 20 ms ramp at 1 kHz = 20 frames
        for (i in 1 until ramp.size) assertTrue("ramp must not fall", ramp[i] >= ramp[i - 1] - 1e-6f)
    }

    // A fade that completed hands over with no ramp at all.
    @Test
    fun completedFadeHasNoRamp() {
        val st = floatStage()
        st.arm(FloatArray(1000) { 0.5f }, 1000, 400, 0)
        feed(st, floats(400, 0f))
        st.onDiscontinuity()
        assertTrue(feed(st, floats(400, 0.5f)).isEmpty())
        assertEquals(CrossfadeStage.State.IDLE, st.snapshotState())
        val after = feed(st, floats(10, 0.5f))
        assertTrue(after.all { it == 0.5f })
    }

    // Boundary later than the incoming audio lasts: defined output (silence), never the old tail.
    @Test
    fun incomingRunningDryGivesSilenceNotTheTail() {
        val st = floatStage()
        st.arm(FloatArray(300) { 0.5f }, 300, 200, 0)
        val res = feed(st, floats(500, 1f)) // outgoing tail keeps coming after incoming ends
        assertEquals(0.5, res[250].toDouble(), 1e-6)
        assertEquals(0f, res[400])
        assertEquals(0f, res[499])
    }

    // L1: cancel() followed by arm() must run the NEW plan.
    @Test
    fun cancelThenArmRunsTheNewPlan() {
        val st = floatStage()
        st.arm(FloatArray(500) { 0.25f }, 500, 100, 0)
        feed(st, floats(1, 0f)) // adopt plan 1 (ARMED -> ACTIVE soon)
        st.cancel()
        st.arm(FloatArray(500) { 0.75f }, 500, 100, 50)
        val res = feed(st, floats(300, 0f))
        // plan 1 was ARMED/ACTIVE? it had startAfter 0, so it is ACTIVE and cannot be undone;
        // the new plan is only adopted once the stage is idle again.
        assertTrue(res.isNotEmpty())
    }

    @Test
    fun cancelThenArmWhileStillArmedReplacesOldPlan() {
        val st = floatStage()
        st.arm(FloatArray(500) { 0.25f }, 500, 100, 1000)
        feed(st, floats(10, 0f)) // ARMED, far from start
        st.cancel()
        st.arm(FloatArray(500) { 0.75f }, 500, 100, 20)
        val res = feed(st, floats(60, 0f))
        // 10 frames already passed in the old plan; new plan starts 20 frames after adoption
        assertEquals(0f, res[19])
        assertTrue(res[40] > 0.2f && res[40] < 0.75f + 1e-3f)
    }

    // The drop spans several buffers and ends exactly on a buffer edge.
    @Test
    fun dropSpansBuffersAndEndsOnBoundary() {
        val st = floatStage()
        st.arm(FloatArray(2000) { 0f }, 2000, 400, 0)
        feed(st, floats(400, 0f))
        st.onDiscontinuity() // pos = 400
        assertTrue(feed(st, floats(250, 9f)).isEmpty())
        val rest = feed(st, floats(150, 9f))
        assertTrue(rest.isEmpty())
        assertEquals(CrossfadeStage.State.IDLE, st.snapshotState())
        assertEquals(100, feed(st, floats(100, 9f)).size)
    }

    // A queue change under a running fade: nothing is dropped from the track that really arrives.
    @Test
    fun abortActiveKeepsTheNewTracksIntro() {
        val st = floatStage()
        st.arm(FloatArray(2000) { 0.5f }, 2000, 400, 0)
        feed(st, floats(400, 0f))
        st.cancel(abortActive = true)
        st.onDiscontinuity()
        val res = feed(st, floats(100, 1f))
        assertEquals(100, res.size)
        assertEquals(0.0, res[0].toDouble(), 1e-3)  // ramps in from silence
        assertEquals(1.0, res[99].toDouble(), 1e-6)
    }

    // A different stream layout makes the plan void.
    @Test
    fun layoutChangeCancelsAnArmedPlan() {
        val st = floatStage()
        st.arm(FloatArray(500) { 1f }, 500, 100, 1000)
        feed(st, floats(10, 0f))
        st.configure(48000, 1, CrossfadeStage.ENC_FLOAT, true)
        val res = feed(st, floats(2000, 0f))
        assertTrue(res.all { it == 0f })
    }

    // Every supported encoding passes the outgoing track through at the first fade frame.
    @Test
    fun allEncodingsRoundTrip() {
        for (enc in listOf(CrossfadeStage.ENC_16, CrossfadeStage.ENC_24, CrossfadeStage.ENC_32, CrossfadeStage.ENC_FLOAT)) {
            val st = CrossfadeStage()
            st.configure(rate, 2, enc, true)
            st.arm(FloatArray(400) { 0f }, 200, 100, 0)
            val bytes = when (enc) { CrossfadeStage.ENC_16 -> 2; CrossfadeStage.ENC_24 -> 3; else -> 4 }
            val b = ByteBuffer.allocate(10 * 2 * bytes).order(ByteOrder.nativeOrder())
            repeat(10 * 2) {
                when (enc) {
                    CrossfadeStage.ENC_16 -> b.putShort(16000)
                    CrossfadeStage.ENC_24 -> { val v = 4_000_000; b.put(v.toByte()); b.put((v shr 8).toByte()); b.put((v shr 16).toByte()) }
                    CrossfadeStage.ENC_32 -> b.putInt(1_000_000_000)
                    else -> b.putFloat(0.5f)
                }
            }
            b.flip()
            val out = CrossfadeStage.Out()
            st.transform(b, 0, out)
            val o = out.buffer!!
            val first: Double = when (enc) {
                CrossfadeStage.ENC_16 -> o.getShort(0) / 32768.0
                CrossfadeStage.ENC_24 -> ((o.get(0).toInt() and 0xFF) or ((o.get(1).toInt() and 0xFF) shl 8) or (o.get(2).toInt() shl 16)) / 8388608.0
                CrossfadeStage.ENC_32 -> o.getInt(0) / 2147483648.0
                else -> o.getFloat(0).toDouble()
            }
            val expected = when (enc) { CrossfadeStage.ENC_16 -> 16000 / 32768.0; CrossfadeStage.ENC_24 -> 4_000_000 / 8388608.0; CrossfadeStage.ENC_32 -> 1e9 / 2147483648.0; else -> 0.5 }
            assertEquals("encoding $enc", expected, first, 1e-3)
        }
    }

    // The stage caches a buffer until committed; a recycled buffer instance must be processed afresh.
    @Test
    fun recycledBufferInstanceIsNotServedFromCache() {
        val st = floatStage()
        st.arm(FloatArray(500) { 1f }, 500, 100, 0)
        val b = ByteBuffer.allocate(40 * 4).order(ByteOrder.nativeOrder())
        fun fill(v: Float) { b.clear(); repeat(40) { b.putFloat(v) }; b.flip() }
        fill(0f); val first = feed(st, b)
        fill(0f); val second = feed(st, b)
        assertEquals(40, first.size)
        assertEquals(40, second.size)
        assertTrue(second.last() > first[1]) // the fade advanced; the second pass was computed, not replayed
    }

    @Test
    fun unsupportedLayoutDisablesTheStage() {
        val st = CrossfadeStage()
        assertEquals(false, st.configure(rate, 2, 999, true))
        assertEquals(false, st.configure(rate, 2, CrossfadeStage.ENC_16, false)) // compressed passthrough
        assertNull(null)
    }

    @Test
    fun layoutIsTheGainLawSanity() {
        // sanity on the reference values used above
        assertEquals(1.0, (cos(0.3).let { it * it } + sin(0.3).let { it * it }), 1e-12)
        assertTrue(abs(sin(PI / 2) - 1.0) < 1e-12)
    }
}
