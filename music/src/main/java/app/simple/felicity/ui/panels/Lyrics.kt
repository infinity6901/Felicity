package app.simple.felicity.ui.panels

import android.content.SharedPreferences
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.format.DateUtils
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.fragment.app.viewModels
import androidx.lifecycle.lifecycleScope
import app.simple.felicity.R
import app.simple.felicity.databinding.FragmentLyricsBinding
import app.simple.felicity.decorations.lrc.view.FelicityLrcView
import app.simple.felicity.decorations.seekbars.WaveformSeekbar
import app.simple.felicity.decorations.utils.TextViewUtils.setTextWithEffect
import app.simple.felicity.dialogs.lyrics.AddLyrics
import app.simple.felicity.dialogs.lyrics.AddLyrics.Companion.showAddLyrics
import app.simple.felicity.dialogs.lyrics.LyricsMenu
import app.simple.felicity.dialogs.lyrics.LyricsMenu.Companion.showLyricsMenu
import app.simple.felicity.engine.managers.MediaPlaybackManager
import app.simple.felicity.extensions.fragments.MediaFragment
import app.simple.felicity.glide.util.AudioCoverUtils.loadArtCoverWithPayload
import app.simple.felicity.managers.EmbedLyricsResult
import app.simple.felicity.managers.LyricsLoadingStatus
import app.simple.felicity.preferences.LyricsPreferences
import app.simple.felicity.repository.constants.MediaConstants
import app.simple.felicity.repository.models.Audio
import app.simple.felicity.repository.utils.AudioUtils.getProperArtists
import app.simple.felicity.repository.utils.AudioUtils.getProperTitle
import app.simple.felicity.ui.subpanels.LrcEditor
import app.simple.felicity.ui.subpanels.LyricsSearch
import app.simple.felicity.viewmodels.player.LyricsViewModel
import app.simple.felicity.viewmodels.player.WaveformViewModel
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch

@AndroidEntryPoint
class Lyrics : MediaFragment(), AddLyrics.Companion.OnLyricsCreatedListener {

    private lateinit var binding: FragmentLyricsBinding

    /**
     * Path of the song whose lyrics are currently rendered in the lrc view.
     * Compared in [onAudio] to distinguish a genuine song change from a
     * predictive-back resume that replays [MediaPlaybackManager.songPositionFlow] for
     * the same song — the latter must NOT reset the view.
     */
    private var currentAudioPath: String? = null

    /**
     * Holds an [Audio] track whose waveform has been requested but whose load has been
     * intentionally deferred because ExoPlayer has not yet reached the playing state.
     * Cleared to `null` as soon as [loadWaveformWhenReady] actually kicks off the decode.
     */
    private var pendingWaveformAudio: Audio? = null

    /** Debounce handler – coalesces rapid text-size changes from the slider. */
    private val textSizeHandler = Handler(Looper.getMainLooper())
    private val textSizeRunnable = Runnable { applyTextSize() }

    /** ViewModel that decodes and exposes the per-second waveform amplitude data. */
    private val waveformViewModel: WaveformViewModel by viewModels()

    /**
     * Reads from the shared [app.simple.felicity.managers.LyricsManager] so this panel and
     * the player screen always display the same lyrics data — no more stale panel blues.
     */
    private val lyricsViewModel: LyricsViewModel by viewModels()

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        binding = FragmentLyricsBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        requireHiddenMiniPlayer()
        setAlignment()
        applyTextSize()
        updateState()

        binding.lrc.setOnLrcClickListener { timeInMillis, _ ->
            MediaPlaybackManager.seekTo(timeInMillis)
        }

        binding.settings.setOnClickListener {
            childFragmentManager.showLyricsMenu().setOnMenuListener(object : LyricsMenu.Companion.LyricsMenuListener {
                override fun onTimeMinusClicked() {
                    lyricsViewModel.seekBy(-SEEK_JUMP_MS)
                }

                override fun onTimePlusClicked() {
                    lyricsViewModel.seekBy(SEEK_JUMP_MS)
                }

                override fun onLyricsDelete() {
                    withSureDialog { sure ->
                        if (sure) {
                            lyricsViewModel.deleteLrc {
                                binding.lrc.reset()
                                Log.d("LyricsFragment", "Lyrics deleted successfully.")
                            }
                        }
                    }
                }

                override fun onLrcEdit() {
                    if (MediaPlaybackManager.getCurrentOnlineTrack() != null) return
                    val currentAudio = MediaPlaybackManager.getCurrentSong() ?: return
                    openFragment(LrcEditor.newInstance(currentAudio), LrcEditor.TAG)
                }

                override fun onAddLyrics() {
                    if (MediaPlaybackManager.getCurrentOnlineTrack() != null) return
                    val currentAudio = MediaPlaybackManager.getCurrentSong() ?: return
                    childFragmentManager.showAddLyrics(currentAudio)
                        .setOnLyricsCreatedListener(this@Lyrics)
                }

                override fun onEmbedInFile() {
                    withSureDialog { sure ->
                        if (sure) {
                            lyricsViewModel.embedLyricsIntoFile()
                        }
                    }
                }
            })
        }

