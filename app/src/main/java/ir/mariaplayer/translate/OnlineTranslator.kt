package ir.mariaplayer.translate

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

object OnlineTranslator {

    private const val UA =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 Chrome/120.0"

    suspend fun translateBatch(
        lines: List<String>,
        targetLang: String,
        timeoutMs: Int = 6000,
    ): List<String>? = withContext(Dispatchers.IO) {
        if (lines.isEmpty()) return@withContext emptyList()
        if (targetLang.isBlank()) return@withContext null
        if (lines.size > 80) return@withContext null

        val target = targetLang.lowercase().trim()

        translateMSBatch(lines, target, timeoutMs)?.let { return@withContext it }
        translateGoogleBatch(lines, target, timeoutMs)?.let { return@withContext it }
        translateMyMemoryBatch(lines, target, timeoutMs)
    }

    private fun sanitizeLocal(s: String?): String? =
        ir.mariaplayer.util.sanitizeJsonString(s)

    // ── Tier 1: MS Edge / Azure Translator ─────────────────────────
    private suspend fun translateMSBatch(
        lines: List<String>, target: String, timeoutMs: Int,
    ): List<String>? = withContext(Dispatchers.IO) {
        try {
            val joined = lines.joinToString("\n")
            val body = JSONArray().put(joined).toString()
            val url = "https://edge.microsoft.com/translate/translatetext" +
                    "?from=auto&to=$target&isEnterpriseClient=false"
            val resp = httpPost(
                url, body, timeoutMs,
                extraHeaders = mapOf(
                    "Origin" to "https://edge.microsoft.com",
                    "Referer" to "https://edge.microsoft.com/translate/",
                ),
            ) ?: return@withContext null
            val arr = JSONObject(resp).optJSONArray("translations")
                ?: return@withContext null
            if (arr.length() == 0) return@withContext null
            val translated = sanitizeLocal(arr.getJSONObject(0).optString("text", ""))
                ?: return@withContext null
            val split = translated.split("\n")
            if (split.size == lines.size) split else null
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            null
        }
    }

    // ── Tier 2: Google clients5 ────────────────────────────────────
    private suspend fun translateGoogleBatch(
        lines: List<String>, target: String, timeoutMs: Int,
    ): List<String>? = withContext(Dispatchers.IO) {
        try {
            val joined = lines.joinToString("\n")
            val encoded = URLEncoder.encode(joined, "UTF-8")
            val url = "https://clients5.google.com/translate_a/t" +
                    "?client=dict-chrome-ex&sl=auto&tl=$target&q=$encoded"
            val body = httpGet(url, timeoutMs) ?: return@withContext null
            val translated = when {
                body.startsWith("[[") -> {
                    val outer = JSONArray(body)
                    val inner = outer.getJSONArray(0)
                    val sb = StringBuilder()
                    for (i in 0 until inner.length()) {
                        val seg = inner.optJSONArray(i) ?: continue
                        if (seg.length() > 0) sb.append(seg.optString(0, ""))
                    }
                    sb.toString()
                }
                body.startsWith("[") -> JSONArray(body).optString(0, "")
                else -> return@withContext null
            }
            sanitizeLocal(translated)?.let {
                val split = it.split("\n")
                if (split.size == lines.size) split else null
            }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            null
        }
    }

    // ── Tier 3: MyMemory per-line (500-byte limit) ─────────────────
    private suspend fun translateMyMemoryBatch(
        lines: List<String>, target: String, timeoutMs: Int,
    ): List<String>? = withContext(Dispatchers.IO) {
        val out = ArrayList<String>(lines.size)
        var reached = false
        for (line in lines) {
            if (line.isBlank()) { out.add(""); continue }
            val tr = translateMyMemory(line, target, timeoutMs)
            if (tr != null) { out.add(tr); reached = true } else { out.add(line) }
            delay(120)
        }
        if (!reached) null else out
    }

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
            if (data.contains("MYMEMORY WARNING", ignoreCase = true) ||
                data.contains("QUERY LENGTH LIMIT", ignoreCase = true)) return@withContext null
            sanitizeLocal(data)
        } catch (e: CancellationException) {
            throw e
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

    private fun httpPost(
        url: String,
        body: String,
        timeoutMs: Int,
        extraHeaders: Map<String, String> = emptyMap(),
    ): String? {
        val conn = try {
            (URL(url).openConnection() as HttpURLConnection).apply {
                connectTimeout = timeoutMs
                readTimeout = timeoutMs
                requestMethod = "POST"
                doOutput = true
                setRequestProperty("User-Agent", UA)
                setRequestProperty("Content-Type", "application/json")
                extraHeaders.forEach { (k, v) -> setRequestProperty(k, v) }
            }
        } catch (_: Exception) { return null }
        return try {
            conn.outputStream.use { it.write(body.toByteArray()) }
            if (conn.responseCode != 200) null
            else conn.inputStream.bufferedReader().use { it.readText() }
        } catch (_: Exception) { null } finally {
            try { conn.disconnect() } catch (_: Exception) {}
        }
    }
}
