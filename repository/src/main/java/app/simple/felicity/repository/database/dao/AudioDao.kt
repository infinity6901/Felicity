package app.simple.felicity.repository.database.dao

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.RawQuery
import androidx.room.Update
import androidx.sqlite.db.SupportSQLiteQuery
import app.simple.felicity.repository.models.Audio
import kotlinx.coroutines.flow.Flow

@Dao
interface AudioDao {
    @Query("SELECT * FROM audio WHERE is_available = 1 ORDER BY title COLLATE NOCASE ASC")
    fun getAllAudio(): Flow<MutableList<Audio>>

    @Query("SELECT * FROM audio WHERE is_available = 1 ORDER BY title COLLATE NOCASE ASC")
    fun getAllAudioList(): MutableList<Audio>

    /** Returns the first available track whose content hash matches [hash], or null if none found. */
    @Query("SELECT * FROM audio WHERE hash = :hash AND is_available = 1 LIMIT 1")
    suspend fun getAudioByHash(hash: Long): Audio?

    /**
     * Returns every row regardless of availability – used by reconcile and dedup passes.
     */
    @Query("SELECT * FROM audio")
    fun getAllAudioListAll(): MutableList<Audio>

    /**
     * Deletes all duplicate rows that share the same URI, keeping only the row whose
     * date_modified is the highest (most recently scanned). If two rows share the same
     * date_modified, the one with the larger id wins.
     */
    @Query("""
        DELETE FROM audio
        WHERE id IN (
            SELECT a.id FROM audio a
            WHERE EXISTS (
                SELECT 1 FROM audio b
                WHERE b.uri = a.uri
                AND (
                    b.date_modified > a.date_modified
                    OR (b.date_modified = a.date_modified AND b.id > a.id)
                )
            )
        )
    """)
    suspend fun deleteStalePathDuplicates()

    // Filtered queries – honour minimum duration (ms) and minimum size (bytes) at query level
    @Query("SELECT * FROM audio WHERE is_available = 1 AND duration >= :minDuration AND size >= :minSize ORDER BY title COLLATE NOCASE ASC")
    fun getFilteredAudio(minDuration: Long, minSize: Long): Flow<MutableList<Audio>>

    @Query("SELECT * FROM audio WHERE is_available = 1 AND duration >= :minDuration AND size >= :minSize ORDER BY title COLLATE NOCASE ASC")
    fun getFilteredAudioList(minDuration: Long, minSize: Long): MutableList<Audio>

    // Get unique artists
    @Query("SELECT * FROM audio WHERE is_available = 1 GROUP BY artist ORDER BY artist COLLATE NOCASE ASC")
    fun getAllArtists(): Flow<MutableList<Audio>>

    // Get unique artists with filtering
    @Query("SELECT * FROM audio WHERE is_available = 1 AND duration >= :minDuration AND size >= :minSize GROUP BY artist ORDER BY artist COLLATE NOCASE ASC")
    fun getFilteredArtists(minDuration: Long, minSize: Long): Flow<MutableList<Audio>>

    // Get unique albums
    @Query("SELECT * FROM audio WHERE is_available = 1 GROUP BY album ORDER BY album COLLATE NOCASE ASC")
    fun getAllAlbums(): Flow<MutableList<Audio>>

    // Get unique albums with filtering
    @Query("SELECT * FROM audio WHERE is_available = 1 AND duration >= :minDuration AND size >= :minSize GROUP BY album ORDER BY album COLLATE NOCASE ASC")
    fun getFilteredAlbums(minDuration: Long, minSize: Long): Flow<MutableList<Audio>>

    // Get all audio files grouped by album for aggregation
    @Query("SELECT * FROM audio WHERE is_available = 1 ORDER BY album COLLATE NOCASE ASC, title COLLATE NOCASE ASC")
    fun getAllAudioForAlbumAggregation(): Flow<MutableList<Audio>>

    // Get all audio files grouped by album for aggregation with filtering
    @Query("SELECT * FROM audio WHERE is_available = 1 AND duration >= :minDuration AND size >= :minSize ORDER BY album COLLATE NOCASE ASC, title COLLATE NOCASE ASC")
    fun getFilteredAudioForAlbumAggregation(minDuration: Long, minSize: Long): Flow<MutableList<Audio>>

    // Get recent audio
    @Query("SELECT * FROM audio WHERE is_available = 1 ORDER BY date_added DESC LIMIT 25")
    fun getRecentAudio(): Flow<MutableList<Audio>>

    // Get recent audio with filtering – returns all songs added in the last 30 days
    @Query("SELECT * FROM audio WHERE is_available = 1 AND duration >= :minDuration AND size >= :minSize AND date_modified >= :minDateModified ORDER BY date_modified DESC")
    fun getFilteredRecentAudio(minDuration: Long, minSize: Long, minDateModified: Long): Flow<MutableList<Audio>>

