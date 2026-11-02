package app.simple.felicity.widget

import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import app.simple.felicity.engine.managers.MediaPlaybackManager
import app.simple.felicity.repository.utils.AudioUtils.getProperArtists
import app.simple.felicity.repository.utils.AudioUtils.getProperTitle
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Keeps the "Up next" widget in sync with the playback queue.
 *
 * Whenever the queue, the current position, or an online (YouTube) queue changes, it works
 * out the next few songs, saves them to [WidgetQueuePrefs], and pokes [UpNextWidgetProvider]
 * to redraw. Only runs while an Up-next widget exists (see [WidgetBootstrap]).
 *
 * @author Hamza417
 */
@Singleton
class QueueWidgetUpdater @Inject constructor(
        @ApplicationContext private val context: Context
) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var job: Job? = null
    private var lastKey: String? = null

    @OptIn(FlowPreview::class)
    @Synchronized
    fun start() {
        if (job?.isActive == true) return
        job = scope.launch {
            // Any of these changing means "the upcoming list may have changed"; the values are
            // then re-read from the manager, so combine()'s need for every flow to emit first
            // doesn't apply.
            merge(
                    MediaPlaybackManager.songListFlow.map { },
                    MediaPlaybackManager.songPositionFlow.map { },
                    MediaPlaybackManager.onlineQueueFlow.map { },
                    MediaPlaybackManager.onlineQueueIndexFlow.map { },
                    MediaPlaybackManager.currentOnlineTrackFlow.map { }
            ).debounce(250).collect { update() }
        }
        // Draw once right away for a widget that was just added.
        scope.launch { update() }
    }

    private fun update() {
        val manager = AppWidgetManager.getInstance(context)
        val component = ComponentName(context, UpNextWidgetProvider::class.java)
        if (manager.getAppWidgetIds(component).isEmpty()) {
            job?.cancel()
            job = null
            lastKey = null
            return
        }

        val upcoming = computeUpcoming()
        val key = upcoming.items.joinToString("|") { "${it.title}/${it.artist}" } + "#${upcoming.remaining}"
        if (key == lastKey) return
        lastKey = key

        WidgetQueuePrefs.save(context, upcoming)
        context.sendBroadcast(Intent(UpNextWidgetProvider.ACTION_QUEUE_UPDATE).setComponent(component))
    }

    private fun computeUpcoming(): WidgetQueuePrefs.Upcoming {
        val online = MediaPlaybackManager.onlineQueueFlow.value
        val onlineIndex = MediaPlaybackManager.onlineQueueIndexFlow.value
        if (online.isNotEmpty() && onlineIndex >= 0) {
            val rest = online.drop(onlineIndex + 1)
            return WidgetQueuePrefs.Upcoming(
                    rest.take(WidgetQueuePrefs.MAX_ITEMS).map { WidgetQueuePrefs.Item(it.title, it.subtitle ?: "") },
                    (rest.size - WidgetQueuePrefs.MAX_ITEMS).coerceAtLeast(0)
            )
        }

        val songs = MediaPlaybackManager.getSongs()
        val position = MediaPlaybackManager.getCurrentSongPosition()
        val rest = if (position in songs.indices) songs.drop(position + 1) else emptyList()
        return WidgetQueuePrefs.Upcoming(
                rest.take(WidgetQueuePrefs.MAX_ITEMS).map { WidgetQueuePrefs.Item(it.getProperTitle(), it.getProperArtists()) },
                (rest.size - WidgetQueuePrefs.MAX_ITEMS).coerceAtLeast(0)
        )
    }
}
