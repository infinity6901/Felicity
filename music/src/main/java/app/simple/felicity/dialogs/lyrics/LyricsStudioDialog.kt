package app.simple.felicity.dialogs.lyrics

import android.app.AlertDialog
import android.app.Dialog
import android.content.Context
import android.graphics.Color
import android.graphics.Outline
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.Switch
import android.widget.TextView
import androidx.appcompat.content.res.AppCompatResources
import androidx.core.graphics.ColorUtils
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import app.simple.felicity.R
import app.simple.felicity.decorations.lrc.view.FelicityLrcView
import app.simple.felicity.decorations.typeface.TypeFace
import app.simple.felicity.engine.managers.MediaPlaybackManager
import app.simple.felicity.preferences.LyricsPreferences
import app.simple.felicity.repository.utils.AudioUtils.getProperTitle
import app.simple.felicity.theme.managers.ThemeManager
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Entry point for Lyrics Studio.
 *
 * Studio is a full-screen editor: a live preview stays pinned at the top while the sections
 * (styles, typography, colours, effects, background, fine-tuning) scroll underneath it. Every
 * control reads from and writes to [LyricsPreferences] only; the Lyrics panel listens to those
 * preferences and restyles itself, and the preview uses the same [LyricsStyle.apply] code path,
 * so nothing here has state that can drift out of sync.
 */
object LyricsStudioDialog {
    fun show(context: Context, onTranslationChanged: () -> Unit = {}, onPhraseSearch: (String) -> Unit = {}): Dialog {
        return LyricsStudioScreen(context, onTranslationChanged, onPhraseSearch).also { it.show() }
    }
}

