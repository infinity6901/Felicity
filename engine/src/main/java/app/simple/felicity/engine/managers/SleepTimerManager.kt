package app.simple.felicity.engine.managers

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Singleton bridge between the Sleep Timer UI (dialog) and [app.simple.felicity.engine.services.FelicityPlayerService].
 *
 * <p>Follows the same in-process-singleton pattern as [MediaPlaybackManager] and
 * [VisualizerManager]: the UI calls into this object directly (no IPC / session-command
 * round-trip needed, since dialog and service live in the same process), and the service
 * registers a [Listener] so it can react to the timer actually finishing by pausing —
 * and, for countdown timers, fading — playback.</p>
 *
 * <p>Two independent modes are supported:</p>
 * <ul>
 *   <li>[Mode.Countdown] — stop after a fixed duration has elapsed.</li>
 *   <li>[Mode.EndOfTrack] — stop the instant the current song finishes playing, regardless
 *       of how long that takes. There is no ticking countdown for this mode; the service
 *       simply asks [consumeIfArmedForEndOfTrack] whenever a track transitions naturally.</li>
 * </ul>
 *
 * <p>State is intentionally held only in memory. If the app process is killed, any running
 * timer is gone with it rather than resurrecting itself with a stale end time on the next
 * cold start — the same behavior users already expect from most sleep timers.</p>
 *
 * @author Hamza417
 */
object SleepTimerManager {

    /** Represents the currently armed sleep timer, or the absence of one. */
    sealed class Mode {
        /** Stops playback once [endTimeMs] (epoch millis) is reached. */
        data class Countdown(val endTimeMs: Long, val fadeOut: Boolean) : Mode()

        /** Stops playback the moment the current song ends naturally. */
        data class EndOfTrack(val fadeOut: Boolean) : Mode()
    }

    /** Implemented by [app.simple.felicity.engine.services.FelicityPlayerService] to react to timer events. */
    interface Listener {
        /** Called roughly once a second while a [Mode.Countdown] timer is running. */
        fun onSleepTimerTick(remainingMs: Long) {}

        /** Called the instant a timer (of either mode) is armed. */
        fun onSleepTimerArmed(mode: Mode) {}

        /**
         * Called when the timer's condition is met and playback should stop.
         *
         * @param fadeOut Whether the stop should be preceded by a short volume fade,
         *                as opposed to an immediate hard pause.
         */
        fun onSleepTimerFinished(fadeOut: Boolean) {}

        /** Called when a running timer is cancelled before it could finish. */
        fun onSleepTimerCancelled() {}
    }

    private const val TICK_INTERVAL_MS = 1000L

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var tickJob: Job? = null
    private val listeners = mutableSetOf<Listener>()

    @Volatile
    private var mode: Mode? = null

    /** Whether a timer (either mode) is currently armed. */
    val isArmed: Boolean
        get() = mode != null

    /** The currently armed [Mode], or `null` if no timer is running. */
    fun currentMode(): Mode? = mode

    fun registerListener(listener: Listener) {
        synchronized(listeners) { listeners.add(listener) }
    }

    fun unregisterListener(listener: Listener) {
        synchronized(listeners) { listeners.remove(listener) }
    }

    /**
     * Arms a countdown timer that stops playback after [durationMs] milliseconds.
     *
     * @param durationMs The countdown length in milliseconds.
     * @param fadeOut    Whether the stop should fade the volume out first.
     */
    fun startCountdown(durationMs: Long, fadeOut: Boolean) {
        require(durationMs > 0) { "durationMs must be positive, was $durationMs" }

        cancelInternal(notify = false)

        val endTimeMs = System.currentTimeMillis() + durationMs
        val countdown = Mode.Countdown(endTimeMs, fadeOut)
        mode = countdown
        notifyArmed(countdown)

        tickJob = scope.launch {
            while (isActive) {
                val remaining = endTimeMs - System.currentTimeMillis()
                if (remaining <= 0L) {
                    mode = null
                    notifyTick(0L)
                    notifyFinished(fadeOut)
                    break
                }
                notifyTick(remaining)
                delay(TICK_INTERVAL_MS.coerceAtMost(remaining))
            }
        }
    }

    /**
     * Arms a timer that stops playback the moment the current song finishes naturally.
     * No countdown ticking happens in this mode — see [consumeIfArmedForEndOfTrack].
     *
     * @param fadeOut Whether the stop should fade the volume out first. Note that for a
     *                natural end-of-track stop there is generally nothing left to fade,
     *                since playback has already reached silence — the flag is honored
     *                for API consistency but the service may choose to skip the fade.
     */
    fun startEndOfTrack(fadeOut: Boolean) {
        cancelInternal(notify = false)
        val endOfTrack = Mode.EndOfTrack(fadeOut)
        mode = endOfTrack
        notifyArmed(endOfTrack)
    }

    /**
     * Atomically checks whether an end-of-track timer is currently armed, clearing it and
     * firing [Listener.onSleepTimerFinished] if so.
     *
     * Intended to be called by the service from its track-transition callback whenever a
     * transition happens for reasons other than a manual seek/skip (i.e. the song actually
     * played out to completion). Returns `false` (and does nothing) for [Mode.Countdown] or
     * when no timer is armed at all.
     *
     * @return `true` if an end-of-track timer was armed and has now fired.
     */
    fun consumeIfArmedForEndOfTrack(): Boolean {
        val armed = mode as? Mode.EndOfTrack ?: return false
        mode = null
        notifyFinished(armed.fadeOut)
        return true
    }

    /** Cancels any running timer. No-op if nothing is armed. */
    fun cancel() = cancelInternal(notify = true)

    private fun cancelInternal(notify: Boolean) {
        tickJob?.cancel()
        tickJob = null
        val wasArmed = mode != null
        mode = null
        if (notify && wasArmed) {
            notifyCancelled()
        }
    }

    /** Remaining time in milliseconds for a [Mode.Countdown] timer, or `0` otherwise. */
    fun getRemainingMs(): Long {
        val countdown = mode as? Mode.Countdown ?: return 0L
        return (countdown.endTimeMs - System.currentTimeMillis()).coerceAtLeast(0L)
    }

    private fun notifyTick(remainingMs: Long) {
        snapshotListeners().forEach { it.onSleepTimerTick(remainingMs) }
    }

    private fun notifyArmed(mode: Mode) {
        snapshotListeners().forEach { it.onSleepTimerArmed(mode) }
    }

    private fun notifyFinished(fadeOut: Boolean) {
        snapshotListeners().forEach { it.onSleepTimerFinished(fadeOut) }
    }

    private fun notifyCancelled() {
        snapshotListeners().forEach { it.onSleepTimerCancelled() }
    }

    private fun snapshotListeners(): List<Listener> = synchronized(listeners) { listeners.toList() }
}
