package app.simple.felicity.widget

import android.graphics.Bitmap
import android.graphics.Color
import android.widget.RemoteViews
import app.simple.felicity.R

/**
 * A compact 4×1 "now playing bar" widget — a small thumbnail, title/artist, and the
 * three transport controls all in a single row.
 *
 * Built on the exact same template [FelicityWidgetProvider] uses; only the layout
 * ([R.layout.widget_compact]) and view wiring differ. See [BaseWidgetProvider]'s kdoc
 * for the two-phase update contract this fulfills.
 *
 * @author Hamza417
 */
class CompactWidgetProvider : BaseWidgetProvider() {

    override fun getLayoutId(): Int = R.layout.widget_compact

    override fun applyStaticViews(scope: WidgetViewScope) {
        val views = scope.views
        val context = scope.context

        views.setTextViewText(R.id.widget_title, scope.title)
        views.setTextViewText(R.id.widget_artist, scope.artist)
        views.setTextColor(R.id.widget_title, Color.WHITE)
        views.setTextColor(R.id.widget_artist, Color.LTGRAY)

        val playPauseRes = if (scope.isPlaying) R.drawable.ic_pause else R.drawable.ic_play
        views.setImageViewResource(R.id.widget_play_pause, playPauseRes)

        views.setInt(R.id.widget_prev, "setColorFilter", Color.WHITE)
        views.setInt(R.id.widget_play_pause, "setColorFilter", Color.WHITE)
        views.setInt(R.id.widget_next, "setColorFilter", Color.WHITE)

        views.setInt(R.id.widget_prev, "setBackgroundColor", Color.TRANSPARENT)
        views.setInt(R.id.widget_play_pause, "setBackgroundColor", Color.TRANSPARENT)
        views.setInt(R.id.widget_next, "setBackgroundColor", Color.TRANSPARENT)

        views.setOnClickPendingIntent(
                R.id.widget_play_pause,
                scope.pendingIntentBuilder(context, WidgetActionReceiver.ACTION_PLAY_PAUSE)
        )
        views.setOnClickPendingIntent(
                R.id.widget_prev,
                scope.pendingIntentBuilder(context, WidgetActionReceiver.ACTION_PREV)
        )
        views.setOnClickPendingIntent(
                R.id.widget_next,
                scope.pendingIntentBuilder(context, WidgetActionReceiver.ACTION_NEXT)
        )

        // The row has no dedicated background view to tap outside of art/text/buttons —
        // opening the app from the album art thumbnail is the natural spot for a bar layout.
        views.setOnClickPendingIntent(
                R.id.widget_album_art,
                scope.pendingIntentBuilder(context, WidgetActionReceiver.ACTION_OPEN_APP)
        )
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
}
