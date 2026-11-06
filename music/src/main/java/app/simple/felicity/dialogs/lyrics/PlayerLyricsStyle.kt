package app.simple.felicity.dialogs.lyrics

import android.app.AlertDialog
import android.app.Dialog
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
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
import app.simple.felicity.decorations.lrc.view.LrcLineView
import app.simple.felicity.decorations.typeface.TypeFace
import app.simple.felicity.preferences.PlayerPreferences
import app.simple.felicity.theme.managers.ThemeManager
import kotlin.math.roundToInt

/**
 * Styling for the single-line lyric caption shown on the player screen: line transition,
 * the gliding highlight box, glow, free text/glow/box colours, size and backdrop. Everything
 * is persisted in [PlayerPreferences] and applied with [apply].
 */
object PlayerLyricsStyle {

    /** Pushes every saved caption setting onto [view]. */
    fun apply(view: LrcLineView) {
        view.setTextSizeSp(PlayerPreferences.getCaptionTextSize())
        view.setTransitionMode(PlayerPreferences.getCaptionTransition())
        view.setBoxEnabled(PlayerPreferences.isCaptionBox())
        view.setBoxOpacityPercent(PlayerPreferences.getCaptionBoxOpacity())
        view.setGlowEnabled(PlayerPreferences.isCaptionGlow())
        view.setColorMode(PlayerPreferences.getCaptionColorMode())
        view.setBackdropOpacityPercent(PlayerPreferences.getCaptionPillOpacity())
        view.setCustomColors(
                PlayerPreferences.getCaptionTextColor(),
                PlayerPreferences.getCaptionGlowColor(),
                PlayerPreferences.getCaptionBoxColor())
    }

    /** Shows the Studio-style customisation screen; [onChanged] runs after every tweak. */
    fun show(context: Context, onChanged: () -> Unit) {
        PlayerLyricsStyleScreen(context, onChanged).show()
    }
}

