package app.simple.felicity.dialogs.lyrics

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import app.simple.felicity.decorations.constants.TypeFaceConstants
import app.simple.felicity.decorations.lrc.model.LrcData
import app.simple.felicity.decorations.lrc.model.LrcEntry
import app.simple.felicity.decorations.lrc.model.WordEntry
import app.simple.felicity.decorations.lrc.view.FelicityLrcView
import app.simple.felicity.decorations.typeface.TypeFace
import app.simple.felicity.preferences.LyricsPreferences
import app.simple.felicity.theme.managers.ThemeManager

/**
 * Single source of truth for how Lyrics Studio settings become pixels.
 *
 * Both the Lyrics panel and the live preview inside Lyrics Studio call [apply], so what the user
 * sees while editing is exactly what the panel renders. Everything here reads the persisted
 * [LyricsPreferences] values; nothing keeps state of its own except the artwork colour of the
 * current track.
 */
object LyricsStyle {

    class Option<T>(val label: String, val value: T)

    val sizeOptions = listOf(
            Option("Compact", 14f), Option("Standard", 16f),
            Option("Large", 20f), Option("Extra Large", 24f))

    val weightOptions = listOf(
            Option("Medium", 500), Option("Semi-Bold", 600), Option("Bold", 700),
            Option("Extra Bold", 800), Option("Black", 900))

    val alignmentOptions = listOf(
            Option("Start", LyricsPreferences.LEFT), Option("Center", LyricsPreferences.CENTER),
            Option("End", LyricsPreferences.RIGHT))

    val spacingOptions = listOf(
            Option("Compact", 2f), Option("Standard", 8f), Option("Relaxed", 16f), Option("Spacious", 24f))

    val inactiveScaleOptions = listOf(Option("80%", 0.8f), Option("90%", 0.9f), Option("100%", 1f))

    val inactiveOpacityOptions = listOf(
            Option("25%", 0.25f), Option("40%", 0.40f), Option("55%", 0.55f), Option("70%", 0.70f))

    val fontOptions = listOf(
            Option("Default", TypeFaceConstants.AUTO), Option("Inter", TypeFaceConstants.INTER),
            Option("Nunito", TypeFaceConstants.NUNITO), Option("Jost", TypeFaceConstants.JOST),
            Option("Work Sans", TypeFaceConstants.WORK_SANS), Option("Noto Sans", TypeFaceConstants.NOTOSANS),
            Option("Exo 2", TypeFaceConstants.EXO2), Option("Fraunces", TypeFaceConstants.FRAUNCES))

    val colorOptions = listOf(
            Option("Accent", LyricsPreferences.COLOR_ACCENT), Option("White", LyricsPreferences.COLOR_WHITE),
            Option("Artwork", LyricsPreferences.COLOR_ARTWORK), Option("Custom", LyricsPreferences.COLOR_CUSTOM))

    val glowOptions = listOf(Option("Off", 0), Option("Subtle", 1), Option("Vibrant", 2))

    val backgroundOptions = listOf(
            Option("Levyra Original", LyricsPreferences.BACKGROUND_ORIGINAL),
            Option("Solid Dark", LyricsPreferences.BACKGROUND_SOLID_DARK),
            Option("AMOLED Black", LyricsPreferences.BACKGROUND_AMOLED),
            Option("Dynamic Gradient", LyricsPreferences.BACKGROUND_GRADIENT),
            Option("Blurred Artwork", LyricsPreferences.BACKGROUND_BLURRED_ARTWORK),
            Option("Cinematic Atmosphere", LyricsPreferences.BACKGROUND_CINEMATIC))

    /** Colour sampled from the current track's artwork, or null before any artwork has loaded. */
    @Volatile
    var artworkColor: Int? = null

    // ---------------------------------------------------------------------------------------
    // Presets
    // ---------------------------------------------------------------------------------------

