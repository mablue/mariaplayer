package ir.mariaplayer.asr

import android.content.Context
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File

/**
 * PR-L15: full-track transcribe with per-chunk timestamps.
 *
 * Chunks audio into 10s slices, transcribes each, returns (startMs, text)
 * pairs. Each non-blank chunk becomes one LRC line — this gives us approximate
 * subtitle timing for free. Chunk boundaries may cut words, so line text is
 * best-effort; user can edit afterwards.
 */
object AsrManager {

    data class AsrChunk(val startMs: Long, val text: String)

    /** Simple single-pass transcribe (used by SelfTest). */
    suspend fun transcribe(
        context: Context,
        uri: Uri,
        modelDir: File,
        startMs: Long = 0L,
        totalMs: Long = 30_000L,
        chunkMs: Long = 5_000L,
        onProgress: (Float) -> Unit = {},
    ): String = withContext(Dispatchers.Default) {
        val chunks = transcribeFull(
            context, uri, modelDir, totalMs, chunkMs,
            startMs = startMs,
            onProgress = { p, _ -> onProgress(p) },
        )
        chunks.map { it.text }.filter { it.isNotBlank() }.joinToString(" ")
    }

    /**
     * Full transcribe with per-chunk timing.
     * [onProgress] receives (progress 0..1, status string including ETA).
     */
    suspend fun transcribeFull(
        context: Context,
        uri: Uri,
        modelDir: File,
        totalMs: Long,
        chunkMs: Long = 10_000L,
        startMs: Long = 0L,
        onProgress: (Float, String) -> Unit = { _, _ -> },
    ): List<AsrChunk> = withContext(Dispatchers.Default) {
        require(modelDir.isDirectory) { "model dir missing: $modelDir" }
        require(totalMs > 0) { "totalMs must be > 0" }
        val chunkCount = ((totalMs + chunkMs - 1) / chunkMs).toInt()
        val result = ArrayList<AsrChunk>(chunkCount)
        val eng = SherpaEngine(modelDir)
        val startedAt = System.currentTimeMillis()
        try {
            for (i in 0 until chunkCount) {
                currentCoroutineContext().ensureActive()
                val chunkStart = startMs + i * chunkMs
                val samples = try {
                    AudioDecoder.decodeToFloat(
                        context = context,
                        uri = uri,
                        startMs = chunkStart,
                        durationMs = chunkMs,
                    )
                } catch (_: Throwable) {
                    FloatArray(0)
                }
                val text = if (samples.isEmpty()) "" else runCatching {
                    eng.transcribe(samples).trim()
                }.getOrDefault("")
                result.add(AsrChunk(chunkStart, text))

                val done = i + 1
                val progress = done.toFloat() / chunkCount
                val elapsed = System.currentTimeMillis() - startedAt
                val eta = if (progress > 0.05f)
                    (elapsed / progress - elapsed).toLong() else -1L
                val etaStr = if (eta > 1000) " · ${formatEta(eta)} left" else ""
                onProgress(progress, "Chunk $done/$chunkCount$etaStr")
            }
        } finally {
            runCatching { eng.close() }
        }
        result
    }

    /** Convert chunks to LRC. Blank chunks are dropped. */
    fun toLrc(chunks: List<AsrChunk>): String {
        val sb = StringBuilder()
        for (c in chunks) {
            if (c.text.isBlank()) continue
            val sec = c.startMs / 1000
            val min = sec / 60
            val s = sec % 60
            val cs = (c.startMs % 1000) / 10
            if (sb.isNotEmpty()) sb.append('\n')
            sb.append("[%02d:%02d.%02d]%s".format(min, s, cs, c.text))
        }
        return sb.toString()
    }

    private fun formatEta(ms: Long): String {
        val sec = (ms / 1000).coerceAtLeast(0)
        return if (sec < 60) "${sec}s" else "${sec / 60}m ${sec % 60}s"
    }
}
