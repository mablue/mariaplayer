package ir.mariaplayer.lyrics

import ir.mariaplayer.data.QueryNormalizer
import ir.mariaplayer.data.Source
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.URL
import java.net.URLEncoder

/**
 * Fetches lyrics from LRCLIB (https://lrclib.net/api/search).
 *
 * Behaviour:
 *   - Builds up to 3 candidate queries from QueryNormalizer output.
 *   - Stops at the first query that yields a scored result above threshold.
 *   - Never fires automatically — caller must invoke fetch().
 *   - Returns NetworkError / Timeout so UI can distinguish "offline"
 *     from "no lyrics".
 */
class LyricsFetcher(
    private val timeoutMs: Int = 8000,
    private val userAgent: String = "MariaPlayer/1.0 (lyrics PR-L2)",
) {

    suspend fun fetch(
        rawTitle: String,
        rawArtist: String,
        filename: String,
        durationSec: Int,
    ): LyricsFetchResult = withContext(Dispatchers.IO) {
        val norm = QueryNormalizer.normalize(rawTitle, rawArtist, filename)
        val candidates = buildCandidates(norm)

        if (candidates.isEmpty()) return@withContext LyricsFetchResult.NotFound

        var lastError: IOException? = null
        for (q in candidates) {
            try {
                val results = searchLrclib(q.title, q.artist)
                val best = LyricsScorer.pickBest(
                    results, q.title, q.artist, durationSec,
                )
                if (best != null) return@withContext LyricsFetchResult.Found(best)
            } catch (e: SocketTimeoutException) {
                lastError = e
            } catch (e: IOException) {
                lastError = e
            }
        }
        if (lastError is SocketTimeoutException) LyricsFetchResult.Timeout
        else if (lastError != null) LyricsFetchResult.NetworkError(lastError!!.message ?: "IO")
        else LyricsFetchResult.NotFound
    }

    // ─── Internal ────────────────────────────────────────────────────────

    private data class Query(val title: String, val artist: String)

    private fun buildCandidates(norm: ir.mariaplayer.data.NormalizedQuery): List<Query> {
        val out = mutableListOf<Query>()
        // Prefer the cleaned tag/filename title with cleaned artist.
        if (norm.title.isNotBlank()) {
            out += Query(norm.title, norm.artist)
            // Second try: without artist (helps with placeholder-y artist names
            // that slipped past PLACEHOLDERS list).
            if (norm.artist.isNotBlank()) {
                out += Query(norm.title, "")
            }
        }
        // Filename variant only when tag was INVALID.
        if (norm.source == Source.INVALID && norm.title.isNotBlank()) {
            out += Query(norm.title, "")
        }
        return out.distinctBy { "${it.title}\u0000${it.artist}" }
    }

    private fun searchLrclib(title: String, artist: String): List<LyricsResult> {
        val enc = URLEncoder.encode(title, "UTF-8")
        val urlStr = buildString {
            append("https://lrclib.net/api/search?track_name=").append(enc)
            if (artist.isNotBlank()) {
                append("&artist_name=").append(URLEncoder.encode(artist, "UTF-8"))
            }
        }
        val conn = (URL(urlStr).openConnection() as HttpURLConnection).apply {
            connectTimeout = timeoutMs
            readTimeout = timeoutMs
            requestMethod = "GET"
            setRequestProperty("User-Agent", userAgent)
            setRequestProperty("Accept", "application/json")
        }
        try {
            val code = conn.responseCode
            if (code != 200) return emptyList()
            val body = conn.inputStream.bufferedReader().use { it.readText() }
            return parseLrclib(body)
        } finally {
            try { conn.disconnect() } catch (_: Exception) {}
        }
    }

    private fun parseLrclib(body: String): List<LyricsResult> {
        return try {
            val arr = JSONArray(body)
            val out = ArrayList<LyricsResult>(arr.length())
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                out += LyricsResult(
                    title        = o.optString("trackName", ""),
                    artist       = o.optString("artistName", ""),
                    album        = o.optString("albumName", "").ifBlank { null },
                    durationSec  = if (o.has("duration")) o.optInt("duration", -1).takeIf { it >= 0 } else null,
                    syncedLyrics = o.optString("syncedLyrics", "").ifBlank { null },
                    plainLyrics  = o.optString("plainLyrics", "").ifBlank { null },
                    source       = "lrclib",
                    score        = 0,
                )
            }
            out
        } catch (_: Exception) {
            emptyList()
        }
    }
}
