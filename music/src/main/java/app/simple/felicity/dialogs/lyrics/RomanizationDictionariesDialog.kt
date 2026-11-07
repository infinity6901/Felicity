package app.simple.felicity.dialogs.lyrics

import android.app.Dialog
import android.content.Context
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.ViewGroup
import android.view.Window
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.lifecycleScope
import app.simple.felicity.managers.RomanizationDictionaries
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import java.util.Locale

/**
 * Lists the downloadable romanization dictionaries and lets the user download or remove each.
 * Auto romanize keeps working without any of them; an installed pack simply takes over for its
 * language. [onChanged] runs after an install or removal so the lyrics on screen are redone.
 */
object RomanizationDictionariesDialog {

    fun show(context: Context, owner: LifecycleOwner, onChanged: () -> Unit) {
        val dp = context.resources.displayMetrics.density
        fun px(v: Int) = (v * dp).toInt()

        val dialog = Dialog(context)
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)

        val root = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(px(20), px(20), px(20), px(14))
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#FF1E1F24"))
                cornerRadius = px(22).toFloat()
            }
        }
        root.addView(TextView(context).apply {
            text = "Romanization dictionaries"
            setTextColor(Color.WHITE)
            textSize = 18f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
        })
        root.addView(TextView(context).apply {
            text = "Auto romanize works without these. A downloaded dictionary adds readings for kanji " +
                    "and picks the right pronunciation for the song's language. Songs in other languages " +
                    "keep using the built-in rules."
            setTextColor(Color.parseColor("#FFB4B7C0"))
            textSize = 13f
            setPadding(0, px(6), 0, px(12))
        })

        val list = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        root.addView(ScrollView(context).apply { addView(list) },
                     LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        val jobs = HashMap<String, Job>()

        fun render() {
            list.removeAllViews()
            RomanizationDictionaries.PACKS.forEach { pack ->
                val installed = RomanizationDictionaries.isInstalled(context, pack.id)
                val downloading = jobs[pack.id]?.isActive == true

                val row = LinearLayout(context).apply {
                    orientation = LinearLayout.VERTICAL
                    setPadding(px(14), px(12), px(14), px(12))
                    background = GradientDrawable().apply {
                        setColor(Color.parseColor("#FF2A2C33"))
                        cornerRadius = px(16).toFloat()
                    }
                    layoutParams = LinearLayout.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                        bottomMargin = px(10)
                    }
                }
                row.addView(TextView(context).apply {
                    text = pack.name
                    setTextColor(Color.WHITE)
                    textSize = 15f
                    setTypeface(typeface, android.graphics.Typeface.BOLD)
                })
                row.addView(TextView(context).apply {
                    text = pack.description
                    setTextColor(Color.parseColor("#FFB4B7C0"))
                    textSize = 12f
                    setPadding(0, px(3), 0, px(8))
                })

                val status = TextView(context).apply {
                    setTextColor(Color.parseColor("#FF8FB8FF"))
                    textSize = 12f
                    text = when {
                        downloading -> "Downloading…"
                        installed -> "Installed · " + String.format(
                                Locale.US, "%.1f MB", RomanizationDictionaries.installedSizeBytes(context, pack.id) / 1048576f)
                        else -> String.format(Locale.US, "About %.1f MB", pack.approxSizeMb)
                    }
                }
                val progress = ProgressBar(context, null, android.R.attr.progressBarStyleHorizontal).apply {
                    max = 100
                    isIndeterminate = true
                    visibility = if (downloading) android.view.View.VISIBLE else android.view.View.GONE
                }
                val button = Button(context).apply {
                    isAllCaps = false
                    text = when {
                        downloading -> "Cancel"
                        installed -> "Remove"
                        else -> "Download"
                    }
                    setOnClickListener {
                        when {
                            downloading -> {
                                jobs.remove(pack.id)?.cancel()
                                render()
                            }
                            installed -> {
                                RomanizationDictionaries.delete(context, pack.id)
                                render()
                                onChanged()
                            }
                            else -> {
                                jobs[pack.id] = owner.lifecycleScope.launch {
                                    val error = RomanizationDictionaries.download(context, pack) { percent ->
                                        progress.post {
                                            if (percent >= 0) {
                                                progress.isIndeterminate = false
                                                progress.progress = percent
                                                status.text = "Downloading… $percent%"
                                            }
                                        }
                                    }
                                    jobs.remove(pack.id)
                                    if (error != null) {
                                        Toast.makeText(context, "Download failed: $error", Toast.LENGTH_LONG).show()
                                    } else {
                                        onChanged()
                                    }
                                    if (dialog.isShowing) render()
                                }
                                render()
                            }
                        }
                    }
                }

                val bottom = LinearLayout(context).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                }
                bottom.addView(status, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
                bottom.addView(button)
                row.addView(progress)
                row.addView(bottom)
                list.addView(row)
            }
        }
        render()

        root.addView(Button(context).apply {
            isAllCaps = false
            text = "Close"
            setOnClickListener { dialog.dismiss() }
        })

        dialog.setContentView(root)
        dialog.window?.apply {
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            setLayout((context.resources.displayMetrics.widthPixels * 0.92f).toInt(), ViewGroup.LayoutParams.WRAP_CONTENT)
        }
        dialog.setOnDismissListener { jobs.values.forEach { it.cancel() } }
        dialog.show()
    }
}
