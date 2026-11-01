package app.simple.felicity.dialogs.youtube

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import androidx.fragment.app.FragmentManager
import app.simple.felicity.extensions.dialogs.ScopedBottomSheetFragment
import app.simple.felicity.repository.online.OnlineTrack

class YoutubeTrackMenu : ScopedBottomSheetFragment() {

    private var track: OnlineTrack? = null
    var onDownloadRequested: ((OnlineTrack) -> Unit)? = null

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        val context = requireContext()
        val layout = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(32, 32, 32, 32)
        }

        val trackTitle = TextView(context).apply {
            text = track?.title ?: "Track Options"
            textSize = 18f
            setPadding(16, 16, 16, 24)
        }
        layout.addView(trackTitle)

        val btnDownload = TextView(context).apply {
            text = "Download Track"
            textSize = 16f
            setPadding(16, 24, 16, 24)
            setOnClickListener {
                dismiss()
                track?.let { onDownloadRequested?.invoke(it) }
            }
        }
        layout.addView(btnDownload)

        val btnShare = TextView(context).apply {
            text = "Share Link"
            textSize = 16f
            setPadding(16, 24, 16, 24)
            setOnClickListener {
                dismiss()
                track?.let { t ->
                    val shareIntent = Intent(Intent.ACTION_SEND).apply {
                        type = "text/plain"
                        putExtra(Intent.EXTRA_TEXT, "https://music.youtube.com/watch?v=${t.id}")
                    }
                    startActivity(Intent.createChooser(shareIntent, "Share Track"))
                }
            }
        }
        layout.addView(btnShare)

        return layout
    }

    companion object {
        private const val TAG = "YoutubeTrackMenu"

        fun show(fragmentManager: FragmentManager, track: OnlineTrack, onDownload: (OnlineTrack) -> Unit) {
            val menu = YoutubeTrackMenu().apply {
                this.track = track
                this.onDownloadRequested = onDownload
            }
            menu.show(fragmentManager, TAG)
        }
    }
}