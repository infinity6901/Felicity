package app.simple.felicity.managers

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import app.simple.felicity.decorations.lrc.model.LrcData
import app.simple.felicity.decorations.lrc.model.LrcEntry
import app.simple.felicity.decorations.lrc.parser.ILyricsParser
import app.simple.felicity.decorations.lrc.parser.LrcParser
import app.simple.felicity.decorations.lrc.parser.LyricsParseException
import app.simple.felicity.decorations.lrc.parser.TxtParser
import app.simple.felicity.decorations.lrc.parser.WordLrcParser
import app.simple.felicity.engine.managers.MediaPlaybackManager
import app.simple.felicity.managers.LyricsManager.Companion.SYNC_SAVE_DEBOUNCE_MS
import app.simple.felicity.preferences.LyricsPreferences
import app.simple.felicity.lyricsanalysis.lyrics.FelicityLyricsAdapter
import app.simple.felicity.lyricsanalysis.lyrics.LyricSection
import app.simple.felicity.lyricsanalysis.lyrics.LyricsPhraseMatcher
import app.simple.felicity.repository.metadata.LyricsMetaHelper
import app.simple.felicity.repository.models.Audio
import app.simple.felicity.repository.online.OnlineTrack
import app.simple.felicity.repository.repositories.LrcRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * A single place where all lyrics data lives for the currently playing song.
 *
 * Every screen that needs lyrics just collects [lrcData] — no manual loading required.
 * The manager watches the playback queue and reloads automatically on song changes,
 * so the Lyrics panel and the player screen always see the same data.
 *
 * Think of it as the one lyrics DJ that everyone in the app is tuned into.
 *
 * @author Hamza417
 */
