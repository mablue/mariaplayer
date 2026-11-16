package ir.mariaplayer.data

/**
 * C23: duration-only classifier.
 *
 * Regex/keyword heuristics proved fragile across English and Persian
 * (S01E05, Episode 5, Ep.5, فصل 3, Chapter 12, کتاب صوتی, ...). Rather
 * than chase every naming convention, we split by duration alone:
 *
 *   • <  20 min  →  MUSIC   (normal songs, albums, singles)
 *   • >= 20 min  →  PODCAST (podcasts, audiobooks, DJ mixes, DJ sets)
 *
 * AUDIOBOOK is never auto-assigned — user can pick it manually via the
 * TrackTypeSheet for extra distinction. ContentTypePrefs overrides win
 * over classification.
 *
 * Queue isolation in MainActivity guarantees that playing a song never
 * pulls a podcast into the queue and vice versa.
 */
object MediaClassifier {
    /** Threshold between music and long-form content. Tunable. */
    const val LONG_THRESHOLD_MS = 8 * 60_000L

    fun classify(
        title: String,
        album: String?,
        folder: String,
        filename: String,
        durationMs: Long,
        isPodcastFlag: Boolean = false,
    ): ContentType {
        if (isPodcastFlag) return ContentType.PODCAST
        return if (durationMs >= LONG_THRESHOLD_MS) {
            ContentType.PODCAST
        } else {
            ContentType.MUSIC
        }
    }
}
