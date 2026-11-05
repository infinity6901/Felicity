package app.simple.felicity.repository.youtube

import android.content.Context
import java.io.File

/**
 * Stand-in used by "lite" builds (`-PliteBuild=true`), which leave out the bundled
 * Python + yt-dlp + ffmpeg runtime to keep the APK small. It exposes the same API as the
 * full implementation but reports itself as unavailable, so callers fall back to the
 * lightweight InnerTube resolver and skip YouTube downloads.
 */
object YtDlpManager {

    @JvmStatic
    fun init(@Suppress("UNUSED_PARAMETER") context: Context) = Unit

    val isAvailable: Boolean
        get() = false

    suspend fun resolveDirectUrl(
            @Suppress("UNUSED_PARAMETER") videoId: String,
            @Suppress("UNUSED_PARAMETER") cookiesFile: File? = null
    ): String? = null

    data class DownloadResult(val file: File, val title: String?, val artist: String?, val thumbnailUrl: String?)

    suspend fun downloadAudio(
            @Suppress("UNUSED_PARAMETER") videoId: String,
            @Suppress("UNUSED_PARAMETER") destinationDir: File,
            @Suppress("UNUSED_PARAMETER") format: String = "m4a",
            @Suppress("UNUSED_PARAMETER") cookiesFile: File? = null,
            @Suppress("UNUSED_PARAMETER") onProgress: (percent: Float) -> Unit,
    ): DownloadResult? = null
}
