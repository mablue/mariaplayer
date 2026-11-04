package ir.mariaplayer.translate

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * PR-L55: Lingva Translate (OSS Google Translate frontend)
 * - No API key required
 * - Auto-detects source language
 * - Much better quality than MyMemory for fa/en pair
 */
object OnlineTranslator {

    private const val USER_AGENT = "MariaPlayer/1.0 (lyrics translate)"
    private val INSTANCES = listOf(
        "https://lingva.ml/api/v1",
        "https://translate.igna.wtf/api/v1",
        "https://translate.plausibility.cloud/api/v1"
    )

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
            val tr = translateOne(line, "auto", target, timeoutMs)
            if (tr != null) {
                out.add(tr)
                reachedNetwork = true
            } else {
                out.add(line) // Fallback to original text on failure
            }
            kotlinx.coroutines.delay(150)
        }

        if (!reachedNetwork) null else out
    }

    private suspend fun translateOne(
        text: String,
        sourceLang: String,
        targetLang: String,
        timeoutMs: Int,
    ): String? = withContext(Dispatchers.IO) {
        if (text.isBlank()) return@withContext null

        val encodedText = URLEncoder.encode(text, "UTF-8").replace("+", "%20")
        
        // Try multiple instances if one fails
        for (baseUrl in INSTANCES) {
            val urlStr = "$baseUrl/$sourceLang/$targetLang/$encodedText"
            val body = httpGet(urlStr, timeoutMs)
            if (body != null) {
                try {
                    val json = JSONObject(body)
                    val translation = json.optString("translation", "")
                    if (translation.isNotBlank()) {
                        return@withContext translation.trim()
                    }
                } catch (_: Exception) {
                    // Ignore and try next instance
                }
            }
        }
        null
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
