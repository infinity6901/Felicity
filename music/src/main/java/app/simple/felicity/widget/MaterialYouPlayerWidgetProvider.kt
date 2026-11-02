package app.simple.felicity.widget

import android.content.Context
import android.graphics.Bitmap
import android.widget.RemoteViews
import app.simple.felicity.R

/**
 * Material You 4×2 player card: rounded album art on the left, title / artist, and a row of
 * previous · play/pause · next buttons styled with the system's dynamic colors.
 *
 * @author Hamza417
 */
class MaterialYouPlayerWidgetProvider : BaseWidgetProvider() {

    override fun getLayoutId(): Int = R.layout.widget_md_player

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
        views.setOnClickPendingIntent(R.id.widget_prev, scope.pendingIntentBuilder(context, WidgetActionReceiver.ACTION_PREV))
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

    /** This style has a solid dynamic-color background — no blurred art needed. */
    override suspend fun loadBackgroundArt(
            context: Context,
            manager: android.appwidget.AppWidgetManager,
            widgetId: Int,
            songId: Long
    ): Bitmap? = null

    override fun applyBackgroundArt(views: RemoteViews, bitmap: Bitmap?) = Unit

    /** Bigger source bitmap so the 96dp art stays sharp on high-density screens. */
    override fun getAlbumArtSize(context: Context): Int =
        (192 * context.resources.displayMetrics.density).toInt()
}
