package app.simple.felicity.repository.youtube

/** Anything a YouTube Music shelf, search result or library listing can contain. */
sealed interface YoutubeItem {
    val title: String
    val subtitle: String
    val thumbnailUrl: String
}

data class YoutubeSongItem(
        val videoId: String,
        override val title: String,
        /** Artist(s). */
        override val subtitle: String,
        val album: String = "",
        val durationText: String = "",
        override val thumbnailUrl: String = "",
) : YoutubeItem

data class YoutubeAlbumItem(
        val browseId: String,
        override val title: String,
        override val subtitle: String,
        override val thumbnailUrl: String = "",
) : YoutubeItem

data class YoutubeArtistItem(
        val browseId: String,
        override val title: String,
        override val subtitle: String,
        override val thumbnailUrl: String = "",
) : YoutubeItem

/** A playlist or a generated mix; [playlistId] is the bare id (no `VL` prefix). */
data class YoutubePlaylistItem(
        val playlistId: String,
        override val title: String,
        override val subtitle: String,
        override val thumbnailUrl: String = "",
) : YoutubeItem

/** A titled row of items, such as Quick picks, Albums for you or an artist's Singles. */
data class YoutubeShelf(
        val title: String,
        val items: List<YoutubeItem>,
        /** The compact list-style shelf at the top of Home. */
        val isQuickPicks: Boolean = false,
        /** Browse id behind the shelf's "More" button, when it has one. */
        val moreBrowseId: String? = null,
)

/** A mood / genre chip along the top of Home; tapping it reloads the feed with [params]. */
data class YoutubeChip(val title: String, val params: String?, val selected: Boolean)

data class YoutubeHomePage(
        val chips: List<YoutubeChip>,
        val shelves: List<YoutubeShelf>,
        val continuation: String?,
)

/** One page of search results or a library listing. */
data class YoutubeItemsPage(val items: List<YoutubeItem>, val continuation: String?)

/** An artist, album or playlist page. */
data class YoutubeDetailPage(
        val kind: Kind,
        val title: String,
        val subtitle: String,
        val description: String,
        val thumbnailUrl: String,
        val tracks: List<YoutubeSongItem>,
        val shelves: List<YoutubeShelf>,
        /** Playlist id that starts this page's radio (artist and album pages). */
        val radioPlaylistId: String? = null,
        /** Playlist id to play or shuffle the whole page. */
        val playlistId: String? = null,
        /** Channel id for the subscribe button (artists). */
        val channelId: String? = null,
        val subscribed: Boolean = false,
        val continuation: String? = null,
) {
    enum class Kind { ARTIST, ALBUM, PLAYLIST }
}
