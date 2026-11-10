package app.simple.felicity.server

import android.content.Context
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.util.Log
import androidx.core.graphics.scale
import androidx.core.net.toUri
import app.simple.felicity.R
import app.simple.felicity.core.constants.ThemeConstants
import app.simple.felicity.engine.managers.MediaPlaybackManager
import app.simple.felicity.preferences.AppearancePreferences
import app.simple.felicity.preferences.ServerPreferences
import app.simple.felicity.repository.covers.AudioCover
import app.simple.felicity.repository.database.instances.AudioDatabase
import app.simple.felicity.repository.models.Audio
import app.simple.felicity.repository.repositories.SongStatRepository
import app.simple.felicity.repository.youtube.YoutubeApiClient
import app.simple.felicity.repository.youtube.YoutubeSearchParser
import app.simple.felicity.repository.youtube.YoutubeSessionStore
import app.simple.felicity.theme.managers.ThemeManager
import com.google.gson.GsonBuilder
import fi.iki.elonen.NanoHTTPD
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.security.SecureRandom

/**
 * Local WiFi HTTP server that exposes the device music library as a fully-featured
 * single-page web application with Artists, Albums, Genres, and Songs browsing.
 *
 * @author Hamza417
 */
class MusicHttpServer(
        private val context: Context,
        port: Int,
        private val songStatRepository: SongStatRepository
) : NanoHTTPD(port) {

    private val gson = GsonBuilder().create()

    private val audioDatabase: AudioDatabase by lazy {
        AudioDatabase.getInstance(context)
    }

    /** Per-instance random session token used to prevent cross-site / unauthenticated API abuse. */
    private val sessionToken: String = ByteArray(24).let { bytes ->
        SecureRandom().nextBytes(bytes)
        bytes.joinToString("") { "%02x".format(it) }
    }

    /** ID → Audio map, rebuilt whenever the songs endpoint is queried. */
    @Volatile
    private var audioCache: Map<Long, Audio> = emptyMap()

    /** Cached round launcher icon PNG bytes, generated once on first request. */
    @Volatile
    private var iconBytes: ByteArray? = null

    override fun serve(session: IHTTPSession): Response {
        return try {
            if (!isSameOriginRequest(session)) {
                return newFixedLengthResponse(
                        Response.Status.FORBIDDEN,
                        MIME_PLAINTEXT,
                        "Cross-origin requests are not allowed"
                )
            }
            when (session.method) {
                Method.POST -> handlePost(session)
                Method.DELETE -> handleDelete(session)
                else -> handleGet(session)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error handling ${session.method} ${session.uri}", e)
            newFixedLengthResponse(
                    Response.Status.INTERNAL_ERROR,
                    MIME_PLAINTEXT,
                    "Internal server error: ${e.message}"
            )
        }
    }

    /**
     * Rejects cross-origin browser requests (`Origin` / `Referer` mismatch) and requires the
     * session cookie initialized by `/` on mutating requests (`POST`, `DELETE`).
     */
    private fun isSameOriginRequest(session: IHTTPSession): Boolean {
        val host = session.headers["host"]?.lowercase()?.trim()
        val origin = session.headers["origin"]?.lowercase()?.trim()
        if (!origin.isNullOrBlank() && origin != "null" && host != null) {
            val originHost = runCatching { Uri.parse(origin).authority?.lowercase() }.getOrNull()
            if (originHost != null && originHost != host) {
                return false
            }
        }
        val referer = session.headers["referer"]?.lowercase()?.trim()
        if (!referer.isNullOrBlank() && host != null) {
            val refererHost = runCatching { Uri.parse(referer).authority?.lowercase() }.getOrNull()
            if (refererHost != null && refererHost != host) {
                return false
            }
        }
        if (session.method == Method.POST || session.method == Method.DELETE) {
            val cookieToken = session.cookies?.read(SESSION_COOKIE_NAME)
            if (cookieToken != sessionToken) {
                return false
            }
        }
        return true
    }

    @Suppress("DEPRECATION")
    private fun handleGet(session: IHTTPSession): Response {
        val uri = session.uri
        return when {
            uri == "/" || uri == "/index.html" -> {
                serveAsset("server/index.html", MIME_HTML).apply {
                    addHeader(
                            "Set-Cookie",
                            "$SESSION_COOKIE_NAME=$sessionToken; Path=/; HttpOnly; SameSite=Strict"
                    )
                }
            }
            uri == "/app-icon" -> serveAppIcon()
            uri == "/api/theme" -> serveTheme()
            uri == "/api/player/now-playing" -> serveNowPlaying()
            uri == "/api/youtube/search" -> handleYoutubeSearch(session)
            uri == "/api/songs" -> serveSongList()
            uri == "/api/albums" -> serveAlbums()
            uri == "/api/albums/songs" -> serveAlbumSongs(session.parms["name"] ?: "")
            uri == "/api/artists" -> serveArtists()
            uri == "/api/artists/songs" -> serveArtistSongs(session.parms["name"] ?: "")
            uri == "/api/genres" -> serveGenres()
            uri == "/api/genres/songs" -> serveGenreSongs(session.parms["name"] ?: "")
            STREAM_REGEX.matches(uri) -> {
                val id = STREAM_REGEX.find(uri)!!.groupValues[1].toLongOrNull()
                    ?: return badRequest("Invalid song ID")
                streamAudio(id, session)
            }
            ART_REGEX.matches(uri) -> {
                val id = ART_REGEX.find(uri)!!.groupValues[1].toLongOrNull()
                    ?: return badRequest("Invalid song ID")
                serveAlbumArt(id)
            }
            STATIC_REGEX.matches(uri) -> {
                val filename = uri.removePrefix("/")
                val mimeType = if (filename.endsWith(".css")) MIME_CSS else MIME_JS
                try {
                    serveAsset("server/$filename", mimeType)
                } catch (_: Exception) {
                    newFixedLengthResponse(Response.Status.NOT_FOUND, MIME_PLAINTEXT, "Not found")
                }
            }
            else -> newFixedLengthResponse(Response.Status.NOT_FOUND, MIME_PLAINTEXT, "Not found")
        }
    }

    private fun handlePost(session: IHTTPSession): Response {
        val uri = session.uri
        return when {
            uri == "/api/player/control" -> handlePlayerControl(session)
            PLAYED_REGEX.matches(uri) -> {
                val id = PLAYED_REGEX.find(uri)!!.groupValues[1].toLongOrNull()
                    ?: return badRequest("Invalid song ID")
                val audio = findAudio(id) ?: return notFound("Song not found")
                runBlocking(Dispatchers.IO) { songStatRepository.recordPlay(audio.hash) }
                newFixedLengthResponse(Response.Status.OK, MIME_PLAINTEXT, "OK")
            }
            else -> {
                newFixedLengthResponse(
                        Response.Status.METHOD_NOT_ALLOWED, MIME_PLAINTEXT, "Method not allowed"
                )
            }
        }
    }

    private fun serveNowPlaying(): Response {
        val json = runBlocking(Dispatchers.Main.immediate) {
            val song = MediaPlaybackManager.getCurrentSong()
            val onlineTrack = MediaPlaybackManager.getCurrentOnlineTrack()
            val isPlaying = MediaPlaybackManager.isPlaying()
            val position = MediaPlaybackManager.getSeekPosition()
            val duration = MediaPlaybackManager.getDuration()

            mapOf(
                    "title" to (onlineTrack?.title ?: song?.title ?: "Not Playing"),
                    "artist" to (onlineTrack?.subtitle ?: song?.artist ?: ""),
                    "album" to (song?.album ?: ""),
                    "isPlaying" to isPlaying,
                    "positionMs" to position,
                    "durationMs" to duration,
                    "isOnline" to (onlineTrack != null)
            )
        }
        return jsonResponse(json)
    }

    private fun handlePlayerControl(session: IHTTPSession): Response {
        val action = session.parms["action"] ?: ""
        val seekPos = session.parms["pos"]?.toLongOrNull() ?: 0L
        runBlocking(Dispatchers.Main.immediate) {
            when (action) {
                "play" -> MediaPlaybackManager.play()
                "pause" -> MediaPlaybackManager.pause()
                "toggle" -> MediaPlaybackManager.flipState()
                "next" -> MediaPlaybackManager.next()
                "previous" -> MediaPlaybackManager.previous()
                "seek" -> MediaPlaybackManager.seekTo(seekPos)
            }
        }
        return newFixedLengthResponse(Response.Status.OK, MIME_PLAINTEXT, "OK")
    }

    private fun handleYoutubeSearch(session: IHTTPSession): Response {
        val query = session.parms["q"] ?: ""
        if (query.isBlank()) return jsonResponse(emptyList<Any>())
        val sessionObj = YoutubeSessionStore.get(context)
        val raw = YoutubeApiClient.search(query, sessionObj)
        val results = YoutubeSearchParser.parse(raw)
        return jsonResponse(results)
    }

    private fun jsonResponse(data: Any): Response {
        val json = gson.toJson(data)
        return newFixedLengthResponse(Response.Status.OK, MIME_JSON, json)
    }

    /**
     * Handles `DELETE /api/songs/{id}` by permanently removing the audio file from disk
     * and evicting it from the database and in-memory cache when remote deletion is enabled.
     */
    private fun handleDelete(session: IHTTPSession): Response {
        if (!ServerPreferences.isAllowRemoteDelete()) {
            return newFixedLengthResponse(
                    Response.Status.FORBIDDEN,
                    MIME_PLAINTEXT,
                    "Remote file deletion is disabled in Server settings"
            )
        }
        val uri = session.uri
        if (!DELETE_REGEX.matches(uri)) {
            return newFixedLengthResponse(Response.Status.NOT_FOUND, MIME_PLAINTEXT, "Not found")
        }
        val id = DELETE_REGEX.find(uri)!!.groupValues[1].toLongOrNull()
            ?: return badRequest("Invalid song ID")
        val audio = findAudio(id) ?: return notFound("Song not found")
        val audioUri = audio.uri.toUri()
        return try {
            val deleted = context.contentResolver.delete(audioUri, null, null)
            if (deleted > 0) {
                runBlocking(Dispatchers.IO) {
                    audioDatabase.audioDao()?.delete(audio)
                }
                audioCache = audioCache - id
                Log.i(TAG, "Deleted audio with URI: ${audio.uri}")
                newFixedLengthResponse(Response.Status.OK, MIME_PLAINTEXT, "Deleted")
            } else {
                Log.w(TAG, "ContentResolver reported 0 rows deleted for URI: ${audio.uri}")
                newFixedLengthResponse(
                        Response.Status.INTERNAL_ERROR, MIME_PLAINTEXT, "Could not delete file"
                )
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to delete URI: ${audio.uri}", e)
            newFixedLengthResponse(
                    Response.Status.INTERNAL_ERROR, MIME_PLAINTEXT, "Could not delete file"
            )
        }
    }

    private fun serveAsset(path: String, mimeType: String): Response {
        val text = context.assets.open(path).bufferedReader().use { it.readText() }
        return newFixedLengthResponse(Response.Status.OK, mimeType, text)
    }

    private fun serveAppIcon(): Response {
        if (iconBytes == null) {
            iconBytes = try {
                context.assets.open("server/app_icon.png").use { it.readBytes() }
            } catch (_: Exception) {
                val src = BitmapFactory.decodeResource(context.resources, R.mipmap.ic_launcher_round)
                val out = ByteArrayOutputStream()
                src.scale(128, 128)
                    .compress(Bitmap.CompressFormat.PNG, 100, out)
                out.toByteArray()
            }
        }
        val bytes = iconBytes!!
        return newFixedLengthResponse(
                Response.Status.OK, "image/png",
                ByteArrayInputStream(bytes), bytes.size.toLong()
        )
    }

    private fun serveSongList(): Response {
        val songs = audioDatabase.audioDao()?.getAllAudioList() ?: emptyList()
        audioCache = songs.associateBy { it.id }
        return jsonOk(songs.map { it.toMap() })
    }

    private fun serveAlbums(): Response {
        val albums = cachedSongs()
            .filter { !it.album.isNullOrBlank() }
            .groupBy { it.album!! }
            .map { (name, group) ->
                mapOf(
                        "name" to name,
                        "artist" to (group.firstOrNull()?.artist ?: ""),
                        "songCount" to group.size,
                        "coverSongId" to (group.firstOrNull()?.id ?: 0L)
                )
            }
            .sortedBy { (it["name"] as? String ?: "").lowercase() }
        return jsonOk(albums)
    }

    private fun serveArtists(): Response {
        val artists = cachedSongs()
            .filter { !it.artist.isNullOrBlank() }
            .groupBy { it.artist!! }
            .map { (name, group) ->
                mapOf(
                        "name" to name,
                        "songCount" to group.size,
                        "albumCount" to group.mapNotNull { it.album?.takeIf { a -> a.isNotBlank() } }.distinct().size,
                        "coverSongId" to (group.firstOrNull()?.id ?: 0L)
                )
            }
            .sortedBy { (it["name"] as? String ?: "").lowercase() }
        return jsonOk(artists)
    }

    private fun serveGenres(): Response {
        val genres = cachedSongs()
            .groupBy { it.genre?.takeIf { g -> g.isNotBlank() } ?: UNKNOWN_GENRE }
            .map { (name, group) ->
                mapOf(
                        "name" to name,
                        "songCount" to group.size,
                        "coverSongId" to (group.firstOrNull()?.id ?: 0L)
                )
            }
            .sortedBy { (it["name"] as? String ?: "").lowercase() }
        return jsonOk(genres)
    }

    private fun serveAlbumSongs(name: String): Response {
        if (name.isBlank()) return badRequest("Missing 'name' parameter")
        return jsonOk(cachedSongs().filter { it.album == name }.map { it.toMap() })
    }

    private fun serveArtistSongs(name: String): Response {
        if (name.isBlank()) return badRequest("Missing 'name' parameter")
        return jsonOk(cachedSongs().filter { it.artist == name }.map { it.toMap() })
    }

    private fun serveGenreSongs(name: String): Response {
        if (name.isBlank()) return badRequest("Missing 'name' parameter")
        val target = if (name == UNKNOWN_GENRE) null else name
        val songs = cachedSongs()
            .filter { (it.genre?.takeIf { g -> g.isNotBlank() }) == target }
            .map { it.toMap() }
        return jsonOk(songs)
    }

    private fun skipFully(stream: InputStream, bytesToSkip: Long) {
        var remaining = bytesToSkip
        val discardBuffer = ByteArray(8192)
        while (remaining > 0) {
            val skipped = stream.skip(remaining)
            if (skipped > 0) {
                remaining -= skipped
            } else {
                val read = stream.read(discardBuffer, 0, minOf(remaining, discardBuffer.size.toLong()).toInt())
                if (read < 0) break
                remaining -= read
            }
        }
    }

    /**
     * Streams the audio file through the SAF ContentResolver with HTTP Range support.
     */
    private fun streamAudio(id: Long, session: IHTTPSession): Response {
        val audio = findAudio(id) ?: return notFound("Song not found")

        val audioUri = audio.uri.toUri()
        val mimeType = audio.mimeType?.takeIf { it.isNotBlank() } ?: "audio/*"
        val range = session.headers["range"]

        val fileLength = try {
            context.contentResolver.openFileDescriptor(audioUri, "r")?.use { it.statSize } ?: -1L
        } catch (e: Exception) {
            Log.w(TAG, "Could not stat file descriptor for URI: ${audio.uri}", e)
            -1L
        }

        return if (range != null && range.startsWith("bytes=")) {
            val parts = range.removePrefix("bytes=").split("-")
            val rawStart = parts[0].toLongOrNull() ?: 0L
            val start = if (fileLength > 0) rawStart.coerceIn(0L, fileLength - 1) else maxOf(0L, rawStart)
            val parsedEnd = if (parts.size > 1 && parts[1].isNotBlank()) parts[1].toLongOrNull() else null
            val end = when {
                fileLength > 0 && parsedEnd != null -> parsedEnd.coerceIn(start, fileLength - 1)
                fileLength > 0 -> fileLength - 1
                parsedEnd != null && parsedEnd >= start -> parsedEnd
                else -> Long.MAX_VALUE / 2
            }
            val length = (end - start + 1).coerceAtLeast(0L)

            val stream: InputStream = context.contentResolver.openInputStream(audioUri)
                ?: return notFound("Could not open audio stream")
            skipFully(stream, start)

            newFixedLengthResponse(Response.Status.PARTIAL_CONTENT, mimeType, stream, length).apply {
                if (fileLength > 0) {
                    addHeader("Content-Range", "bytes $start-$end/$fileLength")
                }
                addHeader("Accept-Ranges", "bytes")
            }
        } else {
            val stream: InputStream = context.contentResolver.openInputStream(audioUri)
                ?: return notFound("Could not open audio stream")

            if (fileLength > 0) {
                newFixedLengthResponse(Response.Status.OK, mimeType, stream, fileLength).apply {
                    addHeader("Accept-Ranges", "bytes")
                }
            } else {
                newChunkedResponse(Response.Status.OK, mimeType, stream).apply {
                    addHeader("Accept-Ranges", "bytes")
                }
            }
        }
    }

    private fun serveAlbumArt(id: Long): Response {
        val audio = findAudio(id) ?: return notFound("Song not found")
        val bitmap = try {
            AudioCover.load(context, audio)
        } catch (e: Exception) {
            Log.w(TAG, "Album art load failed for id=$id", e)
            null
        }
        return if (bitmap != null && !bitmap.isRecycled) {
            val out = ByteArrayOutputStream()
            bitmap.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, out)
            val bytes = out.toByteArray()
            newFixedLengthResponse(
                    Response.Status.OK, "image/jpeg",
                    ByteArrayInputStream(bytes), bytes.size.toLong()
            )
        } else {
            notFound("No artwork available")
        }
    }

    private fun jsonOk(data: Any): Response {
        val json = gson.toJson(data)
        return newFixedLengthResponse(Response.Status.OK, MIME_JSON, json)
    }

    private fun serveTheme(): Response {
        val accent = ThemeManager.accent
        val primaryHex = String.format("#%06X", 0xFFFFFF and accent.primaryAccentColor)
        val secondaryHex = String.format("#%06X", 0xFFFFFF and accent.secondaryAccentColor)

        val isDark = when (AppearancePreferences.getTheme()) {
            ThemeConstants.LIGHT_THEME,
            ThemeConstants.HIGH_CONTRAST_LIGHT,
            ThemeConstants.SOAPSTONE -> false
            ThemeConstants.FOLLOW_SYSTEM,
            ThemeConstants.DAY_NIGHT -> {
                val nightMask = context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK
                nightMask == Configuration.UI_MODE_NIGHT_YES
            }
            else -> true
        }

        val json = """{"accent":"$primaryHex","accentSecondary":"$secondaryHex","isDark":$isDark}"""
        return newFixedLengthResponse(Response.Status.OK, MIME_JSON, json)
    }

    private fun cachedSongs(): List<Audio> {
        if (audioCache.isEmpty()) {
            audioCache = (audioDatabase.audioDao()?.getAllAudioList() ?: emptyList())
                .associateBy { it.id }
        }
        return audioCache.values.toList()
    }

    private fun findAudio(id: Long): Audio? {
        if (audioCache.isEmpty()) {
            audioCache = (audioDatabase.audioDao()?.getAllAudioList() ?: emptyList())
                .associateBy { it.id }
        }
        return audioCache[id]
    }

    private fun Audio.toMap(): Map<String, Any?> = mapOf(
            "id" to id,
            "title" to (title?.takeIf { it.isNotBlank() } ?: name),
            "name" to name,
            "artist" to artist,
            "album" to album,
            "genre" to genre,
            "duration" to duration,
            "mimeType" to mimeType
    )

    private fun badRequest(message: String): Response =
        newFixedLengthResponse(Response.Status.BAD_REQUEST, MIME_PLAINTEXT, message)

    private fun notFound(message: String): Response =
        newFixedLengthResponse(Response.Status.NOT_FOUND, MIME_PLAINTEXT, message)

    @Throws(IOException::class)
    fun startServer() {
        start(SOCKET_READ_TIMEOUT, false)
    }

    companion object {
        private const val TAG = "MusicHttpServer"
        private const val SESSION_COOKIE_NAME = "felicity_session"
        private const val MIME_HTML = "text/html; charset=utf-8"
        private const val MIME_CSS = "text/css; charset=utf-8"
        private const val MIME_JS = "application/javascript; charset=utf-8"
        private const val MIME_JSON = "application/json; charset=utf-8"
        private const val JPEG_QUALITY = 85
        private const val UNKNOWN_GENRE = "Unknown"

        private val STREAM_REGEX = Regex("/api/songs/(\\d+)/stream")
        private val ART_REGEX = Regex("/api/songs/(\\d+)/art")
        private val PLAYED_REGEX = Regex("/api/songs/(\\d+)/played")
        private val DELETE_REGEX = Regex("/api/songs/(\\d+)")

        /** Matches any top-level CSS or JS file request, e.g. `/theme.css` or `/app.js`. */
        private val STATIC_REGEX = Regex("/[\\w-]+\\.(?:css|js)")
    }
}
