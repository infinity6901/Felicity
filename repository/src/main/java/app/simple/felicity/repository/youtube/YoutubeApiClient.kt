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

    /**
     * Search against InnerTube. [params] is one of [YoutubeConstants.SearchFilter] (songs by default, null
     * for the unfiltered "All" view); [continuation] fetches the next page of a previous result.
     */
    fun search(
        query: String,
        session: YoutubeSession?,
        params: String? = YoutubeConstants.SearchFilter.SONGS,
        continuation: String? = null,
    ): String {
        if (continuation != null) {
            return postJson(YoutubeConstants.SEARCH_ENDPOINT, session, extraQuery = continuationQuery(continuation)) { }
        }
        return postJson(YoutubeConstants.SEARCH_ENDPOINT, session) {
            put("query", query)
            params?.let { put("params", it) }
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
     * Calls InnerTube's `/browse` endpoint: library shelves (`FEmusic_liked_playlists`), home
     * (`FEmusic_home`, with a chip's [params]), artist (`UC...`), album (`MPREb_...`) and playlist
     * (`VL...`) pages. [continuation] pages through a shelf or the home feed. Anything under the
     * user's own library needs a signed-in [session].
     */
    fun browse(
        browseId: String,
        session: YoutubeSession?,
        continuation: String? = null,
        params: String? = null,
    ): String {
        if (continuation != null) {
            return postJson(YoutubeConstants.BROWSE_ENDPOINT, session, extraQuery = continuationQuery(continuation)) { }
        }
        return postJson(YoutubeConstants.BROWSE_ENDPOINT, session) {
            put("browseId", browseId)
            params?.let { put("params", it) }
        }
    }

    /** `/next`: the watch queue for [videoId]. With [playlistId] `RDAMVM<videoId>` this is that song's radio. */
    fun next(
        videoId: String?,
        playlistId: String?,
        session: YoutubeSession?,
        continuation: String? = null,
    ): String {
        if (continuation != null) {
            return postJson(YoutubeConstants.NEXT_ENDPOINT, session, extraQuery = continuationQuery(continuation)) { }
        }
        return postJson(YoutubeConstants.NEXT_ENDPOINT, session) {
            videoId?.let { put("videoId", it) }
            playlistId?.let { put("playlistId", it) }
            put("isAudioOnly", true)
            put("tunerSettingValue", "AUTOMIX_SETTING_NORMAL")
        }
    }

    fun likeVideo(videoId: String, like: Boolean, session: YoutubeSession?): Boolean = runCatching {
        postJson(if (like) YoutubeConstants.LIKE_ENDPOINT else YoutubeConstants.REMOVE_LIKE_ENDPOINT, session) {
            put("target", JSONObject().put("videoId", videoId))
        }
        true
    }.getOrDefault(false)

    fun subscribeChannel(channelId: String, subscribe: Boolean, session: YoutubeSession?): Boolean = runCatching {
        postJson(if (subscribe) YoutubeConstants.SUBSCRIBE_ENDPOINT else YoutubeConstants.UNSUBSCRIBE_ENDPOINT, session) {
            put("channelIds", org.json.JSONArray().put(channelId))
        }
        true
    }.getOrDefault(false)

    fun addToPlaylist(playlistId: String, videoId: String, session: YoutubeSession?): Boolean = runCatching {
        postJson(YoutubeConstants.EDIT_PLAYLIST_ENDPOINT, session) {
            put("playlistId", playlistId.removePrefix("VL"))
            put(
                "actions", org.json.JSONArray().put(
                    JSONObject().put("action", "ACTION_ADD_VIDEO").put("addedVideoId", videoId)
                )
            )
        }
        true
    }.getOrDefault(false)

    /** Creates a private playlist, returning its id or null on failure. */
    fun createPlaylist(title: String, videoIds: List<String>, session: YoutubeSession?): String? = runCatching {
        val raw = postJson(YoutubeConstants.CREATE_PLAYLIST_ENDPOINT, session) {
            put("title", title)
            put("privacyStatus", "PRIVATE")
            put("videoIds", org.json.JSONArray(videoIds))
        }
        JSONObject(raw).optString("playlistId").takeIf { it.isNotBlank() }
    }.getOrNull()

    /**
     * Tells YouTube a song was played so it lands in the signed-in account's history and feeds the
     * recommendations. [trackingUrl] is `playbackTracking.videostatsPlaybackUrl.baseUrl` from a player
     * response; the request is the one the web player sends when playback starts.
     */
    fun reportPlayback(trackingUrl: String, session: YoutubeSession?): Boolean = runCatching {
        val rewritten = trackingUrl.replace("s.youtube.com", "music.youtube.com")
        val cpn = (1..16).map { CPN_ALPHABET.random() }.joinToString("")
        val url = rewritten.toHttpUrl().newBuilder()
            .addQueryParameter("ver", "2")
            .addQueryParameter("c", "WEB_REMIX")
            .addQueryParameter("cpn", cpn)
            .build()
        val request = Request.Builder().url(url).get()
            .applyYoutubeHeaders(url, session, YoutubeConstants.Client.WEB_REMIX)
            .removeHeader("Content-Type")
            .build()
        client.newCall(request).execute().use { it.isSuccessful }
    }.getOrDefault(false)

    /** Scrapes the anonymous visitorData that VISIONOS / iOS player requests need. */
    fun fetchVisitorData(): String? = runCatching {
        val request = Request.Builder()
            .url(YoutubeConstants.VISITOR_DATA_URL)
            .header("User-Agent", YoutubeConstants.STREAMING_USER_AGENT)
            .get()
            .build()
        client.newCall(request).execute().use { response ->
            val text = response.body?.string().orEmpty()
            Regex("\"(Cg[ts][A-Za-z0-9_%\\-=]{10,})\"").find(text)?.groupValues?.get(1)
        }
    }.getOrNull()

    private const val CPN_ALPHABET = "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789-_"

    private fun continuationQuery(token: String) =
        "&ctoken=${java.net.URLEncoder.encode(token, "UTF-8")}&continuation=${java.net.URLEncoder.encode(token, "UTF-8")}&type=next"

    /** POSTs an InnerTube request with the WEB_REMIX context and returns the raw response. */
    private inline fun postJson(
        endpoint: String,
        session: YoutubeSession?,
        extraQuery: String = "",
        build: JSONObject.() -> Unit,
    ): String {
        val url = (endpoint + "?key=" + YoutubeConstants.INNERTUBE_API_KEY + "&prettyPrint=false" + extraQuery).toHttpUrl()
        val body = buildContextBody(session, YoutubeConstants.Client.WEB_REMIX).apply(build)
        val request = Request.Builder()
            .url(url)
            .post(body.toString().toRequestBody(jsonMediaType))
            .applyYoutubeHeaders(url, session, YoutubeConstants.Client.WEB_REMIX)
            .build()
        client.newCall(request).execute().use { response ->
            val bodyString = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                throw Exception("HTTP ${response.code} from InnerTube ${url.encodedPath}: ${bodyString.take(200)}")
            }
            return bodyString
        }
    }

    /**
     * Confirms [url] actually serves bytes. A ranged GET (`bytes=0-1`) is used rather than HEAD, which
     * googlevideo answers inconsistently, and it carries [userAgent] so the check sees what playback will.
     */
    fun headCheck(url: String, userAgent: String = YoutubeConstants.STREAMING_USER_AGENT): Boolean {
        return runCatching {
            val request = Request.Builder()
                .url(url)
                .header("User-Agent", userAgent)
                .header("Range", "bytes=0-1")
                .get()
                .build()
            client.newCall(request).execute().use { response ->
                response.code == 200 || response.code == 206
            }
        }.getOrDefault(false)
    }

    private fun String.toHttpUrlOrThrow() = this.toHttpUrl()
}
