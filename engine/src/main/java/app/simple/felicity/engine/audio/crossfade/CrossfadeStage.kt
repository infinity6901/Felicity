package app.simple.felicity.engine.audio.crossfade

import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * A mixing stage that sits at the very front of the audio path (inside
 * [app.simple.felicity.engine.audio.FelicityAudioSink.handleBuffer], before the sink's DSP
 * chain), so the equaliser, effects, compressor and visualizer all see ONE already-crossfaded
 * stream. That is what lets a crossfade work without the DSP having to grow multiple inputs.
 *
 * ## How a crossfade plays out
 *
 * The service decodes the first few seconds of the next track (the "incoming" audio) into a
 * float array and hands it over with [arm], together with how many input frames of the current
 * track are left before the mix should begin. Then, per input buffer handed to [transform]:
 *
 * 1. **ARMED** – passes audio through unchanged while counting frames down.
 * 2. **ACTIVE** – outputs `outgoing * cos(t) + incoming * sin(t)` (equal-power), `t` going 0→π/2
 *    over `fadeFrames`. Past the fade the outgoing tail is silenced and the incoming audio plays
 *    solo, so a track that runs a little longer than predicted never produces a gap or a loud tail.
 *    Every incoming frame used is counted in [pos]. If the incoming audio runs out before the
 *    boundary (a track more than the slack longer than predicted) the stage outputs silence for
 *    the rest of the tail; it never lets the faded-out tail come back at full level.
 * 3. **DROPPING** – ExoPlayer signals the real track boundary through
 *    [onDiscontinuity]. The next track's first `pos` frames are exactly what the listener already
 *    heard inside the fade, so they are swallowed. The sink re-anchors its clock to the first
 *    frame it is shown afterwards, so the player's position lands on `pos` frames into the next
 *    track and the seekbar and lyrics stay in sync.
 *
 * 4. **RAMPING** – if the boundary arrives before the fade reached full incoming level (a track
 *    shorter than predicted), the next track's first few milliseconds ramp from the level the
 *    fade had reached up to 1.0, so there is no audible gain step.
 *
 * This works because the boundary is observed, not predicted: an error in the estimate of where
 * the track ends only changes how much of the fade-out plays, never what is dropped.
 *
 * Pure Kotlin on purpose (no Android types) so the state machine can be unit tested on a JVM.
 *
 * Threading: [arm], [cancel] and [snapshotState] may be called from any thread. Everything else
 * is called from ExoPlayer's playback thread only.
 */
class CrossfadeStage {

    enum class State { IDLE, ARMED, ACTIVE, DROPPING, RAMPING }

    /** Result holder for [transform]; reused to avoid allocating per buffer. */
    class Out {
        /** What to hand to the real sink, or null when the whole buffer was swallowed. */
        var buffer: ByteBuffer? = null

        /** Presentation time that goes with [buffer]. */
        var ptsUs: Long = 0L
    }

    private class Plan(
            val incoming: FloatArray,
            val incomingFrames: Int,
            val fadeFrames: Int,
            val startAfterFrames: Long
    )

    private val lock = Any()

    @Volatile
    private var pendingPlan: Plan? = null

    @Volatile
    private var cancelRequested = false

    /** Set when the queue changed under a running fade: the track that arrives is not the one mixed in. */
    @Volatile
    private var abortActiveRequested = false

    @Volatile
    private var stateView = State.IDLE

    /**
     * Optional diagnostics hook, called on the playback thread with a short human-readable note
     * at each state change. The service uses it for log lines and debug-build toasts.
     */
    @Volatile
    var eventListener: ((String) -> Unit)? = null

    private fun emit(msg: String) {
        eventListener?.invoke(msg)
    }

    // --- stream format, set by configure() ---------------------------------------------------
    @Volatile
    var sampleRate = 0
        private set

    @Volatile
    var channelCount = 0
        private set

    @Volatile
    var encoding = 0
        private set

    private var bytesPerSample = 0
    private var frameSize = 0

    /** Bytes per interleaved frame of the current stream (0 until configured). */
    fun frameBytes(): Int = frameSize

    /** True when the current stream is raw PCM in a layout this stage can mix. */
    @Volatile
    var supported = false
        private set

    // --- playback-thread state -----------------------------------------------------------------
    private var state = State.IDLE
    private var plan: Plan? = null
    private var startRemaining = 0L
    private var pos = 0
    private var dropRemaining = 0L
    private var droppedSoFar = 0L
    private var rampRemaining = 0
    private var rampTotal = 0
    private var rampFromGain = 1f

    // Retry cache: if the sink answers "not consumed" the caller re-offers the SAME buffer.
    private var cachedInput: ByteBuffer? = null
    private var cachedOut: ByteBuffer? = null
    private var cachedPts = 0L
    private var cachedSwallow = false

