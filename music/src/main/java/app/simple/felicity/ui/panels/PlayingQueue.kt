package app.simple.felicity.ui.panels

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import androidx.fragment.app.viewModels
import androidx.recyclerview.widget.GridLayoutManager
import app.simple.felicity.R
import app.simple.felicity.adapters.ui.lists.AdapterPlayingQueue
import app.simple.felicity.callbacks.GeneralAdapterCallbacks
import app.simple.felicity.databinding.FragmentPlayingQueueBinding
import app.simple.felicity.databinding.HeaderPlayingQueueBinding
import app.simple.felicity.decorations.views.AppHeader
import app.simple.felicity.decorations.views.PopupMenuItem
import app.simple.felicity.decorations.views.SharedScrollViewPopup
import app.simple.felicity.dialogs.app.TotalTime.Companion.showTotalTime
import app.simple.felicity.dialogs.playlists.AddMultipleToPlaylistDialog.Companion.showAddMultipleToPlaylistDialog
import app.simple.felicity.dialogs.queue.QueueLabel.Companion.QueueLabelCallbacks
import app.simple.felicity.dialogs.queue.QueueLabel.Companion.openQueueLabel
import app.simple.felicity.engine.managers.MediaPlaybackManager
import app.simple.felicity.engine.managers.PlaybackStateManager
import app.simple.felicity.extensions.fragments.BasePanelFragment
import app.simple.felicity.preferences.QueueLabelPreferences
import app.simple.felicity.repository.managers.SelectionManager
import app.simple.felicity.repository.models.Audio
import app.simple.felicity.repository.online.OnlineSource
import app.simple.felicity.repository.online.OnlineTrack
import app.simple.felicity.shared.utils.TimeUtils.toDynamicTimeString
import app.simple.felicity.viewmodels.panels.PlayingQueueViewModel

class PlayingQueue : BasePanelFragment() {

    private lateinit var binding: FragmentPlayingQueueBinding
    private lateinit var headerBinding: HeaderPlayingQueueBinding

    private var adapterPlayingQueue: AdapterPlayingQueue? = null

    private var hasScrolledToInitialPosition = false
    private var REMAINING_DURATION: Long = 0L

    private val playingQueueViewModel: PlayingQueueViewModel by viewModels({ requireActivity() })

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        binding = FragmentPlayingQueueBinding.inflate(inflater, container, false)
        headerBinding = HeaderPlayingQueueBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        binding.recyclerView.requireAttachedMiniPlayer()
        binding.appHeader.setContentView(headerBinding.root)
        binding.appHeader.attachTo(binding.recyclerView, AppHeader.ScrollMode.HIDE_ON_SCROLL)
        binding.recyclerView.attachSlideFastScroller()

        /** Queue always shows as a single-column list; no grid switching. */
        binding.recyclerView.setupGridLayoutManager(1)

        updateQueueHeaderLabel()
        setupHeaderClicks()

        // Keep the header label in sync whenever the active queue slot changes.
        MediaPlaybackManager.activeQueueIdFlow.collectWhenStarted { queueId ->
            updateQueueHeaderLabel()
        }

        val updateQueueDisplay = {
            val onlineTrack = MediaPlaybackManager.getCurrentOnlineTrack()
            if (onlineTrack != null) {
                val onlineTracks = playingQueueViewModel.onlineQueue.value
                val displayOnlineTracks = if (onlineTracks.isNotEmpty()) onlineTracks else listOf(onlineTrack)
                val currentIndex = if (onlineTracks.isNotEmpty()) playingQueueViewModel.onlineQueueIndex.value else 0
                val mappedAudios = displayOnlineTracks.map { it.toAudio() }

                headerBinding.currentQueue.text = if (onlineTrack.source == OnlineSource.YOUTUBE) "YouTube Queue" else "Online Queue"
                updateOnlineQueueList(mappedAudios, displayOnlineTracks, currentIndex)
            } else {
                updateQueueHeaderLabel()
                val songs = playingQueueViewModel.songs.value
                if (songs.isNotEmpty()) {
                    updateQueueList(songs)
                    setDurations()
                    adapterPlayingQueue?.updateCurrentPosition(MediaPlaybackManager.getCurrentSongPosition())
                } else {
                    adapterPlayingQueue = null
                    binding.recyclerView.adapter = null
                    headerBinding.count.text = getString(R.string.x_songs, 0)
                    headerBinding.hours.text = 0L.toDynamicTimeString()
                }
            }
        }

