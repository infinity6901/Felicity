package app.simple.felicity.viewmodels.panels

import android.app.Application
import android.util.Log
import androidx.lifecycle.viewModelScope
import app.simple.felicity.engine.managers.MediaPlaybackManager
import app.simple.felicity.extensions.viewmodels.WrappedViewModel
import app.simple.felicity.repository.online.OnlineSource
import app.simple.felicity.repository.online.OnlineTrack
import app.simple.felicity.repository.repositories.AudiusRepository
import app.simple.felicity.repository.repositories.InternetArchiveRepository
import app.simple.felicity.repository.repositories.JamendoRepository
import app.simple.felicity.repository.repositories.RadioBrowserRepository
import app.simple.felicity.repository.repositories.YoutubeRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * ViewModel for the Online panel — searches whichever of the four free online sources is
 * currently selected and exposes the results as a single flat list.
 *
 * Mirrors [SearchViewModel]'s debounce/state-flow shape, but with a network call in place
 * of a database query per emission, and a selectable [OnlineSource] added into the mix
 * alongside the query.
 *
 * @author Hamza417
 */
@HiltViewModel
class OnlineViewModel @Inject constructor(
        application: Application,
        private val radioBrowserRepository: RadioBrowserRepository,
        private val jamendoRepository: JamendoRepository,
        private val audiusRepository: AudiusRepository,
        private val internetArchiveRepository: InternetArchiveRepository,
        private val youtubeRepository: YoutubeRepository
) : WrappedViewModel(application) {

    private val _selectedSource = MutableStateFlow(OnlineSource.RADIO_BROWSER)
    val selectedSource: StateFlow<OnlineSource> = _selectedSource.asStateFlow()

    private val _searchQuery = MutableStateFlow("")
    val searchQuery: StateFlow<String> = _searchQuery.asStateFlow()

    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    private val _results = MutableStateFlow<List<OnlineTrack>>(emptyList())
    val results: StateFlow<List<OnlineTrack>> = _results.asStateFlow()

    /** Set when [play] can't get a playable stream — the panel shows this once, then clears it via [consumePlaybackError]. */
    private val _playbackError = MutableStateFlow<String?>(null)
    val playbackError: StateFlow<String?> = _playbackError.asStateFlow()

    val isYoutubeSignedIn: Boolean
        get() = youtubeRepository.isSignedIn

    fun downloadYoutubeTrack(track: OnlineTrack) {
        youtubeRepository.download(track)
    }

    fun signOutOfYoutube() {
        youtubeRepository.signOut()
    }

    init {
        observeSearch()
    }

    fun setSearchQuery(query: String) {
        _searchQuery.value = query
    }

    fun setSelectedSource(source: OnlineSource) {
        if (_selectedSource.value == source) return
        _selectedSource.value = source
    }

    /**
     * Resolves whatever is needed to get a playable URL for [track] — immediate for every
     * source except Internet Archive, which requires the extra file-manifest lookup — then
     * hands it to [MediaPlaybackManager.playOnlineTrack].
     */
    fun play(track: OnlineTrack) {
        viewModelScope.launch {
            val streamUrl = when (track.source) {
                OnlineSource.INTERNET_ARCHIVE -> internetArchiveRepository.resolveStreamUrl(track)
                OnlineSource.YOUTUBE -> youtubeRepository.resolveStreamUrl(track)
                else -> track.streamUrl
            }

            if (streamUrl == null) {
                _playbackError.value = "Couldn't find a playable audio file for this item."
                return@launch
            }

            val started = MediaPlaybackManager.playOnlineTrack(track, streamUrl)
            if (!started) {
                _playbackError.value = "Playback isn't ready yet — try again in a moment."
            }
        }
    }

    fun consumePlaybackError() {
        _playbackError.value = null
    }

    @OptIn(FlowPreview::class, ExperimentalCoroutinesApi::class)
    private fun observeSearch() {
        val debouncedQuery = _searchQuery
            .debounce(400L)
            .distinctUntilChanged()

        viewModelScope.launch {
            combine(debouncedQuery, _selectedSource) { query, source -> query to source }
                .flatMapLatest { (query, source) ->
                    flow {
                        _isLoading.value = true
                        val list = if (query.isBlank()) {
                            trendingFor(source)
                        } else {
                            searchFor(source, query)
                        }
                        emit(list)
                    }.catch { e ->
                        Log.w(TAG, "Online fetch failed for source=$source, query=\"$query\"", e)
                        emit(emptyList())
                    }
                }
                .collect { list ->
                    _isLoading.value = false
                    _results.value = list
                }
        }
    }

    private suspend fun searchFor(source: OnlineSource, query: String): List<OnlineTrack> = when (source) {
        OnlineSource.RADIO_BROWSER -> radioBrowserRepository.search(query)
        OnlineSource.JAMENDO -> jamendoRepository.search(query)
        OnlineSource.AUDIUS -> audiusRepository.search(query)
        OnlineSource.INTERNET_ARCHIVE -> internetArchiveRepository.search(query)
        OnlineSource.YOUTUBE -> youtubeRepository.search(query)
    }

    /** Shown by default, before the user has typed anything — see each repository's own `trending()` kdoc for what "trending" means per source. */
    private suspend fun trendingFor(source: OnlineSource): List<OnlineTrack> = when (source) {
        OnlineSource.RADIO_BROWSER -> radioBrowserRepository.trending()
        OnlineSource.JAMENDO -> jamendoRepository.trending()
        OnlineSource.AUDIUS -> audiusRepository.trending()
        OnlineSource.INTERNET_ARCHIVE -> internetArchiveRepository.trending()
        OnlineSource.YOUTUBE -> youtubeRepository.trending()
    }

    companion object {
        private const val TAG = "OnlineViewModel"
    }
}
