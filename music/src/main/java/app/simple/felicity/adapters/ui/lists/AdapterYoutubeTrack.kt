package app.simple.felicity.adapters.ui.lists

import android.graphics.Typeface
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import app.simple.felicity.R
import app.simple.felicity.databinding.AdapterYoutubeTrackBinding
import app.simple.felicity.decorations.overscroll.VerticalListViewHolder
import app.simple.felicity.repository.online.OnlineTrack
import app.simple.felicity.shared.utils.TimeUtils.toDynamicTimeString
import com.bumptech.glide.Glide
import java.util.Collections

/**
 * Standalone-YouTube-tab equivalent of [AdapterOnline], with an inline download
 * button + live progress bar per row instead of a long-press action. Progress per
 * video ID is looked up from [downloadProgress] on every bind, which the fragment
 * keeps updated from WorkManager's live progress observation (see `YoutubeSection`).
 *
 * Supports drag-to-reorder ([onItemMove]/[onDragFinished]) for when this is showing
 * an opened playlist's tracks — see `YoutubeSection`'s `ItemTouchHelper` wiring, which
 * only attaches while a playlist (not a search-results list) is being shown.
 */
class AdapterYoutubeTrack(
    private var items: List<OnlineTrack>,
) : RecyclerView.Adapter<VerticalListViewHolder>() {

    var callbacks: Callbacks? = null

    /** Called after a drag-and-drop reorder settles, with the new full list order. */
    var onItemsReordered: ((List<OnlineTrack>) -> Unit)? = null

    /** videoId -> 0..100 while downloading, 100 briefly on success, -1 on failure. Absent = idle. */
    var downloadProgress: Map<String, Int> = emptyMap()
        set(value) {
            field = value
            notifyDataSetChanged()
        }

    /** (source name, id) of the track currently playing, for a "now playing" highlight. Null = none of these rows. */
    var nowPlayingId: Pair<String, String>? = null
        set(value) {
            field = value
            notifyDataSetChanged()
        }

    var isSelectionMode: Boolean = false
        set(value) {
            field = value
            if (!value) selectedTrackIds.clear()
            notifyDataSetChanged()
        }

    val selectedTrackIds: MutableSet<String> = mutableSetOf()

    var onSelectionChanged: ((Int) -> Unit)? = null

    fun selectAll() {
        selectedTrackIds.clear()
        selectedTrackIds.addAll(items.map { it.id })
        notifyDataSetChanged()
        onSelectionChanged?.invoke(selectedTrackIds.size)
    }

    fun clearSelection() {
        isSelectionMode = false
        selectedTrackIds.clear()
        notifyDataSetChanged()
        onSelectionChanged?.invoke(0)
    }

    fun getSelectedTracks(): List<OnlineTrack> = items.filter { it.id in selectedTrackIds }

    override fun getItemCount(): Int = items.size

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VerticalListViewHolder {
        return TrackHolder(AdapterYoutubeTrackBinding.inflate(LayoutInflater.from(parent.context), parent, false))
    }

    override fun onBindViewHolder(holder: VerticalListViewHolder, position: Int) {
        (holder as TrackHolder).bind(items[position])
    }

    fun updateItems(newItems: List<OnlineTrack>) {
        items = newItems
        notifyDataSetChanged()
    }

    /** Called by the ItemTouchHelper while a drag is in progress, to live-reorder the visible list. */
    fun onItemMove(fromPosition: Int, toPosition: Int) {
        val mutable = items.toMutableList()
        Collections.swap(mutable, fromPosition, toPosition)
        items = mutable
        notifyItemMoved(fromPosition, toPosition)
    }

    /** Called by the ItemTouchHelper once the drag gesture ends. */
    fun onDragFinished() {
        onItemsReordered?.invoke(items)
    }

    inner class TrackHolder(private val binding: AdapterYoutubeTrackBinding) :
        VerticalListViewHolder(binding.root) {

        fun bind(item: OnlineTrack) {
            binding.title.text = item.title
            binding.secondaryDetail.text = item.subtitle
            binding.tertiaryDetail.text = item.durationMs?.toDynamicTimeString().orEmpty()

            val isPlaying = nowPlayingId?.let { (source, id) -> item.source.name == source && item.id == id } == true
            val isSelected = isSelectionMode && item.id in selectedTrackIds
            binding.container.isSelected = isSelected

            binding.root.alpha = if (isPlaying) 1f else 0.85f
            binding.title.setTypeface(binding.title.typeface, if (isPlaying) Typeface.BOLD else Typeface.NORMAL)

            Glide.with(binding.cover)
                .load(item.artworkUrl)
                .placeholder(R.mipmap.ic_launcher)
                .error(R.mipmap.ic_launcher)
                .into(binding.cover)

            binding.container.setOnClickListener {
                if (isSelectionMode) {
                    if (item.id in selectedTrackIds) {
                        selectedTrackIds.remove(item.id)
                        if (selectedTrackIds.isEmpty()) {
                            isSelectionMode = false
                        }
                    } else {
                        selectedTrackIds.add(item.id)
                    }
                    notifyItemChanged(bindingAdapterPosition)
                    onSelectionChanged?.invoke(selectedTrackIds.size)
                } else {
                    callbacks?.onTrackClicked(item)
                }
            }

            binding.container.setOnLongClickListener {
                if (!isSelectionMode) {
                    isSelectionMode = true
                    selectedTrackIds.add(item.id)
                    notifyItemChanged(bindingAdapterPosition)
                    onSelectionChanged?.invoke(selectedTrackIds.size)
                    true
                } else false
            }

            val progress = downloadProgress[item.id]
            if (progress != null && progress in 0..99) {
                binding.downloadProgress.visibility = View.VISIBLE
                binding.downloadProgress.progress = progress
                binding.downloadButton.isEnabled = false
                binding.downloadButton.alpha = 0.4f
            } else {
                binding.downloadProgress.visibility = View.GONE
                binding.downloadButton.isEnabled = true
                binding.downloadButton.alpha = 1f
            }

            binding.downloadButton.setOnClickListener {
                callbacks?.onDownloadClicked(item)
            }

            binding.moreOptions.setOnClickListener {
                callbacks?.onMoreOptionsClicked(item)
            }
        }
    }

    interface Callbacks {
        fun onTrackClicked(track: OnlineTrack)
        fun onDownloadClicked(track: OnlineTrack)
        fun onMoreOptionsClicked(track: OnlineTrack)
    }
}
