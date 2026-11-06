package app.simple.felicity.widget

import android.content.Context
import android.graphics.Bitmap
import android.widget.RemoteViews
import app.simple.felicity.R

/**
 * Frosted-glass 4×2 player card: a thumbnail, title / artist and previous · play/pause · next
 * buttons on a real frosted pane (blur, wash, grain, sheen, rim) built from the album art by
 * [GlassRenderer]. The play icon takes a shade of the artwork's colour.
 *
 * @author Hamza417
 */
class GlassPlayerWidgetProvider : BaseWidgetProvider() {

    override fun getLayoutId(): Int = R.layout.widget_glass_player

    override val frostedBackground: Boolean = true

    override fun applyAccent(views: RemoteViews, accent: Int) {
        views.setInt(R.id.widget_play_pause, "setColorFilter", GlassRenderer.iconShade(accent))
    }

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

    override fun applyBackgroundArt(views: RemoteViews, bitmap: Bitmap?) {
        if (bitmap != null) {
            views.setImageViewBitmap(R.id.widget_background_view, bitmap)
        }
    }

    /** Bigger source bitmap so the 96dp art stays sharp on high-density screens. */
    override fun getAlbumArtSize(context: Context): Int =
        (176 * context.resources.displayMetrics.density).toInt()
}
