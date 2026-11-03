package app.simple.felicity.engine.playback

import android.content.Context
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.audio.AudioCapabilities
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.DefaultAudioSink
import app.simple.felicity.engine.managers.AudioProcessorManager
import app.simple.felicity.preferences.AudioPreferences

/**
 * Renderer factory for the second long-lived player during dual-player overlap.
 *
 * This factory deliberately uses Media3's DefaultAudioSink rather than FelicityAudioSink.
 * FelicityAudioSink currently owns service/device-oriented USB, AAudio and Oboe routes that
 * are not safe to open twice. Both players therefore use Android's shared AudioTrack mixer
 * during overlap. The standby still has a completely independent DSP graph.
 *
 * Do not install this manager's processors in the primary player's sink (or vice versa).
 */
@UnstableApi
class DualPlayerStandbyRenderersFactory(
    context: Context,
    private val processorManager: AudioProcessorManager,
) : DefaultRenderersFactory(context) {

    override fun buildAudioSink(
        context: Context,
        enableFloatOutput: Boolean,
        enableOffload: Boolean,
    ): AudioSink {
        processorManager.applyCurrentPreferences()
        val processors = buildList<AudioProcessor> {
            if (AudioPreferences.isSkipSilenceEnabled()) {
                add(processorManager.silenceTrimmingProcessor)
            }
            if (AudioPreferences.isStereoDownmixForced()) {
                add(processorManager.downmixProcessor)
            }
            add(processorManager.karaokeProcessor)
            add(processorManager.nativeDspProcessor)
            add(processorManager.nightModeProcessor)
            add(processorManager.visualizerProcessor)
        }

        // Float output preference is shared with the primary player. Offload is intentionally
        // not enabled here: overlapping PCM playback must remain under software volume control.
        return DefaultAudioSink.Builder(context)
            .setEnableFloatOutput(AudioPreferences.isHiresOutputEnabled())
            .setAudioCapabilities(AudioCapabilities.getCapabilities(context))
            .setAudioProcessors(processors.toTypedArray())
            .build()
            .also { it.setOffloadMode(DefaultAudioSink.OFFLOAD_MODE_DISABLED) }
    }
}
