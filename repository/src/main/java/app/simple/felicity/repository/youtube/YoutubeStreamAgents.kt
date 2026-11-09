package app.simple.felicity.repository.youtube

/**
 * Remembers which User-Agent minted each stream URL. googlevideo ties a URL to the client that asked for
 * it, so playback must present the same one: the resolver records it here and the player's data source
 * looks it up per request. URLs nobody registered (yt-dlp's) use [YoutubeConstants.STREAMING_USER_AGENT].
 */
object YoutubeStreamAgents {

    private const val MAX_ENTRIES = 96

    private val agents = object : LinkedHashMap<String, String>(MAX_ENTRIES, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, String>?) = size > MAX_ENTRIES
    }

    @Synchronized
    fun remember(url: String, userAgent: String) {
        agents[url] = userAgent
    }

    @Synchronized
    fun userAgentFor(url: String): String = agents[url] ?: YoutubeConstants.STREAMING_USER_AGENT
}
