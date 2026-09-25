package app.simple.felicity.ui.panels

import android.content.Intent
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import app.simple.felicity.R
import app.simple.felicity.activities.youtube.YoutubeLoginActivity
import app.simple.felicity.adapters.ui.lists.AdapterOnline
import app.simple.felicity.databinding.FragmentOnlineBinding
import app.simple.felicity.databinding.HeaderOnlineBinding
import app.simple.felicity.decorations.highlight.HighlightTextView
import app.simple.felicity.decorations.views.AppHeader
import app.simple.felicity.dialogs.app.JamendoClientIdDialog.Companion.showJamendoClientIdDialog
import app.simple.felicity.extensions.fragments.PanelFragment
import app.simple.felicity.preferences.JamendoPreferences
import app.simple.felicity.repository.online.OnlineSource
import app.simple.felicity.repository.online.OnlineTrack
import app.simple.felicity.repository.repositories.RadioBrowserRepository
import app.simple.felicity.shared.utils.ViewUtils.gone
import app.simple.felicity.shared.utils.ViewUtils.visible
import app.simple.felicity.viewmodels.panels.OnlineViewModel
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject
import kotlinx.coroutines.launch

/**
 * Search and playback panel for the four free online sources — Radio Browser, Jamendo,
 * Audius, and Internet Archive. Structurally a simplified sibling of [Search]: same
 * search-as-you-type header shape, but one flat result list instead of grouped sections,
 * and a source picker in place of a category filter.
 *
 * Tapping a result calls straight into [OnlineViewModel.play], which — per its own kdoc —
 * plays that single track immediately without touching the local-library queue.
 *
 * @author Hamza417
 */
@AndroidEntryPoint
class Online : PanelFragment() {

    private lateinit var binding: FragmentOnlineBinding
    private lateinit var headerBinding: HeaderOnlineBinding

    private var adapterOnline: AdapterOnline? = null

    @Inject
    lateinit var radioBrowserRepository: RadioBrowserRepository

    private val onlineViewModel: OnlineViewModel by viewModels()

