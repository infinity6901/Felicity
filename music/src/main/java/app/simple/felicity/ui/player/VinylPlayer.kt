package app.simple.felicity.ui.player

import android.animation.ObjectAnimator
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.animation.LinearInterpolator
import android.widget.ImageView
import android.widget.TextView
import androidx.lifecycle.lifecycleScope
import app.simple.felicity.databinding.FragmentPlayerVinylBinding
import app.simple.felicity.decorations.highlight.HighlightTextView
import app.simple.felicity.decorations.lrc.view.LrcLineView
import app.simple.felicity.decorations.pager.FelicityPager
import app.simple.felicity.decorations.seekbars.WaveformSeekbar
import app.simple.felicity.decorations.views.FavoriteButton
import app.simple.felicity.decorations.views.FelicityMediaControls
import app.simple.felicity.decorations.views.FelicityVisualizer
import app.simple.felicity.decorations.views.FlexStackLayout
import app.simple.felicity.engine.managers.MediaPlaybackManager
import app.simple.felicity.extensions.fragments.BasePlayerFragment
import app.simple.felicity.repository.constants.MediaConstants
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch

/**
 * Vinyl player interface. Structurally identical to [DefaultPlayer] — same abstract view
 * wiring, same [BasePlayerFragment] behavior for playback/seekbar/visualizer/pager — the
 * only real difference is [FragmentPlayerVinylBinding]'s layout: the swipeable album art
 * sits centered on top of a static decorative record ([R.id.vinyl_disc] in the layout,
 * exposed here as [vinylDisc]) that this fragment spins while a song is playing and holds
 * in place — rather than resetting to 0° — while paused, the way a real turntable platter
 * behaves.
 *
 * @author Hamza417
 */
@AndroidEntryPoint
class VinylPlayer : BasePlayerFragment() {

    private lateinit var binding: FragmentPlayerVinylBinding
    private var vinylAnimator: ObjectAnimator? = null

    override val pager: FelicityPager
        get() = binding.pager

    override val count: TextView
        get() = binding.count

    override val mediaControls: FelicityMediaControls
        get() = binding.mediaControls

    override val queue: View
        get() = binding.queue

    override val search: View
        get() = binding.search

    override val menu: View
        get() = binding.menu

    override val repeat: ImageView
        get() = binding.repeat

    override val pcmInfo: TextView
        get() = binding.pcmInfo

    override val seekbar: WaveformSeekbar
        get() = binding.seekbar

    override val lyrics: View
        get() = binding.lyrics

    override val favorite: FavoriteButton
        get() = binding.favorite

    override val equalizer: View
        get() = binding.equalizer

    override val visualizerButton: View
        get() = binding.visualizerButton

    override val sleepTimer: View
        get() = binding.sleepTimer

    override val visualizer: FelicityVisualizer
        get() = binding.visualizer

    override val title: TextView
        get() = binding.title

    override val artist: TextView
        get() = binding.artist

    override val album: TextView
        get() = binding.album

    override val shuffle: HighlightTextView
        get() = binding.shuffle

    override val lrc: LrcLineView
        get() = binding.lrc

    override val seekbarContainer: FlexStackLayout
        get() = binding.seekbarContainer

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        binding = FragmentPlayerVinylBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        lrc.setShowBackground(true)
        setupVinylAnimator()

        viewLifecycleOwner.lifecycleScope.launch {
            MediaPlaybackManager.playbackStateFlow.collect { state ->
                if (state == MediaConstants.PLAYBACK_PLAYING) {
                    resumeSpin()
                } else {
                    pauseSpin()
                }
            }
        }
    }

    override fun onDestroyView() {
        vinylAnimator?.cancel()
        vinylAnimator = null
        super.onDestroyView()
    }

    private fun setupVinylAnimator() {
        vinylAnimator = ObjectAnimator.ofFloat(binding.vinylDisc, View.ROTATION, 0f, 360f).apply {
            duration = VINYL_ROTATION_DURATION_MS
            repeatCount = ObjectAnimator.INFINITE
            interpolator = LinearInterpolator()
        }
    }

    private fun resumeSpin() {
        val animator = vinylAnimator ?: return
        when {
            animator.isPaused -> animator.resume()
            !animator.isStarted -> animator.start()
        }
    }

    private fun pauseSpin() {
        vinylAnimator?.takeIf { it.isStarted }?.pause()
    }

    companion object {
        /**
         * Creates a new instance of [VinylPlayer] with an empty argument bundle.
         *
         * @return a fresh [VinylPlayer] fragment instance
         */
        fun newInstance(): VinylPlayer {
            val args = Bundle()
            val fragment = VinylPlayer()
            fragment.arguments = args
            return fragment
        }

        /** One full turn every 1.8s ≈ 33⅓ RPM — the standard vinyl LP playback speed. */
        private const val VINYL_ROTATION_DURATION_MS = 1800L

        const val TAG = "VinylPlayer"
    }
}
