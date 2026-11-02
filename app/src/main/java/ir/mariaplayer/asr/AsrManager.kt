package ir.mariaplayer.asr

import android.content.Context
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.math.sqrt

/**
 * PR-L15b: full-track transcribe with silence-based chunk boundaries.
 *
 * Root cause of PR-L15 truncation: fixed 10s chunks cut inside words,
 * and RNNT (Koochik) needs longer context than CTC (Rizeh) to fill the
 * decoder state. Each chunk therefore ends at the local energy minimum
 * within ±1.5s of the target boundary, producing natural sentence breaks.
 *
 * Also decodes the whole file ONCE (via AudioDecoder.decodeFull) instead
 * of re-seeking MediaExtractor per chunk.
 */
object AsrManager {

    data class AsrChunk(val startMs: Long, val text: String)

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

    suspend fun transcribeFull(
        context: Context,
        uri: Uri,
        modelDir: File,
        totalMs: Long,
        chunkMs: Long = 15_000L,
        startMs: Long = 0L,
        onProgress: (Float, String) -> Unit = { _, _ -> },
    ): List<AsrChunk> = withContext(Dispatchers.Default) {
        require(modelDir.isDirectory) { "model dir missing: $modelDir" }
        require(totalMs > 0) { "totalMs must be > 0" }

        onProgress(0f, "Decoding audio…")
        val all = AudioDecoder.decodeFull(context, uri, startMs + totalMs + 5_000L)

        // Slice from startMs
        val startSamples = (startMs * 16000 / 1000L).toInt().coerceAtLeast(0)
        val samples = if (startSamples < all.size) all.copyOfRange(startSamples, all.size)
                      else FloatArray(0)

        if (samples.isEmpty()) return@withContext emptyList()

        val chunkSamples = (chunkMs * 16000 / 1000L).toInt()
        val minChunkSamples = (5_000 * 16000 / 1000L).toInt()

        val eng = SherpaEngine(modelDir)
        val startedAt = System.currentTimeMillis()
        val result = ArrayList<AsrChunk>()
        try {
            var pos = 0
            while (pos < samples.size) {
                currentCoroutineContext().ensureActive()
                val targetEnd = (pos + chunkSamples).coerceAtMost(samples.size)
                val end = if (targetEnd < samples.size)
                    findSilenceNear(samples, targetEnd, 1500, minChunkSamples)
                else samples.size

                val slice = samples.copyOfRange(pos, end)
                if (slice.size >= 1000) {
                    val text = runCatching { eng.transcribe(slice).trim() }
                        .getOrDefault("")
                    result.add(AsrChunk(pos * 1000L / 16000L, text))
                }
                pos = end

                val progress = pos.toFloat() / samples.size
                val elapsed = System.currentTimeMillis() - startedAt
                val eta = if (progress > 0.05f)
                    (elapsed / progress - elapsed).toLong() else -1L
                val etaStr = if (eta > 1000) " · ${formatEta(eta)} left" else ""
                onProgress(progress, "${(progress * 100).toInt()}%$etaStr")
            }
        } finally {
            runCatching { eng.close() }
        }
        result
    }

    /**
     * Find the local energy minimum within ±windowMs of [target].
     * Returns the sample index of that minimum, biased toward [target]
     * if no silence stands out (energy is flat).
     */
    private fun findSilenceNear(
        samples: FloatArray,
        target: Int,
        windowMs: Int = 1500,
        minChunk: Int = 80_000,
    ): Int {
        val window = 16000 * windowMs / 1000
        val lo = (target - window).coerceAtLeast(minChunk)
        val hi = (target + window).coerceAtMost(samples.size - 1)
        if (hi - lo < 2000) return target

        val step = 800 // 50 ms analysis frame
        var bestPos = target
        var bestRms = Float.MAX_VALUE
        var p = lo
        while (p + step <= hi) {
            var sum = 0.0
            for (i in 0 until step) {
                val v = samples[p + i].toDouble()
                sum += v * v
            }
            val rms = sqrt(sum / step).toFloat()
            if (rms < bestRms) {
                bestRms = rms
                bestPos = p
            }
            p += step / 2 // 50% overlap for finer resolution
        }
        return bestPos.coerceIn(lo, hi)
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

    private fun formatEta(ms: Long): String {
        val sec = (ms / 1000).coerceAtLeast(0)
        return if (sec < 60) "${sec}s" else "${sec / 60}m ${sec % 60}s"
    }
}