        binding.next.setOnClickListener {
            MediaPlaybackManager.next()
        }

        binding.previous.setOnClickListener {
            MediaPlaybackManager.previous()
        }

        binding.play.setOnClickListener {
            MediaPlaybackManager.flipState()
        }

        binding.search.setOnClickListener {
            openFragment(LyricsSearch.newInstance(), LyricsSearch.TAG)
        }

        viewLifecycleOwner.lifecycleScope.launch {
            lyricsViewModel.lrcData.collect { lrcData ->
                if (lrcData == null || lrcData.isEmpty) {
                    Log.d("LyricsFragment", "No lyrics found for the current song.")
                    binding.lrc.setEmptyLrcData()
                } else {
                    Log.d("LyricsFragment", "Loaded lyrics with ${lrcData.size()} lines.")
                    binding.lrc.setLrcData(
                            lrcData, MediaPlaybackManager.getSeekPosition() + lyricsViewModel.syncOffset)
                }
            }
        }

        viewLifecycleOwner.lifecycleScope.launch {
            lyricsViewModel.loadingStatus.collect { status ->
                val message = when (status) {
                    is LyricsLoadingStatus.Searching -> getString(R.string.searching_lyrics)
                    is LyricsLoadingStatus.Downloading -> getString(R.string.downloading_lyrics)
                    is LyricsLoadingStatus.Idle -> getString(R.string.no_lyrics_found)
                }
                binding.lrc.setEmptyText(message)
            }
        }

        viewLifecycleOwner.lifecycleScope.launch {
            lyricsViewModel.embedResult.collect { result ->
                when (result) {
                    is EmbedLyricsResult.Success -> {
                        Toast.makeText(requireContext(), getString(R.string.lyrics_embedded_successfully), Toast.LENGTH_SHORT).show()
                        lyricsViewModel.clearEmbedResult()
                    }
                    is EmbedLyricsResult.Error -> {
                        Toast.makeText(requireContext(), result.message, Toast.LENGTH_SHORT).show()
                        lyricsViewModel.clearEmbedResult()
                    }
                    null -> Unit
                }
            }
        }

        viewLifecycleOwner.lifecycleScope.launch {
            MediaPlaybackManager.currentOnlineTrackFlow.collect { onlineTrack ->
                if (onlineTrack != null) {
                    updateState()
                }
            }
        }

        parentFragmentManager.setFragmentResultListener(
                LyricsSearch.REQUEST_KEY_LYRICS_SAVED, viewLifecycleOwner) { _, _ ->
            Log.d("LyricsFragment", "Lyrics saved signal received, reloading lrc data.")
            lyricsViewModel.reloadLrcData()
        }

        parentFragmentManager.setFragmentResultListener(
                LrcEditor.REQUEST_KEY_LRC_SAVED, viewLifecycleOwner) { _, _ ->
            Log.d("LyricsFragment", "LRC editor saved signal received, reloading lrc data.")
            lyricsViewModel.reloadLrcData()
        }

        binding.seekbar.setLeftLabelProvider { progress, _, _ ->
            DateUtils.formatElapsedTime(progress.div(1000))
        }

        binding.seekbar.setRightLabelProvider { _, _, max ->
            DateUtils.formatElapsedTime(max.div(1000))
        }

        binding.seekbar.setOnSeekListener(object : WaveformSeekbar.OnSeekListener {
            override fun onSeekEnd(positionMs: Long) {
                MediaPlaybackManager.seekTo(positionMs)
            }
        })

        binding.seekbar.setOnFlingEndListener { positionMs ->
            MediaPlaybackManager.seekTo(positionMs)
        }

