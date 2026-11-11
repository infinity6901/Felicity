package app.simple.felicity.repository.models

import com.google.gson.annotations.SerializedName

private val WORD_SYNC_TAG_REGEX = Regex("<\\d{1,3}:\\d{2}[.:]\\d{1,3}>")

data class LrcLibResponse(
        @SerializedName("id") val id: Int,
        @SerializedName("trackName") val trackName: String,
        @SerializedName("artistName") val artistName: String,
        @SerializedName("albumName") val albumName: String?,
        @SerializedName("duration") val duration: Double,
        @SerializedName("plainLyrics") val plainLyrics: String?,
        @SerializedName("syncedLyrics") val syncedLyrics: String?, // This is the .lrc content
        @SerializedName("instrumental") val isInstrumental: Boolean
) {
    /** True when [syncedLyrics] contains inline per-word timestamps (`<mm:ss.xxx>`). */
    val isWordSynced: Boolean
        get() = !syncedLyrics.isNullOrBlank() && WORD_SYNC_TAG_REGEX.containsMatchIn(syncedLyrics)
}