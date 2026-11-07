package app.simple.felicity.widget

import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.widget.RemoteViews
import app.simple.felicity.R

/**
 * Material You lyrics widget — shows the previous / current / next lyric line, advancing
 * in time with the song, in the system's dynamic colors.
 *
 * The lines themselves are produced by [LyricsWidgetUpdater]; this class only draws them.
 * It reuses [BaseWidgetProvider] for the shared plumbing (play-state broadcasts, the
 * play/pause intent) but skips all album-art loading since this layout has no artwork.
 *
 * @author Hamza417
 */
open class LyricsWidgetProvider : BaseWidgetProvider() {

    override fun getLayoutId(): Int = R.layout.widget_lyrics

    /** Glass variants draw the blurred artwork behind the lyrics; the Material You one doesn't. */
    protected open val usesBackgroundArt: Boolean = false

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == ACTION_LYRICS_UPDATE) {
            // Lightweight and synchronous: just re-render from the saved lines.
            val manager = AppWidgetManager.getInstance(context)
            val ids = manager.getAppWidgetIds(ComponentName(context, this::class.java))
            if (ids.isNotEmpty()) {
                // Partial update: only the lyric text changes, so the blurred background
                // bitmap already on screen is left untouched (no flicker, no re-decoding).
                manager.partiallyUpdateAppWidget(ids, render(context))
            }
            return
        }
        super.onReceive(context, intent)
    }

    override fun onEnabled(context: Context) {
        super.onEnabled(context)
        startUpdater(context)
    }

    override suspend fun pushUpdate(context: Context, manager: AppWidgetManager, widgetId: Int) {
        startUpdater(context)
        val views = render(context)
        manager.updateAppWidget(widgetId, views)

        if (usesBackgroundArt) {
            val background = loadBackgroundArt(context, manager, widgetId, WidgetStatePrefs.getSongId(context))
            if (background != null) {
                views.setImageViewBitmap(R.id.widget_background_view, background)
                manager.updateAppWidget(widgetId, views)
            }
        }
    }

    private fun startUpdater(context: Context) {
        WidgetBootstrap.startUpdaters(context)
    }

    private fun render(context: Context): RemoteViews {
        val views = RemoteViews(context.packageName, getLayoutId())
        applyStaticViews(
                WidgetViewScope(
                        context = context,
                        views = views,
                        title = WidgetStatePrefs.getTitle(context),
                        artist = WidgetStatePrefs.getArtist(context),
                        isPlaying = WidgetStatePrefs.isPlaying(context),
                        pendingIntentBuilder = ::buildPendingIntent
                )
        )
        return views
    }

    override fun applyStaticViews(scope: WidgetViewScope) {
        val views = scope.views
        val context = scope.context
        val lines = WidgetLyricsPrefs.load(context)
        val hasSong = scope.title.isNotEmpty()

        views.setTextViewText(
                R.id.widget_title,
                if (hasSong) listOf(scope.title, scope.artist).filter { it.isNotEmpty() }.joinToString(" • ")
                else context.getString(R.string.widget_nothing_playing)
        )

        when {
            !hasSong -> {
                views.setTextViewText(R.id.widget_lyric_prev, "")
                views.setTextViewText(R.id.widget_lyric_current, context.getString(R.string.widget_nothing_playing))
                views.setTextViewText(R.id.widget_lyric_next, "")
            }
            lines.state == WidgetLyricsPrefs.STATE_NONE -> {
                views.setTextViewText(R.id.widget_lyric_prev, "")
                views.setTextViewText(R.id.widget_lyric_current, context.getString(R.string.widget_no_lyrics))
                views.setTextViewText(R.id.widget_lyric_next, "")
            }
            lines.state == WidgetLyricsPrefs.STATE_UNSYNCED -> {
                views.setTextViewText(R.id.widget_lyric_prev, "")
                views.setTextViewText(R.id.widget_lyric_current, context.getString(R.string.widget_lyrics_unsynced))
                views.setTextViewText(R.id.widget_lyric_next, "")
            }
            else -> {
                val neighbours = WidgetLyricStyle.isShowNeighbours()
                views.setTextViewText(R.id.widget_lyric_prev, if (neighbours) lines.prev else "")
                views.setTextViewText(R.id.widget_lyric_current, WidgetLyricStyle.styleCurrent(lines.current))
                views.setTextViewText(R.id.widget_lyric_next, if (neighbours) lines.next else "")
            }
        }
        val currentSize = WidgetLyricStyle.getSize()
        views.setTextViewTextSize(R.id.widget_lyric_current, android.util.TypedValue.COMPLEX_UNIT_SP, currentSize)
        val neighbourSize = (currentSize * 0.65f).coerceAtLeast(10f)
        views.setTextViewTextSize(R.id.widget_lyric_prev, android.util.TypedValue.COMPLEX_UNIT_SP, neighbourSize)
        views.setTextViewTextSize(R.id.widget_lyric_next, android.util.TypedValue.COMPLEX_UNIT_SP, neighbourSize)

        views.setImageViewResource(
                R.id.widget_play_pause,
                if (scope.isPlaying) R.drawable.ic_pause else R.drawable.ic_play
        )
        views.setOnClickPendingIntent(
                R.id.widget_play_pause,
                scope.pendingIntentBuilder(context, WidgetActionReceiver.ACTION_PLAY_PAUSE)
        )
        views.setOnClickPendingIntent(
                R.id.widget_lyrics_area,
                scope.pendingIntentBuilder(context, WidgetActionReceiver.ACTION_OPEN_APP)
        )
    }

    override fun applyAlbumArt(views: RemoteViews, bitmap: Bitmap?) = Unit

    override fun applyBackgroundArt(views: RemoteViews, bitmap: Bitmap?) = Unit

    companion object {
        /** Sent by [LyricsWidgetUpdater] when the visible lyric lines changed. */
        const val ACTION_LYRICS_UPDATE = "app.simple.felicity.ACTION_LYRICS_WIDGET_UPDATE"
    }
}
