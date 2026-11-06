package ir.mariaplayer.lyrics

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.URL
import java.net.URLEncoder
import java.util.concurrent.ConcurrentLinkedQueue

/**
 * PR-L44: return-all fetcher. No auto-picking, no dictionary, no heuristics.
 *
 * The user edits title/artist in the form, taps Search, and sees every
 * result from every source. They pick what fits. That is the design.
 *
 * Sources queried in parallel:
 *   - LRCLIB /api/get     (with duration ±2s)
 *   - LRCLIB /api/search  (top 3 hits)
 *   - Genius              (search + page scrape, strict filter)
 *   - lyrics.ovh          (single request, timeout 3s)
 *
 * Results sorted by fuzzy score but NEVER rejected. Empty-text results
 * are dropped (no lyrics = useless). Duplicates across (source, title,
 * artist) are removed.
 */
class LyricsFetcher(
    private val lrclibTimeoutMs: Int = 5000,
    private val geniusTimeoutMs: Int = 6000,
    private val ovhTimeoutMs: Int = 3000,
    private val totalBudgetMs: Long = 12_000L,
    private val userAgent: String = "MariaPlayer/1.0 (lyrics PR-L44)",
) {
    suspend fun fetchAll(
        title: String,
        artist: String,
        durationSec: Int,
    ): List<LyricsResult> = withContext(Dispatchers.IO) {
        if (title.isBlank()) return@withContext emptyList()

        val collected = ConcurrentLinkedQueue<LyricsResult>()

        withTimeoutOrNull(totalBudgetMs) {
            coroutineScope {
                val jobs = listOf(
                    async { tryLrclibGet(title, artist, durationSec, collected) },
                    async { tryLrclibSearch(title, artist, collected) },
                    async { tryGenius(title, artist, collected) },
                    async { tryLyricsTranslate(title, artist, collected) },
                    async { tryMusixmatch(title, artist, collected) },
                    async { tryLyricsOvh(title, artist, collected) },
                )
                jobs.awaitAll()
            }
        }

        val seen = mutableSetOf<String>()
        val unique = mutableListOf<LyricsResult>()
        for (r in collected) {
            if (r.plainLyrics.isNullOrBlank() && r.syncedLyrics.isNullOrBlank()) continue
            val key = "${r.source}|${r.title.lowercase().trim()}|${r.artist.lowercase().trim()}"
            if (seen.add(key)) unique.add(r)
        }

        unique.map { r ->
            r.copy(
                syncedLyrics = sanitize(r.syncedLyrics),
                plainLyrics = sanitize(r.plainLyrics),
            )
        }.filter { !it.plainLyrics.isNullOrBlank() || !it.syncedLyrics.isNullOrBlank() }
            .map { r ->
                r.copy(score = LyricsScorer.score(
                    targetTitle = title,
                    targetArtist = artist,
                    targetDurationSec = durationSec,
                    candidateTitle = r.title,
                    candidateArtist = r.artist,
                    candidateDurationSec = r.durationSec,
                ))
            }.sortedByDescending { it.score }
    }

    /** org.json on Android returns literal "null" for JSON null values. */
    private fun sanitize(s: String?): String? =
        s?.takeUnless { it.isBlank() || it.equals("null", ignoreCase = true) }

    // ─── LRCLIB /api/get ───
    private fun tryLrclibGet(
        title: String,
        artist: String,
        durationSec: Int,
        out: ConcurrentLinkedQueue<LyricsResult>,
    ) {
        try {
            val deltas = if (durationSec > 0) listOf(0, -2) else listOf(0)
            for (d in deltas) {
                val dur = if (durationSec > 0) (durationSec + d).coerceAtLeast(0) else -1
                val urlStr = buildString {
                    append("https://lrclib.net/api/get?track_name=")
                        .append(URLEncoder.encode(title, "UTF-8"))
                    if (artist.isNotBlank()) {
                        append("&artist_name=").append(URLEncoder.encode(artist, "UTF-8"))
                    }
                    if (dur > 0) append("&duration=").append(dur)
                }
                val body = httpGetText(urlStr, lrclibTimeoutMs) ?: continue
                val o = try { JSONObject(body) } catch (_: Exception) { continue }
                val r = LyricsResult(
                    title = o.optString("trackName", ""),
                    artist = o.optString("artistName", ""),
                    album = o.optString("albumName", "").ifBlank { null },
                    durationSec = if (o.has("duration"))
                        o.optInt("duration", -1).takeIf { it >= 0 } else null,
                    syncedLyrics = o.optString("syncedLyrics", "").ifBlank { null },
                    plainLyrics = o.optString("plainLyrics", "").ifBlank { null },
                    source = "lrclib",
                    score = 0,
                )
                if (!r.plainLyrics.isNullOrBlank() || !r.syncedLyrics.isNullOrBlank()) {
                    out.add(r)
                    return
                }
            }
        } catch (_: Exception) {}
    }

    // ─── LRCLIB /api/search ───
    private fun tryLrclibSearch(
        title: String,
        artist: String,
        out: ConcurrentLinkedQueue<LyricsResult>,
    ) {
        try {
            val urlStr = buildString {
                append("https://lrclib.net/api/search?track_name=")
                    .append(URLEncoder.encode(title, "UTF-8"))
                if (artist.isNotBlank()) {
                    append("&artist_name=").append(URLEncoder.encode(artist, "UTF-8"))
                }
            }
            val body = httpGetText(urlStr, lrclibTimeoutMs) ?: return
            val arr = try { JSONArray(body) } catch (_: Exception) { return }
            for (i in 0 until arr.length()) {
                val o = try { arr.getJSONObject(i) } catch (_: Exception) { continue }
                val plain = o.optString("plainLyrics", "").ifBlank { null }
                val synced = o.optString("syncedLyrics", "").ifBlank { null }
                if (plain == null && synced == null) continue
                out.add(LyricsResult(
                    title = o.optString("trackName", ""),
                    artist = o.optString("artistName", ""),
                    album = o.optString("albumName", "").ifBlank { null },
                    durationSec = if (o.has("duration"))
                        o.optInt("duration", -1).takeIf { it >= 0 } else null,
                    syncedLyrics = synced,
                    plainLyrics = plain,
                    source = "lrclib-search",
                    score = 0,
                ))
                if (out.size >= 5) return
            }
        } catch (_: Exception) {}
    }

    // ─── Genius (delegate to existing GeniusProvider) ───
    private suspend fun tryGenius(
        title: String,
        artist: String,
        out: ConcurrentLinkedQueue<LyricsResult>,
    ) {
        try {
            val r = GeniusProvider.fetch(title, artist, geniusTimeoutMs)
            if (r is LyricsFetchResult.Found) {
                val res = r.result
                if (!res.plainLyrics.isNullOrBlank() || !res.syncedLyrics.isNullOrBlank()) {
                    out.add(res)
                }
            }
        } catch (_: CancellationException) {
            throw CancellationException()
        } catch (_: Exception) {}
    }

    // ─── LyricsTranslate (HTML scrape, no API) ───
    private suspend fun tryLyricsTranslate(
        title: String, artist: String, out: ConcurrentLinkedQueue<LyricsResult>,
    ) {
        // TODO PR-53b: parse https://lyricstranslate.com/en/translations/...
    }

    // ─── Musixmatch (HTML scrape, no API) ───
    private suspend fun tryMusixmatch(
        title: String, artist: String, out: ConcurrentLinkedQueue<LyricsResult>,
    ) {
        // TODO PR-53c: parse https://www.musixmatch.com/lyrics/...
    }

    // ─── lyrics.ovh ───
    private fun tryLyricsOvh(
        title: String,
        artist: String,
        out: ConcurrentLinkedQueue<LyricsResult>,
    ) {
        if (artist.isBlank()) return
        try {
            val urlStr = "https://api.lyrics.ovh/v1/" +
                URLEncoder.encode(artist, "UTF-8") + "/" +
                URLEncoder.encode(title, "UTF-8")
            val body = httpGetText(urlStr, ovhTimeoutMs) ?: return
            val o = try { JSONObject(body) } catch (_: Exception) { return }
            val lyrics = o.optString("lyrics", "").trim()
            if (lyrics.length < 60) return
            out.add(LyricsResult(
                title = title,
                artist = artist,
                album = null,
                durationSec = null,
                syncedLyrics = null,
                plainLyrics = lyrics,
                source = "lyrics.ovh",
                score = 0,
            ))
        } catch (_: Exception) {}
    }

    private fun httpGetText(url: String, timeoutMs: Int): String? {
        val conn = try {
            (URL(url).openConnection() as HttpURLConnection).apply {
                connectTimeout = timeoutMs
                readTimeout = timeoutMs
                requestMethod = "GET"
                setRequestProperty("User-Agent", userAgent)
                setRequestProperty("Accept", "application/json,text/html")
            }
        } catch (_: Exception) { return null }
        return try {
            val code = conn.responseCode
            if (code != 200) null
            else conn.inputStream.bufferedReader().use { it.readText() }
        } catch (_: Exception) {
            null
        } finally {
            try { conn.disconnect() } catch (_: Exception) {}
        }
    }
}
