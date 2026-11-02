package app.simple.felicity.widget

import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.view.View
import android.widget.RemoteViews
import app.simple.felicity.R

/**
 * Material You "Up next" widget (4×3): the current song with play/pause and next buttons,
 * and the next three songs in the queue below it, plus a "+N more" count.
 *
 * The upcoming list comes from [QueueWidgetUpdater]; no artwork is loaded.
 *
 * @author Hamza417
 */
class UpNextWidgetProvider : BaseWidgetProvider() {

    override fun getLayoutId(): Int = R.layout.widget_up_next

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == ACTION_QUEUE_UPDATE) {
            val manager = AppWidgetManager.getInstance(context)
            val ids = manager.getAppWidgetIds(ComponentName(context, UpNextWidgetProvider::class.java))
            if (ids.isNotEmpty()) manager.partiallyUpdateAppWidget(ids, render(context))
            return
        }
        super.onReceive(context, intent)
    }

    override fun onEnabled(context: Context) {
        super.onEnabled(context)
        WidgetBootstrap.startUpdaters(context)
    }

    override suspend fun pushUpdate(context: Context, manager: AppWidgetManager, widgetId: Int) {
        WidgetBootstrap.startUpdaters(context)
        manager.updateAppWidget(widgetId, render(context))
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
        val upcoming = WidgetQueuePrefs.load(context)

        views.setTextViewText(
                R.id.widget_title,
                scope.title.ifEmpty { context.getString(R.string.widget_nothing_playing) }
        )
        views.setImageViewResource(
                R.id.widget_play_pause,
                if (scope.isPlaying) R.drawable.ic_pause else R.drawable.ic_play
        )

        val rows = listOf(
                Triple(R.id.widget_q1, R.id.widget_q1_title, R.id.widget_q1_artist),
                Triple(R.id.widget_q2, R.id.widget_q2_title, R.id.widget_q2_artist),
                Triple(R.id.widget_q3, R.id.widget_q3_title, R.id.widget_q3_artist)
        )
        rows.forEachIndexed { i, (row, title, artist) ->
            val item = upcoming.items.getOrNull(i)
            if (item != null) {
                views.setViewVisibility(row, View.VISIBLE)
                views.setTextViewText(title, item.title)
                views.setTextViewText(artist, item.artist)
            } else {
                views.setViewVisibility(row, View.GONE)
            }
        }
        views.setViewVisibility(
                R.id.widget_queue_empty,
                if (upcoming.items.isEmpty()) View.VISIBLE else View.GONE
        )
        if (upcoming.remaining > 0) {
            views.setViewVisibility(R.id.widget_queue_more, View.VISIBLE)
            views.setTextViewText(R.id.widget_queue_more, context.getString(R.string.widget_queue_more, upcoming.remaining))
        } else {
            views.setViewVisibility(R.id.widget_queue_more, View.GONE)
        }

        views.setOnClickPendingIntent(R.id.widget_play_pause, scope.pendingIntentBuilder(context, WidgetActionReceiver.ACTION_PLAY_PAUSE))
        views.setOnClickPendingIntent(R.id.widget_next, scope.pendingIntentBuilder(context, WidgetActionReceiver.ACTION_NEXT))
        views.setOnClickPendingIntent(R.id.widget_queue_area, scope.pendingIntentBuilder(context, WidgetActionReceiver.ACTION_OPEN_APP))
    }

    override fun applyAlbumArt(views: RemoteViews, bitmap: Bitmap?) = Unit

    override fun applyBackgroundArt(views: RemoteViews, bitmap: Bitmap?) = Unit

    companion object {
        /** Sent by [QueueWidgetUpdater] when the upcoming list changed. */
        const val ACTION_QUEUE_UPDATE = "app.simple.felicity.ACTION_QUEUE_WIDGET_UPDATE"
    }
}
