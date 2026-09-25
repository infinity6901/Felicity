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
import app.simple.felicity.repository.youtube.YoutubePlaylist
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

enum class YoutubeTab { SEARCH, PLAYLISTS, DOWNLOADED }

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

    private val _activeTab = MutableStateFlow(YoutubeTab.SEARCH)
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

    fun setActiveTab(tab: YoutubeTab) {
        _activeTab.value = tab
        if (tab == YoutubeTab.PLAYLISTS && _playlists.value.isEmpty() && _openedPlaylist.value == null) {
            loadLibraryPlaylists()
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
        if (query.isBlank()) {
            _searchResults.value = emptyList()
            _searchSuggestions.value = emptyList()
            if (_quickPicks.value.isEmpty()) {
                loadHomeSections()
            }
            return
        }

        viewModelScope.launch {
            _isLoading.value = true
            _searchResults.value = youtubeRepository.search(query)
            _isLoading.value = false
        }
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
