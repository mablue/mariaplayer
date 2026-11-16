package ir.mariaplayer.data

/**
 * C21: heuristics for Podcast vs Audiobook detection when ID3 tags are
 * unhelpful. Priority order (specific → generic):
 *   1. MediaStore IS_PODCAST flag
 *   2. Audiobook keyword in folder/album/title/filename
 *   3. Podcast keyword in folder/album/title/filename
 *   4. Episode pattern (S01E05, Episode N, Ep. N)
 *   5. Chapter pattern + >30 min duration
 *   6. Duration > 2 hours with no markers
 *   7. MUSIC
 */
object MediaClassifier {

    private val audiobookKeywords = listOf(
        "audiobook", "audio book", "کتاب صوتی", "كتاب صوتي",
    )
    private val podcastKeywords = listOf(
        "podcast", "podcasts", "پادکست", "پاد كست",
    )

    private val episodePattern = Regex(
        """\b(S\d{1,3}[\s-]?E\d{1,4}|Episode?\s*[#.]?\s*\d+|Ep\.?\s*[#.]?\s*\d+)\b""",
        RegexOption.IGNORE_CASE,
    )
    private val chapterPattern = Regex(
        """\b(Chapter|Ch\.?|Part|بخش|فصل)\s*\d+\b""",
        RegexOption.IGNORE_CASE,
    )

    fun classify(
        title: String,
        album: String?,
        folder: String,
        filename: String,
        durationMs: Long,
        isPodcastFlag: Boolean = false,
    ): ContentType {
        if (isPodcastFlag) return ContentType.PODCAST

        val blob = listOf(title, album ?: "", folder, filename)
            .joinToString(" ")
            .lowercase()

        if (audiobookKeywords.any { blob.contains(it) }) return ContentType.AUDIOBOOK
        if (podcastKeywords.any { blob.contains(it) }) return ContentType.PODCAST
        if (episodePattern.containsMatchIn(blob)) return ContentType.PODCAST
        if (durationMs > 30 * 60_000L && chapterPattern.containsMatchIn(blob)) {
            return ContentType.AUDIOBOOK
        }
        if (durationMs > 120 * 60_000L) return ContentType.AUDIOBOOK
        return ContentType.MUSIC
    }
}
