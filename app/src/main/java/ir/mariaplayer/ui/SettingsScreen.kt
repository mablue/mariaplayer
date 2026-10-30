package ir.mariaplayer.ui

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import ir.mariaplayer.data.FavoritesPrefs
import ir.mariaplayer.data.TagFavorites
import ir.mariaplayer.data.Track
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    tracks: List<Track>,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var status by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }

    // Export: one createWriteRequest for ALL favorite URIs (API 29+)
    val exportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartIntentSenderForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            scope.launch {
                busy = true
                val favs = FavoritesPrefs.snapshot(context)
                var ok = 0; var fail = 0
                for (t in tracks.filter { it.id in favs }) {
                    val success = TagFavorites.write(context, Uri.parse(t.contentUri), true)
                    if (success) ok++ else fail++
                }
                busy = false
                status = "Exported $ok favorites to tags" +
                    if (fail > 0) " ($fail failed)" else ""
            }
        } else {
            status = "Export cancelled"
        }
    }

    // Import: read tags, merge into DataStore
    val importLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { /* not used — import reads directly */ }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Settings") },
                navigationIcon = {
                    androidx.compose.material3.TextButton(onClick = onBack) { Text("Back") }
                },
            )
        },
    ) { inner ->
        Column(
            Modifier.fillMaxSize().padding(inner).padding(16.dp)
                .verticalScroll(rememberScrollState()),
        ) {
            Text("Favorites backup", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(6.dp))
            Text(
                "Export writes a [MARIA_FAV] marker inside each favorite file's COMMENT tag. " +
                "Survives uninstall/reinstall. One system dialog for the whole batch.",
                style = MaterialTheme.typography.bodySmall,
            )
            Spacer(Modifier.height(10.dp))

            Button(
                enabled = !busy,
                onClick = {
                    scope.launch {
                        busy = true
                        val favs = FavoritesPrefs.snapshot(context)
                        val uris = tracks.filter { it.id in favs }
                            .map { Uri.parse(it.contentUri) }
                        if (uris.isEmpty()) {
                            busy = false
                            status = "No favorites to export"
                            return@launch
                        }
                        if (Build.VERSION.SDK_INT >= 29) {
                            val pi = MediaStore.createWriteRequest(
                                context.contentResolver, uris
                            )
                            busy = false
                            exportLauncher.launch(
                                IntentSenderRequest.Builder(pi.intentSender).build()
                            )
                        } else {
                            var ok = 0; var fail = 0
                            for (u in uris) {
                                val s = TagFavorites.write(context, u, true)
                                if (s) ok++ else fail++
                            }
                            busy = false
                            status = "Exported $ok favorites to tags" +
                                if (fail > 0) " ($fail failed)" else ""
                        }
                    }
                },
            ) { Text(if (busy) "Working..." else "Export favorites to tags") }

            Spacer(Modifier.height(8.dp))

            Button(
                enabled = !busy,
                onClick = {
                    scope.launch {
                        busy = true
                        status = "Scanning tags..."
                        var found = 0
                        withContext(Dispatchers.IO) {
                            for (t in tracks) {
                                val isFav = TagFavorites.read(context, Uri.parse(t.contentUri))
                                if (isFav) {
                                    FavoritesPrefs.set(context, t.id, true)
                                    found++
                                }
                            }
                        }
                        busy = false
                        status = "Imported $found favorites from tags"
                    }
                },
            ) { Text("Import favorites from tags") }

            Spacer(Modifier.height(12.dp))
            if (status.isNotEmpty()) {
                Text(status, style = MaterialTheme.typography.bodyMedium)
            }

            HorizontalDivider(Modifier.padding(vertical = 16.dp))

            Text(
                "Note: tag write copies each file to a temp cache, modifies the tag, " +
                "then copies back. For 592 tracks this may take a few minutes. " +
                "Formats supported: MP3, FLAC, OGG, M4A (WAV is read-only).",
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}
