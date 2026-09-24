package app.simple.felicity.adapters.ui.lists

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import app.simple.felicity.R
import app.simple.felicity.databinding.AdapterStyleListBinding
import app.simple.felicity.decorations.overscroll.VerticalListViewHolder
import app.simple.felicity.repository.online.OnlineSource
import app.simple.felicity.repository.online.OnlineTrack
import app.simple.felicity.shared.utils.TimeUtils.toDynamicTimeString
import com.bumptech.glide.Glide

/**
 * Flat list of [OnlineTrack] search results from whichever [OnlineSource] is currently
 * selected in the Online panel. Reuses the same generic row layout
 * ([AdapterStyleListBinding]) the local-library bookmark list uses — title, a secondary
 * detail line (artist/station description), a tertiary line (duration when known, the
 * source's display name otherwise), and cover art loaded directly from [OnlineTrack.artworkUrl].
 *
 * @author Hamza417
 */
class AdapterOnline(
        private var items: List<OnlineTrack>
) : RecyclerView.Adapter<VerticalListViewHolder>() {

    var callbacks: Callbacks? = null

    override fun getItemCount(): Int = items.size

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VerticalListViewHolder {
        return TrackHolder(AdapterStyleListBinding.inflate(LayoutInflater.from(parent.context), parent, false))
    }

    override fun onBindViewHolder(holder: VerticalListViewHolder, position: Int) {
        (holder as TrackHolder).bind(items[position])
    }

    /** Replaces the list wholesale — result sets are small enough per search that a full rebind is cheap and simple. */
    fun updateItems(newItems: List<OnlineTrack>) {
        items = newItems
        notifyDataSetChanged()
    }

    inner class TrackHolder(private val binding: AdapterStyleListBinding) :
            VerticalListViewHolder(binding.root) {

        fun bind(item: OnlineTrack) {
            binding.title.text = item.title
            binding.secondaryDetail.text = item.subtitle

            binding.tertiaryDetail.text = item.durationMs?.toDynamicTimeString()
                ?: context.getString(sourceLabelRes(item.source))

            Glide.with(binding.cover)
                .load(item.artworkUrl)
                .placeholder(R.mipmap.ic_launcher)
                .error(R.mipmap.ic_launcher)
                .into(binding.cover)

            binding.container.setOnClickListener {
                callbacks?.onTrackClicked(item)
            }

            binding.container.setOnLongClickListener {
                callbacks?.onTrackLongClicked(item) ?: false
            }
        }
    }

    private fun sourceLabelRes(source: OnlineSource): Int = when (source) {
        OnlineSource.RADIO_BROWSER -> R.string.online_source_radio_browser
        OnlineSource.JAMENDO -> R.string.online_source_jamendo
        OnlineSource.AUDIUS -> R.string.online_source_audius
        OnlineSource.INTERNET_ARCHIVE -> R.string.online_source_internet_archive
        OnlineSource.YOUTUBE -> R.string.online_source_youtube
    }

    interface Callbacks {
        fun onTrackClicked(track: OnlineTrack)

        /** Only meaningful for [OnlineSource.YOUTUBE] rows — see `Online.setupAdapter`. */
        fun onTrackLongClicked(track: OnlineTrack): Boolean
    }
}
