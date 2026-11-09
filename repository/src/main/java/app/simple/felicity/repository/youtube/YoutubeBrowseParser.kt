package app.simple.felicity.repository.youtube

import org.json.JSONArray
import org.json.JSONObject

/**
 * Parsers for InnerTube `/browse`, `/search` and `/next` responses: Home (chips, Quick picks, shelves),
 * artist / album / playlist pages, search results in every filter, library listings and radio queues.
 *
 * The JSON is undocumented and shifts; each field is read from the known paths and the walkers fall
 * back to collecting every row renderer they can find, so a layout change degrades to a flat list
 * instead of an empty screen. The renderer names follow ytmusicapi and InterTune's parsers.
 */
object YoutubeBrowseParser {

    // ------------------------------------------------------------------ small helpers

    private fun JSONObject.obj(vararg path: String): JSONObject? {
        var current: JSONObject = this
        for (key in path) current = current.optJSONObject(key) ?: return null
        return current
    }

    private fun JSONObject.arr(vararg path: String): JSONArray? {
        if (path.isEmpty()) return null
        var current: JSONObject = this
        for (i in 0 until path.size - 1) current = current.optJSONObject(path[i]) ?: return null
        return current.optJSONArray(path.last())
    }

    private fun JSONArray?.objects(): List<JSONObject> =
        if (this == null) emptyList() else (0 until length()).mapNotNull { optJSONObject(it) }

    private fun runsText(text: JSONObject?): String {
        val runs = text?.optJSONArray("runs") ?: return text?.optString("simpleText").orEmpty()
        return (0 until runs.length()).joinToString("") { runs.optJSONObject(it)?.optString("text").orEmpty() }
    }

    private fun lastThumbnail(container: JSONObject?): String {
        val thumbs = container?.optJSONArray("thumbnails") ?: return ""
        return thumbs.optJSONObject(thumbs.length() - 1)?.optString("url").orEmpty()
    }

    /** Thumbnail for any renderer that carries one under the usual wrappers. */
    private fun thumbnailOf(renderer: JSONObject): String {
        lastThumbnail(renderer.obj("thumbnailRenderer", "musicThumbnailRenderer", "thumbnail")).takeIf { it.isNotEmpty() }?.let { return it }
        lastThumbnail(renderer.obj("thumbnail", "musicThumbnailRenderer", "thumbnail")).takeIf { it.isNotEmpty() }?.let { return it }
        lastThumbnail(renderer.obj("thumbnail", "croppedSquareThumbnailRenderer", "thumbnail")).takeIf { it.isNotEmpty() }?.let { return it }
        lastThumbnail(renderer.obj("thumbnail")).takeIf { it.isNotEmpty() }?.let { return it }
        return ""
    }

    private fun pageType(endpoint: JSONObject?): String =
        endpoint?.obj("browseEndpoint", "browseEndpointContextSupportedConfigs", "browseEndpointContextMusicConfig")
            ?.optString("pageType").orEmpty()

    private fun findString(element: Any?, key: String): String? {
        when (element) {
            is JSONObject -> {
                element.optString(key).takeIf { it.isNotBlank() }?.let { return it }
                for (k in element.keys()) findString(element.opt(k), key)?.let { return it }
            }
            is JSONArray -> for (i in 0 until element.length()) findString(element.opt(i), key)?.let { return it }
        }
        return null
    }

    private val durationRegex = Regex("""^\d{1,2}:\d{2}(:\d{2})?$""")

    private fun continuationOf(root: JSONObject?): String? {
        val continuations = root?.optJSONArray("continuations") ?: return null
        for (c in continuations.objects()) {
            c.optJSONObject("nextContinuationData")?.optString("continuation")?.takeIf { it.isNotBlank() }?.let { return it }
            c.optJSONObject("reloadContinuationData")?.optString("continuation")?.takeIf { it.isNotBlank() }?.let { return it }
        }
        return null
    }

    // ------------------------------------------------------------------ rows and cards

