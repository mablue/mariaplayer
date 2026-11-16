package ir.mariaplayer.data

/**
 * C25: pure functions behind the content-type UI. Extracted from
 * MainActivity so SelfTest can exercise them without Compose UI Test.
 */
object ContentTypeLogic {

    fun derive(track: Track, overrides: Map<Long, ContentType>): ContentType =
        overrides[track.id] ?: MediaClassifier.classify(
            track.title, track.album, track.folder, track.filename, track.durationMs,
        )

    fun applyAll(
        rawTracks: List<Track>,
        overrides: Map<Long, ContentType>,
    ): List<Track> = rawTracks.map { it.copy(contentType = derive(it, overrides)) }

    fun countByType(tracks: List<Track>): Map<ContentType?, Int> = mapOf(
        null to tracks.size,
        ContentType.MUSIC to tracks.count { it.contentType == ContentType.MUSIC },
        ContentType.PODCAST to tracks.count { it.contentType == ContentType.PODCAST },
        ContentType.AUDIOBOOK to tracks.count { it.contentType == ContentType.AUDIOBOOK },
    )

    fun matchesFilter(track: Track, filter: ContentType?): Boolean =
        filter == null || track.contentType == filter

    fun sameTypeOnly(tracks: List<Track>, target: Track): List<Track> =
        tracks.filter { it.contentType == target.contentType }
}
