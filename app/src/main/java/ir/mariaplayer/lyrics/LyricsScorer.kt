package ir.mariaplayer.lyrics

import kotlin.math.abs

/**
 * Scoring for lyrics candidate selection.
 *
 * Knowledge.md spec:
 *   title-40/25 + artist-30/15 + duration-30/15
 *   threshold = 40  (below → reject)
 *   ambiguous gap = 5  (if 2nd is within 5 of best → reject as ambiguous)
 */
object LyricsScorer {

    private val WS = Regex("\\s+")

    fun score(
        targetTitle: String,
        targetArtist: String,
        targetDurationSec: Int,
        candidateTitle: String,
        candidateArtist: String,
        candidateDurationSec: Int?,
    ): Int {
        val t  = normalize(targetTitle)
        val ca = normalize(candidateTitle)
        val a  = normalize(targetArtist)
        val ar = normalize(candidateArtist)

        val titleScore = when {
            t.isEmpty() || ca.isEmpty() -> 0
            t == ca -> 40
            ca.startsWith(t) || t.startsWith(ca) -> 25
            ca.contains(t) || t.contains(ca) -> 15
            else -> 0
        }
        val artistScore = when {
            a.isEmpty() || ar.isEmpty() -> 0
            a == ar -> 30
            ar.contains(a) || a.contains(ar) -> 15
            else -> 0
        }
        val durationScore = if (candidateDurationSec == null) 0 else {
            when (abs(targetDurationSec - candidateDurationSec)) {
                in 0..2 -> 30
                in 3..3 -> 20
                in 4..5 -> 15
                else -> 0
            }
        }
        return titleScore + artistScore + durationScore
    }

    /**
     * Pick the single best, or null if too weak or ambiguous.
     */
    fun pickBest(
        candidates: List<LyricsResult>,
        targetTitle: String,
        targetArtist: String,
        targetDurationSec: Int,
        threshold: Int = 40,
        ambiguousGap: Int = 5,
    ): LyricsResult? {
        if (candidates.isEmpty()) return null
        // PR-L13: reject "empty shell" candidates. LRCLIB has metadata-only
        // entries where both plainLyrics and syncedLyrics are null. Scoring
        // matches them by title+artist+duration alone and returns blank text.
        val usable = candidates.filter {
            !it.plainLyrics.isNullOrBlank() || !it.syncedLyrics.isNullOrBlank()
        }
        if (usable.isEmpty()) return null
        val scored = usable.map { c ->
            c.copy(
                score = score(
                    targetTitle, targetArtist, targetDurationSec,
                    c.title, c.artist, c.durationSec,
                )
            )
        }.sortedByDescending { it.score }

        val best = scored[0]
        if (best.score < threshold) return null
        if (scored.size > 1 && best.score - scored[1].score < ambiguousGap) return null
        // Prefer synced lyrics if a near-tie alternative has them
        if (best.syncedLyrics == null) {
            val syncedAlt = scored.firstOrNull {
                it.syncedLyrics != null && it.score >= threshold && it.score >= best.score - 15
            }
            if (syncedAlt != null) return syncedAlt
        }
        return best
    }

    private fun normalize(s: String): String =
        WS.replace(s.trim().lowercase(), " ")
}
