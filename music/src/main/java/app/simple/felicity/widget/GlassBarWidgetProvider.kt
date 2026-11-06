package app.simple.felicity.widget

import android.content.Context
import android.graphics.Bitmap
import android.widget.RemoteViews
import app.simple.felicity.R

/**
 * Frosted-glass 4×1 now-playing bar: small art, title / artist and previous · play/pause · next.
 * Replaces the old plain compact and minimal widgets.
 *
 * @author Hamza417
 */
class GlassBarWidgetProvider : BaseWidgetProvider() {

    override fun getLayoutId(): Int = R.layout.widget_glass_bar

    override val frostedBackground: Boolean = true

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

    override fun applyAccent(views: RemoteViews, accent: Int) {
        views.setInt(R.id.widget_play_pause, "setColorFilter", GlassRenderer.iconShade(accent))
    }

    override fun applyAlbumArt(views: RemoteViews, bitmap: Bitmap?) {
        if (bitmap != null) {
            views.setImageViewBitmap(R.id.widget_album_art, bitmap)
        } else {
            views.setImageViewResource(R.id.widget_album_art, R.mipmap.ic_launcher)
        }
    }

    override fun applyBackgroundArt(views: RemoteViews, bitmap: Bitmap?) {
        if (bitmap != null) {
            views.setImageViewBitmap(R.id.widget_background_view, bitmap)
        }
    }

    override fun getAlbumArtSize(context: Context): Int =
        (96 * context.resources.displayMetrics.density).toInt()
}
