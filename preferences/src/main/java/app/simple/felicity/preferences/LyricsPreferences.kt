package app.simple.felicity.preferences

import androidx.core.content.edit
import app.simple.felicity.manager.SharedPreferences

object LyricsPreferences {

    const val LRC_ALIGNMENT = "lyrics_lrc_alignment"
    const val LRC_TEXT_SIZE = "lyrics_lrc_text_size"
    const val AUTO_DOWNLOAD_LYRICS = "lyrics_auto_download"
    const val ALBUM_ART_BACKGROUND = "lyrics_album_art_background"
    const val AUTO_ROMANIZE_LYRICS = "lyrics_auto_romanize"
    const val STUDIO_TRANSLATION = "lyrics_studio_translation"
    const val STUDIO_GLOW = "lyrics_studio_glow"
    const val STUDIO_INACTIVE_ALPHA = "lyrics_studio_inactive_alpha"
    const val STUDIO_LINE_SPACING = "lyrics_studio_line_spacing"
    const val STUDIO_GLOW_RADIUS = "lyrics_studio_glow_radius"
    const val STUDIO_GLOW_INTENSITY = "lyrics_studio_glow_intensity"
    const val STUDIO_PROGRESSIVE_FILL = "lyrics_studio_progressive_fill"
    const val STUDIO_GLASS_BACKGROUND = "lyrics_studio_glass_background"
    const val STUDIO_GLASS_ALPHA = "lyrics_studio_glass_alpha"

    const val STUDIO_PRESET = "lyrics_studio_preset"
    const val STUDIO_FONT = "lyrics_studio_font"
    const val STUDIO_FONT_WEIGHT = "lyrics_studio_font_weight"
    const val STUDIO_INACTIVE_SCALE = "lyrics_studio_inactive_scale"
    const val STUDIO_ACTIVE_COLOR_MODE = "lyrics_studio_active_color_mode"
    const val STUDIO_CUSTOM_COLOR = "lyrics_studio_custom_color"
    const val STUDIO_EDGE_BLUR = "lyrics_studio_edge_blur"
    const val STUDIO_ACTIVE_SCALE = "lyrics_studio_active_scale"
    const val STUDIO_BACKGROUND_DIM = "lyrics_studio_background_dim"
    const val STUDIO_BACKGROUND = "lyrics_studio_background"

    const val LEFT = 0
    const val CENTER = 1
    const val RIGHT = 2

    /** Active-line colour sources. [COLOR_ACCENT] is the long-standing default behaviour. */
    const val COLOR_ACCENT = 0
    const val COLOR_WHITE = 1
    const val COLOR_ARTWORK = 2
    const val COLOR_CUSTOM = 3

    /** Lyrics panel backgrounds. [BACKGROUND_ORIGINAL] follows the "album art background" switch. */
    const val BACKGROUND_ORIGINAL = 0
    const val BACKGROUND_SOLID_DARK = 1
    const val BACKGROUND_AMOLED = 2
    const val BACKGROUND_GRADIENT = 3
    const val BACKGROUND_BLURRED_ARTWORK = 4
    const val BACKGROUND_CINEMATIC = 5

    /** Every key Lyrics Studio owns, used for "did something in Studio change?" checks. */
    val STUDIO_KEYS: Set<String> = setOf(
            LRC_ALIGNMENT, LRC_TEXT_SIZE, ALBUM_ART_BACKGROUND, STUDIO_TRANSLATION, STUDIO_GLOW,
            STUDIO_INACTIVE_ALPHA, STUDIO_LINE_SPACING, STUDIO_GLOW_RADIUS, STUDIO_GLOW_INTENSITY,
            STUDIO_PROGRESSIVE_FILL, STUDIO_GLASS_BACKGROUND, STUDIO_GLASS_ALPHA, STUDIO_PRESET,
            STUDIO_FONT, STUDIO_FONT_WEIGHT, STUDIO_INACTIVE_SCALE, STUDIO_ACTIVE_COLOR_MODE,
            STUDIO_CUSTOM_COLOR, STUDIO_EDGE_BLUR, STUDIO_ACTIVE_SCALE, STUDIO_BACKGROUND_DIM,
            STUDIO_BACKGROUND
    )


    fun setLrcAlignment(value: Int) {
        SharedPreferences.getSharedPreferences().edit {
            putInt(LRC_ALIGNMENT, value)
        }
    }

