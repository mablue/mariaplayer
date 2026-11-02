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
 * PR-L20: architecture-aware sherpa engine.
 *
 *   CTC    -> model.onnx + tokens.txt
 *   RNNT   -> encoder + decoder + joiner + tokens.txt
 *   Whisper-> encoder + decoder + tokens.txt (no joiner)
 */
class SherpaEngine(
    private val modelDir: File,
    numThreads: Int = 4,
) {
    private sealed interface Arch {
        data class Ctc(val model: File, val tokens: File) : Arch
        data class Rnnt(val encoder: File, val decoder: File, val joiner: File, val tokens: File) : Arch
        data class Whisper(val encoder: File, val decoder: File, val tokens: File) : Arch
    }

    private val architecture: Arch
    private val recognizer: OfflineRecognizer

    init {
        require(modelDir.isDirectory) { "model dir missing: $modelDir" }
        architecture = detect(modelDir)
            ?: throw IllegalArgumentException("no recognizable model in $modelDir")
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
                ),
                tokens = a.tokens.absolutePath,
                numThreads = numThreads,
                debug = false,
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

    fun close() = recognizer.release()

    private fun detect(dir: File): Arch? {
        val tokens = File(dir, "tokens.txt").takeIf { it.exists() } ?: return null
        val enc = pick(dir, "encoder")
        val dec = pick(dir, "decoder")
        val join = pick(dir, "joiner")
        val model = pick(dir, "model")
        if (enc != null && dec != null && join != null) return Arch.Rnnt(enc, dec, join, tokens)
        if (enc != null && dec != null) return Arch.Whisper(enc, dec, tokens)
        if (model != null) return Arch.Ctc(model, tokens)
        return null
    }

    private fun pick(dir: File, base: String): File? {
        val files = dir.listFiles() ?: return null
        return files.firstOrNull { it.isFile && it.name.endsWith("$base.int8.onnx") }
            ?: files.firstOrNull { it.isFile && it.name.endsWith("$base.onnx") }
    }
}
