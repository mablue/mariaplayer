#!/data/data/com.termux/files/usr/bin/bash
set -euo pipefail
cd ~/mariaplayer
git pull --rebase origin main >/dev/null 2>&1 || true

mkdir -p .bak/delete-simplify-ux
cp app/src/main/java/ir/mariaplayer/DeleteActivity.kt     .bak/delete-simplify-ux/ 2>/dev/null || true
cp app/src/main/java/ir/mariaplayer/MainActivity.kt       .bak/delete-simplify-ux/ 2>/dev/null || true
cp app/src/main/java/ir/mariaplayer/playback/PlaybackService.kt .bak/delete-simplify-ux/ 2>/dev/null || true
cp app/src/main/AndroidManifest.xml                       .bak/delete-simplify-ux/ 2>/dev/null || true
cp LESSONS.md                                             .bak/delete-simplify-ux/ 2>/dev/null || true

# ── 1. DeleteActivity: kill its AlertDialog, go straight to system ──
cat > app/src/main/java/ir/mariaplayer/DeleteActivity.kt << 'KOTEOF'
package ir.mariaplayer

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import ir.mariaplayer.playback.PlaybackService

class DeleteActivity : ComponentActivity() {

    private var pendingUri: Uri? = null
    private var pendingTitle: String = ""

    private val deleteLauncher = registerForActivityResult(
        ActivityResultContracts.StartIntentSenderForResult()
    ) { result ->
        val uri = pendingUri
        val title = pendingTitle
        if (result.resultCode == Activity.RESULT_OK && uri != null) {
            Toast.makeText(this, "Deleted: $title", Toast.LENGTH_SHORT).show()
            notifyDeleted(uri)
        }
        finish()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val uriStr = intent.getStringExtra(EXTRA_URI)
        val title = intent.getStringExtra(EXTRA_TITLE).orEmpty()
        if (uriStr == null) { finish(); return }

        val uri = Uri.parse(uriStr)
        pendingUri = uri
        pendingTitle = title

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            try {
                val pi = MediaStore.createDeleteRequest(contentResolver, listOf(uri))
                deleteLauncher.launch(IntentSenderRequest.Builder(pi.intentSender).build())
            } catch (_: Exception) {
                tryDirectDelete(uri, title)
            }
        } else {
            tryDirectDelete(uri, title)
        }
    }

    private fun tryDirectDelete(uri: Uri, title: String) {
        try {
            val rows = contentResolver.delete(uri, null, null)
            if (rows > 0) {
                Toast.makeText(this, "Deleted: $title", Toast.LENGTH_SHORT).show()
                notifyDeleted(uri)
            }
        } catch (_: Exception) { }
        finish()
    }

    private fun notifyDeleted(uri: Uri) {
        sendBroadcast(
            Intent(PlaybackService.ACTION_TRACK_DELETED)
                .setPackage(packageName)
                .putExtra(PlaybackService.EXTRA_DELETED_URI, uri.toString())
        )
    }

    companion object {
        const val EXTRA_URI = "delete_uri"
        const val EXTRA_TITLE = "delete_title"
    }
}
KOTEOF

# ── 2. Manifest: DeleteActivity translucent, no dialog window ──
python3 - << 'PY'
import pathlib, re
p = pathlib.Path("app/src/main/AndroidManifest.xml")
s = p.read_text()
pat = re.compile(
    r'<activity\s+[^>]*?android:name="\.DeleteActivity"[^>]*?/>',
    re.S,
)
new = '''<activity
            android:name=".DeleteActivity"
            android:exported="false"
            android:excludeFromRecents="true"
            android:theme="@android:style/Theme.Translucent.NoTitleBar" />'''
s2, n = pat.subn(new, s)
if n == 0:
    print("FAIL: DeleteActivity activity block not found")
    raise SystemExit(1)
p.write_text(s2)
print("manifest: DeleteActivity -> Theme.Translucent.NoTitleBar")
PY

# ── 3. MainActivity: direct system delete from library + rescan on broadcast ──
python3 - << 'PY'
import pathlib, sys
p = pathlib.Path("app/src/main/java/ir/mariaplayer/MainActivity.kt")
s = p.read_text()

# 3a: imports
if "import android.content.BroadcastReceiver" not in s:
    s = s.replace(
        "import android.Manifest\n",
        "import android.Manifest\n"
        "import android.content.BroadcastReceiver\n"
        "import android.content.Context\n"
        "import android.content.IntentFilter\n"
        "import android.net.Uri\n"
        "import android.provider.MediaStore\n"
        "import android.widget.Toast\n",
        1)
