package app.simple.felicity.repository.metadata

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import android.util.Log
import app.simple.felicity.repository.metadata.MetadataWriter.write
import java.io.File

/**
 * Writes audio tag metadata to a file using TagLib through JNI.
 *
 * Call [write] with a [Uri] along with a [Fields] object describing what to change.
 *
 * @author Hamza417
 */
object MetadataWriter {

    private const val TAG = "MetadataWriter"

    data class Fields(
            val title: String?,
            val artist: String?,
            val album: String?,
            val albumArtist: String?,
            val year: String?,
            val trackNumber: String?,
            val numTracks: String?,
            val discNumber: String?,
            val genre: String?,
            val composer: String?,
            val writer: String?,
            val compilation: String?,
            val comment: String?,
            val lyrics: String?,
            val artworkFile: File? = null,
            val replayGainTrackGain: String? = null,
            val replayGainTrackPeak: String? = null,
            val replayGainAlbumGain: String? = null,
            val replayGainAlbumPeak: String? = null
    )

    /**
     * Writes [fields] (including optional cover artwork) into the audio file pointed to by [uri].
     */
    fun write(context: Context, uri: Uri, fields: Fields): Boolean {
        return write(uri, fields, context.contentResolver)
    }

    /**
     * Writes [fields] (including optional cover artwork) into the audio file pointed to by [uri].
     */
    fun write(uri: Uri, fields: Fields, contentResolver: ContentResolver): Boolean {
        return try {
            val tagsOk = contentResolver.openFileDescriptor(uri, "rw")?.use { pfd ->
                callNativeSave(pfd.fd, fields).also {
                    if (it) Log.d(TAG, "Tags saved to file: $uri")
                    else Log.w(TAG, "TagLib save returned false for: $uri")
                }
            } ?: run {
                Log.e(TAG, "ContentResolver returned null pfd for: $uri")
                false
            }

            // If the user picked new artwork, write it in a second pass so that
            // the tag write and the picture write each get a clean file descriptor.
            val artworkOk = if (tagsOk && fields.artworkFile != null) {
                writeArtwork(uri, fields.artworkFile, contentResolver)
            } else {
                true
            }

            tagsOk && artworkOk
        } catch (e: Exception) {
            Log.e(TAG, "Failed to write tags to file: $uri", e)
            false
        }
    }

    private fun writeArtwork(uri: Uri, artworkFile: File, contentResolver: ContentResolver): Boolean {
        return try {
            val imageBytes = artworkFile.readBytes()
            val mimeType = when (artworkFile.extension.lowercase()) {
                "png" -> "image/png"
                else -> "image/jpeg"
            }
            contentResolver.openFileDescriptor(uri, "rw")?.use { pfd ->
                TagLibBridge.nativeSaveArtworkToFd(pfd.fd, imageBytes, mimeType).also {
                    if (it) Log.d(TAG, "Artwork saved to file: $uri")
                    else Log.w(TAG, "TagLib artwork save returned false for: $uri")
                }
            } ?: run {
                Log.e(TAG, "ContentResolver returned null pfd for artwork write: $uri")
                false
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to write artwork to file: $uri", e)
            false
        }
    }

    private fun callNativeSave(fd: Int, fields: Fields): Boolean {
        return TagLibBridge.nativeSaveToFd(
                fd = fd,
                title = fields.title,
                artist = fields.artist,
                album = fields.album,
                albumArtist = fields.albumArtist,
                year = fields.year,
                trackNumber = fields.trackNumber,
                numTracks = fields.numTracks,
                discNumber = fields.discNumber,
                genre = fields.genre,
                composer = fields.composer,
                lyricist = fields.writer,
                compilation = fields.compilation,
                comment = fields.comment,
                lyrics = fields.lyrics,
                replayGainTrackGain = fields.replayGainTrackGain,
                replayGainTrackPeak = fields.replayGainTrackPeak,
                replayGainAlbumGain = fields.replayGainAlbumGain,
                replayGainAlbumPeak = fields.replayGainAlbumPeak
        )
    }
}
