#!/data/data/com.termux/files/usr/bin/bash
set -euo pipefail
cd ~/mariaplayer

# --- backup existing
mkdir -p .bak/p1-library
if [ -f app/src/main/AndroidManifest.xml ]; then
  cp app/src/main/AndroidManifest.xml .bak/p1-library/
fi
if [ -f app/build.gradle.kts ]; then
  cp app/build.gradle.kts .bak/p1-library/
fi
if [ -f app/src/main/java/ir/mariaplayer/MainActivity.kt ]; then
  cp app/src/main/java/ir/mariaplayer/MainActivity.kt .bak/p1-library/
fi

grep -qxF '.bak/' .gitignore 2>/dev/null || echo '.bak/' >> .gitignore

# --- AndroidManifest
cat > app/src/main/AndroidManifest.xml << 'EOF'
<?xml version="1.0" encoding="utf-8"?>
<manifest xmlns:android="http://schemas.android.com/apk/res/android">

    <uses-permission android:name="android.permission.READ_MEDIA_AUDIO" />
    <uses-permission
        android:name="android.permission.READ_EXTERNAL_STORAGE"
        android:maxSdkVersion="32" />
    <uses-permission android:name="android.permission.POST_NOTIFICATIONS" />
    <uses-permission android:name="android.permission.FOREGROUND_SERVICE" />
    <uses-permission android:name="android.permission.FOREGROUND_SERVICE_MEDIA_PLAYBACK" />
    <uses-permission android:name="android.permission.WAKE_LOCK" />

    <application
        android:label="MariaPlayer"
        android:theme="@android:style/Theme.Material.NoActionBar"
        android:allowBackup="false"
        android:supportsRtl="true">
        <activity android:name=".MainActivity" android:exported="true">
            <intent-filter>
                <action android:name="android.intent.action.MAIN" />
                <category android:name="android.intent.category.LAUNCHER" />
            </intent-filter>
        </activity>
    </application>
</manifest>
EOF

# --- Theme
mkdir -p app/src/main/java/ir/mariaplayer/ui
cat > app/src/main/java/ir/mariaplayer/ui/Theme.kt << 'EOF'
package ir.mariaplayer.ui

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext

@Composable
fun MariaPlayerTheme(content: @Composable () -> Unit) {
    val dark = isSystemInDarkTheme()
    val ctx = LocalContext.current
    val scheme = when {
        Build.VERSION.SDK_INT >= 31 && dark -> dynamicDarkColorScheme(ctx)
        Build.VERSION.SDK_INT >= 31          -> dynamicLightColorScheme(ctx)
        dark                                 -> darkColorScheme()
        else                                 -> lightColorScheme()
    }
    MaterialTheme(colorScheme = scheme, content = content)
}
EOF

# --- Track model
mkdir -p app/src/main/java/ir/mariaplayer/data
cat > app/src/main/java/ir/mariaplayer/data/Track.kt << 'EOF'
package ir.mariaplayer.data

data class Track(
    val id: Long,
    val title: String,
    val artist: String?,
    val album: String?,
    val durationMs: Long,
    val contentUri: String,
)
EOF

# --- Scanner
cat > app/src/main/java/ir/mariaplayer/data/LibraryScanner.kt << 'EOF'
package ir.mariaplayer.data

import android.Manifest
import android.content.ContentUris
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import androidx.core.content.ContextCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

object LibraryScanner {

    fun hasAudioPermission(context: Context): Boolean {
        val perm = requiredPermission()
        return ContextCompat.checkSelfPermission(context, perm) ==
            PackageManager.PERMISSION_GRANTED
    }

    fun requiredPermission(): String =
        if (Build.VERSION.SDK_INT >= 33)
            Manifest.permission.READ_MEDIA_AUDIO
        else
            Manifest.permission.READ_EXTERNAL_STORAGE

