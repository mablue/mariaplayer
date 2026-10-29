package ir.mariaplayer

import android.Manifest
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.session.MediaController
import ir.mariaplayer.data.LibraryScanner
import ir.mariaplayer.data.PlaybackPrefs
import ir.mariaplayer.data.Track
import ir.mariaplayer.playback.NowPlayingBar
import ir.mariaplayer.playback.rememberPlayerController
import ir.mariaplayer.ui.MariaPlayerTheme
import ir.mariaplayer.ui.PermissionScreen
import java.util.Locale

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent { MariaPlayerTheme { AppRoot() } }
    }
}

@Composable
private fun AppRoot() {
    val context = LocalContext.current

    // Ask for POST_NOTIFICATIONS once on API 33+
    if (Build.VERSION.SDK_INT >= 33) {
        val launcher = rememberLauncherForActivityResult(
            ActivityResultContracts.RequestPermission()
        ) { /* result ignored; notification will appear if granted */ }
        LaunchedEffect(Unit) { launcher.launch(Manifest.permission.POST_NOTIFICATIONS) }
    }

    var hasAudio by remember { mutableStateOf(LibraryScanner.hasAudioPermission(context)) }
    if (!hasAudio) {
        PermissionScreen(onGranted = { hasAudio = true })
        return
    }

    val tracks by produceState<List<Track>?>(initialValue = null, hasAudio) {
        value = LibraryScanner.scan(context)
    }
    val controller = rememberPlayerController()

    // Resume last track once both tracks + controller exist
    LaunchedEffect(controller, tracks) {
        val c = controller ?: return@LaunchedEffect
        val list = tracks ?: return@LaunchedEffect
        if (c.currentMediaItem != null) return@LaunchedEffect  // already playing
        if (list.isEmpty()) return@LaunchedEffect
        val saved = PlaybackPrefs.read(context)
        val savedTrack = saved.trackId?.let { id -> list.firstOrNull { it.id == id } }
            ?: return@LaunchedEffect
        play(c, savedTrack, startPositionMs = saved.positionMs, autoPlay = false)
    }

    LibraryScreen(
        tracks = tracks,
        scanning = tracks == null,
        controller = controller,
        onTrackClick = { t -> play(controller, t, startPositionMs = 0L, autoPlay = true) },
    )
}

private fun play(c: MediaController?, t: Track, startPositionMs: Long, autoPlay: Boolean) {
    if (c == null) return
    val item = MediaItem.Builder()
        .setMediaId(t.id.toString())
        .setUri(t.contentUri)
        .setMediaMetadata(
            MediaMetadata.Builder()
                .setTitle(t.title).setArtist(t.artist).setAlbumTitle(t.album).build()
        )
        .build()
    c.setMediaItem(item, startPositionMs)
    c.prepare()
    if (autoPlay) c.play()
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun LibraryScreen(
    tracks: List<Track>?,
    scanning: Boolean,
    controller: MediaController?,
    onTrackClick: (Track) -> Unit,
) {
    Scaffold(
        topBar = { TopAppBar(title = { Text("MariaPlayer") }) },
        bottomBar = { NowPlayingBar(controller) },
    ) { inner ->
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
                                    listOfNotNull(t.artist, t.album, formatDuration(t.durationMs))
                                        .joinToString(" - ")
                                )
                            },
                            modifier = Modifier.clickable { onTrackClick(t) },
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
