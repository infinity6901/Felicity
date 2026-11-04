package app.simple.felicity.viewmodels.panels

import android.app.Application
import android.util.Log
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import app.simple.felicity.R
import app.simple.felicity.engine.managers.MediaPlaybackManager
import app.simple.felicity.extensions.viewmodels.WrappedViewModel
import app.simple.felicity.managers.LyricsManager
import app.simple.felicity.repository.models.LrcLibResponse
import app.simple.felicity.repository.repositories.LrcRepository
import app.simple.felicity.repository.utils.AudioUtils.getProperTitle
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * ViewModel for the [app.simple.felicity.ui.subpanels.LyricsSearch] panel.
 *
 * Handles fetching LRC lyrics from the LrcLib API, persisting the user's search keyword
 * across configuration changes and process death via [SavedStateHandle], and saving the
 * selected [LrcLibResponse] as a sidecar `.lrc` file next to the currently playing audio file.
 *
 * On first creation the ViewModel auto-searches using the currently playing song's title and
 * artist from [MediaPlaybackManager]. If the user subsequently modifies the search keyword
 * and presses search, the user-provided text is used instead and is persisted so it survives
 * process death.
 *
 * @author Hamza417
 */
@HiltViewModel
class LyricsSearchViewModel @Inject constructor(
        application: Application,
        private val savedStateHandle: SavedStateHandle,
        private val lrcRepository: LrcRepository,
        private val lyricsManager: LyricsManager,
        private val multiProviderEngine: app.simple.felicity.managers.MultiProviderLyricsEngine
) : WrappedViewModel(application) {

    private val _searchResults = MutableLiveData<List<LrcLibResponse>>(emptyList())

    /** The current list of [LrcLibResponse] items returned by the latest search. */
    val searchResults: LiveData<List<LrcLibResponse>> get() = _searchResults

    private val _isLoading = MutableLiveData(false)

    /** True while a network search or file-save operation is in progress. */
    val isLoading: LiveData<Boolean> get() = _isLoading

    private val _lrcSaved = MutableLiveData<Boolean>()

    /**
     * Emits `true` once an LRC entry has been successfully saved to disk.
     * The UI should observe this and navigate back on a `true` emission.
     */
    val lrcSaved: LiveData<Boolean> get() = _lrcSaved

    /**
     * The current search keyword.
     *
     * Returns the keyword persisted in [SavedStateHandle] if one exists, or falls back
     * to the title of the currently playing song via [buildDefaultKeyword].
     */
    val keyword: String
        get() = savedStateHandle[KEY_KEYWORD] ?: buildDefaultKeyword()

    /**
     * Returns `true` if the user has manually edited the search keyword at least once
     * since the panel was first opened.
     */
    val isKeywordUserModified: Boolean
        get() = savedStateHandle.get<Boolean>(KEY_USER_MODIFIED) ?: false

    init {
        val currentTrackPath = getCurrentTrackPath()
        val lastTrackPath: String? = savedStateHandle[KEY_LAST_TRACK_PATH]
        if (lastTrackPath == null || lastTrackPath != currentTrackPath) {
            savedStateHandle[KEY_LAST_TRACK_PATH] = currentTrackPath
            savedStateHandle[KEY_USER_MODIFIED] = false
            savedStateHandle[KEY_KEYWORD] = buildDefaultKeyword()
        } else if (!isKeywordUserModified) {
            savedStateHandle[KEY_KEYWORD] = buildDefaultKeyword()
        }
        triggerAutoSearch()
    }

    private fun getCurrentTrackPath(): String {
        val online = MediaPlaybackManager.getCurrentOnlineTrack()
        if (online != null) return "online:${online.source}:${online.id}"
        val audio = MediaPlaybackManager.getCurrentSong()
        return audio?.uri ?: ""
    }

    /**
     * Performs an automatic search using the title and artist of the currently playing song.
     * Falls back to [keyword] as the track name if no song is playing.
     */
    private fun triggerAutoSearch() {
        val onlineTrack = MediaPlaybackManager.getCurrentOnlineTrack()
        if (onlineTrack != null) {
            performSearch(onlineTrack.title, onlineTrack.subtitle)
        } else {
            val audio = MediaPlaybackManager.getCurrentSong()
            if (audio != null) {
                val title = audio.getProperTitle()
                val artist = audio.artist ?: ""
                performSearch(title, artist)
            } else {
                val fallback = keyword
                if (fallback.isNotBlank()) {
                    performSearch(fallback, "")
                }
            }
        }
    }

    /**
     * Persists the user-typed keyword in [SavedStateHandle] and marks it as user-modified
     * so that [triggerAutoSearch] does not overwrite it on a subsequent ViewModel recreation.
     *
     * @param query the text entered by the user in the search field.
     */
    fun setUserKeyword(query: String) {
        savedStateHandle[KEY_KEYWORD] = query
        savedStateHandle[KEY_USER_MODIFIED] = true
    }

    /**
     * Executes a search using whatever keyword is currently saved in [SavedStateHandle].
     * The whole keyword is passed as `trackName` with an empty `artistName` since the user
     * may have typed either a title, a title + artist, or any free-form query.
     */
    fun searchWithCurrentKeyword() {
        val query = keyword.trim()
        if (query.isBlank()) {
            warning.postValue(getString(R.string.search))
            return
        }
        performSearch(query, "")
    }

    /**
     * Sends a search request to [LrcRepository] and updates [searchResults].
     * Posts an appropriate warning message to [warning] when no results are found or on error.
     *
     * @param trackName  the song title to search for.
     * @param artistName the artist name to search for (may be empty).
     */
    private fun performSearch(trackName: String, artistName: String) {
        if (trackName.isBlank()) return

        viewModelScope.launch(Dispatchers.IO) {
            _isLoading.postValue(true)
            Log.d(TAG, "Searching for lyrics: trackName='$trackName', artistName='$artistName'")

            // Query every source in parallel: LrcLib's own search plus the multi-provider engine
            // (LRCLIB candidates, LyricsPlus mirrors, cached results). Results are merged and
            // de-duplicated so the same lyrics from two sources show only once.
            val merged = kotlinx.coroutines.supervisorScope {
                val lrcLib = async { runCatching { lrcRepository.searchLyrics(trackName, artistName).getOrThrow() }.getOrDefault(emptyList()) }
                val engine = async {
                    runCatching {
                        multiProviderEngine.search(trackName, artistName).map { c ->
                            LrcLibResponse(
                                    id = c.rawLyrics.hashCode(),
                                    trackName = c.title.ifBlank { trackName },
                                    artistName = c.artist.ifBlank { artistName },
                                    albumName = c.provider,
                                    duration = 0.0,
                                    plainLyrics = c.plainLyrics.ifBlank { if (c.synced) null else c.rawLyrics },
                                    syncedLyrics = if (c.synced) c.rawLyrics else null,
                                    isInstrumental = false)
                        }
                    }.getOrDefault(emptyList())
                }
                (lrcLib.await() + engine.await())
                    .filter { !it.syncedLyrics.isNullOrBlank() || !it.plainLyrics.isNullOrBlank() }
                    .distinctBy { (it.syncedLyrics ?: it.plainLyrics).orEmpty().trim().hashCode() }
                    .sortedByDescending { !it.syncedLyrics.isNullOrBlank() }
            }

            _searchResults.postValue(merged)
            if (merged.isEmpty()) {
                warning.postValue(getString(R.string.no_lyrics_found))
            }
            Log.d(TAG, "Search returned ${merged.size} result(s) from all sources")

            _isLoading.postValue(false)
        }
    }

    /**
     * Downloads the `syncedLyrics` content from [lrcResponse] and saves it as a `.lrc`
     * sidecar file next to the currently playing audio file using [LrcRepository].
     *
     * Any pre-existing .lrc or .txt sidecar files are removed first so the freshly
     * downloaded content is guaranteed to be what loads next — no stale file will
     * sneak in ahead of the new one.
     *
     * On success, posts `true` to [lrcSaved] signaling the UI to navigate back, and
     * tells [LyricsManager] to reload so every screen immediately shows the new lyrics.
     * On failure, posts an error message to [warning].
     *
     * @param lrcResponse the chosen search result to save.
     */
    fun downloadAndSaveLrc(lrcResponse: LrcLibResponse) {
        val syncedLyrics = lrcResponse.syncedLyrics ?: lrcResponse.plainLyrics
        if (syncedLyrics.isNullOrBlank()) {
            warning.postValue(getString(R.string.no_lyrics_found))
            return
        }

        if (MediaPlaybackManager.getCurrentOnlineTrack() != null) {
            lyricsManager.setOnlineLyrics(syncedLyrics)
            _lrcSaved.postValue(true)
        } else {
            val audio = MediaPlaybackManager.getCurrentSong()
            if (audio != null) {
                viewModelScope.launch(Dispatchers.IO) {
                    _isLoading.postValue(true)
                    Log.d(TAG, "Saving LRC for: ${audio.title} -> ${audio.uri}")

                    // Wipe out whatever was there before so the fresh download is the only
                    // source of truth going forward — no accidental fallback to old files.
                    lrcRepository.deleteLrcFile(audio.uri)

                    val saveResult = if (!lrcResponse.syncedLyrics.isNullOrBlank()) {
                        lrcRepository.saveLrcToFile(syncedLyrics, audio.uri)
                    } else {
                        lrcRepository.saveTxtToFile(syncedLyrics, audio.uri)
                    }

                    saveResult.onSuccess {
                        Log.d(TAG, "LRC saved successfully to ${it.path}")
                        lyricsManager.reloadLrcData()
                        _lrcSaved.postValue(true)
                    }.onFailure { exception ->
                        Log.e(TAG, "Failed to save LRC", exception)
                        warning.postValue(getString(R.string.no_lyrics_found))
                    }

                    _isLoading.postValue(false)
                }
            } else {
                warning.postValue(getString(R.string.no_lyrics_found))
            }
        }
    }

    /**
     * Builds the default search keyword from the currently playing song's title.
     * Returns an empty string if no song is currently playing.
     */
    private fun buildDefaultKeyword(): String {
        val onlineTrack = MediaPlaybackManager.getCurrentOnlineTrack()
        if (onlineTrack != null) return onlineTrack.title
        val audio = MediaPlaybackManager.getCurrentSong()
        return audio?.getProperTitle() ?: ""
    }

    companion object {
        private const val TAG = "LyricsSearchViewModel"
        private const val KEY_KEYWORD = "lyrics_search_keyword"
        private const val KEY_USER_MODIFIED = "lyrics_search_user_modified"
        private const val KEY_LAST_TRACK_PATH = "lyrics_search_last_track_path"
    }
}

