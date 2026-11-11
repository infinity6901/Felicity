package app.simple.felicity.viewmodels.player

import android.app.Application
import android.util.Log
import androidx.core.net.toUri
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.viewModelScope
import app.simple.felicity.extensions.viewmodels.WrappedViewModel
import app.simple.felicity.repository.database.instances.AudioDatabase
import app.simple.felicity.repository.models.Audio
import app.simple.felicity.repository.models.WaveformData
import app.simple.felicity.repository.utils.AudioUtils.getProperTitle
import com.linc.amplituda.Amplituda
import com.linc.amplituda.Cache
import com.linc.amplituda.Compress
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import java.io.File
import javax.inject.Inject
import kotlin.math.PI
import kotlin.math.sin
import kotlin.random.Random.Default.nextFloat

/**
 * ViewModel responsible for loading and exposing per-second amplitude data
 * for the currently playing audio track.
 *
 * Uses a DB-first strategy: when a track changes, the ViewModel checks the
 * {@code waveform_data} table first. If a row is already there, the stored samples
 * are used immediately without touching the audio file. Only when no cached
 * row exists does the ViewModel call [Amplituda] to decode the file, after
 * which the result is saved to the database so the next play is instant.
 *
 * Values are normalized to [0.0, 1.0], with one entry per second of audio.
 *
 * @author Hamza417
 */
