package app.simple.felicity.engine.playback

import android.content.Context
import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import android.os.SystemClock
import android.util.Log
import kotlin.math.max
import kotlin.math.sqrt

/**
 * Finds how much of a song's end is silent (the fade-out or empty tail many tracks have).
 *
 * A crossfade that overlaps the last seconds of the file overlaps nothing audible when those seconds
 * are silent, so the two songs seem to follow each other instead of blending. The crossfade is timed
 * against the last audible moment instead of the end of the file.
 *
 * Only the last [SCAN_US] of the track is decoded, with a hard time limit, so this is cheap enough
 * to run once per song just before it is needed.
 */
internal object TrailingSilenceDetector {

    private const val TAG = "TrailingSilence"
    private const val SCAN_US = 20_000_000L
    private const val TIME_LIMIT_MS = 4_000L

    /** RMS below this (about -50 dBFS) counts as silence. */
    private const val SILENCE_RMS = 0.003f
    private const val MIN_TAIL_MS = 400L
    const val MAX_TAIL_MS = 15_000L

    /** Milliseconds of silence at the end of [uri]; 0 when there is none or it cannot be measured. */
    fun detect(context: Context, uri: Uri, durationMs: Long): Long {
        val extractor = MediaExtractor()
        var codec: MediaCodec? = null
        try {
            extractor.setDataSource(context, uri, null)
            var trackIndex = -1
            var format: MediaFormat? = null
            for (i in 0 until extractor.trackCount) {
                val candidate = extractor.getTrackFormat(i)
                if (candidate.getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true) {
                    trackIndex = i
                    format = candidate
                    break
                }
            }
            if (trackIndex < 0 || format == null) return 0L
            extractor.selectTrack(trackIndex)

            val durationUs = if (format.containsKey(MediaFormat.KEY_DURATION)) format.getLong(MediaFormat.KEY_DURATION)
            else durationMs * 1000L
            if (durationUs <= SCAN_US) return 0L
            extractor.seekTo(max(0L, durationUs - SCAN_US), MediaExtractor.SEEK_TO_PREVIOUS_SYNC)

            val mime = format.getString(MediaFormat.KEY_MIME) ?: return 0L
            val decoder = MediaCodec.createDecoderByType(mime)
            codec = decoder
            decoder.configure(format, null, null, 0)
            decoder.start()

            val info = MediaCodec.BufferInfo()
            var inputDone = false
            var outputDone = false
            var encoding = AudioFormat.ENCODING_PCM_16BIT
            var channels = 2
            var sampleRate = 44_100
            var lastAudibleUs = -1L
            var endUs = -1L
            val deadline = SystemClock.elapsedRealtime() + TIME_LIMIT_MS

            while (!outputDone && SystemClock.elapsedRealtime() < deadline) {
                if (!inputDone) {
                    val inIndex = decoder.dequeueInputBuffer(5_000)
                    if (inIndex >= 0) {
                        val buffer = decoder.getInputBuffer(inIndex)
                        val size = if (buffer != null) extractor.readSampleData(buffer, 0) else -1
                        if (size < 0) {
                            decoder.queueInputBuffer(inIndex, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inputDone = true
                        } else {
                            decoder.queueInputBuffer(inIndex, 0, size, extractor.sampleTime, 0)
                            extractor.advance()
                        }
                    }
                }
                val outIndex = decoder.dequeueOutputBuffer(info, 5_000)
                when {
                    outIndex >= 0 -> {
                        val buffer = decoder.getOutputBuffer(outIndex)
                        if (buffer != null && info.size > 0) {
                            buffer.position(info.offset)
                            buffer.limit(info.offset + info.size)
                            val (rms, frames) = measure(buffer, encoding, channels)
                            val bufferEndUs = info.presentationTimeUs + frames * 1_000_000L / sampleRate
                            endUs = bufferEndUs
                            if (rms > SILENCE_RMS) lastAudibleUs = bufferEndUs
                        }
                        decoder.releaseOutputBuffer(outIndex, false)
                        if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) outputDone = true
                    }
                    outIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        val out = decoder.outputFormat
                        encoding = if (out.containsKey(MediaFormat.KEY_PCM_ENCODING)) out.getInteger(MediaFormat.KEY_PCM_ENCODING)
                        else AudioFormat.ENCODING_PCM_16BIT
                        channels = out.getInteger(MediaFormat.KEY_CHANNEL_COUNT).coerceAtLeast(1)
                        sampleRate = out.getInteger(MediaFormat.KEY_SAMPLE_RATE).coerceAtLeast(8_000)
                    }
                }
            }
            if (!outputDone || lastAudibleUs < 0L || endUs < 0L) return 0L
            val tailMs = (endUs - lastAudibleUs) / 1000L
            return if (tailMs < MIN_TAIL_MS) 0L else tailMs.coerceAtMost(MAX_TAIL_MS)
        } catch (e: Exception) {
            Log.w(TAG, "Could not measure trailing silence: ${e.message}")
            return 0L
        } finally {
            runCatching { codec?.stop() }
            runCatching { codec?.release() }
            runCatching { extractor.release() }
        }
    }

    /** RMS (0..1) and frame count of one decoded buffer. */
    private fun measure(buffer: java.nio.ByteBuffer, encoding: Int, channels: Int): Pair<Float, Int> {
        buffer.order(java.nio.ByteOrder.nativeOrder())
        var sum = 0.0
        var count = 0
        if (encoding == AudioFormat.ENCODING_PCM_FLOAT) {
            val floats = buffer.asFloatBuffer()
            count = floats.remaining()
            while (floats.hasRemaining()) {
                val v = floats.get()
                sum += v * v
            }
        } else {
            val shorts = buffer.asShortBuffer()
            count = shorts.remaining()
            while (shorts.hasRemaining()) {
                val v = shorts.get() / 32768f
                sum += v * v
            }
        }
        val rms = if (count > 0) sqrt(sum / count).toFloat() else 0f
        return rms to count / channels
    }
}
