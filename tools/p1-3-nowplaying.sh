#!/data/data/com.termux/files/usr/bin/bash
set -euo pipefail
cd ~/mariaplayer

mkdir -p .bak/p1-3
for f in app/build.gradle.kts \
         app/src/main/java/ir/mariaplayer/MainActivity.kt; do
  [ -f "$f" ] && cp "$f" ".bak/p1-3/$(basename "$f")"
done

python3 - << 'PY'
import pathlib
p = pathlib.Path("app/build.gradle.kts")
s = p.read_text()
add = []
if "androidx.datastore:datastore-preferences" not in s:
    add.append('    implementation("androidx.datastore:datastore-preferences:1.1.1")')
if "androidx.lifecycle:lifecycle-runtime-compose" not in s:
    add.append('    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")')
if add:
    s = s.replace(
        '    implementation("androidx.core:core-ktx:1.13.1")\n',
        '    implementation("androidx.core:core-ktx:1.13.1")\n' + "\n".join(add) + "\n",
        1
    )
    p.write_text(s)
    print("deps added")
PY

mkdir -p app/src/main/java/ir/mariaplayer/data

cat > app/src/main/java/ir/mariaplayer/data/PlaybackPrefs.kt << 'EOF'
package ir.mariaplayer.data

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private val Context.dataStore by preferencesDataStore(name = "playback")

data class ResumeState(val trackId: Long?, val positionMs: Long)

object PlaybackPrefs {
    private val KEY_TRACK = longPreferencesKey("last_track_id")
    private val KEY_POS   = longPreferencesKey("last_position_ms")

    fun observe(context: Context): Flow<ResumeState> =
        context.dataStore.data.map { p ->
            ResumeState(p[KEY_TRACK], p[KEY_POS] ?: 0L)
        }

    suspend fun read(context: Context): ResumeState =
        observe(context).first()

    suspend fun save(context: Context, trackId: Long, positionMs: Long) {
        context.dataStore.edit { p ->
            p[KEY_TRACK] = trackId
            p[KEY_POS] = positionMs
        }
    }
}
EOF

cat > app/src/main/java/ir/mariaplayer/playback/NowPlayingBar.kt << 'EOF'
package ir.mariaplayer.playback

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.media3.common.Player
import androidx.media3.session.MediaController

@Composable
fun NowPlayingBar(controller: MediaController?) {
    if (controller == null) return
    var playing by remember { mutableStateOf(controller.isPlaying) }
    var title by remember { mutableStateOf(controller.mediaMetadata.title?.toString() ?: "") }
    var artist by remember { mutableStateOf(controller.mediaMetadata.artist?.toString().orEmpty()) }

    androidx.compose.runtime.DisposableEffect(controller) {
        val listener = object : Player.Listener {
            override fun onIsPlayingChanged(isPlaying: Boolean) { playing = isPlaying }
            override fun onMediaMetadataChanged(mediaMetadata: androidx.media3.common.MediaMetadata) {
                title = mediaMetadata.title?.toString() ?: ""
                artist = mediaMetadata.artist?.toString().orEmpty()
            }
        }
        controller.addListener(listener)
        onDispose { controller.removeListener(listener) }
    }

    if (title.isEmpty()) return

    Surface(tonalElevation = 3.dp) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.titleSmall)
                if (artist.isNotEmpty()) {
                    Text(artist, maxLines = 1, overflow = TextOverflow.Ellipsis,
                        style = MaterialTheme.typography.bodySmall)
                }
            }
            IconButton(onClick = { if (playing) controller.pause() else controller.play() }) {
                Icon(
                    imageVector = if (playing) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                    contentDescription = if (playing) "Pause" else "Play",
                )
            }
        }
    }
}
EOF

cat > app/src/main/java/ir/mariaplayer/MainActivity.kt << 'EOF'
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
EOF

git add -A
git commit -qm "feat(P1): now-playing bar + resume last track + POST_NOTIFICATIONS"

if ! git push origin main; then
  git pull --rebase origin main
  git push origin main
fi
echo "pushed"
