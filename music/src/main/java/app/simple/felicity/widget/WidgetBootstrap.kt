package app.simple.felicity.widget

import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Context
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent

/**
 * Starts the background helpers that keep the "live" widgets (lyrics, up next) in sync —
 * but only for widget types the user has actually placed on their home screen, so nothing
 * runs (and no extra managers are created) for people who don't use them.
 *
 * Called from the widget providers themselves and from the Application on process start.
 *
 * @author Hamza417
 */
object WidgetBootstrap {

    @EntryPoint
    @InstallIn(SingletonComponent::class)
    interface Updaters {
        fun lyricsWidgetUpdater(): LyricsWidgetUpdater
        fun queueWidgetUpdater(): QueueWidgetUpdater
    }

    /** Starts whichever updaters have at least one widget placed. Safe to call repeatedly. */
    @JvmStatic
    fun startUpdaters(context: Context) {
        runCatching {
            val app = context.applicationContext
            val manager = AppWidgetManager.getInstance(app)
            fun hasWidget(cls: Class<*>) = manager.getAppWidgetIds(ComponentName(app, cls)).isNotEmpty()

            val needLyrics = LyricsWidgetUpdater.PROVIDERS.any { hasWidget(it) }
            val needQueue = UpNextWidgetProvider.PROVIDERS.any { hasWidget(it) }
            if (!needLyrics && !needQueue) return

            val entry = EntryPointAccessors.fromApplication(app, Updaters::class.java)
            if (needLyrics) entry.lyricsWidgetUpdater().start()
            if (needQueue) entry.queueWidgetUpdater().start()
        }
    }
}