    fun getLrcAlignment(): Int {
        return SharedPreferences.getSharedPreferences()
            .getInt(LRC_ALIGNMENT, CENTER)
    }

    fun setLrcTextSize(value: Float) {
        SharedPreferences.getSharedPreferences().edit {
            putFloat(LRC_TEXT_SIZE, value)
        }
    }

    fun getLrcTextSize(): Float {
        return SharedPreferences.getSharedPreferences()
            .getFloat(LRC_TEXT_SIZE, 16f)
    }

    fun setAutoDownloadLyrics(enabled: Boolean) {
        SharedPreferences.getSharedPreferences().edit {
            putBoolean(AUTO_DOWNLOAD_LYRICS, enabled)
        }
    }

    fun isAutoDownloadLyrics(): Boolean {
        return SharedPreferences.getSharedPreferences()
            .getBoolean(AUTO_DOWNLOAD_LYRICS, false)
    }

    fun setAutoRomanizeLyrics(enabled: Boolean) {
        SharedPreferences.getSharedPreferences().edit {
            putBoolean(AUTO_ROMANIZE_LYRICS, enabled)
        }
    }

    fun isAutoRomanizeLyrics(): Boolean {
        return SharedPreferences.getSharedPreferences()
            .getBoolean(AUTO_ROMANIZE_LYRICS, false)
    }

    fun isStudioTranslationEnabled() = SharedPreferences.getSharedPreferences().getBoolean(STUDIO_TRANSLATION, false)
    fun setStudioTranslationEnabled(enabled: Boolean) { SharedPreferences.getSharedPreferences().edit { putBoolean(STUDIO_TRANSLATION, enabled) } }
    fun isStudioGlowEnabled() = SharedPreferences.getSharedPreferences().getBoolean(STUDIO_GLOW, false)
    fun setStudioGlowEnabled(enabled: Boolean) { SharedPreferences.getSharedPreferences().edit { putBoolean(STUDIO_GLOW, enabled) } }
    fun getStudioInactiveAlpha() = SharedPreferences.getSharedPreferences().getFloat(STUDIO_INACTIVE_ALPHA, 0.55f)
    fun setStudioInactiveAlpha(value: Float) { SharedPreferences.getSharedPreferences().edit { putFloat(STUDIO_INACTIVE_ALPHA, value.coerceIn(0.15f, 0.9f)) } }
    fun getStudioLineSpacing() = SharedPreferences.getSharedPreferences().getFloat(STUDIO_LINE_SPACING, 8f)
    fun getStudioGlowRadius() = SharedPreferences.getSharedPreferences().getFloat(STUDIO_GLOW_RADIUS, 12f)
    fun setStudioGlowRadius(value: Float) { SharedPreferences.getSharedPreferences().edit { putFloat(STUDIO_GLOW_RADIUS, value.coerceIn(0f, 28f)) } }
    fun getStudioGlowIntensity() = SharedPreferences.getSharedPreferences().getFloat(STUDIO_GLOW_INTENSITY, 0.75f)
    fun setStudioGlowIntensity(value: Float) { SharedPreferences.getSharedPreferences().edit { putFloat(STUDIO_GLOW_INTENSITY, value.coerceIn(0f, 1f)) } }
    fun isStudioProgressiveFillEnabled() = SharedPreferences.getSharedPreferences().getBoolean(STUDIO_PROGRESSIVE_FILL, true)
    fun setStudioProgressiveFillEnabled(value: Boolean) { SharedPreferences.getSharedPreferences().edit { putBoolean(STUDIO_PROGRESSIVE_FILL, value) } }
    fun isStudioGlassBackgroundEnabled() = SharedPreferences.getSharedPreferences().getBoolean(STUDIO_GLASS_BACKGROUND, false)
    fun setStudioGlassBackgroundEnabled(value: Boolean) { SharedPreferences.getSharedPreferences().edit { putBoolean(STUDIO_GLASS_BACKGROUND, value) } }
    fun getStudioGlassAlpha() = SharedPreferences.getSharedPreferences().getFloat(STUDIO_GLASS_ALPHA, 0.16f)
    fun setStudioGlassAlpha(value: Float) { SharedPreferences.getSharedPreferences().edit { putFloat(STUDIO_GLASS_ALPHA, value.coerceIn(0f, 0.45f)) } }
    fun setStudioLineSpacing(value: Float) { SharedPreferences.getSharedPreferences().edit { putFloat(STUDIO_LINE_SPACING, value.coerceIn(0f, 30f)) } }

