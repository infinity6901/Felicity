package app.simple.felicity.repository.youtube

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.ListenableWorker.Result
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import app.simple.felicity.repository.metadata.LyricsMetaHelper
import app.simple.felicity.repository.metadata.MetadataWriter
import app.simple.felicity.repository.repositories.LrcRepository
import app.simple.felicity.repository.services.AudioDatabaseService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import java.io.File
import java.util.UUID
import java.util.concurrent.TimeUnit

/**
 * Downloads a YouTube-sourced track into the device's public Music library, ported from
 * Umihi's download flow but rebuilt around yt-dlp (see [YtDlpManager]) instead of a
 * hand-rolled extractor, and around [MetadataWriter]/[LyricsMetaHelper] (Felicity's own
 * native TagLib pipeline) instead of yt-dlp's own tagging flags — so a YouTube download
 * ends up tagged exactly the same way any other track in this library is.
 *
 * Pipeline: yt-dlp downloads + reports live progress -> file is copied into
 * MediaStore.Audio under Music/Felicity (the real, public Music folder, not an
 * app-private directory) -> title/artist/artwork are embedded -> lyrics are looked up on
 * LRCLIB by title+artist and embedded if found -> AudioDatabaseService.refreshScan
 * picks it up like any other local file.
 *
 * Legal/ToS note carried over from the rest of this package: this sits outside
 * YouTube's Terms of Service, same as it does for yt-dlp/NewPipe generally.
 */
