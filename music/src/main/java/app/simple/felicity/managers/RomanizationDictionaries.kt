package app.simple.felicity.managers

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.zip.GZIPInputStream

/**
 * Optional, user-downloadable romanization dictionaries.
 *
 * Reading kanji (or choosing between the several readings of a Chinese character) is impossible
 * without a word list, so Auto romanize works in two tiers: the built-in rules that need no
 * download, and a per-language dictionary that, once downloaded, takes over for that language.
 *
 * A pack is one gzip-compressed TSV (see [RomanizationDictionary]). New languages are added by
 * publishing another pack file and appending one [Pack] to [PACKS]; nothing else changes.
 */
object RomanizationDictionaries {

    private const val TAG = "RomanizationDicts"
    private const val BASE_URL =
        "https://raw.githubusercontent.com/infinity6901/felicity/Felicity-Remake/dictionaries/romanization/"

    data class Pack(
            val id: String,
            val name: String,
            val description: String,
            val approxSizeMb: Float,
            val fileName: String = "$id.tsv.gz",
    ) {
        val url: String get() = BASE_URL + fileName
    }

    val PACKS = listOf(
            Pack("ja", "Japanese", "Kanji readings with word-aware spacing and particle sounds (は→wa, を→o). Based on IPAdic.", 2.7f),
            Pack("zh", "Chinese (Mandarin)", "Phrase-aware pinyin, so characters with several readings are romanized correctly.", 0.7f),
            Pack("hi", "Hindi", "Spoken-style romanization with schwa deletion (kamal, not kamala) and common spellings, for about 24,000 frequent words.", 0.3f),
            Pack("bn", "Bengali", "Silent final vowels, doubled conjuncts (shotto) and common spellings, for about 57,000 frequent words.", 0.7f),
    )

    /** Bumped on every install / removal so cached romanizations can be recomputed. */
    @Volatile
    var revision: Int = 0
        private set

    private fun dir(context: Context) = File(context.filesDir, "romanization").apply { mkdirs() }

    fun file(context: Context, id: String) = File(dir(context), "$id.tsv.gz")

    fun isInstalled(context: Context, id: String): Boolean = file(context, id).let { it.isFile && it.length() > 0 }

    fun installedSizeBytes(context: Context, id: String): Long = file(context, id).takeIf { it.isFile }?.length() ?: 0L

    fun delete(context: Context, id: String) {
        file(context, id).delete()
        revision++
    }

    /**
     * Downloads [pack] into app storage. Returns null on success or a short error message.
     * [onProgress] receives 0..100 (or -1 while the size is unknown).
     */
    suspend fun download(context: Context, pack: Pack, onProgress: (Int) -> Unit): String? =
        withContext(Dispatchers.IO) {
            val target = file(context, pack.id)
            val temp = File(target.parentFile, "${pack.id}.part")
            var connection: HttpURLConnection? = null
            try {
                connection = (URL(pack.url).openConnection() as HttpURLConnection).apply {
                    connectTimeout = 15_000
                    readTimeout = 30_000
                    instanceFollowRedirects = true
                }
                if (connection.responseCode != HttpURLConnection.HTTP_OK) {
                    return@withContext "Server answered ${connection.responseCode}"
                }
                val total = connection.contentLengthLong
                var done = 0L
                var lastPercent = -2
                connection.inputStream.use { input ->
                    FileOutputStream(temp).use { output ->
                        val buffer = ByteArray(32 * 1024)
                        while (true) {
                            val read = input.read(buffer)
                            if (read < 0) break
                            output.write(buffer, 0, read)
                            done += read
                            val percent = if (total > 0) (done * 100 / total).toInt() else -1
                            if (percent != lastPercent) {
                                lastPercent = percent
                                onProgress(percent)
                            }
                        }
                    }
                }
                // Make sure it really is a readable pack before it replaces anything.
                if (!looksValid(temp)) {
                    temp.delete()
                    return@withContext "Downloaded file is not a valid dictionary"
                }
                target.delete()
                if (!temp.renameTo(target)) return@withContext "Could not save the dictionary"
                revision++
                null
            } catch (e: Exception) {
                Log.w(TAG, "Download of ${pack.id} failed", e)
                temp.delete()
                e.message ?: e.javaClass.simpleName
            } finally {
                connection?.disconnect()
            }
        }

    private fun looksValid(file: File): Boolean = runCatching {
        GZIPInputStream(file.inputStream().buffered()).bufferedReader().use { reader ->
            reader.readLine()?.startsWith(RomanizationDictionary.HEADER) == true
        }
    }.getOrDefault(false)
}