@HiltViewModel
class WaveformViewModel @Inject constructor(
        application: Application
) : WrappedViewModel(application) {

    private val waveformData: MutableLiveData<FloatArray> = MutableLiveData()
    private var currentUri: String? = null
    private var loadJob: Job? = null
    private var amplitudaInstance: Amplituda? = null
    private val amplitudaLock = Any()

    /**
     * Returns a [LiveData] stream of normalized amplitude arrays.
     * Each emission contains one [Float] value per second of audio, in [0.0, 1.0].
     */
    fun getWaveformData(): LiveData<FloatArray> = waveformData

    fun loadWaveform(audio: Audio) {
        // Prevent redundant loading
        if (audio.uri == currentUri && waveformData.value?.isNotEmpty() == true) return

        // Track the current request and cancel any superseded decode job
        currentUri = audio.uri
        loadJob?.cancel()

        loadJob = viewModelScope.launch(Dispatchers.IO) {
            runCatching {
                if (currentUri != audio.uri) return@launch

                postFlatData(audio) // Show the ghost waveform immediately while we load the real one

                val dao = AudioDatabase.getInstance(getApplication()).waveformDao()

                // DB-first: try to get the cached samples before touching the audio file
                val cached = dao.getWaveformByHash(audio.hash)
                if (cached != null) {
                    val samples = cached.toFloatArray()
                    if (samples.isNotEmpty() && currentUri == audio.uri) {
                        waveformData.postValue(samples)
                        return@launch
                    }
                }

                val rawAmplitudes = extractRawAmplitudes(audio)

                if (currentUri != audio.uri) return@launch

                if (rawAmplitudes.isNullOrEmpty()) {
                    waveformData.postValue(getRandomGhostData(audio.duration))
                    return@launch
                }

                // --- Deterministic Time-Based Downsampling ---

                val expectedBars = ((audio.duration / 1000f) * BARS_PER_SECOND).toInt().coerceAtLeast(1)

                val chunkedAmplitudes = FloatArray(expectedBars)
                val chunkSize = rawAmplitudes.size.toFloat() / expectedBars

                for (i in 0 until expectedBars) {
                    val start = (i * chunkSize).toInt()
                    val end = ((i + 1) * chunkSize).toInt().coerceAtMost(rawAmplitudes.size)

                    var peakInChunk = 0
                    for (j in start until end) {
                        if (rawAmplitudes[j] > peakInChunk) {
                            peakInChunk = rawAmplitudes[j]
                        }
                    }
                    chunkedAmplitudes[i] = peakInChunk.toFloat()
                }

                // --- Final Normalization ---

                val maxPeak = chunkedAmplitudes.maxOrNull() ?: 1f
                val sampled = if (maxPeak > 0f) {
                    FloatArray(expectedBars) { i ->
                        chunkedAmplitudes[i] / maxPeak
                    }
                } else {
                    getRandomGhostData(audio.duration)
                }

                // Persist to DB so the next play of this track skips decoding entirely
                dao.insertWaveform(WaveformData.fromFloatArray(audio.hash, sampled))

                // Push the perfectly scaled array safely to the UI thread
                if (currentUri == audio.uri) {
                    waveformData.postValue(sampled)
                }
            }.getOrElse {
                Log.e(TAG, "Unexpected error in loadWaveform for ${audio.getProperTitle()}", it)
                if (currentUri == audio.uri) {
                    waveformData.postValue(getRandomGhostData(audio.duration))
                }
            }
        }
    }

    private fun extractRawAmplitudes(audio: Audio): List<Int>? = synchronized<List<Int>?>(amplitudaLock) {
        val amplituda = amplitudaInstance ?: Amplituda(getApplication()).also {
            amplitudaInstance = it
        }
        val compress = Compress.withParams(Compress.AVERAGE, BARS_PER_SECOND)
        val cache = Cache.withParams(Cache.REUSE, audio.hash.toString())

        // 1. Prefer direct filesystem path when available (most reliable for native FFmpeg probing)
        val directPath = audio.path?.takeIf { it.isNotBlank() && File(it).canRead() }
        if (directPath != null) {
            val fromPath = runCatching<List<Int>?> {
                amplituda.processAudio(directPath, compress, cache)?.get()?.amplitudesAsList()
            }.getOrNull()
            if (!fromPath.isNullOrEmpty()) return@synchronized fromPath
        }

        // 2. Try ContentResolver InputStream
        val fromStream = runCatching<List<Int>?> {
            // This Amplituda version only reads paths and file descriptors, so the stream is
            // copied to a temporary file first.
            val temp = File(getApplication<Application>().cacheDir, "waveform_${audio.hash}.tmp")
            try {
                val copied = getApplication<Application>().contentResolver.openInputStream(audio.uri.toUri())?.use { stream ->
                    temp.outputStream().use { out -> stream.copyTo(out) }
                    true
                } ?: false
                if (copied) amplituda.processAudio(temp.absolutePath, compress, cache)?.get()?.amplitudesAsList() else null
            } finally {
                temp.delete()
            }
        }.getOrNull()
        if (!fromStream.isNullOrEmpty()) return@synchronized fromStream

        // 3. Fallback to ParcelFileDescriptor with null-safe result unwrapping
        return@synchronized runCatching<List<Int>?> {
            getApplication<Application>().contentResolver.openFileDescriptor(audio.uri.toUri(), "r")?.use { pfd ->
                amplituda.processAudio(pfd, compress, cache)?.get()?.amplitudesAsList()
            }
        }.onFailure { e ->
            Log.w(TAG, "Amplituda extraction failed on IO thread for ${audio.getProperTitle()}", e)
        }.getOrNull()
    }

    /**
     * Clears the current waveform data and cancels any pending load.
     * Call this when the user navigates to a song whose waveform has not yet been decoded.
     */
    fun resetWaveform() {
        loadJob?.cancel()
        currentUri = null
        waveformData.postValue(FloatArray(0))
    }

    private fun postFlatData(audio: Audio) {
        waveformData.postValue(getGhostData(audio.duration))
    }

    private fun getGhostData(durationMs: Long): FloatArray {
        val seconds = (durationMs / 1000).toInt().coerceAtLeast(1)
        return FloatArray(seconds) { 0.05f }
    }

    private fun getRandomGhostData(durationMs: Long): FloatArray {
        val expectedBars = ((durationMs / 1000f) * BARS_PER_SECOND).toInt().coerceAtLeast(1)

        return FloatArray(expectedBars) { i ->
            val progress = i.toFloat() / expectedBars
            val envelope = sin(progress * PI).toFloat()
            val noise = (nextFloat() * 0.8f) + 0.2f
            (envelope * noise) * 0.4f
        }
    }

    override fun onCleared() {
        super.onCleared()
        loadJob?.cancel()
        synchronized(amplitudaLock) {
            amplitudaInstance?.release()
            amplitudaInstance = null
        }
    }

    companion object {
        private const val TAG = "WaveformViewModel"

        private const val BARS_PER_SECOND = 1
    }
}
