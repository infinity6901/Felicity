package app.simple.felicity.engine.managers

import android.content.Context
import android.util.Log
import app.simple.felicity.preferences.PlayerPreferences
import app.simple.felicity.preferences.ShufflePreferences
import app.simple.felicity.repository.database.instances.AudioDatabase
import app.simple.felicity.repository.models.Audio
import app.simple.felicity.repository.models.PlaybackQueueEntry
import app.simple.felicity.repository.models.PlaybackState
import app.simple.felicity.repository.models.SavedQueueEntry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Manages persistence and restoration of playback state across ten independent
 * queue slots (0–9).
 *
 * @author Hamza417
 */
object PlaybackStateManager {

    private const val TAG = "PlaybackStateManager"

    /** Number of independent queue slots available to the user (slots 0–9). */
    const val QUEUE_COUNT = 10

    private val saveMutex = kotlinx.coroutines.sync.Mutex()
    private var lastSavedPosition: Int = -1
    private var lastSavedSeek: Long = -1L
    private var lastSavedQueueSize: Int = -1
    private var lastSavedShuffle: Boolean? = null
    private var lastSavedRepeat: Int = -1
    private var lastSavedQueueId: Int = -1
    private var lastSaveTimeMs: Long = 0L

    data class QueueSwitchResult(
            val songs: List<Audio>,
            val lastPosition: Int,
            val lastSeek: Long
    )

    suspend fun saveCurrentPlaybackState(context: Context, logTag: String = TAG): Boolean = saveMutex.let { mutex ->
        mutex.lock()
        try {
            // Always persist the original (unshuffled) queue so we can restore and optionally
            // re-shuffle on the next launch rather than saving an already-shuffled order.
            val songs = MediaPlaybackManager.getOriginalQueue()
            if (songs.isEmpty()) {
                Log.w(logTag, "Songs list is empty, skipping state save")
                return false
            }

            var seek = 0L
            var position = 0

            withContext(Dispatchers.Main) {
                seek = MediaPlaybackManager.getSeekPosition()
                val currentSong = MediaPlaybackManager.getCurrentSong()
                position = if (currentSong != null) {
                    songs.indexOfFirst { it.id == currentSong.id }.coerceAtLeast(0)
                } else {
                    MediaPlaybackManager.getCurrentSongPosition().coerceIn(0, (songs.size - 1).coerceAtLeast(0))
                }
            }

            val activeQueueId = MediaPlaybackManager.getActiveQueueId()
            val repeatMode = PlayerPreferences.getRepeatMode()
            val shuffle = ShufflePreferences.isShuffleEnabled()
            val now = System.currentTimeMillis()

            // Coalesce duplicate saves triggered within 500ms by multiple active MediaFragment instances
            if (now - lastSaveTimeMs < 500L &&
                    position == lastSavedPosition &&
                    songs.size == lastSavedQueueSize &&
                    shuffle == lastSavedShuffle &&
                    repeatMode == lastSavedRepeat &&
                    activeQueueId == lastSavedQueueId &&
                    kotlin.math.abs(seek - lastSavedSeek) < 1000L) {
                return true
            }

            return try {
                val audioDatabase = AudioDatabase.getInstance(context)
                savePlaybackState(
                        db = audioDatabase,
                        queueHash = songs.map { it.hash },
                        index = position,
                        position = seek,
                        shuffle = shuffle,
                        repeat = repeatMode,
                        activeQueueId = activeQueueId
                )
                lastSavedPosition = position
                lastSavedSeek = seek
                lastSavedQueueSize = songs.size
                lastSavedShuffle = shuffle
                lastSavedRepeat = repeatMode
                lastSavedQueueId = activeQueueId
                lastSaveTimeMs = now
                Log.d(
                        logTag, "Playback state saved: position=$position, seek=$seek, " +
                        "queueSize=${songs.size}, shuffle=$shuffle, " +
                        "repeat=$repeatMode, activeQueue=$activeQueueId"
                )
                true
            } catch (e: Exception) {
                Log.e(logTag, "Error saving playback state", e)
                false
            }
        } finally {
            mutex.unlock()
        }
    }

    suspend fun savePlaybackState(
            db: AudioDatabase,
            queueHash: List<Long>,
            index: Int,
            position: Long,
            shuffle: Boolean,
            repeat: Int,
            activeQueueId: Int = 0
    ) {
        if (queueHash.isEmpty()) return

        val clampedIndex = index.coerceIn(0, queueHash.size - 1)
        val currentHash = queueHash.getOrElse(clampedIndex) { 0L }

        val state = PlaybackState(
                index = clampedIndex,
                position = position,
                shuffle = shuffle,
                repeatMode = repeat,
                updatedAt = System.currentTimeMillis(),
                currentHash = currentHash,
                activeQueueId = activeQueueId
        )

        val entries = queueHash.mapIndexed { pos, hash ->
            PlaybackQueueEntry(queuePos = pos, audioHash = hash)
        }

        db.playbackQueueDao().clear()
        db.playbackQueueDao().insertAll(entries)
        db.playbackStateDao().save(state)

        saveQueueToSlot(db, activeQueueId, queueHash, clampedIndex, position)
    }

