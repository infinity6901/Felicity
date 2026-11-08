/*
 * The intensity maths follows InterTune's GlassSpec (https://github.com/ItzSkyeYT/InterTune),
 * Copyright (C) 2026 InterTune, GPL-3.0, used in Felicity under the AGPL-3.0.
 */

package app.simple.felicity.preferences

import androidx.core.content.edit
import app.simple.felicity.manager.SharedPreferences.getSharedPreferences
import kotlin.math.sqrt

/**
 * Liquid glass: floating surfaces become frosted glass. One switch and one slider, where the
 * slider means "how much glass": higher is more see-through, lower is closer to a solid surface.
 */
object LiquidGlassPreferences {

    const val LIQUID_GLASS = "liquid_glass"
    const val GLASS_INTENSITY = "liquid_glass_intensity"

    const val DEFAULT_INTENSITY = 0.6f

    fun isLiquidGlass(): Boolean = getSharedPreferences().getBoolean(LIQUID_GLASS, false)
    fun setLiquidGlass(value: Boolean) = getSharedPreferences().edit { putBoolean(LIQUID_GLASS, value) }

    /** Glass intensity, 0..1. */
    fun getIntensity(): Float = getSharedPreferences().getFloat(GLASS_INTENSITY, DEFAULT_INTENSITY).coerceIn(0f, 1f)
    fun setIntensity(value: Float) = getSharedPreferences().edit { putFloat(GLASS_INTENSITY, value.coerceIn(0f, 1f)) }

    /**
     * How much a panel hides what is behind it, 1 solid to 0 clear. Falls as intensity rises, eased
     * so it drops gently at first: the readability cost of a clear panel arrives well before the
     * glass starts to look strong.
     */
    fun frost(intensity: Float = getIntensity()): Float = 1f - sqrt(intensity.coerceIn(0f, 1f))

    /**
     * Opacity of a panel's tint. The floor at full intensity is deliberately not near zero: below
     * about half, text behind the panel fights the text on it, and nothing rescues that.
     */
    fun tintAlpha(intensity: Float = getIntensity(), min: Float = 0.52f, max: Float = 0.97f): Float =
        min + (max - min) * frost(intensity)

    /** The alpha to draw a surface with now: 1 (unchanged) when glass is off. */
    fun surfaceAlpha(): Float = if (isLiquidGlass()) tintAlpha() else 1f
}
