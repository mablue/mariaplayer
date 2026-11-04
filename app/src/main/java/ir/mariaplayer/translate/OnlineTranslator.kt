package ir.mariaplayer.translate

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * PR-L47-fix: MyMemory instead of Google (Google blocks Iranian IPs).
 */
object OnlineTranslator {

    private const val USER_AGENT = "MariaPlayer/1.0 (lyrics translate)"
    private const val BASE = "https://api.mymemory.translated.net/get"

    private fun pair(targetLang: String): String {
        val target = targetLang.lowercase().trim()
        // PR-54: MyMemory supports "Autodetect" for source.
        // Hardcoding "en" caused phonetic gibberish for non-English songs.
        return "Autodetect|$target"
    }

    suspend fun translateBatch(
        lines: List<String>,
        targetLang: String,
        timeoutMs: Int = 8000,
    ): List<String>? = withContext(Dispatchers.IO) {
        if (lines.isEmpty()) return@withContext emptyList()
        if (targetLang.isBlank()) return@withContext null
        if (lines.size > 80) return@withContext null

        val langPair = pair(targetLang)
        val out = ArrayList<String>(lines.size)
        var reachedNetwork = false

        for (line in lines) {
            if (line.isBlank()) { out.add(""); continue }
            val tr = translateOne(line, langPair, timeoutMs)
            if (tr != null) {
                out.add(tr)
                reachedNetwork = true
            } else {
                out.add(line)
            }
            kotlinx.coroutines.delay(100)
        }

        if (!reachedNetwork) null else out
    }

    suspend fun translateOne(
        text: String,
        langPair: String,
        timeoutMs: Int = 6000,
    ): String? = withContext(Dispatchers.IO) {
        if (text.isBlank()) return@withContext null
        if (text.toByteArray(Charsets.UTF_8).size > 480) return@withContext null

        val urlStr = buildString {
            append(BASE)
            append("?q=").append(URLEncoder.encode(text, "UTF-8"))
            append("&langpair=").append(URLEncoder.encode(langPair, "UTF-8"))
        }

        val body = httpGet(urlStr, timeoutMs) ?: return@withContext null
        try {
            val o = JSONObject(body)
            val status = o.optInt("responseStatus", -1)
            if (status != 200) return@withContext null
            // PR-L47-hotfix: MyMemory returns responseData as a JSON OBJECT
            // {translatedText, match, ...}, not a plain string. Older / error
            // paths may still put a string there, so handle both shapes.
            val rd = o.opt("responseData")
            val data = when (rd) {
                is JSONObject -> rd.optString("translatedText", "")
                is String -> rd
                else -> ""
            }
            if (data.isBlank()) return@withContext null
            if (data.contains("MYMEMORY WARNING", ignoreCase = true)) return@withContext null
            if (data.contains("QUERY LENGTH LIMIT", ignoreCase = true)) return@withContext null
            data.trim()
        } catch (_: Exception) {
            null
        }
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
