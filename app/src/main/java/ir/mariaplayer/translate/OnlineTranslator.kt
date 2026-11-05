package ir.mariaplayer.translate

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

object OnlineTranslator {

    private const val USER_AGENT = "MariaPlayer/1.0 (translate)"

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
            // Try DeepLX first, then Google
            val tr = translateDeepLX(line, "auto", target, timeoutMs)
                ?: translateGoogle(line, target, timeoutMs)
            if (tr != null) {
                out.add(tr)
                reachedNetwork = true
            } else {
                out.add(line)
            }
            kotlinx.coroutines.delay(150)
        }

        if (!reachedNetwork) null else out
    }

    // DeepLX public instance (POST)
    private suspend fun translateDeepLX(
        text: String, sourceLang: String, targetLang: String, timeoutMs: Int,
    ): String? = withContext(Dispatchers.IO) {
        try {
            val url = URL("https://api.deeplx.net/translate")
            val conn = (url.openConnection() as HttpURLConnection).apply {
                connectTimeout = timeoutMs
                readTimeout = timeoutMs
                requestMethod = "POST"
                setRequestProperty("Content-Type", "application/json")
                setRequestProperty("User-Agent", USER_AGENT)
                doOutput = true
            }
            val payload = JSONObject().apply {
                put("text", text)
                put("source_lang", sourceLang.uppercase())
                put("target_lang", targetLang.uppercase())
            }
            conn.outputStream.write(payload.toString().toByteArray())
            if (conn.responseCode != 200) return@withContext null
            val body = conn.inputStream.bufferedReader().use { it.readText() }
            val json = JSONObject(body)
            val data = json.optString("data", "")
            if (data.isBlank()) null else data.trim()
        } catch (e: Exception) {
            null
        }
    }

    // Google Translate unofficial endpoint (GET)
    private suspend fun translateGoogle(
        text: String, targetLang: String, timeoutMs: Int,
    ): String? = withContext(Dispatchers.IO) {
        try {
            val encodedText = URLEncoder.encode(text, "UTF-8")
            val url = "https://translate.googleapis.com/translate_a/single?client=gtx&sl=auto&tl=$targetLang&dt=t&q=$encodedText"
            val conn = (URL(url).openConnection() as HttpURLConnection).apply {
                connectTimeout = timeoutMs
                readTimeout = timeoutMs
                setRequestProperty("User-Agent", USER_AGENT)
            }
            if (conn.responseCode != 200) return@withContext null
            val body = conn.inputStream.bufferedReader().use { it.readText() }
            // Response is a nested JSON array: [[["translated text","source",...]]]
            val start = body.indexOf('"') + 1
            val end = body.indexOf('"', start)
            if (start > 0 && end > start) body.substring(start, end).trim() else null
        } catch (e: Exception) {
            null
        }
    }
}
