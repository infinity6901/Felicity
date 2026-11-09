package app.simple.felicity.viewmodels.panels

import android.app.Application
import androidx.lifecycle.viewModelScope
import androidx.work.WorkInfo
import androidx.work.WorkManager
import app.simple.felicity.engine.managers.MediaPlaybackManager
import app.simple.felicity.extensions.viewmodels.WrappedViewModel
import app.simple.felicity.repository.notifications.DownloadNotificationManager
import app.simple.felicity.repository.online.OnlineTrack
import app.simple.felicity.repository.repositories.YoutubeRepository
import app.simple.felicity.repository.youtube.YoutubeDownloadWorker
import app.simple.felicity.repository.repositories.toOnlineTrack
import app.simple.felicity.repository.youtube.YoutubeAlbumItem
import app.simple.felicity.repository.youtube.YoutubeArtistItem
import app.simple.felicity.repository.youtube.YoutubeChip
import app.simple.felicity.repository.youtube.YoutubeConstants
import app.simple.felicity.repository.youtube.YoutubeDetailPage
import app.simple.felicity.repository.youtube.YoutubeItem
import app.simple.felicity.repository.youtube.YoutubePlaylist
import app.simple.felicity.repository.youtube.YoutubePlaylistItem
import app.simple.felicity.repository.youtube.YoutubeShelf
import app.simple.felicity.repository.youtube.YoutubeSongItem
import app.simple.felicity.repository.youtube.YoutubeSessionStore
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import javax.inject.Inject
import kotlin.coroutines.resume

enum class YoutubeTab { HOME, SEARCH, PLAYLISTS, DOWNLOADED }

/** Result type filters offered under the search box. */
enum class YoutubeSearchFilter(val label: String, val params: String?) {
    ALL("All", null),
    SONGS("Songs", YoutubeConstants.SearchFilter.SONGS),
    VIDEOS("Videos", YoutubeConstants.SearchFilter.VIDEOS),
    ALBUMS("Albums", YoutubeConstants.SearchFilter.ALBUMS),
    ARTISTS("Artists", YoutubeConstants.SearchFilter.ARTISTS),
    PLAYLISTS("Playlists", YoutubeConstants.SearchFilter.COMMUNITY_PLAYLISTS),
}

/** What the Library tab (the old Playlists tab) lists. */
enum class YoutubeLibraryKind(val label: String, val browseId: String?) {
    PLAYLISTS("Playlists", null),
    LIKED("Liked songs", YoutubeRepository.LIBRARY_LIKED_SONGS),
    ALBUMS("Albums", YoutubeRepository.LIBRARY_ALBUMS),
    ARTISTS("Artists", YoutubeRepository.LIBRARY_ARTISTS),
    HISTORY("History", YoutubeRepository.LIBRARY_HISTORY),
}

