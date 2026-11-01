package ir.mariaplayer.asr

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.first
import java.io.File

private val Context.asrStore by preferencesDataStore("asr")

data class AvailableAsr(
    val id: String,
    val displayName: String,
    val arch: String,
    val dir: File,
)

/**
 * PR-L14b: model discovery + selection.
 *
 * Scans filesDir/models/* for directories with a valid layout:
 *   CTC  → model.onnx or model.int8.onnx + tokens.txt
 *   RNNT → encoder + decoder + joiner (.onnx or .int8.onnx) + tokens.txt
 * The user's chosen model id is stored in the "asr" DataStore so it
 * survives app restarts and per-track invocations.
 */
object AsrModels {

    private val KEY = stringPreferencesKey("selected_model")

    fun list(context: Context): List<AvailableAsr> {
        val root = File(context.filesDir, "models")
        if (!root.exists()) return emptyList()
        return root.listFiles()?.mapNotNull { d ->
            if (!d.isDirectory) return@mapNotNull null
            if (!File(d, "tokens.txt").exists()) return@mapNotNull null
            val hasRnnt = listOf("encoder", "decoder", "joiner").all { base ->
                File(d, "$base.int8.onnx").exists() || File(d, "$base.onnx").exists()
            }
            val hasCtc = File(d, "model.int8.onnx").exists() ||
                File(d, "model.onnx").exists()
            val arch = when {
                hasRnnt -> "rnnt"
                hasCtc -> "ctc"
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
        else -> id.substringBefore("-int8")
    }

    private fun priority(id: String): Int = when {
        id.contains("rizeh") && !id.contains("rizeh-pizeh") -> 0
        id.contains("rizeh-pizeh") -> 1
        id.contains("koochik") -> 2
        else -> 9
    }
}
