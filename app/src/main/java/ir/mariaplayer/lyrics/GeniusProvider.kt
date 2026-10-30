package ir.mariaplayer.lyrics

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * Genius scraper with STRICT filtering.
 * In library test, ~15% of Persian tracks have valid lyrics on Genius.
 * Rejects false-wins by checking:
 *   - query title in Persian ⇒ lyrics must contain Persian
 *   - result title must share ≥1 significant word with query
 *   - result artist must share ≥1 significant word if query artist present
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
        val searchUrl = "https://genius.com/api/search/multi?q=" + URLEncoder.encode(q, "UTF-8")

        val candidates = trySearch(searchUrl, timeoutMs) ?: return@withContext null
        if (candidates.isEmpty()) return@withContext null

        for (c in candidates.take(3)) {
            val lyrics = tryLyrics(c.url, timeoutMs) ?: continue
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

    private fun trySearch(url: String, timeoutMs: Int): List<Cand>? = try {
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
                        artistName = r.optJSONObject("primary_artist")?.optString("name", "").orEmpty(),
                        url = pageUrl,
                    )
                    if (out.size >= 3) break
                }
            }
            if (out.size >= 3) break
        }
        out
    } catch (_: Exception) { null }

    private fun tryLyrics(url: String, timeoutMs: Int): String? = try {
        val html = httpGet(url, timeoutMs) ?: return null
        val re = Regex(
            """<div[^>]*data-lyrics-container="true"[^>]*>(.*?)</div>""",
            RegexOption.DOT_MATCHES_ALL,
        )
        val chunks = re.findAll(html).map { it.groupValues[1] }.toList()
        if (chunks.isEmpty()) return null
        val text = chunks.joinToString("\n") { chunk ->
            chunk.replace(Regex("<[^>]+>"), "\n")
                .replace("&amp;", "&")
                .replace("&#x27;", "'")
                .replace("&quot;", "\"")
                .replace(Regex("\n{2,}"), "\n\n")
                .trim()
        }
        text.ifBlank { null }
    } catch (_: Exception) { null }

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

        // Persian in query must imply Persian in lyrics
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
