package app.simple.felicity.widget

import app.simple.felicity.R

/**
 * Frosted-glass take on [LyricsWidgetProvider]: same lyric lines and controls, but drawn
 * in white over a blurred, darkened copy of the album art with a translucent glass panel.
 *
 * @author Hamza417
 */
class GlassLyricsWidgetProvider : LyricsWidgetProvider() {
    override fun getLayoutId(): Int = R.layout.widget_glass_lyrics
    override val usesBackgroundArt: Boolean = true
}