    /** `musicTwoRowItemRenderer`: a card in a carousel or grid. */
    private fun parseTwoRow(renderer: JSONObject): YoutubeItem? {
        val title = runsText(renderer.optJSONObject("title"))
        if (title.isBlank()) return null
        val subtitle = runsText(renderer.optJSONObject("subtitle"))
        val thumb = thumbnailOf(renderer)
        val nav = renderer.optJSONObject("navigationEndpoint")

        nav?.optJSONObject("watchEndpoint")?.optString("videoId")?.takeIf { it.isNotBlank() }?.let {
            return YoutubeSongItem(it, title, subtitle.substringAfter(" • ", subtitle), thumbnailUrl = thumb)
        }
        nav?.optJSONObject("watchPlaylistEndpoint")?.optString("playlistId")?.takeIf { it.isNotBlank() }?.let {
            return YoutubePlaylistItem(it, title, subtitle, thumb)
        }
        val browseId = nav?.optJSONObject("browseEndpoint")?.optString("browseId").orEmpty()
        if (browseId.isBlank()) return null
        return when {
            browseId.startsWith("MPRE") -> YoutubeAlbumItem(browseId, title, subtitle, thumb)
            browseId.startsWith("UC") || pageType(nav).endsWith("ARTIST") || pageType(nav).endsWith("USER_CHANNEL") ->
                YoutubeArtistItem(browseId, title, subtitle, thumb)
            else -> YoutubePlaylistItem(browseId.removePrefix("VL"), title, subtitle, thumb)
        }
    }

    private fun flexRuns(renderer: JSONObject, index: Int): JSONArray? =
        renderer.optJSONArray("flexColumns")?.optJSONObject(index)
            ?.optJSONObject("musicResponsiveListItemFlexColumnRenderer")?.optJSONObject("text")?.optJSONArray("runs")

    /** `musicResponsiveListItemRenderer`: a row in a list, search result or playlist. */
    private fun parseResponsive(renderer: JSONObject): YoutubeItem? {
        val title = runsText(JSONObject().put("runs", flexRuns(renderer, 0) ?: JSONArray()))
        if (title.isBlank()) return null
        val thumb = thumbnailOf(renderer)

        val videoId = renderer.optJSONObject("playlistItemData")?.optString("videoId")?.takeIf { it.isNotBlank() }
            ?: renderer.obj("overlay", "musicItemThumbnailOverlayRenderer", "content", "musicPlayButtonRenderer", "playNavigationEndpoint", "watchEndpoint")
                ?.optString("videoId")?.takeIf { it.isNotBlank() }

        val secondRuns = flexRuns(renderer, 1)
        val secondText = runsText(JSONObject().put("runs", secondRuns ?: JSONArray()))

        if (videoId != null) {
            val artists = mutableListOf<String>()
            var album = ""
            var duration = ""
            for (run in secondRuns.objects()) {
                val text = run.optString("text")
                val runPage = pageType(run.optJSONObject("navigationEndpoint"))
                when {
                    text.trim() == "•" || text.isBlank() -> Unit
                    durationRegex.matches(text.trim()) -> duration = text.trim()
                    runPage.endsWith("ALBUM") -> album = text
                    runPage.endsWith("ARTIST") || runPage.endsWith("USER_CHANNEL") -> artists += text
                }
            }
            if (artists.isEmpty()) {
                // Rows without typed runs ("Artist • Album • 3:45" as plain text).
                val parts = secondText.split(" • ").map { it.trim() }.filter { it.isNotEmpty() }
                parts.firstOrNull { !durationRegex.matches(it) && it != "Song" && it != "Video" }?.let { artists += it }
                parts.firstOrNull { durationRegex.matches(it) }?.let { if (duration.isEmpty()) duration = it }
            }
            if (duration.isEmpty()) {
                duration = renderer.arr("fixedColumns")?.optJSONObject(0)
                    ?.obj("musicResponsiveListItemFixedColumnRenderer", "text")?.let { runsText(it) }.orEmpty()
            }
            return YoutubeSongItem(videoId, title, artists.joinToString(", "), album, duration, thumb)
        }

        val nav = renderer.optJSONObject("navigationEndpoint")
        val browseId = nav?.optJSONObject("browseEndpoint")?.optString("browseId").orEmpty()
        if (browseId.isBlank()) return null
        val subtitle = secondText.replace(Regex("^(Album|Artist|Playlist|Single|EP) • "), "").ifBlank { secondText }
        return when {
            browseId.startsWith("MPRE") -> YoutubeAlbumItem(browseId, title, subtitle, thumb)
            browseId.startsWith("UC") || pageType(nav).endsWith("ARTIST") -> YoutubeArtistItem(browseId, title, subtitle, thumb)
            else -> YoutubePlaylistItem(browseId.removePrefix("VL"), title, subtitle, thumb)
        }
    }