private class LyricsStudioScreen(
        private val ctx: Context,
        private val onTranslationChanged: () -> Unit,
        private val onPhraseSearch: (String) -> Unit
) : Dialog(ctx, android.R.style.Theme_DeviceDefault_NoActionBar) {

    // Fixed dark palette: the design is a dark surface regardless of the app theme.
    private val colorBackground = Color.rgb(10, 10, 12)
    private val colorCard = Color.rgb(20, 20, 24)
    private val colorStroke = Color.rgb(38, 38, 44)
    private val colorChip = Color.rgb(34, 34, 38)
    private val colorText = Color.rgb(240, 240, 244)
    private val colorTextSecondary = Color.rgb(150, 150, 162)

    private val accent: Int get() = ThemeManager.accent.primaryAccentColor
    private val onAccent: Int
        get() = if (ColorUtils.calculateLuminance(accent) > 0.45) Color.rgb(10, 10, 12) else Color.WHITE

    private val handler = Handler(Looper.getMainLooper())
    private val refreshers = mutableListOf<() -> Unit>()

    private lateinit var preview: FelicityLrcView
    private lateinit var previewCard: FrameLayout
    private var previewStart = 0L
    private var lastPreviewPosition = 0L

    private val previewTick = object : Runnable {
        override fun run() {
            val position = (SystemClock.uptimeMillis() - previewStart) % LyricsStyle.PREVIEW_LOOP_MS
            if (position < lastPreviewPosition) preview.setPosition(0) else preview.updateTime(position)
            lastPreviewPosition = position
            handler.postDelayed(this, 40L)
        }
    }

    // ---------------------------------------------------------------------------------------
    // Helpers
    // ---------------------------------------------------------------------------------------

    private fun dp(value: Int) = (value * ctx.resources.displayMetrics.density).roundToInt()

    private fun rounded(color: Int, radiusDp: Int, strokeColor: Int? = null, strokeDp: Int = 1) =
        GradientDrawable().apply {
            setColor(color)
            cornerRadius = dp(radiusDp).toFloat()
            if (strokeColor != null) setStroke(dp(strokeDp), strokeColor)
        }

    private fun text(value: String, sizeSp: Float, color: Int, bold: Boolean = false) = TextView(ctx).apply {
        text = value
        textSize = sizeSp
        setTextColor(color)
        typeface = if (bold) TypeFace.getBoldTypeFace(ctx) else TypeFace.getMediumTypeFace(ctx)
    }

    private fun icon(res: Int, tint: Int, sizeDp: Int) = ImageView(ctx).apply {
        setImageDrawable(AppCompatResources.getDrawable(ctx, res)?.mutate())
        setColorFilter(tint)
        layoutParams = LinearLayout.LayoutParams(dp(sizeDp), dp(sizeDp))
    }

    /** Something the user changed by hand: the active style preset no longer describes the result. */
    private fun custom(change: () -> Unit) {
        change()
        if (LyricsPreferences.getStudioPreset() != -1) LyricsPreferences.setStudioPreset(-1)
        scheduleRefresh()
    }

    private var refreshPending = false
    private var lastPreviewBackground = Int.MIN_VALUE
    private val refreshRunnable = Runnable {
        refreshPending = false
        refreshAll()
    }

    /**
     * Slider drags fire dozens of changes a second. Refreshing every control and restyling the
     * preview for each one made the Studio stutter, so changes are coalesced to ~30 per second.
     */
    private fun scheduleRefresh() {
        if (refreshPending) return
        refreshPending = true
        handler.postDelayed(refreshRunnable, 33L)
    }

    private fun refreshAll() {
        refreshers.forEach { it() }
        LyricsStyle.apply(preview)
        val mode = LyricsPreferences.getStudioBackground()
        if (mode != lastPreviewBackground) {
            lastPreviewBackground = mode
            previewCard.background = LyricsStyle.backgroundDrawable(mode, forPreview = true)
        }
    }

    private fun nearest(options: List<LyricsStyle.Option<Float>>, value: Float): Float =
        options.minByOrNull { abs(it.value - value) }!!.value

    // ---------------------------------------------------------------------------------------
    // Building blocks
    // ---------------------------------------------------------------------------------------

    private fun sectionHeader(iconRes: Int, title: String) = LinearLayout(ctx).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(dp(4), dp(22), 0, dp(10))
        addView(icon(iconRes, accent, 18))
        addView(text(title, 17f, colorText, bold = true).apply { setPadding(dp(10), 0, 0, 0) })
    }

    private fun card(vararg children: View) = LinearLayout(ctx).apply {
        orientation = LinearLayout.VERTICAL
        background = rounded(colorCard, 20, colorStroke)
        setPadding(dp(16), dp(16), dp(16), dp(16))
        children.forEachIndexed { index, child ->
            addView(child, LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                if (index > 0) topMargin = dp(16)
            })
        }
    }

    private fun label(title: String) = text(title, 15f, colorText, bold = true).apply {
        setPadding(0, 0, 0, dp(10))
    }

    private fun styleChip(view: TextView, selected: Boolean) {
        view.background = rounded(if (selected) accent else colorChip, 12)
        view.setTextColor(if (selected) onAccent else colorText)
        view.compoundDrawables.forEach { it?.setTint(if (selected) onAccent else colorText) }
    }

    private fun makeChip(label: String, iconRes: Int? = null) = TextView(ctx).apply {
        text = label
        textSize = 13f
        gravity = Gravity.CENTER
        maxLines = 1
        ellipsize = TextUtils.TruncateAt.END
        typeface = TypeFace.getMediumTypeFace(ctx)
        setPadding(dp(16), dp(11), dp(16), dp(11))
        if (iconRes != null) {
            setCompoundDrawablesWithIntrinsicBounds(AppCompatResources.getDrawable(ctx, iconRes)?.mutate(), null, null, null)
            compoundDrawablePadding = dp(6)
        }
    }

    /**
     * A row of selectable chips. [equalWidth] spreads them across the row; otherwise they wrap.
     * Selection is read back through [isSelected] on every [refreshAll], so presets, Reset and
     * edits made elsewhere always show up here.
     */
    private fun <T> chips(
            options: List<LyricsStyle.Option<T>>,
            equalWidth: Boolean,
            isSelected: (T) -> Boolean,
            onPick: (T) -> Unit,
            icons: List<Int>? = null
    ): View {
        val views = options.mapIndexed { index, option ->
            makeChip(option.label, icons?.getOrNull(index)).apply {
                setOnClickListener { onPick(option.value) }
            }
        }
        val container: ViewGroup = if (equalWidth) {
            LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL
                views.forEachIndexed { index, view ->
                    addView(view, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                        if (index > 0) marginStart = dp(8)
                    })
                }
            }
        } else {
            FlowLayout(ctx, dp(8)).apply { views.forEach { addView(it) } }
        }
        refreshers += { views.forEachIndexed { i, v -> styleChip(v, isSelected(options[i].value)) } }
        return container
    }

    private fun switchRow(title: String, subtitle: String?, checked: () -> Boolean, onChange: (Boolean) -> Unit): View {
        val toggle = Switch(ctx).apply {
            scaleX = 1.2f
            scaleY = 1.2f
        }
        var updating = false
        toggle.setOnCheckedChangeListener { _, value -> if (!updating) onChange(value) }
        refreshers += {
            updating = true
            toggle.isChecked = checked()
            toggle.thumbTintList = android.content.res.ColorStateList.valueOf(if (toggle.isChecked) accent else Color.LTGRAY)
            toggle.trackTintList = android.content.res.ColorStateList.valueOf(
                    ColorUtils.setAlphaComponent(if (toggle.isChecked) accent else Color.GRAY, 150))
            updating = false
        }
        return LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(LinearLayout(ctx).apply {
                orientation = LinearLayout.VERTICAL
                addView(text(title, 16.5f, colorText, bold = true))
                if (subtitle != null) addView(text(subtitle, 14f, colorTextSecondary).apply { setPadding(0, dp(3), 0, 0) })
            }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            addView(toggle)
        }
    }

    private fun slider(
            title: String, min: Int, max: Int,
            current: () -> Int, display: (Int) -> String,
            enabled: () -> Boolean = { true },
            onChange: (Int) -> Unit
    ): View {
        val value = text("", 13f, colorTextSecondary)
        val bar = SeekBar(ctx).apply { this.max = max - min }
        bar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar, progress: Int, fromUser: Boolean) {
                if (fromUser) onChange(progress + min)
            }

            override fun onStartTrackingTouch(seekBar: SeekBar) = Unit
            override fun onStopTrackingTouch(seekBar: SeekBar) = Unit
        })
        val root = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            addView(LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL
                addView(text(title, 14f, colorText, bold = true), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
                addView(value)
            })
            addView(bar, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }
        refreshers += {
            val now = current().coerceIn(min, max)
            if (bar.progress != now - min) bar.progress = now - min
            value.text = display(now)
            val on = enabled()
            bar.isEnabled = on
            bar.progressTintList = android.content.res.ColorStateList.valueOf(accent)
            bar.thumbTintList = android.content.res.ColorStateList.valueOf(accent)
            root.alpha = if (on) 1f else 0.4f
        }
        return root
    }

    private fun outlinedButton(title: String, onClick: () -> Unit) = TextView(ctx).apply {
        text = title
        textSize = 16f
        gravity = Gravity.CENTER
        typeface = TypeFace.getBoldTypeFace(ctx)
        setPadding(dp(18), dp(17), dp(18), dp(17))
        setOnClickListener { onClick() }
        refreshers += {
            setTextColor(accent)
            background = rounded(Color.TRANSPARENT, 28, accent, 2)
        }
    }

    // ---------------------------------------------------------------------------------------
    // Sections
    // ---------------------------------------------------------------------------------------

    private fun stylesSection(): View {
        val list = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        LyricsStyle.presets.forEachIndexed { index, preset ->
            val title = text(preset.name, 16f, colorText, bold = true)
            val subtitle = text(preset.description, 13f, colorTextSecondary).apply { setPadding(0, dp(3), 0, 0) }
            val check = ImageView(ctx).apply {
                setImageDrawable(AppCompatResources.getDrawable(ctx, R.drawable.ic_check)?.mutate())
                setPadding(dp(4), dp(4), dp(4), dp(4))
            }
            val row = LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(16), dp(16), dp(16), dp(16))
                addView(LinearLayout(ctx).apply {
                    orientation = LinearLayout.VERTICAL
                    addView(title)
                    addView(subtitle)
                }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
                addView(check, LinearLayout.LayoutParams(dp(26), dp(26)))
                setOnClickListener {
                    LyricsStyle.applyPreset(index)
                    refreshAll()
                }
            }
            refreshers += {
                val selected = LyricsPreferences.getStudioPreset() == index
                row.background = if (selected) {
                    rounded(ColorUtils.setAlphaComponent(accent, 36), 18, accent, 2)
                } else rounded(colorCard, 18, colorStroke)
                title.setTextColor(if (selected) accent else colorText)
                check.visibility = if (selected) View.VISIBLE else View.GONE
                check.background = rounded(accent, 13)
                check.setColorFilter(onAccent)
            }
            list.addView(row, LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                if (index > 0) topMargin = dp(10)
            })
        }
        return list
    }

    private fun fontRow(): View {
        val row = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL }
        val views = LyricsStyle.fontOptions.mapIndexed { index, option ->
            makeChip(option.label).apply {
                // Show each family in its own face so the choice is obvious.
                typeface = TypeFace.getTypeFaceForWeight(option.value, 600, ctx)
                setOnClickListener { custom { LyricsPreferences.setStudioFont(option.value) } }
                row.addView(this, LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                    if (index > 0) marginStart = dp(8)
                })
            }
        }
        refreshers += {
            views.forEachIndexed { i, v -> styleChip(v, LyricsPreferences.getStudioFont() == LyricsStyle.fontOptions[i].value) }
        }
        return HorizontalScrollView(ctx).apply {
            isHorizontalScrollBarEnabled = false
            addView(row)
        }
    }

    /** Which versions of each lyric line are on screen; any combination works and all effects follow. */
    private fun versionsSection(): View = card(
            switchRow("Original lyrics", "The lyrics as written",
                      { LyricsPreferences.isShowOriginal() },
                      { v -> custom { LyricsPreferences.setShowOriginal(v) } }),
            switchRow("Romanized lyrics", "Latin-letter reading (Auto romanize in the lyrics menu creates it when the lyrics have none)",
                      { LyricsPreferences.isShowRomanized() },
                      { v -> custom { LyricsPreferences.setShowRomanized(v) } }),
            switchRow("Translated lyrics", "Fetches and shows a translation when available",
                      { LyricsPreferences.isStudioTranslationEnabled() },
                      { v ->
                          LyricsPreferences.setStudioTranslationEnabled(v)
                          refreshAll()
                          onTranslationChanged()
                      })
    )

    private fun typographySection(): View = LinearLayout(ctx).apply {
        orientation = LinearLayout.VERTICAL
        addView(card(
                LinearLayout(ctx).apply {
                    orientation = LinearLayout.VERTICAL
                    addView(label("Font"))
                    addView(fontRow())
                }
        ))
        addView(card(
                // Precise size in half-sp steps (the old four presets were too coarse, and a long
                // line at "Large" no longer fits the panel).
                slider("Text Size", 20, 64, { Math.round(LyricsPreferences.getLrcTextSize() * 2f) },
                       { String.format(java.util.Locale.US, "%.1f sp", it / 2f) }) { v ->
                    custom { LyricsPreferences.setLrcTextSize(v / 2f) }
                },
                block("Font Weight", chips(
                        LyricsStyle.weightOptions, false,
                        { it == LyricsStyle.weightOptions.minByOrNull { o -> abs(o.value - LyricsPreferences.getStudioFontWeight()) }!!.value },
                        { v -> custom { LyricsPreferences.setStudioFontWeight(v) } })),
                block("Alignment", chips(
                        LyricsStyle.alignmentOptions, true,
                        { it == LyricsPreferences.getLrcAlignment() },
                        { v -> custom { LyricsPreferences.setLrcAlignment(v) } },
                        icons = listOf(R.drawable.ic_align_left_12dp, R.drawable.ic_align_center_12dp, R.drawable.ic_align_right_12dp))),
                block("Line Spacing", chips(
                        LyricsStyle.spacingOptions, false,
                        { it == nearest(LyricsStyle.spacingOptions, LyricsPreferences.getStudioLineSpacing()) },
                        { v -> custom { LyricsPreferences.setStudioLineSpacing(v) } })),
                block("Inactive Text Scale", chips(
                        LyricsStyle.inactiveScaleOptions, true,
                        { it == nearest(LyricsStyle.inactiveScaleOptions, LyricsPreferences.getStudioInactiveScale()) },
                        { v -> custom { LyricsPreferences.setStudioInactiveScale(v) } }))
        ).apply { (layoutParams as? ViewGroup.MarginLayoutParams)?.topMargin = dp(12) },
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(12) })
    }

    private fun block(title: String, content: View) = LinearLayout(ctx).apply {
        orientation = LinearLayout.VERTICAL
        addView(label(title))
        addView(content)
    }

    private fun colorsSection(): View = card(
            block("Active Line Color", chips(
                    LyricsStyle.colorOptions, false,
                    { it == LyricsPreferences.getStudioActiveColorMode() },
                    { mode ->
                        if (mode == LyricsPreferences.COLOR_CUSTOM) {
                            pickColor(LyricsPreferences.getStudioCustomColor()) { picked ->
                                custom {
                                    LyricsPreferences.setStudioCustomColor(picked)
                                    LyricsPreferences.setStudioActiveColorMode(LyricsPreferences.COLOR_CUSTOM)
                                }
                            }
                        } else custom { LyricsPreferences.setStudioActiveColorMode(mode) }
                    })),
            block("Inactive Line Opacity", chips(
                    LyricsStyle.inactiveOpacityOptions, true,
                    { it == nearest(LyricsStyle.inactiveOpacityOptions, LyricsPreferences.getStudioInactiveAlpha()) },
                    { v -> custom { LyricsPreferences.setStudioInactiveAlpha(v) } }))
    )

    private fun effectsSection(): View = card(
            block("Glow", chips(
                    LyricsStyle.glowOptions, true,
                    { it == LyricsStyle.glowMode() },
                    { v -> custom { LyricsStyle.setGlowMode(v) } })),
            switchRow("Progressive Karaoke", "Highlights words smoothly as they are sung",
                      { LyricsPreferences.isStudioProgressiveFillEnabled() },
                      { v -> custom { LyricsPreferences.setStudioProgressiveFillEnabled(v) } }),
            switchRow("Depth Blur", "Gently blurs surrounding lines",
                      { LyricsPreferences.getStudioEdgeBlur() > 0.01f },
                      { v -> custom { LyricsPreferences.setStudioEdgeBlur(if (v) 1f else 0f) } })
    )

    private fun backgroundSection(): View {
        val options = LyricsStyle.backgroundOptions
        val views = options.map { makeChip(it.label) }
        views.forEachIndexed { i, v -> v.setOnClickListener { custom { LyricsPreferences.setStudioBackground(options[i].value) } } }
        refreshers += {
            views.forEachIndexed { i, v -> styleChip(v, LyricsPreferences.getStudioBackground() == options[i].value) }
        }
        val grid = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        views.chunked(2).forEachIndexed { rowIndex, pair ->
            grid.addView(LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL
                pair.forEachIndexed { i, v ->
                    addView(v, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                        if (i > 0) marginStart = dp(8)
                    })
                }
            }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                if (rowIndex > 0) topMargin = dp(8)
            })
        }
        return card(grid)
    }

    private fun fineTuneSection(): View = card(
            slider("Glow radius", 0, 28, { LyricsPreferences.getStudioGlowRadius().roundToInt() }, { "$it dp" },
                   enabled = { LyricsPreferences.isStudioGlowEnabled() }) { v ->
                custom { LyricsPreferences.setStudioGlowRadius(v.toFloat()) }
            },
            slider("Glow intensity", 0, 100, { (LyricsPreferences.getStudioGlowIntensity() * 100).roundToInt() }, { "$it%" },
                   enabled = { LyricsPreferences.isStudioGlowEnabled() }) { v ->
                custom { LyricsPreferences.setStudioGlowIntensity(v / 100f) }
            },
            slider("Active line emphasis", 100, 120, { (LyricsPreferences.getStudioActiveScale() * 100).roundToInt() }, { "$it%" }) { v ->
                custom { LyricsPreferences.setStudioActiveScale(v / 100f) }
            },
            slider("Edge blur strength", 0, 200, { (LyricsPreferences.getStudioEdgeBlur() * 100).roundToInt() }, { "$it%" }) { v ->
                custom { LyricsPreferences.setStudioEdgeBlur(v / 100f) }
            },
            slider("Background dimming", 0, 75, { (LyricsPreferences.getStudioBackgroundDim() * 100).roundToInt() }, { "$it%" }) { v ->
                custom { LyricsPreferences.setStudioBackgroundDim(v / 100f) }
            },
            switchRow("Frosted glass lyrics panel", null,
                      { LyricsPreferences.isStudioGlassBackgroundEnabled() },
                      { v -> custom { LyricsPreferences.setStudioGlassBackgroundEnabled(v) } }),
            slider("Glass panel opacity", 0, 45, { (LyricsPreferences.getStudioGlassAlpha() * 100).roundToInt() }, { "$it%" },
                   enabled = { LyricsPreferences.isStudioGlassBackgroundEnabled() }) { v ->
                custom { LyricsPreferences.setStudioGlassAlpha(v / 100f) }
            }
    )

    private fun toolsSection(): View = card(
            text("Word-by-word karaoke timing is used automatically when the selected lyrics source provides word timestamps.",
                 14f, colorTextSecondary).apply { setLineSpacing(0f, 1.15f) },
            outlinedButton("Search songs by lyric phrase") {
                val input = EditText(ctx).apply { hint = "Type a lyric line or phrase" }
                AlertDialog.Builder(ctx).setTitle("Find a song from its lyrics")
                    .setView(input)
                    .setNegativeButton("Cancel", null)
                    .setPositiveButton("Search") { _, _ ->
                        input.text.toString().trim().takeIf { it.isNotBlank() }?.let(onPhraseSearch)
                    }
                    .show()
            }
    )

    // ---------------------------------------------------------------------------------------
    // Colour picker
    // ---------------------------------------------------------------------------------------

    private fun pickColor(initial: Int, onPicked: (Int) -> Unit) {
        val hsv = FloatArray(3).also { Color.colorToHSV(initial, it) }
        val swatch = View(ctx)
        fun current() = Color.HSVToColor(hsv)
        fun paintSwatch() {
            swatch.background = rounded(current(), 14)
        }

        fun bar(title: String, max: Int, start: Int, onChange: (Int) -> Unit): View {
            val seek = SeekBar(ctx).apply {
                this.max = max
                progress = start
                setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                    override fun onProgressChanged(seekBar: SeekBar, progress: Int, fromUser: Boolean) {
                        onChange(progress)
                        paintSwatch()
                    }

                    override fun onStartTrackingTouch(seekBar: SeekBar) = Unit
                    override fun onStopTrackingTouch(seekBar: SeekBar) = Unit
                })
            }
            return LinearLayout(ctx).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(0, dp(8), 0, 0)
                addView(TextView(ctx).apply { text = title })
                addView(seek)
            }
        }

        val body = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), dp(16), dp(24), 0)
            addView(swatch, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(48)))
            addView(bar("Hue", 360, hsv[0].roundToInt()) { hsv[0] = it.toFloat() })
            addView(bar("Saturation", 100, (hsv[1] * 100).roundToInt()) { hsv[1] = it / 100f })
            addView(bar("Brightness", 100, (hsv[2] * 100).roundToInt()) { hsv[2] = it / 100f })
        }
        paintSwatch()
        AlertDialog.Builder(ctx)
            .setTitle("Custom active line color")
            .setView(body)
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Apply") { _, _ -> onPicked(current()) }
            .show()
    }

    // ---------------------------------------------------------------------------------------
    // Screen
    // ---------------------------------------------------------------------------------------

    override fun onCreate(savedInstanceState: android.os.Bundle?) {
        super.onCreate(savedInstanceState)
        val window = window ?: return
        window.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        window.setBackgroundDrawable(android.graphics.drawable.ColorDrawable(colorBackground))
        WindowCompat.setDecorFitsSystemWindows(window, false)

        val root = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        ViewCompat.setOnApplyWindowInsetsListener(root) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            v.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            insets
        }

        // --- Pinned part: header + live preview -------------------------------------------
        val back = ImageView(ctx).apply {
            setImageDrawable(AppCompatResources.getDrawable(ctx, R.drawable.ic_arrow_back)?.mutate())
            setPadding(dp(10), dp(10), dp(10), dp(10))
            setOnClickListener { dismiss() }
            refreshers += {
                background = rounded(ColorUtils.setAlphaComponent(accent, 70), 22)
                setColorFilter(Color.WHITE)
            }
        }
        val reset = ImageView(ctx).apply {
            setImageDrawable(AppCompatResources.getDrawable(ctx, R.drawable.ic_reset)?.mutate())
            setColorFilter(colorTextSecondary)
            setPadding(dp(10), dp(10), dp(10), dp(10))
            setOnClickListener { resetToDefaults() }
        }
        val header = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(16), dp(10), dp(16), dp(6))
            addView(back, LinearLayout.LayoutParams(dp(44), dp(44)))
            addView(LinearLayout(ctx).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(12), 0, 0, 0)
                addView(text("Lyrics Studio", 22f, colorText, bold = true))
                addView(text("Customize your lyrics experience", 12.5f, colorTextSecondary))
            }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            addView(reset, LinearLayout.LayoutParams(dp(44), dp(44)))
        }
        root.addView(header)

        val songPill = text(MediaPlaybackManager.getCurrentSong()?.getProperTitle() ?: "Sample lyrics",
                            12f, accent, bold = true).apply {
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
            setPadding(dp(12), dp(5), dp(12), dp(5))
            refreshers += {
                setTextColor(accent)
                background = rounded(ColorUtils.setAlphaComponent(accent, 40), 10)
            }
        }
        root.addView(LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(20), dp(4), dp(16), dp(8))
            // The label keeps its natural width; the song pill takes what is left and ellipsizes.
            // (A weighted label used to be squeezed to nothing by a long title and wrapped letter
            // by letter, which is what stretched this row into a huge empty band.)
            addView(text("Live Preview", 13f, colorTextSecondary).apply { maxLines = 1 },
                    LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            addView(FrameLayout(ctx).apply {
                addView(songPill, FrameLayout.LayoutParams(
                        ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.END or Gravity.CENTER_VERTICAL))
            }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginStart = dp(12) })
        })

        preview = FelicityLrcView(ctx).apply {
            setAlbumArtBackground(true) // light-on-dark text colours, the card is always dark
            setLrcData(LyricsStyle.previewLyrics(), 0)
            setOnTouchListener { _, _ -> true } // the preview plays by itself; keep it out of the way of scrolling
        }
        previewCard = FrameLayout(ctx).apply {
            addView(preview, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
            clipToOutline = true
            outlineProvider = object : ViewOutlineProvider() {
                override fun getOutline(view: View, outline: Outline) {
                    outline.setRoundRect(0, 0, view.width, view.height, dp(24).toFloat())
                }
            }
        }
        val previewHeight = minOf(dp(190), (ctx.resources.displayMetrics.heightPixels * 0.30f).roundToInt())
        root.addView(previewCard, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, previewHeight).apply {
            marginStart = dp(16)
            marginEnd = dp(16)
        })

        // --- Scrolling part -----------------------------------------------------------------
        val content = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(0), dp(16), dp(32))
            addView(sectionHeader(R.drawable.ic_lyrics, "Styles"))
            addView(stylesSection())
            addView(sectionHeader(R.drawable.ic_lyrics, "Lyric versions"))
            addView(versionsSection())
            addView(sectionHeader(R.drawable.ic_text_fields, "Typography"))
            addView(typographySection())
            addView(sectionHeader(R.drawable.ic_palette, "Colors"))
            addView(colorsSection())
            addView(sectionHeader(R.drawable.ic_waves, "Effects"))
            addView(effectsSection())
            addView(sectionHeader(R.drawable.ic_image, "Background"))
            addView(backgroundSection())
            addView(sectionHeader(R.drawable.ic_equalizer, "Fine-tuning"))
            addView(fineTuneSection())
            addView(sectionHeader(R.drawable.ic_lyrics, "Tools"))
            addView(toolsSection())
            addView(outlinedButton("Reset to default") { resetToDefaults() }, LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                topMargin = dp(28)
                marginStart = dp(24)
                marginEnd = dp(24)
            })
        }
        root.addView(ScrollView(ctx).apply {
            isVerticalScrollBarEnabled = false
            clipToPadding = false
            addView(content)
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))

        setContentView(root)
        runCatching { WindowCompat.getInsetsController(window, root).isAppearanceLightStatusBars = false }
        refreshAll()
    }

    private fun resetToDefaults() {
        val translationWasOn = LyricsPreferences.isStudioTranslationEnabled()
        LyricsPreferences.resetStudio()
        refreshAll()
        if (translationWasOn) onTranslationChanged()
    }

    override fun onStart() {
        super.onStart()
        previewStart = SystemClock.uptimeMillis()
        lastPreviewPosition = 0L
        handler.post(previewTick)
    }

    override fun onStop() {
        handler.removeCallbacks(previewTick)
        super.onStop()
    }
}

