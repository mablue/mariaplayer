package ir.mariaplayer.translate

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * PR-L48 (updated 2026-11): probe the backends that OnlineTranslator
 * actually uses. Old Lingva mirrors are dead as of Nov 2026 — probing
 * them produced a misleading "1/4" result while MS Edge + Google work.
 *
 * Probed in parallel:
 *   - MS Edge / Azure Translator (primary tier)
 *   - Google clients5 (secondary tier)
 *   - MyMemory (fallback tier)
 */
object TranslationProbe {

    data class Probe(
        val name: String,
        val ok: Boolean,
        val output: String,
    )

    private const val UA =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 Chrome/120.0"

    suspend fun probeAll(
        text: String,
        source: String,
        target: String,
        timeoutMs: Int = 4500,
    ): List<Probe> = coroutineScope {
        val jobs = listOf(
            async { probeMSEdge(text, target, timeoutMs) },
            async { probeGoogle(text, target, timeoutMs) },
            async { probeGoogleGtx(text, target, timeoutMs) },
            async { probeMyMemory(text, source, target, timeoutMs) },
        )
        jobs.awaitAll()
    }

    /** MS Edge rejects from=auto (400) since Nov 2026; pick fa/en by script. */
    private fun detectSource(text: String): String {
        for (ch in text) {
            if (ch in '\u0600'..'\u06FF' || ch in '\uFB50'..'\uFDFF' || ch in '\uFE70'..'\uFEFF') {
                return "fa"
            }
        }
        return "en"
    }

    private suspend fun probeMSEdge(
        text: String, target: String, timeoutMs: Int,
    ): Probe = withContext(Dispatchers.IO) {
        val name = "MS Edge"
        try {
            val from = detectSource(text)
            val body = JSONArray().put(text).toString()
            val url = "https://edge.microsoft.com/translate/translatetext" +
                    "?from=$from&to=$target&isEnterpriseClient=false"
            val resp = httpPost(
                url, body, timeoutMs,
                extraHeaders = mapOf(
                    "Origin" to "https://edge.microsoft.com",
                    "Referer" to "https://edge.microsoft.com/translate/",
                ),
            ) ?: return@withContext Probe(name, false, "no response")
            val arr = JSONObject(resp).optJSONArray("translations")
                ?: return@withContext Probe(name, false, "no translations")
            if (arr.length() == 0) return@withContext Probe(name, false, "empty")
            val t = arr.getJSONObject(0).optString("text", "")
            if (t.isBlank() || t.equals("null", ignoreCase = true))
                return@withContext Probe(name, false, "empty")
            Probe(name, true, t.trim())
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Probe(name, false, e.message?.take(40) ?: "err")
        }
    }

    private suspend fun probeGoogle(
        text: String, target: String, timeoutMs: Int,
    ): Probe = withContext(Dispatchers.IO) {
        val name = "Google"
        try {
            val enc = URLEncoder.encode(text, "UTF-8")
            val url = "https://clients5.google.com/translate_a/t" +
                    "?client=dict-chrome-ex&sl=auto&tl=$target&q=$enc"
            val body = httpGet(url, timeoutMs)
                ?: return@withContext Probe(name, false, "no response")
            val t = when {
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
                else -> ""
            }
            if (t.isBlank() || t.equals("null", ignoreCase = true))
                return@withContext Probe(name, false, "empty")
            Probe(name, true, t.trim())
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Probe(name, false, e.message?.take(40) ?: "err")
        }
    }

    private suspend fun probeGoogleGtx(
        text: String, target: String, timeoutMs: Int,
    ): Probe = withContext(Dispatchers.IO) {
        val name = "Google gtx"
        try {
            val enc = URLEncoder.encode(text, "UTF-8")
            val url = "https://translate.googleapis.com/translate_a/single" +
                    "?client=gtx&sl=auto&tl=$target&dt=t&q=$enc"
            val body = httpGet(url, timeoutMs)
                ?: return@withContext Probe(name, false, "no response")
            val outer = JSONArray(body)
            val inner = outer.getJSONArray(0)
            val sb = StringBuilder()
            for (i in 0 until inner.length()) {
                val seg = inner.optJSONArray(i) ?: continue
                if (seg.length() > 0) sb.append(seg.optString(0, ""))
            }
            val t = sb.toString().trim()
            if (t.isBlank() || t.equals("null", ignoreCase = true))
                return@withContext Probe(name, false, "empty")
            Probe(name, true, t)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Probe(name, false, e.message?.take(40) ?: "err")
        }
    }

    private suspend fun probeMyMemory(
        text: String, source: String, target: String, timeoutMs: Int,
    ): Probe = withContext(Dispatchers.IO) {
        val name = "MyMemory"
        try {
            val url = "https://api.mymemory.translated.net/get" +
                "?q=" + URLEncoder.encode(text, "UTF-8") +
                "&langpair=" + URLEncoder.encode("$source|$target", "UTF-8")
            val body = httpGet(url, timeoutMs)
                ?: return@withContext Probe(name, false, "no response")
            val o = JSONObject(body)
            val st = o.optInt("responseStatus", -1)
            if (st != 200) return@withContext Probe(name, false, "status $st")
            val rd = o.opt("responseData")
            val data = when (rd) {
                is JSONObject -> rd.optString("translatedText", "")
                is String -> rd
                else -> ""
            }
            if (data.isBlank()) return@withContext Probe(name, false, "empty")
            if (data.contains("MYMEMORY WARNING", ignoreCase = true))
                return@withContext Probe(name, false, "quota")
            if (data.contains("QUERY LENGTH LIMIT", ignoreCase = true))
                return@withContext Probe(name, false, "quota")
            Probe(name, true, data.trim())
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Probe(name, false, e.message?.take(40) ?: "err")
        }
    }

    private fun httpGet(url: String, timeoutMs: Int): String? {
        val conn = try {
            (URL(url).openConnection() as HttpURLConnection).apply {
                connectTimeout = timeoutMs
                readTimeout = timeoutMs
                requestMethod = "GET"
                setRequestProperty("User-Agent", UA)
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
