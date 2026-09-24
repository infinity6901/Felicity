package app.simple.felicity.preferences

import androidx.core.content.edit
import app.simple.felicity.manager.SharedPreferences

/**
 * Persists the user's own free Jamendo `client_id` (from https://devportal.jamendo.com),
 * entered via Settings rather than baked into the app at build time.
 *
 * [JamendoRepository] treats a blank/unset value exactly like the old hardcoded placeholder
 * did — search silently returns an empty list instead of crashing or making a doomed request.
 *
 * @author Hamza417
 */
object JamendoPreferences {

    private const val CLIENT_ID = "jamendo_client_id"

    fun setClientId(clientId: String) {
        SharedPreferences.getSharedPreferences().edit {
            putString(CLIENT_ID, clientId.trim())
        }
    }

    fun getClientId(): String {
        return SharedPreferences.getSharedPreferences().getString(CLIENT_ID, "").orEmpty()
    }

    fun hasClientId(): Boolean = getClientId().isNotBlank()
}
