package app.simple.felicity.widget

import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import androidx.core.content.edit
import app.simple.felicity.manager.SharedPreferences

/**
 * How see-through the home-screen frosted-glass widgets are. Each widget style can have its own
 * opacity, or all of them can share one value ([isShared]).
 */
object WidgetGlass {

    const val MIN_PERCENT = 10f
    const val MAX_PERCENT = 100f
    const val DEFAULT_PERCENT = 100f

    const val KEY_SHARED = "widget_glass_shared"
    private const val KEY_ALL = "widget_glass_all"
    private const val KEY_TRANSPARENCY_ALL = "widget_glass_transparency_all"
    const val DEFAULT_TRANSPARENCY = 0f
    const val MAX_TRANSPARENCY = 90f

    enum class Kind(val key: String, val provider: Class<out BaseWidgetProvider>) {
        BAR("widget_glass_bar", GlassBarWidgetProvider::class.java),
        CARD("widget_glass_card", GlassCardWidgetProvider::class.java),
        PLAYER("widget_glass_player", GlassPlayerWidgetProvider::class.java),
        LYRICS("widget_glass_lyrics", GlassLyricsWidgetProvider::class.java),
        UP_NEXT("widget_glass_up_next", GlassUpNextWidgetProvider::class.java),
    }

    private val prefs get() = SharedPreferences.getSharedPreferences()

    fun isShared(): Boolean = prefs.getBoolean(KEY_SHARED, true)
    fun setShared(value: Boolean) = prefs.edit { putBoolean(KEY_SHARED, value) }

    fun getAllPercent(): Float = prefs.getFloat(KEY_ALL, DEFAULT_PERCENT)
    fun setAllPercent(value: Float) = prefs.edit { putFloat(KEY_ALL, value.coerceIn(MIN_PERCENT, MAX_PERCENT)) }

    fun getPercent(kind: Kind): Float = prefs.getFloat(kind.key, DEFAULT_PERCENT)
    fun setPercent(kind: Kind, value: Float) = prefs.edit { putFloat(kind.key, value.coerceIn(MIN_PERCENT, MAX_PERCENT)) }

    fun getAllTransparency(): Float = prefs.getFloat(KEY_TRANSPARENCY_ALL, DEFAULT_TRANSPARENCY)
    fun setAllTransparency(value: Float) = prefs.edit { putFloat(KEY_TRANSPARENCY_ALL, value.coerceIn(0f, MAX_TRANSPARENCY)) }

    fun getTransparency(kind: Kind): Float = prefs.getFloat(kind.key + "_transparency", DEFAULT_TRANSPARENCY)
    fun setTransparency(kind: Kind, value: Float) =
        prefs.edit { putFloat(kind.key + "_transparency", value.coerceIn(0f, MAX_TRANSPARENCY)) }

    /** How see-through (0..0.9) the blurred artwork of the widget class [provider] is. */
    fun transparencyFor(provider: Class<*>): Float {
        val percent = if (isShared()) {
            getAllTransparency()
        } else {
            Kind.entries.firstOrNull { it.provider == provider }?.let { getTransparency(it) } ?: DEFAULT_TRANSPARENCY
        }
        return (percent / 100f).coerceIn(0f, MAX_TRANSPARENCY / 100f)
    }

    /** Opacity (0.1..1) to paint the glass of the widget class [provider] with. */
    fun opacityFor(provider: Class<*>): Float {
        val percent = if (isShared()) {
            getAllPercent()
        } else {
            Kind.entries.firstOrNull { it.provider == provider }?.let { getPercent(it) } ?: DEFAULT_PERCENT
        }
        return (percent / 100f).coerceIn(MIN_PERCENT / 100f, 1f)
    }

    /** Asks every glass widget on the home screen to redraw with the current opacity. */
    fun refreshWidgets(context: Context) {
        val app = context.applicationContext
        Kind.entries.forEach { kind ->
            runCatching {
                app.sendBroadcast(Intent(AppWidgetManager.ACTION_APPWIDGET_UPDATE).apply {
                    component = ComponentName(app, kind.provider)
                })
            }
        }
    }
}
