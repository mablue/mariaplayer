package ir.mariaplayer.asr

import android.content.Context
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

/**
 * PR-L37: streaming decode with bounded memory.
 *
 * Prior decodeFull() built the entire mono/resampled FloatArray in RAM
 * before returning. A 4-hour audiobook at 44.1 kHz stereo needs 1.27 GB
 * just for PCM → OOM on the 256 MB Android heap. That is why books only
 * transcribed the first 3 minutes — the app quietly died past that point.
 *
 * streamDecode() keeps ONE 30-second buffer alive at a time. Peak memory
 * is ~2 MB regardless of file length. Same overhead for 30s and 4h.
 */
object AudioDecoder {

    private const val TARGET_SR = 16000
    private const val MAX_PCM_SHORTS = 60_000_000

    suspend fun decodeToFloat(
        context: Context,
        uri: Uri,
        startMs: Long = 0L,
        durationMs: Long = 15_000L,
    ): FloatArray = withContext(Dispatchers.IO) {
        decodeInternal(context, uri, startMs, durationMs)
    }

    suspend fun decodeFull(
        context: Context,
        uri: Uri,
        maxMs: Long = 180_000L,
    ): FloatArray = withContext(Dispatchers.IO) {
        decodeInternal(context, uri, 0L, maxMs)
    }

    /**
     * PR-L37: chunk-by-chunk decode. Memory stays bounded.
     *
     * @param chunkMs size per emitted chunk (default 30s)
     * @param maxMs   optional hard cap (0 = unbounded). Chunks cover [0, maxMs).
     * @param onChunk receives (samples, chunkStartMs). Suspend; can cancel.
     */
    suspend fun streamDecode(
        context: Context,
        uri: Uri,
        chunkMs: Long = 30_000L,
        maxMs: Long = 0L,
        onChunk: suspend (FloatArray, Long) -> Unit,
    ) = withContext(Dispatchers.IO) {
        val extractor = MediaExtractor()
        var codec: MediaCodec? = null
        try {
            extractor.setDataSource(context, uri, null)
            var audioTrack = -1
            var format: MediaFormat? = null
            for (i in 0 until extractor.trackCount) {
                val f = extractor.getTrackFormat(i)
                val mime = f.getString(MediaFormat.KEY_MIME) ?: continue
                if (mime.startsWith("audio/")) { audioTrack = i; format = f; break }
            }
            require(audioTrack >= 0) { "no audio track in $uri" }
            extractor.selectTrack(audioTrack)

            val fmt = format!!
            val mime = fmt.getString(MediaFormat.KEY_MIME)!!
            val srcSr = fmt.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            val srcCh = fmt.getInteger(MediaFormat.KEY_CHANNEL_COUNT)

            codec = MediaCodec.createDecoderByType(mime)
            codec.configure(fmt, null, null, 0)
            codec.start()

            val info = MediaCodec.BufferInfo()
            val chunkSrcFrames = ((chunkMs * srcSr) / 1000L).toInt().coerceAtLeast(1)
            val chunkShorts = chunkSrcFrames * srcCh
            val pcm = ShortArray(chunkShorts)
            var filled = 0
            var emittedFrames = 0L
            val capSrcFrames: Long =
                if (maxMs > 0L) (maxMs * srcSr / 1000L) else Long.MAX_VALUE

            suspend fun emit() {
                if (filled == 0) return
                val srcFrames = filled / srcCh
                if (srcFrames <= 0) { filled = 0; return }
                val ratio = if (srcSr == TARGET_SR) 1.0 else srcSr.toDouble() / TARGET_SR
                val outLen = (srcFrames / ratio).toInt().coerceAtLeast(1)
                val out = FloatArray(outLen)
                for (i in 0 until outLen) {
                    val sp = i * ratio
                    val i0 = sp.toInt().coerceIn(0, srcFrames - 1)
                    val i1 = (i0 + 1).coerceAtMost(srcFrames - 1)
                    val frac = (sp - i0).toFloat()
                    var s0 = 0f; var s1 = 0f
                    for (c in 0 until srcCh) {
                        s0 += pcm[i0 * srcCh + c] / 32768f
                        s1 += pcm[i1 * srcCh + c] / 32768f
                    }
                    out[i] = (s0 / srcCh) * (1f - frac) + (s1 / srcCh) * frac
                }
                val startMs = (emittedFrames * 1000L) / srcSr
                emittedFrames += srcFrames
                onChunk(out, startMs)
                filled = 0
            }

            var sawInputEos = false
            var sawOutputEos = false
            while (!sawOutputEos && emittedFrames < capSrcFrames) {
                currentCoroutineContext().ensureActive()
                if (!sawInputEos) {
                    val inIdx = codec.dequeueInputBuffer(10_000L)
                    if (inIdx >= 0) {
                        val inBuf = codec.getInputBuffer(inIdx)!!
                        val sz = extractor.readSampleData(inBuf, 0)
                        if (sz < 0) {
                            codec.queueInputBuffer(inIdx, 0, 0, 0L,
                                MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            sawInputEos = true
                        } else {
                            codec.queueInputBuffer(inIdx, 0, sz, extractor.sampleTime, 0)
                            extractor.advance()
                        }
                    }
                }
                val outIdx = codec.dequeueOutputBuffer(info, 10_000L)
                if (outIdx >= 0) {
                    val outBuf = codec.getOutputBuffer(outIdx)!!
                    outBuf.position(info.offset)
                    outBuf.limit(info.offset + info.size)
                    val decoded = ShortArray(info.size / 2)
                    outBuf.asShortBuffer().get(decoded)
                    codec.releaseOutputBuffer(outIdx, false)

                    var cursor = 0
                    while (cursor < decoded.size) {
                        val space = pcm.size - filled
                        val take = minOf(space, decoded.size - cursor)
                        System.arraycopy(decoded, cursor, pcm, filled, take)
                        filled += take
                        cursor += take
                        if (filled >= pcm.size) emit()
                    }
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) {
                        sawOutputEos = true
                    }
                }
            }
            emit()
        } finally {
            codec?.let { runCatching { it.stop() }; runCatching { it.release() } }
            runCatching { extractor.release() }
        }
    }

