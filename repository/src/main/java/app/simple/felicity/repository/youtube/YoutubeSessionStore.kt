package app.simple.felicity.repository.youtube

import android.content.Context
import androidx.core.content.edit
import app.simple.felicity.manager.SharedPreferences
import org.json.JSONObject

/**
 * Wraps [YoutubeSession] persistence on top of Felicity's existing encrypted
 * SharedPreferences facility (see `preferences` module), so cookies never sit in
 * plaintext prefs on disk.
 */
object YoutubeSessionStore {

    private const val KEY_SESSION = "youtube_session_v1"

    @Volatile
    private var cached: YoutubeSession? = null

    fun get(context: Context): YoutubeSession {
        cached?.let { return it }

        SharedPreferences.initEncrypted(context)
        val raw = SharedPreferences.getEncryptedSharedPreferences().getString(KEY_SESSION, null)
        val session = raw?.let { parse(it) } ?: YoutubeSession()
        cached = session
        return session
    }

    fun save(context: Context, session: YoutubeSession) {
        cached = session
        SharedPreferences.initEncrypted(context)
        SharedPreferences.getEncryptedSharedPreferences().edit {
            putString(KEY_SESSION, serialize(session))
        }
    }

    fun signOut(context: Context) {
        save(context, YoutubeSession())
    }

    private fun serialize(session: YoutubeSession): String {
        val json = JSONObject()
        val cookies = JSONObject()
        session.rawCookies.forEach { (k, v) -> cookies.put(k, v) }
        json.put("cookies", cookies)
        json.put("visitorData", session.visitorData)
        json.put("dataSyncId", session.dataSyncId)
        return json.toString()
    }

    private fun parse(raw: String): YoutubeSession {
        return runCatching {
            val json = JSONObject(raw)
            val cookiesJson = json.optJSONObject("cookies")
            val cookies = mutableMapOf<String, String>()
            cookiesJson?.keys()?.forEach { key ->
                cookies[key] = cookiesJson.getString(key)
            }
            YoutubeSession(
                rawCookies = cookies,
                visitorData = json.optString("visitorData").takeIf { it.isNotBlank() },
                dataSyncId = json.optString("dataSyncId").takeIf { it.isNotBlank() },
            )
        }.getOrDefault(YoutubeSession())
    }
}
