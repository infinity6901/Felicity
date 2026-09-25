package app.simple.felicity.repository.youtube

import app.simple.felicity.repository.youtube.YoutubeAuthHelper.applyYoutubeHeaders
import app.simple.felicity.repository.youtube.YoutubeAuthHelper.buildContextBody
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.concurrent.TimeUnit

object YoutubeApiClient {

    private val client: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .build()
    }

    private val jsonMediaType = "application/json; charset=utf-8".toMediaType()

    /**
     * Calls the InnerTube `/player` endpoint for [videoId] using the given [client]
     * fingerprint, returning the raw JSON response body.
     */
    fun getPlayerInfo(
        videoId: String,
        session: YoutubeSession?,
        clientFingerprint: JSONObject,
    ): String {
        val url = (YoutubeConstants.PLAYER_ENDPOINT + "?key=" + YoutubeConstants.INNERTUBE_API_KEY)
            .toHttpUrlOrThrow()

        val body = buildContextBody(session, clientFingerprint).apply {
            put("videoId", videoId)
            put(
                "playbackContext", JSONObject().apply {
                    put(
                        "contentPlaybackContext", JSONObject().apply {
                            put("html5Preference", "HTML5_PREF_WANTS")
                        }
                    )
                }
            )
        }

        val request = Request.Builder()
            .url(url)
            .post(body.toString().toRequestBody(jsonMediaType))
            .applyYoutubeHeaders(url, session, clientFingerprint)
            .build()

        client.newCall(request).execute().use { response ->
            val bodyString = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                throw Exception("HTTP ${response.code} from InnerTube: $bodyString")
            }
            return bodyString
        }
    }

    /** Basic search against InnerTube, useful for a "search YouTube Music" UI. */
    fun search(
        query: String,
        session: YoutubeSession?,
    ): String {
        val url = (YoutubeConstants.SEARCH_ENDPOINT + "?key=" + YoutubeConstants.INNERTUBE_API_KEY)
            .toHttpUrlOrThrow()

        val body = buildContextBody(session, YoutubeConstants.Client.WEB_REMIX).apply {
            put("query", query)
            put("params", "EgWKAQIIAWoKEAMQBBAJEAoQBQ%3D%3D") // "songs" filter
        }

        val request = Request.Builder()
            .url(url)
            .post(body.toString().toRequestBody(jsonMediaType))
            .applyYoutubeHeaders(url, session, YoutubeConstants.Client.WEB_REMIX)
            .build()

        client.newCall(request).execute().use { response ->
            val bodyString = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                throw Exception("HTTP ${response.code} from InnerTube search: $bodyString")
            }
            return bodyString
        }
    }

    fun getSearchSuggestions(
        query: String,
        session: YoutubeSession?
    ): List<String> {
        if (query.isBlank()) return emptyList()
        val url = ("https://music.youtube.com/youtubei/v1/music/get_search_suggestions?key=" + YoutubeConstants.INNERTUBE_API_KEY)
            .toHttpUrlOrThrow()

        val body = buildContextBody(session, YoutubeConstants.Client.WEB_REMIX).apply {
            put("input", query)
        }

        val request = Request.Builder()
            .url(url)
            .post(body.toString().toRequestBody(jsonMediaType))
            .applyYoutubeHeaders(url, session, YoutubeConstants.Client.WEB_REMIX)
            .build()

        return runCatching {
            client.newCall(request).execute().use { response ->
                val bodyString = response.body?.string().orEmpty()
                if (!response.isSuccessful) return emptyList()

                val json = JSONObject(bodyString)
                val suggestions = mutableListOf<String>()
                val contents = json.optJSONArray("contents") ?: return emptyList()
                for (i in 0 until contents.length()) {
                    val section = contents.optJSONObject(i)?.optJSONObject("searchSuggestionsSectionRenderer") ?: continue
                    val items = section.optJSONArray("contents") ?: continue
                    for (j in 0 until items.length()) {
                        val item = items.optJSONObject(j)?.optJSONObject("searchSuggestionRenderer") ?: continue
                        val runs = item.optJSONObject("suggestion")?.optJSONArray("runs") ?: continue
                        val text = (0 until runs.length()).joinToString("") { runs.optJSONObject(it)?.optString("text").orEmpty() }
                        if (text.isNotBlank()) suggestions.add(text)
                    }
                }
                suggestions
            }
        }.getOrDefault(emptyList())
    }

    fun fetchLyrics(
        videoId: String,
        session: YoutubeSession?
    ): String? {
        return runCatching {
            val playerUrl = (YoutubeConstants.PLAYER_ENDPOINT + "?key=" + YoutubeConstants.INNERTUBE_API_KEY).toHttpUrlOrThrow()
            val playerBody = buildContextBody(session, YoutubeConstants.Client.WEB_REMIX).apply {
                put("videoId", videoId)
            }
            val playerReq = Request.Builder()
                .url(playerUrl)
                .post(playerBody.toString().toRequestBody(jsonMediaType))
                .applyYoutubeHeaders(playerUrl, session, YoutubeConstants.Client.WEB_REMIX)
                .build()

            client.newCall(playerReq).execute().use { resp ->
                val body = resp.body?.string().orEmpty()
                if (!resp.isSuccessful) return null
                val json = JSONObject(body)
                val browseId = json.optJSONObject("videoDetails")?.optString("videoId") ?: videoId

                val browseUrl = (YoutubeConstants.BROWSE_ENDPOINT + "?key=" + YoutubeConstants.INNERTUBE_API_KEY).toHttpUrlOrThrow()
                val browseBody = buildContextBody(session, YoutubeConstants.Client.WEB_REMIX).apply {
                    put("browseId", "MPLY$browseId")
                }
                val browseReq = Request.Builder()
                    .url(browseUrl)
                    .post(browseBody.toString().toRequestBody(jsonMediaType))
                    .applyYoutubeHeaders(browseUrl, session, YoutubeConstants.Client.WEB_REMIX)
                    .build()

                client.newCall(browseReq).execute().use { browseResp ->
                    val lyricsBody = browseResp.body?.string().orEmpty()
                    if (!browseResp.isSuccessful) return null
                    val lyricsJson = JSONObject(lyricsBody)
                    val runs = lyricsJson.optJSONObject("contents")
                        ?.optJSONObject("sectionListRenderer")
                        ?.optJSONArray("contents")
                        ?.optJSONObject(0)
                        ?.optJSONObject("musicDescriptionShelfRenderer")
                        ?.optJSONObject("description")
                        ?.optJSONArray("runs") ?: return null

                    val textBuilder = StringBuilder()
                    for (i in 0 until runs.length()) {
                        textBuilder.append(runs.optJSONObject(i)?.optString("text").orEmpty())
                    }
                    textBuilder.toString().takeIf { it.isNotBlank() }
                }
            }
        }.getOrNull()
    }

    /**
     * Calls InnerTube's `/browse` endpoint — used for library/playlist browsing
     * (e.g. `browseId = "FEmusic_liked_playlists"` for the signed-in user's playlist
     * shelf, or a playlist's own browse ID for its track list). Requires [session]
     * to be signed in for anything under the user's own library.
     */
    fun browse(
        browseId: String,
        session: YoutubeSession?,
        continuation: String? = null,
    ): String {
        val url = (YoutubeConstants.BROWSE_ENDPOINT + "?key=" + YoutubeConstants.INNERTUBE_API_KEY)
            .toHttpUrlOrThrow()

        val body = buildContextBody(session, YoutubeConstants.Client.WEB_REMIX).apply {
            put("browseId", browseId)
            continuation?.let { put("continuation", it) }
        }

        val request = Request.Builder()
            .url(url)
            .post(body.toString().toRequestBody(jsonMediaType))
            .applyYoutubeHeaders(url, session, YoutubeConstants.Client.WEB_REMIX)
            .build()

        client.newCall(request).execute().use { response ->
            val bodyString = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                throw Exception("HTTP ${response.code} from InnerTube browse: $bodyString")
            }
            return bodyString
        }
    }

    fun headCheck(url: String): Boolean {
        return runCatching {
            val request = Request.Builder()
                .url(url)
                .header("User-Agent", YoutubeConstants.STREAMING_USER_AGENT)
                .head()
                .build()
            client.newCall(request).execute().use { response ->
                response.isSuccessful || response.code in 200..399
            }
        }.getOrDefault(false)
    }

    private fun String.toHttpUrlOrThrow() = this.toHttpUrl()
}
