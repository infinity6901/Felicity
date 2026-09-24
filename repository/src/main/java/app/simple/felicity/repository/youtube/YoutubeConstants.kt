package app.simple.felicity.repository.youtube

import org.json.JSONObject

/**
 * Endpoint and client-fingerprint constants for talking to YouTube's private,
 * undocumented "InnerTube" API.
 *
 * IMPORTANT: This is not YouTube's public Data API. It is a reverse-engineered
 * interface used internally by YouTube's own web/mobile clients. Using it:
 *  - Is against YouTube's Terms of Service.
 *  - Can break at any time without notice when YouTube changes its clients.
 *  - Is the same general approach used by open-source projects such as
 *    NewPipe and yt-dlp/ytmusicapi. Ported here from Umihi Music for the
 *    same purpose (personal streaming/downloading), with the same caveats.
 */
object YoutubeConstants {

    const val BASE_URL = "https://music.youtube.com/youtubei/v1"
    const val PLAYER_ENDPOINT = "$BASE_URL/player"
    const val SEARCH_ENDPOINT = "$BASE_URL/search"
    const val BROWSE_ENDPOINT = "$BASE_URL/browse"

    // Public InnerTube key used by the official web client. Widely known/published;
    // not a secret credential, just an API routing key.
    const val INNERTUBE_API_KEY = "AIzaSyC9XL3ZjWddXya6X74dJoCTL-WEYFDNX30"

    object Client {

        val WEB_REMIX: JSONObject = JSONObject().apply {
            put("clientName", "WEB_REMIX")
            put("clientVersion", "1.20241127.01.00")
            put("xClientName", "67")
            put(
                "userAgent",
                "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
                        "(KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"
            )
            put("hl", "en")
            put("gl", "US")
        }

        val ANDROID_MUSIC: JSONObject = JSONObject().apply {
            put("clientName", "ANDROID_MUSIC")
            put("clientVersion", "7.16.51")
            put("xClientName", "21")
            put("androidSdkVersion", 30)
            put(
                "userAgent",
                "com.google.android.apps.youtube.music/7.16.51 (Linux; U; Android 11) gzip"
            )
            put("hl", "en")
            put("gl", "US")
        }

        val IOS_MUSIC: JSONObject = JSONObject().apply {
            put("clientName", "IOS_MUSIC")
            put("clientVersion", "7.16.5")
            put("xClientName", "26")
            put("deviceModel", "iPhone16,2")
            put(
                "userAgent",
                "com.google.ios.youtubemusic/7.16.5 (iPhone16,2; U; CPU iOS 17_5 like Mac OS X)"
            )
            put("hl", "en")
            put("gl", "US")
        }

        /** Order in which clients are tried when resolving a stream. */
        val FALLBACK_ORDER = listOf(ANDROID_MUSIC, IOS_MUSIC, WEB_REMIX)
    }

    const val RETRY_COUNT = 3
    const val RETRY_DELAY_MS = 500L
    const val SOFT_TRIES_PER_CLIENT = 2
    const val HARD_TRIES_PER_CLIENT = 4

    /**
     * The single source of truth for the User-Agent used anywhere a YouTube/googlevideo
     * stream URL gets touched: the yt-dlp resolve/download requests in `YtDlpManager`
     * (`--user-agent`) AND the actual ExoPlayer HTTP playback request in
     * `FelicityPlayerService.buildStreamingMediaSourceFactory()`.
     *
     * googlevideo.com URLs are bound to the User-Agent used when they were minted — a
     * mismatch between the resolving UA and the playing UA is rejected with an HTTP error
     * (surfaces as ExoPlayer's ERROR_CODE_IO_BAD_HTTP_STATUS), regardless of how plausible
     * either individual UA string looks. Previously these lived as two independent
     * hardcoded literals in two different modules — this constant is what they should both
     * reference instead, so they can't drift apart again.
     */
    const val STREAMING_USER_AGENT =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"
}