    // get all audio files by artist name in ascending order
    @Query("SELECT * FROM audio WHERE artist = :artist AND is_available = 1 ORDER BY title COLLATE NOCASE ASC")
    fun getAudioByArtist(artist: String): Flow<MutableList<Audio>>

    // Fetch only the tracks for the specific album
    @Query("""
        SELECT * FROM audio 
        WHERE is_available = 1
        AND album = :albumName 
        AND duration >= :minDuration 
        AND size >= :minSize
    """)
    fun getTracksForAlbum(albumName: String, minDuration: Long, minSize: Long): Flow<List<Audio>>

    // Get global track and unique album counts for specific artists
    @Query("""
        SELECT COUNT(id) FROM audio WHERE is_available = 1 AND album_artist = :artistName COLLATE NOCASE
    """)
    fun getTrackCountForArtist(artistName: String): Int

    @Query("""
        SELECT COUNT(DISTINCT album) FROM audio WHERE is_available = 1 AND album_artist = :artistName COLLATE NOCASE
    """)
    fun getAlbumCountForArtist(artistName: String): Int

    // Get all track paths for an artist (for the Artist object map)
    @Query("""
        SELECT uri FROM audio WHERE is_available = 1 AND album_artist = :artistName COLLATE NOCASE
    """)
    fun getTrackPathsForArtist(artistName: String): List<String>

    // Get global track paths for a specific genre
    @Query("""
        SELECT uri FROM audio WHERE is_available = 1 AND genre = :genreName
    """)
    fun getTrackPathsForGenre(genreName: String): List<String>

    // Fetches only tracks that contain the artist name somewhere in the field.
    @Query("""
    SELECT * FROM audio 
    WHERE is_available = 1
    AND artist LIKE '%' || :artistName || '%' 
    AND duration >= :minDuration 
    AND size >= :minSize
    """)
    fun getCandidateTracksForArtist(artistName: String, minDuration: Long, minSize: Long): Flow<List<Audio>>

    @Query("""
    SELECT * FROM audio 
    WHERE is_available = 1
    AND album_artist LIKE '%' || :artistName || '%' 
    AND duration >= :minDuration 
    AND size >= :minSize
    """)
    fun getCandidateTracksForAlbumArtist(artistName: String, minDuration: Long, minSize: Long): Flow<List<Audio>>

    @Query("""
    SELECT * FROM audio 
    WHERE is_available = 1
    AND composer = :composerName COLLATE NOCASE
    AND duration >= :minDuration 
    AND size >= :minSize
    """)
    fun getTracksForComposer(composerName: String, minDuration: Long, minSize: Long): Flow<List<Audio>>

    @Query("""
    SELECT * FROM audio 
    WHERE is_available = 1
    AND uri LIKE '%' || :folderPath || '%' 
    AND duration >= :minDuration 
    AND size >= :minSize
    """)
    fun getCandidateTracksForFolder(folderPath: String, minDuration: Long, minSize: Long): Flow<List<Audio>>

    @Query("""
    SELECT * FROM audio 
    WHERE is_available = 1
    AND genre = :genreName 
    AND duration >= :minDuration 
    AND size >= :minSize
    """)
    fun getTracksForGenre(genreName: String, minDuration: Long, minSize: Long): Flow<List<Audio>>

    // Query for a specific year
    @Query("""
    SELECT * FROM audio 
    WHERE is_available = 1
    AND year = :year 
    AND duration >= :minDuration 
    AND size >= :minSize
    """)
    fun getTracksForYear(year: String, minDuration: Long, minSize: Long): Flow<List<Audio>>

    // 2. Query for tracks with missing year data
    @Query("""
    SELECT * FROM audio 
    WHERE is_available = 1
    AND (year IS NULL OR year = '') 
    AND duration >= :minDuration 
    AND size >= :minSize
    """)
    fun getTracksForUnknownYear(minDuration: Long, minSize: Long): Flow<List<Audio>>

    // get all audio files by artist name with filtering
    @Query("SELECT * FROM audio WHERE artist = :artist AND is_available = 1 AND duration >= :minDuration AND size >= :minSize ORDER BY title COLLATE NOCASE ASC")
    fun getFilteredAudioByArtist(artist: String, minDuration: Long, minSize: Long): Flow<MutableList<Audio>>

    // Reactive search – Room will re-emit whenever the 'audio' table changes
    @Query("SELECT * FROM audio WHERE is_available = 1 AND title LIKE '%' || :query || '%' ORDER BY title COLLATE NOCASE ASC")
    fun searchByTitle(query: String): Flow<MutableList<Audio>>

