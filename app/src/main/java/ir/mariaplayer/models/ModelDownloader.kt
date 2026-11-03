package ir.mariaplayer.models

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

/**
 * PR-L8a: resume-capable model downloader.
 *
 * - Writes to "<id>.bin.part" during transfer, renames to "<id>.bin" on success.
 * - Supports HTTP Range resume: if .part exists, asks server for bytes=N-.
 *   If server ignores Range (returns 200 instead of 206), restarts from zero.
 * - Verifies SHA-256 if provided before rename; on mismatch deletes .part.
 * - Retries up to 3 times on IOException (network hiccups). Cancels cleanly
 *   via coroutine cancellation (checks ensureActive() inside read loop).
 *
 * No UI, no catalog — this is the core technology only (PR-L8b/c add those).
 */
data class DownloadProgress(
    val bytesDownloaded: Long,
    val totalBytes: Long,   // -1 if unknown
    val done: Boolean,
    val error: String? = null,
) {
    val fraction: Float
        get() = when {
            done -> 1f
            totalBytes > 0 -> (bytesDownloaded.toFloat() / totalBytes).coerceIn(0f, 1f)
            else -> 0f
        }
}

class ModelDownloader(private val context: Context) {

    private val dir: File
        get() = File(context.filesDir, "models").apply { mkdirs() }

    fun modelFile(id: String): File = File(dir, "$id.bin")
    fun partFile(id: String): File  = File(dir, "$id.bin.part")

    // PR-L31: modelDir is now side-effect-free. Callers that intend to write
    // must call .mkdirs() explicitly. This prevents isEntryDownloaded() from
    // silently creating 346 empty directories every time Models screen opens.
    fun modelDir(id: String): File = File(dir, id)
    fun modelPath(id: String, filename: String): File = File(modelDir(id), filename)

    fun isDownloaded(id: String, expectedSha256: String?): Boolean {
        val f = modelFile(id)
        if (!f.exists()) return false
        if (expectedSha256.isNullOrBlank()) return true
        return sha256(f).equals(expectedSha256, ignoreCase = true)
    }

    suspend fun download(
        id: String,
        url: String,
        expectedSha256: String?,
        onProgress: (DownloadProgress) -> Unit = {},
    ): File = withContext(Dispatchers.IO) {
        val target = modelFile(id)
        val part   = partFile(id)

        if (isDownloaded(id, expectedSha256)) {
            onProgress(DownloadProgress(target.length(), target.length(), done = true))
            return@withContext target
        }

        var lastError: String? = null
        repeat(3) { attempt ->
            try {
                val existing = if (part.exists()) part.length() else 0L
                downloadOnce(url, part, existing, onProgress)

                if (!expectedSha256.isNullOrBlank()) {
                    val got = sha256(part)
                    if (!got.equals(expectedSha256, ignoreCase = true)) {
                        part.delete()
                        throw IOException("SHA-256 mismatch: got $got")
                    }
                }
                if (target.exists()) target.delete()
                if (!part.renameTo(target)) {
                    part.copyTo(target, overwrite = true)
                    part.delete()
                }
                onProgress(DownloadProgress(target.length(), target.length(), done = true))
                return@withContext target
            } catch (e: IOException) {
                lastError = e.message ?: "IO"
                if (attempt < 2) delay(1_000L * (attempt + 1))
            } catch (e: Exception) {
                lastError = e.message ?: "Error"
            }
        }
        onProgress(
            DownloadProgress(
                bytesDownloaded = if (part.exists()) part.length() else 0L,
                totalBytes = -1L, done = false, error = lastError,
            )
        )
        throw IOException("Download failed after 3 attempts: $lastError")
    }

