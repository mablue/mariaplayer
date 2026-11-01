package ir.mariaplayer.lyrics

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * PR-L13: fixed Genius scraper.
 *
 * Old parser died at the first </div>, so nested divs (section headers)
 * truncated the outer container. New parser walks each data-lyrics-container
 * with proper depth counting, then joins all chunks. <br> is converted to \n
 * BEFORE stripping other tags, so line structure survives.
 */
object GeniusProvider {

    private const val UA = "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 Chrome/120.0 Safari/537.36"
    private val WS = Regex("\\s+")
    private val PERSIAN = Regex("[\\u0600-\\u06FF]")

    suspend fun fetch(
        title: String,
        artist: String,
        timeoutMs: Int = 15_000,
    ): LyricsFetchResult? = withContext(Dispatchers.IO) {
        val q = "$artist $title".trim()
        val searchUrl = "https://genius.com/api/search/multi?q=" +
            URLEncoder.encode(q, "UTF-8")

        val candidates = trySearch(searchUrl, timeoutMs) ?: return@withContext null
        if (candidates.isEmpty()) return@withContext null

        for (c in candidates.take(3)) {
            val lyrics = tryLyrics(c.url, timeoutMs) ?: continue
            if (lyrics.length < 30) continue
            if (isValid(title, artist, c.trackName, c.artistName, lyrics)) {
                return@withContext LyricsFetchResult.Found(
                    LyricsResult(
                        title = c.trackName,
                        artist = c.artistName,
                        album = null, durationSec = null,
                        syncedLyrics = null, plainLyrics = lyrics,
                        source = "genius", score = 100,
                    )
                )
            }
        }
        null
    }

    private data class Cand(val trackName: String, val artistName: String, val url: String)

    private fun trySearch(url: String, timeoutMs: Int): List<Cand>? {
        return try {
            val body = httpGet(url, timeoutMs) ?: return null
            val sections = JSONObject(body).getJSONObject("response").getJSONArray("sections")
            val out = mutableListOf<Cand>()
            for (i in 0 until sections.length()) {
                val sec = sections.getJSONObject(i)
                if (sec.optString("type") != "song") continue
                val hits = sec.optJSONArray("hits") ?: continue
                for (j in 0 until hits.length()) {
                    val r = hits.getJSONObject(j).getJSONObject("result")
                    val pageUrl = r.optString("url")
                    if (pageUrl.isNotBlank()) {
                        out += Cand(
                            trackName = r.optString("title", ""),
                            artistName = r.optJSONObject("primary_artist")
                                ?.optString("name", "").orEmpty(),
                            url = pageUrl,
                        )
                        if (out.size >= 3) break
                    }
                }
                if (out.size >= 3) break
            }
            out
        } catch (_: Exception) { null }
    }

    private fun tryLyrics(url: String, timeoutMs: Int): String? {
        return try {
            val html = httpGet(url, timeoutMs) ?: return null
            val chunks = extractContainers(html)
            if (chunks.isEmpty()) return null
            val text = chunks.joinToString("\n") { raw ->
                raw.replace(Regex("(?i)<br\\s*/?>"), "\n")
                    .replace(Regex("<[^>]+>"), "")
                    .replace("&amp;", "&")
                    .replace("&#x27;", "'")
                    .replace("&quot;", "\"")
                    .replace("&lt;", "<")
                    .replace("&gt;", ">")
                    .replace("&nbsp;", " ")
                    .replace(Regex("\n{3,}"), "\n\n")
                    .trim()
            }.trim()
            text.ifBlank { null }
        } catch (_: Exception) { null }
    }

    /**
     * Walks every `data-lyrics-container="true"` div, counting <div>/<\/div>
     * depth so nested wrappers don't truncate. Returns inner HTML of each.
     */
    private fun extractContainers(html: String): List<String> {
        val marker = "data-lyrics-container=\"true\""
        val out = mutableListOf<String>()
        var searchFrom = 0
        while (true) {
            val i = html.indexOf(marker, searchFrom)
            if (i < 0) break
            val tagEnd = html.indexOf('>', i)
            if (tagEnd < 0) break
            var depth = 1
            var j = tagEnd + 1
            var closedAt = -1
            while (j < html.length) {
                val nextOpen = html.indexOf("<div", j)
                val nextClose = html.indexOf("</div>", j)
                if (nextClose < 0) break
                if (nextOpen in 0 until nextClose) {
                    depth++
                    j = nextOpen + 4
                } else {
                    depth--
                    if (depth == 0) { closedAt = nextClose; break }
                    j = nextClose + 6
                }
            }
            if (closedAt < 0) break
            out += html.substring(tagEnd + 1, closedAt)
            searchFrom = closedAt + 6
        }
        return out
    }

    private fun httpGet(url: String, timeoutMs: Int): String? {
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = timeoutMs
            readTimeout = timeoutMs
            setRequestProperty("User-Agent", UA)
            setRequestProperty("Accept", "application/json,text/html")
        }
        return try {
            if (conn.responseCode != 200) null
            else conn.inputStream.bufferedReader().use { it.readText() }
        } catch (_: Exception) { null } finally {
            try { conn.disconnect() } catch (_: Exception) {}
        }
    }

    private fun isValid(
        qTitle: String, qArtist: String,
        rTitle: String, rArtist: String, lyrics: String,
    ): Boolean {
        if (lyrics.length < 30) return false
        if (PERSIAN.containsMatchIn(qTitle)) {
            if (PERSIAN.findAll(lyrics).count() < 10) return false
        }
        val qt = norm(qTitle); val rt = norm(rTitle)
        val qw = qt.split(" ").filter { it.length > 2 }.toSet()
        val rw = rt.split(" ").filter { it.length > 2 }.toSet()
        if (qw.isNotEmpty() && rw.isNotEmpty()) {
            if ((qw intersect rw).isEmpty() && qt !in rt && rt !in qt) return false
        }
        if (qArtist.isNotBlank()) {
            val qa = norm(qArtist); val ra = norm(rArtist)
            val qaw = qa.split(" ").filter { it.length > 2 }.toSet()
            val raw = ra.split(" ").filter { it.length > 2 }.toSet()
            if (qaw.isNotEmpty() && raw.isNotEmpty()) {
                if ((qaw intersect raw).isEmpty() && qa !in ra && ra !in qa) return false
            }
        }
        return true
    }

    private fun norm(s: String) = WS.replace(s.trim().lowercase(), " ")
}
