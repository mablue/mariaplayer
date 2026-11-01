package ir.mariaplayer.asr

import com.k2fsa.sherpa.onnx.FeatureConfig
import com.k2fsa.sherpa.onnx.OfflineModelConfig
import com.k2fsa.sherpa.onnx.OfflineNemoEncDecCtcModelConfig
import com.k2fsa.sherpa.onnx.OfflineRecognizer
import com.k2fsa.sherpa.onnx.OfflineRecognizerConfig
import com.k2fsa.sherpa.onnx.OfflineTransducerModelConfig
import java.io.File

/**
 * PR-L14: architecture-aware sherpa engine.
 *
 * Detects model layout from files present in [modelDir]:
 *   CTC  → model.onnx (or model.int8.onnx) + tokens.txt
 *   RNNT → encoder.onnx + decoder.onnx + joiner.onnx + tokens.txt
 *          (each also accepts the .int8.onnx variant)
 *
 * Uses OfflineNemoEncDecCtcModelConfig for CTC and OfflineTransducerModelConfig
 * with modelType = "nemo_transducer" for RNNT. The explicit modelType is
 * required — sherpa cannot auto-detect NeMo from file names alone.
 */
class SherpaEngine(
    private val modelDir: File,
    numThreads: Int = 4,
) {
    private sealed interface Arch {
        data class Ctc(val model: File, val tokens: File) : Arch
        data class Rnnt(
            val encoder: File,
            val decoder: File,
            val joiner: File,
            val tokens: File,
        ) : Arch
    }

    private val architecture: Arch
    private val recognizer: OfflineRecognizer

    init {
        require(modelDir.isDirectory) { "model dir missing: $modelDir" }
        architecture = detect(modelDir)
            ?: throw IllegalArgumentException(
                "no recognizable model in $modelDir " +
                "(expected model.onnx OR encoder/decoder/joiner.onnx + tokens.txt)"
            )

        val feat = FeatureConfig(sampleRate = 16000, featureDim = 80)
        val modelConfig = when (val a = architecture) {
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
        }
        recognizer = OfflineRecognizer(
            config = OfflineRecognizerConfig(
                featConfig = feat,
                modelConfig = modelConfig,
            )
        )
    }

    fun architectureName(): String = when (architecture) {
        is Arch.Ctc -> "ctc"
        is Arch.Rnnt -> "rnnt"
    }

    /**
     * @param samples 16 kHz mono float32 in [-1, 1]
     * @return recognized Persian text (may be empty)
     */
    fun transcribe(samples: FloatArray): String {
        val stream = recognizer.createStream()
        try {
            stream.acceptWaveform(samples, 16000)
            recognizer.decode(stream)
            return recognizer.getResult(stream).text
        } finally {
            stream.release()
        }
    }

    fun close() {
        recognizer.release()
    }

    private fun detect(dir: File): Arch? {
        val tokens = File(dir, "tokens.txt").takeIf { it.exists() } ?: return null
        val encoder = pick(dir, "encoder")
        val decoder = pick(dir, "decoder")
        val joiner = pick(dir, "joiner")
        if (encoder != null && decoder != null && joiner != null) {
            return Arch.Rnnt(encoder, decoder, joiner, tokens)
        }
        val model = pick(dir, "model")
        if (model != null) return Arch.Ctc(model, tokens)
        return null
    }

    private fun pick(dir: File, base: String): File? = listOf(
        File(dir, "$base.int8.onnx"),
        File(dir, "$base.onnx"),
    ).firstOrNull { it.exists() }
}
