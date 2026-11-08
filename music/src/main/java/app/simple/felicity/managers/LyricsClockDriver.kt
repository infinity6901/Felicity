package app.simple.felicity.managers

import android.view.Choreographer
import app.simple.felicity.engine.managers.MediaPlaybackManager

/**
 * Feeds a lyrics view once per frame with where the song really is.
 *
 * The player reports its position in coarse steps, so a view that reads it directly lights lines
 * and words a little late. [LyricsClock] runs on between those readings in real time; this class
 * keeps it supplied with readings and calls [onPosition] on every frame while playing.
 *
 * Start it with [start] when the view is shown, hand every player reading to [read], and call
 * [stop] when the view goes away. While paused, [onPosition] is called only when a reading changes.
 */
class LyricsClockDriver(private val onPosition: (Long) -> Unit) {

    private val clock = LyricsClock()
    private var started = false
    private var lastPlaying = false
    private var lastReported = Long.MIN_VALUE
    private var lastReading = 0L

    private val frame = object : Choreographer.FrameCallback {
        override fun doFrame(frameTimeNanos: Long) {
            if (!started) return
            val now = System.nanoTime()
            val playing = MediaPlaybackManager.isPlaying()
            if (playing != lastPlaying) {
                clock.restart(lastReading, now, running = playing, speed = 1f,
                              why = if (playing) LyricsClock.Why.CHANGE else LyricsClock.Why.PAUSE)
                lastPlaying = playing
            }
            report(clock.at(now))
            Choreographer.getInstance().postFrameCallback(this)
        }
    }

    fun start(positionMs: Long) {
        if (started) return
        started = true
        lastPlaying = MediaPlaybackManager.isPlaying()
        lastReading = positionMs
        clock.restart(positionMs, System.nanoTime(), running = lastPlaying, speed = 1f)
        Choreographer.getInstance().postFrameCallback(frame)
    }

    fun stop() {
        started = false
        Choreographer.getInstance().removeFrameCallback(frame)
    }

    /** A reading from the player. A jump (seek, new song) restarts the clock from it. */
    fun read(positionMs: Long) {
        val now = System.nanoTime()
        val jumped = kotlin.math.abs(positionMs - clock.at(now)) > JUMP_MS
        lastReading = positionMs
        if (!started) {
            report(positionMs)
            return
        }
        if (jumped) clock.restart(positionMs, now, running = lastPlaying, speed = 1f) else clock.read(positionMs, now)
        report(clock.at(now))
    }

    /** Forget the old song: the next reading is taken as where the new one stands. */
    fun reset(positionMs: Long = 0L) {
        lastReading = positionMs
        lastReported = Long.MIN_VALUE
        clock.restart(positionMs, System.nanoTime(), running = lastPlaying, speed = 1f)
    }

    private fun report(positionMs: Long) {
        if (positionMs == lastReported) return
        lastReported = positionMs
        onPosition(positionMs)
    }

    private companion object {
        /** A reading this far from the clock is a seek, not a late reading. */
        const val JUMP_MS = 1_500L
    }
}
