package app.simple.felicity.adapters.ui.lists

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import app.simple.felicity.R
import app.simple.felicity.databinding.AdapterYoutubeCardBinding
import app.simple.felicity.databinding.AdapterYoutubeChipsBinding
import app.simple.felicity.databinding.AdapterYoutubeDetailBinding
import app.simple.felicity.databinding.AdapterYoutubeItemBinding
import app.simple.felicity.databinding.AdapterYoutubeShelfBinding
import app.simple.felicity.decorations.highlight.HighlightTextView
import app.simple.felicity.repository.youtube.YoutubeAlbumItem
import app.simple.felicity.repository.youtube.YoutubeArtistItem
import app.simple.felicity.repository.youtube.YoutubeDetailPage
import app.simple.felicity.repository.youtube.YoutubeItem
import app.simple.felicity.repository.youtube.YoutubeShelf
import app.simple.felicity.repository.youtube.YoutubeSongItem
import com.bumptech.glide.Glide

/**
 * One adapter for every YouTube Music page: the Home feed (chips plus shelves of cards and the Quick
 * picks list), search results, library listings and artist / album / playlist pages. The fragment builds
 * a flat list of [FeedRow]s; this class only knows how to draw each kind of row.
 */
class AdapterYoutubeFeed : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

    sealed interface FeedRow {
        /** A row of filter / mood chips; [key] comes back in [Callbacks.onChip]. */
        data class Chips(val chips: List<Chip>) : FeedRow

        /** A shelf: title with a horizontal row of cards, or a vertical list when it is Quick picks. */
        data class Shelf(val shelf: YoutubeShelf) : FeedRow

        /** A single result row. [songs] is the list a tapped song is queued with. */
        data class Entry(val item: YoutubeItem, val songs: List<YoutubeSongItem>) : FeedRow

        /** Artist / album / playlist page header. */
        data class Detail(val page: YoutubeDetailPage) : FeedRow
    }

    data class Chip(val key: String, val label: String, val selected: Boolean)

    interface Callbacks {
        fun onChip(key: String)
        fun onSong(song: YoutubeSongItem, songs: List<YoutubeSongItem>)
        fun onOpen(item: YoutubeItem)
        fun onSongMore(song: YoutubeSongItem)
        fun onShelfMore(shelf: YoutubeShelf)
        fun onPlay(page: YoutubeDetailPage, shuffle: Boolean)
        fun onRadio(page: YoutubeDetailPage)
        fun onSubscribe(page: YoutubeDetailPage)
        fun onNearEnd()
    }

    var callbacks: Callbacks? = null
    private var rows: List<FeedRow> = emptyList()

    /** videoId of the song playing now, bolded in rows. */
    var nowPlayingId: String? = null
        set(value) {
            if (field == value) return
            field = value
            notifyDataSetChanged()
        }

    fun submit(newRows: List<FeedRow>) {
        rows = newRows
        notifyDataSetChanged()
    }

    override fun getItemCount() = rows.size

    override fun getItemViewType(position: Int) = when (rows[position]) {
        is FeedRow.Chips -> TYPE_CHIPS
        is FeedRow.Shelf -> TYPE_SHELF
        is FeedRow.Entry -> TYPE_ENTRY
        is FeedRow.Detail -> TYPE_DETAIL
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val inflater = LayoutInflater.from(parent.context)
        return when (viewType) {
            TYPE_CHIPS -> ChipsHolder(AdapterYoutubeChipsBinding.inflate(inflater, parent, false))
            TYPE_SHELF -> ShelfHolder(AdapterYoutubeShelfBinding.inflate(inflater, parent, false))
            TYPE_DETAIL -> DetailHolder(AdapterYoutubeDetailBinding.inflate(inflater, parent, false))
            else -> EntryHolder(AdapterYoutubeItemBinding.inflate(inflater, parent, false))
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        when (val row = rows[position]) {
            is FeedRow.Chips -> (holder as ChipsHolder).bind(row)
            is FeedRow.Shelf -> (holder as ShelfHolder).bind(row.shelf)
            is FeedRow.Entry -> (holder as EntryHolder).bind(row)
            is FeedRow.Detail -> (holder as DetailHolder).bind(row.page)
        }
        if (position >= rows.size - 4) callbacks?.onNearEnd()
    }

    private fun load(view: android.widget.ImageView, url: String) {
        Glide.with(view).load(url.ifBlank { null }).placeholder(R.mipmap.ic_launcher).error(R.mipmap.ic_launcher).into(view)
    }

    // ------------------------------------------------------------------ holders

    private inner class ChipsHolder(private val binding: AdapterYoutubeChipsBinding) : RecyclerView.ViewHolder(binding.root) {
        fun bind(row: FeedRow.Chips) {
            binding.chipsContainer.removeAllViews()
            val inflater = LayoutInflater.from(binding.root.context)
            row.chips.forEach { chip ->
                val view = inflater.inflate(R.layout.view_youtube_chip, binding.chipsContainer, false) as HighlightTextView
                view.text = chip.label
                view.alpha = if (chip.selected) 1f else 0.55f
                view.setOnClickListener { callbacks?.onChip(chip.key) }
                binding.chipsContainer.addView(view)
            }
        }
    }

    private inner class EntryHolder(private val binding: AdapterYoutubeItemBinding) : RecyclerView.ViewHolder(binding.root) {
        fun bind(row: FeedRow.Entry) {
            val item = row.item
            binding.title.text = item.title
            val song = item as? YoutubeSongItem
            binding.subtitle.text = when {
                song != null && song.durationText.isNotBlank() && song.subtitle.isNotBlank() -> "${song.subtitle} • ${song.durationText}"
                else -> item.subtitle
            }
            binding.title.alpha = if (song != null && song.videoId == nowPlayingId) 1f else 0.9f
            load(binding.cover, item.thumbnailUrl)
            binding.moreOptions.visibility = if (song != null) View.VISIBLE else View.GONE
            binding.moreOptions.setOnClickListener { song?.let { callbacks?.onSongMore(it) } }
            binding.container.setOnClickListener {
                if (song != null) callbacks?.onSong(song, row.songs) else callbacks?.onOpen(item)
            }
            binding.container.setOnLongClickListener {
                song?.let { callbacks?.onSongMore(it) }
                song != null
            }
        }
    }

    private inner class ShelfHolder(private val binding: AdapterYoutubeShelfBinding) : RecyclerView.ViewHolder(binding.root) {
        private val cards = CardAdapter()

        init {
            binding.shelfRecycler.layoutManager = LinearLayoutManager(binding.root.context, LinearLayoutManager.HORIZONTAL, false)
            binding.shelfRecycler.adapter = cards
        }

        fun bind(shelf: YoutubeShelf) {
            binding.shelfTitle.text = shelf.title
            binding.shelfTitle.setOnClickListener { if (shelf.moreBrowseId != null) callbacks?.onShelfMore(shelf) }
            cards.submit(shelf)
        }
    }

    private inner class CardAdapter : RecyclerView.Adapter<CardHolder>() {
        private var shelf: YoutubeShelf? = null
        fun submit(newShelf: YoutubeShelf) {
            shelf = newShelf
            notifyDataSetChanged()
        }

        override fun getItemCount() = shelf?.items?.size ?: 0
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
            CardHolder(AdapterYoutubeCardBinding.inflate(LayoutInflater.from(parent.context), parent, false))

        override fun onBindViewHolder(holder: CardHolder, position: Int) {
            val current = shelf ?: return
            holder.bind(current.items[position], current.items.filterIsInstance<YoutubeSongItem>())
        }
    }

    private inner class CardHolder(private val binding: AdapterYoutubeCardBinding) : RecyclerView.ViewHolder(binding.root) {
        fun bind(item: YoutubeItem, songs: List<YoutubeSongItem>) {
            binding.title.text = item.title
            binding.subtitle.text = item.subtitle
            load(binding.cover, item.thumbnailUrl)
            binding.container.setOnClickListener {
                if (item is YoutubeSongItem) callbacks?.onSong(item, songs) else callbacks?.onOpen(item)
            }
            binding.container.setOnLongClickListener {
                (item as? YoutubeSongItem)?.let { callbacks?.onSongMore(it) }
                item is YoutubeSongItem
            }
        }
    }

    private inner class DetailHolder(private val binding: AdapterYoutubeDetailBinding) : RecyclerView.ViewHolder(binding.root) {
        fun bind(page: YoutubeDetailPage) {
            binding.title.text = page.title
            binding.subtitle.text = page.subtitle
            binding.description.text = page.description
            binding.description.visibility = if (page.description.isBlank()) View.GONE else View.VISIBLE
            load(binding.cover, page.thumbnailUrl)
            binding.actionPlay.setOnClickListener { callbacks?.onPlay(page, false) }
            binding.actionShuffle.setOnClickListener { callbacks?.onPlay(page, true) }
            binding.actionRadio.visibility = if (page.radioPlaylistId != null || page.tracks.isNotEmpty()) View.VISIBLE else View.GONE
            binding.actionRadio.setOnClickListener { callbacks?.onRadio(page) }
            val artist = page.kind == YoutubeDetailPage.Kind.ARTIST && page.channelId != null
            binding.actionSubscribe.visibility = if (artist) View.VISIBLE else View.GONE
            binding.actionSubscribe.setText(if (page.subscribed) R.string.youtube_subscribed else R.string.youtube_subscribe)
            binding.actionSubscribe.setOnClickListener { callbacks?.onSubscribe(page) }
        }
    }

    companion object {
        private const val TYPE_CHIPS = 0
        private const val TYPE_SHELF = 1
        private const val TYPE_ENTRY = 2
        private const val TYPE_DETAIL = 3

        /** Item kinds that open a page instead of playing. */
        fun opensPage(item: YoutubeItem) = item is YoutubeAlbumItem || item is YoutubeArtistItem
    }
}
