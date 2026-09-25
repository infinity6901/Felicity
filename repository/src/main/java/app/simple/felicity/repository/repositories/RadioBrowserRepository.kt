package app.simple.felicity.repository.repositories

import android.util.Log
import app.simple.felicity.repository.factories.TaggedSocketFactory
import app.simple.felicity.repository.online.OnlineSource
import app.simple.felicity.repository.online.OnlineTrack
import app.simple.felicity.shared.constants.AppConstants
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
 * Free, community-run directory of internet radio stations — [radio-browser.info](https://www.radio-browser.info).
 *
 * No API key, no auth, no rate limiting beyond "send a real User-Agent". Every station
 * this returns is already a live, publicly broadcast internet radio stream — there's no
 * separate licensing question here the way there is for on-demand tracks, since internet
 * radio stations are responsible for their own broadcast licensing, the same as terrestrial
 * radio.
 *
 * The API is served from several independent mirror servers (`de1`, `de2`, `at1`, ...) that
 * are meant to be resolved via a DNS round-robin (`all.api.radio-browser.info`) for load
 * balancing and failover. This repository deliberately skips that and hits a single mirror
 * directly — simpler, and fine for a single app's search volume, but if `de1` ever goes
 * down this repository fails along with it rather than falling back to another mirror.
 *
 * @author Hamza417
 */
@Singleton
class RadioBrowserRepository @Inject constructor() {

    private val networkTag = 0x1002

    private val client: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .socketFactory(TaggedSocketFactory(networkTag))
            .build()
    }

    private val gson: Gson by lazy { Gson() }

    /**
     * Radio Browser's own concept of "trending" — the most-voted stations across its whole
     * directory right now. Shown by default before the user has typed anything.
     */
    suspend fun trending(limit: Int = 30): List<OnlineTrack> = withContext(Dispatchers.IO) {
        try {
            val url = "$BASE_URL/json/stations/topvote/$limit".toHttpUrlOrNull()
                ?.newBuilder()
                ?.addQueryParameter("hidebroken", "true")
                ?.build() ?: return@withContext emptyList()

            val body = get(url.toString()) ?: return@withContext emptyList()
            val stations = gson.fromJson(body, Array<RadioBrowserStation>::class.java) ?: return@withContext emptyList()

            stations
                .filter { it.urlResolved.isNullOrBlank().not() }
                .map { it.toOnlineTrack() }
        } catch (e: Exception) {
            Log.w(TAG, "Radio Browser trending fetch failed", e)
            emptyList()
        }
    }

    /**
     * Searches for radio stations by name.
     *
     * @param query Free-text station name search (e.g. "jazz", "bbc radio 1").
     * @param limit Max results to return (Radio Browser allows up to 1000; kept modest
     *              here since this is for interactive search, not bulk indexing).
     */
    suspend fun search(query: String, limit: Int = 30): List<OnlineTrack> = withContext(Dispatchers.IO) {
        if (query.isBlank()) return@withContext emptyList()

        try {
            val url = "$BASE_URL/json/stations/search".toHttpUrlOrNull()
                ?.newBuilder()
                ?.addQueryParameter("name", query)
                ?.addQueryParameter("limit", limit.toString())
                ?.addQueryParameter("hidebroken", "true")
                ?.addQueryParameter("order", "votes")
                ?.addQueryParameter("reverse", "true")
                ?.build() ?: return@withContext emptyList()

            val body = get(url.toString()) ?: return@withContext emptyList()
            val stations = gson.fromJson(body, Array<RadioBrowserStation>::class.java) ?: return@withContext emptyList()

            stations
                .filter { it.urlResolved.isNullOrBlank().not() }
                .map { it.toOnlineTrack() }
        } catch (e: Exception) {
            Log.w(TAG, "Radio Browser search failed for: $query", e)
            emptyList()
        }
    }

    fun createCustomStation(
        name: String,
        streamUrl: String,
        genre: String = "Custom Radio",
        logoUrl: String? = null
    ): OnlineTrack {
        val uuid = "custom_${System.currentTimeMillis()}"
        return OnlineTrack(
            source = OnlineSource.RADIO_BROWSER,
            id = uuid,
            title = name,
            subtitle = genre,
            artworkUrl = logoUrl,
            streamUrl = streamUrl,
            durationMs = null
        )
    }

    private fun RadioBrowserStation.toOnlineTrack(): OnlineTrack {
        return OnlineTrack(
                source = OnlineSource.RADIO_BROWSER,
                id = stationUuid,
                title = name,
                subtitle = listOfNotNull(country?.ifBlank { null }, tags?.ifBlank { null })
                    .joinToString(" — ")
                    .ifBlank { "Internet radio" },
                artworkUrl = favicon?.ifBlank { null },
                streamUrl = urlResolved,
                durationMs = null // a radio station has no fixed duration
        )
    }

    private fun get(url: String): String? {
        return try {
            val request = Request.Builder()
                .url(url)
                .header("User-Agent", AppConstants.RADIO_BROWSER_USER_AGENT)
                .build()
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

    /** Mirrors only the fields this repository actually uses — see docs.radio-browser.info for the full schema. */
    private data class RadioBrowserStation(
            @SerializedName("stationuuid") val stationUuid: String,
            @SerializedName("name") val name: String,
            @SerializedName("url_resolved") val urlResolved: String?,
            @SerializedName("favicon") val favicon: String?,
            @SerializedName("tags") val tags: String?,
            @SerializedName("country") val country: String?
    )

    companion object {
        private const val TAG = "RadioBrowserRepository"

        // One of several documented, stable mirrors. See the kdoc above for the tradeoff
        // of hardcoding a single mirror instead of doing proper DNS-based server discovery.
        private const val BASE_URL = "https://de1.api.radio-browser.info"
    }
}
