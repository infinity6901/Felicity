package app.simple.felicity.repository.youtube

import org.json.JSONArray
import org.json.JSONObject

/**
 * Parses the InnerTube `/search` response (song shelf) into [YoutubeSearchResult]s.
 * The JSON shape here is undocumented and has shifted before — this walks a few
 * known paths per field and falls back to a shallow recursive search for
 * `videoId` if the expected shape isn't found, rather than throwing.
 */
object YoutubeSearchParser {

    private val durationRegex = Regex("""^\d{1,2}:\d{2}(:\d{2})?$""")

    fun parse(rawResponse: String): List<YoutubeSearchResult> {
        val root = JSONObject(rawResponse)
        val results = mutableListOf<YoutubeSearchResult>()

        val tabs = root.optJSONObject("contents")
            ?.optJSONObject("tabbedSearchResultsRenderer")
            ?.optJSONArray("tabs")
            ?: return results

        for (i in 0 until tabs.length()) {
            val sectionContents = tabs.optJSONObject(i)
                ?.optJSONObject("tabRenderer")
                ?.optJSONObject("content")
                ?.optJSONObject("sectionListRenderer")
                ?.optJSONArray("contents")
                ?: continue

            for (j in 0 until sectionContents.length()) {
                val shelfItems = sectionContents.optJSONObject(j)
                    ?.optJSONObject("musicShelfRenderer")
                    ?.optJSONArray("contents")
                    ?: continue

                for (k in 0 until shelfItems.length()) {
                    val item = shelfItems.optJSONObject(k) ?: continue
                    parseItem(item)?.let { results.add(it) }
                }
            }
        }

        return results
    }

    private fun parseItem(item: JSONObject): YoutubeSearchResult? = parseResponsiveItem(item)

    /**
     * Parses a single `musicResponsiveListItemRenderer`-wrapping item into a
     * [YoutubeSearchResult]. Exposed publicly since playlist-contents browsing
     * ([YoutubePlaylistParser]) walks the exact same row shape as search results do.
     */
    fun parseResponsiveItem(item: JSONObject): YoutubeSearchResult? {
        val renderer = item.optJSONObject("musicResponsiveListItemRenderer") ?: return null

        val videoId = findVideoId(renderer) ?: return null
        val title = flexColumnText(renderer, 0)
        val artist = flexColumnText(renderer, 1)
        val duration = findDuration(renderer)
        val thumbnail = findThumbnailUrl(renderer)

        if (title.isBlank()) return null

        return YoutubeSearchResult(
            videoId = videoId,
            title = title,
            artist = artist,
            durationText = duration,
            thumbnailUrl = thumbnail,
        )
    }

    private fun findVideoId(renderer: JSONObject): String? {
        renderer.optJSONObject("playlistItemData")?.optString("videoId")
            ?.takeIf { it.isNotBlank() }
            ?.let { return it }

        renderer.optJSONObject("overlay")
            ?.optJSONObject("musicItemThumbnailOverlayRenderer")
            ?.optJSONObject("content")
            ?.optJSONObject("musicPlayButtonRenderer")
            ?.optJSONObject("playNavigationEndpoint")
            ?.optJSONObject("watchEndpoint")
            ?.optString("videoId")
            ?.takeIf { it.isNotBlank() }
            ?.let { return it }

        renderer.optJSONObject("navigationEndpoint")
            ?.optJSONObject("watchEndpoint")
            ?.optString("videoId")
            ?.takeIf { it.isNotBlank() }
            ?.let { return it }

        // Last resort: shallow recursive scan for any "videoId" key.
        return findAny(renderer, "videoId")
    }

    private fun flexColumnText(renderer: JSONObject, index: Int): String {
        val runs = renderer.optJSONArray("flexColumns")
            ?.optJSONObject(index)
            ?.optJSONObject("musicResponsiveListItemFlexColumnRenderer")
            ?.optJSONObject("text")
            ?.optJSONArray("runs")
            ?: return ""

        return runs.optJSONObject(0)?.optString("text").orEmpty()
    }

    private fun findDuration(renderer: JSONObject): String {
        val fixed = renderer.optJSONArray("fixedColumns")
            ?.optJSONObject(0)
            ?.optJSONObject("musicResponsiveListItemFixedColumnRenderer")
            ?.optJSONObject("text")
            ?.optJSONArray("runs")
            ?.optJSONObject(0)
            ?.optString("text")

        if (!fixed.isNullOrBlank()) return fixed

        val flexColumns = renderer.optJSONArray("flexColumns") ?: return ""
        for (i in 0 until flexColumns.length()) {
            val runs = flexColumns.optJSONObject(i)
                ?.optJSONObject("musicResponsiveListItemFlexColumnRenderer")
                ?.optJSONObject("text")
                ?.optJSONArray("runs")
                ?: continue

            for (r in 0 until runs.length()) {
                val text = runs.optJSONObject(r)?.optString("text").orEmpty()
                if (durationRegex.matches(text)) return text
            }
        }

        return ""
    }

    private fun findThumbnailUrl(renderer: JSONObject): String {
        val thumbnails = renderer.optJSONObject("thumbnail")
            ?.optJSONObject("musicThumbnailRenderer")
            ?.optJSONObject("thumbnail")
            ?.optJSONArray("thumbnails")
            ?: return findAny(renderer, "url").orEmpty()

        return thumbnails.optJSONObject(thumbnails.length() - 1)?.optString("url").orEmpty()
    }

    /** Shallow recursive search for the first string value under [key]. */
    private fun findAny(element: Any?, key: String): String? {
        when (element) {
            is JSONObject -> {
                element.optString(key).takeIf { it.isNotBlank() }?.let { return it }
                for (childKey in element.keys()) {
                    findAny(element.opt(childKey), key)?.let { return it }
                }
            }
            is JSONArray -> {
                for (i in 0 until element.length()) {
                    findAny(element.opt(i), key)?.let { return it }
                }
            }
        }
        return null
    }
}
