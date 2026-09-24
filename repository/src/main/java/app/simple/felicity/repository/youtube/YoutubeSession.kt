package app.simple.felicity.repository.youtube

/**
 * Holds the state of a "logged in" YouTube session: the raw cookie string captured
 * from a WebView login, plus the visitorData token the InnerTube API hands back and
 * expects on subsequent requests.
 *
 * This is not an OAuth token — it is a mirror of a real browser session's cookies.
 * Treat it like any other credential: store it encrypted, never log it, and let the
 * user clear it (sign out) from settings.
 */
data class YoutubeSession(
    val rawCookies: Map<String, String> = emptyMap(),
    var visitorData: String? = null,
    var dataSyncId: String? = null,
) {
    val isLoggedIn: Boolean
        get() = rawCookies.containsKey("SAPISID") || rawCookies.containsKey("__Secure-3PAPISID")

    fun toRawCookieHeader(): String =
        rawCookies.entries.joinToString("; ") { (key, value) -> "$key=$value" }

    companion object {
        /** Parses the semicolon-delimited cookie string CookieManager hands back. */
        fun fromCookieString(cookieString: String): YoutubeSession {
            val map = cookieString.split(";")
                .mapNotNull { pair ->
                    val idx = pair.indexOf('=')
                    if (idx <= 0) return@mapNotNull null
                    val key = pair.substring(0, idx).trim()
                    val value = pair.substring(idx + 1).trim()
                    if (key.isEmpty()) null else key to value
                }
                .toMap()
            return YoutubeSession(rawCookies = map)
        }
    }
}
