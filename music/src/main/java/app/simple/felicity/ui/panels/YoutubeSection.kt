package app.simple.felicity.ui.panels

import android.content.Intent
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.LinearLayoutManager
import app.simple.felicity.R
import app.simple.felicity.activities.youtube.YoutubeLoginActivity
import app.simple.felicity.adapters.ui.lists.AdapterYoutubeFeed
import app.simple.felicity.adapters.ui.lists.AdapterYoutubeFeed.FeedRow
import app.simple.felicity.adapters.ui.lists.AdapterYoutubePlaylist
import app.simple.felicity.adapters.ui.lists.AdapterYoutubeTrack
import app.simple.felicity.databinding.FragmentYoutubeBinding
import app.simple.felicity.databinding.HeaderYoutubeBinding
import app.simple.felicity.decorations.views.AppHeader
import app.simple.felicity.dialogs.youtube.YoutubeTrackMenu
import app.simple.felicity.extensions.fragments.PanelFragment
import app.simple.felicity.repository.online.OnlineTrack
import app.simple.felicity.repository.repositories.toOnlineTrack
import app.simple.felicity.repository.youtube.YoutubeDetailPage
import app.simple.felicity.repository.youtube.YoutubeItem
import app.simple.felicity.repository.youtube.YoutubePlaylist
import app.simple.felicity.repository.youtube.YoutubeShelf
import app.simple.felicity.repository.youtube.YoutubeSongItem
import app.simple.felicity.shared.utils.ViewUtils.gone
import app.simple.felicity.shared.utils.ViewUtils.visible
import app.simple.felicity.viewmodels.panels.YoutubeLibraryKind
import app.simple.felicity.viewmodels.panels.YoutubeSearchFilter
import app.simple.felicity.viewmodels.panels.YoutubeTab
import app.simple.felicity.viewmodels.panels.YoutubeViewModel
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

/**
 * A standalone YouTube tab, separate from the [Online] panel's four ToS-compliant
 * sources — see [app.simple.felicity.repository.online.OnlineSource.YOUTUBE]'s kdoc for
 * why it's treated as its own thing. Umihi-style feature set: search, sign-in, browsing
 * your own library playlists, playback, and downloading with a live per-row progress bar.
 *
 * @author Hamza417 (adapted)
 */
@AndroidEntryPoint
class YoutubeSection : PanelFragment() {

    private lateinit var binding: FragmentYoutubeBinding
    private lateinit var headerBinding: HeaderYoutubeBinding

    private var trackAdapter: AdapterYoutubeTrack? = null
    private var playlistAdapter: AdapterYoutubePlaylist? = null
    private var feedAdapter: AdapterYoutubeFeed? = null
    private var feedHasContent = false
    private var itemTouchHelper: ItemTouchHelper? = null

    private val youtubeViewModel: YoutubeViewModel by viewModels()