    private suspend fun downloadOnce(
        url: String,
        part: File,
        resumeFrom: Long,
        onProgress: (DownloadProgress) -> Unit,
    ) {
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 15_000
            readTimeout    = 30_000
            setRequestProperty("User-Agent", "MariaPlayer/1.0 (model-downloader)")
            if (resumeFrom > 0) setRequestProperty("Range", "bytes=$resumeFrom-")
        }
        try {
            val code = conn.responseCode
            val supportsRange = code == HttpURLConnection.HTTP_PARTIAL
            if (code != 200 && code != HttpURLConnection.HTTP_PARTIAL) {
                throw IOException("HTTP $code")
            }

            val startAt = if (supportsRange) resumeFrom else 0L
            val totalBytes = if (supportsRange)
                resumeFrom + conn.contentLengthLong.coerceAtLeast(-1L)
            else
                conn.contentLengthLong

            if (!supportsRange && part.exists()) part.delete()

            conn.inputStream.use { input ->
                RandomAccessFile(part, "rw").use { raf ->
                    raf.seek(startAt)
                    val buf = ByteArray(64 * 1024)
                    var downloaded = startAt
                    while (true) {
                        currentCoroutineContext().ensureActive()
                        val n = input.read(buf)
                        if (n < 0) break
                        raf.write(buf, 0, n)
                        downloaded += n
                        onProgress(DownloadProgress(downloaded, totalBytes, done = false))
                    }
                }
            }
        } finally {
            try { conn.disconnect() } catch (_: Exception) {}
        }
    }

    private fun sha256(file: File): String {
        val md = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buf = ByteArray(64 * 1024)
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                md.update(buf, 0, n)
            }
        }
        return md.digest().joinToString("") { "%02x".format(it) }
    }

    fun delete(id: String) {
        modelFile(id).delete()
        partFile(id).delete()
        modelDir(id).deleteRecursively()
    }

    // PR-L10a-fix: entry-based API. Handles both legacy single-file
    // (Whisper .bin) and multi-file (Shenava, koochik).
    fun isEntryDownloaded(entry: ModelEntry): Boolean {
        return when (entry.format) {
            "tar.bz2", "zip" -> {
                val d = modelDir(entry.id)
                val files = d.listFiles() ?: return false
                val hasModel = files.any {
                    it.name.endsWith(".onnx") &&
                    (it.name.startsWith("model") || it.name.startsWith("encoder"))
                }
                val hasTokens = files.any { it.name == "tokens.txt" }
                hasModel && hasTokens
            }
            else -> {
                if (entry.files.isNotEmpty()) {
                    entry.files.all { (name, _) ->
                        val f = File(dir, "${entry.id}/$name")
                        f.exists() && f.length() > 0
                    }
                } else {
                    isDownloaded(entry.id, entry.sha256)
                }
            }
        }
    }

    suspend fun downloadEntry(
        entry: ModelEntry,
        onProgress: (DownloadProgress) -> Unit = {},
    ): File = withContext(Dispatchers.IO) {
        when (entry.format) {
            "tar.bz2" -> return@withContext downloadAndExtract(entry, "tar.bz2", onProgress)
            "zip"     -> return@withContext downloadAndExtract(entry, "zip", onProgress)
            else -> {
                if (entry.files.isNotEmpty()) {
                    modelFile(entry.id).delete()
                    partFile(entry.id).delete()
                    downloadFiles(entry.id, entry.files, onProgress)
                } else {
                    download(entry.id, entry.url, entry.sha256, onProgress)
                }
            }
        }
    }

    /**
     * PR-L27: download an archive, extract to models/<id>/, delete the archive.
     * Extraction maps filenames to canonical (encoder/decoder/joiner/model/tokens).
     * If the archive contains no usable ASR files, throw — user sees a clear error.
     */
    private suspend fun downloadAndExtract(
        entry: ModelEntry,
        format: String,
        onProgress: (DownloadProgress) -> Unit,
    ): File = withContext(Dispatchers.IO) {
        val targetDir = modelDir(entry.id)
        targetDir.listFiles()?.forEach { runCatching { it.delete() } }
        targetDir.mkdirs()

        val ext = if (format == "tar.bz2") "tar.bz2" else "zip"
        val archive = File(dir, "${entry.id}.$ext")
        val part = File(dir, "${entry.id}.$ext.part")

        var lastErr: String? = null
        var ok = false
        repeat(3) { attempt ->
            try {
                val resumeFrom = if (part.exists()) part.length() else 0L
                downloadOnce(entry.url, part, resumeFrom, onProgress)
                if (archive.exists()) archive.delete()
                if (!part.renameTo(archive)) {
                    part.copyTo(archive, overwrite = true)
                    part.delete()
                }
                ok = true
                return@repeat
            } catch (e: Exception) {
                lastErr = e.message ?: "IO"
                if (attempt < 2) delay(1_000L * (attempt + 1))
            }
        }
        if (!ok) throw IOException("Download failed: $lastErr")
        if (archive.length() < 1000L)
            throw IOException("Archive too small: ${archive.length()} bytes")

        try {
            onProgress(DownloadProgress(archive.length(), archive.length(), done = false))
            val result = if (format == "tar.bz2")
                ArchiveExtractor.extractTarBz2(archive, targetDir)
            else
                ArchiveExtractor.extractZip(archive, targetDir)

            if (result.files.isEmpty())
                throw IOException("Archive contains no ASR files")
            if (result.files.none { it == "tokens.txt" })
                throw IOException("Archive missing tokens.txt")

            onProgress(DownloadProgress(1L, 1L, done = true))
        } finally {
            runCatching { archive.delete() }
        }
        targetDir
    }

    /**
     * PR-L10a: download a set of files into models/<id>/.
     * Each entry is (filename, url). Progress reports aggregate bytes.
     * Resumable per file via .part suffix. No SHA-256 (sherpa models don't
     * publish one); size is validated implicitly by successful HTTP 200.
     */
    suspend fun downloadFiles(
        id: String,
        files: List<Pair<String, String>>,
        onProgress: (DownloadProgress) -> Unit = {},
    ): File = withContext(Dispatchers.IO) {
        val targetDir = modelDir(id).apply { mkdirs() }
        var totalBytes = 0L
        var downloaded = 0L

        for ((filename, url) in files) {
            val dst = File(targetDir, filename)
            val part = File(targetDir, "$filename.part")
            if (dst.exists() && dst.length() > 0) {
                totalBytes += dst.length()
                downloaded += dst.length()
                onProgress(DownloadProgress(downloaded, totalBytes, done = false))
                continue
            }
            var lastErr: String? = null
            var ok = false
            repeat(3) { attempt ->
                try {
                    val resumeFrom = if (part.exists()) part.length() else 0L
                    downloadOnce(url, part, resumeFrom) { p ->
                        onProgress(DownloadProgress(downloaded + p.bytesDownloaded, -1L, false))
                    }
                    if (dst.exists()) dst.delete()
                    if (!part.renameTo(dst)) { part.copyTo(dst, true); part.delete() }
                    downloaded += dst.length()
                    totalBytes += dst.length()
                    ok = true
                    return@repeat
                } catch (e: Exception) {
                    lastErr = e.message ?: "IO"
                    if (attempt < 2) delay(1000L * (attempt + 1))
                }
            }
            if (!ok) throw IOException("Failed $filename: $lastErr")
        }
        onProgress(DownloadProgress(downloaded, downloaded, done = true))
        targetDir
    }

    /**
     * PR-L31: remove empty subdirectories under models/.
     * Prior bug: isEntryDownloaded() called modelDir() which auto-mkdir'd,
     * leaving one empty dir per catalog entry (346 in the current catalog).
     * Called on app startup to self-heal any existing installation.
     */
    /**
     * PR-L33: remove subdirectories that exist but fail engine validation
     * (e.g. toy models like tdnn-yesno that pass the download but cannot
     * run). Without this, such dirs stay on disk forever — AsrModels.list
     * filters them out so the user cannot even see them to delete.
     */
    fun cleanupInvalidModels(): Int {
        val root = dir
        if (!root.exists()) return 0
        var removed = 0
        root.listFiles()?.forEach { d ->
            if (d.isDirectory) {
                val empty = d.listFiles()?.isEmpty() != false
                if (empty) return@forEach
                val invalid = runCatching {
                    ir.mariaplayer.asr.SherpaEngine.validateOrThrow(d)
                    false
                }.getOrDefault(true)
                if (invalid) {
                    if (d.deleteRecursively()) removed++
                }
            }
        }
        return removed
    }

    fun cleanupEmptyDirs(): Int {
        val root = dir
        if (!root.exists()) return 0
        var removed = 0
        root.listFiles()?.forEach { d ->
            if (d.isDirectory) {
                val inner = d.listFiles()
                if (inner == null || inner.isEmpty()) {
                    if (d.delete()) removed++
                }
            }
        }
        return removed
    }

    fun listDownloaded(): List<File> =
        dir.listFiles { f -> f.isFile && f.extension == "bin" }?.toList().orEmpty()
}
