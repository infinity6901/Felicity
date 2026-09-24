package app.simple.felicity.widget

import android.appwidget.AppWidgetManager
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.widget.RemoteViews
import app.simple.felicity.R

/**
 * The smallest widget style Felicity offers — just the blurred album art filling a 2×1
 * cell with a single centered play/pause button. No text, no prev/next: for users who
 * just want a glanceable "something is playing, tap to toggle" tile.
 *
 * Tapping anywhere that isn't the play/pause button opens the app, via the background
 * view's click intent — there's no separate small art thumbnail to wire up here, so
 * [applyAlbumArt] is a no-op and [loadAlbumArt] is short-circuited to avoid fetching a
 * bitmap that would never be displayed.
 *
 * @author Hamza417
 */
class MinimalWidgetProvider : BaseWidgetProvider() {

    override fun getLayoutId(): Int = R.layout.widget_minimal

    override fun applyStaticViews(scope: WidgetViewScope) {
        val views = scope.views
        val context = scope.context

        val playPauseRes = if (scope.isPlaying) R.drawable.ic_pause else R.drawable.ic_play
        views.setImageViewResource(R.id.widget_play_pause, playPauseRes)
        views.setInt(R.id.widget_play_pause, "setColorFilter", Color.WHITE)
        views.setInt(R.id.widget_play_pause, "setBackgroundColor", Color.TRANSPARENT)

        views.setOnClickPendingIntent(
                R.id.widget_play_pause,
                scope.pendingIntentBuilder(context, WidgetActionReceiver.ACTION_PLAY_PAUSE)
        )
        views.setOnClickPendingIntent(
                R.id.widget_background_view,
                scope.pendingIntentBuilder(context, WidgetActionReceiver.ACTION_OPEN_APP)
        )
    }

    /** No dedicated art thumbnail in this layout — the blurred background carries the art instead. */
    override fun applyAlbumArt(views: RemoteViews, bitmap: Bitmap?) {
        // Intentionally empty.
    }

    /** Skip the redundant square-thumbnail fetch entirely; only the background bitmap is ever shown. */
    override suspend fun loadAlbumArt(context: Context, songId: Long): Bitmap? = null

    override fun applyBackgroundArt(views: RemoteViews, bitmap: Bitmap?) {
        if (bitmap != null) {
            views.setImageViewBitmap(R.id.widget_background_view, bitmap)
        }
    }
}
