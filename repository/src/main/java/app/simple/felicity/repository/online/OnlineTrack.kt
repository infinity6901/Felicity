package app.simple.felicity.repository.online

/**
 * Which online source an [OnlineTrack] came from.
 *
 * The first four are either public-domain/Creative-Commons content (Jamendo, Internet
 * Archive), free internet radio (Radio Browser), or an openly-licensed streaming API
 * where artists opt in to distribution (Audius) — none of them involve extracting or
 * circumventing access controls on someone else's proprietary catalogue.
 *
 * [YOUTUBE] is the deliberate exception to that rule: it works by calling YouTube's
 * private, undocumented internal API rather than an official, licensed one, which sits
 * outside YouTube's Terms of Service. It's included at the user's explicit request and
 * uses the same general approach as NewPipe/yt-dlp, with the same legal/ToS caveats.
 * See `app.simple.felicity.repository.youtube` for the implementation.
 */
enum class OnlineSource {
    RADIO_BROWSER,
    JAMENDO,
    AUDIUS,
    INTERNET_ARCHIVE,
    YOUTUBE
}

/**
 * A single playable result from one of the online sources, normalized to a shape the
 * player doesn't need to know the origin of.
 *
 * @param streamUrl Directly playable by ExoPlayer (plain HTTP(S) — no special extractor
 *                  needed) for [OnlineSource.RADIO_BROWSER], [OnlineSource.JAMENDO], and
 *                  [OnlineSource.AUDIUS]. For [OnlineSource.INTERNET_ARCHIVE] this is
 *                  `null` until [app.simple.felicity.repository.repositories.InternetArchiveRepository.resolveStreamUrl]
 *                  is called — Archive.org's search API only returns an item identifier,
 *                  not a file URL, so resolving the real audio file is a deliberate,
 *                  separate step done right before playback rather than for every search
 *                  result up front. [OnlineSource.YOUTUBE] works the same way, via
 *                  `YoutubeRepository.resolveStreamUrl` — YouTube's stream URLs also expire,
 *                  so resolving right before playback (rather than caching them) matters here too.
 * @param durationMs Known up front for Jamendo and Audius; unknown (`null`) for Radio
 *                    Browser (a radio station has no fixed duration) and Internet Archive
 *                    (not returned by the search API).
 */
data class OnlineTrack(
        val source: OnlineSource,
        val id: String,
        val title: String,
        val subtitle: String,
        val artworkUrl: String?,
        val streamUrl: String?,
        val durationMs: Long? = null
)
