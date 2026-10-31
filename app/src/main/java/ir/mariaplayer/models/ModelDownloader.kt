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
    }

    fun listDownloaded(): List<File> =
        dir.listFiles { f -> f.isFile && f.extension == "bin" }?.toList().orEmpty()
}
