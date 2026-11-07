package app.simple.felicity.ui.preferences.main

import android.content.SharedPreferences
import android.os.Bundle
import app.simple.felicity.R
import app.simple.felicity.adapters.preference.GenericPreferencesAdapter
import app.simple.felicity.extensions.fragments.PreferenceFragment
import app.simple.felicity.models.Preference
import app.simple.felicity.widget.WidgetGlass
import app.simple.felicity.widget.WidgetLyricStyle

class Widgets : PreferenceFragment() {

    override val titleRes: Int
        get() = R.string.widgets

    override val iconRes: Int
        get() = R.drawable.ic_widgets

    override fun getPreferencesList(): List<Preference> {
        return createWidgetsPanel()
    }

    override fun onSharedPreferenceChanged(sharedPreferences: SharedPreferences?, key: String?) {
        super.onSharedPreferenceChanged(sharedPreferences, key)
        // The list itself changes with these switches: one shared slider pair vs one pair per widget,
        // and the highlight box opacity only while the box is on.
        if (key == WidgetGlass.KEY_SHARED || key == WidgetLyricStyle.KEY_BOX) {
            view?.post { rebuildList() }
        }
    }

    private fun rebuildList() {
        val recycler = runCatching { binding.recyclerView }.getOrNull() ?: return
        val state = recycler.layoutManager?.onSaveInstanceState()
        recycler.adapter = GenericPreferencesAdapter(getPreferencesList())
        if (state != null) {
            recycler.layoutManager?.onRestoreInstanceState(state)
        }
    }

    companion object {
        fun newInstance(): Widgets {
            val args = Bundle()
            val fragment = Widgets()
            fragment.arguments = args
            return fragment
        }

        const val TAG = "Widgets"
    }
}
