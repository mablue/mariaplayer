package ir.mariaplayer.lyrics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

class LyricsScorerTest {

    private fun cand(
        title: String, artist: String, dur: Int?,
    ) = LyricsResult(
        title = title, artist = artist, album = null, durationSec = dur,
        syncedLyrics = "[00:00.00]...", plainLyrics = "...", source = "lrclib", score = 0,
    )

    // ─── score() matrix ───────────────────────────────────────────────────

    @Test fun perfectMatch() {
        val s = LyricsScorer.score("Song", "Artist", 200, "Song", "Artist", 200)
        assertEquals(40 + 30 + 30, s)
    }
    @Test fun titleCaseInsensitive() {
        val s = LyricsScorer.score("song", "artist", 200, "SONG", "ARTIST", 200)
        assertEquals(100, s)
    }
    @Test fun titleWhitespaceNormalized() {
        val s = LyricsScorer.score("Song  Name", "A", 200, "Song Name", "A", 200)
        assertEquals(100, s)
    }
    @Test fun titleStartswith() {
        val s = LyricsScorer.score("Song", "A", 200, "Song Title", "A", 200)
        assertEquals(25 + 30 + 30, s)
    }
    @Test fun titleContains() {
        val s = LyricsScorer.score("Song", "A", 200, "The Song For You", "A", 200)
        assertEquals(15 + 30 + 30, s)
    }
    @Test fun titleMismatch() {
        val s = LyricsScorer.score("Song", "A", 200, "Different", "A", 200)
        assertEquals(0 + 30 + 30, s)
    }
    @Test fun artistContains() {
        val s = LyricsScorer.score("Song", "Dire Straits", 200, "Song", "Dire Straits Live", 200)
        assertEquals(40 + 15 + 30, s)
    }
    @Test fun durationPlus2() {
        assertEquals(30, LyricsScorer.score("Song","A",200,"Song","A",202)
            - 70)
    }
    @Test fun durationPlus3() {
        assertEquals(20, LyricsScorer.score("Song","A",200,"Song","A",203)
            - 70)
    }
    @Test fun durationPlus5() {
        assertEquals(15, LyricsScorer.score("Song","A",200,"Song","A",205)
            - 70)
    }
    @Test fun durationBeyond() {
        assertEquals(0, LyricsScorer.score("Song","A",200,"Song","A",210) - 70)
    }
    @Test fun durationNull() {
        assertEquals(70, LyricsScorer.score("Song","A",200,"Song","A",null))
    }

    // ─── pickBest() ───────────────────────────────────────────────────────

    @Test fun pickBestSingle() {
        val c = cand("Song", "Artist", 200)
        val r = LyricsScorer.pickBest(listOf(c), "Song", "Artist", 200)
        assertEquals(c.title, r?.title)
        assertEquals(100, r?.score)
    }
    @Test fun pickBestBelowThreshold() {
        val c = cand("Other", "Someone", 500)
        val r = LyricsScorer.pickBest(listOf(c), "Song", "Artist", 200)
        assertNull(r)
    }
    @Test fun pickBestAmbiguous() {
        val a = cand("Song", "Artist", 200)
        val b = cand("Song", "Artist", 201)
        val r = LyricsScorer.pickBest(listOf(a, b), "Song", "Artist", 200)
        // both score 100/100 — within gap of 5 → null
        assertNull(r)
    }
    @Test fun pickBestClearWinner() {
        val a = cand("Song", "Artist", 200)    // 100
        val b = cand("Song", "Other", 500)     // 40 + 0 + 0 = 40
        val r = LyricsScorer.pickBest(listOf(a, b), "Song", "Artist", 200)
        assertEquals(100, r?.score)
    }
    @Test fun pickBestEmpty() {
        assertNull(LyricsScorer.pickBest(emptyList(), "Song", "Artist", 200))
    }
}
