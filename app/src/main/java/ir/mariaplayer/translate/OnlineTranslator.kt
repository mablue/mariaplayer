package ir.mariaplayer.translate

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * PR-L55: Chained translation.
 * 1) SimplyTranslate (Google backend) — high quality, works from Iran.
 * 2) MyMemory — fallback, works but lower quality.
 */
object OnlineTranslator {

    private const val UA = "MariaPlayer/1.0 (translate)"

    suspend fun translateBatch(
        lines: List<String>,
        targetLang: String,
        timeoutMs: Int = 6000,
    ): List<String>? = withContext(Dispatchers.IO) {
        if (lines.isEmpty()) return@withContext emptyList()
        if (targetLang.isBlank()) return@withContext null
        if (lines.size > 80) return@withContext null

        val target = targetLang.lowercase().trim()
        val out = ArrayList<String>(lines.size)
        var reachedNetwork = false

        for (line in lines) {
            if (line.isBlank()) { out.add(""); continue }

            // Try SimplyTranslate first (better quality)
            val tr = translateSimply(line, target, timeoutMs)
                ?: translateMyMemory(line, target, timeoutMs)

            if (tr != null) {
                out.add(tr)
                reachedNetwork = true
            } else {
                out.add(line) // keep original on total failure
            }
            kotlinx.coroutines.delay(120)
        }

        if (!reachedNetwork) null else out
    }

    // ── SimplyTranslate (Google engine) ──────────────────────────────
    private suspend fun translateSimply(
        text: String, targetLang: String, timeoutMs: Int,
    ): String? = withContext(Dispatchers.IO) {
        try {
            val encoded = URLEncoder.encode(text, "UTF-8")
            val url = "https://simplytranslate.org/api/translate/" +
                    "?engine=google&from=auto&to=$targetLang&text=$encoded"
            val body = httpGet(url, timeoutMs) ?: return@withContext null
            val json = JSONObject(body)
            val result = json.optString("translated-text", "")
            if (result.isBlank()) null else result.trim()
        } catch (_: Exception) {
            null
        }
    }

    // ── MyMemory (fallback) ────────────────────────────────────────
    private suspend fun translateMyMemory(
        text: String, targetLang: String, timeoutMs: Int,
    ): String? = withContext(Dispatchers.IO) {
        try {
            val encoded = URLEncoder.encode(text, "UTF-8")
            val pair = URLEncoder.encode("Autodetect|$targetLang", "UTF-8")
            val url = "https://api.mymemory.translated.net/get?q=$encoded&langpair=$pair"
            val body = httpGet(url, timeoutMs) ?: return@withContext null
            val json = JSONObject(body)
            if (json.optInt("responseStatus", -1) != 200) return@withContext null
            val rd = json.opt("responseData")
            val data = when (rd) {
                is JSONObject -> rd.optString("translatedText", "")
                is String -> rd
                else -> ""
            }
            if (data.isBlank() || data.contains("MYMEMORY WARNING", true) ||
                data.contains("QUERY LENGTH LIMIT", true)) null else data.trim()
        } catch (_: Exception) {
            null
        }
    }

    private fun httpGet(url: String, timeoutMs: Int): String? {
        val conn = try {
            (URL(url).openConnection() as HttpURLConnection).apply {
                connectTimeout = timeoutMs
                readTimeout = timeoutMs
                setRequestProperty("User-Agent", UA)
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
