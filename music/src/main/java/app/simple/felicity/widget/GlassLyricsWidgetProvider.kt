package app.simple.felicity.widget

import app.simple.felicity.R

/**
 * Frosted-glass take on [LyricsWidgetProvider]: same lyric lines and controls, but drawn
 * in white on a real frosted-glass pane built from the album art (see [GlassRenderer]).
 *
 * @author Hamza417
 */
class GlassLyricsWidgetProvider : LyricsWidgetProvider() {
    override fun getLayoutId(): Int = R.layout.widget_glass_lyrics
    override val usesBackgroundArt: Boolean = true
    override val frostedBackground: Boolean = true
}