    private val youtubeLoginLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) {
        updateYoutubeSignInLabel()
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        binding = FragmentOnlineBinding.inflate(inflater, container, false)
        headerBinding = HeaderOnlineBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        binding.recyclerView.requireAttachedMiniPlayer()
        binding.appHeader.setContentView(headerBinding.root)
        binding.appHeader.attachTo(binding.recyclerView, AppHeader.ScrollMode.HIDE_ON_SCROLL)
        binding.recyclerView.layoutManager = LinearLayoutManager(requireContext())

        setupAdapter()
        setupClickListeners()
        selectSource(onlineViewModel.selectedSource.value)

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                onlineViewModel.results.collect { results ->
                    adapterOnline?.updateItems(results)
                }
            }
        }

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                onlineViewModel.isLoading.collect { loading ->
                    if (loading) headerBinding.progress.visible() else headerBinding.progress.gone()
                    // Only judge "empty" once a fetch has actually finished — otherwise the
                    // empty state flashes briefly before trending/search results arrive.
                    if (!loading) {
                        val isEmpty = onlineViewModel.results.value.isEmpty()
                        if (isEmpty) binding.emptyState.visible() else binding.emptyState.gone()
                    }
                }
            }
        }

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                onlineViewModel.playbackError.collect { error ->
                    if (error != null) {
                        showWarning(error)
                        onlineViewModel.consumePlaybackError()
                    }
                }
            }
        }
    }

    override fun onDestroyView() {
        adapterOnline = null
        super.onDestroyView()
    }

    private fun setupAdapter() {
        adapterOnline = AdapterOnline(emptyList()).also { adapter ->
            adapter.callbacks = object : AdapterOnline.Callbacks {
                override fun onTrackClicked(track: OnlineTrack) {
                    onlineViewModel.play(track)
                }

                override fun onTrackLongClicked(track: OnlineTrack): Boolean {
                    if (track.source != OnlineSource.YOUTUBE) return false
                    onlineViewModel.downloadYoutubeTrack(track)
                    Toast.makeText(
                            requireContext(),
                            getString(app.simple.felicity.R.string.youtube_download_started, track.title),
                            Toast.LENGTH_SHORT
                    ).show()
                    return true
                }
            }
        }
        binding.recyclerView.adapter = adapterOnline
    }

    private fun setupClickListeners() {
        headerBinding.editText.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit

            override fun afterTextChanged(s: Editable?) {
                onlineViewModel.setSearchQuery(s?.toString().orEmpty())
            }
        })

        headerBinding.sourceRadioBrowser.setOnClickListener { selectSource(OnlineSource.RADIO_BROWSER) }
        headerBinding.addCustomRadio.setOnClickListener { showAddCustomRadioDialog() }
        headerBinding.sourceJamendo.setOnClickListener {
            selectSource(OnlineSource.JAMENDO)
            if (JamendoPreferences.hasClientId().not()) {
                childFragmentManager.showJamendoClientIdDialog()
            }
        }
        headerBinding.sourceAudius.setOnClickListener { selectSource(OnlineSource.AUDIUS) }
        headerBinding.sourceInternetArchive.setOnClickListener { selectSource(OnlineSource.INTERNET_ARCHIVE) }
        headerBinding.sourceYoutube.setOnClickListener { selectSource(OnlineSource.YOUTUBE) }

        headerBinding.youtubeSignIn.setOnClickListener {
            if (onlineViewModel.isYoutubeSignedIn) {
                onlineViewModel.signOutOfYoutube()
                updateYoutubeSignInLabel()
            } else {
                youtubeLoginLauncher.launch(Intent(requireContext(), YoutubeLoginActivity::class.java))
            }
        }
    }

    private fun updateYoutubeSignInLabel() {
        headerBinding.youtubeSignIn.text = getString(
                if (onlineViewModel.isYoutubeSignedIn) {
                    app.simple.felicity.R.string.youtube_sign_out
                } else {
                    app.simple.felicity.R.string.youtube_sign_in
                }
        )
    }

    private fun selectSource(source: OnlineSource) {
        onlineViewModel.setSelectedSource(source)
        highlightSelectedSource(source)

        if (source == OnlineSource.RADIO_BROWSER) {
            headerBinding.addCustomRadio.visible()
        } else {
            headerBinding.addCustomRadio.gone()
        }

        if (source == OnlineSource.YOUTUBE) {
            headerBinding.youtubeSignIn.visible()
            updateYoutubeSignInLabel()
        } else {
            headerBinding.youtubeSignIn.gone()
        }
    }

    private fun showAddCustomRadioDialog() {
        val layout = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(32, 16, 32, 16)
        }
        val inputName = EditText(requireContext()).apply { hint = "Station Name (e.g. My Favorite FM)" }
        val inputUrl = EditText(requireContext()).apply { hint = "Stream URL (http://...)" }
        layout.addView(inputName)
        layout.addView(inputUrl)

        MaterialAlertDialogBuilder(requireContext())
            .setTitle("Add Custom Radio Station")
            .setView(layout)
            .setPositiveButton("Add") { _, _ ->
                val name = inputName.text.toString().trim()
                val url = inputUrl.text.toString().trim()
                if (name.isNotBlank() && url.isNotBlank()) {
                    val customTrack = radioBrowserRepository.createCustomStation(name, url)
                    onlineViewModel.play(customTrack)
                }
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    /**
     * Dims every source chip except the active one — the same alpha-based selected-state
     * convention already used for the sleep-timer and repeat buttons elsewhere in this app,
     * rather than relying on [HighlightTextView]'s `isSelected` having a defined visual
     * effect, which nothing else in this codebase actually exercises.
     */
    private fun highlightSelectedSource(source: OnlineSource) {
        headerBinding.sourceRadioBrowser.alpha = if (source == OnlineSource.RADIO_BROWSER) 1f else 0.5f
        headerBinding.sourceJamendo.alpha = if (source == OnlineSource.JAMENDO) 1f else 0.5f
        headerBinding.sourceAudius.alpha = if (source == OnlineSource.AUDIUS) 1f else 0.5f
        headerBinding.sourceInternetArchive.alpha = if (source == OnlineSource.INTERNET_ARCHIVE) 1f else 0.5f
        headerBinding.sourceYoutube.alpha = if (source == OnlineSource.YOUTUBE) 1f else 0.5f
    }

    companion object {
        const val TAG = "Online"

        fun newInstance(): Online {
            val args = Bundle()
            val fragment = Online()
            fragment.arguments = args
            return fragment
        }
    }
}
