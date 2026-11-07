package app.simple.felicity.debug

import android.content.Context
import android.util.Log
import java.io.BufferedWriter
import java.io.File
import java.io.FileWriter
import java.io.RandomAccessFile
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Debug-build-only logcat recorder.
 *
 * Runs `logcat -v threadtime,usec,uid -b main,system,crash,events` (an app may read its own process' log without any permission) and
 * appends everything to `files/debug-logs/felicity-log.txt`. When the file passes [MAX_BYTES] it
 * is rolled to `felicity-log.1.txt`, so disk use stays bounded.
 *
 * The timestamp of the last recorded line is remembered, and the next start resumes from there
 * (`logcat -T`), so a crash that killed the previous process is still captured from the system
 * log buffer without duplicating what was already saved.
 */
object LogRecorder {

    private const val TAG = "LogRecorder"
    private const val PREFS = "debug_log_recorder"
    private const val KEY_ENABLED = "enabled"
    private const val KEY_LAST_TS = "last_ts"
    private const val MAX_BYTES = 32L * 1024 * 1024
    private const val FILE_NAME = "felicity-log.txt"
    private const val ROLLED_NAME = "felicity-log.1.txt"

    private val lock = Any()
    private var process: Process? = null
    private var thread: Thread? = null
    private var writer: BufferedWriter? = null

    fun logDir(context: Context): File = File(context.filesDir, "debug-logs").apply { mkdirs() }

    fun logFile(context: Context): File = File(logDir(context), FILE_NAME)

    fun isEnabled(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_ENABLED, true)

    val isRecording: Boolean
        get() = thread?.isAlive == true

    /** Starts recording (no-op if already running) and remembers that the user wants it on. */
    fun start(context: Context) {
        val app = context.applicationContext
        attach(app)
        synchronized(lock) {
            app.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(KEY_ENABLED, true).apply()
            if (isRecording) return
            thread = Thread({ record(app) }, "LogRecorder").apply {
                isDaemon = true
                start()
            }
        }
    }

    /** Stops recording and remembers that the user wants it off. */
    fun stop(context: Context) {
        val app = context.applicationContext
        synchronized(lock) {
            app.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(KEY_ENABLED, false).apply()
            process?.destroy()
            thread?.interrupt()
            process = null
            thread = null
        }
        flush()
    }

    /** Writes buffered lines to disk so a copy / share sees the latest output. */
    fun flush() {
        synchronized(lock) {
            try {
                writer?.flush()
            } catch (_: Exception) {
            }
        }
    }

    /** Deletes the recorded log (recording, if active, continues into a fresh file). */
    fun clear(context: Context) {
        synchronized(lock) {
            try {
                writer?.flush()
            } catch (_: Exception) {
            }
            try {
                writer?.close()
            } catch (_: Exception) {
            }
            writer = null
            logFile(context).delete()
            File(logDir(context), ROLLED_NAME).delete()
            if (isRecording) writer = openWriter(context)
        }
    }

    /** Last [maxBytes] of the current log file, for the on-screen viewer. */
    fun tail(context: Context, maxBytes: Int = 200_000): String {
        flush()
        val file = logFile(context)
        if (!file.exists()) return ""
        return try {
            RandomAccessFile(file, "r").use { raf ->
                val start = (raf.length() - maxBytes).coerceAtLeast(0)
                raf.seek(start)
                val bytes = ByteArray((raf.length() - start).toInt())
                raf.readFully(bytes)
                val text = String(bytes, Charsets.UTF_8)
                if (start > 0) text.substringAfter('\n', text) else text
            }
        } catch (e: Exception) {
            "Could not read log: ${e.message}"
        }
    }

    /** One file containing the rolled part (if any) followed by the current part. */
    fun buildExportFile(context: Context): File {
        flush()
        val out = File(logDir(context), "felicity-log-export.txt")
        out.outputStream().use { os ->
            File(logDir(context), ROLLED_NAME).takeIf { it.exists() }?.inputStream()?.use { it.copyTo(os) }
            logFile(context).takeIf { it.exists() }?.inputStream()?.use { it.copyTo(os) }
        }
        return out
    }

    private fun openWriter(context: Context): BufferedWriter =
        BufferedWriter(FileWriter(logFile(context), true))

    private fun record(context: Context) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        try {
            synchronized(lock) { writer = openWriter(context) }

            val lastTs = prefs.getString(KEY_LAST_TS, null)
            val command = ArrayList<String>().apply {
                add("logcat")
                // threadtime + microseconds; the first 18 chars stay "MM-dd HH:mm:ss.SSS" so -T resume works.
                add("-v")
                add("threadtime,usec,uid")
                // Everything the app can see: app + system messages, crashes and binary events
                // (activity lifecycle, GC, ANR hints).
                add("-b")
                add("main,system,crash,events")
                if (lastTs != null) {
                    add("-T")
                    add(lastTs)
                }
            }

            val header = "==== Recording started ${SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())}" +
                    " pid=${android.os.Process.myPid()} resume=${lastTs ?: "full buffer"} ===="
            write(header)

            val proc = ProcessBuilder(command).redirectErrorStream(true).start()
            synchronized(lock) { process = proc }

            var lastSaved = 0L
            proc.inputStream.bufferedReader().use { reader ->
                while (true) {
                    val line = reader.readLine() ?: break
                    write(line)

                    // "MM-dd HH:mm:ss.SSS ..." - keep the newest timestamp for the next start.
                    if (line.length > 18 && line[2] == '-' && line[5] == ' ') {
                        val now = System.currentTimeMillis()
                        if (now - lastSaved > 2_000) {
                            prefs.edit().putString(KEY_LAST_TS, line.substring(0, 18)).apply()
                            lastSaved = now
                        }
                    }

                    if (!reader.ready()) flush()
                }
            }
            write("==== Recording ended ====")
        } catch (e: InterruptedException) {
            // stop() requested
        } catch (e: Exception) {
            if (isEnabled(context)) Log.e(TAG, "Log recording failed", e)
        } finally {
            flush()
            synchronized(lock) {
                process?.destroy()
                process = null
            }
        }
    }

    private fun write(line: String) {
        synchronized(lock) {
            val w = writer ?: return
            try {
                w.write(line)
                w.newLine()
                val context = appContextOrNull ?: return
                val file = logFile(context)
                if (file.length() > MAX_BYTES) {
                    w.flush()
                    w.close()
                    val rolled = File(logDir(context), ROLLED_NAME)
                    rolled.delete()
                    file.renameTo(rolled)
                    writer = openWriter(context)
                }
            } catch (_: Exception) {
            }
        }
    }

    @Volatile
    private var appContextOrNull: Context? = null

    internal fun attach(context: Context) {
        appContextOrNull = context.applicationContext
    }
}
