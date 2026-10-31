package ir.mariaplayer.asr

import com.k2fsa.sherpa.onnx.FeatureConfig
import com.k2fsa.sherpa.onnx.OfflineModelConfig
import com.k2fsa.sherpa.onnx.OfflineNemoEncDecCtcModelConfig
import com.k2fsa.sherpa.onnx.OfflineRecognizer
import com.k2fsa.sherpa.onnx.OfflineRecognizerConfig
import java.io.File

class SherpaEngine(
    private val modelDir: File,
    numThreads: Int = 4,
) {
    private val recognizer: OfflineRecognizer

    init {
        require(modelDir.isDirectory) { "model dir missing: $modelDir" }
        val model = File(modelDir, "model.onnx")
        val tokens = File(modelDir, "tokens.txt")
        require(model.exists()) { "model.onnx missing" }
        require(tokens.exists()) { "tokens.txt missing" }

        val config = OfflineRecognizerConfig(
            featConfig = FeatureConfig(
                sampleRate = 16000,
                featureDim = 80,
            ),
            modelConfig = OfflineModelConfig(
                nemo = OfflineNemoEncDecCtcModelConfig(
                    model = model.absolutePath,
                ),
                tokens = tokens.absolutePath,
                numThreads = numThreads,
                debug = false,
            ),
        )
        recognizer = OfflineRecognizer(config = config)
    }

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
}
