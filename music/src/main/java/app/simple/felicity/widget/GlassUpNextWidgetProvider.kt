package app.simple.felicity.widget

import app.simple.felicity.R

/**
 * Frosted-glass take on [UpNextWidgetProvider]: the same current song and next three queue
 * entries, in white on a glass pane built from the album art.
 *
 * @author Hamza417
 */
class GlassUpNextWidgetProvider : UpNextWidgetProvider() {
    override fun getLayoutId(): Int = R.layout.widget_glass_up_next
    override val frostedBackground: Boolean = true
}
