package ir.mariaplayer.asr

import android.content.Context
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File

/**
 * PR-L10c: transcribe a fixed duration of audio with per-chunk progress.
 *
 * Splits [startMs, startMs+totalMs) into chunks of chunkMs, decodes each
 * to 16 kHz mono float, feeds to Shenava, joins with spaces. Progress is
 * reported after each chunk so the UI can show real movement instead of
 * a blank spinner during the (potentially 1-2 min) FP32 inference.
 *
 * The engine is created once and released at the end. Cancellable via
 * coroutine cancellation — ensureActive() between chunks.
 */
object AsrManager {

    suspend fun transcribe(
        context: Context,
        uri: Uri,
        modelDir: File,
        startMs: Long = 0L,
        totalMs: Long = 30_000L,
        chunkMs: Long = 5_000L,
        onProgress: (Float) -> Unit = {},
    ): String = withContext(Dispatchers.Default) {
        require(modelDir.isDirectory) { "model dir missing: $modelDir" }
        val chunks = ((totalMs + chunkMs - 1) / chunkMs).toInt()
        val sb = StringBuilder()
        val eng = SherpaEngine(modelDir)
        try {
            for (i in 0 until chunks) {
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
                if (samples.isEmpty()) {
                    onProgress((i + 1).toFloat() / chunks)
                    continue
                }
                currentCoroutineContext().ensureActive()
                val text = runCatching { eng.transcribe(samples).trim() }
                    .getOrDefault("")
                if (text.isNotBlank()) {
                    if (sb.isNotEmpty()) sb.append(' ')
                    sb.append(text)
                }
                onProgress((i + 1).toFloat() / chunks)
            }
        } finally {
            runCatching { eng.close() }
        }
        sb.toString()
    }
}
