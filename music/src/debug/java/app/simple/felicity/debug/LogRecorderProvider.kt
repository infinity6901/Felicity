package app.simple.felicity.debug

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.net.Uri

/**
 * Empty ContentProvider whose only job is to run [LogRecorder.start] when the process starts
 * (providers are created before Application.onCreate), so the very beginning of the session and
 * early crashes are recorded. Recording stays off if the user switched it off.
 */
class LogRecorderProvider : ContentProvider() {
    override fun onCreate(): Boolean {
        val context = context ?: return false
        LogRecorder.attach(context)
        if (LogRecorder.isEnabled(context)) LogRecorder.start(context)
        return true
    }

    override fun query(uri: Uri, projection: Array<out String>?, selection: String?,
                       selectionArgs: Array<out String>?, sortOrder: String?): Cursor? = null

    override fun getType(uri: Uri): String? = null
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int = 0
}
