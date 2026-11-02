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
 * PR-L17: transcribe with bounded memory AND silence-aware chunk boundaries.
 *
 * Peak memory is one probe window (~chunkMs + 3s), not the whole track.
 * For each iteration:
 *   - decode [posMs, posMs + chunkMs + marginMs)  ← the "probe"
 *   - find the local energy minimum near chunkSamples
 *   - keep only [0, silenceAt), feed to sherpa, advance posMs to silenceAt
 * This preserves the sentence-aligned chunk boundaries that PR-L15b added,
 * without ever holding the entire file in a FloatArray.
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

        val chunkSamples = (chunkMs * 16000L / 1000L).toInt()
        val minChunkSamples = (5_000L * 16000L / 1000L).toInt()
        val probeMarginMs = 3_000L

        val eng = SherpaEngine(modelDir)
        val startedAt = System.currentTimeMillis()
        val result = ArrayList<AsrChunk>()

        try {
            var posMs = startMs
            val endMs = startMs + totalMs

            while (posMs < endMs - 500L) {
                currentCoroutineContext().ensureActive()
                val remainingMs = endMs - posMs
                val probeMs = minOf(chunkMs + probeMarginMs, remainingMs)

                val probe = try {
                    AudioDecoder.decodeToFloat(context, uri, posMs, probeMs)
                } catch (_: Throwable) {
                    FloatArray(0)
                }

                if (probe.isEmpty()) {
                    // Decode failure on this region — skip forward
                    posMs += chunkMs
                    continue
                }

                val (slice, advanceMs) = if (probe.size <= chunkSamples) {
                    probe to remainingMs
                } else {
                    val silenceAt = findSilenceNear(
                        probe, chunkSamples, windowMs = 1500, minChunk = minChunkSamples,
                    )
                    probe.copyOfRange(0, silenceAt) to (silenceAt * 1000L / 16000L)
                }

                val text = runCatching { eng.transcribe(slice).trim() }.getOrDefault("")
                result.add(AsrChunk(posMs, text))
                posMs += advanceMs

                val progress = ((posMs - startMs).toFloat() / totalMs).coerceIn(0f, 1f)
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

    private fun findSilenceNear(
        samples: FloatArray,
        target: Int,
        windowMs: Int,
        minChunk: Int,
    ): Int {
        val window = 16000 * windowMs / 1000
        val lo = (target - window).coerceAtLeast(minChunk)
        val hi = (target + window).coerceAtMost(samples.size - 1)
        if (hi - lo < 2000) return target

        val step = 800  // 50 ms analysis frame
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
            p += step / 2
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