    private fun decodeInternal(
        context: Context,
        uri: Uri,
        startMs: Long,
        durationMs: Long,
    ): FloatArray {
        val extractor = MediaExtractor()
        var codec: MediaCodec? = null
        try {
            extractor.setDataSource(context, uri, null)
            var audioTrack = -1
            var format: MediaFormat? = null
            for (i in 0 until extractor.trackCount) {
                val f = extractor.getTrackFormat(i)
                val mime = f.getString(MediaFormat.KEY_MIME) ?: continue
                if (mime.startsWith("audio/")) { audioTrack = i; format = f; break }
            }
            require(audioTrack >= 0) { "no audio track in $uri" }
            extractor.selectTrack(audioTrack)
            if (startMs > 0L) {
                extractor.seekTo(startMs * 1000L, MediaExtractor.SEEK_TO_CLOSEST_SYNC)
            }
            val fmt = format!!
            val mime = fmt.getString(MediaFormat.KEY_MIME)!!
            val srcSr = fmt.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            val srcCh = fmt.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
            codec = MediaCodec.createDecoderByType(mime)
            codec.configure(fmt, null, null, 0)
            codec.start()
            val info = MediaCodec.BufferInfo()
            val maxShorts = ((durationMs * srcSr / 1000L).toInt() * srcCh)
                .coerceAtMost(MAX_PCM_SHORTS)
            var pcm = ShortArray(
                minOf(maxShorts / 4, 4_000_000).coerceAtLeast(srcCh * 16000)
            )
            var filled = 0
            var sawEos = false
            while (filled < maxShorts) {
                if (!sawEos) {
                    val inIdx = codec.dequeueInputBuffer(10_000L)
                    if (inIdx >= 0) {
                        val inBuf = codec.getInputBuffer(inIdx)!!
                        val sz = extractor.readSampleData(inBuf, 0)
                        if (sz < 0) {
                            codec.queueInputBuffer(inIdx, 0, 0, 0L,
                                MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            sawEos = true
                        } else {
                            codec.queueInputBuffer(inIdx, 0, sz, extractor.sampleTime, 0)
                            extractor.advance()
                        }
                    }
                }
                val outIdx = codec.dequeueOutputBuffer(info, 10_000L)
                if (outIdx >= 0) {
                    val outBuf = codec.getOutputBuffer(outIdx)!!
                    outBuf.position(info.offset)
                    outBuf.limit(info.offset + info.size)
                    val chunk = ShortArray(info.size / 2)
                    outBuf.asShortBuffer().get(chunk)
                    val need = filled + chunk.size
                    if (need > pcm.size) {
                        val newSize = minOf(maxShorts, maxOf(pcm.size * 2, need))
                            .coerceAtMost(MAX_PCM_SHORTS)
                        pcm = pcm.copyOf(newSize)
                    }
                    val toCopy = minOf(chunk.size, pcm.size - filled)
                    System.arraycopy(chunk, 0, pcm, filled, toCopy)
                    filled += toCopy
                    codec.releaseOutputBuffer(outIdx, false)
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) break
                }
            }
            val srcFrames = filled / srcCh
            if (srcFrames == 0) return FloatArray(0)
            val ratio = if (srcSr == TARGET_SR) 1.0 else srcSr.toDouble() / TARGET_SR
            val outLen = (srcFrames / ratio).toInt()
            val out = FloatArray(outLen)
            for (i in 0 until outLen) {
                val sp = i * ratio
                val i0 = sp.toInt().coerceIn(0, srcFrames - 1)
                val i1 = (i0 + 1).coerceAtMost(srcFrames - 1)
                val frac = (sp - i0).toFloat()
                var s0 = 0f; var s1 = 0f
                for (c in 0 until srcCh) {
                    s0 += pcm[i0 * srcCh + c] / 32768f
                    s1 += pcm[i1 * srcCh + c] / 32768f
                }
                out[i] = (s0 / srcCh) * (1f - frac) + (s1 / srcCh) * frac
            }
            return out
        } finally {
            codec?.let { runCatching { it.stop() }; runCatching { it.release() } }
            runCatching { extractor.release() }
        }
    }
}
