package ir.mariaplayer.translate

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * PR-L48: Probe multiple free translation backends in parallel so we
 * can pick one that actually works from Iran. No deps, no SDK, HTTP only.
 *
 * Backends:
 *   - MyMemory (already in use, 5000 chars/day anon quota)
 *   - Lingva: OSS Google-Translate frontend, multiple public mirrors
 */
object TranslationProbe {

    data class Probe(
        val name: String,
        val ok: Boolean,
        val output: String,
    )

    private const val UA = "MariaPlayer/1.0 (translate-probe)"

    /** Tests every backend in parallel; returns one Probe per backend. */
    suspend fun probeAll(
        text: String,
        source: String,
        target: String,
        timeoutMs: Int = 4500,
    ): List<Probe> = coroutineScope {
        val jobs = listOf(
            async { probeMyMemory(text, source, target, timeoutMs) },
            async { probeLingva("lingva.ml", text, source, target, timeoutMs) },
            async { probeLingva("lingva.garudalinux.org", text, source, target, timeoutMs) },
            async { probeLingva("translate.plausibility.cloud", text, source, target, timeoutMs) },
        )
        jobs.awaitAll()
    }

    private suspend fun probeMyMemory(
        text: String, source: String, target: String, timeoutMs: Int,
    ): Probe = withContext(Dispatchers.IO) {
        val name = "MyMemory"
        try {
            val url = "https://api.mymemory.translated.net/get" +
                "?q=" + URLEncoder.encode(text, "UTF-8") +
                "&langpair=" + URLEncoder.encode("$source|$target", "UTF-8")
            val body = httpGet(url, timeoutMs) ?: return@withContext Probe(name, false, "no response")
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
        } catch (e: Exception) {
            Probe(name, false, e.message?.take(40) ?: "err")
        }
    }

    private suspend fun probeLingva(
        host: String, text: String, source: String, target: String, timeoutMs: Int,
    ): Probe = withContext(Dispatchers.IO) {
        val name = "Lingva/$host"
        try {
            val enc = URLEncoder.encode(text, "UTF-8").replace("+", "%20")
            val url = "https://$host/api/v1/$source/$target/$enc"
            val body = httpGet(url, timeoutMs) ?: return@withContext Probe(name, false, "no response")
            val o = JSONObject(body)
            val t = o.optString("translation", "")
            if (t.isBlank()) return@withContext Probe(name, false, "empty")
            Probe(name, true, t.trim())
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
}