if "import androidx.activity.result.IntentSenderRequest" not in s:
    s = s.replace(
        "import androidx.activity.result.contract.ActivityResultContracts\n",
        "import androidx.activity.result.IntentSenderRequest\n"
        "import androidx.activity.result.contract.ActivityResultContracts\n",
        1)
if "import androidx.compose.runtime.DisposableEffect" not in s:
    s = s.replace(
        "import androidx.compose.runtime.LaunchedEffect\n",
        "import androidx.compose.runtime.DisposableEffect\n"
        "import androidx.compose.runtime.LaunchedEffect\n"
        "import androidx.compose.runtime.mutableIntStateOf\n",
        1)

# 3b: insert delete launcher + broadcast receiver before produceState(tracks)
old_a = """    val tracks by produceState<List<Track>?>(initialValue = null, hasAudio) {
        value = LibraryScanner.scan(context)
    }"""
new_a = """    var reloadKey by remember { mutableIntStateOf(0) }
    var pendingDeleteUri by remember { mutableStateOf<Uri?>(null) }
    var pendingDeleteTitle by remember { mutableStateOf("") }

    val deleteLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartIntentSenderForResult()
    ) { result ->
        val uri = pendingDeleteUri
        val title = pendingDeleteTitle
        pendingDeleteUri = null
        if (result.resultCode == android.app.Activity.RESULT_OK && uri != null) {
            Toast.makeText(context, "Deleted: $title", Toast.LENGTH_SHORT).show()
            context.sendBroadcast(
                Intent(ir.mariaplayer.playback.PlaybackService.ACTION_TRACK_DELETED)
                    .setPackage(context.packageName)
                    .putExtra(ir.mariaplayer.playback.PlaybackService.EXTRA_DELETED_URI, uri.toString())
            )
        }
    }

    DisposableEffect(Unit) {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(c: Context?, i: Intent?) {
                if (i?.action == ir.mariaplayer.playback.PlaybackService.ACTION_TRACK_DELETED) {
                    reloadKey++
                }
            }
        }
        val filter = IntentFilter(ir.mariaplayer.playback.PlaybackService.ACTION_TRACK_DELETED)
        if (Build.VERSION.SDK_INT >= 33) {
            context.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("UnspecifiedRegisterReceiverFlag")
            context.registerReceiver(receiver, filter)
        }
        onDispose { try { context.unregisterReceiver(receiver) } catch (_: Exception) {} }
    }

    val tracks by produceState<List<Track>?>(initialValue = null, hasAudio, reloadKey) {
        value = LibraryScanner.scan(context)
    }"""
if old_a not in s:
    print("FAIL: produceState(tracks) anchor not found")
    sys.exit(1)
s = s.replace(old_a, new_a, 1)

# 3c: replace onDeleteRequest block
old_b = """        onDeleteRequest = { t ->
            context.startActivity(
                Intent(context, DeleteActivity::class.java).apply {
                    putExtra(DeleteActivity.EXTRA_URI, t.contentUri)
                    putExtra(DeleteActivity.EXTRA_TITLE, t.title)
                }
            )
        },"""
new_b = """        onDeleteRequest = { t ->
            val uri = Uri.parse(t.contentUri)
            if (Build.VERSION.SDK_INT >= 29) {
                try {
                    val pi = MediaStore.createDeleteRequest(context.contentResolver, listOf(uri))
                    pendingDeleteUri = uri
                    pendingDeleteTitle = t.title
                    deleteLauncher.launch(IntentSenderRequest.Builder(pi.intentSender).build())
                } catch (_: Exception) {
                    try {
                        val rows = context.contentResolver.delete(uri, null, null)
                        if (rows > 0) {
                            Toast.makeText(context, "Deleted: " + t.title, Toast.LENGTH_SHORT).show()
                            context.sendBroadcast(
                                Intent(ir.mariaplayer.playback.PlaybackService.ACTION_TRACK_DELETED)
                                    .setPackage(context.packageName)
                                    .putExtra(ir.mariaplayer.playback.PlaybackService.EXTRA_DELETED_URI, t.contentUri)
                            )
                        }
                    } catch (e2: Exception) {
                        Toast.makeText(context, "Delete failed: " + e2.message, Toast.LENGTH_LONG).show()
                    }
                }
            } else {
                try {
                    val rows = context.contentResolver.delete(uri, null, null)
                    if (rows > 0) {
                        Toast.makeText(context, "Deleted: " + t.title, Toast.LENGTH_SHORT).show()
                        context.sendBroadcast(
                            Intent(ir.mariaplayer.playback.PlaybackService.ACTION_TRACK_DELETED)
                                .setPackage(context.packageName)
                                .putExtra(ir.mariaplayer.playback.PlaybackService.EXTRA_DELETED_URI, t.contentUri)
                        )
                    }
                } catch (e: Exception) {
                    Toast.makeText(context, "Delete failed: " + e.message, Toast.LENGTH_LONG).show()
                }
            }
        },"""