        playingQueueViewModel.songs.collectWhenStarted { updateQueueDisplay() }
        playingQueueViewModel.onlineQueue.collectWhenStarted { updateQueueDisplay() }
        playingQueueViewModel.onlineQueueIndex.collectWhenStarted { updateQueueDisplay() }
        playingQueueViewModel.currentOnlineTrack.collectWhenStarted { updateQueueDisplay() }
    }

    override fun onDestroyView() {
        adapterPlayingQueue = null
        hasScrolledToInitialPosition = false
        super.onDestroyView()
    }

    private fun setupHeaderClicks() {
        headerBinding.currentQueue.setOnClickListener { anchorView ->
            if (MediaPlaybackManager.getCurrentOnlineTrack() != null) return@setOnClickListener
            hideMiniPlayer()

            val activeQueueId = MediaPlaybackManager.getActiveQueueId()
            viewLifecycleOwner.lifecycleScope.launch {
                val counts = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                    val dao = app.simple.felicity.repository.database.instances.AudioDatabase
                        .getInstance(requireContext()).savedQueueDao()
                    IntArray(PlaybackStateManager.QUEUE_COUNT) { slot ->
                        if (slot == activeQueueId) {
                            MediaPlaybackManager.getSongs().size
                        } else {
                            dao.getQueue(slot).size
                        }
                    }
                }
                val labels = mutableListOf<String>().apply {
                    for (i in 0 until PlaybackStateManager.QUEUE_COUNT) {
                        val base = QueueLabelPreferences.getDisplayLabel(i) { n ->
                            getString(R.string.current_queue, n)
                        }
                        add("$base (${counts[i]})")
                    }
                }

                SharedScrollViewPopup(
                        container = requireContainerView(),
                        anchorView = anchorView,
                        menuItems = labels.map { label -> PopupMenuItem(title = label) },
                        onMenuItemClick = { clickedIndex ->
                            if (clickedIndex != activeQueueId) {
                                MediaPlaybackManager.switchToQueue(clickedIndex, requireContext())
                            }
                        },
                        onDismiss = {
                            showMiniPlayer()
                        },
                ).show()
            }
        }

        headerBinding.renameQueue.setOnClickListener {
            if (MediaPlaybackManager.getCurrentOnlineTrack() != null) return@setOnClickListener
            val activeQueueId = MediaPlaybackManager.getActiveQueueId()
            childFragmentManager.openQueueLabel(QueueLabelPreferences.getDisplayLabel(activeQueueId) { n ->
                getString(R.string.current_queue, n)
            }).setOnQueueLabelChangedListener(object : QueueLabelCallbacks {
                override fun onQueueLabelChanged(label: String) {
                    QueueLabelPreferences.setLabel(activeQueueId, label)
                    updateQueueHeaderLabel()
                }
            })
        }

        headerBinding.createPlaylist.setOnClickListener {
            if (MediaPlaybackManager.getCurrentOnlineTrack() != null) return@setOnClickListener
            childFragmentManager.showAddMultipleToPlaylistDialog(MediaPlaybackManager.getSongs())
        }

        headerBinding.shuffle.setOnClickListener {
            val onlineTracks = MediaPlaybackManager.onlineQueueFlow.value
            if (onlineTracks.isNotEmpty()) {
                MediaPlaybackManager.reorderOnlineQueue(onlineTracks.shuffled())
            } else {
                shuffleMediaItems(MediaPlaybackManager.getSongs())
            }
        }

        headerBinding.clearQueue.setOnClickListener {
            withSureDialog {
                if (it) {
                    MediaPlaybackManager.clearQueueExceptCurrent()
                }
            }
        }
    }

    private fun OnlineTrack.toAudio(): Audio {
        return Audio().apply {
            id = this@toAudio.id.hashCode().toLong()
            title = this@toAudio.title
            name = this@toAudio.title
            artist = this@toAudio.subtitle
            album = this@toAudio.source.name
            duration = this@toAudio.durationMs ?: 0L
            uri = this@toAudio.artworkUrl ?: "online:${this@toAudio.source}:${this@toAudio.id}"
        }
    }

    private fun updateOnlineQueueList(audios: List<Audio>, onlineTracks: List<OnlineTrack>, currentIndex: Int) {
        if (adapterPlayingQueue == null) {
            adapterPlayingQueue = AdapterPlayingQueue(audios)
            adapterPlayingQueue?.setHasStableIds(true)

            adapterPlayingQueue?.setGeneralAdapterCallbacks(object : GeneralAdapterCallbacks {
                override fun onSongClicked(songs: MutableList<Audio>, position: Int, view: View) {
                    if (MediaPlaybackManager.getCurrentOnlineTrack() != null) {
                        MediaPlaybackManager.jumpToOnlineQueueIndex(position)
                    } else if (SelectionManager.hasSelection) {
                        SelectionManager.toggle(songs[position])
                    } else {
                        MediaPlaybackManager.updatePosition(position, forcePlay = true)
                    }
                }

                override fun onSongLongClicked(audios: MutableList<Audio>, position: Int, imageView: ImageView?) {
                    if (MediaPlaybackManager.getCurrentOnlineTrack() == null) {
                        openSongsMenu(audios, position, imageView)
                    }
                }
            })

            adapterPlayingQueue?.setOnItemSwipedCallback { position ->
                val currentOnline = MediaPlaybackManager.onlineQueueFlow.value
                if (currentOnline.isNotEmpty()) {
                    val mutable = currentOnline.toMutableList()
                    if (position in mutable.indices) {
                        mutable.removeAt(position)
                        MediaPlaybackManager.reorderOnlineQueue(mutable)
                    }
                }
            }

            binding.recyclerView.adapter = adapterPlayingQueue
        } else {
            adapterPlayingQueue?.updateSongs(audios)
            if (binding.recyclerView.adapter == null) {
                binding.recyclerView.adapter = adapterPlayingQueue
            }
        }

        adapterPlayingQueue?.updateCurrentPosition(currentIndex)

        headerBinding.count.text = getString(R.string.x_songs, audios.size)
        headerBinding.hours.text = audios.sumOf { it.duration }.toDynamicTimeString()
    }

    /**
     * Updates the header label to show the name of the currently active queue.
     * When the user has assigned a custom label via [QueueLabelPreferences]
     * that name is shown; otherwise the default "Queue N" is used.
     */
    private fun updateQueueHeaderLabel() {
        val activeId = MediaPlaybackManager.getActiveQueueId()
        val label = QueueLabelPreferences.getDisplayLabel(activeId) { n ->
            getString(R.string.current_queue, n)
        }
        headerBinding.currentQueue.text = label
    }

    private fun updateQueueList(songs: List<Audio>) {
        if (adapterPlayingQueue == null) {
            adapterPlayingQueue = AdapterPlayingQueue(songs)

            adapterPlayingQueue?.setGeneralAdapterCallbacks(object : GeneralAdapterCallbacks {
                override fun onSongClicked(songs: MutableList<Audio>, position: Int, view: View) {
                    val clickedSong = songs[position]

                    // If we are in multi-select mode, just toggle and stop here.
                    if (SelectionManager.hasSelection) {
                        SelectionManager.toggle(clickedSong)
                        return
                    }

                    // If the user tapped a different song, play it and stop here.
                    if (MediaPlaybackManager.getCurrentSong()?.id != clickedSong.id) {
                        MediaPlaybackManager.updatePosition(position, forcePlay = true)
                        return
                    }

                    // If we reach this point, the user tapped the currently playing song.
                    // Decide where to navigate them.
                    val cameFromPlayer = requireArguments().getBoolean(FROM_PLAYER_KEY, false)
                    
                    if (cameFromPlayer) {
                        goBack()
                    } else {
                        openDefaultPlayer()
                    }
                }

                override fun onSongLongClicked(audios: MutableList<Audio>, position: Int, imageView: ImageView?) {
                    openSongsMenu(audios, position, imageView)
                }
            })

            adapterPlayingQueue?.setOnItemSwipedCallback { position ->
                MediaPlaybackManager.removeQueueItemSilently(position)
            }

            binding.recyclerView.adapter = adapterPlayingQueue
        } else {
            adapterPlayingQueue?.updateSongs(songs)
            if (binding.recyclerView.adapter == null) {
                binding.recyclerView.adapter = adapterPlayingQueue
            }
        }

        headerBinding.count.text = getString(R.string.x_songs, songs.size)
        headerBinding.hours.text = songs.sumOf { it.duration }.toDynamicTimeString()

        headerBinding.hours.setOnClickListener {
            childFragmentManager.showTotalTime(
                    totalTime = songs.sumOf { it.duration },
                    count = songs.size
            )
        }

        /**
         * Scroll to the currently playing song only on the initial load. Subsequent queue
         * changes (drag reorder, swipe-to-remove, song change) must NOT trigger a scroll
         * to avoid a feedback loop that floods the RecyclerView with continuous scroll events.
         */
        if (requireArguments().getBoolean(FIRST_LAUNCH_KEY, true)) {
            if (!hasScrolledToInitialPosition && binding.recyclerView.layoutManager is GridLayoutManager) {
                hasScrolledToInitialPosition = true
                val layoutManager = binding.recyclerView.layoutManager as GridLayoutManager
                val currentPosition = MediaPlaybackManager.getCurrentSongPosition()
                binding.recyclerView.post {
                    val firstVisible = layoutManager.findFirstVisibleItemPosition()
                    val lastVisible = layoutManager.findLastVisibleItemPosition()
                    if (currentPosition !in firstVisible..lastVisible) {
                        layoutManager.scrollToPositionWithOffset(
                                currentPosition,
                                binding.appHeader.height
                                        + resources.getDimensionPixelSize(R.dimen.padding_8)
                                        + binding.recyclerView.paddingTop
                        )
                        binding.recyclerView.scheduleLayoutAnimation()
                    }
                }
            }

            requireArguments().putBoolean(FIRST_LAUNCH_KEY, false)
        }
    }

    override fun onSeekChanged(seek: Long) {
        super.onSeekChanged(seek)
        updateCountDown(seek)
    }

    override fun onAudio(audio: Audio) {
        super.onAudio(audio)
        setDurations()
        adapterPlayingQueue?.updateCurrentPosition(MediaPlaybackManager.getCurrentSongPosition())
    }

    private fun setDurations() {
        REMAINING_DURATION = 0L

        playingQueueViewModel.songs.value.let { songs ->
            val currentIndex = MediaPlaybackManager.getCurrentSongPosition()
            for (i in currentIndex + 1 until songs.size) {
                REMAINING_DURATION += songs[i].duration
            }
        }
    }

    private fun updateCountDown(seek: Long) {
        val remainingTimeInCurrentSong = (MediaPlaybackManager.getDuration() - seek).coerceAtLeast(0)
        val totalRemainingTime = remainingTimeInCurrentSong + REMAINING_DURATION
        headerBinding.hours.text = totalRemainingTime.toDynamicTimeString()
    }

    companion object {
        const val TAG = "PlayingQueue"

        private const val FIRST_LAUNCH_KEY = "first_launch"
        private const val FROM_PLAYER_KEY = "from_player"

        fun newInstance(fromPlayer: Boolean = false): PlayingQueue {
            val args = Bundle()
            args.putBoolean(FROM_PLAYER_KEY, fromPlayer)
            val fragment = PlayingQueue()
            fragment.arguments = args
            return fragment
        }
    }
}