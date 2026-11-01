package app.simple.felicity.ui.subpanels

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import androidx.lifecycle.lifecycleScope
import app.simple.felicity.extensions.fragments.MediaFragment
import app.simple.felicity.repository.database.instances.AudioDatabase
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Subpanel displaying advanced playback insights and analytics.
 */
@AndroidEntryPoint
class PlaybackAnalytics : MediaFragment() {

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        val context = requireContext()
        val root = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(32, 32, 32, 32)
        }

        val title = TextView(context).apply {
            text = "Playback Analytics & Insights"
            textSize = 20f
            setPadding(0, 16, 0, 32)
        }
        root.addView(title)

        val statText = TextView(context).apply {
            text = "Loading statistics..."
            textSize = 15f
        }
        root.addView(statText)

        viewLifecycleOwner.lifecycleScope.launch(Dispatchers.IO) {
            val db = AudioDatabase.getInstance(context)
            val mostPlayed = db.songStatDao().getMostPlayedAudio().firstOrNull().orEmpty()
            val totalPlayedCount = mostPlayed.size

            withContext(Dispatchers.Main) {
                statText.text = "Total Tracks Played: $totalPlayedCount\nMost Played Track: ${mostPlayed.firstOrNull()?.title ?: "None"}"
            }
        }

        return root
    }

    companion object {
        const val TAG = "PlaybackAnalytics"

        fun newInstance(): PlaybackAnalytics = PlaybackAnalytics()
    }
}