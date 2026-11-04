package app.simple.felicity.engine.playback

import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Assume.assumeNoException
import org.junit.Test

class DualAudioProcessorSetTest {

    /**
     * The processors load the native felicity_audio_engine library, which does not exist in a
     * plain JVM unit test. Skip (not fail) there; the check runs wherever the library is loadable.
     */
    private fun newSet(): DualAudioProcessorSet? = try {
        DualAudioProcessorSet()
    } catch (e: LinkageError) {
        assumeNoException(e)
        null
    }

    @Test
    fun createsIndependentManagersAndStatefulProcessors() {
        val set = newSet() ?: return
        assertNotSame(set.first, set.second)
        assertNotSame(set.first.nativeDspProcessor, set.second.nativeDspProcessor)
        assertNotSame(set.first.visualizerProcessor, set.second.visualizerProcessor)
        assertNotSame(set.first.karaokeProcessor, set.second.karaokeProcessor)
        assertNotSame(set.first.nightModeProcessor, set.second.nightModeProcessor)
    }

    @Test
    fun exposesStableManagerPerSlot() {
        val set = newSet() ?: return
        assertSame(set.first, set.manager(DualAudioProcessorSet.Slot.FIRST))
        assertSame(set.second, set.manager(DualAudioProcessorSet.Slot.SECOND))
    }
}
