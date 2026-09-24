package app.simple.felicity.repository.youtube

data class YoutubeSearchResult(
    val videoId: String,
    val title: String,
    val artist: String,
    val durationText: String,
    val thumbnailUrl: String,
)
