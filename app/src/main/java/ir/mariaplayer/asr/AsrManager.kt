package ir.mariaplayer.asr

import android.content.Context
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File

/**
 * PR-L20: 30 s chunks with 3 s overlap. No VAD.
 *
 * Silero VAD on music strips vocals as noise (see LESSONS.md), so we
 * deliberately avoid it. Larger chunks (30 s vs 15 s) halve the number
 * of boundaries, and 3 s overlap guarantees every boundary word appears
 * fully in at least one chunk. smartMerge dedups the overlap using
 * longest word n-gram matching.
 */
object AsrManager {

    data class AsrChunk(val startMs: Long, val text: String)

    private const val CHUNK_MS = 30_000L
    private const val OVERLAP_MS = 3_000L
    private const val STEP_MS = CHUNK_MS - OVERLAP_MS

    suspend fun transcribe(
        context: Context, uri: Uri, modelDir: File,
        startMs: Long = 0L, totalMs: Long = 30_000L,
        chunkMs: Long = 5_000L, onProgress: (Float) -> Unit = {},
    ): String = withContext(Dispatchers.Default) {
        val chunks = transcribeFull(context, uri, modelDir, totalMs, startMs) { p, _ -> onProgress(p) }
        chunks.map { it.text }.filter { it.isNotBlank() }.joinToString(" ")
    }

    suspend fun transcribeFull(
        context: Context, uri: Uri, modelDir: File,
        totalMs: Long, startMs: Long = 0L,
        onProgress: (Float, String) -> Unit = { _, _ -> },
    ): List<AsrChunk> = withContext(Dispatchers.Default) {
        require(modelDir.isDirectory) { "model dir missing: $modelDir" }
        require(totalMs > 0) { "totalMs must be > 0" }

        onProgress(0f, "Decoding audio…")
        val all = AudioDecoder.decodeFull(context, uri, startMs + totalMs + 5_000L)
        val s0 = (startMs * 16000 / 1000L).toInt().coerceAtLeast(0)
        val samples = if (s0 < all.size) all.copyOfRange(s0, all.size) else FloatArray(0)
        if (samples.isEmpty()) return@withContext emptyList()

        val chunkSamples = (CHUNK_MS * 16000 / 1000L).toInt()
        val stepSamples  = (STEP_MS  * 16000 / 1000L).toInt()

        val eng = SherpaEngine(modelDir)
        val startedAt = System.currentTimeMillis()
        val raw = ArrayList<AsrChunk>()
        try {
            var pos = 0
            var idx = 0
            while (true) {
                currentCoroutineContext().ensureActive()
                val end = (pos + chunkSamples).coerceAtMost(samples.size)
                val slice = samples.copyOfRange(pos, end)
                if (slice.size >= 2000) {
                    val text = runCatching { eng.transcribe(slice).trim() }
                        .getOrDefault("")
                    raw.add(AsrChunk(pos * 1000L / 16000L, text))
                }
                idx++
                if (end >= samples.size) break
                pos += stepSamples

                val progress = pos.toFloat() / samples.size
                val elapsed = System.currentTimeMillis() - startedAt
                val eta = if (progress > 0.05f)
                    (elapsed / progress - elapsed).toLong() else -1L
                val etaStr = if (eta > 1000) " · ${fmtEta(eta)} left" else ""
                onProgress(progress.coerceAtMost(1f), "Chunk $idx · ${(progress*100).toInt()}%$etaStr")
            }
        } finally {
            runCatching { eng.close() }
        }

        // Merge overlapping chunks
        val merged = ArrayList<AsrChunk>()
        for (c in raw) {
            if (c.text.isBlank()) continue
            if (merged.isEmpty()) merged.add(c)
            else {
                val prev = merged.last()
                merged[merged.lastIndex] = AsrChunk(
                    prev.startMs, smartMerge(prev.text, c.text),
                )
            }
        }
        merged
    }

    /**
     * PR-L20: longest word n-gram boundary dedup.
     * Find longest k (>=2) such that last k words of [a] == first k of [b].
     * Drop that overlap from [b] and append the rest.
     */
    private fun smartMerge(a: String, b: String): String {
        if (a.isBlank()) return b
        if (b.isBlank()) return a
        val aw = a.trim().split(Regex("\\s+"))
        val bw = b.trim().split(Regex("\\s+"))
        val maxK = minOf(aw.size, bw.size)
        for (k in maxK downTo 2) {
            val aTail = aw.takeLast(k).joinToString(" ")
            val bHead = bw.take(k).joinToString(" ")
            if (aTail == bHead) {
                val tail = bw.drop(k).joinToString(" ")
                return if (tail.isBlank()) a else "$a $tail"
            }
        }
        return "$a $b"
    }

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

    private fun fmtEta(ms: Long): String {
        val sec = (ms / 1000).coerceAtLeast(0)
        return if (sec < 60) "${sec}s" else "${sec / 60}m ${sec % 60}s"
    }

    /** Test-visible wrapper for the merge logic. */
    fun mergeForTest(chunks: List<AsrChunk>): List<AsrChunk> {
        val merged = ArrayList<AsrChunk>()
        for (c in chunks) {
            if (c.text.isBlank()) continue
            if (merged.isEmpty()) merged.add(c)
            else merged[merged.lastIndex] = AsrChunk(
                merged.last().startMs, smartMerge(merged.last().text, c.text),
            )
        }
        return merged
    }
}