    /** Every row renderer under [element], in document order. Headers and chips are skipped. */
    private fun collectItems(element: Any?, out: MutableList<YoutubeItem>) {
        when (element) {
            is JSONObject -> {
                element.optJSONObject("musicResponsiveListItemRenderer")?.let { parseResponsive(it)?.let(out::add); return }
                element.optJSONObject("musicTwoRowItemRenderer")?.let { parseTwoRow(it)?.let(out::add); return }
                for (key in element.keys()) {
                    if (key == "header" || key == "chipCloudRenderer" || key == "continuations") continue
                    collectItems(element.opt(key), out)
                }
            }
            is JSONArray -> for (i in 0 until element.length()) collectItems(element.opt(i), out)
        }
    }

    private fun itemsOf(contents: JSONArray?): List<YoutubeItem> {
        val out = mutableListOf<YoutubeItem>()
        collectItems(contents, out)
        return out
    }

    private fun songsOf(items: List<YoutubeItem>) = items.filterIsInstance<YoutubeSongItem>()

    // ------------------------------------------------------------------ layout containers

    private fun singleColumnSections(root: JSONObject): JSONObject? {
        val tabs = root.obj("contents", "singleColumnBrowseResultsRenderer")?.optJSONArray("tabs")
        for (tab in tabs.objects()) {
            tab.obj("tabRenderer", "content", "sectionListRenderer")?.let { return it }
        }
        return null
    }

    private fun twoColumn(root: JSONObject): JSONObject? = root.obj("contents", "twoColumnBrowseResultsRenderer")

    // ------------------------------------------------------------------ home

    private fun shelfFrom(section: JSONObject): YoutubeShelf? {
        val carousel = section.optJSONObject("musicCarouselShelfRenderer")
        if (carousel != null) {
            val header = carousel.obj("header", "musicCarouselShelfBasicHeaderRenderer")
            var title = runsText(header?.optJSONObject("title"))
            val more = header?.obj("moreContentButton", "buttonRenderer", "navigationEndpoint", "browseEndpoint")?.optString("browseId")
            val contents = carousel.optJSONArray("contents")
            val items = itemsOf(contents)
            if (items.isEmpty()) return null
            val listStyle = contents.objects().firstOrNull()?.has("musicResponsiveListItemRenderer") == true
            if (title.isBlank() && listStyle) title = "Quick picks"
            return YoutubeShelf(title, items, isQuickPicks = listStyle && items.all { it is YoutubeSongItem }, moreBrowseId = more?.takeIf { it.isNotBlank() })
        }
        val shelf = section.optJSONObject("musicShelfRenderer") ?: section.optJSONObject("musicPlaylistShelfRenderer")
        if (shelf != null) {
            val items = itemsOf(shelf.optJSONArray("contents"))
            if (items.isEmpty()) return null
            val more = shelf.obj("bottomEndpoint", "browseEndpoint")?.optString("browseId")
            return YoutubeShelf(runsText(shelf.optJSONObject("title")), items, moreBrowseId = more?.takeIf { it.isNotBlank() })
        }
        return null
    }

    private fun chipsOf(sectionList: JSONObject?): List<YoutubeChip> {
        val chips = sectionList?.obj("header", "chipCloudRenderer")?.optJSONArray("chips")
        return chips.objects().mapNotNull { chip ->
            val r = chip.optJSONObject("chipCloudChipRenderer") ?: return@mapNotNull null
            val title = runsText(r.optJSONObject("text"))
            if (title.isBlank()) return@mapNotNull null
            val params = r.obj("navigationEndpoint", "browseEndpoint")?.optString("params")?.takeIf { it.isNotBlank() }
            YoutubeChip(title, params, r.optBoolean("isSelected"))
        }
    }

    fun parseHome(raw: String): YoutubeHomePage {
        val root = JSONObject(raw)
        val sectionList = singleColumnSections(root)
        val shelves = sectionList?.optJSONArray("contents").objects().mapNotNull(::shelfFrom)
        return YoutubeHomePage(chipsOf(sectionList), shelves, continuationOf(sectionList))
    }