        waveformViewModel.getWaveformData().observe(viewLifecycleOwner) { amplitudes ->
            binding.seekbar.setAmplitudes(amplitudes)
        }
    }

    private fun setAlignment(animate: Boolean = false) {
        when (LyricsPreferences.getLrcAlignment()) {
            LyricsPreferences.LEFT -> binding.lrc.setTextAlignment(FelicityLrcView.Alignment.LEFT, animate)
            LyricsPreferences.CENTER -> binding.lrc.setTextAlignment(FelicityLrcView.Alignment.CENTER, animate)
            LyricsPreferences.RIGHT -> binding.lrc.setTextAlignment(FelicityLrcView.Alignment.RIGHT, animate)
        }
    }

    private fun applyTextSize() {
        val normal = LyricsPreferences.getLrcTextSize()
        binding.lrc.setTextSizes(normal, normal * LRC_HIGHLIGHT_TIMES)
    }

    private fun scheduleTextSizeUpdate() {
        textSizeHandler.removeCallbacks(textSizeRunnable)
        textSizeHandler.postDelayed(textSizeRunnable, TEXT_SIZE_DEBOUNCE_MS)
    }

    private fun updatePlayButtonState(isPlaying: Boolean) {
        if (isPlaying) {
            binding.play.setPlaying()
        } else {
            binding.play.setPaused()
        }
    }

    private fun updateState() {
        val onlineTrack = MediaPlaybackManager.getCurrentOnlineTrack()
        if (onlineTrack != null) {
            currentAudioPath = "online:${onlineTrack.source}:${onlineTrack.id}"
            binding.title.text = onlineTrack.title
            binding.artists.text = onlineTrack.subtitle
            binding.cover.loadArtCoverWithPayload(onlineTrack.artworkUrl ?: "")
            val duration = onlineTrack.durationMs ?: 0L
            binding.lrc.setDuration(duration)
            binding.seekbar.setDurationWithReset(duration)
            binding.seekbar.setProgress(MediaPlaybackManager.getSeekPosition(), animate = false)
            updatePlayButtonState(MediaPlaybackManager.isPlaying())
        } else {
            val audio = MediaPlaybackManager.getCurrentSong()
            if (audio != null) {
                val isSameSong = audio.uri == currentAudioPath
                currentAudioPath = audio.uri

                val forward = MediaPlaybackManager.lastNavigationDirection
                binding.title.setTextWithEffect(audio.getProperTitle(), forward)
                binding.artists.setTextWithEffect(audio.getProperArtists(), forward, 50L)
                binding.cover.loadArtCoverWithPayload(audio)
                binding.lrc.setDuration(audio.duration)
                binding.seekbar.setDurationWithReset(audio.duration)
                binding.seekbar.setProgress(MediaPlaybackManager.getSeekPosition(), animate = false)
                updatePlayButtonState(MediaPlaybackManager.isPlaying())

                if (!isSameSong) {
                    lyricsViewModel.loadLrcData()
                }

                loadWaveformWhenReady(audio)
            }
        }
    }

    private fun loadWaveformWhenReady(audio: Audio) {
        if (MediaPlaybackManager.isPlaying() || MediaPlaybackManager.isPlayerReady()) {
            waveformViewModel.loadWaveform(audio)
            pendingWaveformAudio = null
        } else {
            pendingWaveformAudio = audio
        }
    }

    override val wantsMiniPlayerVisible: Boolean
        get() = false

    override fun onSharedPreferenceChanged(sharedPreferences: SharedPreferences?, key: String?) {
        super.onSharedPreferenceChanged(sharedPreferences, key)
        when (key) {
            LyricsPreferences.LRC_ALIGNMENT -> setAlignment(animate = true)
            LyricsPreferences.LRC_TEXT_SIZE -> scheduleTextSizeUpdate()
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        textSizeHandler.removeCallbacks(textSizeRunnable)
    }

    override fun onResume() {
        super.onResume()
        if (lyricsViewModel.lrcData.value?.isEmpty == true) {
            lyricsViewModel.reloadLrcData()
        }
    }

    override fun onSeekChanged(seek: Long) {
        super.onSeekChanged(seek)
        binding.lrc.updateTime(seek + lyricsViewModel.syncOffset)
        binding.seekbar.setProgress(seek, animate = true)
    }

    override fun onAudio(audio: Audio) {
        super.onAudio(audio)

        val onlineTrack = MediaPlaybackManager.getCurrentOnlineTrack()
        if (onlineTrack != null) {
            val onlinePath = "online:${onlineTrack.source}:${onlineTrack.id}"
            if (currentAudioPath != onlinePath) {
                updateState()
            } else {
                binding.seekbar.setProgress(MediaPlaybackManager.getSeekPosition(), animate = true)
            }
            return
        }

        val isSameSong = audio.uri == currentAudioPath
        if (!isSameSong) {
            updateState()
        } else {
            binding.seekbar.setProgress(MediaPlaybackManager.getSeekPosition(), animate = true)
        }
    }

    override fun onPlaybackStateChanged(state: Int) {
        super.onPlaybackStateChanged(state)
        when (state) {
            MediaConstants.PLAYBACK_READY -> {
                pendingWaveformAudio?.let { audio ->
                    waveformViewModel.loadWaveform(audio)
                    pendingWaveformAudio = null
                }
            }
            MediaConstants.PLAYBACK_PLAYING -> {
                updatePlayButtonState(true)
                pendingWaveformAudio?.let { audio ->
                    waveformViewModel.loadWaveform(audio)
                    pendingWaveformAudio = null
                }
            }
            MediaConstants.PLAYBACK_PAUSED -> {
                updatePlayButtonState(false)
            }
        }
    }

    override fun onLyricsCreated() {
        lyricsViewModel.reloadLrcData()
    }

    companion object {
        fun newInstance(): Lyrics {
            val args = Bundle()
            val fragment = Lyrics()
            fragment.arguments = args
            return fragment
        }

        const val TAG = "LyricsFragment"

        private const val LRC_HIGHLIGHT_TIMES = 1.2F
        private const val TEXT_SIZE_DEBOUNCE_MS = 150L
        private const val SEEK_JUMP_MS = 500L
    }
}
