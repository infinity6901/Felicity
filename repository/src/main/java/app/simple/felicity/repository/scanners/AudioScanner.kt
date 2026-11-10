package app.simple.felicity.repository.scanners

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import android.util.Log
import app.simple.felicity.preferences.LibraryPreferences
import java.io.File

/**
 * Finds audio and playlist files on the device. Supports both the traditional
 * file-system approach and the Storage Access Framework (SAF) approach.
 *
 * @author Hamza417
 */
class AudioScanner {

    companion object {
        private const val TAG = "AudioScanner"

        private val AUDIO_EXTENSIONS = hashSetOf(
                "mp3", "m4a", "aac", "ts", "flac", "mid", "xmf", "mxmf",
                "rtttl", "rtx", "ota", "imy", "ogg", "opus", "wav", "alac",
                "aiff", "wma", "ape", "pcm", "webm", "m4b", "m4r", "wv",
                "dsf", "dff", "mpc", "ac3", "dts", "3gp", "3g2", "shn", "tta"
        )

        /** Extensions we recognize as M3U playlist files. */
        private val M3U_EXTENSIONS = hashSetOf("m3u", "m3u8")

        /** Extension for cue sheets — see [app.simple.felicity.repository.parsers.CueSheetParser]. */
        private val CUE_EXTENSIONS = hashSetOf("cue")

        private val SAF_PROJECTION = arrayOf(
                DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                DocumentsContract.Document.COLUMN_MIME_TYPE,
                DocumentsContract.Document.COLUMN_SIZE,
                DocumentsContract.Document.COLUMN_LAST_MODIFIED
        )
    }

    fun getAudioFiles(root: File): List<File> {
        if (!root.exists() || !root.isDirectory) {
            Log.e(TAG, "Invalid root directory: ${root.absolutePath}")
            return emptyList()
        }
        Log.d(TAG, "Scanning for audio files in: ${root.absolutePath}")

        val skipHiddenFiles = LibraryPreferences.isSkipHiddenFiles()
        val skipHiddenFolders = LibraryPreferences.isSkipHiddenFolders()

        return collectAudio(root, skipHiddenFiles, skipHiddenFolders)
    }

    fun getM3uFiles(root: File): List<File> {
        if (!root.exists() || !root.isDirectory) {
            Log.e(TAG, "Invalid root directory for M3U scan: ${root.absolutePath}")
            return emptyList()
        }
        Log.d(TAG, "Scanning for M3U files in: ${root.absolutePath}")

        val skipHiddenFiles = LibraryPreferences.isSkipHiddenFiles()
        val skipHiddenFolders = LibraryPreferences.isSkipHiddenFolders()

        return collectM3u(root, skipHiddenFiles, skipHiddenFolders)
    }

    fun getCueFiles(root: File): List<File> {
        if (!root.exists() || !root.isDirectory) {
            Log.e(TAG, "Invalid root directory for cue sheet scan: ${root.absolutePath}")
            return emptyList()
        }
        Log.d(TAG, "Scanning for cue sheets in: ${root.absolutePath}")

        val skipHiddenFiles = LibraryPreferences.isSkipHiddenFiles()
        val skipHiddenFolders = LibraryPreferences.isSkipHiddenFolders()

        return collectCue(root, skipHiddenFiles, skipHiddenFolders)
    }

    fun getAudioFiles(context: Context, treeUri: Uri): List<SAFFile> {
        val rootDocId = DocumentsContract.getTreeDocumentId(treeUri)
        Log.d(TAG, "SAF audio scan starting from tree: $treeUri (rootDocId=$rootDocId)")

        val skipHiddenFiles = LibraryPreferences.isSkipHiddenFiles()
        val skipHiddenFolders = LibraryPreferences.isSkipHiddenFolders()

        return collectAudioSAF(context, treeUri, rootDocId, skipHiddenFiles, skipHiddenFolders)
    }

    fun getM3uFiles(context: Context, treeUri: Uri): List<SAFFile> {
        val rootDocId = DocumentsContract.getTreeDocumentId(treeUri)
        Log.d(TAG, "SAF M3U scan starting from tree: $treeUri (rootDocId=$rootDocId)")

        val skipHiddenFiles = LibraryPreferences.isSkipHiddenFiles()
        val skipHiddenFolders = LibraryPreferences.isSkipHiddenFolders()

        return collectM3uSAF(context, treeUri, rootDocId, skipHiddenFiles, skipHiddenFolders)
    }

    fun getCueFiles(context: Context, treeUri: Uri): List<SAFFile> {
        val rootDocId = DocumentsContract.getTreeDocumentId(treeUri)
        Log.d(TAG, "SAF cue sheet scan starting from tree: $treeUri (rootDocId=$rootDocId)")

        val skipHiddenFiles = LibraryPreferences.isSkipHiddenFiles()
        val skipHiddenFolders = LibraryPreferences.isSkipHiddenFolders()

        return collectCueSAF(context, treeUri, rootDocId, skipHiddenFiles, skipHiddenFolders)
    }

