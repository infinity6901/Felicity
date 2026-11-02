package app.simple.felicity.widget

import android.content.Context

/**
 * The "Up next" widget's sticky note: the next few songs in the queue, written by
 * [QueueWidgetUpdater] and read by [UpNextWidgetProvider].
 *
 * @author Hamza417
 */
object WidgetQueuePrefs {

    private const val PREFS_NAME = "felicity_widget_queue"
    private const val KEY_COUNT = "count"
    private const val KEY_REMAINING = "remaining"
    const val MAX_ITEMS = 3

    data class Item(val title: String, val artist: String)
    data class Upcoming(val items: List<Item>, val remaining: Int)

    fun save(context: Context, upcoming: Upcoming) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit().apply {
            putInt(KEY_COUNT, upcoming.items.size)
            putInt(KEY_REMAINING, upcoming.remaining)
            upcoming.items.forEachIndexed { i, item ->
                putString("title_$i", item.title)
                putString("artist_$i", item.artist)
            }
            apply()
        }
    }

    fun load(context: Context): Upcoming {
        val p = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val count = p.getInt(KEY_COUNT, 0).coerceIn(0, MAX_ITEMS)
        val items = (0 until count).map {
            Item(p.getString("title_$it", "") ?: "", p.getString("artist_$it", "") ?: "")
        }
        return Upcoming(items, p.getInt(KEY_REMAINING, 0))
    }
}
