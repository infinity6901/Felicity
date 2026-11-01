package app.simple.felicity.preferences

import androidx.core.content.edit
import app.simple.felicity.manager.SharedPreferences

/**
 * Persists the user's crossfade preferences (on/off + duration).
 *
 * See `FelicityPlayerService.maybeStartCrossfade`/`beginCrossfade` (engine module) for the
 * actual playback-side implementation — this object only holds the two settings.
 *
 * @author Hamza417
 */
object CrossfadePreferences {

    const val CROSSFADE_ENABLED = "crossfade_enabled"
    private const val CROSSFADE_DURATION_MS = "crossfade_duration_ms"

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
    }
}
