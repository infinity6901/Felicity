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
 * [Audius](https://audius.org) — a decentralized streaming platform where artists
 * themselves choose to publish and distribute their tracks through the public API. No key
 * required, only a descriptive `app_name` (a courtesy identifier, not an auth credential).
 *
 * The stream URL returned by [search] is the discovery node's `/stream` endpoint directly
 * — it 302-redirects to the actual content-node CDN URL, which ExoPlayer's default HTTP
 * data source follows automatically, so no extra resolution step is needed before playback
 * (unlike [InternetArchiveRepository], where a second request is unavoidable).
 *
 * Audius is a decentralized network of independently-run "discovery nodes"; this
 * repository hits the well-known `discoveryprovider.audius.co` aggregator used in Audius's
 * own official REST API examples rather than implementing full node discovery/failover.
 *
 * @author Hamza417
 */
@Singleton
class AudiusRepository @Inject constructor() {

    private val networkTag = 0x1004

    private val client: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .socketFactory(TaggedSocketFactory(networkTag))
            .build()
    }

    private val gson: Gson by lazy { Gson() }

    /**
     * Audius's own "top 100 trending this week" chart — shown by default before the user
     * has typed anything.
     */
    suspend fun trending(limit: Int = 30): List<OnlineTrack> = withContext(Dispatchers.IO) {
        try {
            val url = "$BASE_URL/v1/tracks/trending".toHttpUrlOrNull()
                ?.newBuilder()
                ?.addQueryParameter("time", "week")
                ?.addQueryParameter("app_name", AppConstants.AUDIUS_APP_NAME)
                ?.build() ?: return@withContext emptyList()

            val body = get(url.toString()) ?: return@withContext emptyList()
            val response = gson.fromJson(body, AudiusSearchResponse::class.java)

            response.data
                ?.take(limit)
                ?.map { it.toOnlineTrack() }
                .orEmpty()
        } catch (e: Exception) {
            Log.w(TAG, "Audius trending fetch failed", e)
            emptyList()
        }
    }

    /**
     * Searches Audius tracks by free-text query.
     *
     * @param query Free-text search.
     * @param limit Max results to keep for an interactive search (the API itself doesn't
     *              take a limit parameter for this endpoint, so this just truncates
     *              client-side).
     */
    suspend fun search(query: String, limit: Int = 30): List<OnlineTrack> = withContext(Dispatchers.IO) {
        if (query.isBlank()) return@withContext emptyList()

        try {
            val url = "$BASE_URL/v1/tracks/search".toHttpUrlOrNull()
                ?.newBuilder()
                ?.addQueryParameter("query", query)
                ?.addQueryParameter("app_name", AppConstants.AUDIUS_APP_NAME)
                ?.build() ?: return@withContext emptyList()

            val body = get(url.toString()) ?: return@withContext emptyList()
            val response = gson.fromJson(body, AudiusSearchResponse::class.java)

            response.data
                ?.take(limit)
                ?.map { it.toOnlineTrack() }
                .orEmpty()
        } catch (e: Exception) {
            Log.w(TAG, "Audius search failed for: $query", e)
            emptyList()
        }
    }

    private fun AudiusTrack.toOnlineTrack(): OnlineTrack {
        return OnlineTrack(
                source = OnlineSource.AUDIUS,
                id = id,
                title = title,
                subtitle = user?.name ?: user?.handle.orEmpty(),
                artworkUrl = artwork?.let { it.large ?: it.medium ?: it.small },
                streamUrl = buildStreamUrl(id),
                durationMs = duration?.let { it * 1000L } // Audius returns whole seconds
        )
    }

    private fun buildStreamUrl(trackId: String): String {
        return "$BASE_URL/v1/tracks/$trackId/stream".toHttpUrlOrNull()
            ?.newBuilder()
            ?.addQueryParameter("app_name", AppConstants.AUDIUS_APP_NAME)
            ?.build()
            ?.toString()
            ?: "$BASE_URL/v1/tracks/$trackId/stream?app_name=${AppConstants.AUDIUS_APP_NAME}"
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

    private data class AudiusSearchResponse(
            @SerializedName("data") val data: List<AudiusTrack>?
    )

    /** Mirrors only the fields this repository actually uses — see docs.audius.org for the full schema. */
    private data class AudiusTrack(
            @SerializedName("id") val id: String,
            @SerializedName("title") val title: String,
            @SerializedName("user") val user: AudiusUser?,
            @SerializedName("artwork") val artwork: AudiusArtwork?,
            @SerializedName("duration") val duration: Long?
    )

    private data class AudiusUser(
            @SerializedName("name") val name: String?,
            @SerializedName("handle") val handle: String?
    )

    private data class AudiusArtwork(
            @SerializedName("480x480") val large: String?,
            @SerializedName("150x150") val medium: String?,
            @SerializedName("50x50") val small: String?
    )

    companion object {
        private const val TAG = "AudiusRepository"
        private const val BASE_URL = "https://discoveryprovider.audius.co"
    }
}