    /** The response to a home / shelf continuation. */
    fun parseHomeContinuation(raw: String): YoutubeHomePage {
        val root = JSONObject(raw)
        val cont = root.optJSONObject("continuationContents")?.optJSONObject("sectionListContinuation")
        val shelves = cont?.optJSONArray("contents").objects().mapNotNull(::shelfFrom)
        return YoutubeHomePage(emptyList(), shelves, continuationOf(cont))
    }

    // ------------------------------------------------------------------ search / library listings

    /** Search results (any filter) or a library / history listing, flat, with the next-page token. */
    fun parseItemsPage(raw: String): YoutubeItemsPage {
        val root = JSONObject(raw)
        val items = mutableListOf<YoutubeItem>()
        val cont = root.optJSONObject("continuationContents")
        if (cont != null) {
            val shelfCont = cont.optJSONObject("musicShelfContinuation") ?: cont.optJSONObject("musicPlaylistShelfContinuation")
                ?: cont.optJSONObject("sectionListContinuation") ?: cont.optJSONObject("gridContinuation")
            collectItems(shelfCont?.optJSONArray("contents") ?: shelfCont?.optJSONArray("items"), items)
            return YoutubeItemsPage(items, continuationOf(shelfCont))
        }
        collectItems(root.optJSONObject("contents"), items)
        // The next page hangs off the last shelf of the result list.
        var next: String? = null
        fun scan(e: Any?) {
            when (e) {
                is JSONObject -> {
                    for (key in listOf("musicShelfRenderer", "musicPlaylistShelfRenderer", "gridRenderer")) {
                        e.optJSONObject(key)?.let { shelf -> continuationOf(shelf)?.let { next = it } }
                    }
                    continuationOf(e.optJSONObject("sectionListRenderer"))?.let { if (next == null) next = it }
                    for (k in e.keys()) scan(e.opt(k))
                }
                is JSONArray -> for (i in 0 until e.length()) scan(e.opt(i))
            }
        }
        scan(root.optJSONObject("contents"))
        return YoutubeItemsPage(items, next)
    }

    // ------------------------------------------------------------------ artist / album / playlist

    private fun headerOf(root: JSONObject): JSONObject? {
        val header = root.optJSONObject("header")
        header?.optJSONObject("musicImmersiveHeaderRenderer")?.let { return it }
        header?.optJSONObject("musicVisualHeaderRenderer")?.let { return it }
        header?.optJSONObject("musicDetailHeaderRenderer")?.let { return it }
        header?.optJSONObject("musicEditablePlaylistDetailHeaderRenderer")?.let { editable ->
            return editable.optJSONObject("header")?.optJSONObject("musicResponsiveHeaderRenderer")
                ?: editable.optJSONObject("header")?.optJSONObject("musicDetailHeaderRenderer") ?: editable
        }
        val first = twoColumn(root)?.arr("tabs").objects().firstOrNull()
            ?.obj("tabRenderer", "content", "sectionListRenderer")?.optJSONArray("contents").objects().firstOrNull()
        first?.optJSONObject("musicResponsiveHeaderRenderer")?.let { return it }
        first?.optJSONObject("musicEditablePlaylistDetailHeaderRenderer")?.let { editable ->
            return editable.optJSONObject("header")?.optJSONObject("musicResponsiveHeaderRenderer") ?: editable
        }
        return null
    }

