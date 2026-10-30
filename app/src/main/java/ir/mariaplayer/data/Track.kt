package ir.mariaplayer.data

data class Track(
    val id: Long,
    val title: String,
    val artist: String?,
    val album: String?,
    val durationMs: Long,
    val contentUri: String,
    val folder: String = "",
    val filename: String = "",
)