    fun getStudioPreset() = SharedPreferences.getSharedPreferences().getInt(STUDIO_PRESET, 0)
    fun setStudioPreset(value: Int) { SharedPreferences.getSharedPreferences().edit { putInt(STUDIO_PRESET, value) } }
    fun getStudioFont() = SharedPreferences.getSharedPreferences().getString(STUDIO_FONT, "auto") ?: "auto"
    fun setStudioFont(value: String) { SharedPreferences.getSharedPreferences().edit { putString(STUDIO_FONT, value) } }
    fun getStudioFontWeight() = SharedPreferences.getSharedPreferences().getInt(STUDIO_FONT_WEIGHT, 900)
    fun setStudioFontWeight(value: Int) { SharedPreferences.getSharedPreferences().edit { putInt(STUDIO_FONT_WEIGHT, value.coerceIn(100, 900)) } }
    fun getStudioInactiveScale() = SharedPreferences.getSharedPreferences().getFloat(STUDIO_INACTIVE_SCALE, 1f)
    fun setStudioInactiveScale(value: Float) { SharedPreferences.getSharedPreferences().edit { putFloat(STUDIO_INACTIVE_SCALE, value.coerceIn(0.6f, 1f)) } }
    fun getStudioActiveColorMode() = SharedPreferences.getSharedPreferences().getInt(STUDIO_ACTIVE_COLOR_MODE, COLOR_ACCENT)
    fun setStudioActiveColorMode(value: Int) { SharedPreferences.getSharedPreferences().edit { putInt(STUDIO_ACTIVE_COLOR_MODE, value) } }
    fun getStudioCustomColor() = SharedPreferences.getSharedPreferences().getInt(STUDIO_CUSTOM_COLOR, 0xFF64D2FF.toInt())
    fun setStudioCustomColor(value: Int) { SharedPreferences.getSharedPreferences().edit { putInt(STUDIO_CUSTOM_COLOR, value) } }
    fun getStudioEdgeBlur() = SharedPreferences.getSharedPreferences().getFloat(STUDIO_EDGE_BLUR, 1f)
    fun setStudioEdgeBlur(value: Float) { SharedPreferences.getSharedPreferences().edit { putFloat(STUDIO_EDGE_BLUR, value.coerceIn(0f, 2f)) } }
    fun getStudioActiveScale() = SharedPreferences.getSharedPreferences().getFloat(STUDIO_ACTIVE_SCALE, 1f)
    fun setStudioActiveScale(value: Float) { SharedPreferences.getSharedPreferences().edit { putFloat(STUDIO_ACTIVE_SCALE, value.coerceIn(1f, 1.2f)) } }
    fun getStudioBackgroundDim() = SharedPreferences.getSharedPreferences().getFloat(STUDIO_BACKGROUND_DIM, 0f)
    fun setStudioBackgroundDim(value: Float) { SharedPreferences.getSharedPreferences().edit { putFloat(STUDIO_BACKGROUND_DIM, value.coerceIn(0f, 0.75f)) } }
    fun getStudioBackground() = SharedPreferences.getSharedPreferences().getInt(STUDIO_BACKGROUND, BACKGROUND_ORIGINAL)
    fun setStudioBackground(value: Int) { SharedPreferences.getSharedPreferences().edit { putInt(STUDIO_BACKGROUND, value.coerceIn(0, 5)) } }

    /**
     * Restores every Lyrics Studio setting (and the text size / alignment it controls) to its
     * default. Keys are removed rather than overwritten so the defaults live in one place.
     * Translation is deliberately included; the album-art switch from the lyrics menu is not.
     */
    fun resetStudio() {
        SharedPreferences.getSharedPreferences().edit {
            STUDIO_KEYS.filter { it != ALBUM_ART_BACKGROUND }.forEach { remove(it) }
        }
    }

    fun setAlbumArtBackground(enabled: Boolean) {
        SharedPreferences.getSharedPreferences().edit {
            putBoolean(ALBUM_ART_BACKGROUND, enabled)
        }
    }

    fun isAlbumArtBackground(): Boolean {
        return SharedPreferences.getSharedPreferences()
            .getBoolean(ALBUM_ART_BACKGROUND, true)
    }
}