package app.simple.felicity.ui.panels

import android.content.SharedPreferences
import android.graphics.Bitmap
import android.graphics.drawable.Drawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.format.DateUtils
import android.util.Log
import android.widget.Toast
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import kotlinx.coroutines.flow.first
import app.simple.felicity.managers.MultiProviderLyricsEngine
import androidx.fragment.app.viewModels
import androidx.lifecycle.lifecycleScope
import app.simple.felicity.R
import app.simple.felicity.databinding.FragmentLyricsBinding
import app.simple.felicity.decorations.lrc.view.FelicityLrcView
import app.simple.felicity.decorations.seekbars.WaveformSeekbar
import app.simple.felicity.decorations.theme.ThemeImageButton
import app.simple.felicity.decorations.typeface.TypeFaceTextView
import app.simple.felicity.decorations.utils.TextViewUtils.setTextWithEffect
import app.simple.felicity.decorations.views.FelicityMediaControls.Companion.MediaControlListener
import app.simple.felicity.dialogs.lyrics.AddLyrics
import app.simple.felicity.dialogs.lyrics.AddLyrics.Companion.showAddLyrics
import app.simple.felicity.dialogs.lyrics.LyricsStudioDialog
import app.simple.felicity.dialogs.lyrics.LyricsStyle
import app.simple.felicity.dialogs.lyrics.LyricsMenu
import app.simple.felicity.dialogs.lyrics.LyricsMenu.Companion.showLyricsMenu
import app.simple.felicity.engine.managers.MediaPlaybackManager
import app.simple.felicity.extensions.fragments.MediaFragment
import app.simple.felicity.glide.transformation.Blur
import app.simple.felicity.glide.transformation.Darken
import app.simple.felicity.glide.transformation.Greyscale
import app.simple.felicity.glide.transformation.VignetteTransformation
import app.simple.felicity.managers.EmbedLyricsResult
import app.simple.felicity.managers.LyricsLoadingStatus
import app.simple.felicity.preferences.AlbumArtPreferences
import app.simple.felicity.preferences.LyricsPreferences
import app.simple.felicity.preferences.UserInterfacePreferences
import app.simple.felicity.repository.constants.MediaConstants
import app.simple.felicity.repository.models.Audio
import app.simple.felicity.repository.utils.AudioUtils.getProperArtists
import app.simple.felicity.repository.utils.AudioUtils.getProperTitle
import app.simple.felicity.shared.utils.ViewUtils.gone
import app.simple.felicity.shared.utils.ViewUtils.visible
import app.simple.felicity.theme.managers.ThemeUtils
import app.simple.felicity.ui.panels.Lyrics.Companion.TEXT_SIZE_DEBOUNCE_MS
import app.simple.felicity.ui.subpanels.LrcEditor
import app.simple.felicity.ui.subpanels.LyricsSearch
import app.simple.felicity.viewmodels.player.LyricsViewModel
import app.simple.felicity.viewmodels.player.WaveformViewModel
import com.bumptech.glide.Glide
import com.bumptech.glide.load.Transformation
import com.bumptech.glide.load.engine.DiskCacheStrategy
import com.bumptech.glide.load.resource.bitmap.CenterCrop
import com.bumptech.glide.request.target.CustomTarget
import com.bumptech.glide.request.transition.Transition
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
     *
     * Also used to re-seek seekbar and lrc view to the correct position when a predictive-back resumes
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

    /** While the Studio is open it previews changes itself, so the screen behind it must not restyle on every tick. */
    private var studioOpen = false
    private var appliedBackgroundMode = Int.MIN_VALUE
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
        setAlbumArtBackground(animate = false)
        updateState()

        binding.lrc.setOnLrcClickListener { timeInMillis, _ ->
            MediaPlaybackManager.seekTo(timeInMillis)
        }

        binding.settings.setOnClickListener {
            childFragmentManager.showLyricsMenu().setOnMenuListener(object : LyricsMenu.Companion.LyricsMenuListener {
                override fun onTimeMinusClicked() {
                    lyricsViewModel.seekBy(-SEEK_JUMP_MS)
                    // syncOffset is updated inside LyricsManager.seekBy() — no extra line needed
                }

                override fun onTimePlusClicked() {
                    lyricsViewModel.seekBy(SEEK_JUMP_MS)
                    // Same as above — the manager keeps track of the accumulated offset
                }

                override fun onLyricsDelete() {
                    withSureDialog { sure ->
                        if (sure) {
                            lyricsViewModel.deleteLrc {
                                binding.lrc.reset()
                                Log.d(TAG, "Lyrics deleted successfully.")
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

                override fun onRomanizationPreferenceChanged() {
                    lyricsViewModel.reloadLrcData()
                }
            })
        }

        binding.mediaControls.setMediaControlListener(object : MediaControlListener {
            override fun onPreviousClick() {
                MediaPlaybackManager.previous()
            }

            override fun onNextClick() {
                MediaPlaybackManager.next()
            }

            override fun onPlayClick() {
                MediaPlaybackManager.flipState()
            }

            override fun onForwardStep() {
                MediaPlaybackManager.seekRelative(SEEK_PER_PULSE_MS)
            }

            override fun onRewindStep() {
                MediaPlaybackManager.seekRelative(-SEEK_PER_PULSE_MS)
            }
        })

        binding.studio.setOnClickListener {
            studioOpen = true
            LyricsStudioDialog.show(
                requireContext(),
                onTranslationChanged = { lyricsViewModel.reloadLrcData() },
                onPhraseSearch = { phrase ->
                    viewLifecycleOwner.lifecycleScope.launch {
                        val hits = lyricsViewModel.searchPhraseAcrossTracks(phrase)
                        val labels = hits.map { "${it.title} · ${it.artist}\n${it.snippet}" }.toTypedArray()
                        androidx.appcompat.app.AlertDialog.Builder(requireContext())
                            .setTitle(if (hits.isEmpty()) "No verified lyric matches" else "Matching songs")
                            .setItems(labels, null)
                            .setPositiveButton("Done", null)
                            .show()
                    }
                }
            ).setOnDismissListener {
                studioOpen = false
                if (isAdded && view != null) applyTextSize()
            }
        }

        // The version picker is available for every song, local or online.
        binding.versions.visibility = View.VISIBLE
        binding.versions.setOnClickListener {
            viewLifecycleOwner.lifecycleScope.launch {
                if (lyricsViewModel.availableVersions.value.size < 2) {
                    android.widget.Toast.makeText(requireContext(), "Searching lyric sources…", android.widget.Toast.LENGTH_SHORT).show()
                    lyricsViewModel.loadVersions()
                    lyricsViewModel.versionsLoading.first { !it }
                }
                showLyricsVersions()
            }
        }

        binding.search.setOnClickListener {
            openFragment(LyricsSearch.newInstance(), LyricsSearch.TAG)
        }

        // Collect from the shared manager — every screen collecting this StateFlow
        // always gets the same data, so the lyrics panel never shows a stale song.
        viewLifecycleOwner.lifecycleScope.launch {
            lyricsViewModel.lrcData.collect { lrcData ->
                if (lrcData == null || lrcData.isEmpty) {
                    Log.d(TAG, "No lyrics found for the current song.")
                    binding.lrc.setEmptyLrcData()
                } else {
                    Log.d(TAG, "Loaded lyrics with ${lrcData.size()} lines.")
                    binding.lrc.setLrcData(
                            lrcData, MediaPlaybackManager.getSeekPosition() + lyricsViewModel.syncOffset)
                }
            }
        }

        // Watch the loading status and update the empty-text in the lrc view so the user
        // knows what is happening while the app is talking to the internet.
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

        // Listen for the signal from LyricsSearch that a new .lrc sidecar was saved so that
        // the lyrics panel refreshes immediately without requiring a close/reopen cycle.
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

        // Online (YouTube) tracks have no Audio row, so onAudio never fires for them.
        viewLifecycleOwner.lifecycleScope.launch {
            MediaPlaybackManager.currentOnlineTrackFlow.collect { onlineTrack ->
                if (onlineTrack != null) {
                    updateState()
                }
            }
        }

        parentFragmentManager.setFragmentResultListener(
                LyricsSearch.REQUEST_KEY_LYRICS_SAVED, viewLifecycleOwner) { _, _ ->
            Log.d(TAG, "Lyrics saved signal received, reloading lrc data.")
            lyricsViewModel.reloadLrcData()
        }

        // Listen for the signal from LrcEditor that the user has saved a newly edited .lrc
        // file, so this panel can reload without requiring the user to close and reopen.
        parentFragmentManager.setFragmentResultListener(
                LrcEditor.REQUEST_KEY_LRC_SAVED, viewLifecycleOwner) { _, _ ->
            Log.d(TAG, "LRC editor saved signal received, reloading lrc data.")
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

    /**
     * Version picker: an editable search box (title, or "Artist - Title") above the list of
     * candidates, each showing source, sync type, size and length so they can be told apart.
     */
    private fun showLyricsVersions() {
        val ctx = requireContext()
        val dialogBinding = app.simple.felicity.databinding.DialogLyricsVersionsBinding.inflate(LayoutInflater.from(ctx))
        val song = MediaPlaybackManager.getCurrentSong()
        val online = MediaPlaybackManager.getCurrentOnlineTrack()
        val initialQuery = online?.title ?: song?.title ?: song?.name.orEmpty()

        val adapter = VersionsAdapter { index ->
            lyricsViewModel.selectLyricsVersion(index)
        }
        dialogBinding.versionsRecycler.layoutManager = androidx.recyclerview.widget.LinearLayoutManager(ctx)
        dialogBinding.versionsRecycler.adapter = adapter
        dialogBinding.header.editText.setText(initialQuery)
        dialogBinding.header.editText.hint = getString(R.string.lyrics_versions_search_hint)

        val dialog = androidx.appcompat.app.AlertDialog.Builder(ctx)
            .setView(dialogBinding.root)
            .create()

        fun runSearch() {
            val q = dialogBinding.header.editText.text?.toString().orEmpty().trim()
            if (q.isNotEmpty()) lyricsViewModel.searchVersions(q)
            val imm = ctx.getSystemService(android.content.Context.INPUT_METHOD_SERVICE) as? android.view.inputmethod.InputMethodManager
            imm?.hideSoftInputFromWindow(dialogBinding.header.editText.windowToken, 0)
        }
        dialogBinding.header.search.setOnClickListener { runSearch() }
        dialogBinding.header.editText.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == android.view.inputmethod.EditorInfo.IME_ACTION_SEARCH) { runSearch(); true } else false
        }

        val jobs = listOf(
                viewLifecycleOwner.lifecycleScope.launch {
                    lyricsViewModel.availableVersions.collect { list ->
                        adapter.submit(list)
                        dialogBinding.header.count.text = getString(R.string.lyrics_versions_count, list.size)
                    }
                },
                viewLifecycleOwner.lifecycleScope.launch {
                    lyricsViewModel.versionsLoading.collect { loading ->
                        dialogBinding.header.progress.visibility = if (loading) View.VISIBLE else View.GONE
                    }
                }
        )
        adapter.onPicked = { dialog.dismiss() }
        dialog.setOnDismissListener { jobs.forEach { it.cancel() } }
        dialog.show()
    }

    private inner class VersionsAdapter(
            private val onClick: (Int) -> Unit
    ) : androidx.recyclerview.widget.RecyclerView.Adapter<VersionsAdapter.Holder>() {
        private var items: List<MultiProviderLyricsEngine.Candidate> = emptyList()
        var onPicked: () -> Unit = {}

        fun submit(list: List<MultiProviderLyricsEngine.Candidate>) {
            items = list
            notifyDataSetChanged()
        }

        inner class Holder(val b: app.simple.felicity.databinding.AdapterLyricsVersionBinding) :
                androidx.recyclerview.widget.RecyclerView.ViewHolder(b.root)

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) = Holder(
                app.simple.felicity.databinding.AdapterLyricsVersionBinding.inflate(
                        LayoutInflater.from(parent.context), parent, false))

        override fun getItemCount() = items.size

        override fun onBindViewHolder(holder: Holder, position: Int) {
            val c = items[position]
            holder.b.trackName.text = c.title.ifBlank { c.provider }
            holder.b.artistName.text = c.artist
            holder.b.artistName.visibility = if (c.artist.isBlank()) View.GONE else View.VISIBLE
            holder.b.kindBadge.text = getString(when {
                c.wordSynced -> R.string.lyrics_word_by_word
                c.synced -> R.string.lyrics_line_by_line
                else -> R.string.lyrics_plain_text
            })
            holder.b.details.text = buildList {
                add(c.provider)
                // Spelled out in the details too, starred for word-by-word, so it reads at a glance.
                add(when {
                    c.wordSynced -> "★ " + getString(R.string.lyrics_word_by_word)
                    c.synced -> getString(R.string.lyrics_line_by_line)
                    else -> getString(R.string.lyrics_plain_text)
                })
                add("${c.lineCount} lines")
                if (c.durationSec > 0) add(String.format(java.util.Locale.US, "%d:%02d", c.durationSec / 60, c.durationSec % 60))
                add("${c.confidence}%")
            }.joinToString(" · ")
            holder.b.container.setOnClickListener {
                val index = holder.bindingAdapterPosition
                if (index != androidx.recyclerview.widget.RecyclerView.NO_POSITION) {
                    onClick(index)
                    onPicked()
                }
            }
        }
    }

    /** Applies every persisted Lyrics Studio setting to the lyrics view and background. */
    private fun applyTextSize() {
        LyricsStyle.apply(binding.lrc)
        applyStudioBackground()
    }

    /**
     * Paints the Studio background: flat modes replace the artwork with a dark surface, artwork
     * modes show the (already blurred) cover, and "Blurred Artwork" adds a further blur on API 31+.
     */
    private fun applyStudioBackground() {
        val mode = LyricsPreferences.getStudioBackground()
        if (mode == appliedBackgroundMode) return
        appliedBackgroundMode = mode
        val flat = LyricsStyle.isFlatBackground(mode)
        binding.coverContainer.background = if (flat) LyricsStyle.backgroundDrawable(mode) else null
        val artAlpha = if (flat) 0f else 1f
        binding.coverFront.alpha = artAlpha
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
            val effect = if (mode == LyricsPreferences.BACKGROUND_BLURRED_ARTWORK) {
                android.graphics.RenderEffect.createBlurEffect(
                        40f, 40f, android.graphics.Shader.TileMode.CLAMP)
            } else null
            binding.coverFront.setRenderEffect(effect)
        }
        setAlbumArtBackground(animate = false)
    }

    /**
     * Schedules [applyTextSize] after [TEXT_SIZE_DEBOUNCE_MS] ms, cancelling any
     * previously pending call.  Rapid slider events therefore collapse into one update
     * that fires only once the user stops (or nearly stops) dragging.
     */
    private fun scheduleTextSizeUpdate() {
        if (studioOpen) return
        textSizeHandler.removeCallbacks(textSizeRunnable)
        textSizeHandler.postDelayed(textSizeRunnable, TEXT_SIZE_DEBOUNCE_MS)
    }

    private fun updatePlayButtonState(isPlaying: Boolean) {
        binding.mediaControls.setPlaying(isPlaying)
    }

    private fun updateState() {
        val onlineTrack = MediaPlaybackManager.getCurrentOnlineTrack()
        if (onlineTrack != null) {
            currentAudioPath = "online:${onlineTrack.source}:${onlineTrack.id}"
            binding.title.text = onlineTrack.title
            binding.artists.text = onlineTrack.subtitle
            loadArtCover(onlineTrack.artworkUrl ?: "")
            val duration = onlineTrack.durationMs ?: 0L
            binding.lrc.setDuration(duration)
            binding.seekbar.setDurationWithReset(duration)
            binding.seekbar.setProgress(MediaPlaybackManager.getSeekPosition(), animate = false)
            updatePlayButtonState(MediaPlaybackManager.isPlaying())
            return
        }

        val audio = MediaPlaybackManager.getCurrentSong() ?: return
        currentAudioPath = audio.uri
        binding.title.text = audio.getProperTitle()
        binding.artists.text = audio.getProperArtists()
        loadArtCover(audio)
        binding.lrc.setDuration(audio.duration)
        binding.seekbar.setDuration(audio.duration)
        binding.seekbar.setProgress(MediaPlaybackManager.getSeekPosition(), animate = false)
        updatePlayButtonState(MediaPlaybackManager.isPlaying())

        binding.seekbar.optics = UserInterfacePreferences.getWaveformOptics()

        binding.seekbar.layoutMode = if (UserInterfacePreferences.isWaveformFullMode()) {
            WaveformSeekbar.LAYOUT_MODE_FULL
        } else {
            WaveformSeekbar.LAYOUT_MODE_SCROLLING
        }

        // Defer waveform decoding until ExoPlayer is actually playing to avoid
        // Amplituda and ExoPlayer fighting over the same file I/O resources.
        loadWaveformWhenReady(audio)
    }

    /**
     * Schedules waveform decoding for [audio].
     *
     * The decode starts immediately when the player is already playing or in a ready state
     * (paused or playing). If the player is still buffering or idle, [audio] is stored in
     * [pendingWaveformAudio] and the decode is deferred until [onPlaybackStateChanged]
     * receives [MediaConstants.PLAYBACK_READY] or [MediaConstants.PLAYBACK_PLAYING].
     *
     * This prevents Amplituda and ExoPlayer from contending over the same file I/O
     * resources during the initial buffering phase.
     *
     * @param audio the track whose waveform should be decoded
     */
    private fun loadWaveformWhenReady(audio: Audio) {
        if (MediaPlaybackManager.isPlaying() || MediaPlaybackManager.isPlayerReady()) {
            waveformViewModel.loadWaveform(audio)
            pendingWaveformAudio = null
        } else {
            pendingWaveformAudio = audio
        }
    }

    @Suppress("SameParameterValue")
    private fun setAlbumArtBackground(animate: Boolean = false) {
        val enabled = LyricsPreferences.isAlbumArtBackground() ||
                LyricsStyle.isFlatBackground(LyricsPreferences.getStudioBackground())

        binding.lrc.setAlbumArtBackground(enabled)

        if (enabled) {
            if (animate) {
                binding.coverContainer.animate()
                    .alpha(1f)
                    .setDuration(300L)
                    .withStartAction { binding.coverContainer.visible(false) }
                    .start()
            } else {
                binding.coverContainer.animate().cancel()
                binding.coverContainer.visible(false)
                binding.coverContainer.alpha = 1f
            }

            binding.title.setTextColorMode(TypeFaceTextView.WHITE)
            binding.artists.setTextColorMode(TypeFaceTextView.SECONDARY_WHITE)
            binding.search.setTintMode(ThemeImageButton.WHITE)
            binding.studio.setTintMode(ThemeImageButton.WHITE)
            binding.versions.setTintMode(ThemeImageButton.WHITE)
            binding.settings.setTintMode(ThemeImageButton.WHITE)
            requireLightBarIcons()
        } else {
            if (animate) {
                binding.coverContainer.animate()
                    .alpha(0f)
                    .setDuration(300L)
                    .withEndAction { binding.coverContainer.gone(false) }
                    .start()
            } else {
                binding.coverContainer.animate().cancel()
                binding.coverContainer.gone(false)
                binding.coverContainer.alpha = 0f
            }

            binding.title.setTextColorMode(TypeFaceTextView.BOLD)
            binding.artists.setTextColorMode(TypeFaceTextView.SECONDARY)
            binding.search.setTintMode(ThemeImageButton.REGULAR)
            binding.studio.setTintMode(ThemeImageButton.REGULAR)
            binding.versions.setTintMode(ThemeImageButton.REGULAR)
            binding.settings.setTintMode(ThemeImageButton.REGULAR)

            /**
             * We cannot just set dark bar colors since user can be on any theme
             * and each theme may set their own bar colors. So we just reapply
             * the theme's bar colors to ensure consistency.
             */
            ThemeUtils.setBarColors(resources, requireActivity().window)
        }
    }

    override val wantsMiniPlayerVisible: Boolean
        get() = false

    override fun onSharedPreferenceChanged(sharedPreferences: SharedPreferences?, key: String?) {
        super.onSharedPreferenceChanged(sharedPreferences, key)
        when (key) {
            LyricsPreferences.LRC_ALIGNMENT -> setAlignment(animate = true)
            LyricsPreferences.LRC_TEXT_SIZE -> scheduleTextSizeUpdate()
            LyricsPreferences.ALBUM_ART_BACKGROUND -> setAlbumArtBackground(animate = true)
            else -> if (key != null && key in LyricsPreferences.STUDIO_KEYS) scheduleTextSizeUpdate()
        }
    }

    override fun onDestroyView() {
        appliedBackgroundMode = Int.MIN_VALUE
        super.onDestroyView()
        textSizeHandler.removeCallbacks(textSizeRunnable)
    }

    /** Runs the lyrics on in real time between the player's coarse position readings. */
    private val clockDriver = app.simple.felicity.managers.LyricsClockDriver { position ->
        if (this::binding.isInitialized) binding.lrc.updateTime(position + lyricsViewModel.syncOffset)
    }

    override fun onPause() {
        clockDriver.stop()
        super.onPause()
    }

    override fun onResume() {
        super.onResume()
        clockDriver.start(MediaPlaybackManager.getSeekPosition())
        // If lyrics data is missing after returning from a long background session,
        // force a fresh reload so the view is not left blank.
        if (lyricsViewModel.lrcData.value?.isEmpty == true) {
            lyricsViewModel.reloadLrcData()
        }
    }

    override fun onSeekChanged(seek: Long) {
        super.onSeekChanged(seek)
        clockDriver.read(seek)
        binding.seekbar.setProgress(seek, animate = true)
    }

    override fun onAudio(audio: Audio) {
        super.onAudio(audio)

        // An online track is playing; its state is driven by currentOnlineTrackFlow.
        if (MediaPlaybackManager.getCurrentOnlineTrack() != null) {
            return
        }

        val isSameSong = audio.uri == currentAudioPath
        currentAudioPath = audio.uri

        if (!isSameSong) {
            lyricsViewModel.loadLrcData()
            val forward = MediaPlaybackManager.lastNavigationDirection
            binding.title.setTextWithEffect(audio.getProperTitle(), forward)
            binding.artists.setTextWithEffect(audio.getProperArtists(), forward, 50L)
            loadArtCover(audio)
            binding.lrc.setDuration(audio.duration)
            binding.seekbar.setDurationWithReset(audio.duration)
        }

        // Always refresh the seek position (covers predictive-back resume and actual changes).
        binding.seekbar.setProgress(MediaPlaybackManager.getSeekPosition(), animate = true)

        // Defer waveform decoding until ExoPlayer is actually playing to avoid
        // Amplituda and ExoPlayer fighting over the same file I/O resources.
        loadWaveformWhenReady(audio)
    }

    override fun onPlaybackStateChanged(state: Int) {
        super.onPlaybackStateChanged(state)
        when (state) {
            MediaConstants.PLAYBACK_READY -> {
                // ExoPlayer has finished buffering and the decoder is ready. This is the
                // earliest safe moment to run Amplituda's extraction in parallel, regardless
                // of whether playback will start immediately or stay paused.
                pendingWaveformAudio?.let { audio ->
                    waveformViewModel.loadWaveform(audio)
                    pendingWaveformAudio = null
                }
            }
            MediaConstants.PLAYBACK_PLAYING -> {
                updatePlayButtonState(true)
                // Also drain any pending waveform that was queued before the ready event arrived
                // (e.g., when the service emits PLAYING without a preceding READY being observed).
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
        // Reload the lyrics view after the sidecar file has been created
        lyricsViewModel.reloadLrcData()
    }

    /**
     * Loads the album art for the given [item] and applies a series of transformations
     * (crop, blur, greyscale, darken, vignette) before setting it as the background of
     * the lyrics panel.
     *
     * The front cover is also updated with a fade-out/fade-in animation to smoothly
     * transition to the new image. If loading fails, a default image is displayed.
     *
     * @param item The source of the album art, typically an [Audio] object or a URI.
     */
    private fun loadArtCover(item: Any) {
        val transformations = mutableListOf<Transformation<Bitmap>>()

        transformations.add(CenterCrop()) // crop
        transformations.add(Blur(48)) // blur
        if (AlbumArtPreferences.isGreyscaleEnabled()) transformations.add(Greyscale()) // greyscale but from user preference
        transformations.add(Darken(0.3F)) // darken/dim the image
        transformations.add(VignetteTransformation()) // vignette to smooth out the corners

        Glide.with(this)
            .asBitmap()
            .load(item)
            .dontTransform()
            .transform(*transformations.toTypedArray())
            .diskCacheStrategy(DiskCacheStrategy.ALL)
            .error(R.drawable.ic_felicity)
            .dontAnimate()
            .into(object : CustomTarget<Bitmap>() {
                override fun onResourceReady(resource: Bitmap, transition: Transition<in Bitmap>?) {
                    LyricsStyle.artworkColor = LyricsStyle.sampleArtworkColor(resource)
                    textSizeHandler.removeCallbacks(textSizeRunnable)
                    textSizeHandler.post(textSizeRunnable)
                    binding.coverBack.setImageBitmap(resource)
                    binding.coverBack.alpha = 1f

                    binding.coverFront.animate()
                        .alpha(0f)
                        .setDuration(300)
                        .withEndAction {
                            binding.coverFront.setImageBitmap(resource)
                            binding.coverFront.alpha =
                                if (LyricsStyle.isFlatBackground(LyricsPreferences.getStudioBackground())) 0f else 1f
                            binding.coverBack.setImageDrawable(null)
                        }
                        .start()
                }

                override fun onLoadFailed(errorDrawable: Drawable?) {
                    binding.coverFront.setImageResource(R.drawable.ic_felicity)
                }

                override fun onLoadCleared(placeholder: Drawable?) {}
            })
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

        /** How long to wait after the last slider event before applying the text-size change. */
        private const val TEXT_SIZE_DEBOUNCE_MS = 150L
        private const val SEEK_JUMP_MS = 500L
    }
}