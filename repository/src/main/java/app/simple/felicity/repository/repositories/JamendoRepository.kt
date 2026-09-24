package app.simple.felicity.repository.repositories

import android.util.Log
import app.simple.felicity.preferences.JamendoPreferences
import app.simple.felicity.repository.factories.TaggedSocketFactory
import app.simple.felicity.repository.online.OnlineSource
import app.simple.felicity.repository.online.OnlineTrack
import com.google.gson.Gson
import com.google.gson.annotations.SerializedName
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/**
 * [Jamendo](https://www.jamendo.com) — independent artists distributing under Creative
 * Commons and Jamendo's own free-for-personal-use licenses. Every track this returns is
 * legitimately free to stream; Jamendo's business model is licensing tracks to businesses
 * for commercial use, not gating personal listening.
 *
 * Requires a free `client_id` from https://devportal.jamendo.com, entered by the user via
 * Settings and read through [JamendoPreferences.getClientId]. Every call below returns an
 * empty list rather than making a doomed request while none is set.
 *
 * @author Hamza417
 */
@Singleton
class JamendoRepository @Inject constructor() {

    private val networkTag = 0x1003

    private val client: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .socketFactory(TaggedSocketFactory(networkTag))
            .build()
    }

    private val gson: Gson by lazy { Gson() }

    /**
     * Jamendo's most-popular tracks over the last month — shown by default before the
     * user has typed anything, using the same `order` mechanism Jamendo's own site uses
     * for its "popular this month" charts.
     */
    suspend fun trending(limit: Int = 30): List<OnlineTrack> = withContext(Dispatchers.IO) {
        if (JamendoPreferences.hasClientId().not()) {
            return@withContext emptyList()
        }

        try {
            val url = "$BASE_URL/tracks/".toHttpUrlOrNull()
                ?.newBuilder()
                ?.addQueryParameter("client_id", JamendoPreferences.getClientId())
                ?.addQueryParameter("format", "json")
                ?.addQueryParameter("order", "popularity_month")
                ?.addQueryParameter("limit", limit.toString())
                ?.addQueryParameter("audioformat", "mp32")
                ?.build() ?: return@withContext emptyList()

            val body = get(url.toString()) ?: return@withContext emptyList()
            val response = gson.fromJson(body, JamendoSearchResponse::class.java)

            response.results
                ?.filter { it.audio.isNullOrBlank().not() }
                ?.map { it.toOnlineTrack() }
                .orEmpty()
        } catch (e: Exception) {
            Log.w(TAG, "Jamendo trending fetch failed", e)
            emptyList()
        }
    }

    /**
     * Searches Jamendo's catalogue by free-text query (matches track and artist names).
     *
     * @param query Free-text search.
     * @param limit Max results (Jamendo allows up to 200 per request).
     */
    suspend fun search(query: String, limit: Int = 30): List<OnlineTrack> = withContext(Dispatchers.IO) {
        if (query.isBlank()) return@withContext emptyList()

        if (JamendoPreferences.hasClientId().not()) {
            Log.w(TAG, "Jamendo search skipped — no client_id set in Settings.")
            return@withContext emptyList()
        }

        try {
            val url = "$BASE_URL/tracks/".toHttpUrlOrNull()
                ?.newBuilder()
                ?.addQueryParameter("client_id", JamendoPreferences.getClientId())
                ?.addQueryParameter("format", "json")
                ?.addQueryParameter("search", query)
                ?.addQueryParameter("limit", limit.toString())
                ?.addQueryParameter("audioformat", "mp32")
                ?.build() ?: return@withContext emptyList()

            val body = get(url.toString()) ?: return@withContext emptyList()
            val response = gson.fromJson(body, JamendoSearchResponse::class.java)

            response.results
                ?.filter { it.audio.isNullOrBlank().not() }
                ?.map { it.toOnlineTrack() }
                .orEmpty()
        } catch (e: Exception) {
            Log.w(TAG, "Jamendo search failed for: $query", e)
            emptyList()
        }
    }

    private fun JamendoTrack.toOnlineTrack(): OnlineTrack {
        return OnlineTrack(
                source = OnlineSource.JAMENDO,
                id = id,
                title = name,
                subtitle = artistName,
                artworkUrl = image?.ifBlank { null },
                streamUrl = audio,
                durationMs = duration?.let { it * 1000L } // Jamendo returns whole seconds
        )
    }

    private fun get(url: String): String? {
        return try {
            val request = Request.Builder().url(url).build()
            client.newCall(request).execute().use { response ->
                if (response.isSuccessful) response.body.string()
                else {
                    Log.w(TAG, "HTTP ${response.code} for URL: $url"); null
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Network error for URL: $url", e)
            null
        }
    }

    private data class JamendoSearchResponse(
            @SerializedName("results") val results: List<JamendoTrack>?
    )

    /** Mirrors only the fields this repository actually uses — see developer.jamendo.com/v3.0/tracks for the full schema. */
    private data class JamendoTrack(
            @SerializedName("id") val id: String,
            @SerializedName("name") val name: String,
            @SerializedName("artist_name") val artistName: String,
            @SerializedName("image") val image: String?,
            @SerializedName("audio") val audio: String?,
            @SerializedName("duration") val duration: Long?
    )

    companion object {
        private const val TAG = "JamendoRepository"
        private const val BASE_URL = "https://api.jamendo.com/v3.0"
    }
}
