package ir.mariaplayer.asr

import com.k2fsa.sherpa.onnx.FeatureConfig
import com.k2fsa.sherpa.onnx.OfflineModelConfig
import com.k2fsa.sherpa.onnx.OfflineNemoEncDecCtcModelConfig
import com.k2fsa.sherpa.onnx.OfflineRecognizer
import com.k2fsa.sherpa.onnx.OfflineRecognizerConfig
import com.k2fsa.sherpa.onnx.OfflineTransducerModelConfig
import com.k2fsa.sherpa.onnx.OfflineWhisperModelConfig
import java.io.File

/**
 * PR-L35a: architecture-aware sherpa engine + token timestamps.
 *
 * Adds transcribeWithTimestamps() which returns per-token start/duration.
 * This is the foundation for forced alignment: ASR text may be wrong, but
 * its timing is reliable. We pair this with external lyrics text.
 */
class SherpaEngine(
    private val modelDir: File,
    private val whisperLanguage: String = "",
    numThreads: Int = 4,
) {
    data class TokenTiming(
        val token: String,
        val startSec: Float,
        val durationSec: Float,
    )

    private sealed interface Arch {
        data class Ctc(val model: File, val tokens: File) : Arch
        data class Rnnt(val encoder: File, val decoder: File, val joiner: File, val tokens: File) : Arch
        data class Whisper(val encoder: File, val decoder: File, val tokens: File) : Arch
    }

    private val architecture: Arch
    private val recognizer: OfflineRecognizer

    init {
        require(modelDir.isDirectory) { "model dir missing: $modelDir" }
        validateOrThrow(modelDir)
        architecture = detect(modelDir) ?: run {
            val files = modelDir.listFiles()?.joinToString(", ") { it.name } ?: "empty"
            throw IllegalArgumentException(
                "no recognizable model in $modelDir. Files: [$files]"
            )
        }
        val feat = FeatureConfig(sampleRate = 16000, featureDim = 80)
        val mc = when (val a = architecture) {
            is Arch.Ctc -> OfflineModelConfig(
                nemo = OfflineNemoEncDecCtcModelConfig(model = a.model.absolutePath),
                tokens = a.tokens.absolutePath,
                numThreads = numThreads,
                debug = false,
            )
            is Arch.Rnnt -> OfflineModelConfig(
                transducer = OfflineTransducerModelConfig(
                    encoder = a.encoder.absolutePath,
                    decoder = a.decoder.absolutePath,
                    joiner = a.joiner.absolutePath,
                ),
                tokens = a.tokens.absolutePath,
                numThreads = numThreads,
                debug = false,
                modelType = "nemo_transducer",
            )
            is Arch.Whisper -> OfflineModelConfig(
                whisper = OfflineWhisperModelConfig(
                    encoder = a.encoder.absolutePath,
                    decoder = a.decoder.absolutePath,
                    language = whisperLanguage,
                ),
                tokens = a.tokens.absolutePath,
                numThreads = numThreads,
                debug = false,
                modelType = "whisper",
            )
        }
        recognizer = OfflineRecognizer(
            config = OfflineRecognizerConfig(featConfig = feat, modelConfig = mc)
        )
    }

    fun architectureName(): String = when (architecture) {
        is Arch.Ctc -> "ctc"
        is Arch.Rnnt -> "rnnt"
        is Arch.Whisper -> "whisper"
    }

    fun transcribe(samples: FloatArray): String {
        val s = recognizer.createStream()
        try {
            s.acceptWaveform(samples, 16000)
            recognizer.decode(s)
            return recognizer.getResult(s).text
        } finally { s.release() }
    }

    fun transcribeWithTimestamps(samples: FloatArray): List<TokenTiming> {
        val s = recognizer.createStream()
        try {
            s.acceptWaveform(samples, 16000)
            recognizer.decode(s)
            val res = recognizer.getResult(s)
            val timings = mutableListOf<TokenTiming>()
            for (i in res.tokens.indices) {
                if (i < res.timestamps.size) {
                    val dur = if (i < res.durations.size) res.durations[i] else 0f
                    timings.add(TokenTiming(res.tokens[i], res.timestamps[i], dur))
                }
            }
            return timings
        } finally { s.release() }
    }

    fun close() = recognizer.release()

    private fun detect(dir: File): Arch? {
        val files = dir.listFiles() ?: return null
        val tokens = files.firstOrNull { it.isFile && it.name.endsWith("tokens.txt") }
            ?: return null
        val enc = pick(files, "encoder")
        val dec = pick(files, "decoder")
        val join = pick(files, "joiner")
        val model = pick(files, "model")
        if (enc != null && dec != null && join != null) return Arch.Rnnt(enc, dec, join, tokens)
        if (enc != null && dec != null) return Arch.Whisper(enc, dec, tokens)
        if (model != null) return Arch.Ctc(model, tokens)
        return null
    }

    private fun pick(files: Array<File>, base: String): File? {
        return files.firstOrNull { it.isFile && it.name.endsWith("$base.int8.onnx") }
            ?: files.firstOrNull { it.isFile && it.name.endsWith("$base.onnx") }
    }

    companion object {
        fun validateOrThrow(dir: File) {
            val totalSize = dir.walkTopDown().filter { it.isFile }.sumOf { it.length() }
            if (totalSize < MIN_TOTAL_BYTES) {
                throw IllegalArgumentException(
                    "Model is too small (${totalSize / 1024} KB total). " +
                    "Real ASR models are >= 5 MB. " +
                    "This looks like a toy or demo model, not speech recognition. " +
                    "Pick a different model in the Models screen."
                )
            }
            val tokens = File(dir, "tokens.txt")
            if (!tokens.exists() || tokens.length() < MIN_TOKENS_BYTES) {
                throw IllegalArgumentException(
                    "tokens.txt is missing or too small (${tokens.length()} bytes). " +
                    "This is not a valid ASR model."
                )
            }
            val onnx = dir.listFiles()?.filter { it.isFile && it.name.endsWith(".onnx") }
                ?: emptyList()
            if (onnx.isEmpty()) {
                throw IllegalArgumentException("No .onnx file found in ${dir.name}.")
            }
            val biggest = onnx.maxOf { it.length() }
            if (biggest < MIN_ONNX_BYTES) {
                throw IllegalArgumentException(
                    "Largest .onnx is only ${biggest / 1024} KB. " +
                    "Real ASR models are >= 500 KB per file."
                )
            }
        }

        const val MIN_TOTAL_BYTES = 5_000_000L
        const val MIN_TOKENS_BYTES = 100L
        const val MIN_ONNX_BYTES = 500_000L
    }
}
