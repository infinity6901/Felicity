package app.simple.felicity.managers

import android.content.Context
import androidx.core.content.edit
import app.simple.felicity.decorations.lrc.model.LrcData
import app.simple.felicity.decorations.lrc.model.LrcEntry
import app.simple.felicity.decorations.lrc.model.WordEntry
import app.simple.felicity.decorations.lrc.parser.LrcParser
import app.simple.felicity.lyricsanalysis.lyrics.LyricsPhraseMatcher
import app.simple.felicity.preferences.LyricsPreferences
import app.simple.felicity.repository.factories.TaggedSocketFactory
import app.simple.felicity.repository.models.LrcLibResponse
import app.simple.felicity.repository.repositories.LrcRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject

private val WORD_TIME_TAG = Regex("<\\d{1,3}:\\d{2}[.:]\\d{1,3}>")

/** Multi-source lyrics search with ranked candidates and durable manual selection. */
@Singleton
class MultiProviderLyricsEngine @Inject constructor(
    private val repository: LrcRepository,
    @ApplicationContext private val context: Context
) {
    data class PhraseHit(val title: String, val artist: String, val snippet: String, val score: Float, val provider: String)

    data class LineExtras(
        val timeMs: Long,
        val translation: String = "",
        val romanization: String = "",
        val words: List<WordEntry> = emptyList()
    )

    data class Candidate(
        val id: String,
        val provider: String,
        val title: String,
        val artist: String,
        val synced: Boolean,
        val confidence: Int,
        val rawLyrics: String,
        val plainLyrics: String = "",
        val lineExtras: List<LineExtras> = emptyList(),
        val durationSec: Int = 0
    ) {
        /** True when the lyrics carry per-word timing (enhanced LRC tags or provider word data). */
        val wordSynced: Boolean
            get() = lineExtras.any { it.words.isNotEmpty() } || WORD_TIME_TAG.containsMatchIn(rawLyrics)

        /** Number of non-empty lyric lines. */
        val lineCount: Int
            get() = rawLyrics.ifBlank { plainLyrics }.lineSequence().count { it.isNotBlank() }

        /**
         * Returns LRC text with inline `<mm:ss.mmm>` word timestamps baked in when
         * [lineExtras] carry per-word timings, so saving or passing the string preserves
         * word-by-word sync.
         */
        fun toSaveableLrc(): String {
            if (!synced) return plainLyrics.ifBlank { rawLyrics }
            if (lineExtras.none { it.words.isNotEmpty() } || WORD_TIME_TAG.containsMatchIn(rawLyrics)) {
                return rawLyrics
            }
            val parsed = runCatching { LrcParser().parse(rawLyrics) }.getOrNull() ?: return rawLyrics
            if (parsed.isEmpty) return rawLyrics
            val extrasByTime = lineExtras.associateBy { it.timeMs }
            val enriched = LrcData()
            parsed.metadata.forEach { (key, value) -> enriched.addMetadata(key, value) }
            parsed.entries.forEachIndexed { index, entry ->
                val extra = if (entry.timeInMillis >= 0L) {
                    extrasByTime[entry.timeInMillis]
                } else {
                    lineExtras.getOrNull(index)
                }
                enriched.addEntry(
                    LrcEntry(
                        entry.timeInMillis,
                        entry.text,
                        null,
                        null,
                        extra?.words?.takeIf { it.isNotEmpty() } ?: entry.words
                    )
                )
            }
            return enriched.toLrcString()
        }
    }

    private val client = OkHttpClient.Builder()
        .connectTimeout(4, TimeUnit.SECONDS)
        .readTimeout(7, TimeUnit.SECONDS)
        .callTimeout(9, TimeUnit.SECONDS)
        .socketFactory(TaggedSocketFactory(0x4C595243))
        .build()

    suspend fun search(title: String, artist: String): List<Candidate> = supervisorScope {
        if (title.isBlank()) return@supervisorScope emptyList()
        val lrclib = async(Dispatchers.IO) {
            if (LyricsPreferences.isSourceEnabled("LRCLib")) searchLrcLib(title, artist) else emptyList()
        }
        val mirrors = async(Dispatchers.IO) {
            if (LyricsPreferences.isSourceEnabled("LyricsPlus")) withTimeoutOrNull(12_000L) { searchLyricsPlus(title, artist) }.orEmpty() else emptyList()
        }
        val better = async(Dispatchers.IO) {
            if (LyricsPreferences.isSourceEnabled("Better Lyrics")) withTimeoutOrNull(12_000L) { searchBetterLyrics(title, artist) }.orEmpty() else emptyList()
        }
        val fromLrclib = runCatching { lrclib.await() }.getOrDefault(emptyList())
        val fromMirrors = runCatching { mirrors.await() }.getOrDefault(emptyList())
        val fromBetter = runCatching { better.await() }.getOrDefault(emptyList())
        val fetched = (fromLrclib + fromMirrors + fromBetter)
            .distinctBy { it.provider + ":" + it.rawLyrics.ifBlank { it.plainLyrics }.hashCode() }
        android.util.Log.d("LyricsSearch", "\"$title\" / \"$artist\": LRCLib=${fromLrclib.size} " +
            "LyricsPlus=${fromMirrors.size} BetterLyrics=${fromBetter.size}; word-by-word=${fetched.count { it.wordSynced }}")
        val candidates = fetched.ifEmpty { cachedCandidates(title, artist) }
        if (fetched.isNotEmpty()) saveCandidates(title, artist, fetched)
        candidates.sortedWith(compareByDescending<Candidate> { selectedId(title, artist) == it.id }
            .thenByDescending { it.wordSynced }
            .thenBy { LyricsPreferences.sourceRank(it.provider) }
            .thenByDescending { it.confidence }
            .thenByDescending { it.synced })
    }

    /**
     * Free-text search typed by the user. Uses cleaned/split query pairs and merges unique
     * results while keeping word-by-word timed lyrics prioritized at the top.
     */
    suspend fun searchManual(query: String): List<Candidate> = supervisorScope {
        val q = query.trim()
        if (q.isBlank()) return@supervisorScope emptyList()
        val attempts = buildList {
            add(q to "")
            val parts = q.split(Regex("\\s+[-–—|/｜⧸／•·]+\\s+|\\s*[|/｜⧸／•·]\\s*"), limit = 2).map { it.trim() }
            if (parts.size == 2 && parts.all { it.isNotBlank() }) {
                add(parts[1] to parts[0])
                add(parts[0] to parts[1])
            }
        }.distinct()
        attempts.map { (title, artist) -> async { runCatching { search(title, artist) }.getOrDefault(emptyList()) } }
            .flatMap { it.await() }
            .distinctBy { it.provider + ":" + it.rawLyrics.ifBlank { it.plainLyrics }.hashCode() }
            .sortedWith(compareByDescending<Candidate> { it.wordSynced }
                .thenBy { LyricsPreferences.sourceRank(it.provider) }
                .thenByDescending { it.confidence }
                .thenByDescending { it.synced })
    }

    /** True once the word-by-word upgrade check has run for this song. */
    fun wordUpgradeChecked(title: String, artist: String): Boolean =
        prefs().getBoolean("word_checked:" + selectionKey(title, artist), false)

    fun markWordUpgradeChecked(title: String, artist: String) {
        prefs().edit { putBoolean("word_checked:" + selectionKey(title, artist), true) }
    }

    /** Search local cached lyrics and LRCLib candidates using a lyric phrase, then verify against lyric text. */
    suspend fun searchPhraseAcrossTracks(phrase: String): List<PhraseHit> = withContext(Dispatchers.IO) {
        if (!LyricsPhraseMatcher.isSearchable(phrase)) return@withContext emptyList()
        val localSongs = runCatching {
            app.simple.felicity.repository.database.instances.AudioDatabase.getInstance(context)
                .audioDao()?.getAllAudioList().orEmpty()
        }.getOrDefault(emptyList())

        val localHits = localSongs.mapNotNull { audio ->
            val raw = repository.loadLrcFromFile(audio.uri).getOrNull()
                ?: repository.loadTxtFromFile(audio.uri).getOrNull()
                ?: return@mapNotNull null
            if (raw.isBlank()) return@mapNotNull null
            val lines = raw.lineSequence().map { line ->
                line.replace(Regex("^\\s*\\[[^]]+]\\s*"), "").trim()
            }.filter(String::isNotBlank).toList()
            val match = LyricsPhraseMatcher.match(phrase, lines) ?: return@mapNotNull null
            if (match.score < LyricsPhraseMatcher.VERIFIED_SCORE) return@mapNotNull null
            PhraseHit(
                title = audio.title ?: audio.name,
                artist = audio.artist.orEmpty(),
                snippet = lines.getOrNull(match.lineIndex).orEmpty().take(110),
                score = match.score + 0.05f,
                provider = "Library"
            )
        }

        val candidates = repository.searchLyrics(phrase, "").getOrNull().orEmpty()
        val remoteHits = candidates.mapNotNull { candidate ->
            val raw = candidate.syncedLyrics.orEmpty().ifBlank { candidate.plainLyrics.orEmpty() }
            if (raw.isBlank()) return@mapNotNull null
            val lines = raw.lineSequence().map { line ->
                line.replace(Regex("^\\s*\\[[^]]+]\\s*"), "").trim()
            }.filter(String::isNotBlank).toList()
            val match = LyricsPhraseMatcher.match(phrase, lines) ?: return@mapNotNull null
            if (match.score < LyricsPhraseMatcher.VERIFIED_SCORE) return@mapNotNull null
            PhraseHit(candidate.trackName, candidate.artistName,
                lines.getOrNull(match.lineIndex).orEmpty().take(110), match.score, "LRCLib")
        }

        (localHits + remoteHits)
            .distinctBy { "${it.title.lowercase()}|${it.artist.lowercase()}" }
            .sortedByDescending { it.score }
            .take(12)
    }

    private suspend fun searchLrcLib(title: String, artist: String): List<Candidate> = withContext(Dispatchers.IO) {
        repository.searchLyrics(title, artist).getOrNull().orEmpty().mapNotNull { item: LrcLibResponse ->
            val raw = item.syncedLyrics.orEmpty()
            val plain = item.plainLyrics.orEmpty()
            if (raw.isBlank() && plain.isBlank()) return@mapNotNull null
            val hasWordSync = WORD_TIME_TAG.containsMatchIn(raw)
            Candidate(
                id = "lrclib:${item.id}",
                provider = "LRCLib",
                title = item.trackName,
                artist = item.artistName,
                synced = raw.isNotBlank(),
                confidence = when {
                    hasWordSync -> 95
                    raw.isNotBlank() -> 92
                    else -> 68
                },
                rawLyrics = raw,
                plainLyrics = plain,
                durationSec = item.duration.toInt()
            )
        }
    }

    private suspend fun searchLyricsPlus(title: String, artist: String): List<Candidate> = withContext(Dispatchers.IO) {
        val queryPairs = repository.extractQueryPairs(title, artist).ifEmpty { listOf(title to artist) }.take(4)
        for ((qTitle, qArtist) in queryPairs) {
            val found = withTimeoutOrNull(4_500L) { searchLyricsPlusSingle(qTitle, qArtist) }.orEmpty()
            if (found.isNotEmpty()) return@withContext found
        }
        emptyList()
    }

    private suspend fun searchLyricsPlusSingle(title: String, artist: String): List<Candidate> = coroutineScope {
        val servers = listOf(
            "https://lyricsplus.binimum.org",
            "https://lyricsplus.atomix.one",
            "https://lyricsplus.prjktla.my.id",
            "https://lyricsplus-seven.vercel.app"
        )
        // Race every mirror; return as soon as the first non-empty response arrives.
        val results = kotlinx.coroutines.channels.Channel<List<Candidate>>(servers.size)
        val jobs = servers.map { server ->
            launch {
                val url = okhttp3.HttpUrl.Builder().scheme("https")
                    .host(server.removePrefix("https://").substringBefore('/'))
                    .addPathSegments("v2/lyrics/get")
                    .addQueryParameter("title", title)
                    .apply { if (artist.isNotBlank()) addQueryParameter("artist", artist) }
                    .build()
                val r = runCatching {
                    client.newCall(Request.Builder().url(url).header("Accept", "application/json").build())
                        .execute().use { response ->
                            if (!response.isSuccessful) emptyList() else parseLyricsPlus(response.body.string(), title, artist)
                        }
                }.getOrDefault(emptyList())
                results.trySend(r)
            }
        }
        var found: List<Candidate> = emptyList()
        for (i in servers.indices) {
            val r = results.receive()
            if (r.isNotEmpty()) {
                found = r
                break
            }
        }
        jobs.forEach { it.cancel() }
        found
    }

    /**
     * Better Lyrics (lyrics-api.boidu.dev): lyrics timed word by word, answered as TTML.
     * Adapted from InterTune's provider (GPL-3.0).
     */
    private suspend fun searchBetterLyrics(title: String, artist: String): List<Candidate> = withContext(Dispatchers.IO) {
        val queryPairs = repository.extractQueryPairs(title, artist).ifEmpty { listOf(title to artist) }.take(4)
        for ((qTitle, qArtist) in queryPairs) {
            val found = searchBetterLyricsSingle(qTitle, qArtist)
            if (found.isNotEmpty()) return@withContext found
        }
        emptyList()
    }

    private fun searchBetterLyricsSingle(title: String, artist: String): List<Candidate> {
        val url = okhttp3.HttpUrl.Builder().scheme("https").host("lyrics-api.boidu.dev")
            .addPathSegment("getLyrics")
            .addQueryParameter("s", title)
            .addQueryParameter("a", artist)
            .build()
        val ttml = runCatching {
            client.newCall(Request.Builder().url(url).header("Accept", "application/json").header("User-Agent", "Felicity").build())
                .execute().use { response ->
                    if (!response.isSuccessful) return@use null
                    JSONObject(response.body.string()).optString("ttml").trim().takeIf { it.isNotEmpty() }
                }
        }.getOrNull() ?: return emptyList()
        val parsed = TtmlParser.parse(ttml)
        if (parsed.isEmpty()) return emptyList()
        val extras = ArrayList<LineExtras>()
        val raw = buildString {
            for (line in parsed) {
                val minutes = line.startMs / 60_000L
                val seconds = (line.startMs % 60_000L) / 1_000.0
                append(String.format(java.util.Locale.ROOT, "[%02d:%06.3f]", minutes, seconds))
                append(line.text).append('\n')
                extras += LineExtras(timeMs = line.startMs, words = line.words.map { WordEntry(it.first, it.second, it.third) })
            }
        }.trim()
        return listOf(Candidate(
            id = "betterlyrics:${raw.hashCode()}", provider = "Better Lyrics",
            title = title, artist = artist, synced = true, confidence = 91,
            rawLyrics = raw, lineExtras = extras
        ))
    }

    private fun parseLyricsPlus(body: String, title: String, artist: String): List<Candidate> {
        val root = runCatching { JSONObject(body) }.getOrNull() ?: return emptyList()
        val lyrics = root.optJSONArray("lyrics") ?: return emptyList()
        if (lyrics.length() == 0) return emptyList()
        val metadata = root.optJSONObject("metadata")
        val resolvedTitle = metadata?.optString("title")?.takeIf { it.isNotBlank() } ?: title
        val resolvedArtist = metadata?.optString("artist")?.takeIf { it.isNotBlank() } ?: artist
        val isSynced = !root.optString("type").equals("None", true)
        val extras = ArrayList<LineExtras>()
        val lines = buildString {
            for (i in 0 until lyrics.length()) {
                val line = lyrics.optJSONObject(i) ?: continue
                val text = line.optString("text").trim()
                if (text.isBlank()) continue
                val ms = line.optLong("time", 0L).coerceAtLeast(0L)
                val words = ArrayList<WordEntry>()
                val syllables = line.optJSONArray("syllabus")
                if (syllables != null) {
                    var searchFrom = 0
                    for (wordIndex in 0 until syllables.length()) {
                        val word = syllables.optJSONObject(wordIndex) ?: continue
                        if (word.optBoolean("isBackground", false)) continue
                        val rawWordText = word.optString("text")
                        if (rawWordText.isEmpty()) continue
                        if (rawWordText.isBlank()) {
                            if (words.isNotEmpty() && !words.last().text.endsWith(" ")) {
                                val last = words.removeAt(words.lastIndex)
                                words += WordEntry(last.startMs, last.endMs, last.text + " ")
                            }
                            continue
                        }
                        val trimmedWord = rawWordText.trim()
                        val matchIdx = text.indexOf(trimmedWord, searchFrom)
                        val hasTrailingSpace = if (matchIdx >= 0) {
                            val endIdx = matchIdx + trimmedWord.length
                            searchFrom = endIdx
                            endIdx < text.length && text[endIdx].isWhitespace()
                        } else {
                            rawWordText.endsWith(" ")
                        }
                        val wordText = if (hasTrailingSpace && !rawWordText.endsWith(" ")) "$trimmedWord " else rawWordText
                        val wordStart = word.optLong("time", ms).coerceAtLeast(0L)
                        val wordEnd = wordStart + word.optLong("duration", 0L).coerceAtLeast(45L)
                        words += WordEntry(wordStart, wordEnd, wordText)
                    }
                }
                extras += LineExtras(
                    timeMs = ms,
                    translation = line.optJSONObject("translation")?.optString("text").orEmpty(),
                    romanization = line.optJSONObject("transliteration")?.optString("text").orEmpty(),
                    words = words
                )
                if (isSynced) {
                    val minutes = ms / 60_000L
                    val seconds = (ms % 60_000L) / 1_000.0
                    append(String.format(java.util.Locale.ROOT, "[%02d:%06.3f]", minutes, seconds))
                }
                append(text).append('\n')
            }
        }.trim()
        if (lines.isBlank()) return emptyList()
        return listOf(Candidate(
            id = "lyricsplus:${lines.hashCode()}", provider = "LyricsPlus",
            title = resolvedTitle, artist = resolvedArtist,
            synced = isSynced, confidence = if (isSynced) 90 else 70,
            rawLyrics = if (isSynced) lines else "", plainLyrics = if (isSynced) "" else lines,
            lineExtras = extras
        ))
    }


    /** Translate one lyric line using Android's current system language as target. */
    suspend fun translateLine(text: String, targetLanguage: String = java.util.Locale.getDefault().language): String? =
        withContext(Dispatchers.IO) {
            if (text.isBlank() || targetLanguage.isBlank()) return@withContext null
            val cacheKey = "translation:${targetLanguage}:${text.hashCode()}"
            val cachedTranslation = prefs().getString(cacheKey, null)
            if (cachedTranslation != null) return@withContext cachedTranslation
            runCatching {
                val url = okhttp3.HttpUrl.Builder().scheme("https").host("translate.googleapis.com")
                    .addPathSegments("translate_a/single")
                    .addQueryParameter("client", "gtx")
                    .addQueryParameter("sl", "auto")
                    .addQueryParameter("tl", targetLanguage)
                    .addQueryParameter("dt", "t")
                    .addQueryParameter("q", text)
                    .build()
                client.newCall(Request.Builder().url(url).get().build()).execute().use { response ->
                    if (!response.isSuccessful) return@use null
                    val json = org.json.JSONArray(response.body.string())
                    val segments = json.optJSONArray(0) ?: return@use null
                    buildString {
                        for (i in 0 until segments.length()) {
                            val segment = segments.optJSONArray(i) ?: continue
                            append(segment.optString(0))
                        }
                    }.takeIf { it.isNotBlank() }?.also { translated ->
                        prefs().edit { putString(cacheKey, translated) }
                    }
                }
            }.getOrNull()
        }


    private fun saveCandidates(title: String, artist: String, candidates: List<Candidate>) {
        val array = org.json.JSONArray()
        candidates.forEach { candidate ->
            array.put(JSONObject().apply {
                put("id", candidate.id); put("provider", candidate.provider)
                put("title", candidate.title); put("artist", candidate.artist)
                put("synced", candidate.synced); put("confidence", candidate.confidence)
                put("raw", candidate.rawLyrics); put("plain", candidate.plainLyrics); put("duration", candidate.durationSec)
                put("extras", org.json.JSONArray().apply {
                    candidate.lineExtras.forEach { extra ->
                        put(JSONObject().apply {
                            put("time", extra.timeMs); put("translation", extra.translation)
                            put("romanization", extra.romanization)
                            put("words", org.json.JSONArray().apply {
                                extra.words.forEach { word -> put(JSONObject().apply {
                                    put("start", word.startMs); put("end", word.endMs); put("text", word.text)
                                }) }
                            })
                        })
                    }
                })
            })
        }
        prefs().edit { putString(cacheKey(title, artist), array.toString()) }
    }

    private fun cachedCandidates(title: String, artist: String): List<Candidate> {
        val raw = prefs().getString(cacheKey(title, artist), null) ?: return emptyList()
        val array = runCatching { org.json.JSONArray(raw) }.getOrNull() ?: return emptyList()
        return buildList {
            for (i in 0 until array.length()) {
                val item = array.optJSONObject(i) ?: continue
                val extrasJson = item.optJSONArray("extras") ?: org.json.JSONArray()
                val extras = buildList {
                    for (extraIndex in 0 until extrasJson.length()) {
                        val extra = extrasJson.optJSONObject(extraIndex) ?: continue
                        val wordsJson = extra.optJSONArray("words") ?: org.json.JSONArray()
                        val words = buildList {
                            for (wordIndex in 0 until wordsJson.length()) {
                                val word = wordsJson.optJSONObject(wordIndex) ?: continue
                                add(WordEntry(word.optLong("start"), word.optLong("end"), word.optString("text")))
                            }
                        }
                        add(LineExtras(extra.optLong("time"), extra.optString("translation"), extra.optString("romanization"), words))
                    }
                }
                add(Candidate(item.optString("id"), item.optString("provider"),
                    item.optString("title"), item.optString("artist"), item.optBoolean("synced"),
                    item.optInt("confidence"), item.optString("raw"), item.optString("plain"), extras,
                    item.optInt("duration")))
            }
        }
    }

    private fun cacheKey(title: String, artist: String) =
        "lyrics_cache:${selectionKey(title, artist)}"

    fun select(title: String, artist: String, candidate: Candidate) {
        prefs().edit { putString(selectionKey(title, artist), candidate.id) }
    }

    fun selectedId(title: String, artist: String): String? =
        prefs().getString(selectionKey(title, artist), null)

    private fun prefs() = context.getSharedPreferences("lyrics_provider_selection", Context.MODE_PRIVATE)
    private fun selectionKey(title: String, artist: String) = "${title.trim().lowercase()}|${artist.trim().lowercase()}"
}
