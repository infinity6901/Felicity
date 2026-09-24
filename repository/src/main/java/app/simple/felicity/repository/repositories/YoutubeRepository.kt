package app.simple.felicity.repository.repositories

import android.content.Context
import android.util.Log
import app.simple.felicity.repository.online.OnlineSource
import app.simple.felicity.repository.online.OnlineTrack
import app.simple.felicity.repository.youtube.YoutubeApiClient
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

    /**
     * Resolves a direct, playable stream URL for [track]. Tries yt-dlp first (actively
     * maintained against YouTube's changes — see [YtDlpManager]) and falls back to the
     * lighter-weight hand-rolled InnerTube extractor if yt-dlp isn't initialized or fails.
     * Returns `null` if both fail (video pulled, region-locked, etc.).
     */
    suspend fun resolveStreamUrl(track: OnlineTrack): String? = withContext(Dispatchers.IO) {
        if (track.source != OnlineSource.YOUTUBE) return@withContext track.streamUrl

        val ytDlpUrl = YtDlpManager.resolveDirectUrl(track.id)
        if (ytDlpUrl != null && YoutubeApiClient.headCheck(ytDlpUrl)) {
            Log.d(TAG, "yt-dlp resolved valid stream URL for ${track.id}")
            return@withContext ytDlpUrl
        } else if (ytDlpUrl != null) {
            Log.w(TAG, "yt-dlp URL for ${track.id} failed HEAD check, falling back to InnerTube resolver")
        }

        try {
            val resolved = YoutubeStreamResolver.resolve(track.id, currentSession())
            Log.d(TAG, "InnerTube resolved valid stream URL for ${track.id}")
            resolved.url
        } catch (e: Exception) {
            Log.w(TAG, "Both yt-dlp and InnerTube resolution failed for video: ${track.id}", e)
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

    /** Enqueues a background download of [track], returning a WorkManager request ID the UI can observe live progress on (see `YoutubeDownloadWorker`). */
    fun download(track: OnlineTrack, format: String = "m4a"): UUID? {
        if (track.source != OnlineSource.YOUTUBE) return null
        return YoutubeDownloadWorker.enqueue(context, track.id, track.title, track.subtitle, format)
    }

    fun signOut() {
        YoutubeSessionStore.signOut(context)
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

    companion object {
        private const val TAG = "YoutubeRepository"
    }
}
