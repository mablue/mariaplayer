#!/data/data/com.termux/files/usr/bin/bash
set -euo pipefail
cd ~/mariaplayer

mkdir -p .bak/p1-4
cp app/src/main/java/ir/mariaplayer/MainActivity.kt .bak/p1-4/ 2>/dev/null || true
cp app/src/main/java/ir/mariaplayer/playback/NowPlayingBar.kt .bak/p1-4/ 2>/dev/null || true

cat > app/src/main/java/ir/mariaplayer/playback/NowPlayingBar.kt << 'KOTEOF'
package ir.mariaplayer.playback

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.session.MediaController

@Composable
fun NowPlayingBar(controller: MediaController?) {
    if (controller == null) return
    var playing by remember { mutableStateOf(controller.isPlaying) }
    var title by remember { mutableStateOf(controller.mediaMetadata.title?.toString() ?: "") }
    var artist by remember { mutableStateOf(controller.mediaMetadata.artist?.toString().orEmpty()) }
    var hasPrev by remember { mutableStateOf(controller.hasPreviousMediaItem()) }
    var hasNext by remember { mutableStateOf(controller.hasNextMediaItem()) }

    DisposableEffect(controller) {
        val listener = object : Player.Listener {
            override fun onIsPlayingChanged(isPlaying: Boolean) { playing = isPlaying }
            override fun onMediaMetadataChanged(mediaMetadata: MediaMetadata) {
                title = mediaMetadata.title?.toString() ?: ""
                artist = mediaMetadata.artist?.toString().orEmpty()
            }
            override fun onMediaItemTransition(mediaItem: androidx.media3.common.MediaItem?, reason: Int) {
                hasPrev = controller.hasPreviousMediaItem()
                hasNext = controller.hasNextMediaItem()
            }
            override fun onTimelineChanged(timeline: androidx.media3.common.Timeline, reason: Int) {
                hasPrev = controller.hasPreviousMediaItem()
                hasNext = controller.hasNextMediaItem()
            }
        }
        controller.addListener(listener)
        onDispose { controller.removeListener(listener) }
    }

    if (title.isEmpty()) return

    Surface(tonalElevation = 3.dp) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TextButton(
                onClick = { controller.seekToPreviousMediaItem() },
                enabled = hasPrev,
            ) { Text("Prev") }

            TextButton(onClick = { if (playing) controller.pause() else controller.play() }) {
                Text(if (playing) "Pause" else "Play")
            }

            TextButton(
                onClick = { controller.seekToNextMediaItem() },
                enabled = hasNext,
            ) { Text("Next") }

            Column(Modifier.weight(1f).padding(start = 8.dp)) {
                Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.titleSmall)
                if (artist.isNotEmpty()) {
                    Text(artist, maxLines = 1, overflow = TextOverflow.Ellipsis,
                        style = MaterialTheme.typography.bodySmall)
                }
            }
        }
    }
}
KOTEOF

cat > app/src/main/java/ir/mariaplayer/MainActivity.kt << 'KOTEOF'
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
import androidx.compose.material3.TextButton
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
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import ir.mariaplayer.data.LibraryScanner
import ir.mariaplayer.data.PlaybackPrefs
import ir.mariaplayer.data.Track
import ir.mariaplayer.playback.NowPlayingBar
import ir.mariaplayer.playback.rememberPlayerController
import ir.mariaplayer.ui.MariaPlayerTheme
import ir.mariaplayer.ui.PermissionScreen
import kotlinx.coroutines.delay
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

    if (Build.VERSION.SDK_INT >= 33) {
        val launcher = rememberLauncherForActivityResult(
            ActivityResultContracts.RequestPermission()
        ) { }
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
    var shuffleOn by remember { mutableStateOf(controller?.shuffleModeEnabled ?: false) }

    // Resume last queue+position on cold start
    LaunchedEffect(controller, tracks) {
        val c = controller ?: return@LaunchedEffect
        val list = tracks ?: return@LaunchedEffect
        if (c.mediaItemCount > 0) return@LaunchedEffect
        if (list.isEmpty()) return@LaunchedEffect
        val saved = PlaybackPrefs.read(context)
        val idx = saved.trackId?.let { id -> list.indexOfFirst { it.id == id } } ?: -1
        if (idx < 0) return@LaunchedEffect
        playQueue(c, list, idx, saved.positionMs, autoPlay = false)
        shuffleOn = c.shuffleModeEnabled
    }

    // Save position every 3 s while playing (P1-3 fix: nothing saved before)
    LaunchedEffect(controller) {
        val c = controller ?: return@LaunchedEffect
        while (true) {
            delay(3000)
            val mi = c.currentMediaItem ?: continue
            val tid = mi.mediaId.toLongOrNull() ?: continue
            PlaybackPrefs.save(context, tid, c.currentPosition)
        }
    }

    LibraryScreen(
        tracks = tracks,
        scanning = tracks == null,
        controller = controller,
        shuffleOn = shuffleOn,
        onShuffleClick = {
            val c = controller ?: return@LibraryScreen
            val list = tracks ?: return@LibraryScreen
            if (c.mediaItemCount == 0 && list.isNotEmpty()) {
                playQueue(c, list, 0, 0L, autoPlay = true)
                c.shuffleModeEnabled = true
            } else {
                c.shuffleModeEnabled = !c.shuffleModeEnabled
            }
            shuffleOn = c.shuffleModeEnabled
        },
        onTrackClick = { t ->
            val c = controller ?: return@LibraryScreen
            val list = tracks ?: return@LibraryScreen
            val idx = list.indexOfFirst { it.id == t.id }
            if (idx >= 0) playQueue(c, list, idx, 0L, autoPlay = true)
        },
    )
}

private fun playQueue(
    c: MediaController?,
    tracks: List<Track>,
    startIndex: Int,
    startPositionMs: Long,
    autoPlay: Boolean,
) {
    if (c == null || tracks.isEmpty()) return
    if (startIndex !in tracks.indices) return
    val items = tracks.map { t ->
        MediaItem.Builder()
            .setMediaId(t.id.toString())
            .setUri(t.contentUri)
            .setMediaMetadata(
                MediaMetadata.Builder()
                    .setTitle(t.title).setArtist(t.artist).setAlbumTitle(t.album).build()
            )
            .build()
    }
    c.setMediaItems(items, startIndex, startPositionMs)
    c.repeatMode = Player.REPEAT_MODE_ALL
    c.prepare()
    if (autoPlay) c.play()
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun LibraryScreen(
    tracks: List<Track>?,
    scanning: Boolean,
    controller: MediaController?,
    shuffleOn: Boolean,
    onShuffleClick: () -> Unit,
    onTrackClick: (Track) -> Unit,
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("MariaPlayer") },
                actions = {
                    TextButton(onClick = onShuffleClick) {
                        Text(if (shuffleOn) "Shuffle ON" else "Shuffle")
                    }
                },
            )
        },
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
KOTEOF

git add -A
git commit -qm "feat(P1-4): queue + Prev/Next + Shuffle + save position (resume fix)"

if ! git push origin main; then
  git pull --rebase origin main
  git push origin main
fi
echo "pushed"
