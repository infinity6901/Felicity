package app.simple.felicity.widget

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.text.SpannableString
import android.text.Spanned
import android.text.style.BackgroundColorSpan
import android.text.style.ForegroundColorSpan
import androidx.core.content.edit
import app.simple.felicity.manager.SharedPreferences
import app.simple.felicity.theme.managers.ThemeManager

/**
 * Look of the lyric lines on the lyrics widgets. Home-screen widgets are RemoteViews, so they
 * cannot animate word by word; what they can do is show any version of the lyrics and colour,
 * size and highlight the current line.
 */
object WidgetLyricStyle {

    const val VERSION_ORIGINAL = 0
    const val VERSION_ROMANIZED = 1
    const val VERSION_TRANSLATED = 2

    const val COLOR_DEFAULT = 0
    const val COLOR_ACCENT = 1
    const val COLOR_WHITE = 2

    const val SIZE_MIN = 12f
    const val SIZE_MAX = 32f
    const val SIZE_DEFAULT = 20f
    const val BOX_MIN = 10f
    const val BOX_MAX = 80f
    const val BOX_DEFAULT = 30f

    const val KEY_VERSION = "widget_lyric_version"
    const val KEY_NEIGHBOURS = "widget_lyric_neighbours"
    const val KEY_SIZE = "widget_lyric_size"
    const val KEY_COLOR = "widget_lyric_color"
    const val KEY_BOX = "widget_lyric_box"
    const val KEY_BOX_OPACITY = "widget_lyric_box_opacity"

    private val prefs get() = SharedPreferences.getSharedPreferences()

    fun getVersion(): Int = prefs.getInt(KEY_VERSION, VERSION_ORIGINAL)
    fun setVersion(v: Int) = prefs.edit { putInt(KEY_VERSION, v) }

    fun isShowNeighbours(): Boolean = prefs.getBoolean(KEY_NEIGHBOURS, true)
    fun setShowNeighbours(v: Boolean) = prefs.edit { putBoolean(KEY_NEIGHBOURS, v) }

    fun getSize(): Float = prefs.getFloat(KEY_SIZE, SIZE_DEFAULT)
    fun setSize(v: Float) = prefs.edit { putFloat(KEY_SIZE, v.coerceIn(SIZE_MIN, SIZE_MAX)) }

    fun getColorMode(): Int = prefs.getInt(KEY_COLOR, COLOR_DEFAULT)
    fun setColorMode(v: Int) = prefs.edit { putInt(KEY_COLOR, v) }

    fun isBox(): Boolean = prefs.getBoolean(KEY_BOX, false)
    fun setBox(v: Boolean) = prefs.edit { putBoolean(KEY_BOX, v) }

    fun getBoxOpacity(): Float = prefs.getFloat(KEY_BOX_OPACITY, BOX_DEFAULT)
    fun setBoxOpacity(v: Float) = prefs.edit { putFloat(KEY_BOX_OPACITY, v.coerceIn(BOX_MIN, BOX_MAX)) }

    /** The current line with the chosen colour and highlight box applied. */
    fun styleCurrent(text: String): CharSequence {
        val mode = getColorMode()
        val box = isBox()
        if (text.isEmpty() || (mode == COLOR_DEFAULT && !box)) return text
        val accent = runCatching { ThemeManager.accent.primaryAccentColor }.getOrDefault(Color.WHITE)
        val span = SpannableString(text)
        when (mode) {
            COLOR_ACCENT -> span.setSpan(ForegroundColorSpan(accent), 0, text.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            COLOR_WHITE -> span.setSpan(ForegroundColorSpan(Color.WHITE), 0, text.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
        if (box) {
            val alpha = (255 * getBoxOpacity() / 100f).toInt().coerceIn(0, 255)
            val base = if (mode == COLOR_ACCENT) Color.WHITE else accent
            val boxColor = Color.argb(alpha, Color.red(base), Color.green(base), Color.blue(base))
            span.setSpan(BackgroundColorSpan(boxColor), 0, text.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
        return span
    }

    /** Redraws every lyrics widget with the current style. */
    fun refresh(context: Context) {
        val app = context.applicationContext
        // The chosen lyric version changes the text, so the updater has to recompute the lines too.
        runCatching {
            dagger.hilt.android.EntryPointAccessors.fromApplication(app, WidgetBootstrap.Updaters::class.java)
                .lyricsWidgetUpdater().invalidate()
        }
        LyricsWidgetUpdater.PROVIDERS.forEach {
            runCatching {
                app.sendBroadcast(Intent(LyricsWidgetProvider.ACTION_LYRICS_UPDATE).setComponent(ComponentName(app, it)))
            }
        }
    }
}
