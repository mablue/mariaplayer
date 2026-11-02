package ir.mariaplayer.asr

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.first
import java.io.File

private val Context.asrStore by preferencesDataStore("asr")

object AsrModels {

    data class AvailableAsr(
        val id: String,
        val displayName: String,
        val arch: String,
        val dir: File,
    )

    private val KEY = stringPreferencesKey("selected_model")

    fun list(context: Context): List<AvailableAsr> {
        val root = File(context.filesDir, "models")
        if (!root.exists()) return emptyList()
        return root.listFiles()?.mapNotNull { d ->
            if (!d.isDirectory) return@mapNotNull null
            if (!File(d, "tokens.txt").exists()) return@mapNotNull null

            val hasEncoder = File(d, "encoder.int8.onnx").exists() ||
                             File(d, "encoder.onnx").exists()
            val hasDecoder = File(d, "decoder.int8.onnx").exists() ||
                             File(d, "decoder.onnx").exists()
            val hasJoiner  = File(d, "joiner.int8.onnx").exists() ||
                             File(d, "joiner.onnx").exists()
            val hasCtc     = File(d, "model.int8.onnx").exists() ||
                             File(d, "model.onnx").exists()

            val arch = when {
                hasEncoder && hasDecoder && hasJoiner -> "rnnt"
                hasEncoder && hasDecoder              -> "whisper"
                hasCtc                                -> "ctc"
                else -> return@mapNotNull null
            }

            AvailableAsr(
                id = d.name,
                displayName = friendlyName(d.name),
                arch = arch,
                dir = d,
            )
        }?.sortedWith(compareBy({ priority(it.id) }, { it.displayName }))
            ?: emptyList()
    }

    suspend fun selected(context: Context): String =
        context.asrStore.data.first()[KEY] ?: ""

    suspend fun saveSelected(context: Context, id: String) {
        context.asrStore.edit { it[KEY] = id }
    }

    private fun friendlyName(id: String): String = when {
        id.contains("koochik") -> "Koochik v1.5"
        id.contains("rizeh-pizeh") -> "Rizeh-Pizeh"
        id.contains("rizeh") -> "Rizeh v1.0"
        id.contains("whisper-tiny") -> "Whisper Tiny"
        id.contains("whisper-base") -> "Whisper Base"
        id.contains("whisper-small") -> "Whisper Small"
        id.contains("whisper-medium") -> "Whisper Medium"
        id.contains("whisper-large-v3-turbo") -> "Whisper Large Turbo"
        id.contains("whisper-large-v3") -> "Whisper Large v3"
        id.contains("whisper") -> "Whisper"
        else -> id.substringBefore("-int8")
    }

    private fun priority(id: String): Int = when {
        // Persian-first: Koochik best, then Rizeh, then Whisper (EN-oriented)
        id.contains("koochik") -> 0
        id.contains("rizeh") && !id.contains("rizeh-pizeh") -> 1
        id.contains("rizeh-pizeh") -> 2
        id.contains("whisper-tiny") -> 3
        id.contains("whisper-base") -> 4
        id.contains("whisper-small") -> 5
        id.contains("whisper-medium") -> 6
        id.contains("whisper-large") -> 7
        id.contains("whisper") -> 8
        else -> 9
    }
}
