package app.simple.felicity.widget

import android.appwidget.AppWidgetManager
import android.content.Context
import android.graphics.Bitmap
import android.widget.RemoteViews
import app.simple.felicity.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Vinyl 2×2: a record drawn by [GlassRenderer.vinylDisc] with the album art as its label. The rim
 * glows in the artwork's colour while playing. Tap the disc to open the app, the corner button to
 * play or pause.
 *
 * @author Hamza417
 */
class VinylWidgetProvider : BaseWidgetProvider() {

    override fun getLayoutId(): Int = R.layout.widget_vinyl

    override fun applyStaticViews(scope: WidgetViewScope) {
        val views = scope.views
        val context = scope.context
        views.setImageViewResource(
                R.id.widget_play_pause,
                if (scope.isPlaying) R.drawable.ic_pause else R.drawable.ic_play
        )
        views.setOnClickPendingIntent(R.id.widget_play_pause, scope.pendingIntentBuilder(context, WidgetActionReceiver.ACTION_PLAY_PAUSE))
        views.setOnClickPendingIntent(R.id.widget_album_art, scope.pendingIntentBuilder(context, WidgetActionReceiver.ACTION_OPEN_APP))
    }

    override fun applyAccent(views: RemoteViews, accent: Int) {
        views.setInt(R.id.widget_play_pause, "setColorFilter", GlassRenderer.iconShade(accent))
    }

    override suspend fun loadAlbumArt(context: Context, songId: Long): Bitmap? = withContext(Dispatchers.IO) {
        val density = context.resources.displayMetrics.density
        val side = if (lastWidgetSize.first > 8) minOf(lastWidgetSize.first, lastWidgetSize.second) else (150 * density).toInt()
        val art = loadRawArt(context, songId, 300)
        accentColor = GlassRenderer.accentOf(art)
        GlassRenderer.vinylDisc(art, side, WidgetStatePrefs.isPlaying(context), accentColor)
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
}
