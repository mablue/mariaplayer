package ir.mariaplayer.models

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * PR-L8b: catalog of downloadable models.
 *
 * Source resolution order:
 *   1. If REMOTE_URL is set and network works → fetch, cache, use.
 *   2. Cached remote (last successful fetch) — survives offline restarts.
 *   3. Bundled asset `models.json` — always available, offline baseline.
 *
 * Update strategy is lazy: no background polling. Caller (ModelManagerScreen)
 * invokes refresh() on launch; failure silently falls back to cache/asset.
 */
data class ModelEntry(
    val id: String,
    val name: String,
    val languages: List<String>,
    val sizeBytes: Long,
    val url: String,
    val format: String,
    val engine: String,
    val wer: Double?,
    val license: String,
    val musicRating: String,
    val sha256: String?,
    val files: List<Pair<String, String>> = emptyList(),
) {
    val sizeMb: Int get() = (sizeBytes / (1024 * 1024)).toInt().coerceAtLeast(1)
    val langLabel: String get() = languages.joinToString(", ").ifBlank { "?" }
    val werLabel: String
        get() = wer?.let { "${(it * 100).toInt()}%" } ?: "—"
}

object ModelCatalog {

    /**
     * Set to a Gist / CDN URL when you want remote updates.
     * Empty = asset only. Users on CI builds get asset; a future Settings
     * can flip this at runtime via a DataStore pref.
     */
    private const val REMOTE_URL = ""

    private const val ASSET_FILE = "models.json"
    private const val CACHE_FILE = "models_catalog_cache.json"

    suspend fun load(context: Context, remoteUrl: String = REMOTE_URL): List<ModelEntry> =
        withContext(Dispatchers.IO) {
            // 1) remote
            if (remoteUrl.isNotBlank()) {
                try {
                    val body = httpGet(remoteUrl)
                    if (!body.isNullOrBlank()) {
                        val parsed = parse(body)
                        if (parsed.isNotEmpty()) {
                            File(context.filesDir, CACHE_FILE).writeText(body)
                            return@withContext parsed
                        }
                    }
                } catch (_: Exception) { /* fall through */ }
            }
            // 2) cache
            try {
                val cache = File(context.filesDir, CACHE_FILE)
                if (cache.exists()) {
                    val parsed = parse(cache.readText())
                    if (parsed.isNotEmpty()) return@withContext parsed
                }
            } catch (_: Exception) { /* fall through */ }
            // 3) asset
            try {
                val body = context.assets.open(ASSET_FILE)
                    .bufferedReader().use { it.readText() }
                parse(body)
            } catch (_: Exception) { emptyList() }
        }

    fun parse(raw: String): List<ModelEntry> = try {
        val root = JSONObject(raw)
        val arr = root.optJSONArray("models") ?: return emptyList()
        val out = ArrayList<ModelEntry>(arr.length())
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            out += ModelEntry(
                id          = o.getString("id"),
                name        = o.getString("name"),
                languages   = o.optJSONArray("language")?.let { a ->
                    (0 until a.length()).map { a.getString(it) }
                } ?: emptyList(),
                sizeBytes   = o.optLong("sizeBytes", 0L),
                url         = o.getString("url"),
                format      = o.optString("format", "unknown"),
                engine      = o.optString("engine", "unknown"),
                wer         = if (o.has("wer")) o.optDouble("wer").takeIf { !it.isNaN() } else null,
                license     = o.optString("license", "Unknown"),
                musicRating = o.optString("musicRating", "Unknown"),
                sha256      = o.optString("sha256", "").ifBlank { null },
                files       = o.optJSONArray("files")?.let { a ->
                    (0 until a.length()).map { i ->
                        val f = a.getJSONObject(i)
                        f.getString("name") to f.getString("url")
                    }
                } ?: emptyList(),
            )
        }
        out.sortedWith(compareBy({ it.engine }, { it.sizeBytes }))
    } catch (_: Exception) { emptyList() }

    private fun httpGet(url: String): String? {
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 8000
            readTimeout    = 15000
            setRequestProperty("User-Agent", "MariaPlayer/1.0 (model-catalog)")
        }
        return try {
            if (conn.responseCode != 200) null
            else conn.inputStream.bufferedReader().use { it.readText() }
        } catch (_: Exception) { null } finally {
            try { conn.disconnect() } catch (_: Exception) {}
        }
    }
}
