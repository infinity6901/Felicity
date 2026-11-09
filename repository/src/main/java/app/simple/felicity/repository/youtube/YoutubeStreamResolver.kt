package app.simple.felicity.repository.youtube

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.json.JSONObject

/**
 * Resolves a direct, playable audio URL for a YouTube video ID via the InnerTube
 * `player` endpoint, ported from Umihi Music's stream-resolution logic.
 *
 * These URLs are short-lived (they expire, typically within hours) and are tied to
 * the requesting IP/session in various ways — resolve on demand right before
 * playback or download, don't cache them long-term.
 */
object YoutubeStreamResolver {

    data class ResolvedStream(
        val url: String,
        val mimeType: String,
        val bitrate: Int,
        val approxDurationMs: Long?,
        /** `videostatsPlaybackUrl` from the player response, used to register the play in the account's history. */
        val playbackTrackingUrl: String? = null,
    )

    suspend fun resolve(
        videoId: String,
        session: YoutubeSession?,
        retries: Int = YoutubeConstants.RETRY_COUNT,
    ): ResolvedStream = withContext(Dispatchers.IO) {
        var lastError: Throwable? = null

        repeat(retries) { attempt ->
            try {
                return@withContext resolveOnce(videoId, session)
            } catch (e: Throwable) {
                lastError = e
                if (attempt < retries - 1) {
                    delay(YoutubeConstants.RETRY_DELAY_MS * (attempt + 1))
                }
            }
        }

        throw Exception("Could not resolve stream for $videoId after $retries attempts", lastError)
    }

    private fun resolveOnce(videoId: String, session: YoutubeSession?): ResolvedStream {
        // VISIONOS / iOS player calls need a visitorData; fetch an anonymous one when the session has none.
        if (session != null && session.visitorData.isNullOrBlank()) {
            YoutubeApiClient.fetchVisitorData()?.let { session.visitorData = it }
        }
        for (clientFingerprint in YoutubeConstants.Client.FALLBACK_ORDER) {
            var hardTries = 0
            var softTries = 0

            while (hardTries < YoutubeConstants.HARD_TRIES_PER_CLIENT &&
                softTries < YoutubeConstants.SOFT_TRIES_PER_CLIENT
            ) {
                val previousVisitorData = session?.visitorData

                val result = runCatching {
                    val raw = YoutubeApiClient.getPlayerInfo(videoId, session, clientFingerprint)
                    extractStream(raw, session, clientFingerprint.optString("userAgent").ifBlank { YoutubeConstants.STREAMING_USER_AGENT })
                }

                result.getOrNull()?.let { return it }

                hardTries++
                val visitorDataChanged = session?.visitorData != previousVisitorData
                if (!visitorDataChanged) {
                    softTries++
                }
            }
        }

        throw Exception("No client fingerprint could resolve a stream for $videoId")
    }

    private fun extractStream(rawResponse: String, session: YoutubeSession?, userAgent: String): ResolvedStream {
        val root = JSONObject(rawResponse)

        root.optJSONObject("responseContext")?.optString("visitorData")
            ?.takeIf { it.isNotBlank() }
            ?.let { session?.visitorData = it }

        val playability = root.optJSONObject("playabilityStatus")
        val status = playability?.optString("status")
        val reason = playability?.optString("reason")

        val adaptiveFormats = root.optJSONObject("streamingData")
            ?.optJSONArray("adaptiveFormats")
            ?: throw Exception("($status) $reason")

        var best: JSONObject? = null
        var bestBitrate = -1

        for (i in 0 until adaptiveFormats.length()) {
            val format = adaptiveFormats.optJSONObject(i) ?: continue
            val url = format.optString("url")
            val mimeType = format.optString("mimeType")

            if (url.isBlank() || !mimeType.startsWith("audio/", ignoreCase = true)) continue

            val bitrate = format.optInt("bitrate", 0)
            if (bitrate > bestBitrate) {
                bestBitrate = bitrate
                best = format
            }
        }

        val chosen = best ?: throw Exception("($status) $reason — no audio formats returned")
        val url = chosen.getString("url")

        if (!YoutubeApiClient.headCheck(url, userAgent)) {
            throw Exception("($status) Resolved URL failed the byte-range check")
        }
        YoutubeStreamAgents.remember(url, userAgent)

        return ResolvedStream(
            url = url,
            mimeType = chosen.optString("mimeType"),
            bitrate = chosen.optInt("bitrate", 0),
            approxDurationMs = chosen.optString("approxDurationMs").toLongOrNull(),
            playbackTrackingUrl = root.optJSONObject("playbackTracking")
                ?.optJSONObject("videostatsPlaybackUrl")?.optString("baseUrl")?.takeIf { it.isNotBlank() },
        )
    }
}
