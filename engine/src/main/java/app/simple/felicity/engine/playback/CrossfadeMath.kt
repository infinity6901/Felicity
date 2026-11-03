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
}
