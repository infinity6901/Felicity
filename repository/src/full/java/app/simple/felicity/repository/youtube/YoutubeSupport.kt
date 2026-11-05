package app.simple.felicity.repository.youtube

/**
 * Whether this build can play and download YouTube tracks. YouTube streams need the bundled
 * yt-dlp runtime to be decoded reliably, so "lite" builds (no yt-dlp) hide every YouTube entry
 * point instead of offering a source that mostly fails.
 */
object YoutubeSupport {
    const val AVAILABLE: Boolean = true
}