    private var scratch: ByteBuffer = ByteBuffer.allocate(0)

    // -------------------------------------------------------------------------------------------

    /**
     * Declares the PCM layout of the stream that follows. Returns false (and disables mixing)
     * for anything but raw 16/24/32-bit integer or float PCM with 1..8 channels.
     */
    fun configure(sampleRate: Int, channelCount: Int, encoding: Int, isRawPcm: Boolean): Boolean {
        val bytes = when (encoding) {
            ENC_16 -> 2
            ENC_24 -> 3
            ENC_32, ENC_FLOAT -> 4
            else -> 0
        }
        val ok = isRawPcm && bytes > 0 && sampleRate > 0 && channelCount in 1..8
        val layoutChanged = sampleRate != this.sampleRate || channelCount != this.channelCount || encoding != this.encoding
        this.sampleRate = sampleRate
        this.channelCount = channelCount
        this.encoding = encoding
        this.bytesPerSample = bytes
        this.frameSize = bytes * channelCount
        supported = ok
        // A layout change mid-plan means the incoming audio no longer matches the stream.
        if (!ok || layoutChanged) cancel()
        return ok
    }

    /**
     * Hands over decoded incoming audio. [incoming] must be interleaved float samples at this
     * stage's [sampleRate] and [channelCount]. [startAfterFrames] is how many frames of the
     * current track still pass through before the fade begins.
     */
    fun arm(incoming: FloatArray, incomingFrames: Int, fadeFrames: Int, startAfterFrames: Long): Boolean {
        if (!supported || fadeFrames <= 0 || incomingFrames < fadeFrames) return false
        if (incoming.size < incomingFrames * channelCount) return false
        synchronized(lock) {
            // cancelRequested is deliberately NOT cleared here: a cancel() followed by arm() must
            // still cancel the OLD plan before the new one is adopted (see adoptPendingPlan).
            pendingPlan = Plan(incoming, incomingFrames, fadeFrames, startAfterFrames.coerceAtLeast(0L))
        }
        return true
    }

    /**
     * Aborts an armed plan. A fade that is already running can't be undone; with
     * [abortActive] the stage at least stops treating the arriving track as the one it mixed in:
     * no frames are dropped from it and it ramps in, instead of losing its intro.
     */
    fun cancel(abortActive: Boolean = false) {
        synchronized(lock) {
            pendingPlan = null
            cancelRequested = true
            if (abortActive) abortActiveRequested = true
        }
    }

    /** Safe from any thread: where the stage is, for deciding whether to plan again. */
    fun snapshotState(): State = stateView

    /** Playback thread: a seek, track change or flush wiped the pipeline. */
    fun reset() {
        synchronized(lock) {
            pendingPlan = null
            cancelRequested = false
            abortActiveRequested = false
        }
        enter(State.IDLE)
        rampRemaining = 0
        plan = null
        pos = 0
        dropRemaining = 0
        cachedInput = null
        cachedOut = null
    }

    /**
     * Playback thread: ExoPlayer is about to hand over the first buffer of a new stream (the
     * track boundary). A fade in progress turns into a drop of the part the listener already heard.
     */
    fun onDiscontinuity() {
        adoptPendingPlan()
        val abort = synchronized(lock) { abortActiveRequested.also { abortActiveRequested = false } }
        when (state) {
            State.ACTIVE -> {
                val p = plan
                // Level the fade had reached when the track ended (1.0 once the fade completed).
                val gain = if (p == null || pos >= p.fadeFrames) 1f
                else sin((pos.toDouble() / p.fadeFrames) * (PI / 2.0)).toFloat()
                plan = null
                if (abort) {
                    // Wrong track arrived: nothing to drop, bring it in from silence.
                    emit("boundary: plan was voided, ramping the new track in")
                    startRamp(0f)
                } else {
                    emit("boundary: dropping ${pos * 1000L / sampleRate} ms already heard (fade reached ${(gain * 100).toInt()}%)")
                    dropRemaining = pos.toLong()
                    droppedSoFar = 0L
                    rampFromGain = gain
                    enter(if (dropRemaining > 0) State.DROPPING else State.IDLE)
                }
            }
            State.ARMED -> {
                // The boundary arrived before the fade could start: nothing was mixed.
                emit("boundary arrived before the fade started - no crossfade this time")
                plan = null
                enter(State.IDLE)
            }
            else -> Unit
        }
        cachedInput = null
        cachedOut = null
    }

