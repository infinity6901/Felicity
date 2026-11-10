package app.simple.felicity.engine.managers

import app.simple.felicity.engine.processors.NativeDspAudioProcessor
import app.simple.felicity.preferences.EqualizerPreferences
import app.simple.felicity.shared.models.ParsedBand
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.Locale

/**
 * Singleton manager for the Felicity 10-band graphic and parametric equalizer.
 *
 * Updates both the active and standby [NativeDspAudioProcessor] instances so live
 * EQ adjustments are preserved across dual-player crossfade promotions.
 *
 * @author Hamza417
 */
object EqualizerManager {

    private var processor: NativeDspAudioProcessor? = null
    private var standbyProcessor: NativeDspAudioProcessor? = null

    private val _bandGainsFlow = MutableStateFlow(EqualizerPreferences.getAllBandGains())
    val bandGainsFlow: StateFlow<FloatArray> = _bandGainsFlow.asStateFlow()

    private val _preampFlow = MutableStateFlow(EqualizerPreferences.getPreampDb())
    val preampFlow: StateFlow<Float> = _preampFlow.asStateFlow()

    private val _peqBandsFlow = MutableStateFlow(loadPeqBandsFromPreferences())
    val peqBandsFlow: StateFlow<List<ParsedBand>> = _peqBandsFlow.asStateFlow()

    private val _eqModeFlow = MutableStateFlow(EqualizerPreferences.getEqMode())
    val eqModeFlow: StateFlow<String> = _eqModeFlow.asStateFlow()

    /**
     * Registers the active (and optional standby) [NativeDspAudioProcessor] and applies
     * persisted EQ/PEQ bands, preamp, and enabled state without clobbering Parametric EQ mode.
     */
    fun attachProcessor(
            nativeDspProcessor: NativeDspAudioProcessor,
            standbyDspProcessor: NativeDspAudioProcessor? = this.standbyProcessor
    ) {
        processor = nativeDspProcessor
        standbyProcessor = standbyDspProcessor

        val savedGains = EqualizerPreferences.getAllBandGains()
        val savedPreamp = EqualizerPreferences.getPreampDb()
        val eqEnabled = EqualizerPreferences.isEqEnabled()
        val peqBands = loadPeqBandsFromPreferences()
        val isPeq = EqualizerPreferences.isParametricEqMode() && peqBands.isNotEmpty()

        fun syncProcessor(dsp: NativeDspAudioProcessor) {
            if (isPeq) {
                val gains = FloatArray(peqBands.size) { peqBands[it].gain }
                val qValues = FloatArray(peqBands.size) { peqBands[it].q }
                val frequencies = FloatArray(peqBands.size) { peqBands[it].frequencyHz }
                val types = IntArray(peqBands.size) { peqBands[it].type }
                dsp.setPeqBands(gains, frequencies, qValues, types)
            } else {
                dsp.setEqBands(savedGains)
            }
            dsp.eqEnabled = eqEnabled
            dsp.setPreamp(savedPreamp)
        }

        syncProcessor(nativeDspProcessor)
        standbyDspProcessor?.let { if (it !== nativeDspProcessor) syncProcessor(it) }

        _bandGainsFlow.value = savedGains
        _preampFlow.value = savedPreamp
        _peqBandsFlow.value = peqBands
        _eqModeFlow.value = EqualizerPreferences.getEqMode()
    }

    fun detachProcessor() {
        processor = null
        standbyProcessor = null
    }

    fun setBandGain(band: Int, gainDb: Float, persist: Boolean = true) {
        if (band !in 0..9) return
        val clamped = gainDb.coerceIn(-15f, 15f)

        if (persist) {
            EqualizerPreferences.setBandGain(band, clamped)
        }

        processor?.setBandGain(band, clamped)
        standbyProcessor?.setBandGain(band, clamped)

        val updated = _bandGainsFlow.value.copyOf()
        updated[band] = clamped
        _bandGainsFlow.value = updated
    }

