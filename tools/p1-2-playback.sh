#!/data/data/com.termux/files/usr/bin/bash
set -euo pipefail
cd ~/mariaplayer

mkdir -p .bak/p1-2-playback
for f in app/build.gradle.kts app/src/main/AndroidManifest.xml \
         app/src/main/java/ir/mariaplayer/MainActivity.kt; do
  [ -f "$f" ] && cp "$f" ".bak/p1-2-playback/$(basename "$f")"
done

python3 - << 'PY'
import pathlib
p = pathlib.Path("app/build.gradle.kts")
s = p.read_text()
add = []
if "androidx.media3:media3-exoplayer" not in s:
    add.append('    implementation("androidx.media3:media3-exoplayer:1.5.1")')
if "androidx.media3:media3-session" not in s:
    add.append('    implementation("androidx.media3:media3-session:1.5.1")')
if add:
    s = s.replace(
        '    implementation("androidx.core:core-ktx:1.13.1")\n',
        '    implementation("androidx.core:core-ktx:1.13.1")\n' + "\n".join(add) + "\n",
        1
    )
    p.write_text(s)
    print("deps added")
PY

python3 - << 'PY'
import pathlib
p = pathlib.Path("app/src/main/AndroidManifest.xml")
s = p.read_text()
if "PlaybackService" not in s:
    service = '''        <service
            android:name=".playback.PlaybackService"
            android:exported="true"
            android:foregroundServiceType="mediaPlayback">
            <intent-filter>
                <action android:name="androidx.media3.session.MediaSessionService" />
                <action android:name="android.media.browse.MediaBrowserService" />
            </intent-filter>
        </service>
'''
    s = s.replace('    </application>', service + '    </application>')
    p.write_text(s)
    print("manifest updated")
PY

mkdir -p app/src/main/java/ir/mariaplayer/playback

cat > app/src/main/java/ir/mariaplayer/playback/PlaybackService.kt << 'EOF'
package ir.mariaplayer.playback

import android.app.PendingIntent
import android.content.Intent
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import ir.mariaplayer.MainActivity

class PlaybackService : MediaSessionService() {

    private var mediaSession: MediaSession? = null

    override fun onCreate() {
        super.onCreate()

        val attrs = AudioAttributes.Builder()
            .setUsage(C.USAGE_MEDIA)
            .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
            .build()

        val player = ExoPlayer.Builder(this)
            .setAudioAttributes(attrs, /* handleAudioFocus = */ true)
            .setHandleAudioBecomingNoisy(true)
            .build()

        val sessionActivity = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        mediaSession = MediaSession.Builder(this, player)
            .setSessionActivity(sessionActivity)
            .build()
    }

    override fun onGetSession(
        controllerInfo: MediaSession.ControllerInfo
    ): MediaSession? = mediaSession

    override fun onDestroy() {
        mediaSession?.run {
            player.release()
            release()
        }
        mediaSession = null
        super.onDestroy()
    }
}
EOF

cat > app/src/main/java/ir/mariaplayer/playback/PlayerConnection.kt << 'EOF'
package ir.mariaplayer.playback

import android.content.ComponentName
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.google.common.util.concurrent.MoreExecutors

@Composable
fun rememberPlayerController(): MediaController? {
    val ctx = LocalContext.current
    var controller by remember { mutableStateOf<MediaController?>(null) }

    LaunchedEffect(Unit) {
        val token = SessionToken(
            ctx,
            ComponentName(ctx, PlaybackService::class.java),
        )
        val future = MediaController.Builder(ctx, token).buildAsync()
        future.addListener(
            { controller = try { future.get() } catch (_: Throwable) { null } },
            MoreExecutors.directExecutor(),
        )
    }

    DisposableEffect(Unit) {
        onDispose { controller?.release() }
    }

    return controller
}
EOF

cat > app/src/main/java/ir/mariaplayer/MainActivity.kt << 'EOF'
package ir.mariaplayer

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
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
import ir.mariaplayer.data.Track
import ir.mariaplayer.playback.rememberPlayerController
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

    val controller = rememberPlayerController()

    LibraryScreen(
        tracks = tracks,
        scanning = tracks == null,
        onTrackClick = { track -> play(controller, track) },
    )
}

private fun play(controller: MediaController?, track: Track) {
    if (controller == null) return
    val item = MediaItem.Builder()
        .setMediaId(track.id.toString())
        .setUri(track.contentUri)
        .setMediaMetadata(
            MediaMetadata.Builder()
                .setTitle(track.title)
                .setArtist(track.artist)
                .setAlbumTitle(track.album)
                .build()
        )
        .build()
    controller.setMediaItem(item)
    controller.prepare()
    controller.play()
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun LibraryScreen(
    tracks: List<Track>?,
    scanning: Boolean,
    onTrackClick: (Track) -> Unit,
) {
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
git commit -qm "feat(P1): Media3 PlaybackService + tap-to-play"

if ! git push origin main; then
  git pull --rebase origin main
  git push origin main
fi
echo "pushed"
