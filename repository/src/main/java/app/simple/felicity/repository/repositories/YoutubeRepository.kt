package app.simple.felicity.repository.repositories

import android.content.Context
import android.util.Log
import app.simple.felicity.repository.online.OnlineSource
import app.simple.felicity.repository.online.OnlineTrack
import app.simple.felicity.repository.youtube.YoutubeApiClient
import app.simple.felicity.repository.youtube.YoutubeBrowseParser
import app.simple.felicity.repository.youtube.YoutubeConstants
import app.simple.felicity.repository.youtube.YoutubeDetailPage
import app.simple.felicity.repository.youtube.YoutubeHomePage
import app.simple.felicity.repository.youtube.YoutubeItemsPage
import app.simple.felicity.repository.youtube.YoutubeSongItem
import app.simple.felicity.repository.youtube.YoutubeDownloadWorker
import app.simple.felicity.repository.youtube.YoutubePlaylist
import app.simple.felicity.repository.youtube.YoutubePlaylistParser
import app.simple.felicity.repository.youtube.YoutubeSearchParser
import app.simple.felicity.repository.youtube.YoutubeSearchResult
import app.simple.felicity.repository.youtube.YoutubeSession
import app.simple.felicity.repository.youtube.YoutubeSessionStore
import app.simple.felicity.repository.youtube.YoutubeStreamResolver
import app.simple.felicity.repository.youtube.YtDlpManager
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Adapts the `app.simple.felicity.repository.youtube` backend (InnerTube auth/search/stream
 * resolution, ported from Umihi Music) to the same [OnlineTrack]/[OnlineSource] shape the
 * other three online repositories use — see [OnlineSource.YOUTUBE]'s kdoc for the ToS caveat
 * that makes this source different from the other three.
 *
 * Same two-step shape as [InternetArchiveRepository]: [search] never returns a directly
 * playable [OnlineTrack.streamUrl] (InnerTube stream URLs are short-lived and tied to the
 * requesting session, so there's no point resolving one for every row in a results list) —
 * call [resolveStreamUrl] right before playback instead.
 *
 * @author Hamza417 (adapted)
 */
@Singleton
class YoutubeRepository @Inject constructor(
    @ApplicationContext private val context: Context,
) {

    private fun currentSession(): YoutubeSession = YoutubeSessionStore.get(context)

    val isSignedIn: Boolean
        get() = currentSession().isLoggedIn

    /** No real "trending" endpoint used here — falls back to a broad, popular-leaning query. */
    suspend fun trending(limit: Int = 30): List<OnlineTrack> = search("official music video", limit)

    suspend fun search(query: String, limit: Int = 30): List<OnlineTrack> = withContext(Dispatchers.IO) {
        if (query.isBlank()) return@withContext emptyList()

        try {
            val raw = YoutubeApiClient.search(query, currentSession())
            YoutubeSearchParser.parse(raw)
                .take(limit)
                .map { it.toOnlineTrack() }
        } catch (e: Exception) {
            Log.w(TAG, "YouTube search failed for: $query", e)
            emptyList()
        }
    }

    suspend fun getSearchSuggestions(query: String): List<String> = withContext(Dispatchers.IO) {
        YoutubeApiClient.getSearchSuggestions(query, currentSession())
    }

    /**
     * Resolves a direct, playable stream URL for [track]. Tries yt-dlp first (actively
     * maintained against YouTube's changes — see [YtDlpManager]) and falls back to the
     * lighter-weight hand-rolled InnerTube extractor if yt-dlp isn't initialized or fails.
     * Returns `null` if both fail (video pulled, region-locked, etc.).
     */
    suspend fun resolveStreamUrl(track: OnlineTrack): String? = withContext(Dispatchers.IO) {
        if (track.source != OnlineSource.YOUTUBE) return@withContext track.streamUrl

        // InnerTube first: its VISIONOS client is exempt from the proof-of-origin check that cuts
        // ANDROID_VR / IOS (and the web streams yt-dlp picks) off after about 1 MB.
        try {
            val session = currentSession()
            val resolved = YoutubeStreamResolver.resolve(track.id, session)
            Log.d(TAG, "InnerTube resolved a stream for ${track.id}")
            resolved.playbackTrackingUrl?.let { url ->
                if (session.isLoggedIn) YoutubeApiClient.reportPlayback(url, session)
            }
            return@withContext resolved.url
        } catch (e: Exception) {
            Log.w(TAG, "InnerTube resolution failed for ${track.id}, trying yt-dlp", e)
        }

        val ytDlpUrl = YtDlpManager.resolveDirectUrl(track.id)
        if (ytDlpUrl != null && YoutubeApiClient.headCheck(ytDlpUrl)) {
            Log.d(TAG, "yt-dlp resolved valid stream URL for ${track.id}")
            ytDlpUrl
        } else {
            Log.w(TAG, "No usable stream for ${track.id}")
            null
        }
    }

    /** The signed-in user's own YouTube Music playlists. Empty if not signed in. */
    suspend fun getLibraryPlaylists(): List<YoutubePlaylist> = withContext(Dispatchers.IO) {
        val session = currentSession()
        if (!session.isLoggedIn) return@withContext emptyList()

        try {
            val raw = YoutubeApiClient.browse("FEmusic_liked_playlists", session)
            YoutubePlaylistParser.parseLibraryPlaylists(raw)
        } catch (e: Exception) {
            Log.w(TAG, "Failed to fetch library playlists", e)
            emptyList()
        }
    }

    /** Track list for a given playlist browse/playlist ID. */
    suspend fun getPlaylistTracks(playlistId: String): List<OnlineTrack> = withContext(Dispatchers.IO) {
        try {
            val browseId = if (playlistId.startsWith("VL")) playlistId else "VL$playlistId"
            val raw = YoutubeApiClient.browse(browseId, currentSession())
            YoutubePlaylistParser.parsePlaylistTracks(raw).map { it.toOnlineTrack() }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to fetch tracks for playlist $playlistId", e)
            emptyList()
        }
    }

    /** Extracts playlist ID from a YouTube URL and fetches its tracks. */
    suspend fun importPlaylistByUrl(urlStr: String): List<OnlineTrack> = withContext(Dispatchers.IO) {
        val playlistId = runCatching {
            when {
                urlStr.contains("list=") -> urlStr.substringAfter("list=").substringBefore("&")
                urlStr.startsWith("PL") || urlStr.startsWith("VL") -> urlStr
                else -> urlStr.trim()
            }
        }.getOrDefault("")

        if (playlistId.isBlank()) return@withContext emptyList()
        getPlaylistTracks(playlistId)
    }

    private fun YoutubeSearchResult.toOnlineTrack(): OnlineTrack {
        return OnlineTrack(
            source = OnlineSource.YOUTUBE,
            id = videoId,
            title = title,
            subtitle = artist,
            artworkUrl = thumbnailUrl.ifBlank { null },
            streamUrl = null,
            durationMs = parseDurationToMs(durationText),
        )
    }

    private fun parseDurationToMs(text: String): Long? {
        if (text.isBlank()) return null
        val parts = text.split(":").map { it.toIntOrNull() ?: return null }
        val seconds = when (parts.size) {
            2 -> parts[0] * 60 + parts[1]
            3 -> parts[0] * 3600 + parts[1] * 60 + parts[2]
            else -> return null
        }
        return seconds * 1000L
    }

    /** Fetches trending music tracks on YouTube Music. */
    suspend fun getTrendingSongs(): List<OnlineTrack> = search("Trending music")

    /** Fetches quick picks / top tracks on YouTube Music. */
    suspend fun getQuickPicks(): List<OnlineTrack> = search("Top hits music")

    /** Fetches new releases on YouTube Music. */
    suspend fun getNewReleases(): List<OnlineTrack> = search("New release songs")

    /** Enqueues a background download of [track], returning a WorkManager request ID the UI can observe live progress on (see `YoutubeDownloadWorker`). */
    fun download(track: OnlineTrack, format: String = "m4a"): UUID? {
        if (track.source != OnlineSource.YOUTUBE) return null
        return YoutubeDownloadWorker.enqueue(context, track.id, track.title, track.subtitle, format)
    }

    // ------------------------------------------------------------------ Home, pages, search, library

    /**
     * The Home feed. Quick picks live in the first continuation rather than the first response, so up to
     * three continuations are followed until a Quick picks shelf shows up (or the feed runs out).
     */
    suspend fun getHome(chipParams: String? = null): YoutubeHomePage = withContext(Dispatchers.IO) {
        try {
            val session = currentSession()
            var page = YoutubeBrowseParser.parseHome(YoutubeApiClient.browse("FEmusic_home", session, params = chipParams))
            val shelves = page.shelves.toMutableList()
            var token = page.continuation
            var batches = 0
            while (token != null && batches < 3 && (chipParams != null || shelves.none { it.isQuickPicks })) {
                val more = YoutubeBrowseParser.parseHomeContinuation(YoutubeApiClient.browse("", session, continuation = token))
                shelves += more.shelves
                token = more.continuation
                batches++
            }
            page.copy(shelves = shelves, continuation = token)
        } catch (e: Exception) {
            Log.w(TAG, "Home feed failed", e)
            YoutubeHomePage(emptyList(), emptyList(), null)
        }
    }

    suspend fun getHomeContinuation(token: String): YoutubeHomePage = withContext(Dispatchers.IO) {
        runCatching {
            YoutubeBrowseParser.parseHomeContinuation(YoutubeApiClient.browse("", currentSession(), continuation = token))
        }.getOrElse { YoutubeHomePage(emptyList(), emptyList(), null) }
    }

    /** Search in one of [YoutubeConstants.SearchFilter]'s views (null = everything), or fetch the next page with [continuation]. */
    suspend fun searchPage(query: String, filter: String?, continuation: String? = null): YoutubeItemsPage = withContext(Dispatchers.IO) {
        if (query.isBlank() && continuation == null) return@withContext YoutubeItemsPage(emptyList(), null)
        try {
            YoutubeBrowseParser.parseItemsPage(YoutubeApiClient.search(query, currentSession(), filter, continuation))
        } catch (e: Exception) {
            Log.w(TAG, "Search failed for: $query", e)
            YoutubeItemsPage(emptyList(), null)
        }
    }

    suspend fun getArtist(browseId: String): YoutubeDetailPage? = detail(browseId, YoutubeDetailPage.Kind.ARTIST)
    suspend fun getAlbum(browseId: String): YoutubeDetailPage? = detail(browseId, YoutubeDetailPage.Kind.ALBUM)

    suspend fun getPlaylist(playlistId: String): YoutubeDetailPage? =
        detail(if (playlistId.startsWith("VL")) playlistId else "VL$playlistId", YoutubeDetailPage.Kind.PLAYLIST)

    private suspend fun detail(browseId: String, kind: YoutubeDetailPage.Kind): YoutubeDetailPage? = withContext(Dispatchers.IO) {
        try {
            YoutubeBrowseParser.parseDetailPage(YoutubeApiClient.browse(browseId, currentSession()), kind)
        } catch (e: Exception) {
            Log.w(TAG, "Failed to load $kind page $browseId", e)
            null
        }
    }

    /** More tracks of a long playlist. */
    suspend fun getPlaylistMore(token: String): Pair<List<YoutubeSongItem>, String?> = withContext(Dispatchers.IO) {
        runCatching { YoutubeBrowseParser.parsePlaylistContinuation(YoutubeApiClient.browse("", currentSession(), continuation = token)) }
            .getOrElse { emptyList<YoutubeSongItem>() to null }
    }

    /** The account's library lists. [browseId] is one of the `LIBRARY_*` ids below. */
    suspend fun getLibrary(browseId: String, continuation: String? = null): YoutubeItemsPage = withContext(Dispatchers.IO) {
        val session = currentSession()
        if (!session.isLoggedIn) return@withContext YoutubeItemsPage(emptyList(), null)
        try {
            YoutubeBrowseParser.parseItemsPage(YoutubeApiClient.browse(browseId, session, continuation))
        } catch (e: Exception) {
            Log.w(TAG, "Library $browseId failed", e)
            YoutubeItemsPage(emptyList(), null)
        }
    }

    /** A radio queue seeded by a song ([videoId]) or a playlist / mix id. */
    suspend fun getRadio(videoId: String?, playlistId: String? = null): List<OnlineTrack> = withContext(Dispatchers.IO) {
        try {
            val list = playlistId ?: videoId?.let { "RDAMVM$it" }
            YoutubeBrowseParser.parseRadio(YoutubeApiClient.next(videoId, list, currentSession())).map { it.toOnlineTrack() }
        } catch (e: Exception) {
            Log.w(TAG, "Radio failed", e)
            emptyList()
        }
    }

    suspend fun like(videoId: String, like: Boolean): Boolean = withContext(Dispatchers.IO) {
        currentSession().isLoggedIn && YoutubeApiClient.likeVideo(videoId, like, currentSession())
    }

    suspend fun subscribe(channelId: String, subscribe: Boolean): Boolean = withContext(Dispatchers.IO) {
        currentSession().isLoggedIn && YoutubeApiClient.subscribeChannel(channelId, subscribe, currentSession())
    }

    suspend fun addToPlaylist(playlistId: String, videoId: String): Boolean = withContext(Dispatchers.IO) {
        currentSession().isLoggedIn && YoutubeApiClient.addToPlaylist(playlistId, videoId, currentSession())
    }

    suspend fun createPlaylist(title: String, videoIds: List<String>): String? = withContext(Dispatchers.IO) {
        if (!currentSession().isLoggedIn) null else YoutubeApiClient.createPlaylist(title, videoIds, currentSession())
    }

    fun signOut() {
        YoutubeSessionStore.signOut(context)
    }

    companion object {
        private const val TAG = "YoutubeRepository"

        const val LIBRARY_LIKED_SONGS = "FEmusic_liked_videos"
        const val LIBRARY_ALBUMS = "FEmusic_liked_albums"
        const val LIBRARY_ARTISTS = "FEmusic_library_corpus_artists"
        const val LIBRARY_HISTORY = "FEmusic_history"
    }
}

/** Plays and downloads work on [OnlineTrack]s; this maps a parsed song onto one. */
fun YoutubeSongItem.toOnlineTrack(): OnlineTrack = OnlineTrack(
    source = OnlineSource.YOUTUBE,
    id = videoId,
    title = title,
    subtitle = subtitle,
    artworkUrl = thumbnailUrl.ifBlank { null },
    streamUrl = null,
    durationMs = durationText.split(":").mapNotNull { it.toIntOrNull() }.takeIf { it.size in 2..3 }?.let { p ->
        (if (p.size == 2) p[0] * 60 + p[1] else p[0] * 3600 + p[1] * 60 + p[2]) * 1000L
    },
)
