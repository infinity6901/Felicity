package app.simple.felicity.preferences

import androidx.core.content.edit
import app.simple.felicity.manager.SharedPreferences

/**
 * Persists the user's crossfade preferences (on/off, duration, experimental DSP mode).
 *
 * See `FelicityPlayerService.maybeStartCrossfade`/`beginCrossfade` (engine module) for the
 * actual playback-side implementation — this object only holds the settings.
 *
 * @author Hamza417
 */
object CrossfadePreferences {

    const val CROSSFADE_ENABLED = "crossfade_enabled"
    private const val CROSSFADE_DURATION_MS = "crossfade_duration_ms"
    const val CROSSFADE_DSP_MODE = "crossfade_dsp_mode"

    /** Minimum crossfade length selectable via the settings slider. */
    const val MIN_DURATION_MS = 1000L

    /** Maximum crossfade length selectable via the settings slider. */
    const val MAX_DURATION_MS = 12000L

    /** Default shown the first time the setting is opened. */
    const val DEFAULT_DURATION_MS = 4000L

    // ---------------------------------------------------------------------------------------------------------- //

    fun setCrossfadeEnabled(enabled: Boolean) {
        SharedPreferences.getSharedPreferences().edit {
            putBoolean(CROSSFADE_ENABLED, enabled)
        }
    }

    /** Off by default — crossfading is a deliberate opt-in, not a silent behavior change. */
    fun isCrossfadeEnabled(): Boolean {
        return SharedPreferences.getSharedPreferences().getBoolean(CROSSFADE_ENABLED, false)
    }

    // ---------------------------------------------------------------------------------------------------------- //

    fun setCrossfadeDurationMs(durationMs: Long) {
        SharedPreferences.getSharedPreferences().edit {
            putLong(CROSSFADE_DURATION_MS, durationMs.coerceIn(MIN_DURATION_MS, MAX_DURATION_MS))
        }
    }

    fun getCrossfadeDurationMs(): Long {
        return SharedPreferences.getSharedPreferences()
            .getLong(CROSSFADE_DURATION_MS, DEFAULT_DURATION_MS)
            .coerceIn(MIN_DURATION_MS, MAX_DURATION_MS)
    }

    /**
     * True when either crossfade switch is on. The DSP switch stands on its own: turning it on
     * crossfades even if the main switch is off, falling back to the standard crossfade whenever
     * the DSP path can't be used. Everything that decides "should we crossfade at all" uses this.
     */
    fun isActive(): Boolean = isCrossfadeEnabled() || isDspModeEnabled()

    // ---------------------------------------------------------------------------------------------------------- //

    fun setDspModeEnabled(enabled: Boolean) {
        SharedPreferences.getSharedPreferences().edit {
            putBoolean(CROSSFADE_DSP_MODE, enabled)
        }
    }

    /**
     * Experimental. When on, the next track is mixed into the current one before the DSP chain,
     * so the equaliser and effects apply to the whole fade. Falls back to the classic
     * two-player crossfade whenever that can't be done. Off by default.
     */
    fun isDspModeEnabled(): Boolean {
        return SharedPreferences.getSharedPreferences().getBoolean(CROSSFADE_DSP_MODE, false)
    }
}
