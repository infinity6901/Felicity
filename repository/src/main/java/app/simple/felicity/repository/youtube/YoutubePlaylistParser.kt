package app.simple.felicity.repository.youtube

import org.json.JSONArray
import org.json.JSONObject

/**
 * Parses InnerTube `/browse` responses for:
 *  - The signed-in user's library playlist shelf (`browseId = "FEmusic_liked_playlists"`)
 *  - A single playlist's track list (`browseId = "VL" + playlistId`)
 *
 * NOTE ON RELIABILITY: unlike search/player parsing (which this app can exercise
 * without login), these two browse surfaces require a signed-in session to test
 * properly, and InnerTube's browse JSON shape is the least stable part of the whole
 * private API — this mirrors the shape documented by the ytmusicapi project (the most
 * thorough public reference for this specific endpoint), but treat this file as the
 * most likely place to need a fix if playlists don't show up correctly.
 */
object YoutubePlaylistParser {

    /** Parses the user's own library playlists (requires a signed-in [YoutubeSession]). */
    fun parseLibraryPlaylists(rawResponse: String): List<YoutubePlaylist> {
        val root = JSONObject(rawResponse)
        val results = mutableListOf<YoutubePlaylist>()

        val sectionContents = sectionListContents(root) ?: return results

        for (i in 0 until sectionContents.length()) {
            val section = sectionContents.optJSONObject(i) ?: continue

            val gridItems = section.optJSONObject("gridRenderer")?.optJSONArray("items")
            val shelfItems = section.optJSONObject("musicShelfRenderer")?.optJSONArray("contents")
            val items = gridItems ?: shelfItems ?: continue

            for (j in 0 until items.length()) {
                val item = items.optJSONObject(j) ?: continue
                parsePlaylistCard(item)?.let { results.add(it) }
            }
        }

        return results
    }

    /** Parses the track list of a single playlist. */
    fun parsePlaylistTracks(rawResponse: String): List<YoutubeSearchResult> {
        val root = JSONObject(rawResponse)
        val results = mutableListOf<YoutubeSearchResult>()

        val sectionContents = twoColumnSecondaryContents(root) ?: singleColumnSectionListContents(root)
        ?: return results

        for (i in 0 until sectionContents.length()) {
            val section = sectionContents.optJSONObject(i) ?: continue

            val shelfItems = section.optJSONObject("musicPlaylistShelfRenderer")?.optJSONArray("contents")
                ?: section.optJSONObject("musicShelfRenderer")?.optJSONArray("contents")
                ?: continue

            for (j in 0 until shelfItems.length()) {
                val item = shelfItems.optJSONObject(j) ?: continue
                YoutubeSearchParser.parseResponsiveItem(item)?.let { results.add(it) }
            }
        }

        return results
    }

    private fun singleColumnSectionListContents(root: JSONObject): JSONArray? {
        // The library playlist shelf (FEmusic_liked_playlists) uses this single-column,
        // tabbed layout.
        val tabs = root.optJSONObject("contents")
            ?.optJSONObject("singleColumnBrowseResultsRenderer")
            ?.optJSONArray("tabs")
            ?: return null

        for (i in 0 until tabs.length()) {
            val contents = tabs.optJSONObject(i)
                ?.optJSONObject("tabRenderer")
                ?.optJSONObject("content")
                ?.optJSONObject("sectionListRenderer")
                ?.optJSONArray("contents")
            if (contents != null) return contents
        }

        return null
    }

    /**
     * An individual playlist's own page (`browseId = "VL" + playlistId`) uses a
     * *two*-column layout instead — the header/description sits in the primary column,
     * and the actual track list lives under `secondaryContents`. This is the shape
     * [parsePlaylistTracks] needs; [singleColumnSectionListContents] alone (which is all
     * this parser originally checked) only covers the library shelf, not a playlist's own
     * contents, which is why playlists opened as empty before this fix.
     */
    private fun twoColumnSecondaryContents(root: JSONObject): JSONArray? {
        return root.optJSONObject("contents")
            ?.optJSONObject("twoColumnBrowseResultsRenderer")
            ?.optJSONObject("secondaryContents")
            ?.optJSONObject("sectionListRenderer")
            ?.optJSONArray("contents")
    }

    private fun sectionListContents(root: JSONObject): JSONArray? = singleColumnSectionListContents(root)

    private fun parsePlaylistCard(item: JSONObject): YoutubePlaylist? {
        val renderer = item.optJSONObject("musicTwoRowItemRenderer") ?: return null

        val browseId = renderer.optJSONObject("navigationEndpoint")
            ?.optJSONObject("browseEndpoint")
            ?.optString("browseId")
            ?.takeIf { it.isNotBlank() }
            ?: return null

        val title = renderer.optJSONObject("title")
            ?.optJSONArray("runs")
            ?.optJSONObject(0)
            ?.optString("text")
            .orEmpty()

        if (title.isBlank()) return null

        val subtitle = renderer.optJSONObject("subtitle")
            ?.optJSONArray("runs")
            ?.let { runs -> (0 until runs.length()).joinToString("") { runs.optJSONObject(it)?.optString("text").orEmpty() } }
            .orEmpty()

        val thumbnails = renderer.optJSONObject("thumbnailRenderer")
            ?.optJSONObject("musicThumbnailRenderer")
            ?.optJSONObject("thumbnail")
            ?.optJSONArray("thumbnails")

        val thumbnailUrl = thumbnails?.optJSONObject(thumbnails.length() - 1)?.optString("url").orEmpty()

        return YoutubePlaylist(
            playlistId = browseId,
            title = title,
            subtitle = subtitle,
            thumbnailUrl = thumbnailUrl,
        )
    }
}