    class Preset(val name: String, val description: String, val apply: () -> Unit)

    private fun configure(
            size: Float, weight: Int, spacing: Float, inactiveScale: Float, inactiveAlpha: Float,
            colorMode: Int, glow: Int, progressive: Boolean, blur: Float, activeScale: Float, background: Int
    ) {
        LyricsPreferences.setLrcTextSize(size)
        LyricsPreferences.setStudioFontWeight(weight)
        LyricsPreferences.setStudioFont(TypeFaceConstants.AUTO)
        LyricsPreferences.setLrcAlignment(LyricsPreferences.CENTER)
        LyricsPreferences.setStudioLineSpacing(spacing)
        LyricsPreferences.setStudioInactiveScale(inactiveScale)
        LyricsPreferences.setStudioInactiveAlpha(inactiveAlpha)
        LyricsPreferences.setStudioActiveColorMode(colorMode)
        setGlowMode(glow)
        LyricsPreferences.setStudioProgressiveFillEnabled(progressive)
        LyricsPreferences.setStudioEdgeBlur(blur)
        LyricsPreferences.setStudioActiveScale(activeScale)
        LyricsPreferences.setStudioBackground(background)
        LyricsPreferences.setStudioGlassBackgroundEnabled(false)
        LyricsPreferences.setStudioBackgroundDim(0f)
    }

    val presets = listOf(
            Preset("Levyra Original", "Classic look with maximum compatibility") {
                configure(16f, 900, 8f, 1f, 0.55f, LyricsPreferences.COLOR_ACCENT, 0, true, 1f, 1f,
                          LyricsPreferences.BACKGROUND_ORIGINAL)
            },
            Preset("Expressive Karaoke", "Large text with progressive word highlighting") {
                configure(20f, 700, 16f, 0.9f, 0.55f, LyricsPreferences.COLOR_ARTWORK, 1, true, 1f, 1.04f,
                          LyricsPreferences.BACKGROUND_GRADIENT)
            },
            Preset("Minimal", "Clean, centered and distraction-free") {
                configure(14f, 500, 2f, 1f, 0.55f, LyricsPreferences.COLOR_WHITE, 0, false, 0f, 1f,
                          LyricsPreferences.BACKGROUND_SOLID_DARK)
            },
            Preset("Focus", "Strongly highlights active line with depth") {
                configure(20f, 700, 8f, 0.8f, 0.25f, LyricsPreferences.COLOR_WHITE, 0, true, 1f, 1.08f,
                          LyricsPreferences.BACKGROUND_ORIGINAL)
            },
            Preset("Cinematic", "Immersive artwork atmosphere and soft lighting") {
                configure(20f, 600, 24f, 0.9f, 0.40f, LyricsPreferences.COLOR_ARTWORK, 2, true, 1f, 1.04f,
                          LyricsPreferences.BACKGROUND_CINEMATIC)
            }
    )

    fun applyPreset(index: Int) {
        presets.getOrNull(index)?.apply?.invoke()
        LyricsPreferences.setStudioPreset(index)
    }

    // ---------------------------------------------------------------------------------------
    // Glow
    // ---------------------------------------------------------------------------------------

    /** 0 = off, 1 = subtle, 2 = vibrant. Derived from the stored enabled / radius / intensity. */
    fun glowMode(): Int = when {
        !LyricsPreferences.isStudioGlowEnabled() -> 0
        LyricsPreferences.getStudioGlowRadius() >= 14f -> 2
        else -> 1
    }

    fun setGlowMode(mode: Int) {
        when (mode) {
            0 -> LyricsPreferences.setStudioGlowEnabled(false)
            1 -> {
                LyricsPreferences.setStudioGlowEnabled(true)
                LyricsPreferences.setStudioGlowRadius(8f)
                LyricsPreferences.setStudioGlowIntensity(0.5f)
            }
            else -> {
                LyricsPreferences.setStudioGlowEnabled(true)
                LyricsPreferences.setStudioGlowRadius(18f)
                LyricsPreferences.setStudioGlowIntensity(1f)
            }
        }
    }

