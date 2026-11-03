package ir.mariaplayer.models

import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.compressors.bzip2.BZip2CompressorInputStream
import java.io.BufferedInputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.util.zip.ZipInputStream

/**
 * PR-L27: extract sherpa-onnx tar.bz2 / zip archives into a model dir.
 *
 * GitHub release archives for sherpa-onnx ship files with varying names
 * (encoder-epoch-99-avg-1.onnx, tiny-encoder.int8.onnx, model-epoch-...)
 * and nested subdirectories. We flatten and rename to the canonical set
 * the engine expects:
 *
 *   *encoder*.onnx   -> encoder(.int8).onnx
 *   *decoder*.onnx   -> decoder(.int8).onnx
 *   *joiner*.onnx    -> joiner(.int8).onnx
 *   *model*.onnx     -> model(.int8).onnx
 *   *tokens.txt      -> tokens.txt
 *
 * Non-ASR assets (TTS, VAD, speaker, diarization, punctuation) are skipped
 * so users never download a model the engine cannot actually use.
 */
object ArchiveExtractor {

    data class ExtractResult(
        val files: List<String>,
        val totalBytes: Long,
    )

    private val NON_ASR_MARKERS = listOf(
        "vits", "kokoro", "matcha", "piper",
        "vad", "silero", "ten-vad",
        "speaker", "diarization", "diarize",
        "punctuation", "kws", "keyword-spotting",
        "audio-tagging", "denoiser",
        "source-separation", "speech-translation",
    )

    fun extractTarBz2(archive: File, destDir: File): ExtractResult {
        destDir.mkdirs()
        val names = mutableListOf<String>()
        var total = 0L
        val buf = ByteArray(64 * 1024)
        BufferedInputStream(FileInputStream(archive), 64 * 1024).use { bis ->
            BZip2CompressorInputStream(bis, true).use { bz ->
                TarArchiveInputStream(bz).use { tar ->
                    while (true) {
                        val entry = tar.nextEntry ?: break
                        if (entry.isDirectory) continue
                        val canonical = canonicalName(entry.name) ?: continue
                        val dst = File(destDir, canonical)
                        FileOutputStream(dst).use { out ->
                            var read: Int
                            while (tar.read(buf).also { read = it } > 0) {
                                out.write(buf, 0, read)
                                total += read
                            }
                        }
                        names.add(canonical)
                    }
                }
            }
        }
        return ExtractResult(names, total)
    }

    fun extractZip(archive: File, destDir: File): ExtractResult {
        destDir.mkdirs()
        val names = mutableListOf<String>()
        var total = 0L
        val buf = ByteArray(64 * 1024)
        BufferedInputStream(FileInputStream(archive), 64 * 1024).use { bis ->
            ZipInputStream(bis).use { zip ->
                while (true) {
                    val entry = zip.nextEntry ?: break
                    if (entry.isDirectory) continue
                    val canonical = canonicalName(entry.name) ?: continue
                    val dst = File(destDir, canonical)
                    FileOutputStream(dst).use { out ->
                        var read: Int
                        while (zip.read(buf).also { read = it } > 0) {
                            out.write(buf, 0, read)
                            total += read
                        }
                    }
                    names.add(canonical)
                }
            }
        }
        return ExtractResult(names, total)
    }

    private fun canonicalName(original: String): String? {
        val lower = original.lowercase()
        if (NON_ASR_MARKERS.any { lower.contains(it) }) return null
        val isInt8 = lower.contains("int8")

        // PR-L33b: strict whitelist. Previous version mapped every unknown
        // .onnx to model.onnx, so Moonshine's 4 files (preprocess, encode,
        // uncached_decode, cached_decode) overwrote each other — only one
        // survived, and the resulting model couldn't load. We now accept
        // only files we can positively identify as the CTC / RNNT / Whisper
        // parts, or a plain model.onnx / model.int8.onnx exactly.
        if (lower.endsWith("tokens.txt")) return "tokens.txt"
        if (!lower.endsWith(".onnx")) return null

        val name = lower.substringAfterLast('/')
        return when {
            name.startsWith("encoder") || name.contains("-encoder") ->
                if (isInt8) "encoder.int8.onnx" else "encoder.onnx"
            name.startsWith("decoder") || name.contains("-decoder") ->
                if (isInt8) "decoder.int8.onnx" else "decoder.onnx"
            name.startsWith("joiner") || name.contains("-joiner") ->
                if (isInt8) "joiner.int8.onnx" else "joiner.onnx"
            name == "model.onnx" || name == "model.int8.onnx" ->
                if (isInt8) "model.int8.onnx" else "model.onnx"
            name.startsWith("model-") || name.startsWith("model_") ->
                if (isInt8) "model.int8.onnx" else "model.onnx"
            else -> null   // reject unknown .onnx (Moonshine, preprocess, etc.)
        }
    }
}
