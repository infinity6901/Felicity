package app.simple.felicity.widget

import android.content.Context

/**
 * The lyrics widget's sticky note: the three lines it should show right now (previous,
 * current, next) plus a state flag, written by [LyricsWidgetUpdater] and read by
 * [LyricsWidgetProvider] whenever it redraws.
 *
 * @author Hamza417
 */
object WidgetLyricsPrefs {

    private const val PREFS_NAME = "felicity_widget_lyrics"
    private const val KEY_PREV = "prev"
    private const val KEY_CURRENT = "current"
    private const val KEY_NEXT = "next"
    private const val KEY_STATE = "state"

    /** Normal, time-synced lyrics are available. */
    const val STATE_SYNCED = 0

    /** No lyrics for the current song (or nothing loaded yet). */
    const val STATE_NONE = 1

    /** Lyrics exist but carry no timestamps, so a "current line" can't be derived. */
    const val STATE_UNSYNCED = 2

    data class Lines(val prev: String, val current: String, val next: String, val state: Int)

    fun save(context: Context, lines: Lines) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit()
            .putString(KEY_PREV, lines.prev)
            .putString(KEY_CURRENT, lines.current)
            .putString(KEY_NEXT, lines.next)
            .putInt(KEY_STATE, lines.state)
            .apply()
    }

    fun load(context: Context): Lines {
        val p = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return Lines(
                prev = p.getString(KEY_PREV, "") ?: "",
                current = p.getString(KEY_CURRENT, "") ?: "",
                next = p.getString(KEY_NEXT, "") ?: "",
                state = p.getInt(KEY_STATE, STATE_NONE)
        )
    }
}