    private fun File.isAudioFile(): Boolean {
        val ext = extension
        if (ext.isEmpty()) return false
        return AUDIO_EXTENSIONS.contains(ext.lowercase())
    }

    private fun File.isM3uFile(): Boolean {
        val ext = extension
        if (ext.isEmpty()) return false
        return M3U_EXTENSIONS.contains(ext.lowercase())
    }

    private fun File.isCueFile(): Boolean {
        val ext = extension
        if (ext.isEmpty()) return false
        return CUE_EXTENSIONS.contains(ext.lowercase())
    }

    private fun String.isAudioFile(): Boolean {
        val ext = substringAfterLast('.', "")
        if (ext.isEmpty()) return false
        return AUDIO_EXTENSIONS.contains(ext.lowercase())
    }

    private fun String.isM3uFile(): Boolean {
        val ext = substringAfterLast('.', "")
        if (ext.isEmpty()) return false
        return M3U_EXTENSIONS.contains(ext.lowercase())
    }

    private fun String.isCueFile(): Boolean {
        val ext = substringAfterLast('.', "")
        if (ext.isEmpty()) return false
        return CUE_EXTENSIONS.contains(ext.lowercase())
    }

    private fun collectAudio(
            root: File,
            skipHiddenFiles: Boolean,
            skipHiddenFolders: Boolean
    ): List<File> {
        val result = mutableListOf<File>()
        val excludedFolders = LibraryPreferences.getExcludedFolders()

        root.walkTopDown()
            .onEnter { dir ->
                if (skipHiddenFolders && dir.name.startsWith(".")) {
                    Log.d(TAG, "Skipping hidden folder: ${dir.absolutePath}")
                    return@onEnter false
                }
                if (excludedFolders.any { dir.absolutePath.contains(it, ignoreCase = true) || dir.name.equals(it, ignoreCase = true) }) {
                    Log.d(TAG, "Skipping excluded folder: ${dir.absolutePath}")
                    return@onEnter false
                }
                return@onEnter true
            }
            .forEach { file ->
                if (file.isFile && file.length() > 0 && file.isAudioFile()) {
                    if (skipHiddenFiles && file.name.startsWith(".")) {
                        Log.d(TAG, "Skipping hidden file: ${file.absolutePath}")
                        return@forEach
                    }
                    result.add(file)
                }
            }

        return result
    }

    private fun collectM3u(
            root: File,
            skipHiddenFiles: Boolean,
            skipHiddenFolders: Boolean
    ): List<File> {
        val result = mutableListOf<File>()
        val excludedFolders = LibraryPreferences.getExcludedFolders()

        root.walkTopDown()
            .onEnter { dir ->
                if (skipHiddenFolders && dir.name.startsWith(".")) return@onEnter false
                if (excludedFolders.any { dir.absolutePath.contains(it, ignoreCase = true) || dir.name.equals(it, ignoreCase = true) }) {
                    return@onEnter false
                }
                return@onEnter true
            }
            .forEach { file ->
                if (file.isFile && file.length() > 0 && file.isM3uFile()) {
                    if (skipHiddenFiles && file.name.startsWith(".")) return@forEach
                    result.add(file)
                }
            }

        return result
    }

    private fun collectCue(
            root: File,
            skipHiddenFiles: Boolean,
            skipHiddenFolders: Boolean
    ): List<File> {
        val result = mutableListOf<File>()
        val excludedFolders = LibraryPreferences.getExcludedFolders()

        root.walkTopDown()
            .onEnter { dir ->
                if (skipHiddenFolders && dir.name.startsWith(".")) return@onEnter false
                if (excludedFolders.any { dir.absolutePath.contains(it, ignoreCase = true) || dir.name.equals(it, ignoreCase = true) }) {
                    return@onEnter false
                }
                return@onEnter true
            }
            .forEach { file ->
                if (file.isFile && file.isCueFile()) {
                    if (skipHiddenFiles && file.name.startsWith(".")) return@forEach
                    result.add(file)
                }
            }

        return result
    }

    private fun collectAudioSAF(
            context: Context,
            treeUri: Uri,
            dirDocumentId: String,
            skipHiddenFiles: Boolean,
            skipHiddenFolders: Boolean
    ): List<SAFFile> {
        val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, dirDocumentId)

        data class Row(
                val docId: String,
                val name: String,
                val mimeType: String,
                val size: Long,
                val lastModified: Long
        )

        val rows = mutableListOf<Row>()

