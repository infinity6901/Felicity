package app.simple.felicity.repository.metadata

import android.content.Context
import android.util.Log
import androidx.core.net.toUri

/**
 * A small helper that knows how to pull lyrics out of an audio file's tags.
 *
 * Instead of a heavy third-party library, this goes straight to our native
 * TagLib bridge — same one used for all other metadata — so we get support
 * for MP3 (USLT), M4A (©lyr), FLAC/OGG (LYRICS Vorbis comment), and more
 * with zero extra dependencies. Pretty neat, right?
 *
 * @author Hamza417
 */
object LyricsMetaHelper {

    private const val TAG = "LyricsMetaHelper"

    /**
     * Opens [audioUri] through the system's content resolver, hands the raw
     * file descriptor to TagLib's JNI bridge, and returns whatever lyrics are
     * baked into the file's tags — or null if there are none.
     *
     * This works with both regular file paths and SAF content:// URIs because
     * we never need the actual path: only the file descriptor matters.
     *
     * @param context  Any valid Android context — needed to reach the ContentResolver.
     * @param audioUri The URI (content:// or file://) of the audio file to inspect.
     * @return The embedded lyrics string if found, or null if the file has none.
     */
    fun extractEmbeddedLyrics(context: Context, audioUri: String): String? {
        return try {
            val uri = audioUri.toUri()
            context.contentResolver.openFileDescriptor(uri, "r")?.use { pfd ->
                val lyrics = TagLibBridge.nativeExtractLyricsFromFd(pfd.fd)
                if (!lyrics.isNullOrBlank()) {
                    Log.d(TAG, "Found embedded lyrics in ${audioUri.substringAfterLast('/')}")
                }
                lyrics?.takeIf { it.isNotBlank() }
            }
        } catch (e: Exception) {
            // If the file can't be opened (missing permissions, bad URI, etc.)
            // we just quietly return null — no crash, no drama.
            Log.w(TAG, "Could not open $audioUri to extract embedded lyrics: ${e.message}")
            null
        }
    }

    /**
     * Writes [lyricsText] directly into [audioUri]'s tag metadata (USLT for MP3, ©lyr
     * for M4A, LYRICS Vorbis comment for FLAC/OGG, etc.) via the same native TagLib
     * bridge used by [extractEmbeddedLyrics]. No other tag field is touched — every
     * other [MetadataWriter.Fields] entry is left `null`, which TagLib treats as
     * "leave this field exactly as it is".
     *
     * This is only ever meant to be called from an explicit, user-confirmed action.
     * Lyrics fetched automatically from the internet should never be embedded
     * silently — an occasional wrong match would otherwise permanently alter the
     * file's tags instead of just a disposable `.lrc` sidecar that's easy to redo.
     *
     * @param context    Any valid Android context — needed to reach the ContentResolver.
     * @param audioUri   The URI (content:// or file://) of the audio file to modify.
     * @param lyricsText The lyrics content to embed (LRC-timestamped or plain text).
     * @return `true` if the write succeeded, `false` otherwise.
     */
    fun embedLyrics(context: Context, audioUri: String, lyricsText: String): Boolean {
        return try {
            val uri = audioUri.toUri()
            val fields = MetadataWriter.Fields(
                    title = null,
                    artist = null,
                    album = null,
                    albumArtist = null,
                    year = null,
                    trackNumber = null,
                    numTracks = null,
                    discNumber = null,
                    genre = null,
                    composer = null,
                    writer = null,
                    compilation = null,
                    comment = null,
                    lyrics = lyricsText
            )
            MetadataWriter.write(uri, fields, context.contentResolver).also { success ->
                if (success) {
                    Log.d(TAG, "Embedded lyrics into ${audioUri.substringAfterLast('/')}")
                } else {
                    Log.w(TAG, "TagLib reported failure embedding lyrics into $audioUri")
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Could not embed lyrics into $audioUri: ${e.message}")
            false
        }
    }
}