    // ---------------------------------------------------------------------------------------
    // Applying settings
    // ---------------------------------------------------------------------------------------

    /** The colour the highlighted line really uses for the current settings. */
    fun effectiveActiveColor(): Int = when (LyricsPreferences.getStudioActiveColorMode()) {
        LyricsPreferences.COLOR_WHITE -> Color.WHITE
        LyricsPreferences.COLOR_ARTWORK -> artworkColor ?: Color.WHITE
        LyricsPreferences.COLOR_CUSTOM -> LyricsPreferences.getStudioCustomColor()
        else -> ThemeManager.accent.primaryAccentColor
    }

    /** Extra dimming the chosen background asks for, on top of the user's own dimming. */
    private fun backgroundDim(): Float {
        val modeDim = if (LyricsPreferences.getStudioBackground() == LyricsPreferences.BACKGROUND_CINEMATIC) 0.3f else 0f
        return maxOf(modeDim, LyricsPreferences.getStudioBackgroundDim())
    }

    /**
     * Pushes every persisted Lyrics Studio setting onto [view]. Safe to call repeatedly; setters
     * that would restart animations are guarded inside the view.
     */
    fun apply(view: FelicityLrcView, animateAlignment: Boolean = false) {
        val context = view.context

        val size = LyricsPreferences.getLrcTextSize()
        view.setTextSizes(size, size)
        view.setLineSpacing(LyricsPreferences.getStudioLineSpacing())
        view.setLyricsTypeface(
                TypeFace.getTypeFaceForWeight(
                        LyricsPreferences.getStudioFont(), LyricsPreferences.getStudioFontWeight(), context))

        view.setTextAlignment(
                when (LyricsPreferences.getLrcAlignment()) {
                    LyricsPreferences.LEFT -> FelicityLrcView.Alignment.LEFT
                    LyricsPreferences.RIGHT -> FelicityLrcView.Alignment.RIGHT
                    else -> FelicityLrcView.Alignment.CENTER
                }, animateAlignment)

        val mode = LyricsPreferences.getStudioActiveColorMode()
        val activeColor = effectiveActiveColor()
        view.setStudioActiveColor(mode != LyricsPreferences.COLOR_ACCENT, activeColor)
        view.setInactiveLineAlpha(LyricsPreferences.getStudioInactiveAlpha())
        view.setInactiveTextScale(LyricsPreferences.getStudioInactiveScale())

        view.setKaraokeGlow(
                LyricsPreferences.isStudioGlowEnabled(),
                LyricsPreferences.getStudioGlowRadius(),
                LyricsPreferences.getStudioGlowIntensity(),
                activeColor)
        view.setProgressiveWordFill(LyricsPreferences.isStudioProgressiveFillEnabled())
        view.setGlassBackground(
                LyricsPreferences.isStudioGlassBackgroundEnabled(), LyricsPreferences.getStudioGlassAlpha())
        view.setActiveLineScale(LyricsPreferences.getStudioActiveScale())
        view.setEdgeBlurIntensity(LyricsPreferences.getStudioEdgeBlur())
        view.setBackgroundDim(backgroundDim())
    }

    // ---------------------------------------------------------------------------------------
    // Backgrounds
    // ---------------------------------------------------------------------------------------

    /** True when the chosen background paints its own dark surface instead of showing artwork. */
    fun isFlatBackground(mode: Int) = mode == LyricsPreferences.BACKGROUND_SOLID_DARK ||
            mode == LyricsPreferences.BACKGROUND_AMOLED ||
            mode == LyricsPreferences.BACKGROUND_GRADIENT

