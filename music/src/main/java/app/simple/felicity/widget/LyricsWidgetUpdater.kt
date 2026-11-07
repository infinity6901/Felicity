package app.simple.felicity.widget

import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import app.simple.felicity.decorations.lrc.model.LrcData
import app.simple.felicity.decorations.lrc.model.LyricVersions
import app.simple.felicity.engine.managers.MediaPlaybackManager
import app.simple.felicity.managers.LyricsManager
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Keeps the lyrics home screen widget in sync with playback.
 *
 * Home screen widgets can't tick on their own, so this watches the app's shared
 * [LyricsManager] data and the playback position, works out which lyric line is "now",
 * and only when that line changes (every few seconds, not every frame) saves the new
 * lines to [WidgetLyricsPrefs] and pokes [LyricsWidgetProvider] to redraw.
 *
 * It runs inside the app process, which is kept alive by the foreground playback service
 * while music plays — so no extra service, alarm or wake-lock is needed. It's only started
 * when at least one lyrics widget exists (see [start]), so users without the widget pay
 * nothing for it.
 *
 * @author Hamza417
 */
@Singleton
class LyricsWidgetUpdater @Inject constructor(
        @ApplicationContext private val context: Context,
        private val lyricsManager: LyricsManager
) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var job: Job? = null
    private var lastKey: String? = null

    /** Idempotent: safe to call from every widget update. */
    @Synchronized
    fun start() {
        if (job?.isActive == true) return
        job = scope.launch {
            combine(
                    lyricsManager.lrcData,
                    MediaPlaybackManager.songSeekPositionFlow,
                    lyricsManager.syncOffsetMs
            ) { data, position, offset -> Triple(data, position, offset) }
                .collect { (data, position, offset) -> update(data, position + offset) }
        }
    }

    /** Forces the next position tick (or this call, when data is known) to redraw the widgets. */
    @Synchronized
    fun invalidate() {
        lastKey = null
        val data = lyricsManager.lrcData.value
        if (job?.isActive == true && data != null) {
            update(data, MediaPlaybackManager.getSeekPosition() + lyricsManager.syncOffsetMs.value)
        }
    }

    private fun update(data: LrcData?, positionMs: Long) {
        val lines = computeLines(data, positionMs)
        // Cheap early-out: nothing visible changed (the position flow ticks ~10x/sec).
        val key = "${System.identityHashCode(data)}|${lines.state}|${lines.prev}|${lines.current}|${lines.next}"
        if (key == lastKey) return
        lastKey = key

        val manager = AppWidgetManager.getInstance(context)
        // Every lyrics-style widget (Material You and Glass) shares these lines.
        val active = PROVIDERS.filter {
            manager.getAppWidgetIds(ComponentName(context, it)).isNotEmpty()
        }
        if (active.isEmpty()) {
            // Widget was removed — stop watching until a new one asks us to start again.
            job?.cancel()
            job = null
            lastKey = null
            return
        }

        WidgetLyricsPrefs.save(context, lines)
        active.forEach {
            context.sendBroadcast(
                    Intent(LyricsWidgetProvider.ACTION_LYRICS_UPDATE).setComponent(ComponentName(context, it))
            )
        }
    }

    private fun computeLines(data: LrcData?, positionMs: Long): WidgetLyricsPrefs.Lines {
        if (data == null || data.isEmpty) {
            return WidgetLyricsPrefs.Lines("", "", "", WidgetLyricsPrefs.STATE_NONE)
        }
        val entries = data.entries
        // Plain-text lyrics have no timestamps: every line sits at 0 ms.
        if (entries.size > 1 && entries.all { it.timeInMillis <= 0L }) {
            return WidgetLyricsPrefs.Lines("", "", "", WidgetLyricsPrefs.STATE_UNSYNCED)
        }

        // Last entry whose time is <= position (entries are sorted by time).
        var lo = 0
        var hi = entries.size - 1
        var index = -1
        while (lo <= hi) {
            val mid = (lo + hi) ushr 1
            if (entries[mid].timeInMillis <= positionMs) {
                index = mid
                lo = mid + 1
            } else {
                hi = mid - 1
            }
        }

        val version = WidgetLyricStyle.getVersion()
        fun text(i: Int): String {
            val entry = entries.getOrNull(i) ?: return ""
            val shown = LyricVersions.versionText(entry, version) ?: entry.text
            return shown.trim()
        }
        return if (index < 0) {
            // Intro: before the first line.
            WidgetLyricsPrefs.Lines("", "♪", text(0), WidgetLyricsPrefs.STATE_SYNCED)
        } else {
            val current = text(index).ifEmpty { "♪" }
            WidgetLyricsPrefs.Lines(text(index - 1), current, text(index + 1), WidgetLyricsPrefs.STATE_SYNCED)
        }
    }

    companion object {
        /** All provider classes that display the shared lyric lines. */
        val PROVIDERS: List<Class<out LyricsWidgetProvider>> = listOf(
                LyricsWidgetProvider::class.java,
                GlassLyricsWidgetProvider::class.java
        )
    }
}
