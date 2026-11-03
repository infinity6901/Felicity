package app.simple.felicity.engine.playback

import org.junit.Assert.assertEquals
import org.junit.Test

class DualPlayerOutputPolicyTest {
    @Test fun exclusiveRoutesFallBackToSharedMixerDuringOverlapByDefault() {
        listOf(
            DualPlayerOutputPolicy.RequestedRoute.AUTOMATIC,
            DualPlayerOutputPolicy.RequestedRoute.USB_DAC,
            DualPlayerOutputPolicy.RequestedRoute.AAUDIO,
            DualPlayerOutputPolicy.RequestedRoute.OBOE,
        ).forEach { route ->
            assertEquals(
                DualPlayerOutputPolicy.ResolvedRoute.ANDROID_SHARED_MIXER,
                DualPlayerOutputPolicy.resolve(route, dualPlaybackOverlap = true),
            )
        }
    }

    @Test fun explicitIndependentNativeCapabilityAllowsNativeRouteDuringOverlap() {
        val capabilities = DualPlayerOutputPolicy.Capabilities(
            usbSupportsIndependentSessions = true,
            aaudioSupportsIndependentSessions = true,
            oboeSupportsIndependentSessions = true,
        )
        listOf(
            DualPlayerOutputPolicy.RequestedRoute.USB_DAC,
            DualPlayerOutputPolicy.RequestedRoute.AAUDIO,
            DualPlayerOutputPolicy.RequestedRoute.OBOE,
        ).forEach { route ->
            assertEquals(
                DualPlayerOutputPolicy.ResolvedRoute.NATIVE_EXCLUSIVE,
                DualPlayerOutputPolicy.resolve(route, dualPlaybackOverlap = true, capabilities),
            )
        }
    }

    @Test fun outsideOverlapPreservesRequestedRoute() {
        assertEquals(
            DualPlayerOutputPolicy.ResolvedRoute.NATIVE_EXCLUSIVE,
            DualPlayerOutputPolicy.resolve(
                DualPlayerOutputPolicy.RequestedRoute.USB_DAC,
                dualPlaybackOverlap = false,
            ),
        )
        assertEquals(
            DualPlayerOutputPolicy.ResolvedRoute.ANDROID_SHARED_MIXER,
            DualPlayerOutputPolicy.resolve(
                DualPlayerOutputPolicy.RequestedRoute.ANDROID,
                dualPlaybackOverlap = false,
            ),
        )
    }
}
