package app.simple.felicity.engine.playback

import org.junit.Assert.assertNotSame
import org.junit.Test

class DualAudioProcessorSetTest {
    @Test
    fun createsIndependentManagersAndStatefulProcessors() {
        val set = DualAudioProcessorSet()
        assertNotSame(set.first, set.second)
        assertNotSame(set.first.nativeDspProcessor, set.second.nativeDspProcessor)
        assertNotSame(set.first.visualizerProcessor, set.second.visualizerProcessor)
        assertNotSame(set.first.karaokeProcessor, set.second.karaokeProcessor)
        assertNotSame(set.first.nightModeProcessor, set.second.nightModeProcessor)
    }

    @Test
    fun exposesStableManagerPerSlot() {
        val set = DualAudioProcessorSet()
        assert(set.manager(DualAudioProcessorSet.Slot.FIRST) === set.first)
        assert(set.manager(DualAudioProcessorSet.Slot.SECOND) === set.second)
    }
}
