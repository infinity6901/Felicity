package app.simple.felicity.engine.audio.crossfade

import android.content.Context
import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.coroutines.coroutineContext

/**
 * Decodes the first few seconds of a track to float PCM so [CrossfadeStage] can mix it into the
 * outgoing stream. Uses the platform MediaExtractor/MediaCodec, not ExoPlayer, so it needs no
 * sink and runs faster than real time.
 *
 * Returns null whenever the track can't be used for a DSP crossfade (unsupported codec, decode
 * error, or a sample rate / channel count that differs from the stream that is playing). The
 * caller then falls back to the classic crossfade, so null is never an error the user sees.
 */
object IncomingDecoder {

    private const val TAG = "IncomingDecoder"
    private const val TIMEOUT_US = 10_000L

    class Result(val samples: FloatArray, val frames: Int)

    /**
     * @param frames how many frames to produce at [sampleRate].
     * The decoded track must already be at [sampleRate]/[channelCount]; no resampling is done
     * because the track after it would then need a different sink configuration anyway.
     */
    suspend fun decodeHead(
            context: Context,
            uri: Uri,
            sampleRate: Int,
            channelCount: Int,
            frames: Int
    ): Result? = withContext(Dispatchers.IO) {
        val extractor = MediaExtractor()
        var codec: MediaCodec? = null
        try {
            extractor.setDataSource(context, uri, null)
            val track = (0 until extractor.trackCount).firstOrNull {
                extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true
            } ?: return@withContext null

            val format = extractor.getTrackFormat(track)
            val mime = format.getString(MediaFormat.KEY_MIME) ?: return@withContext null
            val srcRate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            val srcCh = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
            if (srcRate != sampleRate || srcCh != channelCount) {
                Log.d(TAG, "Layout mismatch ($srcRate Hz/$srcCh ch vs $sampleRate Hz/$channelCount ch), skipping")
                return@withContext null
            }

            extractor.selectTrack(track)
            // Ask for float output when the platform supports it; otherwise 16-bit comes back.
            format.setInteger(MediaFormat.KEY_PCM_ENCODING, AudioFormat.ENCODING_PCM_FLOAT)

            codec = MediaCodec.createDecoderByType(mime)
            codec.configure(format, null, null, 0)
            codec.start()

            val out = FloatArray(frames * channelCount)
            var written = 0
            val info = MediaCodec.BufferInfo()
            var inputDone = false
            var outputDone = false
            var pcmEncoding = AudioFormat.ENCODING_PCM_16BIT
            val deadline = System.nanoTime() + 8_000_000_000L

            while (!outputDone && written < out.size) {
                if (!coroutineContext.isActive || System.nanoTime() > deadline) return@withContext null

                if (!inputDone) {
                    val inIdx = codec.dequeueInputBuffer(TIMEOUT_US)
                    if (inIdx >= 0) {
                        val inBuf = codec.getInputBuffer(inIdx)!!
                        val size = extractor.readSampleData(inBuf, 0)
                        if (size < 0) {
                            codec.queueInputBuffer(inIdx, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inputDone = true
                        } else {
                            codec.queueInputBuffer(inIdx, 0, size, extractor.sampleTime, 0)
                            extractor.advance()
                        }
                    }
                }

                val outIdx = codec.dequeueOutputBuffer(info, TIMEOUT_US)
                when {
                    outIdx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        val f = codec.outputFormat
                        if (f.containsKey(MediaFormat.KEY_PCM_ENCODING)) {
                            pcmEncoding = f.getInteger(MediaFormat.KEY_PCM_ENCODING)
                        }
                        // The real output layout is only known now; a mismatch is a hard stop.
                        if (f.getInteger(MediaFormat.KEY_SAMPLE_RATE) != sampleRate ||
                                f.getInteger(MediaFormat.KEY_CHANNEL_COUNT) != channelCount) {
                            return@withContext null
                        }
                    }
                    outIdx >= 0 -> {
                        val buf = codec.getOutputBuffer(outIdx)
                        if (buf != null && info.size > 0) {
                            buf.position(info.offset)
                            buf.limit(info.offset + info.size)
                            buf.order(ByteOrder.nativeOrder())
                            written += appendAsFloat(buf, pcmEncoding, out, written)
                        }
                        if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) outputDone = true
                        codec.releaseOutputBuffer(outIdx, false)
                    }
                }
            }

            val gotFrames = written / channelCount
            // A track shorter than the fade can't be crossfaded in; let the caller decide.
            if (gotFrames <= 0) null else Result(out, gotFrames)
        } catch (e: Throwable) {
            Log.w(TAG, "Could not decode head of $uri: ${e.message}")
            null
        } finally {
            try {
                codec?.stop()
            } catch (_: Throwable) {
            }
            try {
                codec?.release()
            } catch (_: Throwable) {
            }
            extractor.release()
        }
    }

    /** Converts [buf] (16-bit, 32-bit int or float PCM) into [out] starting at [at]; returns samples added. */
    private fun appendAsFloat(buf: ByteBuffer, encoding: Int, out: FloatArray, at: Int): Int {
        var n = 0
        val room = out.size - at
        when (encoding) {
            AudioFormat.ENCODING_PCM_FLOAT -> {
                val count = minOf(buf.remaining() / 4, room)
                for (i in 0 until count) out[at + i] = buf.getFloat()
                n = count
            }
            AudioFormat.ENCODING_PCM_32BIT -> {
                val count = minOf(buf.remaining() / 4, room)
                for (i in 0 until count) out[at + i] = (buf.getInt() / 2147483648.0).toFloat()
                n = count
            }
            else -> {
                val count = minOf(buf.remaining() / 2, room)
                for (i in 0 until count) out[at + i] = buf.getShort() / 32768f
                n = count
            }
        }
        return n
    }
}
