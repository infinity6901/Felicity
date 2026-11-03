package app.simple.felicity.engine.playback

/**
 * Resolves which output path is safe while two player instances may render concurrently.
 *
 * The current USB DAC, AAudio and Oboe integrations are service/device oriented and are not
 * guaranteed to provide two independent hardware streams. Opening the same exclusive route
 * twice can fail, steal the device from the audible player, or create two competing clocks.
 * Until a backend explicitly advertises independent-session support, dual-player overlap uses
 * Android's normal AudioTrack route for BOTH players. This avoids changing output devices in
 * the middle of a fade and keeps the two players on the same system mixer.
 *
 * This is a policy primitive; the renderer factory must call [resolve] when constructing each
 * player's sink. It deliberately does not open or close hardware itself.
 */
object DualPlayerOutputPolicy {
    enum class RequestedRoute { AUTOMATIC, USB_DAC, AAUDIO, OBOE, ANDROID }
    enum class ResolvedRoute { NATIVE_EXCLUSIVE, ANDROID_SHARED_MIXER }

    data class Capabilities(
        val usbSupportsIndependentSessions: Boolean = false,
        val aaudioSupportsIndependentSessions: Boolean = false,
        val oboeSupportsIndependentSessions: Boolean = false,
    )

    /**
     * During overlap, select only a route that is explicitly known to support independent
     * simultaneous sessions. Otherwise use Android's shared mixer for both players.
     * Outside overlap, retain the user's requested route.
     */
    fun resolve(
        requested: RequestedRoute,
        dualPlaybackOverlap: Boolean,
        capabilities: Capabilities = Capabilities(),
    ): ResolvedRoute {
        if (!dualPlaybackOverlap) {
            return if (requested == RequestedRoute.ANDROID) {
                ResolvedRoute.ANDROID_SHARED_MIXER
            } else {
                ResolvedRoute.NATIVE_EXCLUSIVE
            }
        }

        val independent = when (requested) {
            RequestedRoute.USB_DAC -> capabilities.usbSupportsIndependentSessions
            RequestedRoute.AAUDIO -> capabilities.aaudioSupportsIndependentSessions
            RequestedRoute.OBOE -> capabilities.oboeSupportsIndependentSessions
            RequestedRoute.AUTOMATIC, RequestedRoute.ANDROID -> false
        }
        return if (independent) ResolvedRoute.NATIVE_EXCLUSIVE
        else ResolvedRoute.ANDROID_SHARED_MIXER
    }
}
