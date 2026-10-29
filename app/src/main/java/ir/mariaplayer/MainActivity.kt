package ir.mariaplayer

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import ir.mariaplayer.data.LibraryScanner
import ir.mariaplayer.data.Track
import ir.mariaplayer.ui.MariaPlayerTheme
import ir.mariaplayer.ui.PermissionScreen
import java.util.Locale

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            MariaPlayerTheme { AppRoot() }
        }
    }
}

@Composable
private fun AppRoot() {
    val context = LocalContext.current
    var hasPermission by remember { mutableStateOf(LibraryScanner.hasAudioPermission(context)) }

    if (!hasPermission) {
        PermissionScreen(onGranted = { hasPermission = true })
        return
    }

    val tracks by produceState<List<Track>?>(initialValue = null, hasPermission) {
        value = LibraryScanner.scan(context)
    }

    LibraryScreen(tracks = tracks, scanning = tracks == null)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun LibraryScreen(tracks: List<Track>?, scanning: Boolean) {
    Scaffold(topBar = { TopAppBar(title = { Text("MariaPlayer") }) }) { inner ->
        Box(Modifier.fillMaxSize().padding(inner)) {
            when {
                scanning -> CenterText("Scanning library...")
                tracks.isNullOrEmpty() -> CenterText(
                    "No audio files found.\nAdd music to /sdcard/Music, then reopen the app."
                )
                else -> LazyColumn(Modifier.fillMaxSize()) {
                    items(tracks, key = { it.id }) { t ->
                        ListItem(
                            headlineContent = { Text(t.title) },
                            supportingContent = {
                                Text(
                                    listOfNotNull(
                                        t.artist, t.album, formatDuration(t.durationMs)
                                    ).joinToString(" - ")
                                )
                            },
                        )
                        HorizontalDivider()
                    }
                }
            }
        }
    }
}

@Composable
private fun CenterText(text: String) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text(text, style = MaterialTheme.typography.bodyLarge)
    }
}

private fun formatDuration(ms: Long): String {
    val totalSec = (ms / 1000).coerceAtLeast(0)
    return String.format(Locale.US, "%d:%02d", totalSec / 60, totalSec % 60)
}
