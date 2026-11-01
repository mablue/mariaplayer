package ir.mariaplayer.asr

import android.content.Context
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * PR-L10b: decodes any MediaStore audio URI to mono float32 at 16 kHz.
 *
 * Pipeline:
 *   MediaExtractor → MediaCodec (decode to PCM 16-bit) → mono mix → linear
 *   resample to 16 kHz.
 *
 * Shenava expects exactly this format. Duration is capped so the tester
 * doesn't run for minutes; adjust durationMs if you want a longer sample.
 */
object AudioDecoder {

    private const val TARGET_SR = 16000

    suspend fun decodeToFloat(
        context: Context,
        uri: Uri,
        startMs: Long = 0L,
        durationMs: Long = 15_000L,
    ): FloatArray = withContext(Dispatchers.IO) {
        val extractor = MediaExtractor()
        var codec: MediaCodec? = null
        try {
            extractor.setDataSource(context, uri, null)

            var audioTrack = -1
            var format: MediaFormat? = null
            for (i in 0 until extractor.trackCount) {
                val f = extractor.getTrackFormat(i)
                val mime = f.getString(MediaFormat.KEY_MIME) ?: continue
                if (mime.startsWith("audio/")) {
                    audioTrack = i
                    format = f
                    break
                }
            }
            require(audioTrack >= 0) { "no audio track in $uri" }
            extractor.selectTrack(audioTrack)
            if (startMs > 0) {
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
            val targetShorts = (durationMs * TARGET_SR / 1000L).toInt() * srcCh
            val pcm = ArrayList<Short>(targetShorts)
            var sawEos = false

            while (pcm.size < targetShorts) {
                if (!sawEos) {
                    val inIdx = codec.dequeueInputBuffer(10_000L)
                    if (inIdx >= 0) {
                        val buf = codec.getInputBuffer(inIdx)!!
                        val sz = extractor.readSampleData(buf, 0)
                        if (sz < 0) {
                            codec.queueInputBuffer(
                                inIdx, 0, 0, 0L,
                                MediaCodec.BUFFER_FLAG_END_OF_STREAM,
                            )
                            sawEos = true
                        } else {
                            codec.queueInputBuffer(inIdx, 0, sz, extractor.sampleTime, 0)
                            extractor.advance()
                        }
                    }
                }
                val outIdx = codec.dequeueOutputBuffer(info, 10_000L)
                if (outIdx >= 0) {
                    val buf = codec.getOutputBuffer(outIdx)!!
                    buf.position(info.offset)
                    buf.limit(info.offset + info.size)
                    val shorts = ShortArray(info.size / 2)
                    buf.asShortBuffer().get(shorts)
                    for (s in shorts) pcm.add(s)
                    codec.releaseOutputBuffer(outIdx, false)
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) break
                }
            }

            // mono mix
            val mono = FloatArray(pcm.size / srcCh)
            for (i in mono.indices) {
                var s = 0f
                for (c in 0 until srcCh) s += pcm[i * srcCh + c] / 32768f
                mono[i] = s / srcCh
            }

            if (srcSr == TARGET_SR) mono
            else resample(mono, srcSr, TARGET_SR, durationMs)
        } finally {
            codec?.let {
                runCatching { it.stop() }
                runCatching { it.release() }
            }
            runCatching { extractor.release() }
        }
    }

    /**
     * PR-L15b: decode the entire track in one pass. Avoids re-seeking
     * MediaExtractor per chunk (slow + occasionally skips key frames).
     */
    suspend fun decodeFull(
        context: Context,
        uri: Uri,
        maxMs: Long = 600_000L,
    ): FloatArray = withContext(Dispatchers.IO) {
        val extractor = MediaExtractor()
        var codec: MediaCodec? = null
        try {
            extractor.setDataSource(context, uri, null)
            var audioTrack = -1
            var format: MediaFormat? = null
            for (i in 0 until extractor.trackCount) {
                val f = extractor.getTrackFormat(i)
                val mime = f.getString(MediaFormat.KEY_MIME) ?: continue
                if (mime.startsWith("audio/")) {
                    audioTrack = i; format = f; break
                }
            }
            require(audioTrack >= 0) { "no audio track" }
            extractor.selectTrack(audioTrack)
            val fmt = format!!
            val mime = fmt.getString(MediaFormat.KEY_MIME)!!
            val srcSr = fmt.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            val srcCh = fmt.getInteger(MediaFormat.KEY_CHANNEL_COUNT)

            codec = MediaCodec.createDecoderByType(mime)
            codec.configure(fmt, null, null, 0)
            codec.start()

            val info = MediaCodec.BufferInfo()
            val targetShorts = ((maxMs * srcSr / 1000L).toInt()) * srcCh
            val pcm = ArrayList<Short>(targetShorts.coerceAtMost(30_000_000))
            var sawEos = false
            while (pcm.size < targetShorts) {
                if (!sawEos) {
                    val inIdx = codec.dequeueInputBuffer(10_000L)
                    if (inIdx >= 0) {
                        val buf = codec.getInputBuffer(inIdx)!!
                        val sz = extractor.readSampleData(buf, 0)
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
                    val buf = codec.getOutputBuffer(outIdx)!!
                    buf.position(info.offset); buf.limit(info.offset + info.size)
                    val shorts = ShortArray(info.size / 2)
                    buf.asShortBuffer().get(shorts)
                    for (s in shorts) pcm.add(s)
                    codec.releaseOutputBuffer(outIdx, false)
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) break
                }
            }

            val mono = FloatArray(pcm.size / srcCh)
            for (i in mono.indices) {
                var s = 0f
                for (c in 0 until srcCh) s += pcm[i * srcCh + c] / 32768f
                mono[i] = s / srcCh
            }
            if (srcSr == TARGET_SR) mono
            else resampleFull(mono, srcSr, TARGET_SR)
        } finally {
            codec?.let { runCatching { it.stop() }; runCatching { it.release() } }
            runCatching { extractor.release() }
        }
    }

    private fun resampleFull(input: FloatArray, fromSr: Int, toSr: Int): FloatArray {
        if (input.isEmpty() || fromSr <= 0) return FloatArray(0)
        val ratio = fromSr.toDouble() / toSr
        val outLen = (input.size / ratio).toInt()
        val out = FloatArray(outLen)
        for (i in 0 until outLen) {
            val srcPos = i * ratio
            val i0 = srcPos.toInt().coerceIn(0, input.lastIndex)
            val i1 = (i0 + 1).coerceAtMost(input.lastIndex)
            val frac = (srcPos - i0).toFloat()
            out[i] = input[i0] * (1f - frac) + input[i1] * frac
        }
        return out
    }

    private fun resample(
        input: FloatArray,
        fromSr: Int,
        toSr: Int,
        durationMs: Long,
    ): FloatArray {
        if (input.isEmpty() || fromSr <= 0) return FloatArray(0)
        val ratio = fromSr.toDouble() / toSr
        val outLen = minOf(
            (durationMs * toSr / 1000L).toInt(),
            (input.size / ratio).toInt(),
        )
        val out = FloatArray(outLen)
        for (i in 0 until outLen) {
            val srcPos = i * ratio
            val i0 = srcPos.toInt().coerceIn(0, input.lastIndex)
            val i1 = (i0 + 1).coerceAtMost(input.lastIndex)
            val frac = (srcPos - i0).toFloat()
            out[i] = input[i0] * (1f - frac) + input[i1] * frac
        }
        return out
    }
}