    fun parseDetailPage(raw: String, kind: YoutubeDetailPage.Kind): YoutubeDetailPage {
        val root = JSONObject(raw)
        val header = headerOf(root)

        val title = runsText(header?.optJSONObject("title"))
        var subtitle = runsText(header?.optJSONObject("subtitle"))
        if (subtitle.isBlank()) subtitle = runsText(header?.optJSONObject("straplineTextOne"))
        val description = runsText(header?.optJSONObject("description")?.optJSONObject("musicDescriptionShelfRenderer")?.optJSONObject("description"))
            .ifBlank { runsText(header?.optJSONObject("description")) }
        val thumb = thumbnailOf(header ?: JSONObject()).ifBlank {
            lastThumbnail(header?.obj("foregroundThumbnail", "musicThumbnailRenderer", "thumbnail"))
        }

        // Artist pages: shelves in a single column. Album / playlist pages: tracks in the secondary column.
        val sectionContents: JSONArray? = twoColumn(root)?.obj("secondaryContents", "sectionListRenderer")?.optJSONArray("contents")
            ?: singleColumnSections(root)?.optJSONArray("contents")

        val tracks = mutableListOf<YoutubeSongItem>()
        val shelves = mutableListOf<YoutubeShelf>()
        var continuation: String? = null
        for (section in sectionContents.objects()) {
            val plain = section.optJSONObject("musicPlaylistShelfRenderer") ?: section.optJSONObject("musicShelfRenderer")
            if (plain != null && (tracks.isEmpty() || kind != YoutubeDetailPage.Kind.ARTIST)) {
                val items = itemsOf(plain.optJSONArray("contents"))
                val songs = songsOf(items)
                if (songs.isNotEmpty()) {
                    tracks += songs
                    continuation = continuationOf(plain)
                    continue
                }
            }
            shelfFrom(section)?.let { shelf ->
                if (kind == YoutubeDetailPage.Kind.ARTIST && tracks.isEmpty() && shelf.items.all { it is YoutubeSongItem }) {
                    tracks += shelf.items.filterIsInstance<YoutubeSongItem>()
                } else {
                    shelves += shelf
                }
            }
        }

        // Album rows carry no artwork or artist of their own.
        val artistOfAlbum = subtitle.split(" • ").getOrNull(1)?.trim().orEmpty()
        val filled = tracks.map { t ->
            t.copy(
                thumbnailUrl = t.thumbnailUrl.ifBlank { thumb },
                subtitle = t.subtitle.ifBlank { if (kind == YoutubeDetailPage.Kind.ALBUM) artistOfAlbum.ifBlank { title } else title },
                album = t.album.ifBlank { if (kind == YoutubeDetailPage.Kind.ALBUM) title else "" },
            )
        }

        val radio = header?.obj("startRadioButton", "buttonRenderer", "navigationEndpoint", "watchPlaylistEndpoint")?.optString("playlistId")?.takeIf { it.isNotBlank() }
        val playlistId = findString(header?.optJSONObject("playButton"), "playlistId")
            ?: root.obj("microformat", "microformatDataRenderer")?.optString("urlCanonical")?.substringAfter("list=", "")?.substringBefore("&")?.takeIf { it.isNotBlank() }
        val subscribe = header?.obj("subscriptionButton", "subscribeButtonRenderer")

        return YoutubeDetailPage(
            kind = kind, title = title, subtitle = subtitle, description = description, thumbnailUrl = thumb,
            tracks = filled, shelves = shelves, radioPlaylistId = radio, playlistId = playlistId,
            channelId = subscribe?.optString("channelId")?.takeIf { it.isNotBlank() },
            subscribed = subscribe?.optBoolean("subscribed") == true, continuation = continuation,
        )
    }

    /** Next page of a playlist's tracks. */
    fun parsePlaylistContinuation(raw: String): Pair<List<YoutubeSongItem>, String?> {
        val page = parseItemsPage(raw)
        return songsOf(page.items) to page.continuation
    }

    // ------------------------------------------------------------------ radio (/next)

    /** Tracks of a `/next` response: a song's radio or an artist / album mix. */
    fun parseRadio(raw: String): List<YoutubeSongItem> {
        val root = JSONObject(raw)
        val panel = findObject(root, "playlistPanelRenderer") ?: return emptyList()
        return panel.optJSONArray("contents").objects().mapNotNull { entry ->
            val r = entry.optJSONObject("playlistPanelVideoRenderer")
                ?: entry.optJSONObject("playlistPanelVideoWrapperRenderer")?.obj("primaryRenderer", "playlistPanelVideoRenderer")
                ?: return@mapNotNull null
            val id = r.optString("videoId").takeIf { it.isNotBlank() } ?: return@mapNotNull null
            val artist = runsText(r.optJSONObject("shortBylineText")).ifBlank { runsText(r.optJSONObject("longBylineText")) }
            YoutubeSongItem(
                videoId = id,
                title = runsText(r.optJSONObject("title")),
                subtitle = artist.substringBefore(" • "),
                durationText = runsText(r.optJSONObject("lengthText")),
                thumbnailUrl = lastThumbnail(r.optJSONObject("thumbnail")),
            )
        }
    }

    private fun findObject(element: Any?, key: String): JSONObject? {
        when (element) {
            is JSONObject -> {
                element.optJSONObject(key)?.let { return it }
                for (k in element.keys()) findObject(element.opt(k), key)?.let { return it }
            }
            is JSONArray -> for (i in 0 until element.length()) findObject(element.opt(i), key)?.let { return it }
        }
        return null
    }
}
