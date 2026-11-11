package app.simple.felicity.decorations.glass

import android.content.Context
import android.content.SharedPreferences
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Shader
import android.util.AttributeSet
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import app.simple.felicity.manager.SharedPreferences.registerSharedPreferenceChangeListener
import app.simple.felicity.manager.SharedPreferences.unregisterSharedPreferenceChangeListener
import app.simple.felicity.preferences.LiquidGlassPreferences
import app.simple.felicity.theme.managers.ThemeManager

/**
 * A pill-shaped control panel that is liquid glass over whatever sits behind it.
 *
 * It must be placed inside a [GlassHostLayout] after the content it floats over (for example the album
 * art). With liquid glass on, it blurs and refracts that content live; with it off, or below Android 12,
 * it is a floating card in the theme color so the controls stay readable over artwork.
 *
 * A view tag of `fit_status` pushes the panel below the status bar.
 */
class GlassPanelLayout @JvmOverloads constructor(
        context: Context,
        attrs: AttributeSet? = null,
        defStyleAttr: Int = 0
) : LinearLayout(context, attrs, defStyleAttr), SharedPreferences.OnSharedPreferenceChangeListener {

    private val density = resources.displayMetrics.density
    private val rect = RectF()
    private val rim = RectF()
    private val clip = Path()
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val sheen = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rimPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = density }
    private var shaderHeight = -1f
    private var host: GlassHostLayout? = null
    private var baseTopMargin = -1

    init {
        setWillNotDraw(false)
        clipChildren = false
    }

    private fun hostOf(): GlassHostLayout? {
        var p = parent
        while (p != null) {
            if (p is GlassHostLayout) return p
            p = (p as? View)?.parent
        }
        return null
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        if (!isInEditMode) registerSharedPreferenceChangeListener()
        host = hostOf()
        apply()
        if (tag == "fit_status") {
            val lp = layoutParams as? MarginLayoutParams
            val top = ViewCompat.getRootWindowInsets(this)?.getInsets(WindowInsetsCompat.Type.statusBars())?.top ?: 0
            if (lp != null) {
                if (baseTopMargin < 0) baseTopMargin = lp.topMargin
                lp.topMargin = baseTopMargin + top
                layoutParams = lp
            }
        }
    }

    override fun onDetachedFromWindow() {
        host?.removeGlassTarget(this)
        host = null
        if (!isInEditMode) unregisterSharedPreferenceChangeListener()
        super.onDetachedFromWindow()
    }

    override fun onSharedPreferenceChanged(sharedPreferences: SharedPreferences?, key: String?) {
        if (key == LiquidGlassPreferences.LIQUID_GLASS || key == LiquidGlassPreferences.GLASS_INTENSITY) {
            apply()
        }
    }

    private fun apply() {
        val h = host ?: return
        val on = !isInEditMode && LiquidGlassPreferences.isLiquidGlass() && h.isSupported
        h.glassEnabled = on
        if (on) h.addGlassTarget(this) else h.removeGlassTarget(this)
        invalidate()
    }

    override fun invalidate() {
        super.invalidate()
        host?.invalidate()
    }

    override fun onDescendantInvalidated(child: View, target: View) {
        super.onDescendantInvalidated(child, target)
        host?.invalidate()
    }

    override fun draw(canvas: Canvas) {
        val h = host
        if (h != null && h.glassEnabled && h.isSupported && canvas.isHardwareAccelerated) {
            if (h.recording || !h.drawingTargets) return
        }
        super.draw(canvas)
    }

    override fun dispatchDraw(canvas: Canvas) {
        val h = host
        if (h != null && h.glassEnabled && h.isSupported && canvas.isHardwareAccelerated) {
            if (h.recording || !h.drawingTargets) return
        }
        super.dispatchDraw(canvas)
    }

    override fun onDraw(canvas: Canvas) {
        val h = host
        if (h != null && h.glassEnabled && h.isSupported && canvas.isHardwareAccelerated) {
            if (h.recording || !h.drawingTargets) return
        }
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return
        rect.set(0f, 0f, w, h)
        val radius = h / 2f
        val bg = ThemeManager.theme.viewGroupTheme.backgroundColor
        val glass = host?.glassEnabled == true

        if (shaderHeight != h) {
            shaderHeight = h
            sheen.shader = LinearGradient(0f, 0f, 0f, h * 0.7f, Color.argb(70, 255, 255, 255), Color.TRANSPARENT, Shader.TileMode.CLAMP)
            rimPaint.shader = LinearGradient(0f, 0f, 0f, h, Color.argb(150, 255, 255, 255), Color.argb(30, 255, 255, 255), Shader.TileMode.CLAMP)
        }

        var drawn = false
        if (glass) {
            clip.rewind()
            clip.addRoundRect(rect, radius, radius, Path.Direction.CW)
            val save = canvas.save()
            canvas.clipPath(clip)
            drawn = host?.drawBackdrop(
                    canvas, this, rect, 0f, radius,
                    blur = 14f * density,
                    refraction = LiquidGlassPreferences.getIntensity(),
                    depth = minOf(18f * density, h / 2f)) == true
            canvas.restoreToCount(save)
        }

        val tint = if (drawn) LiquidGlassPreferences.tintAlpha(min = 0.22f, max = 0.82f) else 0.92f
        fill.color = Color.argb((Color.alpha(bg) * tint).toInt(), Color.red(bg), Color.green(bg), Color.blue(bg))
        canvas.drawRoundRect(rect, radius, radius, fill)
        if (drawn) canvas.drawRoundRect(rect, radius, radius, sheen)
        val inset = rimPaint.strokeWidth / 2f
        rim.set(inset, inset, w - inset, h - inset)
        canvas.drawRoundRect(rim, radius - inset, radius - inset, rimPaint)
    }
}
