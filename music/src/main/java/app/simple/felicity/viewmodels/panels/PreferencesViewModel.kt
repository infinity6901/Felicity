package app.simple.felicity.viewmodels.panels

import android.app.Application
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.viewModelScope
import app.simple.felicity.R
import app.simple.felicity.extensions.viewmodels.WrappedViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class PreferencesViewModel(application: Application) : WrappedViewModel(application) {

    private val preference: MutableLiveData<List<Preference>> by lazy {
        MutableLiveData<List<Preference>>().also {
            loadPreferences()
        }
    }

    fun getPreferences(): LiveData<List<Preference>> {
        return preference
    }

    private fun loadPreferences() {
        viewModelScope.launch(Dispatchers.Default) {
            val preferences = mutableListOf<Preference>()

            preferences.add(
                    Preference(
                            title = R.string.appearance,
                            description = R.string.appearance_desc,
                            icon = R.drawable.ic_water_drop
                    )
            )

            preferences.add(
                    Preference(
                            title = R.string.configration,
                            description = R.string.configuration_desc,
                            icon = R.drawable.ic_app_settings
                    )
            )

            preferences.add(
                    Preference(
                            title = R.string.behavior,
                            description = R.string.behavior_desc,
                            icon = R.drawable.ic_behavior
                    )
            )

            preferences.add(
                    Preference(
                            title = R.string.user_interface,
                            description = R.string.user_interface_desc,
                            icon = R.drawable.ic_carousel
                    )
            )

            preferences.add(
                    Preference(
                            title = R.string.audio,
                            description = R.string.audio_desc,
                            icon = R.drawable.ic_volume_up
                    )
            )

            preferences.add(
                    Preference(
                            title = R.string.library,
                            description = R.string.library_desc,
                            icon = R.drawable.ic_library
                    )
            )

            preferences.add(
                    Preference(
                            title = R.string.widgets,
                            description = R.string.widgets_desc,
                            icon = R.drawable.ic_widgets
                    )
            )

            preferences.add(
                    Preference(
                            title = R.string.accessibility,
                            description = R.string.accessibility_desc,
                            icon = R.drawable.ic_accessibility
                    )
            )

            preferences.add(
                    Preference(
                            title = R.string.about,
                            description = R.string.about_desc,
                            icon = R.drawable.ic_info
                    )
            )

            if (app.simple.felicity.BuildConfig.DEBUG) {
                preferences.add(
                        Preference(
                                title = R.string.debug_logs,
                                description = R.string.debug_logs_desc,
                                icon = R.drawable.ic_app_settings
                        )
                )
            }

            preference.postValue(preferences)
        }
    }

    companion object {
        data class Preference(
                val title: Int,
                val description: Int,
                val icon: Int,
        )
    }
}