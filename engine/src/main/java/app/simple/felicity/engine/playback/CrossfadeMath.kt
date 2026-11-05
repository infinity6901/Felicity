package app.simple.felicity.engine.playback

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/** Equal-power crossfade helpers shared by playback and unit tests. */
internal object CrossfadeMath {
    data class Gains(val outgoing: Float, val incoming: Float)

    /** Keeps perceived loudness steadier than a linear 1-t / t ramp. */
    fun equalPowerGains(progress: Float): Gains {
        val t = progress.coerceIn(0f, 1f)
        val angle = t * (PI.toFloat() / 2f)
        return Gains(outgoing = cos(angle), incoming = sin(angle))
    }

    /** ExoPlayer positions advance in media time, so the coroutine delay must account for speed. */
    fun wallClockStepMs(mediaStepMs: Long, speed: Float, minimumMs: Long = 10L): Long {
        val safeSpeed = if (speed.isFinite() && speed > 0f) speed else 1f
        return (mediaStepMs / safeSpeed).toLong().coerceAtLeast(minimumMs)
    }

    /** Fraction (0..1) of the fade completed after [elapsedMediaMs] of an [fadeMs]-long fade. */
    fun fadeProgress(elapsedMediaMs: Long, fadeMs: Long): Float {
        if (fadeMs <= 0L) return 1f
        return (elapsedMediaMs.toFloat() / fadeMs.toFloat()).coerceIn(0f, 1f)
    }

    /**
     * Picks a fade length for one pair of songs. It never exceeds [baseMs] (the user's chosen
     * maximum): short songs get proportionally shorter overlaps so a fade never swallows a large
     * part of them, and neighbours from the same album (often continuous mixes or live sets) get
     * a brief blend instead of a long one.
     */
    fun adaptiveFadeMs(
            baseMs: Long,
            outgoingDurationMs: Long,
            incomingDurationMs: Long,
            sameAlbum: Boolean,
            minMs: Long = 1_000L
    ): Long {
        val shortest = minOf(
                if (outgoingDurationMs > 0L) outgoingDurationMs else Long.MAX_VALUE,
                if (incomingDurationMs > 0L) incomingDurationMs else Long.MAX_VALUE)
        var factor = when {
            shortest == Long.MAX_VALUE -> 1f
            shortest <= 30_000L -> 0.25f
            shortest <= 90_000L -> 0.5f
            shortest <= 150_000L -> 0.75f
            else -> 1f
        }
        if (sameAlbum) factor *= 0.35f
        val adapted = (baseMs * factor).toLong()
        return adapted.coerceAtLeast(minMs).coerceAtMost(maxOf(baseMs, minMs))
    }
}