    suspend fun saveQueueToSlot(
            db: AudioDatabase,
            queueId: Int,
            queueHash: List<Long>,
            position: Int = 0,
            seek: Long = 0L
    ) {
        if (queueHash.isEmpty()) return
        val timestamp = System.currentTimeMillis()
        val clampedPosition = position.coerceIn(0, queueHash.size - 1)

        val entries = queueHash.mapIndexed { pos, hash ->
            SavedQueueEntry(
                    queueId = queueId,
                    queuePos = pos,
                    audioHash = hash,
                    lastPosition = clampedPosition,
                    lastSeek = seek,
                    lastUsed = timestamp
            )
        }

        db.savedQueueDao().clear(queueId)
        db.savedQueueDao().insertAll(entries)
    }

    suspend fun loadQueueFromSlot(
            db: AudioDatabase,
            queueId: Int
    ): MutableList<Audio>? {
        val audios = db.savedQueueDao().getQueuedAudios(queueId)
        return if (audios.isEmpty()) null else audios.toMutableList()
    }

    suspend fun switchToQueue(
            context: Context,
            currentQueueId: Int,
            targetQueueId: Int,
            currentPosition: Int = 0,
            currentSeek: Long = 0L
    ): QueueSwitchResult {
        val db = AudioDatabase.getInstance(context)

        val currentSongs = MediaPlaybackManager.getOriginalQueue()
        if (currentSongs.isNotEmpty()) {
            val activeSong = MediaPlaybackManager.getSongs().getOrNull(currentPosition)
            val originalPos = if (activeSong != null) {
                currentSongs.indexOfFirst { it.id == activeSong.id }.takeIf { it >= 0 } ?: currentPosition
            } else {
                currentPosition
            }
            saveQueueToSlot(db, currentQueueId, currentSongs.map { it.hash }, originalPos, currentSeek)
            Log.d(TAG, "switchToQueue: archived queue $currentQueueId (${currentSongs.size} songs) " +
                    "at position=$originalPos, seek=$currentSeek")
        }

        val targetSongs = loadQueueFromSlot(db, targetQueueId)

        val firstEntry = db.savedQueueDao().getQueue(targetQueueId).firstOrNull()
        val restoredPosition = firstEntry?.lastPosition ?: 0
        val restoredSeek = firstEntry?.lastSeek ?: 0L

        val currentState = fetchPlaybackState(db)
        if (currentState != null) {
            db.playbackStateDao().save(currentState.copy(activeQueueId = targetQueueId))
        } else {
            db.playbackStateDao().save(
                    PlaybackState(activeQueueId = targetQueueId, updatedAt = System.currentTimeMillis())
            )
        }

        if (targetSongs.isNullOrEmpty()) {
            Log.d(TAG, "switchToQueue: queue $targetQueueId is empty, returning empty list")
            return QueueSwitchResult(emptyList(), 0, 0L)
        }

        val clampedPosition = restoredPosition.coerceIn(0, (targetSongs.size - 1).coerceAtLeast(0))

        val entries = targetSongs.mapIndexed { pos, audio ->
            PlaybackQueueEntry(queuePos = pos, audioHash = audio.hash)
        }
        db.playbackQueueDao().clear()
        db.playbackQueueDao().insertAll(entries)

        Log.d(
                TAG, "switchToQueue: switched from queue $currentQueueId to $targetQueueId " +
                "(${targetSongs.size} songs loaded), " +
                "restoredPosition=$clampedPosition, restoredSeek=$restoredSeek"
        )
        return QueueSwitchResult(targetSongs, clampedPosition, restoredSeek)
    }

    suspend fun findFirstEmptySlot(db: AudioDatabase, excludeQueueId: Int = -1): Int? {
        for (i in 0 until QUEUE_COUNT) {
            if (i == excludeQueueId) continue
            val audios = db.savedQueueDao().getQueuedAudios(i)
            if (audios.isEmpty()) return i
        }

        return db.savedQueueDao().getOldestQueueId(excludeQueueId)
    }

    suspend fun getActiveQueueId(db: AudioDatabase): Int {
        return fetchPlaybackState(db)?.activeQueueId ?: 0
    }

    suspend fun fetchPlaybackState(db: AudioDatabase): PlaybackState? {
        return db.playbackStateDao().get()
    }

    suspend fun getAudiosFromQueueIDs(db: AudioDatabase): MutableList<Audio>? {
        val audios = db.playbackQueueDao().getQueuedAudios()
        return if (audios.isEmpty()) null else audios.toMutableList()
    }
}
