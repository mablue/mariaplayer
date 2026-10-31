package ir.mariaplayer.translate

import com.google.android.gms.tasks.Task
import com.google.mlkit.common.model.DownloadConditions
import com.google.mlkit.nl.languageid.LanguageIdentification
import com.google.mlkit.nl.translate.Translation
import com.google.mlkit.nl.translate.Translator
import com.google.mlkit.nl.translate.TranslatorOptions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * PR-L9a: on-device translation via ML Kit.
 *
 * - Persian (fa) is supported natively by ML Kit.
 * - First call per language pair triggers a ~30 MB model download
 *   (needs internet once, then fully offline).
 * - Source detection uses language-id (~2 MB one-time model).
 * - All calls are suspend and dispatch off the main thread.
 *
 * The translator instances are cached per (source→target) pair; close()
 * releases them (call from Application.onTerminate or on clear-cache).
 */
object TranslationManager {

    private val ids = LanguageIdentification.getClient()

    private val translators = mutableMapOf<String, Translator>()

    private fun key(src: String, tgt: String) = "$src->$tgt"

    private fun translator(source: String, target: String): Translator {
        val k = key(source, target)
        return translators.getOrPut(k) {
            Translation.getClient(
                TranslatorOptions.Builder()
                    .setSourceLanguage(source)
                    .setTargetLanguage(target)
                    .build()
            )
        }
    }

    /** Returns BCP-47 code (e.g. "fa", "en") or "und" if unknown. */
    suspend fun detect(text: String): String = withContext(Dispatchers.Default) {
        if (text.isBlank()) return@withContext "und"
        try {
            await(ids.identifyLanguage(text))
        } catch (_: Exception) {
            "und"
        }
    }

    /** Downloads the language model if not present. Blocks until ready. */
    suspend fun ensureModel(source: String, target: String) =
        withContext(Dispatchers.Default) {
            await(
                translator(source, target)
                    .downloadModelIfNeeded(DownloadConditions.Builder().build())
            )
        }

    /** Runs translation. Requires ensureModel() to have succeeded first. */
    suspend fun translate(text: String, source: String, target: String): String =
        withContext(Dispatchers.Default) {
            if (text.isBlank()) return@withContext text
            await(translator(source, target).translate(text))
        }

    fun closeAll() {
        translators.values.forEach { runCatching { it.close() } }
        translators.clear()
    }

    private suspend fun <T> await(task: Task<T>): T =
        suspendCancellableCoroutine { cont ->
            task.addOnSuccessListener { cont.resume(it) }
                .addOnFailureListener { cont.resumeWithException(it) }
        }
}