private class PlayerLyricsStyleScreen(
        private val ctx: Context,
        private val onChanged: () -> Unit
) : Dialog(ctx, android.R.style.Theme_DeviceDefault_NoActionBar) {

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
    private lateinit var preview: LrcLineView
    private var previewStart = 0L
    private var lastPosition = 0L

    private val previewTick = object : Runnable {
        override fun run() {
            val pos = (SystemClock.uptimeMillis() - previewStart) % LyricsStyle.PREVIEW_LOOP_MS
            if (pos < lastPosition) preview.setLrcData(LyricsStyle.previewLyrics(), 0) else preview.updateTime(pos)
            lastPosition = pos
            handler.postDelayed(this, 40L)
        }
    }

    private fun dp(v: Int) = (v * ctx.resources.displayMetrics.density).roundToInt()

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

    /** Applies a change: persists via [change], refreshes controls, restyles the preview and the caption. */
    private fun edit(change: () -> Unit) {
        change()
        PlayerLyricsStyle.apply(preview)
        refreshers.forEach { it() }
        onChanged()
    }

    private fun sectionHeader(iconRes: Int, title: String) = LinearLayout(ctx).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(dp(4), dp(22), 0, dp(10))
        addView(ImageView(ctx).apply {
            setImageDrawable(AppCompatResources.getDrawable(ctx, iconRes)?.mutate())
            setColorFilter(accent)
        }, LinearLayout.LayoutParams(dp(18), dp(18)))
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

    private fun block(title: String, content: View) = LinearLayout(ctx).apply {
        orientation = LinearLayout.VERTICAL
        addView(text(title, 15f, colorText, bold = true).apply { setPadding(0, 0, 0, dp(10)) })
        addView(content)
    }

    private fun styleChip(view: TextView, selected: Boolean) {
        view.background = rounded(if (selected) accent else colorChip, 12)
        view.setTextColor(if (selected) onAccent else colorText)
    }

    private fun chips(options: List<String>, selected: () -> Int, onPick: (Int) -> Unit): View {
        val views = options.mapIndexed { i, label ->
            TextView(ctx).apply {
                text = label
                textSize = 13f
                gravity = Gravity.CENTER
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.END
                typeface = TypeFace.getMediumTypeFace(ctx)
                setPadding(dp(16), dp(11), dp(16), dp(11))
                setOnClickListener { onPick(i) }
            }
        }
        refreshers += { views.forEachIndexed { i, v -> styleChip(v, i == selected()) } }
        return FlowLayout(ctx, dp(8)).apply { views.forEach { addView(it) } }
    }

    private fun switchRow(title: String, subtitle: String, checked: () -> Boolean, onToggle: (Boolean) -> Unit): View {
        val toggle = Switch(ctx)
        var updating = false
        toggle.setOnCheckedChangeListener { _, v -> if (!updating) onToggle(v) }
        refreshers += {
            updating = true
            toggle.isChecked = checked()
            toggle.thumbTintList = ColorStateList.valueOf(if (toggle.isChecked) accent else Color.LTGRAY)
            toggle.trackTintList = ColorStateList.valueOf(
                    ColorUtils.setAlphaComponent(if (toggle.isChecked) accent else Color.GRAY, 110))
            updating = false
        }
        return LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(LinearLayout(ctx).apply {
                orientation = LinearLayout.VERTICAL
                addView(text(title, 15f, colorText, bold = true))
                addView(text(subtitle, 12.5f, colorTextSecondary).apply { setPadding(0, dp(2), 0, 0) })
            }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            addView(toggle)
        }
    }

    private fun slider(title: String, min: Int, max: Int, current: () -> Int, unit: String,
                       enabled: () -> Boolean = { true }, onValue: (Int) -> Unit): View {
        val value = text("", 13f, colorTextSecondary)
        val bar = SeekBar(ctx).apply { this.max = max - min }
        bar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar, progress: Int, fromUser: Boolean) {
                if (fromUser) onValue(progress + min)
            }

            override fun onStartTrackingTouch(sb: SeekBar) = Unit
            override fun onStopTrackingTouch(sb: SeekBar) = Unit
        })
        val root = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            addView(LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL
                addView(text(title, 14f, colorText, bold = true),
                        LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
                addView(value)
            })
            addView(bar, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }
        refreshers += {
            val now = current().coerceIn(min, max)
            if (bar.progress != now - min) bar.progress = now - min
            value.text = "$now$unit"
            bar.progressTintList = ColorStateList.valueOf(accent)
            bar.thumbTintList = ColorStateList.valueOf(accent)
            val on = enabled()
            bar.isEnabled = on
            root.alpha = if (on) 1f else 0.4f
        }
        return root
    }

    /**
     * A free colour control: an "Auto" swatch (0 = automatic), a palette of common colours and
     * a custom picker with hue / saturation / brightness.
     */
    private fun colorRow(title: String, get: () -> Int, set: (Int) -> Unit): View {
        val palette = listOf(
                Color.WHITE, Color.rgb(255, 82, 82), Color.rgb(255, 152, 0), Color.rgb(255, 214, 0),
                Color.rgb(105, 240, 174), Color.rgb(0, 229, 255), Color.rgb(68, 138, 255),
                Color.rgb(179, 136, 255), Color.rgb(255, 128, 171), Color.BLACK)
        val swatches = mutableListOf<Pair<View, Int>>()

        fun swatch(color: Int, label: String?) = FrameLayout(ctx).apply {
            if (label != null) addView(text(label, 11f, colorText, bold = true).apply { gravity = Gravity.CENTER },
                                       FrameLayout.LayoutParams(-1, -1))
            setOnClickListener { edit { set(color) } }
            layoutParams = ViewGroup.LayoutParams(dp(38), dp(38))
        }

        val flow = FlowLayout(ctx, dp(10))
        val auto = swatch(0, "Auto").also { flow.addView(it); swatches += it to 0 }
        palette.forEach { c -> flow.addView(swatch(c, null).also { swatches += it to (c or 0xFF000000.toInt()) }) }
        val custom = FrameLayout(ctx).apply {
            addView(text("+", 18f, colorText, bold = true).apply { gravity = Gravity.CENTER }, FrameLayout.LayoutParams(-1, -1))
            setOnClickListener {
                pickColor(title, get().takeIf { it != 0 } ?: Color.WHITE) { picked -> edit { set(picked) } }
            }
            layoutParams = ViewGroup.LayoutParams(dp(38), dp(38))
        }
        flow.addView(custom)

        refreshers += {
            val now = get()
            val isPalette = now == 0 || palette.any { (it or 0xFF000000.toInt()) == now }
            swatches.forEach { (view, color) ->
                val selected = color == now
                view.background = if (color == 0) rounded(colorChip, 19, if (selected) accent else colorStroke, if (selected) 3 else 1)
                else rounded(color, 19, if (selected) accent else colorStroke, if (selected) 3 else 1)
            }
            custom.background = if (!isPalette) rounded(now, 19, accent, 3) else rounded(colorChip, 19, colorStroke, 1)
        }
        return block(title, flow)
    }

    private fun pickColor(title: String, initial: Int, onPicked: (Int) -> Unit) {
        val hsv = FloatArray(3).also { Color.colorToHSV(initial, it) }
        val swatch = View(ctx)
        fun current() = Color.HSVToColor(hsv)
        fun paint() { swatch.background = rounded(current(), 14) }
        fun bar(label: String, max: Int, start: Int, onChange: (Int) -> Unit) = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(8), 0, 0)
            addView(TextView(ctx).apply { text = label })
            addView(SeekBar(ctx).apply {
                this.max = max
                progress = start
                setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                    override fun onProgressChanged(sb: SeekBar, p: Int, fromUser: Boolean) { onChange(p); paint() }
                    override fun onStartTrackingTouch(sb: SeekBar) = Unit
                    override fun onStopTrackingTouch(sb: SeekBar) = Unit
                })
            })
        }
        val body = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), dp(16), dp(24), 0)
            addView(swatch, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(48)))
            addView(bar("Hue", 360, hsv[0].roundToInt()) { hsv[0] = it.toFloat() })
            addView(bar("Saturation", 100, (hsv[1] * 100).roundToInt()) { hsv[1] = it / 100f })
            addView(bar("Brightness", 100, (hsv[2] * 100).roundToInt()) { hsv[2] = it / 100f })
        }
        paint()
        AlertDialog.Builder(ctx)
            .setTitle(title)
            .setView(body)
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Apply") { _, _ -> onPicked(current() or 0xFF000000.toInt()) }
            .show()
    }

    override fun onCreate(savedInstanceState: android.os.Bundle?) {
        super.onCreate(savedInstanceState)
        val window = window ?: return
        window.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        window.setBackgroundDrawable(ColorDrawable(colorBackground))
        WindowCompat.setDecorFitsSystemWindows(window, false)

        val root = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        ViewCompat.setOnApplyWindowInsetsListener(root) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            v.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            insets
        }

        val back = ImageView(ctx).apply {
            setImageDrawable(AppCompatResources.getDrawable(ctx, R.drawable.ic_arrow_back)?.mutate())
            setPadding(dp(10), dp(10), dp(10), dp(10))
            setColorFilter(Color.WHITE)
            setOnClickListener { dismiss() }
            refreshers += { background = rounded(ColorUtils.setAlphaComponent(accent, 70), 22) }
        }
        val reset = ImageView(ctx).apply {
            setImageDrawable(AppCompatResources.getDrawable(ctx, R.drawable.ic_reset)?.mutate())
            setColorFilter(colorTextSecondary)
            setPadding(dp(10), dp(10), dp(10), dp(10))
            setOnClickListener { resetToDefaults() }
        }
        root.addView(LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(16), dp(10), dp(16), dp(6))
            addView(back, LinearLayout.LayoutParams(dp(44), dp(44)))
            addView(LinearLayout(ctx).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(12), 0, 0, 0)
                addView(text("Player Lyrics Style", 22f, colorText, bold = true))
                addView(text("The line shown on the player screen", 12.5f, colorTextSecondary))
            }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            addView(reset, LinearLayout.LayoutParams(dp(44), dp(44)))
        })

        root.addView(text("Live Preview", 12.5f, colorTextSecondary).apply { setPadding(dp(20), dp(4), dp(16), dp(8)) })

        preview = LrcLineView(ctx).apply {
            setLrcData(LyricsStyle.previewLyrics(), 0)
        }
        val previewCard = FrameLayout(ctx).apply {
            background = rounded(Color.rgb(28, 24, 40), 24, colorStroke)
            setPadding(dp(12), dp(12), dp(12), dp(12))
            addView(preview, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                                                       ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER))
        }
        root.addView(previewCard, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(120)).apply {
            marginStart = dp(16)
            marginEnd = dp(16)
        })

        val content = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), 0, dp(16), dp(32))
            addView(sectionHeader(R.drawable.ic_text_fields, "Text"))
            addView(card(
                    slider("Text size", 12, 28, { PlayerPreferences.getCaptionTextSize().roundToInt() }, " sp") {
                        edit { PlayerPreferences.setCaptionTextSize(it.toFloat()) }
                    },
                    block("Line animation", chips(listOf("None", "Fade", "Slide up", "Pop"),
                                                  { PlayerPreferences.getCaptionTransition() }) {
                        edit { PlayerPreferences.setCaptionTransition(it) }
                    }),
                    slider("Backdrop opacity", 0, 100, { PlayerPreferences.getCaptionPillOpacity() }, "%") {
                        edit { PlayerPreferences.setCaptionPillOpacity(it) }
                    }))
            addView(sectionHeader(R.drawable.ic_palette, "Colors"))
            addView(card(
                    block("Text colour mode (when Text colour is Auto)", chips(listOf("Theme", "Accent", "White"),
                                                  { PlayerPreferences.getCaptionColorMode() }) {
                        edit { PlayerPreferences.setCaptionColorMode(it) }
                    }),
                    colorRow("Text colour", { PlayerPreferences.getCaptionTextColor() }) {
                        PlayerPreferences.setCaptionTextColor(it)
                    },
                    colorRow("Glow colour", { PlayerPreferences.getCaptionGlowColor() }) {
                        PlayerPreferences.setCaptionGlowColor(it)
                    },
                    colorRow("Highlight box colour", { PlayerPreferences.getCaptionBoxColor() }) {
                        PlayerPreferences.setCaptionBoxColor(it)
                    }))
            addView(sectionHeader(R.drawable.ic_waves, "Effects"))
            addView(card(
                    switchRow("Glow", "Soft halo behind the text", { PlayerPreferences.isCaptionGlow() }) {
                        edit { PlayerPreferences.setCaptionGlow(it) }
                    },
                    switchRow("Highlight box", "A box that glides along the sung word",
                              { PlayerPreferences.isCaptionBox() }) {
                        edit { PlayerPreferences.setCaptionBox(it) }
                    },
                    slider("Highlight box opacity", 5, 100, { PlayerPreferences.getCaptionBoxOpacity() }, "%",
                           enabled = { PlayerPreferences.isCaptionBox() }) {
                        edit { PlayerPreferences.setCaptionBoxOpacity(it) }
                    }))
        }
        root.addView(ScrollView(ctx).apply {
            isVerticalScrollBarEnabled = false
            addView(content)
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))

        setContentView(root)
        runCatching { WindowCompat.getInsetsController(window, root).isAppearanceLightStatusBars = false }
        PlayerLyricsStyle.apply(preview)
        refreshers.forEach { it() }
    }

    private fun resetToDefaults() = edit {
        PlayerPreferences.setCaptionTextSize(16f)
        PlayerPreferences.setCaptionTransition(0)
        PlayerPreferences.setCaptionBox(false)
        PlayerPreferences.setCaptionBoxOpacity(35)
        PlayerPreferences.setCaptionGlow(false)
        PlayerPreferences.setCaptionColorMode(0)
        PlayerPreferences.setCaptionPillOpacity(70)
        PlayerPreferences.setCaptionTextColor(0)
        PlayerPreferences.setCaptionGlowColor(0)
        PlayerPreferences.setCaptionBoxColor(0)
    }

    override fun onStart() {
        super.onStart()
        previewStart = SystemClock.uptimeMillis()
        lastPosition = 0L
        handler.post(previewTick)
    }

    override fun onStop() {
        handler.removeCallbacks(previewTick)
        super.onStop()
    }
}