    /**
     * Playback thread. Returns what should be written to the real sink for [input].
     * Fast path (nothing planned) returns [input] untouched.
     *
     * Idempotent for the same [input] instance until [commit] is called, which is what lets the
     * sink answer "not fully consumed, call again" safely.
     */
    fun transform(input: ByteBuffer, ptsUs: Long, out: Out) {
        if (input === cachedInput) {
            out.buffer = if (cachedSwallow) null else cachedOut
            out.ptsUs = cachedPts
            return
        }
        adoptPendingPlan()

        if (state == State.IDLE || !supported) {
            out.buffer = input
            out.ptsUs = ptsUs
            return
        }

        val frames = input.remaining() / frameSize
        if (frames <= 0) {
            out.buffer = input
            out.ptsUs = ptsUs
            return
        }

        when (state) {
            State.ARMED -> transformArmed(input, ptsUs, frames, out)
            State.ACTIVE -> transformActive(input, ptsUs, frames, 0, out)
            State.DROPPING -> transformDropping(input, ptsUs, frames, out)
            State.RAMPING -> transformRamping(input, input.position(), ptsUs, frames, out)
            State.IDLE -> Unit
        }
    }

    /** Playback thread: the sink accepted what [transform] returned for [input]. */
    fun commit(input: ByteBuffer) {
        if (input === cachedInput) {
            input.position(input.limit())
            cachedInput = null
            cachedOut = null
        }
    }

    // -------------------------------------------------------------------------------------------

    private fun transformArmed(input: ByteBuffer, ptsUs: Long, frames: Int, out: Out) {
        if (startRemaining >= frames) {
            startRemaining -= frames
            // Cached like every other path: the sink may answer "not consumed" and re-offer this
            // very buffer, and the frames must not be counted down a second time.
            remember(input, input, ptsUs, false)
            out.buffer = input
            out.ptsUs = ptsUs
            return
        }
        // The fade starts part-way through this buffer.
        val passFrames = startRemaining.toInt()
        startRemaining = 0
        enter(State.ACTIVE)
        emit("mixing started")
        transformActive(input, ptsUs, frames, passFrames, out)
    }

    /**
     * Mixes [input] from frame [from] on; frames before it are copied through. [from] is the
     * number of leading frames that belong to the pre-fade part of the buffer.
     */
    private fun transformActive(input: ByteBuffer, ptsUs: Long, frames: Int, from: Int, out: Out) {
        val p = plan!!
        val ch = channelCount
        val buf = ensureScratch(frames * frameSize)
        val src = input.duplicate().order(ByteOrder.nativeOrder())
        val dst = buf

        for (f in 0 until frames) {
            if (f < from) {
                copyFrame(src, dst, f)
                continue
            }
            var gOut = 0f
            var gIn = 0f
            val usable = pos < p.incomingFrames
            if (pos < p.fadeFrames) {
                val t = (pos.toDouble() / p.fadeFrames) * (PI / 2.0)
                gOut = cos(t).toFloat()
                gIn = sin(t).toFloat()
            } else {
                // Past the fade: incoming solo. The outgoing tail stays silent so a track that
                // is slightly longer than predicted can't pop back in at full volume.
                gOut = 0f
                gIn = 1f
            }
            for (c in 0 until ch) {
                val o = readSample(src, f * frameSize + c * bytesPerSample)
                val i = if (usable) p.incoming[pos * ch + c] else 0f
                writeSample(dst, f * frameSize + c * bytesPerSample, o * gOut + i * gIn)
            }
            if (usable) pos++
        }
        dst.limit(frames * frameSize)
        dst.position(0)
        remember(input, dst, ptsUs, false)
        out.buffer = dst
        out.ptsUs = ptsUs
    }

    private fun transformDropping(input: ByteBuffer, ptsUs: Long, frames: Int, out: Out) {
        if (dropRemaining >= frames) {
            dropRemaining -= frames
            droppedSoFar += frames
            if (dropRemaining == 0L) startRamp(rampFromGain)
            // Entire buffer swallowed: committing it consumes the input without any write.
            remember(input, null, ptsUs, true)
            out.buffer = null
            out.ptsUs = ptsUs
            return
        }
        val skipFrames = dropRemaining.toInt()
        dropRemaining = 0
        droppedSoFar += skipFrames
        startRamp(rampFromGain)
        val newPts = ptsUs + skipFrames * 1_000_000L / sampleRate
        if (state == State.RAMPING) {
            transformRamping(input, input.position() + skipFrames * frameSize, newPts, frames - skipFrames, out)
            return
        }
        val view = input.duplicate().order(ByteOrder.nativeOrder())
        view.position(input.position() + skipFrames * frameSize)
        remember(input, view, newPts, false)
        out.buffer = view
        out.ptsUs = newPts
    }

