package ir.mariaplayer.lyrics

data class LyricsResult(
    val title: String,
    val artist: String,
    val album: String?,
    val durationSec: Int?,
    val syncedLyrics: String?,
    val plainLyrics: String?,
    val source: String,
    val score: Int,
)

sealed class LyricsFetchResult {
    data class Found(val result: LyricsResult) : LyricsFetchResult()
    data object NotFound : LyricsFetchResult()
    data class NetworkError(val message: String) : LyricsFetchResult()
    data object Timeout : LyricsFetchResult()
}