    @Query("SELECT * FROM audio WHERE is_available = 1 AND artist LIKE '%' || :query || '%' ORDER BY title COLLATE NOCASE ASC")
    fun searchByArtist(query: String): Flow<MutableList<Audio>>

    @Query("SELECT * FROM audio WHERE is_available = 1 AND album LIKE '%' || :query || '%' ORDER BY title COLLATE NOCASE ASC")
    fun searchByAlbum(query: String): Flow<MutableList<Audio>>

    @Query("""
        SELECT * FROM audio
        WHERE is_available = 1 AND duration >= :minDuration AND size >= :minSize
        AND (
            title LIKE '%' || :query || '%'
            OR name LIKE '%' || :query || '%'
            OR path LIKE '%' || :query || '%'
        )
        ORDER BY title COLLATE NOCASE ASC
    """)
    fun searchByTitleFiltered(query: String, minDuration: Long, minSize: Long): Flow<MutableList<Audio>>

    @Query("SELECT * FROM audio WHERE is_available = 1 AND duration >= :minDuration AND size >= :minSize AND artist LIKE '%' || :query || '%' ORDER BY title COLLATE NOCASE ASC")
    fun searchByArtistFiltered(query: String, minDuration: Long, minSize: Long): Flow<MutableList<Audio>>

    @Query("SELECT * FROM audio WHERE is_available = 1 AND duration >= :minDuration AND size >= :minSize AND album LIKE '%' || :query || '%' ORDER BY title COLLATE NOCASE ASC")
    fun searchByAlbumFiltered(query: String, minDuration: Long, minSize: Long): Flow<MutableList<Audio>>

    @Query("SELECT * FROM audio WHERE is_available = 1 AND duration >= :minDuration AND size >= :minSize AND genre LIKE '%' || :query || '%' ORDER BY title COLLATE NOCASE ASC")
    fun searchByGenreFiltered(query: String, minDuration: Long, minSize: Long): Flow<MutableList<Audio>>

    @Query("SELECT * FROM audio WHERE is_available = 1 AND duration >= :minDuration AND size >= :minSize AND composer LIKE '%' || :query || '%' ORDER BY title COLLATE NOCASE ASC")
    fun searchByComposerFiltered(query: String, minDuration: Long, minSize: Long): Flow<MutableList<Audio>>

    @Query("SELECT * FROM audio WHERE is_available = 1 AND duration >= :minDuration AND size >= :minSize AND year LIKE '%' || :query || '%' ORDER BY title COLLATE NOCASE ASC")
    fun searchByYearFiltered(query: String, minDuration: Long, minSize: Long): Flow<MutableList<Audio>>

    @Query("SELECT id FROM audio WHERE uri = :path AND is_available = 1")
    fun getAudioIdByPath(path: String): Long

    @Query("SELECT * FROM audio WHERE id = :id LIMIT 1")
    suspend fun getAudioById(id: Long): Audio?

    @Query("SELECT * FROM audio WHERE uri = :path LIMIT 1")
    fun getAudioByPath(path: String): Audio?

    @Query("SELECT * FROM audio WHERE path = :posixPath LIMIT 1")
    fun getAudioByPosixPath(posixPath: String): Audio?

    @Query("SELECT * FROM audio WHERE is_favorite = 1 AND is_available = 1 ORDER BY title COLLATE NOCASE ASC")
    fun getFavoriteAudio(): Flow<MutableList<Audio>>

    @Query("SELECT * FROM audio WHERE always_skip = 1 AND is_available = 1 ORDER BY title COLLATE NOCASE ASC")
    fun getAlwaysSkippedAudio(): Flow<MutableList<Audio>>

    @Query("UPDATE audio SET is_favorite = :isFavorite WHERE id = :id")
    suspend fun setFavorite(id: Long, isFavorite: Boolean)

    @Query("UPDATE audio SET always_skip = :alwaysSkip WHERE id = :id")
    suspend fun setAlwaysSkip(id: Long, alwaysSkip: Boolean)

    @Query("UPDATE audio SET always_skip = :alwaysSkip WHERE id IN (:ids)")
    suspend fun setAlwaysSkipBatch(ids: List<Long>, alwaysSkip: Boolean)

    @RawQuery
    fun getQueriedData(query: SupportSQLiteQuery): MutableList<Audio>

    @RawQuery
    fun getAudioByIDs(query: SupportSQLiteQuery): MutableList<Audio>

    @Delete
    suspend fun delete(audio: Audio)

    @Delete
    suspend fun delete(audioList: List<Audio>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun insert(audio: Audio)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    fun insertOrIgnore(audio: Audio): Long

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertBatch(audioList: List<Audio>)

    @Update
    suspend fun update(audio: Audio)

    @Update
    suspend fun update(audioList: List<Audio>)

    @Query("DELETE FROM audio")
    fun nukeTable()
}