    suspend fun scan(context: Context): List<Track> = withContext(Dispatchers.IO) {
        val collection: Uri = if (Build.VERSION.SDK_INT >= 29)
            MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL)
        else
            MediaStore.Audio.Media.EXTERNAL_CONTENT_URI

        val projection = arrayOf(
            MediaStore.Audio.Media._ID,
            MediaStore.Audio.Media.TITLE,
            MediaStore.Audio.Media.ARTIST,
            MediaStore.Audio.Media.ALBUM,
            MediaStore.Audio.Media.DURATION,
        )
        val selection = "${MediaStore.Audio.Media.IS_MUSIC} != 0"
        val sort = "${MediaStore.Audio.Media.TITLE} COLLATE NOCASE ASC"

        val out = ArrayList<Track>()
        context.contentResolver.query(collection, projection, selection, null, sort)?.use { c ->
            val idCol = c.getColumnIndexOrThrow(MediaStore.Audio.Media._ID)
            val titleCol = c.getColumnIndexOrThrow(MediaStore.Audio.Media.TITLE)
            val artistCol = c.getColumnIndexOrThrow(MediaStore.Audio.Media.ARTIST)
            val albumCol = c.getColumnIndexOrThrow(MediaStore.Audio.Media.ALBUM)
            val durCol = c.getColumnIndexOrThrow(MediaStore.Audio.Media.DURATION)
            while (c.moveToNext()) {
                val id = c.getLong(idCol)
                val uri = ContentUris.withAppendedId(collection, id)
                out += Track(
                    id = id,
                    title = c.getString(titleCol) ?: "Unknown title",
                    artist = c.getString(artistCol)?.takeUnless { it.isBlank() || it == "<unknown>" },
                    album = c.getString(albumCol)?.takeUnless { it.isBlank() || it == "<unknown>" },
                    durationMs = c.getLong(durCol),
                    contentUri = uri.toString(),
                )
            }
        }
        out
    }
}
EOF

# --- Permission screen
cat > app/src/main/java/ir/mariaplayer/ui/PermissionScreen.kt << 'EOF'
package ir.mariaplayer.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import ir.mariaplayer.data.LibraryScanner

@Composable
fun PermissionScreen(onGranted: () -> Unit) {
    val context = LocalContext.current
    val perm = remember { LibraryScanner.requiredPermission() }
    var denied by remember { mutableStateOf(false) }

    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted -> if (granted) onGranted() else denied = true }

    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            "MariaPlayer needs access to your music",
            style = MaterialTheme.typography.headlineSmall,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(12.dp))
        Text(
            "It only reads audio. Nothing is uploaded anywhere.",
            style = MaterialTheme.typography.bodyMedium,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(24.dp))
        Button(onClick = { launcher.launch(perm) }) { Text("Allow access") }
        if (denied) {
            Spacer(Modifier.height(12.dp))
            Text(
                "Permission denied. Open Settings - Apps - MariaPlayer - Permissions.",
                style = MaterialTheme.typography.bodySmall,
                textAlign = TextAlign.Center,
            )
        }
    }
}
EOF

# --- MainActivity
cat > app/src/main/java/ir/mariaplayer/MainActivity.kt << 'EOF'
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
EOF

# --- add foundation dep
python3 - << 'PY'
import pathlib
p = pathlib.Path("app/build.gradle.kts")
s = p.read_text()
if "compose.foundation:foundation" not in s:
    s = s.replace(
        '    implementation("androidx.compose.ui:ui")\n',
        '    implementation("androidx.compose.ui:ui")\n'
        '    implementation("androidx.compose.foundation:foundation")\n',
        1
    )
    p.write_text(s)
    print("added compose foundation dep")
else:
    print("foundation dep already present")
PY

# --- commit + push
git add -A
git commit -qm "feat(P1): permission gate + MediaStore scanner + library list"

if ! git push origin main; then
  git pull --rebase origin main
  git push origin main
fi

echo
echo "OK pushed. Watch:"
echo "  https://github.com/$(gh api user -q .login)/mariaplayer/actions"
