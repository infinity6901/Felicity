package app.simple.felicity.repository.repositories

import android.util.Log
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
 * [The Internet Archive](https://archive.org) — public-domain and Creative-Commons audio:
 * old-time radio, field recordings, netlabel releases, live concert recordings uploaded
 * with the taper's permission (the famous `etree` collection), and more. No API key.
 *
 * Unlike the other three online sources, Archive.org's search API ([search]) only returns
 * an item identifier and metadata — never a directly playable file URL. Getting the actual
 * audio requires a second request, [resolveStreamUrl], to that item's file manifest. This
 * repository deliberately keeps that as an explicit separate step done right before
 * playback, rather than resolving every search result's files up front (which would mean
 * one extra HTTP request per row just to render a results list).
 *
 * @author Hamza417
 */
@Singleton
class InternetArchiveRepository @Inject constructor() {

    private val networkTag = 0x1005

    private val client: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS)
            .socketFactory(TaggedSocketFactory(networkTag))
            .build()
    }

    private val gson: Gson by lazy { Gson() }

    /**
     * The most-downloaded audio items on the whole archive — shown by default before the
     * user has typed anything. Same two-step caveat as [search]: every result still needs
     * [resolveStreamUrl] before it's actually playable.
     */
    suspend fun trending(limit: Int = 30): List<OnlineTrack> = withContext(Dispatchers.IO) {
        try {
            val url = "$BASE_URL/advancedsearch.php".toHttpUrlOrNull()
                ?.newBuilder()
                ?.addQueryParameter("q", "mediatype:audio")
                ?.addQueryParameter("fl[]", "identifier")
                ?.addQueryParameter("fl[]", "title")
                ?.addQueryParameter("fl[]", "creator")
                ?.addQueryParameter("sort[]", "downloads desc")
                ?.addQueryParameter("rows", limit.toString())
                ?.addQueryParameter("output", "json")
                ?.build() ?: return@withContext emptyList()

            val body = get(url.toString()) ?: return@withContext emptyList()
            val response = gson.fromJson(body, AdvancedSearchResponse::class.java)

            response.response?.docs
                ?.map { it.toOnlineTrack() }
                .orEmpty()
        } catch (e: Exception) {
            Log.w(TAG, "Internet Archive trending fetch failed", e)
            emptyList()
        }
    }

    /**
     * Searches Archive.org items restricted to `mediatype:audio`.
     *
     * Every returned [OnlineTrack.streamUrl] is `null` — call [resolveStreamUrl] with the
     * result right before playback to get an actual playable file URL.
     */
    suspend fun search(query: String, limit: Int = 30): List<OnlineTrack> = withContext(Dispatchers.IO) {
        if (query.isBlank()) return@withContext emptyList()

        try {
            val luceneQuery = "($query) AND mediatype:audio"
            val url = "$BASE_URL/advancedsearch.php".toHttpUrlOrNull()
                ?.newBuilder()
                ?.addQueryParameter("q", luceneQuery)
                ?.addQueryParameter("fl[]", "identifier")
                ?.addQueryParameter("fl[]", "title")
                ?.addQueryParameter("fl[]", "creator")
                ?.addQueryParameter("rows", limit.toString())
                ?.addQueryParameter("output", "json")
                ?.build() ?: return@withContext emptyList()

            val body = get(url.toString()) ?: return@withContext emptyList()
            val response = gson.fromJson(body, AdvancedSearchResponse::class.java)

            response.response?.docs
                ?.map { it.toOnlineTrack() }
                .orEmpty()
        } catch (e: Exception) {
            Log.w(TAG, "Internet Archive search failed for: $query", e)
            emptyList()
        }
    }

    private fun SearchDoc.toOnlineTrack(): OnlineTrack {
        return OnlineTrack(
                source = OnlineSource.INTERNET_ARCHIVE,
                id = identifier,
                title = title ?: identifier,
                subtitle = normalizeCreator(creator),
                // A thumbnail is always at this fixed path for any item — no extra
                // request needed to know it exists, unlike the audio file.
                artworkUrl = "https://archive.org/services/img/$identifier",
                streamUrl = null,
                durationMs = null
        )
    }

    /**
     * Fetches this item's file manifest and returns a direct, playable URL for the best
     * available audio file, preferring higher-quality formats first.
     *
     * @return `null` if the item has no recognizable audio file (e.g. it turned out to be
     *         a text/image item that merely matched the search terms) or the request fails.
     */
    suspend fun resolveStreamUrl(track: OnlineTrack): String? = withContext(Dispatchers.IO) {
        if (track.source != OnlineSource.INTERNET_ARCHIVE) return@withContext track.streamUrl

        try {
            val body = get("$BASE_URL/metadata/${track.id}") ?: return@withContext null
            val metadata = gson.fromJson(body, ItemMetadataResponse::class.java)
            val files = metadata.files.orEmpty()

            val bestFile = PREFERRED_FORMATS.firstNotNullOfOrNull { format ->
                files.firstOrNull { it.format.equals(format, ignoreCase = true) }
            } ?: files.firstOrNull { it.name.substringAfterLast('.', "").lowercase() in AUDIO_EXTENSIONS }

            bestFile?.let { "$BASE_URL/download/${track.id}/${it.name}" }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to resolve stream URL for item: ${track.id}", e)
            null
        }
    }

    /**
     * Archive.org's `creator` field is famously inconsistent across items: a single-author
     * item stores it as a plain JSON string, a multi-author item stores it as a JSON array
     * — and Gson throws if a field typed `List<String>` receives a bare string. [SearchDoc]
     * therefore holds this field as a raw [com.google.gson.JsonElement] and this function
     * normalizes either shape into the single display string [search] actually needs.
     */
    private fun normalizeCreator(element: com.google.gson.JsonElement?): String {
        if (element == null || element.isJsonNull) return ""
        return when {
            element.isJsonArray -> element.asJsonArray.firstOrNull()?.asString.orEmpty()
            element.isJsonPrimitive -> element.asString
            else -> ""
        }
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

    private data class AdvancedSearchResponse(
            @SerializedName("response") val response: SearchResponseBody?
    )

    private data class SearchResponseBody(
            @SerializedName("docs") val docs: List<SearchDoc>?
    )

    private data class SearchDoc(
            @SerializedName("identifier") val identifier: String,
            @SerializedName("title") val title: String?,
            @SerializedName("creator") val creator: com.google.gson.JsonElement?
    )

    private data class ItemMetadataResponse(
            @SerializedName("files") val files: List<ItemFile>?
    )

    private data class ItemFile(
            @SerializedName("name") val name: String,
            @SerializedName("format") val format: String?
    )

    companion object {
        private const val TAG = "InternetArchiveRepository"
        private const val BASE_URL = "https://archive.org"

        /** Checked in this order — prefer a real audio format over a generic file-extension fallback match. */
        private val PREFERRED_FORMATS = listOf("VBR MP3", "MP3", "Ogg Vorbis", "128Kbps MP3", "FLAC")
        private val AUDIO_EXTENSIONS = setOf("mp3", "ogg", "flac", "m4a", "wav")
    }
}