@Singleton
class LyricsManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val lrcRepository: LrcRepository,
    private val multiProviderLyricsEngine: MultiProviderLyricsEngine
) {

    private val TAG = "LyricsManager"

    private val EMPTY_LRC_DATA = LrcData().apply {
        addEntry(LrcEntry(0L, "No lyrics found for this song."))
    }

    /**
     * App-wide coroutine scope. A [SupervisorJob] is used so that a failed lyrics
     * fetch does not cancel the song-change observer or any other running job.
     */
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private val _lrcData = MutableStateFlow<LrcData?>(null)

    /**
     * The parsed lyrics for the currently playing song.
     * - `null`      → a load is in progress (first start only; during song changes
     *                  the previous song's data stays until the new one is ready).
     * - empty [LrcData] → load finished but no lyrics were found.
     * - non-empty   → lyrics are ready to display.
     */
    val lrcData: StateFlow<LrcData?> = _lrcData.asStateFlow()

    private val _detectedSections = MutableStateFlow<List<LyricSection>>(emptyList())

    /** Automatically inferred INTRO/VERSE/CHORUS/BRIDGE ranges for the active lyrics. */
    val detectedSections: StateFlow<List<LyricSection>> = _detectedSections.asStateFlow()

    /** Find the best local lyric-line match for a phrase without making a network request. */
    fun findPhrase(phrase: String): Int? {
        if (!LyricsPhraseMatcher.isSearchable(phrase)) return null
        val lines = _lrcData.value?.let(FelicityLyricsAdapter::toLines).orEmpty()
        return LyricsPhraseMatcher.match(phrase, lines.map { it.text })?.lineIndex
    }


    suspend fun searchPhraseAcrossTracks(phrase: String): List<MultiProviderLyricsEngine.PhraseHit> =
        multiProviderLyricsEngine.searchPhraseAcrossTracks(phrase)

    private val _availableVersions = MutableStateFlow<List<MultiProviderLyricsEngine.Candidate>>(emptyList())
    /** All matching lyric versions from the available providers for the current track. */
    val availableVersions: StateFlow<List<MultiProviderLyricsEngine.Candidate>> = _availableVersions.asStateFlow()

    private val _versionsLoading = MutableStateFlow(false)

    /** True while [loadVersions] is searching the lyric sources. */
    val versionsLoading: StateFlow<Boolean> = _versionsLoading.asStateFlow()

    /**
     * Searches every lyric source for the current song so the user can pick a version, even when
     * the lyrics on screen came from a local file or the tags. Results land in [availableVersions].
     */
    fun loadVersions() {
        val online = MediaPlaybackManager.getCurrentOnlineTrack()
        val song = if (online == null) MediaPlaybackManager.getCurrentSong() else null
        val path = online?.let { "online:${it.source}:${it.id}" } ?: song?.uri ?: return
        val title = online?.title ?: song?.title ?: song?.name.orEmpty()
        val artist = online?.subtitle ?: song?.artist.orEmpty()
        if (title.isBlank()) return
        _versionsLoading.value = true
        scope.launch {
            try {
                val candidates = multiProviderLyricsEngine.search(title, artist)
                if (isStillRelevant(path)) _availableVersions.value = candidates
            } catch (e: Exception) {
                Log.w(TAG, "Could not search lyric versions", e)
            } finally {
                _versionsLoading.value = false
            }
        }
    }

    /**
     * Searches the lyric sources with text the user typed (title, or "Artist - Title") instead of
     * the playing song's tags. Results replace [availableVersions].
     */
    fun searchVersions(query: String) {
        val online = MediaPlaybackManager.getCurrentOnlineTrack()
        val song = if (online == null) MediaPlaybackManager.getCurrentSong() else null
        val path = online?.let { "online:${it.source}:${it.id}" } ?: song?.uri ?: return
        _versionsLoading.value = true
        scope.launch {
            try {
                val candidates = multiProviderLyricsEngine.searchManual(query)
                if (isStillRelevant(path)) _availableVersions.value = candidates
            } catch (e: Exception) {
                Log.w(TAG, "Manual lyric search failed", e)
            } finally {
                _versionsLoading.value = false
            }
        }
    }

    private val _loadingStatus = MutableStateFlow<LyricsLoadingStatus>(LyricsLoadingStatus.Idle)

    /**
     * Only emits non-[LyricsLoadingStatus.Idle] values when the manager is actively
     * talking to the internet (searching or downloading). Local file reads are fast
     * enough that showing a loading indicator would just be noise.
     */
    val loadingStatus: StateFlow<LyricsLoadingStatus> = _loadingStatus.asStateFlow()

    private val _syncOffsetMs = MutableStateFlow(0L)

    /**
     * The current sync offset in milliseconds to add to the playback position before
     * passing it to the lyrics view. Positive = highlight a later line (fixes lagging
     * lyrics), negative = highlight an earlier line (fixes lyrics running ahead).
     */
    val syncOffsetMs: StateFlow<Long> = _syncOffsetMs.asStateFlow()

    /**
     * Accumulated sync offset that callers can read synchronously from the seek listener
     * without needing a coroutine collector.
     */
    var syncOffset: Long = 0L
        private set

    /** Mirrors the LRC content currently written on disk after the last [persistSyncAdjustment] call. */
    private var bakedLrcData: LrcData? = null

    /** Pending delta that has not yet been baked into the .lrc file on disk. */
    private var pendingSyncDeltaMs: Long = 0L

    /** Debounces disk writes so rapid tap-and-hold adjustments collapse into one save. */
    private val syncSaveHandler = Handler(Looper.getMainLooper())
    private val syncSaveRunnable = Runnable { persistSyncAdjustment() }

    /**
     * Path of the song whose lyrics the current [loadingJob] was started for.
     * Used to discard stale results when the song changes mid-load.
     */
    @Volatile
    private var lastLoadedPath: String? = null

    /** Reference to the currently running load job so we can cancel it if the song changes. */
    private var loadingJob: Job? = null

    init {
        scope.launch {
            _lrcData.collect { data ->
                _detectedSections.value = data?.let { FelicityLyricsAdapter.sections(it).sections }.orEmpty()
            }
        }
        // Watch the playback queue — whenever a new song starts, reload lyrics automatically.
        scope.launch {
            MediaPlaybackManager.songPositionFlow.collect {
                if (MediaPlaybackManager.getCurrentOnlineTrack() == null) {
                    loadLrcData()
                }
            }
        }
        scope.launch {
            MediaPlaybackManager.currentOnlineTrackFlow.collect {
                loadLrcData()
            }
        }
    }

    /**
     * Picks the best parser for the given raw lyrics string.
     * Word-sync LRC wins first, then standard LRC, then plain text as a last resort.
     */
    private fun selectParser(content: String): ILyricsParser = when {
        WordLrcParser().canParse(content) -> WordLrcParser()
        LrcParser().canParse(content) -> LrcParser()
        else -> TxtParser()
    }

    /**
     * Loads lyrics for the currently playing song, or skips if they are already loaded.
     *
     * The song reference is captured at the call site so that a fast song change cannot
     * trick the coroutine into loading the wrong file. Every emission into [_lrcData] is
     * guarded by a stale-check — if the song changed before the result was ready, the
     * result is silently discarded and the next [loadLrcData] call wins.
     */
    fun loadLrcData() {
        // Capture both the song/online track and its path NOW — do not re-read them inside the coroutine.
        val onlineTrack = MediaPlaybackManager.getCurrentOnlineTrack()
        val currentSong = if (onlineTrack == null) MediaPlaybackManager.getCurrentSong() else null
        val currentSongPath = onlineTrack?.let { "online:${it.source}:${it.id}" } ?: currentSong?.uri

        // Already loaded (or loading) this exact song — nothing to do.
        if (currentSongPath != null && currentSongPath == lastLoadedPath) {
            if (loadingJob?.isActive == true) {
                Log.d(TAG, "loadLrcData() skipped – already loading '${onlineTrack?.title ?: currentSong?.title}'.")
                return
            }
            if (_lrcData.value != null && !_lrcData.value!!.isEmpty) {
                Log.d(TAG, "loadLrcData() skipped – lyrics already ready for '${onlineTrack?.title ?: currentSong?.title}'.")
                return
            }
        }

        // Bake any unsaved sync adjustment for the outgoing song before resetting state.
        persistSyncAdjustment()

        // Cancel the in-flight job for the previous song (cooperative cancellation).
        loadingJob?.cancel()

        // Plant our flag — any result that arrives after this for a different path is stale.
        lastLoadedPath = currentSongPath
        _availableVersions.value = emptyList()

        // Fresh slate for sync state on the new song.
        syncOffset = 0L
        pendingSyncDeltaMs = 0L
        _syncOffsetMs.value = 0L
        bakedLrcData = null
        syncSaveHandler.removeCallbacks(syncSaveRunnable)

        // No song is playing at all — mark empty and stop here.
        if (currentSong == null && onlineTrack == null) {
            _lrcData.value = LrcData()
            return
        }

        val path = currentSongPath ?: return

        loadingJob = scope.launch {
            try {
                if (onlineTrack != null) {
                    doLoadOnline(onlineTrack, path)
                } else if (currentSong != null) {
                    doLoad(currentSong, path)
                }
            } catch (e: Exception) {
                e.printStackTrace()
                emitIfStillRelevant(path, LrcData())
            }
        }
    }

    private suspend fun doLoadOnline(track: OnlineTrack, path: String) {
        val title = track.title
        val artist = track.subtitle
        if (title.isBlank()) {
            emitIfStillRelevant(path, LrcData())
            return
        }

        emitIfStillRelevant(path, LrcData())

        fetchAndSaveLrc(
                trackName = title,
                artistName = artist,
                audioPath = path,
                songPath = path
        )
    }

    fun setOnlineLyrics(rawLrc: String) {
        try {
            val parsed = LrcParser().parse(rawLrc)
            _lrcData.value = parsed
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    /**
     * Runs the actual file-read / network-fetch sequence for [song].
     * [songPath] is used as the "load token" — every [_lrcData] emission is guarded
     * so that a late-arriving result for an old song never stomps on the current one.
     */
    private suspend fun doLoad(song: Audio, songPath: String) {
        // Step 1: Try the .lrc sidecar (fastest — local disk).
        val lrcContent = withContext(Dispatchers.IO) {
            lrcRepository.loadLrcFromFile(song.uri).getOrNull()
        }

        if (!isStillRelevant(songPath)) return

        if (lrcContent != null) {
            Log.d(TAG, "LRC sidecar found for '${song.title}'.")
            parseThenEmit(lrcContent, songPath, fallback = LrcData()) { content ->
                selectParser(content).parse(content)
            }
            maybeUpgradeToWordSynced(song, songPath)
            return
        }

        // Step 2: Try the .txt sidecar (plain-text lyrics, also local).
        val txtContent = withContext(Dispatchers.IO) {
            lrcRepository.loadTxtFromFile(song.uri).getOrNull()
        }

        if (!isStillRelevant(songPath)) return

        if (!txtContent.isNullOrBlank()) {
            Log.d(TAG, "TXT sidecar found for '${song.title}'.")
            parseThenEmit(txtContent, songPath, fallback = LrcData()) { content ->
                TxtParser().parse(content)
            }
            return
        }

        // Step 2.5: Try embedded tag lyrics in file tags (USLT, ©lyr, Vorbis LYRICS via TagLib).
        val embeddedContent = withContext(Dispatchers.IO) {
            LyricsMetaHelper.extractEmbeddedLyrics(context, song.uri)
        }

        if (!isStillRelevant(songPath)) return

        if (!embeddedContent.isNullOrBlank()) {
            Log.d(TAG, "Embedded tag lyrics found for '${song.title}'.")
            parseThenEmit(embeddedContent, songPath, fallback = LrcData()) { content ->
                selectParser(content).parse(content)
            }
            maybeUpgradeToWordSynced(song, songPath)
            return
        }

        // Step 3: Nothing local — try the internet if the user opted in.
        if (LyricsPreferences.isAutoDownloadLyrics()) {
            Log.d(TAG, "No local lyrics for '${song.title}', fetching online.")

            // post an empty data first
            emitIfStillRelevant(songPath, LrcData())

            fetchAndSaveLrc(
                    trackName = song.title ?: song.name,
                    artistName = song.artist ?: "",
                    audioPath = song.uri,
                    songPath = songPath
            )
        } else {
            Log.d(TAG, "No local lyrics and auto-download is off for '${song.title}'.")
            emitIfStillRelevant(songPath, LrcData())
        }
    }

    /**
     * Parses [content] on the default dispatcher, then emits the result — or [fallback]
     * if parsing blows up — provided [songPath] is still the active load target.
     */
    private suspend fun parseThenEmit(
            content: String,
            songPath: String,
            fallback: LrcData,
            parse: suspend (String) -> LrcData
    ) {
        try {
            var parsed = withContext(Dispatchers.Default) {
                val data = parse(content)
                if (LyricsPreferences.isAutoRomanizeLyrics()) addAutomaticRomanization(data) else data
            }
            parsed = applyStudioTranslation(parsed)
            emitIfStillRelevant(songPath, parsed)
        } catch (e: LyricsParseException) {
            e.printStackTrace()
            emitIfStillRelevant(songPath, fallback)
        }
    }


    /** Applies opt-in line translations while preserving original text and timestamps. */
    private suspend fun applyStudioTranslation(data: LrcData): LrcData {
        if (!LyricsPreferences.isStudioTranslationEnabled() || data.isEmpty) return data
        return coroutineScope {
            val translated = LrcData()
            data.metadata.forEach { (key, value) -> translated.addMetadata(key, value) }
            val entries = data.entries
            val semaphore = Semaphore(4)
            val translations = withTimeoutOrNull(30_000L) {
                entries.take(80).map { entry ->
                    async { semaphore.withPermit { multiProviderLyricsEngine.translateLine(entry.text).orEmpty() } }
                }.awaitAll()
            }.orEmpty()
            entries.forEachIndexed { index, entry ->
                val translation = translations.getOrNull(index).orEmpty()
                translated.addEntry(LrcEntry(
                    entry.timeInMillis, entry.text, entry.romanization,
                    entry.adlib?.takeIf { it.isNotBlank() } ?: translation, entry.words
                ))
            }
            translated
        }
    }

    /** Adds generated romanization only where the lyric source did not provide one. */
    private fun addAutomaticRomanization(data: LrcData): LrcData {
        val enriched = LrcData()
        data.entries.forEach { entry ->
            val romanization = entry.romanization ?: LyricsRomanizer.romanize(entry.text)
            enriched.addEntry(
                LrcEntry(
                    entry.timeInMillis,
                    entry.text,
                    romanization,
                    entry.adlib,
                    entry.words
                )
            )
        }
        return enriched
    }

    /**
     * Emits [data] into [_lrcData] only if [songPath] still matches [lastLoadedPath].
     * Stale results from cancelled-but-not-yet-dead coroutines are quietly dropped here.
     */
    private fun emitIfStillRelevant(songPath: String?, data: LrcData) {
        if (isStillRelevant(songPath)) {
            _lrcData.value = data
        } else {
            Log.d(TAG, "Discarding stale lyrics result for path='$songPath' (current='$lastLoadedPath').")
        }
    }

    /**
     * Returns true when [songPath] is still the song we should be loading for.
     * False means the song changed while we were busy — our result is garbage now.
     */
    private fun isStillRelevant(songPath: String?): Boolean = (songPath == lastLoadedPath)

    /**
     * Forces a fresh load even when the same song is already loaded.
     * Call this after saving a new .lrc file so the view picks up the change.
     */
    fun reloadLrcData() {
        loadingJob?.cancel()
        loadingJob = null
        lastLoadedPath = null
        loadLrcData()
    }

    /**
     * Triggers a fresh load only when the current song genuinely has no lyrics.
     * Handy when the app returns to the foreground — if lyrics are already loaded,
     * there's nothing to do; if not, we give it another shot.
     */
    fun refreshIfNoLyrics() {
        val currentLrc = _lrcData.value
        if (currentLrc != null && currentLrc.isEmpty) {
            reloadLrcData()
        }
    }

    /**
     * Fetches and ranks lyric candidates from multiple providers, saves the selected result as a sidecar, and emits it.
     * This is the only place that updates [loadingStatus] — local reads are too fast
     * to be worth announcing.
     *
     * [songPath] is the load token; the result is discarded if the song has changed
     * by the time the network round-trip finishes.
     */
    private suspend fun fetchAndSaveLrc(
            trackName: String,
            artistName: String,
            audioPath: String,
            songPath: String
    ) {
        _loadingStatus.value = LyricsLoadingStatus.Searching(trackName)
        val candidates = multiProviderLyricsEngine.search(trackName, artistName)
        if (!isStillRelevant(songPath)) {
            _loadingStatus.value = LyricsLoadingStatus.Idle
            return
        }
        _availableVersions.value = candidates
        val preferredId = multiProviderLyricsEngine.selectedId(trackName, artistName)
        val chosen = candidates.firstOrNull { it.id == preferredId } ?: candidates.firstOrNull()
        if (chosen == null) {
            emitIfStillRelevant(songPath, LrcData())
            _loadingStatus.value = LyricsLoadingStatus.Idle
            return
        }
        applyLyricsCandidate(chosen, audioPath, songPath, trackName, artistName, rememberSelection = false)
        _loadingStatus.value = LyricsLoadingStatus.Idle
    }

    /**
     * When the lyrics loaded from disk are only line-synced, looks (once per song, and only if the
     * user enabled auto-download and never picked a version by hand) for a word-by-word version of
     * the same lyrics and swaps it in, saving it so it is cached from then on.
     */
    private suspend fun maybeUpgradeToWordSynced(song: Audio, songPath: String) {
        if (!LyricsPreferences.isAutoDownloadLyrics()) return
        val current = _lrcData.value ?: return
        if (current.isEmpty || current.entries.any { it.hasWordSync() }) return
        val title = song.title ?: song.name
        val artist = song.artist ?: ""
        if (multiProviderLyricsEngine.selectedId(title, artist) != null) return
        if (multiProviderLyricsEngine.wordUpgradeChecked(title, artist)) return
        val candidates = try {
            multiProviderLyricsEngine.search(title, artist)
        } catch (e: Exception) {
            return
        }
        if (!isStillRelevant(songPath) || candidates.isEmpty()) return
        multiProviderLyricsEngine.markWordUpgradeChecked(title, artist)
        val best = candidates.firstOrNull { it.wordSynced && it.synced && it.confidence >= 85 } ?: return
        // Guard against replacing a hand-edited file with a different lyric: line counts must be close.
        val existing = current.entries.size.coerceAtLeast(1)
        if (kotlin.math.abs(best.lineCount - existing) > existing / 5 + 2) return
        _availableVersions.value = candidates
        applyLyricsCandidate(best, song.uri, songPath, title, artist, rememberSelection = false)
    }

    /** Select a different provider/version without restarting playback. */
    fun selectLyricsVersion(index: Int) {
        val candidate = _availableVersions.value.getOrNull(index) ?: return
        val online = MediaPlaybackManager.getCurrentOnlineTrack()
        val song = if (online == null) MediaPlaybackManager.getCurrentSong() else null
        val path = online?.let { "online:${it.source}:${it.id}" } ?: song?.uri ?: return
        val audioPath = path
        val title = online?.title ?: song?.title.orEmpty()
        val artist = online?.subtitle ?: song?.artist.orEmpty()
        scope.launch {
            applyLyricsCandidate(candidate, audioPath, path, title, artist, rememberSelection = true)
        }
    }

    private suspend fun applyLyricsCandidate(
        candidate: MultiProviderLyricsEngine.Candidate,
        audioPath: String,
        songPath: String,
        title: String,
        artist: String,
        rememberSelection: Boolean
    ) {
        if (!isStillRelevant(songPath)) return
        _loadingStatus.value = LyricsLoadingStatus.Downloading(title)
        try {
            val raw = candidate.rawLyrics.ifBlank { candidate.plainLyrics }
            var parsed = withContext(Dispatchers.Default) {
                if (candidate.synced) selectParser(raw).parse(raw) else TxtParser().parse(raw)
            }
            if (candidate.lineExtras.isNotEmpty()) {
                val extrasByTime = candidate.lineExtras.associateBy { it.timeMs }
                val enriched = LrcData()
                parsed.metadata.forEach { (key, value) -> enriched.addMetadata(key, value) }
                parsed.entries.forEach { entry ->
                    val extra = extrasByTime[entry.timeInMillis]
                    enriched.addEntry(LrcEntry(
                        entry.timeInMillis, entry.text,
                        extra?.romanization?.takeIf { it.isNotBlank() } ?: entry.romanization,
                        extra?.translation?.takeIf { it.isNotBlank() } ?: entry.adlib,
                        extra?.words?.takeIf { it.isNotEmpty() } ?: entry.words
                    ))
                }
                parsed = enriched
            }
            // Persist word timings too, otherwise the cached .lrc silently degrades to line-by-line.
            val toSave = if (candidate.synced && parsed.entries.any { it.hasWordSync() }) {
                val wordsOnly = LrcData()
                parsed.entries.forEach { e -> wordsOnly.addEntry(LrcEntry(e.timeInMillis, e.text, null, null, e.words)) }
                wordsOnly.toLrcString()
            } else raw
            if (LyricsPreferences.isAutoRomanizeLyrics()) parsed = addAutomaticRomanization(parsed)
            parsed = applyStudioTranslation(parsed)
            if (!isStillRelevant(songPath)) return
            if (rememberSelection) multiProviderLyricsEngine.select(title, artist, candidate)
            if (audioPath.startsWith("content://")) {
                withContext(Dispatchers.IO) {
                    if (candidate.synced) lrcRepository.saveLrcToFile(toSave, audioPath)
                    else lrcRepository.saveTxtToFile(toSave, audioPath)
                    if (LyricsPreferences.isAutoEmbedLyrics()) {
                        lrcRepository.embedLyricsInFile(audioPath, toSave)
                            .onFailure { Log.w(TAG, "Auto-embed failed", it) }
                    }
                }
            }
            emitIfStillRelevant(songPath, parsed)
        } catch (e: Exception) {
            Log.w(TAG, "Could not parse selected lyrics version from ${candidate.provider}", e)
            emitIfStillRelevant(songPath, LrcData())
        } finally {
            _loadingStatus.value = LyricsLoadingStatus.Idle
        }
    }

    /**
     * Shifts the lyrics sync by [deltaMs] milliseconds so the highlighted line
     * catches up to — or backs off from — the music.
     *
     * After [SYNC_SAVE_DEBOUNCE_MS] ms of silence the accumulated offset is baked
     * into the .lrc file on disk and the in-memory offset resets to zero so the
     * next load starts clean.
     *
     * Positive = later line highlights (fixes lagging lyrics).
     * Negative = earlier line highlights (fixes lyrics running ahead).
     */
    fun seekBy(deltaMs: Long) {
        val current = _lrcData.value
        if (current == null || current.isEmpty) return

        pendingSyncDeltaMs += deltaMs
        syncOffset = pendingSyncDeltaMs
        _syncOffsetMs.value = pendingSyncDeltaMs

        syncSaveHandler.removeCallbacks(syncSaveRunnable)
        syncSaveHandler.postDelayed(syncSaveRunnable, SYNC_SAVE_DEBOUNCE_MS)
    }

    /**
     * Bakes the accumulated sync offset into the .lrc file on disk and updates
     * the in-memory [_lrcData] to use the corrected timestamps.
     *
     * Updating [_lrcData] here is intentional — without it the view would lose
     * the correction the moment [_syncOffsetMs] resets to zero, making the
     * lyrics jump by twice the intended amount.
     */
    private fun persistSyncAdjustment() {
        val delta = pendingSyncDeltaMs
        if (delta == 0L) return

        if (MediaPlaybackManager.getCurrentOnlineTrack() != null) return

        val base = bakedLrcData ?: _lrcData.value ?: return
        val currentSong = MediaPlaybackManager.getCurrentSong() ?: return

        // Positive offset means "show a later line" → timestamps must move earlier.
        val baked = base.shiftTimestamps(-delta)

        scope.launch(Dispatchers.IO) {
            val result = lrcRepository.saveLrcToFile(baked.toLrcString(), currentSong.uri)
            result.onSuccess { Log.d(TAG, "Sync offset ${delta}ms baked and saved to ${it.path}.") }
                .onFailure { Log.e(TAG, "Failed to persist sync adjustment.", it) }
        }

        bakedLrcData = baked
        pendingSyncDeltaMs = 0L
        _lrcData.value = baked
        _syncOffsetMs.value = 0L
        syncOffset = 0L
    }

    private val _embedResult = MutableStateFlow<EmbedLyricsResult?>(null)

    /**
     * Result of the most recent [embedLyricsIntoFile] call, or `null` when idle /
     * already consumed. The UI should observe this, show a toast/snackbar, then
     * call [clearEmbedResult] so the same result doesn't re-fire (e.g. on rotation).
     */
    val embedResult: StateFlow<EmbedLyricsResult?> = _embedResult.asStateFlow()

    /**
     * Embeds the currently loaded lyrics directly into the playing song's file tags
     * via TagLib, converting the in-memory [LrcData] back to LRC text first so any
     * sync-offset correction the user already applied is included.
     *
     * This is always an explicit action — call it only in response to the user
     * tapping a confirmed "Embed into file" action, never automatically. Automatic
     * embedding would risk baking an occasional wrong online match permanently
     * into the file itself, instead of just a disposable `.lrc` sidecar.
     *
     * Posts the outcome to [embedResult] for the UI to observe.
     */
    fun embedLyricsIntoFile() {
        if (MediaPlaybackManager.getCurrentOnlineTrack() != null) {
            _embedResult.value = EmbedLyricsResult.Error("Cannot embed lyrics into an online track.")
            return
        }

        val currentSong = MediaPlaybackManager.getCurrentSong()
        if (currentSong == null) {
            _embedResult.value = EmbedLyricsResult.Error("No song is currently playing.")
            return
        }

        val currentLrc = _lrcData.value
        if (currentLrc == null || currentLrc.isEmpty) {
            _embedResult.value = EmbedLyricsResult.Error("No lyrics loaded for this song.")
            return
        }

        scope.launch {
            val lyricsText = withContext(Dispatchers.Default) { currentLrc.toLrcString() }
            val result = lrcRepository.embedLyricsInFile(currentSong.uri, lyricsText)
            result.onSuccess {
                Log.d(TAG, "Embedded lyrics into file for '${currentSong.title}'.")
                _embedResult.value = EmbedLyricsResult.Success
            }.onFailure { e ->
                Log.e(TAG, "Failed to embed lyrics into file.", e)
                _embedResult.value = EmbedLyricsResult.Error(e.message ?: "Failed to embed lyrics.")
            }
        }
    }

    /** Clears the last [embedResult] so it doesn't get re-observed (e.g. after a config change). */
    fun clearEmbedResult() {
        _embedResult.value = null
    }

    /**
     * Deletes the .lrc sidecar for the currently playing song and clears the lyrics view.
     *
     * @param onSuccess optional callback that fires on the main thread after deletion.
     */
    fun deleteLrc(onSuccess: (() -> Unit)? = null) {
        val onlineTrack = MediaPlaybackManager.getCurrentOnlineTrack()
        if (onlineTrack != null) {
            _lrcData.value = LrcData()
            pendingSyncDeltaMs = 0L
            syncOffset = 0L
            _syncOffsetMs.value = 0L
            bakedLrcData = null
            syncSaveHandler.removeCallbacks(syncSaveRunnable)
            onSuccess?.invoke()
            return
        }

        val currentSong = MediaPlaybackManager.getCurrentSong() ?: return

        scope.launch {
            withContext(Dispatchers.IO) { lrcRepository.deleteLrcFile(currentSong.uri) }

            _lrcData.value = LrcData()
            pendingSyncDeltaMs = 0L
            syncOffset = 0L
            _syncOffsetMs.value = 0L
            bakedLrcData = null
            syncSaveHandler.removeCallbacks(syncSaveRunnable)

            onSuccess?.invoke()
        }
    }

    companion object {
        private const val SYNC_SAVE_DEBOUNCE_MS = 1500L
    }
}

/**
 * Describes what the lyrics loader is doing at any given moment.
 *
 * [Idle] is the quiet default — nothing happening, nothing to show.
 * [Searching] and [Downloading] only appear when talking to the internet.
 */
sealed class LyricsLoadingStatus {

    /** Calm and quiet — no network activity in progress. */
    data object Idle : LyricsLoadingStatus()

    /**
     * Asking LrcLib whether it has lyrics for this track.
     * @param trackName the song being searched, so the UI can display it.
     */
    data class Searching(val trackName: String) : LyricsLoadingStatus()

    /**
     * A match was found and the lyrics are being written to disk.
     * @param trackName the song whose lyrics are being saved.
     */
    data class Downloading(val trackName: String) : LyricsLoadingStatus()
}

/**
 * Outcome of [LyricsManager.embedLyricsIntoFile], for the UI to react to
 * (typically with a toast or snackbar) and then clear via [LyricsManager.clearEmbedResult].
 */
sealed class EmbedLyricsResult {
    /** The lyrics were written into the file's tags successfully. */
    data object Success : EmbedLyricsResult()

    /** Something went wrong; [message] is safe to show directly to the user. */
    data class Error(val message: String) : EmbedLyricsResult()
}