/** Minimal wrapping row layout: children flow left to right and wrap onto new lines. */
internal class FlowLayout(context: Context, private val gap: Int) : ViewGroup(context) {

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val maxWidth = MeasureSpec.getSize(widthMeasureSpec) - paddingLeft - paddingRight
        var x = 0
        var y = 0
        var rowHeight = 0
        for (i in 0 until childCount) {
            val child = getChildAt(i)
            if (child.visibility == GONE) continue
            val lp = child.layoutParams
            val widthSpec = if (lp != null && lp.width > 0) MeasureSpec.makeMeasureSpec(lp.width, MeasureSpec.EXACTLY)
            else MeasureSpec.makeMeasureSpec(maxWidth, MeasureSpec.AT_MOST)
            val heightSpec = if (lp != null && lp.height > 0) MeasureSpec.makeMeasureSpec(lp.height, MeasureSpec.EXACTLY)
            else MeasureSpec.UNSPECIFIED
            child.measure(widthSpec, heightSpec)
            if (x > 0 && x + child.measuredWidth > maxWidth) {
                x = 0
                y += rowHeight + gap
                rowHeight = 0
            }
            x += child.measuredWidth + gap
            rowHeight = maxOf(rowHeight, child.measuredHeight)
        }
        setMeasuredDimension(
                MeasureSpec.getSize(widthMeasureSpec),
                y + rowHeight + paddingTop + paddingBottom)
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        val maxWidth = r - l - paddingLeft - paddingRight
        var x = 0
        var y = 0
        var rowHeight = 0
        for (i in 0 until childCount) {
            val child = getChildAt(i)
            if (child.visibility == GONE) continue
            if (x > 0 && x + child.measuredWidth > maxWidth) {
                x = 0
                y += rowHeight + gap
                rowHeight = 0
            }
            child.layout(paddingLeft + x, paddingTop + y,
                         paddingLeft + x + child.measuredWidth, paddingTop + y + child.measuredHeight)
            x += child.measuredWidth + gap
            rowHeight = maxOf(rowHeight, child.measuredHeight)
        }
    }
}
