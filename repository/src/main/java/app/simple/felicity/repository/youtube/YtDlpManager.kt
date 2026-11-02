package app.simple.felicity.repository.youtube

import android.content.Context
import android.util.Log
import com.yausername.ffmpeg.FFmpeg
import com.yausername.youtubedl_android.YoutubeDL
import com.yausername.youtubedl_android.YoutubeDLRequest
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID

/**
 * Wraps `youtubedl-android` — a bundled Python + yt-dlp + ffmpeg build for Android
 * (https://github.com/yausername/youtubedl-android) — for resolving direct stream URLs
 * and downloading audio.
 *
 * This replaces the hand-rolled InnerTube extraction in [YoutubeStreamResolver] as the
 * primary path: yt-dlp is maintained daily against YouTube's cipher/response changes,
 * where a hand-rolled client silently breaks whenever YouTube tweaks something.
 * [YoutubeStreamResolver] is kept as a lighter-weight fallback since it needs no
 * ~30MB Python runtime, but yt-dlp is tried first.
 */
object YtDlpManager {

    private const val TAG = "YtDlpManager"

    @Volatile
    private var initialized = false

    @Volatile
    private var initFailed = false

    /** Call once from Application.onCreate(). Safe to call multiple times. */
    @JvmStatic
    fun init(context: Context) {
        if (initialized || initFailed) return

        synchronized(this) {
            if (initialized || initFailed) return

            try {
                YoutubeDL.getInstance().init(context.applicationContext)
                FFmpeg.getInstance().init(context.applicationContext)
                initialized = true
                Log.i(TAG, "yt-dlp/ffmpeg runtime initialized")

                // Update yt-dlp binary asynchronously so the app gets YouTube fixes promptly.
                CoroutineScope(Dispatchers.IO).launch {
                    try {
                        val updateStatus = YoutubeDL.getInstance().updateYoutubeDL(
                            context.applicationContext,
                            YoutubeDL.UpdateChannel.STABLE
                        )
                        Log.i(TAG, "yt-dlp background update result: $updateStatus")
                    } catch (e: Exception) {
                        Log.w(TAG, "yt-dlp background update skipped or failed", e)
                    }
                }
            } catch (e: Throwable) {
                // Deliberately Throwable, not just YoutubeDLException: this runs on a bare background
                // thread from Application.onCreate, so ANY escaped error (a NoClassDefFoundError or
                // UnsatisfiedLinkError in a release build, say) would kill the whole app at launch.
                initFailed = true
                Log.e(TAG, "Failed to initialize yt-dlp runtime — falling back to InnerTube-only extraction", e)
            }
        }
    }

    val isAvailable: Boolean
        get() = initialized

    private fun createBaseRequest(url: String, cookiesFile: File?): YoutubeDLRequest {
        return YoutubeDLRequest(url).apply {
            addOption("--no-update")
            addOption("--user-agent", YoutubeConstants.STREAMING_USER_AGENT)
            // Use player clients (android, ios, mweb) that do not enforce SABR streaming / return HTTP 403 Forbidden
            addOption("--extractor-args", "youtube:player_client=android,ios,mweb")
            cookiesFile?.let { addOption("--cookies", it.absolutePath) }
        }
    }

    /**
     * Resolves a direct, playable audio URL for [videoId] via `yt-dlp -g -f bestaudio`.
     * Returns null if yt-dlp isn't initialized or resolution fails for any reason
     * (private/region-locked/deleted video, etc.).
     */
    suspend fun resolveDirectUrl(videoId: String, cookiesFile: File? = null): String? =
        withContext(Dispatchers.IO) {
            if (!initialized) return@withContext null

            try {
                val request = createBaseRequest("https://music.youtube.com/watch?v=$videoId", cookiesFile).apply {
                    addOption("-f", "m4a/bestaudio/best")
                    addOption("-g")
                    addOption("--no-playlist")
                }
                val response = YoutubeDL.getInstance().execute(request)
                response.out.lineSequence().firstOrNull { it.startsWith("http") }
            } catch (e: Exception) {
                Log.w(TAG, "yt-dlp URL resolution failed for $videoId", e)
                null
            }
        }

    data class DownloadResult(val file: File, val title: String?, val artist: String?, val thumbnailUrl: String?)

    /**
     * Downloads and extracts the best-quality audio for [videoId] into [destinationDir]
     * as an m4a file, reporting 0–100 progress via [onProgress].
     *
     * Metadata embedding is deliberately *not* done via yt-dlp's own `--add-metadata`
     * here — Felicity already has a native TagLib-based writer
     * ([app.simple.felicity.repository.metadata.MetadataWriter]) that the caller uses
     * afterwards for consistency with how every other track in the library gets tagged,
     * and so lyrics (fetched separately via LrcRepository) can be embedded in the same pass.
     */
    suspend fun downloadAudio(
        videoId: String,
        destinationDir: File,
        format: String = "m4a",
        cookiesFile: File? = null,
        onProgress: (percent: Float) -> Unit,
    ): DownloadResult? = withContext(Dispatchers.IO) {
        if (!initialized) return@withContext null

        destinationDir.mkdirs()
        val outputTemplate = File(destinationDir, "${UUID.randomUUID()}.%(ext)s").absolutePath

        val audioFormat = when (format.lowercase()) {
            "mp3" -> "mp3"
            "flac" -> "flac"
            else -> "m4a"
        }

        val request = createBaseRequest("https://music.youtube.com/watch?v=$videoId", cookiesFile).apply {
            addOption("-x") // extract audio only
            addOption("--audio-format", audioFormat)
            addOption("--audio-quality", "0") // best
            addOption("--no-playlist")
            addOption("--write-thumbnail")
            addOption("-o", outputTemplate)
        }

        return@withContext try {
            var lastTitle: String? = null
            var lastArtist: String? = null

            YoutubeDL.getInstance().execute(request) { progress, _, _ ->
                onProgress(progress)
            }

            val downloaded = destinationDir.listFiles { f -> f.extension == audioFormat }
                ?.maxByOrNull { it.lastModified() }
                ?: destinationDir.listFiles { f -> f.extension != "jpg" && f.extension != "png" && f.extension != "webp" }
                    ?.maxByOrNull { it.lastModified() }
                ?: return@withContext null

            val thumbnail = destinationDir.listFiles { f ->
                f.nameWithoutExtension == downloaded.nameWithoutExtension && f.extension != "m4a"
            }?.firstOrNull()

            // yt-dlp's own info JSON (via --print) would be a second process call; instead
            // pull title/artist out of the info dict yt-dlp already fetched for this request.
            runCatching {
                val infoRequest = createBaseRequest("https://music.youtube.com/watch?v=$videoId", cookiesFile).apply {
                    addOption("--print", "%(title)s|||%(artist,uploader)s")
                    addOption("--skip-download")
                }
                val info = YoutubeDL.getInstance().execute(infoRequest).out.trim()
                val parts = info.split("|||")
                lastTitle = parts.getOrNull(0)?.takeIf { it.isNotBlank() && it != "NA" }
                lastArtist = parts.getOrNull(1)?.takeIf { it.isNotBlank() && it != "NA" }
            }

            DownloadResult(
                file = downloaded,
                title = lastTitle,
                artist = lastArtist,
                thumbnailUrl = thumbnail?.absolutePath,
            )
        } catch (e: Exception) {
            Log.w(TAG, "yt-dlp download failed for $videoId", e)
            null
        }
    }
}