    fun setAllBands(gains: FloatArray, persist: Boolean = true) {
        if (gains.size < 10) return
        val clamped = FloatArray(10) { gains[it].coerceIn(-15f, 15f) }
        if (persist) {
            EqualizerPreferences.setAllBandGains(clamped)
        }
        processor?.setEqBands(clamped)
        standbyProcessor?.setEqBands(clamped)
        _bandGainsFlow.value = clamped
    }

    fun applyBandFromPreference(band: Int) {
        if (band !in 0..9) return
        val gainDb = EqualizerPreferences.getBandGain(band)
        setBandGain(band, gainDb, persist = false)
    }

    fun getBandGain(band: Int): Float {
        if (band !in 0..9) return 0f
        return _bandGainsFlow.value[band]
    }

    fun getAllGains(): FloatArray = _bandGainsFlow.value.copyOf()

    fun resetAllBands() {
        val flat = FloatArray(10)
        EqualizerPreferences.setAllBandGains(flat)
        processor?.resetEqBands()
        standbyProcessor?.resetEqBands()
        _bandGainsFlow.value = flat
    }

    fun resetPeqGains() {
        val resetBand = ParsedBand(gain = 0f, q = 1.0f, frequencyHz = 1000f, color = 0)
        setPeqBands(listOf(resetBand))
    }

    fun setEnabled(enabled: Boolean) {
        EqualizerPreferences.setEqEnabled(enabled)
        processor?.eqEnabled = enabled
        standbyProcessor?.eqEnabled = enabled
    }

    fun isEnabled(): Boolean = EqualizerPreferences.isEqEnabled()

    fun setPreamp(db: Float, persist: Boolean = true) {
        val clamped = db.coerceIn(-15f, 15f)
        if (persist) {
            EqualizerPreferences.setPreampDb(clamped)
        }
        processor?.setPreamp(clamped)
        standbyProcessor?.setPreamp(clamped)
        _preampFlow.value = clamped
    }

    fun applyPreampFromPreference() {
        val db = EqualizerPreferences.getPreampDb()
        setPreamp(db, persist = false)
    }

    fun getPreamp(): Float = _preampFlow.value

    fun setEqMode(mode: String) {
        EqualizerPreferences.setEqMode(mode)
        _eqModeFlow.value = mode
    }

    fun setPeqBands(bands: List<ParsedBand>, persist: Boolean = true) {
        if (persist) {
            EqualizerPreferences.setPeqBandsRaw(serializePeqBands(bands))
        }
        val gains = FloatArray(bands.size) { bands[it].gain }
        val qValues = FloatArray(bands.size) { bands[it].q }
        val frequencies = FloatArray(bands.size) { bands[it].frequencyHz }
        val types = IntArray(bands.size) { bands[it].type }

        processor?.setPeqBands(gains, frequencies, qValues, types)
        standbyProcessor?.setPeqBands(gains, frequencies, qValues, types)
        _peqBandsFlow.value = bands
    }

    fun applyPeqBandsFromPreference() {
        val bands = loadPeqBandsFromPreferences()
        setPeqBands(bands, persist = false)
    }

    fun getPeqBands(): List<ParsedBand> = _peqBandsFlow.value

    fun serializePeqBands(bands: List<ParsedBand>): String? {
        if (bands.isEmpty()) return null
        return bands.joinToString("|") { band ->
            "%.2f:%.2f:%.2f:%d:%d".format(Locale.US, band.gain, band.q, band.frequencyHz, band.color, band.type)
        }
    }

    private fun loadPeqBandsFromPreferences(): List<ParsedBand> {
        val raw = EqualizerPreferences.getPeqBandsRaw() ?: return emptyList()
        return raw.split("|").mapNotNull { segment ->
            val parts = segment.split(":")
            if (parts.size < 3) return@mapNotNull null
            val gain = parts[0].toFloatOrNull() ?: return@mapNotNull null
            val q = parts[1].toFloatOrNull() ?: return@mapNotNull null
            val freq = parts[2].toFloatOrNull() ?: return@mapNotNull null
            val color = parts.getOrNull(3)?.toIntOrNull() ?: 0
            val type = parts.getOrNull(4)?.toIntOrNull() ?: 0
            ParsedBand(gain, q, freq, color, type)
        }
    }
}