        context.contentResolver.query(childrenUri, SAF_PROJECTION, null, null, null)?.use { cursor ->
            val idxDocId = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DOCUMENT_ID)
            val idxName = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
            val idxMime = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_MIME_TYPE)
            val idxSize = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_SIZE)
            val idxMod = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_LAST_MODIFIED)

            while (cursor.moveToNext()) {
                val name = cursor.getString(idxName) ?: continue
                rows.add(Row(
                        cursor.getString(idxDocId) ?: continue,
                        name,
                        cursor.getString(idxMime) ?: "",
                        cursor.getLong(idxSize),
                        cursor.getLong(idxMod)
                ))
            }
        }

        val result = mutableListOf<SAFFile>()
        val excludedFolders = LibraryPreferences.getExcludedFolders()

        for (row in rows) {
            if (row.mimeType == DocumentsContract.Document.MIME_TYPE_DIR) {
                if (skipHiddenFolders && row.name.startsWith(".")) {
                    Log.d(TAG, "SAF: Skipping hidden folder: ${row.name}")
                    continue
                }
                if (excludedFolders.any { row.name.equals(it, ignoreCase = true) || row.docId.contains(it, ignoreCase = true) }) {
                    Log.d(TAG, "SAF: Skipping excluded folder: ${row.name}")
                    continue
                }
                result.addAll(collectAudioSAF(context, treeUri, row.docId, skipHiddenFiles, skipHiddenFolders))
            } else {
                if (row.size <= 0) continue
                if (!row.name.isAudioFile()) continue
                if (skipHiddenFiles && row.name.startsWith(".")) {
                    Log.d(TAG, "SAF: Skipping hidden audio file: ${row.name}")
                    continue
                }
                val docUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, row.docId)
                result.add(SAFFile(docUri, row.name, row.size, row.lastModified))
            }
        }

        return result
    }

    private fun collectM3uSAF(
            context: Context,
            treeUri: Uri,
            dirDocumentId: String,
            skipHiddenFiles: Boolean,
            skipHiddenFolders: Boolean
    ): List<SAFFile> {
        val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, dirDocumentId)

        data class Row(
                val docId: String,
                val name: String,
                val mimeType: String,
                val size: Long,
                val lastModified: Long
        )

        val rows = mutableListOf<Row>()

        context.contentResolver.query(childrenUri, SAF_PROJECTION, null, null, null)?.use { cursor ->
            val idxDocId = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DOCUMENT_ID)
            val idxName = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
            val idxMime = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_MIME_TYPE)
            val idxSize = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_SIZE)
            val idxMod = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_LAST_MODIFIED)

            while (cursor.moveToNext()) {
                val name = cursor.getString(idxName) ?: continue
                rows.add(Row(
                        cursor.getString(idxDocId) ?: continue,
                        name,
                        cursor.getString(idxMime) ?: "",
                        cursor.getLong(idxSize),
                        cursor.getLong(idxMod)
                ))
            }
        }

        val result = mutableListOf<SAFFile>()
        val excludedFolders = LibraryPreferences.getExcludedFolders()

        for (row in rows) {
            if (row.mimeType == DocumentsContract.Document.MIME_TYPE_DIR) {
                if (skipHiddenFolders && row.name.startsWith(".")) continue
                if (excludedFolders.any { row.name.equals(it, ignoreCase = true) || row.docId.contains(it, ignoreCase = true) }) continue
                result.addAll(collectM3uSAF(context, treeUri, row.docId, skipHiddenFiles, skipHiddenFolders))
            } else {
                if (row.size <= 0) continue
                if (!row.name.isM3uFile()) continue
                if (skipHiddenFiles && row.name.startsWith(".")) continue
                val docUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, row.docId)
                result.add(SAFFile(docUri, row.name, row.size, row.lastModified))
            }
        }

        return result
    }

    private fun collectCueSAF(
            context: Context,
            treeUri: Uri,
            dirDocumentId: String,
            skipHiddenFiles: Boolean,
            skipHiddenFolders: Boolean
    ): List<SAFFile> {
        val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, dirDocumentId)

        data class Row(
                val docId: String,
                val name: String,
                val mimeType: String,
                val size: Long,
                val lastModified: Long
        )

        val rows = mutableListOf<Row>()

        context.contentResolver.query(childrenUri, SAF_PROJECTION, null, null, null)?.use { cursor ->
            val idxDocId = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DOCUMENT_ID)
            val idxName = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
            val idxMime = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_MIME_TYPE)
            val idxSize = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_SIZE)
            val idxMod = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_LAST_MODIFIED)

            while (cursor.moveToNext()) {
                val name = cursor.getString(idxName) ?: continue
                rows.add(Row(
                        cursor.getString(idxDocId) ?: continue,
                        name,
                        cursor.getString(idxMime) ?: "",
                        cursor.getLong(idxSize),
                        cursor.getLong(idxMod)
                ))
            }
        }

        val result = mutableListOf<SAFFile>()
        val excludedFolders = LibraryPreferences.getExcludedFolders()

        for (row in rows) {
            if (row.mimeType == DocumentsContract.Document.MIME_TYPE_DIR) {
                if (skipHiddenFolders && row.name.startsWith(".")) continue
                if (excludedFolders.any { row.name.equals(it, ignoreCase = true) || row.docId.contains(it, ignoreCase = true) }) continue
                result.addAll(collectCueSAF(context, treeUri, row.docId, skipHiddenFiles, skipHiddenFolders))
            } else {
                if (!row.name.isCueFile()) continue
                if (skipHiddenFiles && row.name.startsWith(".")) continue
                val docUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, row.docId)
                result.add(SAFFile(docUri, row.name, row.size, row.lastModified))
            }
        }

        return result
    }
}
