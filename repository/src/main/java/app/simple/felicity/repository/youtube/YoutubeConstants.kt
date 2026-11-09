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
    const val NEXT_ENDPOINT = "$BASE_URL/next"
    const val LIKE_ENDPOINT = "$BASE_URL/like/like"
    const val REMOVE_LIKE_ENDPOINT = "$BASE_URL/like/removelike"
    const val EDIT_PLAYLIST_ENDPOINT = "$BASE_URL/browse/edit_playlist"
    const val CREATE_PLAYLIST_ENDPOINT = "$BASE_URL/playlist/create"
    const val SUBSCRIBE_ENDPOINT = "$BASE_URL/subscription/subscribe"
    const val UNSUBSCRIBE_ENDPOINT = "$BASE_URL/subscription/unsubscribe"
    const val ACCOUNT_MENU_ENDPOINT = "$BASE_URL/account/account_menu"
    const val VISITOR_DATA_URL = "https://music.youtube.com/sw.js_data"

    /** Search filter `params` values (URL-decoded form is what the API expects in the JSON body). */
    object SearchFilter {
        const val SONGS = "EgWKAQIIAWoKEAkQBRAKEAMQBA%3D%3D"
        const val VIDEOS = "EgWKAQIQAWoKEAkQChAFEAMQBA%3D%3D"
        const val ALBUMS = "EgWKAQIYAWoKEAkQChAFEAMQBA%3D%3D"
        const val ARTISTS = "EgWKAQIgAWoKEAkQChAFEAMQBA%3D%3D"
        const val FEATURED_PLAYLISTS = "EgeKAQQoADgBagwQDhAKEAMQBRAJEAQ%3D"
        const val COMMUNITY_PLAYLISTS = "EgeKAQQoAEABagoQAxAEEAoQCRAF"
    }

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

        /**
         * visionOS app client. Since mid-2026 YouTube answers ANDROID_VR / IOS requests that carry no
         * proof-of-origin token with a stream that is cut off after about 1 MB (HTTP 403, ExoPlayer
         * source error 2004). VISIONOS is exempt, needs no signature deobfuscation and only wants a
         * visitorData. Values follow InterTune's YouTubeClient (GPL-3.0, used under the AGPL-3.0).
         */
        val VISIONOS: JSONObject = JSONObject().apply {
            put("clientName", "VISIONOS")
            put("clientVersion", "1.02")
            put("xClientName", "101")
            put("osName", "visionOS")
            put("osVersion", "2.3.21O5565d")
            put("deviceMake", "Apple")
            put("deviceModel", "RealityDevice17,1")
            put("userAgent", "com.google.ios.youtube/1.02 (RealityDevice17,1; U; visionOS 2_3)")
            put("hl", "en")
            put("gl", "US")
        }

        /** Newer visionOS build, tried when [VISIONOS] is refused. */
        val VISIONOS_1_04: JSONObject = JSONObject().apply {
            put("clientName", "VISIONOS")
            put("clientVersion", "1.04")
            put("xClientName", "101")
            put("osName", "visionOS")
            put("osVersion", "26.6.0.23O770")
            put("deviceMake", "Apple")
            put("deviceModel", "RealityDevice17,1")
            put("userAgent", "com.google.visionos.youtube/1.04(RealityDevice17,1; U; CPU visionOS 26_6_0 like Mac OS X; US)")
            put("hl", "en")
            put("gl", "US")
        }

        /** Order in which clients are tried when resolving a stream. */
        val FALLBACK_ORDER = listOf(VISIONOS, VISIONOS_1_04, IOS_MUSIC, ANDROID_MUSIC, WEB_REMIX)
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
