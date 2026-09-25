package app.simple.felicity.engine.managers

import android.os.CountDownTimer
import android.util.Log
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Manages sleep timers for fading out and stopping audio playback after a set duration.
 */
object SleepTimerManager {

    private const val TAG = "SleepTimerManager"

    sealed class Mode {
        data class Countdown(val targetMs: Long) : Mode()
        data object EndOfTrack : Mode()
    }

    interface Listener {
        fun onSleepTimerTick(remainingMs: Long) {}
        fun onSleepTimerArmed(mode: Mode) {}
        fun onSleepTimerFinished(fadeOut: Boolean) {}
        fun onSleepTimerCancelled() {}
    }

    private val listeners = CopyOnWriteArrayList<Listener>()

    @Volatile
    private var activeMode: Mode? = null

    private var countDownTimer: CountDownTimer? = null

    @Volatile
    private var remainingMs: Long = 0L

    val isArmed: Boolean
        get() = activeMode != null

    fun currentMode(): Mode? = activeMode

    fun getRemainingMs(): Long = remainingMs

    fun registerListener(listener: Listener) {
        listeners.addIfAbsent(listener)
    }

    fun unregisterListener(listener: Listener) {
        listeners.remove(listener)
    }

    /** Starts a countdown timer for [durationMs] milliseconds. */
    fun startCountdown(durationMs: Long, fadeOut: Boolean = true) {
        cancel()
        val mode = Mode.Countdown(durationMs)
        activeMode = mode
        remainingMs = durationMs

        listeners.forEach { it.onSleepTimerArmed(mode) }

        countDownTimer = object : CountDownTimer(durationMs, 1000L) {
            override fun onTick(millisUntilFinished: Long) {
                remainingMs = millisUntilFinished
                listeners.forEach { it.onSleepTimerTick(millisUntilFinished) }
            }

            override fun onFinish() {
                Log.d(TAG, "Sleep timer finished, pausing playback.")
                activeMode = null
                remainingMs = 0L
                listeners.forEach { it.onSleepTimerFinished(fadeOut) }
            }
        }.start()

        Log.d(TAG, "Sleep timer countdown started ($durationMs ms)")
    }

    /** Enables "Stop at end of current song" mode. */
    fun startEndOfTrack(fadeOut: Boolean = true) {
        cancel()
        val mode = Mode.EndOfTrack
        activeMode = mode
        remainingMs = 0L
        listeners.forEach { it.onSleepTimerArmed(mode) }
        Log.d(TAG, "Sleep timer set to stop at end of current song.")
    }

    /** Called on song transition to check if end-of-track timer was armed. */
    fun consumeIfArmedForEndOfTrack(): Boolean {
        if (activeMode is Mode.EndOfTrack) {
            activeMode = null
            listeners.forEach { it.onSleepTimerFinished(false) }
            return true
        }
        return false
    }

    /** Cancels any running sleep timer. */
    fun cancel() {
        countDownTimer?.cancel()
        countDownTimer = null
        activeMode = null
        remainingMs = 0L
        listeners.forEach { it.onSleepTimerCancelled() }
        Log.d(TAG, "Sleep timer cancelled.")
    }
}