if old_b not in s:
    print("FAIL: onDeleteRequest block not found")
    sys.exit(1)
s = s.replace(old_b, new_b, 1)
p.write_text(s)
print("MainActivity: delete launcher + broadcast rescan + queue-safe delete")
PY

# ── 4. PlaybackService: try direct startActivity, queue cleanup on broadcast ──
python3 - << 'PY'
import pathlib, sys
p = pathlib.Path("app/src/main/java/ir/mariaplayer/playback/PlaybackService.kt")
s = p.read_text()

old_a = '''    private fun requestDelete(uri: Uri, title: String) {
        // A MediaSessionService cannot reliably start an Activity on Android 10+.
        // Post a heads-up notification whose action PendingIntent is granted BAL.
        postDeleteConfirmNotification(uri, title)
    }'''
new_a = '''    private fun requestDelete(uri: Uri, title: String) {
        val intent = Intent(this, DeleteActivity::class.java).apply {
            putExtra(DeleteActivity.EXTRA_URI, uri.toString())
            putExtra(DeleteActivity.EXTRA_TITLE, title)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        }
        try {
            startActivity(intent)
        } catch (_: Exception) {
            postDeleteConfirmNotification(uri, title)
        }
    }'''
if old_a not in s:
    print("FAIL: requestDelete body not found")
    sys.exit(1)
s = s.replace(old_a, new_a, 1)

old_b = '''    private val deleteReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val deletedUri = intent?.getStringExtra(EXTRA_DELETED_URI) ?: return
            val player = mediaSession?.player ?: return
            val current = player.currentMediaItem ?: return
            if (current.localConfiguration?.uri.toString() == deletedUri) {
                player.seekToNextMediaItem()
            }
        }
    }'''
new_b = '''    private val deleteReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val deletedUri = intent?.getStringExtra(EXTRA_DELETED_URI) ?: return
            val player = mediaSession?.player ?: return
            val toRemove = ArrayList<Int>()
            for (i in 0 until player.mediaItemCount) {
                if (player.getMediaItemAt(i).localConfiguration?.uri?.toString() == deletedUri) {
                    toRemove.add(i)
                }
            }
            for (i in toRemove.sortedDescending()) {
                try { player.removeMediaItem(i) } catch (_: Exception) {}
            }
        }
    }'''
if old_b not in s:
    print("FAIL: deleteReceiver body not found")
    sys.exit(1)
s = s.replace(old_b, new_b, 1)
p.write_text(s)
print("PlaybackService: startActivity->notification fallback + queue cleanup on delete")
PY

# ── 5. LESSONS.md append ──
cat >> LESSONS.md << 'EOF'

## 2026-10-30 ux — delete flow had 3 stacked confirmations
- tried: DeleteActivity AlertDialog + MediaStore.createDeleteRequest + notification fallback
- why-failed: from library: 2 dialogs. from notification: 4 user actions
  (tap delete in media notif -> heads-up notif -> pull down -> tap Delete ->
   activity dialog -> system dialog -> Allow). Unusable.
  Also list stayed stale after delete (produceState(tracks) never re-ran)
  and player queue kept the deleted MediaItem.
- learned: system createDeleteRequest dialog IS already a confirmation.
  Any custom AlertDialog before it is redundant and hostile.
  Fix: kill DeleteActivity's UI (Theme.Translucent.NoTitleBar), go straight
  to launcher. From MainActivity use launcher directly (no DeleteActivity hop).
  For rescan: broadcast ACTION_TRACK_DELETED from delete success; MainActivity
  receiver bumps reloadKey -> produceState(tracks) re-runs.
  For queue: removeMediaItem on matching URIs; ExoPlayer auto-advances on
  current-item removal. Never leave dangling MediaItems pointing at deleted URIs.
- fixed-by: this commit
EOF

git add -A
git commit -qm "fix(ux): collapse delete confirmations; rescan library + clean queue after delete"
if ! git push origin main; then git pull --rebase origin main; git push origin main; fi
echo "pushed"