class YoutubeDownloadWorker(
    context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val videoId = inputData.getString(KEY_VIDEO_ID) ?: return@withContext Result.failure()
        val fallbackTitle = inputData.getString(KEY_TITLE) ?: videoId
        val fallbackArtist = inputData.getString(KEY_ARTIST)
        val format = inputData.getString(KEY_FORMAT) ?: "m4a"

        if (!YtDlpManager.isAvailable) {
            return@withContext Result.failure(
                workDataOf(KEY_ERROR to "yt-dlp runtime isn't ready yet - try again in a few seconds")
            )
        }

        val tempDir = File(applicationContext.cacheDir, "yt_downloads/$id")

        return@withContext try {
            val downloadResult = YtDlpManager.downloadAudio(videoId, tempDir, format = format) { percent ->
                setProgressAsync(workDataOf(KEY_PROGRESS to percent.toInt()))
            } ?: return@withContext Result.failure(
                workDataOf(KEY_ERROR to "yt-dlp could not download this video")
            )

            val title = downloadResult.title ?: fallbackTitle
            val artist = downloadResult.artist ?: fallbackArtist

            setProgressAsync(workDataOf(KEY_PROGRESS to 95))

            val mediaStoreUri = saveToMusicLibrary(downloadResult.file, title)
                ?: return@withContext Result.failure(workDataOf(KEY_ERROR to "Could not save into Music library"))

            embedMetadata(mediaStoreUri, title, artist, downloadResult.thumbnailUrl?.let { File(it) })
            embedLyricsIfFound(mediaStoreUri, title, artist)

            tempDir.deleteRecursively()

            setProgressAsync(workDataOf(KEY_PROGRESS to 100))
            AudioDatabaseService.refreshScan(applicationContext)

            Result.success(workDataOf(KEY_OUTPUT_URI to mediaStoreUri.toString()))
        } catch (e: Exception) {
            tempDir.deleteRecursively()
            Result.failure(workDataOf(KEY_ERROR to (e.message ?: "Unknown error")))
        }
    }

    /**
     * Copies the yt-dlp output into MediaStore.Audio.Media, under the real, public
     * Music folder (via RELATIVE_PATH) rather than an app-private directory - this is
     * the modern (API 29+, matches this app's minSdk) scoped-storage-correct way to
     * write into a shared media collection.
     */
    private fun saveToMusicLibrary(sourceFile: File, title: String): Uri? {
        val resolver = applicationContext.contentResolver
        val ext = sourceFile.extension.ifBlank { "m4a" }
        val displayName = sanitizeFileName(title) + "." + ext
        val mimeType = when (ext) {
            "mp3" -> "audio/mpeg"
            "flac" -> "audio/flac"
            else -> "audio/mp4"
        }

        val values = ContentValues().apply {
            put(MediaStore.Audio.Media.DISPLAY_NAME, displayName)
            put(MediaStore.Audio.Media.MIME_TYPE, mimeType)
            put(MediaStore.Audio.Media.RELATIVE_PATH, Environment.DIRECTORY_MUSIC)
            put(MediaStore.Audio.Media.IS_PENDING, 1)
        }

        val collection = MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        val itemUri = resolver.insert(collection, values) ?: return null

        resolver.openOutputStream(itemUri)?.use { output ->
            sourceFile.inputStream().use { input -> input.copyTo(output) }
        } ?: return null

        values.clear()
        values.put(MediaStore.Audio.Media.IS_PENDING, 0)
        resolver.update(itemUri, values, null, null)

        return itemUri
    }

    private fun embedMetadata(uri: Uri, title: String, artist: String?, thumbnail: File?) {
        val fields = MetadataWriter.Fields(
            title = title,
            artist = artist,
            album = "YouTube Music",
            albumArtist = artist,
            year = null,
            trackNumber = null,
            numTracks = null,
            discNumber = null,
            genre = null,
            composer = null,
            writer = null,
            compilation = null,
            comment = "Downloaded from YouTube via Felicity",
            lyrics = null,
            artworkFile = thumbnail?.takeIf { it.exists() },
        )
        MetadataWriter.write(uri, fields, applicationContext.contentResolver)
    }

    private fun embedLyricsIfFound(uri: Uri, title: String, artist: String?) {
        if (artist.isNullOrBlank()) return

        val lyrics = fetchLrcLibLyrics(title, artist) ?: return
        LyricsMetaHelper.embedLyrics(applicationContext, uri.toString(), lyrics)
    }

    /**
     * Direct, minimal call to LRCLIB - deliberately not routed through the app's own
     * Hilt-injected LrcRepository since wiring Hilt into a plain CoroutineWorker
     * needs a custom androidx.work.WorkerFactory registered in the Application class,
     * which is a bigger change than this one lookup justifies.
     */
    private fun fetchLrcLibLyrics(title: String, artist: String): String? {
        return runCatching {
            val repo = LrcRepository(applicationContext)
            runBlocking {
                val res = repo.searchLyrics(title, artist)
                val match = res.getOrNull()?.firstOrNull()
                match?.syncedLyrics?.takeIf { it.isNotBlank() }
                    ?: match?.plainLyrics?.takeIf { it.isNotBlank() }
            }
        }.getOrNull()
    }

    private fun sanitizeFileName(name: String): String =
        name.replace(Regex("[\\\\/:*?\"<>|]"), "_").take(120).ifBlank { "track" }

    companion object {
        const val KEY_VIDEO_ID = "video_id"
        const val KEY_TITLE = "title"
        const val KEY_ARTIST = "artist"
        const val KEY_FORMAT = "format"
        const val KEY_PROGRESS = "progress"
        const val KEY_OUTPUT_URI = "output_uri"
        const val KEY_ERROR = "error"
        const val TAG_DOWNLOAD = "youtube_download"

        /** Enqueues a download and returns the WorkManager request ID for observing live progress. */
        fun enqueue(context: Context, videoId: String, title: String, artist: String? = null, format: String = "m4a"): UUID {
            val data = Data.Builder()
                .putString(KEY_VIDEO_ID, videoId)
                .putString(KEY_TITLE, title)
                .putString(KEY_FORMAT, format)
                .apply { artist?.let { putString(KEY_ARTIST, it) } }
                .build()

            val request = OneTimeWorkRequestBuilder<YoutubeDownloadWorker>()
                .addTag(TAG_DOWNLOAD)
                .setInputData(data)
                .build()

            WorkManager.getInstance(context).enqueue(request)
            return request.id
        }

        /** Cancels all enqueued or currently running YoutubeDownloadWorker tasks in WorkManager. */
        fun cancelAllDownloads(context: Context) {
            WorkManager.getInstance(context).cancelAllWorkByTag(TAG_DOWNLOAD)
        }
    }
}
