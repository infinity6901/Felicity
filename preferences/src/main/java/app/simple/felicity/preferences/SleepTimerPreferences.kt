package app.simple.felicity.preferences

import androidx.core.content.edit
import app.simple.felicity.manager.SharedPreferences

/**
 * Persists the user's last-used Sleep Timer configuration so the dialog can be
 * re-opened pre-filled with the same duration and options they used before.
 *
 * <p>Note that this store only remembers *preferences* (duration, fade-out, and
 * "stop at end of track" toggle) — it does NOT persist the *live* countdown
 * state. A running timer is intentionally an in-memory concept owned by
 * {@code SleepTimerManager} in the engine module: if the app process dies, the
 * timer dies with it rather than silently resuming with a stale end time.</p>
 *
 * @author Hamza417
 */
object SleepTimerPreferences {

    private const val LAST_DURATION_MINUTES = "sleep_timer_last_duration_minutes"
    private const val FADE_OUT_ENABLED = "sleep_timer_fade_out_enabled"
    private const val STOP_AT_END_OF_TRACK = "sleep_timer_stop_at_end_of_track"

    /** Default duration (in minutes) shown the first time the dialog is opened. */
    const val DEFAULT_DURATION_MINUTES = 30

    /** Minimum duration selectable via the dialog's seekbar. */
    const val MIN_DURATION_MINUTES = 5

    /** Maximum duration selectable via the dialog's seekbar. */
    const val MAX_DURATION_MINUTES = 180

    // ---------------------------------------------------------------------------------------------------------- //

    fun setLastDurationMinutes(minutes: Int) {
        SharedPreferences.getSharedPreferences().edit {
            putInt(LAST_DURATION_MINUTES, minutes.coerceIn(MIN_DURATION_MINUTES, MAX_DURATION_MINUTES))
        }
    }

    fun getLastDurationMinutes(): Int {
        return SharedPreferences.getSharedPreferences()
            .getInt(LAST_DURATION_MINUTES, DEFAULT_DURATION_MINUTES)
    }

    // ---------------------------------------------------------------------------------------------------------- //

    fun setFadeOutEnabled(enabled: Boolean) {
        SharedPreferences.getSharedPreferences().edit {
            putBoolean(FADE_OUT_ENABLED, enabled)
        }
    }

    /** Defaults to `true` — a fade feels far less jarring than an instant cut to silence. */
    fun isFadeOutEnabled(): Boolean {
        return SharedPreferences.getSharedPreferences().getBoolean(FADE_OUT_ENABLED, true)
    }

    // ---------------------------------------------------------------------------------------------------------- //

    fun setStopAtEndOfTrack(enabled: Boolean) {
        SharedPreferences.getSharedPreferences().edit {
            putBoolean(STOP_AT_END_OF_TRACK, enabled)
        }
    }

    /**
     * Whether the timer should ignore the duration and instead stop playback the moment
     * the current song finishes naturally, however long that takes.
     */
    fun isStopAtEndOfTrackEnabled(): Boolean {
        return SharedPreferences.getSharedPreferences().getBoolean(STOP_AT_END_OF_TRACK, false)
    }
}
