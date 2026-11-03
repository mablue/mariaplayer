package ir.mariaplayer.asr

import android.content.Context
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File

object AsrManager {

    data class AsrChunk(val startMs: Long, val text: String)

    private const val CHUNK_MS = 30_000L
    private const val OVERLAP_MS = 3_000L
    private const val STEP_MS = CHUNK_MS - OVERLAP_MS

    private val SENTENCE_SPLIT = Regex("(?<=[.!?،؟؛:])\\s+|\\n+")
    private val WS = Regex("\\s+")

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
        language: String = "",
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

        val eng = SherpaEngine(modelDir, whisperLanguage = language)
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

        val merged = ArrayList<AsrChunk>()
        var prevRawText = ""
        for (c in raw) {
            if (c.text.isBlank()) { prevRawText = c.text; continue }
            val deduped = if (prevRawText.isBlank()) c.text
                          else trimPrefix(prevRawText, c.text)
            if (deduped.isNotBlank()) merged.add(AsrChunk(c.startMs, deduped))
            prevRawText = c.text
        }
        merged
    }

    /**
     * PR-L35c: return token-level timings without merging.
     * Feeds LyricsAligner, which pairs these (wrong text, right timing)
     * against externally-supplied accurate text.
     * Overlapping chunks duplicate tokens in the overlap region — that is
     * fine because the aligner walks each accurate char once and takes the
     * first match in the token stream (earliest chunk wins).
     */
    suspend fun transcribeTimings(
        context: Context, uri: Uri, modelDir: File,
        totalMs: Long, startMs: Long = 0L,
        language: String = "",
        onProgress: (Float, String) -> Unit = { _, _ -> },
    ): List<SherpaEngine.TokenTiming> = withContext(Dispatchers.Default) {
        require(modelDir.isDirectory) { "model dir missing: $modelDir" }
        require(totalMs > 0) { "totalMs must be > 0" }

        onProgress(0f, "Decoding audio…")
        val all = AudioDecoder.decodeFull(context, uri, startMs + totalMs + 5_000L)
        val s0 = (startMs * 16000 / 1000L).toInt().coerceAtLeast(0)
        val samples = if (s0 < all.size) all.copyOfRange(s0, all.size) else FloatArray(0)
        if (samples.isEmpty()) return@withContext emptyList()

        val chunkSamples = (CHUNK_MS * 16000 / 1000L).toInt()
        val stepSamples  = (STEP_MS  * 16000 / 1000L).toInt()

        val eng = SherpaEngine(modelDir, whisperLanguage = language)
        val startedAt = System.currentTimeMillis()
        val result = ArrayList<SherpaEngine.TokenTiming>()
        try {
            var pos = 0
            var idx = 0
            while (true) {
                currentCoroutineContext().ensureActive()
                val end = (pos + chunkSamples).coerceAtMost(samples.size)
                val slice = samples.copyOfRange(pos, end)
                if (slice.size >= 2000) {
                    val offsetSec = pos.toFloat() / 16000f
                    val chunkTimings = runCatching {
                        eng.transcribeWithTimestamps(slice)
                    }.getOrDefault(emptyList())
                    for (t in chunkTimings) {
                        result.add(SherpaEngine.TokenTiming(
                            token = t.token,
                            startSec = t.startSec + offsetSec,
                            durationSec = t.durationSec,
                        ))
                    }
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
        result
    }

    private fun trimPrefix(prevRaw: String, nextRaw: String): String {
        if (prevRaw.isBlank() || nextRaw.isBlank()) return nextRaw
        val pw = prevRaw.trim().split(WS)
        val nw = nextRaw.trim().split(WS)
        val maxK = minOf(pw.size, nw.size)
        for (k in maxK downTo 2) {
            val pTail = pw.takeLast(k).joinToString(" ")
            val nHead = nw.take(k).joinToString(" ")
            if (pTail == nHead) return nw.drop(k).joinToString(" ")
        }
        return nextRaw
    }

    fun toLrc(chunks: List<AsrChunk>, chunkSpanMs: Long = CHUNK_MS): String {
        val sb = StringBuilder()
        for (c in chunks) {
            if (c.text.isBlank()) continue
            val lines = splitIntoLines(c.text)
            if (lines.isEmpty()) continue
            if (lines.size == 1) {
                appendLrcLine(sb, c.startMs, lines[0])
            } else {
                val totalChars = lines.sumOf { it.length }.coerceAtLeast(1)
                var cumulativeMs = c.startMs
                for (s in lines) {
                    appendLrcLine(sb, cumulativeMs, s)
                    val spanMs = (s.length.toLong() * chunkSpanMs) / totalChars
                    cumulativeMs += spanMs.coerceAtLeast(500L)
                }
            }
        }
        return sb.toString()
    }

    private fun splitIntoLines(text: String): List<String> {
        val trimmed = text.trim()
        if (trimmed.isBlank()) return emptyList()
        val byPunct = trimmed.split(SENTENCE_SPLIT)
            .map { it.trim() }
            .filter { it.isNotBlank() }
        if (byPunct.size > 1) return byPunct
        if (trimmed.length <= 50) return listOf(trimmed)
        val words = trimmed.split(WS).filter { it.isNotBlank() }
        if (words.size <= 10) return listOf(trimmed)
        val groups = ArrayList<String>()
        var cur = ArrayList<String>()
        for (w in words) {
            cur.add(w)
            if (cur.size >= 8) {
                groups.add(cur.joinToString(" "))
                cur = ArrayList()
            }
        }
        if (cur.isNotEmpty()) groups.add(cur.joinToString(" "))
        return if (groups.isEmpty()) listOf(trimmed) else groups
    }

    private fun appendLrcLine(sb: StringBuilder, timeMs: Long, text: String) {
        if (sb.isNotEmpty()) sb.append('\n')
        val sec = timeMs / 1000
        val min = sec / 60
        val s = sec % 60
        val cs = (timeMs % 1000) / 10
        sb.append("[%02d:%02d.%02d]%s".format(min, s, cs, text))
    }

    private fun fmtEta(ms: Long): String {
        val sec = (ms / 1000).coerceAtLeast(0)
        return if (sec < 60) "${sec}s" else "${sec / 60}m ${sec % 60}s"
    }

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

    private fun smartMerge(a: String, b: String): String {
        if (a.isBlank()) return b
        if (b.isBlank()) return a
        val aw = a.trim().split(WS)
        val bw = b.trim().split(WS)
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
}
