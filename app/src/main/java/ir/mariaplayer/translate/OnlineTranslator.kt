package ir.mariaplayer.translate

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * PR-L47: online translation via Google's free "gtx" endpoint.
 *
 * No API key. Rate-limited (~100 req/hour per IP). We mitigate by:
 *   - Batching all lines into one request per song
 *   - Caching per (trackId, targetLang) in LyricsCache
 *   - Never auto-translating; user toggles in settings
 *
 * Endpoint:
 *   GET https://translate.googleapis.com/translate_a/single
 *     ?client=gtx&sl=auto&tl=<lang>&dt=t&q=<encoded>
 *
 * Response format:
 *   [[["ترجمه۱","original۱",...],["ترجمه۲","original۲",...]],...]
 *   We join segment[0] of each entry, which preserves "\n".
 */
object OnlineTranslator {

    private const val USER_AGENT = "Mozilla/5.0 (Linux; Android 14) MariaPlayer/1.0"

    /**
     * Translate a list of lines. Sends them joined with \n in one request.
     * Returns List<String> of same size, or null on failure / count mismatch.
     */
    suspend fun translateBatch(
        lines: List<String>,
        targetLang: String,
        timeoutMs: Int = 8000,
    ): List<String>? = withContext(Dispatchers.IO) {
        if (lines.isEmpty()) return@withContext emptyList()
        if (targetLang.isBlank()) return@withContext null

        // Safety: >80 lines → rate limit would be hit; refuse.
        if (lines.size > 80) return@withContext null

        val joined = lines.joinToString("\n")
        if (joined.length > 4500) return@withContext null  // URL length guard

        val urlStr = buildString {
            append("https://translate.googleapis.com/translate_a/single")
            append("?client=gtx")
            append("&sl=auto")
            append("&tl=").append(URLEncoder.encode(targetLang, "UTF-8"))
            append("&dt=t")
            append("&q=").append(URLEncoder.encode(joined, "UTF-8"))
        }

        val body = httpGet(urlStr, timeoutMs) ?: return@withContext null
        parseResponse(body, lines.size)
    }

    /**
     * Single-line convenience (used if batch fails).
     */
    suspend fun translateOne(
        text: String,
        targetLang: String,
        timeoutMs: Int = 6000,
    ): String? = withContext(Dispatchers.IO) {
        if (text.isBlank() || targetLang.isBlank()) return@withContext null
        val urlStr = buildString {
            append("https://translate.googleapis.com/translate_a/single")
            append("?client=gtx&sl=auto&tl=").append(URLEncoder.encode(targetLang, "UTF-8"))
            append("&dt=t&q=").append(URLEncoder.encode(text, "UTF-8"))
        }
        val body = httpGet(urlStr, timeoutMs) ?: return@withContext null
        try {
            val arr = JSONArray(body)
            val segs = arr.getJSONArray(0)
            val sb = StringBuilder()
            for (i in 0 until segs.length()) {
                val seg = segs.optJSONArray(i) ?: continue
                if (seg.length() > 0) sb.append(seg.optString(0, ""))
            }
            sb.toString().trim().ifBlank { null }
        } catch (_: Exception) { null }
    }

    private fun parseResponse(body: String, expectedCount: Int): List<String>? {
        return try {
            val arr = JSONArray(body)
            val segs = arr.getJSONArray(0)
            val sb = StringBuilder()
            for (i in 0 until segs.length()) {
                val seg = segs.optJSONArray(i) ?: continue
                if (seg.length() > 0) sb.append(seg.optString(0, ""))
            }
            val full = sb.toString()
            val split = full.split("\n").map { it.trim() }
            // Google may merge/split; require exact count
            if (split.size != expectedCount) return null
            split
        } catch (_: Exception) { null }
    }

    private fun httpGet(url: String, timeoutMs: Int): String? {
        val conn = try {
            (URL(url).openConnection() as HttpURLConnection).apply {
                connectTimeout = timeoutMs
                readTimeout = timeoutMs
                requestMethod = "GET"
                setRequestProperty("User-Agent", USER_AGENT)
                setRequestProperty("Accept", "application/json")
            }
        } catch (_: Exception) { return null }
        return try {
            if (conn.responseCode != 200) null
            else conn.inputStream.bufferedReader().use { it.readText() }
        } catch (_: Exception) { null } finally {
            try { conn.disconnect() } catch (_: Exception) {}
        }
    }
}