    /** Enters RAMPING from [fromGain] to 1, or IDLE when no ramp is needed. */
    private fun startRamp(fromGain: Float) {
        if (fromGain >= 0.999f) {
            enter(State.IDLE)
            return
        }
        rampFromGain = fromGain
        rampTotal = (sampleRate / 50).coerceAtLeast(16) // 20 ms
        rampRemaining = rampTotal
        enter(State.RAMPING)
    }

    /**
     * Copies [frames] frames of [input] starting at byte offset [startByte], applying the rising
     * gain to those still inside the ramp. [ptsUs] is the time of the first copied frame.
     */
    private fun transformRamping(input: ByteBuffer, startByte: Int, ptsUs: Long, frames: Int, out: Out) {
        val buf = ensureScratch(frames * frameSize)
        val src = input.duplicate().order(ByteOrder.nativeOrder())
        src.position(startByte)
        val ch = channelCount
        for (f in 0 until frames) {
            val g = if (rampRemaining > 0) {
                val k = (rampTotal - rampRemaining).toFloat() / rampTotal
                rampFromGain + (1f - rampFromGain) * k
            } else 1f
            for (c in 0 until ch) {
                val off = f * frameSize + c * bytesPerSample
                writeSample(buf, off, readSample(src, off) * g)
            }
            if (rampRemaining > 0) rampRemaining--
        }
        if (rampRemaining == 0) enter(State.IDLE)
        buf.limit(frames * frameSize)
        buf.position(0)
        remember(input, buf, ptsUs, false)
        out.buffer = buf
        out.ptsUs = ptsUs
    }

    private fun remember(input: ByteBuffer, out: ByteBuffer?, pts: Long, swallow: Boolean) {
        cachedInput = input
        cachedOut = out
        cachedPts = pts
        cachedSwallow = swallow
    }

    private fun adoptPendingPlan() {
        val p: Plan?
        val cancelled: Boolean
        synchronized(lock) {
            p = pendingPlan
            pendingPlan = null
            cancelled = cancelRequested
            cancelRequested = false
        }
        if (cancelled && (state == State.ARMED)) {
            plan = null
            enter(State.IDLE)
        }
        if (p != null && state == State.IDLE) {
            synchronized(lock) { abortActiveRequested = false }
            plan = p
            startRemaining = p.startAfterFrames
            pos = 0
            enter(State.ARMED)
            emit("armed: fade ${p.fadeFrames * 1000L / sampleRate} ms, starts in ${p.startAfterFrames * 1000L / sampleRate} ms of audio")
        }
    }

    private fun enter(s: State) {
        state = s
        stateView = s
    }

    private fun ensureScratch(bytes: Int): ByteBuffer {
        if (scratch.capacity() < bytes) {
            scratch = ByteBuffer.allocate(bytes + 4096).order(ByteOrder.nativeOrder())
        }
        scratch.clear()
        scratch.order(ByteOrder.nativeOrder())
        return scratch
    }

    private fun copyFrame(src: ByteBuffer, dst: ByteBuffer, frame: Int) {
        val base = frame * frameSize
        for (b in 0 until frameSize) {
            dst.put(base + b, src.get(src.position() + base + b))
        }
    }

    private fun readSample(src: ByteBuffer, offset: Int): Float {
        val at = src.position() + offset
        return when (encoding) {
            ENC_16 -> src.getShort(at) / 32768f
            ENC_24 -> {
                val v = (src.get(at).toInt() and 0xFF) or
                        ((src.get(at + 1).toInt() and 0xFF) shl 8) or
                        (src.get(at + 2).toInt() shl 16)
                v / 8388608f
            }
            ENC_32 -> (src.getInt(at) / 2147483648.0).toFloat()
            else -> src.getFloat(at)
        }
    }

    private fun writeSample(dst: ByteBuffer, offset: Int, value: Float) {
        when (encoding) {
            ENC_16 -> dst.putShort(offset, (value.coerceIn(-1f, 1f) * 32767f).toInt().toShort())
            ENC_24 -> {
                val v = (value.coerceIn(-1f, 1f) * 8388607f).toInt()
                dst.put(offset, v.toByte())
                dst.put(offset + 1, (v shr 8).toByte())
                dst.put(offset + 2, (v shr 16).toByte())
            }
            ENC_32 -> dst.putInt(offset, (value.coerceIn(-1f, 1f) * 2147483647.0).toInt())
            else -> dst.putFloat(offset, value)
        }
    }

    companion object {
        // Numeric values of androidx.media3.common.C.ENCODING_PCM_* (kept literal so this class has no Android dependency).
        const val ENC_16 = 2
        const val ENC_FLOAT = 4
        const val ENC_24 = 21
        const val ENC_32 = 22
    }
}