    private val youtubeLoginLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) {
        updateSignInLabel()
        // The account changes what Home recommends and what the library holds.
        youtubeViewModel.loadHome(force = true)
        youtubeViewModel.loadLibrary()
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        binding = FragmentYoutubeBinding.inflate(inflater, container, false)
        headerBinding = HeaderYoutubeBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        binding.recyclerView.requireAttachedMiniPlayer()
        binding.appHeader.setContentView(headerBinding.root)
        binding.appHeader.attachTo(binding.recyclerView, AppHeader.ScrollMode.HIDE_ON_SCROLL)
        binding.recyclerView.layoutManager = LinearLayoutManager(requireContext())

        setupAdapters()
        setupClickListeners()
        updateSignInLabel()
        selectTab(youtubeViewModel.activeTab.value)

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                youtubeViewModel.searchResults.collect { results ->
                    trackAdapter?.updateItems(results)
                    if (youtubeViewModel.activeTab.value == YoutubeTab.SEARCH) updateEmptyState()
                }
            }
        }

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                youtubeViewModel.playlists.collect { playlists ->
                    playlistAdapter?.updateItems(playlists)
                    if (youtubeViewModel.activeTab.value == YoutubeTab.PLAYLISTS &&
                        youtubeViewModel.openedPlaylist.value == null
                    ) updateEmptyState()
                }
            }
        }

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                youtubeViewModel.playlistTracks.collect { tracks ->
                    if (youtubeViewModel.openedPlaylist.value != null) {
                        trackAdapter?.updateItems(tracks)
                        updateEmptyState()
                    }
                }
            }
        }

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                youtubeViewModel.openedPlaylist.collect { playlist ->
                    switchListForPlaylistState(playlist)
                }
            }
        }

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                youtubeViewModel.isLoading.collect { loading ->
                    if (loading) binding.loadingIndicator.visible() else binding.loadingIndicator.gone()
                    if (!loading) updateEmptyState()
                }
            }
        }

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                combine(
                        youtubeViewModel.homeShelves, youtubeViewModel.homeChips, youtubeViewModel.homeChipParams,
                        youtubeViewModel.searchItems, youtubeViewModel.searchFilter
                ) { _, _, _, _, _ -> Unit }.collect { renderFeed() }
            }
        }

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                combine(
                        youtubeViewModel.libraryItems, youtubeViewModel.libraryKind, youtubeViewModel.detail
                ) { _, _, detail -> detail }.collect { detail ->
                    updateBackButton(detail)
                    renderFeed()
                }
            }
        }

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                youtubeViewModel.downloadProgress.collect { progress ->
                    trackAdapter?.downloadProgress = progress
                }
            }
        }

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                youtubeViewModel.playbackError.collect { error ->
                    if (error != null) {
                        showWarning(error)
                        youtubeViewModel.consumePlaybackError()
                    }
                }
            }
        }

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                combine(
                        youtubeViewModel.isDownloadingBatch,
                        youtubeViewModel.downloadProgress
                ) { isBatch, progress ->
                    isBatch || progress.values.any { it in 0..99 }
                }.collect { downloading ->
                    if (downloading) headerBinding.cancelDownload.visible() else headerBinding.cancelDownload.gone()
                }
            }
        }

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                combine(
                        youtubeViewModel.onlineQueueFlow,
                        youtubeViewModel.onlineQueueIndexFlow
                ) { queue, index -> queue.getOrNull(index) }
                    .collect { nowPlaying ->
                        trackAdapter?.nowPlayingId = nowPlaying?.let { it.source.name to it.id }
                        feedAdapter?.nowPlayingId = nowPlaying?.id
                    }
            }
        }
    }

    override fun onDestroyView() {
        itemTouchHelper?.attachToRecyclerView(null)
        itemTouchHelper = null
        trackAdapter = null
        playlistAdapter = null
        feedAdapter = null
        super.onDestroyView()
    }

    private fun showDownloadFormatDialog(onFormatSelected: (String) -> Unit) {
        val formats = arrayOf(
            "M4A (Original Stream — Best Quality, No Re-encoding)",
            "MP3 (Transcoded — Best Compatibility)",
            "FLAC (Re-encoded Lossless Container)"
        )
        val formatKeys = arrayOf("m4a", "mp3", "flac")

        MaterialAlertDialogBuilder(requireContext())
            .setTitle("Select Download Format")
            .setItems(formats) { _, which ->
                onFormatSelected(formatKeys[which])
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun setupAdapters() {
        trackAdapter = AdapterYoutubeTrack(emptyList()).also { adapter ->
            adapter.callbacks = object : AdapterYoutubeTrack.Callbacks {
                override fun onTrackClicked(track: OnlineTrack) {
                    youtubeViewModel.play(track)
                }

                override fun onDownloadClicked(track: OnlineTrack) {
                    showDownloadFormatDialog { chosenFormat ->
                        youtubeViewModel.download(track, format = chosenFormat)
                        Toast.makeText(
                            requireContext(),
                            getString(R.string.youtube_download_started, track.title),
                            Toast.LENGTH_SHORT
                        ).show()
                    }
                }

                override fun onMoreOptionsClicked(track: OnlineTrack) {
                    YoutubeTrackMenu.show(childFragmentManager, track, songMenuExtras(track.id)) { chosenTrack ->
                        showDownloadFormatDialog { chosenFormat ->
                            youtubeViewModel.download(chosenTrack, format = chosenFormat)
                            Toast.makeText(
                                requireContext(),
                                getString(R.string.youtube_download_started, chosenTrack.title),
                                Toast.LENGTH_SHORT
                            ).show()
                        }
                    }
                }
            }

            adapter.onSelectionChanged = { count ->
                if (count > 0) {
                    headerBinding.selectionContainer.visible()
                    headerBinding.downloadSelected.text = getString(R.string.download_selected, count)
                } else {
                    headerBinding.selectionContainer.gone()
                }
            }

            adapter.onItemsReordered = { newOrder ->
                youtubeViewModel.reorderOnlineQueue(newOrder)
            }
        }

        feedAdapter = AdapterYoutubeFeed().also { adapter ->
            adapter.callbacks = object : AdapterYoutubeFeed.Callbacks {
                override fun onChip(key: String) {
                    when {
                        key.startsWith("home:") -> youtubeViewModel.loadHome(key.removePrefix("home:").ifEmpty { null }, force = true)
                        key.startsWith("filter:") -> youtubeViewModel.setSearchFilter(YoutubeSearchFilter.valueOf(key.removePrefix("filter:")))
                        key.startsWith("lib:") -> youtubeViewModel.setLibraryKind(YoutubeLibraryKind.valueOf(key.removePrefix("lib:")))
                    }
                }

                override fun onSong(song: YoutubeSongItem, songs: List<YoutubeSongItem>) = youtubeViewModel.playSong(song, songs)
                override fun onOpen(item: YoutubeItem) = youtubeViewModel.openItem(item)
                override fun onSongMore(song: YoutubeSongItem) = showSongMenu(song)
                override fun onShelfMore(shelf: YoutubeShelf) = Unit

                override fun onPlay(page: YoutubeDetailPage, shuffle: Boolean) = youtubeViewModel.playAll(page.tracks, shuffle)

                override fun onRadio(page: YoutubeDetailPage) =
                    youtubeViewModel.startRadio(page.tracks.firstOrNull()?.videoId, page.radioPlaylistId)

                override fun onSubscribe(page: YoutubeDetailPage) = youtubeViewModel.toggleSubscribe(page)
                override fun onNearEnd() = youtubeViewModel.loadMore()
            }
        }

        playlistAdapter = AdapterYoutubePlaylist(emptyList()).also { adapter ->
            adapter.callbacks = object : AdapterYoutubePlaylist.Callbacks {
                override fun onPlaylistClicked(playlist: YoutubePlaylist) {
                    youtubeViewModel.openPlaylist(playlist)
                }
            }
        }

        itemTouchHelper = ItemTouchHelper(object : ItemTouchHelper.SimpleCallback(
                ItemTouchHelper.UP or ItemTouchHelper.DOWN, 0
        ) {
            override fun onMove(
                    recyclerView: androidx.recyclerview.widget.RecyclerView,
                    viewHolder: androidx.recyclerview.widget.RecyclerView.ViewHolder,
                    target: androidx.recyclerview.widget.RecyclerView.ViewHolder
            ): Boolean {
                trackAdapter?.onItemMove(viewHolder.bindingAdapterPosition, target.bindingAdapterPosition)
                return true
            }

            override fun onSwiped(viewHolder: androidx.recyclerview.widget.RecyclerView.ViewHolder, direction: Int) = Unit

            override fun clearView(
                    recyclerView: androidx.recyclerview.widget.RecyclerView,
                    viewHolder: androidx.recyclerview.widget.RecyclerView.ViewHolder
            ) {
                super.clearView(recyclerView, viewHolder)
                trackAdapter?.onDragFinished()
            }
        })

        binding.recyclerView.adapter = trackAdapter
    }

    private fun setupClickListeners() {
        headerBinding.youtubeSearchInput.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
            override fun afterTextChanged(s: Editable?) {
                val query = s?.toString().orEmpty()
                youtubeViewModel.search(query)
                youtubeViewModel.fetchSearchSuggestions(query)
            }
        })

        headerBinding.tabHome.setOnClickListener { selectTab(YoutubeTab.HOME) }
        headerBinding.tabSearch.setOnClickListener { selectTab(YoutubeTab.SEARCH) }
        headerBinding.tabPlaylists.setOnClickListener { selectTab(YoutubeTab.PLAYLISTS) }
        headerBinding.tabDownloaded.setOnClickListener { selectTab(YoutubeTab.DOWNLOADED) }

        headerBinding.importPlaylistUrl.setOnClickListener {
            val input = EditText(requireContext()).apply {
                hint = "Paste YouTube playlist URL or ID"
            }
            MaterialAlertDialogBuilder(requireContext())
                .setTitle("Import YouTube Playlist")
                .setView(input)
                .setPositiveButton("Import") { _, _ ->
                    val url = input.text.toString().trim()
                    if (url.isNotBlank()) {
                        youtubeViewModel.importPlaylistByUrl(url)
                    }
                }
                .setNegativeButton(R.string.cancel, null)
                .show()
        }

        headerBinding.youtubeSignIn.setOnClickListener {
            if (youtubeViewModel.isSignedIn) {
                youtubeViewModel.signOut()
                updateSignInLabel()
            } else {
                youtubeLoginLauncher.launch(Intent(requireContext(), YoutubeLoginActivity::class.java))
            }
        }

        headerBinding.playlistBack.setOnClickListener {
            if (!youtubeViewModel.closeDetail()) youtubeViewModel.closePlaylist()
        }

        headerBinding.downloadAll.setOnClickListener {
            showDownloadFormatDialog { chosenFormat ->
                youtubeViewModel.downloadOpenedPlaylist(format = chosenFormat)
                Toast.makeText(requireContext(), R.string.youtube_download_all, Toast.LENGTH_SHORT).show()
            }
        }

        headerBinding.cancelDownload.setOnClickListener {
            youtubeViewModel.cancelDownloads()
            Toast.makeText(requireContext(), R.string.downloads_cancelled, Toast.LENGTH_SHORT).show()
        }

        headerBinding.selectAll.setOnClickListener {
            trackAdapter?.selectAll()
        }

        headerBinding.downloadSelected.setOnClickListener {
            val selected = trackAdapter?.getSelectedTracks().orEmpty()
            if (selected.isNotEmpty()) {
                showDownloadFormatDialog { chosenFormat ->
                    youtubeViewModel.downloadTracks(selected, format = chosenFormat)
                    Toast.makeText(
                        requireContext(),
                        getString(R.string.youtube_download_started, "${selected.size} songs"),
                        Toast.LENGTH_SHORT
                    ).show()
                    trackAdapter?.clearSelection()
                }
            }
        }

        headerBinding.cancelSelection.setOnClickListener {
            trackAdapter?.clearSelection()
        }
    }

    private fun selectTab(tab: YoutubeTab) {
        trackAdapter?.clearSelection()
        youtubeViewModel.setActiveTab(tab)
        headerBinding.tabHome.alpha = if (tab == YoutubeTab.HOME) 1f else 0.5f
        headerBinding.tabSearch.alpha = if (tab == YoutubeTab.SEARCH) 1f else 0.5f
        headerBinding.tabPlaylists.alpha = if (tab == YoutubeTab.PLAYLISTS) 1f else 0.5f
        headerBinding.tabDownloaded.alpha = if (tab == YoutubeTab.DOWNLOADED) 1f else 0.5f

        headerBinding.youtubeSearchInput.visibility = if (tab == YoutubeTab.SEARCH) View.VISIBLE else View.GONE

        when (tab) {
            YoutubeTab.DOWNLOADED -> {
                itemTouchHelper?.attachToRecyclerView(null)
                binding.recyclerView.adapter = trackAdapter
                val completedIds = youtubeViewModel.downloadProgress.value.filter { it.value == 100 }.keys
                val downloadedTracks = (youtubeViewModel.searchResults.value + youtubeViewModel.playlistTracks.value)
                    .filter { it.id in completedIds }
                    .distinctBy { it.id }
                trackAdapter?.updateItems(downloadedTracks)
                updateBackButton(null)
            }
            else -> {
                if (youtubeViewModel.openedPlaylist.value != null && tab == YoutubeTab.PLAYLISTS) {
                    switchListForPlaylistState(youtubeViewModel.openedPlaylist.value)
                } else {
                    itemTouchHelper?.attachToRecyclerView(null)
                    binding.recyclerView.adapter = feedAdapter
                    updateBackButton(youtubeViewModel.detail.value)
                    renderFeed()
                }
            }
        }
        updateEmptyState()
    }

    /** Rebuilds the rows for the page that is showing: a detail page if one is open, else the active tab. */
    private fun renderFeed() {
        val adapter = feedAdapter ?: return
        if (binding.recyclerView.adapter !== adapter) return
        val vm = youtubeViewModel
        val rows = mutableListOf<FeedRow>()
        val detail = vm.detail.value

        fun entries(items: List<YoutubeItem>) {
            val songs = items.filterIsInstance<YoutubeSongItem>()
            items.forEach { rows += FeedRow.Entry(it, songs) }
        }

        if (detail != null) {
            rows += FeedRow.Detail(detail)
            detail.tracks.forEach { rows += FeedRow.Entry(it, detail.tracks) }
            detail.shelves.forEach { rows += FeedRow.Shelf(it) }
        } else {
            when (vm.activeTab.value) {
                YoutubeTab.HOME -> {
                    val chosen = vm.homeChipParams.value
                    val chips = mutableListOf(AdapterYoutubeFeed.Chip("home:", getString(R.string.youtube_tab_home), chosen == null))
                    vm.homeChips.value.forEach { chips += AdapterYoutubeFeed.Chip("home:${it.params.orEmpty()}", it.title, it.params != null && it.params == chosen) }
                    if (chips.size > 1) rows += FeedRow.Chips(chips)
                    vm.homeShelves.value.forEach { rows += FeedRow.Shelf(it) }
                }
                YoutubeTab.SEARCH -> {
                    rows += FeedRow.Chips(YoutubeSearchFilter.values().map {
                        AdapterYoutubeFeed.Chip("filter:${it.name}", it.label, it == vm.searchFilter.value)
                    })
                    entries(vm.searchItems.value)
                }
                YoutubeTab.PLAYLISTS -> {
                    rows += FeedRow.Chips(YoutubeLibraryKind.values().map {
                        AdapterYoutubeFeed.Chip("lib:${it.name}", it.label, it == vm.libraryKind.value)
                    })
                    entries(vm.libraryItems.value)
                }
                YoutubeTab.DOWNLOADED -> Unit
            }
        }
        feedHasContent = rows.any { it !is FeedRow.Chips }
        adapter.submit(rows)
        updateEmptyState()
    }

    private fun updateBackButton(detail: YoutubeDetailPage?) {
        if (youtubeViewModel.openedPlaylist.value != null) return
        if (detail != null) {
            headerBinding.playlistBack.visible()
            headerBinding.playlistBack.text = getString(R.string.youtube_back)
        } else {
            headerBinding.playlistBack.gone()
        }
    }

    private fun songMenuExtras(videoId: String): List<Pair<String, () -> Unit>> = listOf(
        getString(R.string.youtube_menu_radio) to { youtubeViewModel.startRadio(videoId) },
        getString(R.string.youtube_menu_like) to { youtubeViewModel.like(videoId) },
        getString(R.string.youtube_menu_add_to_playlist) to { pickPlaylistFor(videoId) },
    )

    private fun showSongMenu(song: YoutubeSongItem) {
        val track = song.toOnlineTrack()
        YoutubeTrackMenu.show(childFragmentManager, track, songMenuExtras(song.videoId)) { chosen ->
            showDownloadFormatDialog { format ->
                youtubeViewModel.download(chosen, format = format)
                Toast.makeText(requireContext(), getString(R.string.youtube_download_started, chosen.title), Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun pickPlaylistFor(videoId: String) {
        viewLifecycleOwner.lifecycleScope.launch {
            val lists = youtubeViewModel.libraryPlaylistsForPicker()
            if (lists.isEmpty()) {
                Toast.makeText(requireContext(), R.string.youtube_empty_library, Toast.LENGTH_SHORT).show()
                return@launch
            }
            MaterialAlertDialogBuilder(requireContext())
                .setTitle(R.string.youtube_pick_playlist)
                .setItems(lists.map { it.title }.toTypedArray()) { _, which ->
                    youtubeViewModel.addToPlaylist(lists[which].playlistId, videoId)
                }
                .setNegativeButton(R.string.cancel, null)
                .show()
        }
    }

    private fun switchListForPlaylistState(playlist: YoutubePlaylist?) {
        if (youtubeViewModel.activeTab.value != YoutubeTab.PLAYLISTS) return

        if (playlist == null) {
            headerBinding.downloadAll.gone()
            itemTouchHelper?.attachToRecyclerView(null)
            binding.recyclerView.adapter = feedAdapter
            updateBackButton(youtubeViewModel.detail.value)
            renderFeed()
        } else {
            headerBinding.playlistBack.visible()
            headerBinding.playlistBack.text = getString(R.string.youtube_back_to_playlists_named, playlist.title)
            headerBinding.downloadAll.visible()
            binding.recyclerView.adapter = trackAdapter
            trackAdapter?.updateItems(youtubeViewModel.playlistTracks.value)
            // Reordering (drag/drop) only makes sense for an actual playlist's own track
            // order, not a search-results list — attached here, detached above.
            itemTouchHelper?.attachToRecyclerView(binding.recyclerView)
        }
        updateEmptyState()
    }

    private fun updateSignInLabel() {
        headerBinding.youtubeSignIn.text = getString(
            if (youtubeViewModel.isSignedIn) R.string.youtube_sign_out else R.string.youtube_sign_in
        )
    }

    private fun updateEmptyState() {
        if (youtubeViewModel.isLoading.value) {
            binding.emptyState.gone()
            return
        }

        val isEmpty = when {
            binding.recyclerView.adapter === feedAdapter -> !feedHasContent
            binding.recyclerView.adapter === trackAdapter && youtubeViewModel.openedPlaylist.value != null ->
                youtubeViewModel.playlistTracks.value.isEmpty()
            else -> (trackAdapter?.itemCount ?: 0) == 0
        }

        if (isEmpty) binding.emptyState.visible() else binding.emptyState.gone()
    }

    companion object {
        const val TAG = "YoutubeSection"

        fun newInstance(): YoutubeSection {
            val args = Bundle()
            val fragment = YoutubeSection()
            fragment.arguments = args
            return fragment
        }
    }
}
