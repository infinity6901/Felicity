package app.simple.felicity.dialogs.player

import android.app.AlertDialog
import android.content.Context
import android.text.Editable
import android.text.TextWatcher
import android.widget.ArrayAdapter
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.Toast
import app.simple.felicity.R
import app.simple.felicity.engine.managers.EqualizerManager
import app.simple.felicity.managers.AutoEq
import app.simple.felicity.preferences.EqualizerPreferences
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * Pick a headphone model and set the ten equalizer sliders to the curve AutoEq measured for it.
 * Built in code, a search box over a list, so it needs no layout of its own.
 */
object AutoEqDialog {

    fun show(context: Context, scope: CoroutineScope) {
        val search = EditText(context).apply {
            hint = context.getString(R.string.autoeq_search_hint)
            setSingleLine()
        }
        val list = ListView(context)
        val adapter = ArrayAdapter<String>(context, android.R.layout.simple_list_item_1, mutableListOf())
        list.adapter = adapter
        val root = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            val pad = (16 * resources.displayMetrics.density).toInt()
            setPadding(pad, pad, pad, 0)
            addView(search)
            addView(list, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, (320 * resources.displayMetrics.density).toInt()))
        }
        val dialog = AlertDialog.Builder(context)
            .setTitle(R.string.autoeq_title)
            .setView(root)
            .setNegativeButton(R.string.cancel, null)
            .create()
        dialog.show()

        var models: List<AutoEq.Model> = emptyList()
        var shown: List<AutoEq.Model> = emptyList()
        adapter.add(context.getString(R.string.autoeq_loading))
        scope.launch {
            val all = AutoEq.models(context)
            adapter.clear()
            if (all == null) {
                adapter.add(context.getString(R.string.autoeq_offline))
            } else {
                models = all
                adapter.add(context.getString(R.string.autoeq_type_to_search))
            }
        }
        search.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
            override fun afterTextChanged(s: Editable?) {
                shown = AutoEq.search(models, s?.toString().orEmpty())
                adapter.clear()
                adapter.addAll(shown.map { m -> if (m.rig != null) "${m.name}  ·  ${m.source} (${m.rig})" else "${m.name}  ·  ${m.source}" })
            }
        })
        list.setOnItemClickListener { _, _, position, _ ->
            val model = shown.getOrNull(position) ?: return@setOnItemClickListener
            scope.launch {
                when (val curve = AutoEq.curve(model)) {
                    is AutoEq.Curve.Gains -> {
                        val gains = curve.db.toFloatArray()
                        EqualizerManager.setEqMode(EqualizerPreferences.EQ_MODE_GRAPHIC)
                        EqualizerPreferences.setAllBandGains(gains)
                        for (i in gains.indices) EqualizerManager.setBandGain(i, gains[i], persist = false)
                        // The curve's own peak boost would clip, so the preamp takes it off.
                        EqualizerManager.setPreamp(-(gains.maxOrNull() ?: 0f).coerceAtLeast(0f))
                        EqualizerPreferences.setEqEnabled(true)
                        Toast.makeText(context, context.getString(R.string.autoeq_applied, model.name), Toast.LENGTH_SHORT).show()
                        dialog.dismiss()
                    }
                    AutoEq.Curve.NotTenBands -> Toast.makeText(context, R.string.autoeq_not_ten_bands, Toast.LENGTH_LONG).show()
                    AutoEq.Curve.Unreachable -> Toast.makeText(context, R.string.autoeq_offline, Toast.LENGTH_LONG).show()
                }
            }
        }
    }
}
