package app.simple.felicity.search

import android.app.SearchManager
import android.content.ContentProvider
import android.content.ContentValues
import android.content.UriMatcher
import android.content.pm.ApplicationInfo
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.Binder
import android.os.Process
import app.simple.felicity.BuildConfig
import app.simple.felicity.repository.repositories.AudioRepository
import dagger.hilt.EntryPoint
import dagger.hilt.EntryPoints
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.runBlocking

/**
 * Backs Felicity's `searchable.xml` with live song suggestions as the user types into a
 * system search surface (Assistant search, or any [android.widget.SearchView] configured
 * with `setSearchableInfo()`).
 *
 * <p>Only [SearchManager.SUGGEST_URI_PATH_QUERY] is handled — this provider exists purely
 * to serve suggestion rows, never to expose the library as general-purpose queryable data,
 * so every other URI and every non-`query()` operation is a deliberate no-op.</p>
 *
 * @author Hamza417
 */
class FelicitySearchProvider : ContentProvider() {

    @EntryPoint
    @InstallIn(SingletonComponent::class)
    interface FelicitySearchProviderEntryPoint {
        fun audioRepository(): AudioRepository
    }

    private val audioRepository: AudioRepository by lazy {
        val appContext = requireNotNull(context) { "ContentProvider#getContext() was null" }.applicationContext
        EntryPoints.get(appContext, FelicitySearchProviderEntryPoint::class.java).audioRepository()
    }

    override fun onCreate(): Boolean = true

    private fun isTrustedCaller(): Boolean {
        val callingUid = Binder.getCallingUid()
        if (callingUid == Process.myUid() || callingUid == Process.SYSTEM_UID) {
            return true
        }
        val ctx = context ?: return false
        val packages = ctx.packageManager.getPackagesForUid(callingUid) ?: return false
        return packages.any { pkg ->
            runCatching {
                val appInfo = ctx.packageManager.getApplicationInfo(pkg, 0)
                (appInfo.flags and (ApplicationInfo.FLAG_SYSTEM or ApplicationInfo.FLAG_UPDATED_SYSTEM_APP)) != 0
            }.getOrDefault(false)
        }
    }

    override fun query(
            uri: Uri,
            projection: Array<out String>?,
            selection: String?,
            selectionArgs: Array<out String>?,
            sortOrder: String?
    ): Cursor? {
        if (uriMatcher.match(uri) != SEARCH_SUGGEST) {
            return null
        }

        val cursor = MatrixCursor(COLUMNS)
        if (!isTrustedCaller()) {
            return cursor
        }

        val query = uri.lastPathSegment?.trim().orEmpty()

        if (query.length < MIN_QUERY_LENGTH) {
            return cursor
        }

        val matches = runBlocking { audioRepository.searchByTitle(query) }
            .take(MAX_SUGGESTIONS)

        matches.forEachIndexed { index, audio ->
            val subtitle = listOfNotNull(audio.artist, audio.album)
                .filter { it.isNotBlank() }
                .joinToString(" — ")

            cursor.addRow(
                    arrayOf<Any?>(
                            index.toLong(),
                            audio.title.orEmpty().ifBlank { subtitle.ifBlank { query } },
                            subtitle,
                            "felicity://audio/${audio.id}",
                            audio.id
                    )
            )
        }

        return cursor
    }

    override fun getType(uri: Uri): String? = when (uriMatcher.match(uri)) {
        SEARCH_SUGGEST -> SearchManager.SUGGEST_MIME_TYPE
        else -> null
    }

    // No writes are ever supported — this provider only ever serves read-only suggestions.
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int = 0

    companion object {
        private const val SEARCH_SUGGEST = 1
        private const val MIN_QUERY_LENGTH = 2
        private const val MAX_SUGGESTIONS = 10

        /** Must match `android:searchSuggestAuthority` in res/xml/searchable.xml. */
        val AUTHORITY: String = "${BuildConfig.APPLICATION_ID}.searchsuggest"

        private val COLUMNS = arrayOf(
                "_id",
                SearchManager.SUGGEST_COLUMN_TEXT_1,
                SearchManager.SUGGEST_COLUMN_TEXT_2,
                SearchManager.SUGGEST_COLUMN_INTENT_DATA,
                SearchManager.SUGGEST_COLUMN_INTENT_DATA_ID
        )

        private val uriMatcher = UriMatcher(UriMatcher.NO_MATCH).apply {
            addURI(AUTHORITY, SearchManager.SUGGEST_URI_PATH_QUERY, SEARCH_SUGGEST)
            addURI(AUTHORITY, "${SearchManager.SUGGEST_URI_PATH_QUERY}/*", SEARCH_SUGGEST)
        }
    }
}