    /**
     * Drawable for the backgrounds that do not use the blurred artwork, or null for the artwork
     * based ones. [forPreview] also returns a stand-in for the artwork modes so the Studio
     * preview card still reflects them.
     */
    fun backgroundDrawable(mode: Int, forPreview: Boolean = false): Drawable? {
        val art = artworkColor ?: Color.rgb(40, 70, 120)
        return when (mode) {
            LyricsPreferences.BACKGROUND_SOLID_DARK -> GradientDrawable().apply { setColor(Color.rgb(18, 18, 20)) }
            LyricsPreferences.BACKGROUND_AMOLED -> GradientDrawable().apply { setColor(Color.BLACK) }
            LyricsPreferences.BACKGROUND_GRADIENT -> GradientDrawable(
                    GradientDrawable.Orientation.TOP_BOTTOM,
                    intArrayOf(blend(art, Color.BLACK, 0.45f), blend(art, Color.BLACK, 0.85f), Color.BLACK))
            else -> if (forPreview) GradientDrawable(
                    GradientDrawable.Orientation.TOP_BOTTOM,
                    intArrayOf(blend(art, Color.BLACK, 0.6f), Color.rgb(10, 10, 12))) else null
        }
    }

    private fun blend(from: Int, to: Int, fraction: Float): Int {
        val f = fraction.coerceIn(0f, 1f)
        return Color.rgb(
                (Color.red(from) + (Color.red(to) - Color.red(from)) * f).toInt(),
                (Color.green(from) + (Color.green(to) - Color.green(from)) * f).toInt(),
                (Color.blue(from) + (Color.blue(to) - Color.blue(from)) * f).toInt())
    }

    // ---------------------------------------------------------------------------------------
    // Artwork colour
    // ---------------------------------------------------------------------------------------

    /**
     * Picks a lively, readable colour from [bitmap]: saturated pixels count for more, and the
     * result is lifted to a brightness that stays legible on the dark lyrics backgrounds.
     */
    fun sampleArtworkColor(bitmap: Bitmap): Int {
        val small = Bitmap.createScaledBitmap(bitmap, 24, 24, true)
        var r = 0.0
        var g = 0.0
        var b = 0.0
        var total = 0.0
        val hsv = FloatArray(3)
        for (y in 0 until small.height) {
            for (x in 0 until small.width) {
                val pixel = small.getPixel(x, y)
                Color.colorToHSV(pixel, hsv)
                val weight = 0.15 + hsv[1] * hsv[2]
                r += Color.red(pixel) * weight
                g += Color.green(pixel) * weight
                b += Color.blue(pixel) * weight
                total += weight
            }
        }
        if (small !== bitmap) small.recycle()
        if (total <= 0.0) return Color.WHITE
        Color.colorToHSV(Color.rgb((r / total).toInt(), (g / total).toInt(), (b / total).toInt()), hsv)
        hsv[1] = hsv[1].coerceIn(0.35f, 0.9f)
        hsv[2] = maxOf(hsv[2], 0.85f)
        return Color.HSVToColor(hsv)
    }

    // ---------------------------------------------------------------------------------------
    // Preview data
    // ---------------------------------------------------------------------------------------

    const val PREVIEW_LOOP_MS = 16_000L

    /** Original sample lines with per-word timing so progressive fill and glow can be judged. */
    fun previewLyrics(): LrcData {
        val lines = listOf(
                "Hold the light a little longer",
                "Let the quiet turn to song",
                "Every word you sing is glowing",
                "Carry me where I belong",
                "Time can wait, the night is young")
        val data = LrcData()
        val lineMs = 3_200L
        lines.forEachIndexed { index, text ->
            val start = index * lineMs
            val words = text.split(" ")
            val per = (lineMs - 400L) / words.size
            val timed = words.mapIndexed { i, word ->
                WordEntry(start + i * per, start + (i + 1) * per, if (i == words.lastIndex) word else "$word ")
            }
            data.addEntry(LrcEntry(start, text, timed))
        }
        return data
    }
}