@HiltViewModel
class YoutubeViewModel @Inject constructor(
        application: Application,
        private val youtubeRepository: YoutubeRepository,
) : WrappedViewModel(application) {

    init {
        // MediaPlaybackManager is a plain object (not Hilt-managed) so it can't inject
        // YoutubeRepository itself — this hands it a resolver closure instead, used for
        // next()/previous() navigation within an online (playlist) queue. Re-assigning
        // this on every ViewModel construction is harmless, it's just a lambda swap.
        MediaPlaybackManager.onlineStreamResolver = { track -> youtubeRepository.resolveStreamUrl(track) }
    }

    private val _activeTab = MutableStateFlow(YoutubeTab.HOME)
    val activeTab: StateFlow<YoutubeTab> = _activeTab.asStateFlow()

    private val _searchResults = MutableStateFlow<List<OnlineTrack>>(emptyList())
    val searchResults: StateFlow<List<OnlineTrack>> = _searchResults.asStateFlow()

    private val _quickPicks = MutableStateFlow<List<OnlineTrack>>(emptyList())
    val quickPicks: StateFlow<List<OnlineTrack>> = _quickPicks.asStateFlow()

    private val _trendingSongs = MutableStateFlow<List<OnlineTrack>>(emptyList())
    val trendingSongs: StateFlow<List<OnlineTrack>> = _trendingSongs.asStateFlow()

    private val _newReleases = MutableStateFlow<List<OnlineTrack>>(emptyList())
    val newReleases: StateFlow<List<OnlineTrack>> = _newReleases.asStateFlow()

    private val _playlists = MutableStateFlow<List<YoutubePlaylist>>(emptyList())
    val playlists: StateFlow<List<YoutubePlaylist>> = _playlists.asStateFlow()

    private val _openedPlaylist = MutableStateFlow<YoutubePlaylist?>(null)
    val openedPlaylist: StateFlow<YoutubePlaylist?> = _openedPlaylist.asStateFlow()

    private val _playlistTracks = MutableStateFlow<List<OnlineTrack>>(emptyList())
    val playlistTracks: StateFlow<List<OnlineTrack>> = _playlistTracks.asStateFlow()

    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    private val _playbackError = MutableStateFlow<String?>(null)
    val playbackError: StateFlow<String?> = _playbackError.asStateFlow()

    /** videoId -> 0..100 while downloading, 100 on success, -1 on failure. */
    private val _downloadProgress = MutableStateFlow<Map<String, Int>>(emptyMap())
    val downloadProgress: StateFlow<Map<String, Int>> = _downloadProgress.asStateFlow()

    private var batchDownloadJob: Job? = null

    private val _isDownloadingBatch = MutableStateFlow(false)
    val isDownloadingBatch: StateFlow<Boolean> = _isDownloadingBatch.asStateFlow()

    /** The live online-queue position, straight from MediaPlaybackManager, for highlighting "now playing" in a playlist's track list. */
    val onlineQueueFlow: StateFlow<List<OnlineTrack>> = MediaPlaybackManager.onlineQueueFlow
    val onlineQueueIndexFlow: StateFlow<Int> = MediaPlaybackManager.onlineQueueIndexFlow

    val isSignedIn: Boolean
        get() = youtubeRepository.isSignedIn

    init {
        viewModelScope.launch {
            MediaPlaybackManager.onlineQueueErrorFlow.collect { message ->
                _playbackError.value = message
            }
        }
    }

    // ------------------------------------------------------------------ Home

    private val _homeChips = MutableStateFlow<List<YoutubeChip>>(emptyList())
    val homeChips: StateFlow<List<YoutubeChip>> = _homeChips.asStateFlow()

    private val _homeShelves = MutableStateFlow<List<YoutubeShelf>>(emptyList())
    val homeShelves: StateFlow<List<YoutubeShelf>> = _homeShelves.asStateFlow()

    /** The chip currently filtering Home (its `params`), null for the plain feed. */
    private val _homeChipParams = MutableStateFlow<String?>(null)
    val homeChipParams: StateFlow<String?> = _homeChipParams.asStateFlow()

    private var homeToken: String? = null
    private var homeLoaded = false

    fun loadHome(chipParams: String? = null, force: Boolean = false) {
        if (homeLoaded && !force && chipParams == _homeChipParams.value) return
        homeLoaded = true
        _homeChipParams.value = chipParams
        viewModelScope.launch {
            _isLoading.value = true
            val page = youtubeRepository.getHome(chipParams)
            if (chipParams == null || _homeChips.value.isEmpty()) _homeChips.value = page.chips
            _homeShelves.value = page.shelves
            homeToken = page.continuation
            _isLoading.value = false
        }
    }

    // ------------------------------------------------------------------ Search

    private val _searchFilter = MutableStateFlow(YoutubeSearchFilter.ALL)
    val searchFilter: StateFlow<YoutubeSearchFilter> = _searchFilter.asStateFlow()

    private val _searchItems = MutableStateFlow<List<YoutubeItem>>(emptyList())
    val searchItems: StateFlow<List<YoutubeItem>> = _searchItems.asStateFlow()

    private var searchQuery = ""
    private var searchToken: String? = null
    private var searchJob: Job? = null

    fun setSearchFilter(filter: YoutubeSearchFilter) {
        if (_searchFilter.value == filter) return
        _searchFilter.value = filter
        runSearch(searchQuery)
    }

    private fun runSearch(query: String) {
        searchQuery = query
        searchJob?.cancel()
        if (query.isBlank()) {
            _searchItems.value = emptyList()
            searchToken = null
            return
        }
        searchJob = viewModelScope.launch {
            kotlinx.coroutines.delay(300)
            _isLoading.value = true
            val page = youtubeRepository.searchPage(query, _searchFilter.value.params)
            _searchItems.value = page.items
            _searchResults.value = page.items.filterIsInstance<YoutubeSongItem>().map { it.toOnlineTrack() }
            searchToken = page.continuation
            _isLoading.value = false
        }
    }

    // ------------------------------------------------------------------ Library

    private val _libraryKind = MutableStateFlow(YoutubeLibraryKind.PLAYLISTS)
    val libraryKind: StateFlow<YoutubeLibraryKind> = _libraryKind.asStateFlow()

    private val _libraryItems = MutableStateFlow<List<YoutubeItem>>(emptyList())
    val libraryItems: StateFlow<List<YoutubeItem>> = _libraryItems.asStateFlow()

    private var libraryToken: String? = null

    fun setLibraryKind(kind: YoutubeLibraryKind) {
        _libraryKind.value = kind
        loadLibrary()
    }

    fun loadLibrary() {
        val kind = _libraryKind.value
        libraryToken = null
        viewModelScope.launch {
            _isLoading.value = true
            if (kind.browseId == null) {
                _libraryItems.value = youtubeRepository.getLibraryPlaylists().map {
                    YoutubePlaylistItem(it.playlistId.removePrefix("VL"), it.title, it.subtitle, it.thumbnailUrl)
                }
            } else {
                val page = youtubeRepository.getLibrary(kind.browseId)
                _libraryItems.value = page.items
                libraryToken = page.continuation
            }
            _isLoading.value = false
        }
    }

    // ------------------------------------------------------------------ Detail pages (artist / album / playlist)

    private val detailStack = ArrayList<YoutubeDetailPage>()

    private val _detail = MutableStateFlow<YoutubeDetailPage?>(null)
    val detail: StateFlow<YoutubeDetailPage?> = _detail.asStateFlow()

    private var detailToken: String? = null

    /** Opens the page behind an album, artist or playlist card; songs are played, not opened. */
    fun openItem(item: YoutubeItem) {
        viewModelScope.launch {
            _isLoading.value = true
            val page = when (item) {
                is YoutubeAlbumItem -> youtubeRepository.getAlbum(item.browseId)
                is YoutubeArtistItem -> youtubeRepository.getArtist(item.browseId)
                is YoutubePlaylistItem -> youtubeRepository.getPlaylist(item.playlistId)
                is YoutubeSongItem -> null
            }
            if (page != null) {
                _detail.value?.let { detailStack.add(it) }
                _detail.value = page
                detailToken = page.continuation
            } else if (item !is YoutubeSongItem) {
                _playbackError.value = "Could not open ${item.title}."
            }
            _isLoading.value = false
        }
    }

    /** Goes back one page; returns false when no detail page was open. */
    fun closeDetail(): Boolean {
        if (_detail.value == null) return false
        _detail.value = detailStack.removeLastOrNull()
        detailToken = _detail.value?.continuation
        return true
    }

    // ------------------------------------------------------------------ Paging

    private var loadingMore = false

    /** Fetches the next page of whatever list is showing, if it has one. */
    fun loadMore() {
        if (loadingMore) return
        val detailPage = _detail.value
        when {
            detailPage != null -> {
                val token = detailToken ?: return
                loadingMore = true
                viewModelScope.launch {
                    val (more, next) = youtubeRepository.getPlaylistMore(token)
                    detailToken = next
                    _detail.value = detailPage.copy(tracks = detailPage.tracks + more, continuation = next)
                    loadingMore = false
                }
            }
            _activeTab.value == YoutubeTab.SEARCH -> {
                val token = searchToken ?: return
                loadingMore = true
                viewModelScope.launch {
                    val page = youtubeRepository.searchPage(searchQuery, _searchFilter.value.params, token)
                    searchToken = page.continuation
                    _searchItems.value = _searchItems.value + page.items
                    _searchResults.value = _searchItems.value.filterIsInstance<YoutubeSongItem>().map { it.toOnlineTrack() }
                    loadingMore = false
                }
            }
            _activeTab.value == YoutubeTab.PLAYLISTS -> {
                val token = libraryToken ?: return
                val browseId = _libraryKind.value.browseId ?: return
                loadingMore = true
                viewModelScope.launch {
                    val page = youtubeRepository.getLibrary(browseId, token)
                    libraryToken = page.continuation
                    _libraryItems.value = _libraryItems.value + page.items
                    loadingMore = false
                }
            }
            _activeTab.value == YoutubeTab.HOME -> {
                val token = homeToken ?: return
                loadingMore = true
                viewModelScope.launch {
                    val page = youtubeRepository.getHomeContinuation(token)
                    homeToken = page.continuation
                    _homeShelves.value = _homeShelves.value + page.shelves
                    loadingMore = false
                }
            }
        }
    }

    // ------------------------------------------------------------------ Playback and account actions

    /** Plays [song] and queues the other [songs] of its list behind it. */
    fun playSong(song: YoutubeSongItem, songs: List<YoutubeSongItem>) {
        val queue = songs.map { it.toOnlineTrack() }
        val index = songs.indexOfFirst { it.videoId == song.videoId }.coerceAtLeast(0)
        MediaPlaybackManager.playOnlineQueue(queue, index)
    }

    fun playAll(songs: List<YoutubeSongItem>, shuffle: Boolean = false) {
        if (songs.isEmpty()) return
        val queue = songs.map { it.toOnlineTrack() }.let { if (shuffle) it.shuffled() else it }
        MediaPlaybackManager.playOnlineQueue(queue, 0)
    }

    /** Starts the radio for a song, or for an artist / album / playlist mix id. */
    fun startRadio(videoId: String?, playlistId: String? = null) {
        viewModelScope.launch {
            _isLoading.value = true
            val tracks = youtubeRepository.getRadio(videoId, playlistId)
            _isLoading.value = false
            if (tracks.isEmpty()) _playbackError.value = "Could not start the radio." else MediaPlaybackManager.playOnlineQueue(tracks, 0)
        }
    }

    fun like(videoId: String, like: Boolean = true) {
        viewModelScope.launch {
            val ok = youtubeRepository.like(videoId, like)
            _playbackError.value = if (ok) null else "Sign in to YouTube to like songs."
        }
    }

    fun toggleSubscribe(page: YoutubeDetailPage) {
        val channel = page.channelId ?: return
        viewModelScope.launch {
            val target = !page.subscribed
            if (youtubeRepository.subscribe(channel, target)) {
                _detail.value = _detail.value?.copy(subscribed = target)
            } else {
                _playbackError.value = "Sign in to YouTube to subscribe."
            }
        }
    }

    fun addToPlaylist(playlistId: String, videoId: String) {
        viewModelScope.launch {
            val ok = youtubeRepository.addToPlaylist(playlistId, videoId)
            if (!ok) _playbackError.value = "Could not add the song to the playlist."
        }
    }

    suspend fun libraryPlaylistsForPicker(): List<YoutubePlaylist> =
        _playlists.value.ifEmpty { youtubeRepository.getLibraryPlaylists().also { _playlists.value = it } }

    fun setActiveTab(tab: YoutubeTab) {
        _activeTab.value = tab
        when (tab) {
            YoutubeTab.HOME -> loadHome()
            YoutubeTab.PLAYLISTS -> if (_libraryItems.value.isEmpty()) loadLibrary()
            else -> Unit
        }
    }

    fun loadHomeSections() {
        viewModelScope.launch {
            _isLoading.value = true
            _quickPicks.value = youtubeRepository.getQuickPicks()
            _trendingSongs.value = youtubeRepository.getTrendingSongs()
            _newReleases.value = youtubeRepository.getNewReleases()
            _isLoading.value = false
        }
    }

    private val _searchSuggestions = MutableStateFlow<List<String>>(emptyList())
    val searchSuggestions: StateFlow<List<String>> = _searchSuggestions.asStateFlow()

    fun fetchSearchSuggestions(query: String) {
        if (query.isBlank()) {
            _searchSuggestions.value = emptyList()
            return
        }
        viewModelScope.launch {
            _searchSuggestions.value = youtubeRepository.getSearchSuggestions(query)
        }
    }

    fun search(query: String) {
        runSearch(query.trim())
    }

    fun loadLibraryPlaylists() {
        viewModelScope.launch {
            _isLoading.value = true
            _playlists.value = youtubeRepository.getLibraryPlaylists()
            _isLoading.value = false
        }
    }

    fun importPlaylistByUrl(url: String) {
        if (url.isBlank()) return
        viewModelScope.launch {
            _isLoading.value = true
            val importedTracks = youtubeRepository.importPlaylistByUrl(url)
            if (importedTracks.isNotEmpty()) {
                _openedPlaylist.value = YoutubePlaylist(
                    playlistId = "imported",
                    title = "Imported Playlist",
                    subtitle = "${importedTracks.size} tracks",
                    thumbnailUrl = importedTracks.firstOrNull()?.artworkUrl.orEmpty()
                )
                _playlistTracks.value = importedTracks
            } else {
                _playbackError.value = "Could not import playlist from the provided URL."
            }
            _isLoading.value = false
        }
    }

    fun openPlaylist(playlist: YoutubePlaylist) {
        _openedPlaylist.value = playlist
        viewModelScope.launch {
            _isLoading.value = true
            _playlistTracks.value = youtubeRepository.getPlaylistTracks(playlist.playlistId)
            _isLoading.value = false
        }
    }

    /** Back out of a playlist's track list to the playlist grid. */
    fun closePlaylist() {
        _openedPlaylist.value = null
        _playlistTracks.value = emptyList()
    }

    /**
     * Plays [track] and queues the rest of [listContext] (whichever list [track] came
     * from — search results or an opened playlist's tracks) after it, so next/previous
     * and reordering (see [reorderOnlineQueue]) work the way they would for a local
     * playlist. [listContext] defaults to whatever's currently visible for the active tab.
     */
    fun play(track: OnlineTrack, listContext: List<OnlineTrack>? = null) {
        val queue = listContext
            ?: if (_openedPlaylist.value != null) _playlistTracks.value else _searchResults.value

        val startIndex = queue.indexOfFirst { it.source == track.source && it.id == track.id }
            .takeIf { it >= 0 } ?: 0

        MediaPlaybackManager.playOnlineQueue(queue, startIndex)
    }

    /** Applies a new order to the currently playing online queue (drag-to-reorder in the track list). */
    fun reorderOnlineQueue(newOrder: List<OnlineTrack>) {
        MediaPlaybackManager.reorderOnlineQueue(newOrder)
    }

    fun consumePlaybackError() {
        _playbackError.value = null
    }

    /** Kicks off a single track download sequentially and notifies progress. */
    fun download(track: OnlineTrack, format: String = "m4a") {
        downloadTracks(listOf(track), format)
    }

    /** Downloads every track in the currently opened playlist sequentially, one by one. */
    fun downloadOpenedPlaylist(format: String = "m4a") {
        val tracks = _playlistTracks.value
        downloadTracks(tracks, format)
    }

    /** Downloads a specified list of tracks sequentially, reporting batch progress. */
    fun downloadTracks(tracks: List<OnlineTrack>, format: String = "m4a") {
        if (tracks.isEmpty()) return

        val alreadyInProgress = _downloadProgress.value.keys
        val tracksToDownload = tracks.filter { it.id !in alreadyInProgress }

        if (tracksToDownload.isEmpty()) return

        batchDownloadJob?.cancel()
        batchDownloadJob = viewModelScope.launch {
            _isDownloadingBatch.value = true
            val notificationManager = DownloadNotificationManager(getApplication())
            val total = tracksToDownload.size
            var successCount = 0
            var failureCount = 0

            try {
                tracksToDownload.forEachIndexed { index, track ->
                    val trackNum = index + 1
                    val success = downloadTrackSequential(track, trackNum, total, format, notificationManager)
                    if (success) {
                        successCount++
                    } else {
                        failureCount++
                    }
                }

                if (total > 1) {
                    notificationManager.postBatchSummary(successCount, failureCount, total)
                } else if (successCount > 0) {
                    notificationManager.postSuccess(tracksToDownload.first().title, 1, 1)
                }
            } finally {
                _isDownloadingBatch.value = false
                notificationManager.dismissProgress()
            }
        }
    }

    /** Cancels any in-flight batch download and all WorkManager tasks for YoutubeDownloadWorker. */
    fun cancelDownloads() {
        batchDownloadJob?.cancel()
        batchDownloadJob = null
        _isDownloadingBatch.value = false
        YoutubeDownloadWorker.cancelAllDownloads(getApplication())
        _downloadProgress.value = emptyMap()
        val notificationManager = DownloadNotificationManager(getApplication())
        notificationManager.dismissProgress()
    }

    private suspend fun downloadTrackSequential(
        track: OnlineTrack,
        currentTrackIndex: Int = 1,
        totalTracks: Int = 1,
        format: String = "m4a",
        notificationManager: DownloadNotificationManager? = null
    ): Boolean {
        val workId = youtubeRepository.download(track, format) ?: return false
        _downloadProgress.value = _downloadProgress.value + (track.id to 0)
        notificationManager?.postProgress(track.title, 0, currentTrackIndex, totalTracks)

        return suspendCancellableCoroutine { continuation ->
            val workManager = WorkManager.getInstance(getApplication())
            val job = viewModelScope.launch {
                workManager.getWorkInfoByIdFlow(workId).collect { info ->
                    if (info == null) return@collect

                    when (info.state) {
                        WorkInfo.State.RUNNING -> {
                            val percent = info.progress.getInt(YoutubeDownloadWorker.KEY_PROGRESS, 0)
                            _downloadProgress.value = _downloadProgress.value + (track.id to percent)
                            notificationManager?.postProgress(track.title, percent, currentTrackIndex, totalTracks)
                        }
                        WorkInfo.State.SUCCEEDED -> {
                            _downloadProgress.value = _downloadProgress.value + (track.id to 100)
                            clearProgressAfterDelay(track.id)
                            if (continuation.isActive) continuation.resume(true)
                        }
                        WorkInfo.State.FAILED, WorkInfo.State.CANCELLED -> {
                            val error = info.outputData.getString(YoutubeDownloadWorker.KEY_ERROR) ?: "Download failed"
                            _downloadProgress.value = _downloadProgress.value + (track.id to -1)
                            clearProgressAfterDelay(track.id)
                            notificationManager?.postFailure(track.title, error, currentTrackIndex, totalTracks)
                            if (continuation.isActive) continuation.resume(false)
                        }
                        else -> Unit
                    }
                }
            }
            continuation.invokeOnCancellation {
                job.cancel()
            }
        }
    }

    private fun clearProgressAfterDelay(videoId: String) {
        viewModelScope.launch {
            kotlinx.coroutines.delay(2000)
            _downloadProgress.value = _downloadProgress.value - videoId
        }
    }

    fun signOut() {
        YoutubeSessionStore.signOut(getApplication())
    }
}
