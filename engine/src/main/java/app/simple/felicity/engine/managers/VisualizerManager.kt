package app.simple.felicity.engine.managers

import app.simple.felicity.engine.managers.VisualizerManager.BAND_COUNT
import app.simple.felicity.engine.managers.VisualizerManager.emit
import app.simple.felicity.engine.managers.VisualizerManager.processor
import app.simple.felicity.engine.managers.VisualizerManager.spectrumFlow
import app.simple.felicity.engine.processors.VisualizerProcessor
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch

/**
 * Singleton bridge that both exposes the live [VisualizerProcessor] reference for
 * direct twin-buffer wiring and relays real-time spectrum data via a [SharedFlow] for
 * any remaining legacy consumers.
 *
 * @author Hamza417
 */
object VisualizerManager {

    /** App-scoped coroutine scope used internally for non-blocking emissions. */
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /** Number of frequency bands emitted per window. */
    const val BAND_COUNT = 40

    /**
     * Live reference to the [VisualizerProcessor] managed by the player service.
     */
    @Volatile
    var processor: VisualizerProcessor? = null
        set(value) {
            val old = field
            field = value
            if (old !== value) {
                // Re-apply both direct-output twin-buffer wiring and any active Milkdrop
                // PCM window callback when the active processor swaps on crossfade promotion.
                val pcmCb = old?.getPcmWindowCallback()
                old?.setPcmWindowCallback(null)
                old?.clearDirectOutput()
                if (pcmCb != null) {
                    value?.setPcmWindowCallback(pcmCb)
                }
                val wiring = directWiring
                if (wiring != null) {
                    val view = wiring.view.get()
                    if (view != null) value?.setDirectOutput(wiring.bufA, wiring.bufB, wiring.isAFront, view)
                }
            }
        }

    private class DirectWiring(
            val bufA: FloatArray,
            val bufB: FloatArray,
            val isAFront: java.util.concurrent.atomic.AtomicBoolean,
            val view: java.lang.ref.WeakReference<android.view.View>
    )

    @Volatile
    private var directWiring: DirectWiring? = null

    /** Connects a visualizer view's twin buffers to whichever processor is (or becomes) active. */
    fun connectDirectOutput(
            bufA: FloatArray,
            bufB: FloatArray,
            isAFront: java.util.concurrent.atomic.AtomicBoolean,
            view: android.view.View
    ) {
        directWiring = DirectWiring(bufA, bufB, isAFront, java.lang.ref.WeakReference(view))
        processor?.setDirectOutput(bufA, bufB, isAFront, view)
    }

    /** Disconnects the visualizer view from the active processor and forgets the wiring. */
    fun disconnectDirectOutput() {
        directWiring = null
        processor?.clearDirectOutput()
    }

    private val _spectrumFlow = MutableSharedFlow<FloatArray>(replay = 1)

    val spectrumFlow: SharedFlow<FloatArray> = _spectrumFlow.asSharedFlow()

    fun emit(bands: FloatArray) {
        scope.launch {
            _spectrumFlow.emit(bands)
        }
    }
}
