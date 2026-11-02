package app.simple.felicity.widget

import android.appwidget.AppWidgetManager
import android.content.Context
import android.graphics.Bitmap
import android.widget.RemoteViews
import app.simple.felicity.R

/**
 * Material You 3×1 pill: a round album art thumbnail, title / artist, and play/pause + next
 * buttons inside a slim rounded container that follows the system's dynamic colors.
 *
 * @author Hamza417
 */
class MaterialYouPillWidgetProvider : BaseWidgetProvider() {

    override fun getLayoutId(): Int = R.layout.widget_md_pill

    override fun applyStaticViews(scope: WidgetViewScope) {
        val views = scope.views
        val context = scope.context

        views.setTextViewText(R.id.widget_title, scope.title)
        views.setTextViewText(R.id.widget_artist, scope.artist)
        views.setImageViewResource(
                R.id.widget_play_pause,
                if (scope.isPlaying) R.drawable.ic_pause else R.drawable.ic_play
        )
        views.setOnClickPendingIntent(R.id.widget_play_pause, scope.pendingIntentBuilder(context, WidgetActionReceiver.ACTION_PLAY_PAUSE))
        views.setOnClickPendingIntent(R.id.widget_next, scope.pendingIntentBuilder(context, WidgetActionReceiver.ACTION_NEXT))
        views.setOnClickPendingIntent(R.id.widget_album_art, scope.pendingIntentBuilder(context, WidgetActionReceiver.ACTION_OPEN_APP))
    }

    override fun applyAlbumArt(views: RemoteViews, bitmap: Bitmap?) {
        if (bitmap != null) {
            views.setImageViewBitmap(R.id.widget_album_art, bitmap)
        } else {
            views.setImageViewResource(R.id.widget_album_art, R.mipmap.ic_launcher)
        }
    }

    override suspend fun loadBackgroundArt(
            context: Context,
            manager: AppWidgetManager,
            widgetId: Int,
            songId: Long
    ): Bitmap? = null

    override fun applyBackgroundArt(views: RemoteViews, bitmap: Bitmap?) = Unit

    override fun getAlbumArtSize(context: Context): Int =
        (96 * context.resources.displayMetrics.density).toInt()
}
