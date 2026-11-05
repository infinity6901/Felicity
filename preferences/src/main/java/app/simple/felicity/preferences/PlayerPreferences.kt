package app.simple.felicity.preferences

import androidx.core.content.edit
import app.simple.felicity.manager.SharedPreferences

/**
 * Persisted preferences that control the music player's runtime behavior,
 * including repeat mode, visualizer visibility, and visualizer rendering mode.
 *
 * @author Hamza417
 */
object PlayerPreferences {

    private const val SHOW_LYRICS = "show_lyrics"
    const val CAPTION_TRANSITION = "caption_transition"
    const val CAPTION_BOX = "caption_box"
    const val CAPTION_BOX_OPACITY = "caption_box_opacity"
    const val CAPTION_GLOW = "caption_glow"
    const val CAPTION_TEXT_SIZE = "caption_text_size"
    const val CAPTION_COLOR_MODE = "caption_color_mode"
    const val CAPTION_PILL_OPACITY = "caption_pill_opacity"

    const val REPEAT_MODE = "repeat_mode"

    /** SharedPreferences key for the visualizer enabled/disabled toggle. */
    const val VISUALIZER_ENABLED = "visualizer_enabled"

    /** SharedPreferences key for the waveform seekbar display mode (int, maps to WaveformSeekbar mode constants). */
    const val WAVEFORM_MODE = "waveform_mode_int"

    const val PCM_INFO_MODE = "pcm_info_mode"

    const val PCM_INFO_MODE_SAMPLING_RATE = 0
    const val PCM_INFO_MODE_BITRATE = 1
    const val PCM_INFO_MODE_QUALITY = 2

    fun setRepeatMode(value: Int) {
        SharedPreferences.getSharedPreferences().edit { putInt(REPEAT_MODE, value) }
    }

    fun getRepeatMode(): Int {
        return SharedPreferences.getSharedPreferences().getInt(REPEAT_MODE, 0)
    }

    /**
     * Persists whether the visualizer overlay should be shown in the player.
     *
     * @param value `true` to show the visualizer, `false` to hide it.
     */
    fun setVisualizerEnabled(value: Boolean) {
        SharedPreferences.getSharedPreferences().edit { putBoolean(VISUALIZER_ENABLED, value) }
    }

    /**
     * Returns whether the visualizer overlay is currently enabled.
     * Defaults to `true` if the preference has not been set yet.
     */
    fun isVisualizerEnabled(): Boolean {
        return SharedPreferences.getSharedPreferences().getBoolean(VISUALIZER_ENABLED, true)
    }

    /**
     * Persists the waveform seekbar display mode.
     * Use the mode constants defined in [app.simple.felicity.decorations.seekbars.WaveformSeekbar].
     *
     * @param value one of `WAVEFORM_MODE_HALF`, `WAVEFORM_MODE_FULL`, or `WAVEFORM_MODE_REFLECTION`
     */
    fun setWaveformMode(value: Int) {
        SharedPreferences.getSharedPreferences().edit { putInt(WAVEFORM_MODE, value) }
    }

    /**
     * Returns the persisted waveform seekbar display mode.
     * Defaults to `WAVEFORM_MODE_HALF` (0) if the preference has not been set yet.
     */
    fun getWaveformMode(): Int {
        return SharedPreferences.getSharedPreferences().getInt(WAVEFORM_MODE, 0)
    }

    fun setPcmInfoMode(value: Int) {
        SharedPreferences.getSharedPreferences().edit { putInt(PCM_INFO_MODE, value) }
    }

    fun getPcmInfoMode(): Int {
        return SharedPreferences.getSharedPreferences().getInt(PCM_INFO_MODE, PCM_INFO_MODE_QUALITY)
    }

    fun setShowLyrics(value: Boolean) {
        SharedPreferences.getSharedPreferences().edit { putBoolean(SHOW_LYRICS, value) }
    }

    fun isShowLyrics(): Boolean {
        return SharedPreferences.getSharedPreferences().getBoolean(SHOW_LYRICS, true)
    }

    // --- Player-screen lyric caption style ---
    /** 0 none, 1 fade, 2 slide up, 3 pop. */
    fun getCaptionTransition(): Int = SharedPreferences.getSharedPreferences().getInt(CAPTION_TRANSITION, 0)
    fun setCaptionTransition(v: Int) = SharedPreferences.getSharedPreferences().edit { putInt(CAPTION_TRANSITION, v) }
    fun isCaptionBox(): Boolean = SharedPreferences.getSharedPreferences().getBoolean(CAPTION_BOX, false)
    fun setCaptionBox(v: Boolean) = SharedPreferences.getSharedPreferences().edit { putBoolean(CAPTION_BOX, v) }
    /** Opacity of the moving highlight box, 0..100. */
    fun getCaptionBoxOpacity(): Int = SharedPreferences.getSharedPreferences().getInt(CAPTION_BOX_OPACITY, 35)
    fun setCaptionBoxOpacity(v: Int) = SharedPreferences.getSharedPreferences().edit { putInt(CAPTION_BOX_OPACITY, v) }
    fun isCaptionGlow(): Boolean = SharedPreferences.getSharedPreferences().getBoolean(CAPTION_GLOW, false)
    fun setCaptionGlow(v: Boolean) = SharedPreferences.getSharedPreferences().edit { putBoolean(CAPTION_GLOW, v) }
    fun getCaptionTextSize(): Float = SharedPreferences.getSharedPreferences().getFloat(CAPTION_TEXT_SIZE, 16f)
    fun setCaptionTextSize(v: Float) = SharedPreferences.getSharedPreferences().edit { putFloat(CAPTION_TEXT_SIZE, v) }
    /** 0 theme, 1 accent, 2 white. */
    fun getCaptionColorMode(): Int = SharedPreferences.getSharedPreferences().getInt(CAPTION_COLOR_MODE, 0)
    fun setCaptionColorMode(v: Int) = SharedPreferences.getSharedPreferences().edit { putInt(CAPTION_COLOR_MODE, v) }
    /** Opacity of the rounded backdrop behind the line, 0..100 (70 matches the old look). */
    fun getCaptionPillOpacity(): Int = SharedPreferences.getSharedPreferences().getInt(CAPTION_PILL_OPACITY, 70)
    fun setCaptionPillOpacity(v: Int) = SharedPreferences.getSharedPreferences().edit { putInt(CAPTION_PILL_OPACITY, v) }
}
