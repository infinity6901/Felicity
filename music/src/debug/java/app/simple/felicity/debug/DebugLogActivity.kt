package app.simple.felicity.debug

import android.content.Intent
import android.graphics.Typeface
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.FileProvider
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.concurrent.thread

/**
 * Debug-build-only screen (separate "Felicity Logs" launcher icon) for the logcat recorder:
 * start / stop recording, view the latest output, save the log to a location of the user's
 * choice, share it, or clear it. Built from code so it needs no layout or resource files.
 */
class DebugLogActivity : ComponentActivity() {

    private lateinit var status: TextView
    private lateinit var logView: TextView
    private lateinit var logScroll: ScrollView
    private lateinit var toggle: Button

    private val handler = Handler(Looper.getMainLooper())
    private val refresher = object : Runnable {
        override fun run() {
            refresh(scrollToEnd = false)
            handler.postDelayed(this, 3_000)
        }
    }

    private val saveLauncher = registerForActivityResult(
            ActivityResultContracts.CreateDocument("text/plain")) { uri: Uri? ->
        if (uri != null) saveTo(uri)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        LogRecorder.attach(this)

        val dp = resources.displayMetrics.density
        fun px(v: Int) = (v * dp).toInt()

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(px(16), px(16), px(16), px(16))
        }
        ViewCompat.setOnApplyWindowInsetsListener(root) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            v.setPadding(bars.left + px(16), bars.top + px(16), bars.right + px(16), bars.bottom + px(16))
            insets
        }

        root.addView(TextView(this).apply {
            text = "Felicity debug log"
            textSize = 20f
            setTypeface(typeface, Typeface.BOLD)
        })

        status = TextView(this).apply { setPadding(0, px(8), 0, px(8)) }
        root.addView(status)

        fun button(label: String, onClick: () -> Unit) = Button(this).apply {
            text = label
            isAllCaps = false
            setOnClickListener { onClick() }
        }

        toggle = button("") { toggleRecording() }

        val row1 = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val row2 = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        fun add(row: LinearLayout, b: Button) =
            row.addView(b, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))

        add(row1, toggle)
        add(row1, button("Save to file…") { saveClicked() })
        add(row2, button("Share") { shareClicked() })
        add(row2, button("Refresh") { refresh(scrollToEnd = true) })
        add(row2, button("Clear") { clearClicked() })
        root.addView(row1)
        root.addView(row2)

        logView = TextView(this).apply {
            typeface = Typeface.MONOSPACE
            textSize = 10f
            gravity = Gravity.START
        }
        logScroll = ScrollView(this).apply {
            addView(logView)
            isFillViewport = true
        }
        root.addView(logScroll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))

        setContentView(root)
        refresh(scrollToEnd = true)
    }

    override fun onResume() {
        super.onResume()
        handler.postDelayed(refresher, 3_000)
    }

    override fun onPause() {
        super.onPause()
        handler.removeCallbacks(refresher)
    }

    private fun toggleRecording() {
        if (LogRecorder.isRecording) LogRecorder.stop(this) else LogRecorder.start(this)
        // Give the recorder thread a moment to change state before the label is refreshed.
        handler.postDelayed({ refresh(scrollToEnd = false) }, 300)
    }

    private fun saveClicked() {
        val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
        saveLauncher.launch("felicity-log-$stamp.txt")
    }

    private fun saveTo(uri: Uri) {
        thread {
            val message = try {
                val export = LogRecorder.buildExportFile(this)
                contentResolver.openOutputStream(uri, "wt")?.use { out ->
                    export.inputStream().use { it.copyTo(out) }
                } ?: error("Could not open the chosen file")
                "Log saved (${export.length() / 1024} KB)"
            } catch (e: Exception) {
                "Save failed: ${e.message}"
            }
            runOnUiThread { Toast.makeText(this, message, Toast.LENGTH_LONG).show() }
        }
    }

    private fun shareClicked() {
        thread {
            try {
                val export = LogRecorder.buildExportFile(this)
                val uri = FileProvider.getUriForFile(this, "$packageName.provider", export)
                val send = Intent(Intent.ACTION_SEND).apply {
                    type = "text/plain"
                    putExtra(Intent.EXTRA_STREAM, uri)
                    putExtra(Intent.EXTRA_SUBJECT, "Felicity debug log")
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
                runOnUiThread { startActivity(Intent.createChooser(send, "Share log").addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)) }
            } catch (e: Exception) {
                runOnUiThread { Toast.makeText(this, "Share failed: ${e.message}", Toast.LENGTH_LONG).show() }
            }
        }
    }

    private fun clearClicked() {
        thread {
            LogRecorder.clear(this)
            runOnUiThread { refresh(scrollToEnd = true) }
        }
    }

    private var lastRenderedText: String = ""

    private fun refresh(scrollToEnd: Boolean) {
        val recording = LogRecorder.isRecording
        toggle.text = if (recording) "Stop recording" else "Start recording"
        val file = LogRecorder.logFile(this)
        status.text = (if (recording) "Recording: ON" else "Recording: OFF") +
                "\nFile: ${if (file.exists()) "${file.length() / 1024} KB (showing live tail)" else "empty"}" +
                "\n${file.absolutePath}"
        thread {
            val text = LogRecorder.tail(this).ifEmpty { "(no log recorded yet)" }
            runOnUiThread {
                if (text != lastRenderedText) {
                    lastRenderedText = text
                    logView.text = text
                    if (scrollToEnd) logScroll.post { logScroll.fullScroll(View.FOCUS_DOWN) }
                } else if (scrollToEnd) {
                    logScroll.post { logScroll.fullScroll(View.FOCUS_DOWN) }
                }
            }
        }
    }
}
