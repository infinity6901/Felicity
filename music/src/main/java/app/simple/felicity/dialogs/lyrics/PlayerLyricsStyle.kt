package app.simple.felicity.dialogs.lyrics

import android.content.Context
import android.view.View
import android.widget.CompoundButton
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.SeekBar
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.widget.SwitchCompat
import app.simple.felicity.decorations.lrc.view.LrcLineView
import app.simple.felicity.preferences.PlayerPreferences

/**
 * Styling for the single-line lyric caption shown on the player screen: line transition,
 * the gliding highlight box, glow, text colour, size and backdrop. Everything is persisted
 * in [PlayerPreferences] and applied with [apply].
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
    }

    /** Shows the customisation dialog; [onChanged] runs after every tweak so the caption updates live. */
    fun show(context: Context, onChanged: () -> Unit) {
        val dp = context.resources.displayMetrics.density
        val pad = (16 * dp).toInt()
        val root = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, pad)
        }

        fun label(text: String) = TextView(context).apply {
            this.text = text
            textSize = 14f
            setPadding(0, (12 * dp).toInt(), 0, (4 * dp).toInt())
        }

        fun switchRow(text: String, checked: Boolean, onToggle: (Boolean) -> Unit) = SwitchCompat(context).apply {
            this.text = text
            isChecked = checked
            setPadding(0, (6 * dp).toInt(), 0, (6 * dp).toInt())
            setOnCheckedChangeListener { _: CompoundButton, value: Boolean -> onToggle(value); onChanged() }
        }

        fun slider(title: String, min: Int, max: Int, value: Int, unit: String, onValue: (Int) -> Unit): View {
            val box = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
            val head = label("$title: $value$unit")
            val bar = SeekBar(context).apply {
                this.max = max - min
                progress = value - min
                setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                    override fun onProgressChanged(sb: SeekBar?, p: Int, fromUser: Boolean) {
                        head.text = "$title: ${p + min}$unit"
                        if (fromUser) { onValue(p + min); onChanged() }
                    }
                    override fun onStartTrackingTouch(sb: SeekBar?) = Unit
                    override fun onStopTrackingTouch(sb: SeekBar?) = Unit
                })
            }
            box.addView(head); box.addView(bar)
            return box
        }

        fun choice(title: String, options: List<String>, selected: Int, onPick: (Int) -> Unit): View {
            val box = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
            box.addView(label(title))
            val group = RadioGroup(context).apply { orientation = RadioGroup.VERTICAL }
            options.forEachIndexed { i, name ->
                group.addView(RadioButton(context).apply { text = name; id = View.generateViewId(); tag = i })
            }
            (group.getChildAt(selected) as? RadioButton)?.isChecked = true
            group.setOnCheckedChangeListener { g, checkedId ->
                val index = g.findViewById<View>(checkedId)?.tag as? Int ?: return@setOnCheckedChangeListener
                onPick(index); onChanged()
            }
            box.addView(group)
            return box
        }

        root.addView(slider("Text size", 12, 28, PlayerPreferences.getCaptionTextSize().toInt(), " sp") {
            PlayerPreferences.setCaptionTextSize(it.toFloat())
        })
        root.addView(choice("Line animation", listOf("None", "Fade", "Slide up", "Pop"),
                PlayerPreferences.getCaptionTransition()) { PlayerPreferences.setCaptionTransition(it) })
        root.addView(choice("Text colour", listOf("Theme", "Accent", "White"),
                PlayerPreferences.getCaptionColorMode()) { PlayerPreferences.setCaptionColorMode(it) })
        root.addView(switchRow("Highlight box follows the word", PlayerPreferences.isCaptionBox()) {
            PlayerPreferences.setCaptionBox(it)
        })
        root.addView(slider("Highlight box opacity", 5, 100, PlayerPreferences.getCaptionBoxOpacity(), "%") {
            PlayerPreferences.setCaptionBoxOpacity(it)
        })
        root.addView(switchRow("Glow", PlayerPreferences.isCaptionGlow()) { PlayerPreferences.setCaptionGlow(it) })
        root.addView(slider("Backdrop opacity", 0, 100, PlayerPreferences.getCaptionPillOpacity(), "%") {
            PlayerPreferences.setCaptionPillOpacity(it)
        })

        val scroll = android.widget.ScrollView(context).apply { addView(root) }
        AlertDialog.Builder(context)
            .setTitle("Player lyrics style")
            .setView(scroll)
            .setPositiveButton(android.R.string.ok, null)
            .show()
    }
}
