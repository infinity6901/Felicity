package app.simple.felicity.engine.playback

import app.simple.felicity.engine.managers.AudioProcessorManager

/**
 * Owns two completely independent DSP graphs for the two long-lived playback players.
 *
 * AudioProcessor instances (especially NativeDspAudioProcessor and VisualizerProcessor) are
 * stateful and must never be installed in two AudioSinks at once. This holder deliberately
 * creates two managers and exposes them by player slot. It does not itself route audio or
 * promote players; the service must promote the player and its matching processor graph as one
 * transaction.
 */
class DualAudioProcessorSet(
    val first: AudioProcessorManager = AudioProcessorManager(),
    val second: AudioProcessorManager = AudioProcessorManager(),
) {
    enum class Slot { FIRST, SECOND }

    init {
        require(first !== second) { "Each player requires its own AudioProcessorManager" }
        require(first.nativeDspProcessor !== second.nativeDspProcessor) {
            "Native DSP processors must not be shared between players"
        }
        require(first.visualizerProcessor !== second.visualizerProcessor) {
            "Visualizer processors must not be shared between players"
        }
    }

    fun manager(slot: Slot): AudioProcessorManager = when (slot) {
        Slot.FIRST -> first
        Slot.SECOND -> second
    }

    /** Apply current persisted preferences independently to both processor graphs. */
    fun applyCurrentPreferences() {
        first.applyCurrentPreferences()
        second.applyCurrentPreferences()
    }

    /**
     * Re-apply preferences after an audio-mode/decoder rebuild. This does not share processor
     * state, FFT buffers, JNI handles, or AudioProcessor objects between the two graphs.
     */
    fun refreshBoth() = applyCurrentPreferences()
}
