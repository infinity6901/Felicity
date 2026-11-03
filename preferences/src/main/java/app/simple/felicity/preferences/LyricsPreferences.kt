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

    const val LEFT = 0
    const val CENTER = 1
    const val RIGHT = 2


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