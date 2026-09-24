package app.simple.felicity.adapters.ui.lists

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import app.simple.felicity.R
import app.simple.felicity.databinding.AdapterStyleListBinding
import app.simple.felicity.decorations.overscroll.VerticalListViewHolder
import app.simple.felicity.repository.youtube.YoutubePlaylist
import com.bumptech.glide.Glide

/** The signed-in user's YouTube Music library playlists — see `YoutubeSection`'s "Playlists" tab. */
class AdapterYoutubePlaylist(
    private var items: List<YoutubePlaylist>,
) : RecyclerView.Adapter<VerticalListViewHolder>() {

    var callbacks: Callbacks? = null

    override fun getItemCount(): Int = items.size

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VerticalListViewHolder {
        return PlaylistHolder(AdapterStyleListBinding.inflate(LayoutInflater.from(parent.context), parent, false))
    }

    override fun onBindViewHolder(holder: VerticalListViewHolder, position: Int) {
        (holder as PlaylistHolder).bind(items[position])
    }

    fun updateItems(newItems: List<YoutubePlaylist>) {
        items = newItems
        notifyDataSetChanged()
    }

    inner class PlaylistHolder(private val binding: AdapterStyleListBinding) :
        VerticalListViewHolder(binding.root) {

        fun bind(item: YoutubePlaylist) {
            binding.title.text = item.title
            binding.secondaryDetail.text = item.subtitle
            binding.tertiaryDetail.text = ""

            Glide.with(binding.cover)
                .load(item.thumbnailUrl)
                .placeholder(R.mipmap.ic_launcher)
                .error(R.mipmap.ic_launcher)
                .into(binding.cover)

            binding.container.setOnClickListener {
                callbacks?.onPlaylistClicked(item)
            }
        }
    }

    interface Callbacks {
        fun onPlaylistClicked(playlist: YoutubePlaylist)
    }
}
