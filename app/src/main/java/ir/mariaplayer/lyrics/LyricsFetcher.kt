package ir.mariaplayer.lyrics

import ir.mariaplayer.data.QueryNormalizer
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
 * PR-L13: multi-strategy lyrics fetcher.
 *
 * For each candidate query (up to 6, in order of confidence):
 *  1. cleaned title + artist
 *  2. title with camelCase split + artist
 *  3. title alone (drop placeholder artist)
 *  4. first 3 words of title + artist
 *  5. filename without extension
 *  6. filename with camelCase split
 *
 * Stop at the first candidate that yields a result **with actual lyrics**.
 * Empty-shell results (both plain and synced null) are filtered at every
 * stage so we never show a blank "found" screen.
 */
class LyricsFetcher(
    private val timeoutMs: Int = 8000,
    private val userAgent: String = "MariaPlayer/1.0 (lyrics PR-L13)",
) {

    suspend fun fetch(
        rawTitle: String,
        rawArtist: String,
        filename: String,
        durationSec: Int,
    ): LyricsFetchResult = withContext(Dispatchers.IO) {
        val norm = QueryNormalizer.normalize(rawTitle, rawArtist, filename)
        val candidates = buildCandidates(norm, filename)

        if (candidates.isEmpty()) return@withContext LyricsFetchResult.NotFound

        var lastError: IOException? = null

        for ((idx, q) in candidates.withIndex()) {
            // /api/get — only first candidate runs the delta sweep.
            val deltas = if (idx == 0 && durationSec > 0)
                listOf(0, -2, 2, -3, 3, -5, 5) else listOf(0)
            try {
                val hit = tryGetLrclib(q.title, q.artist, durationSec, deltas)
                if (hit != null && hasLyrics(hit)) {
                    return@withContext LyricsFetchResult.Found(hit)
                }
            } catch (e: SocketTimeoutException) { lastError = e }
              catch (e: IOException) { lastError = e }

            // /api/search — filter empty shells before scoring.
            try {
                val raw = searchLrclib(q.title, q.artist)
                val usable = raw.filter { hasLyrics(it) }
                val best = LyricsScorer.pickBest(
                    usable, q.title, q.artist, durationSec,
                )
                if (best != null) return@withContext LyricsFetchResult.Found(best)
            } catch (e: SocketTimeoutException) { lastError = e }
              catch (e: IOException) { lastError = e }

            // Genius — first 2 candidates only (avoid rate limits).
            if (idx < 2) {
                try {
                    val r = GeniusProvider.fetch(q.title, q.artist)
                    if (r is LyricsFetchResult.Found && hasLyrics(r.result)) {
                        return@withContext r
                    }
                } catch (_: Exception) {}
            }
        }

        if (lastError is SocketTimeoutException) LyricsFetchResult.Timeout
        else if (lastError != null)
            LyricsFetchResult.NetworkError(lastError!!.message ?: "IO")
        else LyricsFetchResult.NotFound
    }

    private fun hasLyrics(r: LyricsResult): Boolean =
        !r.plainLyrics.isNullOrBlank() || !r.syncedLyrics.isNullOrBlank()

    private data class Query(val title: String, val artist: String)

    private fun buildCandidates(
        norm: ir.mariaplayer.data.NormalizedQuery,
        filename: String,
    ): List<Query> {
        val out = mutableListOf<Query>()
        val title = norm.title.trim()
        val artist = norm.artist.trim()

        if (title.isNotBlank()) out += Query(title, artist)

        val camelTitle = splitCamel(title)
        if (camelTitle != title) out += Query(camelTitle, artist)

        if (title.isNotBlank() && artist.isNotBlank()) out += Query(title, "")

        val short = title.split(" ").take(3).joinToString(" ").trim()
        if (short.length >= 3 && short != title) out += Query(short, artist)

        val fn = filename.substringBeforeLast('.')
            .replace('_', ' ')
            .replace(Regex("\\s+"), " ")
            .trim()
        if (fn.isNotBlank() && fn != title) out += Query(fn, "")

        val fnCamel = splitCamel(fn)
        if (fnCamel != fn) out += Query(fnCamel, "")

        return out.distinctBy { "${it.title}\u0000${it.artist}" }.take(6)
    }

    private fun splitCamel(s: String): String =
        s.replace(Regex("([a-z])([A-Z])"), "$1 $2")

    private fun tryGetLrclib(
        title: String,
        artist: String,
        durationSec: Int,
        deltas: List<Int>,
    ): LyricsResult? {
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
            val conn = (URL(urlStr).openConnection() as HttpURLConnection).apply {
                connectTimeout = timeoutMs
                readTimeout = timeoutMs
                requestMethod = "GET"
                setRequestProperty("User-Agent", userAgent)
                setRequestProperty("Accept", "application/json")
            }
            try {
                val code = conn.responseCode
                if (code == 404) continue
                if (code != 200) return null
                val body = conn.inputStream.bufferedReader().use { it.readText() }
                val o = JSONObject(body)
                return LyricsResult(
                    title        = o.optString("trackName", ""),
                    artist       = o.optString("artistName", ""),
                    album        = o.optString("albumName", "").ifBlank { null },
                    durationSec  = if (o.has("duration"))
                        o.optInt("duration", -1).takeIf { it >= 0 } else null,
                    syncedLyrics = o.optString("syncedLyrics", "").ifBlank { null },
                    plainLyrics  = o.optString("plainLyrics", "").ifBlank { null },
                    source       = "lrclib",
                    score        = 100,
                )
            } catch (e: SocketTimeoutException) {
                throw e
            } catch (e: IOException) {
                throw e
            } catch (_: Exception) {
                continue
            } finally {
                try { conn.disconnect() } catch (_: Exception) {}
            }
        }
        return null
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
                    durationSec  = if (o.has("duration"))
                        o.optInt("duration", -1).takeIf { it >= 0 } else null,
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
