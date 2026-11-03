package app.simple.felicity.dialogs.lyrics

import android.app.AlertDialog
import android.content.Context
import android.graphics.Color
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.EditText
import android.widget.Button
import android.widget.SeekBar
import android.widget.Switch
import android.widget.TextView
import app.simple.felicity.decorations.lrc.view.FelicityLrcView
import app.simple.felicity.preferences.LyricsPreferences

/** A compact live-style editor for Felicity's native LRC renderer. */
object LyricsStudioDialog {
    private fun applyPreset(size: Float, spacing: Float, accent: Int, view: FelicityLrcView) {
        LyricsPreferences.setLrcTextSize(size)
        LyricsPreferences.setStudioLineSpacing(spacing)
        view.setTextSizes(size, size)
        view.setLineSpacing(spacing)
        view.setCurrentTextColor(accent)
    }

    fun show(context: Context, lyricsView: FelicityLrcView, onTranslationChanged: () -> Unit = {}, onPhraseSearch: (String) -> Unit = {}) {
        val root = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(28, 12, 28, 8)
        }
        fun label(text: String) = TextView(context).apply {
            this.text = text
            textSize = 14f
            setTextColor(Color.WHITE)
            setPadding(0, 12, 0, 2)
        }
        fun slider(title: String, min: Int, max: Int, initial: Int, onChange: (Int) -> Unit) {
            root.addView(label(title))
            root.addView(SeekBar(context).apply {
                this.max = max - min
                progress = (initial - min).coerceIn(0, max - min)
                setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                    override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) { onChange(progress + min) }
                    override fun onStartTrackingTouch(seekBar: SeekBar?) = Unit
                    override fun onStopTrackingTouch(seekBar: SeekBar?) = Unit
                })
            }, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        }
        fun toggle(title: String, checked: Boolean, onChange: (Boolean) -> Unit) {
            root.addView(Switch(context).apply {
                text = title
                isChecked = checked
                setTextColor(Color.WHITE)
                setOnCheckedChangeListener { _, value -> onChange(value) }
            })
        }
        slider("Lyrics size", 12, 34, LyricsPreferences.getLrcTextSize().toInt()) {
            LyricsPreferences.setLrcTextSize(it.toFloat()); lyricsView.setTextSizes(it.toFloat(), it.toFloat())
        }
        slider("Line spacing", 0, 30, LyricsPreferences.getStudioLineSpacing().toInt()) {
            LyricsPreferences.setStudioLineSpacing(it.toFloat()); lyricsView.setLineSpacing(it.toFloat())
        }
        slider("Inactive line opacity", 15, 90, (LyricsPreferences.getStudioInactiveAlpha() * 100).toInt()) {
            LyricsPreferences.setStudioInactiveAlpha(it / 100f)
            lyricsView.setInactiveLineAlpha(it / 100f)
            val alpha = (255 * it / 100f).toInt().coerceIn(30, 230)
            lyricsView.setNormalTextColor(Color.argb(alpha, 220, 220, 220))
        }
        slider("Glow radius", 0, 28, LyricsPreferences.getStudioGlowRadius().toInt()) {
            LyricsPreferences.setStudioGlowRadius(it.toFloat())
            lyricsView.setKaraokeGlow(LyricsPreferences.isStudioGlowEnabled(), it.toFloat(), LyricsPreferences.getStudioGlowIntensity(), Color.MAGENTA)
        }
        slider("Glow intensity", 0, 100, (LyricsPreferences.getStudioGlowIntensity() * 100).toInt()) {
            LyricsPreferences.setStudioGlowIntensity(it / 100f)
            lyricsView.setKaraokeGlow(LyricsPreferences.isStudioGlowEnabled(), LyricsPreferences.getStudioGlowRadius(), it / 100f, Color.MAGENTA)
        }
        slider("Glass panel opacity", 0, 45, (LyricsPreferences.getStudioGlassAlpha() * 100).toInt()) {
            LyricsPreferences.setStudioGlassAlpha(it / 100f)
            lyricsView.setGlassBackground(LyricsPreferences.isStudioGlassBackgroundEnabled(), it / 100f)
        }
        slider("Active line emphasis", 100, 120, 100) {
            lyricsView.setActiveLineScale(it / 100f)
        }
        slider("Edge blur strength", 0, 200, 100) {
            lyricsView.setEdgeBlurIntensity(it / 100f)
        }
        slider("Background dimming", 0, 75, 0) {
            lyricsView.setBackgroundDim(it / 100f)
        }
        root.addView(label("Word-by-word karaoke timing is used automatically when the selected lyrics source provides word timestamps."))
        toggle("Glow accent", LyricsPreferences.isStudioGlowEnabled()) {
            LyricsPreferences.setStudioGlowEnabled(it)
            lyricsView.setKaraokeGlow(it, LyricsPreferences.getStudioGlowRadius(), LyricsPreferences.getStudioGlowIntensity(), Color.MAGENTA)
            lyricsView.setCurrentTextColor(if (it) Color.rgb(255, 80, 210) else Color.WHITE)
        }
        toggle("Progressive word fill", LyricsPreferences.isStudioProgressiveFillEnabled()) {
            LyricsPreferences.setStudioProgressiveFillEnabled(it)
            lyricsView.setProgressiveWordFill(it)
        }
        toggle("Frosted glass lyrics panel", LyricsPreferences.isStudioGlassBackgroundEnabled()) {
            LyricsPreferences.setStudioGlassBackgroundEnabled(it)
            lyricsView.setGlassBackground(it, LyricsPreferences.getStudioGlassAlpha())
        }
        toggle("Show translated lyrics when available", LyricsPreferences.isStudioTranslationEnabled()) {
            LyricsPreferences.setStudioTranslationEnabled(it)
            onTranslationChanged()
        }
        val phraseButton = Button(context).apply {
            text = "Search songs by lyric phrase"
            setOnClickListener {
                val input = EditText(context).apply { hint = "Type a lyric line or phrase" }
                AlertDialog.Builder(context).setTitle("Find a song from its lyrics")
                    .setView(input)
                    .setNegativeButton("Cancel", null)
                    .setPositiveButton("Search") { _, _ -> input.text.toString().trim().takeIf { it.isNotBlank() }?.let(onPhraseSearch) }
                    .show()
            }
        }
        root.addView(phraseButton)
        // Apply persisted renderer settings immediately when Studio opens.
        lyricsView.setKaraokeGlow(LyricsPreferences.isStudioGlowEnabled(), LyricsPreferences.getStudioGlowRadius(), LyricsPreferences.getStudioGlowIntensity(), Color.MAGENTA)
        lyricsView.setProgressiveWordFill(LyricsPreferences.isStudioProgressiveFillEnabled())
        lyricsView.setGlassBackground(LyricsPreferences.isStudioGlassBackgroundEnabled(), LyricsPreferences.getStudioGlassAlpha())
        lyricsView.setInactiveLineAlpha(LyricsPreferences.getStudioInactiveAlpha())
        root.addView(label("Style preset"))
        val presets = android.widget.RadioGroup(context).apply {
            orientation = android.widget.RadioGroup.VERTICAL
            listOf("Classic", "Karaoke", "Minimal", "Focus", "Cinematic").forEachIndexed { index, title ->
                addView(android.widget.RadioButton(context).apply {
                    id = 100 + index
                    text = title
                    setTextColor(Color.WHITE)
                })
            }
            setOnCheckedChangeListener { _, checked ->
                when (checked - 100) {
                    0 -> applyPreset(16f, 8f, Color.WHITE, lyricsView)
                    1 -> applyPreset(24f, 16f, Color.rgb(100, 210, 255), lyricsView)
                    2 -> applyPreset(14f, 2f, Color.WHITE, lyricsView)
                    3 -> applyPreset(26f, 18f, Color.WHITE, lyricsView)
                    4 -> applyPreset(23f, 20f, Color.rgb(180, 140, 255), lyricsView)
                }
            }
        }
        root.addView(presets)
        root.addView(label("Alignment"))
        val alignment = android.widget.RadioGroup(context).apply {
            orientation = android.widget.RadioGroup.HORIZONTAL
            val values = listOf("Left", "Center", "Right")
            values.forEachIndexed { index, text ->
                addView(android.widget.RadioButton(context).apply {
                    id = index + 1; this.text = text; setTextColor(Color.WHITE)
                    isChecked = LyricsPreferences.getLrcAlignment() == index
                })
            }
            setOnCheckedChangeListener { _, checked ->
                val value = (checked - 1).coerceIn(0, 2)
                LyricsPreferences.setLrcAlignment(value)
                lyricsView.setTextAlignment(when (value) {
                    0 -> FelicityLrcView.Alignment.LEFT
                    2 -> FelicityLrcView.Alignment.RIGHT
                    else -> FelicityLrcView.Alignment.CENTER
                }, true)
            }
        }
        root.addView(alignment)
        AlertDialog.Builder(context)
            .setTitle("Lyrics Studio")
            .setMessage("Customize the live lyrics experience. Changes preview immediately.")
            .setView(root)
            .setPositiveButton("Done", null)
            .setNeutralButton("Reset") { _, _ ->
                LyricsPreferences.setLrcTextSize(16f)
                LyricsPreferences.setLrcAlignment(LyricsPreferences.CENTER)
                LyricsPreferences.setStudioLineSpacing(8f)
                LyricsPreferences.setStudioInactiveAlpha(0.55f)
                LyricsPreferences.setStudioGlowEnabled(false)
                LyricsPreferences.setStudioGlowRadius(12f)
                LyricsPreferences.setStudioGlowIntensity(0.75f)
                LyricsPreferences.setStudioProgressiveFillEnabled(true)
                LyricsPreferences.setStudioGlassBackgroundEnabled(false)
                LyricsPreferences.setStudioGlassAlpha(0.16f)
                LyricsPreferences.setStudioTranslationEnabled(false)
                lyricsView.setTextSizes(16f, 16f)
                lyricsView.setLineSpacing(8f)
                lyricsView.setNormalTextColor(Color.LTGRAY)
                lyricsView.setCurrentTextColor(Color.WHITE)
                lyricsView.setKaraokeGlow(false, 12f, 0.75f, Color.MAGENTA)
                lyricsView.setProgressiveWordFill(true)
                lyricsView.setGlassBackground(false, 0.16f)
                lyricsView.setInactiveLineAlpha(0.55f)
                lyricsView.setActiveLineScale(1f)
                lyricsView.setEdgeBlurIntensity(1f)
                lyricsView.setBackgroundDim(0f)
                lyricsView.setTextAlignment(FelicityLrcView.Alignment.CENTER, true)
            }
            .show()
    }
}
